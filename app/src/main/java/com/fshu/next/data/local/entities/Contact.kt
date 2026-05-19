package com.fshu.next.data.local.entities

import androidx.room.ColumnInfo
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
    val expiresAt: Long,
    @ColumnInfo(name = "allow_emergency_call") val allowEmergencyCall: Int? = null,
    @ColumnInfo(name = "allow_emergency_location") val allowEmergencyLocation: Int? = null
)

fun effectiveEmergencyAllow(allowEmergencyCall: Int?): Boolean = allowEmergencyCall == 1
