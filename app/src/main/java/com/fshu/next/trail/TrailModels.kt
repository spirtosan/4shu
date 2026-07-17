package com.fshu.next.trail

// SPEC_T13.md §2.1 — wire/plaintext point shape. Pure Kotlin, no Room/Gson annotations
// needed: Gson maps these fields by name and omits nulls by default, which already
// matches the spec's "nullable fields omitted when unavailable" rule.

object TrailPointKind {
    const val FIX = "fix"
    const val EVENT = "event"
}

// ev values per §2.1: shutdown | boot | airplane_on | airplane_off | loc_off | loc_on |
// sim_changed | batt_low | charge_on | charge_off | svc_restart. Kept as a plain String
// on TrailPointData (not an enum) — the event receivers that produce these land in
// Block C; enforcing the closed set here would be speculative for Block A.

data class CellInfo(
    val t: String,            // "lte" | "nr" | "gsm" | "wcdma" | "cdma"
    val mcc: Int? = null,
    val mnc: Int? = null,
    val tac: Int? = null,
    val ci: Long? = null,
    val pci: Int? = null,
    val sig: Int? = null,     // RSRP (LTE/NR) or RSSI (WCDMA/GSM), dBm
    val reg: Boolean = false  // true marks the serving cell
)

data class WifiAp(
    val b: String,            // BSSID
    val s: String? = null,    // SSID (omitted for hidden networks)
    val r: Int,                // RSSI, dBm
    val f: Int? = null         // frequency, MHz
)

data class WifiInfo(
    val conn: WifiAp? = null,  // currently connected AP
    val scan: List<WifiAp>? = null // latest scan results
)

// Event snapshot of the last fix — §2.1 "last".
data class LastFix(
    val lat: Double,
    val lon: Double,
    val acc: Double,
    val ts: Long
)

data class TrailPointData(
    val seq: Long,
    val kind: String,           // TrailPointKind.FIX | .EVENT
    val ts: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val acc: Double? = null,
    val alt: Double? = null,
    val spd: Double? = null,
    val brg: Double? = null,
    val prov: String? = null,   // location provider, e.g. "fused" | "gps" | "network" | "passive"
    val mock: Boolean? = null,
    val mot: String? = null,    // "moving" | "still"
    val batt: Int? = null,
    val chg: Boolean? = null,
    val net: String? = null,
    val cells: List<CellInfo>? = null,
    val wifi: WifiInfo? = null,
    val ev: String? = null,      // kind=event only
    val last: LastFix? = null    // kind=event only
)
