# T17 — Mic privacy indicator stays on after call teardown — FIX SPEC

> **Status:** Recon DONE (planning chat, 2026-07-05, from Ivan-supplied
> `WebRTCManager.kt` + `CallViewModel.kt`). This supersedes the recon phase of
> `T17_MIC_INDICATOR_LEAK.md` — go straight to implementation.
> Task header: `[T17] mic-indicator leak fix | UPDATE` — client-only, no
> protocol/DB/permission/server change, no [PROTOCOL CHANGE].
> Line numbers below are from the uploaded copies and may drift slightly —
> anchor on symbols, not lines.

---

## 1. Findings (root cause)

Every terminal path DOES reach WebRTC teardown (this is not a missed-path bug):
`CallViewModel.endCall()` / `remoteEndCall()` call `webRTC.endCall()`, and
`onCleared()` calls `webRTC.dispose()` on every activity finish. The bug is that
the teardown itself violates the libwebrtc Android disposal contract in four
compounding ways:

1. **The main `PeerConnection` is never disposed — only `close()`d.**
   `WebRTCManager.endCall()` (~:308) does `peerConnection?.close()` and nulls the
   ref; `dispose()` (~:371) never touches it (already null by then). `close()`
   alone is not a reliable capture stop on current WebRTC Android builds —
   `dispose()` is the contract — and the native PC object is leaked outright.
2. **The audio source is never disposed, anywhere.** `addLocalAudio()` (~:204)
   creates `factory.createAudioSource(...)` as a *local variable* — no field, no
   disposal, leaked every call. (The camera `VideoSource` in `addLocalVideo()`
   ~:217 has the same local-variable leak.)
3. **Tracks are disposed in the wrong order** — `localVideoTrack?.dispose()` /
   `localAudioTrack?.dispose()` (~:301–304) run while the tracks are still
   attached to senders of a live, un-closed PC; the PC is closed only afterwards.
4. **`dispose()` drops the factory handle while the leaked native PC still pins
   the native stack.** `factory.dispose()` + `eglBase.release()` free the Java
   handles, but the never-disposed native PeerConnection keeps the native
   factory — **including its audio device module and its `AudioRecord`** —
   alive and unreachable. After that, nothing in the process can ever stop the
   recorder. Only process death can → exactly Ivan's symptom ("app kill removes
   it").

Same pattern in **`checkTurnReachable()`** (~:383–505): a dummy audio track is
added to a temp PC and `setLocalDescription(offer)` is called; the `finally`
block disposes the dummy *track* but **not the dummy audio source**, and does
`tempPc?.close()` — **never `dispose()`** — leaking a second native PC (+ pinned
audio path) per invocation. *(Grep the repo for `checkTurnReachable` callers; fix
its cleanup regardless of whether it currently runs in the call flow.)*

Secondary path gaps (all made harmless by the idempotent fix, but wire them
anyway so the mic stops *immediately* rather than at delayed activity finish):

- `handleBusy()` never calls `webRTC.endCall()` — relies entirely on
  `onCleared()`.
- The `onCallEnded` lambda (ICE failure/disconnect, `CallViewModel` ~:82–92)
  posts `ENDED` but never tears down WebRTC — capture keeps running until the
  activity's delayed finish → `onCleared()`.
- `onCleared()` calls `webRTC.dispose()` **unconditionally**, which *initializes
  the lazy* on paths where WebRTC never started (e.g. rejected incoming voice
  call) — creating a factory + EglBase just to dispose them.

**Verdict on "was it there before screen share?":** almost certainly YES —
pre-existing, not a T7 regression. The close-only/no-source-dispose pattern
predates Block A (Block A only added the screen branch and `videoSender`
nulling; the camera block was byte-identical). It may even predate T2's M144
swap (the old Stream build may have been more forgiving on `close()`). T7 is
exonerated; its testing just put eyes on the status-bar privacy chips. The
bisect from the recon brief is now optional curiosity, not needed for the fix.

---

## 2. Fix — Block 1 (expected sufficient)

### 2.1 `WebRTCManager.kt`

**a. Store the sources.** New fields `private var audioSource: AudioSource?`,
`private var cameraVideoSource: VideoSource?`; assign in `addLocalAudio` /
`addLocalVideo` instead of locals.

**b. Rewrite `endCall()` as a one-shot, per-step-guarded `releaseAll`.**

```kotlin
private val released = java.util.concurrent.atomic.AtomicBoolean(false)

private inline fun step(name: String, block: () -> Unit) {
    try { block(); Log.d("CallTeardown", "$name ok") }
    catch (e: Exception) { Log.w("CallTeardown", "$name FAILED", e) }
}

fun endCall() {
    if (!released.compareAndSet(false, true)) return
    step("iceJob")        { iceDisconnectJob?.cancel(); iceDisconnectJob = null }
    step("candidates")    { synchronized(pendingCandidates) { remoteDescriptionSet = false; pendingCandidates.clear() } }
    step("screenCapture") { screenCapturer?.stopCapture() }            // no swap-back — full teardown
    step("cameraCapture") { videoCapturer?.stopCapture() }
    step("pcDispose")     { peerConnection?.dispose() }                // close + free — THE key change
    step("audioTrack")    { localAudioTrack?.dispose() }               // tolerate already-disposed-by-sender
    step("videoTrack")    { localVideoTrack?.dispose() }
    step("screenTrack")   { screenVideoTrack?.dispose() }
    step("audioSource")   { audioSource?.dispose() }
    step("videoSource")   { cameraVideoSource?.dispose() }
    step("screenSource")  { screenVideoSource?.dispose() }
    step("cameraDispose") { videoCapturer?.dispose() }
    step("screenDispose") { screenCapturer?.dispose() }
    step("surfaceHelper") { surfaceTextureHelper?.dispose() }
    step("screenHelper")  { screenSurfaceTextureHelper?.dispose() }
    // null everything: peerConnection, videoSender, tracks, sources, capturers,
    // helpers, remoteVideoTrack, storedLocal/RemoteRenderer; screenSharing = false
}
```

Ordering rationale: capturers stopped first; **PC disposed before tracks/sources**
(inverts the current order — disposal of in-use tracks under a live PC is the
contract violation); every step isolated so no single failure aborts the rest;
`AtomicBoolean` guard because `endCall` can now arrive from main (UI), from
`viewModelScope` (VM wiring below), and repeatedly via `dispose()`.

⚠ **Verify against the pinned M144 artifact** (same bytecode-recon method as
Block D step (i)): whether `PeerConnection.dispose()` → `RtpSender.dispose()`
disposes the sender's cached track (ownsTrack semantics for `addTrack` and for
`setTrack(track, false)`). The per-step try/catch makes either answer safe
(worst case a logged, caught `IllegalStateException` on the track steps), but
note the finding in PROJECT_MEMORY.

**c. Guard post-release entry points.** `startScreenShare`, `stopScreenShare`,
and the `MediaProjection.Callback.onStop` handler get an early
`if (released.get()) return` so a late OS-revoke or UI tap can't touch disposed
objects.

**d. `dispose()`** stays structurally the same — `endCall()` (now releaseAll),
`scope.cancel()`, `factory.dispose()`, `eglBase.release()` — plus its own
one-shot guard and `step(...)` wrapping for the factory/egl lines. It's now
valid because the PC is actually disposed first.

**e. Fix `checkTurnReachable()` cleanup.** Hoist the dummy `audioSource` so the
`finally` can reach it; in the `finally` (still `NonCancellable`):
`dummyTrack?.dispose()`, dummy source `.dispose()`, and `tempPc?.dispose()`
(dispose implies close — replace the bare `close()`).

### 2.2 `CallViewModel.kt`

- **`handleBusy()`**: add `webRTC.endCall()` (audio focus can stay on the
  `onCleared` path — minimal diff).
- **`onCallEnded` lambda**: add `viewModelScope.launch { webRTC.endCall() }`.
  Must NOT call it inline — the lambda fires from WebRTC-internal threads, and
  disposing from an observer callback thread can deadlock; `viewModelScope`
  (Main) is the safe hop, same reasoning as Block A's `mainHandler.post` in the
  projection callback.
- **`onCleared()`**: only dispose if the lazy was ever created:

```kotlin
private val webRTCDelegate = lazy { WebRTCManager(...) }
private val webRTC: WebRTCManager by webRTCDelegate
// onCleared:
if (webRTCDelegate.isInitialized()) webRTC.dispose()
```

- `rejectCall()` needs no WebRTC call (nothing started on the incoming path) —
  confirm with a quick check that `CallActivity` doesn't call
  `getEglBaseContext()` during INCOMING preview; if it does, the lazy exists and
  `onCleared`'s dispose covers it anyway.

### 2.3 Cross-checks (grep, before finalizing)

- All `WebRTCManager` reference holders outside `ui/call` (expect none).
- `checkTurnReachable` call sites — if it runs during call setup, say so in the
  memory entry (it's a co-suspect for pre-answer capture start).
- `CallActivity`'s ENDED handling and `onDestroy` (`clearVideoRenderers` before
  renderer release) still consistent with the nulling in releaseAll.

---

## 3. Fix — Block 2 (contingent — only if Block 1's test still shows the dot)

Own the audio device module explicitly so there is a release handle of last
resort:

```kotlin
private val audioDeviceModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
factory = PeerConnectionFactory.builder()
    .setAudioDeviceModule(audioDeviceModule)
    .setVideoDecoderFactory(...)
    .setVideoEncoderFactory(...)
    .createPeerConnectionFactory()
// dispose(), after factory.dispose():
step("admRelease") { audioDeviceModule.release() }
```

Behavior-adjacent (explicit builder defaults vs the factory's implicit default
ADM — verify against M144 source that they match, expected yes). Do NOT bundle
into Block 1; it ships only if Ivan's Block 1 test fails, as its own commit.

---

## 4. Verification (Ivan, G60 pair — after Block 1 build)

Mic (and camera where applicable) indicator must clear within ~2 s, no app kill:

1. Outgoing ring, unanswered → caller taps End (the reported repro).
2. Outgoing ring → callee rejects. 3. Outgoing ring → caller back-press/close.
4. Answered audio call → either side ends. 5. Answered video call → end (camera
   AND mic clear). 6. `call-busy` on the caller. 7. Video call + screen share →
   end while sharing; and stop share → end. 8. Outgoing ring → swipe app from
   recents. 9. Emergency-call variants of 1–2.
10. Regression: calls connect both directions; mute/camera/flip/share OK;
    logcat tag `CallTeardown` shows the full step sequence, no `FAILED` lines
    (a FAILED line that still ends with the dot clearing is acceptable but must
    be reported back — it identifies the M144 ownsTrack answer).

Also note once: on the fixed build, when does the dot first appear — CALLING
screen or connect? (Pure telemetry for the memory entry; either is fine.)

---

## 5. Close-out

- `PROJECT_MEMORY.md`: changelog entry (root cause = pre-existing disposal-
  contract violations, not a T7 regression — T7 exonerated), board T17 → Done
  pending G60 verification, note the M144 ownsTrack finding and whether
  `checkTurnReachable` is live in the call flow.
- Commit Block 1 as one commit (+ memory). Block 2, if ever needed, separately.
- UPDATE build. No server/protocol/schema/manifest change. Do not resume T7
  Block E under this ticket (the share-path guards in §2.1c overlap it — note
  that in the T7 park note, nothing more).
