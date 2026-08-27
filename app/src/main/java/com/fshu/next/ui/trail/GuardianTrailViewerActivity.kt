package com.fshu.next.ui.trail

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fshu.next.R
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityGuardianTrailViewerBinding
import com.fshu.next.trail.GuardianTrail
import com.fshu.next.trail.TrailExport
import com.fshu.next.trail.TrailPointData
import com.fshu.next.trail.TrailPointKind
import com.fshu.next.util.LocationHelper
import com.fshu.next.util.Prefs
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Chunk 3 — guardian trail viewer.
 *
 * Fetches a tracked person's ciphertext batches (`trail-fetch` -> `trail-data`), decrypts
 * and merges them with [GuardianTrail.assemble], and shows the LAST KNOWN position first
 * (card + Open in Maps), then the full reverse-chronological path. Exports GPX/JSON via the
 * Storage Access Framework (works on all API levels, no storage permission).
 */
class GuardianTrailViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USER = "tracked_user"
    }

    private lateinit var binding: ActivityGuardianTrailViewerBinding
    private lateinit var trackedUser: String
    private val gson = Gson()
    private var assembled: GuardianTrail.Assembled? = null
    private var pendingExport: String? = null

    private val gpxLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri -> writePending(uri) }

    private val jsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> writePending(uri) }

    private val wsHandler: (JsonObject) -> Unit = { json ->
        when (json.get("type")?.asString) {
            "trail-data" -> {
                if (json.get("user")?.asString?.lowercase() == trackedUser.lowercase()) {
                    val batches = json.getAsJsonArray("batches")
                    handleData(batches)
                }
            }
            "trail-error" -> {
                val reason = json.get("reason")?.asString
                if (reason == "not-guardian") runOnUiThread {
                    showMessage(getString(R.string.guardian_trail_not_guardian))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianTrailViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        trackedUser = intent.getStringExtra(EXTRA_USER)?.lowercase() ?: run { finish(); return }
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = Prefs.getContactNickname(this@GuardianTrailViewerActivity, trackedUser).ifEmpty { trackedUser }
            subtitle = getString(R.string.guardian_trail_subtitle)
            setDisplayHomeAsUpEnabled(true)
        }
        binding.rvTrail.layoutManager = LinearLayoutManager(this)
    }

    override fun onStart() {
        super.onStart()
        WebSocketClient.addHandler(wsHandler)
        fetch()
    }

    override fun onStop() {
        super.onStop()
        WebSocketClient.removeHandler(wsHandler)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_guardian_trail_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_refresh -> { fetch(); true }
        R.id.action_export_gpx -> { exportGpx(); true }
        R.id.action_export_json -> { exportJson(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun fetch() {
        if (!WebSocketClient.isConnected) {
            showMessage(getString(R.string.guardian_trail_offline))
            return
        }
        binding.progress.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        WebSocketClient.send(mapOf("type" to "trail-fetch", "user" to trackedUser))
    }

    private fun handleData(batchesJson: com.google.gson.JsonArray?) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                val myPriv = Prefs.getEcPrivateKey(this@GuardianTrailViewerActivity)
                val me = Prefs.getUsername(this@GuardianTrailViewerActivity)
                val pub = AppDatabase.getInstance(this@GuardianTrailViewerActivity)
                    .peerKeyDao().get(trackedUser)?.publicKey
                if (myPriv.isEmpty() || pub.isNullOrEmpty()) return@withContext null
                val batches = if (batchesJson == null) emptyList()
                    else gson.fromJson(batchesJson, Array<GuardianTrail.Batch>::class.java).toList()
                GuardianTrail.assemble(myPriv, me, trackedUser, pub, batches)
            }
            assembled = result
            render(result)
        }
    }

    private fun render(a: GuardianTrail.Assembled?) {
        binding.progress.visibility = View.GONE
        if (a == null) { showMessage(getString(R.string.guardian_trail_no_key)); return }

        // Last-known card first (the guardian's priority).
        val last = a.lastKnownFix
        if (last?.lat != null && last.lon != null) {
            binding.cardLastKnown.visibility = View.VISIBLE
            binding.tvLastKnownCoords.text = "%.5f, %.5f".format(Locale.US, last.lat, last.lon)
            val fmt = SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.getDefault())
            val meta = StringBuilder(fmt.format(Date(last.ts)))
            last.acc?.let { meta.append(" · ±%.0fm".format(Locale.US, it)) }
            binding.tvLastKnownMeta.text = meta.toString()
            val mapsUrl = LocationHelper.buildMapsUrl(last.lat, last.lon)
            binding.btnOpenMaps.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl)))
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.toast_maps_not_available), Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            binding.cardLastKnown.visibility = View.GONE
        }

        binding.tvSummary.visibility = View.VISIBLE
        binding.tvSummary.text = getString(
            R.string.guardian_trail_summary, a.totalPoints, a.decryptedBatches, a.failedBatches
        )

        if (a.points.isEmpty()) {
            binding.rvTrail.visibility = View.GONE
            showMessage(getString(R.string.guardian_trail_empty))
            return
        }
        binding.tvEmpty.visibility = View.GONE
        binding.rvTrail.visibility = View.VISIBLE
        // Reverse-chronological (newest first).
        val points = a.points.map { it.data }.sortedByDescending { it.ts }
        binding.rvTrail.adapter = TrailAdapter(buildTrailItems(points))
    }

    private fun showMessage(text: String) {
        binding.progress.visibility = View.GONE
        binding.rvTrail.visibility = View.GONE
        binding.tvEmpty.visibility = View.VISIBLE
        binding.tvEmpty.text = text
    }

    // ── Export (SAF) ──────────────────────────────────────────────────────────

    private fun exportGpx() {
        val a = assembled ?: return exportNothing()
        pendingExport = TrailExport.toGpx(a.points, trackedUser)
        gpxLauncher.launch(TrailExport.fileStem(trackedUser) + ".gpx")
    }

    private fun exportJson() {
        val a = assembled ?: return exportNothing()
        pendingExport = TrailExport.toJson(a, trackedUser)
        jsonLauncher.launch(TrailExport.fileStem(trackedUser) + ".json")
    }

    private fun exportNothing() {
        Toast.makeText(this, getString(R.string.guardian_trail_nothing_to_export), Toast.LENGTH_SHORT).show()
    }

    private fun writePending(uri: Uri?) {
        val content = pendingExport
        pendingExport = null
        if (uri == null || content == null) return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    true
                } catch (e: Exception) { false }
            }
            Toast.makeText(
                this@GuardianTrailViewerActivity,
                getString(if (ok) R.string.guardian_trail_export_ok else R.string.guardian_trail_export_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ── List (day-grouped, mirrors TrailViewerActivity) ────────────────────────

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
            if (label != currentLabel) { result.add(TrailItem.Header(label)); currentLabel = label }
            result.add(if (point.kind == TrailPointKind.EVENT) TrailItem.Event(point) else TrailItem.Fix(point))
        }
        return result
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    sealed class TrailItem {
        data class Header(val label: String) : TrailItem()
        data class Fix(val point: TrailPointData) : TrailItem()
        data class Event(val point: TrailPointData) : TrailItem()
    }

    private inner class TrailAdapter(private val items: List<TrailItem>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        private val tHeader = 0; private val tFix = 1; private val tEvent = 2

        override fun getItemViewType(position: Int) = when (items[position]) {
            is TrailItem.Header -> tHeader
            is TrailItem.Fix -> tFix
            is TrailItem.Event -> tEvent
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                tHeader -> HeaderVH(inf.inflate(R.layout.item_media_header, parent, false) as TextView)
                tFix -> {
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
                    vh.primary.text = if (p.lat != null && p.lon != null)
                        "${timeFmt.format(Date(p.ts))} — %.5f, %.5f".format(Locale.US, p.lat, p.lon)
                    else timeFmt.format(Date(p.ts))
                    val bits = mutableListOf<String>()
                    p.mot?.let { bits.add(TrailLabels.motion(this@GuardianTrailViewerActivity, it)) }
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
                    vh.primary.text = TrailLabels.event(this@GuardianTrailViewerActivity, p.ev)
                    vh.secondary.text = timeFmt.format(Date(p.ts))
                    vh.itemView.setOnClickListener {
                        TrailPointDetailSheet.newInstance(p).show(supportFragmentManager, "trail_point_detail")
                    }
                }
            }
        }
    }

    private class HeaderVH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    private class FixVH(v: View, val primary: TextView, val secondary: TextView) : RecyclerView.ViewHolder(v)
    private class EventVH(v: View, val primary: TextView, val secondary: TextView) : RecyclerView.ViewHolder(v)
}
