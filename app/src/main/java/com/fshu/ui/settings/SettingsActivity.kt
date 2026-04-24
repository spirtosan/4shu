package com.fshu.ui.settings

import android.Manifest
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
import com.google.gson.JsonParser
import com.fshu.data.remote.WebSocketClient
import com.fshu.databinding.ActivitySettingsBinding
import com.fshu.service.FshuService
import com.fshu.ui.admin.ChangePasswordDialog
import com.fshu.ui.passphrase.PassphraseSetupActivity
import com.fshu.util.CryptoHelper
import com.fshu.util.Prefs

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "Settings"
            setDisplayHomeAsUpEnabled(true)
        }

        // Server URL
        val defaultUrl = "wss://shumkov.eu/fshu/"
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
                .setTitle("Server URL")
                .setView(wrap)
                .setPositiveButton("Save") { _, _ ->
                    val url = et.text.toString().trim()
                    if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
                        Toast.makeText(this, "URL must start with wss:// or ws://", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    Prefs.setServerUrl(this, url)
                    binding.tvServerUrl.text = url
                    Toast.makeText(this, "Server URL updated — reconnecting", Toast.LENGTH_SHORT).show()
                    startService(Intent(this, FshuService::class.java).apply {
                        action = FshuService.ACTION_RECONNECT
                    })
                }
                .setNeutralButton("Reset") { _, _ ->
                    Prefs.setServerUrl(this, defaultUrl)
                    binding.tvServerUrl.text = defaultUrl
                    Toast.makeText(this, "Reset to default — reconnecting", Toast.LENGTH_SHORT).show()
                    startService(Intent(this, FshuService::class.java).apply {
                        action = FshuService.ACTION_RECONNECT
                    })
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Location sharing toggle
        binding.switchLocationSharing.isChecked = Prefs.getLocationSharingEnabled(this)
        binding.rowLocationSharing.setOnClickListener {
            val currentlyEnabled = Prefs.getLocationSharingEnabled(this)
            if (!currentlyEnabled) {
                AlertDialog.Builder(this)
                    .setTitle("Enable location sharing?")
                    .setMessage("When enabled, Fshu will automatically reply with your current GPS position whenever someone requests your location.\n\nYou can disable this at any time from Settings.")
                    .setPositiveButton("Enable") { _, _ ->
                        Prefs.setLocationSharingEnabled(this, true)
                        binding.switchLocationSharing.isChecked = true
                    }
                    .setNegativeButton("Cancel", null)
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
                Toast.makeText(this, "Push wake-up enabled", Toast.LENGTH_SHORT).show()
            } else {
                // Clear token on server
                if (WebSocketClient.isConnected) {
                    WebSocketClient.send(mapOf("type" to "fcm-token", "token" to ""))
                }
                Toast.makeText(this, "Push wake-up disabled", Toast.LENGTH_SHORT).show()
            }
        }

        // App lock toggle
        binding.switchAppLock.isChecked = Prefs.getAppLockEnabled(this)
        binding.rowAppLock.setOnClickListener {
            val enabled = !Prefs.getAppLockEnabled(this)
            Prefs.setAppLockEnabled(this, enabled)
            binding.switchAppLock.isChecked = enabled
            Toast.makeText(this, if (enabled) "App lock enabled" else "App lock disabled", Toast.LENGTH_SHORT).show()
        }

        // History sync
        binding.rowSyncHistory.setOnClickListener { showGlobalHistoryDialog() }

        // Change password
        binding.tvChangePassword.setOnClickListener {
            ChangePasswordDialog().show(supportFragmentManager, "change_password")
        }

        // Reset encryption passphrase
        binding.tvResetPassphrase.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset encryption passphrase")
                .setMessage("This will clear your stored passphrase. You will need to re-enter it on next open. Messages encrypted with the old passphrase will not be readable until you enter the same passphrase again.")
                .setPositiveButton("Reset") { _, _ ->
                    Prefs.setPassphrase(this, "")
                    CryptoHelper.clearKeyCache()
                    startActivity(Intent(this, PassphraseSetupActivity::class.java))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // App version
        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = pInfo.versionName
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pInfo.longVersionCode
        else
            @Suppress("DEPRECATION") pInfo.versionCode.toLong()
        binding.tvAppVersion.text = "Fshu v$versionName (build $versionCode)"
    }

    private fun showGlobalHistoryDialog() {
        val options = arrayOf("Last 7 days", "Last 30 days", "Last 90 days", "Custom...")
        AlertDialog.Builder(this)
            .setTitle("Sync all chat history")
            .setItems(options) { _, which ->
                val days = when (which) { 0 -> 7; 1 -> 30; 2 -> 90; else -> null }
                if (days != null) syncHistoryForAllPeers(days)
                else {
                    val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "Days (1–90)" }
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    val wrap = FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(et) }
                    AlertDialog.Builder(this)
                        .setTitle("Custom period")
                        .setView(wrap)
                        .setPositiveButton("Sync") { _, _ ->
                            syncHistoryForAllPeers(et.text.toString().toIntOrNull()?.coerceIn(1, 90) ?: 30)
                        }
                        .setNegativeButton("Cancel", null).show()
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
        if (peers.isEmpty()) { Toast.makeText(this, "No known peers", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Syncing history…", Toast.LENGTH_SHORT).show()
        for (peer in peers) {
            WebSocketClient.send(mapOf("type" to "history-request", "from" to me, "to" to peer, "since" to since, "days" to days))
        }
        Toast.makeText(this, "History requests sent", Toast.LENGTH_SHORT).show()
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
