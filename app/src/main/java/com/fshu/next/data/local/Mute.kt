package com.fshu.next.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "mutes", primaryKeys = ["owner", "target", "targetType"])
data class Mute(
    val owner: String,
    val target: String,
    @ColumnInfo(name = "target_type") val targetType: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "mute_until") val muteUntil: Long? = null
)
