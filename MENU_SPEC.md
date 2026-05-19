# 4shu — Final Menu & Navigation Structure
**Implementation Specification — Post Gemini Review**
Last updated after: Ivan notes, Gemini architecture review, Gemini presence review, DB schema verification.

---

## STATUS LEGEND

- ✅ Implemented and working
- ⚠️ Implemented but broken / incomplete
- 🔲 Placeholder — add menu item now, implement later
- ❌ Missing entirely — needs to be built
- 🗑️ Remove from codebase

---

## DECISIONS LOG (Summary)

| Decision | Outcome |
|----------|---------|
| Trust level | **Remove completely** from DB and UI. Per-contact allow_emergency_call / allow_emergency_location replace it with more precision |
| Mutes table PK | **Change to (owner, target, target_type)** + add mute_until INTEGER column for timed mutes |
| Polls | **New tables** — do not reuse lists. polls + poll_options + poll_votes |
| Presence | **Implement** — in-memory on client (StateFlow), server broadcasts on connect/disconnect. Privacy enforced server-side |
| Presence privacy | **Mutual rule** — if you hide your status, you also cannot see others |
| Drafts | **Implement** — SharedPreferences keyed by chat ID, no Room migration needed |
| Chat list sort | **Fix bug** — mix DMs and groups by last message timestamp |
| Contact Requests | **Move to Contacts tab** — sticky header at top when requests pending |
| Admin panel | **Keep in Settings** — visible only when users.admin = 1 |
| Bouncy Castle | **No action** — uses lightweight API directly, no JCE conflict |
| Notification channels | **4 channels**: Emergency, Direct Messages, Groups, System |
| Share intent | **Add to manifest** — allow sharing from Gallery/Files to 4shu |
| auto_location UI | **Remove UI** — table stays in server DB |
| Read Receipts | **One location only** — Privacy Settings screen, remove from Settings tab |

---

## ROOM DB MIGRATION PLAN

| Version | Changes |
|---------|---------|
| 19 | Current |
| 20 | Fix mutes: drop and recreate with PK=(owner,target,target_type) + add mute_until INTEGER |
| 21 | Remove trust_level from contacts entity |

**Server DB changes required:**
- `ALTER TABLE mutes` — rebuild with new PK (or drop/recreate)
- `ALTER TABLE users ADD COLUMN hide_presence INTEGER DEFAULT 0`
- `ALTER TABLE contacts DROP COLUMN trust_level` (SQLite: requires table rebuild)
- `ALTER TABLE users DROP COLUMN trust_level` (SQLite: requires table rebuild)

---

## BOTTOM NAVIGATION BAR

Three tabs: **CHATS | CONTACTS | SETTINGS**

Back button behavior: Back from any tab returns to CHATS, then exits app. No circular loops.
BottomNav visibility: Hidden inside DM/Group chat views to maximize screen space.

---

## 1. CHATS TAB

### 1.1 Main Chats Screen

| Item | Status | Notes |
|------|--------|-------|
| Unified search bar (top) | ⚠️ | Bug: wrong display string shown for group results. Fix before rework |
| My avatar (top right of search bar) | ✅ | Tap → My Profile screen |
| Favorites section | ✅ | Pinned contacts/groups, reorderable via drag handle |
| Chat + group list (mixed, by last message timestamp) | ⚠️ | Bug: groups always sort to top. Fix before rework |
| Online presence dot on avatars | ❌ | In-memory PresenceRepository. Green dot when contact connected |
| Unread message badge per item | ✅ | |
| Last message preview per item | ✅ | |
| Timestamp per item | ✅ | |
| Long press chat item → multi-select | 🔲 | Placeholder. Low priority |
| Per-item 3-dot menu | ⚠️ | See 1.2 |

### 1.2 Per-Chat 3-Dot Menu (Chat List)

| Item | Status | Notes |
|------|--------|-------|
| Mute chat | ❌ | Server DB + Room: mutes table (owner, target, target_type, mute_until). Show submenu: 1h / 8h / 24h / Until I turn it on |
| Pin / Unpin | ✅ | Adds/removes from favorites |
| Mark as read | ❌ | Clears unread badge locally |
| Delete chat (local only) | ❌ | Removes messages from Room only. Confirmation dialog required |

---

## 2. DM CHAT SCREEN

### 2.1 Top Bar

| Item | Status | Notes |
|------|--------|-------|
| Contact avatar | ✅ | Tap → User Profile. Show online dot overlay |
| Contact name | ✅ | Tap → User Profile. Show "Online" or "Last seen [relative time]" as subtitle |
| Voice call button (tap) | ✅ | Voice call |
| Voice call button (long press) | ✅ | Emergency Menu |
| Video call button | ✅ | Video call |
| 3-dot menu | ✅ | See 2.3 |

### 2.2 Emergency Long Press Menu (on call button)

| Item | Status | Notes |
|------|--------|-------|
| Priority Call | ✅ | Bypasses DND |
| SOS Message | ✅ | Sends predefined SOS text |
| Request Location | ✅ | Sends location request |

### 2.3 DM Chat 3-Dot Menu

| Item | Status | Notes |
|------|--------|-------|
| Search in conversation | 🔲 | Filter messages by keyword in current DM |
| View Media / Files / Links | 🔲 | Filter messages by type=file/image/link |
| New Todo List | ✅ | Creates shared todo in DM |
| Change background | ✅ | |
| Export conversation | ✅ | JSON export to Downloads |
| 🗑️ Share my location | 🗑️ | Remove |
| 🗑️ Request location | 🗑️ | Remove |
| 🗑️ Auto share location switch | 🗑️ | Remove |
| 🗑️ Emergency Call | 🗑️ | Remove — replaced by long press call button |

### 2.4 Message Input Bar

| Item | Status | Notes |
|------|--------|-------|
| Attachment button (+) | ✅ | Opens attachment picker |
| Text input field | ✅ | Restored on return if draft saved |
| Mic / Send button (combined) | ✅ | Mic when empty, Send when has text |
| Draft save/restore | ❌ | SharedPreferences keyed by chat ID. Save on leave, restore on enter, clear on send |
| Attachment picker: Gallery | ✅ | Use Intent.ACTION_GET_CONTENT for Android 12+ scoped storage |
| Attachment picker: Camera | ✅ | |
| Attachment picker: File | ✅ | |
| Attachment picker: Location | ✅ | Sends current location as message |
| Attachment picker: Contact | ✅ | Shares a contact card |

### 2.5 Message Long Press Menu

| Item | Status | Notes |
|------|--------|-------|
| React (emoji picker) | ✅ | |
| Reply | ✅ | |
| Copy | ✅ | |
| Edit (own messages only) | ✅ | |
| Delete for me | ✅ | |
| Delete for all (own messages only) | ✅ | |
| Forward | 🔲 | Placeholder. Low priority |

### 2.6 Message Delivery Status (per message)

| Status | Indicator | Notes |
|--------|-----------|-------|
| SENDING | Clock icon | ✅ |
| SENT | Single tick | ✅ |
| DELIVERED | Double tick | ✅ |
| READ | Colored double tick | ✅ |

---

## 3. GROUP CHAT SCREEN

### 3.1 Top Bar

| Item | Status | Notes |
|------|--------|-------|
| Group name | ✅ | |
| Member count | ✅ | |
| Group avatar | ✅ | |
| Tap name/avatar → Group Info | ✅ | |
| 3-dot menu | ✅ | See 3.2 |
| No call buttons | ✅ | Intentional. Group calls not planned |

### 3.2 Group Chat 3-Dot Menu

| Item | Status | Notes |
|------|--------|-------|
| Group Info | ✅ | Opens Group Info screen |
| Search in group | 🔲 | Placeholder. Same implementation as DM search |
| Mute group | ❌ | Same mute flow as chat mute. target_type='group' |
| Change Background | ✅ | |

### 3.3 Group Message Input Bar

| Item | Status | Notes |
|------|--------|-------|
| Attachment button (+) | ⚠️ | Broken in groups. **Must fix before UI rework** |
| Text input | ✅ | |
| Draft save/restore | ❌ | Same as DM, keyed by group_id |
| Mic / Send button | ✅ | Voice messages in groups: deferred |
| Attachment picker items | ⚠️ | Same items as DM but broken in group context |

### 3.4 Group Message Long Press Menu

| Item | Status | Notes |
|------|--------|-------|
| React (emoji picker) | ✅ | |
| Reply | ✅ | |
| Copy | ✅ | |
| Delete for all (admin/owner or own message) | ✅ | |

---

## 4. GROUP INFO SCREEN

| Item | Status | Notes |
|------|--------|-------|
| Group avatar (tap to change — admin/owner only) | ✅ | |
| Group name (editable — admin/owner only) | ✅ | |
| Group type badge (family/group) | ✅ | DB: groups.type |
| Member list | ✅ | Avatar, name, role badge (owner/admin/member) |
| Member tap → User Profile | ✅ | |
| Member long press → Remove (admin/owner) | ✅ | |
| Member long press → Promote to admin (owner only) | ❌ | DB supports it. UI missing |
| Member long press → Demote to member (owner only) | ❌ | DB supports it. UI missing |
| Add Member button (admin/owner) | ⚠️ | Opens contact picker. Non-contacts cannot be added — invite flow needed |
| Invite to group via link | ❌ | Generate group invite link. Non-contact joins via link |
| Create Poll | 🔲 | Placeholder. New tables needed: polls, poll_options, poll_votes |
| Group Privacy (open/closed/secret) | 🔲 | Placeholder. New column needed in groups table |
| Leave Group | ✅ | |
| Delete Group (owner only) | ✅ | |

---

## 5. USER PROFILE SCREEN (viewing a contact)

| Item | Status | Notes |
|------|--------|-------|
| Avatar | ✅ | Respects show_avatar privacy setting |
| Online presence dot | ❌ | From PresenceRepository |
| Display name | ✅ | |
| Nickname (my custom label for this contact) | ✅ | DB: contact_nicknames |
| Username | ✅ | |
| Bio | ✅ | |
| Last seen | ❌ | Relative time: "Online" / "Today at 14:32" / "Yesterday" / "3 days ago". Hidden if contact has hide_presence=1 |
| Allow Emergency Call (switch) | ✅ | Per-contact. DB: contacts.allow_emergency_call |
| Allow Emergency Location (switch) | ✅ | Per-contact. DB: contacts.allow_emergency_location |
| Set Nickname (inline edit or button) | ✅ | |
| Mute this contact (switch) | ✅ | DB: mutes, target_type='contact' |
| 🗑️ Trust Level selector | 🗑️ | Remove completely. Replaced by explicit per-contact switches |
| Remove Contact | ✅ | |
| Block User | ✅ | DB: blocks table |

---

## 6. MY PROFILE SCREEN

Accessible from: Settings tab header tap, Chats search bar avatar tap.

| Item | Status | Notes |
|------|--------|-------|
| Profile picture (tap to change / remove) | ✅ | |
| Display name (editable) | ✅ | DB: users.nickname |
| Bio / About (editable) | ✅ | DB: users.bio |
| Email (editable, optional) | ✅ | DB: users.email |
| Phone (editable, optional) | ✅ | DB: users.phone, E.164 |
| Save Profile button | ✅ | |
| Privacy Settings link | ✅ | Opens Privacy Settings screen |
| Account Recovery section | ✅ | |
| Secret question status ("Set" / "Not set") | ✅ | |
| Set / Change Secret Question button | ✅ | Dialog: question text + answer |

---

## 7. PRIVACY SETTINGS SCREEN

Opened from My Profile. Single location — not duplicated in Settings tab.

| Item | Status | Notes |
|------|--------|-------|
| Discoverable (switch) | ✅ | DB: users.discoverable |
| Show avatar to non-contacts (switch) | ✅ | DB: users.show_avatar |
| Show nickname to non-contacts (switch) | ✅ | DB: users.show_nickname |
| Email searchable (switch) | ✅ | DB: users.email_searchable |
| Phone searchable (switch) | ✅ | DB: users.phone_searchable |
| Read Receipts (switch) | ✅ | Moved here from Settings tab |
| Hide presence / last seen (switch) | ❌ | New. DB: users.hide_presence. Mutual rule: hiding your status also hides others' from you. Enforced server-side |
| Save button | ✅ | |

---

## 8. CONTACTS TAB

### 8.1 Main Contacts Screen

| Item | Status | Notes |
|------|--------|-------|
| Search bar (contacts only) | ✅ | |
| Contact Requests sticky header | ❌ | Shows "N New Requests" when pending requests exist. Tap → requests list. Currently buried in Settings |
| Accepted contact list | ✅ | |
| Online presence dot per contact | ❌ | From PresenceRepository |
| Per-contact star (pin to favorites) | ✅ | |
| Per-contact 3-dot menu | ⚠️ | See 8.2 |
| FAB: Find People | ✅ | Opens discovery screen |

### 8.2 Per-Contact 3-Dot Menu (Contacts Tab)

| Item | Status | Notes |
|------|--------|-------|
| Send Message | ❌ | Opens DM chat |
| View Profile | ❌ | Opens User Profile screen |
| Mute | ✅ | Per-contact mute |
| Block | ✅ | |
| Remove Contact | ✅ | |

### 8.3 Find People Screen

| Item | Status | Notes |
|------|--------|-------|
| Search by username | ✅ | |
| Search by email (if email_searchable=1) | ✅ | |
| Search by phone (if phone_searchable=1) | ✅ | |
| Result card: avatar, name, username | ✅ | Respects privacy settings |
| Add Contact button | ✅ | Sends contact request |

### 8.4 Contact Requests Screen

| Item | Status | Notes |
|------|--------|-------|
| Pending incoming requests list | ✅ | Currently in Settings. Move to here |
| Accept button per request | ✅ | |
| Decline button per request | ✅ | |
| Sent requests list (pending outgoing) | ❌ | Show with Cancel option. Currently only visible from search screen |

---

## 9. SETTINGS TAB

### 9.1 Header

| Item | Status | Notes |
|------|--------|-------|
| My avatar | ✅ | |
| My nickname | ✅ | |
| My username | ✅ | |
| Tap → My Profile | ✅ | |

### 9.2 Section: APPEARANCE

| Item | Status | Notes |
|------|--------|-------|
| Theme (System / Light / Dark) | ✅ | |
| Language (English / Bulgarian) | ✅ | Russian: deferred |

### 9.3 Section: PRIVACY

| Item | Status | Notes |
|------|--------|-------|
| Privacy Settings (link → Privacy Settings screen) | ✅ | Contains all privacy switches incl. Read Receipts and Hide Presence |
| Blocked Users (link → list) | ✅ | |
| 🗑️ Read Receipts switch | 🗑️ | Remove from here — kept only in Privacy Settings screen |

### 9.4 Section: SECURITY

| Item | Status | Notes |
|------|--------|-------|
| App Lock (switch / setup) | ✅ | |
| Change Password | ✅ | |
| Secret Question status ("Set" / "Not set") | ⚠️ | Show as read-only status. Tap → My Profile to change |

### 9.5 Section: EMERGENCY

| Item | Status | Notes |
|------|--------|-------|
| SOS Message (editable field) | ✅ | |
| Reset to Default button | ✅ | |
| Save button | ✅ | |

### 9.6 Section: DEVICES

| Item | Status | Notes |
|------|--------|-------|
| This Device: name + star icon | ✅ | |
| Rename this device | ✅ | WebSocket: device-rename |
| Linked Devices list | ✅ | Other active sessions with last seen |
| Remove device (per device) | ✅ | WebSocket: device-remove |

### 9.7 Section: CONNECTION & ADVANCED

| Item | Status | Notes |
|------|--------|-------|
| Server URL (editable) | ✅ | Reset to Default. Triggers FshuService reconnect |
| Push Wake-Up / FCM (switch) | ✅ | Registers/clears FCM token |
| Permissions panel | ✅ | Notifications, Microphone, Camera, Location, Battery Optimization (explicitly prompt user to disable battery optimization for 4shu), Display Over Apps, Full-Screen Intents (API 34+). Green tick = granted, red = tap to open system settings |

### 9.8 Section: ADMIN (visible only if users.admin = 1)

| Item | Status | Notes |
|------|--------|-------|
| Create Invite Link | ⚠️ | Server: invites table (48h TTL). Code exists, UI lost in rework |
| User List | ⚠️ | Search/browse all users. Lost in rework |
| Reset User Password | ⚠️ | Admin resets another user's password. Lost in rework |
| Remove User | ⚠️ | Admin deletes another account. Lost in rework |

### 9.9 Section: DATA & ACCOUNT

| Item | Status | Notes |
|------|--------|-------|
| Export My Data (JSON) | ✅ | On-device decrypt, saves to Downloads |
| Delete Account | ✅ | Confirmation dialog |

### 9.10 Footer

| Item | Status | Notes |
|------|--------|-------|
| App version | ✅ | |
| Build time | ✅ | |

---

## 10. NOTIFICATION ARCHITECTURE

### Channels (must be registered on app start)

| Channel ID | Name | Importance | Bypasses DND | Notes |
|------------|------|-----------|-------------|-------|
| emergency | Emergency | MAX | Yes | Custom loud sound. SOS, priority calls |
| dm | Direct Messages | HIGH | No | Default sound |
| groups | Groups | DEFAULT | No | Silent by default, no vibrate |
| system | System | LOW | No | Device linked, service alerts |

### Notification Features

| Feature | Status | Notes |
|---------|--------|-------|
| Direct Reply (RemoteInput) | ❌ | Reply from notification shade without opening app |
| MessagingStyle | ❌ | Groups messages by conversation, shows avatars |
| Mark as Read action in notification | ❌ | |
| Notification grouping per conversation | ❌ | |
| Per-chat notification settings | 🔲 | Deferred. Global mute per chat is first step |

---

## 11. SYSTEM INTEGRATION

| Feature | Status | Notes |
|---------|--------|-------|
| Share intent from other apps | ❌ | Add intent filters to AndroidManifest for image/*, */* to receive shares from Gallery, Files etc. |
| POST_NOTIFICATIONS runtime permission | ⚠️ | Must be explicitly requested on Android 13+ (API 33) |
| Battery Optimization exemption prompt | ⚠️ | Must prompt user. WebSocket dies without it. Show in Permissions panel |
| WebSocket keepalive (ping/pong) | ✅ | 8-layer connection stability system. Already implemented and working |
| FCM fallback for de-Googled devices | 🔲 | Foreground service with persistent notification as fallback when FCM unavailable |

---

## 12. PRESENCE SYSTEM

### Architecture

- **Server**: broadcasts `presence_update` on first device connect / last device disconnect / hide_presence toggle
- **On login**: server pushes presence snapshot (array of all contacts' presence state) in one message
- **Offline delay**: 15 seconds after socket close before broadcasting offline (prevents Wi-Fi → LTE flicker)
- **Privacy**: enforced server-side. `canSee(viewer, target)` — if either party has hide_presence=1, no presence data sent
- **Client**: `PresenceRepository` holds `StateFlow<Map<String, PresenceInfo>>`. No Room writes. UI observes flow

### WebSocket Message Format

```json
{
  "type": "presence_update",
  "data": {
    "username": "alice",
    "isOnline": true,
    "lastSeen": 1715600000000,
    "hideStatus": false
  }
}
```

### Login Snapshot Format

```json
{
  "type": "presence_snapshot",
  "data": [
    { "username": "alice", "isOnline": true, "lastSeen": 1715600000000, "hideStatus": false },
    { "username": "bob", "isOnline": false, "lastSeen": 1715500000000, "hideStatus": false }
  ]
}
```

### Last Seen Display Rules

| Condition | Display |
|-----------|---------|
| isOnline = true | "Online" |
| lastSeen < 5 min ago | "Online recently" |
| lastSeen today | "Today at HH:MM" |
| lastSeen yesterday | "Yesterday" |
| lastSeen < 7 days | "N days ago" |
| lastSeen older | "Long time ago" |
| hideStatus = true | "Last seen hidden" |

### Mute vs Presence Rule
Mute = notification preference only. Muted contacts still see your presence. Only Block removes presence visibility.

---

## 13. PLANNED FEATURES (Placeholders in UI)

| Feature | Priority | DB Changes Needed |
|---------|----------|------------------|
| Drafts (SharedPreferences, per chat) | High | None |
| Presence system | High | users.hide_presence column |
| Mute per chat (with timed options) | High | mutes PK fix (migration v20) |
| Contact Requests in Contacts tab | High | None |
| Admin panel restoration | High | None (code exists) |
| Group attachment picker fix | High (bug) | None |
| Chat list sort fix | High (bug) | None |
| Search string fix for groups | High (bug) | None |
| Notification channels (4) | High | None |
| Member promote/demote UI | Medium | None (DB supports it) |
| Search within conversation | Medium | None |
| Mark as read | Medium | None |
| Direct Reply from notification | Medium | None |
| Share intent from other apps | Medium | None |
| Media gallery per chat | Medium | None |
| Sent contact requests list | Medium | None |
| Group polls | Low | New: polls, poll_options, poll_votes tables |
| Group privacy settings | Low | New: groups.privacy column |
| Group invite link | Low | New or extend invites table |
| Russian language strings | Low | None |
| SMTP email password reset | Low | None |
| Empty states UI | Low | None |
| FCM fallback foreground service | Low | None |

---

## 14. ITEMS TO REMOVE FROM CODEBASE

| Item | Currently In | Action |
|------|-------------|--------|
| Trust level (all UI) | User Profile, contacts table, users table | Remove from Android + server. DB migration required |
| Emergency Call from DM 3-dot menu | DM 3-dot | Remove |
| Share my location from DM 3-dot | DM 3-dot | Remove |
| Request location from DM 3-dot | DM 3-dot | Remove |
| Auto share location switch | DM 3-dot + old SettingsActivity | Remove UI. Keep auto_location table in server DB |
| Group Debug Log | Old SettingsActivity | Remove |
| Contact Requests from Settings | Old SettingsActivity | Move to Contacts tab |
| Read Receipts from Settings tab | Settings tab | Keep only in Privacy Settings screen |
| Old SettingsActivity (bulk) | SettingsActivity | Migrate all content to SettingsFragment. Retire or rename to DevicesActivity |

---

## 15. BUGS — MUST FIX BEFORE UI REWORK

| Bug | Blocking? |
|-----|-----------|
| Attachment picker broken in groups | Yes — groups unusable for file sharing |
| Chat list sort — groups always on top | Yes — core list behavior wrong |
| Unified search — wrong string for group results | Yes — misleading to users |
| Admin panel items missing after rework | Yes — admins cannot manage users |
