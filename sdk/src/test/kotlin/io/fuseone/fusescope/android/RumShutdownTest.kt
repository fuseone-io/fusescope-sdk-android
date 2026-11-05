package io.fuseone.fusescope.android

import android.os.Looper
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class RumShutdownTest {
    private fun assertWorkerShutdown(close: (() -> CompletableResultCode) -> CompletableResultCode) {
        val closed = CountDownLatch(1)
        val closedOnMain = AtomicBoolean(false)
        assertTrue(Looper.myLooper() == Looper.getMainLooper())
        val result =
            close {
                closedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                closed.countDown()
                CompletableResultCode.ofSuccess()
            }
        assertTrue("the exporter must close", closed.await(5, TimeUnit.SECONDS))
        assertTrue("shutdown completion must propagate", result.join(5, TimeUnit.SECONDS).isSuccess)
        assertFalse("a completed batch callback can run on main and close a TLS socket", closedOnMain.get())
    }

    @Test
    fun `a completed span batch cannot close TLS on main`() {
        assertWorkerShutdown { shutdown ->
            val delegate =
                object : SpanExporter by InMemorySpanExporter.create() {
                    override fun shutdown(): CompletableResultCode = shutdown()
                }
            RumSpanExporter(delegate, QueryPolicy.Allow(emptySet())).shutdown()
        }
    }

    @Test
    fun `a completed log batch cannot close TLS on main`() {
        assertWorkerShutdown { shutdown ->
            val delegate =
                object : LogRecordExporter by InMemoryLogRecordExporter.create() {
                    override fun shutdown(): CompletableResultCode = shutdown()
                }
            RumLogExporter(delegate, QueryPolicy.Allow(emptySet()), 1.0).shutdown()
        }
    }

    @Test
    fun `withdrawal closes network exporters off the main thread`() {
        val emitted = CountDownLatch(1)
        val closed = CountDownLatch(2)
        val closedOnMain = AtomicBoolean(false)

        fun recordClose(): CompletableResultCode {
            if (Looper.myLooper() == Looper.getMainLooper()) closedOnMain.set(true)
            closed.countDown()
            return CompletableResultCode.ofSuccess()
        }
        val spans =
            object : SpanExporter by InMemorySpanExporter.create() {
                override fun shutdown(): CompletableResultCode = recordClose()
            }
        val logs =
            object : LogRecordExporter by InMemoryLogRecordExporter.create() {
                override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
                    emitted.countDown()
                    return CompletableResultCode.ofSuccess()
                }

                override fun shutdown(): CompletableResultCode = recordClose()
            }
        val config =
            resolveConfig(
                FuseScopeConfig(applicationId = "shutdown-test", key = "rum_pub_test", endpoint = "https://collector.example.com"),
            )
        val rum = Rum.start(RuntimeEnvironment.getApplication(), config, Exporters(spans, logs))
        rum.addError(IllegalStateException("before withdrawal"), Attributes.empty())
        rum.flush()
        assertTrue("exporters must be initialized before testing shutdown", emitted.await(5, TimeUnit.SECONDS))
        val worker = thread { rum.shutdown() }
        worker.join(15_000)
        assertFalse("withdrawal worker did not complete", worker.isAlive)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("both exporters must close", closed.await(5, TimeUnit.SECONDS))
        assertFalse("closing TLS exporters on main can throw NetworkOnMainThreadException", closedOnMain.get())
    }
}
