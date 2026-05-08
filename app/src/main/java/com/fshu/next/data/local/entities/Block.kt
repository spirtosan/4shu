package com.fshu.next.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocks")
data class Block(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val owner: String,
    val blocked: String,
    val createdAt: Long
)
