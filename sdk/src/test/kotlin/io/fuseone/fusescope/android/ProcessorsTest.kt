package io.fuseone.fusescope.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessorsTest {
    @Test
    fun `rates app starts against the Android vitals thresholds`() {
        val cases =
            listOf(
                Triple("cold", 2_499.0, "good"),
                Triple("cold", 2_500.0, "needs-improvement"),
                Triple("cold", 5_000.0, "poor"),
                Triple("warm", 999.0, "good"),
                Triple("warm", 2_000.0, "poor"),
                Triple("hot", 750.0, "needs-improvement"),
                Triple(null, 6_000.0, "poor"),
            )
        for ((type, ms, want) in cases) assertEquals("$type $ms", want, appStartRating(type, ms))
    }

    @Test
    fun `a cold start counts from process start when it is plausible`() {
        val ms = 1_000_000L
        assertEquals(800.0, appStartMillis("cold", 500 * ms, 900 * ms, 100 * ms), 0.0)
        assertEquals(400.0, appStartMillis("warm", 500 * ms, 900 * ms, 100 * ms), 0.0)
        assertEquals(400.0, appStartMillis("cold", 500 * ms, 900 * ms, null), 0.0)
        assertEquals(400.0, appStartMillis("cold", 500 * ms, 900 * ms, 600 * ms), 0.0)
        assertEquals(60_500.0, appStartMillis("cold", 500 * ms, 61_000 * ms, 0L), 0.0)
    }
}
