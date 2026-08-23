package com.fshu.next.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SPEC_T13_GLITCH_FILTER.md §5. Coordinates are the real exported glitches from
 * fshu_export_ivan_* (2026-08-23 and 2026-07-30), so the thresholds are validated against
 * the actual data, not synthetic numbers. ts values are epoch-ms; only the DELTA matters.
 */
class TrailFixQualityTest {

    private fun t(h: Int, m: Int, s: Int): Long = (h * 3600L + m * 60L + s) * 1000L

    @Test
    fun `first ever fix has no baseline and is never flagged`() {
        assertNull(TrailFixQuality.classify(null, null, null, 42.12, 24.73, t(8, 0, 0), 500.0))
    }

    @Test
    fun `zero or negative dt is not a divide-by-zero and is not flagged`() {
        assertNull(TrailFixQuality.classify(42.12, 24.73, t(8, 0, 0), 42.60, 24.90, t(8, 0, 0), 800.0))
    }

    @Test
    fun `Aug23 teleport is flagged, its snap-back measured against last good fix is clean`() {
        // baseline: accurate fix near Voynyagovo
        val g = Triple(42.5997, 24.7664, t(8, 3, 0))
        // B: the 183 km/h jump, coarse -> flagged
        assertEquals("jump",
            TrailFixQuality.classify(g.first, g.second, g.third, 42.6308, 24.8243, t(8, 9, 13), 700.0))
        // C: snap-back, still measured against the SAME good baseline g (B did not advance it)
        // -> tiny implied speed -> clean, proving no double-flag.
        assertNull(
            TrailFixQuality.classify(g.first, g.second, g.third, 42.5475, 24.8265, t(8, 12, 17), 300.0))
    }

    @Test
    fun `Jul30 255kmh teleport is flagged`() {
        assertEquals("jump",
            TrailFixQuality.classify(42.6420, 24.8010, t(6, 57, 28), 42.5267, 24.8139, t(7, 0, 29), 400.0))
    }

    @Test
    fun `genuine fast but accurate highway fix is not flagged`() {
        // ~174 km/h implied but accuracy 15 m -> the AND-accuracy term protects it.
        assertNull(
            TrailFixQuality.classify(42.10, 24.70, t(9, 0, 0), 42.60, 24.90, t(9, 20, 0), 15.0))
    }

    @Test
    fun `impossible speed but good accuracy is not flagged (jump needs both)`() {
        assertNull(
            TrailFixQuality.classify(42.10, 24.70, t(9, 0, 0), 43.50, 24.90, t(9, 5, 0), 20.0))
    }

    @Test
    fun `slow move with poor accuracy is not flagged by default`() {
        // Poor accuracy alone must NOT flag (FLAG_POOR_ACC is off) -- legit STILL heartbeat.
        assertNull(
            TrailFixQuality.classify(42.12, 24.73, t(8, 0, 0), 42.121, 24.731, t(8, 10, 0), 600.0))
    }
}
