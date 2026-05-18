package com.fshu.next.ui.sos

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fshu.next.R
import com.fshu.next.service.FshuService

class SosAlertActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PEER = "extra_peer"
        const val EXTRA_PREVIEW = "extra_preview"
    }

    private lateinit var peer: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
        }

        peer = intent.getStringExtra(EXTRA_PEER) ?: run { finish(); return }
        val preview = intent.getStringExtra(EXTRA_PREVIEW) ?: ""

        setContentView(R.layout.activity_sos_alert)

        findViewById<TextView>(R.id.tv_from).text = peer
        findViewById<TextView>(R.id.tv_preview).text = preview

        findViewById<Button>(R.id.btn_dismiss).setOnClickListener {
            FshuService.stopSosAlarm(peer, this)
            finish()
        }
    }
}
