package com.fshu.next.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

// Per-guardian-device upload watermark (SPEC_T13.md §3.5). Consumed starting Block I.
@Entity(tableName = "trail_upload_state")
data class TrailUploadState(
    @PrimaryKey val guardianDevice: String,
    val lastAckedSeq: Long
)
