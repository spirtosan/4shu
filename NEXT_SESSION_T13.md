# T13 Phase 2/3 — Next Session Startup Notes (2026-08-27)

**Status:** T13 Phase 2/3 trail server persistence — server LIVE on /opt/fshu5, app installed & working (2026-08-27).

## What shipped

- **Block F** — server schema/config/keygen (trail tables, `data/config.json` trailAdmins, admin keypair generation).
- **Block G** — WS handlers + passphrase-unlock admin decrypt.
- **Block I** — Android upload engine `TrailUploader` with priority-resend + per-recipient fanout to admin+guardians.
- **Block H** — frozen-clock retention purge, server + client.
- **Block J** — last-gasp flush + PANIC capability.
- **Block K** — server-side admin viewer at `/admin/trail`.

## Key facts for next session

- Admin key lives in `/opt/fshu5/data/config.json` under `trailAdmins` (id `__admin__`); daily passphrase + offline recovery passphrase; recovery is offline (not server-mediated).
- Trail batch envelope: `HKDF-SHA256(X25519(senderPriv, recipientPubHex), salt=SHA256("lo:hi"), info="fshu-next-1-1-v1")` + explicit random 12-byte IV + `AES-256-GCM(ct||tag)`. Admin pub is distributed to the app as hex via `auth-ok`.
- Watermark row key is `"__batch__"`.
- Server drift rule: additive only — apply identically to the repo's `server.js` and the live `/opt/fshu5/server.js`.
- This repo is on a Windows-over-bridge mount where the Cowork session cannot run git/Gradle — Ivan builds in Android Studio; Claude Code on this machine handles git/push and server deploy.

## Remaining work (deferred, recorded in SPEC_T13.md)

- (a) In-app GUARDIAN-facing viewer UI — Block K Android side (fetch/decrypt/merge multi-device, timeline+map, GPX+JSON export, access-log screen). The one build-bound piece not yet written.
- (b) SOS→PANIC one-line wiring: call `TrailService.engagePanic(context, true)` from the app's SOS trigger, and `(false)` on dismiss.
- (c) Add `panic_on`/`panic_off` + `svc_restart`/`sim_changed` labels to TrailLabels/strings — unknown events currently render raw.
- (d) Server trail-stale alert (`config.trailStaleAlertHours`, currently 0/off).
- (e) Backfill dedup-by-seq refinement — repeated guardian accepts create duplicate rows; they age out in <=7d but aren't deduped up front.

## Acceptance still to run

Block F/G/I checklist in SPEC_T13.md: enable Trail → collect → airplane-mode → reconnect → backlog lands within seconds, seq contiguous; admin `/admin/trail` reconstructs the run including any `susp` points.

## Pointers

- `SPEC_T13.md` — Block F–L implementation notes + checklists.
- `SPEC_T13_PHASE2_SERVER_PERSISTENCE.md` — design + envelope §9a.
- `DEPLOY_T13_PHASE2.md` — deploy runbook.
- `PROJECT_MEMORY.md` — changelog + board.
