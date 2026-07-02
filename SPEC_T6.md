# Spec — T6: Build flavors (personal vs distribution server URL) | UPDATE

> For: Claude Code. Drafted by planning Claude, approved by Ivan.
> Mode: maintenance, mobile only.
> **Build type: UPDATE** — config only. No protocol change, no DB migration, no new permissions.
> Testing deferred to real G60 devices; implement now.
> **After implementing: update `PROJECT_MEMORY.md` (move T6 To Do → Done, add a
> Changelog row with files + commit hash) and commit it alongside the code.**

---

## Goal

Produce two build flavors from the same codebase:

- **`personal`** — login server-URL field is **pre-filled** with `wss://shumkov.eu/fshu5/`.
- **`distribution`** — login server-URL field stays **blank** with its **existing
  placeholder** (current behavior, unchanged).

The personal server URL must **not** exist in the distribution APK. That is the whole
point of using flavors rather than a runtime flag: the constant is compiled out of the
build that other people receive.

---

## Decisions (final — do not deviate)

- One flavor **dimension**: `serverType`, with flavors `personal` and `distribution`.
- Both flavors keep `applicationId "com.fshu.next"` (do NOT add a package suffix —
  single app, build one or the other).
- App **label** stays `"4shu β"` for both (do NOT change `android:label` per flavor).
- Add `versionNameSuffix`: `-personal` / `-dist`, so the running build is identifiable
  in the About screen / APK filename.
- `distribution` keeps the current blank-field + existing placeholder behavior exactly
  — do not touch the placeholder, do not add new hint text.

---

## Steps

### 1. Enable BuildConfig (verify first)
Reading `BuildConfig.DEFAULT_SERVER_URL` requires the BuildConfig feature to be on.
Newer AGP does NOT enable it by default. Check `app/build.gradle`:

```groovy
android {
    buildFeatures {
        buildConfig true   // add this if missing
    }
}
```

If `buildFeatures` already exists, add `buildConfig true` inside it; don't duplicate the block.

### 2. Declare flavors in `app/build.gradle`

```groovy
android {
    // ...existing config...

    flavorDimensions "serverType"
    productFlavors {
        personal {
            dimension "serverType"
            buildConfigField "String", "DEFAULT_SERVER_URL", "\"wss://shumkov.eu/fshu5/\""
            versionNameSuffix "-personal"
        }
        distribution {
            dimension "serverType"
            buildConfigField "String", "DEFAULT_SERVER_URL", "\"\""
            versionNameSuffix "-dist"
        }
    }
}
```

Note the escaped quotes — `buildConfigField` String values must include literal quotes
inside the Groovy string, or the generated constant won't compile.

### 3. Use the constant in LoginActivity
Find where the login/first-launch screen sets up the server-URL field (per CLAUDE.md:
`LoginActivity` collects username + server URL on first launch, stored via `Prefs`).

Apply this logic, **only when there is no already-saved server URL** (don't overwrite a
URL the user previously typed/saved):

- If `BuildConfig.DEFAULT_SERVER_URL` is non-empty → set the field's text to it
  (personal flavor: field comes up pre-filled).
- If it is empty → leave the field and its existing placeholder exactly as they are
  (distribution flavor: current behavior).

Pseudostructure:

```kotlin
val savedUrl = Prefs.getServerUrl()           // however Prefs exposes it
if (savedUrl.isNullOrEmpty() && BuildConfig.DEFAULT_SERVER_URL.isNotEmpty()) {
    serverUrlField.setText(BuildConfig.DEFAULT_SERVER_URL)
}
// else: leave field as-is (placeholder shows on distribution)
```

Do not change the distribution path's behavior in any other way.

### 4. Sanity checks
- Confirm `BuildConfig` import resolves in `LoginActivity` (`com.fshu.next.BuildConfig`).
- Confirm no other code hard-codes `wss://shumkov.eu/fshu5/` as a fallback that would
  reintroduce it into the distribution build. If such a fallback exists, note it in the
  task summary and flag it to Ivan rather than silently changing it.

---

## What Ivan does after (build-time note — for the summary, not for Claude Code to run)

Android Studio's build-variant dropdown will now list combinations:
`personalDebug`, `personalRelease`, `distributionDebug`, `distributionRelease`.
A one-time Gradle sync is needed after the flavor change. For his own phones Ivan picks
a `personal*` variant; for handing the app to others he picks a `distribution*` variant.
**Claude Code does NOT run Gradle or adb.**

---

## Done criteria
- [ ] `buildConfig true` present (added only if it was missing).
- [ ] `serverType` dimension + `personal` / `distribution` flavors declared with the
      correct `DEFAULT_SERVER_URL` values and version suffixes.
- [ ] `LoginActivity` pre-fills from `BuildConfig.DEFAULT_SERVER_URL` only when no saved
      URL exists; distribution behavior unchanged.
- [ ] No stray hard-coded personal URL remains as a fallback (or it's flagged in the summary).
- [ ] `PROJECT_MEMORY.md`: T6 moved To Do → Done; Changelog row added (date, files, commit hash).
- [ ] Committed to git (code + PROJECT_MEMORY.md together). No Gradle/adb run by Claude Code.
