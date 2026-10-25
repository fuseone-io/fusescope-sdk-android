package io.fusescope.android

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import okhttp3.Call
import okhttp3.OkHttpClient

/** FuseScope Real User Monitoring for Android. */
public object FuseScope {
    /** Version of this SDK, sent as `telemetry.distro.version`. */
    public const val VERSION: String = BuildConfig.SDK_VERSION

    private const val TAG = "FuseScope"

    private var application: Application? = null
    private var config: ResolvedConfig? = null
    private var consented = false
    private var pendingUser: String? = null

    @Volatile private var rum: Rum? = null

    /**
     * Starts collection; with [FuseScopeConfig.requireConsent], collection waits for [setConsent].
     * Call it from `Application.onCreate`; from another thread, collection starts on the main thread shortly after.
     * An invalid configuration, or a second call, is logged and ignored.
     */
    @JvmStatic
    @Synchronized
    public fun init(
        application: Application,
        config: FuseScopeConfig,
    ) {
        if (this.config != null) {
            Log.w(TAG, "init() was already called; ignoring")
            return
        }
        val resolved =
            try {
                resolveConfig(config)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, e.message.orEmpty())
                return
            }
        this.application = application
        this.config = resolved
        consented = !resolved.requireConsent
        start()
    }

    /** Grants (starts collection) or withdraws (flushes and stops it) the user's consent. */
    @JvmStatic
    @Synchronized
    public fun setConsent(granted: Boolean) {
        consented = granted
        if (granted) start() else stop()
    }

    /**
     * Identifies the user (opt-in): `user.id` is the SHA-256 of [id] unless [PrivacyConfig.hashUserId] is false.
     * `null` clears it. Spans already started, such as the current view, keep their previous value.
     */
    @JvmStatic
    @Synchronized
    public fun setUser(id: String?) {
        pendingUser = id
        rum?.setUser(id)
    }

    /** Reports a handled error (`fusescope.rum.error.source = manual`). */
    @JvmStatic
    @JvmOverloads
    public fun addError(
        throwable: Throwable,
        attributes: Attributes = Attributes.empty(),
    ) {
        rum?.addError(throwable, attributes)
    }

    /** Reports a custom user action, e.g. `addAction("checkout")`. */
    @JvmStatic
    @JvmOverloads
    public fun addAction(
        name: String,
        attributes: Attributes = Attributes.empty(),
    ) {
        rum?.addAction(name, attributes)
    }

    /**
     * Starts a view named [route], e.g. a Compose navigation route template such as `orders/{id}`.
     * It replaces the automatic view of the current Activity and is restored when that Activity resumes.
     */
    @JvmStatic
    public fun startView(route: String) {
        rum?.startView(route)
    }

    /**
     * Wraps [client] so its requests are traced and, for sampled sessions, carry `traceparent`.
     * The factory may be created before [init]; requests made while collection is off go through [client] unchanged.
     */
    @JvmStatic
    public fun callFactory(client: OkHttpClient): Call.Factory {
        var instrumented: Pair<Rum, Call.Factory>? = null
        return Call.Factory { request ->
            val current = rum ?: return@Factory client.newCall(request)
            val factory =
                instrumented?.takeIf { it.first === current }?.second
                    ?: current.callFactory(client).also { instrumented = current to it }
            factory.newCall(request)
        }
    }

    /** The OpenTelemetry instance FuseScope sends through, for custom spans and logs; a no-op while collection is off. */
    @JvmStatic
    public val openTelemetry: OpenTelemetry get() = rum?.openTelemetry ?: OpenTelemetry.noop()

    /** Exports pending data now, blocking for up to 5 s per signal. Call it off the main thread. */
    @JvmStatic
    public fun flush() {
        rum?.flush()
    }

    /** Flushes and stops collection; [init] may be called again afterwards. */
    @JvmStatic
    @Synchronized
    public fun shutdown() {
        stop()
        application = null
        config = null
        consented = false
        pendingUser = null
    }

    /** Upstream instrumentations register lifecycle observers, which Android only allows on the main thread. */
    private fun start() {
        val app = application ?: return
        val c = config ?: return
        if (!consented || rum != null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { synchronized(this) { start() } }
            return
        }
        rum = Rum.start(app, c).also { r -> pendingUser?.let(r::setUser) }
    }

    private fun stop() {
        val r = rum ?: return
        rum = null
        r.shutdown()
    }
}
