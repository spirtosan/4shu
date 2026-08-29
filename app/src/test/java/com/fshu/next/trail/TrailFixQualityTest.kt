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

    // --- "detour" rule (SPEC_T13_GLITCH_FILTER.md §detour). Coordinates are the real
    // decrypted Aug-29 trail (seq numbers noted); validated 2026-08-29. ---

    @Test
    fun `Asenovgradsko coarse there-and-back is flagged detour (seq 460)`() {
        assertEquals("detour", TrailFixQuality.classifyDetour(
            42.1280176, 24.7180593,                     // prev (seq 459, 24 m)
            42.120191, 24.7569775, 98.4, "moving",      // the 98 m sideways spike
            42.1314883, 24.6953185))                    // next (seq 461, 30 m)
    }

    @Test
    fun `short coarse out-and-back is flagged detour (seq 475)`() {
        assertEquals("detour", TrailFixQuality.classifyDetour(
            42.4863957, 24.8074094,
            42.4830797, 24.8065719, 92.9, "moving",
            42.4863957, 24.8074094))
    }

    @Test
    fun `far coarse spike is flagged detour (seq 504)`() {
        assertEquals("detour", TrailFixQuality.classifyDetour(
            42.5715802, 24.7582542,
            42.600532, 24.7648981, 400.0, "moving",
            42.5725356, 24.7588525))
    }

    @Test
    fun `coarse moving spike between two still fixes is flagged detour (seq 526)`() {
        assertEquals("detour", TrailFixQuality.classifyDetour(
            42.1187466, 24.7349809,
            42.1216862, 24.7341426, 300.0, "moving",
            42.1187654, 24.7350573))
    }

    @Test
    fun `coarse but on-path fix is not a detour (seq 462)`() {
        // 500 m accuracy, but it sits along the northbound route -- neighbours are NOT
        // closer to each other than to it, so the there-and-back test fails. Kept clean.
        assertNull(TrailFixQuality.classifyDetour(
            42.1314883, 24.6953185,
            42.1338443, 24.6784834, 500.0, "moving",
            42.1640944, 24.6761348))
    }

    @Test
    fun `tight accurate moving fix is never a detour`() {
        // 18 m accuracy -> below DETOUR_ACC_MIN_M, cannot be a detour whatever the geometry.
        assertNull(TrailFixQuality.classifyDetour(
            42.1199301, 24.7326805,
            42.1199234, 24.7326049, 18.34, "moving",
            42.1199307, 24.7326733))
    }

    @Test
    fun `coarse STILL heartbeat is never a detour`() {
        // mot=still: a legitimately coarse stationary heartbeat must not be flagged --
        // this is exactly the false-positive that keeps FLAG_POOR_ACC off.
        assertNull(TrailFixQuality.classifyDetour(
            42.1192888, 24.7319164,
            42.119951, 24.7324649, 100.0, "still",
            42.1187, 24.732))
    }

    @Test
    fun `detour needs the next fix - unknown successor is not yet flagged`() {
        // Online/streaming moment: the successor has not arrived. Never flag prematurely.
        assertNull(TrailFixQuality.classifyDetour(
            42.1280176, 24.7180593,
            42.120191, 24.7569775, 98.4, "moving",
            null, null))
    }

    @Test
    fun `detour needs a predecessor - first fix is never flagged`() {
        assertNull(TrailFixQuality.classifyDetour(
            null, null,
            42.120191, 24.7569775, 98.4, "moving",
            42.1314883, 24.6953185))
    }
}
