package com.fshu.next.ui.trail

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.fshu.next.R
import com.fshu.next.trail.TrailPointCodec
import com.fshu.next.trail.TrailPointData
import com.fshu.next.trail.TrailPointKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SPEC_T13.md §7 Phase 1 Block E — per-point detail sheet. Every §2.1 field is
 * rendered human-readable via simple label/value rows (mirrors ConnectionTestSheet's
 * BottomSheetDialogFragment convention, the app's only existing bottom-sheet detail
 * screen); a raw-JSON view (via TrailPointCodec) is offered behind a cheap toggle.
 * Nullable fields are simply omitted from the row list, same "not available" idiom
 * §2.1 itself uses on the wire.
 */
class TrailPointDetailSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_POINT_JSON = "point_json"

        fun newInstance(point: TrailPointData) = TrailPointDetailSheet().apply {
            arguments = bundleOf(ARG_POINT_JSON to TrailPointCodec.toJson(point))
        }
    }

    private val point: TrailPointData by lazy {
        TrailPointCodec.fromJson(requireArguments().getString(ARG_POINT_JSON)!!)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_trail_point_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val df = SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault())

        val tvTitle = view.findViewById<TextView>(R.id.tvDetailTitle)
        val llRows = view.findViewById<ViewGroup>(R.id.llDetailRows)
        tvTitle.text = getString(
            if (point.kind == TrailPointKind.EVENT) R.string.trail_point_detail_title_event
            else R.string.trail_point_detail_title_fix
        )

        fun row(labelRes: Int, value: String?) {
            if (value == null) return
            val row = LayoutInflater.from(ctx).inflate(R.layout.item_trail_detail_row, llRows, false)
            row.findViewById<TextView>(R.id.tvDetailLabel).text = getString(labelRes)
            row.findViewById<TextView>(R.id.tvDetailValue).text = value
            llRows.addView(row)
        }

        row(R.string.trail_field_time, df.format(Date(point.ts)))

        if (point.lat != null && point.lon != null) {
            row(R.string.trail_field_location, "%.5f, %.5f".format(Locale.US, point.lat, point.lon))
        }
        row(R.string.trail_field_accuracy, point.acc?.let { "%.1f m".format(Locale.US, it) })
        row(R.string.trail_field_altitude, point.alt?.let { "%.1f m".format(Locale.US, it) })
        row(R.string.trail_field_speed, point.spd?.let { "%.1f m/s".format(Locale.US, it) })
        row(R.string.trail_field_bearing, point.brg?.let { "%.0f°".format(Locale.US, it) })
        row(R.string.trail_field_provider, point.prov?.let { TrailLabels.provider(it) })
        row(R.string.trail_field_mock, point.mock?.let { if (it) getString(R.string.trail_value_yes) else getString(R.string.trail_value_no) })
        row(R.string.trail_field_motion, point.mot?.let { TrailLabels.motion(ctx, it) })

        if (point.batt != null) {
            val chgSuffix = if (point.chg == true) " (${getString(R.string.trail_battery_charging)})" else ""
            row(R.string.trail_field_battery, "${point.batt}%$chgSuffix")
        }
        row(R.string.trail_field_network, point.net?.let { TrailLabels.network(ctx, it) })

        val cells = point.cells
        if (!cells.isNullOrEmpty()) {
            val serving = cells.firstOrNull { it.reg } ?: cells.first()
            row(R.string.trail_field_serving_cell, TrailLabels.cell(serving))
            row(R.string.trail_field_cell_count, cells.size.toString())
        }

        val wifi = point.wifi
        if (wifi?.conn != null) {
            val ap = wifi.conn
            val ssid = ap.s ?: getString(R.string.trail_value_none)
            row(R.string.trail_field_wifi_connected, "$ssid (${ap.b})")
        }
        if (!wifi?.scan.isNullOrEmpty()) {
            row(R.string.trail_field_wifi_scan_count, wifi!!.scan!!.size.toString())
        }

        if (point.kind == TrailPointKind.EVENT) {
            row(R.string.trail_field_event, TrailLabels.event(ctx, point.ev))
            point.last?.let { last ->
                row(
                    R.string.trail_field_last_known,
                    "%.5f, %.5f (±%.0f m) @ %s".format(
                        Locale.US, last.lat, last.lon, last.acc, df.format(Date(last.ts))
                    )
                )
            }
        }

        // Raw JSON — cheap: same TrailPointCodec used for the wire format, pretty-printed
        // via org.json (matches SettingsFragment's GDPR-export pretty-print convention).
        val btnToggle = view.findViewById<View>(R.id.btnToggleRawJson)
        val sectionRawJson = view.findViewById<View>(R.id.sectionRawJson)
        val tvRawJson = view.findViewById<TextView>(R.id.tvRawJson)
        val rawJson = org.json.JSONObject(TrailPointCodec.toJson(point)).toString(2)
        tvRawJson.text = rawJson

        btnToggle.setOnClickListener {
            val show = sectionRawJson.visibility != View.VISIBLE
            sectionRawJson.visibility = if (show) View.VISIBLE else View.GONE
            (btnToggle as? android.widget.Button)?.text =
                getString(if (show) R.string.trail_hide_raw_json else R.string.trail_show_raw_json)
        }

        view.findViewById<View>(R.id.btnCopyRawJson).setOnClickListener {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("trail-point", rawJson))
            Toast.makeText(ctx, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
        }
    }
}
