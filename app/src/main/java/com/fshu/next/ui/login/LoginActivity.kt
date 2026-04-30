package com.fshu.next.ui.login

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.fshu.next.MainActivity
import com.fshu.next.databinding.ActivityLoginBinding
import com.fshu.next.service.FshuService
import com.fshu.next.ui.passphrase.PassphraseActivity
import com.fshu.next.ui.permission.PermissionSetupActivity
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTH_ERROR = "auth_error"
        /** True while LoginActivity is in the foreground — prevents FshuService from auto-redirecting here. */
        var isActive = false
    }

    private lateinit var binding: ActivityLoginBinding

    override fun onResume() {
        super.onResume()
        isActive = true
    }

    override fun onPause() {
        super.onPause()
        isActive = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Prefs.isPermissionsSetupDone(this)) {
            startActivity(Intent(this, PermissionSetupActivity::class.java))
            finish()
            return
        }

        // Cold start with saved credentials — skip the form entirely.
        if (Prefs.getUsername(this).isNotBlank()
            && Prefs.getPassword(this).isNotBlank()
            && Prefs.getServerUrl(this).isNotBlank()
        ) {
            goNext(); return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (intent.getBooleanExtra(EXTRA_AUTH_ERROR, false)) {
            Snackbar.make(binding.root, "Invalid credentials. Please log in again.", Snackbar.LENGTH_LONG).show()
        }

        binding.etServerUrl.setText(Prefs.getServerUrl(this))

        binding.btnJoin.setOnClickListener {
            val username = binding.etUsername.text.toString().trim().lowercase()
            val password = binding.etPassword.text.toString()
            val serverUrl = binding.etServerUrl.text.toString().trim()
            if (username.isBlank() || password.isBlank() || serverUrl.isBlank()) return@setOnClickListener

            setLoading(true)

            // Save creds so FshuService can read them when it starts.
            Prefs.setUsername(this, username)
            Prefs.setPassword(this, password)
            Prefs.setServerUrl(this, serverUrl)
            getSharedPreferences("fshu_boot", Context.MODE_PRIVATE)
                .edit().putBoolean("was_logged_in", true).apply()

            // Register channel-based listener BEFORE starting the service so no auth event is missed.
            val authChannel = Channel<android.util.Pair<String, String>>(capacity = 1)
            val collector = lifecycleScope.launch {
                MessageBus.events
                    .filter { it.get("type")?.asString in setOf("auth-ok", "auth-error") }
                    .collect {
                        val type = it.get("type")?.asString ?: ""
                        val msg  = it.get("message")?.asString ?: ""
                        authChannel.trySend(android.util.Pair(type, msg))
                    }
            }

            startService(Intent(this, FshuService::class.java))

            lifecycleScope.launch {
                val result = withTimeoutOrNull(10_000) { authChannel.receive() }
                collector.cancel()
                authChannel.close()
                when {
                    result == null -> {
                        // Timeout — clean up and show error.
                        Prefs.setUsername(this@LoginActivity, "")
                        Prefs.setPassword(this@LoginActivity, "")
                        getSharedPreferences("fshu_boot", Context.MODE_PRIVATE)
                            .edit().putBoolean("was_logged_in", false).apply()
                        showError("Could not reach server")
                        setLoading(false)
                    }
                    result.first == "auth-ok" -> {
                        navigateAfterAuth()
                    }
                    else -> {
                        Prefs.setUsername(this@LoginActivity, "")
                        Prefs.setPassword(this@LoginActivity, "")
                        getSharedPreferences("fshu_boot", Context.MODE_PRIVATE)
                            .edit().putBoolean("was_logged_in", false).apply()
                        showError(result.second.ifBlank { "Invalid credentials" })
                        setLoading(false)
                    }
                }
            }
        }
    }

    private fun goNext() {
        startService(Intent(this, FshuService::class.java))
        if (Prefs.getPassphrase(this).isBlank()) {
            startActivity(Intent(this, PassphraseActivity::class.java))
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private fun navigateAfterAuth() {
        if (Prefs.getPassphrase(this).isBlank()) {
            startActivity(Intent(this, PassphraseActivity::class.java))
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.pbLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnJoin.isEnabled = !loading
        binding.btnJoin.text = if (loading) "Connecting…" else "Connect"
        if (loading) binding.tvError.visibility = View.GONE
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
    }
}
