package com.fshu.next.ui.trail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityTrailAccessLogBinding
import com.fshu.next.databinding.ItemTrailAccessBinding
import com.fshu.next.trail.AccessLogStore
import com.fshu.next.util.Prefs
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chunk 4 — tracked-side "who viewed my trail" screen. Reads [AccessLogStore] (accumulated
 * from trail-accessed pushes) and lists each fetch: who, when, and the range they pulled.
 */
class TrailAccessLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrailAccessLogBinding
    private val whenFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
    private val rangeFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    private val wsHandler: (JsonObject) -> Unit = { json ->
        if (json.get("type")?.asString == "trail-accessed") runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrailAccessLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.trail_access_title)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onResume() {
        super.onResume()
        WebSocketClient.addHandler(wsHandler)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        WebSocketClient.removeHandler(wsHandler)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_trail_access_log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_clear_access_log -> { confirmClear(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun display(username: String): String =
        Prefs.getContactNickname(this, username).ifEmpty { username }

    private fun refresh() {
        val entries = AccessLogStore.getAllNewestFirst(this)
        binding.tvAccessEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.llAccess.removeAllViews()
        for (e in entries) {
            val row = ItemTrailAccessBinding.inflate(LayoutInflater.from(this), binding.llAccess, false)
            row.tvAccessPrimary.text = getString(R.string.trail_access_row_primary, display(e.by))
            val range = if (e.fromTs > 0 && e.toTs > 0)
                "${rangeFmt.format(Date(e.fromTs))} – ${rangeFmt.format(Date(e.toTs))}" else ""
            row.tvAccessSecondary.text =
                if (range.isEmpty()) whenFmt.format(Date(e.at))
                else "${whenFmt.format(Date(e.at))} · $range"
            binding.llAccess.addView(row.root)
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.trail_access_clear))
            .setMessage(getString(R.string.trail_access_clear_confirm))
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setPositiveButton(getString(R.string.trail_access_clear)) { _, _ ->
                AccessLogStore.clear(this); refresh()
            }
            .show()
    }
}
