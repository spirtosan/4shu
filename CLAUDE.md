# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

Open in Android Studio. Requires Android SDK 34.

```bash
./gradlew assembleDebug          # build APK
./gradlew installDebug           # install on connected device
./gradlew test                   # unit tests
./gradlew connectedAndroidTest   # instrumented tests
```

For local/dev testing over plain WebSocket (not WSS), add a `network_security_config.xml` and set `android:networkSecurityConfig` in the manifest — `usesCleartextTraffic="false"` is enforced by default.

## Architecture

**Single-module Android app**, Kotlin, MVVM, min SDK 26.

```
app/src/main/java/com/fshu/
├── data/
│   ├── model/          Message.kt, User.kt
│   ├── local/          Room database (AppDatabase, MessageDao)
│   └── remote/         WebSocketClient (OkHttp singleton)
├── service/
│   ├── FshuService    Foreground service — owns the WS connection, restarts on disconnect
│   └── WebRTCManager   Wraps PeerConnectionFactory/PeerConnection lifecycle
├── ui/
│   ├── login/          LoginActivity — username + server URL, saved to SharedPrefs
│   ├── chat/           ChatActivity + ChatViewModel + ChatAdapter
│   └── call/           CallActivity + CallViewModel
├── util/
│   ├── MessageBus      SharedFlow singleton: service writes, activities collect
│   └── Prefs           SharedPreferences wrapper (username, server URL)
└── MainActivity        Online-users list (RecyclerView)
```

**Message flow:** `WebSocketClient` → `FshuService` handler → `MessageBus` (SharedFlow) → activity collectors. Incoming `call-offer` is intercepted by the service and launches `CallActivity` directly (bypasses MessageBus).

**WebSocket protocol** (all JSON over WSS):

| type | direction | key fields |
|---|---|---|
| `auth` | client→server | `username`, `password`, `lastSeq` (long) |
| `auth-ok` | server→client | `appSecret` (hex string, 32 bytes) — used for key derivation |
| `users` | server→client | `users: []` |
| `message` | both | `from`, `to`, `content`, `timestamp` |
| `file` | both | `from`, `to`, `filename`, `mimeType`, `data` (base64) |
| `call-offer` | both | `from`, `to`, `sdp` |
| `call-emergency` | both | `from`, `to`, `sdp` — same as call-offer but bypasses busy check; STREAM_ALARM on receiver |
| `call-answer` | both | `from`, `to`, `sdp` |
| `ice-candidate` | both | `from`, `to`, `sdpMid`, `sdpMLineIndex`, `candidate` |
| `call-end` | both | `from`, `to`, `reason` (`"ended"` \| `"disconnected"`) |
| `call-reject` | both | `from`, `to`, `reason` (`"rejected"`) |
| `call-busy` | server→client | `from`, `to` — sent to caller when callee is already in a call |
| `call-ringing` | callee→caller | `from`, `to` — sent when callee's device starts ringing |
| `ringing-ack` | caller→callee | `from`, `to` — optional acknowledgement |
| `ping` | client→server | `lastSeq` (long — highest server seq received) |
| `pong` | server→client | `serverTime` (long), `onlineUsers` ([string]) |
| `call-mutual-resolve` | server→both clients | `caller` (higher username), `callee` (lower username) — sent when both users call each other simultaneously; callee auto-accepts, caller waits |
| `set-secret-question` | client→server | `question`, `answer` — stores secret question + bcrypt hash of answer for account recovery |
| `get-secret-question` | client→server | _(no extra fields)_ — requests own secret question |
| `my-secret-question` | server→client | `question` (string or null) — own secret question |
| `secret-question-ok` | server→client | `message` — confirmation after set-secret-question |
| `secret-question-error` | server→client | `message` — error after set-secret-question |
| `set-hint` | client→server | `username`, `hint` — stores passphrase hint server-side |
| `get-hint` | client→server | `username` — requests stored hint for a user |
| `hint-response` | server→client | `username`, `hint` (string or null) |
| `list-create` | client→server | `from`, `to`, `listId`, `items:[{id,text}]` — creates list; server stores, broadcasts list-state, acks |
| `list-edit` | client→server | `from`, `to`, `listId`, `items:[{id,text,deleted?}]` — owner edits items; server merges, increments version |
| `list-check` | client→server | `from`, `to`, `listId`, `itemId`, `done` — any participant checks item by UUID; server increments version |
| `list-sync-request` | client→server | `from`, `to`, `listId` — client requests full list-state for a specific list |
| `list-state` | server→client | `listId`, `version`, `owner`, `to`, `items:[{id,text,done,checkedBy,checkedAt,deletedAt}]` — authoritative state pushed to all participants |
| `list-ack` | server→client | `listId`, `version` — sent to action initiator after list-create/list-edit/list-check |
| `ping` (enhanced) | client→server | `lastSeq`, `listVersions:{listId:version}` — server includes `outdatedLists:[listId]` in pong when server version is newer |
| `emergency-location` | both | `from`, `to`, `lat`, `lon`, `accuracy`, `timestamp`, `callId` — sent by caller alongside emergency call; server stores in emergency_locations.json, forwards/queues |
| `location-request` | both | `from`, `to`, `requestId` (UUID) — silent location request; server forwards/queues; receiver shows "Share my location" button |
| `location-response` | both | `from`, `to`, `requestId`, `lat`, `lon`, `accuracy`, `timestamp` — reply to location-request; server stores and forwards |
| `group-avatar-upload` | client→server | `groupId`, `data` (base64 JPEG ≤300KB) — owner/admin sets group avatar; server saves as `group_{groupId}.jpg`, broadcasts `group-avatar` to all members |
| `group-avatar` | server→client | `groupId`, `data` (base64 JPEG) — group avatar pushed to all members after upload or queued for offline members |
| `group-file` | binary (client→server upload); JSON (server→client fan-out) | upload: binary frame with header `{type:"group-file", from, groupId, filename, mimeType, nonce, size, tempId, messageId, timestamp}` + AES-GCM ciphertext using group key; fan-out JSON: same fields + `fileId`, `serverMsgId` |
| `set-auto-location` | client→server | `peer`, `enabled` (bool) — toggle auto-respond to location-requests from this peer |
| `get-auto-location` | client→server | _(no extra fields)_ — request current auto-location peer list |
| `auto-location-peers` | server→client | `peers: [string]` — full list of peers for whom auto-location is enabled |

**WebRTC:** `io.getstream:stream-webrtc-android` (`org.webrtc.*`). Audio-only, UNIFIED_PLAN, Google STUN. `WebRTCManager` is instantiated per-call inside `CallViewModel` and disposed in `onCleared()`.

## Key Dependencies

- `com.squareup.okhttp3:okhttp:4.12.0` — WebSocket client
- `io.getstream:stream-webrtc-android:1.1.1` — WebRTC
- `androidx.room:room-*:2.6.1` — local message persistence
- `com.google.code.gson:gson:2.10.1` — JSON parsing
- `com.google.android.gms:play-services-location:21.1.0` — FusedLocationProviderClient

## First-time Setup

On first launch, `LoginActivity` collects username and server URL (`wss://…`), stores them via `Prefs`, then starts `FshuService`. On subsequent launches it skips straight to `MainActivity`.

## Claude Code Working Rules

### Output discipline
- Be terse. No explanations unless asked.
- When editing files, show only the changed hunks, not full files.
- Confirm each step with one line: `✓ done` or `✗ failed: <reason>`.
- Never ask clarifying questions mid-task — make a decision and note it.

### Build type guidance (state this at the start of every task)
- **Update install** — no protocol changes, no DB migration, no new permissions.
  Command: `./gradlew assembleDebug` then adb install -r
- **Clean reinstall** — protocol changes, DB version bump, new permissions, or server changes.
  Command: `./gradlew assembleDebug` then adb uninstall com.fshu && adb install

### Server deploy
  ssh root@<your-server-ip>
  cp /opt/fshu5/server.js /opt/fshu5/server.js.bak && nano /opt/fshu5/server.js
  systemctl restart fshu5 && journalctl -u fshu5 -f

### DB migration rule
- Every Room schema change MUST bump DATABASE_VERSION and add a Migration object.
- State the new version number in the task summary.

### Protocol change rule
- Any new WebSocket message type must be added to the protocol table in this file.
- Mark tasks that require both server.js AND app changes with [PROTOCOL CHANGE].

### File locations (absolute paths)
- Server: /opt/fshu/server.js
- Service: app/src/main/java/com/fshu/service/FshuService.kt
- WebRTC: app/src/main/java/com/fshu/service/WebRTCManager.kt
- CallActivity: app/src/main/java/com/fshu/ui/call/CallActivity.kt
- CallViewModel: app/src/main/java/com/fshu/ui/call/CallViewModel.kt
- ChatActivity: app/src/main/java/com/fshu/ui/chat/ChatActivity.kt
- ChatViewModel: app/src/main/java/com/fshu/ui/chat/ChatViewModel.kt
- ChatAdapter: app/src/main/java/com/fshu/ui/chat/ChatAdapter.kt
- MessageDao: app/src/main/java/com/fshu/data/local/MessageDao.kt
- Message model: app/src/main/java/com/fshu/data/model/Message.kt
- AppDatabase: app/src/main/java/com/fshu/data/local/AppDatabase.kt
- WebSocketClient: app/src/main/java/com/fshu/data/remote/WebSocketClient.kt
- Prefs: app/src/main/java/com/fshu/util/Prefs.kt

### Task format
Each task starts with:
[PHASE X.Y] <name> | UPDATE or REINSTALL | [PROTOCOL CHANGE] if applicable
