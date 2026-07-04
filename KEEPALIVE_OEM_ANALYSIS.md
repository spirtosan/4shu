# KEEPALIVE_OEM_ANALYSIS.md
## Why the keep-alive system fails on Huawei (EMUI) & UMIDIGI (MediaTek) — diagnosis + plan

> Companion to **`KEEPALIVE_AUDIT.md`** (the as-built extraction). This file is
> analysis + a parked action plan. **No code has been changed.** Nothing here is
> implemented. Pick this up later if/when background reliability on aggressive-OEM
> devices becomes a priority.
>
> _Status: PARKED. Drafted 2026-06-29. Not on the active task board._
> _Symptom: works on Moto G60 (Android 12, near-stock); app appears offline and stops
> receiving real-time messages after time in background on Huawei (EMUI) + UMIDIGI (MTK)._

---

## 0. TL;DR

The 8–10 layer keep-alive system is **not buggy**. It is built on stock-Android (AOSP)
assumptions that EMUI and UMIDIGI's MediaTek ROM deliberately violate. The Moto G60
runs near-stock Android — only standard Doze, which the layers handle. EMUI and the
MTK/DuraSpeed stack kill the service **and bar it from restarting**, which collapses
every recovery layer at once (alarms, WorkManager, boot receivers all depend on the OS
being willing to restart the app).

**The likely fix is mostly NOT more layers.** It is (a) getting the app onto the OEM
whitelist via a guided setup screen, plus (b) a few small, safe code hardening changes.
HMS Push integration was considered and **rejected** (breaks the self-hosted, no-third-
party-cloud model).

**One free diagnostic, not yet done, would split the problem cleanly — see §2.**

---

## 1. Root-cause ranking

Common denominator: two *different* OEMs failing identically points at OEM process
management, not a per-device bug.

**Rank 1 — Service killed AND barred from restarting (the big one).**
On stock Android, when the OS kills the foreground service, `START_STICKY` + the
AlarmManager chain + WorkManager + boot receivers bring it back. On EMUI's *App launch*
("managed automatically") and UMIDIGI's MediaTek **DuraSpeed** + `com.pri.screenoff.killer`,
the OS kills the service and then **blocks the restart paths** — broadcast receivers
don't fire, `START_STICKY` is ignored, WorkManager is frozen, and some EMUI builds block
the boot receiver. So audit Layers 5/6/7/8 (every recovery path) are defeated together.
These ROMs also silently roll a user-set "Unrestricted" battery flag back to "Optimized"
on reboot / interval sweeps.

**Rank 2 — Wake lock + alarm stripped while the service still lives.**
EMUI "Smart Power Saving" can release a held `PARTIAL_WAKE_LOCK` without throwing
(audit Gap 7), and there is **no re-acquire path** (acquired once in `onCreate`).
Exact alarms (`setExactAndAllowWhileIdle`) get downgraded to batched or dropped in deep
Doze (audit Gap 3). Tools neutralized even though the process is alive.

**Rank 3 — Socket throttled at kernel level during Doze vs. server zombie window.**
CPU wake lock is held but Android blocks outbound socket writes in Doze. Server kills
the socket after one missed 30 s PING cycle (~30–60 s), client doesn't notice until it
exits Doze (audit Gap 9). Reconnect on Doze-exit already works (Layer 4 `onAvailable`
+ Layer 3 watchdog), so this is real-time-delivery degradation, not a permanent break.

---

## 2. FREE diagnostic to run first (no code) — splits the whole problem

**The single most useful observation.** On the Huawei and the UMIDIGI, when the app goes
offline in the background: **does the persistent "4shu" foreground notification STAY
VISIBLE or VANISH?**

- **Vanishes →** the service is being **killed**. Root cause = Rank 1 (whitelisting).
  Fix is the guided OEM setup screen (§4 P2).
- **Stays visible but peer shows offline →** the service lives but the **socket/wakelock/
  alarm is throttled**. Root cause = Rank 2/3. Fix is connection-side (§4 P1c/P3/P4).

**Strong prior:** it vanishes (Rank 1), because two different OEMs fail identically and
killing-and-barring-restart is their shared behavior.

### Free manual whitelist test (may fix it outright, confirms root cause)

- **Huawei (EMUI):** Settings → Battery → **App launch** → 4shu → **Manage automatically
  OFF**, then enable all three: **Auto-launch**, **Secondary launch**, **Run in
  background**. Also set Battery optimization → **Don't optimize / Unrestricted** for 4shu.
- **UMIDIGI (MTK):** Settings → search **DuraSpeed** → turn it **OFF** (or whitelist 4shu
  inside it). Then Battery / **App Power Manager / Background management** → allow 4shu in
  background. Confirm battery-optimization exemption.

If both phones then stay online with screen off for 30+ minutes → **root cause confirmed =
OEM process-killing**, and the code work is purely "make the app guide the user to do this
automatically." If they still drop after manual whitelisting → connection-side work matters
more.

---

## 3. Gemini consult — cross-checked

Gemini was given `KEEPALIVE_AUDIT.md` and asked for OEM-specific guidance. Verdict on its
answer:

**Trust as-is (matches dontkillmyapp / AutoStartPermissionHelper consensus):**
- EMUI *App launch* sub-toggles (Auto-launch / Secondary launch / Run in background).
- Huawei deep-link components (try in order, all may fail across EMUI versions):
  - `com.huawei.systemmanager/.startupmgr.ui.StartupAppControlActivity` (EMUI 9–12 / HarmonyOS)
  - `com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity` (variant)
  - `com.huawei.systemmanager/.optimize.process.ProtectActivity` (legacy EMUI 5–8)
- GMS-vs-HMS runtime detection by lightweight `PackageManager` package probe
  (`com.google.android.gms` / `com.huawei.hwid`) — no heavy SDK needed.
- MediaTek **DuraSpeed** is real; detect by probing `com.mediatek.duraspeed` package
  because `Build.MANUFACTURER` is fuzzy on white-label MTK. Deep link:
  `com.mediatek.duraspeed/.DuraSpeedActivity` (wrap in try/catch).

**HMS Push Kit — REJECTED (correct).** Needs a verified Huawei enterprise/developer
account + App ID, and routes wake-ups through Huawei's cloud. That breaks 4shu's
self-hosted, no-third-party-cloud, E2E threat model. **Do not integrate.** For no-GMS
Huawei, the AlarmManager chain + manual whitelisting is the chosen path.

**Discount the *mechanism* claim, keep the action.** Gemini asserts OEM low-memory-killers
read notification-channel importance into their scoring tables. Unverifiable internal
claim — but raising the foreground channel off `IMPORTANCE_LOW` is low-risk and broadly
endorsed anyway, so do it for the empirical benefit, not the stated reason.

**Caveats Gemini glossed over (important):**
1. **Notification importance → use `DEFAULT`, not `HIGH`.** A permanent foreground
   notification at `IMPORTANCE_HIGH` will try to heads-up/peek and re-alert — annoying.
   `IMPORTANCE_DEFAULT` with sound + vibration explicitly disabled = higher process
   priority without a nagging banner.
2. **Wake-lock cycling is the riskiest item.** Release-then-reacquire every 60 s can,
   if the re-acquire throws or races, leave the service with **no** wake lock at all —
   worse than today. Must use try/finally so a failed release never skips re-acquire.
   It also runs on the working G60, so it must be re-tested there for regression.
   Medium impact, not high.
3. **Every change here touches the G60's working path too.** Cardinal rule:
   **don't fix Huawei by breaking the Moto.** Re-test the G60 after any of these.

---

## 4. Phased plan (parked — not on the board)

Two buckets: **user-must-do-in-OS-settings** (handled via a guided screen) and
**app-code**.

### App-code, priority order

**P1 — pure code, no user action, low risk (safe first batch):**
- **P1a** Foreground service channel `IMPORTANCE_LOW → IMPORTANCE_DEFAULT`
  (`CHANNEL_ID = "fshu_fg"`), sound + vibration explicitly off. (audit Gap 1)
- **P1b** Declare `SCHEDULE_EXACT_ALARM` in manifest; on API 31+ check
  `canScheduleExactAlarms()` and route the user to
  `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` during setup. (audit Gap 3)

**P2 — the actual fix if root cause is killing (guided OEM setup):**
- Detection: `Build.MANUFACTURER` (huawei/honor) + package probes
  (`com.huawei.systemmanager`, `com.mediatek.duraspeed`) → a `VendorProfile`
  (HUAWEI / MEDIATEK_BUDGET / STANDARD).
- A "Background reliability" setup flow that deep-links to the OEM whitelist screens
  (components in §3), **each wrapped in try/catch**, with a **textual step-by-step
  fallback** shown when the intent fails (intents are volatile across OEM micro-updates).
- Re-assert the standard `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` here too.
- EN + BG strings (no RU — `values-ru` absent).
- Reachable by **existing** users (see open decision), not only first-launch onboarding.

**P3 — defensive, medium risk (only if §2 says service lives but socket dies):**
- Wake-lock cycling inside the 60 s Layer-3 watchdog: `release()` then
  `acquire(10*60*1000L)` with a try/finally guard so re-acquire always runs.
  Note `wakeLock.isHeld` tracks the Java ref, not kernel state, so it can lie under EMUI.
  **Hold this** until the diagnostic confirms it's needed; it's the one item that could
  regress the G60.

**P4 — optional polish, later:**
- Doze-adaptive heartbeat backoff on `PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED`
  (reduce ping cadence in deep Doze to avoid filling outbound TCP buffers). Genuinely
  optional — reconnect-after-Doze already works via Layers 3 + 4.

### User-must-do-in-OS-settings (surfaced by the P2 screen)
EMUI App launch toggles · DuraSpeed off / whitelist · battery-optimization exemption.
These are the real fix on aggressive OEMs; the standard battery dialog alone is
insufficient. The app can only *guide* — it cannot set these programmatically.

---

## 5. Open decisions (deferred until pickup)

1. **Run the free §2 test first?** Strongly recommended — could reorder priorities and
   B alone might fix it. (No code.)
2. **Guided screen: extend `PermissionSetupActivity`, or a new standalone "Background
   reliability" screen reachable from Settings?** A standalone screen reachable from
   Settings is better for *existing* users who already onboarded.
3. **Include wake-lock cycling (P3) in the first batch, or hold?** Lean **hold** — only
   item that can regress the working G60.
4. **First code batch scope: P1 only, or P1 + P2?** Lean **P1 + P2** (safe code wins +
   the guided whitelist screen, which is the real fix), hold P3, P4 later.

**Recommended sequence when resumed:** run §2 diagnostic → if "vanishes," build P1 + P2,
hold P3/P4 → re-test BOTH the problem phones AND the G60 (regression).

---

## 6. Hard constraints to respect (unchanged)

- Self-distributed (not on Google Play) → Play targetSdk / exact-alarm policy deadlines
  do **not** apply; `SCHEDULE_EXACT_ALARM` is fair game.
- **No HMS Push / no third-party cloud** — rejected above; preserves the threat model.
- Android crypto stays Bouncy Castle + javax.crypto (irrelevant to this work, but no new
  crypto libs sneak in).
- Ivan builds all APKs; Claude Code never runs Gradle/adb. SSH LAN only.
- **Do not fix the OEMs by regressing the Moto G60** — re-test it after every change here.

---

## 7. Pointers

- As-built layer-by-layer detail, exact constants, manifest/permissions/wakelock/alarm/
  notification-channel config, and the GAPS list: **`KEEPALIVE_AUDIT.md`** (same folder).
- External reference pattern (no library import needed): the dontkillmyapp.com guidance
  and the component lists inside AutoStartPermissionHelper.
