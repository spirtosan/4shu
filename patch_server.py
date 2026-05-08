import sys

with open('/opt/fshu5/server.js', 'r') as f:
    src = f.read()

original_len = len(src)
errors = []

# 1. Add auto_location table to schema init
old1 = '  used_at     INTEGER\n);\n`);'
new1 = ('  used_at     INTEGER\n);\n\n'
        'CREATE TABLE IF NOT EXISTS auto_location (\n'
        '  owner TEXT NOT NULL,\n'
        '  peer  TEXT NOT NULL,\n'
        '  PRIMARY KEY (owner, peer)\n'
        ');\n`);')
if old1 in src:
    src = src.replace(old1, new1, 1)
    print('1. Schema: OK')
else:
    errors.append('1. Schema insert point not found')

# 2. Add prepared statements after getSecretQuestion
old2 = ('stmt.getSecretQuestion = db.prepare(\n'
        '  "SELECT secret_question, secret_answer_hash FROM users WHERE username = ?"\n'
        ');')
new2 = (old2 + '\n'
        "stmt.setAutoLocation = db.prepare(\n"
        "  'INSERT OR IGNORE INTO auto_location (owner, peer) VALUES (?, ?)'\n"
        ");\n"
        "stmt.clearAutoLocation = db.prepare(\n"
        "  'DELETE FROM auto_location WHERE owner = ? AND peer = ?'\n"
        ");\n"
        "stmt.getAutoLocationPeers = db.prepare(\n"
        "  'SELECT peer FROM auto_location WHERE owner = ?'\n"
        ");\n"
        "stmt.checkAutoLocation = db.prepare(\n"
        "  'SELECT 1 FROM auto_location WHERE owner = ? AND peer = ?'\n"
        ");\n"
        "stmt.checkContactAccepted = db.prepare(\n"
        "  \"SELECT 1 FROM contacts WHERE owner = ? AND contact = ? AND status = 'accepted'\"\n"
        ");")
if old2 in src:
    src = src.replace(old2, new2, 1)
    print('2. Stmts: OK')
else:
    errors.append('2. Stmt insert point not found')

# 3 & 4. Add autoLocationPeers to both auth-ok sends
# They share the same suffix; we replace both occurrences
suffix = ', profile: stmt.getMyProfile.get(username) || null });'
replacement = ', profile: stmt.getMyProfile.get(username) || null, autoLocationPeers: stmt.getAutoLocationPeers.all(username).map(r => r.peer) });'
count = src.count(suffix)
if count == 2:
    src = src.replace(suffix, replacement)
    print(f'3+4. auth-ok x2: OK')
elif count == 1:
    src = src.replace(suffix, replacement)
    print(f'3+4. auth-ok x1: OK (only one instance found)')
else:
    errors.append(f'3+4. auth-ok suffix found {count} times (expected 2)')

# 5. Replace location-request handler + prepend new handlers
old5 = ("            case 'location-request': {\n"
        "                const senderTrust = stmt.getUserTrustLevel.get(username)?.trust_level || 'contact';\n"
        "                if (senderTrust === 'stranger') {\n"
        "                    send(ws, { type: 'location-error', reason: 'trust-denied', to: msg.to });\n"
        "                    break;\n"
        "                }\n"
        "                if (msg.messageId != null) send(ws, { type: 'ack', messageId: msg.messageId });")
new5 = ("            case 'set-auto-location': {\n"
        "                const { peer, enabled } = msg;\n"
        "                if (!peer) break;\n"
        "                if (enabled) {\n"
        "                    stmt.setAutoLocation.run(username, peer);\n"
        "                } else {\n"
        "                    stmt.clearAutoLocation.run(username, peer);\n"
        "                }\n"
        "                const peers = stmt.getAutoLocationPeers.all(username).map(r => r.peer);\n"
        "                send(ws, { type: 'auto-location-peers', peers });\n"
        "                break;\n"
        "            }\n\n"
        "            case 'get-auto-location': {\n"
        "                const peers = stmt.getAutoLocationPeers.all(username).map(r => r.peer);\n"
        "                send(ws, { type: 'auto-location-peers', peers });\n"
        "                break;\n"
        "            }\n\n"
        "            case 'location-request': {\n"
        "                const isContact = !!stmt.checkContactAccepted.get(msg.to, username);\n"
        "                if (!isContact) {\n"
        "                    send(ws, { type: 'location-error', reason: 'not-contact', to: msg.to });\n"
        "                    break;\n"
        "                }\n"
        "                if (msg.messageId != null) send(ws, { type: 'ack', messageId: msg.messageId });")
if old5 in src:
    src = src.replace(old5, new5, 1)
    print('5. location-request: OK')
else:
    errors.append('5. location-request handler not found')

if errors:
    print('ERRORS:', errors)
    sys.exit(1)

with open('/opt/fshu5/server.js', 'w') as f:
    f.write(src)
print(f'Done. Size delta: +{len(src)-original_len} chars')
