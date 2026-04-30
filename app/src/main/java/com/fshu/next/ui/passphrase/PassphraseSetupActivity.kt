package com.fshu.next.ui.passphrase

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fshu.next.MainActivity
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityPassphraseSetupBinding
import com.fshu.next.service.FshuService
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class PassphraseSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPassphraseSetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPassphraseSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cbAutoLocation.isChecked = Prefs.getLocationSharingEnabled(this)

        // Pre-fill hint with whatever is stored locally.
        val storedHint = Prefs.getPassphraseHint(this)
        if (storedHint.isNotEmpty()) binding.etHint.setText(storedHint)

        binding.btnShowHint.setOnClickListener {
            binding.btnShowHint.isEnabled = false
            binding.btnShowHint.text = "Loading…"
            lifecycleScope.launch {
                try {
                    if (!WebSocketClient.isConnected) {
                        binding.tvHint.text = "Waiting for connection…"
                        binding.tvHint.visibility = View.VISIBLE
                        var attempts = 0
                        while (!WebSocketClient.isConnected && attempts < 6) {
                            delay(1000)
                            attempts++
                        }
                        if (!WebSocketClient.isConnected) {
                            binding.tvHint.text = "Not connected"
                            return@launch
                        }
                    }
                    // Start collector BEFORE sending to avoid the response arriving first.
                    val hintDeferred = async {
                        withTimeout(8_000L) {
                            MessageBus.events.filter {
                                it.get("type")?.asString == "passphrase-hint"
                            }.first()
                        }
                    }
                    WebSocketClient.send(mapOf("type" to "get-passphrase-hint"))
                    val json = hintDeferred.await()
                    val hint = json.get("hint")?.asString ?: ""
                    binding.tvHint.text = if (hint.isBlank()) "No hint set" else hint
                } catch (e: TimeoutCancellationException) {
                    binding.tvHint.text = "Could not reach server"
                } finally {
                    binding.tvHint.visibility = View.VISIBLE
                    binding.btnShowHint.isEnabled = true
                    binding.btnShowHint.text = "Show hint"
                }
            }
        }

        binding.btnConfirm.setOnClickListener {
            val pass = binding.etPassphrase.text.toString().trim()
            val confirm = binding.etPassphraseConfirm.text.toString().trim()
            val hint = binding.etHint.text.toString().trim()

            if (pass.length < 4) {
                Toast.makeText(this, "Passphrase must be at least 4 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pass != confirm) {
                Toast.makeText(this, "Passphrases do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Prefs.setPassphrase(this, pass)
            Prefs.setLocationSharingEnabled(this, binding.cbAutoLocation.isChecked)
            if (hint.isNotEmpty() && binding.cbSaveHint.isChecked) {
                Prefs.setPassphraseHint(this, hint)
                WebSocketClient.send(mapOf("type" to "set-passphrase-hint", "hint" to hint))
            }

            startService(Intent(this, FshuService::class.java))
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
