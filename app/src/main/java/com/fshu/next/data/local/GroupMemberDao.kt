package com.fshu.next.data.local

import androidx.room.*
import com.fshu.next.data.model.GroupMember

@Dao
interface GroupMemberDao {
    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getMembersOf(groupId: String): List<GroupMember>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(member: GroupMember)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(members: List<GroupMember>)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND username = :username")
    suspend fun remove(groupId: String, username: String)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun removeAll(groupId: String)
}
