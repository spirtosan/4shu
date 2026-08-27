package com.fshu.next.ui.trail

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonObject
import com.fshu.next.R
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityTrailSettingsBinding
import com.fshu.next.databinding.ItemTrailGuardianBinding
import com.fshu.next.service.TrailService
import com.fshu.next.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SPEC_T13.md §6 (Block D). Trail's own settings screen — master enable behind the
 * staged [TrailPermissionActivity] walkthrough, status card, guardian picker (local
 * only — §6.2's grant/accept wire protocol is Phase 2/3, this screen says so), and
 * one-tap disable (stop service + local wipe, §6.5; server-side trail-wipe rides
 * Phase 2/3 same as the guardian wire).
 */
class TrailSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrailSettingsBinding
    private val db by lazy { AppDatabase.getInstance(this) }

    // Chunk 1 — surface server-side guardian errors (cap / not-mutual). Registered in
    // onResume, removed in onPause. WS callbacks arrive off the main thread.
    private val trailWsHandler: (JsonObject) -> Unit = { json ->
        if (json.get("type")?.asString == "trail-error") {
            val reason = json.get("reason")?.asString
            val guardian = json.get("guardian")?.asString
            when (reason) {
                "guardian-cap" -> runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_trail_guardian_cap, maxGuardians), Toast.LENGTH_LONG).show()
                }
                "not-mutual-contact", "bad-guardian" -> runOnUiThread {
                    if (guardian != null) Prefs.setTrailGuardians(this, Prefs.getTrailGuardians(this) - guardian)
                    Toast.makeText(this, getString(R.string.toast_trail_guardian_grant_failed, guardian ?: ""), Toast.LENGTH_LONG).show()
                    refreshGuardianList()
                }
                else -> { /* ignore other trail-* traffic */ }
            }
        } else if (json.get("type")?.asString == "trail-guardian-changed") {
            runOnUiThread { refreshGuardWardsCard() }
        } else if (json.get("type")?.asString == "trail-accessed") {
            runOnUiThread { refreshTrailAccessCard() }
        }
    }

    // Cap per SPEC_T13.md §1.5 (Locked decision 5).
    private val maxGuardians = 5

    // B.1.4: how long since the last real fix (or since enabling, if none yet) before
    // the status card stops calling itself "collecting" and shows a warning instead.
    // Generous relative to TrailService's own STILL_INTERVAL_MS (20 min) to absorb one
    // missed heartbeat without false-alarming on ordinary STILL behavior.
    private val staleThresholdMs = 45 * 60 * 1000L

    private val permissionWalkthroughLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            enableTrail()
        } else {
            binding.switchTrailEnabled.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrailSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.label_trail)
            setDisplayHomeAsUpEnabled(true)
        }

        binding.switchTrailEnabled.setOnCheckedChangeListener { switchView, checked ->
            if (!switchView.isPressed) return@setOnCheckedChangeListener // programmatic set, not a real user tap
            if (checked) {
                permissionWalkthroughLauncher.launch(Intent(this, TrailPermissionActivity::class.java))
            } else {
                confirmDisable()
            }
        }

        binding.btnAddGuardian.setOnClickListener { showGuardianPicker() }

        binding.rowViewTrail.setOnClickListener {
            startActivity(Intent(this, TrailViewerActivity::class.java))
        }

        binding.rowGuardWards.setOnClickListener {
            startActivity(Intent(this, GuardianWardsActivity::class.java))
        }

        binding.rowTrailAccess.setOnClickListener {
            startActivity(Intent(this, TrailAccessLogActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        binding.switchTrailEnabled.isChecked = Prefs.isTrailEnabled(this)
        refreshStatusSection()
        refreshViewTrailRow()
        WebSocketClient.addHandler(trailWsHandler)
        refreshGuardWardsCard()
        refreshTrailAccessCard()
    }

    override fun onPause() {
        super.onPause()
        WebSocketClient.removeHandler(trailWsHandler)
    }

    // Chunk 2 — guardian-side card: visible when I have any ward requests/relationships.
    // Chunk 4 — "Who viewed my trail" card: visible once anyone has fetched my trail.
    private fun refreshTrailAccessCard() {
        val has = com.fshu.next.trail.AccessLogStore.getAll(this).isNotEmpty()
        binding.cardTrailAccess.visibility = if (has) View.VISIBLE else View.GONE
    }

    private fun refreshGuardWardsCard() {
        val pending = Prefs.getTrailWardsPending(this).size
        val accepted = Prefs.getTrailWardsAccepted(this).size
        binding.cardGuardWards.visibility = if (pending + accepted > 0) View.VISIBLE else View.GONE
        if (pending > 0) {
            binding.tvGuardWardsBadge.visibility = View.VISIBLE
            binding.tvGuardWardsBadge.text = pending.toString()
        } else {
            binding.tvGuardWardsBadge.visibility = View.GONE
        }
    }

    // Block E: "View my trail" stays visible whenever Trail is enabled, or points
    // still exist locally (e.g. left over from a prior debug/collection session).
    private fun refreshViewTrailRow() {
        lifecycleScope.launch(Dispatchers.IO) {
            val hasPoints = db.trailDao().getCount() > 0
            withContext(Dispatchers.Main) {
                binding.cardViewTrail.visibility =
                    if (Prefs.isTrailEnabled(this@TrailSettingsActivity) || hasPoints) View.VISIBLE else View.GONE
            }
        }
    }

    private fun enableTrail() {
        Prefs.setTrailEnabled(this, true)
        Prefs.setTrailEnabledAt(this, System.currentTimeMillis())
        Prefs.resetTrailRestartCount(this)
        ContextCompat.startForegroundService(this, Intent(this, TrailService::class.java))
        binding.switchTrailEnabled.isChecked = true
        refreshStatusSection()
    }

    private fun confirmDisable() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_trail_disable_title))
            .setMessage(getString(R.string.dialog_trail_disable_message))
            .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                binding.switchTrailEnabled.isChecked = true
            }
            .setPositiveButton(getString(R.string.btn_trail_disable)) { _, _ -> disableTrail() }
            .setOnCancelListener { binding.switchTrailEnabled.isChecked = true }
            .show()
    }

    private fun disableTrail() {
        stopService(Intent(this, TrailService::class.java))
        Prefs.setTrailEnabled(this, false)
        lifecycleScope.launch(Dispatchers.IO) {
            db.trailDao().deleteAll()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@TrailSettingsActivity, getString(R.string.toast_trail_disabled), Toast.LENGTH_SHORT).show()
                refreshStatusSection()
                refreshViewTrailRow()
            }
        }
    }

    private fun refreshStatusSection() {
        val enabled = Prefs.isTrailEnabled(this)
        binding.sectionStatus.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return

        val df = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        val enabledAt = Prefs.getTrailEnabledAt(this)
        binding.tvStatusSince.text = getString(
            R.string.trail_status_since,
            if (enabledAt > 0) df.format(Date(enabledAt)) else "—"
        )
        binding.tvStatusRestarts.text = getString(R.string.trail_status_restarts, Prefs.getTrailRestartCount(this))

        val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        lifecycleScope.launch(Dispatchers.IO) {
            val count = db.trailDao().getCount()
            val oldest = db.trailDao().getOldestTs()
            val newest = db.trailDao().getNewestTs()
            val newestFix = db.trailDao().getNewestFixTs()
            withContext(Dispatchers.Main) {
                binding.tvStatusPoints.text = getString(R.string.trail_status_points, count)
                binding.tvStatusRange.text = if (oldest != null && newest != null) {
                    getString(R.string.trail_status_range, df.format(Date(oldest)), df.format(Date(newest)))
                } else {
                    getString(R.string.trail_status_range_empty)
                }
                refreshHealthWarning(enabledAt, newestFix, backgroundGranted)
            }
        }
        refreshGuardianList()
    }

    // B.1.4: an "enabled" toggle only reflects Prefs.isTrailEnabled() — recorded
    // intent, not whether TrailService is actually alive and producing fixes (the
    // exact gap that let a real-device outage read as healthy for 7 days). Warn
    // explicitly instead of letting the status card imply collection is happening
    // when the data doesn't back that up. See SPEC_T13.md's Block B.1.4.
    private fun refreshHealthWarning(enabledAt: Long, newestFixTs: Long?, backgroundGranted: Boolean) {
        val now = System.currentTimeMillis()
        val gracePassed = enabledAt > 0 && now - enabledAt >= staleThresholdMs
        val stale = gracePassed && (newestFixTs == null || now - newestFixTs >= staleThresholdMs)
        val warning = when {
            stale -> getString(R.string.trail_status_warning_stale)
            !backgroundGranted -> getString(R.string.trail_status_warning_no_background)
            else -> null
        }
        binding.tvStatusHealth.text = warning
        binding.tvStatusHealth.visibility = if (warning != null) View.VISIBLE else View.GONE
    }

    // --- guardians (local-only, §6.2) ---

    private fun refreshGuardianList() {
        val guardians = Prefs.getTrailGuardians(this).sorted()
        binding.llGuardians.removeAllViews()
        binding.tvGuardiansEmpty.visibility = if (guardians.isEmpty()) View.VISIBLE else View.GONE
        for (username in guardians) {
            val row = ItemTrailGuardianBinding.inflate(LayoutInflater.from(this), binding.llGuardians, false)
            row.tvGuardianUsername.text = Prefs.getContactNickname(this, username).ifEmpty { username }
            row.btnRemoveGuardian.setOnClickListener {
                Prefs.setTrailGuardians(this, Prefs.getTrailGuardians(this) - username)
                WebSocketClient.send(mapOf("type" to "trail-revoke", "guardian" to username))   // Chunk 1 — revoke wire
                refreshGuardianList()
            }
            binding.llGuardians.addView(row.root)
        }
        binding.btnAddGuardian.isEnabled = guardians.size < maxGuardians
    }

    private fun showGuardianPicker() {
        val me = Prefs.getUsername(this)
        val current = Prefs.getTrailGuardians(this)
        if (current.size >= maxGuardians) {
            Toast.makeText(this, getString(R.string.toast_trail_guardian_cap, maxGuardians), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            // Guardians are mutual contacts only (§1.5) — same "accepted" status the
            // rest of the app treats as a real contact relationship.
            val candidates = db.contactDao().getAcceptedContacts(me)
                .map { it.contact }
                .filter { it !in current }
                .sorted()
            withContext(Dispatchers.Main) {
                if (candidates.isEmpty()) {
                    Toast.makeText(this@TrailSettingsActivity, getString(R.string.toast_trail_no_guardian_candidates), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val names = candidates.map { Prefs.getContactNickname(this@TrailSettingsActivity, it).ifEmpty { it } }.toTypedArray()
                MaterialAlertDialogBuilder(this@TrailSettingsActivity)
                    .setTitle(getString(R.string.btn_add_guardian))
                    .setItems(names) { _, index ->
                        val g = candidates[index]
                        Prefs.setTrailGuardians(this@TrailSettingsActivity, Prefs.getTrailGuardians(this@TrailSettingsActivity) + g)
                        WebSocketClient.send(mapOf("type" to "trail-grant", "guardian" to g))   // Chunk 1 — grant wire
                        refreshGuardianList()
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
