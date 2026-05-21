package com.fshu.next.ui.call

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.service.WebRTCManager
import com.fshu.next.util.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

class CallViewModel(app: Application) : AndroidViewModel(app) {
    private val TAG = "CallViewModel"
    enum class State { INCOMING, CALLING, IN_CALL, ENDED }

    val state = MutableLiveData(State.CALLING)
    val endReason = MutableLiveData("ended")
    val isMuted = MutableLiveData(false)
    val isSpeakerOn = MutableLiveData(false)
    val callDuration = MutableLiveData("")
    val isCameraOn = MutableLiveData(true)
    val isFrontCamera = MutableLiveData(true)
    val isRinging = MutableLiveData(false)

    private val me = Prefs.getUsername(app)
    private var peer = ""
    private var pendingOfferSdp: String? = null
    private var timerJob: Job? = null
    @Volatile private var callEndSent = false
    @Volatile private var ringingTimeoutJob: Job? = null
    @Volatile private var incomingTimeoutJob: Job? = null
    @Volatile private var noAnswerTimeoutJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var ringbackThread: Thread? = null
    private var incomingVibrator: Vibrator? = null

    private var _isVideoCall = false
    val isVideoCall: Boolean get() = _isVideoCall

    private var _isEmergency = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null  // API 26+

    /**
     * Lazily created so [setVideoCall] can be called first to set the isVideoCall flag
     * before WebRTCManager is instantiated.
     */
    private val webRTC: WebRTCManager by lazy {
        WebRTCManager(
            context = getApplication(),
            isVideoCall = _isVideoCall,
            onIceCandidate = { candidate ->
                WebSocketClient.send(mapOf(
                    "type" to "ice-candidate", "from" to me, "to" to peer,
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "candidate" to candidate.sdp
                ))
            },
            onCallEnded = {
                // ICE disconnect: notify the remote side so it doesn't hang
                if (peer.isNotEmpty() && !callEndSent) {
                    callEndSent = true
                    WebSocketClient.send(mapOf(
                        "type" to "call-end", "from" to me, "to" to peer, "reason" to "disconnected"
                    ))
                }
                endReason.postValue("disconnected")
                state.postValue(State.ENDED)
            }
        )
    }

    /** Must be called before any WebRTC operations and before accessing [eglBaseContext]. */
    fun setVideoCall(isVideo: Boolean) { _isVideoCall = isVideo }

    fun setEmergency(emergency: Boolean) { _isEmergency = emergency }

    fun getEglBaseContext() = webRTC.eglBaseContext

    fun initVideoRenderers(localView: SurfaceViewRenderer, remoteView: SurfaceViewRenderer) {
        webRTC.initVideoRenderers(localView, remoteView)
    }

    fun clearVideoRenderers() {
        webRTC.clearVideoRenderers()
    }

    fun startCall(peerUsername: String) {
        peer = peerUsername
        acquireAudioFocus()
        state.value = State.CALLING
        val callType = if (_isEmergency) "call-emergency" else "call-offer"
        webRTC.startCall { sdp ->
            WebSocketClient.send(mapOf(
                "type" to callType, "from" to me, "to" to peer,
                "sdp" to sdp.description,
                "video" to _isVideoCall
            ))
            ringingTimeoutJob?.cancel()
            ringingTimeoutJob = viewModelScope.launch {
                delay(4_000)
                ringingTimeoutJob = null
                // Timeout — receiver didn't send call-ringing (old app version); stay silent
            }
            if (!_isEmergency) {
                noAnswerTimeoutJob?.cancel()
                noAnswerTimeoutJob = viewModelScope.launch {
                    delay(60_000)
                    if (state.value == State.CALLING) {
                        cancelNoAnswerTimeout()
                        endCall("no_answer")
                    }
                }
            }
        }
    }

    private fun cancelNoAnswerTimeout() {
        noAnswerTimeoutJob?.cancel()
        noAnswerTimeoutJob = null
    }

    fun handleRinging() {
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = null
        isRinging.postValue(true)
        startRingback()
    }

    private fun startRingback() {
        stopRingback()
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val earpiece = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                earpiece?.let { audioManager.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }

            val sampleRate = 22050
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            track.play()

            // Read WAV PCM data once (skip 44-byte header)
            val pcmData: ByteArray = getApplication<Application>()
                .resources.openRawResource(com.fshu.next.R.raw.ringback).use { stream ->
                    stream.skip(44) // skip WAV header
                    stream.readBytes()
                }

            // Loop PCM data on a background thread until stopRingback() nulls audioTrack
            ringbackThread = Thread {
                val buf = ByteArray(bufferSize)
                var pos = 0
                while (!Thread.currentThread().isInterrupted && audioTrack != null) {
                    val remaining = pcmData.size - pos
                    val toWrite = minOf(buf.size, remaining)
                    System.arraycopy(pcmData, pos, buf, 0, toWrite)
                    track.write(buf, 0, toWrite)
                    pos += toWrite
                    if (pos >= pcmData.size) pos = 0 // loop back
                }
            }.apply {
                isDaemon = true
                start()
            }
        } catch (_: Exception) {}
    }

    private fun stopRingback() {
        ringbackThread?.interrupt()
        ringbackThread = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        if (state.value != State.IN_CALL) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun startIncomingVibration() {
        try {
            val context = getApplication<Application>()
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            incomingVibrator = vib
            val pattern = longArrayOf(0, 1000, 500, 1000, 500)
            val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, 0)
            }
        } catch (_: Exception) {}
    }

    private fun stopIncomingVibration() {
        try {
            incomingVibrator?.cancel()
        } catch (_: Exception) {}
        incomingVibrator = null
    }

    /** Called when an incoming offer arrives — does NOT start WebRTC yet. */
    fun prepareIncoming(fromUser: String, sdpStr: String, isEmergency: Boolean = false) {
        _isEmergency = isEmergency
        peer = fromUser
        pendingOfferSdp = sdpStr
        startIncomingVibration()
        state.value = State.INCOMING
        if (_isEmergency) return   // emergency calls ring until manually dismissed
        incomingTimeoutJob?.cancel()
        incomingTimeoutJob = viewModelScope.launch {
            delay(30_000)
            if (state.value == State.INCOMING) {
                // Notify the caller so they see "No answer" immediately
                WebSocketClient.send(mapOf(
                    "type" to "call-end", "from" to me, "to" to peer, "reason" to "no_answer"
                ))
                endReason.postValue("no_answer")
                stopIncomingVibration()
                state.postValue(State.ENDED)
            }
        }
    }

    private fun cancelIncomingTimeout() {
        incomingTimeoutJob?.cancel()
        incomingTimeoutJob = null
    }

    /** User tapped Accept — now start WebRTC and send the answer. */
    fun acceptCall() {
        stopIncomingVibration()
        cancelIncomingTimeout()
        if (!WebSocketClient.isConnected) {
            endReason.postValue("disconnected")
            state.postValue(State.ENDED)
            return
        }
        val sdp = pendingOfferSdp ?: return
        webRTC.handleOffer(SessionDescription(SessionDescription.Type.OFFER, sdp)) { answer ->
            WebSocketClient.send(mapOf("type" to "call-answer", "from" to me, "to" to peer,
                "sdp" to answer.description))
            pendingIceCandidates.forEach { webRTC.addIceCandidate(it) }
            pendingIceCandidates.clear()
            acquireAudioFocus()
            state.postValue(State.IN_CALL)
            startTimer()
        }
    }

    /** User tapped Reject. */
    fun rejectCall() {
        stopIncomingVibration()
        cancelIncomingTimeout()
        WebSocketClient.send(mapOf("type" to "call-reject", "from" to me, "to" to peer, "reason" to "rejected"))
        endReason.value = "rejected"
        state.value = State.ENDED
    }

    /**
     * Server determined a tie-break for mutual simultaneous calls.
     * [caller] = higher username (initiates), [callee] = lower username (accepts).
     * Returns true if this device should auto-accept (i.e. me == callee and state == INCOMING).
     * Returns false if this device should silently cancel its outgoing leg (me == callee, state == CALLING).
     * Returns null if this device is the caller — no action needed here.
     */
    fun handleMutualResolve(@Suppress("UNUSED_PARAMETER") _caller: String, callee: String): Boolean? {
        return when {
            me == callee && state.value == State.INCOMING -> true   // accept
            me == callee && state.value == State.CALLING -> false   // drop outgoing leg
            else -> null                                            // caller side — wait
        }
    }

    fun handleBusy() {
        cancelNoAnswerTimeout()
        stopTimer()
        stopRingback()
        ringingTimeoutJob?.cancel(); ringingTimeoutJob = null
        callEndSent = true
        endReason.value = "busy"
        state.value = State.ENDED
    }

    fun handleAnswer(sdpStr: String) {
        cancelNoAnswerTimeout()
        stopRingback()
        ringingTimeoutJob?.cancel(); ringingTimeoutJob = null
        webRTC.handleAnswer(SessionDescription(SessionDescription.Type.ANSWER, sdpStr))
        acquireAudioFocus()
        state.value = State.IN_CALL
        startTimer()
        getApplication<Application>().getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun handleIce(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        if (state.value == State.INCOMING) {
            pendingIceCandidates.add(ice)
        } else {
            webRTC.addIceCandidate(ice)
        }
    }

    /** Local user ended the call — send WS message and tear down. */
    fun endCall(reason: String = "ended") {
        cancelNoAnswerTimeout()
        stopTimer()
        stopRingback()
        ringingTimeoutJob?.cancel(); ringingTimeoutJob = null
        if (!callEndSent && peer.isNotEmpty()) {
            callEndSent = true
            WebSocketClient.send(mapOf("type" to "call-end", "from" to me, "to" to peer, "reason" to "ended"))
        }
        webRTC.endCall()
        releaseAudioFocus()
        endReason.postValue(reason)
        getApplication<Application>().getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        state.postValue(State.ENDED)
    }

    /** Remote side ended the call — tear down without sending another WS message. */
    fun remoteEndCall(reason: String = "ended") {
        cancelNoAnswerTimeout()
        cancelIncomingTimeout()
        stopTimer()
        stopRingback()
        ringingTimeoutJob?.cancel(); ringingTimeoutJob = null
        callEndSent = true   // suppress any ICE-disconnect echo
        webRTC.endCall()
        releaseAudioFocus()
        endReason.value = reason
        state.value = State.ENDED
    }

    fun toggleMute() {
        val muted = !(isMuted.value ?: false)
        isMuted.value = muted
        webRTC.setMicEnabled(!muted)
    }

    fun toggleCamera() {
        val on = !(isCameraOn.value ?: true)
        isCameraOn.value = on
        webRTC.enableVideo(on)
    }

    fun switchCamera() {
        val front = !(isFrontCamera.value ?: true)
        isFrontCamera.value = front
        webRTC.switchCamera()
    }

    fun toggleSpeaker() {
        val speaker = !(isSpeakerOn.value ?: false)
        val speakerBefore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            audioManager.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        else
            @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn
        Log.d(TAG, "toggleSpeaker → enabling=$speaker | " +
            "mode=${audioManager.mode} | " +
            "speakerBefore=$speakerBefore | " +
            "voiceVol=${audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/" +
            "${audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}")

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setSpeakerApi31(speaker)
        } else {
            setSpeakerLegacy(speaker)
        }

        // If switching to speaker and voice volume is at zero, raise it to half-max.
        if (speaker) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL) == 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max / 2, 0)
                Log.d(TAG, "toggleSpeaker: voice volume was 0, raised to ${max / 2}")
            }
        }

        val speakerAfter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            audioManager.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        else
            @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn
        Log.d(TAG, "toggleSpeaker: done → speakerAfter=$speakerAfter")
        isSpeakerOn.value = speaker
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun setSpeakerApi31(enable: Boolean) {
        if (enable) {
            val speakerDevice = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speakerDevice != null) {
                val ok = audioManager.setCommunicationDevice(speakerDevice)
                Log.d(TAG, "setCommunicationDevice(BUILTIN_SPEAKER) → $ok")
                if (!ok) {
                    Log.w(TAG, "setCommunicationDevice failed, falling back to setSpeakerphoneOn")
                    setSpeakerLegacy(true)
                }
            } else {
                Log.w(TAG, "TYPE_BUILTIN_SPEAKER not in availableCommunicationDevices, falling back")
                setSpeakerLegacy(true)
            }
        } else {
            audioManager.clearCommunicationDevice()
            Log.d(TAG, "clearCommunicationDevice() called (back to earpiece)")
        }
    }

    @Suppress("DEPRECATION")
    private fun setSpeakerLegacy(enable: Boolean) {
        if (!enable) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.setSpeakerphoneOn(false)
            Log.d(TAG, "setSpeakerphoneOn(false) → ${audioManager.isSpeakerphoneOn}")
            return
        }
        viewModelScope.launch {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0)
            Log.d(TAG, "volume forced to max ($maxVol)")

            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.setSpeakerphoneOn(true)
            Log.d(TAG, "attempt 1 (MODE_IN_CALL) → isSpeakerphoneOn=${audioManager.isSpeakerphoneOn}")

            delay(300)

            if (audioManager.isSpeakerphoneOn) {
                Log.d(TAG, "attempt 1 succeeded")
                return@launch
            }

            Log.d(TAG, "attempt 1 failed, trying MODE_NORMAL fallback")
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.setSpeakerphoneOn(true)
            Log.d(TAG, "attempt 2 (MODE_NORMAL) → isSpeakerphoneOn=${audioManager.isSpeakerphoneOn}")
            delay(150)
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.d(TAG, "attempt 2 after MODE_IN_COMMUNICATION restore → isSpeakerphoneOn=${audioManager.isSpeakerphoneOn}")
        }
    }

    private fun acquireAudioFocus() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.setSpeakerphoneOn(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var seconds = 0
            while (true) {
                callDuration.postValue("%02d:%02d".format(seconds / 60, seconds % 60))
                delay(1000)
                seconds++
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelNoAnswerTimeout()
        cancelIncomingTimeout()
        stopTimer()
        stopRingback()
        ringingTimeoutJob?.cancel(); ringingTimeoutJob = null
        releaseAudioFocus()
        webRTC.dispose()
    }
}
