package com.fshu.next.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.fshu.next.BuildConfig
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.FragmentSettingsBinding
import com.fshu.next.ui.admin.ChangePasswordDialog
import com.fshu.next.ui.login.LoginActivity
import com.fshu.next.ui.settings.BlockListActivity
import com.fshu.next.ui.settings.MyProfileActivity
import com.fshu.next.ui.settings.SettingsActivity
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import com.fshu.next.ui.ThemeManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

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
        setupEmergency()
        setupSecurity()
        setupAdmin()
        setupDangerZone()
        setupVersion()
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        setupProfile()
        refreshLockTimeout()
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
        val prefs = ctx.getSharedPreferences("fshu_prefs", Context.MODE_PRIVATE)

        b.switchReadReceipts.isChecked = prefs.getBoolean("read_receipts_enabled", true)
        b.rowReadReceipts.setOnClickListener {
            val enabled = !b.switchReadReceipts.isChecked
            b.switchReadReceipts.isChecked = enabled
            prefs.edit().putBoolean("read_receipts_enabled", enabled).apply()
        }

        b.switchDiscoverable.isChecked = Prefs.getPrivacyDiscoverable(ctx) == 1
        b.rowDiscoverable.setOnClickListener {
            val enabled = !b.switchDiscoverable.isChecked
            b.switchDiscoverable.isChecked = enabled
            val v = if (enabled) 1 else 0
            Prefs.setPrivacyDiscoverable(ctx, v)
            if (WebSocketClient.isConnected) {
                WebSocketClient.send(mapOf(
                    "type"            to "privacy-update",
                    "discoverable"    to v,
                    "showAvatar"      to Prefs.getPrivacyShowAvatar(ctx),
                    "showNickname"    to Prefs.getPrivacyShowNickname(ctx),
                    "emailSearchable" to Prefs.getPrivacyEmailSearchable(ctx),
                    "phoneSearchable" to Prefs.getPrivacyPhoneSearchable(ctx)
                ))
            }
        }

        b.rowBlocked.setOnClickListener {
            startActivity(Intent(ctx, BlockListActivity::class.java))
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

        b.rowDevices.setOnClickListener {
            startActivity(Intent(ctx, SettingsActivity::class.java))
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

    private fun setupDangerZone() {
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
                    val ch = Channel<com.google.gson.JsonObject>(1)
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

    private fun setupVersion() {
        _binding?.tvVersion?.text = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TIME}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
