const { WebSocketServer } = require('ws');
     const fs = require('fs');
     const path = require('path');
     const crypto = require('crypto');
     const bcrypt = require('bcrypt');

     const PORT = process.env.PORT || 8080;
     const QUEUE_FILE = '/opt/fshu/queue.json';
     const FILES_DIR = '/opt/fshu/files';
     const USERS_FILE = '/opt/fshu/users.json';
     const FILE_MAX_AGE_MS = 90 * 24 * 60 * 60 * 1000; // 90 days

     // ---------------------------------------------------------------------------
     // Users persistence
     // ---------------------------------------------------------------------------

     fs.mkdirSync(FILES_DIR, { recursive: true });

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

     // Monotonic per-user seq counters — initialised from persisted queue on startup
     const userSeqCounters = new Map();
     (function initSeqCounters() {
         for (const envelopes of Object.values(queue)) {
             for (const env of envelopes) {
                 if (!env.seq || !env.to) continue;
                 const cur = userSeqCounters.get(env.to) || 0;
                 if (env.seq > cur) userSeqCounters.set(env.to, env.seq);
             }
         }
     })();

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

         if (changed) saveQueue();
     }

     deleteOldFiles();
     setInterval(deleteOldFiles, 6 * 60 * 60 * 1000);

     // ---------------------------------------------------------------------------
     // Queue helpers
     // ---------------------------------------------------------------------------

     function enqueue(username, envelope) {
         if (!queue[username]) queue[username] = [];
         const seq = (userSeqCounters.get(username) || 0) + 1;
         userSeqCounters.set(username, seq);
         envelope.seq = seq;
         queue[username].push(envelope);
         saveQueue();
     }

     /** Send all envelopes with seq > afterSeq. Does NOT delete from queue. */
     function flushQueue(username, ws, afterSeq = 0) {
         const envelopes = queue[username];
         if (!envelopes || envelopes.length === 0) return;

         for (const env of envelopes) {
             if ((env.seq || 0) <= afterSeq) continue;
             if (env.type === 'file') {
                 const data = readFileAsBase64(env.filePath);
                 if (data === null) continue;
                 send(ws, { ...env, data, filePath: undefined });
             } else {
                 send(ws, env);
             }
         }
     }

     /** Remove envelopes the client has already confirmed (seq <= clientLastSeq). */
     function purgeConfirmed(username, clientLastSeq) {
         if (!clientLastSeq || !queue[username]) return;
         const before = queue[username].length;
         queue[username] = queue[username].filter(env => !env.seq || env.seq > clientLastSeq);
         if (queue[username].length === 0) delete queue[username];
         if ((queue[username] ? queue[username].length : 0) !== before) saveQueue();
     }

     // ---------------------------------------------------------------------------
     // WebSocket server
     // ---------------------------------------------------------------------------

     const wss = new WebSocketServer({ port: PORT });

     // username -> WebSocket
     const clients = new Map();

     // Pending disconnect timers — cleared if same user reconnects within 45 s
     const disconnectTimers = new Map();

     // Active/pending calls: username -> peerUsername (both sides stored)
     const activeCalls = new Map();

     function send(ws, data) {
         if (ws.readyState === ws.OPEN) {
             ws.send(JSON.stringify(data));
         }
     }

     // Broadcast all registered users (online and offline) to every connected client.
     function broadcastAllUsers() {
         const allUsers = Object.keys(users).map(u => ({
             username: u,
             online: clients.has(u),
         }));
         for (const ws of clients.values()) {
             send(ws, { type: 'users', users: allUsers });
         }
     }

     wss.on('connection', (ws, req) => {
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
                 // Cancel pending offline broadcast if reconnecting quickly
                 const pendingTimer = disconnectTimers.get(username);
                 if (pendingTimer) {
                     clearTimeout(pendingTimer);
                     disconnectTimers.delete(username);
                     console.log(`~ ${username} reconnected (timer cancelled)`);
                 }
                 const clientLastSeq = typeof msg.lastSeq === 'number' ? msg.lastSeq : 0;
                 clients.set(username, ws);
                 send(ws, { type: 'auth-ok' });
                 console.log(`+ ${username} (${clients.size} online)`);
                 broadcastAllUsers();
                 purgeConfirmed(username, clientLastSeq);
                 flushQueue(username, ws, clientLastSeq);
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
                     if (activeCalls.has(msg.to)) {
                         // Recipient is already in a call — send busy back to caller
                         send(ws, { type: 'call-busy', from: msg.to, to: username });
                         console.log(`  ${msg.to} busy, rejected call from ${username}`);
                         break;
                     }
                     // Register both sides before forwarding so disconnect is tracked
                     activeCalls.set(username, msg.to);
                     activeCalls.set(msg.to, username);
                     const recipientWs = clients.get(msg.to);
                     if (recipientWs) {
                         send(recipientWs, msg);
                     } else {
                         // Recipient offline — clean up and queue missed call
                         activeCalls.delete(username);
                         activeCalls.delete(msg.to);
                         enqueue(msg.to, {
                             type: 'missed-call',
                             from: msg.from,
                             to: msg.to,
                             timestamp: Date.now(),
                         });
                         console.log(`  queued missed-call for offline user ${msg.to}`);
                     }
                     break;
                 }

                 case 'ping': {
                     const clientLastSeq = typeof msg.lastSeq === 'number' ? msg.lastSeq : 0;
                     purgeConfirmed(username, clientLastSeq);
                     flushQueue(username, ws, clientLastSeq);
                     send(ws, { type: 'pong', serverTime: Date.now(), lastDeliveredSeq: userSeqCounters.get(username) || 0 });
                     break;
                 }

                 case 'call-answer':
                 case 'ice-candidate':
                 case 'call-ringing':
                 case 'ringing-ack':
                 case 'call-end':
                 case 'call-reject': {
                     if (msg.type === 'call-end' || msg.type === 'call-reject') {
                         activeCalls.delete(username);
                         activeCalls.delete(msg.to);
                     }
                     const recipientWs = clients.get(msg.to);
                     if (recipientWs) send(recipientWs, msg);
                     break;
                 }

                 default:
                     console.warn(`Unknown type: ${msg.type}`);
             }
         });

         ws.on('close', () => {
             if (username) {
                 // Notify call peer immediately before the presence timer fires
                 const callPeer = activeCalls.get(username);
                 if (callPeer) {
                     activeCalls.delete(username);
                     activeCalls.delete(callPeer);
                     const peerWs = clients.get(callPeer);
                     if (peerWs) {
                         send(peerWs, { type: 'call-end', from: username, to: callPeer, reason: 'disconnected' });
                         console.log(`  call-end sent to ${callPeer} (${username} disconnected)`);
                     }
                 }
                 clients.delete(username);
                 console.log(`- ${username} (pending, ${clients.size} online)`);
                 const timer = setTimeout(() => {
                     disconnectTimers.delete(username);
                     console.log(`- ${username} confirmed offline (${clients.size} online)`);
                     broadcastAllUsers();
                 }, 45000);
                 disconnectTimers.set(username, timer);
             }
         });

         ws.on('error', (err) => {
             console.error(`Error [${username ?? 'unauthenticated'}]: ${err.message}`);
         });
     });

     console.log(`Fshu signaling server listening on ws://localhost:${PORT}`);