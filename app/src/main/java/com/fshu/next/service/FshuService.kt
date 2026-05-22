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
import com.fshu.next.MainActivity
import com.fshu.next.R
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.data.local.Mute
import com.fshu.next.data.local.entities.Contact
import com.fshu.next.data.model.Group
import com.fshu.next.data.model.GroupMember
import com.fshu.next.data.model.Message
import com.fshu.next.util.EcdhHelper
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

private fun com.google.gson.JsonElement?.safeString(): String? = this?.takeIf { !it.isJsonNull }?.asString
private fun com.google.gson.JsonElement?.safeLong(): Long? = this?.takeIf { !it.isJsonNull }?.asLong
private fun com.google.gson.JsonElement?.safeDouble(): Double? = this?.takeIf { !it.isJsonNull }?.asDouble
private fun com.google.gson.JsonElement?.safeBoolean(): Boolean? = this?.takeIf { !it.isJsonNull }?.asBoolean

class FshuService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db by lazy { AppDatabase.getInstance(this) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkId: Long = -1L
    private var serviceStartTime: Long = 0L
    private var connectionWatchdogJob: Job? = null
    private val pendingDecryptQueue = mutableMapOf<String, MutableList<Pair<Long, String>>>()

    companion object {
        const val CHANNEL_ID = "fshu_fg"
        private const val CHANNEL_MESSAGES = "fshu_messages_v3"
        // v3: enableVibration(true) + vibration pattern (channel settings are immutable after first creation)
        private const val CHANNEL_CALLS = "fshu_calls_v3"
        private const val CHANNEL_CALLS_LEGACY = "fshu_calls"
        private const val CHANNEL_GROUPS = "fshu_groups_v1"

        const val ACTION_RESTART = "com.fshu.next.ACTION_RESTART_SERVICE"
        const val ACTION_RECONNECT = "com.fshu.next.ACTION_RECONNECT"
        const val ACTION_ALARM_CHECK = "com.fshu.next.ACTION_ALARM_CHECK"
        const val ACTION_DISMISS_SOS = "com.fshu.next.ACTION_DISMISS_SOS"
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
        @Volatile private var activeSosRingtone: Ringtone? = null
        @Volatile var sosPeer: String? = null
        @Volatile private var activeSosNotifId: Int = -1
        @Volatile private var sosWakeLock: PowerManager.WakeLock? = null

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
            // Defensive cancel: if activeVibrator was already null the hardware may still be buzzing
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(VibratorManager::class.java)
                        ?.defaultVibrator?.cancel()
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Vibrator::class.java)?.cancel()
                }
            } catch (_: Exception) {}
            cancelVolumeRamp(context)
        }

        fun stopSosAlarm(peer: String? = null, context: Context? = null) {
            if (peer != null && sosPeer != peer) return
            activeSosRingtone?.stop()
            activeSosRingtone = null
            sosPeer = null
            sosWakeLock?.release()
            sosWakeLock = null
            if (context != null) {
                cancelVolumeRamp(context)
                val notifId = activeSosNotifId
                if (notifId != -1) {
                    context.getSystemService(NotificationManager::class.java).cancel(notifId)
                    activeSosNotifId = -1
                }
            }
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

        fun sendSetEmergencyAllow(contact: String, allow: Boolean) {
            WebSocketClient.send(mapOf(
                "type"    to "set-emergency-allow",
                "contact" to contact,
                "allow"   to if (allow) 1 else 0
            ))
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceStartTime = System.currentTimeMillis()
        writeGroupDebug("=== FshuService started ===")
        scope.launch(Dispatchers.IO) { cleanGroupFilesCache() }
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
            try {
                if (json.get("type")?.takeIf { !it.isJsonNull }?.asString == "message") {
                    val debugEvt = com.google.gson.JsonObject().apply {
                        addProperty("type", "ws-dm-debug")
                        addProperty("from", json.get("from")?.takeIf { !it.isJsonNull }?.asString ?: "?")
                        addProperty("frame", WebSocketClient.wsFrameCount.get())
                    }
                    MessageBus.tryEmit(debugEvt)
                }
            } catch (e: Exception) {
                Log.w("FshuService", "ws-dm-debug builder threw: ${e.message}")
            }
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
        if (intent?.action == ACTION_DISMISS_SOS) {
            stopSosAlarm(context = this)
            return START_STICKY
        }
        if (intent?.action == ACTION_RECONNECT) {
            try {
                val url = Prefs.getServerUrl(this)
                val username = Prefs.getUsername(this)
                val password = Prefs.getPassword(this)
                WebSocketClient.deviceId = Prefs.getDeviceId(this)
                WebSocketClient.deviceName = Prefs.getDeviceName(this).ifEmpty { Build.MODEL }
                WebSocketClient.context = applicationContext
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
                WebSocketClient.context = applicationContext

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
            "file", "voice"           -> persistIncomingFile(json)
            "typing"                  -> MessageBus.emit(json)
            "deleted"                 -> handleDeletedForAll(json)
            "edited"                  -> handleEdited(json)
            "reactions"               -> handleReactions(json)
            "missed-call"             -> persistMissedCall(json)
            "list-state"              -> persistListState(json)
            "list-ack"                -> handleListAck(json)
            "public-key-response"     -> {
                val uname  = json.get("username")?.asString ?: return
                val pubHex = json.get("publicKey")?.asString ?: return
                if (pubHex.length != 64 || !pubHex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                    Log.w("Crypto", "peer key REJECTED for $uname — invalid format (len=${pubHex.length})")
                    return
                }
                db.peerKeyDao().upsert(com.fshu.next.data.model.PeerKey(uname, pubHex))
                CryptoHelper.cachePeerKey(this, uname, pubHex)
                scope.launch { retryPendingGroupKeyDecryption(uname) }
                Log.d("Crypto", "peer key received for $uname pub=${pubHex.take(16)}")
                val pending = pendingDecryptQueue.remove(uname)
                if (!pending.isNullOrEmpty()) {
                    val me = Prefs.getUsername(this)
                    scope.launch {
                        for ((msgId, rawCipher) in pending) {
                            val msg = db.messageDao().getById(msgId) ?: continue
                            val decrypted = try {
                                CryptoHelper.decryptFromPeer(this@FshuService, uname, pubHex, msgId, rawCipher)
                            } catch (_: Exception) { null } ?: continue
                            db.messageDao().updateContent(msgId, decrypted)
                            if (msg.remoteId > 0L) {
                                WebSocketClient.send(mapOf(
                                    "type" to "delivered", "messageId" to msg.remoteId,
                                    "from" to me, "to" to msg.to
                                ))
                            }
                        }
                        val evt = com.google.gson.JsonObject().apply {
                            addProperty("type", "messages-updated")
                            addProperty("peer", uname)
                        }
                        MessageBus.emit(evt)
                    }
                }
            }
            "peer-test-request"       -> {
                val testId = json.get("testId")?.asString ?: return
                val me = Prefs.getUsername(this)
                WebSocketClient.send(mapOf("type" to "peer-test-response", "from" to me, "testId" to testId))
            }
            "peer-test-result"        -> MessageBus.emit(json)
            "group-state"             -> handleGroupState(json)
            "group-message"           -> persistGroupMessage(json)
            "group-file"              -> persistIncomingGroupFile(json)
            "group-removed"           -> handleGroupRemoved(json)
            "group-delivery-update"   -> handleGroupDeliveryUpdate(json)
            "group-key-update"        -> handleGroupKeyUpdate(json)
            "group-key-needed"        -> handleGroupKeyNeeded(json)
            "mute-updated"            -> handleMuteUpdated(json)
            "group-avatar"            -> handleGroupAvatar(json)
            "group-error"             -> MessageBus.emit(json)
            "ack"                     -> handleAck(json)
            "delivered"               -> handleDelivered(json)
            "read"                    -> handleRead(json)
            "emergency-location"          -> persistEmergencyLocation(json)
            "location-request"            -> persistLocationRequest(json)
            "location-response"           -> persistLocationResponse(json)
            "sos-message"                 -> handleIncomingSosMessage(json)
            "emergency-location-request"  -> persistLocationRequest(json)
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
            "avatar-removed"          -> {
                val uname = json.get("username")?.asString ?: return
                File(applicationContext.filesDir, "avatars/$uname.jpg").delete()
                val evt = com.google.gson.JsonObject().apply {
                    addProperty("type", "avatar-update")
                    addProperty("username", uname)
                }
                MessageBus.emit(evt)
            }
            "auth-ok"                 -> {
                Prefs.setIsAdmin(this, json.get("admin")?.asBoolean ?: false)
                val turnUser = json.get("turnUsername")?.asString
                val turnPass = json.get("turnPassword")?.asString
                if (!turnUser.isNullOrEmpty()) Prefs.setTurnUsername(this, turnUser)
                if (!turnPass.isNullOrEmpty()) Prefs.setTurnPassword(this, turnPass)
                json.getAsJsonArray("contactNicknames")?.forEach { el ->
                    val obj = el.asJsonObject
                    val contact = obj.get("contact")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                    val nick = obj.get("nickname")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                    Prefs.setContactNickname(this, contact, nick)
                }
                json.getAsJsonObject("profile")?.let { p ->
                    p.get("email")?.takeIf { !it.isJsonNull }?.asString?.let { Prefs.setMyEmail(this, it) }
                    p.get("phone")?.takeIf { !it.isJsonNull }?.asString?.let { Prefs.setMyPhone(this, it) }
                    p.get("bio")?.takeIf { !it.isJsonNull }?.asString?.let { Prefs.setMyBio(this, it) }
                    p.get("nickname")?.takeIf { !it.isJsonNull }?.asString?.let { Prefs.setMyNickname(this, it) }
                    p.get("discoverable")?.takeIf { !it.isJsonNull }?.asInt?.let { Prefs.setPrivacyDiscoverable(this, it) }
                    p.get("show_avatar")?.takeIf { !it.isJsonNull }?.asInt?.let { Prefs.setPrivacyShowAvatar(this, it) }
                    p.get("show_nickname")?.takeIf { !it.isJsonNull }?.asInt?.let { Prefs.setPrivacyShowNickname(this, it) }
                    p.get("email_searchable")?.takeIf { !it.isJsonNull }?.asInt?.let { Prefs.setPrivacyEmailSearchable(this, it) }
                    p.get("phone_searchable")?.takeIf { !it.isJsonNull }?.asInt?.let { Prefs.setPrivacyPhoneSearchable(this, it) }
                }
                json.getAsJsonArray("autoLocationPeers")?.let { arr ->
                    val peers = arr.mapNotNull { it?.takeIf { !it.isJsonNull }?.asString }.toSet()
                    Prefs.setAutoLocationPeers(this, peers)
                }
                val mutesArr = json.getAsJsonArray("mutes")
                if (mutesArr != null) {
                    val me = Prefs.getUsername(this)
                    db.muteDao().deleteAll(me)
                    for (m in mutesArr) {
                        val obj = m.asJsonObject
                        val target = obj.get("target")?.asString ?: continue
                        val targetType = obj.get("target_type")?.asString ?: "contact"
                        db.muteDao().insert(Mute(owner = me, target = target, targetType = targetType, createdAt = System.currentTimeMillis()))
                    }
                }
                scope.launch { retryPendingGroupKeyDecryption(Prefs.getUsername(this@FshuService)) }
                MessageBus.emit(json)
            }
            "passphrase-hint"         -> MessageBus.emit(json)
            "auto-location-peers"     -> {
                val peers = json.getAsJsonArray("peers")
                    ?.mapNotNull { it?.takeIf { !it.isJsonNull }?.asString }?.toSet() ?: emptySet()
                Prefs.setAutoLocationPeers(this, peers)
                MessageBus.emit(json)
            }
            "admin-users",
            "admin-result",
            "admin-error",
            "change-password-ok",
            "change-password-error"   -> MessageBus.emit(json)
            "call-busy"               -> { cancelCallNotif(this); MessageBus.emit(json) }
            "call-end", "call-reject", "call-decline" -> {
                cancelCallNotif(this)
                if (CallActivity.isActive) vibrateOnce()
                if (json.get("type")?.asString == "call-reject") {
                    val callee = json.get("from")?.asString
                    val caller = json.get("to")?.asString
                    val me = Prefs.getUsername(this)
                    if (callee != null && caller == me) {
                        val ts = System.currentTimeMillis()
                        // Send to callee immediately — before the Room insert — so the server
                        // delivers the chat message in real-time. Without an explicit send the
                        // message would sit in SENDING state until checkStaleSending fires on
                        // the next heartbeat (~30 s delay). Use ts as the ECDH nonce (same
                        // role Room's auto-id plays for regular messages); include it as
                        // messageId so the receiver can derive the correct decryption nonce.
                        val peerPubKey = Prefs.getPeerPublicKey(this, callee)
                        val wireContent = if (peerPubKey.isNotEmpty())
                            try { CryptoHelper.encryptForPeer(this, callee, peerPubKey, ts, "📵 Call declined") }
                            catch (_: Exception) { null }
                        else null
                        val payload = mutableMapOf<String, Any?>(
                            "type" to "message", "from" to me, "to" to callee,
                            "content" to (wireContent ?: "📵 Call declined"), "timestamp" to ts
                        )
                        if (wireContent != null) payload["messageId"] = ts
                        WebSocketClient.send(payload)
                        // Room insert is async (service scope) so it never blocks the send above.
                        // status = SENT prevents checkStaleSending from re-sending this message.
                        scope.launch {
                            db.messageDao().insert(
                                Message(from = me, to = callee,
                                    content = "📵 Call declined",
                                    type = "text",
                                    timestamp = ts,
                                    isSent = true,
                                    status = "SENT")
                            )
                        }
                    }
                }
                MessageBus.emit(json)
            }
            "users"                   -> {
                lastUsersJson = json
                val me  = Prefs.getUsername(this)
                val arr = json.getAsJsonArray("users")
                if (arr != null) {
                    for (el in arr) {
                        val obj    = el.asJsonObject
                        val uname  = obj.get("username")?.takeIf { !it.isJsonNull }?.asString ?: continue
                        val pubHex = obj.get("publicKey")?.takeIf { !it.isJsonNull }?.asString ?: continue
                        if (uname == me) continue
                        if (pubHex.length != 64 || !pubHex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                            Log.w("FshuService", "users-broadcast: key rejected for $uname (len=${pubHex.length})")
                            continue
                        }
                        val storedKey = db.peerKeyDao().get(uname)
                        if (storedKey == null) {
                            db.peerKeyDao().upsert(com.fshu.next.data.model.PeerKey(uname, pubHex))
                        } else if (storedKey.publicKey != pubHex) {
                            db.peerKeyDao().delete(uname)
                            db.peerKeyDao().upsert(com.fshu.next.data.model.PeerKey(uname, pubHex))
                            Prefs.clearPeerPublicKey(this, uname)
                        }
                        Prefs.setPeerPublicKey(this, uname, pubHex)
                        try {
                            CryptoHelper.cachePeerKey(this, uname, pubHex)
                        } catch (e: Exception) {
                            Log.w("FshuService", "cachePeerKey failed for $uname: ${e.message}")
                        }
                        val pendingFromBroadcast = pendingDecryptQueue.remove(uname)
                        if (!pendingFromBroadcast.isNullOrEmpty()) {
                            scope.launch {
                                for ((msgId, rawCipher) in pendingFromBroadcast) {
                                    val decrypted = try {
                                        CryptoHelper.decryptFromPeer(this@FshuService, uname, pubHex, msgId, rawCipher)
                                    } catch (_: Exception) { null } ?: continue
                                    db.messageDao().updateContent(msgId, decrypted)
                                }
                                val evt = com.google.gson.JsonObject().apply {
                                    addProperty("type", "messages-updated")
                                    addProperty("peer", uname)
                                }
                                MessageBus.emit(evt)
                            }
                        }
                    }
                }
                MessageBus.emit(json)
            }
            "user-update"             -> { MessageBus.emit(json) }
            "contact-list"            -> {
                val me  = Prefs.getUsername(this)
                val now = System.currentTimeMillis()
                val arr = json.getAsJsonArray("contacts")
                if (arr != null) scope.launch {
                    for (el in arr) {
                        val obj     = el.asJsonObject
                        val contact = obj.get("contact")?.takeIf { !it.isJsonNull }?.asString ?: continue
                        val created = try { obj.get("created_at")?.asLong ?: now } catch (_: Exception) { now }
                        val updated = try { obj.get("updated_at")?.asLong ?: now } catch (_: Exception) { now }
                        db.contactDao().upsert(Contact(
                            owner = me, contact = contact, status = "accepted",
                            createdAt = created, updatedAt = updated, expiresAt = 0L
                        ))
                        val allowEmergency = try {
                            obj.get("allow_emergency_call")?.takeIf { !it.isJsonNull }?.asInt
                        } catch (_: Exception) { null }
                        if (allowEmergency != null) {
                            db.contactDao().setEmergencyAllow(me, contact, allowEmergency)
                        }
                        val allowEmergencyLoc = try {
                            obj.get("allow_emergency_location")?.takeIf { !it.isJsonNull }?.asInt
                        } catch (_: Exception) { null }
                        if (allowEmergencyLoc != null) {
                            db.contactDao().setEmergencyLocationAllow(me, contact, allowEmergencyLoc)
                        }
                    }
                }
                MessageBus.emit(json)
            }
            "contact-accepted"        -> {
                val peer = json.get("targetUsername")?.asString?.takeIf { it.isNotEmpty() }
                    ?: json.get("from")?.asString ?: ""
                if (peer.isNotEmpty()) {
                    val me  = Prefs.getUsername(this)
                    val now = System.currentTimeMillis()
                    scope.launch {
                        db.contactDao().upsert(Contact(
                            owner = me, contact = peer, status = "accepted",
                            createdAt = now, updatedAt = now, expiresAt = 0L
                        ))
                        db.messageDao().clearRequestFlag(peer)
                        db.messageDao().clearRequestFlagSent(peer)
                        val evt = com.google.gson.JsonObject().apply {
                            addProperty("type", "contact-accepted-refresh")
                            addProperty("peer", peer)
                        }
                        MessageBus.emit(evt)
                    }
                }
                MessageBus.emit(json)
            }
            "emergency-allow-update"  -> {
                val me      = Prefs.getUsername(this)
                val contact = json.get("contact")?.safeString() ?: return
                val allow   = try { json.get("allow")?.asInt } catch (_: Exception) { null } ?: return
                scope.launch { db.contactDao().setEmergencyAllow(me, contact, allow) }
                MessageBus.emit(json)
            }
            "emergency-allow-set"     -> MessageBus.emit(json)
            else                      -> MessageBus.emit(json)
        }
    }

    private suspend fun persistIncomingMessage(json: JsonObject) {
        if (json.isJsonNull || !json.has("from") || !json.has("content")) {
            Log.e("PERSIST_DBG", "persistIncomingMessage called with invalid json: $json")
            return
        }
        try {
            val from = json.get("from").safeString() ?: run {
                return
            }
            val rawContent = json.get("content").safeString() ?: run {
                return
            }
            val ts = json.get("timestamp").safeLong()?.takeIf { it > 0L } ?: System.currentTimeMillis()
            val remoteId = json.get("messageId").safeLong() ?: 0L
            val existingCheck = if (remoteId > 0) db.messageDao().getByRemoteId(remoteId, from) else null
            if (existingCheck != null) {
                return
            }
            val replyToId = json.get("replyToId").safeLong()
            val replyToSender = json.get("replyToSender").safeString()?.takeIf { it.isNotEmpty() }
            val replyToContent = json.get("replyToContent").safeString()?.takeIf { it.isNotEmpty() }
            val me         = Prefs.getUsername(this)
            val peerPubKey = Prefs.getPeerPublicKey(this, from)
            if (peerPubKey.isEmpty()) requestPeerKey(from)
            val isEncrypted = remoteId != 0L
            val (content, needsRetry) = when {
                !isEncrypted -> Pair(rawContent, false)
                peerPubKey.isEmpty() -> Pair("[waiting for key]", true)
                else -> try {
                    val dec = CryptoHelper.decryptFromPeer(this, from, peerPubKey, remoteId, rawContent)
                    if (dec != null) Pair(dec, false) else Pair("[decryption failed]", true)
                } catch (e: Exception) {
                    Log.w("FshuService", "decryptFromPeer threw for $from remoteId=$remoteId: ${e.message}")
                    Pair("[decryption failed]", true)
                }
            }
            val isRequest = json.get("isRequest").safeBoolean() ?: false
            val isRead = com.fshu.next.ui.chat.ChatActivity.isActive &&
                com.fshu.next.ui.chat.ChatActivity.currentPeer == from
            val insertedId = db.messageDao().insert(
                Message(from = from, to = me, content = content, type = "text",
                    timestamp = ts, isSent = false, remoteId = remoteId,
                    replyToId = replyToId, replyToSender = replyToSender,
                    replyToContent = replyToContent, isRequest = isRequest,
                    isRead = isRead)
            )
            if (needsRetry) {
                pendingDecryptQueue.getOrPut(from) { mutableListOf() }.add(Pair(insertedId, rawContent))
            }
            // TODO: implement seq replay — server does not yet attach seq to forwarded messages
            val seq = json.get("seq").safeLong() ?: 0L
            if (seq > 0 && seq > WebSocketClient.lastSeq) WebSocketClient.lastSeq = seq
            if (remoteId != 0L) {
                WebSocketClient.send(mapOf(
                    "type" to "delivered", "messageId" to remoteId, "from" to me, "to" to from
                ))
            }
            if (isRequest) {
                val event = JsonObject()
                event.addProperty("type", "request-message")
                event.addProperty("from", from)
                event.addProperty("content", content)
                event.addProperty("messageId", remoteId)
                event.addProperty("timestamp", ts)
                MessageBus.tryEmit(event)
                return
            }
            if (!com.fshu.next.ui.chat.ChatActivity.isActive ||
                com.fshu.next.ui.chat.ChatActivity.currentPeer != from) {
                if (!db.muteDao().isMuted(me, from)) {
                    startActivity(
                        com.fshu.next.ui.MessagePopupActivity.createIntent(
                            this, from, getDisplayName(from), content
                        )
                    )
                }
            }
            notifyMessage(from, content)
            MessageBus.tryEmit(json)
        } catch (e: Exception) {
            Log.e("PERSIST_DBG", "persistIncomingMessage THREW: ${e.message}", e)
            val errEvt = com.google.gson.JsonObject().apply {
                addProperty("type", "ws-dm-debug")
                addProperty("from", json.get("from").safeString() ?: "?")
                addProperty("frame", -1)
                addProperty("error", e.message ?: "unknown")
            }
            MessageBus.tryEmit(errEvt)
        }
    }

    private suspend fun persistIncomingFile(json: JsonObject) {
        val from     = json.get("from").safeString() ?: return
        val filename = json.get("filename").safeString() ?: return
        val mimeType = json.get("mimeType").safeString() ?: "application/octet-stream"
        val ts       = json.get("timestamp").safeDouble()?.toLong() ?: System.currentTimeMillis()
        val fileId   = json.get("fileId").safeString()
        val remoteId = json.get("messageId").safeDouble()?.toLong() ?: 0L
        val msgType  = json.get("type").safeString() ?: "file"   // "file" or "voice"
        val me       = Prefs.getUsername(this)

        if (remoteId > 0 && db.messageDao().getByRemoteId(remoteId, from) != null) return

        val content       = if (msgType == "voice") "\uD83C\uDF99\uFE0F Voice message" else "\uD83D\uDCCE $filename"
        val voiceDuration = if (msgType == "voice") json.get("duration").safeDouble()?.toInt() ?: 0 else 0
        val voiceWaveform = if (msgType == "voice") json.get("waveform").safeString()?.takeIf { it.isNotEmpty() } else null
        val isRequest = json.get("isRequest").safeBoolean() ?: false

        val isRead = com.fshu.next.ui.chat.ChatActivity.isActive &&
            com.fshu.next.ui.chat.ChatActivity.currentPeer == from
        db.messageDao().insert(
            Message(from = from, to = me, content = content,
                type = msgType, filename = filename, mimeType = mimeType,
                fileId = fileId, timestamp = ts, isSent = false, remoteId = remoteId,
                voiceDuration = voiceDuration, voiceWaveform = voiceWaveform,
                isRequest = isRequest, isRead = isRead)
        )

        val seq = json.get("seq").safeDouble()?.toLong() ?: 0L
        if (seq > 0 && seq > WebSocketClient.lastSeq) WebSocketClient.lastSeq = seq

        if (fileId != null) {
            WebSocketClient.send(mapOf("type" to "file-request", "fileId" to fileId, "from" to me))
        }
        if (remoteId != 0L) {
            WebSocketClient.send(mapOf("type" to "delivered", "messageId" to remoteId, "from" to me, "to" to from))
        }
        if (isRequest) {
            val event = JsonObject()
            event.addProperty("type", "request-message")
            event.addProperty("from", from)
            event.addProperty("content", content)
            event.addProperty("messageId", remoteId)
            event.addProperty("timestamp", ts)
            MessageBus.tryEmit(event)
            return
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
        // Distinguish 1-1 ack (numeric messageId) from group ack (UUID string messageId).
        val messageIdStr = json.get("messageId")?.asString ?: return
        val messageIdLong = messageIdStr.toLongOrNull()
        if (messageIdLong == null) {
            // Group message ack — tempId is client UUID, messageIdStr is server UUID
            if (tempId != null) db.messageDao().updateGroupMsgAck(tempId, messageIdStr)
            MessageBus.emit(json)
            return
        }
        // 1-1 text message ack — messageId is our Room PK echoed back.
        db.messageDao().upgradeStatus(messageIdLong, "SENT")
        db.messageDao().updateRemoteId(localId = messageIdLong, remoteId = messageIdLong)
        propagateLocationStatus(messageIdLong, "SENT")
        MessageBus.emit(json)
    }

    private suspend fun handleReactions(json: JsonObject) {
        val messageIdEl = json.get("messageId") ?: return
        val reactionsEl = json.get("reactions")
        val reactionsJson = if (reactionsEl != null && reactionsEl.isJsonArray)
            reactionsEl.toString() else "[]"
        val longId = try { messageIdEl.asLong } catch (_: Exception) { -1L }
        if (longId > 0) {
            db.messageDao().updateReactions(longId, reactionsJson)
        } else {
            val tempId = try { messageIdEl.asString } catch (_: Exception) { return }
            db.messageDao().updateReactionsByTempId(tempId, reactionsJson)
        }
        MessageBus.emit(json)
    }

    private suspend fun handleEdited(json: JsonObject) {
        val messageId = json.get("messageId")?.asDouble?.toLong() ?: return
        val rawContent = json.get("newContent")?.asString ?: return
        val editedAt = json.get("editedAt")?.asDouble?.toLong() ?: System.currentTimeMillis()
        val from = json.get("from")?.asString ?: return
        val to = json.get("to")?.asString ?: return
        val me = Prefs.getUsername(this)
        val peer = if (from == me) to else from
        val peerPubKey = Prefs.getPeerPublicKey(this, peer)
        val content = if (peerPubKey.isNotEmpty())
            CryptoHelper.decryptFromPeer(this, peer, peerPubKey, messageId, rawContent) ?: rawContent
        else rawContent
        db.messageDao().updateEditedContent(messageId, content, editedAt)
        MessageBus.emit(json)
    }

    private suspend fun handleDeletedForAll(json: JsonObject) {
        val msgId = json.get("messageId")?.asDouble?.toLong() ?: return
        db.messageDao().markDeletedForAll(msgId, "[Message deleted]")
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
            // Server never includes groupId in file-request response; recover from Room.
            val groupId = header.get("groupId")?.asString
                ?: db.messageDao().getByFileId(fileId)?.groupId

            val decrypted = if (groupId != null) {
                val group = db.groupDao().getById(groupId)
                val groupKeyHex = group?.groupKey ?: ""
                if (groupKeyHex.isNotEmpty()) {
                    val groupKey = with(com.fshu.next.util.EcdhHelper) { groupKeyHex.fromHex() }
                    CryptoHelper.decryptGroupFile(groupKey, nonceHex, encBytes)
                } else {
                    Log.w("FshuService", "No group key for $groupId — saving raw bytes")
                    encBytes
                }
            } else {
                val peerPubKey = Prefs.getPeerPublicKey(this, from)
                if (peerPubKey.isNotEmpty())
                    CryptoHelper.decryptFileFromPeer(this, from, nonceHex, encBytes)
                else {
                    Log.w("FshuService", "No peer key for $from — saving raw bytes")
                    encBytes
                }
            }

            val localUri = decrypted?.let { saveFileToStorage(filename, mimeType, it) }
            if (localUri != null) {
                db.messageDao().updateFileLocalUri(fileId, localUri)
                Log.d("FshuService", "File saved: $filename → $localUri")
            } else {
                Log.e("FshuService", "Failed to decrypt/save file $filename from $from")
            }

            // For voice messages: populate waveform+duration from binary header if present.
            // This covers cases where the metadata JSON arrived before server was patched.
            val headerType = header.get("type")?.asString ?: "file"
            if (headerType == "voice") {
                val waveformStr = header.get("waveform")?.asString ?: ""
                val duration    = header.get("duration")?.asDouble?.toInt() ?: 0
                if (waveformStr.isNotEmpty() || duration > 0) {
                    db.messageDao().updateVoiceMeta(fileId, waveformStr, duration)
                }
            }
        } catch (e: Exception) {
            Log.e("FshuService", "Binary file handling failed", e)
        }
    }

    private fun deleteCacheFile(uriStr: String) {
        try {
            val u = android.net.Uri.parse(uriStr)
            if (u.scheme == "file") java.io.File(u.path ?: return).delete()
        } catch (_: Exception) {}
    }

    private fun cleanGroupFilesCache() {
        val dir = java.io.File(cacheDir, "group_files")
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        dir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
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
                    if (!msg.groupId.isNullOrEmpty()) {
                        val gUri = msg.localUri
                        if (gUri == null) { db.messageDao().updateStatus(msg.id, "FAILED"); continue }
                        val gGroupId = msg.groupId
                        val group = db.groupDao().getById(gGroupId)
                        if (group == null) { db.messageDao().updateStatus(msg.id, "FAILED"); continue }
                        val gKeyHex = group.groupKey
                        if (gKeyHex.length != 64) { db.messageDao().updateStatus(msg.id, "FAILED"); continue }
                        try {
                            val fileBytes = contentResolver.openInputStream(android.net.Uri.parse(gUri))
                                ?.use { it.readBytes() }
                            if (fileBytes == null) {
                                db.messageDao().updateStatus(msg.id, "FAILED")
                            } else {
                                val gKey = with(com.fshu.next.util.EcdhHelper) { gKeyHex.fromHex() }
                                val (encBytes, nonce) = CryptoHelper.encryptGroupFile(gKey, fileBytes)
                                val nonceHex = CryptoHelper.bytesToHex(nonce)
                                val retryTempId = msg.tempId ?: msg.id.toString()
                                val headerObj = com.google.gson.JsonObject().apply {
                                    addProperty("tempId", retryTempId)
                                    addProperty("from", msg.from)
                                    addProperty("groupId", gGroupId)
                                    addProperty("filename", msg.filename ?: "file")
                                    addProperty("mimeType", msg.mimeType ?: "application/octet-stream")
                                    addProperty("size", encBytes.size)
                                    addProperty("nonce", nonceHex)
                                    addProperty("type", "group-file")
                                    addProperty("messageId", msg.id)
                                    addProperty("timestamp", msg.timestamp)
                                }
                                val headerBytes = headerObj.toString().toByteArray(Charsets.UTF_8)
                                val sink = okio.Buffer()
                                sink.writeInt(headerBytes.size)
                                sink.write(headerBytes)
                                sink.write(encBytes)
                                val sent = WebSocketClient.sendBinary(sink.readByteString())
                                if (!sent) Log.e("FshuService", "Group file retry: sendBinary false for msg ${msg.id}")
                                else deleteCacheFile(gUri)
                            }
                        } catch (e: SecurityException) {
                            Log.w("FshuService", "Group file retry: URI expired for msg ${msg.id}, marking FAILED")
                            db.messageDao().updateStatus(msg.id, "FAILED")
                        } catch (e: java.io.FileNotFoundException) {
                            Log.w("FshuService", "Group file retry: URI expired for msg ${msg.id}, marking FAILED")
                            db.messageDao().updateStatus(msg.id, "FAILED")
                        } catch (e: Exception) {
                            Log.e("FshuService", "Group file retry failed for msg ${msg.id}", e)
                        }
                        continue
                    }
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
                        val dmSent = WebSocketClient.sendBinary(sink.readByteString())
                        if (dmSent) deleteCacheFile(uri)
                    } catch (e: SecurityException) {
                        Log.w("FshuService", "File retry: URI expired for msg ${msg.id}, marking FAILED")
                        db.messageDao().updateStatus(msg.id, "FAILED")
                    } catch (e: java.io.FileNotFoundException) {
                        Log.w("FshuService", "File retry: URI expired for msg ${msg.id}, marking FAILED")
                        db.messageDao().updateStatus(msg.id, "FAILED")
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
        if (db.contactDao().getEmergencyLocationAllow(me, from) == 1) {
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
                    val nick = obj.get("nickname")?.takeIf { !it.isJsonNull }?.asString
                    return if (!nick.isNullOrBlank()) nick else username
                }
            }
            username
        } catch (_: Exception) { username }
    }

    private fun notifyMessage(from: String, content: String) {
        val me = Prefs.getUsername(this)
        if (db.muteDao().isMuted(me, from)) return
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
        val me = Prefs.getUsername(this)
        // Muted contacts: show call screen silently (no ring/vibration), but never block the call.
        // Emergency calls always bypass mute entirely.
        val isMuted = !isEmergency && db.muteDao().isMuted(me, from)

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
            .setVibrate(if (isMuted) longArrayOf(0L) else longArrayOf(0, 1000, 1000, 1000, 1000, 1000, 1000))
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

        if (!isMuted) {
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
        }

        if (isEmergency) startVolumeRamp()

        // Notify caller that we are ringing
        WebSocketClient.send(mapOf("type" to "call-ringing", "from" to me, "to" to from))
    }

    private suspend fun handleIncomingSosMessage(json: JsonObject) {
        val from = json.get("from")?.asString ?: return
        val content = json.get("content")?.asString ?: ""
        val ts = json.get("timestamp")?.asDouble?.toLong() ?: System.currentTimeMillis()
        val me = Prefs.getUsername(this)

        db.messageDao().insert(
            Message(from = from, to = me, content = content, type = "sos-message",
                timestamp = ts, isSent = false)
        )
        val remoteId = json.get("messageId")?.asDouble?.toLong() ?: 0L
        val seq = json.get("seq")?.asDouble?.toLong() ?: 0L
        if (seq > 0 && seq > WebSocketClient.lastSeq) WebSocketClient.lastSeq = seq
        if (remoteId != 0L) {
            WebSocketClient.send(mapOf("type" to "delivered", "messageId" to remoteId, "from" to me, "to" to from))
        }

        val sosTextPreview = try {
            com.google.gson.JsonParser.parseString(content).asJsonObject
                .get("text")?.asString?.take(50)
        } catch (_: Exception) { null } ?: content.take(50).ifEmpty { "Emergency SOS" }

        // 1. play ringtone
        val sosUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val sosRingtone = RingtoneManager.getRingtone(this, sosUri)
        sosRingtone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) sosRingtone.isLooping = true
        sosRingtone.play()
        activeSosRingtone = sosRingtone
        sosPeer = from

        // 2. startVolumeRamp()
        startVolumeRamp()

        // 3. build + post notification
        val chatIntent = Intent(this, com.fshu.next.ui.sos.SosAlertActivity::class.java).apply {
            putExtra(com.fshu.next.ui.sos.SosAlertActivity.EXTRA_PEER, from)
            putExtra(com.fshu.next.ui.sos.SosAlertActivity.EXTRA_PREVIEW, sosTextPreview)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notifId = from.hashCode().and(Int.MAX_VALUE) xor 0x5050
        activeSosNotifId = notifId
        val pi = PendingIntent.getActivity(
            this, notifId, chatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissPi = PendingIntent.getService(
            this, (from.hashCode().and(Int.MAX_VALUE) xor 0x5051),
            Intent(this, FshuService::class.java).apply { action = ACTION_DISMISS_SOS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🚨 SOS from $from")
            .setContentText(sosTextPreview)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pi, true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .addAction(0, getString(R.string.btn_close), dismissPi)
            .setContentIntent(pi)
            .build()
        getSystemService(NotificationManager::class.java).notify(notifId, notif)

        // 4. wake lock — only if screen is off, mirroring notifyCall()
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "fshu:sos_alarm"
            ).acquire(10_000L)
        }
        // Screen on (locked or unlocked): fullScreenIntent handles it

        MessageBus.tryEmit(json)
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

    // -------------------------------------------------------------------------
    // Group handlers (Phase 3b)
    // -------------------------------------------------------------------------

    private suspend fun handleGroupState(json: JsonObject) {
        val groupId   = json.get("groupId")?.asString ?: return
        val name      = json.get("name")?.asString ?: return
        val owner     = json.get("owner")?.asString ?: return
        val groupType = json.get("groupType")?.asString ?: "private"
        val encKey    = json.get("encryptedGroupKey")?.takeIf { !it.isJsonNull }?.asString ?: ""

        val existing  = db.groupDao().getById(groupId)
        val createdAt = existing?.createdAt ?: System.currentTimeMillis()
        db.groupDao().upsert(Group(
            groupId = groupId, name = name, owner = owner, groupType = groupType,
            createdAt = createdAt, encryptedGroupKey = encKey,
            groupKey = existing?.groupKey ?: "",
            avatarPath = existing?.avatarPath,
            personalAvatar = existing?.personalAvatar
        ))

        val avatarDataB64 = json.get("avatarData")?.takeIf { !it.isJsonNull }?.asString
        if (!avatarDataB64.isNullOrEmpty()) {
            try {
                val dir = File(applicationContext.filesDir, "avatars").also { it.mkdirs() }
                val file = File(dir, "group_$groupId.jpg")
                file.writeBytes(android.util.Base64.decode(avatarDataB64, android.util.Base64.DEFAULT))
                db.groupDao().updateAvatar(groupId, file.absolutePath)
            } catch (e: Exception) {
                Log.e("FshuService", "group-state avatar save failed", e)
            }
        }

        if (encKey.isNotEmpty()) {
            val me       = Prefs.getUsername(this)
            val myPriv   = Prefs.getEcPrivateKey(this)
            val ownerPub = if (owner == me) Prefs.getEcPublicKey(this)
                           else (db.peerKeyDao().get(owner)?.publicKey ?: Prefs.getPeerPublicKey(this, owner))
            if (myPriv.isNotEmpty() && ownerPub.isNotEmpty()) {
                val groupKey = CryptoHelper.decryptGroupKey(encKey, ownerPub, myPriv)
                if (groupKey != null) {
                    db.groupDao().updateGroupKey(groupId, with(EcdhHelper) { groupKey.toHex() })
                    writeGroupDebug("handleGroupState: stored groupKey for $groupId len=${groupKey.size} hex=${with(EcdhHelper){groupKey.toHex()}.take(8)}")
                    retryDecryptPendingBlobs(groupId, groupKey)
                } else {
                    Log.w("FshuService", "handleGroupState: group key decryption failed for $groupId")
                    writeGroupDebug("handleGroupState: key decrypt FAILED for $groupId ownerPub_len=${ownerPub.length}")
                    // Clear stale local group key so handleGroupKeyNeeded triggers regeneration
                    db.groupDao().clearGroupKey(groupId)
                    writeGroupDebug("handleGroupState: cleared stale local groupKey for $groupId")
                }
            } else {
                Log.w("FshuService", "group-state: skipping key decrypt for $groupId — ownerPub not yet available (owner=$owner), will retry when key arrives")
            }
        }

        val membersEl = json.getAsJsonArray("members") ?: return
        val members   = membersEl.mapNotNull { el ->
            val obj      = el.asJsonObject
            val username = obj.get("username")?.asString ?: return@mapNotNull null
            val role     = obj.get("role")?.asString ?: "member"
            val joinedAt = obj.get("joinedAt")?.asDouble?.toLong() ?: System.currentTimeMillis()
            GroupMember(groupId = groupId, username = username, role = role, joinedAt = joinedAt)
        }
        db.groupMemberDao().removeAll(groupId)
        db.groupMemberDao().upsertAll(members)

        MessageBus.emit(json)
    }

    private suspend fun retryPendingGroupKeyDecryption(ownerUsername: String) {
        val me     = Prefs.getUsername(this)
        val myPriv = Prefs.getEcPrivateKey(this)
        if (myPriv.isEmpty()) return
        val ownerPub = if (ownerUsername == me) Prefs.getEcPublicKey(this)
                       else db.peerKeyDao().get(ownerUsername)?.publicKey ?: return
        if (ownerPub.isEmpty()) return
        val groups = db.groupDao().getAllGroupsDirect()
        for (group in groups) {
            if (group.groupKey.isNotEmpty()) continue
            if (group.owner != ownerUsername) continue
            val encKey = group.encryptedGroupKey
            if (encKey.isEmpty()) continue
            val groupKey = CryptoHelper.decryptGroupKey(encKey, ownerPub, myPriv)
            if (groupKey != null) {
                db.groupDao().updateGroupKey(group.groupId, with(EcdhHelper) { groupKey.toHex() })
                Log.d("FshuService", "retryPendingGroupKeyDecryption: key resolved for ${group.groupId} (owner=$ownerUsername)")
                retryDecryptPendingBlobs(group.groupId, groupKey)
            } else {
                Log.w("FshuService", "retryPendingGroupKeyDecryption: decrypt still failed for ${group.groupId} (owner=$ownerUsername)")
            }
        }
    }

    private suspend fun retryDecryptPendingBlobs(groupId: String, groupKey: ByteArray) {
        val blobs = db.messageDao().getEncryptedGroupBlobs(groupId)
        if (blobs.isEmpty()) return
        writeGroupDebug("retryDecryptPendingBlobs: found ${blobs.size} blobs for $groupId")
        var fixed = 0
        for (msg in blobs) {
            try {
                val plain = CryptoHelper.decryptGroupMessage(groupKey, msg.content)
                if (plain != null) {
                    db.messageDao().updateContent(msg.id, plain)
                    db.messageDao().updateEncryptedBlob(msg.id, false)
                    fixed++
                }
            } catch (e: Exception) {
                writeGroupDebug("retryDecryptPendingBlobs: failed for msg ${msg.id}: ${e.message}")
            }
        }
        writeGroupDebug("retryDecryptPendingBlobs: fixed $fixed / ${blobs.size} blobs for $groupId")
    }

    private suspend fun persistGroupMessage(json: JsonObject) {
        val groupId     = json.get("groupId")?.asString ?: return
        val from        = json.get("from")?.asString ?: return
        val rawContent  = json.get("content")?.asString ?: return
        val serverMsgId = json.get("messageId")?.asString ?: return
        val ts          = json.get("timestamp")?.asDouble?.toLong() ?: System.currentTimeMillis()
        val me          = Prefs.getUsername(this)

        if (db.messageDao().getByTempId(serverMsgId) != null) return

        val group       = db.groupDao().getById(groupId)
        if (group == null) {
            WebSocketClient.send(mapOf("type" to "group-info-request", "groupId" to groupId))
        }
        val groupKeyHex = group?.groupKey ?: ""
        writeGroupDebug("persistGroupMessage: groupId=$groupId group=${group?.groupId} groupKeyHex_len=${groupKeyHex.length} groupKeyHex_start=${groupKeyHex.take(8)}")
        val decrypted   = if (groupKeyHex.isNotEmpty()) {
            writeGroupDebug("persistGroupMessage: attempting decrypt, key=${groupKeyHex.take(8)} rawContent_len=${rawContent.length}")
            val key = with(EcdhHelper) { groupKeyHex.fromHex() }
            CryptoHelper.decryptGroupMessage(key, rawContent)
        } else null
        val isBlob = decrypted == null
        val content = decrypted ?: rawContent
        writeGroupDebug("persistGroupMessage: decrypt result=${if (isBlob) "FAILED/fallback" else "OK"}")

        val isRead = from == me || (com.fshu.next.ui.chat.ChatActivity.isActive &&
            com.fshu.next.ui.chat.ChatActivity.currentPeer == groupId)
        db.messageDao().insert(
            Message(
                from = from, to = "", content = content, type = "text",
                timestamp = ts, isSent = from == me, remoteId = 0,
                tempId = serverMsgId, groupId = groupId,
                encryptedBlob = isBlob, isRead = isRead
            )
        )

        if (from != me) {
            WebSocketClient.send(mapOf(
                "type" to "group-delivered", "groupId" to groupId, "messageId" to serverMsgId
            ))
            if (!com.fshu.next.ui.chat.ChatActivity.isActive ||
                com.fshu.next.ui.chat.ChatActivity.currentPeer != groupId) {
                if (!db.muteDao().isMuted(me, groupId)) {
                    val groupName = group?.name ?: groupId
                    notifyGroupMessage(groupId, groupName, from, content)
                }
            }
        }

        MessageBus.tryEmit(json)
        MessageBus.emit(JsonObject().apply { addProperty("type", "group-preview-update") })
    }

    private suspend fun persistIncomingGroupFile(json: JsonObject) {
        val me       = Prefs.getUsername(this)
        val groupId  = json.get("groupId")?.asString ?: return
        val from     = json.get("from")?.asString ?: return
        if (from == me) return
        val filename = json.get("filename")?.asString ?: return
        val mimeType = json.get("mimeType")?.asString ?: "application/octet-stream"
        val ts       = json.get("timestamp")?.asLong ?: System.currentTimeMillis()
        val fileId   = json.get("fileId")?.asString ?: return

        val isRead = com.fshu.next.ui.chat.ChatActivity.isActive &&
            com.fshu.next.ui.chat.ChatActivity.currentPeer == groupId
        db.messageDao().insert(
            Message(from = from, to = "", content = "📎 $filename",
                type = "file", filename = filename, mimeType = mimeType,
                fileId = fileId, timestamp = ts, isSent = false,
                groupId = groupId, isRead = isRead)
        )
        WebSocketClient.send(mapOf("type" to "file-request", "fileId" to fileId, "from" to me))
        MessageBus.tryEmit(json)
    }

    private suspend fun handleGroupRemoved(json: JsonObject) {
        val groupId = json.get("groupId")?.asString ?: return
        val groupName = db.groupDao().getById(groupId)?.name ?: "a group"
        db.groupDao().deleteById(groupId)
        db.messageDao().deleteMessagesForGroup(groupId)

        val reason    = json.get("reason")?.asString ?: "removed"
        val notifText = when (reason) {
            "kicked"  -> "You were removed from $groupName"
            "deleted" -> "$groupName was deleted"
            else      -> "You left $groupName"
        }

        if (!com.fshu.next.ui.chat.ChatActivity.isActive) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this, groupId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, CHANNEL_GROUPS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_group_update))
                .setContentText(notifText)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            getSystemService(android.app.NotificationManager::class.java)
                .notify(groupId.hashCode(), notif)
        }

        MessageBus.emit(json)
    }

    private suspend fun handleGroupDeliveryUpdate(json: JsonObject) {
        val serverMsgId = json.get("messageId")?.asString ?: return
        val status = when (json.get("status")?.asString) {
            "delivered" -> "DELIVERED"
            "read"      -> "READ"
            else        -> return
        }
        val msg = db.messageDao().getByTempId(serverMsgId) ?: return
        db.messageDao().upgradeStatus(msg.id, status)
        MessageBus.emit(json)
    }

    private suspend fun handleGroupKeyUpdate(json: JsonObject) {
        val groupId = json.get("groupId")?.asString ?: return
        WebSocketClient.send(mapOf("type" to "group-info-request", "groupId" to groupId))
    }

    private suspend fun handleMuteUpdated(json: JsonObject) {
        val target = json.get("target")?.asString ?: return
        val muted = json.get("muted")?.asBoolean ?: return
        val me = Prefs.getUsername(this)
        if (muted) {
            db.muteDao().insert(Mute(owner = me, target = target, targetType = json.get("targetType")?.asString ?: "contact", createdAt = System.currentTimeMillis()))
        } else {
            db.muteDao().delete(me, target)
        }
    }

    private suspend fun handleGroupKeyNeeded(json: JsonObject) {
        val groupId = json.get("groupId")?.asString ?: return
        val me      = Prefs.getUsername(this)
        writeGroupDebug("handleGroupKeyNeeded groupId=$groupId")

        // Format B: { groupId, forUser, forUserPublicKey } — server detected a missing key slot.
        // If forUser == me, I'm the owner who must regenerate the group key from scratch.
        // If forUser == some member, I'm the owner who must encrypt the existing key for them.
        val usernameField = json.get("forUser")?.takeIf { !it.isJsonNull }?.asString
        writeGroupDebug("forUser=$usernameField me=$me")
        if (usernameField != null) {
            if (usernameField == me) {
                writeGroupDebug("owner lost own key, regenerating")
                handleGroupKeyRegenerate(groupId)
            } else {
                val group = db.groupDao().getById(groupId)
                writeGroupDebug("group found=${group != null} owner=${group?.owner} groupKey empty=${group?.groupKey?.isEmpty()}")
                if (group == null) return
                if (group.owner != me) return
                val groupKeyHex = group.groupKey.takeIf { it.isNotEmpty() } ?: run {
                    writeGroupDebug("groupKey empty, regenerating")
                    handleGroupKeyRegenerate(groupId); return
                }
                writeGroupDebug("memberPubKey source: forUserPublicKey=${json.get("forUserPublicKey")?.asString?.length} db=${db.peerKeyDao().get(usernameField)?.publicKey?.length}")
                val memberPubKey = json.get("forUserPublicKey")?.asString?.takeIf { it.isNotEmpty() }
                    ?: db.peerKeyDao().get(usernameField)?.publicKey
                    ?: Prefs.getPeerPublicKey(this, usernameField).takeIf { it.isNotEmpty() }
                    ?: run { writeGroupDebug("memberPubKey not found, dropping"); return }
                val myPriv = Prefs.getEcPrivateKey(this)
                writeGroupDebug("myPriv empty=${myPriv.isEmpty()}")
                if (myPriv.isEmpty()) return
                val encryptedKey = CryptoHelper.encryptGroupKeyForMember(
                    with(EcdhHelper) { groupKeyHex.fromHex() }, memberPubKey, myPriv)
                writeGroupDebug("sending group-key-submit for $usernameField")
                WebSocketClient.send(mapOf(
                    "type"         to "group-key-submit",
                    "groupId"      to groupId,
                    "forUser"      to usernameField,
                    "encryptedKey" to encryptedKey
                ))
            }
            return
        }

        // Format A: { groupId, memberUsername, memberPublicKey } — member's key just changed.
        val memberUsername  = json.get("memberUsername")?.asString ?: return
        val memberPublicKey = json.get("memberPublicKey")?.asString ?: return
        val group           = db.groupDao().getById(groupId) ?: return
        if (group.owner != me) return
        val groupKeyHex = group.groupKey.takeIf { it.isNotEmpty() } ?: return
        val groupKey    = with(EcdhHelper) { groupKeyHex.fromHex() }
        val myPriv      = Prefs.getEcPrivateKey(this)
        writeGroupDebug("myPriv empty=${myPriv.isEmpty()}")
        if (myPriv.isEmpty()) return
        val encryptedKey = CryptoHelper.encryptGroupKeyForMember(groupKey, memberPublicKey, myPriv)
        writeGroupDebug("sending group-key-submit for $memberUsername")
        WebSocketClient.send(mapOf(
            "type"         to "group-key-submit",
            "groupId"      to groupId,
            "forUser"      to memberUsername,
            "encryptedKey" to encryptedKey
        ))
    }

    private suspend fun handleGroupKeyRegenerate(groupId: String) {
        writeGroupDebug("handleGroupKeyRegenerate groupId=$groupId")
        val me     = Prefs.getUsername(this)
        val myPriv = Prefs.getEcPrivateKey(this)
        writeGroupDebug("myPriv empty=${myPriv.isEmpty()}")
        if (myPriv.isEmpty()) return
        val myPub  = Prefs.getEcPublicKey(this).takeIf { it.isNotEmpty() } ?: return

        val members = db.groupMemberDao().getMembersOf(groupId)
        writeGroupDebug("members count=${members.size}")
        if (members.isEmpty()) {
            // Members not cached locally — fetch group state; handleGroupState will re-trigger.
            WebSocketClient.send(mapOf("type" to "group-info-request", "groupId" to groupId))
            return
        }

        val newGroupKey    = CryptoHelper.generateGroupKey()
        val newGroupKeyHex = with(EcdhHelper) { newGroupKey.toHex() }
        db.groupDao().updateGroupKey(groupId, newGroupKeyHex)
        writeGroupDebug("new group key generated for $groupId")

        for (member in members) {
            val memberPubKey: String? = if (member.username == me) myPub
                else db.peerKeyDao().get(member.username)?.publicKey
                    ?: Prefs.getPeerPublicKey(this, member.username).takeIf { it.isNotEmpty() }
            writeGroupDebug("encrypting for ${member.username} pubKey found=${memberPubKey != null}")
            if (memberPubKey.isNullOrEmpty()) {
                Log.w("FshuService", "handleGroupKeyRegenerate: no pubkey for ${member.username}, skipping")
                continue
            }
            try {
                val encryptedKey = CryptoHelper.encryptGroupKeyForMember(newGroupKey, memberPubKey, myPriv)
                writeGroupDebug("sending group-key-submit for ${member.username}")
                WebSocketClient.send(mapOf(
                    "type"         to "group-key-submit",
                    "groupId"      to groupId,
                    "forUser"      to member.username,
                    "encryptedKey" to encryptedKey
                ))
            } catch (e: Exception) {
                Log.w("FshuService", "handleGroupKeyRegenerate: encrypt failed for ${member.username}: ${e.message}")
            }
        }
    }

    private suspend fun handleGroupAvatar(json: JsonObject) {
        val groupId = json.get("groupId")?.asString ?: return
        val data    = json.get("data")?.asString ?: return
        try {
            val dir  = File(applicationContext.filesDir, "avatars").also { it.mkdirs() }
            val file = File(dir, "group_$groupId.jpg")
            file.writeBytes(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
            db.groupDao().updateAvatar(groupId, file.absolutePath)
            val evt = com.google.gson.JsonObject().apply {
                addProperty("type", "group-avatar-update")
                addProperty("groupId", groupId)
            }
            MessageBus.emit(evt)
        } catch (e: Exception) {
            Log.e("FshuService", "group-avatar save failed", e)
        }
    }

    private fun notifyGroupMessage(groupId: String, groupName: String, from: String, content: String) {
        try {
            val am = getSystemService(android.media.AudioManager::class.java)
            if (am.ringerMode != android.media.AudioManager.RINGER_MODE_SILENT) {
                val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    getSystemService(android.os.VibratorManager::class.java).defaultVibrator
                else @Suppress("DEPRECATION") getSystemService(android.os.Vibrator::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    vib.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 250, 250, 250), -1))
            }
        } catch (_: Exception) {}

        val intent = Intent(this, com.fshu.next.ui.chat.ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(com.fshu.next.ui.chat.ChatActivity.EXTRA_GROUP_ID, groupId)
        }
        val pi = PendingIntent.getActivity(
            this, groupId.hashCode().and(Int.MAX_VALUE), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_GROUPS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$groupName · $from")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(groupId.hashCode().and(Int.MAX_VALUE), notif)
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
                val connected = WebSocketClient.isConnected
                val lastPong = WebSocketClient.lastPongTimestamp
                val pongStale = lastPong > 0 && (System.currentTimeMillis() - lastPong) > 45_000
                if ((!connected || pongStale) && !WebSocketClient.isConnectingNow) {
                    Log.w("FshuService", "Watchdog: connected=$connected pongStale=$pongStale — forcing reconnect")
                    if (pongStale && connected) WebSocketClient.disconnect()
                    delay(300)
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
                val netId = network.networkHandle
                if (netId == lastNetworkId) return
                lastNetworkId = netId
                // Skip the immediate onAvailable fired at registration time
                val uptime = System.currentTimeMillis() - serviceStartTime
                if (uptime < 3000) return
                Log.d("FshuService", "Network available (id=$netId uptime=${uptime}ms) — forcing reconnect")
                scope.launch {
                    delay(500)
                    if (!WebSocketClient.isConnected) {
                        connect(url, username, password)
                    } else {
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
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, getString(R.string.notif_channel_messages),
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.notif_channel_messages_desc)
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
            NotificationChannel(CHANNEL_CALLS, getString(R.string.notif_channel_calls), NotificationManager.IMPORTANCE_MAX).apply {
                setSound(null, null)
                enableVibration(true)
                setVibrationPattern(longArrayOf(0, 1000, 1000, 1000, 1000, 1000, 1000))
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_GROUPS, getString(R.string.notif_channel_groups), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
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
        .setContentTitle(getString(R.string.notif_channel_service))
        .setContentText(getString(R.string.notif_connected))
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .build()

    private fun writeGroupDebug(msg: String) {
        try {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            File(filesDir, "group_debug.txt").appendText("$ts $msg\n")
        } catch (_: Exception) {}
    }
}
