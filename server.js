'use strict';
const { WebSocketServer } = require('ws');
const http = require('http');
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
// Email (nodemailer — optional)
// ---------------------------------------------------------------------------

let nodemailer = null;
function getMailer() {
  if (!nodemailer) {
    try { nodemailer = require('nodemailer'); } catch { return null; }
  }
  if (!config.smtp?.host) return null;
  return nodemailer.createTransport({
    host: config.smtp.host,
    port: config.smtp.port || 587,
    secure: config.smtp.secure || false,
    auth: { user: config.smtp.user, pass: config.smtp.password }
  });
}

async function sendEmail(to, subject, html) {
  const mailer = getMailer();
  if (!mailer) {
    console.log(`  [email disabled] to=${to} subject=${subject}`);
    return false;
  }
  try {
    await mailer.sendMail({ from: config.smtp.from, to, subject, html });
    console.log(`  email sent to ${to}`);
    return true;
  } catch(e) {
    console.error(`  email error: ${e.message}`);
    return false;
  }
}

// ---------------------------------------------------------------------------
// Paths
// ---------------------------------------------------------------------------

const PORT        = process.env.PORT || 8083;
const BASE_DIR    = process.env.FSHU_BASE_DIR || '/opt/fshu5';
const DB_PATH     = path.join(BASE_DIR, 'data', 'fshu.db');
const FILES_DIR   = path.join(BASE_DIR, 'files');
const AVATARS_DIR = path.join(BASE_DIR, 'avatars');
const CONFIG_FILE = path.join(BASE_DIR, 'data', 'config.json');
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
    publicUrl:             '',
    features: {
        multiDevice:      true,
        ecdh:             false,
        groups:           false,
        voiceMessages:    false,
        reactions:        false,
        editMessages:     false,
        deleteForAll:     false,
        typingIndicators: false,
        inviteLinks:      true,
        userSearch:       false
    },
    limits: {
        maxFileSizeMB:           50,
        maxVoiceDurationSeconds: 300,
        maxGroupSize:            500,
        fileRetentionDays:       90,
        historyRetentionDays:    90,
        localCacheRetentionDays: 30
    },
    appDescription: '',
    apkUrl: '',
    smtp: {
        host:     '',
        port:     587,
        secure:   false,
        user:     '',
        password: '',
        from:     ''
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

CREATE TABLE IF NOT EXISTS contacts (
  owner       TEXT NOT NULL,
  contact     TEXT NOT NULL,
  status      TEXT DEFAULT 'pending',
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  PRIMARY KEY (owner, contact)
);
CREATE INDEX IF NOT EXISTS idx_contacts_owner ON contacts(owner, status);
CREATE INDEX IF NOT EXISTS idx_contacts_contact ON contacts(contact, status);

CREATE TABLE IF NOT EXISTS blocks (
  owner       TEXT NOT NULL,
  blocked     TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (owner, blocked)
);

CREATE TABLE IF NOT EXISTS password_resets (
  token       TEXT PRIMARY KEY,
  username    TEXT NOT NULL,
  expires_at  INTEGER NOT NULL,
  created_at  INTEGER NOT NULL,
  used_at     INTEGER
);

CREATE TABLE IF NOT EXISTS auto_location (
  owner TEXT NOT NULL,
  peer  TEXT NOT NULL,
  PRIMARY KEY (owner, peer)
);
`);

// Phase 1g migration: add nonce column to files if not present
try { db.exec('ALTER TABLE files ADD COLUMN nonce TEXT'); } catch {}
try { db.exec('ALTER TABLE files ADD COLUMN meta_json TEXT'); } catch {}

// Profile fields migration
try { db.exec('ALTER TABLE users ADD COLUMN email TEXT'); } catch {}
try { db.exec('ALTER TABLE users ADD COLUMN phone TEXT'); } catch {}
try { db.exec('ALTER TABLE users ADD COLUMN bio TEXT'); } catch {}
try { db.exec('ALTER TABLE users ADD COLUMN discoverable INTEGER DEFAULT 1'); } catch {}
try { db.exec('ALTER TABLE users ADD COLUMN show_avatar INTEGER DEFAULT 1'); } catch {}
try { db.exec('ALTER TABLE users ADD COLUMN show_nickname INTEGER DEFAULT 1'); } catch {}
try { db.exec('ALTER TABLE users ADD COLUMN email_searchable INTEGER DEFAULT 1'); } catch {}
try { db.exec('ALTER TABLE users ADD COLUMN phone_searchable INTEGER DEFAULT 1'); } catch {}
try { db.exec('ALTER TABLE users ADD COLUMN secret_question TEXT'); } catch {}
try { db.exec('ALTER TABLE users ADD COLUMN secret_answer_hash TEXT'); } catch {}

// messages client_id migration
try { db.exec('ALTER TABLE messages ADD COLUMN client_id TEXT'); } catch {}
try { db.exec('CREATE INDEX IF NOT EXISTS idx_messages_client_id ON messages(client_id)'); } catch {}

// ---------------------------------------------------------------------------
// Prepared statements
// ---------------------------------------------------------------------------

const stmt = {
    getUser:             db.prepare('SELECT * FROM users WHERE username = ?'),
    getAllUsers:         db.prepare("SELECT * FROM users WHERE status != 'deleted' ORDER BY username"),
    insertUser:         db.prepare(`INSERT INTO users (username, password_hash, admin, created_at, trust_level, email, secret_question, secret_answer_hash) VALUES (?, ?, 0, ?, 'contact', ?, ?, ?)`),
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
          (message_id, from_user, to_user, content, timestamp, type, reply_to_id, reply_to_sender, reply_to_content, client_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`),
    getHistory:         db.prepare(`
        SELECT * FROM messages
        WHERE ((from_user = ? AND to_user = ?) OR (from_user = ? AND to_user = ?))
          AND timestamp >= ?
        ORDER BY timestamp`),
    deleteOldMessages:  db.prepare('DELETE FROM messages WHERE timestamp < ?'),
    getMessage:         db.prepare('SELECT * FROM messages WHERE message_id = ?'),
    getMessageByClientId: db.prepare('SELECT * FROM messages WHERE client_id = ? AND (from_user = ? OR to_user = ?) LIMIT 1'),
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

    // Group statements
    createGroup:           db.prepare('INSERT INTO groups (group_id, name, owner, type, created_at) VALUES (?, ?, ?, ?, ?)'),
    getGroup:              db.prepare('SELECT * FROM groups WHERE group_id = ?'),
    getGroupMembers:       db.prepare('SELECT * FROM group_members WHERE group_id = ?'),
    getMemberGroups:       db.prepare('SELECT g.* FROM groups g JOIN group_members gm ON g.group_id = gm.group_id WHERE gm.username = ?'),
    addGroupMember:        db.prepare('INSERT INTO group_members (group_id, username, role, joined_at, encrypted_group_key) VALUES (?, ?, ?, ?, ?)'),
    removeGroupMember:     db.prepare('DELETE FROM group_members WHERE group_id = ? AND username = ?'),
    updateGroupMemberRole: db.prepare('UPDATE group_members SET role = ? WHERE group_id = ? AND username = ?'),
    updateGroupMemberKey:  db.prepare('UPDATE group_members SET encrypted_group_key = ? WHERE group_id = ? AND username = ?'),
    updateGroupName:       db.prepare('UPDATE groups SET name = ? WHERE group_id = ?'),
    updateGroupOwner:      db.prepare('UPDATE groups SET owner = ? WHERE group_id = ?'),
    deleteGroup:           db.prepare('DELETE FROM groups WHERE group_id = ?'),
    deleteGroupMembers:    db.prepare('DELETE FROM group_members WHERE group_id = ?'),
    insertGroupMessage:    db.prepare(`
        INSERT OR IGNORE INTO messages
          (message_id, from_user, group_id, content, timestamp, type, reply_to_id, reply_to_sender, reply_to_content)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`),
    getGroupHistory:       db.prepare('SELECT * FROM messages WHERE group_id = ? AND timestamp >= ? ORDER BY timestamp'),

    getUserTrustLevel:     db.prepare('SELECT trust_level FROM users WHERE username = ?'),
    setUserTrustLevel:     db.prepare("UPDATE users SET trust_level = ? WHERE username = ?"),
    getMemberFamilyGroups: db.prepare("SELECT 1 FROM groups g JOIN group_members gm ON g.group_id = gm.group_id WHERE gm.username = ? AND g.type = 'family' LIMIT 1"),

    getInvite:    db.prepare('SELECT * FROM invites WHERE token = ?'),
    createInvite: db.prepare('INSERT INTO invites (token, created_by, expires_at) VALUES (?, ?, ?)'),
    useInvite:    db.prepare('UPDATE invites SET used_by = ?, used_at = ? WHERE token = ?'),
    listInvites:  db.prepare('SELECT token, created_by, expires_at, used_by, used_at FROM invites ORDER BY rowid DESC LIMIT 50'),
    revokeInvite: db.prepare('DELETE FROM invites WHERE token = ? AND used_at IS NULL'),

    // Data-export / account-deletion
    getUserForExport:            db.prepare('SELECT username, nickname, created_at, trust_level FROM users WHERE username = ?'),
    getContactNicknamesForExport: db.prepare('SELECT contact, nickname FROM contact_nicknames WHERE owner = ?'),
    getMessagesForExport:        db.prepare('SELECT * FROM messages WHERE (from_user = ? OR to_user = ?) AND deleted_for_all = 0 ORDER BY timestamp ASC'),
    getGroupsForExport:          db.prepare('SELECT g.* FROM groups g JOIN group_members gm ON g.group_id = gm.group_id WHERE gm.username = ?'),
    anonymizeUser:               db.prepare("UPDATE messages SET from_user = '[deleted]' WHERE from_user = ?"),
    deleteUserData:              db.prepare('DELETE FROM users WHERE username = ?'),
    deleteUserSessions:          db.prepare('DELETE FROM sessions WHERE username = ?'),
    deleteUserDevices:           db.prepare('DELETE FROM devices WHERE username = ?'),
    deleteUserNicknames:         db.prepare('DELETE FROM contact_nicknames WHERE owner = ?'),
    removeFromGroups:            db.prepare('DELETE FROM group_members WHERE username = ?'),
    deleteUserReactions:         db.prepare("UPDATE reactions SET from_user = '[deleted]' WHERE from_user = ?"),

    // Device management
    upsertDevice: db.prepare(`
        INSERT INTO devices (username, device_id, device_name, last_seen)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(username, device_id) DO UPDATE SET
          device_name = COALESCE(excluded.device_name, device_name),
          last_seen = excluded.last_seen
    `),
    getDevices:  db.prepare('SELECT device_id, device_name, last_seen FROM devices WHERE username = ? ORDER BY last_seen DESC'),
    removeDevice: db.prepare('DELETE FROM devices WHERE username = ? AND device_id = ?'),
    renameDevice: db.prepare('UPDATE devices SET device_name = ? WHERE username = ? AND device_id = ?'),
    deleteDeviceSessionByDeviceId: db.prepare('DELETE FROM sessions WHERE username = ? AND device_id = ?'),
};

// Contacts & blocks statements
stmt.getContacts = db.prepare(`
  SELECT c.contact, c.status, c.created_at, c.updated_at,
         u.nickname, u.avatar_path, u.last_seen, u.status as user_status
  FROM contacts c
  JOIN users u ON u.username = c.contact
  WHERE c.owner = ? AND c.status = 'accepted'
`);

stmt.getPendingReceived = db.prepare(`
  SELECT c.owner as from_user, c.created_at, c.expires_at,
         u.nickname, u.show_nickname, u.show_avatar, u.avatar_path
  FROM contacts c
  JOIN users u ON u.username = c.owner
  WHERE c.contact = ? AND c.status = 'pending'
`);

stmt.getPendingSent = db.prepare(`
  SELECT contact, status, created_at FROM contacts
  WHERE owner = ? AND status = 'pending'
`);

stmt.getContactStatus = db.prepare(`
  SELECT status FROM contacts WHERE owner = ? AND contact = ?
`);

stmt.upsertContactRequest = db.prepare(`
  INSERT INTO contacts (owner, contact, status, created_at, updated_at, expires_at)
  VALUES (?, ?, 'pending', ?, ?, ?)
  ON CONFLICT(owner, contact) DO UPDATE SET
    status = 'pending',
    updated_at = excluded.updated_at,
    expires_at = excluded.expires_at
`);

stmt.acceptContact = db.prepare(`
  UPDATE contacts SET status = 'accepted', updated_at = ?
  WHERE owner = ? AND contact = ?
`);

stmt.deleteContact = db.prepare(`
  DELETE FROM contacts WHERE (owner = ? AND contact = ?) OR (owner = ? AND contact = ?)
`);

stmt.getBlock = db.prepare(`
  SELECT 1 FROM blocks WHERE owner = ? AND blocked = ?
`);

stmt.insertBlock = db.prepare(`
  INSERT OR IGNORE INTO blocks (owner, blocked, created_at) VALUES (?, ?, ?)
`);

stmt.removeBlock = db.prepare(`
  DELETE FROM blocks WHERE owner = ? AND blocked = ?
`);

stmt.getBlockList = db.prepare(`
  SELECT blocked, created_at FROM blocks WHERE owner = ? ORDER BY created_at DESC
`);

stmt.searchUsers = db.prepare(`
  SELECT username, nickname, show_nickname, show_avatar, avatar_path, bio, discoverable
  FROM users
  WHERE status != 'deleted'
    AND username != ?
    AND discoverable = 1
    AND (
      username LIKE ? OR
      (show_nickname = 1 AND nickname LIKE ?)
    )
  LIMIT 10 OFFSET ?
`);

stmt.searchByEmail = db.prepare(`
  SELECT username, nickname, show_nickname, show_avatar, avatar_path
  FROM users
  WHERE status != 'deleted' AND username != ?
    AND email_searchable = 1 AND email = ?
  LIMIT 1
`);

stmt.searchByPhone = db.prepare(`
  SELECT username, nickname, show_nickname, show_avatar, avatar_path
  FROM users
  WHERE status != 'deleted' AND username != ?
    AND phone_searchable = 1 AND phone = ?
  LIMIT 1
`);

stmt.getUserPublicProfile = db.prepare(`
  SELECT username, nickname, show_nickname, show_avatar, avatar_path,
         discoverable, bio
  FROM users WHERE username = ? AND status != 'deleted'
`);

stmt.getUserContactsProfile = db.prepare(`
  SELECT username, nickname, avatar_path, bio, last_seen
  FROM users WHERE username = ? AND status != 'deleted'
`);

stmt.expireOldRequests = db.prepare(`
  DELETE FROM contacts WHERE status = 'pending' AND expires_at < ?
`);

stmt.updatePrivacy = db.prepare(`
  UPDATE users SET
    discoverable = ?,
    show_avatar = ?,
    show_nickname = ?,
    email_searchable = ?,
    phone_searchable = ?
  WHERE username = ?
`);

stmt.updateProfile = db.prepare(`
  UPDATE users SET bio = ?, email = ?, phone = ? WHERE username = ?
`);

stmt.getMyProfile = db.prepare(`
  SELECT username, nickname, email, phone, bio,
         discoverable, show_avatar, show_nickname,
         email_searchable, phone_searchable
  FROM users WHERE username = ?
`);

stmt.getUserByEmail = db.prepare(
  "SELECT username, email FROM users WHERE email = ? AND status != ?"
);

stmt.insertResetToken = db.prepare(`
  INSERT OR REPLACE INTO password_resets
  (token, username, expires_at, created_at)
  VALUES (?, ?, ?, ?)
`);

stmt.getResetToken = db.prepare(
  "SELECT * FROM password_resets WHERE token = ? AND used_at IS NULL AND expires_at > ?"
);

stmt.markResetTokenUsed = db.prepare(
  "UPDATE password_resets SET used_at = ? WHERE token = ?"
);

stmt.getUserByUsername = db.prepare(
  "SELECT username FROM users WHERE username = ? AND status != 'deleted'"
);
stmt.setSecretQuestion = db.prepare(
  "UPDATE users SET secret_question = ?, secret_answer_hash = ? WHERE username = ?"
);
stmt.getSecretQuestion = db.prepare(
  "SELECT secret_question, secret_answer_hash FROM users WHERE username = ?"
);
stmt.setAutoLocation = db.prepare(
  'INSERT OR IGNORE INTO auto_location (owner, peer) VALUES (?, ?)'
);
stmt.clearAutoLocation = db.prepare(
  'DELETE FROM auto_location WHERE owner = ? AND peer = ?'
);
stmt.getAutoLocationPeers = db.prepare(
  'SELECT peer FROM auto_location WHERE owner = ?'
);
stmt.checkAutoLocation = db.prepare(
  'SELECT 1 FROM auto_location WHERE owner = ? AND peer = ?'
);
stmt.checkContactAccepted = db.prepare(
  "SELECT 1 FROM contacts WHERE owner = ? AND contact = ? AND status = 'accepted'"
);

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
const exportTokens     = new Map();
const searchRateLimit  = new Map();   // username -> { count, resetAt }
const requestRateLimit = new Map();   // username -> { count, resetAt }

function checkRateLimit(map, username, maxCount, windowMs) {
    const now = Date.now();
    const entry = map.get(username);
    if (!entry || entry.resetAt < now) {
        map.set(username, { count: 1, resetAt: now + windowMs });
        return true;
    }
    if (entry.count >= maxCount) return false;
    entry.count++;
    return true;
}

// ---------------------------------------------------------------------------
// WebSocket send helpers
// ---------------------------------------------------------------------------

function areContacts(userA, userB) {
  return !!db.prepare(
    "SELECT 1 FROM contacts WHERE owner = ? AND contact = ? AND status = 'accepted'"
  ).get(userA, userB);
}

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

function deliverOrQueue(username, payload) {
    const devices = clients.get(username);
    let delivered = false;
    if (devices && devices.size > 0) {
        for (const ws of devices.values()) {
            if (ws.readyState === ws.OPEN) {
                ws.send(JSON.stringify(payload));
                delivered = true;
            }
        }
    }
    if (!delivered) {
        enqueue(username, payload);
    }
    return delivered;
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
    for (const [uname, userDevices] of clients.entries()) {
        const contacts = stmt.getContacts.all(uname).map(c => c.contact);
        const contactUsers = contacts.length > 0
            ? db.prepare(`SELECT username, nickname, last_seen, status, public_key, trust_level FROM users WHERE username IN (${contacts.map(() => '?').join(',')})`)
                .all(...contacts)
            : [];
        const self = stmt.getUser.get(uname);
        const userList = [self, ...contactUsers].map(u => ({
            username:   u.username,
            online:     isOnline(u.username),
            lastSeen:   isOnline(u.username) ? null : (u.last_seen || null),
            nickname:   u.nickname || null,
            publicKey:  u.public_key || null,
            trustLevel: u.trust_level || 'contact',
        }));
        for (const ws of userDevices.values()) {
            const pendingRequests = db.prepare("SELECT COUNT(*) as cnt FROM contacts WHERE contact = ? AND status = 'pending'").get(uname)?.cnt || 0;
            send(ws, { type: 'users', users: userList, pendingRequests });
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
            `${rec.from}:${rec.messageId}`,
            rec.from, rec.to,
            rec.content ?? null,
            rec.timestamp ?? Date.now(),
            rec.type ?? 'message',
            rec.replyToId ?? null,
            rec.replyToSender ?? null,
            rec.replyToContent ?? null,
            String(rec.messageId)
        );
    } catch {}
}

function readHistory(a, b, since) {
    return stmt.getHistory.all(a, b, a, b, since).map(r => ({
        from:           r.from_user,
        to:             r.to_user,
        content:        r.content,
        messageId:      r.client_id ? Number(r.client_id) : (Number(r.message_id) || 0),
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
// Group helpers
// ---------------------------------------------------------------------------

// In-memory delivery tracker: Map<messageId, Set<deliveredByUsername>>
const groupDeliveryTracker = new Map();

function sendGroupState(ws, groupId, forUsername) {
    const group = stmt.getGroup.get(groupId);
    if (!group) return;
    const members = stmt.getGroupMembers.all(groupId);
    const myMembership = members.find(m => m.username === forUsername);
    if (!myMembership) return;
    const avatarFile = path.join(AVATARS_DIR, `group_${groupId}.jpg`);
    const avatarData = fs.existsSync(avatarFile) ? fs.readFileSync(avatarFile).toString('base64') : null;
    send(ws, {
        type:              'group-state',
        groupId:           group.group_id,
        name:              group.name,
        groupType:         group.type,
        owner:             group.owner,
        members:           members.map(m => ({ username: m.username, role: m.role, joinedAt: m.joined_at })),
        encryptedGroupKey: myMembership.encrypted_group_key || null,
        avatarData,
    });
}

function broadcastGroupState(groupId) {
    const group = stmt.getGroup.get(groupId);
    if (!group) return;
    const members = stmt.getGroupMembers.all(groupId);
    const memberList = members.map(m => ({ username: m.username, role: m.role, joinedAt: m.joined_at }));
    const avatarFile = path.join(AVATARS_DIR, `group_${groupId}.jpg`);
    const avatarData = fs.existsSync(avatarFile) ? fs.readFileSync(avatarFile).toString('base64') : null;
    for (const m of members) {
        const state = {
            type:              'group-state',
            groupId:           group.group_id,
            name:              group.name,
            groupType:         group.type,
            owner:             group.owner,
            members:           memberList,
            encryptedGroupKey: m.encrypted_group_key || null,
            avatarData,
        };
        if (isOnline(m.username)) {
            sendToAll(m.username, state);
        } else {
            enqueue(m.username, state);
        }
    }
}

function sendGroupStatesOnConnect(username, ws) {
    const groups = stmt.getMemberGroups.all(username);
    for (const g of groups) {
        sendGroupState(ws, g.group_id, username);
    }
}

function fanOutGroupMessage(groupId, msgData, senderUsername, senderDeviceId) {
    const members = stmt.getGroupMembers.all(groupId);
    for (const m of members) {
        if (m.username === senderUsername) {
            // Deliver to sender's other devices only
            const devices = clients.get(m.username);
            if (devices) {
                for (const [devId, devWs] of devices.entries()) {
                    if (devId !== senderDeviceId) send(devWs, msgData);
                }
            }
        } else {
            if (isOnline(m.username)) {
                sendToAll(m.username, msgData);
            } else {
                enqueue(m.username, msgData);
                const u = stmt.getUser.get(m.username);
                if (u?.fcm_token) sendFcmWakeup(u.fcm_token);
            }
        }
    }
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

    // Expire old pending contact requests
    stmt.expireOldRequests.run(Date.now());

    // Expire used/old password reset tokens
    db.prepare('DELETE FROM password_resets WHERE expires_at < ?').run(Date.now());

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


// ---------------------------------------------------------------------------
// HTTP handler - invite registration
// ---------------------------------------------------------------------------

function escHtml(str) { return String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }

function pageWrap(title, body) {
    return `<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escHtml(title)} - 4shu</title>
<style>*{box-sizing:border-box;margin:0;padding:0}body{background:#141928;color:#e0e0e0;font-family:system-ui,sans-serif;min-height:100vh;display:flex;align-items:center;justify-content:center}
.card{background:#1e2538;border-radius:12px;padding:32px;width:100%;max-width:380px;box-shadow:0 4px 24px #0005}
h2{color:#E8711A;text-align:center;margin-bottom:24px;font-size:1.4rem}
.field{margin-bottom:16px}label{display:block;font-size:.85rem;color:#9aa;margin-bottom:6px}
input{width:100%;padding:10px 12px;background:#141928;border:1px solid #2e3650;border-radius:8px;color:#e0e0e0;font-size:1rem;outline:none}
input:focus{border-color:#E8711A}
button{width:100%;padding:11px;background:#E8711A;color:#fff;border:none;border-radius:8px;font-size:1rem;font-weight:600;cursor:pointer;margin-top:8px}
button:hover{background:#d0621a}p{padding:8px 0}a{color:#E8711A}</style></head>
<body><div class="card"><h2>${escHtml(title)}</h2>${body}</div></body></html>`;
}

function res404(res) {
    res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(pageWrap('Not found', '<p style="color:#aaa;text-align:center">404 - page not found</p>'));
}

function sendRegError(res, msg) {
    res.writeHead(400, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(pageWrap('Error', `<p style="color:#f66;text-align:center">${escHtml(msg)}</p><p style="text-align:center;margin-top:16px"><a href="javascript:history.back()">&larr; Back</a></p>`));
}

function handleHttp(req, res) {
    console.log("HTTP", req.method, req.url);
    const url = req.url || '/';

    if (req.method === 'GET' && url.startsWith('/invite/')) {
        const token = url.slice('/invite/'.length).split('?')[0];
        console.log('invite lookup token:', token);
        if (!token) { return res404(res); }
        const invite = stmt.getInvite.get(token);
        console.log('invite lookup result:', JSON.stringify(invite));
        const now = Date.now();
        if (!invite || invite.used_at || (invite.expires_at && invite.expires_at < now)) {
            res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' });
            return res.end(pageWrap('Link expired or invalid', '<p style="color:#aaa;text-align:center">This invite link has expired or is invalid.</p>'));
        }
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        return res.end(pageWrap('Create account', `
            <form method="POST" action="/fshu5/register">
                <input type="hidden" name="token" value="${escHtml(token)}">
                <div class="field"><label>Username</label><input name="username" type="text" autocomplete="off" required minlength="3" maxlength="20" pattern="[a-zA-Z0-9_]+"></div>
                <div class="field"><label>Email address</label><input name="email" type="email" placeholder="your@email.com" required><small style="color:#9aa;font-size:.8rem;display:block;margin-top:4px">Used for password recovery only. Not visible to others.</small></div>
                <div class="field"><label>Password</label><input name="password" type="password" required minlength="6"></div>
                <div class="field"><label>Confirm password</label><input name="confirm" type="password" required minlength="6"></div>
                <div class="field">
                  <label for="secret_question">Security question</label>
                  <input type="text" id="secret_question" name="secret_question" placeholder="e.g. Name of your first pet?" required maxlength="200" />
                  <small>Used to recover your account if you forget your password.</small>
                </div>
                <div class="field">
                  <label for="secret_answer">Answer</label>
                  <input type="text" id="secret_answer" name="secret_answer" placeholder="Your answer (case-insensitive)" required minlength="3" maxlength="200" />
                  <small>Remember this answer exactly â it will be used to verify your identity.</small>
                </div>
                <button type="submit">Create account</button>
            </form>
        `));
    }

    if (req.method === 'POST' && url === '/register') {
        let body = '';
        req.on('data', chunk => { body += chunk; if (body.length > 4096) req.destroy(); });
        req.on('end', async () => {
            try {
                let fields = {};
                const ct = (req.headers['content-type'] || '').toLowerCase();
                if (ct.includes('application/json')) {
                    fields = JSON.parse(body);
                } else {
                    for (const pair of body.split('&')) {
                        const [k, v] = pair.split('=');
                        if (k) fields[decodeURIComponent(k.replace(/\+/g, ' '))] = decodeURIComponent((v || '').replace(/\+/g, ' '));
                    }
                }
                const token    = (fields.token || '').trim();
                const uRaw     = (fields.username || '').trim();
                const password = fields.password || '';
                const confirm  = fields.confirm  || '';
                const email    = (fields.email || '').trim().toLowerCase();

                const secretQuestion = (fields.secret_question || '').trim();
                const secretAnswer   = (fields.secret_answer   || '').trim().toLowerCase();

                const invite = token ? stmt.getInvite.get(token) : null;
                const now = Date.now();
                if (!invite || invite.used_at || (invite.expires_at && invite.expires_at < now)) {
                    return sendRegError(res, 'Invite link is invalid or expired.');
                }
                const u = uRaw.toLowerCase();
                if (!/^[a-zA-Z0-9_]{3,20}$/.test(uRaw)) {
                    return sendRegError(res, 'Username must be 3-20 characters: letters, digits, underscore.');
                }
                if (password.length < 6) {
                    return sendRegError(res, 'Password must be at least 6 characters.');
                }
                if (password !== confirm) {
                    return sendRegError(res, 'Passwords do not match.');
                }
                if (stmt.getUser.get(u)) {
                    return sendRegError(res, 'Username is already taken.');
                }
                if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
                    return sendRegError(res, 'Valid email address is required.');
                }
                const emailTaken = db.prepare('SELECT 1 FROM users WHERE email = ?').get(email);
                if (emailTaken) {
                    return sendRegError(res, 'That email address is already registered.');
                }
                if (!secretQuestion || secretQuestion.length < 5) {
                    return sendRegError(res, 'Security question is required (min 5 characters).');
                }
                if (!secretAnswer || secretAnswer.length < 3) {
                    return sendRegError(res, 'Security answer is required (min 3 characters).');
                }
                const hash       = await bcrypt.hash(password, 10);
                const answerHash = await bcrypt.hash(secretAnswer, 10);
                stmt.insertUser.run(u, hash, now, email || null, secretQuestion, answerHash);
                stmt.useInvite.run(u, now, token);
                console.log(`  invite registration: ${u} (invite ${token.slice(0,8)}...)`);
                res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
                res.end(pageWrap('Account created', '<p style="color:#aaa;text-align:center">Account created! Download the 4shu app and log in.</p>'));
            } catch (err) {
                console.error('register error:', err.message);
                sendRegError(res, 'Server error. Please try again.');
            }
        });
        return;
    }

    else if (req.method === 'GET' && req.url.startsWith('/export/')) {
        const token = req.url.slice('/export/'.length).split('?')[0];
        const entry = exportTokens.get(token);
        if (!entry || entry.expiresAt < Date.now()) {
            res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' });
            return res.end(pageWrap('Link expired', '<p style="color:#aaa;text-align:center">Export link expired or invalid.</p>'));
        }
        const { username: exportUser } = entry;
        exportTokens.delete(token);
        const profile  = stmt.getUserForExport.get(exportUser);
        const contacts = stmt.getContactNicknamesForExport.all(exportUser);
        const messages = stmt.getMessagesForExport.all(exportUser, exportUser);
        const groups   = stmt.getGroupsForExport.all(exportUser);
        const exportData = JSON.stringify({ exportedAt: new Date().toISOString(), profile, contacts, messages, groups }, null, 2);
        res.writeHead(200, {
            'Content-Type': 'application/json',
            'Content-Disposition': `attachment; filename="fshu_export_${exportUser}_${Date.now()}.json"`
        });
        res.end(exportData);
        console.log(`  export downloaded by ${exportUser}`);
        return;
    }

    else if (req.method === 'GET' && url === '/reset') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(pageWrap('Forgot password?', `
            <form method="POST" action="/fshu5/reset">
                <div class="field"><label>Email address</label><input name="email" type="email" required autocomplete="email"></div>
                <button type="submit">Send reset link</button>
            </form>
            <p style="text-align:center;color:#666;margin:24px 0 8px">??? or ???</p>
            <form method="POST" action="/fshu5/reset/question">
                <div class="field"><label>Username</label><input name="username" type="text" required autocomplete="username"></div>
                <button type="submit" style="background:#555">Reset via secret question</button>
            </form>
        `));
        return;
    }

    else if (req.method === 'POST' && url === '/reset') {
        let body = '';
        req.on('data', chunk => { body += chunk; if (body.length > 4096) req.destroy(); });
        req.on('end', async () => {
            try {
                let fields = {};
                const ct = (req.headers['content-type'] || '').toLowerCase();
                if (ct.includes('application/json')) {
                    fields = JSON.parse(body);
                } else {
                    for (const pair of body.split('&')) {
                        const [k, v] = pair.split('=');
                        if (k) fields[decodeURIComponent(k.replace(/\+/g, ' '))] = decodeURIComponent((v || '').replace(/\+/g, ' '));
                    }
                }
                const email = (fields.email || '').trim().toLowerCase();
                const user = email ? stmt.getUserByEmail.get(email, 'deleted') : null;
                if (user && user.email) {
                    const token = crypto.randomBytes(32).toString('hex');
                    const now = Date.now();
                    const expiresAt = now + 60 * 60 * 1000;
                    stmt.insertResetToken.run(token, user.username, expiresAt, now);
                    const resetUrl = config.publicUrl + '/fshu5/reset/' + token;
                    const emailBody = '<p style="color:#aaa;text-align:center;margin-bottom:24px">Click below to reset your 4shu password. Expires in 1 hour.</p>'
                        + '<a href="' + escHtml(resetUrl) + '" style="display:block;padding:12px;background:#E8711A;color:#fff;border-radius:8px;text-decoration:none;font-weight:600;text-align:center">Reset Password</a>';
                    await sendEmail(user.email, '4shu password reset', pageWrap('Reset your password', emailBody));
                    console.log('  password reset requested for ' + user.username + ', url: ' + resetUrl);
                }
                res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
                res.end(pageWrap('Check your inbox', '<p style="color:#aaa;text-align:center">If that email is registered, a reset link has been sent. Check your inbox.</p>'));
            } catch (err) {
                console.error('reset error:', err.message);
                sendRegError(res, 'Server error. Please try again.');
            }
        });
        return;
    }

    else if (req.method === 'POST' && url === '/reset/confirm') {
        let body = '';
        req.on('data', chunk => { body += chunk; if (body.length > 4096) req.destroy(); });
        req.on('end', async () => {
            try {
                let fields = {};
                const ct = (req.headers['content-type'] || '').toLowerCase();
                if (ct.includes('application/json')) {
                    fields = JSON.parse(body);
                } else {
                    for (const pair of body.split('&')) {
                        const [k, v] = pair.split('=');
                        if (k) fields[decodeURIComponent(k.replace(/\+/g, ' '))] = decodeURIComponent((v || '').replace(/\+/g, ' '));
                    }
                }
                const token = (fields.token || '').trim();
                const newPassword = fields.password || '';
                const confirm = fields.confirm || '';
                const row = token ? stmt.getResetToken.get(token, Date.now()) : null;
                if (!row) return sendRegError(res, 'Reset link has expired or is invalid.');
                if (newPassword.length < 6) return sendRegError(res, 'Password must be at least 6 characters.');
                if (newPassword !== confirm) return sendRegError(res, 'Passwords do not match.');
                const hash = await bcrypt.hash(newPassword, 10);
                stmt.updatePassword.run(hash, row.username);
                stmt.markResetTokenUsed.run(Date.now(), token);
                console.log('  password reset completed for ' + row.username);
                res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
                res.end(pageWrap('Password changed', '<p style="color:#aaa;text-align:center">Password changed successfully. Open the 4shu app and log in with your new password.</p>'));
            } catch (err) {
                console.error('reset/confirm error:', err.message);
                sendRegError(res, 'Server error. Please try again.');
            }
        });
        return;
    }

    else if (req.method === 'GET' && url.startsWith('/reset/')) {
        const token = url.slice('/reset/'.length).split('?')[0];
        const row = token ? stmt.getResetToken.get(token, Date.now()) : null;
        if (!row) {
            res.writeHead(400, { 'Content-Type': 'text/html; charset=utf-8' });
            return res.end(pageWrap('Link expired', '<p style="color:#aaa;text-align:center">This reset link has expired or is invalid.</p>'));
        }
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        return res.end(pageWrap('Reset password', `
            <form method="POST" action="/fshu5/reset/confirm">
                <input type="hidden" name="token" value="${escHtml(token)}">
                <div class="field"><label>New password</label><input name="password" type="password" required minlength="6"></div>
                <div class="field"><label>Confirm password</label><input name="confirm" type="password" required minlength="6"></div>
                <button type="submit">Reset Password</button>
            </form>
        `));
    }

    else if (req.method === 'POST' && url === '/reset/question') {
        let body = '';
        req.on('data', chunk => { body += chunk; if (body.length > 4096) req.destroy(); });
        req.on('end', () => {
            try {
                let fields = {};
                for (const pair of body.split('&')) {
                    const [k, v] = pair.split('=');
                    if (k) fields[decodeURIComponent(k.replace(/\+/g, ' '))] = decodeURIComponent((v || '').replace(/\+/g, ' '));
                }
                const reqUsername = (fields.username || '').trim().toLowerCase();
                const user = reqUsername ? stmt.getUserByUsername.get(reqUsername) : null;
                if (!user) return sendRegError(res, 'Username not found.');
                const row = stmt.getSecretQuestion.get(reqUsername);
                if (!row?.secret_question) return sendRegError(res, 'No secret question set for this account. Use email reset.');
                res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
                res.end(pageWrap('Secret question', `
                    <p style="color:#aaa;text-align:center;margin-bottom:16px">${escHtml(row.secret_question)}</p>
                    <form method="POST" action="/fshu5/reset/question/verify">
                        <input type="hidden" name="username" value="${escHtml(reqUsername)}">
                        <div class="field"><label>Your answer</label><input name="answer" type="text" required autocomplete="off"></div>
                        <div class="field"><label>New password</label><input name="password" type="password" required minlength="6"></div>
                        <div class="field"><label>Confirm password</label><input name="confirm" type="password" required minlength="6"></div>
                        <button type="submit">Reset Password</button>
                    </form>
                `));
            } catch (err) {
                console.error('reset/question error:', err.message);
                sendRegError(res, 'Server error. Please try again.');
            }
        });
        return;
    }

    else if (req.method === 'POST' && url === '/reset/question/verify') {
        let body = '';
        req.on('data', chunk => { body += chunk; if (body.length > 4096) req.destroy(); });
        req.on('end', async () => {
            try {
                let fields = {};
                for (const pair of body.split('&')) {
                    const [k, v] = pair.split('=');
                    if (k) fields[decodeURIComponent(k.replace(/\+/g, ' '))] = decodeURIComponent((v || '').replace(/\+/g, ' '));
                }
                const reqUsername = (fields.username || '').trim().toLowerCase();
                const answer = (fields.answer || '').trim().toLowerCase();
                const newPassword = fields.password || '';
                const confirm = fields.confirm || '';
                const row = reqUsername ? stmt.getSecretQuestion.get(reqUsername) : null;
                if (!row?.secret_answer_hash) return sendRegError(res, 'Invalid request.');
                if (!bcrypt.compareSync(answer, row.secret_answer_hash)) return sendRegError(res, 'Incorrect answer.');
                if (newPassword.length < 6) return sendRegError(res, 'Password must be at least 6 characters.');
                if (newPassword !== confirm) return sendRegError(res, 'Passwords do not match.');
                const hash = await bcrypt.hash(newPassword, 10);
                stmt.updatePassword.run(hash, reqUsername);
                console.log('  password reset via secret question for ' + reqUsername);
                res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
                res.end(pageWrap('Password changed', '<p style="color:#aaa;text-align:center">Password changed successfully. Open the 4shu app and log in with your new password.</p>'));
            } catch (err) {
                console.error('reset/question/verify error:', err.message);
                sendRegError(res, 'Server error. Please try again.');
            }
        });
        return;
    }

    else if (req.method === 'GET' && url === '/download/app-release.apk') {
        const apkPath = path.join(BASE_DIR, 'files', 'download', 'app-release.apk');
        if (!fs.existsSync(apkPath)) {
            res.writeHead(404, { 'Content-Type': 'text/plain' });
            res.end('APK not available');
            return;
        }
        const stat = fs.statSync(apkPath);
        res.writeHead(200, {
            'Content-Type': 'application/vnd.android.package-archive',
            'Content-Disposition': 'attachment; filename="4shu.apk"',
            'Content-Length': stat.size
        });
        fs.createReadStream(apkPath).pipe(res);
        return;
    }

    else if (req.method === 'GET' && url === '/join') {
        const apkUrl = config.apkUrl || '';
        const inviteLinksEnabled = !!(config.features && config.features.inviteLinks);
        let sections = '';
        if (apkUrl) {
            sections += '<div class="section">'
                + '<h3>Download the app</h3>'
                + '<a class="dl-btn" href="' + escHtml(apkUrl) + '">&#x2913;&nbsp;Download for Android</a>'
                + '<p class="sub">Android 8.0 or newer required</p>'
                + '</div>';
        }
        if (inviteLinksEnabled) {
            sections += '<div class="section" style="margin-top:' + (apkUrl ? '24px' : '0') + '">'
                + '<h3>Have an invite link?</h3>'
                + '<p class="sub">Ask an existing member for an invite link to join.</p>'
                + '</div>';
        }
        const joinHtml = '<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">'
            + '<meta name="viewport" content="width=device-width,initial-scale=1">'
            + '<title>4shu \u2014 Private messaging</title>'
            + '<style>*{box-sizing:border-box;margin:0;padding:0}'
            + 'body{background:#141928;color:#e0e0e0;font-family:system-ui,sans-serif;'
            + 'min-height:100vh;display:flex;flex-direction:column;align-items:center;'
            + 'justify-content:center;padding:24px}'
            + '.logo{font-size:3rem;font-weight:900;color:#E8711A;letter-spacing:-1px;margin-bottom:8px}'
            + '.tagline{color:#9aa;font-size:1rem;margin-bottom:48px;text-align:center}'
            + '.card{background:#1e2538;border-radius:12px;padding:32px;width:100%;'
            + 'max-width:380px;box-shadow:0 4px 24px #0005}'
            + 'h3{color:#e0e0e0;font-size:1.1rem;margin-bottom:16px;text-align:center}'
            + '.dl-btn{display:block;padding:14px;background:#E8711A;color:#fff;border-radius:8px;'
            + 'text-decoration:none;font-weight:700;font-size:1.05rem;text-align:center}'
            + '.dl-btn:hover{background:#d0621a}'
            + '.sub{color:#9aa;font-size:.85rem;text-align:center;margin-top:12px}'
            + 'footer{color:#555;font-size:.8rem;margin-top:32px}'
            + '</style></head><body>'
            + '<div class="logo">4shu</div>'
            + (config.appDescription ? '<div class="tagline">' + escHtml(config.appDescription) + '</div>' : '')
            + '<div class="card">' + sections + '</div>'
            + '<footer>4shu \u2014 private messaging</footer>'
            + '</body></html>';
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(joinHtml);
        return;
    }

    else if (req.method === 'GET' && url === '/') {
        res.writeHead(302, { Location: '/fshu5/join' });
        res.end();
        return;
    }

    res404(res);
}

const httpServer = http.createServer(handleHttp);
const wss = new WebSocketServer({ server: httpServer });
httpServer.listen(PORT, () => console.log(`4shu β server listening on port ${PORT}`));

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
                stmt.upsertDevice.run(username, deviceId, msg.deviceName || null, Date.now());
                const contactNicknames = stmt.getContactNicknames.all(username);
                send(ws, { type: 'auth-ok', appSecret: sharedAppSecret, admin: user.admin === 1, sessionToken: token, features: config.features, turnUsername: TURN_USERNAME, turnPassword: TURN_PASSWORD, publicKey: user.public_key || null, contactNicknames, profile: stmt.getMyProfile.get(username) || null, autoLocationPeers: stmt.getAutoLocationPeers.all(username).map(r => r.peer) });
                sendAllAvatars(ws);
                console.log(`~ ${username}/${deviceId} resumed (${clients.size} users online)`);
                broadcastAllUsers();
                sendGroupStatesOnConnect(username, ws);
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
            stmt.upsertDevice.run(username, deviceId, msg.deviceName || null, Date.now());
            const contactNicknames = stmt.getContactNicknames.all(username);
            send(ws, { type: 'auth-ok', appSecret: sharedAppSecret, admin: user.admin === 1, sessionToken: token, features: config.features, turnUsername: TURN_USERNAME, turnPassword: TURN_PASSWORD, publicKey: user.public_key || null, contactNicknames, profile: stmt.getMyProfile.get(username) || null, autoLocationPeers: stmt.getAutoLocationPeers.all(username).map(r => r.peer) });
            sendAllAvatars(ws);
            console.log(`+ ${username}/${deviceId} (${clients.size} users online)`);
            broadcastAllUsers();
            sendGroupStatesOnConnect(username, ws);
            flushQueue(username, ws);
            return;
        }

        // ---------------------------------------------------------------
        // Authenticated message handling
        // ---------------------------------------------------------------
        switch (msg.type) {

            case 'message': {
                if (!areContacts(msg.to, username)) {
                    const isBlocked = stmt.getBlock.get(msg.to, username);
                    if (isBlocked) {
                        if (msg.messageId) send(ws, { type: 'ack', messageId: msg.messageId });
                        break;
                    }
                    msg.isRequest = true;
                }
                if (msg.messageId != null) send(ws, { type: 'ack', messageId: msg.messageId });
                const ts = msg.timestamp ?? Date.now();
                appendMessage({ messageId: msg.messageId, from: msg.from, to: msg.to, content: msg.content, timestamp: ts, type: 'message', replyToId: msg.replyToId ?? null, replyToSender: msg.replyToSender ?? null, replyToContent: msg.replyToContent ?? null });
                const dmPayload = { type: 'message', from: msg.from, to: msg.to, content: msg.content, messageId: msg.messageId, timestamp: ts, isRequest: msg.isRequest ?? false, replyToId: msg.replyToId ?? null, replyToSender: msg.replyToSender ?? null, replyToContent: msg.replyToContent ?? null };
                const dmDelivered = deliverOrQueue(msg.to, dmPayload);
                if (!dmDelivered) {
                    console.log(`  queued message for offline or unreachable ${msg.to}`);
                    const toUser = stmt.getUser.get(msg.to);
                    if (toUser?.fcm_token) sendFcmWakeup(toUser.fcm_token);
                }
                break;
            }

            case 'file': {
                if (!areContacts(msg.to, username)) {
                    const isBlocked = stmt.getBlock.get(msg.to, username);
                    if (isBlocked) {
                        if (msg.messageId) send(ws, { type: 'ack', messageId: msg.messageId });
                        break;
                    }
                    msg.isRequest = true;
                }
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
                const msgKey = String(messageId);
                let record = stmt.getMessageByClientId.get(msgKey, username, username);
                if (!record) record = stmt.getMessage.get(msgKey);
                if (!record) { console.log(`  edit: record not found for message_id=${messageId}`); break; }
                if (record.from_user !== username) { console.log(`  edit: ${username} is not sender, rejected`); break; }
                const editedAt = Date.now();
                stmt.editMessage.run(newContent, editedAt, record.message_id, username);
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
                let record = stmt.getMessageByClientId.get(msgId, username, username);
                if (!record) record = stmt.getMessage.get(msgId);
                if (!record) { console.log(`  react: record not found for message_id=${msgId}`); break; }
                const realMsgId = record.message_id;
                if (emoji) {
                    stmt.upsertReaction.run(realMsgId, username, emoji, Date.now());
                } else {
                    stmt.deleteReaction.run(realMsgId, username);
                }
                const reactions = stmt.getReactions.all(realMsgId).map(r => ({ from: r.from_user, emoji: r.emoji }));
                const notice = { type: 'reactions', messageId, reactions };
                if (record.group_id) {
                    const grpMembers = stmt.getGroupMembers.all(record.group_id);
                    grpMembers.forEach(m => sendToAll(m.username, notice));
                    console.log(`  react: group ${record.group_id} fanout to ${grpMembers.length} members, count=${reactions.length}`);
                } else {
                    sendToAll(record.from_user, notice);
                    sendToAll(record.to_user, notice);
                    console.log(`  react: broadcast to ${record.from_user} and ${record.to_user}, count=${reactions.length}`);
                }
                break;
            }

            case 'delete': {
                const { messageId, forAll } = msg;
                console.log(`  delete request from ${username} for messageId ${messageId}`);
                if (!messageId || !forAll) { console.log('  delete: missing messageId or forAll, ignored'); break; }
                const msgKey = String(messageId);
                let record = stmt.getMessageByClientId.get(msgKey, username, username);
                if (!record) record = stmt.getMessage.get(msgKey);
                if (!record) { console.log(`  delete: record not found for message_id=${messageId}`); break; }
                console.log(`  delete: record found ${record.from_user} → ${record.to_user}`);
                stmt.setDeletedForAll.run(record.message_id);
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
                if (!areContacts(msg.to, username)) {
                    send(ws, { type: 'call-reject', from: msg.to, to: username, reason: 'not-contact' });
                    break;
                }
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
                if (!areContacts(msg.to, username)) {
                    send(ws, { type: 'call-reject', from: msg.to, to: username, reason: 'not-contact' });
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

            case 'set-auto-location': {
                const { peer, enabled } = msg;
                if (!peer) break;
                if (enabled) {
                    stmt.setAutoLocation.run(username, peer);
                } else {
                    stmt.clearAutoLocation.run(username, peer);
                }
                const peers = stmt.getAutoLocationPeers.all(username).map(r => r.peer);
                send(ws, { type: 'auto-location-peers', peers });
                break;
            }

            case 'get-auto-location': {
                const peers = stmt.getAutoLocationPeers.all(username).map(r => r.peer);
                send(ws, { type: 'auto-location-peers', peers });
                break;
            }

            case 'location-request': {
                const isContact = !!stmt.checkContactAccepted.get(msg.to, username);
                if (!isContact) {
                    send(ws, { type: 'location-error', reason: 'not-contact', to: msg.to });
                    break;
                }
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

            case 'group-avatar-upload': {
                const { groupId: gaGroupId, data: gaData } = msg;
                if (typeof gaGroupId !== 'string' || typeof gaData !== 'string' || gaData.length > 400_000) break;
                const gaGroup = stmt.getGroup.get(gaGroupId);
                if (!gaGroup) break;
                const gaMembers = stmt.getGroupMembers.all(gaGroupId);
                const gaMyMem = gaMembers.find(m => m.username === username);
                if (!gaMyMem || (gaMyMem.role !== 'owner' && gaMyMem.role !== 'admin')) break;
                try {
                    fs.writeFileSync(path.join(AVATARS_DIR, `group_${gaGroupId}.jpg`), Buffer.from(gaData, 'base64'));
                    const packet = { type: 'group-avatar', groupId: gaGroupId, data: gaData };
                    for (const m of gaMembers) {
                        if (isOnline(m.username)) sendToAll(m.username, packet);
                        else enqueue(m.username, packet);
                    }
                } catch (e) {
                    console.error('group-avatar-upload error', e);
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

            case 'set-secret-question': {
                const { question, answer } = msg;
                if (!question || question.trim().length < 4) {
                    send(ws, { type: 'secret-question-error', message: 'Question too short' }); break;
                }
                if (!answer || answer.trim().length < 2) {
                    send(ws, { type: 'secret-question-error', message: 'Answer too short' }); break;
                }
                const ansHash = bcrypt.hashSync(answer.trim().toLowerCase(), 10);
                stmt.setSecretQuestion.run(question.trim(), ansHash, username);
                send(ws, { type: 'secret-question-ok', message: 'Secret question saved' });
                break;
            }

            case 'get-secret-question': {
                const row = stmt.getSecretQuestion.get(username);
                send(ws, { type: 'my-secret-question', question: row?.secret_question || null });
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
                try { disk = execSync(`df -h ${BASE_DIR} 2>/dev/null`).toString().split('\n')[1]?.trim() || 'N/A'; } catch {}
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
                        username:   u.username,
                        createdAt:  u.created_at ? new Date(u.created_at).toISOString().slice(0, 10) : '',
                        admin:      u.admin === 1,
                        trustLevel: u.trust_level || 'contact',
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
                stmt.insertUser.run(newU, bcrypt.hashSync(newP, 10), Date.now(), null, null, null);
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

            case 'admin-set-trust': {
                const user = stmt.getUser.get(username);
                if (!user?.admin) { send(ws, { type: 'admin-error', message: 'Unauthorized' }); break; }
                const target = (msg.username || '').trim().toLowerCase();
                const trust  = msg.trustLevel;
                if (!target || !["family", "trusted", "contact", "stranger"].includes(trust)) {
                    send(ws, { type: 'admin-error', message: 'Invalid trust level' }); break;
                }
                if (!stmt.getUser.get(target)) { send(ws, { type: 'admin-error', message: 'User not found' }); break; }
                stmt.setUserTrustLevel.run(trust, target);
                broadcastAllUsers();
                send(ws, { type: 'admin-result', message: `Trust level for "${target}" set to ${trust}` });
                break;
            }


            case 'admin-invite-create': {
                if (!stmt.getUser.get(username)?.admin) break;
                const token = crypto.randomBytes(24).toString('hex');
                const expiresAt = Date.now() + 48 * 60 * 60 * 1000;
                stmt.createInvite.run(token, username, expiresAt);
                const baseUrl = config.publicUrl || 'https://shumkov.eu';
                send(ws, { type: 'admin-invite-created', token, url: `${baseUrl}/fshu5/invite/${token}`, expiresAt });
                console.log(`  invite created by ${username}: ${token.slice(0,8)}...`);
                break;
            }

            case 'admin-invite-list': {
                if (!stmt.getUser.get(username)?.admin) break;
                const baseUrl2 = config.publicUrl || 'https://shumkov.eu';
                const invites = stmt.listInvites.all().map(inv => ({ ...inv, url: `${baseUrl2}/fshu5/invite/${inv.token}` }));
                send(ws, { type: 'admin-invite-list', invites });
                break;
            }

            case 'admin-invite-revoke': {
                if (!stmt.getUser.get(username)?.admin) break;
                const { token: rToken } = msg;
                if (!rToken) break;
                stmt.revokeInvite.run(rToken);
                send(ws, { type: 'admin-result', message: 'Invite revoked' });
                break;
            }

            case 'export-request': {
                const exportToken = crypto.randomBytes(24).toString('hex');
                const expiresAt = Date.now() + 48 * 60 * 60 * 1000;
                exportTokens.set(exportToken, { username, expiresAt });
                const baseUrlExp = config.publicUrl || 'https://shumkov.eu';
                send(ws, { type: 'export-ready', url: `${baseUrlExp}/fshu5/export/${exportToken}` });
                console.log(`  export-request by ${username}`);
                break;
            }

            case 'delete-account': {
                const { currentPassword } = msg;
                if (!currentPassword) { send(ws, { type: 'delete-account-error', message: 'Password required' }); break; }
                const userForDel = stmt.getUser.get(username);
                const ok = bcrypt.compareSync(currentPassword, userForDel.password_hash);
                if (!ok) { send(ws, { type: 'delete-account-error', message: 'Wrong password' }); break; }
                stmt.anonymizeUser.run(username);
                stmt.deleteUserReactions.run(username);
                stmt.deleteUserSessions.run(username);
                stmt.deleteUserDevices.run(username);
                stmt.deleteUserNicknames.run(username);
                stmt.removeFromGroups.run(username);
                try { fs.unlinkSync(require('path').join(AVATARS_DIR, `${username}.jpg`)); } catch {}
                stmt.deleteUserData.run(username);
                send(ws, { type: 'delete-account-ok' });
                ws.close();
                clients.get(username)?.delete(ws.deviceId);
                broadcastAllUsers();
                console.log(`  account deleted: ${username}`);
                break;
            }

            case 'device-list': {
                const devices = stmt.getDevices.all(username);
                send(ws, { type: 'device-list', devices, currentDeviceId: ws.deviceId });
                break;
            }

            case 'device-remove': {
                const { deviceId: removeId } = msg;
                if (!removeId || removeId === ws.deviceId) break;
                stmt.removeDevice.run(username, removeId);
                stmt.deleteDeviceSessionByDeviceId.run(username, removeId);
                const userDevicesMap = clients.get(username);
                if (userDevicesMap) {
                    const targetWs = userDevicesMap.get(removeId);
                    if (targetWs) { targetWs.close(); userDevicesMap.delete(removeId); }
                }
                send(ws, { type: 'device-list', devices: stmt.getDevices.all(username), currentDeviceId: ws.deviceId });
                console.log(`  device-remove: ${username} removed ${removeId}`);
                break;
            }

            case 'device-rename': {
                const { deviceName: newName } = msg;
                if (!newName || newName.trim().length === 0) break;
                stmt.renameDevice.run(newName.trim(), username, ws.deviceId);
                send(ws, { type: 'device-list', devices: stmt.getDevices.all(username), currentDeviceId: ws.deviceId });
                break;
            }

            case 'public-key': {
                const key = (msg.publicKey || '').trim();
                if (key) {
                    const existingUser = db.prepare('SELECT public_key FROM users WHERE username = ?').get(username);
                    const keyChanged = existingUser && existingUser.public_key && existingUser.public_key !== key;
                    stmt.updatePublicKey.run(key, username);
                    console.log(`  public key stored for ${username}`);
                    if (keyChanged) {
                        const affectedGroups = db.prepare(
                            'SELECT gm.group_id, g.owner FROM group_members gm JOIN groups g ON g.group_id = gm.group_id WHERE gm.username = ?'
                        ).all(username);
                        for (const grp of affectedGroups) {
                            db.prepare(
                                'UPDATE group_members SET encrypted_group_key = NULL WHERE group_id = ? AND username = ?'
                            ).run(grp.group_id, username);
                            const payload = { type: 'group-key-needed', groupId: grp.group_id, forUser: username };
                            if (isOnline(grp.owner)) {
                                sendToAll(grp.owner, payload);
                            } else {
                                enqueue(grp.owner, payload);
                            }
                        }
                        console.log(`  key changed for ${username}, notified owners of ${affectedGroups.length} group(s)`);
                    }
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

            case 'group-create': {
                const { groupId, name, groupType, members } = msg;
                if (!groupId || !name || !Array.isArray(members) || members.length === 0) break;
                let memberError = null;
                for (const m of members) {
                    if (m.username !== username && !stmt.getUser.get(m.username)) {
                        memberError = m.username; break;
                    }
                }
                if (memberError) { send(ws, { type: 'group-error', groupId, reason: `user-not-found:${memberError}` }); break; }
                const now = Date.now();
                try {
                    stmt.createGroup.run(groupId, name.slice(0, 100), username, groupType || 'group', now);
                } catch {
                    send(ws, { type: 'group-error', groupId, reason: 'already-exists' }); break;
                }
                const creatorEntry = members.find(m => m.username === username);
                stmt.addGroupMember.run(groupId, username, 'owner', now, creatorEntry?.encryptedKey || null);
                for (const m of members) {
                    if (m.username === username) continue;
                    stmt.addGroupMember.run(groupId, m.username, 'member', now, m.encryptedKey || null);
                }
                if ((groupType || 'group') === 'family') {
                    const allMembers = stmt.getGroupMembers.all(groupId);
                    for (const m of allMembers) stmt.setUserTrustLevel.run('family', m.username);
                }
                broadcastGroupState(groupId);
                console.log(`  group-create: ${groupId} "${name}" by ${username}, ${members.length} member(s)`);
                break;
            }

            case 'group-message': {
                const { groupId, content, messageId: tempId, timestamp } = msg;
                if (!groupId || !content) break;
                const members = stmt.getGroupMembers.all(groupId);
                if (!members.find(m => m.username === username)) {
                    send(ws, { type: 'group-error', groupId, reason: 'not-a-member' }); break;
                }
                const serverMsgId = crypto.randomUUID();
                const ts = timestamp ?? Date.now();
                stmt.insertGroupMessage.run(serverMsgId, username, groupId, content, ts, 'message',
                    msg.replyToId ?? null, msg.replyToSender ?? null, msg.replyToContent ?? null);
                send(ws, { type: 'ack', tempId: tempId ?? null, messageId: serverMsgId, timestamp: ts });
                const outMsg = {
                    type: 'group-message', groupId, from: username, content,
                    messageId: serverMsgId, timestamp: ts,
                    replyToId: msg.replyToId ?? null,
                    replyToSender: msg.replyToSender ?? null,
                    replyToContent: msg.replyToContent ?? null,
                };
                fanOutGroupMessage(groupId, outMsg, username, ws.deviceId);
                console.log(`  group-message: ${serverMsgId} → ${groupId} from ${username}`);
                break;
            }

            case 'group-delivered': {
                const { groupId, messageId } = msg;
                if (!groupId || !messageId) break;
                const members = stmt.getGroupMembers.all(groupId);
                if (!members.find(m => m.username === username)) break;
                const record = stmt.getMessage.get(String(messageId));
                if (!record) break;
                if (!groupDeliveryTracker.has(messageId)) groupDeliveryTracker.set(messageId, new Set());
                groupDeliveryTracker.get(messageId).add(username);
                const nonSenderMembers = members.filter(m => m.username !== record.from_user);
                const deliveredSet = groupDeliveryTracker.get(messageId);
                if (nonSenderMembers.length > 0 && nonSenderMembers.every(m => deliveredSet.has(m.username))) {
                    sendToAll(record.from_user, { type: 'group-delivery-update', messageId, groupId, status: 'delivered' });
                    groupDeliveryTracker.delete(messageId);
                }
                break;
            }

            case 'group-read': {
                const { groupId, messageId } = msg;
                if (!groupId || !messageId) break;
                const members = stmt.getGroupMembers.all(groupId);
                if (!members.find(m => m.username === username)) break;
                const record = stmt.getMessage.get(String(messageId));
                if (!record) break;
                sendToAll(record.from_user, { type: 'group-delivery-update', messageId, groupId, status: 'read' });
                break;
            }

            case 'group-invite': {
                const { groupId, username: invitee, encryptedKey } = msg;
                if (!groupId || !invitee) break;
                const group = stmt.getGroup.get(groupId);
                if (!group) { send(ws, { type: 'group-error', groupId, reason: 'not-found' }); break; }
                const members = stmt.getGroupMembers.all(groupId);
                const senderMem = members.find(m => m.username === username);
                if (!senderMem || !['owner', 'admin'].includes(senderMem.role)) {
                    send(ws, { type: 'group-error', groupId, reason: 'unauthorized' }); break;
                }
                if (!stmt.getUser.get(invitee)) {
                    send(ws, { type: 'group-error', groupId, reason: 'user-not-found' }); break;
                }
                if (members.find(m => m.username === invitee)) {
                    send(ws, { type: 'group-error', groupId, reason: 'already-member' }); break;
                }
                stmt.addGroupMember.run(groupId, invitee, 'member', Date.now(), encryptedKey || null);
                if (group.type === 'family') {
                    const allMembers = stmt.getGroupMembers.all(groupId);
                    for (const m of allMembers) stmt.setUserTrustLevel.run('family', m.username);
                }
                broadcastGroupState(groupId);
                console.log(`  group-invite: ${invitee} added to ${groupId} by ${username}`);
                break;
            }

            case 'group-kick': {
                const { groupId, username: target } = msg;
                if (!groupId || !target) break;
                const kickedGroup = stmt.getGroup.get(groupId);
                if (!kickedGroup) break;
                const members = stmt.getGroupMembers.all(groupId);
                const senderMem = members.find(m => m.username === username);
                if (!senderMem || !['owner', 'admin'].includes(senderMem.role)) {
                    send(ws, { type: 'group-error', groupId, reason: 'unauthorized' }); break;
                }
                const targetMem = members.find(m => m.username === target);
                if (!targetMem) { send(ws, { type: 'group-error', groupId, reason: 'not-a-member' }); break; }
                if (targetMem.role === 'owner') { send(ws, { type: 'group-error', groupId, reason: 'cannot-kick-owner' }); break; }
                stmt.removeGroupMember.run(groupId, target);
                if (kickedGroup.type === 'family') {
                    if (!stmt.getMemberFamilyGroups.get(target)) stmt.setUserTrustLevel.run('contact', target);
                }
                const removedMsg = { type: 'group-removed', groupId, reason: 'kicked' };
                if (isOnline(target)) { sendToAll(target, removedMsg); } else { enqueue(target, removedMsg); }
                broadcastGroupState(groupId);
                console.log(`  group-kick: ${target} removed from ${groupId} by ${username}`);
                break;
            }

            case 'group-leave': {
                const { groupId } = msg;
                if (!groupId) break;
                const leftGroup = stmt.getGroup.get(groupId);
                if (!leftGroup) break;
                const members = stmt.getGroupMembers.all(groupId);
                const myMem = members.find(m => m.username === username);
                if (!myMem) break;
                if (myMem.role === 'owner') { send(ws, { type: 'group-error', groupId, reason: 'owner-cannot-leave' }); break; }
                stmt.removeGroupMember.run(groupId, username);
                if (leftGroup.type === 'family') {
                    if (!stmt.getMemberFamilyGroups.get(username)) stmt.setUserTrustLevel.run('contact', username);
                }
                sendToAll(username, { type: 'group-removed', groupId, reason: 'left' });
                broadcastGroupState(groupId);
                console.log(`  group-leave: ${username} left ${groupId}`);
                break;
            }

            case 'group-delete': {
                const { groupId } = msg;
                if (!groupId) break;
                const group = stmt.getGroup.get(groupId);
                if (!group) break;
                if (group.owner !== username) {
                    send(ws, { type: 'group-error', groupId, reason: 'unauthorized' });
                    break;
                }
                const members = stmt.getGroupMembers.all(groupId);
                stmt.deleteGroupMembers.run(groupId);
                stmt.deleteGroup.run(groupId);
                if (group.type === 'family') {
                    for (const m of members) {
                        if (!stmt.getMemberFamilyGroups.get(m.username)) stmt.setUserTrustLevel.run('contact', m.username);
                    }
                }
                const deletedMsg = { type: 'group-removed', groupId, reason: 'deleted' };
                for (const m of members) {
                    if (isOnline(m.username)) { sendToAll(m.username, deletedMsg); }
                    else { enqueue(m.username, deletedMsg); }
                }
                console.log();
                break;
            }

            case 'group-key-rotate': {
                const { groupId, keys } = msg;
                if (!groupId || !Array.isArray(keys)) break;
                const members = stmt.getGroupMembers.all(groupId);
                const senderMem = members.find(m => m.username === username);
                if (!senderMem || !['owner', 'admin'].includes(senderMem.role)) {
                    send(ws, { type: 'group-error', groupId, reason: 'unauthorized' }); break;
                }
                for (const k of keys) {
                    if (k.username && k.encryptedKey) stmt.updateGroupMemberKey.run(k.encryptedKey, groupId, k.username);
                }
                const update = { type: 'group-key-update', groupId };
                for (const m of members) {
                    if (isOnline(m.username)) { sendToAll(m.username, update); } else { enqueue(m.username, update); }
                }
                console.log(`  group-key-rotate: ${groupId} by ${username}, ${keys.length} key(s) updated`);
                break;
            }

            case 'group-key-submit': {
                const { groupId, forUser, encryptedKey } = msg;
                if (!groupId || !forUser || !encryptedKey) break;
                const senderRole = db.prepare(
                    'SELECT role FROM group_members WHERE group_id = ? AND username = ?'
                ).get(groupId, username);
                if (!senderRole || (senderRole.role !== 'owner' && senderRole.role !== 'admin')) break;
                db.prepare(
                    'UPDATE group_members SET encrypted_group_key = ? WHERE group_id = ? AND username = ?'
                ).run(encryptedKey, groupId, forUser);
                const update = { type: 'group-key-update', groupId };
                if (isOnline(forUser)) {
                    sendToAll(forUser, update);
                } else {
                    enqueue(forUser, update);
                }
                console.log(`  group-key-submit: key for ${forUser} in ${groupId} by ${username}`);
                break;
            }

            case 'group-promote': {
                const { groupId, username: target, role: newRole } = msg;
                if (!groupId || !target || !['owner', 'admin', 'member'].includes(newRole)) break;
                if (!stmt.getGroup.get(groupId)) break;
                const members = stmt.getGroupMembers.all(groupId);
                const senderMem = members.find(m => m.username === username);
                if (!senderMem) break;
                const targetMem = members.find(m => m.username === target);
                if (!targetMem) { send(ws, { type: 'group-error', groupId, reason: 'not-a-member' }); break; }
                if (newRole === 'owner') {
                    if (senderMem.role !== 'owner') { send(ws, { type: 'group-error', groupId, reason: 'unauthorized' }); break; }
                    stmt.updateGroupMemberRole.run('admin', groupId, username);
                    stmt.updateGroupOwner.run(target, groupId);
                    stmt.updateGroupMemberRole.run('owner', groupId, target);
                } else {
                    if (!['owner', 'admin'].includes(senderMem.role)) { send(ws, { type: 'group-error', groupId, reason: 'unauthorized' }); break; }
                    stmt.updateGroupMemberRole.run(newRole, groupId, target);
                }
                broadcastGroupState(groupId);
                console.log(`  group-promote: ${target} → ${newRole} in ${groupId} by ${username}`);
                break;
            }

            case 'group-rename': {
                const { groupId, name } = msg;
                if (!groupId || !name) break;
                const members = stmt.getGroupMembers.all(groupId);
                const senderMem = members.find(m => m.username === username);
                if (!senderMem || !['owner', 'admin'].includes(senderMem.role)) {
                    send(ws, { type: 'group-error', groupId, reason: 'unauthorized' }); break;
                }
                stmt.updateGroupName.run(name.slice(0, 100), groupId);
                broadcastGroupState(groupId);
                console.log(`  group-rename: ${groupId} → "${name}" by ${username}`);
                break;
            }

            case 'group-info-request': {
                const { groupId } = msg;
                if (!groupId) break;
                const group = stmt.getGroup.get(groupId);
                if (!group) { send(ws, { type: 'group-error', groupId, reason: 'not-found' }); break; }
                const members = stmt.getGroupMembers.all(groupId);
                const myMem = members.find(m => m.username === username);
                if (!myMem) { send(ws, { type: 'group-error', groupId, reason: 'not-a-member' }); break; }
                const infoAvatarFile = path.join(AVATARS_DIR, `group_${groupId}.jpg`);
                const infoAvatarData = fs.existsSync(infoAvatarFile) ? fs.readFileSync(infoAvatarFile).toString('base64') : null;
                send(ws, {
                    type:              'group-state',
                    groupId:           group.group_id,
                    name:              group.name,
                    groupType:         group.type,
                    owner:             group.owner,
                    members:           members.map(m => ({ username: m.username, role: m.role, joinedAt: m.joined_at })),
                    encryptedGroupKey: myMem.encrypted_group_key || null,
                    avatarData:        infoAvatarData,
                });
                break;
            }

            case 'group-history-request': {
                const { groupId, since } = msg;
                if (!groupId) break;
                const members = stmt.getGroupMembers.all(groupId);
                if (!members.find(m => m.username === username)) {
                    send(ws, { type: 'group-error', groupId, reason: 'not-a-member' }); break;
                }
                const maxMs   = config.maxHistoryRequestDays * 24 * 60 * 60 * 1000;
                const sinceTs = Math.max(since || 0, Date.now() - maxMs);
                const rows    = stmt.getGroupHistory.all(groupId, sinceTs);
                const messages = rows.map(r => ({
                    messageId: r.message_id,
                    from:      r.from_user,
                    groupId:   r.group_id,
                    content:   r.content,
                    timestamp: r.timestamp,
                    type:      r.type,
                }));
                send(ws, { type: 'group-history-response', groupId, messages });
                console.log('  group-history-request: ' + groupId + ' by ' + username + ', ' + messages.length + ' msg(s)');
                break;
            }

            case 'user-search': {
                const { query, offset = 0 } = msg;
                if (!query || query.trim().length < 3) {
                    send(ws, { type: 'user-search-result', users: [], query, offset, hasMore: false });
                    break;
                }
                if (!checkRateLimit(searchRateLimit, username, 20, 60 * 60 * 1000)) {
                    send(ws, { type: 'error', message: 'Search rate limit exceeded. Try again later.' });
                    break;
                }
                const q = query.trim();
                const like = `%${q}%`;
                const results = stmt.searchUsers.all(username, like, like, offset);
                let extra = null;
                if (q.includes('@')) extra = stmt.searchByEmail.get(username, q);
                else if (q.startsWith('+')) extra = stmt.searchByPhone.get(username, q);
                if (extra && !results.find(r => r.username === extra.username)) {
                    results.unshift(extra);
                    if (results.length > 10) results.pop();
                }
                const users = results.map(u => {
                    const contactStatus = stmt.getContactStatus.get(username, u.username);
                    const isBlocked = stmt.getBlock.get(username, u.username);
                    return {
                        username: u.username,
                        nickname: u.show_nickname ? u.nickname : null,
                        hasAvatar: u.show_avatar === 1 && !!u.avatar_path,
                        contactStatus: contactStatus?.status || null,
                        isBlocked: !!isBlocked
                    };
                });
                send(ws, { type: 'user-search-result', users, query: q, offset, hasMore: results.length === 10 });
                break;
            }

            case 'user-profile': {
                const { targetUsername } = msg;
                if (!targetUsername) break;
                const isContact = stmt.getContactStatus.get(username, targetUsername)?.status === 'accepted';
                if (isContact) {
                    const profile = stmt.getUserContactsProfile.get(targetUsername);
                    send(ws, { type: 'user-profile', profile, tier: 'contact' });
                } else {
                    const profile = stmt.getUserPublicProfile.get(targetUsername);
                    if (!profile) { send(ws, { type: 'user-profile', profile: null }); break; }
                    send(ws, { type: 'user-profile', profile: {
                        username: profile.username,
                        nickname: profile.show_nickname ? profile.nickname : null,
                        hasAvatar: profile.show_avatar === 1 && !!profile.avatar_path,
                        bio: profile.bio || null
                    }, tier: 'public' });
                }
                break;
            }

            case 'contact-request': {
                const { targetUsername, message: introMessage } = msg;
                if (!targetUsername || targetUsername === username) break;
                if (!checkRateLimit(requestRateLimit, username, 10, 60 * 60 * 1000)) {
                    send(ws, { type: 'error', message: 'Contact request rate limit exceeded. Try again later.' });
                    break;
                }
                const isBlockedByTarget = stmt.getBlock.get(targetUsername, username);
                if (isBlockedByTarget) {
                    send(ws, { type: 'contact-request-sent', targetUsername });
                    break;
                }
                const now = Date.now();
                const expires = now + 90 * 24 * 60 * 60 * 1000;
                stmt.upsertContactRequest.run(username, targetUsername, now, now, expires);
                send(ws, { type: 'contact-request-sent', targetUsername });
                const requestorProfile = stmt.getUserPublicProfile.get(username);
                if (isOnline(targetUsername)) {
                    sendToAll(targetUsername, {
                        type: 'contact-request-received',
                        from: username,
                        nickname: requestorProfile?.show_nickname ? requestorProfile?.nickname : null,
                        hasAvatar: requestorProfile?.show_avatar === 1 && !!requestorProfile?.avatar_path,
                        introMessage: introMessage || null,
                        createdAt: now,
                        expiresAt: expires
                    });
                }
                if (introMessage && introMessage.trim().length > 0) {
                    const messageId = crypto.randomUUID();
                    const payload = {
                        type: 'message',
                        from: username,
                        to: targetUsername,
                        content: introMessage.trim(),
                        messageId,
                        timestamp: now,
                        isRequest: true
                    };
                    if (isOnline(targetUsername)) {
                        sendToAll(targetUsername, payload);
                    } else {
                        enqueue(targetUsername, payload);
                    }
                }
                break;
            }

            case 'contact-accept': {
                const { targetUsername } = msg;
                if (!targetUsername) break;
                const now = Date.now();
                stmt.upsertContactRequest.run(username, targetUsername, now, now, 0);
                stmt.acceptContact.run(now, username, targetUsername);
                stmt.upsertContactRequest.run(targetUsername, username, now, now, 0);
                stmt.acceptContact.run(now, targetUsername, username);
                send(ws, { type: 'contact-accepted', targetUsername });
                if (isOnline(targetUsername)) {
                    sendToAll(targetUsername, { type: 'contact-accepted', targetUsername: username });
                }
                break;
            }

            case 'contact-decline': {
                const { targetUsername } = msg;
                if (!targetUsername) break;
                db.prepare('DELETE FROM contacts WHERE owner = ? AND contact = ?')
                    .run(targetUsername, username);
                send(ws, { type: 'contact-declined', targetUsername });
                break;
            }

            case 'contact-cancel': {
                const { targetUsername } = msg;
                if (!targetUsername) break;
                db.prepare('DELETE FROM contacts WHERE owner = ? AND contact = ? AND status = ?')
                    .run(username, targetUsername, 'pending');
                send(ws, { type: 'contact-cancelled', targetUsername });
                break;
            }

            case 'contact-remove': {
                const { targetUsername } = msg;
                if (!targetUsername) break;
                stmt.deleteContact.run(username, targetUsername, targetUsername, username);
                send(ws, { type: 'contact-removed', targetUsername });
                if (isOnline(targetUsername)) {
                    sendToAll(targetUsername, { type: 'contact-removed', targetUsername: username });
                }
                break;
            }

            case 'contact-list': {
                const contacts = stmt.getContacts.all(username);
                const pendingReceived = stmt.getPendingReceived.all(username);
                const pendingSent = stmt.getPendingSent.all(username);
                send(ws, { type: 'contact-list', contacts, pendingReceived, pendingSent });
                break;
            }

            case 'get-users': {
                const guContacts = stmt.getContacts.all(username).map(c => c.contact);
                const guContactUsers = guContacts.length > 0
                    ? db.prepare('SELECT username, nickname, last_seen, public_key, trust_level FROM users WHERE username IN (' + guContacts.map(() => '?').join(',') + ')')
                        .all(...guContacts)
                    : [];
                const guSelf = stmt.getUser.get(username);
                const guUserList = [guSelf, ...guContactUsers].map(u => ({
                    username:   u.username,
                    online:     isOnline(u.username),
                    lastSeen:   isOnline(u.username) ? null : (u.last_seen || null),
                    nickname:   u.nickname || null,
                    publicKey:  u.public_key || null,
                    trustLevel: u.trust_level || 'contact',
                }));
                const guPending = db.prepare("SELECT COUNT(*) as cnt FROM contacts WHERE contact = ? AND status = 'pending'").get(username)?.cnt || 0;
                send(ws, { type: 'users', users: guUserList, pendingRequests: guPending });
                break;
            }

            case 'block-user': {
                const { targetUsername } = msg;
                if (!targetUsername || targetUsername === username) break;
                stmt.insertBlock.run(username, targetUsername, Date.now());
                stmt.deleteContact.run(username, targetUsername, targetUsername, username);
                send(ws, { type: 'block-confirmed', targetUsername });
                if (isOnline(targetUsername)) {
                    sendToAll(targetUsername, { type: 'contact-removed', targetUsername: username });
                }
                break;
            }

            case 'unblock-user': {
                const { targetUsername } = msg;
                if (!targetUsername) break;
                stmt.removeBlock.run(username, targetUsername);
                send(ws, { type: 'unblock-confirmed', targetUsername });
                break;
            }

            case 'block-list': {
                const blocks = stmt.getBlockList.all(username);
                send(ws, { type: 'block-list', blocks });
                break;
            }

            case 'update-privacy': {
                const { discoverable, showAvatar, showNickname, emailSearchable, phoneSearchable } = msg;
                stmt.updatePrivacy.run(
                    discoverable ? 1 : 0,
                    showAvatar ? 1 : 0,
                    showNickname ? 1 : 0,
                    emailSearchable ? 1 : 0,
                    phoneSearchable ? 1 : 0,
                    username
                );
                send(ws, { type: 'privacy-updated' });
                break;
            }

            case 'get-my-profile': {
                const profile = stmt.getMyProfile.get(username);
                send(ws, { type: 'my-profile', profile });
                break;
            }

            case 'update-profile': {
                const { bio, email, phone } = msg;
                if (email) {
                    const existing = db.prepare('SELECT username FROM users WHERE email = ? AND username != ?')
                                       .get(email, username);
                    if (existing) {
                        send(ws, { type: 'profile-error', message: 'Email already in use' });
                        break;
                    }
                }
                stmt.updateProfile.run(bio || null, email || null, phone || null, username);
                send(ws, { type: 'profile-updated' });
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

