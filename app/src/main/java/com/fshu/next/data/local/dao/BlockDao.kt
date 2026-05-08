package com.fshu.next.data.local.dao

import androidx.room.*
import com.fshu.next.data.local.entities.Block

@Dao
interface BlockDao {
    @Query("SELECT * FROM blocks WHERE owner = :owner ORDER BY createdAt DESC")
    fun getBlocks(owner: String): List<Block>

    @Query("SELECT 1 FROM blocks WHERE owner = :owner AND blocked = :blocked LIMIT 1")
    fun isBlocked(owner: String, blocked: String): Int?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(block: Block)

    @Query("DELETE FROM blocks WHERE owner = :owner AND blocked = :blocked")
    fun remove(owner: String, blocked: String)
}
