package com.fshu.next.ui.settings

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.Base64
import android.util.Patterns
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.google.gson.JsonObject
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityMyProfileBinding
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

class MyProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyProfileBinding

    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri) ?: return@launch
                val original = BitmapFactory.decodeStream(stream)
                stream.close()
                val side = minOf(original.width, original.height)
                val x = (original.width - side) / 2
                val y = (original.height - side) / 2
                val cropped = Bitmap.createBitmap(original, x, y, side, side)
                val scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                WebSocketClient.send(mapOf("type" to "avatar-upload", "data" to b64))
                val me = Prefs.getUsername(this@MyProfileActivity)
                val dir = File(filesDir, "avatars").also { it.mkdirs() }
                File(dir, "$me.jpg").writeBytes(baos.toByteArray())
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    loadAvatar()
                }
            } catch (_: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@MyProfileActivity,
                        getString(R.string.toast_avatar_upload_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.label_my_profile)
            setDisplayHomeAsUpEnabled(true)
        }

        loadAvatar()

        binding.ivAvatar.setOnClickListener { pickAvatarLauncher.launch("image/*") }
        binding.tvChangePhoto.setOnClickListener { pickAvatarLauncher.launch("image/*") }

        binding.btnSaveProfile.setOnClickListener { saveProfile() }

        binding.btnPrivacySettings.setOnClickListener {
            startActivity(Intent(this, PrivacySettingsActivity::class.java))
        }

        binding.btnSetSecretQuestion.setOnClickListener { showSetSecretQuestionDialog() }

        lifecycleScope.launch {
            MessageBus.events.collect { handleMessage(it) }
        }

        if (WebSocketClient.isConnected) {
            WebSocketClient.send(mapOf("type" to "get-my-profile"))
            WebSocketClient.send(mapOf("type" to "get-secret-question"))
        }
    }

    private fun handleMessage(msg: JsonObject) {
        when (msg.get("type")?.asString) {
            "my-profile" -> {
                val p = msg.getAsJsonObject("profile") ?: return
                runOnUiThread {
                    binding.etNickname.setText(p.get("nickname")?.takeIf { !it.isJsonNull }?.asString ?: "")
                    binding.etBio.setText(p.get("bio")?.takeIf { !it.isJsonNull }?.asString ?: "")
                    binding.etEmail.setText(p.get("email")?.takeIf { !it.isJsonNull }?.asString ?: "")
                    binding.etPhone.setText(p.get("phone")?.takeIf { !it.isJsonNull }?.asString ?: "")
                }
            }
            "profile-updated" -> {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_profile_saved), Toast.LENGTH_SHORT).show()
                }
            }
            "profile-error" -> {
                val message = msg.get("message")?.asString ?: getString(R.string.toast_error)
                runOnUiThread {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
            "my-secret-question" -> {
                val q = msg.get("question")?.takeIf { !it.isJsonNull }?.asString
                runOnUiThread {
                    binding.tvSecretQuestionStatus.text = if (q != null)
                        getString(R.string.label_secret_question_set)
                    else
                        getString(R.string.label_secret_question_not_set)
                }
            }
            "secret-question-ok" -> {
                runOnUiThread {
                    binding.tvSecretQuestionStatus.text = getString(R.string.label_secret_question_set)
                    Toast.makeText(this, getString(R.string.toast_secret_question_saved), Toast.LENGTH_SHORT).show()
                }
            }
            "secret-question-error" -> {
                val message = msg.get("message")?.asString ?: getString(R.string.toast_error)
                runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showSetSecretQuestionDialog() {
        val dp16 = (16 * resources.displayMetrics.density).toInt()
        val etQuestion = EditText(this).apply {
            hint = getString(R.string.hint_secret_question)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 2
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.btn_set_secret_question))
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp16, dp16, dp16, 0)
                addView(etQuestion)
            })
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                val question = etQuestion.text.toString().trim()
                if (question.length < 4) {
                    Toast.makeText(this, getString(R.string.toast_error), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showSetAnswerDialog(question)
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun showSetAnswerDialog(question: String) {
        val dp16 = (16 * resources.displayMetrics.density).toInt()
        val etAnswer = EditText(this).apply {
            hint = getString(R.string.hint_secret_answer)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(question)
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp16, dp16, dp16, 0)
                addView(etAnswer)
            })
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                val answer = etAnswer.text.toString().trim()
                if (answer.length < 2) return@setPositiveButton
                WebSocketClient.send(mapOf(
                    "type"     to "set-secret-question",
                    "question" to question,
                    "answer"   to answer
                ))
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun saveProfile() {
        val nickname = binding.etNickname.text.toString().trim()
        val bio      = binding.etBio.text.toString().trim()
        val email    = binding.etEmail.text.toString().trim()
        val phone    = binding.etPhone.text.toString().trim()

        if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = "Invalid email address"
            return
        }

        WebSocketClient.send(mapOf(
            "type"  to "update-profile",
            "bio"   to bio.ifEmpty { null },
            "email" to email.ifEmpty { null },
            "phone" to phone.ifEmpty { null }
        ))

        WebSocketClient.send(mapOf(
            "type"     to "set-nickname",
            "nickname" to nickname
        ))

        if (nickname != Prefs.getMyNickname(this)) {
            Prefs.setMyNickname(this, nickname)
        }
    }

    private fun loadAvatar() {
        val me = Prefs.getUsername(this)
        if (me.isEmpty()) return
        val avatarFile = File(filesDir, "avatars/$me.jpg")
        val sizePx = (80 * resources.displayMetrics.density).toInt()
        if (avatarFile.exists()) {
            binding.ivAvatar.load(avatarFile) {
                transformations(CircleCropTransformation())
            }
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
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(this@MyProfileActivity, colorRes) }
                .also { canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, it) }
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = sizePx * 0.42f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }.also {
                val yOff = (it.descent() - it.ascent()) / 2 - it.descent()
                canvas.drawText(letter, sizePx / 2f, sizePx / 2f + yOff, it)
            }
            binding.ivAvatar.setImageBitmap(bmp)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
