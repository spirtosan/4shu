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
| T2  | Newer-device launch crash ("4shu keeps stopping") | Bug | **P0** | Installs, crash-loops at launch on newer Android only; fine on G60/A12. Need: crash dump from device Downloads + device model/OS version. Audit native `.so`/WebRTC + 16 KB alignment + AGP/compileSdk. minSdk change is NOT the fix. |
| TS | SDK bump: `minSdk 26→31`, `compileSdk 34→35` | Config | **P1** | Part of platform policy; compileSdk bump also feeds T2 fix. Keep `targetSdk 34` until call testing on G60. Ivan edits in Android Studio. |
| T10 | Rename groups | Feature | **P2** | Groups currently cannot be renamed. Add rename (admin/owner role). |
| T8 | Chat/channel media gallery | Feature | **P2** | List all media sent in a chat/channel. |
| T5 | Polls in groups (reuse todo-list infra) | Feature | **P2** | Build on existing `lists`/`list_items` tables. |
| T7 | Screen share | Feature | **P3** | Large. WebRTC screen capture. Interacts with foreground-service rules. |
| T11 | Shared todo list with "who did the task" | Feature | **P3** | Tentative, not confirmed. |
| T1 | Reset-link page shows "???" symbols | Bug | **P3 / parked** | Charset/encoding on reset page. Secret-question is the only active reset path, so low impact for now. |

### In Progress
_(none yet)_

### Done

| ID | Item | Notes |
|----|------|-------|
| T9 | Slide-to-accept / slide-to-reject incoming-call UI | Single horizontal slide track replaces tap buttons. Right ≥80% → `acceptCall()`, left ≥80% → `rejectCall()`. Spring-back, haptic tick at threshold, commit-once guard, TalkBack accessibility actions. No protocol/DB/permission changes. UPDATE build. |
| T6 | Build flavors: `personal` (server URL pre-filled) vs `distribution` (blank) | `flavorDimensions "serverType"` + two `productFlavors` in `build.gradle`; `LoginActivity` fills from `BuildConfig.DEFAULT_SERVER_URL` when no saved URL. UPDATE build. |
| T4 | Add-contact: auto-focus search field + open keyboard | `SearchActivity`: `view.post` + `WindowInsetsControllerCompat` + `InputMethodManager` fallback; manifest `stateVisible\|adjustResize`. UPDATE build. |
| T3 | Vibration doesn't stop after emergency call | Added `stopAlerting()` to `CallViewModel` — calls both `stopIncomingVibration()` and `FshuService.cancelCallNotif()`. Wired to all terminal paths: `acceptCall`, `rejectCall`, `remoteEndCall`, `incomingTimeoutJob`, `handleBusy`, `onCleared`. Guard added: `callFinished` flag prevents re-entry after stop. UPDATE build. |

### Parked / Deferred
- Per-contact **trust-level UI** (currently admin-panel only).

---

## Open Questions

- **T2:** Is there a crash dump in the newer device's Downloads folder? (Presence →
  Java exception we can read; absence → likely native crash / 16 KB-alignment.)
- **T2:** Exact crashing device model + Android version?

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
| 2026-06-29 | T9 follow-up: fix slider permanently locking when mic permission is denied on accept path. `committed` lifted to class field `sliderCommitted`; `sliderSpringBack` lambda resets it + animates handle to center on denial. `requestPermissionsForCall` gains `onDenied` param; on denial — accept path springs back (user can retry or decline), caller path still calls `finish()`. Same fix applied to TalkBack accept action and mutual-resolve path. | `…/ui/call/CallActivity.kt`, `PROJECT_MEMORY.md` | _pending_ |
