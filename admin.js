#!/usr/bin/env node
/**
 * Fshu user management CLI
 *
 * Usage:
 *   node admin.js add       <username> <password>  — create a new account
 *   node admin.js remove    <username>             — delete an account
 *   node admin.js reset     <username> <password>  — change a password
 *   node admin.js list                             — list all accounts
 *   node admin.js setadmin  <username>             — grant admin rights
 *   node admin.js removeadmin <username>           — revoke admin rights
 */

'use strict';

const fs = require('fs');
const bcrypt = require('bcrypt');

const USERS_FILE = '/opt/fshu/users.json';
const BCRYPT_ROUNDS = 12;

function loadUsers() {
    try {
        return JSON.parse(fs.readFileSync(USERS_FILE, 'utf8'));
    } catch {
        return {};
    }
}

function saveUsers(users) {
    fs.writeFileSync(USERS_FILE, JSON.stringify(users, null, 2), 'utf8');
}

const [, , cmd, rawUsername, password] = process.argv;
const username = rawUsername ? rawUsername.toLowerCase() : rawUsername;

switch (cmd) {
    case 'add': {
        if (!username || !password) {
            console.error('Usage: node admin.js add <username> <password>');
            process.exit(1);
        }
        const users = loadUsers();
        if (users[username]) {
            console.error(`Error: user "${username}" already exists`);
            process.exit(1);
        }
        const hash = bcrypt.hashSync(password, BCRYPT_ROUNDS);
        users[username] = { passwordHash: hash, createdAt: new Date().toISOString() };
        saveUsers(users);
        console.log(`User "${username}" created.`);
        break;
    }

    case 'remove': {
        if (!username) {
            console.error('Usage: node admin.js remove <username>');
            process.exit(1);
        }
        const users = loadUsers();
        if (!users[username]) {
            console.error(`Error: user "${username}" not found`);
            process.exit(1);
        }
        delete users[username];
        saveUsers(users);
        console.log(`User "${username}" removed.`);
        break;
    }

    case 'reset': {
        if (!username || !password) {
            console.error('Usage: node admin.js reset <username> <password>');
            process.exit(1);
        }
        const users = loadUsers();
        if (!users[username]) {
            console.error(`Error: user "${username}" not found`);
            process.exit(1);
        }
        users[username].passwordHash = bcrypt.hashSync(password, BCRYPT_ROUNDS);
        saveUsers(users);
        console.log(`Password updated for "${username}".`);
        break;
    }

    case 'list': {
        const users = loadUsers();
        const entries = Object.entries(users).filter(([u]) => !u.startsWith('_'));
        if (entries.length === 0) {
            console.log('No users registered.');
        } else {
            console.log(`${'Username'.padEnd(24)} Created`);
            console.log('-'.repeat(50));
            for (const [u, d] of entries) {
                console.log(`${u.padEnd(24)} ${d.createdAt || 'unknown'}`);
            }
        }
        break;
    }

    case 'setadmin': {
        if (!username) {
            console.error('Usage: node admin.js setadmin <username>');
            process.exit(1);
        }
        const users = loadUsers();
        if (!users[username]) {
            console.error(`Error: user "${username}" not found`);
            process.exit(1);
        }
        users[username].admin = true;
        saveUsers(users);
        console.log(`"${username}" is now admin.`);
        break;
    }

    case 'removeadmin': {
        if (!username) {
            console.error('Usage: node admin.js removeadmin <username>');
            process.exit(1);
        }
        const users = loadUsers();
        if (!users[username]) {
            console.error(`Error: user "${username}" not found`);
            process.exit(1);
        }
        users[username].admin = false;
        saveUsers(users);
        console.log(`Admin status removed from "${username}".`);
        break;
    }

    default:
        console.error('Usage: node admin.js <add|remove|reset|list|setadmin|removeadmin> [username] [password]');
        process.exit(1);
}
