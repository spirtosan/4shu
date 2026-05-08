#!/usr/bin/env node
/**
 * 4shu user management CLI
 *
 * Usage:
 *   node admin.js add         <username> <password>  — create account
 *   node admin.js remove      <username>             — delete account
 *   node admin.js reset       <username> <password>  — change password
 *   node admin.js list                               — list all accounts
 *   node admin.js setadmin    <username>             — grant admin rights
 *   node admin.js removeadmin <username>             — revoke admin rights
 */

'use strict';

const Database = require('better-sqlite3');
const bcrypt   = require('bcrypt');
const path     = require('path');

const BASE_DIR      = process.env.FSHU_BASE_DIR || '/opt/fshu5';
const DB_PATH       = path.join(BASE_DIR, 'data', 'fshu.db');
const BCRYPT_ROUNDS = 12;

const db = new Database(DB_PATH);
db.pragma('journal_mode = WAL');

const getUser    = db.prepare('SELECT * FROM users WHERE username = ?');
const insertUser = db.prepare('INSERT INTO users (username, password_hash, admin, created_at) VALUES (?, ?, ?, ?)');
const deleteUser = db.prepare('DELETE FROM users WHERE username = ?');
const updateHash = db.prepare('UPDATE users SET password_hash = ? WHERE username = ?');
const setAdmin   = db.prepare('UPDATE users SET admin = ? WHERE username = ?');
const listUsers  = db.prepare("SELECT * FROM users WHERE status != 'deleted' ORDER BY username");

const [, , cmd, rawUsername, password] = process.argv;
const username = rawUsername ? rawUsername.toLowerCase().trim() : rawUsername;

switch (cmd) {
    case 'add': {
        if (!username || !password) { console.error('Usage: node admin.js add <username> <password>'); process.exit(1); }
        if (getUser.get(username)) { console.error(`Error: user "${username}" already exists`); process.exit(1); }
        insertUser.run(username, bcrypt.hashSync(password, BCRYPT_ROUNDS), 0, Date.now());
        console.log(`User "${username}" created.`);
        break;
    }
    case 'remove': {
        if (!username) { console.error('Usage: node admin.js remove <username>'); process.exit(1); }
        if (!getUser.get(username)) { console.error(`Error: user "${username}" not found`); process.exit(1); }
        deleteUser.run(username);
        console.log(`User "${username}" removed.`);
        break;
    }
    case 'reset': {
        if (!username || !password) { console.error('Usage: node admin.js reset <username> <password>'); process.exit(1); }
        if (!getUser.get(username)) { console.error(`Error: user "${username}" not found`); process.exit(1); }
        updateHash.run(bcrypt.hashSync(password, BCRYPT_ROUNDS), username);
        console.log(`Password updated for "${username}".`);
        break;
    }
    case 'list': {
        const rows = listUsers.all();
        if (!rows.length) { console.log('No users registered.'); break; }
        console.log(`${'Username'.padEnd(24)} ${'Admin'.padEnd(6)} Created`);
        console.log('-'.repeat(55));
        for (const u of rows) {
            const date = u.created_at ? new Date(u.created_at).toISOString().slice(0, 10) : 'unknown';
            console.log(`${u.username.padEnd(24)} ${(u.admin ? 'yes' : 'no').padEnd(6)} ${date}`);
        }
        break;
    }
    case 'setadmin': {
        if (!username) { console.error('Usage: node admin.js setadmin <username>'); process.exit(1); }
        if (!getUser.get(username)) { console.error(`Error: user "${username}" not found`); process.exit(1); }
        setAdmin.run(1, username);
        console.log(`"${username}" is now admin.`);
        break;
    }
    case 'removeadmin': {
        if (!username) { console.error('Usage: node admin.js removeadmin <username>'); process.exit(1); }
        if (!getUser.get(username)) { console.error(`Error: user "${username}" not found`); process.exit(1); }
        setAdmin.run(0, username);
        console.log(`Admin status removed from "${username}".`);
        break;
    }
    default:
        console.error('Usage: node admin.js <add|remove|reset|list|setadmin|removeadmin> [username] [password]');
        process.exit(1);
}
