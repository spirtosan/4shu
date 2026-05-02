package com.fshu.next.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fshu.next.data.model.Message
import com.fshu.next.data.model.PeerKey

@Database(entities = [Message::class, PeerKey::class], version = 11, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerKeyDao(): PeerKeyDao

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

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fshu.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11
                ).build().also { INSTANCE = it }
            }
    }
}
