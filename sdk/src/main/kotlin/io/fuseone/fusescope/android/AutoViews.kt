package io.fuseone.fusescope.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.opentelemetry.android.instrumentation.common.DefaultScreenNameExtractor
import java.lang.ref.WeakReference

/**
 * Starts views from resumed Activities and Fragments and reports foreground and background to the sessions.
 *
 * An Activity resumes before its Fragments, and a parent Fragment before its children, all in one main-thread
 * message; the last screen resumed in that message becomes the view, so a host Activity or a `NavHostFragment`
 * never gets a view of its own. A view started with [FuseScope.startView] (Compose) is restored when its Activity
 * resumes again; started within [MANUAL_RENAME_WINDOW_NANOS] of the Activity's automatic view, it renames that
 * view instead, so a Compose screen does not leave a view of its host Activity behind.
 */
internal class AutoViews(
    private val views: ViewController,
    private val sessions: RumSessionManager,
    private val automatic: Boolean,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : Application.ActivityLifecycleCallbacks {
    private data class Shown(
        val route: String,
        val host: WeakReference<Activity>,
        val manual: Boolean,
    )

    private var started = 0
    private var resumed: WeakReference<Activity>? = null
    private var candidate: String? = null
    private var last: Shown? = null
    private var shownSinceLaunch = false
    private val commit = Runnable { commit() }

    private val fragmentCallbacks =
        object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(
                fm: FragmentManager,
                f: Fragment,
            ) {
                if (f.view != null && !f.isHidden) propose(DefaultScreenNameExtractor.extract(f))
            }
        }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        if (automatic && activity is FragmentActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) sessions.onForeground()
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        val previous = last
        val restoreManual = previous != null && previous.manual && previous.host.get() === activity
        propose(if (restoreManual) previous.route else DefaultScreenNameExtractor.extract(activity))
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
        candidate = null
        if (last?.host?.get() === activity) views.end()
    }

    override fun onActivityStopped(activity: Activity) {
        if (--started <= 0) {
            started = 0
            sessions.onBackground()
        }
    }

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) {}

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is FragmentActivity) activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentCallbacks)
    }

    /** Starts a view now, from [FuseScope.startView]. */
    fun startManual(route: String) {
        val run = {
            candidate = null
            val host = resumed?.get()
            val previous = last
            val fresh = previous != null && !previous.manual && host != null && previous.host.get() === host
            if (fresh && views.renameIfRecent(route, MANUAL_RENAME_WINDOW_NANOS)) {
                last = Shown(route, WeakReference(host), manual = true)
            } else {
                show(route, manual = true)
            }
        }
        if (Looper.myLooper() == handler.looper) run() else handler.post(run)
    }

    private fun propose(route: String) {
        if (!automatic && last?.manual != true) return
        candidate = route
        handler.removeCallbacks(commit)
        handler.post(commit)
    }

    private fun commit() {
        val route = candidate ?: return
        candidate = null
        if (resumed?.get() == null) return
        val previous = last
        val manual = previous != null && previous.manual && previous.route == route && previous.host.get() === resumed?.get()
        if (!automatic && !manual) return
        show(route, manual)
    }

    private fun show(
        route: String,
        manual: Boolean,
    ) {
        val host = resumed?.get()
        val previous = last
        val navigationType =
            when {
                !shownSinceLaunch -> NavigationType.LOAD
                manual && previous?.route != route -> NavigationType.MANUAL
                previous?.route == route && previous.host.get() === host -> NavigationType.RESTORE
                else -> NavigationType.PUSH
            }
        shownSinceLaunch = true
        last = Shown(route, WeakReference(host), manual)
        views.start(route, navigationType)
    }

    internal companion object {
        val MANUAL_RENAME_WINDOW_NANOS: Long =
            java.util.concurrent.TimeUnit.SECONDS
                .toNanos(1)
    }
}
