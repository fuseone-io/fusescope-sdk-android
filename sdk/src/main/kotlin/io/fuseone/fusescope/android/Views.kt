package io.fuseone.fusescope.android

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context

internal object NavigationType {
    const val LOAD = "load"
    const val PUSH = "push"
    const val RESTORE = "restore"
    const val MANUAL = "manual"
}

/**
 * One `view` span per screen, so its duration is the time on screen: it ends when the next view starts,
 * when its Activity pauses, or on shutdown. Only the first view of the process is an `initial_load`.
 */
internal class ViewController(
    private val tracer: Tracer,
    private val state: RumState,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var current: Span? = null
    private var startedAt = 0L
    private var first = true

    @Synchronized
    fun start(
        route: String,
        navigationType: String,
    ) {
        current?.end()
        val loadingType = if (first) "initial_load" else "route_change"
        first = false
        state.route = route
        startedAt = nanoTime()
        current =
            tracer
                .spanBuilder(VIEW_SPAN)
                .setParent(Context.root())
                .setAttribute(Attr.VIEW_ROUTE, route)
                .setAttribute(Attr.VIEW_LOADING_TYPE, loadingType)
                .setAttribute(Attr.VIEW_NAVIGATION_TYPE, navigationType)
                .startSpan()
    }

    /** Names the current view [route] if it started less than [windowNanos] ago; returns whether it did. */
    @Synchronized
    fun renameIfRecent(
        route: String,
        windowNanos: Long,
    ): Boolean {
        val span = current ?: return false
        if (nanoTime() - startedAt >= windowNanos) return false
        span.setAttribute(Attr.VIEW_ROUTE, route)
        state.route = route
        return true
    }

    @Synchronized
    fun end() {
        current?.end()
        current = null
    }

    internal companion object {
        const val VIEW_SPAN = "view"
    }
}
