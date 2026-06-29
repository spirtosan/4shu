package com.fshu.next.ui.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonObject
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityCallBinding
import com.fshu.next.service.FshuService
import com.fshu.next.util.MessageBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PEER = "peer"
        const val EXTRA_IS_CALLER = "is_caller"
        const val EXTRA_OFFER_SDP = "offer_sdp"
        const val EXTRA_IS_VIDEO_CALL = "is_video_call"
        const val EXTRA_IS_EMERGENCY = "is_emergency"
        const val EXTRA_IS_MUTED = "is_muted"
        private const val RC_PERMISSIONS = 1

        /** True while the activity is in the foreground — used to gate end-call vibration. */
        @Volatile var isActive = false
    }

    private lateinit var binding: ActivityCallBinding
    private val vm: CallViewModel by viewModels()
    private lateinit var peer: String
    private var isCaller = false
    private var isVideoCall = false
    private var isEmergency = false
    private var renderersInitialized = false

    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var finishJob: Job? = null
    private var busyPlayer: MediaPlayer? = null

    // Slider state — class-level so onRequestPermissionsResult can reset on mic denial
    private var sliderCommitted = false
    private var sliderSpringBack: (() -> Unit)? = null
    private var pendingGranted: (() -> Unit)? = null
    private var pendingDenied: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
        }

        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peer = intent.getStringExtra(EXTRA_PEER) ?: run { finish(); return }
        isCaller = intent.getBooleanExtra(EXTRA_IS_CALLER, false)
        isVideoCall = intent.getBooleanExtra(EXTRA_IS_VIDEO_CALL, false)
        isEmergency = intent.getBooleanExtra(EXTRA_IS_EMERGENCY, false)
        val offerSdp = intent.getStringExtra(EXTRA_OFFER_SDP)

        // Set video flag BEFORE any ViewModel method that touches webRTC (lazy init depends on it)
        vm.setVideoCall(isVideoCall)

        if (isEmergency) {
            binding.tvEmergencyBanner.visibility = View.VISIBLE
        }
        if (isEmergency && !isCaller) {
            binding.llSilenceBtn.visibility = View.VISIBLE
        }

        if (!isVideoCall) initProximityWakeLock()

        binding.tvPeer.text = peer

        if (isVideoCall) {
            initVideoRenderers()
        }

        vm.state.observe(this) { state ->
            val inCall = state == CallViewModel.State.IN_CALL
            val incoming = state == CallViewModel.State.INCOMING

            if (state != CallViewModel.State.ENDED) {
                binding.tvStatus.text = when (state) {
                    CallViewModel.State.INCOMING -> if (isVideoCall) "Incoming video call…" else "Incoming call…"
                    CallViewModel.State.CALLING  -> if (vm.isRinging.value == true) "Ringing…" else "Calling…"
                    CallViewModel.State.IN_CALL  -> "Connected"
                    else -> ""
                }
            }

            binding.panelIncoming.visibility = if (incoming) View.VISIBLE else View.GONE
            binding.panelBottomBar.visibility = if (!incoming) View.VISIBLE else View.GONE
            binding.panelControls.visibility = if (inCall) View.VISIBLE else View.GONE
            binding.tvDuration.visibility = if (inCall) View.VISIBLE else View.GONE

            if (isVideoCall) {
                binding.surfaceRemote.visibility = if (inCall) View.VISIBLE else View.GONE
                // Camera and flip only shown for video calls while in-call
                binding.btnCamera.visibility = if (inCall) View.VISIBLE else View.GONE
                binding.btnFlipCamera.visibility = if (inCall) View.VISIBLE else View.GONE
            }

            when (state) {
                CallViewModel.State.IN_CALL -> {
                    FshuService.cancelCallNotif(this)
                    // Proximity lock only for audio calls on earpiece
                    if (!isVideoCall && vm.isSpeakerOn.value != true) acquireProximityWakeLock()
                }
                CallViewModel.State.ENDED -> {
                    releaseProximityWakeLock()
                    binding.panelBottomBar.visibility = View.GONE
                    binding.panelIncoming.visibility = View.GONE
                    binding.tvDuration.visibility = View.GONE
                    // Set reason text directly here — endReason is already set before state=ENDED
                    val reason = vm.endReason.value ?: "ended"
                    binding.tvStatus.text = when (reason) {
                        "rejected"     -> "Call declined"
                        "disconnected" -> "Connection lost"
                        "busy"         -> "User is busy"
                        "no_answer"    -> "No answer"
                        else           -> "Call ended"
                    }
                    android.util.Log.d("CallDebug", "state=ENDED reason=$reason tvStatus=${binding.tvStatus.text}")
                    if (reason == "busy") {
                        try {
                            busyPlayer = MediaPlayer().apply {
                                setAudioAttributes(
                                    android.media.AudioAttributes.Builder()
                                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .build()
                                )
                                val uri = android.net.Uri.parse(
                                    "android.resource://$packageName/raw/busy"
                                )
                                setDataSource(this@CallActivity, uri)
                                isLooping = false
                                setOnCompletionListener { it.release(); busyPlayer = null }
                                prepare()
                                start()
                            }
                        } catch (_: Exception) {}
                    }
                    binding.tvStatus.bringToFront()
                    binding.tvStatus.visibility = View.VISIBLE
                    finishJob = lifecycleScope.launch {
                        delay(3000)
                        finish()
                    }
                }
                else -> Unit
            }
        }

        vm.endReason.observe(this) { reason ->
            android.util.Log.d("CallDebug", "endReason observer fired reason=$reason state=${vm.state.value}")
            // Also update text if state is already ENDED (e.g. ICE path sets state before reason)
            if (vm.state.value == CallViewModel.State.ENDED) {
                binding.tvStatus.text = when (reason) {
                    "rejected"     -> "Call declined"
                    "disconnected" -> "Connection lost"
                    "busy"         -> "User is busy"
                    "no_answer"    -> "No answer"
                    else           -> "Call ended"
                }
            }
        }

        vm.callDuration.observe(this) { binding.tvDuration.text = it }

        vm.isRinging.observe(this) { ringing ->
            if (vm.state.value == CallViewModel.State.CALLING) {
                binding.tvStatus.text = if (ringing) "Ringing…" else "Calling…"
            }
        }

        vm.isMuted.observe(this) { muted ->
            if (muted) {
                binding.btnMute.setImageResource(R.drawable.ic_mic_off)
                binding.btnMute.setBackgroundResource(R.drawable.bg_call_button_active)
                binding.btnMute.imageTintList = ColorStateList.valueOf(Color.parseColor("#121224"))
            } else {
                binding.btnMute.setImageResource(R.drawable.ic_mic)
                binding.btnMute.setBackgroundResource(R.drawable.bg_call_button)
                binding.btnMute.imageTintList = null
            }
        }

        vm.isSpeakerOn.observe(this) { speaker ->
            if (speaker) {
                binding.btnSpeaker.setImageResource(R.drawable.ic_speaker)
                binding.btnSpeaker.setBackgroundResource(R.drawable.bg_call_button_active)
                binding.btnSpeaker.imageTintList = ColorStateList.valueOf(Color.parseColor("#121224"))
                releaseProximityWakeLock()
            } else {
                binding.btnSpeaker.setImageResource(R.drawable.ic_earpiece)
                binding.btnSpeaker.setBackgroundResource(R.drawable.bg_call_button)
                binding.btnSpeaker.imageTintList = null
                if (!isVideoCall && vm.state.value == CallViewModel.State.IN_CALL) {
                    acquireProximityWakeLock()
                }
            }
        }

        vm.isCameraOn.observe(this) { on ->
            if (on) {
                binding.btnCamera.setImageResource(R.drawable.ic_videocam)
                binding.btnCamera.setBackgroundResource(R.drawable.bg_call_button)
                binding.btnCamera.imageTintList = null
                if (isVideoCall) binding.surfaceLocal.visibility = View.VISIBLE
            } else {
                binding.btnCamera.setImageResource(R.drawable.ic_videocam_off)
                binding.btnCamera.setBackgroundResource(R.drawable.bg_call_button_active)
                binding.btnCamera.imageTintList = ColorStateList.valueOf(Color.parseColor("#121224"))
                binding.surfaceLocal.visibility = View.GONE
            }
        }

        binding.btnEndCall.setOnClickListener { vm.endCall() }
        binding.btnMute.setOnClickListener { vm.toggleMute() }
        binding.btnSpeaker.setOnClickListener { vm.toggleSpeaker() }
        binding.btnCamera.setOnClickListener { vm.toggleCamera() }
        binding.btnFlipCamera.setOnClickListener { vm.switchCamera() }

        binding.btnSilence.setOnClickListener {
            FshuService.silenceEmergencyRamp(this)
            binding.llSilenceBtn.visibility = View.GONE
        }

        setupIncomingSlider()

        if (isCaller) {
            if (isEmergency) vm.setEmergency(true)
            requestPermissionsForCall(onGranted = { vm.startCall(peer) })
        } else {
            val contactMuted = intent.getBooleanExtra(EXTRA_IS_MUTED, false)
        offerSdp?.let { vm.prepareIncoming(peer, it, isEmergency, contactMuted) } ?: finish()
        }

        lifecycleScope.launch {
            MessageBus.events.collect { handleMessage(it) }
        }

        // If the WebSocket drops while we're waiting for the caller to connect, auto-dismiss.
        WebSocketClient.onConnectionLost = {
            when (vm.state.value) {
                CallViewModel.State.IN_CALL -> {
                    // Mid-call disconnect — end it
                    vm.remoteEndCall("disconnected")
                }
                CallViewModel.State.CALLING -> {
                    // Lost connection while waiting for receiver to answer.
                    // The call-offer was sent on a dead socket — end cleanly
                    // so the user can retry rather than being stuck on "Calling".
                    vm.endCall("disconnected")
                }
                else -> Unit
            }
        }
    }

    private fun setupIncomingSlider() {
        val track = binding.slideTrack
        val handle = binding.slideHandle
        val iconDecline = binding.icSlideDecline
        val iconAccept = binding.icSlideAccept
        var halfTravel = 0f
        var pastThreshold = false
        var downRawX = 0f
        var initTx = 0f

        sliderSpringBack = {
            sliderCommitted = false
            handle.animate().translationX(0f).setDuration(150).start()
            iconAccept.animate().alpha(0.5f).setDuration(150).start()
            iconDecline.animate().alpha(0.5f).setDuration(150).start()
        }

        handle.setOnTouchListener { v, event ->
            if (sliderCommitted) return@setOnTouchListener true
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    halfTravel = (track.width - v.width) / 2f
                    downRawX = event.rawX
                    initTx = v.translationX
                    pastThreshold = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (halfTravel <= 0f) return@setOnTouchListener true
                    val newTx = (initTx + event.rawX - downRawX).coerceIn(-halfTravel, halfTravel)
                    v.translationX = newTx
                    val frac = kotlin.math.abs(newTx) / halfTravel
                    if (newTx >= 0f) {
                        iconAccept.alpha = 0.5f + 0.5f * frac
                        iconDecline.alpha = 0.5f
                    } else {
                        iconDecline.alpha = 0.5f + 0.5f * frac
                        iconAccept.alpha = 0.5f
                    }
                    if (!pastThreshold && kotlin.math.abs(newTx) >= halfTravel * 0.8f) {
                        pastThreshold = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        } else {
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    } else if (pastThreshold && kotlin.math.abs(newTx) < halfTravel * 0.8f) {
                        pastThreshold = false
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (halfTravel <= 0f) return@setOnTouchListener true
                    val tx = v.translationX
                    if (!sliderCommitted && kotlin.math.abs(tx) >= halfTravel * 0.8f) {
                        FshuService.cancelCallNotif(this)
                        if (tx > 0f) {
                            // Accept: lock slider during the permission dialog.
                            // sliderSpringBack resets sliderCommitted=false if mic is denied.
                            sliderCommitted = true
                            requestPermissionsThenAccept()
                        } else {
                            sliderCommitted = true  // decline is terminal
                            vm.rejectCall()
                        }
                    } else {
                        v.animate().translationX(0f).setDuration(150).start()
                        iconAccept.animate().alpha(0.5f).setDuration(150).start()
                        iconDecline.animate().alpha(0.5f).setDuration(150).start()
                        pastThreshold = false
                    }
                    true
                }
                else -> false
            }
        }

        ViewCompat.addAccessibilityAction(track, getString(R.string.btn_accept)) { _, _ ->
            if (!sliderCommitted) {
                // Lock slider during permission dialog; sliderSpringBack resets on denial.
                sliderCommitted = true
                FshuService.cancelCallNotif(this)
                requestPermissionsThenAccept()
            }
            true
        }
        ViewCompat.addAccessibilityAction(track, getString(R.string.btn_decline)) { _, _ ->
            if (!sliderCommitted) {
                sliderCommitted = true  // terminal
                FshuService.cancelCallNotif(this)
                vm.rejectCall()
            }
            true
        }
    }

    private fun initVideoRenderers() {
        val eglCtx = vm.getEglBaseContext()
        binding.surfaceRemote.init(eglCtx, null)
        binding.surfaceLocal.init(eglCtx, null)
        binding.surfaceLocal.setMirror(true)
        binding.surfaceLocal.setZOrderMediaOverlay(true)
        binding.surfaceLocal.visibility = View.VISIBLE
        vm.initVideoRenderers(binding.surfaceLocal, binding.surfaceRemote)
        renderersInitialized = true
        setupDraggableLocalPreview()
    }

    private fun setupDraggableLocalPreview() {
        var downRawX = 0f
        var downRawY = 0f
        var downViewX = 0f
        var downViewY = 0f
        binding.surfaceLocal.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downViewX = view.x
                    downViewY = view.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val root = binding.root
                    val maxX = (root.width - view.width).toFloat()
                    val maxY = (root.height - view.height).toFloat()
                    view.x = (downViewX + event.rawX - downRawX).coerceIn(0f, maxX)
                    view.y = (downViewY + event.rawY - downRawY).coerceIn(0f, maxY)
                }
            }
            true
        }
    }

    private fun initProximityWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "fshu:proximity"
            ).apply { setReferenceCounted(false) }
        }
    }

    private fun acquireProximityWakeLock() {
        val lock = proximityWakeLock ?: return
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseProximityWakeLock() {
        val lock = proximityWakeLock ?: return
        if (lock.isHeld) lock.release()
    }

    private fun requestPermissionsThenAccept() {
        requestPermissionsForCall(
            onGranted = { vm.acceptCall() },
            onDenied = { sliderSpringBack?.invoke() }
        )
    }

    private fun requestPermissionsForCall(onGranted: () -> Unit, onDenied: (() -> Unit)? = null) {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@CallActivity, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) add(android.Manifest.permission.RECORD_AUDIO)
            if (isVideoCall && ContextCompat.checkSelfPermission(this@CallActivity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
        }
        if (needed.isEmpty()) {
            onGranted()
        } else {
            pendingGranted = onGranted
            pendingDenied = onDenied
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == RC_PERMISSIONS) {
            // Require at minimum RECORD_AUDIO; camera denial is non-fatal for video calls
            val audioGranted = results.zip(permissions.toList()).firstOrNull {
                it.second == Manifest.permission.RECORD_AUDIO
            }?.first == PackageManager.PERMISSION_GRANTED
            if (audioGranted) {
                pendingGranted?.invoke()
            } else {
                // Accept path: spring the slider back so the user can retry or decline.
                // Caller path: pendingDenied is null, so finish() is called instead.
                pendingDenied?.invoke() ?: finish()
            }
        }
        pendingGranted = null
        pendingDenied = null
    }

    override fun onResume() {
        super.onResume()
        isActive = true
    }

    override fun onPause() {
        super.onPause()
        isActive = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Activity already showing — the system may re-deliver the full-screen intent
        // or startActivity after we're already visible. Safe to ignore.
        setIntent(intent)
    }

    override fun onDestroy() {
        // Only signal the peer if the user was actively in a call (CALLING or IN_CALL).
        // Do NOT send call-end for INCOMING state — the activity can be legitimately
        // destroyed by the OS on a locked screen before the user ever sees it.
        // Sending call-end there makes the caller see "Call ended" immediately.
        // The server's grace-period handles cleanup if the receiver never answers.
        val state = vm.state.value
        if (state == CallViewModel.State.CALLING || state == CallViewModel.State.IN_CALL) {
            vm.endCall("ended")
        }
        busyPlayer?.stop()
        busyPlayer?.release()
        busyPlayer = null
        WebSocketClient.onConnectionLost = null
        super.onDestroy()
        finishJob?.cancel()
        finishJob = null
        releaseProximityWakeLock()
        if (renderersInitialized) {
            vm.clearVideoRenderers()
            binding.surfaceLocal.release()
            binding.surfaceRemote.release()
            renderersInitialized = false
        }
        FshuService.cancelCallNotif(this)
    }

    private fun handleMessage(json: JsonObject) {
        // call-mutual-resolve has no "from" field — handle before the from/peer filter
        if (json.get("type")?.asString == "call-mutual-resolve") {
            val caller = json.get("caller")?.asString ?: return
            val callee = json.get("callee")?.asString ?: return
            when (vm.handleMutualResolve(caller, callee)) {
                true  -> { sliderCommitted = true; requestPermissionsThenAccept() }  // I am callee, was INCOMING
                false -> { vm.endCall(); finish() }       // I am callee, was CALLING — drop redundant leg
                null  -> { /* I am caller — wait for callee's answer */ }
            }
            return
        }

        val from = json.get("from")?.asString ?: return
        if (from != peer) return
        when (json.get("type")?.asString) {
            "call-ringing"  -> vm.handleRinging()
            "call-answer"   -> vm.handleAnswer(json.get("sdp")?.asString ?: return)
            "ice-candidate" -> vm.handleIce(
                json.get("sdpMid")?.asString ?: return,
                json.get("sdpMLineIndex")?.asInt ?: return,
                json.get("candidate")?.asString ?: return
            )
            "call-busy"               -> vm.handleBusy()
            "call-end", "call-reject", "call-decline" -> vm.remoteEndCall(
                json.get("reason")?.asString ?: if (json.get("type")?.asString == "call-reject") "rejected" else "ended"
            )
        }
    }
}
