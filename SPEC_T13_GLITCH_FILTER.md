# SPEC — Trail fix quality filter (impossible-speed / teleport flagging)

**Status:** proposed (plan only — no code written yet)
**Date:** 2026-08-23
**Parent:** SPEC_T13.md (location trail, Phase 1)
**Depends on:** Block B/B.1 (TrailService sampling + dup guard), Block A (Room 25→26 trail schema)
**Decision taken:** flag-and-keep (not drop). Consistent with T13 locked decision 7 ("collect maximally"). Nothing is ever discarded; suspect fixes are marked so the viewer/export can hide or annotate them, and the mark is fully reversible.

---

## 1. Problem

The exported trail shows a recurring GPS "teleport" glitch on the Plovdiv→Karlovo corridor. Confirmed instances (from `fshu_export_ivan_*` analysis, 2026-08-23):

| When (UTC) | Jump | Implied speed |
|---|---|---|
| 2026-08-23 08:06→08:09 | 42.5476,24.8297 → 42.6308,24.8243 | ~184 km/h |
| 2026-08-23 08:09→08:12 | snap back to ~42.5475,24.8265 | ~181 km/h |
| 2026-07-30 06:54→06:57 | 42.6101,24.9543 → 42.6420,24.8010 | ~259 km/h |
| 2026-07-30 06:57→07:00 | snap back to 42.5267,24.8139 | ~255 km/h |

Every bad fix shares a signature: `net: cell`, `prov: fused`, **degraded accuracy 300–800 m** (vs ~20–100 m on good fixes), firing right after a stationary period in a weak-signal rural stretch (nearby cells at −111 to −124 dBm). Hypothesis: GPS/Wi-Fi unavailable there, the fused provider falls back to cell-tower triangulation during a tower handover → a wild position that snaps back on the next fix.

## 2. Goal

Detect physically-impossible fixes at write time and **flag** them (keep in DB, mark suspect) so that:

1. the trail viewer can grey out / hide them,
2. the GDPR export carries the flag for downstream analysis,
3. no data is lost and the rule can be re-tuned or removed without migration pain.

Non-goals: dropping fixes; changing the STILL/MOVING sampling machine; server-side filtering (Phase 2/3).

## 3. Design

### 3.1 Where the check runs

The single choke point is `TrailService.recordFix(location, providerOverride, triggerWifiScan)` (`TrailService.kt:449`). It already:

- returns early on `isDuplicateFix(...)`,
- holds `lastPersistedLocation` / `lastPersistedTs` (updated only on a real persist).

The flag is computed **after** the dup check, **before** `persist(point)`.

### 3.2 The double-flag trap (why "last good", not "last persisted")

A teleport is one bad fix sandwiched between two good ones: `A(good) → B(glitch) → C(good, snaps back)`. If speed is measured against the *last persisted* fix:

- `A→B` huge → flag B ✓
- `B→C` huge (snap-back) → **wrongly flags C too** ✗

Fix: keep a separate **last-good baseline** (`lastGoodLocation` / `lastGoodTs`) = the most recent fix that was *not* flagged. A flagged fix does **not** advance the baseline. Then:

- `A→B` huge → flag B; baseline stays at A
- `A→C` — A and C are both on the real route and dt spans B's interval → plausible → C is **clean** ✓

This is the same accuracy-gating philosophy already used in Block B.1.2/B.1.3 for STILL-entry/exit decisions.

### 3.3 Decision rule (recommended)

Flag a fix as suspect when it implies impossible movement **corroborated by poor accuracy** — the exact signature the data shows. Using both criteria together (rather than either alone) keeps a genuine fast-but-accurate highway fix from being flagged:

```
reason = null
speedKmh = haversine(lastGood, current) / (ts - lastGoodTs)   // km/h, only if baseline exists & dt > 0
poorAcc  = acc != null && acc > ACC_SUSPECT_M

if (baseline exists && speedKmh > SPEED_SUSPECT_KMH && poorAcc) reason = "jump"
else if (poorAcc)                                              reason = "acc"     // optional, see §3.5
```

- `reason == null` → clean fix, advances the last-good baseline.
- `reason != null` → point still persisted, `susp = reason`, baseline **not** advanced.

### 3.4 Thresholds (tunable constants in `companion object`)

| Const | Proposed | Rationale |
|---|---|---|
| `SPEED_SUSPECT_KMH` | **150** | All observed glitches were 181–264 km/h; 150 catches the 184 km/h case while clearing any Bulgarian road speed (motorway limit 140). Fast-but-accurate highway fixes are protected by the AND-accuracy term. |
| `ACC_SUSPECT_M` | **250** | Good fixes here are ≤100 m; every glitch was 300–800 m. Matches your floated "≥250 m" cutoff. |

Open threshold question for Ivan: the 08:06→08:09 jump is **184 km/h**, so a speed-only threshold of 200 would miss it — that's why 150 (or ≤180) is recommended, and why accuracy corroboration matters.

### 3.5 The `susp` field shape

Recommended: **nullable String reason code** on both the wire model and the entity — strictly more informative than a bare boolean for debugging, and Gson omits it when null exactly like a boolean would.

- `null` = clean (default; omitted from export)
- `"jump"` = impossible speed + poor accuracy
- `"acc"` = poor accuracy alone (only if the standalone accuracy flag in §3.3 is kept)

Simpler alternative if preferred: `susp: Boolean? = null` (true only when flagged). Same migration shape (`TEXT` → `INTEGER`).

Note on the standalone `"acc"` flag: the STILL network heartbeat legitimately produces coarse (>250 m) fixes, so an accuracy-only flag will mark *many* honest points. Recommendation: ship §3.3's `"jump"` rule first (high precision, matches the exact glitch signature) and treat the standalone `"acc"` branch as opt-in / off by default.

## 4. Code changes (all in `app/`)

1. **`trail/TrailModels.kt`** — add `val susp: String? = null` to `TrailPointData` (among the fix fields, e.g. after `net`). Codec (`TrailPointCodec`) needs no change — Gson maps by name and omits null.

2. **`data/local/entities/TrailPoint.kt`** — add `val susp: String? = null` column.

3. **`trail/TrailPointMapper.kt`** — map `susp` in both `toEntity()` and `toData()`.

4. **New `trail/TrailFixQuality.kt`** — a pure, Android-free object holding the decision so it's unit-testable without a `Location`:
   ```kotlin
   object TrailFixQuality {
       const val SPEED_SUSPECT_KMH = 150.0
       const val ACC_SUSPECT_M = 250.0
       /** @return reason code, or null if the fix looks clean. */
       fun classify(
           prevLat: Double?, prevLon: Double?, prevTs: Long?,
           lat: Double, lon: Double, ts: Long, acc: Double?
       ): String?
   }
   ```
   Contains a small haversine helper. No dependency on `android.location.Location`.

5. **`service/TrailService.kt`**:
   - add `@Volatile private var lastGoodLocation: Location?` + `lastGoodTs: Long` fields,
   - in `recordFix()`, after the dup check, call `TrailFixQuality.classify(...)` with the last-good baseline and the new fix; set `susp = reason` on the `TrailPointData`,
   - update the baseline only when `reason == null`,
   - reset both baseline fields in `enterMoving()`/`enterStill()`? **No** — the baseline should survive state transitions (a glitch can straddle a STILL→MOVING exit). Leave it purely fix-driven.

6. **`data/local/AppDatabase.kt`**:
   - bump `version = 26` → `27`,
   - add `MIGRATION_26_27`:
     ```kotlin
     private val MIGRATION_26_27 = object : Migration(26, 27) {
         override fun migrate(db: SupportSQLiteDatabase) {
             db.execSQL("ALTER TABLE trail_points ADD COLUMN susp TEXT")
         }
     }
     ```
   - register it in `addMigrations(...)`.

7. **Export** (`ui/SettingsFragment.kt`) — no change; `susp` flows through `TrailPointCodec.toJson(toData())` automatically.

8. **Viewer** (optional, `ui/trail/TrailViewerActivity.kt` ~line 190, `TrailPointDetailSheet.kt`, `TrailLabels.kt`) — grey/annotate points where `susp != null`, add a "suspect fix" info bit. Can ship after the collector change.

## 5. Test plan

- **`TrailPointMapperTest`** — extend the round-trip cases to set `susp = "jump"` and assert it survives `toEntity()`/`toData()`; assert default is `null`.
- **New `TrailFixQualityTest`** (pure JVM unit test) — table-driven, using the real exported coordinates:
  - `A(42.5476,24.8297,08:06) → B(42.6308,24.8243,08:09, acc 700)` ⇒ `"jump"`.
  - `A → C(snap-back, 08:12, acc 300)` with baseline still at A ⇒ `null` (proves no double-flag).
  - A genuine ~130 km/h highway fix with `acc = 15` ⇒ `null` (accuracy term protects it).
  - `prevTs == null` (first-ever fix) ⇒ `null`.
  - `dt == 0` guard ⇒ `null`, no divide-by-zero.
- **Room migration test** — a `MigrationTestHelper` case 26→27 asserting the `susp` column exists and existing rows read back as `null`. (`exportSchema = true` is already set, so schema JSON is available.)
- **Manual on-device** — replay is impractical; instead verify via a fresh export that no *good* fixes carry `susp`, and (best-effort) that the next observed glitch is flagged.

## 6. Risks / notes

- **Threshold false-positives** — flag-not-drop makes these low-stakes and reversible. Constants are centralized in `TrailFixQuality` for easy tuning.
- **Baseline after long gaps** — after a real STILL period the last-good fix may be old; a large dt makes the implied speed *small*, so the rule naturally won't fire on legitimate resumed movement. Good.
- **Mock locations** — already captured via `mock`; orthogonal to this filter, left as-is.
- **No server/UI contract change** — `susp` is additive and null-omitted, so existing consumers (and the guardian upload path in Phase 2/3) are unaffected until they choose to read it.

## 7. Rollout

1. Land steps 1–7 (collector + schema + tests) behind no flag — it only adds a field.
2. Verify on next export that clean fixes stay unflagged.
3. Add the viewer treatment (step 8) once the flag is trusted.

---

## §detour — coarse there-and-back rule (added 2026-08-29)

**Motivation.** A dot sitting on Asenovgradsko shose (real decrypted Aug-29 trail, seq 460)
was a coarse network-blended fix that jumped ~3.3 km sideways and snapped back. The `"jump"`
rule missed it: over a 3-minute sampling gap the implied speed was only 66–104 km/h, well
under `SPEED_SUSPECT_KMH = 150`. Provider name can't catch it either — every fix on this
device reports `prov = "fused"` (Android's blended provider); there is no literal `"gps"`.
The one field that separates the bad dot (acc 98 m) from its neighbours (24 m, 30 m) is
**accuracy**, and the one shape that separates a glitch from a merely-coarse fix is the
**there-and-back geometry**. A pure accuracy gate over-flags badly (moving acc>60 m = 64/518
points, most of them fine), so accuracy is paired with geometry — exactly as `"jump"` pairs
speed with accuracy.

**Rule (`TrailFixQuality.classifyDetour`).** Flag `"detour"` when a fix is `mot == "moving"`
AND `acc ≥ DETOUR_ACC_MIN_M (60 m)` AND both legs to its immediate neighbours exceed
`DETOUR_JUMP_M (250 m)` AND the two neighbours are closer to each other than either is to the
fix (`dSpan < min(dIn, dOut)`). STILL fixes are excluded — a coarse stationary heartbeat
legitimately jitters and must never be flagged (same reason `FLAG_POOR_ACC` is off).

**Non-causal → one-fix look-behind, persist-first.** Unlike `"jump"` (causal, decided against
the last good fix as the point streams in), `"detour"` needs the fix AFTER the suspect one.
The collector keeps its safety-critical **persist-first durability** — every point is written
to Room immediately, never held in volatile memory — and once a fix's successor arrives,
`TrailService.recordFix` retroactively flags the previous point via `TrailDao.updateSusp`.
Only points the online path left clean are eligible, so **`"jump"` always wins** over
`"detour"` on a point that is both. Precedence and the retroactive path are additive: the
well-tested `classify()` online path and the `lastGood` baseline are untouched.

**Validation (decrypted Aug-29 trail, 518 fixes).** The integrated algorithm applies
`"detour"` to exactly seq **460, 475, 504, 526** (the four coarse sideways spikes) and leaves
the four `"jump"` flags (474, 478, 508, 512) intact — 8 suspect of 518, zero tight-fix
(acc ≤ 30 m) false positives, and seq 462 (500 m but on the route line) correctly kept clean.
seq 478 is both a geometric detour and already `"jump"`; the online-susp skip keeps it `"jump"`.
Unit tests in `TrailFixQualityTest.kt` seed these real coordinate triples.

**No schema change, no server change.** `susp` is already a nullable TEXT column (v27); the
new reason is just another string value. The server stores only ciphertext and never reads
`susp`; its `/admin/trail` map and `tools/trail-viewer.html` both colour any non-null `susp`
red and show the reason text, so `"detour"` renders with only a human-label addition
(`TrailLabels.susp`). Retroactive-flag caveat: if a point is uploaded in the brief window
before its successor lands, the admin/guardian copy carries no `detour` flag on that point
(positions are intact regardless); acceptable for flag-and-keep, and a display-time geometry
pass in the viewers could close it later if wanted.

### Files changed (§detour, all under C:\Users\spirt\fshu-next)
- `app/src/main/java/com/fshu/next/trail/TrailFixQuality.kt` — `classifyDetour` + `DETOUR_ACC_MIN_M`/`DETOUR_JUMP_M`.
- `app/src/main/java/com/fshu/next/data/local/dao/TrailDao.kt` — `updateSusp(seq, susp)`.
- `app/src/main/java/com/fshu/next/service/TrailService.kt` — `PendingFix` look-behind in `recordFix`.
- `app/src/test/java/com/fshu/next/trail/TrailFixQualityTest.kt` — 9 detour cases on real coords.
- `tools/trail-viewer.html` — `"detour"` human label.

No Room migration (no schema change), no `server.js` change.
