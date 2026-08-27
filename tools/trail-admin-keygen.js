#!/usr/bin/env node
/*
 * tools/trail-admin-keygen.js  —  T13 Trail Phase 2, one-time admin key mint.
 *
 * Generates ONE admin recipient keypair for the trail (SPEC_T13_PHASE2_SERVER_PERSISTENCE.md).
 * The trail is encrypted to this admin (in addition to each guardian); the admin PRIVATE
 * key never leaves as plaintext — it is wrapped under one or more passphrases (scrypt +
 * AES-256-GCM) and stored inside the "trailAdmins" array in data/config.json.
 *
 *   - "daily"    passphrase: what an admin types to view trails day to day.
 *   - "recovery" passphrase: kept OFFLINE (safe / password manager). If the daily
 *                passphrase is ever lost, the recovery one still unlocks the SAME key,
 *                so no trail data becomes unreadable.  (Ivan's dual-passphrase choice.)
 *
 * The passphrases themselves are NEVER stored. Dependency-free: Node built-in crypto only.
 *
 * Usage (run once, on the trusted server/admin machine):
 *     node tools/trail-admin-keygen.js [--id __admin__]
 *   Passphrases are read interactively (hidden), or from env for automation:
 *     TRAIL_ADMIN_PASS=... TRAIL_ADMIN_RECOVERY_PASS=... node tools/trail-admin-keygen.js
 *
 * It PRINTS a JSON object to paste into config.json's "trailAdmins": [ <here> ].
 * It does not write config.json itself (so you review before it lands).
 */
'use strict';
const crypto   = require('crypto');
const readline = require('readline');

const SCRYPT = { N: 1 << 15, r: 8, p: 1, keyLen: 32, maxmem: 64 * 1024 * 1024 };

function argVal(flag, def) {
  const i = process.argv.indexOf(flag);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : def;
}

// Hidden interactive prompt (no echo). Falls back to env vars for automation.
function prompt(question, envVar) {
  if (process.env[envVar]) return Promise.resolve(process.env[envVar]);
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    const onData = (ch) => {
      ch = ch + '';
      if (ch === '\n' || ch === '\r' || ch === '') process.stdout.write('\n');
      else process.stdout.write('*');
    };
    process.stdin.on('data', onData);
    rl.question(question, (ans) => { process.stdin.removeListener('data', onData); rl.close(); resolve(ans); });
  });
}

// Wrap the pkcs8-DER private key under one passphrase: scrypt(salt) -> AES-256-GCM.
function wrap(label, privDer, passphrase) {
  const salt  = crypto.randomBytes(16);
  const key   = crypto.scryptSync(passphrase, salt, SCRYPT.keyLen,
                  { N: SCRYPT.N, r: SCRYPT.r, p: SCRYPT.p, maxmem: SCRYPT.maxmem });
  const nonce = crypto.randomBytes(12);
  const c     = crypto.createCipheriv('aes-256-gcm', key, nonce);
  const ct    = Buffer.concat([c.update(privDer), c.final()]);
  const tag   = c.getAuthTag();
  return {
    label,
    kdf: 'scrypt', N: SCRYPT.N, r: SCRYPT.r, p: SCRYPT.p,
    salt_b64:  salt.toString('base64'),
    nonce_b64: nonce.toString('base64'),
    // ciphertext WITH the 16-byte GCM tag appended (server splits the last 16 bytes off)
    ct_b64:    Buffer.concat([ct, tag]).toString('base64'),
  };
}

(async () => {
  const id = argVal('--id', '__admin__');

  const daily = await prompt(`Daily passphrase for admin "${id}": `, 'TRAIL_ADMIN_PASS');
  if (!daily || daily.length < 10) { console.error('\nRefusing: daily passphrase must be >= 10 chars.'); process.exit(1); }
  const recovery = await prompt(`Recovery passphrase (kept OFFLINE): `, 'TRAIL_ADMIN_RECOVERY_PASS');
  if (!recovery || recovery.length < 10) { console.error('\nRefusing: recovery passphrase must be >= 10 chars.'); process.exit(1); }
  if (recovery === daily) { console.error('\nRefusing: recovery passphrase must differ from the daily one.'); process.exit(1); }

  // X25519 keypair (matches the app's ECDH primitive; no new crypto).
  const { publicKey, privateKey } = crypto.generateKeyPairSync('x25519');
  const spki  = publicKey.export({ type: 'spki',  format: 'der' });   // DER SPKI
  const pkcs8 = privateKey.export({ type: 'pkcs8', format: 'der' });  // DER PKCS8 (wrapped)
  const pubRaw = spki.subarray(spki.length - 32);                     // raw 32-byte X25519 public

  const entry = {
    id,
    alg: 'x25519',
    // Distribute pub_raw_b64 to the app so devices encrypt trail batches to this admin.
    // NOTE (Block G/I): confirm the Android envelope consumes raw-32 vs SPKI-DER peer keys
    // and hand it whichever form it already uses for guardian/DM public keys.
    pub_raw_b64:      pubRaw.toString('base64'),
    pub_hex:          pubRaw.toString('hex'),        // HEX raw-32 — the form the app + server use
    pub_spki_der_b64: spki.toString('base64'),
    // The admin private key, wrapped under each passphrase. Server unlocks by trying the
    // entered passphrase against each wrap until AES-GCM auth succeeds (see Block G).
    wraps: [ wrap('daily', pkcs8, daily), wrap('recovery', pkcs8, recovery) ],
    created_ts: Date.now(),
  };

  console.log('\n--- paste this object into config.json  "trailAdmins": [ ... ]  ---\n');
  console.log(JSON.stringify(entry, null, 2));
  console.log('\n--- done. The passphrases were NOT stored. Keep the recovery one offline. ---');
})();
