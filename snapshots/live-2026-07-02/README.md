# Snapshot: deployed server state, 2026-07-02

**This is a read-only capture of the DEPLOYED state of `/opt/fshu5` on
`192.168.212.105` as of 2026-07-02.** It is **NOT canonical** and **NOT a
reconciliation**. Nothing was changed on the server to produce it (`cat` /
`sqlite3 .schema` / `ls` only, no writes, no restart).

**Why this exists:** Phase 1 recon on T5 (see `PROJECT_MEMORY.md`) found that the
deployed `/opt/fshu5/server.js` contains code — including safety-relevant features
(emergency call, emergency location, SOS message, `hide_presence`, `group-file`
groupId support) — that is committed **nowhere** in this git repo. If that server's
disk were lost, that code would be gone with no history. This snapshot preserves it,
durably and diffably, until a deliberate reconciliation happens. **Do not merge this
into `server.js` as part of routine work** — reconciliation is a separate, explicit
task.

## What's in this directory

| File | Source | Notes |
|---|---|---|
| `server.js` | `/opt/fshu5/server.js` | Verbatim (`cat`), including original CRLF line endings as found on disk. Syntax-checked locally (`node --check`) — valid. |
| `schema.sql` | `sqlite3 /opt/fshu5/data/fshu.db '.schema'` | Structure only, no row data. |
| `manifest.txt` | `ls -la /opt/fshu5` + `ls -la /opt/fshu5/data` | Directory listing for both, filenames/sizes/timestamps only. |
| `README.md` | this file | |

## What was deliberately skipped (secrets / data)

- `firebase-adminsdk.json` — Firebase service-account private key.
- `secret.key` — server secret key material.
- `data.db`, `fshu5.db`, `fshu.db`, `fshu.db-shm`, `fshu.db-wal`, and all `*.bak*` DB
  files under `/opt/fshu5` and `/opt/fshu5/data` — actual row data (messages, users,
  etc.), not requested and out of scope for a code/schema snapshot.
- `node_modules/` — vendored dependencies, reproducible from `package.json`
  (not captured either, wasn't requested).
- `install-fshu-next.sh` — **not present on the server** (`find` came back empty);
  nothing to capture for this item.
- No other local `./*.js` modules are `require()`d by `server.js` at the top level
  (checked via `grep -n "require("` — only `ws`, `http`, `better-sqlite3`,
  `child_process`, `fs`, `path`, `crypto`, `bcrypt`, `firebase-adminsdk.json`
  (skipped, secret), `firebase-admin`, `nodemailer`; the two inline
  `require('crypto')` / `require('path')` calls are core Node modules, not local
  files). `/opt/fshu5/admin.js` exists on disk but is **not required by
  `server.js`** — it's a separate, presumably manually-run script — so it was left
  uncaptured per the task's scope ("*.js the server loads at top level").

## Live vs. repo `server.js` — feature diff summary

Compared `snapshots/live-2026-07-02/server.js` (this capture) against the
git-tracked `server.js` at the repo root, as of commit `62091a1` (T5 Phase 1). Line
endings normalized for the diff only (live file is CRLF on disk; repo is LF) — the
captured file itself is untouched/verbatim.

**Live-only — deployed, not in git (the reason this snapshot exists):**
- `hide_presence` column on `users` (added via a runtime `ALTER TABLE ... ADD COLUMN`
  guarded by try/catch) + `hidePresence` read/write in the privacy-settings message
  handler and `contacts` query.
- `contacts.allow_emergency_call` / `contacts.allow_emergency_location` used in
  `getContacts`; `stmt.getEmergencyAllow`, `stmt.updateEmergencyLocation`.
- Emergency-call gating on `call-offer`-style handling via `getEmergencyAllow`, and an
  `isEmergency` flag on the resulting `missed-call` push.
- `emergency-location-request` message handler.
- `sos-message` message handler.
- `set-emergency-allow` message handler.
- `emergency-location-update` message handler.
- Group-key self-healing: on `sendGroupStatesOnConnect`, if a member's
  `encrypted_group_key` slot is null, notify the owner (or the member, if they're the
  owner) via `group-key-needed` so the key can be re-encrypted/regenerated.
- `group-file` binary upload: a full `groupId` branch (group file uploads, member
  check, `fanOutGroupMessage`) — the repo's binary upload handler is DM-only
  (`to` required, no `groupId`/group branch).

**Repo-only — committed, not deployed:**
- `trust_level` column on `users` (schema + `insertUser` default `'contact'`),
  `getUserTrustLevel`/`setUserTrustLevel` statements, `trustLevel` surfaced in
  `getUserForExport`, contact lists, and group member/user listings.
- `admin-set-trust` admin-panel message handler.
- Family-group trust propagation: group-create seeds `family` trust for all members
  of a `family`-type group; group role changes, kicks, leaves, and group-delete
  demote a user back to `contact` trust when they're no longer in any family group
  (`getMemberFamilyGroups` check).
- `call-offer`-equivalent not-contact handling sends an explicit
  `call-reject`/`not-contact` back to the caller; live silently drops.

**Trivial, non-functional (found while diffing, not worth listing above):** one of
the three T5-patch comments added this session (`getRecentLists`, "group lists
(group membership) -- group polls...") differs by punctuation only — live has
`--`, repo has `—` — an artifact of the upload script's find/replace missing that
one string. The other two T5 comments match exactly. No behavioral difference.

**Confirmed identical:** all list/poll-related code from T5 Phase 1
(`insertList`, `getRecentLists`, `sendListState`, `broadcastListState`,
`userCanAccessList`, `list-create`, `list-sync-request`) — functionally identical
between live and repo, since that patch was applied to both independently this
session and verified byte-identical beforehand.

## Not done in this pass

No reconciliation, no merging, no deletion, no server changes of any kind. The two
feature sets above (live-only, repo-only) remain exactly as they are on each side
until a deliberate reconciliation task picks this up.
