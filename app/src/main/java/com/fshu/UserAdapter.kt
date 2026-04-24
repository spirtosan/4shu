package com.fshu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.fshu.R
import com.fshu.data.model.User
import com.fshu.databinding.ItemUserBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.absoluteValue

class UserAdapter(
    private val users: List<User>,
    private val onClick: (User) -> Unit,
    private val onCall: (User) -> Unit = {},
    private val onVideoCall: (User) -> Unit = {},
    private val onTestConnection: (User) -> Unit = {},
    private val onEmergencyCall: (User) -> Unit = {},
    private val onEmergencyWithLocation: (User) -> Unit = {},
    private val onRequestLocation: (User) -> Unit = {}
) : RecyclerView.Adapter<UserAdapter.VH>() {

    inner class VH(val binding: ItemUserBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val user = users[position]
        val ctx = holder.itemView.context

        val avatarFile = File(ctx.filesDir, "avatars/${user.username}.jpg")
        val sizePx = (48 * ctx.resources.displayMetrics.density).toInt()
        if (avatarFile.exists()) {
            holder.binding.ivAvatar.load(avatarFile) {
                transformations(CircleCropTransformation())
                crossfade(true)
            }
        } else {
            val letter = user.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            holder.binding.ivAvatar.setImageBitmap(
                createLetterBitmap(letter, avatarColor(user.username, ctx), sizePx)
            )
        }

        // Online indicator dot
        holder.binding.viewOnlineDot.setBackgroundResource(
            if (user.online) R.drawable.bg_online_dot else R.drawable.bg_offline_dot
        )

        holder.binding.tvUsername.text = user.displayName
        if (user.nickname.isNullOrBlank()) {
            holder.binding.tvHandle.visibility = android.view.View.GONE
        } else {
            holder.binding.tvHandle.visibility = android.view.View.VISIBLE
            holder.binding.tvHandle.text = "@${user.username}"
        }

        // Show last seen for offline users, last message preview otherwise
        holder.binding.tvLastMessage.text = when {
            !user.online && user.lastSeen != null -> formatLastSeen(user.lastSeen)
            else -> user.lastMessage ?: ""
        }

        // Timestamp
        holder.binding.tvTime.text = user.lastMessageTime?.let { formatTime(it) } ?: ""

        holder.itemView.setOnClickListener { onClick(user) }

        holder.binding.btnMore.setOnClickListener { anchor ->
            PopupMenu(anchor.context, anchor).apply {
                menuInflater.inflate(R.menu.menu_user, menu)
                // Tint emergency items red
                for (id in listOf(R.id.action_user_emergency_call, R.id.action_user_emergency_location)) {
                    menu.findItem(id)?.let { item ->
                        val s = SpannableString(item.title)
                        s.setSpan(ForegroundColorSpan(Color.parseColor("#E53935")), 0, s.length, 0)
                        item.title = s
                    }
                }
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_user_call -> { onCall(user); true }
                        R.id.action_user_video_call -> { onVideoCall(user); true }
                        R.id.action_user_test_connection -> { onTestConnection(user); true }
                        R.id.action_user_emergency_call -> { onEmergencyCall(user); true }
                        R.id.action_user_emergency_location -> { onEmergencyWithLocation(user); true }
                        else -> false
                    }
                }
                show()
            }
        }
    }

    override fun getItemCount() = users.size

    /** Called by ItemTouchHelper during drag — swaps adjacent items live. */
    fun moveItem(from: Int, to: Int) {
        java.util.Collections.swap(users as MutableList<User>, from, to)
        notifyItemMoved(from, to)
    }

    private fun createLetterBitmap(letter: String, color: Int, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, bgPaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = sizePx * 0.42f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val yPos = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(letter, sizePx / 2f, yPos, textPaint)
        return bmp
    }

    private fun avatarColor(username: String, ctx: android.content.Context): Int {
        val colorRes = when (username.hashCode().absoluteValue % 10) {
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
        return ctx.getColor(colorRes)
    }

    private fun formatLastSeen(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / 60_000
        val hours   = diff / 3_600_000
        val days    = diff / 86_400_000
        return when {
            minutes < 1   -> "last seen just now"
            minutes < 60  -> "last seen ${minutes}m ago"
            hours   < 24  -> "last seen ${hours}h ago"
            days    < 7   -> "last seen ${days}d ago"
            else          -> "last seen ${SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestamp))}"
        }
    }

    private fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()

        return when {
            cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) ->
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

            now - timestamp < 7L * 24 * 60 * 60 * 1000 ->
                SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))

            else ->
                SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
