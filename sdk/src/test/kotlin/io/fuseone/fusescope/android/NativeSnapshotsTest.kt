package io.fuseone.fusescope.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NativeSnapshotsTest {
    @Test
    fun `persists original context and successful acknowledgements across instances`() {
        val app = RuntimeEnvironment.getApplication()
        val token = "fsn1:${"a".repeat(32)}"
        val context = NativeExitContext("app", "b".repeat(32), true, "old-release", environment = "old-environment")
        val first = NativeSnapshots(app, "persist-test")
        assertTrue(first.save(token, context))
        first.markReported("1700000000000:42")
        val next = NativeSnapshots(app, "persist-test")
        assertEquals(context, next.context(token))
        assertTrue(next.reported("1700000000000:42"))
        assertFalse(next.reported("1700000000001:42"))
        assertNull(NativeSnapshots(app, "another-process").context(token))
        assertNull(next.context("unexpected-summary"))
    }

    @Test
    fun `keeps nullable resource fields absent and bounds snapshot retention`() {
        val store = NativeSnapshots(RuntimeEnvironment.getApplication(), "retention-test")
        val context = NativeExitContext("app", "c".repeat(32), false)
        val tokens = (0..20).map { "fsn1:${it.toString(16).padStart(32, '0')}" }
        for (token in tokens) assertTrue(store.save(token, context))
        assertEquals(context, store.context(tokens.last()))
        assertTrue(tokens.count { store.context(it) != null } <= 16)
        repeat(150) { store.markReported("${1700000000000L + it}:42") }
        assertFalse(store.reported("1700000000000:42"))
        assertTrue(store.reported("1700000000149:42"))
    }
}
