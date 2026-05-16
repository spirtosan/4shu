package com.fshu.next.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "mutes", primaryKeys = ["target"])
data class Mute(
    val target: String,
    @ColumnInfo(name = "target_type") val targetType: String
)
