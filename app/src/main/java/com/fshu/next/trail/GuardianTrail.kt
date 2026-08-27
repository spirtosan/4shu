package com.fshu.next.trail

import com.fshu.next.util.EcdhHelper
import com.google.gson.Gson

/**
 * T13 Block K — guardian-side trail assembly (SAFE LOGIC LAYER, no Android UI).
 *
 * Given the ciphertext batches the server returns in a `trail-data` message, this
 * decrypts, parses, merges across the tracked person's devices, dedupes, sorts by time,
 * and surfaces the two things a guardian needs first: the LAST KNOWN POSITION and the
 * MOST RECENT SEGMENT of the path.
 *
 * Envelope (matches [EcdhHelper.encryptTrailBatch] + TrailUploader fanout, SPEC_T13
 * Phase-2 §9a): per (tracked person -> guardian), the tracked device encrypted each
 * batch with `deriveConversationKey(trackedPriv, guardianPub, trackedName, guardianName)`.
 * X25519 and the sorted-username salt are both symmetric, so the guardian reproduces the
 * identical key with its own private key and the tracked person's public key. The batch
 * plaintext is a JSON array of [TrailPointData] (see TrailUploader.buildPointsJson).
 *
 * Pure computation — call it off the main thread; it does no I/O and no Android calls.
 */
object GuardianTrail {
    private val gson = Gson()

    /** One ciphertext batch as delivered by the server `trail-data` message. */
    data class Batch(
        val device: String,
        val seqLo: Long?, val seqHi: Long?,
        val tsLo: Long?, val tsHi: Long?,
        val serverTs: Long?,
        val iv: String, val ct: String,
    )

    /** A decrypted point tagged with the device it came from (seq is per-device). */
    data class DevicePoint(val device: String, val data: TrailPointData)

    data class Assembled(
        /** Merged across devices, deduped by (device, seq), sorted by ts ascending. */
        val points: List<DevicePoint>,
        /** Most recent point carrying coordinates (a fix, or an event's `last` snapshot). */
        val lastKnownFix: TrailPointData?,
        /** Tail fixes within recentWindowMs of the last fix — the "most recent segment". */
        val recentSegment: List<TrailPointData>,
        val decryptedBatches: Int,
        val failedBatches: Int,
    ) {
        val totalPoints: Int get() = points.size
    }

    fun assemble(
        myPrivHex: String,
        myUsername: String,
        trackedUsername: String,
        trackedPubHex: String,
        batches: List<Batch>,
        recentWindowMs: Long = 30 * 60_000L,
    ): Assembled {
        val convKey = EcdhHelper.deriveConversationKey(
            myPrivHex, trackedPubHex, myUsername.lowercase(), trackedUsername.lowercase()
        )

        val seen = HashSet<String>()               // "device#seq" — absorbs overlapping/refetched batches
        val merged = ArrayList<DevicePoint>()
        var ok = 0
        var failed = 0

        for (b in batches) {
            val bytes = EcdhHelper.decryptTrailBatch(convKey, b.iv, b.ct)
            if (bytes == null) { failed++; continue }
            val arr = try {
                gson.fromJson(String(bytes, Charsets.UTF_8), Array<TrailPointData>::class.java)
            } catch (e: Exception) { null }
            if (arr == null) { failed++; continue }
            ok++
            for (pt in arr) {
                if (seen.add("${b.device}#${pt.seq}")) merged.add(DevicePoint(b.device, pt))
            }
        }
        merged.sortBy { it.data.ts }

        // Last known position: newest point that actually carries coordinates. A fix has
        // lat/lon directly; an event (e.g. shutdown) may carry the last fix in `last`.
        val lastKnownFix: TrailPointData? = run {
            for (i in merged.indices.reversed()) {
                val d = merged[i].data
                if (d.lat != null && d.lon != null) return@run d
                val last = d.last
                if (last != null) return@run TrailPointData(
                    seq = d.seq, kind = TrailPointKind.FIX, ts = last.ts,
                    lat = last.lat, lon = last.lon, acc = last.acc
                )
            }
            null
        }

        val recentSegment: List<TrailPointData> = if (lastKnownFix == null) emptyList() else {
            val cutoff = lastKnownFix.ts - recentWindowMs
            merged.asSequence()
                .map { it.data }
                .filter { it.lat != null && it.lon != null && it.ts >= cutoff }
                .toList()
        }

        return Assembled(merged, lastKnownFix, recentSegment, ok, failed)
    }
}
