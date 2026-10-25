package io.fuseone.fusescope.android

import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportersTest {
    private val drop = QueryPolicy.Allow(emptySet())
    private val sampledId = "00000000" + "a".repeat(24)
    private val unsampledId = "ffffffff" + "a".repeat(24)

    private fun exportLogs(
        rate: Double = 1.0,
        emit: (io.opentelemetry.api.logs.Logger) -> Unit,
    ): List<LogRecordData> {
        val sink = InMemoryLogRecordExporter.create()
        val provider =
            SdkLoggerProvider
                .builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(RumLogExporter(sink, drop, rate)))
                .build()
        emit(provider.get("test"))
        provider.forceFlush()
        return sink.finishedLogRecordItems
    }

    @Test
    fun `maps upstream crashes to exception events with source crash`() {
        val log =
            exportLogs {
                it
                    .logRecordBuilder()
                    .setEventName("app.crash")
                    .setAttribute(Attr.SESSION_ID, sampledId)
                    .setAttribute(Attr.EXCEPTION_TYPE, "java.lang.IllegalStateException")
                    .setAttribute(Attr.EXCEPTION_MESSAGE, "boom at https://api.io/x?token=1")
                    .setAttribute(Attr.EXCEPTION_STACKTRACE, "java.lang.IllegalStateException: boom\n\tat a.b.c(SourceFile:1)")
                    .emit()
            }.single()
        assertEquals("exception", log.eventName)
        assertEquals("exception", log.attributes.get(Attr.EVENT_NAME))
        assertEquals("crash", log.attributes.get(Attr.ERROR_SOURCE))
        assertEquals("boom at https://api.io/x", log.attributes.get(Attr.EXCEPTION_MESSAGE))
        assertEquals("boom at https://api.io/x", log.bodyValue?.asString())
        assertEquals(Severity.FATAL, log.severity)
    }

    @Test
    fun `maps upstream ANRs to exception events with a Java stack trace`() {
        val log =
            exportLogs {
                it
                    .logRecordBuilder()
                    .setEventName("device.anr")
                    .setAttribute(Attr.EXCEPTION_STACKTRACE, "java.lang.Thread.sleep(Native Method)\na.b.c(SourceFile:3)\n")
                    .emit()
            }.single()
        assertEquals("exception", log.eventName)
        assertEquals("anr", log.attributes.get(Attr.ERROR_SOURCE))
        assertEquals(ANR_TYPE, log.attributes.get(Attr.EXCEPTION_TYPE))
        assertEquals(
            "$ANR_TYPE: $ANR_MESSAGE\n\tat java.lang.Thread.sleep(Native Method)\n\tat a.b.c(SourceFile:3)",
            log.attributes.get(Attr.EXCEPTION_STACKTRACE),
        )
        assertEquals(Severity.ERROR, log.severity)
    }

    @Test
    fun `copies other event names into event_name and leaves records without one alone`() {
        val logs =
            exportLogs {
                it.logRecordBuilder().setEventName("session.start").emit()
                it.logRecordBuilder().setBody("plain").emit()
            }
        assertEquals("session.start", logs[0].attributes.get(Attr.EVENT_NAME))
        assertNull(logs[1].attributes.get(Attr.EVENT_NAME))
        assertEquals("plain", logs[1].bodyValue?.asString())
    }

    @Test
    fun `drops records of unsampled sessions`() {
        val logs =
            exportLogs(rate = 0.5) {
                it
                    .logRecordBuilder()
                    .setAttribute(Attr.SESSION_ID, unsampledId)
                    .setBody("dropped")
                    .emit()
                it
                    .logRecordBuilder()
                    .setAttribute(Attr.SESSION_ID, sampledId)
                    .setBody("kept")
                    .emit()
            }
        assertEquals(listOf("kept"), logs.map { it.bodyValue?.asString() })
    }

    @Test
    fun `scrubs span names and URL attributes`() {
        val sink = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(RumSpanExporter(sink, drop))).build()
        provider
            .get("test")
            .spanBuilder("GET /search?q=secret")
            .setAllAttributes(Attributes.of(stringKey("url.full"), "https://api.io/search?q=secret"))
            .startSpan()
            .end()
        val span = sink.finishedSpanItems.single()
        assertEquals("GET /search", span.name)
        assertEquals("https://api.io/search", span.attributes.get(stringKey("url.full")))
    }

    @Test
    fun `drops upstream lifecycle spans except AppStart unless kept`() {
        fun export(keep: Boolean): List<String> {
            val sink = InMemorySpanExporter.create()
            val provider =
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(RumSpanExporter(sink, drop, keep))).build()
            for (name in listOf("AppStart", "Created", "Paused")) {
                provider
                    .get(LIFECYCLE_SCOPE)
                    .spanBuilder(name)
                    .startSpan()
                    .end()
            }
            provider
                .get("io.fuseone.fusescope:android")
                .spanBuilder("view")
                .startSpan()
                .end()
            return sink.finishedSpanItems.map { it.name }
        }
        assertEquals(listOf("AppStart", "view"), export(keep = false))
        assertEquals(listOf("AppStart", "Created", "Paused", "view"), export(keep = true))
    }
}
