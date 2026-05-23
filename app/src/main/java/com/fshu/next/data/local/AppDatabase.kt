package com.fshu.next.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fshu.next.data.local.dao.BlockDao
import com.fshu.next.data.local.dao.ContactDao
import com.fshu.next.data.local.entities.Block
import com.fshu.next.data.local.entities.Contact
import com.fshu.next.data.local.Mute
import com.fshu.next.data.local.MuteDao
import com.fshu.next.data.model.Group
import com.fshu.next.data.model.GroupMember
import com.fshu.next.data.model.Message
import com.fshu.next.data.model.PeerKey

@Database(entities = [Message::class, PeerKey::class, Group::class, GroupMember::class, Contact::class, Block::class, Mute::class], version = 25, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerKeyDao(): PeerKeyDao
    abstract fun groupDao(): GroupDao
    abstract fun groupMemberDao(): GroupMemberDao
    abstract fun contactDao(): ContactDao
    abstract fun blockDao(): BlockDao
    abstract fun muteDao(): MuteDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing rows default to SENT (we know the server received them).
                db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT NOT NULL DEFAULT 'SENT'")
                db.execSQL("ALTER TABLE messages ADD COLUMN remoteId INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN localUri TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToId INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToSender TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToContent TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN listId TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN lastSynced INTEGER")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN listVersion INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN listOwner TEXT")
            }
        }

        // v8: no schema change — adds location and location-request message types (stored in content JSON)
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }

        // v9: peer_keys table for ECDH public key cache
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS peer_keys " +
                    "(username TEXT NOT NULL PRIMARY KEY, publicKey TEXT NOT NULL)"
                )
            }
        }

        // v10: tempId + fileId for binary file transfer (Phase 1g)
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN tempId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN fileId TEXT")
            }
        }

        // v11: editedAt for message editing (Phase 2.3)
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN editedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v12: reactions JSON string for message reactions (Phase 2.4)
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reactions TEXT NOT NULL DEFAULT ''")
            }
        }

        // v13: voice message fields (Phase 2.5)
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN voiceDuration INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN voiceWaveform TEXT")
            }
        }

        // v14: groups + group_members tables; messages.groupId (Phase 3b)
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN groupId TEXT")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `groups` (
                        groupId TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        owner TEXT NOT NULL,
                        groupType TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        avatarPath TEXT,
                        personalAvatar TEXT,
                        encryptedGroupKey TEXT NOT NULL DEFAULT '',
                        groupKey TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_members (
                        groupId TEXT NOT NULL,
                        username TEXT NOT NULL,
                        role TEXT NOT NULL DEFAULT 'member',
                        joinedAt INTEGER NOT NULL,
                        PRIMARY KEY (groupId, username),
                        FOREIGN KEY (groupId) REFERENCES `groups`(groupId) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members(groupId)")
            }
        }

        // v15: contacts + blocks tables; profile fields on users
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isRequest INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN trust_level TEXT NOT NULL DEFAULT 'contact'")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS mutes (target TEXT NOT NULL, target_type TEXT NOT NULL DEFAULT 'contact', PRIMARY KEY (target))")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS mutes")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mutes` (`target` TEXT NOT NULL, `target_type` TEXT NOT NULL, PRIMARY KEY(`target`))"
                )
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN allow_emergency_call INTEGER")
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN allow_emergency_location INTEGER")
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN encryptedBlob INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS mutes")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS mutes (
                        owner TEXT NOT NULL,
                        target TEXT NOT NULL,
                        target_type TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        mute_until INTEGER,
                        PRIMARY KEY (owner, target, target_type)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS contacts_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        owner TEXT NOT NULL,
                        contact TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL,
                        allow_emergency_call INTEGER,
                        allow_emergency_location INTEGER
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO contacts_new (id, owner, contact, status, createdAt, updatedAt, expiresAt, allow_emergency_call, allow_emergency_location)
                    SELECT id, owner, contact, status, createdAt, updatedAt, expiresAt, allow_emergency_call, allow_emergency_location
                    FROM contacts
                """.trimIndent())
                db.execSQL("DROP TABLE contacts")
                db.execSQL("ALTER TABLE contacts_new RENAME TO contacts")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try { db.execSQL("ALTER TABLE users ADD COLUMN email TEXT") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE users ADD COLUMN phone TEXT") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE users ADD COLUMN bio TEXT") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE users ADD COLUMN discoverable INTEGER DEFAULT 1") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE users ADD COLUMN show_avatar INTEGER DEFAULT 1") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE users ADD COLUMN show_nickname INTEGER DEFAULT 1") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE users ADD COLUMN email_searchable INTEGER DEFAULT 1") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE users ADD COLUMN phone_searchable INTEGER DEFAULT 1") } catch (_: Exception) {}
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS contacts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        owner TEXT NOT NULL,
                        contact TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS blocks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        owner TEXT NOT NULL,
                        blocked TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fshu.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                    MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
                    MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
                    MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25
                ).build().also { INSTANCE = it }
            }
    }
}
