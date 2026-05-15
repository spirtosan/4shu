package com.fshu.next.data.local

import androidx.room.Entity

@Entity(tableName = "mutes", primaryKeys = ["target"])
data class Mute(
    val target: String,
    val targetType: String = "contact"
)
