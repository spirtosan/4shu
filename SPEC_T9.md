# Spec — T9: Slide-to-accept / slide-to-reject incoming-call UI | UPDATE

> For: Claude Code. Drafted by planning Claude, approved by Ivan.
> Mode: maintenance, mobile only.
> **Build type: UPDATE** — UI only. No protocol change, no DB migration, no new permissions.
> Testing deferred to real G60 devices; implement now.
> **After implementing: update `PROJECT_MEMORY.md` (move T9 To Do → Done, add a
> Changelog row with files + commit hash) and commit it alongside the code.**

---

## Goal

Replace the tap Accept/Reject buttons on the **incoming-call** screen with a single
horizontal slide track:

- Drag the handle **right** past the threshold → **answer** (calls existing `acceptCall()`).
- Drag the handle **left** past the threshold → **decline** (calls existing `rejectCall()`).

Purpose: prevent accidental answers/declines (pocket taps, fumbling for a ringing
phone). Therefore the tap buttons are **removed** from the incoming screen — slide is
the only pointer gesture (accessibility fallback below).

This is the **incoming/ringing state only**. The outgoing-call screen and the in-call
controls (mute/speaker/hang-up) are unchanged.

---

## Chosen pattern (final)

**Single horizontal track**, handle starts centered. Right = answer (green end),
left = decline (red end). Icons at each end (answer-phone right, decline-phone left)
plus faint hint text.

---

## Interaction rules (final — implement exactly)

1. **Commit by position, not fling.** The action fires only when the handle reaches
   **≥ 80%** of the travel from center to an end. A fast flick that doesn't reach 80%
   does NOT commit. Do not implement velocity/fling commit — position only. This is
   intentional (accident prevention).
2. **Spring-back.** On `ACTION_UP` before the 80% threshold, animate the handle back to
   center (short ~150 ms animation). No action taken.
3. **Clamp travel.** Handle `translationX` is clamped between `-halfTravel` and
   `+halfTravel` (cannot be dragged off the track).
4. **Haptic tick at commit threshold.** When the handle first crosses the 80% point on
   either side, fire a light system haptic: `handle.performHapticFeedback(
   HapticFeedbackConstants.CONFIRM)` (fallback `KEYBOARD_TAP` if CONFIRM unavailable).
   **Do NOT use the `Vibrator` service** — only `performHapticFeedback` — so this never
   interferes with the emergency-call vibration managed by `CallViewModel.stopAlerting()`.
5. **Commit once.** After a successful answer/decline, ignore further touches (guard
   with a `committed` flag) so a bounce can't double-fire.
6. **Progress feedback (nice-to-have, keep simple).** As the handle moves toward an
   end, brighten/!reveal that end's icon (e.g. alpha ramp). Optional; don't over-build.

---

## Wiring

- The slide gestures call the **existing** `CallViewModel.acceptCall()` and
  `CallViewModel.rejectCall()`. Do not duplicate teardown logic — those already call
  `stopAlerting()` (T3) and handle WS messaging.
- **Emergency incoming calls** use the same incoming screen and therefore the same
  slider. No special-casing — accept/reject already route correctly for emergency.
- Slider is active only while state is incoming/ringing. If the call state leaves that
  (e.g. remote cancels while ringing), the screen transitions away as it does today;
  no extra handling needed beyond not crashing if a touch is mid-flight.

---

## Accessibility (required, low cost)

Because tap is removed, expose the two actions to assistive tech so TalkBack users can
still answer/decline without performing the drag:

- Add `AccessibilityNodeInfo` custom actions (or `ViewCompat.addAccessibilityAction`)
  on the slider: **"Answer"** → `acceptCall()`, **"Decline"** → `rejectCall()`.
- Give the slider a sensible `contentDescription` (e.g. "Incoming call. Slide right to
  answer, left to decline.").

---

## Strings (EN + BG; RU flagged, not invented)

Add to `values/strings.xml` and `values-bg/strings.xml`:

- `call_slide_to_answer` — EN "Slide to answer" / BG appropriate translation.
- `call_slide_to_decline` — EN "Slide to decline" / BG appropriate translation.

Russian (`values-ru/`) does not exist project-wide yet — **do not invent RU strings**.
If a `values-ru` exists, leave these two keys out and note it in the task summary.

---

## Implementation notes

- Implement the track natively — **no new third-party slider/library** (project rule:
  no unnecessary deps). A handle `View` inside a track container with an
  `onTouchEvent`/`OnTouchListener` computing clamped `translationX`, threshold check on
  `ACTION_UP`, and an `ObjectAnimator` spring-back is sufficient.
- Likely files: the incoming-call layout XML (currently holds the accept/reject
  buttons), `CallActivity.kt` (where those buttons are wired), and the two strings
  files. Confirm exact layout filename in the repo before editing.
- Remove the now-unused accept/reject button click handlers and the button views from
  the incoming layout (keep them for outgoing/in-call screens if shared — verify the
  layout isn't reused before deleting anything).

---

## Done criteria
- [ ] Incoming-call screen shows a single horizontal slide track; tap buttons removed
      from that screen.
- [ ] Right ≥80% → `acceptCall()`; left ≥80% → `rejectCall()`; below threshold springs back.
- [ ] Commit is position-based (no fling), single-fire guarded, with a `performHapticFeedback`
      tick at threshold (no `Vibrator` use).
- [ ] Accessibility "Answer"/"Decline" actions + contentDescription present.
- [ ] EN + BG hint strings added; RU not invented (flagged if `values-ru` exists).
- [ ] Emergency incoming call uses the same slider and still stops vibration on accept/reject.
- [ ] `PROJECT_MEMORY.md`: T9 moved To Do → Done; Changelog row added (date, files, commit hash).
- [ ] Committed to git (code + PROJECT_MEMORY.md together). No Gradle/adb run by Claude Code.
