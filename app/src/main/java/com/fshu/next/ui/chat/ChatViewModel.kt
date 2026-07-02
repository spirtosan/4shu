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

    fun redeliverMessages(peer: String, callback: (List<Message>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val msgs = db.messageDao().getConversationOnce(me, peer)
            withContext(Dispatchers.Main) { callback(msgs) }
        }
    }

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

    suspend fun searchMessages(peer: String, query: String, groupId: String?): List<Message> =
        withContext(Dispatchers.IO) {
            if (groupId != null) db.messageDao().searchGroupMessages(groupId, query)
            else db.messageDao().searchDmMessages(me, peer, query)
        }

    fun sendText(
        peer: String,
        content: String,
        replyToId: Long? = null,
        replyToSender: String? = null,
        replyToContent: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ts = System.currentTimeMillis()
                val id = db.messageDao().insert(
                    Message(from = me, to = peer, content = content, type = "text",
                        timestamp = ts, isSent = true, status = "SENDING",
                        replyToId = replyToId, replyToSender = replyToSender,
                        replyToContent = replyToContent)
                )
                val peerKey = db.peerKeyDao().get(peer)
                if (peerKey == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Cannot send: missing encryption key", Toast.LENGTH_LONG).show()
                    }
                    db.messageDao().updateStatus(id, "FAILED")
                    return@launch
                }
                val wireContent = try {
                    CryptoHelper.encryptForPeer(getApplication(), peer, peerKey.publicKey, id, content)
                } catch (e: Exception) {
                    android.util.Log.e("ChatViewModel", "encryptForPeer failed for $peer: ${e.message}", e)
                    db.messageDao().updateStatus(id, "FAILED")
                    return@launch
                }
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
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "sendText failed: ${e.message}", e)
            }
        }
    }

    fun sendVoice(peer: String, file: java.io.File, waveform: FloatArray, durationSecs: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val peerKey = db.peerKeyDao().get(peer)
                if (peerKey == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Cannot send: missing encryption key", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val fileBytes = file.readBytes()
                val mimeType = "audio/mp4"
                val filename = "voice_${System.currentTimeMillis()}.m4a"
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

                val (encBytes, nonce) = CryptoHelper.encryptFileForPeer(getApplication(), peer, peerKey.publicKey, fileBytes)
                    ?: run {
                        db.messageDao().updateStatus(roomId, "FAILED")
                        return@launch
                    }

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
                val peerKey = db.peerKeyDao().get(peer)
                if (peerKey == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Cannot send: missing encryption key", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val fileBytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val filename = resolveDisplayName(uri, resolver)
                val mimeType = resolver.getType(uri)
                    ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
                    ) ?: "application/octet-stream"
                val tempId = UUID.randomUUID().toString()
                val ts = System.currentTimeMillis()
                val localUri = cacheFileBytes(fileBytes, tempId, filename)
                    ?.let { androidx.core.content.FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.fileprovider", it).toString() }

                val roomId = db.messageDao().insert(
                    Message(from = me, to = peer,
                        content = "\uD83D\uDCCE $filename", type = "file",
                        filename = filename, mimeType = mimeType,
                        localUri = localUri, tempId = tempId,
                        timestamp = ts, isSent = true, status = "SENDING")
                )

                val (encBytes, nonce) = CryptoHelper.encryptFileForPeer(getApplication(), peer, peerKey.publicKey, fileBytes)
                    ?: run {
                        db.messageDao().updateStatus(roomId, "FAILED")
                        return@launch
                    }

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

    fun sendGroupFile(groupId: String, uri: Uri, resolver: ContentResolver) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val group = db.groupDao().getById(groupId) ?: return@launch
                val groupKeyHex = group.groupKey
                val fileBytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val filename = resolveDisplayName(uri, resolver)
                val mimeType = resolver.getType(uri)
                    ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
                    ) ?: "application/octet-stream"
                val tempId = UUID.randomUUID().toString()
                val ts = System.currentTimeMillis()
                Log.d("ChatViewModel", "sendGroupFile: groupId=$groupId keyLen=${groupKeyHex.length} fileSize=${fileBytes.size}")
                val localUri = cacheFileBytes(fileBytes, tempId, filename)
                    ?.let { androidx.core.content.FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.fileprovider", it).toString() }

                val roomId = db.messageDao().insert(
                    Message(from = me, to = "", content = "📎 $filename", type = "file",
                        filename = filename, mimeType = mimeType,
                        localUri = localUri, tempId = tempId,
                        timestamp = ts, isSent = true, status = "SENDING", groupId = groupId)
                )

                if (groupKeyHex.isBlank() || groupKeyHex.length != 64) {
                    Log.e("ChatViewModel", "sendGroupFile: invalid group key for $groupId — length=${groupKeyHex.length}")
                    db.messageDao().updateStatus(roomId, "FAILED")
                    return@launch
                }

                val groupKey = with(EcdhHelper) { groupKeyHex.fromHex() }
                val (encBytes, nonce) = CryptoHelper.encryptGroupFile(groupKey, fileBytes)
                val nonceHex = CryptoHelper.bytesToHex(nonce)

                val header = JsonObject().apply {
                    addProperty("tempId", tempId)
                    addProperty("from", me)
                    addProperty("groupId", groupId)
                    addProperty("filename", filename)
                    addProperty("mimeType", mimeType)
                    addProperty("size", encBytes.size)
                    addProperty("nonce", nonceHex)
                    addProperty("type", "group-file")
                    addProperty("messageId", roomId)
                    addProperty("timestamp", ts)
                }
                val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
                val sink = okio.Buffer()
                sink.writeInt(headerBytes.size)
                sink.write(headerBytes)
                sink.write(encBytes)
                val frame = sink.readByteString()
                Log.d("ChatViewModel", "sendGroupFile: binary frame built, size=${frame.size}, sending...")
                val sent = WebSocketClient.sendBinary(frame)
                if (!sent) {
                    Log.w("ChatViewModel", "sendGroupFile: sendBinary returned false for $groupId — staying SENDING for retry")
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "sendGroupFile failed", e)
            }
        }
    }

    private fun cacheFileBytes(bytes: ByteArray, tempId: String, filename: String): java.io.File? {
        return try {
            val dir = java.io.File(getApplication<Application>().cacheDir, "group_files")
            dir.mkdirs()
            val safe = filename.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(80)
            val f = java.io.File(dir, "${tempId}_$safe")
            f.writeBytes(bytes)
            f
        } catch (e: Exception) {
            Log.e("ChatViewModel", "cacheFileBytes failed for $tempId", e)
            null
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

    /**
     * Creates a poll: one atomic list-create carrying meta + every option (SPEC_T5.md v2 §3b/§3d,
     * Block A.1 wire format — confirmed atomic client+server+local-echo in the Block D read-step).
     * [groupId] null ⇒ DM poll to [peer]; non-null ⇒ group poll (mirrors sendGroupText's
     * from/to=""/groupId convention). option_id scheme: "opt-<index>" in creation-order, used as
     * both the packed `option_id` and the outer list-item id — sequential indices are unique
     * within one creation call and can't collide with the reserved "meta" id or the "ballot:"
     * namespace (Block A.1).
     */
    fun createPoll(peer: String, groupId: String?, question: String, mode: com.fshu.next.poll.PollMode, optionLabels: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val listId = UUID.randomUUID().toString()
            val arr = JsonArray()

            fun addItem(id: String, packed: JsonObject) {
                arr.add(JsonObject().apply {
                    addProperty("id", id)
                    addProperty("text", packed.toString())
                    addProperty("done", false)
                })
            }

            addItem("meta", JsonObject().apply {
                addProperty("kind", "meta")
                addProperty("question", question)
                addProperty("mode", if (mode == com.fshu.next.poll.PollMode.MULTI) "multi" else "single")
                addProperty("status", "open")
            })

            optionLabels.forEachIndexed { i, label ->
                val optId = "opt-$i"
                addItem(optId, JsonObject().apply {
                    addProperty("kind", "option")
                    addProperty("option_id", optId)
                    addProperty("label", label)
                    addProperty("position", i)
                })
            }

            val ts = System.currentTimeMillis()
            val (from, to) = if (groupId != null) Pair(me, "") else Pair(me, peer)
            val msgId = db.messageDao().insert(
                Message(from = from, to = to, content = arr.toString(), type = "list",
                    listId = listId, timestamp = ts, isSent = true, status = "SENDING",
                    listOwner = me, groupId = groupId)
            )
            val payload = mutableMapOf<String, Any?>(
                "type" to "list-create", "from" to me,
                "listId" to listId, "items" to arr,
                "messageId" to msgId, "timestamp" to ts
            )
            if (groupId != null) payload["groupId"] = groupId else payload["to"] = peer
            WebSocketClient.send(payload)
        }
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

    fun sendEmergencyLocation(peer: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                getApplication(),
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(),
                        getApplication<Application>().getString(com.fshu.next.R.string.toast_location_permission_required),
                        Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            android.util.Log.d("EmergencyLoc", "Permission OK, fetching GPS...")
            val location = LocationHelper.getCurrentLocation(getApplication())
            android.util.Log.d("EmergencyLoc", "Location result: $location")
            if (location == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), getApplication<Application>().getString(com.fshu.next.R.string.toast_location_error), Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val mapsUrl = LocationHelper.buildMapsUrl(location.latitude, location.longitude)
            val ts = System.currentTimeMillis()
            val contentJson = """{"lat":${location.latitude},"lon":${location.longitude},"accuracy":${location.accuracy},"timestamp":$ts,"mapsUrl":"$mapsUrl"}"""
            val id = db.messageDao().insert(
                Message(from = me, to = peer, content = contentJson, type = "emergency-location",
                    timestamp = ts, isSent = true, status = "SENT")
            )
            val locKey = CryptoHelper.getKey(getApplication(), peer)
            val wireContent = if (locKey != null) CryptoHelper.encrypt(locKey, id, ts, contentJson) else contentJson
            // Include raw lat/lon fields alongside content — server offline enqueue only preserves raw fields
            WebSocketClient.send(mapOf(
                "type" to "emergency-location", "from" to me, "to" to peer,
                "lat" to location.latitude, "lon" to location.longitude,
                "accuracy" to location.accuracy,
                "content" to wireContent, "messageId" to id, "timestamp" to ts,
                "mapsUrl" to mapsUrl
            ))
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Emergency location sent", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun sendSosMessage(peer: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val sosText = getApplication<Application>()
                .getSharedPreferences("fshu_prefs", android.content.Context.MODE_PRIVATE)
                .getString("sos_message", "")
                ?.takeIf { it.isNotBlank() }
                ?: getApplication<Application>().getString(com.fshu.next.R.string.settings_sos_message_default)

            val hasSosPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                getApplication(),
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasSosPermission) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(),
                        getApplication<Application>().getString(com.fshu.next.R.string.toast_location_permission_required),
                        Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            android.util.Log.d("EmergencyLoc", "Permission OK, fetching GPS...")
            val location = LocationHelper.getCurrentLocation(getApplication())
            android.util.Log.d("EmergencyLoc", "Location result: $location")
            if (location == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), getApplication<Application>().getString(com.fshu.next.R.string.toast_location_error), Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val mapsUrl = LocationHelper.buildMapsUrl(location.latitude, location.longitude)
            val ts = System.currentTimeMillis()
            val clientId = UUID.randomUUID().toString()

            // Content is plain JSON — handleIncomingSosMessage stores it directly without decryption
            val contentJson = com.google.gson.JsonObject().apply {
                addProperty("text", sosText)
                addProperty("lat", location.latitude)
                addProperty("lon", location.longitude)
                addProperty("accuracy", location.accuracy.toDouble())
                addProperty("mapsUrl", mapsUrl)
            }.toString()

            val id = db.messageDao().insert(
                Message(from = me, to = peer, content = contentJson, type = "sos-message",
                    timestamp = ts, isSent = true, status = "SENDING")
            )
            WebSocketClient.send(mapOf(
                "type" to "sos-message", "from" to me, "to" to peer,
                "content" to contentJson, "clientId" to clientId,
                "messageId" to id, "timestamp" to ts
            ))
        }
    }

    fun sendEmergencyLocationRequest(peer: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val requestId = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis()
            val contentJson = """{"requestId":"$requestId"}"""
            val id = db.messageDao().insert(
                Message(
                    from = me, to = peer,
                    content = contentJson,
                    type = "location-request",
                    timestamp = ts,
                    isSent = true,
                    status = "SENT"
                )
            )
            val reqKey = CryptoHelper.getKey(getApplication(), peer)
            val wireContent = if (reqKey != null) CryptoHelper.encrypt(reqKey, id, ts, contentJson) else contentJson
            WebSocketClient.send(mapOf(
                "type" to "emergency-location-request",
                "from" to me,
                "to" to peer,
                "content" to wireContent,
                "requestId" to requestId,
                "messageId" to id,
                "timestamp" to ts
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
            val peerKey = db.peerKeyDao().get(peer) ?: return@launch
            val wireContent = try {
                CryptoHelper.encryptForPeer(getApplication(), peer, peerKey.publicKey, msg.remoteId, newContent)
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "encryptForPeer failed in editMessage: ${e.message}", e)
                return@launch
            }
            WebSocketClient.send(mapOf(
                "type" to "edit", "from" to me, "to" to peer,
                "messageId" to msg.remoteId, "newContent" to wireContent
            ))
        }
    }

    fun sendReaction(msg: Message, emoji: String?) {
        val msgId: Any = if (msg.groupId != null) {
            msg.tempId ?: return
        } else {
            if (msg.remoteId == 0L) return
            msg.remoteId
        }
        viewModelScope.launch(Dispatchers.IO) {
            val payload = mutableMapOf<String, Any>(
                "type" to "react",
                "messageId" to msgId
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
