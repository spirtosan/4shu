package com.fshu.next.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.JsonObject
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionTestSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_PEER = "peer"

        fun newInstance(peer: String, @Suppress("UNUSED_PARAMETER") online: Boolean) = ConnectionTestSheet().apply {
            arguments = bundleOf(ARG_PEER to peer)
        }

        private const val TURN_USER        = "fshu"
        private const val TURN_PASS        = "kWoQPR9m0YPHAds53Dojh6xcc6yXQsrVfRCaMav0bNA="
        private const val TURN_TIMEOUT_MS  = 6_000L

        @ColorInt private val COLOR_GREEN = Color.parseColor("#4CAF50")
        @ColorInt private val COLOR_AMBER = Color.parseColor("#FFC107")
        @ColorInt private val COLOR_RED   = Color.parseColor("#F44336")
        @ColorInt private val COLOR_GRAY  = Color.parseColor("#9E9E9E")
    }

    private val peer get() = arguments?.getString(ARG_PEER) ?: ""

    private fun serverHost(): String =
        try { java.net.URI(Prefs.getServerUrl(requireContext())).host ?: "localhost" }
        catch (e: Exception) { "localhost" }

    private fun turnUrl(): String = "turn:${serverHost()}:3478"

    private lateinit var dotWs:      View
    private lateinit var tvWs:       TextView
    private lateinit var dotDns:     View
    private lateinit var tvDns:      TextView
    private lateinit var dotTurn:    View
    private lateinit var tvTurn:     TextView
    private lateinit var dotPeer:    View
    private lateinit var tvPeer:     TextView
    private lateinit var btnRunTest: Button
    private lateinit var tvOverall:  TextView

    private val running = AtomicBoolean(false)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_connection_test, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.tvCtSubtitle).text = "Testing with $peer"

        dotWs      = view.findViewById(R.id.dotWs)
        tvWs       = view.findViewById(R.id.tvWs)
        dotDns     = view.findViewById(R.id.dotDns)
        tvDns      = view.findViewById(R.id.tvDns)
        dotTurn    = view.findViewById(R.id.dotTurn)
        tvTurn     = view.findViewById(R.id.tvTurn)
        dotPeer    = view.findViewById(R.id.dotPeer)
        tvPeer     = view.findViewById(R.id.tvPeer)
        view.findViewById<TextView>(R.id.tvPeerLabel).text = "Server → $peer"
        btnRunTest = view.findViewById(R.id.btnRunTest)
        tvOverall  = view.findViewById(R.id.tvOverall)

        resetUi()

        btnRunTest.setOnClickListener {
            if (!running.compareAndSet(false, true)) return@setOnClickListener
            btnRunTest.isEnabled = false
            tvOverall.text = ""
            resetUi()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                runTests()
            }
        }
    }

    private fun resetUi() {
        listOf(dotWs, dotDns, dotTurn, dotPeer).forEach { setDot(it, COLOR_GRAY) }
        listOf(tvWs, tvDns, tvTurn, tvPeer).forEach { it.text = "…" }
    }

    private suspend fun runTests() {
        var greenCount = 0

        // ── Test 1: WebSocket ──
        val connected = WebSocketClient.isConnected
        if (connected) {
            setRow(dotWs, tvWs, "Connected", COLOR_GREEN); greenCount++
        } else {
            setRow(dotWs, tvWs, "Disconnected", COLOR_RED)
        }

        // ── Test 2: DNS ──
        data class DnsResult(val ok: Boolean, val label: String)
        val host = serverHost()
        val dns = withContext(Dispatchers.IO) {
            try {
                val addr = InetAddress.getByName(host)
                DnsResult(true, "OK (${addr.hostAddress})")
            } catch (e: UnknownHostException) {
                DnsResult(false, "Failed")
            }
        }
        val dnsOk = dns.ok
        setRow(dotDns, tvDns, dns.label, if (dnsOk) COLOR_GREEN else COLOR_RED)
        if (dnsOk) greenCount++

        // ── Test 3: TURN relay ──
        if (!dnsOk) {
            setRow(dotTurn, tvTurn, "Skipped", COLOR_GRAY)
        } else {
            try {
                val appCtx = requireContext().applicationContext
                val relayFound = withContext(Dispatchers.IO) { turnReachabilityTest(appCtx) }
                if (relayFound) {
                    setRow(dotTurn, tvTurn, "Relay OK", COLOR_GREEN); greenCount++
                } else {
                    setRow(dotTurn, tvTurn, "No relay candidate", COLOR_RED)
                }
            } catch (e: Exception) {
                setRow(dotTurn, tvTurn, "Error: ${e.message}", COLOR_AMBER)
            }
        }

        // ── Test 4: Peer reachability ──
        setRow(dotPeer, tvPeer, "Testing…", COLOR_GRAY)
        if (peer.isNotEmpty() && WebSocketClient.isConnected) {
            val testId = UUID.randomUUID().toString()
            val me = Prefs.getUsername(requireContext())
            WebSocketClient.send(mapOf("type" to "peer-test-request", "from" to me, "to" to peer, "testId" to testId))
            val result: JsonObject? = withTimeoutOrNull(15_000L) {
                MessageBus.events
                    .filter { it.get("type")?.asString == "peer-test-result" && it.get("testId")?.asString == testId }
                    .first()
            }
            when {
                result == null ->
                    setRow(dotPeer, tvPeer, "✗ No response", COLOR_GRAY)
                result.get("success")?.asBoolean == true -> {
                    val ms = result.get("latencyMs")?.asInt ?: 0
                    setRow(dotPeer, tvPeer, "✓ ${ms}ms", COLOR_GREEN); greenCount++
                }
                result.get("reason")?.asString == "offline" -> {
                    val lastSeen = result.get("lastSeen")?.takeIf { !it.isJsonNull }?.asLong
                    val label = if (lastSeen != null) {
                        val diff = System.currentTimeMillis() - lastSeen
                        val minutes = diff / 60_000
                        val hours   = diff / 3_600_000
                        val days    = diff / 86_400_000
                        val ago = when {
                            minutes < 1  -> "just now"
                            minutes < 60 -> "${minutes}m ago"
                            hours < 24   -> "${hours}h ago"
                            else         -> "${days}d ago"
                        }
                        "✗ Offline · last seen $ago"
                    } else "✗ Offline"
                    setRow(dotPeer, tvPeer, label, COLOR_GRAY)
                }
                else ->
                    setRow(dotPeer, tvPeer, "✗ Timeout", COLOR_RED)
            }
        } else {
            setRow(dotPeer, tvPeer, "✗ Not connected", COLOR_GRAY)
        }

        // ── Summary ──
        tvOverall.text = when (greenCount) {
            4    -> "✓ All tests passed"
            3    -> "⚠ $greenCount/4 passed"
            2    -> "⚠ $greenCount/4 passed"
            else -> "✗ $greenCount/4 passed — check connection"
        }
        btnRunTest.isEnabled = true
        running.set(false)
    }

    private suspend fun turnReachabilityTest(appCtx: android.content.Context): Boolean {
        val turnUrl = try {
            val h = java.net.URI(Prefs.getServerUrl(appCtx)).host ?: "localhost"
            "turn:$h:3478"
        } catch (e: Exception) { "turn:localhost:3478" }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(appCtx)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()

        val iceServer = PeerConnection.IceServer
            .builder(turnUrl)
            .setUsername(TURN_USER)
            .setPassword(TURN_PASS)
            .createIceServer()

        val config = PeerConnection.RTCConfiguration(listOf(iceServer)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }

        val found = CompletableDeferred<Boolean>()

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                if (c.sdp.contains("typ relay")) found.complete(true)
            }
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) found.complete(false)
            }
            override fun onSignalingChange(p: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p: Boolean) {}
            override fun onIceCandidatesRemoved(p: Array<out IceCandidate>?) {}
            override fun onAddStream(p: MediaStream?) {}
            override fun onRemoveStream(p: MediaStream?) {}
            override fun onDataChannel(p: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        }

        val pc = factory.createPeerConnection(config, observer)!!
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)

        val offerDeferred = CompletableDeferred<SessionDescription>()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { offerDeferred.complete(sdp) }
            override fun onCreateFailure(msg: String?) { offerDeferred.completeExceptionally(Exception(msg)) }
            override fun onSetSuccess() {}
            override fun onSetFailure(msg: String?) {}
        }, MediaConstraints())

        val offer = offerDeferred.await()

        val setDeferred = CompletableDeferred<Unit>()
        pc.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() { setDeferred.complete(Unit) }
            override fun onSetFailure(msg: String?) { setDeferred.completeExceptionally(Exception(msg)) }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(msg: String?) {}
        }, offer)
        setDeferred.await()

        val result = withTimeoutOrNull(TURN_TIMEOUT_MS) { found.await() } ?: false

        pc.close()
        pc.dispose()
        factory.dispose()

        return result
    }

    private fun setRow(dot: View, tv: TextView, text: String, @ColorInt color: Int) {
        setDot(dot, color)
        tv.text = text
        tv.setTextColor(color)
    }

    private fun setDot(dot: View, @ColorInt color: Int) {
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }
}
