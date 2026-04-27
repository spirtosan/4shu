const { WebSocketServer } = require('ws');
const admin = require('firebase-admin');
const serviceAccount = require('/opt/fshu/firebase-adminsdk.json');

admin.initializeApp({
    credential: admin.credential.cert(serviceAccount)
});

async function sendFcmWakeup(fcmToken) {
    try {
        await admin.messaging().send({
            token: fcmToken,
            data: { wake: '1' },
            android: {
                priority: 'high',
                ttl: 60000
            }
        });
        console.log('  FCM wake-up sent');
    } catch (err) {
        console.warn('  FCM send failed:', err.message);
    }
}
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const bcrypt = require('bcrypt');

const PORT = process.env.PORT || 8080;
const QUEUE_FILE   = '/opt/kapka/queue.json';
const FILES_DIR    = '/opt/kapka/files';
const USERS_FILE   = '/opt/kapka/users.json';
const LISTS_FILE   = '/opt/kapka/lists.json';
const HISTORY_DIR  = '/opt/kapka/history';
const CONFIG_FILE  = '/opt/kapka/config.json';
const AVATARS_DIR  = '/opt/fshu/avatars';
fs.mkdirSync(AVATARS_DIR, { recursive: true });

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

let config = { historyRetentionDays: 90, fileRetentionDays: 90, maxHistoryRequestDays: 90 };
try { Object.assign(config, JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'))); } catch { /* defaults */ }

const FILE_MAX_AGE_MS    = config.fileRetentionDays    * 24 * 60 * 60 * 1000;
const HISTORY_MAX_AGE_MS = config.historyRetentionDays * 24 * 60 * 60 * 1000;

// ---------------------------------------------------------------------------
// Users persistence
// ---------------------------------------------------------------------------

fs.mkdirSync(FILES_DIR,   { recursive: true });
fs.mkdirSync(HISTORY_DIR, { recursive: true });

// users: { [username]: { passwordHash, createdAt } }
let users = {};

function loadUsers() {
    try {
        users = JSON.parse(fs.readFileSync(USERS_FILE, 'utf8'));
    } catch {
        users = {};
    }
}

loadUsers();

if (!users.__appSecret) {
    users.__appSecret = crypto.randomBytes(32).toString('hex');
    saveUsers();
}
const sharedAppSecret = users.__appSecret;

function saveUsers() {
    fs.writeFileSync(USERS_FILE, JSON.stringify(users, null, 2));
}

// ---------------------------------------------------------------------------
// Brute-force protection
// ---------------------------------------------------------------------------
// Separate maps for username and IP to allow locking either independently.
const failedAttempts = new Map(); // key -> { count, lockUntil }
const MAX_ATTEMPTS = 5;
const LOCKOUT_MS = 15 * 60 * 1000; // 15 minutes

function isLocked(key) {
    const entry = failedAttempts.get(key);
    if (!entry) return false;
    if (Date.now() < entry.lockUntil) return true;
    failedAttempts.delete(key); // expired lock, clean up
    return false;
}

function recordFailure(key) {
    const entry = failedAttempts.get(key) || { count: 0, lockUntil: 0 };
    entry.count++;
    if (entry.count >= MAX_ATTEMPTS) {
        entry.lockUntil = Date.now() + LOCKOUT_MS;
        entry.count = 0; // reset counter for next window after lockout expires
    }
    failedAttempts.set(key, entry);
}

function clearFailures(key) {
    failedAttempts.delete(key);
}

// ---------------------------------------------------------------------------
// Queue persistence
// ---------------------------------------------------------------------------

// queue: { [username]: [ ...envelope ] }
// envelope for text:  { type, from, to, content, timestamp }
// envelope for file:  { type, from, to, filename, mimeType, filePath, timestamp }
// envelope for missed call: { type:'missed-call', from, to, timestamp }
let queue = {};

function loadQueue() {
    try {
        queue = JSON.parse(fs.readFileSync(QUEUE_FILE, 'utf8'));
    } catch {
        queue = {};
    }
}

function saveQueue() {
    fs.writeFileSync(QUEUE_FILE, JSON.stringify(queue), 'utf8');
}

loadQueue();

// ---------------------------------------------------------------------------
// History helpers
// ---------------------------------------------------------------------------

function historyFile(a, b) {
    const [u1, u2] = [a, b].sort();
    return path.join(HISTORY_DIR, `${u1}_${u2}.jsonl`);
}

function appendHistory(record) {
    try { fs.appendFileSync(historyFile(record.from, record.to), JSON.stringify(record) + '\n'); } catch { /* ignore */ }
}

function readHistory(a, b, since) {
    const file = historyFile(a, b);
    if (!fs.existsSync(file)) return [];
    try {
        return fs.readFileSync(file, 'utf8').split('\n')
            .filter(l => l.trim())
            .flatMap(line => {
                try {
                    const rec = JSON.parse(line);
                    return rec.timestamp >= since ? [rec] : [];
                } catch { return []; }
            });
    } catch { return []; }
}

// ---------------------------------------------------------------------------
// Lists persistence
// ---------------------------------------------------------------------------

// lists: { [listId]: { owner, to, version, items: [{id, text, done, checkedBy, checkedAt, deletedAt}] } }
let lists = {};

function loadLists() {
    try { lists = JSON.parse(fs.readFileSync(LISTS_FILE, 'utf8')); } catch { lists = {}; }
}

function saveLists() {
    fs.writeFileSync(LISTS_FILE, JSON.stringify(lists, null, 2));
}

loadLists();

function sendListState(ws, list, listId) {
    if (ws && ws.readyState === ws.OPEN) {
        send(ws, {
            type: 'list-state',
            listId,
            version: list.version,
            owner: list.owner,
            to: list.to,
            items: list.items,
            messageId: list.messageId ?? null
        });
    }
}

function broadcastListState(list, listId) {
    const ownerWs = clients.get(list.owner);
    const peerWs  = clients.get(list.to);
    if (ownerWs) sendListState(ownerWs, list, listId);
    if (peerWs)  sendListState(peerWs,  list, listId);
    // Enqueue for offline participants
    const stateEnv = { type: 'list-state', listId, version: list.version, owner: list.owner, to: list.to, items: list.items, messageId: list.messageId ?? null, timestamp: Date.now() };
    if (!ownerWs) enqueue(list.owner, stateEnv);
    if (!peerWs)  enqueue(list.to,    stateEnv);
}

// ---------------------------------------------------------------------------
// File helpers
// ---------------------------------------------------------------------------

function saveFileToDisk(base64Data, filename) {
    const ext = path.extname(filename) || '';
    const name = crypto.randomBytes(16).toString('hex') + ext;
    const filePath = path.join(FILES_DIR, name);
    fs.writeFileSync(filePath, Buffer.from(base64Data, 'base64'));
    return filePath;
}

function readFileAsBase64(filePath) {
    try {
        return fs.readFileSync(filePath).toString('base64');
    } catch {
        return null;
    }
}

function deleteOldFiles() {
    const now = Date.now();
    let changed = false;

    for (const [user, envelopes] of Object.entries(queue)) {
        const before = envelopes.length;
        queue[user] = envelopes.filter(env => {
            if (env.type !== 'file') return true;
            const age = now - (env.timestamp || 0);
            if (age < FILE_MAX_AGE_MS) return true;
            try { fs.unlinkSync(env.filePath); } catch { /* already gone */ }
            return false;
        });
        if (queue[user].length !== before) changed = true;
    }

    try {
        const referenced = new Set(
            Object.values(queue).flat()
                .filter(e => e.type === 'file' && e.filePath)
                .map(e => e.filePath)
        );
        for (const file of fs.readdirSync(FILES_DIR)) {
            const filePath = path.join(FILES_DIR, file);
            const { mtimeMs } = fs.statSync(filePath);
            if (!referenced.has(filePath) || now - mtimeMs > FILE_MAX_AGE_MS) {
                try { fs.unlinkSync(filePath); } catch { /* ignore */ }
                changed = true;
            }
        }
    } catch { /* ignore */ }

    // Clean stale history entries
    try {
        const historyCutoff = now - HISTORY_MAX_AGE_MS;
        for (const file of fs.readdirSync(HISTORY_DIR)) {
            if (!file.endsWith('.jsonl')) continue;
            const filePath = path.join(HISTORY_DIR, file);
            const lines = fs.readFileSync(filePath, 'utf8').split('\n').filter(l => l.trim());
            const kept = lines.filter(line => { try { return JSON.parse(line).timestamp >= historyCutoff; } catch { return false; } });
            if (kept.length !== lines.length) {
                if (kept.length === 0) { fs.unlinkSync(filePath); } else { fs.writeFileSync(filePath, kept.join('\n') + '\n'); }
                changed = true;
            }
        }
    } catch { /* ignore */ }

    if (changed) saveQueue();
}

deleteOldFiles();
setInterval(deleteOldFiles, 6 * 60 * 60 * 1000);

// FCM keepalive: every 3 minutes, wake any user whose WebSocket has gone silent.
// Handles Android Doze mode — the high-priority FCM bypasses Doze and causes
// the app to reconnect, making the user appear online again.
const FCM_KEEPALIVE_INTERVAL_MS = 3 * 60 * 1000;   // check every 3 min
const FCM_KEEPALIVE_THRESHOLD_MS = 4 * 60 * 1000;  // wake if silent for 4+ min

setInterval(async () => {
    const now = Date.now();
    for (const [username, userWs] of clients.entries()) {
        const lastPing = userWs.lastPingAt || 0;
        const silent = now - lastPing;
        if (lastPing > 0 && silent > FCM_KEEPALIVE_THRESHOLD_MS) {
            const token = users[username]?.fcmToken;
            if (token) {
                console.log(`  FCM keepalive → ${username} (silent ${Math.round(silent/1000)}s)`);
                await sendFcmWakeup(token);
            }
        }
    }
}, FCM_KEEPALIVE_INTERVAL_MS);

// ---------------------------------------------------------------------------
// Queue helpers
// ---------------------------------------------------------------------------

function enqueue(username, envelope) {
    if (!queue[username]) queue[username] = [];
    queue[username].push(envelope);
    saveQueue();
}

function flushQueue(username, ws) {
    const envelopes = queue[username];
    if (!envelopes || envelopes.length === 0) return;

    for (const env of envelopes) {
        if (env.type === 'file') {
            const data = readFileAsBase64(env.filePath);
            if (data === null) continue;
            send(ws, { ...env, data, filePath: undefined });
        } else {
            send(ws, env);
        }
    }

    delete queue[username];
    saveQueue();
}

// ---------------------------------------------------------------------------
// WebSocket server
// ---------------------------------------------------------------------------

const wss = new WebSocketServer({ port: PORT });

// Server-side heartbeat — detects zombie sockets that appear OPEN but are
// no longer reachable. Native WS ping frames (opcode 0x9) — if no pong
// returns within the next interval, socket is terminated as zombie.
const WS_HEARTBEAT_INTERVAL = 30_000;

setInterval(() => {
    wss.clients.forEach((ws) => {
        if (ws.isAlive === false) {
            console.log('  heartbeat: terminating zombie socket');
            ws.terminate();
            return;
        }
        ws.isAlive = false;
        ws.ping();
    });
}, WS_HEARTBEAT_INTERVAL);

// username -> WebSocket
const clients = new Map();

// testId -> { ws, startTime, timer }
const pendingPeerTests = new Map();

// sessionToken -> { username, createdAt }
const sessionTokens = new Map();
const SESSION_TOKEN_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours

// callerUsername -> calleeUsername (tracks active calls for busy detection and cleanup)
const activeCalls = new Map();

function send(ws, data) {
    if (ws.readyState === ws.OPEN) {
        ws.send(JSON.stringify(data));
    }
}

// Broadcast all registered users (online and offline) to every connected client.
function broadcastAllUsers() {
    const allUsers = Object.keys(users)
        .filter(u => !u.startsWith('_'))
        .map(u => ({
                username: u,
                online: clients.has(u),
                lastSeen: clients.has(u) ? null : (users[u].lastSeen || null),
                nickname: users[u].nickname || null
            }));
    for (const ws of clients.values()) {
        send(ws, { type: 'users', users: allUsers });
    }
}

function sendAllAvatars(ws) {
    try {
        for (const file of fs.readdirSync(AVATARS_DIR)) {
            const username = path.basename(file, path.extname(file));
            const filePath = path.join(AVATARS_DIR, file);
            try {
                const data = fs.readFileSync(filePath).toString('base64');
                send(ws, { type: 'avatar-data', username, data });
            } catch { /* skip unreadable */ }
        }
    } catch { /* dir unreadable */ }
}

wss.on('connection', (ws, req) => {
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });
    let username = null;
    let authenticated = false;
    const ip = req.socket.remoteAddress || 'unknown';

    ws.on('message', (raw) => {
        let msg;
        try {
            msg = JSON.parse(raw);
        } catch {
            return;
        }

        // ---------------------------------------------------------------
        // Pre-auth: only 'auth' is accepted; anything else closes the socket.
        // ---------------------------------------------------------------
        if (!authenticated) {
            if (msg.type === 'resume') {
                const token = msg.sessionToken || '';
                const entry = sessionTokens.get(token);
                if (!entry || Date.now() - entry.createdAt > SESSION_TOKEN_TTL_MS) {
                    sessionTokens.delete(token);
                    send(ws, { type: 'resume-error', reason: 'invalid' });
                    return;
                }
                const resumeUser = entry.username;
                if (!users[resumeUser]) {
                    sessionTokens.delete(token);
                    send(ws, { type: 'resume-error', reason: 'invalid' });
                    return;
                }
                // Resume success — re-use same token
                authenticated = true;
                username = resumeUser;
                const existingWsResume = clients.get(username);
                if (existingWsResume && existingWsResume !== ws) {
                    console.log(`  closing stale socket for ${username}`);
                    try { existingWsResume.terminate(); } catch (_) {}
                }
                clients.set(username, ws);
                send(ws, { type: 'auth-ok', appSecret: sharedAppSecret, admin: users[resumeUser].admin === true, sessionToken: token });
                sendAllAvatars(ws);
                console.log();
                broadcastAllUsers();
                flushQueue(username, ws);
                return;
            }
            if (msg.type !== 'auth') {
                ws.close();
                return;
            }

            const u = (msg.username || '').trim().toLowerCase();
            const p = msg.password || '';

            // Check lockout first — same response regardless of why.
            if (isLocked(u) || isLocked(ip)) {
                send(ws, { type: 'auth-error', message: 'Invalid credentials' });
                ws.close();
                return;
            }

            // Validate credentials — never reveal whether the username exists.
            const userRecord = users[u];
            if (!u || !userRecord || !bcrypt.compareSync(p, userRecord.passwordHash)) {
                if (u && userRecord) recordFailure(u); // only track per-user if user exists
                recordFailure(ip);
                send(ws, { type: 'auth-error', message: 'Invalid credentials' });
                ws.close();
                return;
            }

            // ✓ Auth success
            clearFailures(u);
            clearFailures(ip);
            authenticated = true;
            username = u;
            const existingWs = clients.get(username);
            if (existingWs && existingWs !== ws) {
                console.log(`  closing stale socket for ${username}`);
                try { existingWs.terminate(); } catch (_) {}
            }
            clients.set(username, ws);
            const sessionToken = crypto.randomBytes(32).toString('hex');
            sessionTokens.set(sessionToken, { username: u, createdAt: Date.now() });
            send(ws, { type: 'auth-ok', appSecret: sharedAppSecret, admin: userRecord.admin === true, sessionToken });
            sendAllAvatars(ws);
            console.log(`+ ${username} (${clients.size} online)`);
            broadcastAllUsers();
            flushQueue(username, ws);
            return;
        }

        // ---------------------------------------------------------------
        // Authenticated message handling
        // ---------------------------------------------------------------
        switch (msg.type) {

            case 'message': {
                if (msg.messageId != null) {
                    send(ws, { type: 'ack', messageId: msg.messageId });
                }
                const histRecord = {
                    from: msg.from, to: msg.to, content: msg.content,
                    messageId: msg.messageId ?? null, timestamp: msg.timestamp ?? Date.now(),
                    replyToId: msg.replyToId ?? null, replyToSender: msg.replyToSender ?? null,
                    replyToContent: msg.replyToContent ?? null,
                };
                appendHistory(histRecord);
                const recipientWs = clients.get(msg.to);
                if (recipientWs) {
                    send(recipientWs, msg);
                } else {
                    enqueue(msg.to, {
                        type: 'message',
                        from: msg.from,
                        to: msg.to,
                        content: msg.content,
                        messageId: msg.messageId,
                        timestamp: msg.timestamp ?? Date.now(),
                    });
                    console.log(`  queued message for offline user ${msg.to}`);
                    // Wake up offline user via FCM
                    const msgFcmToken = users[msg.to] && users[msg.to].fcmToken;
                    if (msgFcmToken) sendFcmWakeup(msgFcmToken);
                }
                break;
            }

            case 'file': {
                if (msg.messageId != null) {
                    send(ws, { type: 'ack', messageId: msg.messageId });
                }
                const recipientWs = clients.get(msg.to);
                if (recipientWs) {
                    send(recipientWs, msg);
                } else {
                    try {
                        const filePath = saveFileToDisk(msg.data, msg.filename);
                        enqueue(msg.to, {
                            type: 'file',
                            from: msg.from,
                            to: msg.to,
                            filename: msg.filename,
                            mimeType: msg.mimeType,
                            filePath,
                            messageId: msg.messageId,
                            timestamp: msg.timestamp ?? Date.now(),
                        });
                        console.log(`  queued file "${msg.filename}" for offline user ${msg.to}`);
                    } catch (err) {
                        console.error(`  failed to save file for ${msg.to}:`, err.message);
                    }
                }
                break;
            }

            case 'delivered': {
                const senderWs = clients.get(msg.to);
                if (senderWs) send(senderWs, msg);
                break;
            }

            case 'read': {
                const senderWs = clients.get(msg.to);
                if (senderWs) send(senderWs, msg);
                break;
            }

            case 'call-offer': {
                // Busy check
                if (activeCalls.has(msg.to)) {
                    send(ws, { type: 'call-busy', from: msg.to, to: msg.from });
                    break;
                }
                // Mutual call — both calling each other simultaneously
                if (activeCalls.get(msg.to) === msg.from) {
                    const caller = msg.from > msg.to ? msg.from : msg.to;
                    const callee = msg.from > msg.to ? msg.to : msg.from;
                    [msg.from, msg.to].forEach(user => {
                        const ws2 = clients.get(user);
                        if (ws2) send(ws2, { type: 'call-mutual-resolve', caller, callee });
                    });
                    break;
                }
                activeCalls.set(msg.from, msg.to);
                activeCalls.set(msg.to, msg.from);
                const recipientWs = clients.get(msg.to);
                if (recipientWs) {
                    send(recipientWs, msg);
                } else {
                    activeCalls.delete(msg.from);
                    activeCalls.delete(msg.to);
                    enqueue(msg.to, { type: 'missed-call', from: msg.from, to: msg.to, timestamp: Date.now() });
                    console.log(`  queued missed-call for offline user ${msg.to}`);
                    // Wake up offline user via FCM
                    const callFcmToken = users[msg.to] && users[msg.to].fcmToken;
                    if (callFcmToken) sendFcmWakeup(callFcmToken);
                }
                break;
            }

            case 'set-nickname': {
                const nickname = (msg.nickname || '').trim().slice(0, 20);
                if (users[username]) {
                    if (nickname) {
                        users[username].nickname = nickname;
                    } else {
                        delete users[username].nickname;
                    }
                    saveUsers();
                    broadcastAllUsers();
                    console.log('  nickname set for ' + username + ': "' + nickname + '"');
                }
                break;
            }

            case 'fcm-token': {
                if (users[username]) {
                    if (msg.token) {
                        users[username].fcmToken = msg.token;
                        console.log('  FCM token stored for ' + username);
                    } else {
                        delete users[username].fcmToken;
                        console.log('  FCM token cleared for ' + username);
                    }
                    saveUsers();
                }
                break;
            }

            case 'ping': {
                ws.lastPingAt = Date.now();
                const onlineUsers = [...clients.keys()];
                const outdatedLists = [];
                if (msg.listVersions && typeof msg.listVersions === 'object') {
                    for (const [listId, clientVersion] of Object.entries(msg.listVersions)) {
                        const stored = lists[listId];
                        if (stored && stored.version > clientVersion) {
                            outdatedLists.push({ listId, serverVersion: stored.version });
                        }
                    }
                }
                send(ws, { type: 'pong', onlineUsers, outdatedLists });
                break;
            }

            case 'list-create': {
                const { listId, items } = msg;
                if (!listId || !Array.isArray(items)) break;
                lists[listId] = {
                    owner: msg.from,
                    to: msg.to,
                    version: 1,
                    createdAt: Date.now(),
                    messageId: msg.messageId ?? null,
                    items: items.map(it => ({
                        id: it.id, text: it.text, done: false,
                        checkedBy: null, checkedAt: null, deletedAt: null
                    }))
                };
                saveLists();
                send(ws, { type: 'list-ack', listId, version: 1 });
                broadcastListState(lists[listId], listId);
                break;
            }

            case 'list-edit': {
                const { listId, items } = msg;
                if (!listId || !Array.isArray(items)) break;
                const list = lists[listId];
                if (!list) break;
                // Build lookup of existing items to preserve done state
                const existing = {};
                for (const it of list.items) existing[it.id] = it;
                list.items = items.map(it => {
                    if (it.deleted) {
                        return { ...(existing[it.id] || { id: it.id, text: '', done: false, checkedBy: null, checkedAt: null }), deletedAt: Date.now() };
                    }
                    const prev = existing[it.id] || {};
                    return { id: it.id, text: it.text, done: prev.done || false, checkedBy: prev.checkedBy || null, checkedAt: prev.checkedAt || null, deletedAt: null };
                });
                list.version++;
                saveLists();
                send(ws, { type: 'list-ack', listId, version: list.version });
                broadcastListState(list, listId);
                break;
            }

            case 'list-check': {
                const { listId, itemId, done } = msg;
                if (!listId || !itemId) break;
                const list = lists[listId];
                if (!list) break;
                const item = list.items.find(it => it.id === itemId);
                if (item) {
                    item.done = done;
                    item.checkedBy = msg.from;
                    item.checkedAt = msg.ts || Date.now();
                }
                list.version++;
                saveLists();
                send(ws, { type: 'list-ack', listId, version: list.version });
                broadcastListState(list, listId);
                break;
            }

            case 'list-sync-request': {
                if (msg.listId) {
                    // Single-list request (from onOutdatedLists handler)
                    const list = lists[msg.listId];
                    if (list && (list.owner === username || list.to === username)) {
                        sendListState(ws, list, msg.listId);
                    }
                } else {
                    // Bulk sync on reconnect — send all participant lists newer than lastKnownVersions
                    const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
                    const knownVersions = (msg.lastKnownVersions && typeof msg.lastKnownVersions === 'object') ? msg.lastKnownVersions : {};
                    for (const [listId, list] of Object.entries(lists)) {
                        if (list.owner !== username && list.to !== username) continue;
                        if ((list.createdAt || 0) < cutoff) continue;
                        const clientVersion = knownVersions[listId] || 0;
                        if (list.version > clientVersion) sendListState(ws, list, listId);
                    }
                }
                break;
            }

            case 'history-request': {
                const peer = msg.to;
                const maxMs = config.maxHistoryRequestDays * 24 * 60 * 60 * 1000;
                const since = Math.max(msg.since || 0, Date.now() - maxMs);
                const messages = readHistory(username, peer, since);
                send(ws, { type: 'history-response', messages, from: peer, to: username });
                break;
            }

            case 'admin-server-info': {
                if (!users[username]?.admin) { send(ws, { type: 'admin-error', message: 'Unauthorized' }); break; }
                let disk = 'N/A';
                try { disk = execSync('df -h /opt/kapka 2>/dev/null').toString().split('\n')[1]?.trim() || 'N/A'; } catch {}
                let filesCount = 0, filesSizeBytes = 0;
                try {
                    const fl = fs.readdirSync(FILES_DIR);
                    filesCount = fl.length;
                    filesSizeBytes = fl.reduce((s, f) => { try { return s + fs.statSync(path.join(FILES_DIR, f)).size; } catch { return s; } }, 0);
                } catch {}
                let historyCount = 0;
                try { historyCount = fs.readdirSync(HISTORY_DIR).filter(f => f.endsWith('.jsonl')).length; } catch {}
                const filesMB = (filesSizeBytes / (1024 * 1024)).toFixed(1);
                send(ws, { type: 'admin-server-info', disk, filesCount, filesMB: `${filesMB} MB`, historyCount });
                break;
            }

            case 'call-emergency': {
                // Emergency bypasses busy check but still registers in activeCalls
                activeCalls.set(msg.from, msg.to);
                activeCalls.set(msg.to, msg.from);
                const recipientWs = clients.get(msg.to);
                if (recipientWs) {
                    send(recipientWs, msg);
                } else {
                    activeCalls.delete(msg.from);
                    activeCalls.delete(msg.to);
                    enqueue(msg.to, { type: 'missed-call', from: msg.from, to: msg.to, timestamp: Date.now() });
                    console.log(`  queued missed-call (emergency) for offline user ${msg.to}`);
                }
                break;
            }

            case 'call-answer':
            case 'ice-candidate':
            case 'call-ringing':
            case 'ringing-ack': {
                const recipientWs = clients.get(msg.to);
                if (recipientWs) send(recipientWs, msg);
                break;
            }

            case 'call-end':
            case 'call-reject': {
                activeCalls.delete(msg.from);
                activeCalls.delete(msg.to);
                const recipientWs = clients.get(msg.to);
                if (recipientWs) send(recipientWs, msg);
                break;
            }

            case 'location-request': {
                if (msg.messageId != null) {
                    send(ws, { type: 'ack', messageId: msg.messageId });
                }
                const recipientWs = clients.get(msg.to);
                if (recipientWs) {
                    send(recipientWs, msg);
                } else {
                    enqueue(msg.to, {
                        type: 'location-request',
                        from: msg.from,
                        to: msg.to,
                        requestId: msg.requestId,
                        content: msg.content,
                        messageId: msg.messageId,
                        timestamp: msg.timestamp ?? Date.now(),
                    });
                }
                break;
            }

            case 'location-response': {
                if (msg.messageId != null) {
                    send(ws, { type: 'ack', messageId: msg.messageId });
                }
                const recipientWs = clients.get(msg.to);
                if (recipientWs) {
                    send(recipientWs, msg);
                } else {
                    enqueue(msg.to, {
                        type: 'location-response',
                        from: msg.from,
                        to: msg.to,
                        requestId: msg.requestId,
                        content: msg.content,
                        messageId: msg.messageId,
                        timestamp: msg.timestamp ?? Date.now(),
                    });
                }
                break;
            }

            case 'emergency-location': {
                const recipientWs = clients.get(msg.to);
                if (recipientWs) {
                    send(recipientWs, msg);
                } else {
                    enqueue(msg.to, {
                        type: 'emergency-location',
                        from: msg.from,
                        to: msg.to,
                        lat: msg.lat,
                        lon: msg.lon,
                        accuracy: msg.accuracy,
                        messageId: msg.messageId,
                        timestamp: msg.timestamp ?? Date.now(),
                    });
                }
                break;
            }

            case 'peer-test-request': {
                const { testId, to: testTarget } = msg;
                if (!testId || !testTarget) break;
                const targetWs = clients.get(testTarget);
                if (!targetWs) {
                    send(ws, { type: 'peer-test-result', testId, success: false, reason: 'offline', lastSeen: users[msg.to]?.lastSeen || null });
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
                send(pending.ws, { type: 'peer-test-result', testId, success: true, latencyMs: Date.now() - pending.startTime, lastSeen: null });
                break;
            }

            case 'change-password': {
                const { currentPassword, newPassword } = msg;
                if (!currentPassword || !bcrypt.compareSync(currentPassword, users[username].passwordHash)) {
                    send(ws, { type: 'change-password-error', message: 'Current password is wrong' }); break;
                }
                if (!newPassword || newPassword.length < 4) {
                    send(ws, { type: 'change-password-error', message: 'New password too short (min 4)' }); break;
                }
                users[username].passwordHash = bcrypt.hashSync(newPassword, 10);
                saveUsers();
                send(ws, { type: 'change-password-ok', message: 'Password changed' });
                break;
            }

            case 'admin-list-users': {
                if (!users[username]?.admin) { send(ws, { type: 'admin-error', message: 'Unauthorized' }); break; }
                const adminList = Object.entries(users)
                    .filter(([u]) => !u.startsWith('_'))
                    .map(([u, rec]) => ({
                        username: u,
                        createdAt: rec.createdAt ? new Date(rec.createdAt).toISOString().slice(0, 10) : '',
                        admin: rec.admin === true
                    }));
                send(ws, { type: 'admin-users', users: adminList });
                break;
            }

            case 'admin-add-user': {
                if (!users[username]?.admin) { send(ws, { type: 'admin-error', message: 'Unauthorized' }); break; }
                const newUser = (msg.username || '').trim().toLowerCase();
                const newPass = msg.password || '';
                if (!newUser || !newPass) { send(ws, { type: 'admin-error', message: 'Username and password required' }); break; }
                if (users[newUser]) { send(ws, { type: 'admin-error', message: `User "${newUser}" already exists` }); break; }
                users[newUser] = { passwordHash: bcrypt.hashSync(newPass, 10), createdAt: new Date().toISOString() };
                saveUsers();
                broadcastAllUsers();
                send(ws, { type: 'admin-result', message: `User "${newUser}" created` });
                break;
            }

            case 'admin-remove-user': {
                if (!users[username]?.admin) { send(ws, { type: 'admin-error', message: 'Unauthorized' }); break; }
                const removeTarget = (msg.username || '').trim().toLowerCase();
                if (!removeTarget || removeTarget === username) { send(ws, { type: 'admin-error', message: 'Cannot remove yourself' }); break; }
                if (!users[removeTarget]) { send(ws, { type: 'admin-error', message: 'User not found' }); break; }
                delete users[removeTarget];
                saveUsers();
                const targetWs = clients.get(removeTarget);
                if (targetWs) targetWs.close();
                broadcastAllUsers();
                send(ws, { type: 'admin-result', message: `User "${removeTarget}" removed` });
                break;
            }

            case 'admin-reset-password': {
                if (!users[username]?.admin) { send(ws, { type: 'admin-error', message: 'Unauthorized' }); break; }
                const resetTarget = (msg.username || '').trim().toLowerCase();
                const resetPass = msg.newPassword || '';
                if (!users[resetTarget]) { send(ws, { type: 'admin-error', message: 'User not found' }); break; }
                if (resetPass.length < 4) { send(ws, { type: 'admin-error', message: 'Password too short (min 4)' }); break; }
                users[resetTarget].passwordHash = bcrypt.hashSync(resetPass, 10);
                saveUsers();
                send(ws, { type: 'admin-result', message: `Password reset for "${resetTarget}"` });
                break;
            }

            case 'get-passphrase-hint': {
                send(ws, { type: 'passphrase-hint', hint: users[username]?.passphraseHint || '' });
                break;
            }

            case 'set-passphrase-hint': {
                users[username].passphraseHint = (msg.hint || '').trim();
                saveUsers();
                break;
            }

            case 'avatar-upload': {
                const data = msg.data;
                if (typeof data !== 'string' || data.length > 400000) break;
                try {
                    const filePath = path.join(AVATARS_DIR, `${username}.jpg`);
                    fs.writeFileSync(filePath, Buffer.from(data, 'base64'));
                    const packet = JSON.stringify({ type: 'avatar-data', username, data });
                    for (const [, clientWs] of clients) {
                        if (clientWs.readyState === clientWs.OPEN) clientWs.send(packet);
                    }
                    console.log();
                } catch (e) {
                    console.error('avatar-upload error', e);
                }
                break;
            }

            default:
                console.warn(`Unknown type: ${msg.type}`);
        }
    });

    ws.on('close', () => {
        if (username) {
            // CRITICAL: only clean up if this socket is still the active one.
            // If the user reconnected, clients.get(username) is already the new socket.
            // Deleting unconditionally would remove the new socket, making the user
            // appear offline even though they just reconnected.
            const isActiveSocket = clients.get(username) === ws;

            const callPeer = activeCalls.get(username);
            if (callPeer) {
                // Grace period before sending call-end — brief disconnects during
                // screen wakeup should not kill the call.
                const graceMs = 4000;
                setTimeout(() => {
                    const reconnected = clients.get(username);
                    const callStillActive = activeCalls.get(callPeer) === username;
                    if (!reconnected && callStillActive) {
                        activeCalls.delete(username);
                        activeCalls.delete(callPeer);
                        const peerWs = clients.get(callPeer);
                        if (peerWs) {
                            send(peerWs, { type: 'call-end', from: username, to: callPeer, reason: 'disconnected' });
                            console.log(`  call-end sent to ${callPeer} (${username} disconnected after grace)`);
                        }
                    }
                }, graceMs);
            }

            if (isActiveSocket) {
                clients.delete(username);
                // Clean up session tokens for this user on disconnect
                for (const [token, entry] of sessionTokens.entries()) {
                    if (entry.username === username) {
                        sessionTokens.delete(token);
                    }
                }
                if (users[username]) {
                    users[username].lastSeen = Date.now();
                    saveUsers();
                }
                console.log(`- ${username} (${clients.size} online)`);
                // Short grace period before broadcasting offline — quick reconnects
                // (network change, screen wake) should not flash the user as offline.
                setTimeout(() => {
                    if (!clients.has(username)) {
                        broadcastAllUsers();
                    }
                    // else: user reconnected within grace period — skip offline broadcast
                }, 2000);
            } else {
                console.log(`  stale socket closed for ${username} — new socket active, skipping cleanup`);
            }
        }
    });

    ws.on('error', (err) => {
        console.error(`Error [${username ?? 'unauthenticated'}]: ${err.message}`);
    });
});

console.log(`4shu signaling server listening on ws://localhost:${PORT}`);
