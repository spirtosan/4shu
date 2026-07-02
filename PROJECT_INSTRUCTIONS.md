# 4shu (fshu-next) — Project Instructions

> **SOURCE OF TRUTH:** the repo's `PROJECT_KNOWLEDGE.md` (stable reference) and
> `PROJECT_MEMORY.md` (live backlog/changelog), maintained by Claude Code and current
> at commit `eb3e078`. If anything pasted or attached in a session conflicts with the
> repo — especially anything claiming **Room 16**, an **Electron desktop client**,
> **trust-level as an open bug**, or **T5 Phase 2 as "not started"** — it is STALE.
> Trust the repo, do not edit toward the stale copy, and flag the conflict.

## What This Is
Private, self-hosted, end-to-end encrypted messenger. **Android app + Node.js
server.** No corporate dependencies, no third-party clouds required. Display name:
**4shu**; all code/identifiers use **`fshu`**. **Status: STABLE / MAINTENANCE MODE** —
bug fixes and minor features only, no large new subsystems. **Mobile only** (no
desktop/Electron client — that idea was dropped 2026-06-28).

## The Team & Roles
- **Ivan** — owner, decision-maker, tester (2× Motorola G60, Android 12). Builds all
  APKs in Android Studio; downloads files to repo root.
- **Claude (planning chat)** — architect: decisions, planning, drafts docs and specs.
  Does **not** write production code directly.
- **Claude Code** — implements in the repo; maintains `PROJECT_MEMORY.md`. SSH **LAN
  only**: `ssh root@192.168.212.105`. **Never** the public IP `89.25.108.245`.

## Current State (verified 2026-07-02, HEAD `eb3e078`)
- **Android app:** `com.fshu.next`, label "4shu β", `versionName 0.1.0-next`,
  `versionCode 1`. `compileSdk 34` / `targetSdk 34` / `minSdk 26` (policy target
  `minSdk 31` / `compileSdk 35`, not yet applied — deferred item "TS"). Groovy build
  script, Kotlin.
- **Android Room schema version: 25** (entities: `Message, PeerKey, Group,
  GroupMember, Contact, Block, Mute`).
- **Server:** `/opt/fshu5/` on `192.168.212.105`, port **8083**, systemd **`fshu5`**,
  Node v20.20.2, runs `node server.js` directly. SQLite `/opt/fshu5/data/fshu.db`.
- **WebSocket:** `wss://shumkov.eu/fshu5/`.
- **Old app** (`/opt/fshu/`, systemd `fshu`) — stopped, **do not touch**.
- **Git:** `/mnt/ivan/git/fshu-next.git` · **Backups:** `/mnt/ivan/backups/fshu-next/`.
- **KNOWN — repo/live `server.js` drift (unresolved):** the committed `server.js` and
  the deployed `/opt/fshu5/server.js` have diverged. Live-only (uncommitted): emergency
  call/location, `sos-message`, `hide_presence`, `group-file` groupId, group-key
  self-heal. Repo-only (undeployed): `trust_level` + family-group propagation,
  `admin-set-trust`. Deployed state is preserved read-only in
  `snapshots/live-2026-07-02/`. **Never deploy repo `server.js` wholesale over live
  until reconciled** — patch surgically.

## Infrastructure
- Ubuntu 24.04, static LAN IP `192.168.212.105`. Public IP `89.25.108.245` — **no SSH**.
- Nginx (TLS + reverse proxy). coturn TURN/STUN port `3478`, relay `49152–49200`.
- Firebase `/opt/fshu5/firebase-adminsdk.json` (FCM, optional).
- APK: `/opt/fshu5/files/download/app-release.apk`. Samba `\\192.168.212.105\fshu-download`
  (user `mc`). Join page `/fshu5/join`.
- **Note:** `install-fshu-next.sh` is referenced by the docs but was not found on the
  server as of 2026-07-02 — locate/reconcile its canonical home during server reconciliation.

## Android Project
- Location `C:\Users\spirt\fshu-next`. Package `com.fshu.next`.
- Keystore `C:\Users\spirt\fshu-next.jks` (password `FshuN3xt#2026`).
- Ivan builds in Android Studio — **Claude Code never runs Gradle or adb.** APK shared
  via Samba, installed manually on both phones.
- Languages: English + Bulgarian (Bulgarian slightly behind). Russian missing.

## Key Architecture Decisions (Final — do not revisit without Ivan)
- SQLite only (`better-sqlite3`). No JSON files.
- Crypto: X25519 ECDH + HKDF + AES-256-GCM. Android: Bouncy Castle + `javax.crypto`
  (**no Tink, no Google crypto libs**). Server: Node built-in `crypto` only (**no npm
  crypto packages**).
- File transfer: WebSocket binary frames, 50 MB limit, no base64.
- Contacts mutual (both accept); strangers → requests inbox. Non-contact messages
  `isRequest=true`. Non-contact calls rejected server-side (**emergency calls bypass**).
  Block = silent rejection. Users-broadcast sends accepted contacts only.
- Username immutable. Email optional/unique/search-only. Secret question mandatory +
  primary recovery (SMTP not configured). Discoverable by default (opt-out).
- Auto-location per-contact opt-in. GDPR export on-device. Favorites pinned in Prefs.

## Active Work — see `PROJECT_MEMORY.md` (authoritative backlog)
- **T5 — polls in groups (P2, IN PROGRESS).** Design approved: `SPEC_T5.md` v2 (polls
  = lists with `type:"poll"`; client-side tally under group E2E; named-only v1;
  single/multi at creation; owner/admin close; re-vote by upsert; live results).
  Wire format locked (Block A.1): poll meta is a `kind:"meta"` list item; ballot
  `item_id` = `ballot:<username>`; option id = `opt-<index>`.
  - **Phase 1 DONE + pushed** (`62091a1`): server made group-aware, no schema migration.
  - **Phase 2 IN PROGRESS — through Block D.1 done + pushed** (HEAD `eb3e078`):
    A/A.1 (poll data model + tally, pure Kotlin), B (read-only poll render in chat
    bubble), D + D.1 (poll creation UI, **gated group-only** — DM poll render/receive
    left intact on purpose). **Block C (voting) is next.** Remaining after C: Block E
    (owner/admin close control), Block F (strings polish + final memory update). Then
    Phase 2 is done and server reconciliation becomes its own focused session.
  - **Non-blocking, flagged for decision:** (1) copy-to-clipboard renders a poll's raw
    packed JSON, not readable text — deferred pending a "what should a copied poll read
    as" decision; (2) **T12** — list-type bubbles (todo + poll) never supported
    reactions (pre-existing gap, P3, no code).
- **Server reconciliation (pending):** deliberate three-way merge (repo ↔
  `snapshots/live-2026-07-02/` ↔ live). Trust-level code removal rides here after a
  dependency check it isn't load-bearing. Also resolve: is `admin.js` (deployed) in
  git; where does `install-fshu-next.sh` live. Note: `list-edit` server handler does no
  group-membership check before upsert — log for this pass (low blast radius under the
  trusted-self-hosted / small-group threat model; a non-member item is undecryptable
  and dropped by the parser).
- **Dropped:** per-contact trust-level UI (Ivan, 2026-07-02 — not needed).
- **Deferred (don't start unless raised):** TS SDK bump; T7 screen share; OEM
  keep-alive (diagnostic-first).

## How We Work (process)
- Planning chat decides/designs; Claude Code implements. Specs saved to repo root.
- Claude Code: read `PROJECT_KNOWLEDGE.md` + `PROJECT_MEMORY.md` first every session.
  After **any** code change, update `PROJECT_MEMORY.md` (changelog + board) and commit
  it alongside the change. Commit to git after each working feature. Push before
  session end so work is durable off-machine.
- Claude Code stops-and-reports on any fix to already-shipped code rather than folding
  it in silently.
- Prompts to Claude Code go one block at a time; wait for output before the next.
- Update the install script when `server.js` changes affect config/schema.

## Rules / Constraints
- Never touch `/opt/fshu/` or `C:\Users\spirt\fshu` (old app).
- SSH **LAN only** (`ssh root@192.168.212.105`); never the public IP.
- No Tink / Google crypto (Android); no npm crypto packages (server). SQLite only.
- Ivan builds the APK — Claude Code never runs Gradle or adb.
- Target Android 12+ (API 31); don't add code paths for older versions.
- **Mobile only** — no desktop/Electron client.

## Commands
```bash
# Push to git
cd C:\Users\spirt\fshu-next && git add -A && git commit -m "description" && git push

# Backup server
ssh root@192.168.212.105 "tar czf /mnt/ivan/backups/fshu-next/fshu5-server-$(date +%Y-%m-%d-%H%M).tar.gz --exclude=/opt/fshu5/node_modules /opt/fshu5/ && echo done"

# Start Claude Code
cd C:\Users\spirt\fshu-next && claude --dangerously-skip-permissions
```
