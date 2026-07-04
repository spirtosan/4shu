# T2 — Newer-Device Launch Crash — FIX PLAN

> **Status: CONFIRMED — 16 KB page-size alignment.** Diagnosed device-free via
> Android Studio APK Analyzer on `app-personal-release.apk` (`com.fshu.next`,
> `0.1.0-next`, versionCode 1). This is the P0. **Do before T7 screen share.**

---

## Diagnosis (evidence)

APK Analyzer top-level: `lib` → **"Does not support 16 KB devices."**

Offending native libs on **arm64-v8a** (the arch that matters — 16 KB applies to
64-bit only; armeabi-v7a 32-bit is exempt; x86/x86_64 are emulator):

| Library | Owner | Alignment finding | Fixable by |
|---|---|---|---|
| `libjingle_peerconnection_so.so` (9.4 MB) | **WebRTC** — prebuilt third-party | 4 KB zip + 4 KB LOAD section | **Dependency bump only** — AGP cannot fix the LOAD section |
| `libfshu_native.so` (3.9 KB) | **Ours** | 4 KB zip + 4 KB LOAD section | NDK r28+ recompile (if built in-project) |
| `libdatastore_shared_counter.so` | upstream | **16 KB — already fine** | — |

**Root cause:** on a newer phone with 16 KB memory pages, a `.so` whose ELF LOAD
segments are 4 KB-aligned cannot be loaded. WebRTC inits at app startup → load
fails → instant crash on launch. Matches the symptom exactly (crashes on open, only
on newer devices, fine on the G60 / Android 12).

**Key distinction:**
- *zip alignment* — fixed by AGP 8.5.1+ at packaging (libs are uncompressed → good).
- *LOAD section alignment* — baked in at **compile time**; AGP cannot change it.
  Needs the `.so` compiled with NDK r28+ (or `-Wl,-z,max-page-size=16384`).

---

## Fix — two tracks

### A. WebRTC (`libjingle_peerconnection_so.so`) — dependency bump
- WebRTC has shipped 16 KB-aligned binaries since **M121**.
- Standard prebuilt: `io.github.webrtc-sdk:android` on Maven Central. Latest seen:
  **`144.7559.09`** (M144 ≫ M121 → aligned). Confirm newest on Maven Central.
- **Stay on the non-prefixed `org.webrtc` variant.** The `-prefixed` variant renames
  the package to `livekit.org.webrtc` and would force a code refactor.
- Risk scales with the jump from the current version → re-test calls (track B-test).

### B. `libfshu_native.so` — recompile aligned
- **First: identify it.** Documented crypto is Bouncy Castle + `javax.crypto` (pure
  Java, no `.so`), so this lib is unexplained. Claude Code: grep the repo for
  `externalNativeBuild` / `CMakeLists.txt` / a vendored `.so` to find its source.
- If built in-project → **NDK r28+** aligns by default on rebuild.
- If vendored prebuilt → rebuild from its own source with NDK r28+.

### C. Toolchain (enables A-zip + B)
- **AGP 8.5.1+** (zip-aligns uncompressed libs at packaging). Also needed later for
  `compileSdk 35` (TS) → does double duty.

---

## Ordered steps

1. Read current WebRTC coordinate + version in `app/build.gradle` (sizes the risk).
2. Bump WebRTC to latest `io.github.webrtc-sdk:android` (non-prefixed).
3. Identify + recompile `libfshu_native.so` 16 KB-aligned (NDK r28+).
4. Bump AGP to 8.5.1+.
5. Rebuild → re-run APK Analyzer → confirm **all arm64-v8a `.so` = 16 KB**, no warning.
6. **Re-test voice + video calls on the G60.** A WebRTC milestone jump can shift
   `PeerConnectionFactory` init / codec factories / audio device module — Claude Code
   patches init code if needed.
7. Proceed to **T7 screen share**, built once on the new WebRTC.

**Lanes:** steps 1–4 are Ivan in Android Studio (`build.gradle`, NDK, AGP). Step 3
investigation + step 6 code patches are Claude Code. Planning chat runs no Gradle/adb.

---

## Open questions

1. Current WebRTC dependency coordinate + version? (Determines API-jump risk.)
2. What is `libfshu_native.so`, and is it built in-project or vendored?
3. Ivan's current AGP + NDK versions?

---

## Memory note (for Claude Code on first commit of this work)

Move T2 To Do → In Progress; update the card from "suspected" to "CONFIRMED 16 KB
(libjingle_peerconnection_so.so + libfshu_native.so unaligned on arm64-v8a)"; record
the WebRTC version bump + AGP/NDK bump in the changelog with the commit.
