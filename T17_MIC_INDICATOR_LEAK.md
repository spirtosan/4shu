# T17 — Mic privacy indicator stays on after call teardown

> **Status:** Investigation brief, drafted by planning chat 2026-07-04.
> **Phase 1 for Claude Code: READ-ONLY recon.** No code changes, no SSH (client-only
> suspicion, server not implicated), no Gradle/adb. Quote code verbatim with line
> numbers, answer the checklist, then STOP AND REPORT with a fix proposal before
> writing any fix.
> Task headers: `[T17 recon] mic-indicator leak | read-only, no build` →
> `[T17 fix] | UPDATE` (expected client-only; no protocol/server/schema change).

---

## 1. Symptom (Ivan, 2026-07-04, G60 / Android 12)

- Make an **outgoing call**. Even if it is **never accepted**: after ending the call
  (or closing during ring), the **green microphone privacy indicator persists** for
  4shu.
- Only **killing the app** clears it.
- First noticed after the T7 screen-share work landed (Blocks A/B/C/C.1 + D(i));
  **unknown whether pre-existing** — T7 testing put a lot of eyes on status-bar
  privacy chips (Block C explicitly watched the cast chip), so discovery bias is
  plausible either way.

**Info still wanted from Ivan (answer in the recon report if known):**
1. Was the repro an **audio** call, a **video** call, or both?
2. After a **video** call, does the **camera** indicator also stick, or only mic?
   (Camera clearing while mic sticks = camera disposal works, audio disposal
   specifically missing/skipped — sharpens H1/H2 a lot.)
3. On the current build, **when does the mic dot first appear** — the moment the
   outgoing CALLING screen opens, or only once the call connects?

---

## 2. What the green dot actually means

Android 12's indicator is **usage-driven**: it lights while an app holds an *active*
`AudioRecord` (RECORD_AUDIO app-op "in use"). It is **not** driven by:

- the FGS `foregroundServiceType` — `FshuService`'s manifest has carried
  `microphone` in its type set since before T7 (Block B only appended
  `mediaProjection`), and the always-running WS service never lights the dot on its
  own;
- a lingering notification or the service merely being alive.

So the symptom means: **after teardown, something in-process is still recording.**
"App kill clears it" fits exactly — the leaked recorder is a native-backed
`AudioRecord` inside our process, released only when the process dies.

In this app there are only two `AudioRecord`/`MediaRecorder` owners:

1. **WebRTC's audio device module** inside `WebRTCManager` (per-call, created in
   `CallViewModel`) — the near-certain owner, since the repro is a pure call flow;
2. the voice-message recorder — not in the repro path; rule out by grep only (§5.6).

WebRTC background: the ADM's `AudioRecord` is started by the native voice engine
when the audio send stream starts, and is stopped when the last audio send stream is
torn down — i.e. when `PeerConnection.close()`/`dispose()` actually runs to
completion, or when the factory/ADM is disposed. If teardown **aborts partway,
races itself, or never runs on some path**, the recorder stays live → this symptom.

---

## 3. What PROJECT_MEMORY already tells us (context for the hypotheses)

- **T7 Block A rewrote `WebRTCManager.endCall()`** (2026-07-03): a new
  screen-resource teardown branch was added **ahead of** the pre-existing
  camera-disposal block; `videoSender` is nulled **after** `peerConnection?.close()`.
  The changelog wording says `close()` — where full `dispose()` of the PC, the
  **audio source / audio track**, factory, ADM and eglBase happens (endCall?
  onCleared? nowhere?) is not recorded and is the recon's core question.
- **Block A's G60 verification** covered call *function* on teardown paths
  (local/remote hang-up, decline, timeout — calls pass, no crash). Nobody checked
  the privacy indicator. A swallowed exception mid-teardown would pass every one of
  those tests.
- **Block E — the teardown-hardening block (call-ends-while-sharing, OS-revoke
  finalize) — was deliberately parked.** T7 is parked in a "working" state whose
  definition of working never included indicator hygiene.
- **T2 (2026-06-30)** swapped WebRTC `io.getstream:stream-webrtc-android:1.1.1` →
  `io.github.webrtc-sdk:android:144.7559.09` (M144). ADM/teardown semantics can
  differ across that jump — a pre-existing-since-T2 leak is fully consistent with
  "may be there before".
- **T3's `callFinished` guard lives in `CallViewModel` and guards *alerting* only**
  (ringtone/vibration). Whether `WebRTCManager` teardown itself is idempotent /
  race-guarded is unknown.
- `WebRTCManager` is per-call, owned by `CallViewModel`; `factory`/`eglBase` are
  private to the wrapper (Block A decision).

---

## 4. Hypotheses, ranked

**H1 — teardown aborts early (T7 regression).** `endCall()` now begins with the
Block-A screen-resource branch, before the camera block and before
`peerConnection?.close()`. If any new step can throw on a **plain never-shared
call** (touching a half-initialized screen field, a disposal-order violation, a
`setTrack` on a closing PC via the projection callback path, etc.) and the method
body is wrapped in one broad try/catch — or called from a context that swallows
exceptions — everything after the throw, **including the PC close that stops the
mic**, is skipped. No crash dump (matches Ivan's report: no crash), calls still
"pass" functionally.
*Confirmed by:* recon shows a throwable step before `close()` + a swallowing
try/catch or caller; or logcat during repro shows a caught exception.

**H2 — double-teardown race → partial teardown.** Remote `call-end`/`call-reject`
arrives on the WS/MessageBus path while the local user simultaneously ends /
finishes the activity (`onCleared`). If two threads run `endCall()`/dispose
concurrently without an idempotency guard, M144's native objects throw
(`IllegalStateException`, double-dispose) and one of the runs aborts before the
audio path is stopped. The unanswered-outgoing repro plausibly hits this: tap End →
`endCall()` + activity `finish()` → `onCleared()` runs a second teardown almost
immediately.
*Confirmed by:* recon shows two entry points into WebRTC teardown with no
`released`/`callFinished`-style guard at the `WebRTCManager` level.

**H3 — the CALLING-cancel path never reaches WebRTC teardown (possibly
pre-existing).** Enumerate every terminal branch and check each actually calls into
`WebRTCManager` teardown: caller taps End **while still ringing**, back-press during
CALLING, `call-reject` received, `call-busy`, timeout, `call-mutual-resolve` loser,
`answered-elsewhere` self-cancel, remote `call-end`, and plain `onCleared()`. The
pre-answer path is the reported repro, so it gets first scrutiny.
*Confirmed by:* a terminal branch that only sends the wire message / stops alerting
/ finishes the activity without WebRTC teardown, on a path where `onCleared` doesn't
compensate (or compensates with a *different*, weaker cleanup).

**H4 — `close()` without full dispose + M144 semantics (pre-existing since T2).**
If teardown only ever does `peerConnection?.close()` and nothing disposes the
audio source / audio track / PC / factory / ADM, the old Stream 1.1.1 build may have
stopped the recorder where M144 does not (or stops it later/never while the audio
source lives). The bisect in §6 splits this from H1/H2 cheaply.
*Confirmed by:* recon shows no `dispose()`/`release()` of audio objects anywhere +
bisect shows the leak already present at `56c1668`.

**H5 — non-WebRTC recorder.** Voice-note `MediaRecorder`/`AudioRecord` left running.
Not in the repro path; rule out with a repo grep only.

---

## 5. Recon checklist (read-only — quote code verbatim, with file:line)

### 5.1 `app/src/main/java/com/fshu/next/service/WebRTCManager.kt`
- Factory + audio module creation: is a `JavaAudioDeviceModule` built explicitly
  (and if so, is `.release()` ever called on it), or is the default ADM used? Is the
  factory strictly per-instance (no companion/static)? Where is `eglBase.release()`?
- Audio pipeline creation: `createAudioSource` / `createAudioTrack` sites; which are
  kept as fields (`audioSource`, `localAudioTrack`, audio `RtpSender`?).
- **Quote `endCall()` in full**, annotating for each step: can it throw on a plain
  never-shared call? What try/catch wraps what? Exact order relative to
  `peerConnection?.close()`. Is there any `peerConnection?.dispose()`,
  `audioSource?.dispose()`, `localAudioTrack?.dispose()`, `factory?.dispose()`
  anywhere in the class? Quote `disposeScreenResources()` too.
- Idempotency/threading: is `endCall()` guarded against a second call? Which threads
  can invoke it (main via UI, WS/MessageBus thread via remote end, `mainHandler`
  post from `MediaProjection.Callback.onStop`)?
- Grep within file: `dispose(`, `release(`, `close(`, `audioSource`, `audioTrack`,
  `JavaAudioDeviceModule`, `setAudioDeviceModule`, `videoSender`, `setTrack`.

### 5.2 `app/src/main/java/com/fshu/next/ui/call/CallViewModel.kt`
- **Quote `onCleared()` in full.** What exactly does it call on `webRTC` — the same
  `endCall()` or a different dispose path?
- Enumerate **every terminal branch** (see H3 list) → for each: does it invoke
  WebRTC teardown, only alerting/wire cleanup, or nothing? Pay first attention to
  the **caller-cancels-during-CALLING** branch (the reported repro).
- Can `endCall()`-equivalent run twice (e.g. terminal handler + `onCleared`)? What
  guard exists at this level beyond T3's alerting-only `callFinished`?
- Confirm the `webRTC` lazy is instantiated on the CALLING path before any answer
  (it must be — the offer needs the audio track), i.e. capture is live pre-answer.

### 5.3 `app/src/main/java/com/fshu/next/ui/call/CallActivity.kt`
- All `finish()` paths and `onDestroy()`: any path that finishes during CALLING
  without going through a VM terminal method (fine only if `onCleared` provably
  compensates)? Back-press behavior during CALLING?
- T15 interaction sanity check only: confirm `controlsHideJob`/reveal logic didn't
  reorder or gate any terminal call (expected no — but T15 is the most recent
  call-code change before discovery, so one look).

### 5.4 Cross-cutting greps (repo-wide, `app/src/main`)
- `AudioRecord`, `MediaRecorder` → owners besides WebRTC + voice notes?
- `WebRTCManager` references outside `ui/call` (any service/companion holding an
  instance that could outlive or double-drive the lifecycle?).
- `endCall(`, `dispose(`, `release(` across call files → build the full teardown
  call graph.

### 5.5 `FshuService.kt` (one-line confirms only)
- No audio capture in the service itself.
- `demoteFromMediaProjection()` untouched by this bug (mediaProjection FGS type is
  camera/screen-side; the mic dot is not FGS-type-driven — note only so nobody
  chases it).

### 5.6 Rule out H5
- Grep the voice-message record/stop path; confirm its recorder is released on stop
  and is untouched by the call flow.

**Deliverable of phase 1:** findings written into `T17_RECON.md` (same style as
`T7_BLOCK_D_RECON.md`), a verdict per hypothesis, and a concrete fix proposal —
then stop for sign-off. Update `PROJECT_MEMORY.md` (board + changelog, read-only
entry) per working rules.

---

## 6. Ivan-side bisect (optional, ~10 min, decisive — can run in parallel)

1. Check out **`56c1668`** (T2 closed, pre-everything-T7), build debug in Android
   Studio, install on a G60, repro the unanswered-outgoing-call case, watch the dot.
   - **Leak present** → pre-existing (H3/H4 territory; T7 innocent).
   - **Leak absent** → T7 Block A regression (H1/H2), diff space is tiny.
2. Only if present at `56c1668` and curiosity demands: a pre-T2 checkout (old Stream
   lib) splits H4 from H3. Not required for the fix.
3. While repro-ing on the current build, note **when the dot first appears**
   (CALLING screen vs connect) and whether the **camera** dot also sticks after a
   video call (§1 questions).
4. Optional precision via adb (Ivan only): `adb shell appops get com.fshu.next
   RECORD_AUDIO` immediately after teardown — an entry still marked in-use/running
   confirms the active recorder; Settings → Privacy dashboard → Microphone shows the
   usage span ending only at app kill.

---

## 7. Verification matrix (for the eventual fix — G60 pair)

After each scenario the mic (and camera, where applicable) indicator must clear
within ~2 s of teardown, without app kill:

1. Outgoing ring, never answered → caller taps End.
2. Outgoing ring → callee rejects.
3. Outgoing ring → caller back-press / closes the activity.
4. Answered audio call → caller ends. 5. Answered → callee ends.
6. Answered video call → end (camera + mic both clear).
7. Video call + screen share active → end call while sharing (Block E territory —
   at minimum must not regress further; full hardening may fold into Block E).
8. Video call + share → stop share, then end.
9. Outgoing ring → swipe app from recents (FGS keeps process; dot must still clear).
10. Emergency call variant of 1–2 (T3 paths) — alerting AND recorder both stop.
11. Regression: normal calls still connect both directions; mute/camera/flip/share
    unaffected.

---

## 8. Fix direction (sketch — do NOT implement before recon sign-off)

Whatever the recon finds, the durable shape is the T3 lesson applied to media:

- One **idempotent** `releaseAll()` in `WebRTCManager` (single `released` flag,
  main-thread-confined or synchronized), invoked from **every** terminal path and
  from `onCleared()` as the safety net — mirroring how `stopAlerting()` was wired.
- **Per-step try/catch** inside it — one failing dispose must never abort the rest.
  Order: stop capturers (screen, then camera) → detach/null sender track → dispose
  tracks → dispose sources → `peerConnection.close()` + `dispose()` → surface
  helpers/renderers → factory / ADM release / eglBase, per what actually exists.
- A `Log.d("CallTeardown", …)` line per step so Ivan's logcat proves the full
  sequence runs on every path in §7 (the crash handler can't see swallowed
  exceptions — logs are the only visibility).
- Scope: client-only → **UPDATE build**, no protocol/DB/server/install-script
  change. `PROJECT_MEMORY.md` changelog + board move on implement, as always.
- If the recon instead lands on H4 (M144 semantics, pre-existing), the same
  `releaseAll()` is still the fix — it just also closes H1–H3 for free.

---

## 9. Explicit non-goals

- No server.js work, no deploy, no SSH.
- Do not resume T7 Block E under this ticket — if the fix naturally hardens the
  end-while-sharing path, note it in the T7 park note, but Block E stays parked.
- Do not bump targetSdk/minSdk here (TS is its own card).
- T14 (emergency silence button) is unrelated — do not bundle (board note).
