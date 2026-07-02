# Spec — T4 (add-contact focus) + T3 (vibration after emergency call)

> For: Claude Code. Drafted by planning Claude, approved by Ivan.
> Mode: maintenance, mobile only. Implement both in one session.
> **Build type: UPDATE** for both — no protocol change, no DB migration, no new permissions.
> Testing is deferred to real G60 devices; implement all now.
> **After implementing: update `PROJECT_MEMORY.md` (move T3, T4 To Do → Done, add
> Changelog entries with files + commit hash) and commit it alongside the code.**

---

## T4 — Add-contact: auto-focus search + open keyboard | UPDATE

### Goal
When the add-contact screen opens, the search field is already focused and the soft
keyboard is already shown, so the user can type a username immediately (saves one tap
every time).

### Steps
1. Locate the add-contact UI — the screen/component that owns the contact-search
   `EditText` (Activity, DialogFragment, or BottomSheetDialogFragment). Confirm which
   it is before editing; the IME approach differs slightly by host.
2. After the view is laid out, focus the field and show the keyboard. Run it inside
   `view.post { ... }` (or `doOnLayout`) — requesting focus before layout silently
   fails.
3. Use BOTH mechanisms for OEM reliability (these are Motorola devices):
   - Modern: `WindowInsetsControllerCompat(window, searchField).show(WindowInsetsCompat.Type.ime())`
   - Fallback: `InputMethodManager.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)`
   - Call `searchField.requestFocus()` before either.
4. If the host is an **Activity**, also set in the manifest for that activity:
   `android:windowSoftInputMode="stateVisible|adjustResize"`.
   If the host is a **Dialog/BottomSheet**, set the soft-input mode on the dialog's
   window instead (`dialog.window?.setSoftInputMode(SOFT_INPUT_STATE_VISIBLE or adjustResize)`).
5. Do not auto-show the keyboard if the field is already non-empty from a restored
   state (edge case; cursor focus is fine, just don't force-show on every resume).

### Acceptance (to verify later on G60)
- Opening add-contact shows a blinking cursor in the search field and the keyboard,
  with no extra tap.
- Rotating / returning to the screen doesn't crash or double-pop the keyboard.

### Constraints
- App-only. No server, no DB, no protocol. Touch only the add-contact screen + its
  manifest entry (if Activity).

---

## T3 — Vibration doesn't stop after emergency call | UPDATE

### Root cause (expected)
Emergency calls use a **repeating** vibration pattern (repeat index 0), so it never
stops by itself — it must be cancelled explicitly. The current code calls
`vibrator.cancel()` on only some call-end paths, so paths that miss it leave the phone
buzzing.

### Steps
1. Find where the emergency-call vibration starts — likely the service that
   intercepts `call-emergency` and launches `CallActivity`, or in `CallActivity` /
   `CallViewModel`. (See CLAUDE.md: emergency uses STREAM_ALARM on receiver.)
2. Hold the `Vibrator` (and ringtone/`Ringtone`/`MediaPlayer`, if used) reference at a
   scope reachable by every call-end path — not in a local that one exit can't see.
3. Add a single idempotent method, e.g. `stopAlerting()`, that:
   - cancels the vibrator (`vibrator?.cancel()`),
   - stops any alarm-stream ringtone/media player,
   - is safe to call multiple times (null-checks, no crash if already stopped).
4. Call `stopAlerting()` from **every** terminal path:
   - user **accepts** the call,
   - user **rejects** (`call-reject`),
   - caller **hangs up** (`call-end`, reason `"ended"`),
   - call **drops** (`call-end`, reason `"disconnected"`),
   - **`call-busy`**,
   - ring **timeout** (if a no-answer timer exists),
   - **safety net:** `CallActivity.onDestroy()` and/or `CallViewModel.onCleared()`.
5. Double-check the start path isn't re-triggered after stop (e.g. a late
   `call-emergency`/duplicate frame restarting vibration after the call already
   ended). If possible, guard with a "call finished" flag so stop wins.

### Acceptance (to verify later on G60)
- Trigger an emergency call, then end it each way — accept, reject, caller hangs up,
  drop/disconnect, busy, no-answer — and confirm vibration stops every time.
- Phone is never left buzzing after the call screen closes.

### Constraints
- App-only. No server, no DB, no protocol. Likely files: the call-intercept service,
  `CallActivity.kt`, `CallViewModel.kt`.

---

## Done criteria for the session
- [ ] T4 implemented (focus + keyboard, both IME mechanisms, manifest/dialog soft-input mode).
- [ ] T3 implemented (single `stopAlerting()` wired into all terminal paths + safety net).
- [ ] `PROJECT_MEMORY.md` updated: T3, T4 moved To Do → Done; Changelog rows added
      (date, what, files, commit hash).
- [ ] Committed to git (code + PROJECT_MEMORY.md together).
- [ ] No Gradle/adb run by Claude Code — Ivan builds the APK.
