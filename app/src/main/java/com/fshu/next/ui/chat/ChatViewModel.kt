package com.fshu.next.ui.chat

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.data.model.Message
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.util.CryptoHelper
import com.fshu.next.util.EcdhHelper
import com.fshu.next.util.LocationHelper
import com.fshu.next.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val me = Prefs.getUsername(app)

    /** Pending debounce jobs keyed by (listId, itemId) — cancelled on rapid taps. */
    private val pendingChecks = ConcurrentHashMap<Pair<String, String>, Job>()

    fun getMessages(peer: String): LiveData<List<Message>> =
        db.messageDao().getConversation(me, peer)

    fun getGroupMessages(groupId: String): LiveData<List<Message>> =
        db.messageDao().getGroupMessages(groupId)

    fun sendGroupText(groupId: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = db.groupDao().getById(groupId) ?: return@launch
            val groupKeyHex = group.groupKey
            val tempId = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis()
            val wireContent = if (groupKeyHex.isNotEmpty()) {
                val key = with(EcdhHelper) { groupKeyHex.fromHex() }
                val (encrypted, _) = CryptoHelper.encryptGroupMessage(key, content)
                encrypted
            } else content
            db.messageDao().insert(
                Message(from = me, to = "", content = content, type = "text",
                    timestamp = ts, isSent = true, status = "SENDING",
                    tempId = tempId, groupId = groupId)
            )
            WebSocketClient.send(mapOf(
                "type" to "group-message", "from" to me, "groupId" to groupId,
                "content" to wireContent, "messageId" to tempId, "timestamp" to ts
            ))
        }
    }

    fun getMe(): String = me

    fun sendText(
        peer: String,
        content: String,
        replyToId: Long? = null,
        replyToSender: String? = null,
        replyToContent: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val ts = System.currentTimeMillis()
            val id = db.messageDao().insert(
                Message(from = me, to = peer, content = content, type = "text",
                    timestamp = ts, isSent = true, status = "SENDING",
                    replyToId = replyToId, replyToSender = replyToSender,
                    replyToContent = replyToContent)
            )
            val peerPubKey  = Prefs.getPeerPublicKey(getApplication(), peer)
            val wireContent = if (peerPubKey.isNotEmpty())
                CryptoHelper.encryptForPeer(getApplication(), peer, peerPubKey, id, content)
            else content
            val payload = mutableMapOf<String, Any>(
                "type" to "message", "from" to me, "to" to peer,
                "content" to wireContent, "messageId" to id,
                "timestamp" to ts
            )
            if (replyToId != null) {
                payload["replyToId"] = replyToId
                payload["replyToSender"] = replyToSender ?: ""
                payload["replyToContent"] = replyToContent ?: ""
            }
            WebSocketClient.send(payload)
        }
    }

    fun sendVoice(peer: String, file: java.io.File, waveform: FloatArray, durationSecs: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileBytes = file.readBytes()
                val mimeType = "audio/mp4"
                val filename = "voice_${System.currentTimeMillis()}.m4a"
                val peerPubKey = Prefs.getPeerPublicKey(getApplication(), peer)
                val tempId = UUID.randomUUID().toString()
                val ts = System.currentTimeMillis()
                val waveformJson = "[${waveform.joinToString(",") { "%.2f".format(it) }}]"

                val roomId = db.messageDao().insert(
                    Message(from = me, to = peer,
                        content = "🎤 Voice message", type = "voice",
                        filename = filename, mimeType = mimeType,
                        localUri = file.toURI().toString(), tempId = tempId,
                        timestamp = ts, isSent = true, status = "SENDING",
                        voiceDuration = durationSecs, voiceWaveform = waveformJson)
                )

                val (encBytes, nonce) = if (peerPubKey.isNotEmpty())
                    CryptoHelper.encryptFileForPeer(getApplication(), peer, peerPubKey, fileBytes)
                        ?: Pair(fileBytes, ByteArray(12).also { java.security.SecureRandom().nextBytes(it) })
                else
                    Pair(fileBytes, ByteArray(12).also { java.security.SecureRandom().nextBytes(it) })

                val nonceHex = CryptoHelper.bytesToHex(nonce)
                val header = JsonObject().apply {
                    addProperty("tempId", tempId)
                    addProperty("from", me)
                    addProperty("to", peer)
                    addProperty("filename", filename)
                    addProperty("mimeType", mimeType)
                    addProperty("size", encBytes.size)
                    addProperty("nonce", nonceHex)
                    addProperty("type", "voice")
                    addProperty("duration", durationSecs)
                    addProperty("waveform", waveformJson)
                    addProperty("messageId", roomId)
                    addProperty("timestamp", ts)
                }
                val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
                val sink = okio.Buffer()
                sink.writeInt(headerBytes.size)
                sink.write(headerBytes)
                sink.write(encBytes)
                WebSocketClient.sendBinary(sink.readByteString())
            } catch (e: Exception) {
                Log.e("ChatViewModel", "sendVoice failed", e)
            }
        }
    }

    fun sendFile(peer: String, uri: Uri, resolver: ContentResolver) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileBytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val filename = resolveDisplayName(uri, resolver)
                val mimeType = resolver.getType(uri) ?: "application/octet-stream"
                val peerPubKey = Prefs.getPeerPublicKey(getApplication(), peer)
                val tempId = UUID.randomUUID().toString()
                val ts = System.currentTimeMillis()

                val roomId = db.messageDao().insert(
                    Message(from = me, to = peer,
                        content = "\uD83D\uDCCE $filename", type = "file",
                        filename = filename, mimeType = mimeType,
                        localUri = uri.toString(), tempId = tempId,
                        timestamp = ts, isSent = true, status = "SENDING")
                )

                val (encBytes, nonce) = if (peerPubKey.isNotEmpty())
                    CryptoHelper.encryptFileForPeer(getApplication(), peer, peerPubKey, fileBytes)
                        ?: Pair(fileBytes, ByteArray(12).also { java.security.SecureRandom().nextBytes(it) })
                else
                    Pair(fileBytes, ByteArray(12).also { java.security.SecureRandom().nextBytes(it) })

                val nonceHex = CryptoHelper.bytesToHex(nonce)
                val header = JsonObject().apply {
                    addProperty("tempId", tempId)
                    addProperty("from", me)
                    addProperty("to", peer)
                    addProperty("filename", filename)
                    addProperty("mimeType", mimeType)
                    addProperty("size", encBytes.size)
                    addProperty("nonce", nonceHex)
                    addProperty("type", "file")
                    addProperty("messageId", roomId)
                    addProperty("timestamp", ts)
                }
                val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
                val sink = okio.Buffer()
                sink.writeInt(headerBytes.size)
                sink.write(headerBytes)
                sink.write(encBytes)
                WebSocketClient.sendBinary(sink.readByteString())
            } catch (e: Exception) {
                Log.e("ChatViewModel", "sendFile failed", e)
            }
        }
    }

    private fun resolveDisplayName(uri: Uri, resolver: ContentResolver): String {
        if (uri.scheme == "content") {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    /**
     * Creates a new todo list. [items] is a list of (itemId UUID, text) pairs.
     * New UUIDs should already be assigned by the caller (ChatActivity).
     */
    fun createList(peer: String, items: List<Pair<String, String>>) {
        viewModelScope.launch(Dispatchers.IO) {
            val listId = UUID.randomUUID().toString()
            val arr = JsonArray()
            items.forEach { (id, text) ->
                val obj = JsonObject()
                obj.addProperty("id", id)
                obj.addProperty("text", text)
                obj.addProperty("done", false)
                arr.add(obj)
            }
            val plainJson = arr.toString()
            val ts = System.currentTimeMillis()
            val msgId = db.messageDao().insert(
                Message(from = me, to = peer, content = plainJson, type = "list",
                    listId = listId, timestamp = ts, isSent = true, status = "SENDING",
                    listOwner = me)
            )
            WebSocketClient.send(mapOf(
                "type" to "list-create", "from" to me, "to" to peer,
                "listId" to listId, "items" to arr,
                "messageId" to msgId, "timestamp" to ts
            ))
        }
    }

    /**
     * Replaces a list's items with [items] — a list of (itemId UUID, text) pairs.
     * [deletedIds] — UUIDs of existing items the user removed via × button.
     * Existing items preserve their done/checkedBy state; deleted items are soft-deleted on server.
     */
    fun editList(msg: Message, items: List<Pair<String, String>>, deletedIds: Set<String>, peer: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val listId = msg.listId ?: return@launch
            // Preserve existing done/checkedBy state for items still present
            val existing: Map<String, JsonObject> = try {
                val arr = JsonParser.parseString(msg.content).asJsonArray
                buildMap {
                    for (i in 0 until arr.size()) {
                        val o = arr[i].asJsonObject
                        val id = o.get("id")?.asString ?: continue
                        put(id, o)
                    }
                }
            } catch (e: Exception) { emptyMap() }

            val wireArr = JsonArray()
            // Active items
            items.forEach { (id, text) ->
                val obj = JsonObject()
                obj.addProperty("id", id)
                obj.addProperty("text", text)
                val prev = existing[id]
                obj.addProperty("done", prev?.get("done")?.asBoolean ?: false)
                obj.addProperty("deleted", false)
                wireArr.add(obj)
            }
            // Soft-deleted items — server sets deletedAt
            deletedIds.forEach { id ->
                val obj = JsonObject()
                obj.addProperty("id", id)
                obj.addProperty("text", "")
                obj.addProperty("deleted", true)
                wireArr.add(obj)
            }
            // Local DB stores only active items (same as list-state received from server)
            val localArr = JsonArray()
            items.forEach { (id, text) ->
                val obj = JsonObject()
                obj.addProperty("id", id)
                obj.addProperty("text", text)
                val prev = existing[id]
                obj.addProperty("done", prev?.get("done")?.asBoolean ?: false)
                localArr.add(obj)
            }
            db.messageDao().updateContent(msg.id, localArr.toString())
            WebSocketClient.send(mapOf(
                "type" to "list-edit", "from" to me, "to" to peer,
                "listId" to listId, "items" to wireArr,
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }

    /**
     * Toggles a list item by [itemId] UUID.
     * Optimistically updates local DB immediately for instant UI feedback.
     * Debounces the network send by 800 ms — rapid taps on the same item
     * cancel the previous pending send and only dispatch one list-check.
     */
    fun checkItem(listId: String, itemId: String, done: Boolean, peer: String) {
        val key = Pair(listId, itemId)

        // Optimistic DB update — run immediately, no debounce on the UI side.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val msg = db.messageDao().getByListId(listId) ?: return@launch
                val arr = JsonParser.parseString(msg.content).asJsonArray
                val updated = JsonArray()
                val now = System.currentTimeMillis()
                for (i in 0 until arr.size()) {
                    val item = arr[i].asJsonObject
                    if (item.get("id")?.asString == itemId) {
                        val obj = JsonObject()
                        for ((k, v) in item.entrySet()) obj.add(k, v)
                        obj.addProperty("done", done)
                        obj.addProperty("checkedBy", me)
                        obj.addProperty("checkedAt", now)
                        updated.add(obj)
                    } else {
                        updated.add(item)
                    }
                }
                db.messageDao().updateContent(msg.id, updated.toString())
            } catch (e: Exception) {
                Log.e("ChatViewModel", "checkItem optimistic update failed", e)
            }
        }

        // Debounced network send — cancel any previous pending send for this item.
        pendingChecks[key]?.cancel()
        val job = viewModelScope.launch(Dispatchers.IO) {
            delay(800)
            try {
                WebSocketClient.send(mapOf(
                    "type" to "list-check", "from" to me, "to" to peer,
                    "listId" to listId, "itemId" to itemId, "done" to done,
                    "ts" to System.currentTimeMillis()
                ))
            } finally {
                pendingChecks.remove(key)
            }
        }
        pendingChecks[key] = job
    }

    fun sendCurrentLocation(peer: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val location = LocationHelper.getCurrentLocation(getApplication())
            if (location == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), getApplication<android.app.Application>().getString(com.fshu.next.R.string.toast_location_error), Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val mapsUrl = LocationHelper.buildMapsUrl(location.latitude, location.longitude)
            val ts = System.currentTimeMillis()
            val contentJson = """{"lat":${location.latitude},"lon":${location.longitude},"accuracy":${location.accuracy},"timestamp":$ts,"mapsUrl":"$mapsUrl"}"""
            val id = db.messageDao().insert(
                Message(from = me, to = peer, content = contentJson, type = "location",
                    timestamp = ts, isSent = true, status = "SENDING")
            )
            val locKey = CryptoHelper.getKey(getApplication(), peer)
            val wireContent = if (locKey != null) CryptoHelper.encrypt(locKey, id, ts, contentJson) else contentJson
            WebSocketClient.send(mapOf(
                "type" to "location-response", "from" to me, "to" to peer,
                "content" to wireContent, "messageId" to id, "timestamp" to ts
            ))
        }
    }

    fun sendLocationRequest(peer: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val requestId = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis()
            val contentJson = """{"requestId":"$requestId"}"""
            val id = db.messageDao().insert(
                Message(from = me, to = peer, content = contentJson, type = "location-request",
                    timestamp = ts, isSent = true, status = "SENDING")
            )
            val wirePayload = """{"requestId":"$requestId","timestamp":$ts}"""
            val reqKey = CryptoHelper.getKey(getApplication(), peer)
            val wireContent = if (reqKey != null) CryptoHelper.encrypt(reqKey, id, ts, wirePayload) else wirePayload
            WebSocketClient.send(mapOf(
                "type" to "location-request", "from" to me, "to" to peer,
                "content" to wireContent, "messageId" to id, "timestamp" to ts
            ))
        }
    }

    fun respondToLocationRequest(msg: Message, peer: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = try { com.google.gson.JsonParser.parseString(msg.content).asJsonObject }
                       catch (e: Exception) { return@launch }
            val requestId = json.get("requestId")?.asString ?: return@launch
            // Mark locally as shared (kept for potential future use by receiver side)
            db.messageDao().updateContent(msg.id, """{"requestId":"$requestId","shared":true}""")
            // Get location and respond
            val location = LocationHelper.getCurrentLocation(getApplication())
            if (location != null) {
                val mapsUrl = LocationHelper.buildMapsUrl(location.latitude, location.longitude)
                val ts = System.currentTimeMillis()
                val locContent = """{"lat":${location.latitude},"lon":${location.longitude},"accuracy":${location.accuracy},"timestamp":$ts,"mapsUrl":"$mapsUrl"}"""
                val locId = db.messageDao().insert(
                    Message(from = me, to = peer, content = locContent, type = "location",
                        timestamp = ts, isSent = true, status = "SENDING")
                )
                val respKey = CryptoHelper.getKey(getApplication(), peer)
                val wireContent = if (respKey != null) CryptoHelper.encrypt(respKey, locId, ts, locContent) else locContent
                WebSocketClient.send(mapOf(
                    "type" to "location-response", "from" to me, "to" to peer,
                    "requestId" to requestId, "content" to wireContent,
                    "messageId" to locId, "timestamp" to ts
                ))
            }
        }
    }

    /**
     * Sends read receipts for every message received from [peer] that carries a
     * remoteId (the sender's Room ID). Called when the chat screen opens.
     */
    fun requestHistory(peer: String, days: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val since = System.currentTimeMillis() - days * 86_400_000L
            WebSocketClient.send(mapOf(
                "type" to "history-request", "from" to me, "to" to peer,
                "since" to since, "days" to days
            ))
        }
    }

    fun sendReadReceipts(peer: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.messageDao().getReceivedFrom(peer, me).forEach { msg ->
                WebSocketClient.send(mapOf(
                    "type" to "read",
                    "messageId" to msg.remoteId,
                    "from" to me,
                    "to" to peer
                ))
            }
        }
    }

    fun deleteMessages(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            db.messageDao().deleteByIds(ids)
        }
    }

    fun editMessage(msg: Message, newContent: String, peer: String) {
        if (msg.remoteId == 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val peerPubKey = Prefs.getPeerPublicKey(getApplication(), peer)
            val wireContent = if (peerPubKey.isNotEmpty())
                CryptoHelper.encryptForPeer(getApplication(), peer, peerPubKey, msg.remoteId, newContent)
            else newContent
            WebSocketClient.send(mapOf(
                "type" to "edit", "from" to me, "to" to peer,
                "messageId" to msg.remoteId, "newContent" to wireContent
            ))
        }
    }

    fun sendReaction(msg: Message, emoji: String?) {
        if (msg.remoteId == 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val payload = mutableMapOf<String, Any>(
                "type" to "react",
                "messageId" to msg.remoteId
            )
            if (emoji != null) payload["emoji"] = emoji
            WebSocketClient.send(payload)
        }
    }

    fun deleteForEveryone(msg: com.fshu.next.data.model.Message) {
        val msgId = msg.remoteId
        if (msgId == 0L) {
            // Never confirmed by server — local delete only
            viewModelScope.launch(Dispatchers.IO) { db.messageDao().deleteByIds(listOf(msg.id)) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            WebSocketClient.send(mapOf("type" to "delete", "from" to me, "messageId" to msgId, "forAll" to true))
        }
    }
}
