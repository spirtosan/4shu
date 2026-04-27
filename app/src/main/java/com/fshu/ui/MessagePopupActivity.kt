package com.fshu.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.transform.CircleCropTransformation
import com.fshu.R
import com.fshu.ui.chat.ChatActivity
import java.io.File

/**
 * Lightweight popup shown over the lock screen when a message arrives.
 * Shows sender avatar, name, and message preview.
 * TODO: Add option to hide message content for privacy (show only sender name).
 * Auto-dismisses after AUTO_DISMISS_MS milliseconds.
 */
class MessagePopupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FROM = "from"
        const val EXTRA_CONTENT = "content"
        const val EXTRA_DISPLAY_NAME = "display_name"
        private const val AUTO_DISMISS_MS = 5000L

        fun createIntent(ctx: Context, from: String, displayName: String, content: String) =
            Intent(ctx, MessagePopupActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                putExtra(EXTRA_FROM, from)
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_CONTENT, content)
            }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen and turn screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Position as a floating card at the top of the screen
        window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        window.attributes = window.attributes.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            y = 48
        }
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val from = intent.getStringExtra(EXTRA_FROM) ?: run { finish(); return }
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: from
        val content = intent.getStringExtra(EXTRA_CONTENT) ?: ""

        // Build UI programmatically — no layout file needed for this simple popup
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundResource(R.drawable.bg_popup_message)
        }

        // Avatar
        val sizePx = (48 * resources.displayMetrics.density).toInt()
        val avatarView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginEnd = (12 * resources.displayMetrics.density).toInt()
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            setBackgroundResource(R.drawable.bg_avatar)
        }
        val avatarFile = File(filesDir, "avatars/$from.jpg")
        if (avatarFile.exists()) {
            avatarView.load(avatarFile) {
                transformations(CircleCropTransformation())
            }
        } else {
            val letter = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val colorRes = when (from.hashCode().let { if (it < 0) -it else it } % 10) {
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
            val color = getColor(colorRes)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }.also {
                canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, it)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textSize = sizePx * 0.42f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }.also {
                val yPos = sizePx / 2f - (it.descent() + it.ascent()) / 2f
                canvas.drawText(letter, sizePx / 2f, yPos, it)
            }
            avatarView.setImageBitmap(bmp)
        }
        root.addView(avatarView)

        // Text column
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvName = TextView(this).apply {
            text = displayName
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val tvContent = TextView(this).apply {
            text = content
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textCol.addView(tvName)
        textCol.addView(tvContent)
        root.addView(textCol)

        root.isClickable = true
        root.isFocusable = true
        root.setOnClickListener {
            handler.removeCallbacks(autoDismiss)
            startActivity(Intent(this, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ChatActivity.EXTRA_PEER, from)
            })
            finish()
        }

        setContentView(root)

        // Auto-dismiss
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoDismiss)
    }
}
