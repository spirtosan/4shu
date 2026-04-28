# 4shu

A private, self-hosted family messaging app for Android. Built to avoid dependency on commercial messengers that can be blocked or monitored. Traffic looks like regular HTTPS — hard to fingerprint or block.

---

## Features

- **Instant messaging** — text messages with delivery and read receipts
- **Voice & video calls** — WebRTC peer-to-peer, low latency
- **Emergency calls** — override silent mode, bypass lock screen
- **Todo lists** — shared, real-time synchronized between all users
- **Location sharing** — on-demand and emergency GPS sharing
- **End-to-end encryption** — AES-256-GCM, passphrase-based, server never sees plaintext
- **Push notifications** — FCM wake-up for offline devices (optional)
- **App lock** — biometric/PIN protection
- **Avatars & nicknames** — per-user photos and display names
- **Self-hosted** — your server, your data, no third parties
- **Dark theme** — forced dark UI, navy + orange accent

---

## Architecture

- **Server:** Node.js + WebSocket (ws library), runs on Ubuntu
- **Transport:** WSS (WebSocket over TLS) — looks like regular HTTPS
- **Signaling:** central server relays messages and WebRTC signaling
- **Calls:** WebRTC peer-to-peer after signaling, TURN relay fallback
- **Database:** none — flat JSON files on server, Room (SQLite) on Android
- **Encryption:** HKDF-derived AES-256-GCM key from passphrase + server secret
- **Android:** Kotlin, MVVM, min SDK 26 (Android 8)

---

## Requirements

### Server
- Ubuntu 24.04 LTS
- Root access
- Open ports: 443/tcp, 80/tcp, 3478/tcp+udp, 49152-49200/udp
- Domain name with DNS pointing to your server (optional but recommended for TLS)

### Android App
- Android 8.0 (API 26) or higher
- Connection to your 4shu server

---

## Server Installation

### 1. Download the installer

Clone the repository or download the release files to your server:

```bash
git clone https://github.com/yourusername/fshu.git
cd fshu
```

### 2. Run the installation script

```bash
sudo bash install.sh
```

The script will prompt you for:
- **Instance name** — identifier for this installation (e.g. `fshu`)
- **Install directory** — where server files are stored (default: `/opt/fshu`)
- **Node.js port** — internal port for the Node.js server (default: `8080`)
- **Domain name** — your server's domain (leave blank to skip TLS)
- **WebSocket path** — URL path for WebSocket endpoint (default: `/fshu/`)
- **TURN credentials** — username and auto-generated password for TURN server
- **Admin account** — first admin username and password
- **Firebase** — optional push notification support

### 3. What the script installs
- Node.js 20 LTS
- nginx (TLS termination + reverse proxy)
- coturn (TURN/STUN server for WebRTC relay)
- certbot (Let's Encrypt TLS certificate, if domain provided)
- 4shu Node.js server as a systemd service

### 4. After installation

Your server will be available at: wss://yourdomain.com/fshu/

Manage users:
```bash
node /opt/fshu/admin.js list
node /opt/fshu/admin.js add <username> <password>
node /opt/fshu/admin.js remove <username>
node /opt/fshu/admin.js setadmin <username>
```

---

## Firebase Push Notifications (Optional)

Push notifications allow the server to wake the Android app when it receives a message while in the background (Doze mode). Without Firebase, the app uses an 8-layer connection stability system that works well in most cases.

To enable Firebase:

1. Create a Firebase project at [console.firebase.google.com](https://console.firebase.google.com)
2. Add an Android app with package name `com.fshu`
3. Download `google-services.json` and place it in `app/`
4. Download the Firebase Admin SDK service account key
5. Place it at your install directory as `firebase-adminsdk.json`
6. Restart the server: `systemctl restart fshu`

---

## Android App

### Building from source

1. Clone the repository
2. Open in Android Studio
3. Add your `google-services.json` to `app/` (or use the dummy one for builds without FCM)
4. Add signing config to `local.properties`:

storeFile=path/to/your.jks
storePassword=yourpassword
keyAlias=youralias
keyPassword=yourpassword

5. Build → Generate Signed APK

### First run

1. Install the APK on your Android device
2. Grant required permissions (notifications, microphone, camera)
3. Enter your server URL: `wss://yourdomain.com/fshu/`
4. Enter your username and password (created by admin on the server)
5. Set your encryption passphrase — **all family members must use the same passphrase**

---

## Encryption

Messages are encrypted end-to-end using AES-256-GCM.

- The server generates a unique `appSecret` per user on first login
- The encryption key is derived as: `HKDF(pepper + appSecret + passphrase, salt=SHA256(userPair))`
- The passphrase is entered locally and never transmitted to the server
- The server stores and forwards only ciphertext — it cannot read messages
- Nonce is derived deterministically from `messageId` and `timestamp`

**Note:** All users sharing a conversation must enter the same passphrase on their devices.

---

## Multiple Instances

The installation script supports multiple 4shu instances on the same server:

```bash
# First instance
sudo bash install.sh  # instance: fshu, port: 8080, path: /fshu/

# Second instance
sudo bash install.sh  # instance: fshu2, port: 8081, path: /fshu2/
```

Each instance has its own users, data, and configuration. TURN credentials are automatically added to the shared coturn instance.

---

## File Structure

/opt/fshu/
├── server.js          # Main WebSocket server
├── admin.js           # User management CLI
├── package.json       # Node.js dependencies
├── firebase-adminsdk.json  # Firebase credentials (optional)
├── data/
│   ├── users.json     # User accounts and hashed passwords
│   ├── queue.json     # Offline message queue
│   ├── lists.json     # Shared todo lists
│   └── config.json    # Server configuration
├── history/           # Chat history per user pair
├── files/             # Shared files (deleted after 90 days)
└── avatars/           # User avatar photos (permanent)

---

## Configuration

Edit `/opt/fshu/data/config.json`:

```json
{
  "turnUsername": "fshu",
  "turnPassword": "your-turn-password",
  "historyRetentionDays": 365,
  "fileRetentionDays": 90,
  "maxHistoryRequestDays": 30
}
```

Restart after changes: `systemctl restart fshu`

---

## Managing the Server

```bash
# Service status
systemctl status fshu

# View logs
journalctl -u fshu -f

# Restart
systemctl restart fshu

# User management
node /opt/fshu/admin.js list
node /opt/fshu/admin.js add <username> <password>
node /opt/fshu/admin.js remove <username>
node /opt/fshu/admin.js reset <username> <newpassword>
node /opt/fshu/admin.js setadmin <username>
node /opt/fshu/admin.js removeadmin <username>
```

---

## Planned Features

- Multiple device support per user
- Windows/Electron desktop client
- Server installation documentation improvements

---

## License

MIT License — free to use, modify, and distribute.

---

## Security Notes

- The server never sees message plaintext
- Passphrase is stored only on the device in Android Keystore-backed encrypted storage
- All traffic is TLS-encrypted in transit
- TURN credentials are rotated per installation
- Brute-force protection on login attempts
- Session tokens with 24-hour TTL

---

*4shu is designed for private family use on a trusted self-hosted server. It is not intended as an anonymous communication tool.*

