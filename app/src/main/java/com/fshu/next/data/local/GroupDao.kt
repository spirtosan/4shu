package com.fshu.next.data.local

import androidx.lifecycle.LiveData
import androidx.room.*
import com.fshu.next.data.model.Group

@Dao
interface GroupDao {
    @Query("SELECT * FROM `groups` ORDER BY createdAt DESC")
    fun getAllGroups(): LiveData<List<Group>>

    @Query("SELECT * FROM `groups` ORDER BY createdAt DESC")
    suspend fun getAllGroupsDirect(): List<Group>

    @Query("SELECT * FROM `groups` WHERE groupId = :groupId")
    suspend fun getById(groupId: String): Group?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: Group)

    @Query("DELETE FROM `groups` WHERE groupId = :groupId")
    suspend fun deleteById(groupId: String)

    @Query("UPDATE `groups` SET groupKey = :key WHERE groupId = :groupId")
    suspend fun updateGroupKey(groupId: String, key: String)

    @Query("UPDATE `groups` SET groupKey = '' WHERE groupId = :groupId")
    suspend fun clearGroupKey(groupId: String)

    @Query("UPDATE `groups` SET avatarPath = :path WHERE groupId = :groupId")
    suspend fun updateAvatar(groupId: String, path: String)

    @Query("UPDATE `groups` SET personalAvatar = :path WHERE groupId = :groupId")
    suspend fun updatePersonalAvatar(groupId: String, path: String)

    @Query("DELETE FROM `groups`")
    suspend fun deleteAll()
}
