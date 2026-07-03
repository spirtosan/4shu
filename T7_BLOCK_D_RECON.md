# T7 Block D — Read-Only Recon

> **Read-only.** No code changed. No SSH, no live/server interaction, no Gradle/adb.
> Repo `server.js` is sha-verified == live (2026-07-03 reconciliation, see
> `PROJECT_MEMORY.md`), so quotes below are read from the repo copy only.
> Captured 2026-07-03, ahead of implementing Block D (screen-share-start /
> screen-share-stop signaling + receiver UX + the T13 cleanup, in the same
> server deploy).

---

## A. CLIENT outgoing signaling

**File:** `app/src/main/java/com/fshu/next/ui/call/CallViewModel.kt`
**Method:** the `webRTC` lazy init's `onIceCandidate` callback (lines 74–81) —
this is the pattern for sending a typed peer-to-peer call-signaling message.
Send goes straight through `WebSocketClient.send(mapOf(...))`, no wrapper/helper.

```kotlin
onIceCandidate = { candidate ->
    WebSocketClient.send(mapOf(
        "type" to "ice-candidate", "from" to me, "to" to peer,
        "sdpMid" to candidate.sdpMid,
        "sdpMLineIndex" to candidate.sdpMLineIndex,
        "candidate" to candidate.sdp
    ))
},
```

Envelope shape used by every call-signaling send in this file: a flat
`mapOf("type" to <string>, "from" to me, "to" to peer, ...payload)`. Same
shape for `call-ringing` (`FshuService.kt:1636` / `:1739`,
`WebSocketClient.send(mapOf("type" to "call-ringing", "from" to me, "to" to from))`)
and for `call-offer`/`call-emergency` (`CallViewModel.kt:116-120`).
**Block D's `screen-share-start`/`screen-share-stop` sends will follow this
exact shape**: `mapOf("type" to "screen-share-start", "from" to me, "to" to peer)`
(no extra payload needed — it's a pure state toggle).

---

## B. CLIENT incoming dispatch

**File:** `app/src/main/java/com/fshu/next/service/FshuService.kt`
**Method:** `dispatch(json: JsonObject)` (starts line 515), a `when (json.get("type")?.asString)`.

None of `call-answer`, `ice-candidate`, `call-ringing`, `ringing-ack`,
`call-mutual-resolve` have their own case in this `when` — confirmed by grep
(no matches). They all fall through to the default branch, which is the hook
point for Block D:

```kotlin
            "emergency-allow-set"     -> MessageBus.emit(json)
            else                      -> MessageBus.emit(json)
        }
    }
```
(`FshuService.kt:818-821`)

**This is where `screen-share-start`/`screen-share-stop` will hook in** — either
add explicit cases that `MessageBus.emit(json)` (to mirror `"peer-test-result"`,
`"group-error"` etc. style, self-documenting) or rely on the existing default
(functionally identical, since it already emits everything unhandled). `CallActivity`/
`CallViewModel` then need a `MessageBus` collector switching on `type` to react —
none exists yet for call-signaling types in `CallViewModel.kt` (it currently only
builds outgoing signaling; incoming SDP/ICE/ringing messages are consumed via
`CallActivity`'s intent extras and direct `webRTC`/`vm` calls, not a MessageBus
collect loop). **Block D will need to add a `MessageBus` collector in
`CallViewModel` or `CallActivity`** for the two new types — confirm exact
collection site when implementing (not found by this recon; flag for Block D
step 1).

---

## C. CLIENT remote renderer

**File:** `app/src/main/java/com/fshu/next/service/WebRTCManager.kt`
**Method:** `initVideoRenderers` (lines 71–76). **File:**
`app/src/main/java/com/fshu/next/ui/call/CallActivity.kt`
**Method:** `initVideoRenderers` (lines 449–456).

```kotlin
    fun initVideoRenderers(localView: SurfaceViewRenderer, remoteView: SurfaceViewRenderer) {
        storedLocalRenderer = localView
        storedRemoteRenderer = remoteView
        localVideoTrack?.addSink(localView)
        remoteVideoTrack?.addSink(remoteView)
    }
```
(`WebRTCManager.kt:71-76`)

```kotlin
    private fun initVideoRenderers() {
        val eglCtx = vm.getEglBaseContext()
        binding.surfaceRemote.init(eglCtx, null)
        binding.surfaceLocal.init(eglCtx, null)
        binding.surfaceLocal.setMirror(true)
        ...
        vm.initVideoRenderers(binding.surfaceLocal, binding.surfaceRemote)
    }
```
(`CallActivity.kt:449-456`, exact line for `setMirror` at 453)

**Current scaling value:** no `setScalingType` call exists anywhere in
`app/src/main` (confirmed by repo-wide grep for `ScalingType`/`VideoLayoutMeasure`
— zero matches). Both `surface_local` and `surface_remote` run on the WebRTC SDK's
built-in default (`RendererCommon.VideoLayoutMeasure`'s default, `SCALE_ASPECT_BALANCED`).
**Block D will call `binding.surfaceRemote.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)`
on share-start and revert to the implicit default (or explicitly re-set
`SCALE_ASPECT_BALANCED`) on share-stop** — first explicit scaling-type call in
the codebase, so it also fixes the "revert" side explicitly rather than relying
on absence of a call.

---

## D. CLIENT banner surface

**File:** `app/src/main/res/layout/activity_call.xml`

Cleanest insertion point: a new `TextView`, styled like the existing
`tv_emergency_banner` (lines 20-35 — full-width, colored background, top-anchored,
`visibility="gone"` by default) but constrained to sit **below `tv_status`**
(`app:layout_constraintTop_toBottomOf="@id/tv_status"`) rather than at the very
top, so it doesn't collide with the emergency banner on emergency calls and
reads naturally near the "Connected"/timer block. E.g. `tv_screen_share_banner`,
text "$peer is sharing their screen", shown/hidden by the same
`updateScreenShareUi()`-style single call site pattern Block C established for
the button state (driven by a `vm`-held boolean, never toggled optimistically).
No code written — insertion point identified only, per read-only recon scope.

---

## E. SERVER relay

**File:** `server.js`

Existing signaling relay cases (`switch (msg.type)`, starts line 1735):

```javascript
            case 'call-answer': {
                sendToAll(msg.to, msg);
                // Cancel ringing on answerer's other devices
                const myDevices = clients.get(username);
                if (myDevices) {
                    for (const [devId, devWs] of myDevices.entries()) {
                        if (devId !== ws.deviceId) {
                            send(devWs, { type: 'call-end', from: msg.to, to: username, reason: 'answered-elsewhere' });
                        }
                    }
                }
                break;
            }

            case 'ice-candidate':
            case 'call-ringing':
            case 'ringing-ack': {
                sendToAll(msg.to, msg);
                break;
            }
```
(`server.js:1924-1943`)

`sendToAll` signature:

```javascript
function sendToAll(username, data) {
    const devices = clients.get(username);
    if (!devices) return;
    for (const ws of devices.values()) send(ws, data);
}
```
(`server.js:732-736`)

Default drop case:

```javascript
            default:
                console.warn(`Unknown type: ${msg.type}`);
```
(`server.js:3012-3013`)

**Block D adds two relay cases** mirroring the `ice-candidate`/`call-ringing`/
`ringing-ack` pattern exactly:

```javascript
            case 'screen-share-start':
            case 'screen-share-stop': {
                sendToAll(msg.to, msg);
                break;
            }
```

T13 dead line (exact, for precise removal), with surrounding context:

```javascript
455	    getGroupHistory:       db.prepare('SELECT * FROM messages WHERE group_id = ? AND timestamp >= ? ORDER BY timestamp'),
456	
457	    getMemberFamilyGroups: db.prepare("SELECT 1 FROM groups g JOIN group_members gm ON g.group_id = gm.group_id WHERE gm.username = ? AND g.type = 'family' LIMIT 1"),
458	
459	    getInvite:    db.prepare('SELECT * FROM invites WHERE token = ?'),
```
(`server.js:457`, blank lines 456/458 on both sides — line 457 removes cleanly
with no dangling comma/blank-line cleanup needed since it's already isolated
between two blank lines inside the `stmt` object literal)

---

## Block D implementation targets

- **A/B** → `CallViewModel.kt` (send in the `webRTC` lazy-init style; needs a new
  `MessageBus` collector for incoming `screen-share-start`/`-stop` — collector
  site not yet found, confirm first) + `FshuService.kt` dispatch (either explicit
  cases or rely on existing `else -> MessageBus.emit(json)` default).
- **C** → `WebRTCManager.kt` / `CallActivity.kt` (first explicit `setScalingType`
  call in the codebase, both directions).
- **D** → `activity_call.xml` (new banner `TextView` below `tv_status`).
- **E** → `server.js` (two new relay cases at `:1943`-ish, plus removal of the
  T13 dead line at `:457`) — the one server deploy for this block.
