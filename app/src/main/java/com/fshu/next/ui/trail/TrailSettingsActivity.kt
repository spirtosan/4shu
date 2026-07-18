package com.fshu.next.ui.trail

import android.content.Intent
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
import com.fshu.next.R
import com.fshu.next.data.local.AppDatabase
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

    // Cap per SPEC_T13.md §1.5 (Locked decision 5).
    private val maxGuardians = 5

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
    }

    override fun onResume() {
        super.onResume()
        binding.switchTrailEnabled.isChecked = Prefs.isTrailEnabled(this)
        refreshStatusSection()
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

        lifecycleScope.launch(Dispatchers.IO) {
            val count = db.trailDao().getCount()
            val oldest = db.trailDao().getOldestTs()
            val newest = db.trailDao().getNewestTs()
            withContext(Dispatchers.Main) {
                binding.tvStatusPoints.text = getString(R.string.trail_status_points, count)
                binding.tvStatusRange.text = if (oldest != null && newest != null) {
                    getString(R.string.trail_status_range, df.format(Date(oldest)), df.format(Date(newest)))
                } else {
                    getString(R.string.trail_status_range_empty)
                }
            }
        }
        refreshGuardianList()
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
                        Prefs.setTrailGuardians(this@TrailSettingsActivity, Prefs.getTrailGuardians(this@TrailSettingsActivity) + candidates[index])
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
