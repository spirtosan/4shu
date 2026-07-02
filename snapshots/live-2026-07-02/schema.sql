CREATE TABLE sessions (
  token           TEXT PRIMARY KEY,
  username        TEXT NOT NULL,
  device_id       TEXT NOT NULL DEFAULT '',
  created_at      INTEGER NOT NULL,
  last_seen       INTEGER
);
CREATE INDEX idx_sessions_username ON sessions(username);
CREATE TABLE devices (
  username        TEXT NOT NULL,
  device_id       TEXT NOT NULL,
  device_name     TEXT,
  fcm_token       TEXT,
  last_seen       INTEGER,
  PRIMARY KEY (username, device_id)
);
CREATE TABLE queue (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  username        TEXT NOT NULL,
  envelope        TEXT NOT NULL,
  created_at      INTEGER NOT NULL
);
CREATE TABLE sqlite_sequence(name,seq);
CREATE INDEX idx_queue_username ON queue(username);
CREATE TABLE messages (
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
, client_id TEXT);
CREATE INDEX idx_messages_pair  ON messages(from_user, to_user, timestamp);
CREATE INDEX idx_messages_group ON messages(group_id,  timestamp);
CREATE TABLE files (
  file_id         TEXT PRIMARY KEY,
  uploader        TEXT NOT NULL,
  filename        TEXT NOT NULL,
  mime_type       TEXT,
  file_path       TEXT NOT NULL,
  size_bytes      INTEGER,
  created_at      INTEGER NOT NULL,
  expires_at      INTEGER
, nonce TEXT, meta_json TEXT);
CREATE TABLE reactions (
  message_id      TEXT NOT NULL,
  from_user       TEXT NOT NULL,
  emoji           TEXT NOT NULL,
  timestamp       INTEGER NOT NULL,
  PRIMARY KEY (message_id, from_user)
);
CREATE TABLE groups (
  group_id        TEXT PRIMARY KEY,
  name            TEXT NOT NULL,
  owner           TEXT NOT NULL,
  type            TEXT DEFAULT 'group',
  created_at      INTEGER NOT NULL,
  avatar_path     TEXT
);
CREATE TABLE group_members (
  group_id              TEXT NOT NULL,
  username              TEXT NOT NULL,
  role                  TEXT DEFAULT 'member',
  joined_at             INTEGER NOT NULL,
  encrypted_group_key   TEXT,
  PRIMARY KEY (group_id, username)
);
CREATE TABLE contact_nicknames (
  owner           TEXT NOT NULL,
  contact         TEXT NOT NULL,
  nickname        TEXT NOT NULL,
  PRIMARY KEY (owner, contact)
);
CREATE TABLE lists (
  list_id         TEXT PRIMARY KEY,
  owner           TEXT NOT NULL,
  peer            TEXT,
  group_id        TEXT,
  version         INTEGER DEFAULT 1,
  created_at      INTEGER NOT NULL,
  message_id      TEXT
);
CREATE TABLE list_items (
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
CREATE TABLE invites (
  token           TEXT PRIMARY KEY,
  created_by      TEXT NOT NULL,
  used_by         TEXT,
  expires_at      INTEGER,
  used_at         INTEGER
);
CREATE TABLE blocks (
  owner       TEXT NOT NULL,
  blocked     TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (owner, blocked)
);
CREATE TABLE password_resets (token TEXT PRIMARY KEY, username TEXT NOT NULL, expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL, used_at INTEGER);
CREATE TABLE auto_location (owner TEXT NOT NULL, peer TEXT NOT NULL, PRIMARY KEY (owner, peer));
CREATE INDEX idx_messages_client_id ON messages(client_id);
CREATE TABLE IF NOT EXISTS "contacts" (
  owner       TEXT NOT NULL,
  contact     TEXT NOT NULL,
  status      TEXT DEFAULT 'pending',
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  allow_emergency_call     INTEGER,
  allow_emergency_location INTEGER,
  PRIMARY KEY (owner, contact)
);
CREATE INDEX idx_contacts_owner   ON contacts(owner,   status);
CREATE INDEX idx_contacts_contact ON contacts(contact, status);
CREATE TABLE IF NOT EXISTS "users" (
  username        TEXT PRIMARY KEY,
  password_hash   TEXT NOT NULL,
  admin           INTEGER DEFAULT 0,
  nickname        TEXT,
  fcm_token       TEXT,
  avatar_path     TEXT,
  last_seen       INTEGER,
  created_at      INTEGER NOT NULL,
  public_key      TEXT,
  status          TEXT DEFAULT 'active',
  email           TEXT,
  phone           TEXT,
  bio             TEXT,
  discoverable    INTEGER DEFAULT 1,
  show_avatar     INTEGER DEFAULT 1,
  show_nickname   INTEGER DEFAULT 1,
  email_searchable INTEGER DEFAULT 1,
  phone_searchable INTEGER DEFAULT 1,
  secret_question TEXT,
  secret_answer_hash TEXT
, hide_presence INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS "mutes" (
  owner       TEXT NOT NULL,
  target      TEXT NOT NULL,
  target_type TEXT NOT NULL DEFAULT 'contact',
  created_at  INTEGER NOT NULL,
  mute_until  INTEGER,
  PRIMARY KEY (owner, target, target_type)
);
