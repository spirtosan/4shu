package com.fshu.next.trail

import android.content.Context
import android.util.Log
import com.fshu.next.util.Prefs
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Chunk 4 — tracked-side trail access log.
 *
 * The server pushes {type:"trail-accessed", by, fromTs, toTs, ts} to the tracked person on
 * every guardian/admin fetch (queued while offline, delivered on reconnect). There is no
 * server history endpoint, so we accumulate these events locally into a capped JSON list in
 * [Prefs]. Registered app-wide in FshuService.
 */
object AccessLogStore {
    private const val TAG = "AccessLogStore"
    private const val CAP = 200
    private val gson = Gson()

    data class Entry(val by: String, val at: Long, val fromTs: Long, val toTs: Long)

    fun onServerMessage(context: Context, json: JsonObject) {
        if (json.get("type")?.asString != "trail-accessed") return
        val by = json.get("by")?.asString ?: return
        val at = json.get("ts")?.asLong ?: System.currentTimeMillis()
        val fromTs = json.get("fromTs")?.asLong ?: 0L
        val toTs = json.get("toTs")?.asLong ?: 0L
        val list = getAll(context).toMutableList()
        list.add(Entry(by, at, fromTs, toTs))
        while (list.size > CAP) list.removeAt(0)
        Prefs.setTrailAccessLogJson(context, gson.toJson(list))
        Log.d(TAG, "trail-accessed by $by (total ${list.size})")
    }

    /** Newest first for display. */
    fun getAll(context: Context): List<Entry> = try {
        gson.fromJson(Prefs.getTrailAccessLogJson(context), Array<Entry>::class.java)?.toList() ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun getAllNewestFirst(context: Context): List<Entry> = getAll(context).sortedByDescending { it.at }

    fun clear(context: Context) = Prefs.setTrailAccessLogJson(context, "[]")
}
