# SPEC — Trail server-side persistence (Phase 2/3): admin-readable, encrypted, always-lands

**Status: SHIPPED & DEPLOYED — no build work outstanding.** Blocks **F–L are implemented,
committed, and live.** Commits: `36c30d7` (F+G+I: schema+config+keygen, handlers +
passphrase-unlock admin decrypt, client upload engine w/ priority resend), `55869c3`
(H+J+K+L: frozen-clock purge, last-gasp/PANIC, admin viewer), `f89408b` (live deploy to
`/opt/fshu5`); glitch filter jump+detour in `56c5a80`/`0651917`. HEAD `5c8b804`.
The admin passphrase-unlock decrypt path is **field-proven — Ivan decoded a live trail with
it (2026-09-04)**; guardian grant/accept/fetch/export path complete. **Only remaining item:
two-device end-to-end acceptance testing** (runbook in `NEXT_SESSION_T13.md`), which Ivan
runs when both devices are available.
*(Design was locked 2026-08-27; the original "No code written this session" header referred
ONLY to that design session and is SUPERSEDED — implementation shipped in the sessions that
followed. Always check `git log` before assuming build state.)*
**§5 `susp` carry-through VERIFIED 2026-09-04** (see §5).
**Policy decisions LOCKED 2026-09-04 (Ivan):** admin reads are **silent to the user — no
push, ever** (§6); admin passphrase = **single, held offline** (§3). The already-minted,
deployed admin key carries the keygen's default daily+recovery wraps — harmless; Ivan keeps
one passphrase offline and no re-mint is done.
**Parent / authority:** `SPEC_T13.md`. **This document AMENDS SPEC_T13 §1(1) and §5** —
see §7. **Priors:** `claude/next-session-trail-server-upload.md` (kickoff),
`claude/gps-trail-findings-2026-08-23.md`, `claude/spec-t13-glitch-filter-2026-08-23.md`.

---

## 0. Guardrails Ivan set (the requirements this design must meet)

1. **Admin access.** The trail must be readable by the admin — both the *server admin*
   (operator) and an *in-program admin* role.
2. **Constant, ASAP delivery.** The trail uploads as soon as possible; if the Internet is
   temporarily down, the backlog is sent **with priority the moment connectivity returns.**
3. **Encrypted in transit and at rest** on the server.
4. **Good documentation** (this doc).

Two design choices Ivan locked this session:
- **Admin key placement:** *passphrase-unlocked.* The server stores the admin private key
  **encrypted under an admin passphrase**; the server cannot read trails at rest, and an
  authenticated admin unlocks decryption for a session by entering the passphrase.
- **Number of admins:** *not fixed yet* → build for **one admin keypair now**, but keep
  the design able to add more admin recipients later **without a schema change** (§2).

---

## 1. The scheme — "admin is a mandatory recipient; the admin key is passphrase-locked"

The core idea in one sentence: **every trail batch is encrypted to its guardians *and* to
a standing admin keypair, using the app's existing message crypto; the admin's private key
lives on the server but only as ciphertext that an admin passphrase unlocks.** This meets
all three security guardrails at once — admin can always read, guardians keep their own
end-to-end copies, and at rest the server holds nothing readable.

### 1.1 Key hierarchy

| Key | Type | Where it lives | Protected by |
|---|---|---|---|
| Guardian device keys | X25519 (existing DM keys) | each guardian's device only | the device (unchanged, true E2E) |
| **Admin public key** `admin_pub` | X25519 | shipped in app config / fetched at trail-enable | public — no secrecy needed |
| **Admin private key** `admin_priv` | X25519 | **server**, stored as ciphertext | AES-256-GCM under `KDF(passphrase)` |
| Per-batch content key | AES-256-GCM (ephemeral, per recipient) | never stored | derived per-recipient via ECDH+HKDF |

`admin_priv` at rest on the server:
```
salt        = 16 random bytes            (stored)
kek         = Argon2id(passphrase, salt) (NEVER stored — derived at unlock time)
admin_priv_enc, nonce = AES-256-GCM(key=kek, plaintext=admin_priv)   (stored)
```
The passphrase itself is never stored anywhere. Argon2id (preferred; scrypt/PBKDF2-HMAC
with a high work factor acceptable if Argon2 isn't available on the Node build). Wrong
passphrase → `kek` is wrong → GCM authentication fails → no decryption, no oracle.

### 1.2 Encrypt path (on the phone, per batch) — reuses the DM envelope, no new primitive

Identical machinery to the existing direct-message envelope (`EcdhHelper.kt`,
`CryptoHelper.kt`, `PeerKeyDao`), so this honors SPEC_T13 §1 "MATCH EXISTING, no new
crypto primitives." For each batch of points, for **each recipient** (every accepted
guardian device **plus** the admin):

```
ephemeral ECDH(sender, recipient_pub) → shared secret → HKDF → AES-256-GCM key
ct = AES-256-GCM(key, iv, JSON-array-of-points)
```
The batch on the wire is exactly SPEC_T13 §2.2, with the admin as one more `for[]` entry:
```json
{"type":"trail-batch","batchId":"<uuid>","device":"<deviceId>",
 "seqLo":101,"seqHi":123,"tsLo":...,"tsHi":...,
 "for":[
   {"g":"maria","dev":"<guardianDeviceId>","iv":"<b64>","ct":"<b64>"},
   {"g":"__admin__","dev":"__server__","iv":"<b64>","ct":"<b64>"}
 ]}
```
**Wire format unchanged. Server tables unchanged** (`trail_batches.guardian` just stores
`"__admin__"` for the admin row). The admin recipient is mandatory and always present.

### 1.3 Admin view path (on the server, passphrase-gated)

```
Admin authenticates to the admin surface  ──▶  enters passphrase
        │
        ▼
server: kek = Argon2id(passphrase, salt);  admin_priv = AES-GCM-decrypt(admin_priv_enc)
        │   (admin_priv held in memory for this session only; zeroed on logout/timeout)
        ▼
for each requested batch row (guardian="__admin__"):
    key = HKDF(ECDH(admin_priv, batch_ephemeral_pub));  points = AES-GCM-decrypt(ct)
        │
        ▼
render trail to admin  +  write trail_access_log row (audit)
```
The decrypted `admin_priv` never touches disk and is wiped when the admin session ends or
times out. So the server can read trails **only** during an active, authenticated admin
session — never at rest, never in a background job, never from a stolen disk image.

### 1.4 How this satisfies each guardrail

- **#1 admin access:** admin is a mandatory recipient on every batch; the passphrase
  unlocks decryption of any user's trail. (Guardians independently keep their own copies.)
- **#2 delivery:** orthogonal to crypto — see §4.
- **#3 encrypted in transit + at rest:** in transit = WSS/TLS **plus** the envelope
  ciphertext (double-wrapped; even a TLS-terminating proxy sees only ciphertext). At rest
  = the server stores only `ct` (trail ciphertext) and a passphrase-encrypted `admin_priv`.
  Nothing on the server's disk is readable without the passphrase.

---

## 2. Room for more admins later (Q2 = "not sure yet") — no schema change needed

The `for[]` fanout already carries an arbitrary recipient list, and
`trail_batches.guardian` is a free-form string id. So multiple admins are a **config**
change, never a schema change:

- Server config holds a **list** of admin entries, each: `{ id, admin_pub, salt,
  admin_priv_enc, nonce }` — e.g. `__admin__` today, `__operator__` / `__admin2__` later,
  each with its **own** passphrase.
- The phone includes a `for[]` entry for **every** admin id in config. Adding an admin =
  append to the config list + push the new `admin_pub` to devices; new batches start
  including it. No migration.
- A newly-added admin sees data **from when they were added forward** by default. If they
  need history, reuse SPEC_T13 §5 **re-encrypt-on-grant**: devices re-upload their current
  local 7-day window encrypted to the new admin key — the same mechanism guardians already
  use. (Server never re-encrypts anything.)
- Distinguishing "server operator" vs "in-program admin" later is just two entries in that
  list with two passphrases; either can be rotated or removed independently.

**Build now:** exactly one admin entry, but read the admin set from a config **list** and
loop the fanout over it, so the second admin is a config append.

---

## 3. Key & passphrase management (operational — document, don't hand-wave)

- **Passphrase rotation** (routine): decrypt `admin_priv` with the old passphrase, re-wrap
  under the new one (`new salt`, `new kek`, `new admin_priv_enc`). The **keypair is
  unchanged**, so no trail data needs re-encryption. Cheap, do it periodically.
- **Keypair rotation** (suspected compromise): generate a new admin keypair, push the new
  `admin_pub` to devices, devices re-encrypt their current 7-day window to it (§5 reuse);
  old ciphertext stays readable only with the old private key and ages out within the
  7-day retention window. Keep the old key just long enough to read pre-rotation data.
- **Passphrase — LOCKED 2026-09-04: single passphrase, held offline.** Ivan's decision:
  operate with **one** admin passphrase, kept outside the server (offline). NOTE the
  deployed reality: the admin key was already minted by `tools/trail-admin-keygen.js`, whose
  default writes **two** wraps (daily + recovery) over the *same* `admin_priv`. That key is
  in live config and has been used to decrypt (2026-09-04). **We leave it as-is** — the
  extra wrap is harmless (either passphrase unlocks the same key), and Ivan simply keeps one
  passphrase offline and doesn't maintain a separate recovery secret. No re-mint, no keygen
  change. If a *future* mint should be single-wrap, the keygen can be trimmed then — not a
  requirement now.
  Recovery story if the passphrase is ever lost: passphrase *rotation* re-wraps the
  **existing** `admin_priv` and needs a working passphrase, so it does **not** recover a
  *lost* one. A genuinely lost passphrase means minting a **new admin keypair**, pushing the
  new `admin_pub` to devices (keypair-rotation above); new batches encrypt to it
  immediately and the old (now-unreadable) ciphertext **ages out inside the 7-day retention
  window** — blast radius ≤7 days of admin-side history, guardians unaffected. Acceptable by
  design; no offline recovery machinery to build.
- **Never** log the passphrase, the derived `kek`, or a decrypted `admin_priv`. Zero the
  key material in memory on session end.

---

## 4. Reliability — "constant, ASAP, priority resend after outage" (guardrail #2)

This is the safety-critical half. Design principle: **persist first, upload after, advance
only on ack, never drop.** At-least-once delivery with idempotent dedup.

### 4.1 The queue is durable by construction
Points are written to Room **before** any network attempt (already true — trail is
device-only today). The "upload queue" is not an in-memory list; it is derived on demand
as `TrailDao.getSince(lastAckedSeq)`. Therefore process death, reboot, and the Motorola
G60s OEM app-killer lose **nothing** — on restart the collector just resumes from the
watermark. The scaffolding already exists: `TrailUploadState(guardianDevice PK,
lastAckedSeq)`, `getUploadWatermark` / `upsertUploadWatermark` / `getSince` / `getMaxSeq`,
and the `uploaded` column. The **admin recipient gets its own watermark row**
(`guardianDevice="__admin__"`) — and since the admin is always a recipient, *that
watermark is the authoritative "has it landed on the server" signal.*

### 4.2 Cadence (ASAP when online)
- Flush a batch when **any** of: 10 points accrued, 5 min elapsed, or a MOVING fix lands
  while online and the last flush was > `trailUploadMaxLatencySec` ago (config, default
  ~30–60 s) — this gives near-real-time upload while moving without hammering the radio.
- STILL heartbeat points batch lazily (low urgency). PANIC/SOS (SPEC_T13 Block J) flushes
  **per point**, bypassing batching entirely.

### 4.3 Priority resend on connectivity regain (the explicit guardrail #2 behavior)
- Register a `ConnectivityManager.NetworkCallback`; on `onAvailable` **immediately** drain
  the backlog `getSince(watermark)`, **oldest-first**, ahead of other non-critical traffic.
- During catch-up allow **larger** batches (e.g. up to ~200 points) so a long outage lands
  in a few round-trips rather than hundreds.
- The WebSocket reconnect handler also triggers a flush (belt and suspenders).
- **Backoff applies to failures, not to backlog:** on a failed attempt, exponential
  backoff with jitter (1→2→4→…→cap 60 s); reset to immediate on the first success. Points
  are removed from the "pending" set **only** after the server ack advances the watermark.

### 4.4 Idempotent dedup (at-least-once is safe)
- `seq` is a per-device monotonic counter; each batch carries `batchId` (uuid) +
  `seqLo/seqHi`. Server dedups on `batchId` (and a `(user,device,seq)` uniqueness guard);
  a re-sent batch is **ack'd, not re-stored**. Ack `{trail-batch-ack, batchId, seqHi}`
  advances `lastAckedSeq` for that recipient. Duplicates are therefore harmless, which is
  what makes aggressive resend-on-reconnect safe.

### 4.5 Proof it landed
Trail settings status card (SPEC_T13 §6.6) shows **last successful upload time** and
**backlog size** = points above the admin watermark. Server-side, the optional §4.4
`trail-stale` tripwire pushes a "trail went silent" alert when the newest stored point is
older than a threshold — the earliest possible signal that a search should start. Together
these let Ivan *see* that (e.g.) the Greece trip's points reached the server.

---

## 5. Suspect (`susp`) fixes carry through to the server — VERIFIED 2026-09-04

Locked decision #6: suspect fixes are **kept and uploaded with the `susp` flag intact**;
server/guardian/admin UI annotates or hides them, never drops them.

**VERIFIED (2026-09-04)** against a fresh field export `fshu_export_ivan_20260904_185459.json`
(2498 points, seq 1–2498, no gaps, ~Aug 29 → Sep 4). The `susp` field now round-trips
`trail_points.susp` → `TrailPointMapper` → point JSON → GDPR export in real data:

- **95 suspect points carry a `susp` reason: 70 `jump`, 25 `detour`.** All 95 sit on
  located fixes (none stranded on event-only rows).
- **Every flag is internally valid — 0 violate its own rule** (checked against decrypted
  coordinates this session): all 70 `jump` have implied speed ≥150 km/h AND acc >250 m
  (min 152; extremes 5,035 / 23,878 km/h at ~700 m are obvious teleports); all 25 `detour`
  satisfy `acc≥60` + both legs >250 m + span < min-leg, all `moving`, `STILL` excluded.
- Some suspects come in **consecutive runs** (e.g. seq 852–855 a 19–23 km there-and-back
  at acc 73 m; runs of 7 and 9 around seq 1919–1925 / 2016–2024) — sustained bad-GPS
  stretches, correctly flag-and-kept, not false positives.

Because the export carries `susp`, the Block I **upload batch will carry it too** (same
`TrailPointMapper` output). **This closes the verification gap that blocked Block I.**
The viewer already renders it: `tools/trail-viewer.html` draws suspects as red dots,
**excludes them from the connecting path line**, labels each by reason, and counts them —
so "handle the 95 in the viewer" is already done on the export/viewer side; the server/
admin/guardian view must apply the same non-null-`susp` treatment.

---

## 6. Transparency & audit (adapting SPEC_T13 §6.4 to admin reads)

- Every **guardian** fetch already logs to `trail_access_log` and pushes `trail-accessed`
  to the user (SPEC_T13 §6.4) — unchanged.
- Every **admin** decrypt session also writes `trail_access_log` rows (audit trail — an
  unlogged admin read would undercut the whole trust model of a safety app).
- **Admin reads do NOT notify the user — LOCKED 2026-09-04 (Ivan): `adminAccessNotifiesUser
  = false`, and no phone notification is raised for an admin read at all.** Rationale: this
  is a **safety** feature and the phone should stay silent — the user opted into the trail
  when they enabled it, and does not need a ping each time the admin looks. The **server-
  side `trail_access_log` audit row is still written** (silent, never reaches the phone) so
  operator accountability is preserved without surfacing anything to the user. Guardian
  fetches keep their existing SPEC_T13 §6.4 behavior — this decision scopes admin reads
  only.

---

## 7. Amendments this makes to SPEC_T13.md (must be recorded there before code lands)

`SPEC_T13.md` is the authority, so it must not silently contradict reality:

- **§1 decision 1** ("server stores ciphertext only" / pure guardian E2E) → **amended**:
  the admin is a mandatory additional recipient; the server can decrypt **only** within an
  authenticated, passphrase-unlocked admin session. Guardian E2E is unchanged.
- **§5 E2E scheme** ("server never reads positions") → **amended** with the admin-view
  path (§1.3 here) and the passphrase-locked `admin_priv` at rest.
- **§4.4** ("metadata-only; server never reads positions") → **amended**: retention/purge
  logic is unchanged (still frozen-clock, still never needs to read positions), but the
  blanket "never reads" claim is now scoped to "never at rest / never outside an admin
  session."
- **§2 / §4.1** wire format and tables: **no change** — admin rides the existing fanout.

The implementing session should apply these edits to `SPEC_T13.md` §1/§5/§4.4 as part of
Block F, and note them in the "Implementation notes" appendix.

---

## 8. Build order (SPEC_T13 block structure; server-persistence path first)

Ivan's priority — data reliably on the server — is Phase 2 + Block I. One block per Claude
Code prompt (SPEC_T13 §7); each ends build-green + commit + `PROJECT_MEMORY.md` update +
stop-and-report.

1. **Block F — server schema + config + install script + SPEC amendments.** §4.1 tables,
   §4.2 keys, the **admin config list** (§2), the passphrase-wrap fields (§1.1), and the
   §7 edits to SPEC_T13. Additive only (drift rule §1.8; apply identically to repo and
   `/opt/fshu5/server.js`).
2. **Block G — handlers + admin decrypt/view + audit.** `trail-batch` (accept guardian +
   admin recipients), `trail-fetch`, grant/accept/revoke, transparency push, the
   passphrase-unlock admin view (§1.3), `trail_access_log` for admin reads.
3. **Block H — purge + stale alert.** Frozen-clock retention (§4.4) — unchanged by the
   admin-readable decision.
4. **Block I — upload queue.** Batching + **priority resend on reconnect** (§4.3) +
   per-recipient watermarks (incl. admin) + offline durability + re-encrypt-on-grant.
   **This is the block that makes the trip data land.** `susp` carry-through **CONFIRMED
   2026-09-04 (§5)** — Block I inherits it free via `TrailPointMapper`; no extra work.
5. **Blocks J–L** — last-gasp/panic, guardian+admin viewer, polish — per SPEC_T13 §7.

---

## 9. Open policy items

- ~~**`adminAccessNotifiesUser`** default (§6)~~ — **RESOLVED 2026-09-04:** notify OFF, no
  phone notification for admin reads; silent audit log only (§6).
- ~~**Passphrase-loss mitigation** (§3: dual passphrase vs Shamir)~~ — **RESOLVED
  2026-09-04:** operate with a single offline passphrase; deployed key keeps its default
  daily+recovery wraps (harmless, left as-is); loss → new keypair (§3).
- **`svc_restart` / `sim_changed` events** seen Aug-21 and in the Aug-27 file — confirm
  these were manual test restarts vs a real OEM kill worth chasing before relying on
  background upload reliability. *(Still open — a reliability check, not a blocker for F/G.)*

---

## 9a. Trail batch envelope — EXACT definition (Block I client must match Block G server)

The trail reuses the app's DM primitive (`EcdhHelper`), but with an **explicit random IV**
per batch (like the group-message path) rather than the DM's messageId-derived nonce —
because a batch has no messageId. Both sides MUST agree on this:

Per recipient (a guardian username, or an admin id such as `__admin__`):
```
convKey = HKDF-SHA256(
            ikm  = X25519(senderPriv, recipientPubHex),      // recipientPubHex is HEX (raw 32B)
            salt = SHA256("<lo>:<hi>"),  lo/hi = sorted(senderUsername, recipientId),
            info = "fshu-next-1-1-v1",  len = 32)             // identical to deriveConversationKey
iv  = 12 random bytes
ct  = AES-256-GCM(convKey, iv, JSON.stringify(pointsArray))  // ciphertext || 16-byte tag
for[] entry = { g: recipientId, iv: base64(iv), ct: base64(ct) }
```
The admin's public key is distributed to the app as **hex** (`pub_hex` from the keygen),
the same representation `users.public_key` already uses. The server, holding the admin
private key (passphrase-unlocked) and the sender's `users.public_key`, derives the same
`convKey` (ECDH is symmetric) and decrypts. Verified in Node this session.

## 10. Git / environment reality (unchanged, from the kickoff)

Repo reached over the desktop bridge on a Windows mount where the session **cannot reliably
run git** (lock-file unlink fails) or Gradle/adb (Ivan's standing rule: builds stay in
Android Studio). Implementation sessions hand Ivan **scoped `git add` + commit commands**;
Ivan builds, runs tests, confirms, then pushes. Server changes obey the **drift rule**
(§1.8): additive, applied identically to repo and live, never a wholesale deploy.
