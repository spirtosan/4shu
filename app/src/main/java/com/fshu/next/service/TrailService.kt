package com.fshu.next.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo as AndroidWifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.telephony.CellIdentityNr
import android.telephony.CellInfo as TelephonyCellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fshu.next.R
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.trail.CellInfo
import com.fshu.next.trail.TrailFixQuality
import com.fshu.next.trail.LastFix
import com.fshu.next.trail.TrailPointData
import com.fshu.next.trail.TrailUploader
import com.fshu.next.trail.TrailPointKind
import com.fshu.next.trail.WifiAp
import com.fshu.next.trail.WifiInfo
import com.fshu.next.trail.toEntity
import com.fshu.next.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * SPEC_T13.md §2.1, §3.1–§3.4, §3.6 (Phase 1 Blocks B+C+D, hotfix Block B.1). Foreground service that
 * runs the MOVING/STILL sampling state machine, enriches every written point with
 * battery/net/cells/wifi (§3.4), emits event points for system broadcasts including
 * `boot` (started via [TrailPermissionActivity]/[com.fshu.next.service.
 * ServiceRestartReceiver] with [EXTRA_TRIGGER]) and `svc_restart` (detected from a
 * null [Intent] on [onStartCommand], the OS's own signal for a START_STICKY restart),
 * and writes straight to Room via [com.fshu.next.data.local.dao.TrailDao]. Started
 * from [com.fshu.next.ui.trail.TrailSettingsActivity]'s master toggle (behind the
 * staged permission walkthrough) or by the boot receiver when Trail is enabled. No
 * upload/E2E fan-out yet (Phase 2/3).
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

    // B.1.1: last fix that actually advanced stillCandidateCount -- see
    // STILL_ENTRY_MIN_SPACING_MS. Distinct from lastSignificantMotionTs (which tracks
    // the anchor-reset/20-min-timeout criterion, untouched by this).
    private var lastCountedFixTs = 0L

    // STILL exit: TYPE_SIGNIFICANT_MOTION fires, or a passive fix lands outside
    // STILL_EXIT_RADIUS_M of the anchor (§3.3). anchor is the position STILL was entered at.
    private var anchor: Location? = null
    private var sigMotionSensor: Sensor? = null

    private var activeProviders = mutableSetOf<String>()

    // §2.1 event "last" snapshot — the most recent fix, updated on every fix (MOVING,
    // STILL heartbeat, or passive), read by event points regardless of source.
    @Volatile private var lastFixSnapshot: LastFix? = null

    // B.1 dup guard: the last fix actually written to Room (any provider/state) --
    // compared against every new persist attempt by isDuplicateFix(). Updated only when
    // a fix is actually persisted, not on suppressed duplicates or MOVING-state passive
    // deliveries (those never reach recordFix at all, see passiveListener).
    @Volatile private var lastPersistedLocation: Location? = null
    @Volatile private var lastPersistedTs: Long = 0L

    // SPEC_T13_GLITCH_FILTER.md: last NON-SUSPECT ("good") fix, used as the speed
    // baseline by TrailFixQuality. Kept separate from lastPersisted* (the dup guard)
    // so a flagged glitch AND the good fix that snaps back after it are not both
    // flagged. Deliberately survives STILL/MOVING transitions -- a glitch can straddle
    // a state change; a long STILL gap just yields a small implied speed, which never
    // false-fires.
    @Volatile private var lastGoodLocation: Location? = null
    @Volatile private var lastGoodTs: Long = 0L

    // SPEC_T13_GLITCH_FILTER.md §detour — one-fix look-behind for the non-causal "detour"
    // rule. We persist every fix immediately (durability: a point is never held only in
    // volatile memory), remember the just-persisted fix here, and once its successor lands
    // we retroactively flag it via TrailDao.updateSusp if it was a there-and-back spike.
    private class PendingFix(
        val seq: Long, val lat: Double, val lon: Double, val acc: Double?, val mot: String?,
        val onlineSusp: String?, val prevLat: Double?, val prevLon: Double?
    )
    @Volatile private var pendingDetourFix: PendingFix? = null

    // Dedupe PROVIDERS_CHANGED_ACTION (fires per-provider) down to real loc_on/loc_off
    // aggregate transitions only.
    private var lastLocationEnabled: Boolean? = null

    companion object {
        private const val TAG = "TrailService"
        private const val CHANNEL_ID = "fshu_trail"
        private const val NOTIF_ID = 5100

        // TelephonyManager.ACTION_SIM_STATE_CHANGED is NOT public SDK -- it lives in the
        // internal (hidden) TelephonyIntents class, so referencing it doesn't compile.
        // The broadcast itself is real and still delivered to dynamically registered
        // receivers, so the literal action string is used directly instead.
        private const val ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"

        private const val MOVING_INTERVAL_MS = 3 * 60 * 1000L   // §3.3: 2–5 min band
        private const val STILL_INTERVAL_MS = 20 * 60 * 1000L   // §3.3: 15–30 min band
        private const val STILL_ENTRY_RADIUS_M = 100f
        private const val STILL_ENTRY_FIX_COUNT = 3
        private const val STILL_ENTRY_TIMEOUT_MS = 20 * 60 * 1000L
        private const val STILL_EXIT_RADIUS_M = 150f

        // B.1.1: §3.3's "3 consecutive fixes within 100 m" was implicitly calibrated to
        // the ~3-min active MOVING cadence. B.1 now also feeds MOVING-state passive
        // deliveries (zero-throttle, ~1/s while e.g. a nav app runs) into this same
        // consecutive-count logic -- without a floor, 3 passive fixes within 100 m can
        // land in seconds (a car stopped at a red light with a nav app running),
        // falsely satisfying the criterion and flipping MOVING->STILL mid-drive
        // (unregistering the active/fused provider). A fix only advances the count if
        // at least this long after the last counted fix; anchor-reset and the
        // independent 20-min-no-motion timeout are unaffected.
        private const val STILL_ENTRY_MIN_SPACING_MS = 60_000L

        // B.1: near-duplicate persist guard. PASSIVE_PROVIDER is registered at zero
        // throttle (0ms/0m) and echoes every location delivered to ANY registered
        // listener system-wide, including our own active-provider (fused/gps/network)
        // fix -- so every MOVING sample was landing in Room at least twice (once via
        // fixListener, once via passiveListener) before this guard existed, with counts
        // above 2 coming from other apps' concurrent location requests also being
        // echoed. Suppress a persist attempt within DUP_RADIUS_M and DUP_WINDOW_S of the
        // last fix actually written, regardless of source. See SPEC_T13.md's B.1
        // implementation notes for the full root-cause writeup.
        private const val DUP_RADIUS_M = 10f
        private const val DUP_WINDOW_S = 5L

        @Volatile var isRunning: Boolean = false
            private set

        // Set by ServiceRestartReceiver when it starts TrailService for a genuine
        // device-boot completion (§3.6/§3.1) — distinguishes a "boot" event from a
        // plain enable-triggered start. svc_restart needs no such extra: Android
        // redelivers onStartCommand with a null Intent specifically (and only) when
        // it auto-restarts a killed START_STICKY service, so intent == null is already
        // the signal on its own.
        const val EXTRA_TRIGGER = "trigger"
        const val TRIGGER_BOOT = "boot"

        // B.1.4: distinguishes a supervisor-initiated restart (ServiceWatchdogWorker's
        // TrailService branch, or FshuService's own in-process check) from the OS's own
        // null-intent svc_restart signal and from a genuine boot — restart telemetry
        // needs to say WHICH mechanism caught the gap. See SPEC_T13.md's Block B.1.4.
        const val TRIGGER_WATCHDOG = "watchdog"

        // T13 Block J — SOS/PANIC: accelerated sampling + per-point upload. Engage from
        // the app's SOS action with TrailService.engagePanic(context, true) (one line).
        const val ACTION_PANIC_ON  = "com.fshu.next.trail.ACTION_PANIC_ON"
        const val ACTION_PANIC_OFF = "com.fshu.next.trail.ACTION_PANIC_OFF"
        private const val PANIC_INTERVAL_MS = 25_000L   // §3.3 PANIC band: 20–30 s
        @Volatile var panic = false
            private set

        fun engagePanic(context: Context, on: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrailService::class.java).apply {
                    action = if (on) ACTION_PANIC_ON else ACTION_PANIC_OFF
                }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        registerSystemEventReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        when (intent?.action) {
            ACTION_PANIC_ON -> {
                panic = true; TrailUploader.setPanic(true)
                recordEvent("panic_on"); if (started) enterMoving()   // re-register at PANIC interval
                Log.w(TAG, "PANIC engaged -> ${PANIC_INTERVAL_MS}ms sampling + per-point upload")
                return START_STICKY
            }
            ACTION_PANIC_OFF -> {
                panic = false; TrailUploader.setPanic(false)
                recordEvent("panic_off"); if (started) enterMoving()
                Log.i(TAG, "PANIC cleared")
                return START_STICKY
            }
        }
        if (!started) {
            started = true
            scope.launch {
                fixSeq.set(db.trailDao().getMaxSeq() ?: 0L)
                registerPassive()
                enterMoving()
                when {
                    // Android redelivers onStartCommand with a null Intent only when it
                    // auto-restarts a previously-killed START_STICKY service -- never on
                    // an explicit startService()/startForegroundService() call, so this
                    // is unambiguously an OS-triggered restart (§3.1/§3.6 svc_restart).
                    intent == null -> {
                        Log.w(TAG, "onStartCommand null intent -> OS-triggered restart (svc_restart)")
                        Prefs.incrementTrailRestartCount(this@TrailService)
                        recordEvent("svc_restart")
                    }
                    intent.getStringExtra(EXTRA_TRIGGER) == TRIGGER_BOOT -> {
                        Log.i(TAG, "started via boot trigger")
                        recordEvent("boot")
                    }
                    // B.1.4: a supervisor (ServiceWatchdogWorker or FshuService) found
                    // Trail enabled but not running and restarted it — the process was
                    // down and nothing else (not the OS's own START_STICKY redelivery,
                    // not a device boot) brought it back. Counts toward the same
                    // restart-count health signal as svc_restart (same underlying
                    // problem: an unwanted kill), under its own event name so the two
                    // recovery paths stay distinguishable in the exported trail.
                    intent.getStringExtra(EXTRA_TRIGGER) == TRIGGER_WATCHDOG -> {
                        Log.w(TAG, "started via watchdog trigger -> supervisor-initiated restart")
                        Prefs.incrementTrailRestartCount(this@TrailService)
                        recordEvent("watchdog_restart")
                    }
                }
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
        try { unregisterReceiver(systemEventReceiver) } catch (e: Exception) { Log.w(TAG, "unregisterReceiver(systemEventReceiver): ${e.message}") }
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
        lastCountedFixTs = 0L
        registerActiveProviders(if (panic) PANIC_INTERVAL_MS else MOVING_INTERVAL_MS)
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
        // B.1.3: log the anchor's own accuracy -- a timeout-path entry with no
        // candidate anchor ever set falls back to the triggering fix as-is, which may
        // be coarse; this makes that visible on-device rather than silent.
        val accStr = if (anchorFix.hasAccuracy()) "${anchorFix.accuracy}m" else "?"
        Log.i(TAG, "state -> STILL anchor=(${anchorFix.latitude},${anchorFix.longitude}) acc=$accStr")
    }

    private fun evaluateStillEntry(location: Location) {
        val now = System.currentTimeMillis()

        // B.1.2: a fix may only drive the anchor/count/lastSignificantMotionTs
        // bookkeeping below if its own reported accuracy is smaller than the radius
        // it's tested against -- a coarse network/cell fix (accuracy commonly
        // 300-1500 m with Wi-Fi off) landing "300 m away" from a phone that hasn't
        // moved would otherwise thrash the anchor AND reset the 20-min timeout, via
        // B.1's newly passive-fed zero-throttle path (pre-B.1 this branch only ever
        // saw GPS-accurate active MOVING fixes, so the blindness was latent).
        // B.1.3: this gate must NOT also block the timedOut check below (it did in the
        // original B.1.2 patch, which early-returned the whole function) -- the
        // timeout is delivery-driven with no other call site, so in a coarse-only
        // environment nothing would ever reach it and STILL entry would become
        // impossible, GPS sampling forever -- the exact failure B.1.2 was trying to
        // prevent. See SPEC_T13.md's Block B.1.3 addendum. Persistence
        // (recordFix/isDuplicateFix) is untouched by any of this: locked decision 7
        // (collect maximally) governs storage, this function governs transitions only.
        if (location.hasAccuracy() && location.accuracy <= STILL_ENTRY_RADIUS_M) {
            val candidate = stillCandidateAnchor
            if (candidate == null || location.distanceTo(candidate) > STILL_ENTRY_RADIUS_M) {
                stillCandidateAnchor = location
                stillCandidateCount = 1
                lastCountedFixTs = now
                lastSignificantMotionTs = now
            } else if (now - lastCountedFixTs >= STILL_ENTRY_MIN_SPACING_MS) {
                // B.1.1: within radius, but only count it if it's spaced out from the last
                // counted fix -- a burst of zero-throttle passive deliveries within the
                // radius must not satisfy "3 consecutive" in seconds. Doesn't touch
                // lastSignificantMotionTs: staying within the anchor radius isn't motion.
                stillCandidateCount++
                lastCountedFixTs = now
            }
        }

        // B.1.3: runs on EVERY delivery, coarse or fine, gate above or not -- the
        // 20-min no-motion timeout is §3.3's OR'd fallback criterion specifically for
        // when the 3-consecutive-fixes count can't be satisfied (e.g. an all-coarse
        // window where the block above never fires).
        val timedOut = now - lastSignificantMotionTs >= STILL_ENTRY_TIMEOUT_MS
        if (stillCandidateCount >= STILL_ENTRY_FIX_COUNT || timedOut) {
            // B.1.3: if the timeout fired with no candidate anchor ever set (an
            // all-coarse window, no fix ever cleared the accuracy gate above), anchor
            // STILL at this triggering fix -- honest about possibly being coarse (up to
            // ~1 km off) rather than reaching for a stale pre-reset position from before
            // whatever put us back in MOVING last (startup or a genuine STILL exit,
            // neither of which makes an old position more trustworthy than a fresh
            // coarse one). Location.accuracy travels with the anchor either way, so
            // downstream consumers (the STILL-exit distance test, logs) can still see
            // it's coarse rather than silently treating it as precise.
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

    private val fixListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)

        // B.1: iterate a batched delivery explicitly rather than relying on the
        // platform LocationListener interface's own default onLocationChanged(List)
        // fan-out (which would otherwise call onLocationChanged(Location) once per item
        // anyway, silently) -- every item still passes through the same dup guard below.
        override fun onLocationChanged(locations: List<Location>) = locations.forEach(::onFix)

        private fun onFix(location: Location) {
            // Only a real MOVING active fix (fused/gps) may trigger an opportunistic wifi
            // scan (§3.4) — the STILL network heartbeat reuses this same listener but must
            // not fight the scan throttle just because it shares the callback.
            recordFix(location, triggerWifiScan = motionState == MotionState.MOVING)
            if (motionState == MotionState.MOVING) evaluateStillEntry(location)
        }
    }

    private val passiveListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onLocationChanged(locations: List<Location>) = locations.forEach(::onFix)

        private fun onFix(location: Location) {
            // B.1: while MOVING, passive deliveries feed the STILL-entry distance logic
            // only -- the active provider (fused/gps+network) is the sole persisted
            // MOVING source per §3.3's source table. While STILL, passive fixes stay
            // persistable (§3.2's designed bonus points + the >150 m exit trigger).
            if (motionState == MotionState.MOVING) {
                evaluateStillEntry(location)
                return
            }
            recordFix(location, providerOverride = "passive")
            val anchorFix = anchor
            // B.1.2: gate the exit decision by accuracy too -- this check predates B.1
            // (pre-existing Block B behavior, always accuracy-blind) but the same coarse-
            // fix risk applies. Safe to gate: TYPE_SIGNIFICANT_MOTION remains the designed
            // primary STILL-exit trigger (§3.3), and this only gates the DECISION -- the
            // coarse fix is still persisted above as a designed STILL bonus point,
            // unaffected by this check.
            if (anchorFix != null && location.hasAccuracy() && location.accuracy <= STILL_EXIT_RADIUS_M &&
                location.distanceTo(anchorFix) > STILL_EXIT_RADIUS_M) {
                Log.i(TAG, "passive fix ${location.distanceTo(anchorFix)}m from anchor -> MOVING")
                enterMoving()
            }
        }
    }

    // --- point construction ---

    private fun recordFix(location: Location, providerOverride: String? = null, triggerWifiScan: Boolean = false) {
        val ts = if (location.time > 0) location.time else System.currentTimeMillis()
        val prov = providerOverride ?: location.provider
        // B.1: check first, before any enrichment work (battery/cells/wifi reads, an
        // opportunistic wifi scan) is spent building a point that will just be dropped.
        if (isDuplicateFix(location, ts, prov)) return

        val seq = fixSeq.incrementAndGet()
        val (battPct, charging) = readBattery()
        val acc = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        // SPEC_T13_GLITCH_FILTER.md: flag physically-implausible fixes (impossible
        // speed since the last good fix, corroborated by poor accuracy). Stored, never
        // dropped (locked decision 7 "collect maximally").
        val susp = TrailFixQuality.classify(
            prevLat = lastGoodLocation?.latitude,
            prevLon = lastGoodLocation?.longitude,
            prevTs = lastGoodLocation?.let { lastGoodTs },
            lat = location.latitude, lon = location.longitude, ts = ts, acc = acc
        )
        val point = TrailPointData(
            seq = seq,
            kind = TrailPointKind.FIX,
            ts = ts,
            lat = location.latitude,
            lon = location.longitude,
            acc = acc,
            alt = if (location.hasAltitude()) location.altitude else null,
            spd = if (location.hasSpeed()) location.speed.toDouble() else null,
            brg = if (location.hasBearing()) location.bearing.toDouble() else null,
            prov = prov,
            mock = isMockLocation(location),
            mot = if (motionState == MotionState.MOVING) "moving" else "still",
            batt = battPct,
            chg = charging,
            net = readNetType(),
            susp = susp,
            cells = readCells(),
            wifi = readWifi(triggerScan = triggerWifiScan)
        )
        lastFixSnapshot = LastFix(lat = location.latitude, lon = location.longitude, acc = acc ?: 0.0, ts = ts)
        lastPersistedLocation = location
        lastPersistedTs = ts
        if (susp == null) {
            lastGoodLocation = location
            lastGoodTs = ts
        }
        persist(point)

        // §detour look-behind: now that THIS fix is the successor of the previously
        // persisted one, decide whether that previous fix was a there-and-back spike.
        // Only fixes the online path left clean are eligible, so "jump" always wins.
        val prev = pendingDetourFix
        if (prev != null && prev.onlineSusp == null) {
            val detour = TrailFixQuality.classifyDetour(
                prevLat = prev.prevLat, prevLon = prev.prevLon,
                lat = prev.lat, lon = prev.lon, acc = prev.acc, mot = prev.mot,
                nextLat = location.latitude, nextLon = location.longitude
            )
            if (detour != null) {
                val flaggedSeq = prev.seq
                scope.launch {
                    try {
                        db.trailDao().updateSusp(flaggedSeq, detour)
                        Log.i(TAG, "detour flag applied retroactively: seq=$flaggedSeq")
                    } catch (e: Exception) {
                        Log.w(TAG, "detour update failed seq=$flaggedSeq: ${e.message}")
                    }
                }
            }
        }
        // This fix becomes the pending one; its immediate predecessor is the fix that was
        // pending (prev). First-ever fix has no predecessor and can never be a detour.
        pendingDetourFix = PendingFix(
            seq = seq, lat = location.latitude, lon = location.longitude, acc = acc,
            mot = point.mot, onlineSusp = susp, prevLat = prev?.lat, prevLon = prev?.lon
        )
    }

    // B.1: true if this attempt lands within DUP_RADIUS_M and DUP_WINDOW_S of the last
    // fix actually persisted, from any provider/listener/state. Logged (tag TrailService)
    // so the Phase 1 device-test checklist can verify suppression live.
    private fun isDuplicateFix(location: Location, ts: Long, prov: String?): Boolean {
        val last = lastPersistedLocation ?: return false
        val dtMs = abs(ts - lastPersistedTs)
        if (dtMs > DUP_WINDOW_S * 1000) return false
        val dist = location.distanceTo(last)
        if (dist > DUP_RADIUS_M) return false
        Log.i(TAG, "dup suppressed: prov=${prov ?: "?"} d=${"%.1f".format(dist)}m dt=${dtMs}ms")
        return true
    }

    private fun recordEvent(ev: String) {
        val seq = fixSeq.incrementAndGet()
        val (battPct, charging) = readBattery()
        val point = TrailPointData(
            seq = seq,
            kind = TrailPointKind.EVENT,
            ts = System.currentTimeMillis(),
            ev = ev,
            batt = battPct,
            chg = charging,
            last = lastFixSnapshot
        )
        persist(point)
    }

    private fun persist(point: TrailPointData) {
        scope.launch {
            try {
                db.trailDao().insert(point.toEntity())
                Log.d(TAG, "point seq=${point.seq} kind=${point.kind} ev=${point.ev ?: "-"} prov=${point.prov ?: "-"} mot=${point.mot ?: "-"}")
                TrailUploader.tick(applicationContext)   // T13 Block I — nudge upload after each new point
            } catch (e: Exception) {
                Log.w(TAG, "insert failed: ${e.message}")
            }
        }
    }

    private fun isMockLocation(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock
        else @Suppress("DEPRECATION") location.isFromMockProvider

    // --- enrichment collectors (§3.4) ---

    /** Peeks the sticky ACTION_BATTERY_CHANGED intent — no persistent receiver needed. */
    private fun readBattery(): Pair<Int?, Boolean?> {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = if (status >= 0)
            (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        else null
        return pct to charging
    }

    private fun readNetType(): String? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    private fun readCells(): List<CellInfo>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val tm = getSystemService(TelephonyManager::class.java) ?: return null
        return try {
            tm.allCellInfo?.mapNotNull { mapCellInfo(it) }?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "getAllCellInfo failed: ${e.message}")
            null
        }
    }

    // §2.1: t/mcc/mnc/tac/ci/pci/sig/reg. WCDMA/GSM's lac/psc map onto the tac/pci
    // slots (same semantic role, different radio-generation name). CDMA and any future
    // unknown subtype are out of scope (spec names LTE/NR/WCDMA/GSM only) — skipped.
    private fun mapCellInfo(info: TelephonyCellInfo): CellInfo? {
        val registered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            info.cellConnectionStatus == TelephonyCellInfo.CONNECTION_PRIMARY_SERVING
        else @Suppress("DEPRECATION") info.isRegistered

        return when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                val ss = info.cellSignalStrength
                CellInfo(
                    t = "lte",
                    mcc = compatMccMnc({ id.mccString }, { id.mcc }),
                    mnc = compatMccMnc({ id.mncString }, { id.mnc }),
                    tac = id.tac.takeIf { it != TelephonyCellInfo.UNAVAILABLE },
                    ci = id.ci.takeIf { it != TelephonyCellInfo.UNAVAILABLE }?.toLong(),
                    pci = id.pci.takeIf { it != TelephonyCellInfo.UNAVAILABLE },
                    sig = ss.rsrp.takeIf { it != TelephonyCellInfo.UNAVAILABLE },
                    reg = registered
                )
            }
            is CellInfoNr -> {
                // Unlike Lte/Wcdma/Gsm, CellInfoNr's cellIdentity/cellSignalStrength
                // getters are typed to return the BASE CellIdentity/CellSignalStrength
                // (an Android API inconsistency) -- explicit cast required, bail to null
                // if either fails. CellInfoNr itself is API 29+, so gate on Q too.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
                val id = info.cellIdentity as? CellIdentityNr ?: return null
                val ss = info.cellSignalStrength as? CellSignalStrengthNr ?: return null
                CellInfo(
                    t = "nr",
                    mcc = id.mccString?.toIntOrNull(),
                    mnc = id.mncString?.toIntOrNull(),
                    tac = id.tac.takeIf { it != TelephonyCellInfo.UNAVAILABLE },
                    ci = id.nci.takeIf { it != TelephonyCellInfo.UNAVAILABLE_LONG },
                    pci = id.pci.takeIf { it != TelephonyCellInfo.UNAVAILABLE },
                    sig = ss.ssRsrp.takeIf { it != TelephonyCellInfo.UNAVAILABLE },
                    reg = registered
                )
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val ss = info.cellSignalStrength
                CellInfo(
                    t = "wcdma",
                    mcc = compatMccMnc({ id.mccString }, { id.mcc }),
                    mnc = compatMccMnc({ id.mncString }, { id.mnc }),
                    tac = id.lac.takeIf { it != TelephonyCellInfo.UNAVAILABLE },
                    ci = id.cid.takeIf { it != TelephonyCellInfo.UNAVAILABLE }?.toLong(),
                    pci = id.psc.takeIf { it != TelephonyCellInfo.UNAVAILABLE },
                    sig = ss.dbm.takeIf { it != TelephonyCellInfo.UNAVAILABLE },
                    reg = registered
                )
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                val ss = info.cellSignalStrength
                CellInfo(
                    t = "gsm",
                    mcc = compatMccMnc({ id.mccString }, { id.mcc }),
                    mnc = compatMccMnc({ id.mncString }, { id.mnc }),
                    tac = id.lac.takeIf { it != TelephonyCellInfo.UNAVAILABLE },
                    ci = id.cid.takeIf { it != TelephonyCellInfo.UNAVAILABLE }?.toLong(),
                    pci = null,
                    sig = ss.dbm.takeIf { it != TelephonyCellInfo.UNAVAILABLE },
                    reg = registered
                )
            }
            else -> null
        }
    }

    /** mcc/mnc String getters need API 28+ (calling them below that throws NoSuchMethodError
     *  at runtime, not just returns null) — the legacy int getter is the only safe path below P. */
    @Suppress("DEPRECATION")
    private fun compatMccMnc(stringGetter: () -> String?, legacyIntGetter: () -> Int): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) stringGetter()?.toIntOrNull()
        else legacyIntGetter().takeIf { it != TelephonyCellInfo.UNAVAILABLE }

    @SuppressLint("MissingPermission")
    private fun readWifi(triggerScan: Boolean): WifiInfo? {
        if (triggerScan) triggerOpportunisticScan()
        val conn = readConnectedWifiAp()
        val scan = readWifiScanCache().takeIf { it.isNotEmpty() }
        return if (conn == null && scan == null) null else WifiInfo(conn = conn, scan = scan)
    }

    @SuppressLint("MissingPermission")
    private fun readConnectedWifiAp(): WifiAp? {
        // Two-step on purpose: binding the if/else result directly to a nullable-typed
        // val and elvis-returning off the whole expression defeats smart-cast at the
        // use sites below (explicit `AndroidWifiInfo?` annotation blocks it) and is
        // fragile to reorder. Assign raw, then unwrap on its own line instead.
        val raw: AndroidWifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return null
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            caps.transportInfo as? AndroidWifiInfo
        } else {
            val wm = getSystemService(WifiManager::class.java) ?: return null
            @Suppress("DEPRECATION") wm.connectionInfo
        }
        val info = raw ?: return null

        // "02:00:00:00:00:00" is the randomized placeholder Android returns when the
        // caller isn't allowed to see the real BSSID — treat as unavailable, not real.
        val bssid = info.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" } ?: return null
        val ssid = info.ssid?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
        return WifiAp(b = bssid, s = ssid, r = info.rssi, f = info.frequency)
    }

    @SuppressLint("MissingPermission")
    private fun triggerOpportunisticScan() {
        val wm = getSystemService(WifiManager::class.java) ?: return
        try {
            @Suppress("DEPRECATION") wm.startScan()
            Log.d(TAG, "wifi scan triggered (opportunistic, MOVING point)")
        } catch (e: Exception) {
            Log.w(TAG, "wifi startScan failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun readWifiScanCache(): List<WifiAp> {
        val wm = getSystemService(WifiManager::class.java) ?: return emptyList()
        return try {
            @Suppress("DEPRECATION")
            wm.scanResults.mapNotNull { sr ->
                val bssid = sr.BSSID?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                WifiAp(b = bssid, s = sr.SSID?.takeIf { it.isNotBlank() }, r = sr.level, f = sr.frequency)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wifi scanResults read failed: ${e.message}")
            emptyList()
        }
    }

    // --- events (§2.1 ev set, minus boot/svc_restart — deferred to Block D) ---

    private fun registerSystemEventReceiver() {
        lastLocationEnabled = isLocationEnabledCompat()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(ACTION_SIM_STATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        registerReceiver(systemEventReceiver, filter)
    }

    private fun isLocationEnabledCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationManager.isLocationEnabled
        else locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
             locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ev = when (intent.action) {
                Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                    if (intent.getBooleanExtra("state", false)) "airplane_on" else "airplane_off"
                LocationManager.PROVIDERS_CHANGED_ACTION -> {
                    val enabled = isLocationEnabledCompat()
                    val prev = lastLocationEnabled
                    lastLocationEnabled = enabled
                    // PROVIDERS_CHANGED fires per-provider — only emit on a real aggregate flip.
                    if (prev == enabled) return
                    if (enabled) "loc_on" else "loc_off"
                }
                ACTION_SIM_STATE_CHANGED -> "sim_changed"
                Intent.ACTION_BATTERY_LOW -> "batt_low"
                Intent.ACTION_BATTERY_OKAY -> "batt_okay"
                Intent.ACTION_POWER_CONNECTED -> "charge_on"
                Intent.ACTION_POWER_DISCONNECTED -> "charge_off"
                Intent.ACTION_SHUTDOWN -> "shutdown"
                else -> return
            }
            Log.i(TAG, "system event: $ev")
            recordEvent(ev)
            if (ev == "shutdown") TrailUploader.flushBlocking(applicationContext, 2500)   // T13 Block J — last-gasp
        }
    }
}
