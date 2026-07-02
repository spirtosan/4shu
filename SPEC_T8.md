# SPEC — T8: Chat/Group Media Gallery (images only, v1)

> For Claude Code. Build per this spec. Rules unchanged: SSH LAN only
> (`ssh root@192.168.212.105`, never the public IP); mobile only; SQLite only;
> no Tink/Google **crypto** libs (PhotoView is a UI lib, not crypto — allowed);
> Ivan builds all APKs (no Gradle/adb). Commit T8 on its own; update
> `PROJECT_MEMORY.md` alongside the change and move T8 To Do → Done.

## Decisions (final — do not revisit)

- **v1 scope: images only.** Video is sent as files today and is **out of scope**.
- Gallery covers **both DM and group** chats ("chat/channel").
- **Single MEDIA view** — no Links/Files tabs in v1.
- **In-app viewer: ADD `com.github.chrisbanes:PhotoView` + `ViewPager2`** (Decision 1 = A).
  Pinch-zoom + swipe between images. Do **not** route taps to the system `ACTION_VIEW`.
- **Per-item actions: Save to device + Share only.** "Show in conversation"
  (jump-to-message) is **DEFERRED** — do not build the adapter-scan helper or wire it.
- **No server change. No schema change. No DB migration.** Read-only over existing
  Room data (`localUri`, `mimeType`). This is purely client-side.

## Confirmed facts from your investigation (basis for this spec)

- Images = `Message.type == "file"` with `Message.mimeType LIKE 'image/%'`.
- `Message.localUri` holds a stable `content://` URI — local, not fetch-on-demand.
- Group messages carry `groupId`; DM messages use from/to columns. Gallery query
  must work for both.
- No in-app viewer exists today (images open via `ACTION_VIEW`). v1 adds one.
- No `scrollToMessageId` helper exists. (Irrelevant in v1 since Show-in-conversation
  is deferred.)

---

## 1. Data layer

Add a `MessageDao` query returning image messages for a conversation, newest-first.

- **DM:** match the existing from/to column pair for the peer (use whatever the
  current DM queries use — do not invent new column semantics).
- **Group:** match `groupId == ?`.
- Filter: `type = 'file' AND mimeType LIKE 'image/%'`.
- Exclude `type = 'deleted'` rows (and any soft-deleted/edited-away state the schema
  already uses — match existing message-list filtering so the gallery doesn't show
  messages the chat itself hides).
- Order: `timestamp DESC`.
- Return enough per row to render + open the viewer: at minimum `id`, `localUri`,
  `timestamp`. Prefer returning the `Message` rows / a lightweight projection.
- **Null/missing `localUri`:** skip rows whose `localUri` is null or whose file no
  longer resolves — don't render broken tiles. (Resolve-check can be lazy at bind
  time; at minimum filter null `localUri` in the query.)

Expose via the existing repository/VM pattern (Flow or suspend list — match how other
message queries are surfaced). Paging: a simple `DESC` list is acceptable for v1; only
add paging if the existing message layer already pages and it's trivial to reuse.

## 2. Gallery screen (grid)

New screen — `MediaGalleryActivity` (or Fragment, match the project's pattern).

- Launched with a conversation key: peer id for DM, `groupId` for group. Title:
  "Media" (string `media_gallery_title`).
- **3-column square grid** (RecyclerView + GridLayoutManager, 3 cols), square
  center-cropped thumbnails. Load thumbnails from `localUri`. Use the image loader
  the app already uses for chat image bubbles — do **not** add a second image-loading
  library beyond PhotoView (PhotoView is for the viewer, not the grid).
- **Date headers / grouping:** group items by date with text headers — `Today`,
  `Yesterday`, else month-year (e.g. `June 2026`). Localize via existing date
  helpers if present; otherwise add minimal strings (see §5). Implement as either a
  sticky-header decoration or a 2-type adapter (header rows span all 3 columns) —
  your choice; the 2-type adapter with `SpanSizeLookup` is simplest.
- **Empty state:** "No media yet" (`media_gallery_empty`) centered when the query
  returns nothing.
- Newest-first (top = most recent), matching the query order.

## 3. In-app viewer (Decision 1 = A)

New `MediaViewerActivity`:

- **`ViewPager2`** over the same ordered image list, opened at the tapped item's
  index.
- Each page = **`PhotoView`** (pinch-zoom, double-tap zoom, pan). Load full-res from
  `localUri`.
- Pure black background, immersive (hide system bars / edge-to-edge), tap toggles a
  top bar with a back button + overflow.
- **Swipe** left/right moves between images (ViewPager2 default).
- **Overflow / action bar — exactly two actions:**
  - **Save to device** (`action_save_to_device`) — copy the image into the device's
    public gallery (MediaStore Pictures). Use the app's existing save-to-Downloads/
    MediaStore pattern if one exists (GDPR export already writes to Downloads — reuse
    that mechanism's MediaStore approach). Toast on success/failure.
  - **Share** (`action_share`) — native Android share sheet (`ACTION_SEND`) with the
    image. Use a `FileProvider`/content URI; if `localUri` is already a shareable
    `content://`, pass it through with the right MIME and `FLAG_GRANT_READ_URI_PERMISSION`.
- **No** Forward, **no** Delete, **no** Show-in-conversation, **no** multi-select in v1.

## 4. Entry points

- **Chat overflow menu** (DM and group) → "Media" item (`menu_media`) → opens the
  gallery for that conversation.
- **Group-info dialog** → add a "Media" link (same dialog you just edited for T10's
  rename link; place it consistently with the other links). Opens the gallery for the
  group.
- (No chat-title tap / swipe-left gestures in v1 — out of scope.)

## 5. Strings (EN + BG; no RU — `values-ru` absent)

Add to `values/strings.xml` and `values-bg/strings.xml`:

- `media_gallery_title` — "Media" / "Медия"
- `media_gallery_empty` — "No media yet" / "Все още няма медия"
- `menu_media` — "Media" / "Медия"
- `action_save_to_device` — "Save to device" / "Запази в устройството"
- `action_share` — "Share" / "Сподели"
- `date_today` — "Today" / "Днес"  *(reuse existing if already present — check first)*
- `date_yesterday` — "Yesterday" / "Вчера"  *(reuse existing if already present)*
- Toasts: `toast_image_saved` / `toast_image_save_failed` — "Saved to gallery" /
  "Запазено в галерията", "Couldn't save image" / "Неуспешно запазване"

Match the existing string ID naming/style in the file. Reuse any existing date or
save/share strings rather than duplicating.

## 6. Dependency

Add to `app/build.gradle` dependencies:

```groovy
implementation 'com.github.chrisbanes:PhotoView:2.3.0'
```

PhotoView is hosted on JitPack — if the project's `settings.gradle` /
`build.gradle` repositories don't already include `maven { url 'https://jitpack.io' }`,
add it. **Flag this in your report** so Ivan knows a repo line changed (it affects the
build). No other new dependencies.

## 7. Out of scope (explicit — for the v1 follow-up backlog)

Links tab · Files tab · video (no video message type yet) · duration badges ·
Forward · Delete / Delete-for-everyone · multi-select / batch toolbar ·
Show-in-conversation (jump-to-message) · autoplay.

---

## Report back after building

1. Whether a `maven { url 'https://jitpack.io' }` repo line had to be added (build-affecting).
2. Whether you reused an existing MediaStore save path (GDPR export) or wrote a new one.
3. Whether existing `date_today` / `date_yesterday` strings were reused or added.
4. Confirm: no schema change, no DB migration, no server change, no protocol change.
5. Files touched + commit hash; `PROJECT_MEMORY.md` updated (T8 → Done, changelog row).

Then stop — Ivan builds `personalDebug` and runs the G60 pass.
