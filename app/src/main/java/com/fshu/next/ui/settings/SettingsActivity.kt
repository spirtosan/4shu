package com.fshu.next.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.fshu.next.R
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.util.CryptoHelper
import com.fshu.next.databinding.ActivitySettingsBinding
import com.fshu.next.databinding.ItemDeviceBinding
import com.fshu.next.service.FshuService
import com.fshu.next.ui.admin.ChangePasswordDialog
import com.fshu.next.ui.contacts.RequestsActivity
import com.fshu.next.ui.login.LoginActivity
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    data class DeviceItem(val deviceId: String, val deviceName: String?, val lastSeen: Long, val isCurrent: Boolean)

    private val deviceItems = mutableListOf<DeviceItem>()
    private lateinit var deviceAdapter: DeviceAdapter

    inner class DeviceAdapter(
        private val items: List<DeviceItem>,
        private val onRemove: (DeviceItem) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {
        inner class VH(val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            holder.b.tvDeviceItemName.text = if (item.isCurrent)
                "⭐ ${item.deviceName ?: item.deviceId.take(8)}"
            else
                item.deviceName ?: item.deviceId.take(8)
            holder.b.tvDeviceItemLastSeen.text = if (item.lastSeen > 0)
                sdf.format(Date(item.lastSeen))
            else
                "Never seen"
            holder.b.btnRemoveDevice.visibility = if (item.isCurrent) View.GONE else View.VISIBLE
            holder.b.btnRemoveDevice.setOnClickListener { onRemove(item) }
        }
    }

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
            if (enabled) showAppLockTimeoutPicker()
        }

        // Devices
        deviceAdapter = DeviceAdapter(deviceItems) { item ->
            lifecycleScope.launch {
                val ch = Channel<JsonObject>(1)
                val job = launch {
                    MessageBus.events.collect {
                        if (it.get("type")?.asString == "device-list") ch.trySend(it)
                    }
                }
                WebSocketClient.send(mapOf("type" to "device-remove", "deviceId" to item.deviceId))
                val result = withTimeoutOrNull(5_000) { ch.receive() }
                job.cancel()
                result?.let { updateDeviceList(it) }
            }
        }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = deviceAdapter
            isNestedScrollingEnabled = false
        }

        binding.btnRenameDevice.setOnClickListener {
            val currentName = Prefs.getDeviceName(this).ifEmpty { Build.MODEL }
            val et = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setText(currentName)
                setSelection(text.length)
            }
            val pad = (16 * resources.displayMetrics.density).toInt()
            val wrap = FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(et) }
            AlertDialog.Builder(this)
                .setTitle("Rename this device")
                .setView(wrap)
                .setPositiveButton("Save") { _, _ ->
                    val newName = et.text.toString().trim().ifEmpty { Build.MODEL }
                    Prefs.setDeviceName(this, newName)
                    WebSocketClient.deviceName = newName
                    lifecycleScope.launch {
                        val ch = Channel<JsonObject>(1)
                        val job = launch {
                            MessageBus.events.collect {
                                if (it.get("type")?.asString == "device-list") ch.trySend(it)
                            }
                        }
                        WebSocketClient.send(mapOf("type" to "device-rename", "deviceName" to newName))
                        val result = withTimeoutOrNull(5_000) { ch.receive() }
                        job.cancel()
                        result?.let { updateDeviceList(it) }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // My Profile
        binding.rowMyProfile.setOnClickListener {
            startActivity(Intent(this, MyProfileActivity::class.java))
        }

        // Contact Requests
        binding.rowContactRequests.setOnClickListener {
            startActivity(Intent(this, RequestsActivity::class.java))
        }

        // Privacy Settings
        binding.rowPrivacySettings.setOnClickListener {
            startActivity(Intent(this, PrivacySettingsActivity::class.java))
        }

        // Blocked Users
        binding.rowBlockedUsers.setOnClickListener {
            startActivity(Intent(this, BlockListActivity::class.java))
        }

        // Change password
        binding.tvChangePassword.setOnClickListener {
            ChangePasswordDialog().show(supportFragmentManager, "change_password")
        }

        binding.tvAppVersion.text = "v${com.fshu.next.BuildConfig.VERSION_NAME} · ${com.fshu.next.BuildConfig.BUILD_TIME}"

        // Export my data
        binding.btnExportData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.export_data_title))
                .setMessage(getString(R.string.export_data_message_local))
                .setPositiveButton(getString(R.string.export_confirm)) { _, _ ->
                    performLocalExport()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
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
                setOnClickListener deleteBtn@{
                    val password = et.text.toString()
                    if (password.isEmpty()) { et.error = "Enter your password"; return@deleteBtn }
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

        binding.btnGroupDebugLog.setOnClickListener {
            val file = java.io.File(filesDir, "group_debug.txt")
            val text = if (file.exists()) file.readText().takeLast(8000) else "No group debug log found."
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Group Debug Log")
                .setMessage(text)
                .setPositiveButton("Copy") { _, _ ->
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("group debug", text))
                }
                .setNegativeButton("Clear") { _, _ -> file.delete() }
                .setNeutralButton("Close", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshDevices()
        refreshRequestsBadge()
    }

    private fun refreshRequestsBadge() {
        lifecycleScope.launch {
            val ch = kotlinx.coroutines.channels.Channel<JsonObject>(1)
            val job = launch {
                MessageBus.events.collect {
                    if (it.get("type")?.asString == "contact-list") ch.trySend(it)
                }
            }
            WebSocketClient.send(mapOf("type" to "contact-list"))
            val result = withTimeoutOrNull(5_000) { ch.receive() }
            job.cancel()
            val count = result?.getAsJsonArray("pendingReceived")?.size() ?: 0
            binding.tvRequestsBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
            binding.tvRequestsBadge.text = if (count > 9) "9+" else count.toString()
        }
    }

    private fun refreshDevices() {
        lifecycleScope.launch {
            val ch = Channel<JsonObject>(1)
            val job = launch {
                MessageBus.events.collect {
                    if (it.get("type")?.asString == "device-list") ch.trySend(it)
                }
            }
            WebSocketClient.send(mapOf("type" to "device-list"))
            val result = withTimeoutOrNull(5_000) { ch.receive() }
            job.cancel()
            result?.let { updateDeviceList(it) }
        }
    }

    private fun updateDeviceList(json: JsonObject) {
        val currentDeviceId = json.get("currentDeviceId")?.asString ?: ""
        val arr = json.getAsJsonArray("devices") ?: return
        deviceItems.clear()
        for (el in arr) {
            val obj = el.asJsonObject
            val did = obj.get("device_id")?.asString ?: continue
            deviceItems.add(DeviceItem(
                deviceId   = did,
                deviceName = obj.get("device_name")?.takeIf { !it.isJsonNull }?.asString,
                lastSeen   = obj.get("last_seen")?.asLong ?: 0L,
                isCurrent  = did == currentDeviceId
            ))
        }
        deviceItems.sortWith(compareByDescending<DeviceItem> { it.isCurrent }.thenByDescending { it.lastSeen })
        deviceAdapter.notifyDataSetChanged()
        val current = deviceItems.find { it.isCurrent }
        val name = current?.deviceName?.takeIf { it.isNotEmpty() }
            ?: Prefs.getDeviceName(this).ifEmpty { Build.MODEL }
        binding.tvCurrentDevice.text = "This device: $name"
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

    private fun performLocalExport() {
        val progressDialog = AlertDialog.Builder(this)
            .setMessage(getString(R.string.export_in_progress))
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val me = Prefs.getUsername(this@SettingsActivity)
                val db = AppDatabase.getInstance(this@SettingsActivity)

                val dmMessages    = db.messageDao().getAllDmMessages()
                val groupMessages = db.messageDao().getAllGroupMessages()

                val exportObj = org.json.JSONObject()
                exportObj.put("exportedAt",
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
                exportObj.put("username", me)

                // DM conversations
                val dmArray  = org.json.JSONArray()
                val dmByPeer = dmMessages.groupBy { msg -> if (msg.from == me) msg.to else msg.from }
                for ((peer, msgs) in dmByPeer) {
                    val convObj  = org.json.JSONObject()
                    convObj.put("peer", peer)
                    val msgArray = org.json.JSONArray()
                    for (msg in msgs) {
                        val msgObj  = org.json.JSONObject()
                        msgObj.put("from",      msg.from)
                        msgObj.put("to",        msg.to)
                        msgObj.put("timestamp", msg.timestamp)
                        msgObj.put("type",      msg.type)
                        val content = try {
                            if (msg.content.length >= 64 &&
                                msg.content.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                val peerPubHex = Prefs.getPeerPublicKey(this@SettingsActivity, peer)
                                CryptoHelper.decryptFromPeer(
                                    this@SettingsActivity, peer, peerPubHex, msg.id, msg.content)
                                    ?: msg.content
                            } else {
                                msg.content
                            }
                        } catch (e: Exception) { msg.content }
                        msgObj.put("content", content)
                        msgArray.put(msgObj)
                    }
                    convObj.put("messages", msgArray)
                    dmArray.put(convObj)
                }
                exportObj.put("conversations", dmArray)

                // Group messages
                val groupArray   = org.json.JSONArray()
                val groupByGroup = groupMessages.groupBy { it.groupId }
                for ((groupId, msgs) in groupByGroup) {
                    val grpObj   = org.json.JSONObject()
                    grpObj.put("groupId", groupId)
                    val msgArray = org.json.JSONArray()
                    for (msg in msgs) {
                        val msgObj = org.json.JSONObject()
                        msgObj.put("from",      msg.from)
                        msgObj.put("timestamp", msg.timestamp)
                        msgObj.put("type",      msg.type)
                        msgObj.put("content",   msg.content)
                        msgArray.put(msgObj)
                    }
                    grpObj.put("messages", msgArray)
                    groupArray.put(grpObj)
                }
                exportObj.put("groups", groupArray)

                val fileName = "fshu_export_${me}_${
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"

                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues)

                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(exportObj.toString(2).toByteArray())
                    }
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(android.content.Intent.createChooser(
                            shareIntent, getString(R.string.export_share_title)))
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(this@SettingsActivity,
                            getString(R.string.export_failed), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@SettingsActivity,
                        getString(R.string.export_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun showAppLockTimeoutPicker() {
        val options = arrayOf(
            getString(R.string.app_lock_timeout_30s),
            getString(R.string.app_lock_timeout_1m),
            getString(R.string.app_lock_timeout_5m),
            getString(R.string.app_lock_timeout_30m)
        )
        val values = longArrayOf(30_000L, 60_000L, 300_000L, 1_800_000L)
        val current = Prefs.getAppLockTimeoutMs(this)
        val currentIndex = values.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 1
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_lock_timeout_title))
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                Prefs.setAppLockTimeoutMs(this, values[which])
                dialog.dismiss()
            }
            .show()
    }
}
