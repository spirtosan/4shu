package com.fshu.next.trail

import android.content.Context
import android.util.Log
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.data.local.entities.TrailPoint
import com.fshu.next.data.local.entities.TrailUploadState
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.util.EcdhHelper
import com.fshu.next.util.Prefs
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T13 Phase 3 Block I — trail upload engine.
 *
 * Reliability (SPEC_T13_PHASE2_SERVER_PERSISTENCE.md §4): persist-first (points are
 * already in Room before any upload), watermark-driven so nothing is lost to process
 * death or the OEM killer, at-least-once with server-side dedup by batch_id, and
 * PRIORITY RESEND on reconnect. The server admin recipient is always present, so its
 * arrival is the authoritative "it landed" signal.
 *
 * Envelope (doc §9a, matched by server.js Block G): per recipient,
 *   convKey  = EcdhHelper.deriveConversationKey(myPriv, recipientPubHex, me, recipientId)
 *   (iv, ct) = EcdhHelper.encryptTrailBatch(convKey, <JSON array of points> bytes)
 * Recipients = every configured admin (guaranteed) + each locally-picked guardian whose
 * public key is known locally. The server stores only for accepted guardians + admins;
 * anything else is dropped server-side, so best-effort guardian fanout is safe.
 */
object TrailUploader {
    private const val TAG = "TrailUploader"
    private const val WM_KEY = "__batch__"          // single main upload watermark row
    private const val BATCH_TRIGGER = 10            // flush when this many points are pending...
    private const val MAX_LATENCY_MS = 5 * 60_000L  // ...or the oldest pending point is this old
    private const val BATCH_MAX = 200               // cap per batch (fast catch-up after an outage)
    private const val INFLIGHT_TIMEOUT_MS = 30_000L // resend a batch whose ack never came

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val flushing = AtomicBoolean(false)
    @Volatile private var inFlightBatchId: String? = null
    @Volatile private var inFlightAt: Long = 0

    data class Recipient(val id: String, val pubHex: String)
    @Volatile private var adminRecipients: List<Recipient> = emptyList()
    @Volatile private var panic = false   // T13 Block J — SOS/PANIC: upload every point immediately

    /** Registered as a WebSocketClient handler — routes the messages the uploader cares about. */
    fun onServerMessage(context: Context, json: JsonObject) {
        when (json.get("type")?.takeIf { !it.isJsonNull }?.asString) {
            "auth-ok" -> parseAdmins(context, json)
            "trail-batch-ack" -> {
                val batchId = json.get("batchId")?.takeIf { !it.isJsonNull }?.asString ?: return
                val seqHi = json.get("seqHi")?.takeIf { !it.isJsonNull }?.asLong ?: return
                if (batchId == inFlightBatchId) {
                    inFlightBatchId = null
                    scope.launch {
                        AppDatabase.getInstance(context).trailDao()
                            .upsertUploadWatermark(TrailUploadState(WM_KEY, seqHi))
                        Log.d(TAG, "ack batch=$batchId seqHi=$seqHi — watermark advanced")
                        doFlush(context, force = true)   // drain remaining backlog immediately
                    }
                }
            }
            "trail-guardian-changed" -> {
                val state = json.get("state")?.takeIf { !it.isJsonNull }?.asString
                val user = json.get("user")?.takeIf { !it.isJsonNull }?.asString?.lowercase()
                val me = Prefs.getUsername(context).lowercase()
                if (state == "accepted" && user == me) {
                    val guardian = json.get("guardian")?.takeIf { !it.isJsonNull }?.asString?.lowercase() ?: return
                    scope.launch { backfillGuardian(context, guardian) }   // §5 re-encrypt-on-grant
                }
            }
        }
    }

    /** Priority drain after (re)connect. */
    fun onConnected(context: Context) {
        if (adminRecipients.isEmpty()) restoreAdmins(context)
        scope.launch { doFlush(context, force = true) }
    }

    /** Periodic nudge (WS heartbeat, ~20s) and after each new fix — sends only when triggers are met. */
    fun tick(context: Context) { scope.launch { doFlush(context, force = false) } }

    /** T13 Block J — PANIC (SOS): force per-point immediate upload until cleared. */
    fun setPanic(on: Boolean) { panic = on }

    /** T13 Block J — last-gasp best-effort flush on shutdown (bounded, synchronous). */
    fun flushBlocking(context: Context, timeoutMs: Long) {
        try { runBlocking { withTimeoutOrNull(timeoutMs) { doFlush(context, force = true) } } }
        catch (e: Exception) { Log.w(TAG, "flushBlocking: ${e.message}") }
    }

    private fun parseAdmins(context: Context, json: JsonObject) {
        val arr = json.get("trailAdmins")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        val list = ArrayList<Recipient>()
        for (el in arr) {
            val o = el.asJsonObject
            val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val pub = o.get("pub")?.takeIf { !it.isJsonNull }?.asString ?: continue
            if (pub.length == 64) list.add(Recipient(id, pub))
        }
        adminRecipients = list
        Prefs.setTrailAdmins(context, arr.toString())   // persist for process-death restarts
        Log.d(TAG, "admin recipients: ${list.map { it.id }}")
    }

    private fun restoreAdmins(context: Context) {
        try {
            val s = Prefs.getTrailAdmins(context)
            if (s.isBlank()) return
            val arr = JSONArray(s)
            val list = ArrayList<Recipient>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id"); val pub = o.optString("pub")
                if (id.isNotEmpty() && pub.length == 64) list.add(Recipient(id, pub))
            }
            adminRecipients = list
        } catch (_: Exception) { }
    }

    private suspend fun recipientsFor(context: Context): List<Recipient> {
        val out = ArrayList<Recipient>(adminRecipients)
        val db = AppDatabase.getInstance(context)
        for (g in Prefs.getTrailGuardians(context)) {
            val pub = db.peerKeyDao().get(g)?.publicKey ?: continue   // skip until we have their key
            if (pub.length == 64) out.add(Recipient(g.lowercase(), pub))
        }
        return out
    }

    private suspend fun doFlush(context: Context, force: Boolean) {
        if (!Prefs.isTrailEnabled(context)) return
        if (!WebSocketClient.isConnected) return
        if (!flushing.compareAndSet(false, true)) return
        try {
            val now = System.currentTimeMillis()
            if (inFlightBatchId != null && now - inFlightAt < INFLIGHT_TIMEOUT_MS) return  // await ack or timeout

            val dao = AppDatabase.getInstance(context).trailDao()
            val wm = dao.getUploadWatermark(WM_KEY)?.lastAckedSeq ?: 0L
            val pending = dao.getSince(wm)
            if (pending.isEmpty()) return

            val oldestAgeMs = now - pending.first().ts
            if (!force && !panic && pending.size < BATCH_TRIGGER && oldestAgeMs < MAX_LATENCY_MS) return

            val recipients = recipientsFor(context)
            if (recipients.isEmpty()) { Log.w(TAG, "no recipients yet — deferring flush"); return }

            val myPriv = Prefs.getEcPrivateKey(context)
            val me = Prefs.getUsername(context).lowercase()
            if (myPriv.isEmpty() || me.isEmpty()) return

            val batch = pending.take(BATCH_MAX)
            val plaintext = buildPointsJson(batch).toByteArray(Charsets.UTF_8)
            val forMaps = ArrayList<Map<String, Any?>>()
            for (r in recipients) {
                try {
                    val convKey = EcdhHelper.deriveConversationKey(myPriv, r.pubHex, me, r.id)
                    val (iv, ct) = EcdhHelper.encryptTrailBatch(convKey, plaintext)
                    forMaps.add(mapOf("g" to r.id, "iv" to iv, "ct" to ct))
                } catch (e: Exception) { Log.w(TAG, "encrypt for ${r.id} failed: ${e.message}") }
            }
            if (forMaps.isEmpty()) return

            val batchId = UUID.randomUUID().toString()
            inFlightBatchId = batchId; inFlightAt = now
            val ok = WebSocketClient.send(mapOf(
                "type" to "trail-batch", "batchId" to batchId,
                "seqLo" to batch.first().seq, "seqHi" to batch.last().seq,
                "tsLo" to batch.minOf { it.ts }, "tsHi" to batch.maxOf { it.ts },
                "for" to forMaps
            ))
            if (!ok) { inFlightBatchId = null; Log.w(TAG, "send failed — will retry on reconnect") }
            else Log.d(TAG, "sent batch=$batchId seq ${batch.first().seq}..${batch.last().seq} to ${forMaps.size} recipients (${batch.size} pts)")
        } finally {
            flushing.set(false)
        }
    }

    /** Re-encrypt-on-grant (§5): send the whole current local window to one new guardian. */
    private suspend fun backfillGuardian(context: Context, guardian: String) {
        if (!WebSocketClient.isConnected) return
        val db = AppDatabase.getInstance(context)
        val pub = db.peerKeyDao().get(guardian)?.publicKey ?: return
        if (pub.length != 64) return
        val all = db.trailDao().getSince(0L)
        if (all.isEmpty()) return
        val myPriv = Prefs.getEcPrivateKey(context); val me = Prefs.getUsername(context).lowercase()
        if (myPriv.isEmpty()) return
        var offset = 0
        while (offset < all.size) {
            val batch = all.subList(offset, minOf(offset + BATCH_MAX, all.size))
            val plaintext = buildPointsJson(batch).toByteArray(Charsets.UTF_8)
            val convKey = EcdhHelper.deriveConversationKey(myPriv, pub, me, guardian)
            val (iv, ct) = EcdhHelper.encryptTrailBatch(convKey, plaintext)
            WebSocketClient.send(mapOf(
                "type" to "trail-batch", "batchId" to UUID.randomUUID().toString(),
                "seqLo" to batch.first().seq, "seqHi" to batch.last().seq,
                "tsLo" to batch.minOf { it.ts }, "tsHi" to batch.maxOf { it.ts },
                "for" to listOf(mapOf("g" to guardian, "iv" to iv, "ct" to ct))
            ))
            offset += BATCH_MAX
        }
        Log.d(TAG, "backfilled ${all.size} pts to new guardian $guardian")
    }

    /** JSON array of points, one TrailPointCodec object each (Gson omits nulls; keeps susp). */
    private fun buildPointsJson(batch: List<TrailPoint>): String {
        val sb = StringBuilder("[")
        for ((i, p) in batch.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(TrailPointCodec.toJson(p.toData()))
        }
        sb.append(']')
        return sb.toString()
    }
}
