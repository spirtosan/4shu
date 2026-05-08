package com.fshu.next.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fshu.next.util.Prefs
import kotlinx.coroutines.*
import org.webrtc.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WebRTCManager(
    private val context: Context,
    val isVideoCall: Boolean,
    private val onIceCandidate: (IceCandidate) -> Unit,
    private val onCallEnded: () -> Unit
) {
    private val TAG = "WebRTCManager"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var iceDisconnectJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val eglBase = EglBase.create()
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    @Volatile private var remoteDescriptionSet = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var localAudioTrack: AudioTrack? = null

    // Video
    private var videoCapturer: Camera2Capturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoTrack: VideoTrack? = null
    @Volatile private var remoteVideoTrack: VideoTrack? = null
    @Volatile private var storedLocalRenderer: SurfaceViewRenderer? = null
    @Volatile private var storedRemoteRenderer: SurfaceViewRenderer? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    /**
     * Store renderer references and attach them to any already-existing tracks.
     * The Activity calls this after calling SurfaceViewRenderer.init() on both views.
     */
    fun initVideoRenderers(localView: SurfaceViewRenderer, remoteView: SurfaceViewRenderer) {
        storedLocalRenderer = localView
        storedRemoteRenderer = remoteView
        localVideoTrack?.addSink(localView)
        remoteVideoTrack?.addSink(remoteView)
    }

    /** Remove sinks from tracks before releasing the SurfaceViewRenderers. */
    fun clearVideoRenderers() {
        storedLocalRenderer?.let { localVideoTrack?.removeSink(it) }
        storedRemoteRenderer?.let { remoteVideoTrack?.removeSink(it) }
        storedLocalRenderer = null
        storedRemoteRenderer = null
    }

    fun enableVideo(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    private val iceServers: List<PeerConnection.IceServer> = try {
        val turnUrl = turnUrlFromServerUrl(context)
        val turnUsername = Prefs.getTurnUsername(context)
        val turnPassword = Prefs.getTurnPassword(context)
        if (turnUsername.isNotEmpty()) {
            listOf(
                PeerConnection.IceServer.builder(turnUrl)
                    .setUsername(turnUsername)
                    .setPassword(turnPassword)
                    .createIceServer()
            )
        } else {
            emptyList() // no credentials yet — WebRTC will use direct P2P only
        }
    } catch (e: Exception) {
        Log.e("WebRTCManager", "IceServer build failed, falling back", e)
        emptyList()
    }

    private fun turnUrlFromServerUrl(ctx: Context): String {
        val serverUrl = Prefs.getServerUrl(ctx)
        return try {
            val host = java.net.URI(serverUrl).host ?: return "turn:localhost:3478"
            "turn:$host:3478"
        } catch (e: Exception) {
            "turn:localhost:3478"
        }
    }

    private fun buildRtcConfig(
        transports: PeerConnection.IceTransportsType = PeerConnection.IceTransportsType.ALL
    ) = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        iceTransportsType = transports
    }

    private fun buildPeerConnection(): PeerConnection {
        return factory.createPeerConnection(buildRtcConfig(), object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) = this@WebRTCManager.onIceCandidate(c)
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CHECKING -> {
                        // Let the WebRTC ICE agent handle candidate prioritization.
                        // Forcing RELAY after 3s was causing candidate mismatches and no audio.
                    }
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        iceDisconnectJob?.cancel(); iceDisconnectJob = null
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        iceDisconnectJob?.cancel()
                        iceDisconnectJob = scope.launch {
                            delay(2000)
                            Log.w(TAG, "ICE DISCONNECTED — restarting ICE")
                            peerConnection?.restartIce()
                            delay(5000)
                            val s = peerConnection?.iceConnectionState()
                            if (s != PeerConnection.IceConnectionState.CONNECTED &&
                                s != PeerConnection.IceConnectionState.COMPLETED) {
                                Log.w(TAG, "ICE recovery failed — ending call")
                                onCallEnded()
                            }
                        }
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        iceDisconnectJob?.cancel(); iceDisconnectJob = null
                        onCallEnded()
                    }
                    else -> Unit
                }
            }
            // onTrack fires for UNIFIED_PLAN; onAddTrack may not fire reliably.
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track() ?: return
                if (track is VideoTrack) {
                    mainHandler.post {
                        remoteVideoTrack = track
                        storedRemoteRenderer?.let { track.addSink(it) }
                    }
                }
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onSignalingChange(p: PeerConnection.SignalingState?) {}
            override fun onIceGatheringChange(p: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p: Array<out IceCandidate>?) {}
            override fun onAddStream(p: MediaStream?) {}
            override fun onRemoveStream(p: MediaStream?) {}
            override fun onDataChannel(p: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onIceConnectionReceivingChange(p: Boolean) {}
        }) ?: throw IllegalStateException(
            "createPeerConnection returned null — " +
            "iceServers: ${iceServers.map { it.urls }}"
        )
    }

    private fun addLocalAudio(pc: PeerConnection) {
        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("audio0", audioSource)
        pc.addTrack(localAudioTrack, listOf("stream0"))
    }

    private fun addLocalVideo(pc: PeerConnection) {
        val enumerator = Camera2Enumerator(context)
        val cameraId = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: run { Log.w(TAG, "No camera found"); return }

        val capturer = Camera2Capturer(context, cameraId, null).also { videoCapturer = it }
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val videoSource = factory.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        localVideoTrack = factory.createVideoTrack("video0", videoSource).also { track ->
            pc.addTrack(track, listOf("stream0"))
            mainHandler.post { storedLocalRenderer?.let { track.addSink(it) } }
        }
    }

    fun startCall(onOffer: (SessionDescription) -> Unit) {
        val pc = buildPeerConnection().also { peerConnection = it }
        addLocalAudio(pc)
        if (isVideoCall) addLocalVideo(pc)
        pc.createOffer(sdpObserver(
            onSuccess = { sdp ->
                pc.setLocalDescription(sdpObserver(onSetSuccess = { onOffer(sdp) }), sdp)
            }
        ), MediaConstraints())
    }

    fun handleOffer(sdp: SessionDescription, onAnswer: (SessionDescription) -> Unit) {
        val pc = buildPeerConnection().also { peerConnection = it }
        addLocalAudio(pc)
        if (isVideoCall) addLocalVideo(pc)
        pc.setRemoteDescription(sdpObserver(
            onSetSuccess = {
                flushPendingCandidates()
                pc.createAnswer(sdpObserver(
                    onSuccess = { answer ->
                        pc.setLocalDescription(sdpObserver(onSetSuccess = { onAnswer(answer) }), answer)
                    }
                ), MediaConstraints())
            }
        ), sdp)
    }

    fun handleAnswer(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(sdpObserver(onSetSuccess = {
            flushPendingCandidates()
        }), sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        synchronized(pendingCandidates) {
            if (remoteDescriptionSet) {
                peerConnection?.addIceCandidate(candidate)
            } else {
                pendingCandidates.add(candidate)
                Log.d(TAG, "ICE candidate queued (remote description not yet set), total=${pendingCandidates.size}")
            }
        }
    }

    private fun flushPendingCandidates() {
        synchronized(pendingCandidates) {
            remoteDescriptionSet = true
            Log.d(TAG, "Flushing ${pendingCandidates.size} queued ICE candidates")
            pendingCandidates.forEach { peerConnection?.addIceCandidate(it) }
            pendingCandidates.clear()
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun endCall() {
        iceDisconnectJob?.cancel(); iceDisconnectJob = null
        synchronized(pendingCandidates) {
            remoteDescriptionSet = false
            pendingCandidates.clear()
        }
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        videoCapturer?.dispose()
        videoCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        remoteVideoTrack = null
        storedLocalRenderer = null
        storedRemoteRenderer = null
        peerConnection?.close()
        peerConnection = null
    }

    fun dispose() {
        endCall()
        scope.cancel()
        factory.dispose()
        eglBase.release()
    }

    /**
     * Gathers RELAY-only ICE candidates on the manager's own scope (independent of the
     * caller's lifecycle). Returns true if a relay candidate appears within 10 seconds.
     * Full debug log written to getExternalFilesDir/turn_debug.txt.
     */
    suspend fun checkTurnReachable(): Boolean {
        // resultDeferred bridges the manager's scope to the caller's suspend point.
        // If the caller is cancelled it stops waiting, but the scope.launch below
        // continues and always completes resultDeferred (so the PC is properly closed).
        val resultDeferred = CompletableDeferred<Boolean>()

        scope.launch {
            val debugFile = File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                "turn_debug.txt"
            )
            debugFile.writeText("")

            val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            fun dbg(msg: String) {
                val line = "[${fmt.format(Date())}] $msg\n"
                synchronized(debugFile) { debugFile.appendText(line) }
                Log.d(TAG, msg)
            }

            dbg("=== checkTurnReachable start ===")
            dbg("ICE servers: ${iceServers.map { it.urls }}")

            var tempPc: PeerConnection? = null
            var dummyTrack: AudioTrack? = null
            try {
                val relayFound = CompletableDeferred<Boolean>()

                val observer = object : PeerConnection.Observer {
                    // Callbacks fire on WebRTC internal thread — CompletableDeferred is thread-safe
                    override fun onIceCandidate(c: IceCandidate) {
                        dbg("candidate: ${c.sdp}")
                        if (!relayFound.isCompleted && c.sdp.contains("typ relay")) {
                            dbg("RELAY candidate found — TURN reachable")
                            relayFound.complete(true)
                        }
                    }
                    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                        dbg("[${fmt.format(Date())}] gathering state changed → $s")
                        if (s == PeerConnection.IceGatheringState.COMPLETE && !relayFound.isCompleted) {
                            dbg("gathering COMPLETE — no relay candidate seen")
                            relayFound.complete(false)
                        }
                    }
                    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) { dbg("ICE connection: $s") }
                    override fun onSignalingChange(s: PeerConnection.SignalingState?) { dbg("signaling state: $s") }
                    override fun onIceCandidatesRemoved(p: Array<out IceCandidate>?) {}
                    override fun onAddStream(p: MediaStream?) {}
                    override fun onRemoveStream(p: MediaStream?) {}
                    override fun onDataChannel(p: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onIceConnectionReceivingChange(p: Boolean) {}
                    override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
                    override fun onTrack(t: RtpTransceiver?) {}
                }

                // RELAY-only: only TURN relay candidates will be gathered
                tempPc = factory.createPeerConnection(
                    buildRtcConfig(PeerConnection.IceTransportsType.RELAY), observer
                ) ?: run {
                    dbg("ERROR: createPeerConnection returned null")
                    resultDeferred.complete(false)
                    return@launch
                }
                dbg("temp PeerConnection created (RELAY only)")

                // Add a dummy audio track — TURN servers only allocate relay candidates
                // when there is actual media to relay (a track-less PC may be ignored)
                val audioSource = factory.createAudioSource(MediaConstraints())
                dummyTrack = factory.createAudioTrack("test_audio", audioSource).also {
                    tempPc.addTrack(it, listOf("test_stream"))
                    dbg("dummy audio track added to trigger TURN allocation")
                }

                // Set up local description before awaiting — gathering starts when it is set
                val offerReady = CompletableDeferred<Unit>()
                tempPc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        dbg("offer SDP:\n${sdp.description}")
                        tempPc.setLocalDescription(sdpObserver(onSetSuccess = {
                            dbg("local description set — ICE gathering started")
                            offerReady.complete(Unit)
                        }), sdp)
                    }
                    override fun onCreateFailure(msg: String?) {
                        dbg("offer create FAILED: $msg")
                        offerReady.complete(Unit)
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(msg: String?) {}
                }, MediaConstraints())

                if (withTimeoutOrNull(5_000) { offerReady.await() } == null) {
                    dbg("TIMEOUT waiting for offer/local-desc (5s)")
                }

                // Await relay candidate — timeout only wraps this wait, not the PC setup
                val found = withTimeoutOrNull(10_000) { relayFound.await() }
                if (found == null) dbg("TIMEOUT waiting for relay candidate (10s)")
                val result = found ?: false
                dbg("=== result: relay reachable = $result ===")
                resultDeferred.complete(result)

            } catch (e: CancellationException) {
                dbg("Cancelled: ${e.message}")
                throw e   // must re-throw so the coroutine machinery knows it was cancelled
            } catch (e: Exception) {
                dbg("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                resultDeferred.complete(false)
            } finally {
                // NonCancellable: cleanup runs even if scope was cancelled by dispose()
                withContext(NonCancellable) {
                    dummyTrack?.dispose()
                    dummyTrack = null
                    tempPc?.close()
                    dbg("temp PeerConnection closed")
                    if (!resultDeferred.isCompleted) resultDeferred.complete(false)
                }
            }
        }

        return resultDeferred.await()
    }

    private fun sdpObserver(
        onSuccess: ((SessionDescription) -> Unit)? = null,
        onSetSuccess: (() -> Unit)? = null
    ) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { onSuccess?.invoke(sdp) }
        override fun onSetSuccess() { onSetSuccess?.invoke() }
        override fun onCreateFailure(msg: String?) { Log.e(TAG, "SDP create failed: $msg") }
        override fun onSetFailure(msg: String?) { Log.e(TAG, "SDP set failed: $msg") }
    }
}
