# 4shu Database Schema Overview

## Server — SQLite (`/opt/fshu5/data/fshu.db`, WAL mode)
Current as of DB work through Phase 5.

### users
| Column | Type | Notes |
|--------|------|-------|
| username | TEXT PK | Immutable, permanent identifier |
| password_hash | TEXT | bcrypt |
| admin | INTEGER | 0/1 |
| nickname | TEXT | Display name, changeable |
| fcm_token | TEXT | Firebase push token |
| avatar_path | TEXT | Path to avatar file |
| last_seen | INTEGER | Timestamp ms |
| created_at | INTEGER | Timestamp ms |
| public_key | TEXT | X25519 public key hex |
| status | TEXT | active/deleted |
| email | TEXT | Optional, unique, used for search/reset |
| phone | TEXT | Optional, E.164 format |
| bio | TEXT | Optional profile bio |
| discoverable | INTEGER | 1=searchable (default), 0=hidden |
| show_avatar | INTEGER | 1=public (default), 0=contacts only |
| show_nickname | INTEGER | 1=public (default), 0=contacts only |
| email_searchable | INTEGER | 1=searchable (default) |
| phone_searchable | INTEGER | 1=searchable (default) |
| secret_question | TEXT | Account recovery question |
| secret_answer_hash | TEXT | bcrypt hash of answer (lowercased) |
| hide_presence | INTEGER | DEFAULT 0; 1=hide last-seen from non-contacts |

### sessions
| Column | Type | Notes |
|--------|------|-------|
| token | TEXT PK | 32 bytes hex |
| username | TEXT | |
| device_id | TEXT | NOT NULL DEFAULT '' |
| created_at | INTEGER | |
| last_seen | INTEGER | |

### devices
| Column | Type | Notes |
|--------|------|-------|
| username | TEXT | |
| device_id | TEXT | |
| device_name | TEXT | User-set name |
| fcm_token | TEXT | |
| last_seen | INTEGER | |
| PRIMARY KEY | | (username, device_id) |

### messages
| Column | Type | Notes |
|--------|------|-------|
| message_id | TEXT PK | Server-assigned UUID |
| from_user | TEXT | |
| to_user | TEXT | null for group messages |
| group_id | TEXT | null for DMs |
| content | TEXT | Encrypted |
| timestamp | INTEGER | ms |
| type | TEXT | message/file/voice/location etc, DEFAULT 'message' |
| file_id | TEXT | |
| reply_to_id | TEXT | |
| reply_to_sender | TEXT | |
| reply_to_content | TEXT | |
| edited_at | INTEGER | |
| deleted_for_all | INTEGER | 0/1 |
| client_id | TEXT | Client-generated dedup UUID |

### files
| Column | Type | Notes |
|--------|------|-------|
| file_id | TEXT PK | UUID |
| uploader | TEXT | |
| filename | TEXT | |
| mime_type | TEXT | |
| file_path | TEXT | Server-side storage path |
| size_bytes | INTEGER | bytes |
| created_at | INTEGER | |
| expires_at | INTEGER | |
| nonce | TEXT | Encryption nonce |
| meta_json | TEXT | Additional metadata |

### reactions
| Column | Type | Notes |
|--------|------|-------|
| message_id | TEXT | |
| from_user | TEXT | |
| emoji | TEXT | Unicode emoji |
| timestamp | INTEGER | |
| PRIMARY KEY | | (message_id, from_user) |

### groups
| Column | Type | Notes |
|--------|------|-------|
| group_id | TEXT PK | UUID |
| name | TEXT | |
| owner | TEXT | username |
| type | TEXT | family/group |
| avatar_path | TEXT | |
| created_at | INTEGER | |

### group_members
| Column | Type | Notes |
|--------|------|-------|
| group_id | TEXT | |
| username | TEXT | |
| role | TEXT | owner/admin/member |
| joined_at | INTEGER | |
| encrypted_group_key | TEXT | Group key encrypted for this member |
| PRIMARY KEY | | (group_id, username) |

### contact_nicknames
| Column | Type | Notes |
|--------|------|-------|
| owner | TEXT | Who set the nickname |
| contact | TEXT | Who the nickname is for |
| nickname | TEXT | |
| PRIMARY KEY | | (owner, contact) |

### contacts
| Column | Type | Notes |
|--------|------|-------|
| owner | TEXT | |
| contact | TEXT | |
| status | TEXT | pending/accepted |
| created_at | INTEGER | |
| updated_at | INTEGER | |
| expires_at | INTEGER | pending expires after 90 days |
| allow_emergency_call | INTEGER | null=not set, 1=allowed, 0=denied |
| allow_emergency_location | INTEGER | null=not set, 1=allowed, 0=denied |
| PRIMARY KEY | | (owner, contact) |

### blocks
| Column | Type | Notes |
|--------|------|-------|
| owner | TEXT | |
| blocked | TEXT | |
| created_at | INTEGER | |
| PRIMARY KEY | | (owner, blocked) |

### mutes
| Column | Type | Notes |
|--------|------|-------|
| owner | TEXT | Who set the mute |
| target | TEXT | Muted contact or group |
| target_type | TEXT | DEFAULT "contact" |
| created_at | INTEGER | |
| mute_until | INTEGER | null=indefinite |
| PRIMARY KEY | | (owner, target, target_type) |

### auto_location
| Column | Type | Notes |
|--------|------|-------|
| owner | TEXT | Who enabled auto-share |
| peer | TEXT | Who triggers auto-share |
| PRIMARY KEY | | (owner, peer) |

### lists (todo)
| Column | Type | Notes |
|--------|------|-------|
| list_id | TEXT PK | |
| owner | TEXT | |
| peer | TEXT | For DM lists |
| group_id | TEXT | For group lists |
| version | INTEGER | DEFAULT 1 |
| created_at | INTEGER | |
| message_id | TEXT | Message that created this list |

### list_items
| Column | Type | Notes |
|--------|------|-------|
| item_id | TEXT | |
| list_id | TEXT | |
| text | TEXT | |
| done | INTEGER | 0/1 DEFAULT 0 |
| checked_by | TEXT | username |
| checked_at | INTEGER | |
| deleted_at | INTEGER | null = not deleted |
| sort_order | INTEGER | |
| PRIMARY KEY | | (item_id, list_id) |

### invites
| Column | Type | Notes |
|--------|------|-------|
| token | TEXT PK | 48 char hex |
| created_by | TEXT | admin username |
| expires_at | INTEGER | 48h TTL |
| used_by | TEXT | username if used |
| used_at | INTEGER | |

### password_resets
| Column | Type | Notes |
|--------|------|-------|
| token | TEXT PK | 64 char hex |
| username | TEXT | |
| expires_at | INTEGER | 1h TTL |
| created_at | INTEGER | |
| used_at | INTEGER | |

### queue
| Column | Type | Notes |
|--------|------|-------|
| id | INTEGER PK | autoincrement |
| username | TEXT | recipient |
| envelope | TEXT | JSON |
| created_at | INTEGER | |

---

## Android — Room SQLite (local device DB)
Current version: **25**

### Key entities
| Entity | Table | Notes |
|--------|-------|-------|
| Message | messages | All DM + group messages, local only |
| Group | groups | Cached group info |
| GroupMember | group_members | Cached membership |
| PeerKey | peer_keys | ECDH public key cache |
| Contact | contacts | Cached accepted contacts (synced from server) |
| Block | blocks | Cached block list |
| Mute | mutes | Muted contacts/groups |

### Message entity key fields
| Field | Type | Notes |
|-------|------|-------|
| id | Long PK | Room autoincrement, used as encryption nonce |
| remoteId | Long | Server message ID (0 if unknown) |
| from | String | |
| to | String | |
| groupId | String? | null for DMs |
| content | String | Decrypted text or encrypted blob |
| type | String | text/file/voice/location/deleted etc |
| filename | String? | |
| mimeType | String? | |
| localUri | String? | content:// URI of saved/picked file |
| status | String | SENDING/SENT/DELIVERED/READ |
| isSent | Boolean | true = sent by me |
| isRequest | Boolean | true = pre-contact message, shown in requests inbox |
| timestamp | Long | ms |
| replyToId | Long? | |
| replyToSender | String? | |
| replyToContent | String? | |
| listId | String? | set for type="list" messages |
| lastSynced | Long? | timestamp of last list sync |
| listVersion | Int? | server-authoritative list version |
| listOwner | String? | list creator username |
| tempId | String? | client UUID for file upload dedup |
| fileId | String? | server-assigned file UUID |
| editedAt | Long | epoch ms of last edit; 0 = never edited |
| reactions | String | JSON array of {from, emoji}; "" = none |
| voiceDuration | Int | seconds; 0 for non-voice |
| voiceWaveform | String? | compact JSON float array of amplitude samples |
| encryptedBlob | Int | 0/1; 1 = content is raw encrypted bytes, not JSON |
| isRead | Int | 0/1 NOT NULL DEFAULT 1; 0 = unread incoming message |

### Contact entity key fields
| Field | Type | Notes |
|-------|------|-------|
| id | Int PK | autoincrement |
| owner | String | |
| contact | String | |
| status | String | pending/accepted |
| createdAt | Long | |
| updatedAt | Long | |
| expiresAt | Long | |
| allowEmergencyCall | Int? | null=not set, 1=allowed, 0=denied (column: allow_emergency_call) |
| allowEmergencyLocation | Int? | null=not set, 1=allowed, 0=denied (column: allow_emergency_location) |

### Mute entity fields
| Field | Type | Notes |
|-------|------|-------|
| owner | String | who set the mute |
| target | String | muted contact or group ID |
| targetType | String | contact/group (column: target_type) |
| createdAt | Long | epoch ms (column: created_at) |
| muteUntil | Long? | epoch ms; null = indefinite (column: mute_until) |
| PRIMARY KEY | | (owner, target, targetType) |

### Migration history
| Version | Changes |
|---------|---------|
| 1 | Base schema (messages table) |
| 2 | Added status, remoteId to messages |
| 3 | Added localUri to messages |
| 4 | Added replyToId, replyToSender, replyToContent to messages |
| 5 | Added listId to messages |
| 6 | Added lastSynced to messages |
| 7 | Added listVersion, listOwner to messages |
| 8 | No schema change (location/location-request stored in content JSON) |
| 9 | Created peer_keys table |
| 10 | Added tempId, fileId to messages |
| 11 | Added editedAt to messages |
| 12 | Added reactions to messages |
| 13 | Added voiceDuration, voiceWaveform to messages |
| 14 | Added groupId to messages; created groups and group_members tables |
| 15 | Created contacts and blocks tables; 8 user profile columns |
| 16 | Added isRequest to messages |
| 17 | Added trust_level to contacts |
| 18 | Created mutes table (initial schema with DEFAULT 'contact') |
| 19 | Dropped and recreated mutes table (removed DEFAULT from target_type) |
| 20 | Added allow_emergency_call to contacts |
| 21 | Added allow_emergency_location to contacts |
| 22 | Added encryptedBlob to messages |
| 23 | Recreated mutes table: added owner, created_at, mute_until; PK changed to (owner, target, target_type) |
| 24 | Recreated contacts table: dropped trust_level column |
| 25 | Added isRead to messages |
