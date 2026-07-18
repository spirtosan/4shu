package com.fshu.next.ui.trail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.fshu.next.R
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.databinding.ActivityTrailViewerBinding
import com.fshu.next.trail.TrailPointData
import com.fshu.next.trail.TrailPointKind
import com.fshu.next.trail.toData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val TYPE_HEADER = 0
private const val TYPE_FIX = 1
private const val TYPE_EVENT = 2

/**
 * SPEC_T13.md §7 Phase 1 Block E — reverse-chronological local trail viewer.
 * Day-grouped list mirrors MediaGalleryActivity's Header/Item sealed-class + view-type
 * adapter idiom (this app's only existing precedent for a grouped RecyclerView list);
 * fixes and events use visually distinct rows (item_trail_fix.xml / item_trail_event.xml).
 */
class TrailViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrailViewerBinding
    private val db by lazy { AppDatabase.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrailViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.trail_viewer_title)
            setDisplayHomeAsUpEnabled(true)
        }
        binding.rvTrail.layoutManager = LinearLayoutManager(this)
        loadTrail()
    }

    private fun loadTrail() {
        lifecycleScope.launch {
            val points = withContext(Dispatchers.IO) {
                db.trailDao().getAllDesc().map { it.toData() }
            }
            if (points.isEmpty()) {
                binding.rvTrail.visibility = View.GONE
                binding.tvTrailEmpty.visibility = View.VISIBLE
                return@launch
            }
            binding.rvTrail.visibility = View.VISIBLE
            binding.tvTrailEmpty.visibility = View.GONE
            binding.rvTrail.adapter = TrailAdapter(buildTrailItems(points))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_trail_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_trail_wipe -> { confirmWipe(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Same confirm + deleteAll as TrailSettingsActivity's disable flow, but Trail
    // itself is left running — only the stored points are deleted (§7 Block E).
    private fun confirmWipe() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_trail_wipe_title))
            .setMessage(getString(R.string.dialog_trail_wipe_message))
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setPositiveButton(getString(R.string.btn_trail_wipe)) { _, _ -> wipeTrail() }
            .show()
    }

    private fun wipeTrail() {
        lifecycleScope.launch(Dispatchers.IO) {
            db.trailDao().deleteAll()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@TrailViewerActivity, getString(R.string.toast_trail_wiped), Toast.LENGTH_SHORT).show()
                binding.rvTrail.visibility = View.GONE
                binding.tvTrailEmpty.visibility = View.VISIBLE
                binding.rvTrail.adapter = null
            }
        }
    }

    private fun buildTrailItems(points: List<TrailPointData>): List<TrailItem> {
        val result = mutableListOf<TrailItem>()
        var currentLabel = ""
        val now = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val monthYearFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

        for (point in points) {
            val cal = Calendar.getInstance().apply { timeInMillis = point.ts }
            val label = when {
                sameDay(cal, now) -> getString(R.string.date_today)
                sameDay(cal, yesterday) -> getString(R.string.date_yesterday)
                else -> monthYearFmt.format(Date(point.ts))
            }
            if (label != currentLabel) {
                result.add(TrailItem.Header(label))
                currentLabel = label
            }
            result.add(
                if (point.kind == TrailPointKind.EVENT) TrailItem.Event(point) else TrailItem.Fix(point)
            )
        }
        return result
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    // ── Sealed item type ──────────────────────────────────────────────────────

    sealed class TrailItem {
        data class Header(val label: String) : TrailItem()
        data class Fix(val point: TrailPointData) : TrailItem()
        data class Event(val point: TrailPointData) : TrailItem()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class TrailAdapter(private val items: List<TrailItem>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        override fun getItemViewType(position: Int) = when (items[position]) {
            is TrailItem.Header -> TYPE_HEADER
            is TrailItem.Fix -> TYPE_FIX
            is TrailItem.Event -> TYPE_EVENT
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderVH(inf.inflate(R.layout.item_media_header, parent, false) as TextView)
                TYPE_FIX -> {
                    val v = inf.inflate(R.layout.item_trail_fix, parent, false)
                    FixVH(v, v.findViewById(R.id.tvFixPrimary), v.findViewById(R.id.tvFixSecondary))
                }
                else -> {
                    val v = inf.inflate(R.layout.item_trail_event, parent, false)
                    EventVH(v, v.findViewById(R.id.tvEventPrimary), v.findViewById(R.id.tvEventSecondary))
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is TrailItem.Header -> (holder as HeaderVH).tv.text = item.label
                is TrailItem.Fix -> {
                    val vh = holder as FixVH
                    val p = item.point
                    vh.primary.text = if (p.lat != null && p.lon != null) {
                        "${timeFmt.format(Date(p.ts))} — %.5f, %.5f".format(Locale.US, p.lat, p.lon)
                    } else {
                        timeFmt.format(Date(p.ts))
                    }
                    val bits = mutableListOf<String>()
                    p.mot?.let { bits.add(TrailLabels.motion(this@TrailViewerActivity, it)) }
                    p.acc?.let { bits.add("±%.0fm".format(Locale.US, it)) }
                    p.spd?.let { if (it > 0.1) bits.add("%.1f m/s".format(Locale.US, it)) }
                    vh.secondary.text = bits.joinToString(" · ")
                    vh.itemView.setOnClickListener {
                        TrailPointDetailSheet.newInstance(p).show(supportFragmentManager, "trail_point_detail")
                    }
                }
                is TrailItem.Event -> {
                    val vh = holder as EventVH
                    val p = item.point
                    vh.primary.text = TrailLabels.event(this@TrailViewerActivity, p.ev)
                    vh.secondary.text = timeFmt.format(Date(p.ts))
                    vh.itemView.setOnClickListener {
                        TrailPointDetailSheet.newInstance(p).show(supportFragmentManager, "trail_point_detail")
                    }
                }
            }
        }

        inner class HeaderVH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        inner class FixVH(view: View, val primary: TextView, val secondary: TextView) : RecyclerView.ViewHolder(view)
        inner class EventVH(view: View, val primary: TextView, val secondary: TextView) : RecyclerView.ViewHolder(view)
    }
}
