# 4shu (fshu-next) — Project Memory

> **Living log. Updated by Claude Code after every code change, committed alongside it.**
> Stable architecture/reference is in `PROJECT_KNOWLEDGE.md`; this file is the
> source of truth for *what is being worked on and what changed*.
>
> **How to update (Claude Code):** at the end of any session that changes code,
> (1) add a CHANGELOG entry (date, what, files, commit hash), (2) move task cards
> between board columns, (3) record any new decision or open question, (4) commit
> with the same change.

---

## Task Board

Priority: **P0** blocking users · **P1** important · **P2** normal · **P3** later.

### To Do

| ID | Item | Type | Pri | Notes |
|----|------|------|-----|-------|
| TS | SDK bump: `minSdk 26→31`, `compileSdk 34→35` | Config | **P1** | Part of platform policy; compileSdk bump also feeds T2 fix. Keep `targetSdk 34` until call testing on G60. Ivan edits in Android Studio. |
| T7 | Screen share | Feature | **P3** | Large. WebRTC screen capture. Interacts with foreground-service rules. |
| T11 | Shared todo list with "who did the task" | Feature | **P3** | Tentative, not confirmed. |
| T1 | Reset-link page shows "???" symbols | Bug | **P3 / parked** | Charset/encoding on reset page. Secret-question is the only active reset path, so low impact for now. |

### In Progress

| ID | Item | Notes |
|----|------|-------|
| T5 | Polls in groups (reuse todo-list infra) | **Phase 1 done** (server group-aware lists). **Phase 2 Block A done** (pure Kotlin poll data model + tally, no UI/server — this session). Block B (client poll UI + wire integration) not started. Two assumptions made in Block A are unconfirmed against SPEC_T5.md v2 — see Open Questions before starting Block B. Design: `SPEC_T5.md` v2. |

### Done

| ID | Item | Notes |
|----|------|-------|
| T2 | Newer-device launch crash ("4shu keeps stopping") | ✓ verified on G60 — APK Analyzer shows all arm64-v8a `.so` 16 KB-aligned, calls pass. Fix: WebRTC `io.getstream:stream-webrtc-android:1.1.1` → `io.github.webrtc-sdk:android:144.7559.09` (M144, 16 KB-aligned); AGP `8.2.2→8.6.1`; Gradle wrapper `8.4→8.7`; ndkVersion `28.0.12433566`; `-Wl,-z,max-page-size=16384` via CMakeLists.txt; invalid `ldFlags` cmake line removed. R8/minify regression: M144 AAR ships `org.jni_zero.**` classes used by JniInit — added `-keep class org.jni_zero.** { *; }` to `proguard-rules.pro`. REINSTALL build. |
| T10 | Rename groups | ✓ verified on G60. Client sends `group-rename`; server (already handled) validates owner/admin, updates name, broadcasts `group-state`; FshuService upserts Room, ChatActivity re-reads title. UI: "Rename group" link (owner/admin only) in group-info dialog. Validation: trim, 1–64 chars, reject empty/unchanged. No schema/protocol/DB changes. No system message (type doesn't exist). UPDATE build. |
| T8 | Chat/group media gallery | ✓ verified on G60. Images-only v1. 3-col grid (date headers), in-app PhotoView+ViewPager2 viewer, Save-to-device + Share. Entry: chat overflow "Media" (DM+group) + group-info dialog link. No schema/DB/server/protocol change. UPDATE build. |
| T9 | Slide-to-accept / slide-to-reject incoming-call UI | ✓ verified on G60. Single horizontal slide track replaces tap buttons. Right ≥80% → `acceptCall()`, left ≥80% → `rejectCall()`. Spring-back, haptic tick at threshold, commit-once guard, TalkBack accessibility actions. No protocol/DB/permission changes. UPDATE build. |
| T6 | Build flavors: `personal` (server URL pre-filled) vs `distribution` (blank) | ✓ verified on G60. `flavorDimensions "serverType"` + two `productFlavors` in `build.gradle`; `LoginActivity` fills from `BuildConfig.DEFAULT_SERVER_URL` when no saved URL. UPDATE build. |
| T4 | Add-contact: auto-focus search field + open keyboard | ✓ verified on G60. `SearchActivity`: `view.post` + `WindowInsetsControllerCompat` + `InputMethodManager` fallback; manifest `stateVisible\|adjustResize`. UPDATE build. |
| T3 | Vibration doesn't stop after emergency call | ✓ verified on G60. Added `stopAlerting()` to `CallViewModel` — calls both `stopIncomingVibration()` and `FshuService.cancelCallNotif()`. Wired to all terminal paths: `acceptCall`, `rejectCall`, `remoteEndCall`, `incomingTimeoutJob`, `handleBusy`, `onCleared`. Guard added: `callFinished` flag prevents re-entry after stop. UPDATE build. |

### Parked / Deferred
_Nothing currently parked._

### Dropped
- Per-contact **trust-level UI** — DROPPED (Ivan, 2026-07-02): trust level not
  needed. Code removal deferred to the repo/live reconciliation pass (see Open
  Questions) — the underlying `trust_level` server code is repo-only / not deployed
  (see `snapshots/live-2026-07-02/README.md`), so before deleting it, check it isn't
  load-bearing for group/safety logic.

---

## Open Questions

- **T5 Phase 2 Block A — two unconfirmed assumptions (flag before Block B, 2026-07-02):**
  SPEC_T5.md v2 doesn't give a literal formula/format for either of these, so Block A
  proceeded with the most spec-consistent reading rather than blocking; both need an
  explicit yes/no before Block B locks the actual wire format:
  1. **Ballot `item_id` derivation.** §2a/§3d/§4 say a ballot's `item_id` is "a stable
     per-voter identifier" and is plaintext to the server, but never state the exact
     string. Nothing else in the ballot payload (`{kind:"ballot", selected:[...]}`) or
     the `list-edit` wire shape carries a separate voter field, so `item_id` is the
     only channel through which voter identity reaches the client — meaning it must
     directly encode the username. **Block A assumed `item_id` == the raw username
     string**, unmodified, unnamespaced. Asked Ivan via AskUserQuestion mid-session;
     no response received, proceeded per tool guidance ("use best judgment, re-ask
     later"). Re-ask before Block B if this matters for wire compatibility.
  2. **Poll meta placement.** §3b describes `type`/`mode`/`status`/`question` as living
     "in the poll's list-message JSON / list-create payload" — worded differently from
     options/ballots ("packed into a list item's text", §3c/§3d), which reads as
     envelope-level rather than item-level. But `Message.content` today is a bare
     JSON array with no envelope for extra fields (confirmed in `ChatViewModel.kt`),
     and the Block A task prompt itself says to parse the definition "from a
     type:"poll" list's **list_items**" — which only makes sense if meta lives inside
     list_items too. **Block A modeled poll meta as its own list item with
     `kind:"meta"`**, packed into `text` the same way as options/ballots (see
     `PollParser.kt`), to reuse list_items as the sole source of truth with zero
     content-envelope/protocol change. This is an implementation choice, not a literal
     spec citation — confirm before Block B writes the `list-create` sending code that
     has to match it.
- **T5 — design RESOLVED** (planning chat + recon, 2026-07-02): see `SPEC_T5.md` v2 for the full decision log (single/multi at creation, named-only votes, client-honored close, re-vote-by-upsert, live results via `list-state`, generic offline queue). Server Phase 1 (group-aware lists) implemented this session; Phase 2 (client poll UI) not started.
- **server.js repo/live drift (found 2026-07-02, unresolved — flag for Ivan):** the committed `server.js` and the deployed `/opt/fshu5/server.js` have diverged. Live-only (not in git): emergency-call/emergency-location/`sos-message` handling, `hide_presence` contact setting, `group-file` binary upload groupId support, `getEmergencyAllow`/emergency-allow toggles, group-key self-healing on connect. Repo-only (not deployed): `trust_level` column + family-group trust propagation, `admin-set-trust`. The T5 patch touched only list-related code, confirmed byte-identical in both copies before editing, and was applied to both independently — no other drift was touched or reconciled. **The deployed state (including the live-only safety features) is now preserved read-only in `snapshots/live-2026-07-02/`** (captured 2026-07-02, see its README for the full diff) so it's durable/diffable pending reconciliation. Recommend a full three-way reconciliation (repo ↔ snapshot ↔ next-live) before the next unrelated deploy; do the trust-level code removal (see Dropped, above) as part of that same pass.

---

## Decisions Log

| Date | Decision |
|------|----------|
| 2026-06-28 | Maintenance mode: bug fixes + minor features only. |
| 2026-06-28 | Mobile only — Electron/desktop client dropped from roadmap. |
| 2026-06-28 | Drop Android < 12. Target `minSdk 31 / compileSdk 35 / targetSdk 34` (test 35 later). |
| 2026-06-28 | Not distributing via Play Store → Play targetSdk deadlines do not apply. |
| 2026-06-28 | Separate `PROJECT_MEMORY.md` (this file), maintained by Claude Code. |
| 2026-06-30 | Session close — T2 verified + closed (calls + 16 KB both green); jni_zero R8 keep-rule fix pushed (56c1668). T8 media gallery also confirmed G60-verified. Next session: T5 polls in groups — design unresolved, tally-under-E2E is the open constraint. |
| 2026-07-02 | T5 design approved (SPEC_T5.md v2) — polls are lists with a `type:"poll"` discriminator; no Android Room migration, no server schema migration; server work collapses to "make lists group-aware". |
| 2026-07-02 | server.js repo/live drift discovered (see Open Questions) — not reconciled, only the isolated list-code path was touched for T5 Phase 1. |

---

## Changelog

| Date | Change | Files | Commit |
|------|--------|-------|--------|
| 2026-06-28 | Created project memory file; seeded task board from planning notes. | PROJECT_MEMORY.md, PROJECT_KNOWLEDGE.md | _pending_ |
| 2026-06-29 | T9: slide-to-accept/reject incoming-call UI. Single horizontal track replaces tap buttons; right ≥80% → acceptCall(), left ≥80% → rejectCall(); spring-back, haptic tick, commit guard, TalkBack actions. EN+BG strings. No RU (values-ru absent). | `activity_call.xml`, `CallActivity.kt`, `bg_slide_track.xml`, `bg_slide_handle.xml`, `values/strings.xml`, `values-bg/strings.xml` | 2ebaf4b |
| 2026-06-29 | T6: build flavors `personal`/`distribution` — server URL pre-filled vs blank. `buildConfig true` was already on; no DB/protocol/permission changes. | `app/build.gradle`, `…/ui/login/LoginActivity.kt` | 1255376 |
| 2026-06-29 | T4: auto-focus search field + show keyboard in SearchActivity on open. Manifest: `stateVisible\|adjustResize`. | `app/src/main/AndroidManifest.xml`, `…/ui/search/SearchActivity.kt` | 5a107ca |
| 2026-06-29 | T3: add `stopAlerting()` to CallViewModel; wired to all terminal paths + `onCleared()` safety net; `callFinished` guard against restart. Root cause: `remoteEndCall()` stopped `incomingVibrator` but not `FshuService.activeRingtone`/`activeVibrator`. | `…/ui/call/CallViewModel.kt` | 5a107ca |
| 2026-06-29 | T10: group rename. "Rename group" link in group-info dialog (owner/admin only); dismisses info dialog, opens rename dialog pre-filled with current name; validates trim/1–64 chars/unchanged; sends `group-rename` to server. Server already handled this: validates role, updates name, broadcasts `group-state`. Client update flows through existing `handleGroupState` → Room upsert → `loadGroupInfo()` → toolbar title. No schema/DB/permission changes. EN+BG strings. | `ChatActivity.kt`, `values/strings.xml`, `values-bg/strings.xml`, `PROJECT_MEMORY.md` | 9a00b44 |
| 2026-06-29 | T9 follow-up: fix slider permanently locking when mic permission is denied on accept path. `committed` lifted to class field `sliderCommitted`; `sliderSpringBack` lambda resets it + animates handle to center on denial. `requestPermissionsForCall` gains `onDenied` param; on denial — accept path springs back (user can retry or decline), caller path still calls `finish()`. Same fix applied to TalkBack accept action and mutual-resolve path. | `…/ui/call/CallActivity.kt`, `PROJECT_MEMORY.md` | 9370661 |
| 2026-06-29 | T8: chat/group media gallery. New `MediaGalleryActivity` (3-col grid, date headers Today/Yesterday/month-year), `MediaViewerActivity` (ViewPager2+PhotoView, immersive, Save+Share). DAO: `getDmImages`/`getGroupImages`. Entry: overflow "Media" (DM+group) + group-info dialog link. PhotoView via JitPack (`maven { url 'https://jitpack.io' }` added to settings.gradle). Save reuses MediaStore Pictures path from export. `date_today`/`date_yesterday` strings new (did not exist). No schema/DB/server/protocol change. UPDATE build. | `settings.gradle`, `app/build.gradle`, `MessageDao.kt`, `MediaGalleryActivity.kt`, `MediaViewerActivity.kt`, `activity_media_gallery.xml`, `activity_media_viewer.xml`, `item_media_grid.xml`, `item_media_header.xml`, `menu_media_viewer.xml`, `menu_chat.xml`, `ChatActivity.kt`, `AndroidManifest.xml`, `values/strings.xml`, `values-bg/strings.xml`, `PROJECT_MEMORY.md` | a63acb6 |
| 2026-06-29 | T8 compile fix: `companion object` is illegal inside `inner class`; moved `TYPE_HEADER`/`TYPE_IMAGE` to file-level `private const val`; updated one qualified reference (`GalleryAdapter.TYPE_HEADER` → `TYPE_HEADER`) in `spanSizeLookup`. | `MediaGalleryActivity.kt` | ac3f283 |
| 2026-06-29 | Session close — T8 built + compile-fixed (ac3f283), pending G60 verification; tree pushed. | `PROJECT_MEMORY.md` | 4176944 |
| 2026-06-30 | T2 (16 KB fix — In Progress): WebRTC `io.getstream:stream-webrtc-android:1.1.1` → `io.github.webrtc-sdk:android:144.7559.09` (M144, 16 KB-aligned, same `org.webrtc.*` imports); AGP `8.2.2→8.6.1`; Gradle wrapper `8.4→8.7`; ndkVersion `"28.0.12433566"` (r28); `-Wl,-z,max-page-size=16384` added to CMakeLists.txt `target_link_options`. `libfshu_native.so` is in-project (JNI pepper, `fshu_native.cpp`), not vendored. jniLibs packaging already uncompressed — no `useLegacyPackaging` change needed. **REINSTALL build required** (AGP+NDK change). Awaiting Ivan rebuild + APK Analyzer + call re-test. | `app/build.gradle`, `build.gradle`, `gradle/wrapper/gradle-wrapper.properties`, `app/src/main/cpp/CMakeLists.txt`, `PROJECT_MEMORY.md` | 9c3db20 |
| 2026-06-30 | T2 hotfix: removed invalid `ldFlags "-Wl,-z,max-page-size=16384"` from `app/build.gradle` `externalNativeBuild { cmake { } }` block — `ldFlags` is an ndkBuild-only DSL key, not valid for cmake; Gradle evaluation fails with "Could not find method ldFlags()". Flag is correctly applied once via CMakeLists.txt `target_link_options`; no functional change. Amended into commit 9c3db20 (was 9914bc9). | `app/build.gradle`, `PROJECT_MEMORY.md` | 9c3db20 |
| 2026-06-30 | T2 session close: `ldFlags` removal confirmed; Android Studio + Gradle updated; project syncs clean on AGP 8.6.1. T2 commit re-amended to 66d4817. Fix NOT yet verified — awaiting Ivan rebuild + APK Analyzer + call re-test on G60. | `PROJECT_MEMORY.md` | 66d4817 |
| 2026-06-30 | T2 VERIFIED + CLOSED: APK Analyzer gate (b) green — all arm64-v8a `.so` 16 KB-aligned. Call gate (c) green — voice + video calls pass on G60. R8/minify regression found + fixed: WebRTC M144 AAR ships `org.jni_zero.**` (JniInit binding classes); R8 was stripping them → crash on startup with minify enabled. Fix: `-keep class org.jni_zero.** { *; }` added to `proguard-rules.pro`. T8 media gallery also confirmed G60-verified this session. | `app/proguard-rules.pro`, `PROJECT_MEMORY.md` | 56c1668 |
| 2026-06-30 | Session close — T5 design questions recorded in Open Questions; Decisions Log session-close note added. No feature code. | `PROJECT_MEMORY.md` | _this commit_ |
| 2026-07-02 | Housekeeping: gradle-wrapper.properties drift (Studio-added timestamp comment + `distributionSha256Sum`, `distributionUrl` unchanged at gradle-8.7-bin) discarded via `git checkout --`. `.gitignore` gained `Screenshots/` (binary churn, no history value). Tracked durable spec/audit docs: `SPEC_T4_T3.md`, `SPEC_T5.md`, `SPEC_T6.md`, `SPEC_T8.md`, `SPEC_T9.md`, `KEEPALIVE_AUDIT.md`. `KEEPALIVE_OEM_ANALYSIS.md`, `T2_16KB_FIX_PLAN.md`, `T7_SCREEN_SHARE_SPEC.md`, `call-crash-log.txt` intentionally left untracked (not in scope for this triage). No feature code — recon-only session for T5, findings not yet committed. | `.gitignore`, `PROJECT_MEMORY.md` | _this commit_ |
| 2026-07-02 | T5 Phase 1 (server-only): made lists group-aware, zero schema migration (`lists.group_id` already existed, unused). `insertList` now writes `group_id`; `getRecentLists` unions DM lists with group lists (via `group_members` join); new `userCanAccessList()` gates `list-sync-request` group access; `broadcastListState` gains a group branch that fans out to `getGroupMembers.all(group_id)` (online → `sendToAll`, offline → `enqueue`), mirroring `broadcastGroupState`; DM branch (`group_id` null) unchanged. `list-create` accepts optional `groupId`, requires sender be a group member. `list-state` envelope gains a `groupId` field (both branches) so a future client can route poll state to the right group. No new protocol verbs; `list-edit`/`list-check` untouched — they already route through the patched `broadcastListState`, so ballot fan-out (Phase 2) needs no further server work. Applied identically to local repo `server.js` and live `/opt/fshu5/server.js` (see repo/live drift note in Open Questions — the touched code was verified byte-identical in both before editing). Verified: syntax check (local + remote), service restart clean (`journalctl` no errors), existing 6 DM list rows + schema unchanged post-restart. Backup: `fshu5-server-2026-07-02-*.tar.gz` on `/mnt/ivan/backups/fshu-next/`; server-side `.bak` also left at `/opt/fshu5/server.js.bak.*`. Phase 2 (client poll UI + tally) not started — needs approval. | `server.js`, `PROJECT_MEMORY.md` | _this commit_ |
| 2026-07-02 | Safety capture of deployed server state into `snapshots/live-2026-07-02/` — read-only (`cat`/`sqlite3 .schema`/`ls` only), no server change of any kind. Captured `server.js` (verbatim, original CRLF preserved), `schema.sql` (structure only, no row data), `manifest.txt` (directory listings). Skipped as secret/data: `firebase-adminsdk.json`, `secret.key`, all `*.db*` files, `node_modules/`. `install-fshu-next.sh` not present on server — nothing to capture. `admin.js` exists on disk but is not `require()`d by `server.js` — left uncaptured (out of stated scope). README documents the live-vs-repo feature diff (live-only: emergency-call/location, `sos-message`, `hide_presence`, `group-file` groupId, group-key self-heal; repo-only: `trust_level`, family-group propagation, `admin-set-trust`) and one trivial cosmetic (non-functional) one-line diff in a T5 comment. Board: per-contact trust-level UI moved from Parked to **Dropped** (Ivan, not needed); removal deferred to reconciliation pending a check it isn't load-bearing. | `snapshots/live-2026-07-02/server.js`, `snapshots/live-2026-07-02/schema.sql`, `snapshots/live-2026-07-02/manifest.txt`, `snapshots/live-2026-07-02/README.md`, `PROJECT_MEMORY.md` | _this commit_ |
| 2026-07-02 | T5 Phase 2 Block A (client-only, pure Kotlin, no UI/server/protocol change): poll data model + tally. New `com.fshu.next.poll` package: `PollModels.kt` (`PollMode`, `PollStatus`, `PollOption`, `PollDefinition`, `Ballot`, `PollTallyResult`), `PollParser.kt` (parses a poll's `List<ListItem>` list_items into `PollDefinition` + `List<Ballot>`, dispatching on a `kind` field packed into each item's `text` — `meta`/`option`/`ballot`), `PollTally.kt` (`tally()`: optionId→count + named voters per option; asserts no duplicate voterId rather than deduping, since re-vote collapse is the server upsert's job; ignores ballots referencing unknown/removed options; agnostic to poll status — close is client-honored on the write path, not in tallying). No Room schema change (stays v25), no new protocol verbs, no `server.js` touch. Added `testImplementation 'junit:junit:4.13.2'` (no test framework existed yet) + unit tests: `PollParserTest.kt`, `PollTallyTest.kt` (single-select, multi-select, re-vote replacement, duplicate-voterId assertion, unknown-option exclusion, closed-poll tally parity, abstain). **Two implementation assumptions made where SPEC_T5.md v2 gives properties but not a literal format — flagged in Open Questions, unconfirmed:** (1) ballot `item_id` == raw voter username; (2) poll meta (`question`/`mode`/`status`) packed as its own `kind:"meta"` list item, same convention as options/ballots. Asked Ivan mid-session via AskUserQuestion; no response, proceeded as instructed by the tool ("best judgment, re-ask later"). Tests written but **not run** — Claude Code does not run Gradle; Ivan to run `./gradlew test` or run via Android Studio to verify before Block B. | `app/build.gradle`, `app/src/main/java/com/fshu/next/poll/PollModels.kt`, `app/src/main/java/com/fshu/next/poll/PollParser.kt`, `app/src/main/java/com/fshu/next/poll/PollTally.kt`, `app/src/test/java/com/fshu/next/poll/PollParserTest.kt`, `app/src/test/java/com/fshu/next/poll/PollTallyTest.kt`, `PROJECT_MEMORY.md` | _this commit_ |
