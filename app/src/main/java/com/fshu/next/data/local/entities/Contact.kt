package com.fshu.next.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val owner: String,
    val contact: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long
)
