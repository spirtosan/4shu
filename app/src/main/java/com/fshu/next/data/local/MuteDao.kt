package com.fshu.next.data.local

import androidx.room.*

@Dao
interface MuteDao {
    @Query("SELECT * FROM mutes") fun getAll(): List<Mute>
    @Query("SELECT COUNT(*) FROM mutes WHERE target = :target") fun isMuted(target: String): Boolean
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(mute: Mute)
    @Query("DELETE FROM mutes WHERE target = :target") fun delete(target: String)
    @Query("DELETE FROM mutes") fun deleteAll()
}
