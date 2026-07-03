# T7 — Screen Share (v1) — Architecture Spec

> **Status:** Spec / not started. Drafted by planning chat for Claude Code to implement.
> **Scope decision (Ivan, this session):** 1-1, inside an active video call, **screen-only** (no system audio).
> **Prereq:** run the 16 KB gate below *before* implementing.

---

## 0. 16 KB Gate — SATISFIED (closed by T2, 2026-06-30)

Screen share reuses the **existing WebRTC `.so`** (`org.webrtc.ScreenCapturerAndroid`).
It adds **no new native library**.

T2 (the newer-device launch crash) is **DONE and G60-verified** (commit `56c1668`, see
`PROJECT_MEMORY.md` changelog): WebRTC swapped
`io.getstream:stream-webrtc-android:1.1.1` → `io.github.webrtc-sdk:android:144.7559.09`
(M144, 16 KB-aligned), AGP bumped to `8.6.1`. APK Analyzer confirmed all arm64-v8a `.so`
files 16 KB-aligned, and voice + video calls were verified passing on the G60 post-fix.

**This closes the gate for screen share:** the WebRTC binary screen share reuses
(`ScreenCapturerAndroid`, same `.so`) *is* the already-16 KB-aligned M144 build T2
shipped. No separate check is needed before implementing — proceed straight to §1.

**Note:** this is satisfied for the *current* WebRTC version only. If a future WebRTC
version bump changes the binary again, re-run the device-free check below before
building further screen-share work on top of it.

**Device-free check (5 min, Android Studio), kept for that future case:**
1. `Build > Analyze APK` on the current release APK.
2. Expand `lib/`, read the **Alignment** column.
3. Green / aligned across `lib/arm64-v8a/*.so` → no action needed.
   Warning on `libjingle`/WebRTC `.so` → the WebRTC binary regressed and needs
   re-alignment (mirror T2's fix) before proceeding.

---

## 1. v1 Scope

**In:**
- 1-to-1 calls only.
- Only while a video call is already active (no standalone "share without a call").
- Screen-only video. No system audio capture.
- Sender can toggle sharing on/off during the call.
- Receiver sees the shared screen in the existing remote-video surface + a
  "X is sharing their screen" banner.

**Out (deferred):**
- Group-call screen share.
- System / app audio capture.
- Remote control, annotation.
- Standalone screen share with no call.

**Why this is small:** video calls already work, so the receive path
(`SurfaceViewRenderer`, remote `VideoTrack` rendering, renderer lifecycle) is done.
Screen share is mostly "swap the outgoing camera track for a screen track on the
existing sender."

---

## 2. Architecture

> **targetSdk note:** the FGS/permission requirements below are written against
> **targetSdk 34**, which is still the repo's current value — the `34→35` bump
> (PROJECT_KNOWLEDGE.md §4) has been deferred and never applied, so these requirements
> remain correct as-is. **targetSdk should stay 34 through screen-share implementation:**
> the Android 14 `mediaProjection` FGS-ordering requirement (below) is targetSdk-sensitive,
> and moving to 35 mid-feature would shift FGS/notification runtime behavior underneath an
> in-progress implementation. Sequence: ship screen share on targetSdk 34 first; consider
> the 35 bump only afterward, as its own isolated, call-tested change.

### Capture
- `MediaProjectionManager.createScreenCaptureIntent()` → **system consent dialog**
  (unavoidable, shown every session — cannot be suppressed).
- On grant → obtain `MediaProjection`.

### WebRTC wiring
- `org.webrtc.ScreenCapturerAndroid(consentIntentData, MediaProjection.Callback)`.
- Feed it through a `SurfaceTextureHelper` into a `VideoSource` → `VideoTrack`
  (the "screen track").

### Track swap (the core trick — no renegotiation)
- Reuse the **existing outgoing video `RtpSender`/transceiver** from the active call.
- Start sharing: `rtpSender.setTrack(screenTrack, /*takeOwnership=*/false)`.
- Stop sharing: `rtpSender.setTrack(cameraTrack, false)`.
- Reusing the existing video m-line avoids SDP renegotiation entirely.
- **Claude Code to confirm:** the call setup uses a single reusable video sender/
  transceiver (almost certainly yes since video calls work). If video is created
  per-call without a reusable sender, this section needs revisiting.

### Foreground service (mandatory)
- Screen capture on Android 10+ requires a foreground service of type
  `mediaProjection`.
- On **targetSdk 34**: needs `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission +
  `android:foregroundServiceType="mediaProjection"` on the service.
- Must **coexist with the existing call FGS** — either add the `mediaProjection`
  type to the existing call service, or run a second capture service. (Decision for
  Ivan — see Open Questions.)
- **Android 14 ordering requirement:** start the `mediaProjection` FGS *before*
  calling `getMediaProjection()`, and register a `MediaProjection.Callback`.

### Signaling
- Add control messages `screen-share-start` / `screen-share-stop` over the **existing
  WebSocket call signaling** so the remote side can show the banner and pick a sane
  scaling mode.
- **No new crypto:** the screen video itself rides the existing DTLS-SRTP WebRTC
  media path. Consistent with maintenance-mode "no new crypto subsystem."
- **Server:** if call signaling already relays arbitrary typed messages between the
  two peers, no `server.js` change is needed. If there's a server-side message-type
  allowlist, add the two new types. **No DB/schema change → no install-script change.**

---

## 3. Permissions / Manifest

*(targetSdk 34 — see the note at the top of §2; do not bump to 35 until after this
feature ships.)*

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"/>

<service
    android:name=".call.ScreenCaptureService"
    android:foregroundServiceType="mediaProjection"
    android:exported="false"/>
```
- No separate runtime permission dialog for projection — the consent intent **is** the
  per-session grant.

---

## 4. UX

- Add a **"Share screen"** toggle to the in-call control bar (next to mute /
  camera-flip).
- Tap → system consent dialog → on grant: start FGS → acquire projection → swap to
  screen track → send `screen-share-start`.
- While sharing: in-app indicator on the toggle; the OS also shows its own
  screen-cast status icon/notification (unavoidable).
- Stop: tap toggle again → swap back to camera track → stop projection → stop FGS →
  send `screen-share-stop`.
- **Receiver:** banner "X is sharing their screen"; screen renders in the main remote
  video surface using `SCALE_ASPECT_FIT` (screen aspect ratio differs from camera —
  avoid cropping content).

---

## 5. Edge Cases

- **Call ends while sharing** → stop projection + FGS cleanly. Tie into existing call
  teardown (mirror the T3 `stopAlerting()` terminal-path pattern so nothing leaks).
- **User revokes projection via OS notification** → `MediaProjection.Callback.onStop`
  → swap back to camera + send `screen-share-stop` + drop FGS.
- **Consent dialog denied** → no-op, revert the toggle to off. Do **not** lock the UI
  on denial (lesson from T9 slider-lock fix — always spring back on denial).
- **Rotation / aspect change** → let WebRTC handle re-scaling; receiver stays
  `SCALE_ASPECT_FIT`.

---

## 6. Likely Files (for Claude Code)

- `call/CallActivity.kt` / `call/CallViewModel.kt` — toggle, projection lifecycle,
  track swap, terminal-path cleanup.
- `call/ScreenCaptureService.kt` (new) **or** extend existing call FGS with the
  `mediaProjection` type.
- PeerConnection / WebRTC wrapper — `rtpSender.setTrack(...)`.
- Call signaling handler — `screen-share-start` / `screen-share-stop` (client; and
  `server.js` only if the relay isn't already generic).
- `AndroidManifest.xml` — permission + service type.
- `values/strings.xml` + `values-bg/strings.xml` — toggle label, "X is sharing their
  screen" banner. **No RU** (`values-ru` absent).
- `PROJECT_MEMORY.md` — changelog + board move on implement.

---

## 7. Open Questions for Ivan

1. **Signaling relay:** does the call signaling already relay arbitrary typed messages
   between the two peers, or is there a server-side allowlist? (Decides whether
   `server.js` needs a change.)

   **RESOLVED:** server dispatches on an explicit `switch(msg.type)` allowlist; the
   default case only `console.warn`-drops unknown types (verified in the reconciled
   `server.js`). Screen share NEEDS a server change — add two relay cases
   `screen-share-start` / `screen-share-stop`, modeled on the existing
   `ice-candidate`/`call-ringing`/`ringing-ack` relay: `sendToAll(msg.to, msg); break;`
   (online-only, no offline queue — correct for ephemeral in-call control). This is a
   functional deploy (repo edit + push to live + restart fshu5) and is the designated
   deploy that also removes the parked T13 dead line.

2. **FGS shape:** add `mediaProjection` type to the existing call service, or a
   separate `ScreenCaptureService`? (Either works.)

   **RESOLVED (Ivan):** add the `mediaProjection` foregroundServiceType to the EXISTING
   call foreground service (not a separate `ScreenCaptureService`). One lifecycle,
   unified teardown via the existing call terminal path (T3 `stopAlerting` pattern);
   screen share only runs inside an active call, so no standalone service.

3. **Reusable video sender:** confirm the call uses one reusable video
   sender/transceiver so `setTrack` works without renegotiation. (Expected yes.)

   **RESOLVED: YES.** `WebRTCManager` is the sole WebRTC wrapper; Unified Plan
   (explicit, `WebRTCManager.kt:127`); one video `RtpSender` created once per call via
   `pc.addTrack` in `addLocalVideo`, never recreated by `switchCamera`/`enableVideo`,
   separate from audio; `onRenegotiationNeeded` is a no-op; `setTrack` won't
   renegotiate. Camera `VideoTrack` already retained as `localVideoTrack` for restore.
   Impl note: the `RtpSender` isn't stored yet — capture `addTrack`'s return value into
   a field (one line), or look it up via `peerConnection.senders`/`transceivers`
   filtered to video at swap time; keep `videoCapturer`/`surfaceTextureHelper`/
   `localVideoTrack` alive during a share (don't let `endCall` disposal run).

---

## 8. Implementation Plan (v1)

Small blocks, each a testable G60 milestone, same rhythm as T5.

- **Block A — WebRTC layer** (client-only, no UI, no change to existing-call
  behavior). Store the video `RtpSender` (capture `addTrack`'s return); add
  `startScreenShare(screenTrack)` / `stopScreenShare()` doing
  `videoSender.setTrack(screen/localVideoTrack, false)`; create the screen track
  (`ScreenCapturerAndroid` → its OWN `SurfaceTextureHelper`/`VideoSource`/`VideoTrack`,
  separate from the camera's); guard camera capturer/track from `endCall` disposal
  mid-share. **Test:** existing voice+video calls still pass on G60 (regression only;
  new methods unused).
- **Block B — MediaProjection + FGS, DEBUG-triggered.**
  `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission; add `mediaProjection` to the
  existing call service's `foregroundServiceType`; consent intent
  (`createScreenCaptureIntent`); Android-14 ordering (start FGS BEFORE
  `getMediaProjection`) + `MediaProjection.Callback`. Temporary `BuildConfig.DEBUG`
  trigger (`seedDebugPoll` pattern) to start/stop without real UI. **Test:**
  debug-trigger in a call → other phone's remote surface shows the screen; OS-revoke
  via cast notification → swaps back to camera.
- **Block C — Sender UX.** Real "Share screen" toggle in the in-call control bar;
  consent flow; in-app sharing indicator; spring-back-on-denial (T9 lesson — never
  lock the UI); remove the debug trigger.
- **Block D — Signaling + receiver UX + the one server deploy.** Client sends
  `screen-share-start`/`stop`; add the two server relay cases AND remove the T13 dead
  line; deploy to live + restart fshu5 + smoke-test a call; receiver shows "X is
  sharing their screen" banner and switches remote surface to `SCALE_ASPECT_FIT`.
  **Test:** sharer toggles → receiver sees banner + correctly-scaled screen.
- **Block E — Edge cases + strings + close-out.** Call-ends-while-sharing teardown
  (mirror T3 `stopAlerting` terminal paths, no leaks); OS-revoke path finalize;
  rotation/aspect; EN+BG strings (no RU); mark §7 resolved end-to-end; memory
  close-out.

**Cross-cutting:** UPDATE build throughout (reuses the M144 `.so` — 16 KB gate
satisfied, no native change); targetSdk STAYS 34 (Android-14 `mediaProjection` FGS
ordering is 34-sensitive); the ONLY production touch is Block D's deploy, which also
carries T13.
