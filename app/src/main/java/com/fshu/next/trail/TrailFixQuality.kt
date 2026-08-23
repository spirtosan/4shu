package com.fshu.next.trail

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SPEC_T13_GLITCH_FILTER.md — pure, Android-free classifier for physically-implausible
 * location fixes (the recurring cell-triangulation "teleport" glitch on the
 * Plovdiv→Karlovo corridor: a coarse fused/cell fix jumps hundreds of metres to
 * kilometres and snaps back on the next fix).
 *
 * Deliberately has NO dependency on android.location.Location so it is unit-testable on
 * the plain JVM (see TrailFixQualityTest). [TrailService.recordFix] passes raw doubles.
 *
 * Returns the reason code to store in [TrailPointData.susp], or null when the fix looks
 * clean:
 *   "jump" — implied speed since the last GOOD fix exceeds [SPEED_SUSPECT_KMH] AND the
 *            fix's own accuracy is worse than [ACC_SUSPECT_M]. Requiring BOTH keeps a
 *            genuine fast-but-accurate highway fix from being flagged; it is also the
 *            exact signature of the observed glitch (coarse cell fix + impossible jump).
 *   "acc"  — accuracy alone worse than [ACC_SUSPECT_M]. OFF by default ([FLAG_POOR_ACC])
 *            because the STILL network heartbeat legitimately produces coarse fixes that
 *            are not glitches; flip it on only if you want every coarse fix marked too.
 *
 * IMPORTANT: the caller MUST pass the last NON-SUSPECT ("good") fix as the baseline
 * (prev*), not merely the last persisted fix. Measuring against the last persisted fix
 * would double-flag — the teleport AND the good fix that snaps back next.
 */
object TrailFixQuality {

    /** Locked with Ivan 2026-08-23: ~max highway speed here; clears BG motorway limit 140. */
    const val SPEED_SUSPECT_KMH = 150.0

    /** Good fixes on this route are <=100 m; every observed glitch was 300–800 m. */
    const val ACC_SUSPECT_M = 250.0

    /** Emit a standalone "acc" flag for coarse fixes regardless of speed. Off by default:
     *  it would also flag legitimate coarse STILL network-heartbeat fixes. */
    const val FLAG_POOR_ACC = false

    private const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * @param prevLat/prevLon/prevTs the last GOOD (non-suspect) fix, or null if none yet.
     * @param acc this fix's reported accuracy in metres, or null if unavailable.
     * @return "jump" | "acc" | null.
     */
    fun classify(
        prevLat: Double?, prevLon: Double?, prevTs: Long?,
        lat: Double, lon: Double, ts: Long, acc: Double?
    ): String? {
        val poorAcc = acc != null && acc > ACC_SUSPECT_M

        if (prevLat != null && prevLon != null && prevTs != null) {
            val dtMs = ts - prevTs
            if (dtMs > 0L) {
                val meters = haversineMeters(prevLat, prevLon, lat, lon)
                val speedKmh = meters * 3600.0 / dtMs   // m/ms -> km/h
                if (speedKmh > SPEED_SUSPECT_KMH && poorAcc) return "jump"
            }
        }

        if (FLAG_POOR_ACC && poorAcc) return "acc"
        return null
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
