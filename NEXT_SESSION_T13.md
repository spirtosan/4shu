# T13 — Next Session Startup Notes (updated 2026-08-27, session 2 close)

**Status:** Guardian trail feature COMPLETE and shipped. Code committed + pushed
(HEAD **a2c7605**); server changes LIVE on /opt/fshu5. Nothing left to build. **Next
session = device install + end-to-end acceptance testing** (no coding expected unless a
test surfaces a bug). The only uncommitted files in the tree are Ivan's unrelated
pre-existing Android Studio work (CLAUDE.md, cpp/, MainActivity, etc.) — leave them alone.

## What shipped this session (commits 6e92c5f, a2c7605)
- Guardian relationship wire: grant/revoke (tracked side) + accept/decline/stop (guardian
  side), ward lists maintained app-wide by `GuardianRegistry`.
- `GuardianWardsActivity` ("People I guard") + card in Trail settings.
- `GuardianTrailViewerActivity`: trail-fetch -> decrypt/merge (`GuardianTrail.assemble`) ->
  last-known card + Open in Maps + reverse-chron list + GPX/JSON export (SAF, no perms).
- `AccessLogStore` + `TrailAccessLogActivity` ("Who viewed my trail"), fed by trail-accessed
  pushes (no server change).
- Server: (d) hourly trail-stale alert, (e) backfill dedup-by-seq, PC `/admin/trail` viewer
  now renders EVENTS (markers + list, labels incl. panic_on/off). Deployed; backups
  server.js.bak-2026-08-27-1512 and -1600.

## INSTALL: update, not clean install
No Room schema / data-format change this session (new state is SharedPreferences only).
**Install over the existing app (update)** — it preserves `peer_keys` + contacts, which the
guardian NEEDS to decrypt. Clean install wipes keys and forces a re-sync. Only uninstall if
Android refuses the update with a signature mismatch (different signing key/variant); then
after clean install, send a normal message between the two devices first so keys re-sync.

## PRE-FLIGHT (before any trail test)
1. Both G60s on the new build (update-installed).
2. The two accounts are **mutual contacts** (guardians must be mutual contacts).
3. **Keys exchanged:** send one normal message each way and confirm delivery — this ensures
   each device has the other's public key in `peer_keys`. If missing, the viewer will say
   "key isn't available yet" (correct, not a crash).

## ACCEPTANCE RUNBOOK  (A = tracked person, B = guardian)
1. **Enable trail on A** (Trail settings -> toggle). Confirm A starts collecting (status card).
2. **Grant:** A -> Trail settings -> add B as guardian. Server log: trail-grant, then
   `trail-guardian-changed granted` delivered/queued to B.
3. **Accept:** on B, Trail settings shows "People I guard" card w/ badge -> open ->
   Requests -> Accept A. Server log: trail-accept + `trail-guardian-changed accepted`.
   A's device should then backfill its current window (TrailUploader backfillGuardian).
4. **Collect + durability:** move A around; airplane-mode A ~1 min; reconnect. Backlog
   should land within seconds, seq contiguous.
5. **View (B):** B -> People I guard -> A -> View trail. Expect: last-known card (coords +
   time) at top, Open in Maps works, reverse-chron list of fixes/events. Check the summary
   line counts (points / batches / undecryptable=0 ideally).
6. **Export (B):** menu -> Export GPX and Export JSON -> file picker -> save. Open the GPX
   (e.g. in a maps app) to confirm the track; JSON contains points + lastKnownFix.
7. **Transparency (A):** after B fetches, A -> Trail settings -> "Who viewed my trail" ->
   shows B + timestamp.
8. **SOS/PANIC (optional):** A sends SOS to B -> A's trail sampling accelerates
   (recordEvent panic_on; ~25s interval). NOTE: no stand-down yet — panic stays until app
   restart/clear.
9. **Revoke:** B "Stop guarding" (or A removes B) -> relationship clears both sides; A's
   future uploads no longer fan out to B; old ciphertext ages out <=7d.
10. **PC admin viewer:** /admin/trail -> admin login + passphrase -> target = A -> View.
    Confirm the run reconstructs incl. susp points (red) AND events (blue markers at last-
    fix + events list, e.g. shutdown / airplane_on / panic_on). Download JSON works.

## If a bug shows up (likely-suspect map)
- Guardian sees "key isn't available yet" -> keys not exchanged (see pre-flight 3).
- Viewer empty but batches>0 undecryptable>0 -> key mismatch (wrong pubkey / not the
  intended guardian). Verify B is the accepted guardian and A uploaded after accept.
- No "People I guard" card on B -> B never received `granted` (offline at grant + no queue
  flush?) — reopen after reconnect; GuardianRegistry updates from the queued push.

## Deliberately parked (not bugs)
- SOS sender-side "stand down" affordance (call `engagePanic(context,false)`; site
  commented in ChatViewModel.sendSosMessage).
- PC admin viewer sorts points by seq — fine single-device; would interleave a multi-device
  target (guardian app viewer handles multi-device correctly via (device,seq) merge).

## Pointers
SPEC_T13.md (Block K), SPEC_T13_PHASE2_SERVER_PERSISTENCE.md (envelope §9a),
DEPLOY_T13_PHASE2.md, PROJECT_MEMORY.md. Rollback server:
cp /opt/fshu5/server.js.bak-2026-08-27-1600 /opt/fshu5/server.js && systemctl restart fshu5
