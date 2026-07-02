# SPEC — T5: Polls in Groups

> **Status: DESIGN APPROVED (planning chat, 2026-07-02). NOT yet implemented.**
> Reuses the existing todo-list transport and `list_items` Room table. **No new
> crypto** — polls ride the existing group ECDH key.
> **Do not write code until the "Pending Recon" section below is resolved** —
> several design facts depend on transport details that must be confirmed against
> the live repo/server first.

---

## 1. Summary

A **poll** is a group message type that lets members vote on a question with a set
of options and see a live tally. It is built as a **list with a type discriminator**,
reusing the todo-list verbs (`list-create` / `list-edit` / `list-check` /
`list-state`) and the `list_items` table. Priority **P2**. Testable on the G60.

Polls are the **first group use of lists** — todo lists are DM-only today. That
makes group routing / fan-out the most likely server touch-point (see Pending Recon).

---

## 2. The Core Constraint (read this first)

Every group item is **E2E-encrypted under the group ECDH key**, so **the server
cannot read poll content and therefore cannot tally votes, dedupe voters, enforce
close, or hide results.** Everything about a poll's *semantics* is a **client-side
convention over relayed encrypted items**, not a server-enforced guarantee.

Three consequences shape the design and MUST be surfaced honestly in the UI/spec —
they are not bugs:

1. **No cryptographic anonymity is possible.** Every member's client decrypts every
   ballot, including the voter identity, and the authenticated envelope sender is
   visible at the transport layer. "Anonymous" could only ever be a display
   convention, never a guarantee. → v1 ships **named only** (Decision 2).
2. **"Hidden until close" cannot be enforced.** Every client already holds every
   ballot, so any member can tally early from raw data. → v1 ships **results always
   live** (Decision 3).
3. **One-vote-per-person is a client convention, not server-enforced.** Resolved by
   last-write-wins per voter identity, ordered by server relay sequence (Decision 4).

For a small trusted group these are acceptable. They are documented here so nobody
later mistakes an honor-system property for a security guarantee.

---

## 3. Data Model

A poll is a `list` row (or however lists are keyed — confirm in recon) carrying poll
meta, plus `list_items` rows of **two kinds**, separated by a `kind` discriminator:

### Poll meta (on the list / list-create payload)
| Field | Values | Notes |
|---|---|---|
| `type` | `"poll"` | Distinguishes a poll from a todo list. Poll-type lists render as a poll, never as a todo list. |
| `mode` | `"single"` \| `"multi"` | Declared at creation, immutable. Default `single`. No `maxChoices` cap in v1. |
| `status` | `"open"` \| `"closed"` | Client-honored. Owner/admin can set `closed`. No auto-expiry in v1. |
| `question` | string | The poll question (natural home is the list title). |

### `list_items` rows — kind = `option`
| Field | Notes |
|---|---|
| `kind` | `"option"` |
| `option_id` | stable id for the option |
| `label` | option text |
| `position` | display order |

Options are created up front at `list-create` time.

### `list_items` rows — kind = `ballot`
| Field | Notes |
|---|---|
| `kind` | `"ballot"` |
| `voter_key` | **the voter's user IDENTITY key, not a per-device key** — one human = one vote across all their devices |
| `selected` | array of `option_id`s. `single` mode ⇒ client enforces length ≤ 1. Empty array `[]` = explicit abstain/retract. |

**Storage shape:** **one ballot row per `voter_key`**, upserted in place on
`(list_id, voter_key)` — NOT append-a-row-per-vote-change. Growth is bounded to N
voters, not N×(changes). Vote change = re-emit/upsert the same row; retract =
upsert to empty `selected` (or delete the row — decide in impl once upsert semantics
are confirmed).

> **Schema note (PENDING RECON):** this requires `list_items` to carry a free
> text/JSON field able to hold the ballot payload (`kind`, `voter_key`, `selected[]`).
> If the table already has such a field, pack JSON into it → **no migration**. If it
> is only `text + checked + position`, either reuse `text` for the JSON blob (no
> migration) or add a nullable column (small migration). Confirm the real column list
> before finalizing this.

---

## 4. Tally (client-side)

For each option, count the ballots (latest per `voter_key`) whose `selected[]`
contains that option's `option_id`.

- **"Latest per voter" is resolved by SERVER-ASSIGNED SEQUENCE, not client `ts`.**
  4shu is multi-device; two devices have two clocks, and skew could otherwise let a
  stale vote win. The server is blind to ballot *content* but still controls relay
  *order*, so it can stamp a monotonic sequence (seq / server-side receive order /
  rowid) on each relayed `list-*` item **without decrypting anything**.
- Re-tally runs on every `list-state` the client receives. Cost = counting N ballots
  (N = group size) — trivially cheap, no debounce needed.
- **Eventual consistency:** two clients can transiently disagree mid-propagation
  (A has seen a ballot B hasn't). This self-heals; the server sequence guarantees all
  clients converge to the same final tally regardless of arrival order. This is
  expected behavior, not a bug.

---

## 5. Locked Decisions (planning chat, 2026-07-02)

1. **Single vs multi:** support both, **chosen at creation**, `mode` in meta,
   default `single`. `single` = client enforces `selected` length ≤ 1 + renders
   radios; `multi` = checkboxes, unbounded. **No `maxChoices` cap in v1** (can be
   added later as meta with no format change).
2. **Anonymous vs named:** **named only in v1.** Cryptographic anonymity is
   impossible under group-key relay (see §2). No "anonymous" checkbox ships — that
   would be security theater in a security product. If an anonymous-*display* mode is
   ever added, it ships with an explicit "names hidden in results; NOT
   cryptographically anonymous — other members can see votes in the raw data"
   disclosure. Deferred.
3. **Close / expiry / results visibility:**
   - Owner/admin can **close early** (role check reuses the existing
     `group-rename` owner/admin logic). Close = `list-edit` setting `status:"closed"`;
     honest clients then reject/ignore new ballots. Client-honored, **not**
     server-enforced.
   - **No auto-expiry in v1** (clock-skew + blind-server edge cases for little value;
     manual close covers the need; can add later as meta).
   - **Results always live/visible in v1.** "Hide until close" is unenforceable
     (see §2) and is deferred; if ever added, ships with the same "not enforced"
     disclosure.
4. **Vote change / revocation:**
   - Change while open by **re-emitting the ballot** (upsert on `(list_id,
     voter_key)`); latest wins.
   - **Retract = empty-ballot** (`selected: []`), distinct from never having voted.
   - **"Latest" ordered by server sequence, not client `ts`** (see §4).
   - **`voter_key` = user identity key**, not per-device (one human = one vote).
   - One upserted ballot row per voter.
   - Closed polls reject new/changed ballots (client-honored).
5. **Live-update latency:** **reuse the existing `list-state` push.** No dedicated
   poll channel, no throttle. A ballot propagates exactly like a todo-item check.
   Results eventually consistent, converge via the §4 server sequence.
6. **Offline catch-up / late-joiners:**
   - **Offline existing members:** rely on the existing `list-state` offline queue
     (already queues for offline todo-list members; group coverage is PENDING RECON).
     On reconnect they receive queued `list-state`, re-tally, converge. No new
     storage.
   - **Late-joiners** (added to the group *after* poll creation): **no special
     handling in v1.** Polls inherit normal group history visibility — you see polls
     created after you joined, not before. This matches how *all* group items already
     behave in an E2E system; making polls the exception would require a general
     "retroactive group history for new members" subsystem, out of scope for P2.
     - **Known v1 limitation:** a member added mid-poll won't see that poll and can't
       vote in it. Acceptable for small trusted groups. Revisit (owner-rebroadcast
       path) only if mid-poll additions turn out to be common.
   - **Closed polls:** **no separate stored final-result summary.** The result is a
     pure function of held ballots; re-tally is cheap. A server-side summary would be
     an unverifiable member-supplied blob (server is blind); a client-side one is
     redundant state that can drift. Recompute-on-demand instead.

---

## 6. Transport / Protocol Reuse

- **Verbs:** `list-create` (poll + options), `list-edit` (ballot upsert, close,
  option/meta edits), `list-check` (may or may not be needed — a ballot is richer
  than a boolean check; confirm during impl whether `list-edit` alone suffices),
  `list-state` (push + tally source).
- **No new crypto.** Ballots are relayed as encrypted items over the existing group
  ECDH key.
- **New protocol surface should be minimal to none.** The likely server change is
  **routing/fan-out**, not new verbs (see Pending Recon #3).

---

## 7. Pending Recon — MUST resolve before writing poll code

Claude Code confirms these against the live repo/server first. Any of them can force
small spec revisions (they are marked in-text above).

1. **Reconcile PROJECT_KNOWLEDGE.** The repo/working copy must be reconciled to the
   uploaded 2026-06-29 version (**Room schema 25, mobile-only, maintenance mode**).
   A stale copy showing **Room 16** and an Electron phase has been circulating; it
   contradicts PROJECT_MEMORY's 2026-06-28 decisions log and Ivan's stated
   direction. **T5 writes to `list_items`; reasoning against Room 16 would be nine
   versions out of date.** Diff repo `PROJECT_KNOWLEDGE.md` vs the uploaded June-29
   file; if the repo holds the stale copy, replace it; if the stale copy is only a
   local artifact on Ivan's side, note it and move on. Confirm the **actual current
   Room version from the repo** before any migration reasoning.
2. **Exact `list_items` columns.** Determines whether the ballot payload needs a
   migration or packs into an existing field (see §3 schema note).
3. **Group routing / fan-out.** Lists are **DM-only** today. Confirm whether the
   server already fans `list-*` / `list-state` out over **group membership**, or
   whether list routing is hardwired to a single DM peer. If DM-hardwired, T5 needs
   server work to broadcast `list-*` over group members — **the main expected
   `server.js` touch-point** (and therefore an install-script update if schema/config
   is affected).
4. **Monotonic server ordering.** Confirm whether relayed `list-*` items already
   carry a server-assigned monotonic order (seq / server ts / rowid). If yes → reuse
   for §4 tally ordering. If no → add one (small, non-crypto server change).
5. **Upsert-by-key across devices.** Confirm `list-edit` can target/replace an
   existing item by a stable key (`(list_id, voter_key)`) across a user's devices, to
   support the one-row-per-voter model (§3).
6. **Offline queue covers group lists.** Confirmed for DM lists; groups are the new
   surface. Confirm the queue carries `list-*` for group lists (Decision 6a).

---

## 8. Out of Scope for v1

- Cryptographic anonymity / anonymous-display mode.
- Hide-results-until-close.
- Time-based auto-expiry.
- `maxChoices` cap on multi-select.
- Late-joiner retroactive poll visibility / owner-rebroadcast.
- Stored closed-poll summaries.
- Server-side tally, dedupe, or close enforcement (impossible under E2E — by design).

---

## 9. Testability

Testable on the G60: create poll (single + multi), cast/change/retract votes across
two devices (same identity → one vote; different members → independent votes), close
as owner/admin, verify non-owner cannot close, verify live tally convergence, verify
an offline member catches up on reconnect.

---

## 10. Rules Reminder (unchanged)

SSH LAN only (`ssh root@192.168.212.105`, never the public IP) · no Tink/Google
crypto on Android, no npm crypto on server · SQLite only · mobile only · Ivan builds
all APKs — Claude Code never runs Gradle or adb · Claude Code updates
`PROJECT_MEMORY.md` and commits it alongside every code change · update the install
script if `server.js` changes affect config/schema.
