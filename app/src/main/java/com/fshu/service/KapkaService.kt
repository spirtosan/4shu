package com.fshu.service

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.JsonObject
import com.fshu.R
import com.fshu.data.local.AppDatabase
import com.fshu.data.model.Message
import com.fshu.data.remote.WebSocketClient
import com.fshu.ui.call.CallActivity
import com.fshu.ui.login.LoginActivity
import com.fshu.util.CryptoHelper
import com.fshu.util.LocationHelper
import com.fshu.util.MessageBus
import com.fshu.util.Prefs
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FshuService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db by lazy { AppDatabase.getInstance(this) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkId: Int = -1
    private var connectionWatchdogJob: Job? = null

    companion object {
        const val CHANNEL_ID = "fshu_fg"
        private const val CHANNEL_MESSAGES = "fshu_messages"
        // v2: recreated to add ringtone + vibration pattern (channel settings are immutable after first creation)
        private const val CHANNEL_CALLS = "fshu_calls_v2"
        private const val CHANNEL_CALLS_LEGACY = "fshu_calls"

        const val ACTION_RESTART = "com.fshu.ACTION_RESTART_SERVICE"
        const val ACTION_RECONNECT = "com.fshu.ACTION_RECONNECT"
        const val ACTION_ALARM_CHECK = "com.fshu.ACTION_ALARM_CHECK"
        private const val ALARM_INTERVAL_MS = 3 * 60 * 1000L  // 3 minutes
        private const val WATCHDOG_WORK_NAME = "fshu_service_watchdog"

        private val notifCounter = AtomicInteger(1000)

        @Volatile var lastLatencyMs: Long = -1

        /** Last "users" broadcast received from the server. Replayed to MainActivity if it
         *  starts collecting MessageBus after the event was already emitted. */
        @Volatile var lastUsersJson: com.google.gson.JsonObject? = null

        @Volatile private var activeCallNotifId = -1

        @Volatile private var activeRingtone: Ringtone? = null
        @Volatile private var activeVibrator: Vibrator? = null
        @Volatile private var volumeRampHandler: Handler? = null
        @Volatile private var prevAlarmVolume: Int = -1

        fun cancelCallNotif(context: Context) {
            val id = activeCallNotifId
            if (id != -1) {
                context.getSystemService(NotificationManager::class.java).cancel(id)
                activeCallNotifId = -1
            }
            activeRingtone?.stop()
            activeRingtone = null
            activeVibrator?.cancel()
            activeVibrator = null
            cancelVolumeRamp(context)
        }

        fun cancelVolumeRamp(context: Context) {
            volumeRampHandler?.removeCallbacksAndMessages(null)
            volumeRampHandler = null
            val prev = prevAlarmVolume
            if (prev >= 0) {
                context.getSystemService(AudioManager::class.java)
                    ?.setStreamVolume(AudioManager.STREAM_ALARM, prev, 0)
                prevAlarmVolume = -1
            }
        }

        /** Stop the volume ramp but leave ringtone playing; set alarm to a moderate level. */
        fun silenceEmergencyRamp(context: Context) {
            volumeRampHandler?.removeCallbacksAndMessages(null)
            volumeRampHandler = null
            val am = context.getSystemService(AudioManager::class.java) ?: return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, (max * 0.35).toInt().coerceAtLeast(1), 0)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // CryptoHelper.initDebugLog(applicationContext)
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fshu:connection").apply {
            acquire()
        }
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fshu:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        createNotificationChannels()
        WebSocketClient.addHandler { json ->
            scope.launch { dispatch(json) }
        }
        WebSocketClient.onHeartbeat = {
            scope.launch { checkStaleSending() }
        }
        WebSocketClient.onPong = { latencyMs ->
            lastLatencyMs = latencyMs
        }
        WebSocketClient.onOnlineUsers = { onlineList ->
            val update = com.google.gson.JsonObject()
            update.addProperty("type", "users-update")
            val arr = com.google.gson.JsonArray()
            onlineList.forEach { arr.add(it) }
            update.add("onlineUsers", arr)
            scope.launch { MessageBus.emit(update) }
        }
        WebSocketClient.onAppSecret = { secret ->
            Prefs.setAppSecret(this, secret)
            CryptoHelper.clearKeyCache()
            Log.d("Crypto", "appSecret stored: ${secret.take(8)}")
        }
        WebSocketClient.onAdmin = { isAdmin ->
            Prefs.setIsAdmin(this@FshuService, isAdmin)
        }
        // Register FCM token with server after connect
        WebSocketClient.onConnectedCallback = {
            val token = Prefs.getFcmToken(this@FshuService)
            if (token.isNotEmpty() && Prefs.getFcmEnabled(this@FshuService)) {
                WebSocketClient.send(mapOf("type" to "fcm-token", "token" to token))
            }
            // Also fetch fresh token in case it was refreshed while offline
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { freshToken ->
                    if (Prefs.getFcmEnabled(this@FshuService)) {
                        if (freshToken != Prefs.getFcmToken(this@FshuService)) {
                            Prefs.setFcmToken(this@FshuService, freshToken)
                            WebSocketClient.send(mapOf("type" to "fcm-token", "token" to freshToken))
                        }
                    }
                }
            // Re-send nickname on reconnect
            val nickname = Prefs.getMyNickname(this@FshuService)
            if (nickname.isNotEmpty()) {
                WebSocketClient.send(mapOf("type" to "set-nickname", "nickname" to nickname))
            }
        }
        WebSocketClient.listVersionsProvider = {
            db.messageDao().getAllLists()
                .filter { it.listId != null && it.listVersion != null }
                .associate { it.listId!! to it.listVersion!! }
        }
        WebSocketClient.onOutdatedLists = { outdated ->
            scope.launch {
                val me = Prefs.getUsername(this@FshuService)
                for ((listId, serverVersion) in outdated) {
                    val msg = db.messageDao().getByListId(listId) ?: continue
                    // Only request sync if server truly has a newer version than our local copy.
                    val localVersion = msg.listVersion ?: 0
                    if (serverVersion <= localVersion) continue
                    val peer = if (msg.isSent) msg.to else msg.from
                    WebSocketClient.send(mapOf(
                        "type" to "list-sync-request", "from" to me, "to" to peer, "listId" to listId
                    ))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RECONNECT) {
            try {
                val url = Prefs.getServerUrl(this)
                val username = Prefs.getUsername(this)
                val password = Prefs.getPassword(this)
                WebSocketClient.disconnect()
                connect(url, username, password)
            } catch (e: Exception) {
                Log.w("FshuService", "Reconnect failed — credentials unavailable: ${e.message}")
            }
            return START_STICKY
        }
        startForeground(1, buildForegroundNotification())
        scheduleWatchdog()
        // EncryptedSharedPreferences may throw before first unlock (LOCKED_BOOT_COMPLETED).
        // In that case, skip connect — USER_UNLOCKED will trigger ServiceRestartReceiver
        // which calls onStartCommand again when prefs are available.
        try {
            val url = Prefs.getServerUrl(this)
            val username = Prefs.getUsername(this)
            val password = Prefs.getPassword(this)
            if (username.isNotEmpty()) {
                connect(url, username, password)
                registerNetworkCallback(url, username, password)
                startConnectionWatchdog(url, username, password)
                scheduleAlarmCheck()
            }
        } catch (e: Exception) {
            Log.w("FshuService", "Could not read credentials (pre-unlock?): ${e.message}")
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // AlarmManager: fire within 1 second so the service restarts immediately after swipe-kill.
        val restartIntent = Intent(this, ServiceRestartReceiver::class.java).apply {
            action = ACTION_RESTART
        }
        val pi = PendingIntent.getBroadcast(
            this, 0, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + 1_000L
        val am = getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }

        // WorkManager: backup restart in case the alarm is deferred by the OS.
        WorkManager.getInstance(this).enqueue(
            OneTimeWorkRequestBuilder<ServiceWatchdogWorker>()
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build()
        )

        super.onTaskRemoved(rootIntent)
    }

    private fun scheduleAlarmCheck() {
        val intent = Intent(this, ServiceRestartReceiver::class.java).apply {
            action = ACTION_ALARM_CHECK
        }
        val pi = PendingIntent.getBroadcast(
            this, 1001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + ALARM_INTERVAL_MS
        val am = getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        Log.d("FshuService", "Alarm check scheduled in ${ALARM_INTERVAL_MS / 60000}min")
    }

    private fun scheduleWatchdog() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WATCHDOG_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    private fun connect(url: String, username: String, password: String) {
        if (WebSocketClient.isConnected) {
            Log.d("FshuService", "connect() skipped — already connected")
            return
        }
        WebSocketClient.sessionToken = Prefs.getSessionToken(this@FshuService)
        WebSocketClient.connect(
            url = url,
            username = username,
            password = password,
            onConnected = {
                scope.launch {
                    val sig = com.google.gson.JsonObject()
                    sig.addProperty("type", "reconnected")
                    MessageBus.emit(sig)
                    val status = com.google.gson.JsonObject()
                    status.addProperty("type", "connection-status")
                    status.addProperty("connected", true)
                    MessageBus.emit(status)
                    val authOk = com.google.gson.JsonObject()
                    authOk.addProperty("type", "auth-ok")
                    MessageBus.emit(authOk)

                    val token = WebSocketClient.sessionToken
                    if (token.isNotEmpty()) Prefs.setSessionToken(this@FshuService, token)

                    // Request all lists we may have missed while offline.
                    val me = Prefs.getUsername(this@FshuService)
                    val versions = db.messageDao().getAllLists()
                        .filter { it.listId != null && it.listVersion != null }
                        .associate { it.listId!! to it.listVersion!! }
                    WebSocketClient.send(mapOf(
                        "type" to "list-sync-request",
                        "from" to me,
                        "lastKnownVersions" to versions
                    ))
                }
            },
            onDisconnected = {
                scope.launch {
                    delay(1_000)
                    connect(url, username, password)
                }
            },
            onAuthError = { errorMsg ->
                scope.launch {
                    val status = com.google.gson.JsonObject()
                    status.addProperty("type", "connection-status")
                    status.addProperty("connected", false)
                    MessageBus.emit(status)
                    val authErr = com.google.gson.JsonObject()
                    authErr.addProperty("type", "auth-error")
                    authErr.addProperty("message", errorMsg)
                    MessageBus.emit(authErr)
                }
                if (!LoginActivity.isActive) {
                    // Session auth error (not initial login) — clear creds and return to login.
                    Prefs.setUsername(this@FshuService, "")
                    Prefs.setPassword(this@FshuService, "")
                    getSharedPreferences("fshu_boot", Context.MODE_PRIVATE)
                        .edit().putBoolean("was_logged_in", false).apply()
                    startActivity(
                        Intent(this@FshuService, LoginActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            putExtra(LoginActivity.EXTRA_AUTH_ERROR, true)
                        }
                    )
                    stopSelf()
                }
            }
        )
    }

    private suspend fun dispatch(json: JsonObject) {
        when (json.get("type")?.asString) {
            "call-offer"              -> handleIncomingCall(json, isEmergency = false)
            "call-emergency"          -> handleIncomingCall(json, isEmergency = true)
            "message"                 -> persistIncomingMessage(json)
            "file"                    -> persistIncomingFile(json)
            "missed-call"             -> persistMissedCall(json)
            "list-state"              -> persistListState(json)
            "list-ack"                -> handleListAck(json)
            "history-response"        -> persistHistoryResponse(json)
            "peer-test-request"       -> {
                val testId = json.get("testId")?.asString ?: return
                val me = Prefs.getUsername(this)
                WebSocketClient.send(mapOf("type" to "peer-test-response", "from" to me, "testId" to testId))
            }
            "peer-test-result"        -> MessageBus.emit(json)
            "ack"                     -> handleAck(json)
            "delivered"               -> handleDelivered(json)
            "read"                    -> handleRead(json)
            "emergency-location"      -> persistEmergencyLocation(json)
            "location-request"        -> persistLocationRequest(json)
            "location-response"       -> persistLocationResponse(json)
            "auth-ok"                 -> { Prefs.setIsAdmin(this, json.get("admin")?.asBoolean ?: false); MessageBus.emit(json) }
            "passphrase-hint"         -> MessageBus.emit(json)
            "admin-users",
            "admin-result",
            "admin-error",
            "change-password-ok",
            "change-password-error"   -> MessageBus.emit(json)
            "call-busy"               -> { cancelCallNotif(this); MessageBus.emit(json) }
            "call-end", "call-reject" -> {
                cancelCallNotif(this)
                if (CallActivity.isActive) vibrateOnce()
                MessageBus.emit(json)
            }
            else                      -> {
                if (json.get("type")?.asString == "users") lastUsersJson = json
                MessageBus.emit(json)
            }
        }
    }

    private suspend fun persistIncomingMessage(json: JsonObject) {
        val from = json.get("from")?.asString ?: return
        val rawContent = json.get("content")?.asString ?: return
        val ts = json.get("timestamp")?.asLong ?: 0L
        val remoteId = json.get("messageId")?.asLong ?: 0L
        val replyToId = json.get("replyToId")?.asLong
        val replyToSender = json.get("replyToSender")?.asString?.takeIf { it.isNotEmpty() }
        val replyToContent = json.get("replyToContent")?.asString?.takeIf { it.isNotEmpty() }
        val me = Prefs.getUsername(this)
        val content = if (CryptoHelper.isReady(this) && remoteId != 0L) {
            CryptoHelper.decrypt(
                CryptoHelper.getKey(this, from), remoteId, ts, rawContent,
                me, from, Prefs.getPassphrase(this), Prefs.getAppSecret(this)
            ) ?: "[encrypted]"
        } else {
            rawContent
        }
        db.messageDao().insert(
            Message(from = from, to = me, content = content, type = "text",
                timestamp = ts, isSent = false, remoteId = remoteId,
                replyToId = replyToId, replyToSender = replyToSender,
                replyToContent = replyToContent)
        )
        val seq = json.get("seq")?.asLong ?: 0L
        if (seq > 0 && seq > WebSocketClient.lastSeq) WebSocketClient.lastSeq = seq
        if (remoteId != 0L) {
            WebSocketClient.send(mapOf(
                "type" to "delivered", "messageId" to remoteId, "from" to me, "to" to from
            ))
        }
        notifyMessage(from, content)
    }

    private suspend fun persistIncomingFile(json: JsonObject) {
        val from = json.get("from")?.asString ?: return
        val filename = json.get("filename")?.asString ?: return
        val mimeType = json.get("mimeType")?.asString ?: "application/octet-stream"
        val dataB64 = json.get("data")?.asString
        val ts = json.get("timestamp")?.asLong ?: System.currentTimeMillis()
        val remoteId = json.get("messageId")?.asLong ?: 0L
        val me = Prefs.getUsername(this)

        val localUri = if (dataB64 != null) {
            try {
                val bytes = Base64.decode(dataB64, Base64.NO_WRAP)
                saveFileToStorage(filename, mimeType, bytes)
            } catch (e: Exception) {
                Log.e("FshuService", "Failed to save received file", e)
                null
            }
        } else null

        val content = "\uD83D\uDCCE $filename"
        db.messageDao().insert(
            Message(from = from, to = me, content = content,
                type = "file", filename = filename, mimeType = mimeType,
                localUri = localUri,
                timestamp = ts, isSent = false, remoteId = remoteId)
        )
        val seq = json.get("seq")?.asLong ?: 0L
        if (seq > 0 && seq > WebSocketClient.lastSeq) WebSocketClient.lastSeq = seq
        if (remoteId != 0L) {
            WebSocketClient.send(mapOf(
                "type" to "delivered", "messageId" to remoteId, "from" to me, "to" to from
            ))
        }
        notifyMessage(from, content)
    }

    /**
     * Saves [bytes] to the public Downloads or Pictures directory.
     * Returns the URI string of the saved file, or null on failure.
     */
    private fun saveFileToStorage(filename: String, mimeType: String, bytes: ByteArray): String? {
        val isImage = mimeType.startsWith("image/")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isImage)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val displayNameCol = if (isImage) MediaStore.Images.Media.DISPLAY_NAME
                                 else MediaStore.Downloads.DISPLAY_NAME
            val mimeTypeCol    = if (isImage) MediaStore.Images.Media.MIME_TYPE
                                 else MediaStore.Downloads.MIME_TYPE
            val isPendingCol   = if (isImage) MediaStore.Images.Media.IS_PENDING
                                 else MediaStore.Downloads.IS_PENDING

            val values = ContentValues().apply {
                put(displayNameCol, filename)
                put(mimeTypeCol, mimeType)
                put(isPendingCol, 1)
            }
            val uri = contentResolver.insert(collection, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(isPendingCol, 0)
            contentResolver.update(uri, values, null, null)
            uri.toString()
        } else {
            val dir = if (isImage)
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            else
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, filename)
            file.writeBytes(bytes)
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file).toString()
        }
    }

    private suspend fun handleAck(json: JsonObject) {
        val messageId = json.get("messageId")?.asLong ?: return
        db.messageDao().upgradeStatus(messageId, "SENT")
        propagateLocationStatus(messageId, "SENT")
        MessageBus.emit(json)
    }

    private suspend fun handleDelivered(json: JsonObject) {
        val messageId = json.get("messageId")?.asLong ?: return
        db.messageDao().upgradeStatus(messageId, "DELIVERED")
        propagateLocationStatus(messageId, "DELIVERED")
    }

    private suspend fun handleRead(json: JsonObject) {
        val messageId = json.get("messageId")?.asLong ?: return
        db.messageDao().upgradeStatus(messageId, "READ")
        propagateLocationStatus(messageId, "READ")
    }

    /** If [messageId] is an auto-respond location message with a linkedReqId, propagate status to the RECV_LOC_REQ bubble. */
    private suspend fun propagateLocationStatus(messageId: Long, status: String) {
        val msg = db.messageDao().getById(messageId) ?: return
        if (msg.type != "location") return
        val linkedReqId = try {
            com.google.gson.JsonParser.parseString(msg.content).asJsonObject.get("linkedReqId")?.asString
        } catch (e: Exception) { null } ?: return
        db.messageDao().getRecvLocationRequest(linkedReqId)?.let { req ->
            db.messageDao().upgradeStatus(req.id, status)
        }
    }

    private suspend fun checkStaleSending() {
        val cutoff = System.currentTimeMillis() - 10_000L
        val stale = db.messageDao().getStaleSending(cutoff)
        for (msg in stale) {
            when (msg.type) {
                "text" -> {
                    val wireContent = if (CryptoHelper.isReady(this)) {
                        CryptoHelper.encrypt(
                            CryptoHelper.getKey(this, msg.to), msg.id, msg.timestamp,
                            msg.content, msg.from, msg.to,
                            Prefs.getPassphrase(this), Prefs.getAppSecret(this)
                        )
                    } else msg.content
                    WebSocketClient.send(mapOf(
                        "type" to "message", "from" to msg.from, "to" to msg.to,
                        "content" to wireContent, "messageId" to msg.id,
                        "timestamp" to msg.timestamp
                    ))
                }
                "file" -> {
                    val uri = msg.localUri ?: continue
                    try {
                        val bytes = contentResolver.openInputStream(android.net.Uri.parse(uri))
                            ?.use { it.readBytes() } ?: continue
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        WebSocketClient.send(mapOf(
                            "type" to "file", "from" to msg.from, "to" to msg.to,
                            "filename" to (msg.filename ?: "file"),
                            "mimeType" to (msg.mimeType ?: "application/octet-stream"),
                            "data" to base64, "messageId" to msg.id,
                            "timestamp" to msg.timestamp
                        ))
                    } catch (e: Exception) {
                        Log.e("FshuService", "File retry failed for msg ${msg.id}", e)
                    }
                }
                "list" -> {
                    val listId = msg.listId ?: continue
                    try {
                        val arr = com.google.gson.JsonParser.parseString(msg.content).asJsonArray
                        WebSocketClient.send(mapOf(
                            "type" to "list-create", "from" to msg.from, "to" to msg.to,
                            "listId" to listId, "items" to arr,
                            "messageId" to msg.id, "timestamp" to msg.timestamp
                        ))
                    } catch (e: Exception) {
                        Log.e("FshuService", "List retry failed for msg ${msg.id}", e)
                    }
                }
            }
        }
    }

    /**
     * Handles a server-pushed list-state. Updates existing record or inserts a new one.
     * list-state is plain JSON (server processes item operations, so no client-side encryption).
     */
    private suspend fun persistListState(json: JsonObject) {
        val listId = json.get("listId")?.asString ?: return
        val version = json.get("version")?.asInt ?: return
        val owner = json.get("owner")?.asString ?: return
        val itemsEl = json.get("items") ?: return
        val ts = json.get("timestamp")?.asLong ?: System.currentTimeMillis()
        val me = Prefs.getUsername(this)
        val otherUser = json.get("to")?.asString ?: return
        val content = itemsEl.toString()
        val existing = db.messageDao().getByListId(listId)
        if (existing != null) {
            // Ignore stale pushes — only apply if server version is strictly newer.
            if (version <= (existing.listVersion ?: 0)) return
            db.messageDao().updateListState(listId, content, version)
            db.messageDao().upgradeStatus(existing.id, "SENT")
        } else {
            val isSent = owner == me
            val (from, to) = if (isSent) Pair(me, otherUser) else Pair(owner, me)
            db.messageDao().insert(
                Message(from = from, to = to, content = content, type = "list",
                    listId = listId, timestamp = ts, isSent = isSent,
                    listVersion = version, listOwner = owner)
            )
            if (!isSent) notifyMessage(owner, "\uD83D\uDCDD Todo list")
        }
    }

    /** Handles list-ack from server: updates list version and marks message as SENT. */
    private suspend fun handleListAck(json: JsonObject) {
        val listId = json.get("listId")?.asString ?: return
        val version = json.get("version")?.asInt ?: return
        db.messageDao().updateListVersion(listId, version)
        val msg = db.messageDao().getByListId(listId) ?: return
        db.messageDao().upgradeStatus(msg.id, "SENT")
    }

    private suspend fun persistMissedCall(json: JsonObject) {
        val from = json.get("from")?.asString ?: return
        val ts = json.get("timestamp")?.asLong ?: System.currentTimeMillis()
        val seq = json.get("seq")?.asLong ?: 0L
        if (seq > 0 && seq > WebSocketClient.lastSeq) WebSocketClient.lastSeq = seq
        val me = Prefs.getUsername(this)
        db.messageDao().insert(
            Message(from = from, to = me, content = "\uD83D\uDCDE Missed call",
                type = "text", timestamp = ts, isSent = false)
        )
    }

    private suspend fun persistEmergencyLocation(json: JsonObject) {
        val from = json.get("from")?.asString ?: return
        val lat = json.get("lat")?.asDouble ?: return
        val lon = json.get("lon")?.asDouble ?: return
        val accuracy = json.get("accuracy")?.asFloat ?: 0f
        val ts = json.get("timestamp")?.asLong ?: System.currentTimeMillis()
        val remoteId = json.get("messageId")?.asLong ?: 0L
        val me = Prefs.getUsername(this)
        val mapsUrl = LocationHelper.buildMapsUrl(lat, lon)
        val contentJson = """{"lat":$lat,"lon":$lon,"accuracy":$accuracy,"timestamp":$ts,"mapsUrl":"$mapsUrl"}"""
        db.messageDao().insert(
            Message(from = from, to = me, content = contentJson, type = "location",
                timestamp = ts, isSent = false, remoteId = remoteId)
        )
        val seq = json.get("seq")?.asLong ?: 0L
        if (seq > 0 && seq > WebSocketClient.lastSeq) WebSocketClient.lastSeq = seq
        if (remoteId != 0L) {
            WebSocketClient.send(mapOf("type" to "delivered", "messageId" to remoteId, "from" to me, "to" to from))
        }
        notifyMessage(from, "\uD83D\uDCCD Emergency location received")
    }

    private suspend fun persistLocationRequest(json: JsonObject) {
        val from = json.get("from")?.asString ?: return
        val remoteId = json.get("messageId")?.asLong ?: 0L
        val ts = json.get("timestamp")?.asLong ?: 0L
        val me = Prefs.getUsername(this)

        // Decrypt content if present, fall back to plain-JSON parse, then raw requestId field.
        val requestId: String
        val contentStr = json.get("content")?.asString
        if (contentStr != null && remoteId != 0L && CryptoHelper.isReady(this)) {
            val decrypted = CryptoHelper.decrypt(
                CryptoHelper.getKey(this, from), remoteId, ts, contentStr,
                me, from, Prefs.getPassphrase(this), Prefs.getAppSecret(this)
            )
            val source = if (decrypted != null) {
                try { com.google.gson.JsonParser.parseString(decrypted).asJsonObject } catch (e: Exception) { return }
            } else {
                // Decryption failed — message may be unencrypted; try plain JSON then raw field.
                try { com.google.gson.JsonParser.parseString(contentStr).asJsonObject } catch (e: Exception) { null }
            }
            requestId = source?.get("requestId")?.asString
                ?: json.get("requestId")?.asString
                ?: return
        } else {
            requestId = json.get("requestId")?.asString ?: return
        }

        val contentJson = """{"requestId":"$requestId","shared":false}"""
        val reqMsgId = db.messageDao().insert(
            Message(from = from, to = me, content = contentJson, type = "location-request",
                timestamp = ts, isSent = false, remoteId = remoteId)
        )
        val seq = json.get("seq")?.asLong ?: 0L
        if (seq > 0 && seq > WebSocketClient.lastSeq) WebSocketClient.lastSeq = seq
        if (remoteId != 0L) {
            WebSocketClient.send(mapOf("type" to "delivered", "messageId" to remoteId, "from" to me, "to" to from))
        }
        playLocationRequestSound()
        if (Prefs.getLocationSharingEnabled(this)) {
            scope.launch {
                val location = LocationHelper.getCurrentLocation(this@FshuService) ?: return@launch
                val mapsUrl = LocationHelper.buildMapsUrl(location.latitude, location.longitude)
                val locTs = System.currentTimeMillis()
                // Update RECV_LOC_REQ with coords so the bubble shows what was shared
                val updatedReqContent = """{"requestId":"$requestId","shared":true,"lat":${location.latitude},"lon":${location.longitude},"accuracy":${location.accuracy},"mapsUrl":"$mapsUrl"}"""
                db.messageDao().updateContent(reqMsgId, updatedReqContent)
                val locContent = """{"lat":${location.latitude},"lon":${location.longitude},"accuracy":${location.accuracy},"timestamp":$locTs,"mapsUrl":"$mapsUrl"}"""
                val wireContent = if (CryptoHelper.isReady(this@FshuService)) {
                    CryptoHelper.encrypt(
                        CryptoHelper.getKey(this@FshuService, from), reqMsgId, locTs, locContent,
                        me, from, Prefs.getPassphrase(this@FshuService), Prefs.getAppSecret(this@FshuService)
                    )
                } else locContent
                WebSocketClient.send(mapOf(
                    "type" to "location-response", "from" to me, "to" to from,
                    "requestId" to requestId, "content" to wireContent,
                    "messageId" to reqMsgId, "timestamp" to locTs
                ))
            }
        }
    }

    private suspend fun persistLocationResponse(json: JsonObject) {
        val from = json.get("from")?.asString ?: return
        val remoteId = json.get("messageId")?.asLong ?: 0L
        val ts = json.get("timestamp")?.asLong ?: 0L
        val requestId = json.get("requestId")?.asString
        val me = Prefs.getUsername(this)

        // Decrypt content if present, fall back to plain-JSON parse, then raw lat/lon fields.
        val lat: Double
        val lon: Double
        val accuracy: Float
        val mapsUrl: String
        val contentStr = json.get("content")?.asString
        if (contentStr != null && remoteId != 0L && CryptoHelper.isReady(this)) {
            val decrypted = CryptoHelper.decrypt(
                CryptoHelper.getKey(this, from), remoteId, ts, contentStr,
                me, from, Prefs.getPassphrase(this), Prefs.getAppSecret(this)
            )
            val source = if (decrypted != null) {
                try { com.google.gson.JsonParser.parseString(decrypted).asJsonObject } catch (e: Exception) { return }
            } else {
                // Decryption failed — message may be unencrypted; try plain JSON then raw fields.
                try { com.google.gson.JsonParser.parseString(contentStr).asJsonObject } catch (e: Exception) { null }
            }
            lat = source?.get("lat")?.asDouble ?: json.get("lat")?.asDouble ?: return
            lon = source?.get("lon")?.asDouble ?: json.get("lon")?.asDouble ?: return
            accuracy = source?.get("accuracy")?.asFloat ?: json.get("accuracy")?.asFloat ?: 0f
            mapsUrl = source?.get("mapsUrl")?.asString ?: json.get("mapsUrl")?.asString
                ?: LocationHelper.buildMapsUrl(lat, lon)
        } else {
            lat = json.get("lat")?.asDouble ?: return
            lon = json.get("lon")?.asDouble ?: return
            accuracy = json.get("accuracy")?.asFloat ?: 0f
            mapsUrl = json.get("mapsUrl")?.asString ?: LocationHelper.buildMapsUrl(lat, lon)
        }

        val contentJson = """{"lat":$lat,"lon":$lon,"accuracy":$accuracy,"timestamp":$ts,"mapsUrl":"$mapsUrl"}"""
        db.messageDao().insert(
            Message(from = from, to = me, content = contentJson, type = "location",
                timestamp = ts, isSent = false, remoteId = remoteId)
        )
        // Mark the matching sent location-request as fulfilled so the bubble updates
        if (requestId != null) {
            db.messageDao().getSentLocationRequest(requestId)?.let { req ->
                db.messageDao().updateContent(req.id, """{"requestId":"$requestId","fulfilled":true}""")
            }
        }
        val seq = json.get("seq")?.asLong ?: 0L
        if (seq > 0 && seq > WebSocketClient.lastSeq) WebSocketClient.lastSeq = seq
        if (remoteId != 0L) {
            WebSocketClient.send(mapOf("type" to "delivered", "messageId" to remoteId, "from" to me, "to" to from))
        }
        notifyMessage(from, "\uD83D\uDCCD Location received")
    }

    private fun playLocationRequestSound() {
        try {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 50)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 300)
            Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 500)
        } catch (e: Exception) { /* ignore */ }
    }

    private fun handleIncomingCall(json: JsonObject, isEmergency: Boolean = false) {
        val from = json.get("from")?.asString ?: return
        val sdp = json.get("sdp")?.asString ?: return
        val isVideo = json.get("video")?.asBoolean ?: false
        notifyCall(from, sdp, isVideo, isEmergency)
    }

    private fun notifyMessage(from: String, content: String) {
        val intent = Intent(this, com.fshu.ui.chat.ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(com.fshu.ui.chat.ChatActivity.EXTRA_PEER, from)
        }
        val pi = PendingIntent.getActivity(
            this, notifCounter.get(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val id = notifCounter.getAndIncrement()
        val notif = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setContentTitle(from)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, notif)
    }

    private fun notifyCall(from: String, sdp: String, isVideo: Boolean = false, isEmergency: Boolean = false) {
        cancelCallNotif(this)

        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CallActivity.EXTRA_PEER, from)
            putExtra(CallActivity.EXTRA_IS_CALLER, false)
            putExtra(CallActivity.EXTRA_OFFER_SDP, sdp)
            putExtra(CallActivity.EXTRA_IS_VIDEO_CALL, isVideo)
            putExtra(CallActivity.EXTRA_IS_EMERGENCY, isEmergency)
        }
        val id = notifCounter.getAndIncrement()
        activeCallNotifId = id
        val pi = PendingIntent.getActivity(
            this, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notifTitle = when {
            isEmergency -> "\uD83D\uDEA8 Emergency call from $from"
            isVideo     -> "Incoming video call"
            else        -> "Incoming call"
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setContentTitle(notifTitle)
            .setContentText(from)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, notif)

        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (!pm.isInteractive) {
            // Screen is off — wake lock forces the screen on before the fullScreenIntent
            // fires. setTurnScreenOn(true) in CallActivity alone is not reliable on
            // Android 10 without this.
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "fshu:call_wakeup"
            ).acquire(10_000L)
        } else if (!km.isKeyguardLocked) {
            // Screen is on and unlocked — launch directly (fullScreenIntent shows as HUN).
            startActivity(intent)
        }
        // Screen on + locked: fullScreenIntent handles it, setShowWhenLocked covers the rest.

        val ringtoneUri = if (isEmergency)
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        else
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringtone = RingtoneManager.getRingtone(this, ringtoneUri)
        if (isEmergency) {
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ringtone.isLooping = true
        }
        ringtone.play()
        activeRingtone = ringtone

        val vibrator = getSystemService(Vibrator::class.java)
        val pattern = longArrayOf(0, 1000, 1000, 1000, 1000, 1000, 1000)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        activeVibrator = vibrator

        if (isEmergency) startVolumeRamp()

        // Notify caller that we are ringing
        val me = Prefs.getUsername(this)
        WebSocketClient.send(mapOf("type" to "call-ringing", "from" to me, "to" to from))
    }

    private fun startVolumeRamp() {
        val am = getSystemService(AudioManager::class.java)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        prevAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val startVol = (maxVol * 0.2).toInt().coerceAtLeast(1)
        am.setStreamVolume(AudioManager.STREAM_ALARM, startVol, 0)
        val step = (maxVol * 0.1).toInt().coerceAtLeast(1)
        val handler = Handler(Looper.getMainLooper())
        volumeRampHandler = handler
        var current = startVol
        fun scheduleStep() {
            handler.postDelayed({
                if (current < maxVol && volumeRampHandler === handler) {
                    current = (current + step).coerceAtMost(maxVol)
                    am.setStreamVolume(AudioManager.STREAM_ALARM, current, 0)
                    scheduleStep()
                }
            }, 1500)
        }
        scheduleStep()
    }

    private suspend fun persistHistoryResponse(json: JsonObject) {
        val messagesEl = json.getAsJsonArray("messages") ?: return
        val me = Prefs.getUsername(this)
        val responsePeer = json.get("from")?.asString ?: ""
        var inserted = 0

        for (el in messagesEl) {
            try {
                val obj = el.asJsonObject
                // Safe msgId: JsonNull.asLong() throws, so guard explicitly
                val msgId = obj.get("messageId")
                    ?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val from = obj.get("from")?.asString ?: continue
                val to   = obj.get("to")?.asString   ?: continue
                val rawContent = obj.get("content")?.asString ?: continue
                val ts   = obj.get("timestamp")?.asLong ?: continue
                val replyToId      = obj.get("replyToId")?.takeIf { !it.isJsonNull }?.asLong
                val replyToSender  = obj.get("replyToSender")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
                val replyToContent = obj.get("replyToContent")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

                // Deduplication
                val alreadyExists = if (from == me) {
                    db.messageDao().getSentNear(to, ts - 1000, ts + 1000) != null
                } else {
                    msgId > 0 && db.messageDao().getByRemoteId(msgId) != null
                }
                if (alreadyExists) continue

                val peer = if (from == me) to else from
                val content = if (CryptoHelper.isReady(this) && msgId > 0) {
                    CryptoHelper.decrypt(
                        CryptoHelper.getKey(this, peer), msgId, ts, rawContent,
                        me, peer, Prefs.getPassphrase(this), Prefs.getAppSecret(this)
                    ) ?: rawContent
                } else rawContent

                db.messageDao().insert(
                    Message(
                        from = from, to = to, content = content, type = "text",
                        timestamp = ts, isSent = from == me, status = "READ",
                        remoteId = msgId, replyToId = replyToId,
                        replyToSender = replyToSender, replyToContent = replyToContent
                    )
                )
                inserted++
            } catch (e: Exception) {
                Log.e("FshuService", "Failed to persist history message", e)
            }
        }

        // Notify UI so ChatActivity can show feedback and scroll to top
        val event = com.google.gson.JsonObject().apply {
            addProperty("type", "history-loaded")
            addProperty("peer", responsePeer)
            addProperty("count", inserted)
        }
        MessageBus.tryEmit(event)
    }

    private fun startConnectionWatchdog(url: String, username: String, password: String) {
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = scope.launch {
            while (isActive) {
                delay(60_000)
                if (!WebSocketClient.isConnected && !WebSocketClient.isConnectingNow) {
                    Log.w("FshuService", "Internal watchdog: not connected — forcing reconnect")
                    connect(url, username, password)
                }
            }
        }
    }

    private fun registerNetworkCallback(url: String, username: String, password: String) {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = cm

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val netId = network.hashCode()
                if (netId == lastNetworkId) return  // same network, skip
                lastNetworkId = netId
                Log.d("FshuService", "Network available (id=$netId) — forcing reconnect")
                scope.launch {
                    // Brief delay so the network stack is fully ready
                    delay(500)
                    if (!WebSocketClient.isConnected) {
                        connect(url, username, password)
                    } else {
                        // Network changed — drop and reconnect to bind to new interface
                        WebSocketClient.disconnect()
                        delay(300)
                        connect(url, username, password)
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.d("FshuService", "Network lost — disconnecting WebSocket")
                WebSocketClient.disconnect()
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.d("FshuService", "Network callback registered")
        } catch (e: Exception) {
            Log.e("FshuService", "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = connectivityManager ?: return
        val callback = networkCallback ?: return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {}
        networkCallback = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelCallNotif(this)
        unregisterNetworkCallback()
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        WebSocketClient.onHeartbeat = null
        WebSocketClient.onPong = null
        WebSocketClient.onOnlineUsers = null
        WebSocketClient.disconnect()
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = null
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.deleteNotificationChannel(CHANNEL_CALLS_LEGACY)

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Fshu", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_MAX).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun vibrateOnce() {
        getSystemService(Vibrator::class.java)
            .vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun buildForegroundNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Fshu")
        .setContentText("Connected")
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .build()
}
