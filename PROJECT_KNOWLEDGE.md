# 4shu (fshu-next) — Project Knowledge

> **Status: STABLE / MAINTENANCE MODE.** Core development complete. Bug fixes and
> minor features only — no large new subsystems. **Mobile only** (no desktop client).
> **Platform: Android 12+ (API 31) policy.**
> _Stable reference doc. Live backlog + changelog live in **PROJECT_MEMORY.md**._
> _Last verified: 2026-06-29._

---

## 1. What This Is

Private, self-hosted, end-to-end encrypted messenger.

- **Client:** Android app · **Server:** Node.js
- No corporate dependencies, no third-party clouds required.
- **Display name:** 4shu — **all code/identifiers use `fshu`.**

---

## 2. The Team & Roles

| Who | Role |
|-----|------|
| **Ivan** | Owner, decision-maker, tester (2× Motorola G60, Android 12), builds all APKs in Android Studio, downloads files to repo root |
| **Claude (planning chat)** | Architect — decisions, planning, drafts docs. Does **not** write production code directly |
| **Claude Code** | Implements in the repo. Maintains `PROJECT_MEMORY.md`. SSH **LAN only**: `ssh root@192.168.212.105`. **Never** use public IP `89.25.108.245` |

---

## 3. Current State (verified 2026-06-28)

- **Android app:** package `com.fshu.next`, label **"4shu β"**
  - `versionName 0.1.0-next`, `versionCode 1`
  - `compileSdk 34`, `targetSdk 34`, **`minSdk 26`** ⟵ _policy target is 31; not yet applied (see §4)_
  - Build script: **Groovy** (`build.gradle`, not `.kts`). Language: **Kotlin**.
- **Room (Android) schema version: 26** (as of T13 Block A, 2026-07-18)
  - Entities: `Message, PeerKey, Group, GroupMember, Contact, Block, Mute`
- **Server:** `/opt/fshu5/` on `192.168.212.105`, port **8083**, systemd **`fshu5`** (active)
  - **Node v20.20.2**, runs `node server.js` directly (no `start` script)
  - Deps: `better-sqlite3 ^12.9.0`, `ws ^8.20.0`, `bcrypt ^6.0.0`,
    `firebase-admin ^13.8.0`, `nodemailer ^8.0.7`
- **WebSocket:** `wss://shumkov.eu/fshu5/`
- **DB (server):** SQLite `/opt/fshu5/data/fshu.db` — 17 tables:
  `users, devices, sessions, contacts, contact_nicknames, blocks, mutes,
  messages, reactions, files, queue, groups, group_members, lists, list_items,
  auto_location, invites, password_resets`
- **Known issue:** app crashes on launch (crash-loop) on some **newer** devices;
  fine on Android 12 G60. Under investigation — see PROJECT_MEMORY.
- **Old app** (`/opt/fshu/`, systemd `fshu`) — stopped, **do not touch**
- **Git:** `/mnt/ivan/git/fshu-next.git` · **Backups:** `/mnt/ivan/backups/fshu-next/`

---

## 4. Platform / SDK Configuration

Target an Android 12 floor without locking out newer devices. The three values are
independent: `minSdk` = oldest device allowed; `compileSdk` = which APIs you can
write against (no runtime effect); `targetSdk` = which OS runtime behaviors you opt
into.

| Setting | Current | Target | Notes |
|---|---|---|---|
| `minSdk` | 26 | **31** | Android 12 floor. Enforces the "drop older than 12" policy; lets pre-31 compat code be removed. Does **not** affect newer-device installs. |
| `compileSdk` | 34 | **35** | Latest APIs; no runtime effect. 36 possible but may need newer AGP. |
| `targetSdk` | 34 | **34 → 35** | Only this changes runtime behavior. Bump deliberately and re-test calls on the G60 (foreground-service / notification behavior changes at 34+). |

- **Not on Google Play** (self-distributed). Play's targetSdk deadlines and the
  Aug 31 2026 API-36 requirement **do not apply** — private/internal-distribution
  apps are exempt.
- `minSdk 31` will **not** fix the newer-device crash (minSdk only blocks *older*
  devices). That crash is a separate runtime/native issue — see PROJECT_MEMORY.
- Keep using `*Compat` classes (`NotificationCompat.Builder`,
  `ActivityCompat.requestPermissions()`) — behavior still differs across 12→16.

```groovy
android {
    compileSdk 35
    defaultConfig {
        applicationId "com.fshu.next"
        minSdk 31
        targetSdk 34   // bump to 35 only after testing calls on the G60
        versionCode 1
        versionName "0.1.0-next"
    }
}
```

---

## 5. Infrastructure

- **Server OS:** Ubuntu 24.04, static LAN IP `192.168.212.105`
- **Public IP:** `89.25.108.245` — **SSH NOT available here**
- **Nginx:** TLS termination + reverse proxy
- **TURN/STUN:** coturn, port `3478`, relay range `49152–49200`
- **Firebase:** `/opt/fshu5/firebase-adminsdk.json` (FCM push, optional)
- **APK download:** `/opt/fshu5/files/download/app-release.apk`
- **Samba share:** `\\192.168.212.105\fshu-download` (user: `mc`)
- **Join/download page:** `/fshu5/join`

---

## 6. Android Project

- **Location:** `C:\Users\spirt\fshu-next`
- **Package:** `com.fshu.next`
- **Keystore:** `C:\Users\spirt\fshu-next.jks` (password: `FshuN3xt#2026`)
- **Build:** Ivan builds in Android Studio — **Claude Code never runs Gradle or adb**
- **Testing:** APK shared via Samba, installed manually on both phones
- **Crash handler:** exports a crash dump to the device **Downloads** folder on
  uncaught (Java/Kotlin) exceptions. Note: native crashes bypass this handler.
- **Languages:** English (391) + Bulgarian (363, 28 behind). Russian missing.

---

## 7. Key Architecture Decisions (Final)

Settled. Do not revisit without an explicit decision from Ivan.

- **Storage:** SQLite only (`better-sqlite3`). No JSON files.
- **Encryption:** X25519 ECDH + HKDF + AES-256-GCM.
- **Android crypto:** Bouncy Castle + `javax.crypto`. **No Tink, no Google crypto libs.**
- **Server crypto:** Node.js built-in `crypto` only. **No npm crypto packages.**
- **File transfer:** WebSocket binary frames, **50 MB** limit. No base64.
- **Contact model:** mutual (both must accept). Strangers → requests inbox.
- **Messages from non-contacts:** tagged `isRequest=true`, routed to requests inbox.
- **Calls from non-contacts:** rejected server-side (emergency calls bypass this).
- **Block:** silent rejection (single tick, never delivered).
- **Users broadcast:** sends only accepted contacts, not all users.
- **Username:** immutable, permanent identifier.
- **Email:** optional, unique, used for search only.
- **Secret question:** mandatory on invite registration; **primary recovery method**.
- **SMTP:** `nodemailer` installed but **not configured** — secret question is the
  only active reset path.
- **Privacy:** discoverable by default (opt-out model).
- **Auto-location:** per-contact opt-in (`auto_location` table).
- **GDPR export:** on-device, decrypts locally, saves to Downloads.
- **Favorites:** pinned section at top, reorderable, stored in Prefs.

---

## 8. Implemented Features

Multi-device · ECDH encryption (1-1 + group) · binary file transfer · full contact
system (search/request/accept/decline/cancel/remove/block) · pre-contact chat
(requests inbox) · per-user privacy settings · My Profile · device management ·
invite links (48h) · password reset via secret question · groups (roles, avatars,
leave/delete) · reactions (DM + group) · voice messages · typing indicators ·
edit/delete · mute chats/contacts · location sharing + per-contact auto-location ·
favorites/pin · todo lists (DM only) · GDPR export + account deletion · join page ·
install script.

---

## 9. Active Work

**The live backlog, priorities, changelog, decisions, and open questions live in
`PROJECT_MEMORY.md`.** That file is the source of truth for what's being worked on
and is updated by Claude Code after every code change.

---

## 10. Roadmap (high-level, slow-moving)

- Configure SMTP for email password reset (`nodemailer` already present).
- Group privacy settings (open / closed / secret).
- Complete Bulgarian translation; add Russian (`values-ru/strings.xml`).
- PostgreSQL migration — only if/when scale demands it.
- Docker packaging — at public launch.

---

## 11. Rules / Constraints

- Never touch `/opt/fshu/` or `C:\Users\spirt\fshu` (old stable app).
- SSH **LAN only**: `ssh root@192.168.212.105`. Never SSH the public IP.
- No Tink, no Google crypto libraries. No npm crypto packages on server.
- SQLite only (`better-sqlite3`).
- Ivan builds the APK — Claude Code never runs Gradle or adb.
- Commit to git after each working feature.
- **After any code change, Claude Code updates `PROJECT_MEMORY.md` and commits it
  alongside the change.**
- Update the install script when `server.js` changes affect config/schema.
- Target Android 12+ (API 31); don't add code paths for older versions.
- **Mobile only** — no desktop/Electron client.

---

## 12. Commands

```bash
# Push to git
cd C:\Users\spirt\fshu-next && git add -A && git commit -m "description" && git push

# Backup server
ssh root@192.168.212.105 "tar czf /mnt/ivan/backups/fshu-next/fshu5-server-$(date +%Y-%m-%d-%H%M).tar.gz --exclude=/opt/fshu5/node_modules /opt/fshu5/ && echo done"

# Start Claude Code
cd C:\Users\spirt\fshu-next && claude --dangerously-skip-permissions
```
