# SPEC — T5: Polls in Groups (v2)

> **Status: DESIGN APPROVED (planning chat, 2026-07-02). NOT yet implemented.**
> v2 folds in the 2026-07-02 recon findings (Claude Code, full repo access).
> Reuses the todo-list transport and the `messages`/`list_items` storage. **No new
> crypto** — polls ride the existing group ECDH key.
> **Net effect of recon vs v1:** no Android Room migration; likely no server schema
> migration; the whole server change collapses to "make lists group-aware"; the
> Decision-4 tally-ordering is simpler than v1 assumed (no new seq column).

---

## 1. Summary

A **poll** is a group message type: a question, a set of options, and a live tally.
It is built as a **list with a `type:"poll"` discriminator**, reusing the todo-list
verbs (`list-create` / `list-edit` / `list-check` / `list-state`). Priority **P2**.
Testable on the G60.

Polls are the **first group use of lists** — lists are DM-only today. Making lists
group-aware is the single server touch-point (see §7).

---

## 2. The Core Constraint (read this first)

Every group item is **E2E-encrypted under the group ECDH key**, so **the server
cannot read poll content and cannot tally, dedupe, enforce close, or hide results.**
All poll *semantics* are a **client-side convention over relayed encrypted items**,
not a server guarantee. Three consequences (design facts, not bugs):

1. **No cryptographic anonymity is possible.** Every member's client decrypts every
   ballot (voter + choice), and the authenticated envelope sender is visible to the
   server at send time. → **named only** in v1 (Decision 2).
2. **"Hidden until close" cannot be enforced.** Every client holds every ballot. →
   **results always live** in v1 (Decision 3).
3. **One-vote-per-person is a client convention**, resolved by last-write-wins per
   voter, in server arrival order (Decision 4).

### 2a. Accepted metadata property (decided 2026-07-02)
To collapse a voter's re-votes to a single row, a ballot's `item_id` is a **stable
per-voter identifier**, and `item_id` is the server-side primary key — i.e.
**plaintext to the server**. Therefore the self-hosted server can see, per poll,
**which identities cast a ballot (participation)** — but never **their choices**
(encrypted in the item body). This leaks nothing beyond what the authenticated
envelope sender already reveals at send time (§2). **Accepted as a known metadata
property** for a trusted self-hosted server; not engineered around (hashing `item_id`
would obscure only the at-rest DB while the live envelope still identifies the
sender, so it buys almost nothing).

---

## 3. Data Model (revised per recon)

### 3a. How lists are actually stored (recon finding)
- **Android:** there is **no Room `@Entity` for list items.** Room entities are only
  `Message, PeerKey, Group, GroupMember, Contact, Block, Mute`. A "list" is **one
  `messages` row** (`type="list"`) whose `content` column holds the entire items
  array as **JSON text**, with `listId` / `listVersion` / `listOwner` fields on
  `Message`. ⇒ **Zero Android Room migration for T5.** A poll is a list-message whose
  JSON carries poll shape + a `type:"poll"` discriminator. The client already
  receives the full items array in `list-state`, so local tally is trivial.
- **Server:** `list_items` = `(item_id, list_id, text, done, checked_by, checked_at,
  deleted_at, sort_order)`, PK `(item_id, list_id)`. `lists` =
  `(list_id, owner, peer, group_id, version, created_at, message_id)`. **`group_id`
  already exists** (present in schema, currently unused, 0 rows populated). No
  dedicated JSON column — the ballot/option payload packs into the existing **`text`**
  column (already an encrypted blob, consistent with server-blindness). ⇒ **Likely no
  server schema migration** (confirm during impl; no `ALTER TABLE` anticipated).

### 3b. Poll meta (in the poll's list-message JSON / list-create payload)
| Field | Values | Notes |
|---|---|---|
| `type` | `"poll"` | Renders as a poll, never as a todo list. |
| `mode` | `"single"` \| `"multi"` | Declared at creation, immutable. Default `single`. No `maxChoices` cap in v1. |
| `status` | `"open"` \| `"closed"` | Client-honored. Owner/admin sets `closed`. No auto-expiry in v1. |
| `question` | string | The poll question. |

### 3c. Options
Created up front at `list-create`. Each: `{ kind:"option", option_id, label,
position }`, packed into a list item's `text`.

### 3d. Ballots
Each: `{ kind:"ballot", selected:[option_id…] }` packed into the item `text`, on a
list item whose **`item_id` = a stable per-voter identifier** (see §2a, §4).
- `voter_key`/identity = the voter's **user identity**, not per-device (one human =
  one vote across devices).
- `single` mode ⇒ client enforces `selected` length ≤ 1.
- Empty `selected: []` = explicit abstain/retract.
- **One row per voter**, upserted on `(item_id, list_id)` — never append-per-change.

---

## 4. Tally & Ordering (revised — simpler than v1)

Client tally: for each option, count ballots whose latest state includes that
`option_id`.

**"Latest per voter" is resolved by SERVER ARRIVAL ORDER via upsert — no new
sequence column needed (recon revision).** v1 specified a server-assigned per-item
sequence to beat multi-device clock skew. Recon shows none exists (`sort_order` is a
dead column, `lists.version` is list-level). It also shows the existing `list-edit`
**upsert on `(item_id, list_id)`** already gives server-receive-order last-write-wins:

- A voter's ballots all share the **same stable `item_id`**, so a re-vote **upserts
  the same row** — the server collapses them in arrival order, immune to client
  clocks. No `seq` column added; `sort_order` stays dead.
- `lists.version` (bumped on edit) handles client snapshot freshness.
- Residual: two of a voter's own devices upserting in the same instant resolve
  deterministically to server arrival order — acceptable for v1.

**Eventual consistency:** clients can transiently disagree mid-propagation; this
self-heals — the per-voter upsert + `lists.version` guarantee convergence to the same
final tally regardless of arrival order. Expected behavior, not a bug.

Re-tally runs on each `list-state`; cost = counting N ballots (N = group size) —
trivial, no debounce.

---

## 5. Locked Decisions (planning chat, 2026-07-02)

1. **Single vs multi:** both, **chosen at creation**, `mode` in meta, default
   `single`. `single` = client enforces `selected` ≤ 1 + radios; `multi` =
   checkboxes, unbounded. **No `maxChoices` cap in v1** (addable later as meta, no
   format change).
2. **Anonymous vs named:** **named only in v1.** Cryptographic anonymity impossible
   under group-key relay (§2). No "anonymous" checkbox ships. If an
   anonymous-*display* mode is ever added, it ships with an explicit "names hidden;
   NOT cryptographically anonymous — other members can see votes in raw data"
   disclosure. Deferred.
3. **Close / expiry / results visibility:**
   - Owner/admin can **close early** (role check reuses existing `group-rename`
     owner/admin logic). Close = `list-edit` setting `status:"closed"`; honest clients
     then reject new ballots. Client-honored, **not** server-enforced.
   - **No auto-expiry in v1** (skew + blind-server edge cases; manual close covers
     the need; addable later as meta).
   - **Results always live/visible in v1.** "Hide until close" unenforceable (§2),
     deferred.
4. **Vote change / revocation:**
   - Change while open by **re-emitting the ballot** (upsert on same
     `(item_id, list_id)`); latest wins by **server arrival order** (§4).
   - **Retract = empty-ballot** (`selected: []`), distinct from never voting.
   - **Identity = user identity**, not per-device (one human = one vote).
   - One upserted ballot row per voter, `item_id` = stable per-voter id (§2a).
   - Closed polls reject new/changed ballots (client-honored).
5. **Live-update latency:** **reuse the existing `list-state` push.** No dedicated
   channel, no throttle. A ballot propagates exactly like a todo-item edit. Results
   eventually consistent, converge per §4.
6. **Offline catch-up / late-joiners:**
   - **Offline existing members:** the existing `enqueue`/`flushQueue` path is
     generic and already reused for group fan-out elsewhere — it just needs
     `broadcastListState` to loop group members (§7). No new storage.
   - **Late-joiners** (added after poll creation): **no special handling in v1.**
     Polls inherit normal group history visibility (see polls created after you
     joined, not before) — matches all other group items in an E2E system.
     - **Known v1 limitation:** a member added mid-poll won't see/vote in it.
       Acceptable for small trusted groups; revisit (owner-rebroadcast) only if
       mid-poll additions prove common.
   - **Closed polls:** **no separate stored final-result summary.** Result is a pure
     function of held ballots; re-tally is cheap. A server-side summary would be an
     unverifiable member-supplied blob (blind server); a client one is redundant
     drift-prone state. Recompute on demand.

---

## 6. Transport / Protocol Reuse (revised per recon)

- **Ballots go through `list-edit` ONLY.** Recon: `list-check` silently no-ops on an
  unknown `item_id` (it can only toggle an item already present in `list.items`), so
  it **cannot originate a first ballot row.** `list-edit` upserts arbitrary items
  including new ones. ⇒ ballots always use `list-edit`; `list-check` is unused by
  polls.
- **Verbs:** `list-create` (poll meta + options), `list-edit` (ballot upsert, close,
  option/meta edits), `list-state` (push + tally source). `list-check` not used.
- **No new crypto.** Ballots relayed as encrypted items over the existing group key.
- **No new protocol verbs.** The only server work is routing/fan-out (§7).

---

## 7. Server Change — collapses to "make lists group-aware" (recon)

All findings below are confirmed against the live repo/server (read-only).

- **`broadcastListState` (server.js) is DM-hardwired** — sends to `list.owner` /
  `list.peer` only, never touches `group_id` or `getGroupMembers`. This is the whole
  T5 server touch-point.
- **The template already exists:** `broadcastGroupState` iterates
  `getGroupMembers.all(groupId)` and `sendToAll` if online / `enqueue` if not. Making
  `broadcastListState` loop group members the same way fixes **both** online delivery
  **and** offline-queue catch-up (Decision 6) in one change, because `enqueue` is a
  generic per-username primitive already reused across the codebase (group-state,
  avatar upload, rename/delete).
- **Also read/write `group_id`** in `list-create`, `list-sync-request`, and
  `getRecentLists` so group polls associate to a group instead of a DM peer.
- **`lists.group_id` already exists** (unused) ⇒ no `ALTER TABLE` anticipated for the
  list↔group association. Confirm no other schema change is needed; if none, **no
  install-script update** (install script only needs touching when server schema/config
  changes).

### Resolved recon items (were §7 pending in v1)
1. **Stale Room-16 doc — FALSE ALARM / CLOSED.** Repo `PROJECT_KNOWLEDGE.md` says
   Room **25** and matches `@Database` exactly; no Electron-phase copy in repo. (The
   circulating Room-16 copy is a stale artifact outside the repo — delete it at
   source.)
2. **`list_items` columns — CONFIRMED.** No JSON column; ballot packs into `text`;
   no migration.
3. **Group fan-out — CONFIRMED DM-hardwired** (the change above).
4. **Monotonic ordering — CONFIRMED none exists; NOT NEEDED** (upsert gives arrival
   order, §4).
5. **Upsert-by-key — CONFIRMED** via `list-edit` ON CONFLICT `(item_id, list_id)`.
6. **Offline queue group coverage — CONFIRMED** the queue is generic; only
   `broadcastListState` needs the member loop.

---

## 8. Out of Scope for v1

Cryptographic anonymity / anonymous-display mode · hide-results-until-close ·
time-based auto-expiry · `maxChoices` cap on multi-select · late-joiner retroactive
poll visibility / owner-rebroadcast · stored closed-poll summaries · server-side
tally/dedupe/close enforcement (impossible under E2E — by design).

---

## 9. Testability (G60)

Create poll (single + multi); cast/change/retract across two devices (same identity
⇒ one vote; different members ⇒ independent votes); close as owner/admin; verify
non-owner cannot close; verify live tally convergence; verify an offline member
catches up on reconnect (validates the new group fan-out + queue path).

---

## 10. Rules Reminder (unchanged)

SSH LAN only (`ssh root@192.168.212.105`, never the public IP) · no Tink/Google
crypto on Android, no npm crypto on server · SQLite only · mobile only · Ivan builds
all APKs — Claude Code never runs Gradle or adb · Claude Code updates
`PROJECT_MEMORY.md` and commits it alongside every code change · update the install
script only if `server.js` changes affect config/schema (not anticipated for T5).
