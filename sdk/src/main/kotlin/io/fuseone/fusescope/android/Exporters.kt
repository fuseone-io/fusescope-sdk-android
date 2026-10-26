package io.fuseone.fusescope.android

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.Value
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.trace.data.DelegatingSpanData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter

/** Scrubs PII from span names and URL attributes before export, and drops upstream lifecycle spans unless kept. */
internal class RumSpanExporter(
    private val delegate: SpanExporter,
    private val policy: QueryPolicy,
    private val lifecycleSpans: Boolean = false,
) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        val kept = spans.filter { lifecycleSpans || it.instrumentationScopeInfo.name != LIFECYCLE_SCOPE || it.name == APP_START_SPAN }
        return if (kept.isEmpty()) CompletableResultCode.ofSuccess() else delegate.export(kept.map(::scrub))
    }

    private fun scrub(span: SpanData): SpanData {
        val attributes = scrubAttributes(span.attributes, policy)
        val name = scrubText(span.name, policy)
        if (attributes === span.attributes && name == span.name) return span
        return object : DelegatingSpanData(span) {
            override fun getName(): String = name

            override fun getAttributes(): Attributes = attributes
        }
    }

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode = delegate.shutdown()
}

/**
 * Drops records of unsampled sessions, maps upstream crash and ANR events to the `exception` event of the
 * FuseScope data model, copies the event name into `event.name` (storage drops the record's own field)
 * and scrubs PII.
 */
internal class RumLogExporter(
    private val delegate: LogRecordExporter,
    private val policy: QueryPolicy,
    private val sampleRate: Double,
) : LogRecordExporter {
    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        val kept = logs.filter(::sampled).map(::transform)
        return if (kept.isEmpty()) CompletableResultCode.ofSuccess() else delegate.export(kept)
    }

    private fun sampled(log: LogRecordData): Boolean = log.attributes.get(Attr.SESSION_ID)?.let { isSampled(it, sampleRate) } ?: true

    private fun transform(log: LogRecordData): LogRecordData {
        val upstream = log.eventName
        val source =
            when (upstream) {
                Events.UPSTREAM_CRASH, Events.UPSTREAM_CRASH_LEGACY -> ErrorSource.CRASH
                Events.UPSTREAM_ANR -> ErrorSource.ANR
                else -> null
            }
        val eventName = if (source != null) Events.EXCEPTION else upstream
        val b = scrubAttributes(log.attributes, policy).toBuilder()
        if (source != null) b.put(Attr.ERROR_SOURCE, source)
        if (source == ErrorSource.ANR) b.putAnr(log.attributes)
        if (eventName != null) b.put(Attr.EVENT_NAME, eventName)
        val attrs = b.build()
        attrs.get(Attr.EXCEPTION_MESSAGE)?.let { b.put(Attr.EXCEPTION_MESSAGE, scrubText(it, policy)) }
        attrs.get(Attr.EXCEPTION_STACKTRACE)?.let { b.put(Attr.EXCEPTION_STACKTRACE, scrubStack(it, policy)) }
        val body =
            when {
                source != null -> attrs.get(Attr.EXCEPTION_MESSAGE)?.let { Value.of(scrubText(it, policy)) } ?: log.bodyValue
                else -> log.bodyValue?.let { v -> if (v.value is String) Value.of(scrubText(v.value as String, policy)) else v }
            }
        val severity =
            when (source) {
                ErrorSource.CRASH -> Severity.FATAL
                ErrorSource.ANR -> Severity.ERROR
                else -> log.severity
            }
        return RumLogRecordData(log, eventName, b.build(), body, severity)
    }

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode = delegate.shutdown()
}

internal const val ANR_TYPE = "ANR"
internal const val ANR_MESSAGE = "Application Not Responding: the main thread was blocked for at least 5 s"

/** Upstream reports an ANR as bare frames; a Java-formatted trace lets the backend retrace it like a crash. */
private fun io.opentelemetry.api.common.AttributesBuilder.putAnr(original: Attributes) {
    if (original.get(Attr.EXCEPTION_TYPE) == null) put(Attr.EXCEPTION_TYPE, ANR_TYPE)
    if (original.get(Attr.EXCEPTION_MESSAGE) == null) put(Attr.EXCEPTION_MESSAGE, ANR_MESSAGE)
    val frames = original.get(Attr.EXCEPTION_STACKTRACE) ?: return
    if (frames.trimStart().startsWith("at ") || frames.contains("\n\tat ")) return
    val type = original.get(Attr.EXCEPTION_TYPE) ?: ANR_TYPE
    val message = original.get(Attr.EXCEPTION_MESSAGE) ?: ANR_MESSAGE
    val body = frames.lineSequence().filter { it.isNotBlank() }.joinToString("") { "\n\tat ${it.trim()}" }
    put(Attr.EXCEPTION_STACKTRACE, "$type: $message$body")
}

private class RumLogRecordData(
    private val d: LogRecordData,
    private val eventName: String?,
    private val attributes: Attributes,
    private val body: Value<*>?,
    private val severity: Severity,
) : LogRecordData by d {
    override fun getEventName(): String? = eventName

    override fun getAttributes(): Attributes = attributes

    override fun getTotalAttributeCount(): Int = maxOf(d.totalAttributeCount, attributes.size())

    override fun getBodyValue(): Value<*>? = body

    @Deprecated("Deprecated upstream", ReplaceWith("bodyValue"))
    @Suppress("DEPRECATION")
    override fun getBody(): io.opentelemetry.sdk.logs.data.Body =
        (body?.value as? String)?.let {
            io.opentelemetry.sdk.logs.data.Body
                .string(it)
        } ?: io.opentelemetry.sdk.logs.data.Body
            .empty()

    override fun getSeverity(): Severity = severity
}
