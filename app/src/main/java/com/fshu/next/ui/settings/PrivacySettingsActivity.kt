package com.fshu.next.ui.settings

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityPrivacySettingsBinding
import com.fshu.next.util.Prefs

class PrivacySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.title_privacy_settings)
            setDisplayHomeAsUpEnabled(true)
        }

        binding.switchDiscoverable.isChecked    = Prefs.getPrivacyDiscoverable(this) == 1
        binding.switchShowAvatar.isChecked      = Prefs.getPrivacyShowAvatar(this) == 1
        binding.switchShowNickname.isChecked    = Prefs.getPrivacyShowNickname(this) == 1
        binding.switchEmailSearchable.isChecked = Prefs.getPrivacyEmailSearchable(this) == 1
        binding.switchPhoneSearchable.isChecked = Prefs.getPrivacyPhoneSearchable(this) == 1

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

        Prefs.setPrivacyDiscoverable(this, discoverable)
        Prefs.setPrivacyShowAvatar(this, showAvatar)
        Prefs.setPrivacyShowNickname(this, showNickname)
        Prefs.setPrivacyEmailSearchable(this, emailSearchable)
        Prefs.setPrivacyPhoneSearchable(this, phoneSearchable)

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
