package com.fshu.next.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "username"],
    foreignKeys = [ForeignKey(
        entity = Group::class,
        parentColumns = ["groupId"],
        childColumns = ["groupId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("groupId")]
)
data class GroupMember(
    val groupId: String,
    val username: String,
    val role: String = "member",
    val joinedAt: Long = System.currentTimeMillis()
)
