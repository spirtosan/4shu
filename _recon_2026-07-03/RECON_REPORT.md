> Note (2026-07-03): scratch artifacts removed after close-out; this report + STRAY_FILES.txt retained as the record.

# Recon Report — 2026-07-03 (READ-ONLY, no changes made)

Safety backup taken first: `/mnt/ivan/backups/fshu-next/fshu5-server-<timestamp>.tar.gz` (live, `node_modules` excluded). SSH used LAN IP `192.168.212.105` only, throughout.

## 1. Repo state

- HEAD: `dfdc30f1179ae85b86cc2fb2a9f2eef84c29ab81` (`dfdc30f docs(T7): refresh screen-share spec...`) — matches the HEAD shown at session start. **Tracked tree is clean** (no staged/unstaged changes to tracked files).
- Untracked, pre-existing (not created by this recon, left alone): `KEEPALIVE_OEM_ANALYSIS.md`, `T2_16KB_FIX_PLAN.md`, `call-crash-log.txt`.
- Full detail: `REPO_STATE.txt`.

## 2. Three-way server.js comparison

| File | Lines | SHA256 | Line endings |
|---|---|---|---|
| `server.repo.js` | 2979 | `5f6c666d...9e69f35` | CRLF |
| `server.snapshot.js` | 3055 | `4d066bb5...381d39` | CRLF |
| `server.live.js` | 3055 | `4d066bb5...381d39` | CRLF |

**Snapshot and live are byte-identical (same SHA256).** Live has **not drifted** from the 2026-07-02 snapshot — that snapshot is still a fully accurate mirror of what's deployed today. All reconciliation work only needs to reason about repo ↔ (snapshot=live).

Repo differs from live in exactly the way `PROJECT_MEMORY.md`'s "server.js repo/live drift" note already describes — confirmed byte-for-byte below, nothing new found.

Full diffs: `DIFF_snapshot_vs_live.txt` (empty/identical), `DIFF_repo_vs_live.txt` (224 lines).

### Repo-only (drop-candidates — not deployed, would be lost/no-op if repo were pushed as-is)
- `users.trust_level` column (schema DDL) + `insertUser` seeding it to `'contact'`
- `getUserTrustLevel` / `setUserTrustLevel` prepared statements
- `getUserForExport` selecting `trust_level`
- `trustLevel` field in the contacts/users broadcast payload (3 send sites: online-users push, group-aware users query, GDPR export query)
- `admin-set-trust` message handler (explicit admin RPC)
- Family-group trust propagation in `group-create`, `group-invite`, `group-kick`, `group-leave`, `group-delete` (writes `trust_level='family'` on join, demotes to `'contact'` on exit via `getMemberFamilyGroups`)

### Live-only (keep — safety/feature code not yet in repo)
- `contacts.hide_presence` column + migration, `phone_searchable`/`hide_presence` in privacy-settings update, `hidePresence` field in `set-privacy` handler
- `contacts.allow_emergency_call` / `allow_emergency_location` columns, `getEmergencyAllow` / `updateEmergencyLocation` prepared statements
- Emergency-call gating on `call-offer` (checks `getEmergencyAllow`, FCM wakeup via `sendFcmWakeup`, `isEmergency` flag on missed-call)
- `emergency-location-request` handler
- `sos-message` handler
- `set-emergency-allow` / `emergency-location-update` handlers
- Group-key self-heal on connect (974a984–1004: detects a member/owner with a null `encrypted_group_key` and pushes `group-key-needed`)
- `group-file` binary upload groupId branch (group file fan-out; repo's binary-upload handler is DM-only, requires `to`, has no `groupId` path)

### Common
Everything else — the bulk of the ~3000-line file (auth, contacts, DM messaging, calls minus emergency gating, reactions, lists/polls incl. all of T5, groups minus the family/trust hooks, devices, invites, password reset, join page, file transfer minus the group branch) is untouched and identical between repo and live.

### Cosmetic
- Line 424: one comment uses an em dash (`—`) in the repo vs a double-hyphen (`--`) in live, in a T5-related comment about DM/group list union. No functional difference.

## 3. `trust_level` load-bearing assessment

**Verdict: not load-bearing for anything that remains after removal.** Full grep results in `GREP_trust_repo.txt` / `GREP_trust_live.txt`.

- **Repo server.js**: every read/write of `trust_level` is confined to (a) bookkeeping writes inside family-group membership events (create/invite/kick/leave/delete — write-only, nothing gates on the value there), (b) a display-only field echoed back in the contacts/users broadcast and GDPR export, and (c) the explicit `admin-set-trust` RPC. Verified directly by reading the group-create/invite/kick/leave/delete handlers (server.js:2400–2576): the `family`-type branches call `setUserTrustLevel.run(...)` but never branch on the *value* to allow/deny anything.
- **Not touched by**: message routing (`message`, `group-message` handlers), call gating (`call-offer`/`call-answer`/`call-reject`/`call-busy` — the *live* emergency-gating code uses `allow_emergency_call`/`allow_emergency_location`, a completely separate contacts-table mechanism, not `trust_level`), emergency/SOS (`sos-message`, `emergency-location-request` — same, uses the emergency-allow columns), presence (`hide_presence` — independent column/feature), `group-file` (groupId branch has zero trust_level references).
- **Live server.js**: has zero live `trust_level` references. It does retain one vestigial artifact — `getMemberFamilyGroups` prepared statement is *declared* (line 457) but **never called anywhere else in live** (repo calls it 3 times in kick/leave/delete; live's own grep for any call site beyond the declaration returned nothing). Harmless dead code, not wired to anything.
- **Android app**: `trustLevel`/`trust_level` do not appear anywhere in `app/src` except one historical Room migration line (`MIGRATION_16_17` adds the column). `MIGRATION_23_24` rebuilds the `contacts` table without `trust_level` (matches `4shu_db_schema.md`'s note "migration 24: recreated contacts table, dropped trust_level column"), and there's no `trustLevel` field on the `Contact` entity. **The client already fully dropped this column** in a past migration — this reconciliation only concerns the server.

## 4. DB schema

- Live DB schema and the 2026-07-02 snapshot schema are **identical** (`schema.live.sql` vs `schema.snapshot.sql`, diffed byte-for-byte after CR normalization — no differences).
- **`trust_level` does not exist as a column in the live database** (`grep -n trust_level schema.live.sql` → no match). Confirms the repo-only column was never deployed/migrated live — there is no live data to preserve or migrate if the repo code is dropped.

## 5. Line-ending situation

All three `server.js` copies use **CRLF** uniformly. No mixed-line-ending risk for a future merge; a plain line-based diff/merge (with `--strip-trailing-cr` or after normalizing) is safe.

## 6. Service sanity

`systemctl is-active fshu5` on live → `active`. No live disruption during recon.

## Bottom line for the reconciliation pass

1. Snapshot = live, so the reconciliation is really just **repo vs. (snapshot=live)** — one diff, not three.
2. The `trust_level` / family-propagation / `admin-set-trust` code in the repo is safe to delete: it's write-only bookkeeping and a display field, not read by anything that gates behavior, has no live DB column to migrate, and the client already removed its side of it in Room migration 24.
3. The live-only safety features (emergency-allow, hide_presence, group-key self-heal, group-file groupId) need to be ported **into** the repo (repo is missing them, not the other way around) before the next deploy, or that deploy will regress live behavior.
4. One cosmetic comment-style diff, no action needed.

## Artifacts in this folder

`REPO_STATE.txt`, `META.txt`, `server.repo.js`, `server.snapshot.js`, `server.live.js`, `DIFF_snapshot_vs_live.txt`, `DIFF_repo_vs_live.txt`, `GREP_trust_repo.txt`, `GREP_trust_live.txt`, `schema.live.sql`, `schema.snapshot.sql`, `SNAPSHOT_README.md`, `RECON_REPORT.md` (this file).

**No files were merged, edited, committed, or deployed. `PROJECT_MEMORY.md` was not touched.**
