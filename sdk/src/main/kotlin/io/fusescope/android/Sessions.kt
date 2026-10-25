package io.fusescope.android

import android.annotation.SuppressLint
import android.content.Context
import io.opentelemetry.android.session.Session
import io.opentelemetry.android.session.SessionObserver
import io.opentelemetry.android.session.SessionProvider
import io.opentelemetry.android.session.SessionPublisher
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

internal data class SessionRecord(
    override val id: String,
    val previousId: String?,
    val startedAt: Long,
    val lastActiveAt: Long,
    val sampled: Boolean,
) : Session {
    override val startTimestamp: Long get() = startedAt

    fun encode(): String = listOf(FORMAT, id, previousId.orEmpty(), startedAt, lastActiveAt, sampled).joinToString("|")

    companion object {
        private const val FORMAT = "v1"

        fun decode(raw: String?): SessionRecord? {
            val parts = raw?.split('|') ?: return null
            if (parts.size != 6 || parts[0] != FORMAT || parts[1].isEmpty()) return null
            return SessionRecord(
                id = parts[1],
                previousId = parts[2].ifEmpty { null },
                startedAt = parts[3].toLongOrNull() ?: return null,
                lastActiveAt = parts[4].toLongOrNull() ?: return null,
                sampled = parts[5].toBooleanStrictOrNull() ?: return null,
            )
        }
    }
}

internal interface KeyValueStore {
    fun get(): String?

    fun set(value: String)
}

@SuppressLint("UseKtx")
internal fun sharedPreferencesStore(context: Context): KeyValueStore {
    val prefs = context.getSharedPreferences("io.fusescope.android", Context.MODE_PRIVATE)
    return object : KeyValueStore {
        override fun get(): String? = prefs.getString("session", null)

        override fun set(value: String) = prefs.edit().putString("session", value).apply()
    }
}

private val random = SecureRandom()

internal fun newSessionId(): String {
    val bytes = ByteArray(16).also(random::nextBytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Sessions with `session.previous_id` and a sampling decision, kept across process restarts.
 * A session ends after [SessionConfig.inactivityTimeoutMillis] in the background (or before the first
 * Activity starts) and never lasts longer than [SessionConfig.maxDurationMillis].
 */
internal class RumSessionManager(
    private val options: SessionConfig,
    private val sampleRate: Double,
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val makeId: () -> String = ::newSessionId,
) : SessionProvider,
    SessionPublisher {
    private val observers = CopyOnWriteArrayList<SessionObserver>()
    private var current: SessionRecord? = SessionRecord.decode(store.get())
    private var foreground = false

    override fun addObserver(observer: SessionObserver) {
        observers += observer
    }

    override fun getSessionId(): String = get().id

    fun get(): SessionRecord {
        var rotated: Pair<SessionRecord, SessionRecord?>? = null
        val session =
            synchronized(this) {
                val t = now()
                val base = current
                if (base != null && !isExpired(base, t)) {
                    if (foreground) base.copy(lastActiveAt = t).also { current = it } else base
                } else {
                    val id = makeId()
                    SessionRecord(id, base?.id, t, t, isSampled(id, sampleRate)).also {
                        current = it
                        store.set(it.encode())
                        rotated = it to base
                    }
                }
            }
        rotated?.let { (next, previous) -> notify(next, previous) }
        return session
    }

    fun onForeground() {
        synchronized(this) { foreground = true }
        persist()
    }

    fun onBackground() {
        persist()
        synchronized(this) { foreground = false }
    }

    private fun persist() {
        val session = get()
        synchronized(this) {
            val touched = session.copy(lastActiveAt = now())
            if (current?.id == touched.id) current = touched
            store.set(touched.encode())
        }
    }

    private fun isExpired(
        s: SessionRecord,
        t: Long,
    ): Boolean = t - s.startedAt > options.maxDurationMillis || (!foreground && t - s.lastActiveAt > options.inactivityTimeoutMillis)

    private fun notify(
        next: SessionRecord,
        previous: SessionRecord?,
    ) {
        for (o in observers) {
            if (previous != null) o.onSessionEnded(previous)
            o.onSessionStarted(next, previous ?: NO_SESSION)
        }
    }

    private companion object {
        val NO_SESSION = SessionRecord("", null, -1, -1, false)
    }
}
