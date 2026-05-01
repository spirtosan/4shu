package com.fshu.next.service

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
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.JsonObject
import com.fshu.next.R
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.data.model.Message
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.ui.call.CallActivity
import com.fshu.next.ui.login.LoginActivity
import com.fshu.next.util.CryptoHelper
import com.fshu.next.util.LocationHelper
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
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
        private const val CHANNEL_MESSAGES = "fshu_messages_v3"
        // v3: enableVibration(true) + vibration pattern (channel settings are immutable after first creation)
        private const val CHANNEL_CALLS = "fshu_calls_v3"
        private const val CHANNEL_CALLS_LEGACY = "fshu_calls"

        const val ACTION_RESTART = "com.fshu.next.ACTION_RESTART_SERVICE"
        const val ACTION_RECONNECT = "com.fshu.next.ACTION_RECONNECT"
        const val ACTION_ALARM_CHECK = "com.fshu.next.ACTION_ALARM_CHECK"
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
            Log.d("Crypto", "appSecret stored: ${secret.take(8)}")
        }
        WebSocketClient.onAdmin = { isAdmin ->
            Prefs.setIsAdmin(this@FshuService, isAdmin)
        }
        // Register FCM token with server after connect
        WebSocketClient.onConnectedCallback = {
            // Upload our public key on every connect so server can store/distribute it (Phase 1f)
            val myPub = Prefs.getEcPublicKey(this@FshuService)
            if (myPub.isNotEmpty()) {
                WebSocketClient.send(mapOf("type" to "public-key", "publicKey" to myPub))
            }
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
        WebSocketClient.onBinaryMessage = { bytes ->
            scope.launch { handleIncomingBinaryFile(bytes) }
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
                WebSocketClient.deviceId = Prefs.getDeviceId(this)
                WebSocketClient.deviceName = Prefs.getDeviceName(this).ifEmpty { Build.MODEL }
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
                // Generate or load deviceId on first launch
                val deviceId = Prefs.getDeviceId(this).ifEmpty {
                    java.util.UUID.randomUUID().toString().also { Prefs.setDeviceId(this, it) }
                }
                WebSocketClient.deviceId = deviceId
                if (Prefs.getDeviceName(this).isEmpty()) Prefs.setDeviceName(this, Build.MODEL)
                WebSocketClient.deviceName = Prefs.getDeviceName(this)

                // Generate EC keypair on first launch; load peer key cache from DB
                if (Prefs.getEcPrivateKey(this).isEmpty()) {
                    val kp = com.fshu.next.util.EcdhHelper.generateKeyPair()
                    Prefs.setEcPrivateKey(this, kp.privateKeyHex)
                    Prefs.setEcPublicKey(this, kp.publicKeyHex)
                    Log.d("Crypto", "EC keypair generated pub=${kp.publicKeyHex.take(16)}")
                }
                scope.launch { preloadPeerKeyCache() }

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

                    val me = Prefs.getUsername(this@FshuService)

                    // On every auth-ok, request history per peer since the last message we have.
                    val peers = try {
                        com.google.gson.JsonParser.parseString(Prefs.getCachedUsers(this@FshuService)).asJsonArray
                            .mapNotNull { it.asJsonObject.get("username")?.asString }
                            .filter { it != me && !it.startsWith("_") }
                    } catch (_: Exception) { emptyList() }
                    for (peer in peers) {
                        val since = db.messageDao().getLastMessage(peer, me)?.timestamp ?: continue
                        WebSocketClient.send(mapOf(
                            "type" to "history-request", "from" to me, "to" to peer, "since" to since
                        ))
                    }

                    // Request all lists we may have missed while offline.
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
            "typing"                  -> MessageBus.emit(json)
            "missed-call"             -> persistMissedCall(json)
            "list-state"              -> persistListState(json)
            "list-ack"                -> handleListAck(json)
            "history-response"        -> persistHistoryResponse(json)
            "public-key-response"     -> {
                val uname  = json.get("username")?.asString ?: return
                val pubHex = json.get("publicKey")?.asString ?: return
                db.peerKeyDao().upsert(com.fshu.next.data.model.PeerKey(uname, pubHex))
                CryptoHelper.cachePeerKey(this, uname, pubHex)
                Log.d("Crypto", "peer key received for $uname pub=${pubHex.take(16)}")
            }
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
            "avatar-data"             -> {
                val uname = json.get("username")?.asString ?: return
                val data  = json.get("data")?.asString ?: return
                try {
                    val dir = File(applicationContext.filesDir, "avatars").also { it.mkdirs() }
                    File(dir, "$uname.jpg").writeBytes(
                        android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                    )
                    val evt = com.google.gson.JsonObject().apply {
                        addProperty("type", "avatar-update")
                        addProperty("username", uname)
                    }
                    MessageBus.emit(evt)
                } catch (e: Exception) {
                    Log.e("FshuService", "avatar-data save failed", e)
                }
            }
            "auth-ok"                 -> {
                Prefs.setIsAdmin(this, json.get("admin")?.asBoolean ?: false)
                val turnUser = json.get("turnUsername")?.asString
                val turnPass = json.get("turnPassword")?.asString
                if (!turnUser.isNullOrEmpty()) Prefs.setTurnUsername(this, turnUser)
                if (!turnPass.isNullOrEmpty()) Prefs.setTurnPassword(this, turnPass)
                MessageBus.emit(json)
            }
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
            "users"                   -> {
                lastUsersJson = json
                val me = Prefs.getUsername(this)
                json.getAsJsonArray("users")?.forEach { el ->
                    val obj    = el.asJsonObject
                    val uname  = obj.get("username")?.asString ?: return@forEach
                    val pubHex = obj.get("publicKey")?.asString ?: return@forEach
                    if (uname == me || pubHex.isEmpty()) return@forEach
                    Prefs.setPeerPublicKey(this, uname, pubHex)
                    CryptoHelper.cachePeerKey(this, uname, pubHex)
                }
                MessageBus.emit(json)
            }
            else                      -> MessageBus.emit(json)
        }
    }

    private suspend fun persistIncomingMessage(json: JsonObject) {
        val from = json.get("from")?.asString ?: return
        val rawContent = json.get("content")?.asString ?: return
        val ts = json.get("timestamp")?.asDouble?.toLong() ?: 0L
        val remoteId = json.get("messageId")?.asDouble?.toLong() ?: 0L
        if (remoteId > 0 && db.messageDao().getByRemoteId(remoteId) != null) return
        val replyToId = json.get("replyToId")?.asDouble?.toLong()
        val replyToSender = json.get("replyToSender")?.asString?.takeIf { it.isNotEmpty() }
        val replyToContent = json.get("replyToContent")?.asString?.takeIf { it.isNotEmpty() }
        val me         = Prefs.getUsername(this)
        val peerPubKey = Prefs.getPeerPublicKey(this, from)
        if (peerPubKey.isEmpty()) requestPeerKey(from)
        val content = if (peerPubKey.isNotEmpty() && remoteId != 0L) {
            CryptoHelper.decryptFromPeer(this, from, peerPubKey, remoteId, rawContent) ?: rawContent
        } else rawContent
        db.messageDao().insert(
            Message(from = from, to = me, content = content, type = "text",
                timestamp = ts, isSent = false, remoteId = remoteId,
                replyToId = replyToId, replyToSender = replyToSender,
                replyToContent = replyToContent)
        )
        val seq = json.get("seq")?.asDouble?.toLong() ?: 0L
        if (seq > 0 && seq > WebSocketClient.lastSeq) WebSocketClient.lastSeq = seq
        if (remoteId != 0L) {
            WebSocketClient.send(mapOf(
                "type" to "delivered", "messageId" to remoteId, "from" to me, "to" to from
            ))
        }
        if (!com.fshu.next.ui.chat.ChatActivity.isActive ||
            com.fshu.next.ui.chat.ChatActivity.currentPeer != from) {
            startActivity(
                com.fshu.next.ui.MessagePopupActivity.createIntent(
                    this, from, getDisplayName(from), content
                )
            )
        }
        notifyMessage(from, content)
        MessageBus.tryEmit(json)
    }

    private suspend fun persistIncomingFile(json: JsonObject) {
        val from = json.get("from")?.asString ?: return
        val filename = json.get("filename")?.asString ?: return
        val mimeType = json.get("mimeType")?.asString ?: "application/octet-stream"
        val ts = json.get("timestamp")?.asDouble?.toLong() ?: System.currentTimeMillis()
        val fileId = json.get("fileId")?.asString
        val remoteId = json.get("messageId")?.asDouble?.toLong() ?: 0L
        val me = Prefs.getUsername(this)

        if (remoteId > 0 && db.messageDao().getByRemoteId(remoteId) != null) return

        val content = "\uD83D\uDCCE $filename"
        db.messageDao().insert(
            Message(from = from, to = me, content = content,
                type = "file", filename = filename, mimeType = mimeType,
                fileId = fileId, timestamp = ts, isSent = false, remoteId = remoteId)
        )

        val seq = json.get("seq")?.asDouble?.toLong() ?: 0L
        if (seq > 0 && seq > WebSocketClient.lastSeq) WebSocketClient.lastSeq = seq

        // Request the encrypted binary from the server
        if (fileId != null) {
            WebSocketClient.send(mapOf("type" to "file-request", "fileId" to fileId, "from" to me))
        }
        if (remoteId != 0L) {
            WebSocketClient.send(mapOf("type" to "delivered", "messageId" to remoteId, "from" to me, "to" to from))
        }
        notifyMessage(from, content)
        MessageBus.tryEmit(json)
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
        val tempId = json.get("tempId")?.asString
        val fileId = json.get("fileId")?.asString
        if (tempId != null && fileId != null) {
            // Binary file ack — match by tempId, store fileId
            db.messageDao().updateOnFileAck(tempId, fileId)
            MessageBus.emit(json)
            return
        }
        // Text message ack — match by sender's Room Long id
        val messageId = json.get("messageId")?.asDouble?.toLong() ?: return
        db.messageDao().upgradeStatus(messageId, "SENT")
        propagateLocationStatus(messageId, "SENT")
        MessageBus.emit(json)
    }

    private suspend fun handleDelivered(json: JsonObject) {
        val messageId = json.get("messageId")?.asDouble?.toLong() ?: return
        db.messageDao().upgradeStatus(messageId, "DELIVERED")
        propagateLocationStatus(messageId, "DELIVERED")
    }

    private suspend fun handleRead(json: JsonObject) {
        val messageId = json.get("messageId")?.asDouble?.toLong() ?: return
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

    private suspend fun handleIncomingBinaryFile(bytes: okio.ByteString) {
        try {
            val buf = bytes.asByteBuffer()
            if (buf.remaining() < 4) return
            val headerLen = buf.getInt()
            if (headerLen <= 0 || buf.remaining() < headerLen) return
            val headerBytes = ByteArray(headerLen)
            buf.get(headerBytes)
            val encBytes = ByteArray(buf.remaining())
            buf.get(encBytes)

            val header = com.google.gson.JsonParser.parseString(headerBytes.toString(Charsets.UTF_8)).asJsonObject
            val fileId = header.get("fileId")?.asString ?: return
            val from = header.get("from")?.asString ?: return
            val filename = header.get("filename")?.asString ?: return
            val mimeType = header.get("mimeType")?.asString ?: "application/octet-stream"
            val nonceHex = header.get("nonce")?.asString ?: return

            val peerPubKey = Prefs.getPeerPublicKey(this, from)
            val decrypted = if (peerPubKey.isNotEmpty())
                CryptoHelper.decryptFileFromPeer(this, from, nonceHex, encBytes)
            else {
                Log.w("FshuService", "No peer key for $from — saving raw bytes")
                encBytes
            }

            val localUri = decrypted?.let { saveFileToStorage(filename, mimeType, it) }
            if (localUri != null) {
                db.messageDao().updateFileLocalUri(fileId, localUri)
                Log.d("FshuService", "File saved: $filename → $localUri")
            } else {
                Log.e("FshuService", "Failed to decrypt/save file $filename from $from")
            }
        } catch (e: Exception) {
            Log.e("FshuService", "Binary file handling failed", e)
        }
    }

    private suspend fun checkStaleSending() {
        val cutoff = System.currentTimeMillis() - 10_000L
        val stale = db.messageDao().getStaleSending(cutoff)
        for (msg in stale) {
            when (msg.type) {
                "text" -> {
                    val retryPubKey = Prefs.getPeerPublicKey(this, msg.to)
                    val wireContent = if (retryPubKey.isNotEmpty()) {
                        CryptoHelper.encryptForPeer(this, msg.to, retryPubKey, msg.id, msg.content)
                    } else msg.content
                    WebSocketClient.send(mapOf(
                        "type" to "message", "from" to msg.from, "to" to msg.to,
                        "content" to wireContent, "messageId" to msg.id,
                        "timestamp" to msg.timestamp
                    ))
                }
                "file" -> {
                    val uri = msg.localUri ?: continue
                    val tempId = msg.tempId ?: continue
                    val peer = msg.to
                    val peerPubKey = Prefs.getPeerPublicKey(this, peer)
                    try {
                        val fileBytes = contentResolver.openInputStream(android.net.Uri.parse(uri))
                            ?.use { it.readBytes() } ?: continue
                        val (encBytes, nonce) = if (peerPubKey.isNotEmpty())
                            CryptoHelper.encryptFileForPeer(this, peer, peerPubKey, fileBytes)
                                ?: (fileBytes to ByteArray(12).also { java.security.SecureRandom().nextBytes(it) })
                        else
                            fileBytes to ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                        val nonceHex = CryptoHelper.bytesToHex(nonce)
                        val headerObj = com.google.gson.JsonObject().apply {
                            addProperty("tempId", tempId)
                            addProperty("from", msg.from)
                            addProperty("to", peer)
                            addProperty("filename", msg.filename ?: "file")
                            addProperty("mimeType", msg.mimeType ?: "application/octet-stream")
                            addProperty("size", encBytes.size)
                            addProperty("nonce", nonceHex)
                            addProperty("type", "file")
                            addProperty("messageId", msg.id)
                            addProperty("timestamp", msg.timestamp)
                        }
                        val headerBytes = headerObj.toString().toByteArray(Charsets.UTF_8)
                        val sink = okio.Buffer()
                        sink.writeInt(headerBytes.size)
                        sink.write(headerBytes)
                        sink.write(encBytes)
                        WebSocketClient.sendBinary(sink.readByteString())
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
        val listId    = json.get("listId")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val version   = json.get("version")?.takeIf { it.isJsonPrimitive }?.asDouble?.toInt() ?: return
        val owner     = json.get("owner")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val itemsEl   = json.get("items") ?: return
        val ts        = json.get("timestamp")?.takeIf { it.isJsonPrimitive }?.asDouble?.toLong() ?: System.currentTimeMillis()
        val me        = Prefs.getUsername(this)
        val otherUser = json.get("to")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val content = itemsEl.toString()
        val msgId = json.get("messageId")?.takeIf { !it.isJsonNull }?.asDouble?.toLong()
        val existing = db.messageDao().getByListId(listId)
        if (existing != null) {
            // Ignore stale pushes — only apply if server version is strictly newer.
            if (version <= (existing.listVersion ?: 0)) return
            db.messageDao().updateListState(listId, content, version)
            if (msgId != null && msgId > 0 && owner != me) {
                WebSocketClient.send(mapOf(
                    "type" to "delivered", "messageId" to msgId, "from" to me, "to" to owner
                ))
            }
        } else {
            val isSent = owner == me
            val (from, to) = if (isSent) Pair(me, otherUser) else Pair(owner, me)
            db.messageDao().insert(
                Message(from = from, to = to, content = content, type = "list",
                    listId = listId, timestamp = ts, isSent = isSent,
                    listVersion = version, listOwner = owner)
            )
            if (!isSent) {
                Log.d("FshuService", "persistListState: notifying owner=$owner isSent=$isSent existing=$existing")
                if (!com.fshu.next.ui.chat.ChatActivity.isActive ||
                    com.fshu.next.ui.chat.ChatActivity.currentPeer != owner) {
                    startActivity(
                        com.fshu.next.ui.MessagePopupActivity.createIntent(
                            this, owner, getDisplayName(owner), "📝 Todo list"
                        )
                    )
                }
                notifyMessage(owner, "\uD83D\uDCDD Todo list")
                if (msgId != null && msgId > 0) {
                    WebSocketClient.send(mapOf(
                        "type" to "delivered", "messageId" to msgId, "from" to me, "to" to owner
                    ))
                }
            }
        }
        MessageBus.tryEmit(json)
    }

    /** Handles list-ack from server: updates list version and marks message as SENT. */
    private suspend fun handleListAck(json: JsonObject) {
        val listId  = json.get("listId")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val version = json.get("version")?.takeIf { it.isJsonPrimitive }?.asDouble?.toInt() ?: return
        db.messageDao().updateListVersion(listId, version)
        val msg = db.messageDao().getByListId(listId) ?: return
        db.messageDao().upgradeStatus(msg.id, "SENT")
    }

    private suspend fun persistMissedCall(json: JsonObject) {
        val from = json.get("from")?.asString ?: return
        val ts = json.get("timestamp")?.asDouble?.toLong() ?: System.currentTimeMillis()
        val seq = json.get("seq")?.asDouble?.toLong() ?: 0L
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
        val ts = json.get("timestamp")?.asDouble?.toLong() ?: System.currentTimeMillis()
        val remoteId = json.get("messageId")?.asDouble?.toLong() ?: 0L
        val me = Prefs.getUsername(this)
        val mapsUrl = LocationHelper.buildMapsUrl(lat, lon)
        val contentJson = """{"lat":$lat,"lon":$lon,"accuracy":$accuracy,"timestamp":$ts,"mapsUrl":"$mapsUrl"}"""
        db.messageDao().insert(
            Message(from = from, to = me, content = contentJson, type = "location",
                timestamp = ts, isSent = false, remoteId = remoteId)
        )
        val seq = json.get("seq")?.asDouble?.toLong() ?: 0L
        if (seq > 0 && seq > WebSocketClient.lastSeq) WebSocketClient.lastSeq = seq
        if (remoteId != 0L) {
            WebSocketClient.send(mapOf("type" to "delivered", "messageId" to remoteId, "from" to me, "to" to from))
        }
        notifyMessage(from, "\uD83D\uDCCD Emergency location received")
    }

    private suspend fun persistLocationRequest(json: JsonObject) {
        val from = json.get("from")?.asString ?: return
        val remoteId = json.get("messageId")?.asDouble?.toLong() ?: 0L
        val ts = json.get("timestamp")?.asDouble?.toLong() ?: 0L
        val me = Prefs.getUsername(this)

        // Decrypt content if present; fall back to plain-JSON parse then raw requestId field.
        val requestId: String
        val contentStr = json.get("content")?.asString
        val locKey = CryptoHelper.getKey(this, from)
        if (contentStr != null && remoteId != 0L && locKey != null) {
            val decrypted = CryptoHelper.decrypt(locKey, remoteId, ts, contentStr)
            val source = if (decrypted != null) {
                try { com.google.gson.JsonParser.parseString(decrypted).asJsonObject } catch (e: Exception) { return }
            } else {
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
        val seq = json.get("seq")?.asDouble?.toLong() ?: 0L
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
                val autoLocKey  = CryptoHelper.getKey(this@FshuService, from)
                val wireContent = if (autoLocKey != null) {
                    CryptoHelper.encrypt(autoLocKey, reqMsgId, locTs, locContent)
                } else locContent
                WebSocketClient.send(mapOf(
                    "type" to "location-response", "from" to me, "to" to from,
                    "requestId" to requestId, "content" to wireContent,
                    "messageId" to reqMsgId, "timestamp" to locTs
                ))
            }
        }
        MessageBus.tryEmit(json)
    }

    private suspend fun persistLocationResponse(json: JsonObject) {
        val from = json.get("from")?.asString ?: return
        val remoteId = json.get("messageId")?.asDouble?.toLong() ?: 0L
        val ts = json.get("timestamp")?.asDouble?.toLong() ?: 0L
        val requestId = json.get("requestId")?.asString
        val me = Prefs.getUsername(this)

        // Decrypt content if present, fall back to plain-JSON parse, then raw lat/lon fields.
        val lat: Double
        val lon: Double
        val accuracy: Float
        val mapsUrl: String
        val contentStr = json.get("content")?.asString
        val locRespKey = CryptoHelper.getKey(this, from)
        if (contentStr != null && remoteId != 0L && locRespKey != null) {
            val decrypted = CryptoHelper.decrypt(locRespKey, remoteId, ts, contentStr)
            val source = if (decrypted != null) {
                try { com.google.gson.JsonParser.parseString(decrypted).asJsonObject } catch (e: Exception) { return }
            } else {
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
        val seq = json.get("seq")?.asDouble?.toLong() ?: 0L
        if (seq > 0 && seq > WebSocketClient.lastSeq) WebSocketClient.lastSeq = seq
        if (remoteId != 0L) {
            WebSocketClient.send(mapOf("type" to "delivered", "messageId" to remoteId, "from" to me, "to" to from))
        }
        notifyMessage(from, "\uD83D\uDCCD Location received")
        MessageBus.tryEmit(json)
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

    private fun getDisplayName(username: String): String {
        return try {
            val usersJson = lastUsersJson ?: return username
            val arr = usersJson.getAsJsonArray("users") ?: return username
            for (el in arr) {
                val obj = el.asJsonObject
                if (obj.get("username")?.asString == username) {
                    val nick = obj.get("nickname")?.asString
                    return if (!nick.isNullOrBlank()) nick else username
                }
            }
            username
        } catch (_: Exception) { username }
    }

    private fun notifyMessage(from: String, content: String) {
        // Vibrate respecting silent/vibrate mode
        try {
            val am = getSystemService(android.media.AudioManager::class.java)
            val ringerMode = am.ringerMode
            if (ringerMode == android.media.AudioManager.RINGER_MODE_VIBRATE ||
                ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL) {
                val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getSystemService(android.os.VibratorManager::class.java)
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(android.os.Vibrator::class.java)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(android.os.VibrationEffect.createWaveform(
                        longArrayOf(0, 250, 250, 250), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(longArrayOf(0, 250, 250, 250), -1)
                }
            }
        } catch (_: Exception) {}

        val intent = Intent(this, com.fshu.next.ui.chat.ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(com.fshu.next.ui.chat.ChatActivity.EXTRA_PEER, from)
        }
        val pi = PendingIntent.getActivity(
            this, from.hashCode().and(Int.MAX_VALUE),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getDisplayName(from))
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(from.hashCode().and(Int.MAX_VALUE), notif)
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
            .setVibrate(longArrayOf(0, 1000, 1000, 1000, 1000, 1000, 1000))
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

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VibratorManager::class.java)
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(android.os.Vibrator::class.java)
        }
        val pattern = longArrayOf(0, 1000, 1000, 1000, 1000, 1000, 1000)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(pattern, amplitudes, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = android.os.VibrationAttributes.Builder()
                    .setUsage(android.os.VibrationAttributes.USAGE_RINGTONE)
                    .build()
                vibrator.vibrate(effect, attrs)
            } else {
                vibrator.vibrate(effect)
            }
        }
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
                    ?.takeIf { !it.isJsonNull }?.asDouble?.toLong() ?: 0L
                val from = obj.get("from")?.asString ?: continue
                val to   = obj.get("to")?.asString   ?: continue
                val rawContent = obj.get("content")?.asString ?: continue
                val ts   = obj.get("timestamp")?.asDouble?.toLong() ?: continue
                val replyToId      = obj.get("replyToId")?.takeIf { !it.isJsonNull }?.asDouble?.toLong()
                val replyToSender  = obj.get("replyToSender")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
                val replyToContent = obj.get("replyToContent")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

                // Deduplication
                val alreadyExists = if (from == me) {
                    db.messageDao().getSentNear(to, ts - 1000, ts + 1000) != null
                } else {
                    msgId > 0 && db.messageDao().getByRemoteId(msgId) != null
                }
                if (alreadyExists) continue

                val peer    = if (from == me) to else from
                val histKey = CryptoHelper.getKey(this, peer)
                val content = if (histKey != null && msgId > 0) {
                    CryptoHelper.decrypt(histKey, msgId, ts, rawContent) ?: rawContent
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

    /** Load all stored peer public keys into the in-memory key cache. Called on startup. */
    private suspend fun preloadPeerKeyCache() {
        db.peerKeyDao().getAll().forEach { pk ->
            CryptoHelper.cachePeerKey(this, pk.username, pk.publicKey)
        }
    }

    /** Request a peer's public key from the server (answered once Phase 1f is live). */
    private fun requestPeerKey(peer: String) {
        WebSocketClient.send(mapOf("type" to "public-key-request", "username" to peer))
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
        WebSocketClient.onBinaryMessage = null
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
        nm.deleteNotificationChannel("fshu_messages")
        nm.deleteNotificationChannel("fshu_messages_v2")

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Fshu", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming message notifications"
                enableLights(true)
                lightColor = android.graphics.Color.parseColor("#E8711A")
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.deleteNotificationChannel("fshu_calls_v2")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_MAX).apply {
                setSound(null, null)
                enableVibration(true)
                setVibrationPattern(longArrayOf(0, 1000, 1000, 1000, 1000, 1000, 1000))
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun vibrateOnce() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(android.os.Vibrator::class.java)
        }
        vib.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun buildForegroundNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Fshu")
        .setContentText("Connected")
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .build()
}
