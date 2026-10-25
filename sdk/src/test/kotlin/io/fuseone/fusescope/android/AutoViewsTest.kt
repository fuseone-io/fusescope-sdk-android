package io.fuseone.fusescope.android

import android.app.Activity
import android.os.Looper
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class AutoViewsTest {
    class HomeActivity : Activity()

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = CONTAINER })
            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction().add(CONTAINER, CartFragment()).commitNow()
            }
        }
    }

    class CartFragment : Fragment() {
        override fun onCreateView(
            inflater: android.view.LayoutInflater,
            container: android.view.ViewGroup?,
            savedInstanceState: android.os.Bundle?,
        ) = FrameLayout(inflater.context)
    }

    private val exporter = InMemorySpanExporter.create()
    private var now = 0L
    private lateinit var views: AutoViews
    private lateinit var sessions: RumSessionManager

    @Before
    fun setUp() {
        val tracer =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
                .get("test")
        val store =
            object : KeyValueStore {
                var v: String? = null

                override fun get() = v

                override fun set(value: String) {
                    v = value
                }
            }
        sessions = RumSessionManager(SessionConfig(), 1.0, store)
        val state = RumState(sessions)
        views = AutoViews(ViewController(tracer, state) { now }, sessions, automatic = true)
        org.robolectric.RuntimeEnvironment
            .getApplication()
            .registerActivityLifecycleCallbacks(views)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun viewSpans(): List<SpanData> = exporter.finishedSpanItems.filter { it.name == "view" }

    private fun SpanData.attr(key: io.opentelemetry.api.common.AttributeKey<String>) = attributes.get(key)

    @Test
    fun `an Activity view lasts until the Activity pauses`() {
        val activity = Robolectric.buildActivity(HomeActivity::class.java).setup()
        idle()
        activity.pause()
        val view = viewSpans().single()
        assertEquals("HomeActivity", view.attr(Attr.VIEW_ROUTE))
        assertEquals("initial_load", view.attr(Attr.VIEW_LOADING_TYPE))
        assertEquals("load", view.attr(Attr.VIEW_NAVIGATION_TYPE))
    }

    @Test
    fun `a Fragment replaces its host Activity as the view`() {
        val activity: ActivityController<HostActivity> = Robolectric.buildActivity(HostActivity::class.java).setup()
        idle()
        activity.pause()
        assertEquals(listOf("CartFragment"), viewSpans().map { it.attr(Attr.VIEW_ROUTE) })
    }

    @Test
    fun `returning to the same screen restores it and the next screen is a push`() {
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        idle()
        home.pause().stop()
        home.start().resume()
        idle()
        home.pause()
        val host = Robolectric.buildActivity(HostActivity::class.java).setup()
        idle()
        host.pause()
        assertEquals(
            listOf("load" to "initial_load", "restore" to "route_change", "push" to "route_change"),
            viewSpans().map { it.attr(Attr.VIEW_NAVIGATION_TYPE) to it.attr(Attr.VIEW_LOADING_TYPE) },
        )
    }

    @Test
    fun `a manual view right after the automatic one renames it`() {
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        idle()
        views.startManual("orders/{id}")
        home.pause()
        assertEquals(listOf("orders/{id}" to "load"), viewSpans().map { it.attr(Attr.VIEW_ROUTE) to it.attr(Attr.VIEW_NAVIGATION_TYPE) })
    }

    @Test
    fun `a later manual view replaces the automatic one and is restored on resume`() {
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        idle()
        now += AutoViews.MANUAL_RENAME_WINDOW_NANOS
        views.startManual("orders/{id}")
        home.pause()
        home.resume()
        idle()
        home.pause()
        assertEquals(
            listOf("HomeActivity" to "load", "orders/{id}" to "manual", "orders/{id}" to "restore"),
            viewSpans().map { it.attr(Attr.VIEW_ROUTE) to it.attr(Attr.VIEW_NAVIGATION_TYPE) },
        )
    }

    @Test
    fun `foreground and background drive the session`() {
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val id = sessions.getSessionId()
        home.pause().stop()
        assertEquals(id, sessions.getSessionId())
    }

    private companion object {
        const val CONTAINER = 0x7f0a0001
    }
}
