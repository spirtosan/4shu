# T13 — Next Session Startup Notes (updated 2026-08-27, session 2)

**Status:** T13 server persistence LIVE on /opt/fshu5. Deferred items (b)(c)(d)(e) done.
The full **guardian trail feature is now written** (grant -> accept -> view -> export +
access transparency) as Chunks 1-4. Chunks 1-2 built green; **Chunks 3-4 await an Android
Studio build**. One further server.js change (PC admin-viewer event rendering) needs deploy.

## Cowork-session changes (all additive)

### Server (server.js) — needs mirroring to live /opt/fshu5 + restart (Claude Code)
- (d) hourly `checkTrailStale()` trail-stale guardian alert (off when trailStaleAlertHours=0).
- (e) backfill dedup-by-seq guard in `trail-batch`.
- **PC admin viewer** (`/admin/trail`): now renders EVENTS — map markers at each event's
  `last` location + an events list with labels incl. panic_on/panic_off. Previously it
  dropped all events and plotted only fixes. (First deploy already carried (d)+(e); this
  admin-page change is an additional deploy.)

### App — guardian feature (Chunks 1-4)
- **Chunk 1 (built OK):** `TrailSettingsActivity` sends `trail-grant`/`trail-revoke`;
  server-error toasts.
- **Chunk 2 (built OK):** `GuardianRegistry` maintains local ward lists from
  `trail-guardian-changed`; `GuardianWardsActivity` (Requests: Accept/Decline; People-you-
  guard: Stop). Prefs ward storage. "People I guard" card in Trail settings.
- **Chunk 3 (needs build):** `GuardianTrailViewerActivity` — `trail-fetch` -> `trail-data`
  -> `GuardianTrail.assemble` -> last-known card + Open in Maps + reverse-chron list +
  GPX/JSON export via SAF (CreateDocument, no storage permission). Launched from accepted
  wards "View trail".
- **Chunk 4 (needs build):** `AccessLogStore` accumulates `trail-accessed` pushes (no
  server change); `TrailAccessLogActivity` "Who viewed my trail" + card in settings.
- Earlier: (b) SOS->engagePanic (guarded on isTrailEnabled), (c) panic labels+strings,
  `EcdhHelper.decryptTrailBatch`, `trail/GuardianTrail.kt`, `trail/TrailExport.kt`.

## Envelope (reference)
Guardian decrypt reuses the DM conversation key: `deriveConversationKey(guardianPriv,
trackedPub, guardianName, trackedName)` (X25519 + sorted-username salt, both symmetric) then
AES-256-GCM. Batch plaintext = JSON array of TrailPointData. Admin is just another recipient.

## Known constraints / notes
- Guardian decrypt needs the tracked person's public key in local `peer_keys` (populated by
  normal mutual-contact key exchange). Viewer shows "key isn't available yet" if missing.
- Multi-device: `GuardianTrail` merges/dedupes by (device, seq) and sorts by ts. The PC
  admin viewer still sorts purely by seq (fine for single-device; could interleave a multi-
  device target — pre-existing, out of scope).
- No sender-side SOS "stand down" yet — PANIC stays engaged until cleared (exact call
  commented in ChatViewModel.sendSosMessage).
- Server drift rule: additive only; mirror server.js changes to repo + live identically.

## Acceptance to run
- Relationship: A adds B guardian -> B accepts -> server sets accepted_ts, A backfills.
- Viewer: B opens A's trail -> last-known + path; export GPX/JSON.
- Transparency: A sees B's fetch in "Who viewed my trail".
- Admin: /admin/trail reconstructs run incl. susp points AND events.

## Pointers
SPEC_T13.md (Block K), SPEC_T13_PHASE2_SERVER_PERSISTENCE.md (envelope §9a),
DEPLOY_T13_PHASE2.md, PROJECT_MEMORY.md.
