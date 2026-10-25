package io.fuseone.fusescope.android

import android.app.Activity
import android.os.Looper
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.KeyValue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
class FuseScopeTest {
    class CheckoutActivity : Activity()

    private val gateway = MockWebServer()
    private val api = MockWebServer()
    private val traces = mutableListOf<ExportTraceServiceRequest>()
    private val logs = mutableListOf<ExportLogsServiceRequest>()
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        gateway.start()
        api.start()
    }

    @After
    fun tearDown() {
        FuseScope.shutdown()
        gateway.close()
        api.close()
    }

    private fun config(rate: Double = 1.0) =
        FuseScopeConfig(
            applicationId = "0199c0de-0000-7000-8000-000000000001",
            key = "rum_pub_test",
            endpoint = gateway.url("/").toString(),
            environment = "test",
            release = "1.4.0",
            sessionSampleRate = rate,
        )

    private fun drain() {
        FuseScope.flush()
        while (true) {
            val r = gateway.takeRequest(2, TimeUnit.SECONDS) ?: return
            requests += r
            val body = GZIPInputStream(r.body!!.toByteArray().inputStream()).readBytes()
            when (r.url.encodedPath) {
                "/rum/v1/traces" -> traces += ExportTraceServiceRequest.parseFrom(body)
                "/rum/v1/logs" -> logs += ExportLogsServiceRequest.parseFrom(body)
            }
        }
    }

    private fun List<KeyValue>.str(key: String): String? = firstOrNull { it.key == key }?.value?.stringValue

    @Test
    fun `sends spans and events with the FuseScope data model`() {
        repeat(4) { gateway.enqueue(MockResponse(code = 200)) }
        api.enqueue(MockResponse(code = 200))
        FuseScope.setUser("user-42")
        FuseScope.init(RuntimeEnvironment.getApplication(), config())
        val activity = Robolectric.buildActivity(CheckoutActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        FuseScope
            .callFactory(OkHttpClient())
            .newCall(Request.Builder().url(api.url("/cart?token=secret")).build())
            .execute()
            .close()
        FuseScope.addError(IllegalStateException("payment failed"))
        activity.pause()
        drain()

        assertEquals(setOf("rum_pub_test"), requests.map { it.headers["FuseScope-RUM-Key"] }.toSet())
        val resource =
            traces
                .first()
                .resourceSpansList
                .first()
                .resource.attributesList
        assertEquals("android", resource.str("fusescope.rum.platform"))
        assertEquals("0199c0de-0000-7000-8000-000000000001", resource.str("fusescope.rum.application.id"))
        assertEquals("1.4.0", resource.str("service.version"))
        assertEquals("test", resource.str("deployment.environment.name"))
        assertEquals(RuntimeEnvironment.getApplication().packageName, resource.str("app.package.name"))
        assertEquals("io.fuseone.fusescope:android", resource.str("telemetry.distro.name"))
        assertNull(resource.str("app.installation.id"))

        val spans = traces.flatMap { it.resourceSpansList }.flatMap { it.scopeSpansList }.flatMap { it.spansList }
        val view = spans.single { it.name == "view" }
        assertEquals("CheckoutActivity", view.attributesList.str("fusescope.rum.view.route"))
        val sessionId = view.attributesList.str("session.id")
        assertNotNull(sessionId)
        assertEquals(sha256Hex("user-42"), view.attributesList.str("user.id"))
        val http = spans.single { it.name == "GET" }
        assertEquals(api.url("/cart").toString(), http.attributesList.str("url.full"))
        assertEquals(sessionId, http.attributesList.str("session.id"))

        val traceparent = api.takeRequest(1, TimeUnit.SECONDS)!!.headers["traceparent"]
        assertNotNull(traceparent)

        val records = logs.flatMap { it.resourceLogsList }.flatMap { it.scopeLogsList }.flatMap { it.logRecordsList }
        val error = records.single { it.eventName == "exception" }
        assertEquals("exception", error.attributesList.str("event.name"))
        assertEquals("manual", error.attributesList.str("fusescope.rum.error.source"))
        assertEquals("java.lang.IllegalStateException", error.attributesList.str("exception.type"))
        assertEquals("CheckoutActivity", error.attributesList.str("fusescope.rum.view.route"))
        assertEquals(sessionId, error.attributesList.str("session.id"))
    }

    @Test
    fun `an unsampled session sends nothing and no traceparent`() {
        api.enqueue(MockResponse(code = 200))
        FuseScope.init(RuntimeEnvironment.getApplication(), config(rate = 0.0))
        Robolectric.buildActivity(CheckoutActivity::class.java).setup().pause()
        shadowOf(Looper.getMainLooper()).idle()
        FuseScope
            .callFactory(OkHttpClient())
            .newCall(Request.Builder().url(api.url("/cart")).build())
            .execute()
            .close()
        FuseScope.addError(IllegalStateException("dropped"))
        drain()
        assertNull(api.takeRequest(1, TimeUnit.SECONDS)!!.headers["traceparent"])
        assertEquals(emptyList<ExportLogsServiceRequest>(), logs.filter { it.resourceLogsCount > 0 })
        assertEquals(0, traces.sumOf { it.resourceSpansCount })
    }

    @Test
    fun `requireConsent defers collection until consent`() {
        FuseScope.init(RuntimeEnvironment.getApplication(), config().copy(requireConsent = true))
        assertEquals(
            io.opentelemetry.api.OpenTelemetry
                .noop(),
            FuseScope.openTelemetry,
        )
        FuseScope.setConsent(true)
        assertNotNull(FuseScope.openTelemetry.getTracer("x"))
        FuseScope.setConsent(false)
        assertEquals(
            io.opentelemetry.api.OpenTelemetry
                .noop(),
            FuseScope.openTelemetry,
        )
    }
}
