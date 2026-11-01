package io.fuseone.fusescope.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NativeExitRecoveryTest {
    private val snapshot = NativeExitContext("app-native", "a".repeat(32), true, release = "1.0")
    private val exit = NativeExit(1000, 17, 6, "fsn1:" + "1".repeat(32))

    @Test
    fun `reports the previous session and release once and retries failed exports`() {
        val store = MemoryNativeHistory(snapshot)
        val reports = mutableListOf<RecoveredNativeCrash>()
        var accepted = false
        val recovery =
            NativeExitRecovery("app-native", store) {
                reports += it
                accepted
            }
        recovery.recover(listOf(exit))
        assertEquals(1, reports.size)
        assertEquals("1.0", reports.single().context.release)
        assertEquals("a".repeat(32), reports.single().context.sessionId)
        accepted = true
        recovery.recover(listOf(exit))
        recovery.recover(listOf(exit))
        assertEquals(2, reports.size)
    }

    @Test
    fun `skips exits without a consented initialized sampled context for the same application`() {
        val reports = mutableListOf<RecoveredNativeCrash>()
        for (context in listOf(null, snapshot.copy(sampled = false), snapshot.copy(applicationId = "other-app"))) {
            NativeExitRecovery("app-native", MemoryNativeHistory(context)) {
                reports += it
                true
            }.recover(listOf(exit))
        }
        NativeExitRecovery("app-native", MemoryNativeHistory(snapshot)) {
                reports += it
                true
            }
            .recover(listOf(exit.copy(summary = null)))
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `missing or inaccessible traces still report exit metadata without inventing frames`() {
        val reports = mutableListOf<RecoveredNativeCrash>()
        val recovery =
            NativeExitRecovery("app-native", MemoryNativeHistory(snapshot)) {
                reports += it
                true
            }
        recovery.recover(listOf(exit.copy(trace = { throw IOException("trace unavailable") })))
        assertEquals(1, reports.size)
        assertEquals(6, reports.single().exit.status)
        assertEquals(null, reports.single().tombstone)
    }

    private class MemoryNativeHistory(
        private val snapshot: NativeExitContext?,
    ) : NativeHistoryStore {
        private val reported = mutableSetOf<String>()

        override fun context(summary: String): NativeExitContext? = snapshot

        override fun reported(id: String): Boolean = id in reported

        override fun markReported(id: String) {
            reported += id
        }
    }
}
