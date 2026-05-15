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
| trust_level | TEXT | family/trusted/contact/stranger |
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

### sessions
| Column | Type | Notes |
|--------|------|-------|
| session_token | TEXT PK | 32 bytes hex, 24h TTL |
| username | TEXT | |
| device_id | TEXT | |
| created_at | INTEGER | |
| expires_at | INTEGER | |

### devices
| Column | Type | Notes |
|--------|------|-------|
| username | TEXT | |
| device_id | TEXT | |
| device_name | TEXT | User-set name |
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
| type | TEXT | text/file/voice/location etc |
| file_id | TEXT | |
| reply_to_id | TEXT | |
| reply_to_sender | TEXT | |
| reply_to_content | TEXT | |
| edited_at | INTEGER | |
| deleted_for_all | INTEGER | 0/1 |

### files
| Column | Type | Notes |
|--------|------|-------|
| file_id | TEXT PK | UUID |
| uploader | TEXT | |
| filename | TEXT | |
| mime_type | TEXT | |
| size | INTEGER | bytes |
| created_at | INTEGER | |

### reactions
| Column | Type | Notes |
|--------|------|-------|
| message_id | TEXT | |
| from_user | TEXT | |
| emoji | TEXT | Unicode emoji |
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
| encrypted_key | TEXT | Group key encrypted for this member |
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
| PRIMARY KEY | | (owner, contact) |

### blocks
| Column | Type | Notes |
|--------|------|-------|
| owner | TEXT | |
| blocked | TEXT | |
| created_at | INTEGER | |
| PRIMARY KEY | | (owner, blocked) |

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
| title | TEXT | |
| owner | TEXT | |
| peer | TEXT | For DM lists |
| group_id | TEXT | For group lists |
| version | INTEGER | |
| created_at | INTEGER | |

### list_items
| Column | Type | Notes |
|--------|------|-------|
| item_id | TEXT PK | |
| list_id | TEXT | |
| text | TEXT | |
| checked | INTEGER | 0/1 |
| checked_by | TEXT | username |
| position | INTEGER | |

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
| payload | TEXT | JSON |
| created_at | INTEGER | |

---

## Android — Room SQLite (local device DB)
Current version: **16**

### Key entities
| Entity | Table | Notes |
|--------|-------|-------|
| Message | messages | All DM + group messages, local only |
| Group | groups | Cached group info |
| GroupMember | group_members | Cached membership |
| PeerKey | peer_keys | ECDH public key cache |
| Contact | contacts | Cached accepted contacts (synced from server) |
| Block | blocks | Cached block list |

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
| status | String | SENDING/SENT/DELIVERED/READ |
| isRequest | Boolean | true = pre-contact message, shown in requests inbox |
| isSent | Boolean | true = sent by me |
| timestamp | Long | ms |

### Migration history
| Version | Changes |
|---------|---------|
| 14 | Base schema |
| 15 | Added contacts + blocks tables, 8 new user profile columns |
| 16 | Added isRequest column to messages |
