package com.fshu.next.ui.trail

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityGuardianWardsBinding
import com.fshu.next.databinding.ItemTrailWardBinding
import com.fshu.next.trail.GuardianRegistry
import com.fshu.next.util.Prefs
import com.google.gson.JsonObject

/**
 * Chunk 2 — guardian-side management screen.
 *
 * Shows two groups derived from [Prefs] ward lists (maintained by [GuardianRegistry]):
 *  - Requests: people who granted me guardianship but I haven't accepted -> Accept / Decline.
 *  - People I guard: accepted wards -> Stop guarding (Chunk 3 adds "View trail" here).
 *
 * Accept sends `trail-accept`; Decline / Stop send `trail-revoke {user}`. A lightweight WS
 * handler live-refreshes the lists while the screen is open.
 */
class GuardianWardsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuardianWardsBinding

    private val wsHandler: (JsonObject) -> Unit = { json ->
        if (json.get("type")?.asString == "trail-guardian-changed") {
            runOnUiThread { refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianWardsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.guardian_wards_title)
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun display(username: String): String =
        Prefs.getContactNickname(this, username).ifEmpty { username }

    private fun refresh() {
        val pending = Prefs.getTrailWardsPending(this).sorted()
        val accepted = Prefs.getTrailWardsAccepted(this).sorted()

        binding.tvRequestsEmpty.visibility = if (pending.isEmpty()) View.VISIBLE else View.GONE
        binding.llRequests.removeAllViews()
        for (user in pending) {
            val row = ItemTrailWardBinding.inflate(LayoutInflater.from(this), binding.llRequests, false)
            row.tvWardUsername.text = display(user)
            row.btnWardPrimary.visibility = View.VISIBLE
            row.btnWardPrimary.text = getString(R.string.guardian_wards_accept)
            row.btnWardPrimary.setOnClickListener { accept(user) }
            row.btnWardSecondary.contentDescription = getString(R.string.guardian_wards_decline)
            row.btnWardSecondary.setOnClickListener { revoke(user, declined = true) }
            binding.llRequests.addView(row.root)
        }

        binding.tvWardsEmpty.visibility = if (accepted.isEmpty()) View.VISIBLE else View.GONE
        binding.llWards.removeAllViews()
        for (user in accepted) {
            val row = ItemTrailWardBinding.inflate(LayoutInflater.from(this), binding.llWards, false)
            row.tvWardUsername.text = display(user)
            row.btnWardPrimary.visibility = View.VISIBLE
            row.btnWardPrimary.text = getString(R.string.guardian_wards_view_trail)
            row.btnWardPrimary.setOnClickListener {
                startActivity(Intent(this, GuardianTrailViewerActivity::class.java).apply {
                    putExtra(GuardianTrailViewerActivity.EXTRA_USER, user)
                })
            }
            row.btnWardSecondary.contentDescription = getString(R.string.guardian_wards_stop)
            row.btnWardSecondary.setOnClickListener { confirmStop(user) }
            binding.llWards.addView(row.root)
        }
    }

    private fun accept(user: String) {
        WebSocketClient.send(mapOf("type" to "trail-accept", "user" to user))
        GuardianRegistry.markAcceptedLocally(this, user)
        Toast.makeText(this, getString(R.string.guardian_wards_accepted_toast, display(user)), Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun confirmStop(user: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.guardian_wards_stop))
            .setMessage(getString(R.string.guardian_wards_stop_confirm, display(user)))
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setPositiveButton(getString(R.string.guardian_wards_stop)) { _, _ -> revoke(user, declined = false) }
            .show()
    }

    private fun revoke(user: String, declined: Boolean) {
        WebSocketClient.send(mapOf("type" to "trail-revoke", "user" to user))
        GuardianRegistry.removeLocally(this, user)
        val msg = if (declined) getString(R.string.guardian_wards_declined_toast, display(user))
                  else getString(R.string.guardian_wards_stopped_toast, display(user))
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        refresh()
    }
}
