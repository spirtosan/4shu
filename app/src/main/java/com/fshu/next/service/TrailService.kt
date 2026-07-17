package com.fshu.next.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fshu.next.R
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.trail.TrailPointData
import com.fshu.next.trail.TrailPointKind
import com.fshu.next.trail.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * SPEC_T13.md §3.1–§3.3 (Phase 1 Block B). Foreground service that runs the
 * MOVING/STILL sampling state machine and writes fixes straight to Room via
 * [com.fshu.next.data.local.dao.TrailDao]. No enrichment (battery/cells/wifi/events —
 * Block C), no upload/E2E (Phase 3), no real consent UI (Block D) — this block wires
 * the collection mechanics only. Start/stop is a BuildConfig.DEBUG-only hook in
 * SettingsFragment until Block D's real toggle exists.
 */
class TrailService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val db by lazy { AppDatabase.getInstance(this) }

    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }

    private enum class MotionState { MOVING, STILL }
    private var motionState = MotionState.MOVING
    private val fixSeq = AtomicLong(0)
    @Volatile private var started = false

    // STILL entry: 3 consecutive MOVING fixes within STILL_ENTRY_RADIUS_M of each other,
    // or STILL_ENTRY_TIMEOUT_MS without a fix landing outside that radius (§3.3).
    private var stillCandidateAnchor: Location? = null
    private var stillCandidateCount = 0
    private var lastSignificantMotionTs = 0L

    // STILL exit: TYPE_SIGNIFICANT_MOTION fires, or a passive fix lands outside
    // STILL_EXIT_RADIUS_M of the anchor (§3.3). anchor is the position STILL was entered at.
    private var anchor: Location? = null
    private var sigMotionSensor: Sensor? = null

    private var activeProviders = mutableSetOf<String>()

    companion object {
        private const val TAG = "TrailService"
        private const val CHANNEL_ID = "fshu_trail"
        private const val NOTIF_ID = 5100

        private const val MOVING_INTERVAL_MS = 3 * 60 * 1000L   // §3.3: 2–5 min band
        private const val STILL_INTERVAL_MS = 20 * 60 * 1000L   // §3.3: 15–30 min band
        private const val STILL_ENTRY_RADIUS_M = 100f
        private const val STILL_ENTRY_FIX_COUNT = 3
        private const val STILL_ENTRY_TIMEOUT_MS = 20 * 60 * 1000L
        private const val STILL_EXIT_RADIUS_M = 150f

        @Volatile var isRunning: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (!started) {
            started = true
            scope.launch {
                fixSeq.set(db.trailDao().getMaxSeq() ?: 0L)
                registerPassive()
                enterMoving()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { locationManager.removeUpdates(fixListener) } catch (e: Exception) { Log.w(TAG, "removeUpdates(fixListener): ${e.message}") }
        try { locationManager.removeUpdates(passiveListener) } catch (e: Exception) { Log.w(TAG, "removeUpdates(passiveListener): ${e.message}") }
        unregisterSignificantMotion()
        Log.i(TAG, "provider unregistered: all (service destroyed)")
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_trail), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notif_trail_title))
        .setContentText(getString(R.string.notif_trail_text))
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .build()

    // --- state machine ---

    private fun enterMoving() {
        unregisterSignificantMotion()
        try { locationManager.removeUpdates(fixListener) } catch (e: Exception) { Log.w(TAG, "removeUpdates(fixListener): ${e.message}") }
        activeProviders.retainAll { it == LocationManager.PASSIVE_PROVIDER }
        motionState = MotionState.MOVING
        stillCandidateAnchor = null
        stillCandidateCount = 0
        lastSignificantMotionTs = System.currentTimeMillis()
        registerActiveProviders(MOVING_INTERVAL_MS)
        Log.i(TAG, "state -> MOVING")
    }

    private fun enterStill(anchorFix: Location) {
        if (motionState == MotionState.STILL) return
        motionState = MotionState.STILL
        anchor = anchorFix
        try { locationManager.removeUpdates(fixListener) } catch (e: Exception) { Log.w(TAG, "removeUpdates(fixListener): ${e.message}") }
        activeProviders.retainAll { it == LocationManager.PASSIVE_PROVIDER }
        Log.i(TAG, "provider unregistered: fused/gps/network (entering STILL, no GPS while stationary)")
        registerStillHeartbeat()
        registerSignificantMotion()
        Log.i(TAG, "state -> STILL anchor=(${anchorFix.latitude},${anchorFix.longitude})")
    }

    private fun evaluateStillEntry(location: Location) {
        val candidate = stillCandidateAnchor
        if (candidate == null || location.distanceTo(candidate) > STILL_ENTRY_RADIUS_M) {
            stillCandidateAnchor = location
            stillCandidateCount = 1
            lastSignificantMotionTs = System.currentTimeMillis()
        } else {
            stillCandidateCount++
        }
        val timedOut = System.currentTimeMillis() - lastSignificantMotionTs >= STILL_ENTRY_TIMEOUT_MS
        if (stillCandidateCount >= STILL_ENTRY_FIX_COUNT || timedOut) {
            enterStill(stillCandidateAnchor ?: location)
        }
    }

    // --- providers ---

    @SuppressLint("MissingPermission")
    private fun registerPassive() {
        if (activeProviders.contains(LocationManager.PASSIVE_PROVIDER)) return
        try {
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, passiveListener, Looper.getMainLooper())
            activeProviders += LocationManager.PASSIVE_PROVIDER
            Log.i(TAG, "provider registered: passive")
        } catch (e: SecurityException) {
            Log.w(TAG, "passive registration failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerActiveProviders(intervalMs: Long) {
        val fused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER)
        val providers = if (fused) listOf(LocationManager.FUSED_PROVIDER)
                        else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in providers) {
            if (!locationManager.allProviders.contains(p)) continue
            try {
                locationManager.requestLocationUpdates(p, intervalMs, 0f, fixListener, Looper.getMainLooper())
                activeProviders += p
                Log.i(TAG, "provider registered: $p interval=$intervalMs (MOVING)")
            } catch (e: SecurityException) {
                Log.w(TAG, "$p registration failed: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerStillHeartbeat() {
        if (!locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) return
        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, STILL_INTERVAL_MS, 0f, fixListener, Looper.getMainLooper())
            activeProviders += LocationManager.NETWORK_PROVIDER
            Log.i(TAG, "provider registered: network interval=$STILL_INTERVAL_MS (STILL heartbeat)")
        } catch (e: SecurityException) {
            Log.w(TAG, "network heartbeat registration failed: ${e.message}")
        }
    }

    private fun registerSignificantMotion() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: run {
            Log.w(TAG, "no TYPE_SIGNIFICANT_MOTION sensor on this device")
            return
        }
        sigMotionSensor = sensor
        sensorManager.requestTriggerSensor(sigMotionTriggerListener, sensor)
    }

    private fun unregisterSignificantMotion() {
        sigMotionSensor?.let { sensorManager.cancelTriggerSensor(sigMotionTriggerListener, it) }
        sigMotionSensor = null
    }

    private val sigMotionTriggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // One-shot by platform contract — already cancelled by firing, no explicit unregister needed.
            Log.i(TAG, "significant motion sensor fired -> MOVING")
            enterMoving()
        }
    }

    private val fixListener = LocationListener { location ->
        recordFix(location)
        if (motionState == MotionState.MOVING) evaluateStillEntry(location)
    }

    private val passiveListener = LocationListener { location ->
        recordFix(location, providerOverride = "passive")
        val anchorFix = anchor
        if (motionState == MotionState.STILL && anchorFix != null && location.distanceTo(anchorFix) > STILL_EXIT_RADIUS_M) {
            Log.i(TAG, "passive fix ${location.distanceTo(anchorFix)}m from anchor -> MOVING")
            enterMoving()
        }
    }

    // --- point construction (§2.1 fix fields only — enrichment is Block C) ---

    private fun recordFix(location: Location, providerOverride: String? = null) {
        val seq = fixSeq.incrementAndGet()
        val point = TrailPointData(
            seq = seq,
            kind = TrailPointKind.FIX,
            ts = if (location.time > 0) location.time else System.currentTimeMillis(),
            lat = location.latitude,
            lon = location.longitude,
            acc = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            alt = if (location.hasAltitude()) location.altitude else null,
            spd = if (location.hasSpeed()) location.speed.toDouble() else null,
            brg = if (location.hasBearing()) location.bearing.toDouble() else null,
            prov = providerOverride ?: location.provider,
            mock = isMockLocation(location),
            mot = if (motionState == MotionState.MOVING) "moving" else "still"
        )
        scope.launch {
            try {
                db.trailDao().insert(point.toEntity())
                Log.d(TAG, "fix seq=$seq prov=${point.prov} mot=${point.mot} acc=${point.acc}")
            } catch (e: Exception) {
                Log.w(TAG, "insert failed: ${e.message}")
            }
        }
    }

    private fun isMockLocation(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock
        else @Suppress("DEPRECATION") location.isFromMockProvider
}
