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
import com.fshu.next.trail.LastFix
import com.fshu.next.trail.TrailPointData
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

/**
 * SPEC_T13.md §2.1, §3.1–§3.4, §3.6 (Phase 1 Blocks B+C+D). Foreground service that
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

    // STILL exit: TYPE_SIGNIFICANT_MOTION fires, or a passive fix lands outside
    // STILL_EXIT_RADIUS_M of the anchor (§3.3). anchor is the position STILL was entered at.
    private var anchor: Location? = null
    private var sigMotionSensor: Sensor? = null

    private var activeProviders = mutableSetOf<String>()

    // §2.1 event "last" snapshot — the most recent fix, updated on every fix (MOVING,
    // STILL heartbeat, or passive), read by event points regardless of source.
    @Volatile private var lastFixSnapshot: LastFix? = null

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
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        registerSystemEventReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
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
        // Only a real MOVING active fix (fused/gps) may trigger an opportunistic wifi
        // scan (§3.4) — the STILL network heartbeat reuses this same listener but must
        // not fight the scan throttle just because it shares the callback.
        recordFix(location, triggerWifiScan = motionState == MotionState.MOVING)
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

    // --- point construction ---

    private fun recordFix(location: Location, providerOverride: String? = null, triggerWifiScan: Boolean = false) {
        val seq = fixSeq.incrementAndGet()
        val (battPct, charging) = readBattery()
        val ts = if (location.time > 0) location.time else System.currentTimeMillis()
        val acc = if (location.hasAccuracy()) location.accuracy.toDouble() else null
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
            prov = providerOverride ?: location.provider,
            mock = isMockLocation(location),
            mot = if (motionState == MotionState.MOVING) "moving" else "still",
            batt = battPct,
            chg = charging,
            net = readNetType(),
            cells = readCells(),
            wifi = readWifi(triggerScan = triggerWifiScan)
        )
        lastFixSnapshot = LastFix(lat = location.latitude, lon = location.longitude, acc = acc ?: 0.0, ts = ts)
        persist(point)
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
        }
    }
}
