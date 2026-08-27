# Deploy — T13 Phase 2/3 trail server persistence → live `/opt/fshu5`

**What lands:** additive `server.js` changes only (new tables via `IF NOT EXISTS`, new
config keys, trail WS handlers, passphrase-unlock admin decrypt, the `/admin/trail`
viewer, and `trailAdmins` in `auth-ok`). No existing message/DM path is touched. Net
diff: **+365 / −3 lines**, all additive (the 3 removals are the two `auth-ok` lines
getting `trailAdmins` appended). Repo commits: `36c30d7` (F+G+I) and `55869c3` (H+J+K+L),
on top of `56c5a80` (glitch filter).

**Who runs this:** someone/something with access to the live server — you, or the
server-side Claude Code you mentioned. This Cowork bridge session has no route to
`/opt/fshu5` (no egress, no SSH creds), so it cannot deploy itself.

## Steps (on the server)

1. **Back up:** `cp /opt/fshu5/server.js /opt/fshu5/server.js.bak-$(date +%F-%H%M)`

2. **Apply the additive change (respect the drift rule — don't blind-overwrite):**
   - First check whether live has drifted from the repo base:
     `diff /opt/fshu5/server.js <repo>/server.js` *at commit `56c5a80`*.
   - If the only differences are the additions in `server-t13-phase2.patch`, it is safe
     to copy the repo's HEAD `server.js` to `/opt/fshu5/server.js`.
   - If live has its own edits (real drift), **apply the patch instead of copying**:
     `cd /opt/fshu5 && git apply /path/to/server-t13-phase2.patch` (if `/opt/fshu5` is a
     git checkout), or hand `server-t13-phase2.patch` to the server-side Claude Code and
     have it apply the same additive blocks by their anchors (schema block, `defaultConfig`,
     the `switch`, `handleHttp`, and the trail-helper region — all stable, additive).

3. **Syntax check:** `node --check /opt/fshu5/server.js`  → must print nothing / exit 0.

4. **Copy the keygen tool** (needed once): copy `tools/trail-admin-keygen.js` to
   `/opt/fshu5/tools/`.

5. **Mint the admin key (once):**
   `node /opt/fshu5/tools/trail-admin-keygen.js`
   Enter a **daily** passphrase and an **offline recovery** passphrase (both ≥10 chars,
   different). Paste the printed object into `/opt/fshu5/data/config.json` as:
   `"trailAdmins": [ <the object> ]`
   Keep the recovery passphrase somewhere offline. **Until `trailAdmins` is non-empty,
   there is no admin recipient and nothing is stored for admin.**
   *(The other trail keys — `locationRetentionDays:7`, `trailMaxGuardians:5`,
   `trailStaleAlertHours:0`, `adminAccessNotifiesUser:false` — default automatically via
   the `defaultConfig` merge, so you don't have to add them unless you want non-defaults.)*

6. **Restart:** `systemctl restart fshu5` (or your instance name).

7. **Smoke test:**
   - `journalctl -u fshu5 -n 30` → "listening on port …", no crash.
   - `sqlite3 /opt/fshu5/data/fshu.db ".tables"` → shows `trail_guardians`,
     `trail_batches`, `trail_access_log`.
   - Browse `https://shumkov.eu/fshu5/admin/trail` → the admin viewer page loads.
   - (After the app build lands and a phone uploads: log in there with an admin account +
     the daily passphrase + a target username → the trail renders.)

## Rollback
`cp /opt/fshu5/server.js.bak-… /opt/fshu5/server.js && systemctl restart fshu5`
The new tables are harmless if left (empty, unused by old code).

## After deploy
- Build the app in Android Studio and install as an **update** (no clean install — no Room
  schema change in Phase 2/3; the 26→27 `susp` migration already runs on update). Only do
  a clean install if you hit `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signing-key mismatch).
- Run the Block F/G/I acceptance checklists in `SPEC_T13.md`.
