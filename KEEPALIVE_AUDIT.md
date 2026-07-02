# KEEPALIVE_AUDIT.md
## fshu-next — As-built connection keep-alive / service-survival system

_Extracted from source on 2026-06-29. Read-only — no code changes made._

---

## LAYER 1 — OkHttp native WebSocket PING frames

**File:** `app/src/main/java/com/fshu/next/data/remote/WebSocketClient.kt` — `OkHttpClient.Builder`, line 24–29

**Mechanism:** OkHttp emits RFC-6455 PING frames at a fixed interval. The remote peer is expected to reply with a PONG; OkHttp handles this at the transport layer, invisible to app code.

**Timing:**
```kotlin
.pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)  // line 28
.readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)    // no idle timeout
.connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
.writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
```
Interval: **20 seconds** (hardcoded). OkHttp's built-in timeout closes the socket if no PONG arrives within twice the ping interval (~40 s), firing `onFailure`.

**Trigger / failure path:** OkHttp fires `WebSocketListener.onFailure` → `FshuService.connect()` `onDisconnected` lambda → `delay(1_000)` → `connect()` again (recursive, runs in `scope`).

**Doze survival:** PARTIAL_WAKE_LOCK is held by the service, so the CPU stays up, but Android's network stack in Doze restricts outbound traffic to maintenance windows. OkHttp PING frames may be suppressed by the kernel. The NAT-keepalive intent of this layer is only reliable outside Doze.

---

## LAYER 2 — App-level adaptive heartbeat (ping/pong)

**File:** `WebSocketClient.kt` — `startHeartbeat()`, lines 275–321

**Mechanism:** After `auth-ok`, a coroutine loop sends a JSON `{type:"ping", lastSeq:…, listVersions:…}` application-level ping. Waits for a JSON `{type:"pong"}` response. Adapts interval based on rolling RTT history.

**Constants:**
```kotlin
private const val PING_INTERVAL_MIN_MS = 5_000L    // 5 s
private const val PING_INTERVAL_MAX_MS = 15_000L   // 15 s
private const val PONG_TIMEOUT_MS      = 30_000L
private const val RTT_HISTORY_SIZE     = 5
```

**Initial interval per network type:**
```kotlin
val cellular = isCellular()
val pongTimeoutMs = if (cellular) 10_000L else PONG_TIMEOUT_MS   // 10 s vs 30 s
var intervalMs    = if (cellular) 20_000L else 10_000L            // 20 s vs 10 s
```

**Adaptive interval (WiFi only):**
```
avgRtt == 0.0          → 10 000 ms
avgRtt < 100 ms        → PING_INTERVAL_MAX_MS = 15 000 ms
avgRtt < 300 ms        → 20 000 ms
avgRtt < 800 ms        → 10 000 ms
avgRtt >= 800 ms       → PING_INTERVAL_MIN_MS = 5 000 ms
```
Cellular: fixed at 20 s (no adaptation). Pong timeout is shorter on cellular (10 s vs 30 s).

**Failure path:**
1. `pongReceived.get()` is false after `pongTimeoutMs` → `ws.cancel()`
2. Hard-fallback 5 s later: if `isConnected` is still true (OkHttp sometimes stalls without firing `onFailure` on cellular), manually set `isConnected = false`, fire `onConnectionLost`, fire `disconnectCallback` → reconnect.

**Doze survival:** Same caveat as Layer 1. The coroutine runs on `heartbeatScope` (IO dispatcher); CPU wakelock is held but kernel network access is gated by Doze.

---

## LAYER 3 — In-service coroutine connection watchdog

**File:** `FshuService.kt` — `startConnectionWatchdog()`, lines 2207–2223

**Mechanism:** A long-running coroutine inside `FshuService.scope` (IO + SupervisorJob) polls every 60 seconds.

```kotlin
delay(60_000)                          // poll every 60 s
val pongStale = lastPong > 0 &&
    (System.currentTimeMillis() - lastPong) > 45_000   // 45 s stale threshold
if ((!connected || pongStale) && !isConnectingNow) {
    if (pongStale && connected) WebSocketClient.disconnect()
    delay(300)
    connect(url, username, password)
}
```

**Timing:** Check every **60 s**; pong considered stale after **45 s**.

**Trigger:** Fires if `isConnected == false` OR last pong was >45 s ago, and not already reconnecting.

**Survival:** Lives inside `FshuService` scope. If the service is killed, this watchdog dies with it. Relies on other layers (AlarmManager, WorkManager) to restart the service first.

---

## LAYER 4 — NetworkCallback (network change detector)

**File:** `FshuService.kt` — `registerNetworkCallback()`, lines 2225–2266

**Mechanism:** `ConnectivityManager.NetworkCallback` registered for `NET_CAPABILITY_INTERNET`.

**`onAvailable(network)`:**
```kotlin
val uptime = System.currentTimeMillis() - serviceStartTime
if (uptime < 3000) return    // skip registration-time callback
if (!isConnected) {
    connect(...)
} else {
    disconnect(); delay(300); connect(...)
}
```
Debounce: skips the immediate callback fired at registration time (uptime < 3 s). De-duplication: only reacts if `network.networkHandle` differs from the last seen network handle.

**`onLost(network)`:** Calls `WebSocketClient.disconnect()`.

**Survival:** Lives inside `FshuService`. Properly unregistered in `onDestroy()`. If the service is killed and not restarted, no callback fires.

---

## LAYER 5 — AlarmManager periodic health check

**File:** `FshuService.kt` — `scheduleAlarmCheck()`, lines 371–391 (scheduling) and `ServiceRestartReceiver.kt` lines 14–36 + 72–91 (handler + rescheduler)

**Constant:**
```kotlin
private const val ALARM_INTERVAL_MS = 3 * 60 * 1000L   // 3 minutes
```

**Scheduling call:**
```kotlin
// API 31+ with canScheduleExactAlarms() == true:
am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
// API 31+ without exact-alarm permission:
am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
// < API 31:
am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
```
`canScheduleExactAlarms()` is checked at both scheduling sites (FshuService and ServiceRestartReceiver). RequestCode: `1001`.

**On fire (`ACTION_ALARM_CHECK`):**
1. If service dead → `startForegroundService(FshuService)`
2. Else if WS disconnected → `startForegroundService(FshuService)` with `ACTION_RECONNECT`
3. `scheduleNextAlarm(context)` reschedules in **3 min** (identical logic)

**Survival:** `RTC_WAKEUP` — fires even if device is in Doze (respects battery-opt maintenance windows unless `setExactAndAllowWhileIdle`). Survives service kill and app swipe (alarm is registered with the OS alarm service). Does NOT survive reboot (alarm is not persistent) — boot receivers re-arm the service which then calls `scheduleAlarmCheck()` again.

---

## LAYER 6 — onTaskRemoved swipe-kill immediate alarm

**File:** `FshuService.kt` — `onTaskRemoved()`, lines 340–368

**Mechanism:** On app swipe (task removal), fires a 1-second alarm to restart the service, plus a WorkManager one-shot as backup.

```kotlin
val triggerAt = System.currentTimeMillis() + 1_000L    // 1 second
// API 31+ / < API 31: same canScheduleExactAlarms() branch as Layer 5
am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)

WorkManager.getInstance(this).enqueue(
    OneTimeWorkRequestBuilder<ServiceWatchdogWorker>()
        .setInitialDelay(5, TimeUnit.SECONDS)
        .build()
)
```

**Trigger:** `ServiceRestartReceiver` fires on `ACTION_RESTART` → `startForegroundService(FshuService)`.

**Survival:** Alarm survives service kill (it's registered in the system). OEM background restriction may delay `startForegroundService` — this is the primary failure point on EMUI/UMIDIGI.

Note: `android:stopWithTask="false"` is set in the manifest — the service is NOT stopped when the app task is removed; only `onTaskRemoved()` is called. This means the service may keep running on stock Android, and the alarm is a belt-and-suspenders restart not always needed.

---

## LAYER 7 — WorkManager periodic watchdog

**File:** `FshuService.kt` — `scheduleWatchdog()`, lines 393–399; `ServiceWatchdogWorker.kt` — full file

**Constant:** Period = **15 minutes**

```kotlin
PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES).build()
// Enqueued with ExistingPeriodicWorkPolicy.KEEP (does not reset if already running)
// Work name: "fshu_service_watchdog"
```

**`ServiceWatchdogWorker.doWork()`:**
1. If service dead → `startForegroundService(FshuService)`
2. Else if WS disconnected → `startForegroundService(FshuService)` with `ACTION_RECONNECT`

**Survival:** WorkManager uses JobScheduler (API 21+) or AlarmManager internally. Subject to battery optimization on OEM ROMs. Interval minimum enforced by Android is 15 min; actual fire time can slip by tens of minutes under battery saver.

---

## LAYER 8 — Boot receivers

**File:** `ServiceRestartReceiver.kt` — `onReceive()`, lines 12–63

**Actions handled:**

| Intent Action | Condition | Behavior |
|---|---|---|
| `BOOT_COMPLETED` | `Prefs.getUsername()` not empty | `startForegroundService(FshuService)` |
| `LOCKED_BOOT_COMPLETED` | plain pref `was_logged_in == true` | `startForegroundService(FshuService)` |
| `USER_UNLOCKED` | `Prefs.getUsername()` not empty | `startForegroundService(FshuService)` |
| `ACTION_RESTART_SERVICE` | username not empty | `startForegroundService(FshuService)` |
| `ACTION_ALARM_CHECK` | username not empty | health check + reschedule (Layer 5 logic) |

`LOCKED_BOOT_COMPLETED` uses a separate plain (non-encrypted) SharedPreferences key `"fshu_boot"/"was_logged_in"` because EncryptedSharedPreferences are unavailable before first unlock.

**Survival:** `directBootAware="true"` → receiver is registered before the user unlocks the device. `exported="true"` — can receive external broadcasts. Does NOT cover cold reboot if the app was never launched (Room DB migration, first-run guard not bypassed).

---

## LAYER 9 — FCM push wakeup

**App file:** `ManyaFirebaseService.kt` (actual class: `FshuFirebaseService`) — `onMessageReceived()`, line 23

**Mechanism:** Server sends a silent FCM push. `onMessageReceived` simply calls `startForegroundService(FshuService)`.

**Server-side FCM keepalive** (from `/opt/fshu5/server.js`):

```javascript
const FCM_KEEPALIVE_INTERVAL_MS  = 3 * 60 * 1000;   // check every 3 minutes
const FCM_KEEPALIVE_THRESHOLD_MS = 4 * 60 * 1000;   // fire if silent for 4 minutes
```
Every 3 min, the server iterates all users; for each user where `maxPing` (last app-level ping timestamp) is >4 min ago, it calls `sendFcmWakeup(user.fcm_token)`.

**FCM message configuration:**
```javascript
android: { priority: 'high', ttl: 60000 }   // high priority, 1-minute TTL
```

**Ad-hoc FCM sends** (from inspected lines): Server also calls `sendFcmWakeup` on message delivery to offline users (lines 999, 1178, 1728, 1874, 1891, 2083, 2139).

**Survival:** Only works if GMS is present on the device. Post-2019 Huawei (HMS-only) has no GMS → FCM is non-functional. Requires `Prefs.getFcmEnabled(context) == true` (user opted in during setup).

---

## LAYER 10 — Server-side zombie socket cleanup (WS PING/PONG)

**File:** `/opt/fshu5/server.js`, lines 1597–1611

```javascript
const WS_HEARTBEAT_INTERVAL = 30_000;   // 30 seconds
setInterval(() => {
    wss.clients.forEach(ws => {
        if (ws.isAlive === false) { ws.terminate(); return; }   // kill zombie
        ws.isAlive = false;
        ws.ping();   // RFC-6455 PING
    });
}, WS_HEARTBEAT_INTERVAL);

wss.on('connection', (ws, req) => {
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });
    ...
});
```

**Cycle:** Every 30 s, server sends WS PING to all clients. If client does not PONG before the next 30 s tick, socket is terminated (`ws.terminate()`). Net timeout before kill: **30–60 s** (one missed cycle).

**Stale device socket cleanup:** When a device connects with a known `deviceId`, any existing socket for that `(username, deviceId)` pair is terminated:
```javascript
const stale = userDevices.get(deviceId);
if (stale && stale !== ws) { try { stale.terminate(); } catch {} }
```

**Disconnect event (on close):** Server waits 2 s then broadcasts updated user list, and if the user was mid-call, sends `call-end` to the peer.

---

## SECTION A — AndroidManifest.xml: Service declaration

```xml
<service
    android:name=".service.FshuService"
    android:foregroundServiceType="dataSync|location|connectedDevice|microphone"
    android:exported="false"
    android:stopWithTask="false"/>
```

- `foregroundServiceType`: four types declared (`dataSync`, `location`, `connectedDevice`, `microphone`).
- `stopWithTask="false"`: service is NOT killed when the app task is swiped away.
- `exported="false"`.

`android:persistent="true"` is set on `<application>`. On non-system-signed apps this attribute is ignored by AOSP Android, but some OEM ROMs may act on it.

---

## SECTION B — AndroidManifest.xml: Receivers

```xml
<receiver
    android:name=".service.ServiceRestartReceiver"
    android:exported="true"
    android:directBootAware="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED"/>
        <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED"/>
        <action android:name="android.intent.action.USER_UNLOCKED"/>
        <action android:name="com.fshu.next.ACTION_RESTART_SERVICE"/>
        <action android:name="com.fshu.next.ACTION_ALARM_CHECK"/>
    </intent-filter>
</receiver>

<service
    android:name=".service.FshuFirebaseService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT"/>
    </intent-filter>
</service>
```

---

## SECTION C — AndroidManifest.xml: Permissions

| Permission | Present | Notes |
|---|---|---|
| `FOREGROUND_SERVICE` | ✓ | Base foreground service |
| `FOREGROUND_SERVICE_DATA_SYNC` | ✓ | API 34 typed FGS |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | ✓ | API 34 typed FGS |
| `FOREGROUND_SERVICE_MICROPHONE` | ✓ | API 34 typed FGS |
| `FOREGROUND_SERVICE_LOCATION` | ✓ | API 34 typed FGS |
| `WAKE_LOCK` | ✓ | Required for PARTIAL_WAKE_LOCK |
| `RECEIVE_BOOT_COMPLETED` | ✓ | Boot receiver |
| `SCHEDULE_EXACT_ALARM` | ✓ | User-grantable on API 31+ |
| `USE_EXACT_ALARM` | ✓ | Normal permission, API 33+ |
| `ACCESS_NETWORK_STATE` | ✓ | NetworkCallback |
| `ACCESS_WIFI_STATE` | ✓ | WiFi lock |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | ✓ | Battery exemption request |
| `SYSTEM_ALERT_WINDOW` | ✓ | Overlay permission |
| `POST_NOTIFICATIONS` | ✓ | API 33+ notification permission |
| `USE_FULL_SCREEN_INTENT` | ✓ | Full-screen call intents |
| `INTERNET` | ✓ | WebSocket |
| `VIBRATE` | ✓ | Call/message alerts |
| `RECORD_AUDIO` | ✓ | VoIP |
| `CAMERA` | ✓ | Video calls |
| `ACCESS_FINE_LOCATION` | ✓ | Location sharing |
| `ACCESS_COARSE_LOCATION` | ✓ | Location sharing |
| `READ_MEDIA_IMAGES/VIDEO/AUDIO` | ✓ | API 33+ media |
| `WRITE_EXTERNAL_STORAGE` | ✓ (maxSdk 28) | Legacy storage |

Missing compared to OEM requirements: no manufacturer-specific normal permissions (Huawei `com.huawei.permission.external_app_settings`, MIUI autostart — these are not standard Android permissions and cannot be declared in the manifest).

---

## SECTION D — WakeLock usage

**PARTIAL_WAKE_LOCK (connection):**
- **Acquired:** `FshuService.onCreate()`, line 183–185
  ```kotlin
  wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fshu:connection").apply { acquire() }
  ```
- **Released:** `FshuService.onDestroy()`, lines 2282–2285
- **Duration:** Held for the entire lifetime of the service — from `onCreate` to `onDestroy`. No timeout.

**WiFi lock (WIFI_MODE_FULL_HIGH_PERF):**
- **Acquired:** `FshuService.onCreate()`, lines 187–191
  ```kotlin
  wifiLock = WifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fshu:wifi").apply {
      setReferenceCounted(false); acquire()
  }
  ```
- **Released:** `FshuService.onDestroy()`, line 2282
- **Note:** `WIFI_MODE_FULL_HIGH_PERF` is deprecated as of API 29. In API 29+, Android ignores this mode for background apps.

**Transient screen wake lock (calls/SOS):**
- `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`, tag `"fshu:call_wakeup"` / `"fshu:sos_alarm"`
- Acquired only when screen is off on incoming call/SOS
- Timeout: `acquire(10_000L)` — 10 seconds auto-release

---

## SECTION E — AlarmManager: exact call details

Both scheduling sites use identical logic:

```kotlin
val am = getSystemService(AlarmManager::class.java)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {      // API 31+
    if (am.canScheduleExactAlarms()) {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    } else {
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }
} else {
    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
}
```

- `RTC_WAKEUP` — wakes CPU if sleeping.
- `setExactAndAllowWhileIdle` — fires during Doze maintenance windows, exact timing.
- `setAndAllowWhileIdle` — fires during Doze maintenance windows but is **batched** (may be delayed by minutes).
- `canScheduleExactAlarms()` — checked at every scheduling call. Falls back gracefully if the user has not granted `SCHEDULE_EXACT_ALARM`.

---

## SECTION F — Notification channels (foreground service importance)

| Channel ID | Constant | Importance | Sound | Vibration |
|---|---|---|---|---|
| `fshu_fg` | `CHANNEL_ID` | **IMPORTANCE_LOW** | default off | off |
| `fshu_messages_v3` | `CHANNEL_MESSAGES` | `IMPORTANCE_HIGH` | system default | `[0,250,250,250]` |
| `fshu_calls_v3` | `CHANNEL_CALLS` | `IMPORTANCE_MAX` | null | `[0,1000,…]` |
| `fshu_groups_v1` | `CHANNEL_GROUPS` | `IMPORTANCE_DEFAULT` | null | off |

**Foreground service notification** uses `CHANNEL_ID` (`IMPORTANCE_LOW`). This is the channel OEMs inspect to decide service priority.

---

## SECTION G — OEM-specific handling in the app

**`PermissionSetupActivity.kt` — steps requested on first launch:**

1. POST_NOTIFICATIONS (runtime, API 33+)
2. RECORD_AUDIO (runtime)
3. CAMERA (runtime)
4. ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION (runtime)
5. Auto-location pref toggle (no system prompt)
6. FCM opt-in (no system prompt; sends FCM token to server)
7. `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` → standard Android battery exemption dialog
8. `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` → standard overlay permission
9. `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (API 34+ only)

**What is NOT present:**

- No `Build.MANUFACTURER` detection anywhere in the codebase.
- No Huawei-specific deep link (`com.huawei.systemmanager.optimize.process.ProtectActivity` — "Protected apps").
- No Xiaomi autostart intent (`com.miui.securitycenter.permission.AUTOSTART` or `AutoStartPermissionActivity`).
- No UMIDIGI/MTK-specific autostart or whitelist screen.
- No OPPO/Realme/Vivo power-saving whitelist intent.
- No user-facing warning about manufacturer-specific battery restriction when `MANUFACTURER` is detected.

---

## SECTION H — Server-side FCM layer summary (from /opt/fshu5/server.js)

| Parameter | Value | Source line |
|---|---|---|
| FCM keepalive check interval | 3 minutes (`3 * 60 * 1000`) | line 1073 |
| FCM silence threshold | 4 minutes (`4 * 60 * 1000`) | line 1074 |
| FCM message priority | `'high'` | line 35 |
| FCM message TTL | `60000` ms (1 minute) | line 35 |
| Server WS PING heartbeat interval | 30 seconds (`30_000`) | line 1597 |
| Server zombie termination condition | one missed PONG cycle | line 1600 |
| Stale duplicate device socket cleanup | on new connection for same (username, deviceId) | lines 1647–1648, 1689–1690 |
| call-end on disconnect | 2 s after close, to peer | lines 3000–3005 |

---

## GAPS / OEM RISK

Identified from code only. No fixes proposed.

**1. Foreground service channel at IMPORTANCE_LOW.**
`CHANNEL_ID = "fshu_fg"` is created with `IMPORTANCE_LOW`. EMUI 9–12 and MIUI use notification channel importance as one signal when deciding which foreground services to protect from aggressive memory reclaim. A low-importance foreground notification is more likely to be collapsed or demoted, increasing kill probability on these OEMs.

**2. No manufacturer-specific autostart guidance.**
`PermissionSetupActivity` shows only the standard `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog. This is insufficient on:
- **EMUI (Huawei):** Requires user to enable "Protected apps" in Phone Manager, or "Auto-launch" in App Settings. The standard battery exemption does NOT prevent EMUI from killing background services.
- **UMIDIGI (MTK):** Has a proprietary "App Power Manager" / "Background Management" screen; the OS-level battery exemption alone does not whitelist the app.
- **Xiaomi/MIUI:** Requires autostart permission via `SecurityCenter`; standard exemption is insufficient.
The app detects none of these at runtime and does not guide the user to manufacturer-specific screens.

**3. `SCHEDULE_EXACT_ALARM` may be denied on EMUI/older ROMs.**
`SCHEDULE_EXACT_ALARM` (API 31+) is a user-grantable permission; if the user did not grant it (or the OEM silently revokes it), `canScheduleExactAlarms()` returns false and the fallback is `setAndAllowWhileIdle`, which is batched. On EMUI, the alarm may be deferred by 15–30 minutes or suppressed entirely in aggressive battery saver mode.

**4. FCM is non-functional on post-2019 Huawei devices (no GMS).**
Huawei devices shipped after ~mid-2019 without Google Mobile Services (P40, Mate 30, and later) have no FCM capability. The server's FCM keepalive (Layer 9) and all ad-hoc FCM wakeups produce no effect. There is no fallback (e.g., Huawei Push Kit / HMS Push). The device's only wakeup mechanisms are the AlarmManager chain (Layers 5–6) and WorkManager (Layer 7), both of which are unreliable on EMUI without battery exemption.

**5. `WIFI_MODE_FULL_HIGH_PERF` WiFi lock is a no-op on API 29+ in background.**
The WiFi lock is acquired on service start, but Android ignores `WIFI_MODE_FULL_HIGH_PERF` for background processes on API 29+. On older UMIDIGI/Huawei devices running Android 8–9 it may function, but provides no guarantee on the problematic target devices.

**6. `android:persistent="true"` is ignored on non-system apps.**
The attribute is silently ignored by AOSP on non-system-signed apps. Some OEM ROMs may honor it, but relying on it is not safe.

**7. PARTIAL_WAKE_LOCK may be forcibly released by EMUI battery optimization.**
EMUI's "Smart Power Saving" can release wake locks held by background apps it deems inactive, even if the service is running. There is no code path that re-acquires the wake lock after a forced release (only acquired once in `onCreate`).

**8. WorkManager 15-min periodic is unreliable under aggressive OEM battery savers.**
On EMUI and UMIDIGI with battery optimization active, WorkManager's JobScheduler backend may defer periodic work by hours. The 15-minute interval is a minimum floor enforced by Android, not a ceiling — OEM schedulers can and do stretch it.

**9. Server zombie window vs. Doze.**
Server terminates a socket after one missed 30-second heartbeat cycle (so within 30–60 s of no PONG). Android Doze maintenance windows can be spaced 15–60 minutes apart in deep Doze. A device in deep Doze will have its WebSocket killed by the server within ~1 minute, and will not notice until it exits Doze and the heartbeat fires. The app reconnects correctly on Doze exit, but messages queued on the server will not be delivered in real time.

**10. Connect-timeout job resets isConnecting after 15 s, but does not restart the service.**
If a connect attempt hangs silently (e.g., Android kills the TCP connect attempt without notifying OkHttp, a pattern seen on some EMUI builds), the 15-second `isConnecting` reset in `WebSocketClient.connect()` (lines 131–139) unblocks the next call, but only if something triggers a reconnect. The Layer-3 watchdog (60 s poll) is the fallback, meaning the device may be effectively offline for up to 75 s without detection.
