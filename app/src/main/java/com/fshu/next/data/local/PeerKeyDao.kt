package com.fshu.next.data.local

import androidx.room.*
import com.fshu.next.data.model.PeerKey

@Dao
interface PeerKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: PeerKey)

    @Query("SELECT * FROM peer_keys WHERE username = :username")
    suspend fun get(username: String): PeerKey?

    @Query("SELECT * FROM peer_keys")
    suspend fun getAll(): List<PeerKey>

    @Query("DELETE FROM peer_keys WHERE username = :username")
    suspend fun delete(username: String)
}
