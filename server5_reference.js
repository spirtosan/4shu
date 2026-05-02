'use strict';
const { WebSocketServer } = require('ws');
const Database = require('better-sqlite3');
const { execSync } = require('child_process');
const fs   = require('fs');
const path = require('path');
const crypto = require('crypto');
const bcrypt = require('bcrypt');

// ---------------------------------------------------------------------------
// Firebase (optional)
// ---------------------------------------------------------------------------

let admin = null;
try {
    const sa = require('/opt/fshu5/firebase-adminsdk.json');
    if (sa.private_key && sa.project_id) {
        admin = require('firebase-admin');
        admin.initializeApp({ credential: admin.credential.cert(sa) });
        console.log('Firebase initialized — FCM push enabled');
    } else {
        console.log('Firebase credentials incomplete — FCM push disabled');
    }
} catch {
    console.log('firebase-adminsdk.json not found — FCM push disabled');
}

async function sendFcmWakeup(fcmToken) {
    if (!admin || !fcmToken) return;
    try {
        await admin.messaging().send({
            token: fcmToken,
            data: { wake: '1' },
            android: { priority: 'high', ttl: 60000 }
        });
        console.log('  FCM wake-up sent');
    } catch (err) {
        console.warn('  FCM send failed:', err.message);
    }
}

// ---------------------------------------------------------------------------
// Paths
// ---------------------------------------------------------------------------

const PORT        = process.env.PORT || 8083;
const BASE_DIR    = '/opt/fshu5';
const DB_PATH     = path.join(BASE_DIR, 'data', 'fshu.db');
const FILES_DIR   = path.join(BASE_DIR, 'files');
const AVATARS_DIR = path.join(BASE_DIR, 'avatars');
const CONFIG_FILE = path.join(BASE_DIR, 'config.json');
const SECRET_FILE = path.join(BASE_DIR, 'secret.key');

fs.mkdirSync(FILES_DIR,   { recursive: true });
fs.mkdirSync(AVATARS_DIR, { recursive: true });

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

const defaultConfig = {
    historyRetentionDays:  90,
    fileRetentionDays:     90,
    maxHistoryRequestDays: 90,
    turnUsername:          'fshu',
    turnPassword:          '',
    features: {
        multiDevice:      true,
        ecdh:             false,
        groups:           false,
        voiceMessages:    false,
        reactions:        false,
        editMessages:     false,
        deleteForAll:     false,
        typingIndicators: false,
        inviteLinks:      false,
        userSearch:       false
    },
    limits: {
        maxFileSizeMB:           50,
        maxVoiceDurationSeconds: 300,
        maxGroupSize:            500,
        fileRetentionDays:       90,
        historyRetentionDays:    90,
        localCacheRetentionDays: 30
    }
};

let config = JSON.parse(JSON.stringify(defaultConfig));
try {
    const loaded = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
    Object.assign(config, loaded);
    if (loaded.features) Object.assign(config.features, loaded.features);
    if (loaded.limits)   Object.assign(config.limits,   loaded.limits);
} catch {
    fs.writeFileSync(CONFIG_FILE, JSON.stringify(defaultConfig, null, 2));
}

const FILE_MAX_AGE_MS    = config.fileRetentionDays    * 24 * 60 * 60 * 1000;
const HISTORY_MAX_AGE_MS = config.historyRetentionDays * 24 * 60 * 60 * 1000;
const TURN_USERNAME = config.turnUsername || 'fshu';
const TURN_PASSWORD = config.turnPassword || '';

// App secret
let sharedAppSecret;
try {
    sharedAppSecret = fs.readFileSync(SECRET_FILE, 'utf8').trim();
    if (sharedAppSecret.length < 32) throw new Error('short');
} catch {
    sharedAppSecret = crypto.randomBytes(32).toString('hex');
    fs.writeFileSync(SECRET_FILE, sharedAppSecret);
}

// ---------------------------------------------------------------------------
// Database
// ---------------------------------------------------------------------------

const db = new Database(DB_PATH);
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

db.exec(`
CREATE TABLE IF NOT EXISTS users (
  username        TEXT PRIMARY KEY,
  password_hash   TEXT NOT NULL,
  admin           INTEGER DEFAULT 0,
  nickname        TEXT,
  fcm_token       TEXT,
  avatar_path     TEXT,
  last_seen       INTEGER,
  created_at      INTEGER NOT NULL,
  trust_level     TEXT DEFAULT 'contact',
  public_key      TEXT,
  status          TEXT DEFAULT 'active'
);

CREATE TABLE IF NOT EXISTS sessions (
  token           TEXT PRIMARY KEY,
  username        TEXT NOT NULL,
  device_id       TEXT NOT NULL DEFAULT '',
  created_at      INTEGER NOT NULL,
  last_seen       INTEGER
);
CREATE INDEX IF NOT EXISTS idx_sessions_username ON sessions(username);

CREATE TABLE IF NOT EXISTS devices (
  username        TEXT NOT NULL,
  device_id       TEXT NOT NULL,
  device_name     TEXT,
  fcm_token       TEXT,
  last_seen       INTEGER,
  PRIMARY KEY (username, device_id)
);

CREATE TABLE IF NOT EXISTS queue (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  username        TEXT NOT NULL,
  envelope        TEXT NOT NULL,
  created_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_queue_username ON queue(username);

CREATE TABLE IF NOT EXISTS messages (
  message_id       TEXT PRIMARY KEY,
  from_user        TEXT NOT NULL,
  to_user          TEXT,
  group_id         TEXT,
  content          TEXT,
  timestamp        INTEGER NOT NULL,
  type             TEXT NOT NULL DEFAULT 'message',
  file_id          TEXT,
  reply_to_id      TEXT,
  reply_to_sender  TEXT,
  reply_to_content TEXT,
  edited_at        INTEGER,
  deleted_for_all  INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_messages_pair  ON messages(from_user, to_user, timestamp);
CREATE INDEX IF NOT EXISTS idx_messages_group ON messages(group_id,  timestamp);

CREATE TABLE IF NOT EXISTS files (
  file_id         TEXT PRIMARY KEY,
  uploader        TEXT NOT NULL,
  filename        TEXT NOT NULL,
  mime_type       TEXT,
  file_path       TEXT NOT NULL,
  size_bytes      INTEGER,
  created_at      INTEGER NOT NULL,
  expires_at      INTEGER
);

CREATE TABLE IF NOT EXISTS reactions (
  message_id      TEXT NOT NULL,
  from_user       TEXT NOT NULL,
  emoji           TEXT NOT NULL,
  timestamp       INTEGER NOT NULL,
  PRIMARY KEY (message_id, from_user)
);

CREATE TABLE IF NOT EXISTS groups (
  group_id        TEXT PRIMARY KEY,
  name            TEXT NOT NULL,
  owner           TEXT NOT NULL,
  type            TEXT DEFAULT 'group',
  created_at      INTEGER NOT NULL,
  avatar_path     TEXT
);

CREATE TABLE IF NOT EXISTS group_members (
  group_id              TEXT NOT NULL,
  username              TEXT NOT NULL,
  role                  TEXT DEFAULT 'member',
  joined_at             INTEGER NOT NULL,
  encrypted_group_key   TEXT,
  PRIMARY KEY (group_id, username)
);

CREATE TABLE IF NOT EXISTS contact_nicknames (
  owner           TEXT NOT NULL,
  contact         TEXT NOT NULL,
  nickname        TEXT NOT NULL,
  PRIMARY KEY (owner, contact)
);

CREATE TABLE IF NOT EXISTS lists (
  list_id         TEXT PRIMARY KEY,
  owner           TEXT NOT NULL,
  peer            TEXT,
  group_id        TEXT,
  version         INTEGER DEFAULT 1,
  created_at      INTEGER NOT NULL,
  message_id      TEXT
);

CREATE TABLE IF NOT EXISTS list_items (
  item_id         TEXT NOT NULL,
  list_id         TEXT NOT NULL,
  text            TEXT NOT NULL,
  done            INTEGER DEFAULT 0,
  checked_by      TEXT,
  checked_at      INTEGER,
  deleted_at      INTEGER,
  sort_order      INTEGER,
  PRIMARY KEY (item_id, list_id)
);

CREATE TABLE IF NOT EXISTS invites (
  token           TEXT PRIMARY KEY,
  created_by      TEXT NOT NULL,
  used_by         TEXT,
  expires_at      INTEGER,
  used_at         INTEGER
);
`);

// Phase 1g migration: add nonce column to files if not present
try { db.exec('ALTER TABLE files ADD COLUMN nonce TEXT'); } catch {}
try { db.exec('ALTER TABLE files ADD COLUMN meta_json TEXT'); } catch {}

// ---------------------------------------------------------------------------
// Prepared statements
// ---------------------------------------------------------------------------

const stmt = {
    getUser:             db.prepare('SELECT * FROM users WHERE username = ?'),
    getAllUsers:         db.prepare("SELECT * FROM users WHERE status != 'deleted' ORDER BY username"),
    insertUser:         db.prepare('INSERT INTO users (username, password_hash, admin, created_at) VALUES (?, ?, ?, ?)'),
    deleteUser:         db.prepare('DELETE FROM users WHERE username = ?'),
    updateNickname:     db.prepare('UPDATE users SET nickname = ? WHERE username = ?'),
    getContactNicknames:   db.prepare('SELECT contact, nickname FROM contact_nicknames WHERE owner = ?'),
    setContactNickname:    db.prepare('INSERT INTO contact_nicknames (owner, contact, nickname) VALUES (?, ?, ?) ON CONFLICT(owner, contact) DO UPDATE SET nickname=excluded.nickname'),
    deleteContactNickname: db.prepare('DELETE FROM contact_nicknames WHERE owner = ? AND contact = ?'),
    updateFcmToken:     db.prepare('UPDATE users SET fcm_token = ? WHERE username = ?'),
    updateLastSeen:     db.prepare('UPDATE users SET last_seen = ? WHERE username = ?'),
    updatePassword:     db.prepare('UPDATE users SET password_hash = ? WHERE username = ?'),
    updatePublicKey:    db.prepare('UPDATE users SET public_key = ? WHERE username = ?'),

    getSession:          db.prepare('SELECT * FROM sessions WHERE token = ?'),
    insertSession:       db.prepare('INSERT INTO sessions (token, username, device_id, created_at) VALUES (?, ?, ?, ?)'),
    deleteSession:       db.prepare('DELETE FROM sessions WHERE token = ?'),
    deleteUserSessions:  db.prepare('DELETE FROM sessions WHERE username = ?'),
    deleteDeviceSession: db.prepare('DELETE FROM sessions WHERE username = ? AND device_id = ?'),

    enqueueRow:         db.prepare('INSERT INTO queue (username, envelope, created_at) VALUES (?, ?, ?)'),
    getQueue:           db.prepare('SELECT * FROM queue WHERE username = ? ORDER BY id'),
    deleteQueue:        db.prepare('DELETE FROM queue WHERE username = ?'),
    deleteOldQueue:     db.prepare('DELETE FROM queue WHERE created_at < ?'),
    getOldQueue:        db.prepare('SELECT envelope FROM queue WHERE created_at < ?'),

    insertMessage:      db.prepare(`
        INSERT OR IGNORE INTO messages
          (message_id, from_user, to_user, content, timestamp, type, reply_to_id, reply_to_sender, reply_to_content)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`),
    getHistory:         db.prepare(`
        SELECT * FROM messages
        WHERE ((from_user = ? AND to_user = ?) OR (from_user = ? AND to_user = ?))
          AND timestamp >= ?
        ORDER BY timestamp`),
    deleteOldMessages:  db.prepare('DELETE FROM messages WHERE timestamp < ?'),
    getMessage:         db.prepare('SELECT * FROM messages WHERE message_id = ?'),
    setDeletedForAll:   db.prepare('UPDATE messages SET deleted_for_all = 1 WHERE message_id = ?'),
    editMessage:        db.prepare('UPDATE messages SET content = ?, edited_at = ? WHERE message_id = ? AND from_user = ?'),

    insertFile:         db.prepare(`
        INSERT INTO files (file_id, uploader, filename, mime_type, file_path, size_bytes, nonce, created_at, expires_at, meta_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`),
    getFile:            db.prepare("SELECT * FROM files WHERE file_id = ?"),
    insertFileMessage:  db.prepare(`
        INSERT OR IGNORE INTO messages (message_id, from_user, to_user, content, timestamp, type, file_id)
        VALUES (?, ?, ?, NULL, ?, 'file', ?)`),
    getExpiredFiles:    db.prepare('SELECT file_path FROM files WHERE expires_at IS NOT NULL AND expires_at < ?'),
    deleteExpiredFiles: db.prepare('DELETE FROM files WHERE expires_at IS NOT NULL AND expires_at < ?'),
    getAllFilePaths:     db.prepare('SELECT file_path FROM files'),

    getList:            db.prepare('SELECT * FROM lists WHERE list_id = ?'),
    getListVersion:     db.prepare('SELECT version FROM lists WHERE list_id = ?'),
    insertList:         db.prepare('INSERT OR IGNORE INTO lists (list_id, owner, peer, version, created_at, message_id) VALUES (?, ?, ?, 1, ?, ?)'),
    bumpListVersion:    db.prepare('UPDATE lists SET version = version + 1 WHERE list_id = ?'),
    getListItems:       db.prepare('SELECT * FROM list_items WHERE list_id = ? ORDER BY COALESCE(sort_order, 0), item_id'),
    upsertListItem:     db.prepare(`
        INSERT INTO list_items (item_id, list_id, text, done, checked_by, checked_at, deleted_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(item_id, list_id) DO UPDATE SET
          text=excluded.text, done=excluded.done,
          checked_by=excluded.checked_by, checked_at=excluded.checked_at,
          deleted_at=excluded.deleted_at`),
    markItemDeleted:    db.prepare('UPDATE list_items SET deleted_at = ? WHERE item_id = ? AND list_id = ?'),
    getRecentLists:     db.prepare('SELECT * FROM lists WHERE (owner = ? OR peer = ?) AND created_at > ?'),

    upsertReaction: db.prepare(`
        INSERT INTO reactions (message_id, from_user, emoji, timestamp)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(message_id, from_user) DO UPDATE SET emoji=excluded.emoji, timestamp=excluded.timestamp`),
    deleteReaction: db.prepare('DELETE FROM reactions WHERE message_id = ? AND from_user = ?'),
    getReactions:   db.prepare('SELECT from_user, emoji FROM reactions WHERE message_id = ?'),
};

// ---------------------------------------------------------------------------
// Brute-force (in-memory — acceptable for single process)
// ---------------------------------------------------------------------------

const failedAttempts = new Map();
const MAX_ATTEMPTS   = 5;
const LOCKOUT_MS     = 15 * 60 * 1000;

function isLocked(key) {
    const e = failedAttempts.get(key);
    if (!e) return false;
    if (Date.now() < e.lockUntil) return true;
    failedAttempts.delete(key);
    return false;
}
function recordFailure(key) {
    const e = failedAttempts.get(key) || { count: 0, lockUntil: 0 };
    e.count++;
    if (e.count >= MAX_ATTEMPTS) { e.lockUntil = Date.now() + LOCKOUT_MS; e.count = 0; }
    failedAttempts.set(key, e);
}
function clearFailures(key) { failedAttempts.delete(key); }

// ---------------------------------------------------------------------------
// Clients map: Map<username, Map<deviceId, WebSocket>>
// ---------------------------------------------------------------------------

const clients          = new Map();
const pendingPeerTests = new Map();
const activeCalls      = new Map();

// ---------------------------------------------------------------------------
// WebSocket send helpers
// ---------------------------------------------------------------------------

function send(ws, data) {
    if (ws && ws.readyState === ws.OPEN) ws.send(JSON.stringify(data));
}

function sendToAll(username, data) {
    const devices = clients.get(username);
    if (!devices) return;
    for (const ws of devices.values()) send(ws, data);
}

function isOnline(username) {
    const devices = clients.get(username);
    return !!(devices && devices.size > 0);
}

function getAnySocket(username) {
    const devices = clients.get(username);
    if (!devices || devices.size === 0) return null;
    return devices.values().next().value;
}

// ---------------------------------------------------------------------------
// User broadcast
// ---------------------------------------------------------------------------

function broadcastAllUsers() {
    const allUsers = stmt.getAllUsers.all().map(u => ({
        username:  u.username,
        online:    isOnline(u.username),
        lastSeen:  isOnline(u.username) ? null : (u.last_seen || null),
        nickname:  u.nickname || null,
        publicKey: u.public_key || null,
    }));
    for (const userDevices of clients.values()) {
        for (const ws of userDevices.values()) {
            send(ws, { type: 'users', users: allUsers });
        }
    }
}

// ---------------------------------------------------------------------------
// Avatar helpers
// ---------------------------------------------------------------------------

function sendAllAvatars(ws) {
    try {
        for (const file of fs.readdirSync(AVATARS_DIR)) {
            const username = path.basename(file, path.extname(file));
            try {
                const data = fs.readFileSync(path.join(AVATARS_DIR, file)).toString('base64');
                send(ws, { type: 'avatar-data', username, data });
            } catch {}
        }
    } catch {}
}

// ---------------------------------------------------------------------------
// Queue helpers
// ---------------------------------------------------------------------------

function enqueue(username, envelope) {
    stmt.enqueueRow.run(username, JSON.stringify(envelope), Date.now());
}

function flushQueue(username, ws) {
    const rows = stmt.getQueue.all(username);
    if (!rows.length) return;
    for (const row of rows) {
        try { send(ws, JSON.parse(row.envelope)); } catch {}
    }
    stmt.deleteQueue.run(username);
}

// ---------------------------------------------------------------------------
// Message history helpers
// ---------------------------------------------------------------------------

function appendMessage(rec) {
    try {
        stmt.insertMessage.run(
            String(rec.messageId ?? crypto.randomUUID()),
            rec.from, rec.to,
            rec.content ?? null,
            rec.timestamp ?? Date.now(),
            rec.type ?? 'message',
            rec.replyToId ?? null,
            rec.replyToSender ?? null,
            rec.replyToContent ?? null
        );
    } catch {}
}

function readHistory(a, b, since) {
    return stmt.getHistory.all(a, b, a, b, since).map(r => ({
        from:           r.from_user,
        to:             r.to_user,
        content:        r.content,
        messageId:      r.message_id,
        timestamp:      r.timestamp,
        type:           r.type,
        replyToId:      r.reply_to_id      ?? null,
        replyToSender:  r.reply_to_sender  ?? null,
        replyToContent: r.reply_to_content ?? null,
    }));
}

// ---------------------------------------------------------------------------
// List helpers
// ---------------------------------------------------------------------------

function getListWithItems(listId) {
    const list = stmt.getList.get(listId);
    if (!list) return null;
    list.items = stmt.getListItems.all(listId).map(r => ({
        id:        r.item_id,
        text:      r.text,
        done:      r.done === 1,
        checkedBy: r.checked_by  ?? null,
        checkedAt: r.checked_at  ?? null,
        deletedAt: r.deleted_at  ?? null,
    }));
    return list;
}

function sendListState(ws, list, listId) {
    send(ws, {
        type:      'list-state',
        listId,
        version:   list.version,
        owner:     list.owner,
        to:        list.peer,
        items:     list.items,
        messageId: list.message_id ?? null,
    });
}

function broadcastListState(list, listId) {
    const msg = {
        type:      'list-state',
        listId,
        version:   list.version,
        owner:     list.owner,
        to:        list.peer,
        items:     list.items,
        messageId: list.message_id ?? null,
    };
    sendToAll(list.owner, msg);
    if (list.peer) sendToAll(list.peer, msg);
    const env = { ...msg, timestamp: Date.now() };
    if (!isOnline(list.owner)) enqueue(list.owner, env);
    if (list.peer && !isOnline(list.peer)) enqueue(list.peer, env);
}

// ---------------------------------------------------------------------------
// File helpers
// ---------------------------------------------------------------------------

function saveFileToDisk(base64Data, filename) {
    const ext    = path.extname(filename) || '';
    const fileId = crypto.randomUUID();
    const fp     = path.join(FILES_DIR, fileId + ext);
    fs.writeFileSync(fp, Buffer.from(base64Data, 'base64'));
    return { fileId, filePath: fp };
}

// ---------------------------------------------------------------------------
// Maintenance
// ---------------------------------------------------------------------------

function runMaintenance() {
    const now = Date.now();

    // Expired queue entries — try to delete their disk files
    const oldQueueRows = stmt.getOldQueue.all(now - FILE_MAX_AGE_MS);
    for (const row of oldQueueRows) {
        try { const e = JSON.parse(row.envelope); if (e.filePath) fs.unlink(e.filePath, () => {}); } catch {}
    }
    stmt.deleteOldQueue.run(now - FILE_MAX_AGE_MS);

    // Old messages
    stmt.deleteOldMessages.run(now - HISTORY_MAX_AGE_MS);

    // Expired tracked files
    for (const row of stmt.getExpiredFiles.all(now)) {
        try { fs.unlink(row.file_path, () => {}); } catch {}
    }
    stmt.deleteExpiredFiles.run(now);

    // Orphan disk files not in DB and older than retention window
    try {
        const known = new Set(stmt.getAllFilePaths.all().map(r => r.file_path));
        const inQueue = new Set(
            db.prepare('SELECT envelope FROM queue').all()
                .map(r => { try { return JSON.parse(r.envelope).filePath; } catch { return null; } })
                .filter(Boolean)
        );
        for (const f of fs.readdirSync(FILES_DIR)) {
            const fp = path.join(FILES_DIR, f);
            try {
                const st = fs.statSync(fp);
                if (!known.has(fp) && !inQueue.has(fp) && now - st.mtimeMs > FILE_MAX_AGE_MS) {
                    fs.unlink(fp, () => {});
                }
            } catch {}
        }
    } catch {}
}

runMaintenance();
setInterval(runMaintenance, 6 * 60 * 60 * 1000);

// ---------------------------------------------------------------------------
// FCM keepalive — checks all devices per user, wakes if all are silent
// ---------------------------------------------------------------------------

const FCM_KEEPALIVE_INTERVAL_MS  = 3 * 60 * 1000;
const FCM_KEEPALIVE_THRESHOLD_MS = 4 * 60 * 1000;

setInterval(async () => {
    const now = Date.now();
    for (const [uname, userDevices] of clients.entries()) {
        let maxPing = 0;
        for (const devWs of userDevices.values()) {
            if (devWs.lastPingAt && devWs.lastPingAt > maxPing) maxPing = devWs.lastPingAt;
        }
        if (maxPing && now - maxPing > FCM_KEEPALIVE_THRESHOLD_MS) {
            const user = stmt.getUser.get(uname);
            if (user?.fcm_token) {
                console.log(`  FCM keepalive → ${uname} (silent ${Math.round((now - maxPing) / 1000)}s)`);
                await sendFcmWakeup(user.fcm_token);
            }
        }
    }
}, FCM_KEEPALIVE_INTERVAL_MS);

// ---------------------------------------------------------------------------
// WebSocket server
// ---------------------------------------------------------------------------


// ---------------------------------------------------------------------------
// Binary file upload handler (Phase 1g)
// ---------------------------------------------------------------------------

function handleBinaryUpload(ws, raw, fromUser) {
    try {
        if (raw.length < 4) return;
        const headerLen = raw.readUInt32BE(0);
        if (headerLen <= 0 || 4 + headerLen > raw.length) return;
        const header = JSON.parse(raw.slice(4, 4 + headerLen).toString('utf8'));
        const encBytes = raw.slice(4 + headerLen);

        const { tempId, from, to, filename, mimeType, nonce, messageId: senderRoomId, timestamp } = header;
        if (!from || !to || !filename) return;
        if (from !== fromUser) return;

        const maxBytes = (config.limits?.maxFileSizeMB ?? 50) * 1024 * 1024;
        if (encBytes.length > maxBytes) {
            send(ws, { type: 'file-error', tempId, reason: 'too-large' });
            return;
        }

        const fileId      = crypto.randomUUID();
        const serverMsgId = crypto.randomUUID();
        const ext         = path.extname(filename);
        const safeExt     = /^.[a-zA-Z0-9]{1,8}$/.test(ext) ? ext : '';
        const filePath    = path.join(FILES_DIR, fileId + safeExt);
        const ts          = timestamp ?? Date.now();

        const msgType  = header.type || 'file';
        const metaJson = (msgType === 'voice')
            ? JSON.stringify({ type: msgType, duration: header.duration ?? 0, waveform: header.waveform ?? '' })
            : null;

        fs.writeFileSync(filePath, encBytes);
        stmt.insertFile.run(fileId, from, filename, mimeType || null, filePath, encBytes.length, nonce || null, ts, ts + FILE_MAX_AGE_MS, metaJson);
        stmt.insertFileMessage.run(serverMsgId, from, to, ts, fileId);

        send(ws, { type: 'ack', tempId: tempId ?? null, messageId: senderRoomId ?? null, fileId, serverMsgId, timestamp: ts });

        const meta = {
            type: msgType, from, to, filename,
            mimeType: mimeType || null,
            size: encBytes.length,
            fileId,
            messageId: senderRoomId ?? null,
            serverMsgId,
            timestamp: ts,
            ...(msgType === 'voice' && { duration: header.duration ?? 0, waveform: header.waveform ?? '' })
        };
        if (isOnline(to)) {
            sendToAll(to, meta);
        } else {
            enqueue(to, meta);
            const toUser = stmt.getUser.get(to);
            if (toUser?.fcm_token) sendFcmWakeup(toUser.fcm_token);
        }
        console.log(`  binary file "${filename}" (${encBytes.length}B) ${from}→${to}`);
    } catch (err) {
        console.error('Binary upload error:', err.message);
    }
}

const wss = new WebSocketServer({ port: PORT });

const WS_HEARTBEAT_INTERVAL = 30_000;
setInterval(() => {
    wss.clients.forEach(ws => {
        if (ws.isAlive === false) { console.log('  heartbeat: terminating zombie socket'); ws.terminate(); return; }
        ws.isAlive = false;
        ws.ping();
    });
}, WS_HEARTBEAT_INTERVAL);

const SESSION_TTL = 24 * 60 * 60 * 1000;

wss.on('connection', (ws, req) => {
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });
    let username      = null;
    let authenticated = false;
    const ip = req.socket.remoteAddress || 'unknown';

    ws.on('message', (raw, isBinary) => {
        if (isBinary) {
            if (authenticated) handleBinaryUpload(ws, raw, username);
            return;
        }
        let msg;
        try { msg = JSON.parse(raw); } catch { return; }

        // ---------------------------------------------------------------
        // Pre-auth
        // ---------------------------------------------------------------
        if (!authenticated) {
            if (msg.type === 'resume') {
                const token = msg.sessionToken || '';
                const entry = stmt.getSession.get(token);
                if (!entry || Date.now() - entry.created_at > SESSION_TTL) {
                    stmt.deleteSession.run(token);
                    send(ws, { type: 'resume-error', reason: 'invalid' });
                    return;
                }
                const user = stmt.getUser.get(entry.username);
                if (!user) {
                    stmt.deleteSession.run(token);
                    send(ws, { type: 'resume-error', reason: 'invalid' });
                    return;
                }
                authenticated = true;
                username = entry.username;
                const deviceId = (msg.deviceId || entry.device_id || '').trim() || 'default';
                ws.deviceId = deviceId;
                let userDevices = clients.get(username);
                if (!userDevices) { userDevices = new Map(); clients.set(username, userDevices); }
                const stale = userDevices.get(deviceId);
                if (stale && stale !== ws) { try { stale.terminate(); } catch {} }
                userDevices.set(deviceId, ws);
                const contactNicknames = stmt.getContactNicknames.all(username);
                send(ws, { type: 'auth-ok', appSecret: sharedAppSecret, admin: user.admin === 1, sessionToken: token, features: config.features, turnUsername: TURN_USERNAME, turnPassword: TURN_PASSWORD, publicKey: user.public_key || null, contactNicknames });
                sendAllAvatars(ws);
                console.log(`~ ${username}/${deviceId} resumed (${clients.size} users online)`);
                broadcastAllUsers();
                flushQueue(username, ws);
                return;
            }

            if (msg.type !== 'auth') { ws.close(); return; }

            const u = (msg.username || '').trim().toLowerCase();
            const p = msg.password || '';

            if (isLocked(u) || isLocked(ip)) {
                send(ws, { type: 'auth-error', message: 'Invalid credentials' });
                ws.close();
                return;
            }

            const user = stmt.getUser.get(u);
            if (!u || !user || !bcrypt.compareSync(p, user.password_hash)) {
                if (u && user) recordFailure(u);
                recordFailure(ip);
                send(ws, { type: 'auth-error', message: 'Invalid credentials' });
                ws.close();
                return;
            }

            clearFailures(u);
            clearFailures(ip);
            authenticated = true;
            username = u;
            const deviceId = (msg.deviceId || '').trim() || 'default';
            ws.deviceId = deviceId;
            let userDevices = clients.get(username);
            if (!userDevices) { userDevices = new Map(); clients.set(username, userDevices); }
            const stale = userDevices.get(deviceId);
            if (stale && stale !== ws) { try { stale.terminate(); } catch {} }
            userDevices.set(deviceId, ws);
            stmt.deleteDeviceSession.run(username, deviceId);
            const token = crypto.randomBytes(32).toString('hex');
            stmt.insertSession.run(token, username, deviceId, Date.now());
            const contactNicknames = stmt.getContactNicknames.all(username);
            send(ws, { type: 'auth-ok', appSecret: sharedAppSecret, admin: user.admin === 1, sessionToken: token, features: config.features, turnUsername: TURN_USERNAME, turnPassword: TURN_PASSWORD, publicKey: user.public_key || null, contactNicknames });
            sendAllAvatars(ws);
            console.log(`+ ${username}/${deviceId} (${clients.size} users online)`);
            broadcastAllUsers();
            flushQueue(username, ws);
            return;
        }

        // ---------------------------------------------------------------
        // Authenticated message handling
        // ---------------------------------------------------------------
        switch (msg.type) {

            case 'message': {
                if (msg.messageId != null) send(ws, { type: 'ack', messageId: msg.messageId });
                const ts = msg.timestamp ?? Date.now();
                appendMessage({ messageId: msg.messageId, from: msg.from, to: msg.to, content: msg.content, timestamp: ts, type: 'message', replyToId: msg.replyToId ?? null, replyToSender: msg.replyToSender ?? null, replyToContent: msg.replyToContent ?? null });
                if (isOnline(msg.to)) {
                    sendToAll(msg.to, msg);
                } else {
                    enqueue(msg.to, { type: 'message', from: msg.from, to: msg.to, content: msg.content, messageId: msg.messageId, timestamp: ts });
                    console.log(`  queued message for offline ${msg.to}`);
                    const toUser = stmt.getUser.get(msg.to);
                    if (toUser?.fcm_token) sendFcmWakeup(toUser.fcm_token);
                }
                break;
            }

            case 'file': {
                // Legacy base64 -- superseded by binary upload (Phase 1g)
                break;
            }

            case 'typing': {
                if (msg.to && isOnline(msg.to)) sendToAll(msg.to, { type: 'typing', from: username });
                break;
            }

            case 'edit': {
                const { messageId, newContent } = msg;
                if (!messageId || !newContent) { console.log('  edit: missing fields, ignored'); break; }
                const record = stmt.getMessage.get(String(messageId));
                if (!record) { console.log(`  edit: record not found for message_id=${messageId}`); break; }
                if (record.from_user !== username) { console.log(`  edit: ${username} is not sender, rejected`); break; }
                const editedAt = Date.now();
                stmt.editMessage.run(newContent, editedAt, String(messageId), username);
                const notice = { type: 'edited', messageId, newContent, editedAt, from: record.from_user, to: record.to_user };
                sendToAll(record.from_user, notice);
                sendToAll(record.to_user, notice);
                console.log(`  edit: updated and broadcast to ${record.from_user} and ${record.to_user}`);
                break;
            }

            case 'react': {
                const { messageId, emoji } = msg;
                if (!messageId) { console.log('  react: missing messageId, ignored'); break; }
                const msgId = String(messageId);
                const record = stmt.getMessage.get(msgId);
                if (!record) { console.log(`  react: record not found for message_id=${msgId}`); break; }
                if (emoji) {
                    stmt.upsertReaction.run(msgId, username, emoji, Date.now());
                } else {
                    stmt.deleteReaction.run(msgId, username);
                }
                const reactions = stmt.getReactions.all(msgId).map(r => ({ from: r.from_user, emoji: r.emoji }));
                const notice = { type: 'reactions', messageId, reactions };
                sendToAll(record.from_user, notice);
                sendToAll(record.to_user, notice);
                console.log(`  react: broadcast to ${record.from_user} and ${record.to_user}, count=${reactions.length}`);
                break;
            }

            case 'delete': {
                const { messageId, forAll } = msg;
                console.log(`  delete request from ${username} for messageId ${messageId}`);
                if (!messageId || !forAll) { console.log('  delete: missing messageId or forAll, ignored'); break; }
                const record = stmt.getMessage.get(String(messageId));
                if (!record) { console.log(`  delete: record not found for message_id=${messageId}`); break; }
                console.log(`  delete: record found ${record.from_user} → ${record.to_user}`);
                stmt.setDeletedForAll.run(String(messageId));
                const notice = { type: 'deleted', messageId, from: record.from_user, to: record.to_user, deletedAt: Date.now() };
                sendToAll(record.from_user, notice);
                sendToAll(record.to_user, notice);
                console.log(`  delete: deleted and broadcast to ${record.from_user} and ${record.to_user}`);
                break;
            }

            case 'file-request': {
                const { fileId } = msg;
                if (!fileId) break;
                const file = stmt.getFile.get(fileId);
                if (!file) { send(ws, { type: 'file-error', fileId, reason: 'not-found' }); break; }
                try {
                    const encBytes  = fs.readFileSync(file.file_path);
                    const fileMeta  = file.meta_json ? JSON.parse(file.meta_json) : {};
                    const hdrJson   = JSON.stringify({
                        fileId,
                        from:      file.uploader,
                        filename:  file.filename,
                        mimeType:  file.mime_type || 'application/octet-stream',
                        nonce:     file.nonce,
                        timestamp: file.created_at,
                        type:      fileMeta.type || 'file',
                        ...(fileMeta.type === 'voice' && { duration: fileMeta.duration ?? 0, waveform: fileMeta.waveform ?? '' })
                    });
                    const hdrBytes = Buffer.from(hdrJson, 'utf8');
                    const lenBuf   = Buffer.alloc(4);
                    lenBuf.writeUInt32BE(hdrBytes.length, 0);
                    ws.send(Buffer.concat([lenBuf, hdrBytes, encBytes]));
                    console.log('  file-request: served ' + file.filename + ' (' + encBytes.length + 'B) to ' + username);
                } catch (err) {
                    send(ws, { type: 'file-error', fileId, reason: 'read-failed' });
                    console.error('  file-request read failed ' + fileId + ':', err.message);
                }
                break;
            }

            case 'delivered':
            case 'read': {
                sendToAll(msg.to, msg);
                break;
            }

            case 'call-offer': {
                if (activeCalls.has(msg.to)) {
                    send(ws, { type: 'call-busy', from: msg.to, to: msg.from });
                    break;
                }
                if (activeCalls.get(msg.to) === msg.from) {
                    const caller = msg.from > msg.to ? msg.from : msg.to;
                    const callee = msg.from > msg.to ? msg.to   : msg.from;
                    sendToAll(msg.from, { type: 'call-mutual-resolve', caller, callee });
                    sendToAll(msg.to,   { type: 'call-mutual-resolve', caller, callee });
                    break;
                }
                activeCalls.set(msg.from, msg.to);
                activeCalls.set(msg.to, msg.from);
                if (isOnline(msg.to)) {
                    sendToAll(msg.to, msg);
                } else {
                    activeCalls.delete(msg.from);
                    activeCalls.delete(msg.to);
                    enqueue(msg.to, { type: 'missed-call', from: msg.from, to: msg.to, timestamp: Date.now() });
                    console.log(`  queued missed-call for offline ${msg.to}`);
                    const toUser = stmt.getUser.get(msg.to);
                    if (toUser?.fcm_token) sendFcmWakeup(toUser.fcm_token);
                }
                break;
            }

            case 'call-emergency': {
                activeCalls.set(msg.from, msg.to);
                activeCalls.set(msg.to, msg.from);
                if (isOnline(msg.to)) {
                    sendToAll(msg.to, msg);
                } else {
                    activeCalls.delete(msg.from);
                    activeCalls.delete(msg.to);
                    enqueue(msg.to, { type: 'missed-call', from: msg.from, to: msg.to, timestamp: Date.now() });
                    console.log(`  queued missed-call (emergency) for offline ${msg.to}`);
                }
                break;
            }

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

            case 'call-end':
            case 'call-reject': {
                activeCalls.delete(msg.from);
                activeCalls.delete(msg.to);
                sendToAll(msg.to, msg);
                // Notify sender's other devices so they can close call UI
                const myDevices = clients.get(username);
                if (myDevices) {
                    for (const [devId, devWs] of myDevices.entries()) {
                        if (devId !== ws.deviceId) send(devWs, msg);
                    }
                }
                break;
            }

            case 'set-nickname': {
                const nick = (msg.nickname || '').trim().slice(0, 20) || null;
                stmt.updateNickname.run(nick, username);
                broadcastAllUsers();
                console.log(`  nickname set for ${username}: "${nick}"`);
                break;
            }

            case 'set-contact-nickname': {
                const contact = (msg.contact || '').trim().toLowerCase();
                const nick    = (msg.nickname || '').trim().slice(0, 30);
                if (!contact) break;
                if (nick === '') {
                    stmt.deleteContactNickname.run(username, contact);
                } else {
                    stmt.setContactNickname.run(username, contact, nick);
                }
                break;
            }

            case 'fcm-token': {
                stmt.updateFcmToken.run(msg.token || null, username);
                console.log(`  FCM token ${msg.token ? 'stored' : 'cleared'} for ${username}`);
                break;
            }

            case 'ping': {
                ws.lastPingAt = Date.now();
                const onlineUsers   = [...clients.keys()];
                const outdatedLists = [];
                if (msg.listVersions && typeof msg.listVersions === 'object') {
                    for (const [listId, clientVer] of Object.entries(msg.listVersions)) {
                        const row = stmt.getListVersion.get(listId);
                        if (row && row.version > clientVer) outdatedLists.push({ listId, serverVersion: row.version });
                    }
                }
                send(ws, { type: 'pong', onlineUsers, outdatedLists });
                break;
            }

            case 'history-request': {
                const peer  = msg.to;
                const maxMs = config.maxHistoryRequestDays * 24 * 60 * 60 * 1000;
                const since = Math.max(msg.since || 0, Date.now() - maxMs);
                const messages = readHistory(username, peer, since);
                send(ws, { type: 'history-response', messages, from: peer, to: username });
                break;
            }

            case 'list-create': {
                const { listId, items } = msg;
                if (!listId || !Array.isArray(items)) break;
                stmt.insertList.run( listId, msg.from, msg.to, Date.now(), msg.messageId ?? null);
                for (const it of items) {
                    stmt.upsertListItem.run(it.id, listId, it.text, 0, null, null, null);
                }
                send(ws, { type: 'list-ack', listId, version: 1 });
                const list = getListWithItems(listId);
                if (list) broadcastListState(list, listId);
                break;
            }

            case 'list-edit': {
                const { listId, items } = msg;
                if (!listId || !Array.isArray(items)) break;
                const list = getListWithItems(listId);
                if (!list) break;
                const existing = {};
                for (const it of list.items) existing[it.id] = it;
                for (const it of items) {
                    if (it.deleted) {
                        stmt.markItemDeleted.run(Date.now(), it.id, listId);
                    } else {
                        const prev = existing[it.id];
                        stmt.upsertListItem.run(it.id, listId, it.text, prev?.done ? 1 : 0, prev?.checkedBy ?? null, prev?.checkedAt ?? null, null);
                    }
                }
                stmt.bumpListVersion.run(listId);
                const updated = getListWithItems(listId);
                if (!updated) break;
                send(ws, { type: 'list-ack', listId, version: updated.version });
                broadcastListState(updated, listId);
                break;
            }

            case 'list-check': {
                const { listId, itemId, done } = msg;
                if (!listId || !itemId) break;
                const list = getListWithItems(listId);
                if (!list) break;
                const item = list.items.find(it => it.id === itemId);
                if (item) {
                    stmt.upsertListItem.run(itemId, listId, item.text, done ? 1 : 0, msg.from, msg.ts ?? Date.now(), item.deletedAt ?? null);
                }
                stmt.bumpListVersion.run(listId);
                const updated = getListWithItems(listId);
                if (!updated) break;
                send(ws, { type: 'list-ack', listId, version: updated.version });
                broadcastListState(updated, listId);
                break;
            }

            case 'list-sync-request': {
                if (msg.listId) {
                    const list = getListWithItems(msg.listId);
                    if (list && (list.owner === username || list.peer === username)) {
                        sendListState(ws, list, msg.listId);
                    }
                } else {
                    const cutoff      = Date.now() - 7 * 24 * 60 * 60 * 1000;
                    const knownVers   = (msg.lastKnownVersions && typeof msg.lastKnownVersions === 'object') ? msg.lastKnownVersions : {};
                    const recentLists = stmt.getRecentLists.all(username, username, cutoff);
                    for (const row of recentLists) {
                        if (row.version > (knownVers[row.list_id] || 0)) {
                            const list = getListWithItems(row.list_id);
                            if (list) sendListState(ws, list, row.list_id);
                        }
                    }
                }
                break;
            }

            case 'location-request': {
                if (msg.messageId != null) send(ws, { type: 'ack', messageId: msg.messageId });
                if (isOnline(msg.to)) {
                    sendToAll(msg.to, msg);
                } else {
                    enqueue(msg.to, { type: 'location-request', from: msg.from, to: msg.to, requestId: msg.requestId, content: msg.content, messageId: msg.messageId, timestamp: msg.timestamp ?? Date.now() });
                }
                break;
            }

            case 'location-response': {
                if (msg.messageId != null) send(ws, { type: 'ack', messageId: msg.messageId });
                if (isOnline(msg.to)) {
                    sendToAll(msg.to, msg);
                } else {
                    enqueue(msg.to, { type: 'location-response', from: msg.from, to: msg.to, requestId: msg.requestId, content: msg.content, messageId: msg.messageId, timestamp: msg.timestamp ?? Date.now() });
                }
                break;
            }

            case 'emergency-location': {
                if (isOnline(msg.to)) {
                    sendToAll(msg.to, msg);
                } else {
                    enqueue(msg.to, { type: 'emergency-location', from: msg.from, to: msg.to, lat: msg.lat, lon: msg.lon, accuracy: msg.accuracy, messageId: msg.messageId, timestamp: msg.timestamp ?? Date.now() });
                }
                break;
            }

            case 'avatar-upload': {
                const data = msg.data;
                if (typeof data !== 'string' || data.length > 400_000) break;
                try {
                    fs.writeFileSync(path.join(AVATARS_DIR, `${username}.jpg`), Buffer.from(data, 'base64'));
                    const packet = JSON.stringify({ type: 'avatar-data', username, data });
                    for (const userDevices of clients.values()) {
                        for (const cws of userDevices.values()) {
                            if (cws.readyState === cws.OPEN) cws.send(packet);
                        }
                    }
                } catch (e) {
                    console.error('avatar-upload error', e);
                }
                break;
            }

            case 'peer-test-request': {
                const { testId, to: target } = msg;
                if (!testId || !target) break;
                const targetWs = getAnySocket(target);
                if (!targetWs) {
                    const u = stmt.getUser.get(target);
                    send(ws, { type: 'peer-test-result', testId, success: false, reason: 'offline', lastSeen: u?.last_seen || null });
                    break;
                }
                const timer = setTimeout(() => {
                    if (!pendingPeerTests.has(testId)) return;
                    pendingPeerTests.delete(testId);
                    send(ws, { type: 'peer-test-result', testId, success: false, reason: 'timeout' });
                }, 14_000);
                pendingPeerTests.set(testId, { ws, startTime: Date.now(), timer });
                send(targetWs, msg);
                break;
            }

            case 'peer-test-response': {
                const { testId } = msg;
                if (!testId) break;
                const pending = pendingPeerTests.get(testId);
                if (!pending) break;
                clearTimeout(pending.timer);
                pendingPeerTests.delete(testId);
                send(pending.ws, { type: 'peer-test-result', testId, success: true, latencyMs: Date.now() - pending.startTime });
                break;
            }

            case 'change-password': {
                const { currentPassword, newPassword } = msg;
                const user = stmt.getUser.get(username);
                if (!currentPassword || !bcrypt.compareSync(currentPassword, user.password_hash)) {
                    send(ws, { type: 'change-password-error', message: 'Current password is wrong' }); break;
                }
                if (!newPassword || newPassword.length < 4) {
                    send(ws, { type: 'change-password-error', message: 'New password too short (min 4)' }); break;
                }
                stmt.updatePassword.run(bcrypt.hashSync(newPassword, 10), username);
                send(ws, { type: 'change-password-ok', message: 'Password changed' });
                break;
            }

            case 'admin-server-info': {
                const user = stmt.getUser.get(username);
                if (!user?.admin) { send(ws, { type: 'admin-error', message: 'Unauthorized' }); break; }
                let disk = 'N/A';
                try { disk = execSync('df -h /opt/fshu5 2>/dev/null').toString().split('\n')[1]?.trim() || 'N/A'; } catch {}
                let filesCount = 0, filesSizeBytes = 0;
                try {
                    const fl = fs.readdirSync(FILES_DIR);
                    filesCount = fl.length;
                    filesSizeBytes = fl.reduce((s, f) => { try { return s + fs.statSync(path.join(FILES_DIR, f)).size; } catch { return s; } }, 0);
                } catch {}
                const msgCount = db.prepare('SELECT COUNT(*) AS c FROM messages').get()?.c || 0;
                const filesMB  = (filesSizeBytes / (1024 * 1024)).toFixed(1);
                send(ws, { type: 'admin-server-info', disk, filesCount, filesMB: `${filesMB} MB`, historyCount: msgCount });
                break;
            }

            case 'admin-list-users': {
                const user = stmt.getUser.get(username);
                if (!user?.admin) { send(ws, { type: 'admin-error', message: 'Unauthorized' }); break; }
                send(ws, {
                    type: 'admin-users',
                    users: stmt.getAllUsers.all().map(u => ({
                        username:  u.username,
                        createdAt: u.created_at ? new Date(u.created_at).toISOString().slice(0, 10) : '',
                        admin:     u.admin === 1
                    }))
                });
                break;
            }

            case 'admin-add-user': {
                const user = stmt.getUser.get(username);
                if (!user?.admin) { send(ws, { type: 'admin-error', message: 'Unauthorized' }); break; }
                const newU = (msg.username || '').trim().toLowerCase();
                const newP = msg.password || '';
                if (!newU || !newP) { send(ws, { type: 'admin-error', message: 'Username and password required' }); break; }
                if (stmt.getUser.get(newU)) { send(ws, { type: 'admin-error', message: `User "${newU}" already exists` }); break; }
                stmt.insertUser.run(newU, bcrypt.hashSync(newP, 10), 0, Date.now());
                broadcastAllUsers();
                send(ws, { type: 'admin-result', message: `User "${newU}" created` });
                break;
            }

            case 'admin-remove-user': {
                const user = stmt.getUser.get(username);
                if (!user?.admin) { send(ws, { type: 'admin-error', message: 'Unauthorized' }); break; }
                const target = (msg.username || '').trim().toLowerCase();
                if (!target || target === username) { send(ws, { type: 'admin-error', message: 'Cannot remove yourself' }); break; }
                if (!stmt.getUser.get(target)) { send(ws, { type: 'admin-error', message: 'User not found' }); break; }
                stmt.deleteUser.run(target);
                stmt.deleteUserSessions.run(target);
                const targetDevices = clients.get(target);
                if (targetDevices) { for (const tws of targetDevices.values()) tws.close(); }
                broadcastAllUsers();
                send(ws, { type: 'admin-result', message: `User "${target}" removed` });
                break;
            }

            case 'admin-reset-password': {
                const user = stmt.getUser.get(username);
                if (!user?.admin) { send(ws, { type: 'admin-error', message: 'Unauthorized' }); break; }
                const target = (msg.username || '').trim().toLowerCase();
                const newP   = msg.newPassword || '';
                if (!stmt.getUser.get(target)) { send(ws, { type: 'admin-error', message: 'User not found' }); break; }
                if (newP.length < 4) { send(ws, { type: 'admin-error', message: 'Password too short (min 4)' }); break; }
                stmt.updatePassword.run(bcrypt.hashSync(newP, 10), target);
                send(ws, { type: 'admin-result', message: `Password reset for "${target}"` });
                break;
            }

            case 'public-key': {
                const key = (msg.publicKey || '').trim();
                if (key) {
                    stmt.updatePublicKey.run(key, username);
                    console.log(`  public key stored for ${username}`);
                    broadcastAllUsers();
                }
                break;
            }

            case 'public-key-request': {
                const target = (msg.username || '').trim().toLowerCase();
                const u = target ? stmt.getUser.get(target) : null;
                send(ws, { type: 'public-key-response', username: target, publicKey: u?.public_key || null });
                break;
            }

            default:
                console.warn(`Unknown type: ${msg.type}`);
        }
    });

    ws.on('close', () => {
        if (!username) return;
        const deviceId = ws.deviceId || 'default';
        const userDevices = clients.get(username);

        if (!userDevices || userDevices.get(deviceId) !== ws) {
            console.log(`  stale socket closed for ${username}`);
            return;
        }

        userDevices.delete(deviceId);

        const callPeer = activeCalls.get(username);
        if (callPeer) {
            setTimeout(() => {
                if (!isOnline(username) && activeCalls.get(callPeer) === username) {
                    activeCalls.delete(username);
                    activeCalls.delete(callPeer);
                    sendToAll(callPeer, { type: 'call-end', from: username, to: callPeer, reason: 'disconnected' });
                    console.log(`  call-end → ${callPeer} (${username} disconnected)`);
                }
            }, 4000);
        }

        if (userDevices.size === 0) {
            clients.delete(username);
            stmt.updateLastSeen.run(Date.now(), username);
            console.log(`- ${username} (${clients.size} online)`);
            setTimeout(() => { if (!isOnline(username)) broadcastAllUsers(); }, 2000);
        } else {
            console.log(`  device ${deviceId} disconnected for ${username} (${userDevices.size} device(s) remaining)`);
        }
    });

    ws.on('error', (err) => {
        console.error(`Error [${username ?? 'unauthenticated'}]: ${err.message}`);
    });
});

console.log(`4shu β server listening on ws://localhost:${PORT}`);
