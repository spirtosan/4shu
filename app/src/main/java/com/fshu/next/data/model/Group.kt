package com.fshu.next.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class Group(
    @PrimaryKey val groupId: String,
    val name: String,
    val owner: String,
    val groupType: String,
    val createdAt: Long,
    val avatarPath: String? = null,
    val personalAvatar: String? = null,
    val encryptedGroupKey: String = "",
    val groupKey: String = ""
)
