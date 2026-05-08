package com.fshu.next.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.MenuItem
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivitySettingsBinding
import com.fshu.next.service.FshuService
import com.fshu.next.ui.admin.ChangePasswordDialog
import com.fshu.next.ui.login.LoginActivity
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = getString(R.string.menu_settings)
            setDisplayHomeAsUpEnabled(true)
        }

        // Device name
        binding.tvDeviceName.text = Prefs.getDeviceName(this).ifEmpty { Build.MODEL }
        binding.rowDeviceName.setOnClickListener {
            val et = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setText(Prefs.getDeviceName(this@SettingsActivity).ifEmpty { Build.MODEL })
                setSelection(text.length)
            }
            val pad = (16 * resources.displayMetrics.density).toInt()
            val wrap = FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(et) }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_device_name_title))
                .setView(wrap)
                .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                    val name = et.text.toString().trim().ifEmpty { Build.MODEL }
                    Prefs.setDeviceName(this, name)
                    binding.tvDeviceName.text = name
                    WebSocketClient.deviceName = name
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }

        // Server URL
        val defaultUrl = ""
        binding.tvServerUrl.text = Prefs.getServerUrl(this)
        binding.rowServerUrl.setOnClickListener {
            val et = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(Prefs.getServerUrl(this@SettingsActivity))
                setSelection(text.length)
            }
            val pad = (16 * resources.displayMetrics.density).toInt()
            val wrap = FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(et) }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_server_url_title))
                .setView(wrap)
                .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                    val url = et.text.toString().trim()
                    if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
                        Toast.makeText(this, getString(R.string.toast_url_invalid), Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    Prefs.setServerUrl(this, url)
                    binding.tvServerUrl.text = url
                    Toast.makeText(this, getString(R.string.toast_server_url_updated), Toast.LENGTH_SHORT).show()
                    startService(Intent(this, FshuService::class.java).apply {
                        action = FshuService.ACTION_RECONNECT
                    })
                }
                .setNeutralButton(getString(R.string.btn_reset_to_default)) { _, _ ->
                    Prefs.setServerUrl(this, defaultUrl)
                    binding.tvServerUrl.text = defaultUrl
                    Toast.makeText(this, getString(R.string.toast_reset_to_default_reconnecting), Toast.LENGTH_SHORT).show()
                    startService(Intent(this, FshuService::class.java).apply {
                        action = FshuService.ACTION_RECONNECT
                    })
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }

        // Location sharing toggle
        binding.switchLocationSharing.isChecked = Prefs.getLocationSharingEnabled(this)
        binding.rowLocationSharing.setOnClickListener {
            val currentlyEnabled = Prefs.getLocationSharingEnabled(this)
            if (!currentlyEnabled) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_enable_location_title))
                    .setMessage(getString(R.string.dialog_enable_location_message))
                    .setPositiveButton(getString(R.string.btn_enable)) { _, _ ->
                        Prefs.setLocationSharingEnabled(this, true)
                        binding.switchLocationSharing.isChecked = true
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            } else {
                Prefs.setLocationSharingEnabled(this, false)
                binding.switchLocationSharing.isChecked = false
            }
        }

        // FCM push wake-up toggle
        binding.switchFcm.isChecked = Prefs.getFcmEnabled(this)
        binding.rowFcm.setOnClickListener {
            val enabled = !Prefs.getFcmEnabled(this)
            Prefs.setFcmEnabled(this, enabled)
            binding.switchFcm.isChecked = enabled
            if (enabled) {
                // Send stored token to server
                val token = Prefs.getFcmToken(this)
                if (token.isNotEmpty() && WebSocketClient.isConnected) {
                    WebSocketClient.send(mapOf("type" to "fcm-token", "token" to token))
                }
                Toast.makeText(this, getString(R.string.toast_push_enabled), Toast.LENGTH_SHORT).show()
            } else {
                // Clear token on server
                if (WebSocketClient.isConnected) {
                    WebSocketClient.send(mapOf("type" to "fcm-token", "token" to ""))
                }
                Toast.makeText(this, getString(R.string.toast_push_disabled), Toast.LENGTH_SHORT).show()
            }
        }

        // App lock toggle
        binding.switchAppLock.isChecked = Prefs.getAppLockEnabled(this)
        binding.rowAppLock.setOnClickListener {
            val enabled = !Prefs.getAppLockEnabled(this)
            Prefs.setAppLockEnabled(this, enabled)
            binding.switchAppLock.isChecked = enabled
            Toast.makeText(this, if (enabled) getString(R.string.toast_app_lock_enabled) else getString(R.string.toast_app_lock_disabled), Toast.LENGTH_SHORT).show()
        }

        // History sync
        binding.rowSyncHistory.setOnClickListener { showGlobalHistoryDialog() }

        // Change password
        binding.tvChangePassword.setOnClickListener {
            ChangePasswordDialog().show(supportFragmentManager, "change_password")
        }

        // App version
        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = pInfo.versionName
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pInfo.longVersionCode
        else
            @Suppress("DEPRECATION") pInfo.versionCode.toLong()
        binding.tvAppVersion.text = getString(R.string.app_version_format, versionName, versionCode.toString())

        // Export my data
        binding.btnExportData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Export your data?")
                .setMessage("A download link will be generated and copied to your clipboard. Messages are exported encrypted — only readable in the app.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Export") { _, _ ->
                    lifecycleScope.launch {
                        val ch = Channel<JsonObject>(1)
                        val job = launch {
                            MessageBus.events.collect {
                                if (it.get("type")?.asString == "export-ready") ch.trySend(it)
                            }
                        }
                        WebSocketClient.send(mapOf("type" to "export-request"))
                        val result = withTimeoutOrNull(10_000) { ch.receive() }
                        job.cancel()
                        if (result == null) {
                            Toast.makeText(this@SettingsActivity, "Export failed", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val url = result.get("url")?.asString ?: ""
                        val clipboard = getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("export", url))
                        Toast.makeText(this@SettingsActivity, "Export link copied! Valid for 48 hours.", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
        }

        // Delete account
        binding.btnDeleteAccount.setOnClickListener {
            val et = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "Password"
            }
            val pad = (16 * resources.displayMetrics.density).toInt()
            val wrap = FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(et) }
            val dialog = AlertDialog.Builder(this)
                .setTitle("Delete account")
                .setMessage("This cannot be undone. Your messages will be anonymized. Enter your password to confirm.")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", null)
                .show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(0xFFE53935.toInt())
                setOnClickListener {
                    val password = et.text.toString()
                    if (password.isEmpty()) { et.error = "Enter your password"; return@setOnClickListener }
                    dialog.dismiss()
                    lifecycleScope.launch {
                        val ch = Channel<JsonObject>(1)
                        val job = launch {
                            MessageBus.events.collect {
                                val t = it.get("type")?.asString
                                if (t == "delete-account-ok" || t == "delete-account-error") ch.trySend(it)
                            }
                        }
                        WebSocketClient.send(mapOf("type" to "delete-account", "currentPassword" to password))
                        val result = withTimeoutOrNull(10_000) { ch.receive() }
                        job.cancel()
                        when {
                            result == null ->
                                Toast.makeText(this@SettingsActivity, "Request timed out", Toast.LENGTH_SHORT).show()
                            result.get("type")?.asString == "delete-account-error" ->
                                Toast.makeText(this@SettingsActivity, result.get("message")?.asString ?: "Error", Toast.LENGTH_LONG).show()
                            else -> {
                                getSharedPreferences("fshu_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                                deleteSharedPreferences("fshu_secure_prefs")
                                val intent = Intent(this@SettingsActivity, LoginActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(intent)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showGlobalHistoryDialog() {
        val options = arrayOf(
            getString(R.string.history_option_7_days),
            getString(R.string.history_option_30_days),
            getString(R.string.history_option_90_days),
            getString(R.string.history_option_custom)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_sync_history_title))
            .setItems(options) { _, which ->
                val days = when (which) { 0 -> 7; 1 -> 30; 2 -> 90; else -> null }
                if (days != null) syncHistoryForAllPeers(days)
                else {
                    val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = getString(R.string.hint_days) }
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    val wrap = FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(et) }
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.dialog_custom_period_title))
                        .setView(wrap)
                        .setPositiveButton(getString(R.string.btn_sync)) { _, _ ->
                            syncHistoryForAllPeers(et.text.toString().toIntOrNull()?.coerceIn(1, 90) ?: 30)
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null).show()
                }
            }
            .show()
    }

    private fun syncHistoryForAllPeers(days: Int) {
        val me = Prefs.getUsername(this)
        val since = System.currentTimeMillis() - days * 86_400_000L
        val peers = try {
            JsonParser.parseString(Prefs.getCachedUsers(this)).asJsonArray
                .mapNotNull { it.asJsonObject.get("username")?.asString }
                .filter { it != me && !it.startsWith("_") }
        } catch (e: Exception) { emptyList() }
        if (peers.isEmpty()) { Toast.makeText(this, getString(R.string.toast_no_known_peers), Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, getString(R.string.toast_syncing_history), Toast.LENGTH_SHORT).show()
        for (peer in peers) {
            WebSocketClient.send(mapOf("type" to "history-request", "from" to me, "to" to peer, "since" to since, "days" to days))
        }
        Toast.makeText(this, getString(R.string.toast_history_requests_sent), Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        binding.permissionsContainer.removeAllViews()

        val dp = resources.displayMetrics.density
        val p16 = (16 * dp).toInt()

        val p8 = (8 * dp).toInt()

        data class PermRow(val name: String, val granted: Boolean)

        val rows = mutableListOf<PermRow>()

        rows += PermRow("Notifications",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true)

        rows += PermRow("Microphone",
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)

        rows += PermRow("Camera",
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)

        rows += PermRow("Location",
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)

        val pm = getSystemService(PowerManager::class.java)
        rows += PermRow("Battery optimization (disabled)",
            pm.isIgnoringBatteryOptimizations(packageName))

        rows += PermRow("Display over other apps",
            Settings.canDrawOverlays(this))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            rows += PermRow("Full-screen calls", nm.canUseFullScreenIntent())
        }

        for (row in rows) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(p16, p8, p16, p8)
            }

            val nameView = TextView(this).apply {
                text = row.name
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val statusView = TextView(this).apply {
                text = if (row.granted) "✓" else "✗ Open Settings"
                textSize = 13f
                setTextColor(
                    if (row.granted) 0xFF4CAF50.toInt() else 0xFFE53935.toInt()
                )
                if (!row.granted) {
                    setOnClickListener {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    }
                }
            }

            container.addView(nameView)
            container.addView(statusView)
            binding.permissionsContainer.addView(container)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
