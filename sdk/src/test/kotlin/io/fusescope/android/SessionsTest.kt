package io.fusescope.android

import io.opentelemetry.android.session.Session
import io.opentelemetry.android.session.SessionObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsTest {
    private class MemoryStore(
        var value: String? = null,
    ) : KeyValueStore {
        override fun get() = value

        override fun set(value: String) {
            this.value = value
        }
    }

    private var time = 1_000_000L
    private var ids = 0
    private val options = SessionConfig(inactivityTimeoutMillis = 15 * 60_000L, maxDurationMillis = 4 * 3_600_000L)

    private fun manager(
        store: KeyValueStore = MemoryStore(),
        rate: Double = 1.0,
    ) = RumSessionManager(options, rate, store, { time }, { "%032x".format(++ids) })

    @Test
    fun `keeps the session in the foreground and rotates at the max duration`() {
        val m = manager()
        m.onForeground()
        val first = m.get()
        time += 3 * 3_600_000L
        assertEquals(first.id, m.get().id)
        time += 3_600_000L + 1
        val next = m.get()
        assertNotEquals(first.id, next.id)
        assertEquals(first.id, next.previousId)
    }

    @Test
    fun `rotates after the inactivity timeout in the background`() {
        val m = manager()
        m.onForeground()
        val first = m.get()
        m.onBackground()
        time += 15 * 60_000L
        assertEquals(first.id, m.get().id)
        time += 1
        assertEquals(first.id, m.get().previousId)
    }

    @Test
    fun `resumes a stored session across process restarts until it expires`() {
        val store = MemoryStore()
        val first = manager(store).apply { onForeground() }.get()
        manager(store).apply { onBackground() }
        time += 60_000L
        assertEquals(first.id, manager(store).get().id)
        time += 15 * 60_000L
        val next = manager(store).get()
        assertNotEquals(first.id, next.id)
        assertEquals(first.id, next.previousId)
    }

    @Test
    fun `ignores a corrupt stored session`() {
        val s = manager(MemoryStore("garbage")).get()
        assertNull(s.previousId)
    }

    @Test
    fun `samples once per session from its id`() {
        assertTrue(RumSessionManager(options, 0.5, MemoryStore(), { time }, { "00000001" + "0".repeat(24) }).get().sampled)
        assertFalse(RumSessionManager(options, 0.5, MemoryStore(), { time }, { "ffffffff" + "0".repeat(24) }).get().sampled)
    }

    @Test
    fun `notifies observers of rotations outside the lock`() {
        val m = manager()
        val events = mutableListOf<String>()
        m.addObserver(
            object : SessionObserver {
                override fun onSessionStarted(
                    newSession: Session,
                    previousSession: Session,
                ) {
                    events +=
                        "start ${newSession.id.takeLast(1)} prev '${previousSession.id.takeLast(1)}' now ${m.getSessionId().takeLast(1)}"
                }

                override fun onSessionEnded(session: Session) {
                    events += "end ${session.id.takeLast(1)}"
                }
            },
        )
        m.get()
        time += 4 * 3_600_000L + 1
        m.get()
        assertEquals(listOf("start 1 prev '' now 1", "end 1", "start 2 prev '1' now 2"), events)
    }
}
