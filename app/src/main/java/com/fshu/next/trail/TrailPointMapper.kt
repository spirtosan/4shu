package com.fshu.next.trail

import com.fshu.next.data.local.entities.TrailPoint
import com.google.gson.Gson

/** Conversion between the pure wire model ([TrailPointData]) and the Room entity
 *  ([TrailPoint]), whose cells/wifi/last columns are JSON strings (§3.5). */
private val gson = Gson()

fun TrailPointData.toEntity(uploaded: Boolean = false): TrailPoint = TrailPoint(
    seq = seq, kind = kind, ts = ts,
    lat = lat, lon = lon, acc = acc, alt = alt, spd = spd, brg = brg,
    prov = prov, mock = mock, mot = mot, batt = batt, chg = chg, net = net, susp = susp,
    cellsJson = cells?.let { gson.toJson(it) },
    wifiJson = wifi?.let { gson.toJson(it) },
    ev = ev,
    lastJson = last?.let { gson.toJson(it) },
    uploaded = uploaded
)

fun TrailPoint.toData(): TrailPointData = TrailPointData(
    seq = seq, kind = kind, ts = ts,
    lat = lat, lon = lon, acc = acc, alt = alt, spd = spd, brg = brg,
    prov = prov, mock = mock, mot = mot, batt = batt, chg = chg, net = net, susp = susp,
    cells = cellsJson?.let { gson.fromJson(it, Array<CellInfo>::class.java).toList() },
    wifi = wifiJson?.let { gson.fromJson(it, WifiInfo::class.java) },
    ev = ev,
    last = lastJson?.let { gson.fromJson(it, LastFix::class.java) }
)
