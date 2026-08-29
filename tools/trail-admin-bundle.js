#!/usr/bin/env node
/*
 * tools/trail-admin-bundle.js — T13 Trail Phase 2, admin export bundle for offline/browser decrypt.
 *
 * Packages one user's admin-recipient trail batches (ciphertext only) together with the
 * wrapped admin private key (trailAdmins entry from config.json) and the target user's
 * public key (users table) into a single JSON bundle. The bundle carries NO passphrase
 * and cannot be decrypted without one — it is safe to move off the server, e.g. into
 * tools/trail-viewer.html's "Load export / trail JSON…" picker, which detects the bundle
 * and decrypts it entirely in the browser (passphrase never leaves the viewing machine).
 * See trailUnwrapAdmin / trailConvKey / trailDecryptBatch in server.js — this script and
 * the viewer's in-browser decrypt both reproduce that same scheme.
 *
 * Run ON the server (needs DB + config.json access):
 *   node tools/trail-admin-bundle.js <username> [--admin-id __admin__] [--out file.json]
 *
 * FSHU_BASE_DIR overrides the default /opt/fshu5 (matches server.js).
 */
'use strict';
const fs = require('fs');
const path = require('path');
const Database = require('better-sqlite3');

const BASE_DIR    = process.env.FSHU_BASE_DIR || '/opt/fshu5';
const DB_PATH     = path.join(BASE_DIR, 'data', 'fshu.db');
const CONFIG_FILE = path.join(BASE_DIR, 'data', 'config.json');

function argVal(flag, def) {
  const i = process.argv.indexOf(flag);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : def;
}

const username = process.argv[2];
if (!username || username.startsWith('--')) {
  console.error('Usage: node tools/trail-admin-bundle.js <username> [--admin-id __admin__] [--out file.json]');
  process.exit(1);
}
const adminId = argVal('--admin-id', '__admin__');
const outPath = argVal('--out', `${username}_trail_bundle.json`);

const config = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
const adminEntry = (config.trailAdmins || []).find(a => a.id === adminId);
if (!adminEntry) {
  console.error(`No trailAdmins entry with id "${adminId}" in ${CONFIG_FILE}.`);
  process.exit(1);
}

const db = new Database(DB_PATH, { readonly: true });
const user = db.prepare('SELECT public_key FROM users WHERE username = ?').get(username);
if (!user || !user.public_key) {
  console.error(`User "${username}" not found, or has no public_key on file.`);
  db.close();
  process.exit(1);
}

const rows = db.prepare(`SELECT device, batch_id, seq_lo, seq_hi, ts_lo, ts_hi, server_ts, iv, ct
                          FROM trail_batches WHERE user = ? AND guardian = ? ORDER BY server_ts ASC`)
                .all(username, adminId);
db.close();

if (!rows.length) {
  console.error(`No trail_batches rows for user="${username}" guardian="${adminId}". Nothing to bundle.`);
  process.exit(1);
}

const bundle = {
  kind:             'fshu-trail-encrypted-bundle-v1',
  username,
  adminId,
  userPublicKeyHex: user.public_key,
  adminWraps:       adminEntry.wraps,   // still passphrase-protected — same protection as config.json
  batches: rows.map(r => ({
    device: r.device, batchId: r.batch_id,
    seqLo: r.seq_lo, seqHi: r.seq_hi, tsLo: r.ts_lo, tsHi: r.ts_hi, serverTs: r.server_ts,
    iv: r.iv, ct: r.ct,
  })),
  exportedAt: new Date().toISOString(),
};

fs.writeFileSync(outPath, JSON.stringify(bundle));
console.log(`Wrote ${rows.length} batch(es) for "${username}" (recipient "${adminId}") -> ${outPath}`);
console.log('Still requires the admin passphrase to decrypt (open it in tools/trail-viewer.html). Safe to move, not safe to publish.');
