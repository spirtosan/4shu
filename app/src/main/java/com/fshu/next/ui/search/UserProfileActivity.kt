package com.fshu.next.ui.search

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fshu.next.R
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityUserProfileBinding
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UserProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserProfileBinding
    private lateinit var db: AppDatabase
    private var targetUsername = ""
    private var currentAllowEmergency: Int? = null
    private var allowEmergencyLocation: Int? = null
    private var savedEmergencyLocationValue: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetUsername = intent.getStringExtra("username") ?: run { finish(); return }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = targetUsername

        db = AppDatabase.getInstance(this)

        drawLetterAvatar(targetUsername, 80)

        lifecycleScope.launch {
            MessageBus.events.collect { handleMessage(it) }
        }

        // Load initial emergency-allow states from Room DB
        lifecycleScope.launch {
            val me = Prefs.getUsername(this@UserProfileActivity)
            val (emergCall, emergLoc) = withContext(Dispatchers.IO) {
                Pair(
                    db.contactDao().getEmergencyAllow(me, targetUsername),
                    db.contactDao().getEmergencyLocationAllow(me, targetUsername)
                )
            }
            currentAllowEmergency = emergCall
            allowEmergencyLocation = emergLoc
        }

        binding.rowEmergencyAllow.setOnClickListener {
            binding.switchEmergencyAllow.toggle()
        }

        binding.rowEmergencyLocation.setOnClickListener {
            binding.switchAllowEmergencyLocation.toggle()
        }

        WebSocketClient.send(mapOf("type" to "user-profile", "targetUsername" to targetUsername))
        WebSocketClient.send(mapOf("type" to "contact-list"))
        WebSocketClient.send(mapOf("type" to "block-list"))

        binding.btnSendRequest.setOnClickListener {
            val et = EditText(this).apply {
                hint = "Add a message (optional)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                maxLines = 3
            }
            val pad = (16 * resources.displayMetrics.density).toInt()
            val wrap = FrameLayout(this).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(et)
            }
            AlertDialog.Builder(this)
                .setTitle("Send Contact Request")
                .setView(wrap)
                .setPositiveButton("Send") { _, _ ->
                    val intro = et.text.toString().trim()
                    WebSocketClient.send(mapOf(
                        "type" to "contact-request",
                        "targetUsername" to targetUsername,
                        "message" to intro.ifEmpty { null }
                    ))
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }

        binding.btnCancelRequest.setOnClickListener {
            WebSocketClient.send(mapOf("type" to "contact-cancel", "targetUsername" to targetUsername))
        }

        binding.btnBlock.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Block $targetUsername?")
                .setMessage("They won't be able to contact you.")
                .setPositiveButton("Block") { _, _ ->
                    WebSocketClient.send(mapOf("type" to "block-user", "targetUsername" to targetUsername))
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }

        binding.btnUnblock.setOnClickListener {
            WebSocketClient.send(mapOf("type" to "unblock-user", "targetUsername" to targetUsername))
        }

        binding.btnRemoveContact.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.remove_contact_title))
                .setMessage(getString(R.string.remove_contact_message, targetUsername))
                .setPositiveButton(getString(R.string.btn_remove_contact)) { _, _ ->
                    WebSocketClient.send(mapOf("type" to "contact-remove", "targetUsername" to targetUsername))
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    private fun handleMessage(json: JsonObject) {
        when (json.get("type")?.asString) {
            "user-profile" -> {
                val profile = json.getAsJsonObject("profile") ?: return
                runOnUiThread {
                    binding.tvUsername.text = profile.get("username")?.asString ?: targetUsername
                    val nick = profile.get("nickname")?.takeIf { !it.isJsonNull }?.asString
                    if (nick != null) {
                        binding.tvNickname.text = nick
                        binding.tvNickname.visibility = View.VISIBLE
                    } else {
                        binding.tvNickname.visibility = View.GONE
                    }
                    val bio = profile.get("bio")?.takeIf { !it.isJsonNull }?.asString
                    if (bio != null) {
                        binding.tvBio.text = bio
                        binding.tvBio.visibility = View.VISIBLE
                    } else {
                        binding.tvBio.visibility = View.GONE
                    }
                }
            }
            "contact-list" -> {
                val contacts = json.getAsJsonArray("contacts")
                val pendingSent = json.getAsJsonArray("pendingSent")
                val isAccepted = contacts?.any {
                    it.asJsonObject.get("contact")?.asString == targetUsername
                } ?: false
                val isPending = pendingSent?.any {
                    it.asJsonObject.get("contact")?.asString == targetUsername
                } ?: false
                if (isAccepted) {
                    val contactRow = contacts?.firstOrNull {
                        it.asJsonObject.get("contact")?.asString == targetUsername
                    }?.asJsonObject
                    val serverAllow = try {
                        contactRow?.get("allow_emergency_call")?.takeIf { !it.isJsonNull }?.asInt
                    } catch (_: Exception) { null }
                    if (serverAllow != null) currentAllowEmergency = serverAllow
                    val serverAllowLoc = try {
                        contactRow?.get("allow_emergency_location")?.takeIf { !it.isJsonNull }?.asInt
                    } catch (_: Exception) { null }
                    if (serverAllowLoc != null) allowEmergencyLocation = serverAllowLoc
                    if (currentAllowEmergency == null) currentAllowEmergency = 0
                    if (allowEmergencyLocation == null) allowEmergencyLocation = 0
                    runOnUiThread { updateTrustUI(true) }
                } else {
                    runOnUiThread { updateTrustUI(false) }
                }
                runOnUiThread {
                    updateContactButtons(when {
                        isAccepted -> "accepted"
                        isPending -> "pending"
                        else -> null
                    })
                }
            }
            "emergency-allow-update" -> {
                if (json.get("contact")?.asString == targetUsername) {
                    val allow = try { json.get("allow")?.asInt } catch (_: Exception) { null }
                    if (allow != null) {
                        currentAllowEmergency = allow
                        runOnUiThread { updateEmergencySwitch() }
                    }
                }
            }
            "block-list" -> {
                val blocks = json.getAsJsonArray("blocks")
                val isBlocked = blocks?.any {
                    it.asJsonObject.get("blocked")?.asString == targetUsername
                } ?: false
                runOnUiThread {
                    binding.btnBlock.visibility = if (isBlocked) View.GONE else View.VISIBLE
                    binding.btnUnblock.visibility = if (isBlocked) View.VISIBLE else View.GONE
                }
            }
            "contact-request-sent" -> {
                if (json.get("targetUsername")?.asString == targetUsername) {
                    runOnUiThread { updateContactButtons("pending") }
                }
            }
            "contact-cancelled" -> {
                if (json.get("targetUsername")?.asString == targetUsername) {
                    runOnUiThread { updateContactButtons(null) }
                }
            }
            "contact-accepted" -> {
                if (json.get("targetUsername")?.asString == targetUsername) {
                    runOnUiThread { updateContactButtons("accepted") }
                }
            }
            "contact-removed" -> {
                if (json.get("targetUsername")?.asString == targetUsername) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.toast_contact_removed), Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            "block-confirmed" -> {
                if (json.get("targetUsername")?.asString == targetUsername) {
                    runOnUiThread {
                        binding.btnBlock.visibility = View.GONE
                        binding.btnUnblock.visibility = View.VISIBLE
                    }
                }
            }
            "unblock-confirmed" -> {
                if (json.get("targetUsername")?.asString == targetUsername) {
                    runOnUiThread {
                        binding.btnBlock.visibility = View.VISIBLE
                        binding.btnUnblock.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateContactButtons(status: String?) {
        when (status) {
            "accepted" -> {
                binding.btnSendRequest.visibility = View.GONE
                binding.btnCancelRequest.visibility = View.GONE
                binding.tvPendingStatus.visibility = View.GONE
                binding.btnRemoveContact.visibility = View.VISIBLE
            }
            "pending" -> {
                binding.btnSendRequest.visibility = View.GONE
                binding.btnCancelRequest.visibility = View.VISIBLE
                binding.tvPendingStatus.visibility = View.VISIBLE
                binding.btnRemoveContact.visibility = View.GONE
            }
            else -> {
                binding.btnSendRequest.visibility = View.VISIBLE
                binding.btnCancelRequest.visibility = View.GONE
                binding.tvPendingStatus.visibility = View.GONE
                binding.btnRemoveContact.visibility = View.GONE
            }
        }
    }

    private fun drawLetterAvatar(username: String, sizeDp: Int) {
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
        val letter = username.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val colorRes = when (username.hashCode().let { if (it < 0) -it else it } % 10) {
            0 -> R.color.avatar_1
            1 -> R.color.avatar_2
            2 -> R.color.avatar_3
            3 -> R.color.avatar_4
            4 -> R.color.avatar_5
            5 -> R.color.avatar_6
            6 -> R.color.avatar_7
            7 -> R.color.avatar_8
            8 -> R.color.avatar_9
            else -> R.color.avatar_10
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(this@UserProfileActivity, colorRes) }
            .also { canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, it) }
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.42f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }.also {
            val yPos = sizePx / 2f - (it.descent() + it.ascent()) / 2f
            canvas.drawText(letter, sizePx / 2f, yPos, it)
        }
        binding.ivAvatar.setImageBitmap(bmp)
    }

    private fun updateTrustUI(visible: Boolean) {
        if (!visible) {
            binding.rowEmergencyAllow.visibility = View.GONE
            binding.rowEmergencyLocation.visibility = View.GONE
            return
        }
        binding.rowEmergencyAllow.visibility = View.VISIBLE
        updateEmergencySwitch()
        binding.rowEmergencyLocation.visibility =
            if (binding.switchEmergencyAllow.isChecked) View.VISIBLE else View.GONE
        updateEmergencyLocationSwitch()
    }

    private fun updateEmergencySwitch() {
        binding.switchEmergencyAllow.setOnCheckedChangeListener(null)
        binding.switchEmergencyAllow.isChecked = currentAllowEmergency == 1
        binding.switchEmergencyAllow.setOnCheckedChangeListener { _, isChecked ->
            val allow = if (isChecked) 1 else 0
            currentAllowEmergency = allow
            val me = Prefs.getUsername(this)
            WebSocketClient.send(mapOf(
                "type"   to "set-emergency-allow",
                "target" to targetUsername,
                "allow"  to allow
            ))
            lifecycleScope.launch(Dispatchers.IO) {
                db.contactDao().setEmergencyAllow(me, targetUsername, allow)
            }
            if (isChecked) {
                // Restore saved Switch 2 value (or use trust-level default if never saved)
                val restoreValue = savedEmergencyLocationValue ?: 0
                allowEmergencyLocation = restoreValue
                binding.rowEmergencyLocation.visibility = View.VISIBLE
                updateEmergencyLocationSwitch()
                lifecycleScope.launch(Dispatchers.IO) {
                    db.contactDao().setEmergencyLocationAllow(me, targetUsername, restoreValue)
                }
                WebSocketClient.send(mapOf(
                    "type"   to "emergency-location-update",
                    "target" to targetUsername,
                    "allow"  to restoreValue
                ))
            } else {
                // Save current Switch 2 value before forcing OFF
                savedEmergencyLocationValue = allowEmergencyLocation
                allowEmergencyLocation = 0
                binding.switchAllowEmergencyLocation.isChecked = false
                binding.rowEmergencyLocation.visibility = View.GONE
                lifecycleScope.launch(Dispatchers.IO) {
                    db.contactDao().setEmergencyLocationAllow(me, targetUsername, 0)
                }
                WebSocketClient.send(mapOf(
                    "type"   to "emergency-location-update",
                    "target" to targetUsername,
                    "allow"  to 0
                ))
            }
        }
    }

    private fun updateEmergencyLocationSwitch() {
        binding.switchAllowEmergencyLocation.setOnCheckedChangeListener(null)
        binding.switchAllowEmergencyLocation.isChecked = allowEmergencyLocation == 1
        binding.switchAllowEmergencyLocation.setOnCheckedChangeListener { _, isChecked ->
            allowEmergencyLocation = if (isChecked) 1 else 0
            val me = Prefs.getUsername(this)
            lifecycleScope.launch(Dispatchers.IO) {
                db.contactDao().setEmergencyLocationAllow(me, targetUsername, if (isChecked) 1 else 0)
            }
            WebSocketClient.send(mapOf(
                "type"   to "emergency-location-update",
                "target" to targetUsername,
                "allow"  to if (isChecked) 1 else 0
            ))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
