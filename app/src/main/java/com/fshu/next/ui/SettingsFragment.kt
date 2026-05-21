package com.fshu.next.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.fshu.next.BuildConfig
import com.fshu.next.R
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.FragmentSettingsBinding
import com.fshu.next.service.FshuService
import com.fshu.next.ui.admin.ChangePasswordDialog
import com.fshu.next.ui.login.LoginActivity
import com.fshu.next.ui.settings.BlockListActivity
import com.fshu.next.ui.settings.DevicesActivity
import com.fshu.next.ui.settings.MyProfileActivity
import com.fshu.next.ui.settings.PrivacySettingsActivity
import com.fshu.next.util.CryptoHelper
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import com.fshu.next.ui.ThemeManager
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupProfile()
        setupAppearance()
        setupPrivacy()
        setupSecurity()
        setupEmergency()
        setupDevices()
        setupConnectionAdvanced()
        setupAdmin()
        setupDataAccount()
        setupVersion()
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        setupProfile()
        refreshLockTimeout()
        refreshPermissions()
        refreshSecretQuestionStatus()
    }

    private fun setupProfile() {
        val b = _binding ?: return
        val ctx = requireContext()
        val me = Prefs.getUsername(ctx)
        val nick = Prefs.getMyNickname(ctx)
        b.tvProfileName.text = nick.ifEmpty { me }
        b.tvProfileUsername.text = "@$me"
        loadProfileAvatar()
        b.cardProfile.setOnClickListener {
            startActivity(Intent(ctx, MyProfileActivity::class.java))
        }
    }

    private fun loadProfileAvatar() {
        val b = _binding ?: return
        val ctx = requireContext()
        val me = Prefs.getUsername(ctx)
        val avatarFile = File(ctx.filesDir, "avatars/$me.jpg")
        val sizePx = (56 * resources.displayMetrics.density).toInt()
        if (avatarFile.exists()) {
            b.ivProfileAvatar.load(avatarFile) { transformations(CircleCropTransformation()) }
        } else {
            val letter = me.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val colorRes = when (me.hashCode().let { if (it < 0) -it else it } % 10) {
                0 -> R.color.avatar_1; 1 -> R.color.avatar_2; 2 -> R.color.avatar_3
                3 -> R.color.avatar_4; 4 -> R.color.avatar_5; 5 -> R.color.avatar_6
                6 -> R.color.avatar_7; 7 -> R.color.avatar_8; 8 -> R.color.avatar_9
                else -> R.color.avatar_10
            }
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(colorRes) }.also {
                canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, it)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = sizePx * 0.42f
                textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
            }.also {
                canvas.drawText(letter, sizePx / 2f, sizePx / 2f - (it.descent() + it.ascent()) / 2f, it)
            }
            b.ivProfileAvatar.setImageBitmap(bmp)
        }
    }

    private fun setupAppearance() {
        refreshThemeValue()
        refreshLanguageValue()
        binding.rowTheme.setOnClickListener { showThemeDialog() }
        binding.rowLanguage.setOnClickListener { showLanguageDialog() }
    }

    private fun refreshThemeValue() {
        val b = _binding ?: return
        b.tvThemeValue.text = when (ThemeManager.getTheme(requireContext())) {
            ThemeManager.THEME_LIGHT -> getString(R.string.option_theme_light)
            ThemeManager.THEME_SYSTEM -> getString(R.string.option_theme_system)
            else -> getString(R.string.option_theme_dark)
        }
    }

    private fun refreshLanguageValue() {
        val b = _binding ?: return
        val lang = requireContext().getSharedPreferences("fshu_prefs", Context.MODE_PRIVATE)
            .getString("language", "system") ?: "system"
        b.tvLanguageValue.text = when (lang) {
            "en" -> getString(R.string.option_language_en)
            "bg" -> getString(R.string.option_language_bg)
            else -> getString(R.string.option_language_system)
        }
    }

    private fun showThemeDialog() {
        val options = arrayOf(
            getString(R.string.option_theme_system),
            getString(R.string.option_theme_light),
            getString(R.string.option_theme_dark)
        )
        val values = arrayOf(ThemeManager.THEME_SYSTEM, ThemeManager.THEME_LIGHT, ThemeManager.THEME_DARK)
        val current = ThemeManager.getTheme(requireContext())
        val idx = values.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 2
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.label_theme))
            .setSingleChoiceItems(options, idx) { dialog, which ->
                ThemeManager.setTheme(requireContext(), values[which])
                dialog.dismiss()
                refreshThemeValue()
            }
            .show()
    }

    private fun showLanguageDialog() {
        val options = arrayOf(
            getString(R.string.option_language_system),
            getString(R.string.option_language_en),
            getString(R.string.option_language_bg)
        )
        val codes = arrayOf("system", "en", "bg")
        val prefs = requireContext().getSharedPreferences("fshu_prefs", Context.MODE_PRIVATE)
        val current = prefs.getString("language", "system") ?: "system"
        val idx = codes.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.label_language))
            .setSingleChoiceItems(options, idx) { dialog, which ->
                val code = codes[which]
                prefs.edit().putString("language", code).apply()
                val locale = if (code == "system") LocaleListCompat.getEmptyLocaleList()
                             else LocaleListCompat.forLanguageTags(code)
                AppCompatDelegate.setApplicationLocales(locale)
                dialog.dismiss()
                refreshLanguageValue()
            }
            .show()
    }

    private fun setupPrivacy() {
        val b = _binding ?: return
        val ctx = requireContext()
        b.rowPrivacySettings.setOnClickListener {
            startActivity(Intent(ctx, PrivacySettingsActivity::class.java))
        }
        b.rowBlocked.setOnClickListener {
            startActivity(Intent(ctx, BlockListActivity::class.java))
        }
    }

    private fun setupSecurity() {
        val b = _binding ?: return
        val ctx = requireContext()

        val lockEnabled = Prefs.getAppLockEnabled(ctx)
        b.switchAppLock.isChecked = lockEnabled
        updateLockTimeoutVisibility(lockEnabled)
        refreshLockTimeout()

        b.rowAppLock.setOnClickListener {
            val enabled = !Prefs.getAppLockEnabled(ctx)
            Prefs.setAppLockEnabled(ctx, enabled)
            b.switchAppLock.isChecked = enabled
            Toast.makeText(ctx,
                if (enabled) getString(R.string.toast_app_lock_enabled)
                else getString(R.string.toast_app_lock_disabled),
                Toast.LENGTH_SHORT).show()
            updateLockTimeoutVisibility(enabled)
            if (enabled) showAppLockTimeoutPicker()
        }

        b.rowLockTimeout.setOnClickListener { showAppLockTimeoutPicker() }

        b.rowChangePassword.setOnClickListener {
            ChangePasswordDialog().show(childFragmentManager, "change_password")
        }

        b.rowSecretQuestion.setOnClickListener {
            startActivity(Intent(ctx, MyProfileActivity::class.java))
        }
    }

    private fun updateLockTimeoutVisibility(lockEnabled: Boolean) {
        val b = _binding ?: return
        val vis = if (lockEnabled) View.VISIBLE else View.GONE
        b.rowLockTimeout.visibility = vis
        b.divLockTimeout.visibility = vis
    }

    private fun refreshLockTimeout() {
        val b = _binding ?: return
        b.tvLockTimeoutValue.text = when (Prefs.getAppLockTimeoutMs(requireContext())) {
            30_000L -> getString(R.string.app_lock_timeout_30s)
            60_000L -> getString(R.string.app_lock_timeout_1m)
            300_000L -> getString(R.string.app_lock_timeout_5m)
            1_800_000L -> getString(R.string.app_lock_timeout_30m)
            else -> getString(R.string.app_lock_timeout_1m)
        }
    }

    private fun showAppLockTimeoutPicker() {
        val options = arrayOf(
            getString(R.string.app_lock_timeout_30s),
            getString(R.string.app_lock_timeout_1m),
            getString(R.string.app_lock_timeout_5m),
            getString(R.string.app_lock_timeout_30m)
        )
        val values = longArrayOf(30_000L, 60_000L, 300_000L, 1_800_000L)
        val current = Prefs.getAppLockTimeoutMs(requireContext())
        val idx = values.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 1
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.app_lock_timeout_title))
            .setSingleChoiceItems(options, idx) { dialog, which ->
                Prefs.setAppLockTimeoutMs(requireContext(), values[which])
                dialog.dismiss()
                refreshLockTimeout()
            }
            .show()
    }

    private fun refreshSecretQuestionStatus() {
        if (!WebSocketClient.isConnected) return
        val b = _binding ?: return
        lifecycleScope.launch {
            val ch = Channel<JsonObject>(1)
            val job = launch {
                MessageBus.events.collect { msg ->
                    if (msg.get("type")?.asString == "my-secret-question") ch.trySend(msg)
                }
            }
            WebSocketClient.send(mapOf("type" to "get-secret-question"))
            val result = withTimeoutOrNull(5_000) { ch.receive() }
            job.cancel()
            val hasQuestion = result?.get("question")?.takeIf { !it.isJsonNull }?.asString?.isNotEmpty() == true
            _binding?.tvSecretQuestionStatus?.text = getString(
                if (hasQuestion) R.string.label_secret_question_set
                else R.string.label_secret_question_not_set
            )
        }
    }

    private fun setupEmergency() {
        val b = _binding ?: return
        val prefs = requireContext().getSharedPreferences("fshu_prefs", Context.MODE_PRIVATE)
        b.etSosMessage.setText(prefs.getString("sos_message", getString(R.string.settings_sos_message_default)))
        b.btnSaveSos.setOnClickListener {
            prefs.edit().putString("sos_message", b.etSosMessage.text.toString().trim()).apply()
            Toast.makeText(requireContext(), getString(R.string.toast_done), Toast.LENGTH_SHORT).show()
        }
        b.btnResetSos.setOnClickListener {
            val default = getString(R.string.settings_sos_message_default)
            b.etSosMessage.setText(default)
            prefs.edit().putString("sos_message", default).apply()
            Toast.makeText(requireContext(), getString(R.string.toast_done), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupDevices() {
        binding.rowDevices.setOnClickListener {
            startActivity(Intent(requireContext(), DevicesActivity::class.java))
        }
    }

    private fun setupConnectionAdvanced() {
        val b = _binding ?: return
        val ctx = requireContext()

        b.tvServerUrl.text = Prefs.getServerUrl(ctx)
        b.rowServerUrl.setOnClickListener {
            val et = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(Prefs.getServerUrl(ctx))
                setSelection(text.length)
            }
            val pad = (16 * resources.displayMetrics.density).toInt()
            val wrap = FrameLayout(ctx).apply { setPadding(pad, 0, pad, 0); addView(et) }
            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.dialog_server_url_title))
                .setView(wrap)
                .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                    val url = et.text.toString().trim()
                    if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
                        Toast.makeText(ctx, getString(R.string.toast_url_invalid), Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    Prefs.setServerUrl(ctx, url)
                    b.tvServerUrl.text = url
                    Toast.makeText(ctx, getString(R.string.toast_server_url_updated), Toast.LENGTH_SHORT).show()
                    requireContext().startService(Intent(ctx, FshuService::class.java).apply {
                        action = FshuService.ACTION_RECONNECT
                    })
                }
                .setNeutralButton(getString(R.string.btn_reset_to_default)) { _, _ ->
                    val defaultUrl = ""
                    Prefs.setServerUrl(ctx, defaultUrl)
                    b.tvServerUrl.text = defaultUrl
                    Toast.makeText(ctx, getString(R.string.toast_reset_to_default_reconnecting), Toast.LENGTH_SHORT).show()
                    requireContext().startService(Intent(ctx, FshuService::class.java).apply {
                        action = FshuService.ACTION_RECONNECT
                    })
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }

        b.switchFcm.isChecked = Prefs.getFcmEnabled(ctx)
        b.rowFcm.setOnClickListener {
            val enabled = !Prefs.getFcmEnabled(ctx)
            Prefs.setFcmEnabled(ctx, enabled)
            b.switchFcm.isChecked = enabled
            if (enabled) {
                val token = Prefs.getFcmToken(ctx)
                if (token.isNotEmpty() && WebSocketClient.isConnected) {
                    WebSocketClient.send(mapOf("type" to "fcm-token", "token" to token))
                }
                Toast.makeText(ctx, getString(R.string.toast_push_enabled), Toast.LENGTH_SHORT).show()
            } else {
                if (WebSocketClient.isConnected) {
                    WebSocketClient.send(mapOf("type" to "fcm-token", "token" to ""))
                }
                Toast.makeText(ctx, getString(R.string.toast_push_disabled), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshPermissions() {
        val b = _binding ?: return
        val ctx = requireContext()
        b.permissionsContainer.removeAllViews()

        val dp = resources.displayMetrics.density
        val p16 = (16 * dp).toInt()
        val p8 = (8 * dp).toInt()

        data class PermRow(val name: String, val granted: Boolean, val settingsIntent: Intent? = null)

        val rows = mutableListOf<PermRow>()

        rows += PermRow("Notifications",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true)

        rows += PermRow("Microphone",
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)

        rows += PermRow("Camera",
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)

        rows += PermRow("Location",
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)

        val pm = ctx.getSystemService(PowerManager::class.java)
        rows += PermRow(
            name = "Battery optimization exempt",
            granted = pm.isIgnoringBatteryOptimizations(ctx.packageName),
            settingsIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${ctx.packageName}")
            }
        )

        rows += PermRow("Display over other apps", Settings.canDrawOverlays(ctx))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            rows += PermRow("Full-screen calls", nm.canUseFullScreenIntent())
        }

        for (row in rows) {
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(p16, p8, p16, p8)
            }
            val nameView = TextView(ctx).apply {
                text = row.name
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val statusView = TextView(ctx).apply {
                text = if (row.granted) "✓" else "✗ Open Settings"
                textSize = 13f
                setTextColor(if (row.granted) 0xFF4CAF50.toInt() else 0xFFE53935.toInt())
                if (!row.granted) {
                    setOnClickListener {
                        val target = row.settingsIntent ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${ctx.packageName}")
                        }
                        startActivity(target)
                    }
                }
            }
            container.addView(nameView)
            container.addView(statusView)
            b.permissionsContainer.addView(container)
        }
    }

    private fun setupAdmin() {
        val ctx = requireContext()
        if (Prefs.isAdmin(ctx)) {
            binding.sectionAdmin.visibility = View.VISIBLE
            binding.rowAdminPanel.setOnClickListener {
                startActivity(Intent(ctx, com.fshu.next.ui.admin.AdminPanelActivity::class.java))
            }
        } else {
            binding.sectionAdmin.visibility = View.GONE
        }
    }

    private fun setupDataAccount() {
        val ctx = requireContext()
        binding.rowExportData.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.export_data_title))
                .setMessage(getString(R.string.export_data_message_local))
                .setPositiveButton(getString(R.string.export_confirm)) { _, _ ->
                    performLocalExport()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
        binding.btnDeleteAccount.setOnClickListener { showDeleteAccountDialog() }
    }

    private fun showDeleteAccountDialog() {
        val ctx = requireContext()
        val et = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.hint_current_password)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(ctx).apply { setPadding(pad, 0, pad, 0); addView(et) }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.dialog_delete_account_title))
            .setMessage(getString(R.string.dialog_delete_account_message))
            .setView(wrap)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setPositiveButton(getString(R.string.btn_delete_account), null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            setTextColor(ctx.getColor(R.color.color_danger))
            setOnClickListener deleteBtn@{
                val password = et.text.toString()
                if (password.isEmpty()) { et.error = getString(R.string.hint_current_password); return@deleteBtn }
                dialog.dismiss()
                lifecycleScope.launch {
                    val ch = Channel<JsonObject>(1)
                    val job = launch {
                        MessageBus.events.collect { msg ->
                            val t = msg.get("type")?.asString
                            if (t == "delete-account-ok" || t == "delete-account-error") ch.trySend(msg)
                        }
                    }
                    WebSocketClient.send(mapOf("type" to "delete-account", "currentPassword" to password))
                    val result = withTimeoutOrNull(10_000) { ch.receive() }
                    job.cancel()
                    when {
                        result == null ->
                            Toast.makeText(ctx, getString(R.string.toast_no_response), Toast.LENGTH_SHORT).show()
                        result.get("type")?.asString == "delete-account-error" ->
                            Toast.makeText(ctx, result.get("message")?.asString ?: getString(R.string.toast_error), Toast.LENGTH_LONG).show()
                        else -> {
                            ctx.getSharedPreferences("fshu_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                            ctx.deleteSharedPreferences("fshu_secure_prefs")
                            val intent = Intent(ctx, LoginActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                        }
                    }
                }
            }
        }
    }

    private fun performLocalExport() {
        val ctx = requireContext()
        val progressDialog = AlertDialog.Builder(ctx)
            .setMessage(getString(R.string.export_in_progress))
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val me = Prefs.getUsername(ctx)
                val db = AppDatabase.getInstance(ctx)

                val dmMessages    = db.messageDao().getAllDmMessages()
                val groupMessages = db.messageDao().getAllGroupMessages()

                val exportObj = org.json.JSONObject()
                exportObj.put("exportedAt",
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
                exportObj.put("username", me)

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
                                val peerPubHex = Prefs.getPeerPublicKey(ctx, peer)
                                CryptoHelper.decryptFromPeer(ctx, peer, peerPubHex, msg.id, msg.content) ?: msg.content
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
                val uri = ctx.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues)

                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(exportObj.toString(2).toByteArray())
                    }
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.export_share_title)))
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(ctx, getString(R.string.export_failed), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(ctx, getString(R.string.export_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupVersion() {
        _binding?.tvVersion?.text = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TIME}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
