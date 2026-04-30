package com.fshu.next.ui.passphrase

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.fshu.next.MainActivity

/** Passphrase removed in Phase 1e (ECDH). Redirect anyone who lands here. */
class PassphraseSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
