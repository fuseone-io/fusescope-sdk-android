package io.fuseone.fusescope.android

import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import java.util.concurrent.TimeUnit

/** What every span and log record is stamped with at the time it starts. */
internal class RumState(
    val sessions: RumSessionManager,
) {
    @Volatile var route: String? = null

    @Volatile var userId: String? = null
}

/** Adds session, view and user attributes when a span starts; reports `AppStart` spans as the `app_start` vital. */
internal class RumSpanProcessor(
    private val state: RumState,
    private val onAppStart: (ReadableSpan) -> Unit,
) : SpanProcessor {
    override fun onStart(
        parentContext: Context,
        span: ReadWriteSpan,
    ) {
        val session = state.sessions.get()
        span.setAttribute(Attr.SESSION_ID, session.id)
        session.previousId?.let { span.setAttribute(Attr.SESSION_PREVIOUS_ID, it) }
        if (span.getAttribute(Attr.VIEW_ROUTE) == null) state.route?.let { span.setAttribute(Attr.VIEW_ROUTE, it) }
        state.userId?.let { span.setAttribute(Attr.USER_ID, it) }
    }

    override fun isStartRequired(): Boolean = true

    override fun onEnd(span: ReadableSpan) {
        if (span.name == APP_START_SPAN && span.instrumentationScopeInfo.name == LIFECYCLE_SCOPE) onAppStart(span)
    }

    override fun isEndRequired(): Boolean = true
}

/**
 * Runs before upstream's processors and the batch processor. A record that already carries a `session.id`,
 * such as a crash recovered from a previous session, keeps it.
 */
internal class RumLogProcessor(
    private val state: RumState,
) : LogRecordProcessor {
    override fun onEmit(
        context: Context,
        logRecord: ReadWriteLogRecord,
    ) {
        val session = state.sessions.get()
        val own = logRecord.getAttribute(Attr.SESSION_ID)
        if (own == null) logRecord.setAttribute(Attr.SESSION_ID, session.id)
        if (own == null || own == session.id) session.previousId?.let { logRecord.setAttribute(Attr.SESSION_PREVIOUS_ID, it) }
        if (logRecord.getAttribute(Attr.VIEW_ROUTE) == null) state.route?.let { logRecord.setAttribute(Attr.VIEW_ROUTE, it) }
        state.userId?.let { logRecord.setAttribute(Attr.USER_ID, it) }
        logRecord.eventName?.let { logRecord.setAttribute(Attr.EVENT_NAME, it) }
    }
}

internal const val APP_START_SPAN = "AppStart"
internal const val LIFECYCLE_SCOPE = "io.opentelemetry.lifecycle"
private val MAX_COLD_START_NANOS = TimeUnit.MINUTES.toNanos(1)
internal const val APP_START_VITAL = "app_start"

/** Android vitals calls a start excessive from 5 s cold, 2 s warm and 1.5 s hot; under half of that is good. */
internal fun appStartRating(
    startType: String?,
    millis: Double,
): String {
    val poor =
        when (startType) {
            "warm" -> 2_000.0
            "hot" -> 1_500.0
            else -> 5_000.0
        }
    return when {
        millis >= poor -> "poor"
        millis >= poor / 2 -> "needs-improvement"
        else -> "good"
    }
}

/** Milliseconds of an `AppStart` span; a cold start counts from [processStartNanos], which upstream's span misses. */
internal fun appStartMillis(
    startType: String?,
    startNanos: Long,
    endNanos: Long,
    processStartNanos: Long?,
): Double {
    val from =
        processStartNanos
            ?.takeIf { startType == "cold" && it < startNanos && endNanos - it < MAX_COLD_START_NANOS }
            ?: startNanos
    return (endNanos - from) / 1_000_000.0
}

internal fun emitAppStartVital(
    logger: Logger,
    span: ReadableSpan,
    processStartNanos: Long?,
) {
    val data = span.toSpanData()
    val startType = data.attributes.get(Attr.START_TYPE)
    val millis = appStartMillis(startType, data.startEpochNanos, data.endEpochNanos, processStartNanos)
    logger
        .logRecordBuilder()
        .setEventName(Events.VITAL)
        .setSeverity(Severity.INFO)
        .setTimestamp(data.endEpochNanos, TimeUnit.NANOSECONDS)
        .setAttribute(Attr.VITAL_NAME, APP_START_VITAL)
        .setAttribute(Attr.VITAL_VALUE, millis)
        .setAttribute(Attr.VITAL_UNIT, "ms")
        .setAttribute(Attr.VITAL_RATING, appStartRating(startType, millis))
        .apply { startType?.let { setAttribute(Attr.START_TYPE, it) } }
        .emit()
}
