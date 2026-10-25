package io.fuseone.fusescope.android

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** On a device: errors reach the gateway with the key header and the FuseScope attributes. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    private val gateway = MockWebServer()

    @After
    fun tearDown() {
        FuseScope.shutdown()
        gateway.close()
    }

    @Test
    fun exportsAnErrorToTheGateway() {
        repeat(4) { gateway.enqueue(MockResponse(code = 200)) }
        gateway.start()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        instrumentation.runOnMainSync {
            FuseScope.init(app, FuseScopeConfig(applicationId = "smoke-app", key = "rum_pub_smoke", endpoint = gateway.url("/").toString()))
        }
        FuseScope.addError(IllegalStateException("smoke"))
        FuseScope.flush()

        var logs: RecordedRequest? = null
        while (logs == null) {
            val r = gateway.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull("no request to /rum/v1/logs", r)
            if (r!!.url.encodedPath == "/rum/v1/logs") logs = r
        }
        assertEquals("rum_pub_smoke", logs.headers["FuseScope-RUM-Key"])
        val body = String(GZIPInputStream(logs.body!!.toByteArray().inputStream()).readBytes(), Charsets.ISO_8859_1)
        for (expected in listOf("fusescope.rum.platform", "android", "app.package.name", app.packageName, "exception", "manual", "smoke")) {
            assertTrue("missing $expected", body.contains(expected))
        }
    }
}
