# T13 Phase 2/3 — Next Session Startup Notes (updated 2026-08-27, session 2)

**Status:** T13 Phase 2/3 trail server persistence LIVE on /opt/fshu5 (app installed &
working). This session cleared four of the five deferred items and wrote the SAFE half of
the guardian viewer (crypto/merge/export logic). The guardian-facing UI (map/timeline/
export screen/access-log screen) is the one build-bound piece still to do — deferred on
purpose because it needs a live Android build to iterate against ("limited testing power").

## Done this session (all edits are ADDITIVE; nothing existing removed)

- **(c) labels + strings** — `panic_on`/`panic_off` added to `TrailLabels.event()`; EN
  `values/strings.xml` + BG `values-bg/strings.xml` gained `trail_event_panic_on/off`.
  (`sim_changed` / `svc_restart` were already mapped and already had strings.)
- **(b) SOS -> PANIC** — `ChatViewModel.sendSosMessage()` now calls
  `TrailService.engagePanic(getApplication(), true)` after the SOS send, **guarded on
  `Prefs.isTrailEnabled(...)`** so an SOS never silently starts a location trail the user
  never opted into. See "Open decision" below re: the (false) dismiss.
- **(d) server trail-stale alert** — `server.js`: `checkTrailStale()` on an hourly
  interval; when a tracked user's newest `ts_hi` is older than `trailStaleAlertHours`,
  pushes ONE `trail-stale` to each accepted guardian via `deliverOrQueue` + `sendFcmWakeup`
  for offline guardians. Re-armed when uploads resume (trail-batch clears the per-user
  flag; a fresh newest ts_hi also clears it). Off when `trailStaleAlertHours = 0` (default).
- **(e) backfill dedup-by-seq** — `server.js`: `trail-batch` now skips inserting a
  re-encrypted backfill (fresh batchId) whose `(user, device, guardian, seq_lo, seq_hi)`
  already exists. Live batches carry increasing seq so never collide; only duplicate
  backfill windows are dropped up front. New prepared stmts: `existsBatchSeq`,
  `staleCandidates`, `acceptedGuardians`.
- **(a) guardian viewer — SAFE LOGIC LAYER only:**
  - `EcdhHelper.decryptTrailBatch(convKey, ivB64, ctB64)` — exact reverse of
    `encryptTrailBatch` (the client never decrypted a trail batch before).
  - `trail/GuardianTrail.kt` — `assemble(myPriv, myUsername, trackedUsername,
    trackedPubHex, batches)`: decrypt every batch with the shared conversation key, parse
    the JSON point array, merge across devices, dedupe by `(device, seq)`, sort by ts, and
    surface **`lastKnownFix`** + **`recentSegment`** (last-known first, as requested).
  - `trail/TrailExport.kt` — pure GPX 1.1 + JSON string builders + `fileStem()`.

`node --check server.js` passes. Kotlin was NOT compiled (no Gradle/kotlinc on this
mount) — brace-balance only. New .kt files are standalone (no existing call sites), so any
compile error is isolated to them.

## Server drift rule — ACTION REQUIRED

The (d)+(e) changes were applied to the **repo** `server.js` only. Per the additive-only
drift rule they must be mirrored **identically** to the live `/opt/fshu5/server.js`. This
Cowork session cannot reach the live box — **Claude Code on this machine deploys.**
`server-t13-phase2.patch` is the earlier Phase-2 patch; these are further additive hunks
on top (see `git diff server.js`).

## Guardian viewer — what's LEFT (build-bound, do interactively next)

1. **WS glue:** send `{type:'trail-fetch', user, fromTs, toTs}`; handle incoming
   `{type:'trail-data', user, batches:[...]}` -> map each into `GuardianTrail.Batch` ->
   `GuardianTrail.assemble(...)`. Guardian's own priv = `Prefs.getEcPrivateKey`, username =
   `Prefs.getUsername`; tracked person's pubHex = `peerKeyDao().get(tracked).publicKey`.
2. **Viewer screen:** reuse the EXISTING map approach from `TrailViewerActivity`
   (tracked-person's own local viewer) — same map lib, same row rendering / TrailLabels /
   TrailPointDetailSheet. Show `lastKnownFix` as a "last known" card + map marker first,
   draw `recentSegment` prominently, then the full merged path with event/gap/battery
   annotations. `susp` points get the same styling the local viewer already uses.
3. **Export button:** `TrailExport.toGpx(assembled.points, name)` /
   `.toJson(assembled, tracked)` -> write to Downloads via MediaStore
   (`MediaStore.Downloads`, `application/gpx+xml` and `application/json`),
   filename `TrailExport.fileStem(tracked) + ".gpx"/".json"`.
4. **Access-log screen (tracked side):** the server logs each fetch and pushes
   `{type:'trail-accessed', by, fromTs, toTs, ts}` to the tracked user in real time
   (no server fetch endpoint exists for the log — accumulate the pushes client-side, or
   add an additive `trail-access-log` WS query if a full history screen is wanted).

## Open decision — SOS PANIC dismiss

Spec (b) says "engagePanic(...,true) on SOS trigger and (false) on dismiss." There is **no
sender-side stand-down / "I'm safe" flow** in the app today, so only the `true` half is
wired; PANIC stays engaged (25 s sampling + per-point upload) until cleared. Options:
add an "I'm safe" affordance that calls `engagePanic(context, false)`, and/or an auto-clear
timer. Left for a product decision — call site + exact line are commented in
`ChatViewModel.sendSosMessage()`.

## Acceptance still to run (unchanged)

Block F/G/I: enable Trail -> collect -> airplane-mode -> reconnect -> backlog lands within
seconds, seq contiguous; admin `/admin/trail` reconstructs the run incl. `susp` points.
Then Block K: guardian G60 reconstructs the other G60's day and exports GPX+JSON.

## Pointers

- `SPEC_T13.md` — Block F–L notes + checklists (Block K = the viewer).
- `SPEC_T13_PHASE2_SERVER_PERSISTENCE.md` — design + envelope §9a.
- `DEPLOY_T13_PHASE2.md` — deploy runbook.
- `PROJECT_MEMORY.md` — changelog + board (update: T13 (b)(c)(d)(e) done, (a) logic layer
  done, (a) UI deferred).
