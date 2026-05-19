package com.fshu.next.data.local

import androidx.room.*

@Dao
interface MuteDao {
    @Query("SELECT * FROM mutes WHERE owner = :owner")
    fun getAll(owner: String): List<Mute>

    @Query("SELECT COUNT(*) FROM mutes WHERE owner = :owner AND target = :target AND (mute_until IS NULL OR mute_until > :now)")
    fun isMuted(owner: String, target: String, now: Long = System.currentTimeMillis()): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(mute: Mute)

    @Query("DELETE FROM mutes WHERE owner = :owner AND target = :target")
    fun delete(owner: String, target: String)

    @Query("DELETE FROM mutes WHERE owner = :owner")
    fun deleteAll(owner: String)
}
