package com.fshu.next.ui.trail

import android.content.Context
import com.fshu.next.R
import com.fshu.next.trail.CellInfo
import java.util.Locale

/** Human-readable label mapping for §2.1 enum-ish string fields, shared between
 *  [TrailViewerActivity]'s row rendering and [TrailPointDetailSheet]'s field rows. */
object TrailLabels {

    fun provider(prov: String) = when (prov) {
        "fused" -> "Fused"
        "gps" -> "GPS"
        "network" -> "Network"
        "passive" -> "Passive"
        else -> prov.replaceFirstChar { it.uppercase() }
    }

    fun motion(ctx: Context, mot: String) = when (mot) {
        "moving" -> ctx.getString(R.string.trail_motion_moving)
        "still" -> ctx.getString(R.string.trail_motion_still)
        else -> mot
    }

    fun network(ctx: Context, net: String) = when (net) {
        "wifi" -> ctx.getString(R.string.trail_net_wifi)
        "cell" -> ctx.getString(R.string.trail_net_cell)
        "offline" -> ctx.getString(R.string.trail_net_offline)
        else -> net
    }

    fun cell(cell: CellInfo): String {
        val type = cell.t.uppercase(Locale.getDefault())
        return if (cell.sig != null) "$type · ${cell.sig} dBm" else type
    }

    fun event(ctx: Context, ev: String?): String = when (ev) {
        "shutdown" -> ctx.getString(R.string.trail_event_shutdown)
        "boot" -> ctx.getString(R.string.trail_event_boot)
        "airplane_on" -> ctx.getString(R.string.trail_event_airplane_on)
        "airplane_off" -> ctx.getString(R.string.trail_event_airplane_off)
        "loc_on" -> ctx.getString(R.string.trail_event_loc_on)
        "loc_off" -> ctx.getString(R.string.trail_event_loc_off)
        "sim_changed" -> ctx.getString(R.string.trail_event_sim_changed)
        "batt_low" -> ctx.getString(R.string.trail_event_batt_low)
        "batt_okay" -> ctx.getString(R.string.trail_event_batt_okay)
        "charge_on" -> ctx.getString(R.string.trail_event_charge_on)
        "charge_off" -> ctx.getString(R.string.trail_event_charge_off)
        "svc_restart" -> ctx.getString(R.string.trail_event_svc_restart)
        "watchdog_restart" -> ctx.getString(R.string.trail_event_watchdog_restart)
        else -> ev ?: ctx.getString(R.string.trail_field_event)
    }
}
