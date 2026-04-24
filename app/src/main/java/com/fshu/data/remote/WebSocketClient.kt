package com.fshu.data.remote

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.atomic.AtomicBoolean

typealias MessageHandler = (JsonObject) -> Unit

object WebSocketClient {
    private const val TAG = "WebSocketClient"
    private const val PING_INTERVAL_MIN_MS = 5_000L
    private const val PING_INTERVAL_MAX_MS = 15_000L
    private const val PONG_TIMEOUT_MS      = 10_000L
    private const val RTT_HISTORY_SIZE     = 5

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)   // essential for WebSockets — no idle timeout
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS) // native WS PING frames keep NAT alive
        .build()

    private var webSocket: WebSocket? = null
    private val handlers = java.util.concurrent.CopyOnWriteArrayList<MessageHandler>()

    @Volatile private var intentionalDisconnect = false

    private val heartbeatScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private val pongReceived = AtomicBoolean(false)
    private val rttHistory = ArrayDeque<Long>(RTT_HISTORY_SIZE)

    /** True between the connect() call and auth-ok/auth-error/failure — prevents duplicate sockets. */
    private val isConnecting = AtomicBoolean(false)
    val isConnectingNow: Boolean get() = isConnecting.get()

    var isConnected = false
        private set

    /** Highest remoteId received from any peer — sent as lastSeq in each ping. */
    @Volatile var lastSeq: Long = 0

    /** Timestamp of the most recent outgoing ping — used to compute RTT. */
    @Volatile private var pingTime: Long = 0

    /** Called on the IO dispatcher each time a heartbeat ping is sent. */
    var onHeartbeat: (() -> Unit)? = null

    /** Called with round-trip latency in ms each time a pong arrives. */
    var onPong: ((latencyMs: Long) -> Unit)? = null

    /** Called with the list of currently online usernames extracted from each pong. */
    var onOnlineUsers: ((List<String>) -> Unit)? = null

    /**
     * Suspend provider called just before each ping; returns {listId → version} map to
     * include in the ping so the server can detect outdated lists.
     */
    var listVersionsProvider: (suspend () -> Map<String, Int>)? = null

    /** Called when pong reports lists whose server version exceeds the client's: (listId, serverVersion). */
    var onOutdatedLists: ((List<Pair<String, Int>>) -> Unit)? = null

    /**
     * Fired on any disconnect (failure or close). Supplements the per-connect [onDisconnected]
     * callback; useful for components that can't hook into the connect call (e.g. CallActivity).
     */
    var onConnectionLost: (() -> Unit)? = null

    /** Fired once on successful auth with the server-issued appSecret hex string. */
    var onAppSecret: ((String) -> Unit)? = null

    /** Fired once on successful auth with the server-reported admin flag. */
    var onAdmin: ((Boolean) -> Unit)? = null

    /** Session token received from server on last successful auth — used for fast resume. */
    @Volatile var sessionToken: String = ""

    /** Called after successful auth or resume — used to send FCM token to server. */
    var onConnectedCallback: (() -> Unit)? = null

    /**
     * Connect to [url], authenticate with [username]/[password].
     *
     * The flow is:
     *  1. TCP/WS opens → client sends `{type:"auth", username, password}`
     *  2. Server replies with `{type:"auth-ok"}` → heartbeat starts, [onConnected] fires
     *     OR `{type:"auth-error"}` → [onAuthError] fires, socket closes (no reconnect).
     *  3. Normal operation until socket closes → [onDisconnected] fires (service reconnects).
     */
    fun connect(
        url: String,
        username: String,
        password: String,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onAuthError: (String) -> Unit = {},
    ) {
        intentionalDisconnect = false
        if (isConnected || !isConnecting.compareAndSet(false, true)) {
            Log.d(TAG, "connect() skipped — already connected or connecting")
            return
        }

        // Safety net: if the connection attempt hangs (Android kills the TCP attempt
        // silently without firing onFailure/onClosed), isConnecting would stay true
        // forever and block all future reconnects. Reset it after 15s if still connecting.
        var connectTimeoutJob: Job? = heartbeatScope.launch {
            delay(15_000)
            if (isConnecting.get()) {
                Log.w(TAG, "connect() timed out after 15s — resetting isConnecting")
                isConnecting.set(false)
                webSocket?.cancel()
                webSocket = null
            }
        }
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                if (sessionToken.isNotEmpty()) {
                    // Try fast resume first — server will respond with auth-ok or resume-error
                    ws.send(gson.toJson(mapOf(
                        "type" to "resume",
                        "sessionToken" to sessionToken,
                        "username" to username,
                        "lastSeq" to lastSeq
                    )))
                } else {
                    ws.send(gson.toJson(mapOf(
                        "type" to "auth",
                        "username" to username,
                        "password" to password,
                        "lastSeq" to lastSeq
                    )))
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = gson.fromJson(text, JsonObject::class.java)
                    when (json.get("type")?.asString) {
                        "auth-ok" -> {
                            connectTimeoutJob?.cancel()
                            connectTimeoutJob = null
                            isConnecting.set(false)
                            isConnected = true
                            val secret = json.get("appSecret")?.asString
                            if (secret != null) onAppSecret?.invoke(secret)
                            val newToken = json.get("sessionToken")?.asString
                            if (!newToken.isNullOrEmpty()) sessionToken = newToken
                            onAdmin?.invoke(json.get("admin")?.asBoolean ?: false)
                            val busEvent = JsonObject()
                            busEvent.addProperty("type", "auth-ok")
                            busEvent.addProperty("admin", json.get("admin")?.asBoolean ?: false)
                            handlers.forEach { it(busEvent) }
                            startHeartbeat(ws)
                            onConnected()
                            onConnectedCallback?.invoke()
                        }
                        "auth-error" -> {
                            isConnecting.set(false)
                            val msg = json.get("message")?.asString ?: "Invalid credentials"
                            Log.w(TAG, "Auth error: $msg")
                            onAuthError(msg)
                            // Do not set isConnected; socket will close server-side.
                        }
                        "resume-error" -> {
                            // Token invalid or expired — fall back to full bcrypt auth
                            Log.d(TAG, "Resume failed, falling back to password auth")
                            sessionToken = ""
                            ws.send(gson.toJson(mapOf(
                                "type" to "auth",
                                "username" to username,
                                "password" to password,
                                "lastSeq" to lastSeq
                            )))
                        }
                        "pong" -> {
                            pongReceived.set(true)
                            val rtt = System.currentTimeMillis() - pingTime
                            if (pingTime > 0) onPong?.invoke(rtt)
                            if (pingTime > 0) {
                                if (rttHistory.size >= RTT_HISTORY_SIZE) rttHistory.removeFirst()
                                rttHistory.addLast(rtt)
                            }
                            val arr = json.getAsJsonArray("onlineUsers")
                            if (arr != null) {
                                val list = arr.map { it.asString }
                                onOnlineUsers?.invoke(list)
                            }
                            val outdated = json.getAsJsonArray("outdatedLists")
                            if (outdated != null && outdated.size() > 0) {
                                val pairs = outdated.mapNotNull { el ->
                                    val obj = el.asJsonObject
                                    val listId = obj.get("listId")?.asString ?: return@mapNotNull null
                                    val sv = obj.get("serverVersion")?.asInt ?: return@mapNotNull null
                                    Pair(listId, sv)
                                }
                                if (pairs.isNotEmpty()) onOutdatedLists?.invoke(pairs)
                            }
                        }
                        else -> handlers.forEach { it(json) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnecting.set(false)
                isConnected = false
                stopHeartbeat()
                Log.e(TAG, "Failure: ${t.message}")
                onConnectionLost?.invoke()
                onDisconnected()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnecting.set(false)
                isConnected = false
                stopHeartbeat()
                val wasIntentional = intentionalDisconnect
                intentionalDisconnect = false
                if (!wasIntentional && (code != 1000 || reason != "auth-failed")) {
                    onConnectionLost?.invoke()
                    onDisconnected()
                }
            }
        })
    }

    private fun startHeartbeat(ws: WebSocket) {
        heartbeatJob?.cancel()
        rttHistory.clear()
        heartbeatJob = heartbeatScope.launch {
            var intervalMs = 10_000L  // start at 10s
            while (isActive) {
                delay(intervalMs)
                onHeartbeat?.invoke()
                pongReceived.set(false)
                pingTime = System.currentTimeMillis()
                val listVersions = listVersionsProvider?.invoke() ?: emptyMap<String, Int>()
                ws.send(gson.toJson(mapOf("type" to "ping", "lastSeq" to lastSeq, "listVersions" to listVersions)))
                delay(PONG_TIMEOUT_MS)
                if (!pongReceived.get()) {
                    Log.w(TAG, "Pong timeout — forcing reconnect")
                    ws.cancel()
                    break
                }
                // Adjust interval based on rolling average RTT
                val avgRtt = if (rttHistory.isNotEmpty()) rttHistory.average() else 0.0
                intervalMs = when {
                    avgRtt == 0.0   -> 10_000L                  // no data yet
                    avgRtt < 100    -> PING_INTERVAL_MAX_MS     // very stable → 30s
                    avgRtt < 300    -> 20_000L                  // good → 20s
                    avgRtt < 800    -> 10_000L                  // moderate → 10s
                    else            -> PING_INTERVAL_MIN_MS     // poor → 5s
                }
                Log.d(TAG, "heartbeat: avgRtt=${avgRtt.toLong()}ms → next interval=${intervalMs/1000}s")
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** Returns true if the message was successfully enqueued on the socket. */
    fun send(data: Map<String, Any?>): Boolean =
        webSocket?.send(gson.toJson(data)) ?: false

    fun addHandler(handler: MessageHandler) = handlers.add(handler)
    fun removeHandler(handler: MessageHandler) = handlers.remove(handler)

    fun disconnect() {
        intentionalDisconnect = true
        isConnecting.set(false)
        stopHeartbeat()
        webSocket?.close(1000, "Bye")
        webSocket = null
        isConnected = false
    }
}
