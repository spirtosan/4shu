package com.fshu.next.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Mirrors SPEC_T13.md §2.1 (Room 25→26, Phase 1 Block A). cells/wifi/last are stored
// as JSON columns rather than embedded objects — see trail/TrailPointMapper.kt for the
// conversion to/from the pure TrailPointData wire model.
@Entity(tableName = "trail_points", indices = [Index(value = ["seq"], unique = true)])
data class TrailPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seq: Long,
    val kind: String,           // "fix" | "event"
    val ts: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val acc: Double? = null,
    val alt: Double? = null,
    val spd: Double? = null,
    val brg: Double? = null,
    val prov: String? = null,
    val mock: Boolean? = null,
    val mot: String? = null,
    val batt: Int? = null,
    val chg: Boolean? = null,
    val net: String? = null,
    val susp: String? = null,  // glitch flag (SPEC_T13_GLITCH_FILTER.md); null when clean
    val cellsJson: String? = null,
    val wifiJson: String? = null,
    val ev: String? = null,
    val lastJson: String? = null,
    // Upload bookkeeping (§3.5) — set once a batch containing this point is acked
    // by the server for at least one guardian. Consumed starting Block I.
    val uploaded: Boolean = false
)
