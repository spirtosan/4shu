<div align="center">

<h1>4shu</h1>

<p><strong>Private, self-hosted encrypted messenger for families and small groups</strong></p>

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg?logo=android)](https://developer.android.com)
[![Node.js](https://img.shields.io/badge/Node.js-20%20LTS-brightgreen.svg?logo=node.js)](https://nodejs.org)
[![WebRTC](https://img.shields.io/badge/WebRTC-audio%2Fvideo-orange.svg)](https://webrtc.org)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-yellow.svg?logo=buy-me-a-coffee)](https://buymeacoffee.com/spirtosan)

</div>

---

4shu is a fully self-hosted messaging app that puts your family's communication on your own server — no third-party services, no data collection, no subscriptions. Traffic is indistinguishable from regular HTTPS, making it resistant to fingerprinting and blocking.

---

## Screenshots

| Chats | Chat | Call | Settings |
|-------|------|------|----------|
| *(coming soon)* | *(coming soon)* | *(coming soon)* | *(coming soon)* |

---

## Features

### Messaging
- **Text messages** — delivery and read receipts, typing indicators
- **Voice messages** — record and send audio clips with waveform display
- **File sharing** — photos, documents, and any file type (up to 50 MB)
- **Message reactions** — emoji reactions on any message
- **Edit & delete** — edit sent messages or delete for everyone
- **Shared todo lists** — real-time synchronized checklists between users

### Calls
- **Audio & video calls** — WebRTC peer-to-peer, low latency
- **Emergency calls** — override silent mode and bypass the lock screen
- **TURN relay** — automatic fallback when direct P2P is blocked

### Privacy & Security
- **End-to-end encryption** — X25519 ECDH key exchange + AES-256-GCM per message
- **App lock** — biometric or PIN protection
- **Self-hosted** — your server, your data, zero third parties
- **Encrypted transport** — WSS (WebSocket over TLS) — looks like HTTPS to any observer

### Groups & Organization
- **Group chats** — multiple participants, group avatars, admin roles
- **Invite links** — share a link to register on your server
- **Contact nicknames** — set your own display names per contact
- **Location sharing** — on-demand and emergency GPS sharing

### Platform
- **Push notifications** — optional Firebase FCM wake-up for Doze mode
- **Multi-device** — connect multiple Android devices per account
- **Admin panel** — in-app user management for admins
- **Dark & light themes** — with customizable chat backgrounds
- **Android 8.0+** — minimum SDK 26, no Google Play required

---

## Architecture

```
Android client (Kotlin, MVVM)
        │  WSS (looks like HTTPS)
        ▼
   nginx (TLS termination)
        │
        ▼
  Node.js server (ws library)
   ├── SQLite database (better-sqlite3)
   ├── coturn (TURN/STUN for WebRTC relay)
   └── Firebase Admin SDK (optional, for FCM)
```

- **Transport:** WebSocket over TLS — HTTPS port 443
- **Encryption:** X25519 ECDH → HKDF → AES-256-GCM, keys derived client-side
- **Server storage:** SQLite (messages, users, files metadata)
- **Android:** Kotlin, MVVM, Room (SQLite), OkHttp WebSocket, Stream WebRTC

---

## Requirements

### Server
- Ubuntu 22.04 or 24.04 LTS
- Root access
- A domain name with DNS pointing to your server (for TLS)
- Open ports: `443/tcp`, `80/tcp`, `3478/tcp+udp`, `49152–49999/udp`

### Android App
- Android 8.0 (API 26) or newer
- Network access to your 4shu server

---

## Self-Hosting Setup

### 1. Clone the repository onto your server

```bash
git clone https://github.com/spirtosan/4shu.git
cd 4shu
```

### 2. Run the installer

```bash
sudo bash install-fshu-next.sh
```

The interactive script will prompt you for:

| Prompt | Description |
|--------|-------------|
| Instance name | Identifier for this install (e.g. `fshu`) |
| Install directory | Where server files go (default: `/opt/fshu`) |
| Port | Internal Node.js port (default: `8083`) |
| Domain | Your domain name — enables automatic TLS via Let's Encrypt |
| WebSocket path | URL path for the WebSocket endpoint (default: `/fshu/`) |
| TURN credentials | Username and password for the TURN relay server |
| Admin account | First admin username and password |
| Firebase | Optional — enable FCM push notifications |

### 3. What gets installed

- **Node.js 20** — server runtime
- **nginx** — TLS termination and reverse proxy
- **coturn** — TURN/STUN server for WebRTC relay behind NAT
- **certbot** — automatic Let's Encrypt TLS certificate
- **4shu server** — running as a systemd service

After installation your server is live at:
```
wss://yourdomain.com/fshu/
```

### 4. Connect the Android app

1. Install the APK on your device (sideload or build from source)
2. On first launch, enter your server URL: `wss://yourdomain.com/fshu/`
3. Enter the username and password created by the admin
4. Set your encryption passphrase — everyone in the same conversation must use the same passphrase

---

## Managing Users

```bash
INSTANCE=/opt/fshu   # adjust to your install directory

node $INSTANCE/admin.js list
node $INSTANCE/admin.js add <username> <password>
node $INSTANCE/admin.js remove <username>
node $INSTANCE/admin.js reset <username> <newpassword>
node $INSTANCE/admin.js setadmin <username>
node $INSTANCE/admin.js removeadmin <username>
```

### Service management

```bash
systemctl status fshu
systemctl restart fshu
journalctl -u fshu -f
```

---

## Server Configuration

Edit `$INSTALL_DIR/data/config.json` to tune behaviour:

```json
{
  "turnUsername": "fshu",
  "turnPassword": "your-turn-password",
  "historyRetentionDays": 90,
  "fileRetentionDays": 90,
  "maxFileSizeMB": 50,
  "maxGroupSize": 500,
  "publicUrl": "https://yourdomain.com",
  "apkUrl": "https://yourdomain.com/download/app.apk"
}
```

Restart after changes: `systemctl restart fshu`

### Directory layout

```
/opt/fshu/
├── server.js               # WebSocket server
├── admin.js                # User management CLI
├── package.json
├── firebase-adminsdk.json  # Firebase credentials (optional)
├── secret.key              # Auto-generated — never share or commit
├── data/
│   ├── fshu.db             # SQLite database (messages, users, sessions)
│   └── config.json         # Runtime configuration
├── files/                  # Uploaded files (auto-deleted after fileRetentionDays)
└── avatars/                # User and group avatars
```

---

## Firebase Push Notifications (Optional)

Without Firebase the app maintains its own persistent WebSocket connection and works reliably in most conditions. Firebase is only needed to wake the app under aggressive Doze mode.

1. Create a project at [console.firebase.google.com](https://console.firebase.google.com)
2. Add an Android app with package name `com.fshu.next`
3. Download `google-services.json` → place in `app/` before building
4. Download the service account key → save as `$INSTALL_DIR/firebase-adminsdk.json`
5. Restart: `systemctl restart fshu`

---

## Building from Source

```bash
# Clone
git clone https://github.com/spirtosan/4shu.git
cd 4shu

# Open in Android Studio (requires Android SDK 34)
# or build from CLI:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

For a release build, create `local.properties` with your signing config:

```properties
storeFile=/path/to/your.jks
storePassword=yourpassword
keyAlias=youralias
keyPassword=yourpassword
```

Then: `./gradlew assembleRelease`

---

## Multiple Instances

Run `install-fshu-next.sh` again on the same server to add a second independent instance with its own users and data — the script detects the existing coturn installation and adds the new TURN credentials automatically.

```bash
# First family
sudo bash install-fshu-next.sh   # instance: family1, port: 8083, path: /family1/

# Second group
sudo bash install-fshu-next.sh   # instance: family2, port: 8084, path: /family2/
```

---

## Encryption Details

- **Key exchange:** X25519 ECDH — each device generates a keypair; public keys are exchanged via the server
- **Message key derivation:** HKDF-SHA256 over the shared secret + server-issued `appSecret`
- **Message encryption:** AES-256-GCM with a unique nonce per message
- **Passphrase:** entered locally on each device, never transmitted — used as an additional HKDF input
- **Server role:** stores and forwards only ciphertext — plaintext is never visible to the server

> All participants in a conversation must enter the same passphrase on their devices.

---

## Security Notes

- The server never sees message plaintext
- `secret.key` is auto-generated at install time — keep it private and back it up
- Passphrase is stored in Android Keystore-backed `EncryptedSharedPreferences`
- All traffic is TLS-encrypted in transit (WSS over nginx)
- Login brute-force protection built in
- Session tokens expire after 24 hours

---

## Support the Project

If 4shu is useful to you, consider buying me a coffee:

[![Buy Me a Coffee](https://img.buymeacoffee.com/button-api/?text=Buy%20me%20a%20coffee&emoji=&slug=spirtosan&button_colour=FFDD00&font_colour=000000&font_family=Cookie&outline_colour=000000&coffee_colour=ffffff)](https://buymeacoffee.com/spirtosan)

---

## License

4shu is free software, released under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0).

You are free to use, modify, and distribute it under the same terms.

---

*4shu is designed for private use on a trusted self-hosted server. It is not intended as an anonymous communication tool.*
