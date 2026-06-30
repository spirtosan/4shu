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
| T5 | Polls in groups (reuse todo-list infra) | Feature | **P2** | Build on existing `lists`/`list_items` tables. |
| T7 | Screen share | Feature | **P3** | Large. WebRTC screen capture. Interacts with foreground-service rules. |
| T11 | Shared todo list with "who did the task" | Feature | **P3** | Tentative, not confirmed. |
| T1 | Reset-link page shows "???" symbols | Bug | **P3 / parked** | Charset/encoding on reset page. Secret-question is the only active reset path, so low impact for now. |

### In Progress

| ID | Item | Type | Pri | Notes |
|----|------|------|-----|-------|
| T2 | Newer-device launch crash ("4shu keeps stopping") | Bug | **P0** | **CONFIRMED 16 KB page-size alignment** (`libjingle_peerconnection_so.so` + `libfshu_native.so` unaligned on arm64-v8a). Fix applied: WebRTC bumped to `io.github.webrtc-sdk:android:144.7559.09` (M144, 16 KB-aligned); AGP `8.2.2→8.6.1`; Gradle wrapper `8.4→8.7`; ndkVersion `28.0.12433566`; `-Wl,-z,max-page-size=16384` linker flag via CMakeLists.txt `target_link_options` only (invalid `ldFlags` line removed from `app/build.gradle` cmake block — `ldFlags` is ndkBuild-only DSL, not valid for cmake). Android Studio + Gradle updated; project syncs clean on AGP 8.6.1. **Three gates before Done:** (a) `ndkVersion` must match the installed r28+ build ID exactly; (b) APK Analyzer: all arm64-v8a `.so` must show 16 KB aligned (no warning); (c) voice + video calls must pass on G60 — WebRTC M92→M144 jump may shift PeerConnectionFactory init or codec behavior. **Awaiting Ivan rebuild + verification.** |

### Done

| ID | Item | Notes |
|----|------|-------|
| T10 | Rename groups | ✓ verified on G60. Client sends `group-rename`; server (already handled) validates owner/admin, updates name, broadcasts `group-state`; FshuService upserts Room, ChatActivity re-reads title. UI: "Rename group" link (owner/admin only) in group-info dialog. Validation: trim, 1–64 chars, reject empty/unchanged. No schema/protocol/DB changes. No system message (type doesn't exist). UPDATE build. |
| T8 | Chat/group media gallery | ⏳ BUILT, NOT YET G60-VERIFIED (test pending next session). Images-only v1. 3-col grid (date headers), in-app PhotoView+ViewPager2 viewer, Save-to-device + Share. Entry: chat overflow "Media" (DM+group) + group-info dialog link. No schema/DB/server/protocol change. UPDATE build. |
| T9 | Slide-to-accept / slide-to-reject incoming-call UI | ✓ verified on G60. Single horizontal slide track replaces tap buttons. Right ≥80% → `acceptCall()`, left ≥80% → `rejectCall()`. Spring-back, haptic tick at threshold, commit-once guard, TalkBack accessibility actions. No protocol/DB/permission changes. UPDATE build. |
| T6 | Build flavors: `personal` (server URL pre-filled) vs `distribution` (blank) | ✓ verified on G60. `flavorDimensions "serverType"` + two `productFlavors` in `build.gradle`; `LoginActivity` fills from `BuildConfig.DEFAULT_SERVER_URL` when no saved URL. UPDATE build. |
| T4 | Add-contact: auto-focus search field + open keyboard | ✓ verified on G60. `SearchActivity`: `view.post` + `WindowInsetsControllerCompat` + `InputMethodManager` fallback; manifest `stateVisible\|adjustResize`. UPDATE build. |
| T3 | Vibration doesn't stop after emergency call | ✓ verified on G60. Added `stopAlerting()` to `CallViewModel` — calls both `stopIncomingVibration()` and `FshuService.cancelCallNotif()`. Wired to all terminal paths: `acceptCall`, `rejectCall`, `remoteEndCall`, `incomingTimeoutJob`, `handleBusy`, `onCleared`. Guard added: `callFinished` flag prevents re-entry after stop. UPDATE build. |

### Parked / Deferred
- Per-contact **trust-level UI** (currently admin-panel only).

---

## Open Questions

- **T2:** Config confirmed clean — invalid `ldFlags` cmake line removed, Android Studio + Gradle updated, project syncs on AGP 8.6.1. Blocker is now Ivan's rebuild + verification. Three gates: (a) `ndkVersion` must match installed r28+ build ID exactly; (b) APK Analyzer must show all arm64-v8a `.so` at 16 KB aligned, no warning; (c) voice + video calls must pass on G60 — WebRTC M92→M144 jump may shift PeerConnectionFactory init or codec behavior.
- **T8:** Awaiting G60 test pass — watch: (1) MediaStore Pictures save path (saved image must appear in device gallery); (2) grid layout + date headers render correctly; (3) viewer swipe + pinch-zoom; (4) both entry points (chat overflow "Media" and group-info dialog link); (5) empty-state message; (6) deleted-image exclusion (soft-deleted images must not appear).

---

## Decisions Log

| Date | Decision |
|------|----------|
| 2026-06-28 | Maintenance mode: bug fixes + minor features only. |
| 2026-06-28 | Mobile only — Electron/desktop client dropped from roadmap. |
| 2026-06-28 | Drop Android < 12. Target `minSdk 31 / compileSdk 35 / targetSdk 34` (test 35 later). |
| 2026-06-28 | Not distributing via Play Store → Play targetSdk deadlines do not apply. |
| 2026-06-28 | Separate `PROJECT_MEMORY.md` (this file), maintained by Claude Code. |

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
