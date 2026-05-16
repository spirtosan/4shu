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
    @ColumnInfo(name = "trust_level") val trustLevel: String = "contact",
    @ColumnInfo(name = "allow_emergency_call") val allowEmergencyCall: Int? = null
)

fun effectiveEmergencyAllow(allowEmergencyCall: Int?, trustLevel: String): Boolean {
    if (allowEmergencyCall != null) return allowEmergencyCall == 1
    return trustLevel == "family" || trustLevel == "trusted"
}
