package com.fshu.next.trail

import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * T13 Block K — GPX + JSON export of an assembled guardian trail (police handoff).
 *
 * Pure string builders, no Android dependencies, so the serialization can be reasoned
 * about and unit-checked directly. The Downloads write (MediaStore) belongs to the
 * caller Activity — keeping it out of here is what makes this layer safe to ship
 * without a device build.
 */
object TrailExport {
    private val gson = Gson()   // omits null fields, matching the wire convention

    private fun iso(ts: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ts))
    }

    private fun xml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** GPX 1.1 track: one <trkpt> per point that has coordinates, in timestamp order. */
    fun toGpx(points: List<GuardianTrail.DevicePoint>, trackName: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"4shu\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n    <name>").append(xml(trackName)).append("</name>\n    <trkseg>\n")
        for (dp in points) {
            val p = dp.data
            val lat = p.lat ?: continue
            val lon = p.lon ?: continue
            sb.append("      <trkpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\">\n")
            if (p.alt != null) sb.append("        <ele>").append(p.alt).append("</ele>\n")
            sb.append("        <time>").append(iso(p.ts)).append("</time>\n")
            if (p.spd != null) sb.append("        <speed>").append(p.spd).append("</speed>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Full JSON export: every decrypted point tagged with its source device. */
    fun toJson(assembled: GuardianTrail.Assembled, trackedUser: String): String {
        val export = linkedMapOf<String, Any?>(
            "exportedAt" to iso(System.currentTimeMillis()),
            "user" to trackedUser,
            "decryptedBatches" to assembled.decryptedBatches,
            "failedBatches" to assembled.failedBatches,
            "lastKnownFix" to assembled.lastKnownFix,
            "points" to assembled.points.map { linkedMapOf("device" to it.device, "point" to it.data) },
        )
        return gson.toJson(export)
    }

    /** Suggested filename stem, e.g. "trail_kid_20260827_1530". Caller adds .gpx / .json. */
    fun fileStem(trackedUser: String, now: Long = System.currentTimeMillis()): String {
        val f = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
        return "trail_${trackedUser}_${f.format(Date(now))}"
    }
}
