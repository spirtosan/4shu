package com.fshu.next.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val amplitudes = mutableListOf<Float>()
    private var samplerJob: Job? = null
    private var startMs = 0L
    private val scope = CoroutineScope(Dispatchers.IO)

    data class Recording(val file: File, val waveform: FloatArray, val durationSecs: Int)

    val isRecording: Boolean get() = recorder != null

    fun start(
        filename: String = "voice_${System.currentTimeMillis()}.m4a",
        onSecondTick: ((Int) -> Unit)? = null
    ) {
        if (recorder != null) return
        outputFile = File(context.cacheDir, filename)
        amplitudes.clear()
        startMs = System.currentTimeMillis()
        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
                    else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44_100)
            setOutputFile(outputFile!!.absolutePath)
            prepare()
            start()
        }
        samplerJob = scope.launch {
            var sampleCount = 0
            while (isActive) {
                delay(100)
                val amp = try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }
                amplitudes.add((amp / 32767f).coerceIn(0f, 1f))
                sampleCount++
                if (sampleCount % 10 == 0) {
                    val elapsedSecs = ((System.currentTimeMillis() - startMs) / 1000).toInt()
                    onSecondTick?.invoke(elapsedSecs)
                }
                if (System.currentTimeMillis() - startMs >= 300_000L) break
            }
        }
    }

    fun release() = destroy()

    fun stop(): Recording? {
        samplerJob?.cancel(); samplerJob = null
        val durationMs = System.currentTimeMillis() - startMs
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            if (durationMs < 500L) { outputFile?.delete(); return null }
            val f = outputFile ?: return null
            val raw = if (amplitudes.isEmpty()) FloatArray(1) { 0.5f } else amplitudes.toFloatArray()
            val maxAmp = raw.maxOrNull() ?: 1f
            val normalized = if (maxAmp > 0f)
                raw.map { ((it / maxAmp) * 0.85f + 0.15f).coerceIn(0f, 1f) }.toFloatArray()
            else raw
            Recording(f, normalized, (durationMs / 1000).toInt().coerceAtLeast(1))
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "stop failed", e)
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            outputFile?.delete()
            null
        }
    }

    fun cancel() {
        samplerJob?.cancel(); samplerJob = null
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        outputFile?.delete(); outputFile = null
    }

    fun elapsedMs(): Long = if (startMs > 0 && recorder != null) System.currentTimeMillis() - startMs else 0L

    fun destroy() {
        cancel()
        scope.cancel()
    }
}
