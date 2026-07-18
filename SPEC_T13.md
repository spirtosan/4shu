# SPEC_T13 — "Trail": Continuous Location Logging for Emergencies

> **Status:** v1, approved by Ivan 2026-07-17. **Priority: P1** (safety > T5; T5 Phase 2
> parked at "Block C next" and resumes after Trail or when Ivan says so).
> **Based on:** repo HEAD `eb3e078`. Planning chat drafted this without repo access —
> every spot marked **MATCH EXISTING** must be resolved by Claude Code against the
> actual code, never invented fresh.

---

## 0. Purpose, scope, non-goals

A rolling, end-to-end-encrypted breadcrumb trail of a family member's phone, stored on
the family's own server, readable only by guardians the tracked person chose. Goal: when
someone goes missing, guardians always have the **last known position and the path
leading to it**, with enough context (battery, cell towers, Wi-Fi, device events) to
interpret how the trail ended — even if the phone has been dead or offline for months.

**Non-goals (state honestly in UI and docs):**
- Cannot locate a phone that is off. No app can. Trail's value is the *preserved path up
  to* the last moment.
- Not covert. Android forces a persistent notification for foreground location services;
  we embrace it as the transparency indicator. Trail is a family-safety feature, not
  surveillance-ware: consent invariants in §6 are non-negotiable.
- v1 is Android-framework-only: **no Google Play Services APIs** (no
  `FusedLocationProviderClient`, no Activity Recognition API). Consistent with project
  constraints; framework equivalents specified below.

---

## 1. Locked decisions (do not revisit without Ivan)

1. **E2E:** points encrypted client-side to each guardian; server stores ciphertext
   only. Per-guardian fanout using the **existing DM message crypto** (X25519 ECDH +
   HKDF + AES-256-GCM, per guardian device — MATCH EXISTING envelope). No new crypto
   primitives, no shared "location key" (that variant deferred, §8).
2. **Retention — frozen clock:** keep points newer than `MAX(ts) − 7 days` **measured
   from the newest point of that user**, not from now. Uploads stop → window freezes →
   trail of a missing person survives indefinitely. Config `locationRetentionDays: 7`.
   No archive tier (rejected: breach surface, E2E guardian-set aging, second subsystem).
3. **Incident preservation is client-side:** guardians' fetched copies + explicit trail
   export (GPX/JSON) are the long-term record; server stays minimal.
4. **Adaptive sampling:** GPS only while moving; stationary phones log cheap
   context on a slow heartbeat (Ivan's requirement, §3.3).
5. **Guardians:** mutual contacts only, chosen by the tracked person on their own
   device, cap `trailMaxGuardians: 5`, guardian must accept, either side can revoke.
   Separate from `auto_location` (different semantics; existing feature untouched).
6. **Transparency:** every guardian fetch is server-logged and pushed to the tracked
   person's devices. Not optional, no silent mode.
7. **Collect maximally per point** (Ivan: "all possible info"): full field list §2.
8. **Server work respects the drift rule:** additive-only surgical patches applied
   identically to repo and `/opt/fshu5/server.js`, or after reconciliation (preferred).
   Never wholesale deploy (standing rule from PROJECT_INSTRUCTIONS).

---

## 2. Wire format — LOCKED (Trail Block A.1)

### 2.1 Point (plaintext JSON; lives only inside encrypted batches and local Room)

```json
{"seq":123,"kind":"fix","ts":1752741000000,
 "lat":42.1354,"lon":24.7453,"acc":12.5,"alt":164.0,"spd":1.4,"brg":270.0,
 "prov":"fused","mock":false,"mot":"moving",
 "batt":63,"chg":false,"net":"cell",
 "cells":[{"t":"lte","mcc":284,"mnc":3,"tac":21901,"ci":123456789,"pci":211,"sig":-97,"reg":true}],
 "wifi":{"conn":{"b":"aa:bb:cc:dd:ee:ff","s":"HomeNet","r":-52,"f":5180},
         "scan":[{"b":"11:22:33:44:55:66","s":"CafeX","r":-71,"f":2437}]}}
```

- `seq` — per-device monotonic counter (gap detection). `ts` — epoch ms, client clock.
- `kind` — `"fix"` or `"event"`. Events carry `ev` + snapshot of last fix:

```json
{"seq":124,"kind":"event","ts":1752741600000,"ev":"shutdown",
 "batt":57,"chg":false,
 "last":{"lat":42.1354,"lon":24.7453,"acc":12.5,"ts":1752741000000}}
```

`ev` ∈ `shutdown | boot | airplane_on | airplane_off | loc_off | loc_on | sim_changed |
batt_low | charge_on | charge_off | svc_restart`. Events are the interpretation layer:
trail ending at `batt:2` = phone died; ending at `batt:74` + `shutdown` = powered off
deliberately; `svc_restart` gaps = OEM killer (doubles as the diagnostic for the
deferred OEM keep-alive item).

- Nullable fields omitted when unavailable (no GPS fix → event-only heartbeat with
  cells/wifi and no lat/lon is valid).
- `mock` — `Location.isMock()` (API 31+; `isFromMockProvider` fallback): spoof flag.
- Cell fields: `getAllCellInfo()`, all visible cells, `reg:true` marks the serving
  cell; `sig` = RSRP (LTE/NR) or RSSI (WCDMA/GSM), dBm.
- `wifi.conn` — currently connected AP (free, no scan, high value). `wifi.scan` —
  latest scan results; scans are opportunistic and throttle-aware (§3.4).

### 2.2 Client → server

```json
{"type":"trail-batch","batchId":"<uuid>","device":"<deviceId>",
 "seqLo":101,"seqHi":123,"tsLo":1752740000000,"tsHi":1752741600000,
 "for":[{"g":"maria","dev":"<guardianDeviceId>","iv":"<b64>","ct":"<b64>"}]}
```
`ct` = AES-256-GCM over the JSON array of points, key derived exactly as the existing
DM path (MATCH EXISTING: field names `iv`/`ct`/key-derivation/device fanout must mirror
the current message envelope — if DMs encrypt per guardian *device*, so does Trail).
Server ack: `{"type":"trail-batch-ack","batchId":"...","seqHi":123}`.

```json
{"type":"trail-grant","guardian":"maria"}
{"type":"trail-accept","user":"kid"}            // sent by guardian
{"type":"trail-revoke","guardian":"maria"}      // or by guardian: {"user":"kid"}
{"type":"trail-fetch","user":"kid","fromTs":0,"toTs":9999999999999}
{"type":"trail-wipe"}                            // tracked user: delete all my batches
```

### 2.3 Server → client

```json
{"type":"trail-data","user":"kid",
 "batches":[{"device":"...","seqLo":101,"seqHi":123,"tsLo":...,"tsHi":...,
             "serverTs":...,"iv":"...","ct":"..."}]}
{"type":"trail-accessed","by":"maria","fromTs":...,"toTs":...,"ts":...}
{"type":"trail-guardian-changed","user":"kid","guardian":"maria","state":"granted|accepted|revoked"}
{"type":"trail-stale","user":"kid","lastTs":...}   // optional alert, §4.4
```

---

## 3. Android client

### 3.1 Service
`TrailService`: foreground service, `foregroundServiceType="location"`, dedicated
notification channel, low-importance but honest text ("4shu — location trail active").
Started by the consent toggle (§6), restarted on `BOOT_COMPLETED`, survives via
battery-optimization exemption request. **MATCH EXISTING** foreground-service and
notification patterns from the current call/emergency services.

### 3.2 Location sources — framework only
- API 31+: `LocationManager.FUSED_PROVIDER` (platform fused, exists since 31 — fits
  the Android 12+ policy). Below 31 (minSdk 26 still in force): `GPS_PROVIDER` +
  `NETWORK_PROVIDER` directly.
- `PASSIVE_PROVIDER` listener **always registered**: free extra points whenever any
  other app requests location, zero battery cost.

### 3.3 Sampling state machine (Ivan's GPS rule)

| State | Interval | Sources | GPS? |
|---|---|---|---|
| MOVING | 2–5 min | fused/GPS, high accuracy | yes |
| STILL | 15–30 min heartbeat | network/passive + cells/wifi only | **no** |
| PANIC | 20–30 s | GPS, max accuracy, per-point flush | yes |

- STILL entry: 3 consecutive fixes within 100 m of anchor, or 20 min without
  significant motion. STILL heartbeat still logs a full enrichment point (cells, wifi,
  batt) — position inferred from anchor.
- STILL exit: `TYPE_SIGNIFICANT_MOTION` sensor (framework `SensorManager`) fires, or a
  passive fix lands >150 m from anchor → MOVING.
- PANIC: engaged by SOS (existing emergency actions — MATCH EXISTING hook), ignores
  STILL, ignores batching, flushes each point immediately.

### 3.4 Enrichment collectors
- Battery: `ACTION_BATTERY_CHANGED` sticky (pct + charging).
- Cells: `TelephonyManager.getAllCellInfo()` per point (needs fine location).
- Wi-Fi: connected AP via `WifiManager.connectionInfo` every point (free); active
  scans opportunistic — trigger at most once per MOVING point, reuse system scan cache
  (`getScanResults`) otherwise; respect the platform throttle (~4 scans/2 min
  foreground) — at our intervals it never binds.
- Events: receivers for `ACTION_SHUTDOWN` (log event; last-gasp flush comes in
  Block J), boot, airplane mode, location toggle, SIM state, battery low.

### 3.5 Storage — Room v25 → v26
New entity `TrailPoint` (mirrors §2.1, cells/wifi as JSON columns, plus `uploaded`
bookkeeping) + `TrailUploadState(guardianDevice, lastAckedSeq)` watermark table +
DAO. Local copy obeys the same frozen-clock 7-day purge (WorkManager daily). Trail
data joins the existing GDPR on-device export. `trail-wipe` + local wipe on disable.

### 3.6 Permissions (staged flow in Block D)
`ACCESS_FINE_LOCATION` → `ACCESS_BACKGROUND_LOCATION` (API 30+: user must pick "Allow
all the time" in Settings — guided walkthrough screen) → `FOREGROUND_SERVICE_LOCATION`
(API 34 manifest) → `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt → `RECEIVE_BOOT_COMPLETED`.
Wi-Fi scan results require fine location (already granted). Cell info likewise.
Motorola note: G60s are aggressive app-killers; the exemption prompt + `svc_restart`
events give us ground truth on kills.

---

## 4. Server

> **Drift rule applies (§1.8).** All changes additive: new tables, new handlers, new
> config keys. Zero edits to existing message paths. Apply identically to repo and
> live, or land after the reconciliation session (preferred if it happens first).

### 4.1 Tables
```sql
CREATE TABLE trail_guardians(user TEXT, guardian TEXT, granted_ts INTEGER,
  accepted_ts INTEGER, PRIMARY KEY(user, guardian));
CREATE TABLE trail_batches(id INTEGER PRIMARY KEY, user TEXT, device TEXT,
  guardian TEXT, seq_lo INTEGER, seq_hi INTEGER, ts_lo INTEGER, ts_hi INTEGER,
  server_ts INTEGER, iv TEXT, ct TEXT);
CREATE TABLE trail_access_log(id INTEGER PRIMARY KEY, user TEXT, guardian TEXT,
  fetch_ts INTEGER, from_ts INTEGER, to_ts INTEGER);
```
`server_ts` = arrival time: free cross-check against client clock drift.

### 4.2 Config (`data/config.json`)
`locationRetentionDays: 7`, `trailMaxGuardians: 5`, `trailStaleAlertHours: 0` (0 =
off). **Update `install-fshu-next.sh`** per standing rule (its canonical home is an
open reconciliation question — flag, don't block).

### 4.3 Handlers
- `trail-batch`: verify sender session; verify each `for[].g` is an accepted guardian;
  insert one row per guardian; ack. Reject rows for non-guardians silently (drop, log).
- `trail-fetch`: verify requester is accepted guardian of `user`; return that
  guardian's rows in range; insert access-log row; push `trail-accessed` to all of
  `user`'s devices (queue if offline — MATCH EXISTING offline queue).
- `trail-grant/accept/revoke`: maintain `trail_guardians`, enforce mutual-contact +
  cap, notify both sides. Revoke (either direction) deletes that guardian's future
  access; their existing ciphertext rows age out naturally (≤7 days).
- `trail-wipe`, account deletion, trail disable → delete user's batches immediately.

### 4.4 Purge — frozen clock (+ optional stale alert)
Daily, alongside existing retention jobs:
```sql
DELETE FROM trail_batches WHERE user=@u AND
  ts_hi < (SELECT MAX(ts_hi) FROM trail_batches WHERE user=@u) - @retentionMs;
```
Stale alert (only if `trailStaleAlertHours > 0`): newest `ts_hi` older than threshold →
one `trail-stale` push to guardians (existing push/FCM path), re-armed when uploads
resume. This is the "trail went silent" tripwire — the earliest possible signal that a
search should start. Metadata-only; server never reads positions.

---

## 5. E2E scheme

- Per-batch, per-guardian(-device) fanout with the existing DM derivation. Server =
  ciphertext post office, same promise as messages.
- **Re-encrypt on grant:** when a new guardian accepts, the tracked device re-uploads
  its current local 7-day window encrypted to them — new guardians get history
  immediately, without any server-side re-encryption ability. (This is why the local
  Room copy matters.)
- Revoked guardian: no new ciphertext; old rows age out in ≤7 days; nothing to rotate.
- Account-recovery note: secret-question reset yields a *new* device without old keys —
  a hijacker cannot decrypt existing trail ciphertext. Guardian grant/accept happens
  only on the tracked person's device. Document, don't "fix".

---

## 6. Consent & transparency invariants (UI copy in EN + BG)

1. Off by default. Enabled only from the tracked person's own device, behind the full
   staged-permission flow (no dark patterns; each screen says what's collected and who
   can see it).
2. Guardian list visible at all times in Trail settings; add = grant + guardian
   accept; remove = one tap, either side.
3. Persistent notification whenever collecting (Android enforces; we word it clearly).
4. Every fetch → `trail-accessed` notification + permanent access-log screen ("Maria
   viewed your trail, Jul 17 14:02, last 24 h").
5. Disable = stop service + local wipe + `trail-wipe` server-side, immediate.
6. Status card in settings: collecting since / points held / oldest–newest / last
   upload / service-health (restart count surfaced honestly).

---

## 7. Phases & blocks — session stopping points

Rules of engagement (unchanged from T5): **one block per prompt** to Claude Code; each
block ends with build-green + commit + `PROJECT_MEMORY.md` update + stop-and-report.
Push at session end. Every block boundary is a safe place for Ivan's session budget to
run out.

### Phase 1 — the phone collects (no server dependency; Ivan's "prepare the phone")
- **Block A — data model.** Room 25→26: `TrailPoint`, `TrailUploadState`, DAO,
  migration, point↔JSON serialization exactly per §2.1 + unit tests. *Accept: app
  builds, migration runs on a v25 DB, serialization round-trips.*
- **Block B — service + fix pipeline.** `TrailService`, providers (31+ fused /
  fallback / passive), MOVING↔STILL state machine, debug-only start toggle + manual
  permission grant (real UX in D). *Accept: G60 logs fixes to Room; STILL provably
  stops GPS (provider registration logged); walking flips it back.*
- **Block C — enrichment + events.** Battery, mock flag, net type, connected AP, cell
  list, throttle-aware scans; all §2.1 event receivers (shutdown = log only, flush
  later). *Accept: points on both G60s carry cells + wifi + batt; toggling airplane
  mode produces events.*
- **Block D — consent UI.** Trail settings screen: staged permission walkthrough,
  master toggle, guardian picker (UI only, contacts-backed, grant wire comes in
  Phase 2/3), status card, disable+wipe. Strings EN + BG. *Accept: clean-install
  enable flow end-to-end on Android 12.*
- **Block E — my-trail viewer + GDPR.** Local timeline list with per-point detail
  sheet (map reuse deferred to Block K), wipe button, trail in GDPR export. *Accept:
  own trail browsable; export contains it.* **← Phase 1 done: phone fully collects.**

### Phase 2 — the server stores (drift rule §1.8 governs when/how it lands)
- **Block F — schema + config + install script.** §4.1 tables, §4.2 keys. *Accept:
  fresh install and existing DB both get tables; config documented.*
- **Block G — handlers.** §4.3 messages + auth checks + transparency push. *Accept:
  scripted WS session exercises grant→accept→batch→fetch→access-log→revoke.*
- **Block H — purge + stale alert.** §4.4. *Accept: synthetic data proves the frozen
  clock (old points survive when uploads stop); alert fires once and re-arms.*

### Phase 3 — sync + guardians
- **Block I — upload queue + E2E.** Batching (10 pts / 5 min / network-regain),
  per-guardian encryption (MATCH EXISTING), watermarks, offline durability,
  re-encrypt-on-grant. *Accept: airplane-mode hour → reconnect → backlog lands; new
  guardian sees the past week.*
- **Block J — last-gasp + panic.** `ACTION_SHUTDOWN` best-effort synchronous flush;
  SOS engages PANIC (interval, accuracy, per-point flush). *Accept: graceful
  power-off delivers a final shutdown event when network allows; SOS visibly
  accelerates cadence.*
- **Block K — guardian trail viewer.** Fetch, decrypt, merge multi-device, timeline +
  map (MATCH EXISTING map approach from location sharing), event/gap annotations,
  battery badge on last point, "last known" card, GPX + JSON export to Downloads
  (police handoff), tracked-side access-log screen. *Accept: guardian G60 reconstructs
  the other G60's day and exports it.*
- **Block L — polish.** Notification/strings sweep EN + BG, README feature blurb,
  PROJECT_MEMORY final update, verify deferred list (§8) recorded.

### Suggested first prompt to Claude Code (copy-paste)
```
Read PROJECT_KNOWLEDGE.md, PROJECT_MEMORY.md and SPEC_T13.md. T5 is parked
(memory board: T5 paused after Block D.1, resumes later). Implement SPEC_T13
Phase 1 Block A only. Follow all project rules: update PROJECT_MEMORY.md
(changelog + board: add T13 as P1 IN PROGRESS, mark T5 PARKED), commit
alongside the change. Resolve every MATCH EXISTING marker against the real
code and record what you matched in an "Implementation notes" appendix at the
bottom of SPEC_T13.md. Stop and report when Block A builds.
```
Subsequent prompts: same shape, next block letter. If a session must end mid-block:
"commit what builds, report state, stop."

---

## 8. Deferred (record, don't build)
- Shared-location-key crypto (single ciphertext + key wrapping) — only if guardian
  fanout ever hurts (it won't at ≤5).
- Geofence alerts ("left school zone"), Wi-Fi RTT ranging, BLE beacons.
- Server push "trail-stale" richer policies (per-guardian thresholds).
- Copied-poll text + T12 list-bubble reactions (pre-existing T5 notes, unchanged).
- iOS. Desktop remains out per project rules.

## 9. Next planning session — what Ivan uploads
1. `PROJECT_MEMORY.md` (as updated by Claude Code — board + changelog tell me where
   Trail stands).
2. `SPEC_T13.md` as committed (with Claude Code's "Implementation notes" appendix —
   the resolved MATCH EXISTING decisions).
3. Paste of Claude Code's last stop-and-report output.

`PROJECT_KNOWLEDGE.md` / `PROJECT_INSTRUCTIONS.md` only if they changed. No source
files needed unless a block gets stuck — then just the files the report names.

---

## Implementation notes (Claude Code)

### Phase 1 Block A — 2026-07-18

**Stale-premise flag (not a MATCH EXISTING marker, but worth recording):** this spec's
header assumes T5 Phase 2 was parked at "Block C next." Per the repo's own
`PROJECT_MEMORY.md` (authoritative), T5 Phase 2 was already **complete** (Blocks A–F)
before this session started — nothing needed parking. Board left as-is; T13 added to
"In Progress" as P1.

**MATCH EXISTING resolutions:**

1. **Entity file location.** Repo has two conventions: older entities live in
   `data/model/` (`Message`, `PeerKey`, `Group`, `GroupMember`), newer ones in
   `data/local/entities/` (`Contact`, `Block`). Matched the newer convention:
   `TrailPoint`/`TrailUploadState` → `data/local/entities/`.
2. **DAO file location.** Same split: older DAOs sit directly in `data/local/`
   (`MessageDao`, `GroupDao`, `PeerKeyDao`), newer ones in `data/local/dao/`
   (`ContactDao`, `BlockDao`). Matched the newer convention: `TrailDao` →
   `data/local/dao/`.
3. **JSON serialization approach.** The app has two JSON idioms: hand-built
   `JsonObject`s for outbound WS envelopes (`FshuService`, `WebSocketClient`), and
   `PollParser`'s manual `JsonObject` walk for reading heterogeneous list-item kinds.
   Neither fits a single fixed-shape point. Used plain Gson data-class mapping
   instead (`TrailPointCodec`) — Gson omits null fields by default, which already
   satisfies §2.1's "nullable fields omitted when unavailable" rule with no custom
   JSON-building code.
4. **cells/wifi/last storage.** Per §3.5's explicit instruction, stored as JSON-string
   Room columns (`cellsJson`, `wifiJson`, `lastJson`) rather than embedded/nested
   Room objects, with `TrailPointMapper.kt` converting to/from the pure
   `TrailPointData` wire model (itself holding real nested objects, not strings).
5. **Migration/version numbering.** Repo was at Room schema version 25 (not 24 as
   `PROJECT_KNOWLEDGE.md`'s "Current State" section states — that doc is a snapshot,
   `PROJECT_MEMORY.md`/`AppDatabase.kt` are authoritative). Bumped to 26.

**Deliberately deferred to later blocks (not built now):** upload-queue / mark-
uploaded DAO methods, watermark consumption logic, event-receiver wiring, the
`ev` enum (kept as a plain `String` on `TrailPointData` since Block C is what actually
produces values for it) — all per Block A's stated scope (data model only).

**Verification status (updated 2026-07-18):** Ivan confirms Gradle build SUCCESSFUL
(52s) — compiles clean, `26.json` schema export generated and committed. Unit tests
not yet executed (plain build, no test task run). On-device v25→v26 migration
verification deliberately deferred — Ivan will batch-test all of Phase 1 on the G60s
after Block E, installing as an UPDATE over the current v25 app, rather than a
piecemeal per-block device pass. See "Phase 1 device-test checklist" below.

### Phase 1 Block B — 2026-07-18

**MATCH EXISTING resolutions:**

1. **Foreground service + notification pattern.** Matched `FshuService`'s style:
   `NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(...).setContentText(...)
   .setSmallIcon(R.drawable.ic_notification).setOngoing(true)`, channel created via
   `getSystemService(NotificationManager::class.java).createNotificationChannel(...)`
   at `IMPORTANCE_LOW` (mirrors `FshuService`'s own `CHANNEL_ID = "fshu_fg"` channel,
   used for its own persistent "service running" notification — the closest existing
   analog to Trail's "collecting" notification). New channel: `fshu_trail`.
2. **Own service, not folded into `FshuService`.** §3.1 names `TrailService` as its
   own service with a dedicated channel. Considered T7 Block B's precedent (adding
   `mediaProjection` to `FshuService`'s existing multi-type foreground service instead
   of a separate `ScreenCaptureService`) but did not extend that pattern here — T7's
   reuse was specifically because screen-share is tied to an active call already
   running inside `FshuService`/`CallViewModel`; Trail is an independent, long-running
   collector with its own lifecycle, unrelated to calls or the WS connection. Matches
   the spec's explicit naming, not T7's reuse decision.
3. **Location provider — deliberate non-match.** The existing location code
   (`util/LocationHelper.kt`, used by location-sharing/emergency-location) uses
   `LocationServices.getFusedLocationProviderClient` (Google Play Services). SPEC_T13.md
   §0 non-goal #3 explicitly forbids Play Services APIs for Trail v1. Used platform
   `android.location.LocationManager` instead (`LocationManager.FUSED_PROVIDER` on
   API 31+, `GPS_PROVIDER`+`NETWORK_PROVIDER` fallback below it, `PASSIVE_PROVIDER`
   always-on) — a deliberate deviation from the app's usual location convention, per
   the spec overriding it for this feature specifically.
4. **`getSystemService` style.** Matched the `getSystemService(Foo::class.java)` form
   used throughout `FshuService`/`SettingsFragment`/`ChatActivity`, not the older
   `getSystemService(Context.X_SERVICE) as Foo` cast style.
5. **Debug-only start toggle.** Matched `CallActivity`'s T7 Block D precedent
   (`BuildConfig.DEBUG`-gated long-press on an existing, gesture-free view; a "TEMP —
   remove at Block [x]" comment marking it for deletion once the real UI lands).
   Placed on `SettingsFragment`'s `tvVersion` line (already present, no existing
   gesture) rather than adding new UI ahead of Block D's real consent screen.
6. **Manifest permissions.** No new `<uses-permission>` entries — `ACCESS_FINE_LOCATION`,
   `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE` were
   already declared (added for the existing location-sharing feature). The debug
   toggle's runtime grant reuses the same permission pair `PermissionSetupActivity`
   already requests at onboarding step 3 ("Allow Location", skippable) — if that was
   skipped, the toggle requests it itself via `RequestMultiplePermissions` before
   starting the service.
7. **`ACCESS_BACKGROUND_LOCATION` deliberately not requested yet.** A foreground
   service actively showing a notification is treated as "foreground" for location
   delivery on every supported API level (26–34) — not required for Block B's fix
   pipeline. Real background-location UX is explicitly Block D's staged walkthrough
   (§3.6).

**Deliberately deferred to later blocks (not built now):** battery/mock-elsewhere/
cell/wifi/event enrichment beyond the bare fix fields (Block C); `BOOT_COMPLETED`
restart + battery-optimization-exemption request for `TrailService` itself (§3.1
lists these, but they belong with the real always-on, consent-driven service in
Block D — the debug toggle is manually started per test session, not meant to survive
reboots); PANIC state (Block J); the real staged consent/permission UI (Block D) —
Block B reuses the existing onboarding location-permission step plus its own debug
toggle only.

**Sampling constants chosen (spec gives bands, not exact numbers):** MOVING interval
3 min (of the 2–5 min band), STILL heartbeat 20 min (of the 15–30 min band), STILL
entry radius 100 m / 3 consecutive fixes / 20 min timeout, STILL exit radius 150 m —
all exactly the values named in §3.3, no invented numbers.

**Verification status (updated 2026-07-18):** Ivan confirms Gradle build SUCCESSFUL
(30s). Unit tests still not run; device verification remains deferred to the
end-of-Phase-1 batch test (same deferral as Block A). Provider registration/
deregistration and state transitions are logged (`Log.i`, tag `TrailService`)
specifically so that batch test can confirm STILL provably drops GPS/FUSED
registration, per the block's Accept criterion.

### Phase 1 Block C — 2026-07-18

**MATCH EXISTING resolutions:**

1. **Battery read — sticky-peek, not a live receiver.** `context.registerReceiver(null,
   IntentFilter(ACTION_BATTERY_CHANGED))` returns the cached sticky intent synchronously
   with no persistent receiver needed — the simplest correct idiom for "read battery
   right now," called fresh at every point (fix or event) rather than cached on a
   receiver-driven field.
2. **Net type — synchronous read, not `FshuService`'s `NetworkCallback` pattern.**
   `FshuService.registerNetworkCallback` (§3.1's neighbor in the codebase) is
   event-driven (reconnect-on-change), which fits its job but not this one — Trail
   needs "what's the transport right now, at this exact point," a one-shot
   `ConnectivityManager.getNetworkCapabilities(activeNetwork)` read, not a standing
   callback. Different problem shape, deliberately not reused.
3. **Cell/wifi framework APIs — first use in the codebase**, no existing convention to
   match. `TelephonyManager.getAllCellInfo()` gated on the same `ACCESS_FINE_LOCATION`
   check already used for the location providers (§3.4: "needs fine location," no
   `READ_PHONE_STATE` requested — would need its own runtime-consent story, out of
   scope for a still-no-consent-UI block). Wifi connected-AP read follows the
   API-31-transportInfo/legacy-connectionInfo split the spec itself specifies (§3.4),
   there being no pre-31 vs 31+ precedent elsewhere in the app to match.
4. **Naming collisions with `com.fshu.next.trail`'s own models.** `android.net.wifi.
   WifiInfo` and `android.telephony.CellInfo` collide by name with our own
   `trail.WifiInfo`/`trail.CellInfo` data classes (Block A). Aliased the Android types
   on import (`... as AndroidWifiInfo`, `... as TelephonyCellInfo`) rather than fully
   qualifying every reference or renaming our own Block A models — keeps the wire-model
   names matching §2.1 exactly, which is the more load-bearing constraint.
5. **Event receivers — dynamic registration, matching `ChatActivity`'s
   `screenOnReceiver` pattern** (`registerReceiver(receiver, IntentFilter(...))` /
   `unregisterReceiver(...)` tied to a lifecycle, no manifest `<receiver>` entries, no
   explicit `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` flag) — the only existing
   dynamic-receiver precedent in the app. All eight actions here are system-protected
   broadcasts (can't be spoofed by a third-party app), the same category as
   `ACTION_SCREEN_ON` in that precedent, so the unflagged 2-arg form was matched
   as-is rather than introducing a new pattern.
6. **CORRECTED (was wrong, caught by the compiler):** the first pass claimed
   `TelephonyManager.ACTION_SIM_STATE_CHANGED` is a public, non-hidden, merely-
   deprecated field. It is not — Ivan's Gradle build failed on it. The constant
   actually lives in the internal `com.android.internal.telephony.TelephonyIntents`
   class, not on the public `TelephonyManager` at all, so the reference didn't
   resolve. Fixed by using the literal action string
   `"android.intent.action.SIM_STATE_CHANGED"` directly (`TrailService.
   ACTION_SIM_STATE_CHANGED`, a private `const val` with a comment explaining why).
   The broadcast itself is still real and still delivered to dynamically registered
   receivers — only the SDK-constant claim was false, not the mechanism. The newer
   `ACTION_SIMCARD_STATE_CHANGED` replacement remains correctly out of reach:
   `@SystemApi`, requires `READ_PRIVILEGED_PHONE_STATE` (platform/carrier-signed
   only), not obtainable by this app.

**Deliberate spec extension (flagging, not a MATCH EXISTING item):** the original
§2.1 `ev` enum lists only `batt_low`, not a paired "back to normal" event. This
session's instruction explicitly asked for both `ACTION_BATTERY_LOW` **and**
`ACTION_BATTERY_OKAY`, so a new event value `batt_okay` was added — safe because
Block A deliberately kept `ev` as a plain `String` (not a closed Kotlin enum) for
exactly this kind of forward-compatible extension. `SPEC_T13.md` §2.1's own text
should be considered amended to include `batt_okay` in the `ev` set alongside
`batt_low`.

**Deliberately deferred to later blocks (not built now):** `boot`/`svc_restart`
events — explicitly out of scope this block per instruction, since the restart
machinery those events describe lands in Block D; upload/E2E fan-out of any of this
(Phase 3); PANIC-state accuracy/cadence overrides (Block J); real consent UI (Block D).

**New permission: `CHANGE_WIFI_STATE`** (manifest), required for `WifiManager.
startScan()`'s opportunistic-scan trigger. Per `CLAUDE.md`'s build-type rule, this
makes Block C's install a **REINSTALL**, not the UPDATE that sufficed for Blocks A/B.

**Verification status:** could not run Gradle/adb (project rule). Cell/wifi/battery/
net reads all fail closed (return `null`/omit the field) on any exception or missing
permission rather than crashing the service — consistent with §2.1's "nullable fields
omitted when unavailable." Needs Ivan's build + the device-test checklist below.

**Follow-up (2026-07-18, same day):** Ivan's build failed on this block — three root
causes, all fixed, no new features. Full detail in `PROJECT_MEMORY.md`'s Block C
compile-fix changelog entry; the short version: (1) `CellInfoNr`'s identity/signal-
strength getters are typed to return the BASE `CellIdentity`/`CellSignalStrength`
(unlike Lte/Wcdma/Gsm), needing explicit casts; (2) `readConnectedWifiAp`'s nullable-
typed if/else-then-elvis shape didn't carry a smart-cast guarantee, restructured
two-step; (3) `TelephonyManager.ACTION_SIM_STATE_CHANGED` isn't public SDK at all —
**implementation note #6 above was wrong about this and has been corrected in place**;
fixed with the literal action string instead. Ivan then confirmed BUILD SUCCESSFUL.

### Phase 1 Block D — 2026-07-18

**MATCH EXISTING resolutions:**

1. **Staged-permission-walkthrough style.** New `TrailPermissionActivity` mirrors
   `PermissionSetupActivity`'s step-list/step-indicator pattern (title/description/
   button, `Step` data class, `currentStep`/`advance()`) on its own layout/activity
   rather than reusing that one directly — this flow is re-enterable from Trail
   settings at any time, not a one-shot first-launch screen the other one is. Unlike
   `PermissionSetupActivity`'s hardcoded-English step descriptions, every Trail step
   string is a proper `@string` resource with an EN+BG pair — Block D's own instruction
   requires that; not a criticism of the precedent, just a stricter bar this block sits
   under.
2. **Background location — guided to Settings, not an in-app runtime dialog.** §3.6
   says "guided walkthrough screen," and Android's own policy (10+) restricts
   requesting `ACCESS_BACKGROUND_LOCATION` via a normal in-app permission dialog
   (behavior varies by OEM/API level when attempted). Sent uniformly to
   `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for API 29+ instead — one code path,
   correct on every OEM, matches the spec's own wording rather than fighting a
   restricted API.
3. **Settings-screen visual language.** `activity_trail_settings.xml` matches
   `PrivacySettingsActivity`/`fragment_settings.xml`'s card+row idiom exactly
   (`MaterialCardView` with `colorSurfaceElevated`/`radius_card`, `SwitchCompat` — not
   `SwitchMaterial`, which isn't used anywhere else in the app — matched after an
   initial wrong guess). Guardian-add dialog matches T16's member-picker precedent
   verbatim (`MaterialAlertDialogBuilder(...).setItems(names) { _, index -> ... }`,
   `ChatActivity.showTransferOwnershipDialog`).
4. **Boot-restart wiring lives in the existing `ServiceRestartReceiver`,** not a new
   receiver — it's already the app's one `BOOT_COMPLETED`-family receiver
   (`directBootAware="true"`, already handles `LOCKED_BOOT_COMPLETED`/
   `USER_UNLOCKED`/the alarm-watchdog check for `FshuService`). `Intent.
   ACTION_BOOT_COMPLETED` previously had no explicit branch there (fell through to a
   generic fallback with identical `FshuService` behavior) — given one now so Trail's
   `boot` trigger attaches only to a genuine boot completion, not the generic
   `ACTION_RESTART_SERVICE` case that also reaches that fallback.
5. **Direct-boot storage — deliberately NOT matched for Trail.** `FshuService`'s
   `LOCKED_BOOT_COMPLETED` branch reads a separate plain-prefs flag (`fshu_boot` /
   `was_logged_in`) instead of the main `Prefs`, because normal (credential-encrypted)
   SharedPreferences aren't reliably readable before first unlock. `trail_enabled`
   lives in the same normal prefs as everything else Trail — reading it during
   `LOCKED_BOOT_COMPLETED` would be unreliable the same way. Rather than duplicating
   that direct-boot-safe-flag machinery for Trail, the boot-restart check was scoped to
   `USER_UNLOCKED` and `BOOT_COMPLETED` only, both of which run once storage is
   actually available in normal operation — simpler, and the gap (missing the
   direct-boot phase specifically) is a rare, narrow window, not a functional loss.
6. **`svc_restart` detection needs no new state at all.** Android redelivers
   `onStartCommand` with a **null** `Intent` specifically and only when it auto-restarts
   a previously-killed `START_STICKY` service — this is already unambiguous on its own
   (documented platform behavior), so no restart-generation counter or persisted flag
   was needed to distinguish it from a normal `startService`/`startForegroundService`
   call (which always delivers a real Intent, even with no extras).

**Deliberate omission (not a bug):** the status card skips the "last upload" row from
§6.6's list. Nothing uploads yet in Phase 1 (upload lands in Block I, Phase 3) — there
is no last-upload state to show, so the row isn't rendered rather than showing a
permanently-empty placeholder.

**§3.6 API 31+ interaction, noted per instruction, not coded around:** a location
foreground service started from the background (e.g. by the boot receiver, with no
activity in the foreground) only actually receives location updates if
`ACCESS_BACKGROUND_LOCATION` is granted. This needs no extra runtime handling — it's
exactly why the staged walkthrough asks for that permission before Trail can be
enabled at all; if the user skipped that step, `TrailService` still starts and still
runs its state machine/enrichment/events correctly, it just won't receive GPS fixes
reliably once the app leaves the foreground, which is an accurate (not broken)
reflection of what the user chose to skip.

**Deliberately deferred to later blocks (not built now):** the access-log screen and
`trail-accessed` notification from §6.4 (Phase 2/3 — no fetches can happen yet, there's
nothing to log); guardian grant/accept wire messages (Phase 2/3, per instruction — the
picker is honestly labeled local-only); PANIC-state overrides (Block J); my-trail
viewer / GDPR export wiring (Block E, next).

**New permission: `ACCESS_BACKGROUND_LOCATION`** (manifest) — adds to the same
REINSTALL-vs-UPDATE tension already flagged in `PROJECT_MEMORY.md`'s Open Questions
for Block A (schema bump) and Block C (`CHANGE_WIFI_STATE`); not re-litigated here,
same open item.

**Verification status:** could not run Gradle/adb (project rule). Needs Ivan's build +
the device-test checklist below.

---

## Phase 1 device-test checklist (batch test, on the G60s, after Block E)

Seeded by Blocks A/B/C; each later block appends its own subsection below.

### Block A
- [x] App builds (Gradle) — confirmed by Ivan, SUCCESSFUL (52s), 2026-07-18.
- [ ] `./gradlew test` passes (`TrailPointCodecTest`, `TrailPointMapperTest`).
- [ ] Migration 25→26 runs cleanly installing as an UPDATE over a v25 app — no data
      loss on existing tables (messages/contacts/groups/etc. still intact after).

### Block B
- [ ] Long-press the version line in Settings (debug build): grants location
      permission if missing, then starts `TrailService`.
- [ ] Persistent low-importance "4shu — location trail active" notification appears
      while running.
- [ ] Logcat (`TrailService` tag) shows `provider registered: fused ...` (or the
      `gps`/`network` fallback below API 31) and `provider registered: passive` on start.
- [ ] Walking around for several minutes produces MOVING fixes in `trail_points`
      (`mot='moving'`, non-null lat/lon) roughly every ~3 min.
- [ ] Leaving the phone stationary (~20 min, or 3 consecutive fixes within 100 m)
      triggers STILL: logcat shows `state -> STILL` and `provider unregistered:
      fused/gps/network`, confirming GPS/FUSED registration is provably gone.
- [ ] While STILL, `trail_points` still gets occasional heartbeat rows via `network`
      — no `gps`/`fused`-provider rows land during this window.
- [ ] Walking away from the STILL anchor flips back to MOVING (logcat `-> MOVING`),
      via either the passive->150 m check or the significant-motion sensor firing.
- [ ] Long-press again stops the service; notification clears; logcat shows all
      providers unregistered.

### Block C
- [ ] Rows in `trail_points` (fixes and STILL heartbeats alike) show non-null
      `batt`/`chg` matching the phone's actual battery state at the time.
- [ ] `net` reads `wifi` on Wi-Fi, `cell` on mobile data, `offline`/null with both off.
- [ ] `cells` is populated on rows taken with a SIM inserted and cell signal present
      (check `t`/`sig`/`reg` at minimum; `mcc`/`mnc` should match the home carrier).
- [ ] `wifi.conn` is populated while connected to a known AP (bssid/ssid/rssi/freq
      look sane); `wifi.scan` has entries when other networks are in range.
- [ ] Logcat shows `wifi scan triggered (opportunistic, MOVING point)` at most once
      per MOVING point, never during STILL heartbeats or passive fixes.
- [ ] STILL heartbeat rows carry the same batt/net/cells/wifi enrichment as MOVING
      fixes — enrichment isn't dropped just because GPS is off.
- [ ] Toggling airplane mode on then off produces a paired `airplane_on`/`airplane_off`
      event row each, both carrying a `last` snapshot and `batt`/`chg`.
- [ ] Toggling location services off/on produces one `loc_off` and one `loc_on` event
      each (not one per individual provider — confirm no duplicate spam).
- [ ] Plugging in / unplugging the charger produces `charge_on`/`charge_off` events.
- [ ] Ejecting/reinserting the SIM (or toggling airplane mode, which also flips SIM
      readiness on some OEMs) produces a `sim_changed` event.
- [ ] `PROJECT_MEMORY.md`/spec note the new `CHANGE_WIFI_STATE` permission — confirm
      the batch test installed this build as an UPDATE over the v25 app (per the
      amended `CLAUDE.md` build-type rule, resolved 2026-07-18 — `CHANGE_WIFI_STATE`
      is a normal install-time permission, auto-granted on update).

### Block D
- [ ] On a clean Android 12 install (or an account with Trail never enabled), the
      Settings → Trail row opens `TrailSettingsActivity` with the toggle off and the
      status/guardian sections hidden.
- [ ] Turning the toggle on launches the staged walkthrough; each visible step's text
      plainly states what's collected and who can see it (§6.1) before its button acts.
- [ ] **Deny path, location step:** denying fine location aborts the walkthrough,
      shows the "Trail needs Location access" toast, returns to Trail settings with
      the toggle back off, and `TrailService` never starts.
- [ ] **Skip path, background location step:** tapping Skip advances without opening
      Settings; Trail still ends up enabled at the end of the flow.
- [ ] **Skip path, battery step:** same — skip advances, Trail still ends up enabled.
- [ ] Completing (or skipping) every step returns to Trail settings with the toggle
      on, the status card visible, and `TrailService` running (persistent notification
      present).
- [ ] Re-opening Trail settings while already enabled does NOT re-trigger the
      walkthrough (toggle just reflects the stored state via `onResume`).
- [ ] Status card shows a real "Collecting since" timestamp, a growing point count,
      and a real oldest/newest range once at least one point has been written.
- [ ] "Add guardian" lists only mutual (accepted) contacts, excludes ones already
      added, and is disabled once 5 guardians are added (toast on attempting a 6th via
      any other path). The on-screen note makes clear this list isn't sent anywhere yet.
- [ ] Removing a guardian updates the list immediately and re-enables "Add guardian"
      if it had hit the cap.
- [ ] Rebooting the device with Trail enabled: `TrailService` restarts on its own
      (persistent notification reappears without opening the app), and logcat /
      `trail_points` shows one `boot` event shortly after.
- [ ] Force-stopping/killing `TrailService`'s process (not via the app) while Trail is
      enabled and waiting for the OS to restart it produces a `svc_restart` event, and
      the status card's restart count increments.
- [ ] Toggling Trail off shows the disable confirmation dialog; confirming stops the
      service (notification clears) and empties `trail_points` (status card reverts to
      hidden); cancelling leaves everything running and the toggle back on.
- [ ] The Block B debug long-press on the Settings version line no longer does
      anything Trail-related (removed this block) — long-pressing it is inert.
- [ ] Confirm the batch test installed this build as an UPDATE over the v25 app (new
      `ACCESS_BACKGROUND_LOCATION` permission; per the amended `CLAUDE.md` build-type
      rule, resolved 2026-07-18 — same as Blocks A/C, all UPDATE-installable now).
