package com.fshu.next.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityPrivacySettingsBinding

class PrivacySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacySettingsBinding
    private val prefs get() = getSharedPreferences("fshu_prefs", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.title_privacy_settings)
            setDisplayHomeAsUpEnabled(true)
        }

        binding.switchDiscoverable.isChecked    = prefs.getInt("privacy_discoverable", 1) == 1
        binding.switchShowAvatar.isChecked      = prefs.getInt("privacy_show_avatar", 1) == 1
        binding.switchShowNickname.isChecked    = prefs.getInt("privacy_show_nickname", 1) == 1
        binding.switchEmailSearchable.isChecked = prefs.getInt("privacy_email_searchable", 0) == 1
        binding.switchPhoneSearchable.isChecked = prefs.getInt("privacy_phone_searchable", 0) == 1

        binding.rowDiscoverable.setOnClickListener {
            binding.switchDiscoverable.isChecked = !binding.switchDiscoverable.isChecked
        }
        binding.rowShowAvatar.setOnClickListener {
            binding.switchShowAvatar.isChecked = !binding.switchShowAvatar.isChecked
        }
        binding.rowShowNickname.setOnClickListener {
            binding.switchShowNickname.isChecked = !binding.switchShowNickname.isChecked
        }
        binding.rowEmailSearchable.setOnClickListener {
            binding.switchEmailSearchable.isChecked = !binding.switchEmailSearchable.isChecked
        }
        binding.rowPhoneSearchable.setOnClickListener {
            binding.switchPhoneSearchable.isChecked = !binding.switchPhoneSearchable.isChecked
        }

        binding.btnSave.setOnClickListener { save() }
    }

    private fun save() {
        val discoverable    = if (binding.switchDiscoverable.isChecked) 1 else 0
        val showAvatar      = if (binding.switchShowAvatar.isChecked) 1 else 0
        val showNickname    = if (binding.switchShowNickname.isChecked) 1 else 0
        val emailSearchable = if (binding.switchEmailSearchable.isChecked) 1 else 0
        val phoneSearchable = if (binding.switchPhoneSearchable.isChecked) 1 else 0

        prefs.edit()
            .putInt("privacy_discoverable", discoverable)
            .putInt("privacy_show_avatar", showAvatar)
            .putInt("privacy_show_nickname", showNickname)
            .putInt("privacy_email_searchable", emailSearchable)
            .putInt("privacy_phone_searchable", phoneSearchable)
            .apply()

        if (WebSocketClient.isConnected) {
            WebSocketClient.send(mapOf(
                "type"            to "privacy-update",
                "discoverable"    to discoverable,
                "showAvatar"      to showAvatar,
                "showNickname"    to showNickname,
                "emailSearchable" to emailSearchable,
                "phoneSearchable" to phoneSearchable
            ))
        }

        Toast.makeText(this, getString(R.string.toast_privacy_saved), Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
