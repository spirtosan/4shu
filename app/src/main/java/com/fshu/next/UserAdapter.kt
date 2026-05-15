package com.fshu.next

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
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import coil.transform.CircleCropTransformation
import com.fshu.next.R
import com.fshu.next.data.model.User
import com.fshu.next.databinding.ItemFavoritesDividerBinding
import com.fshu.next.databinding.ItemUserBinding
import com.fshu.next.util.Prefs
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
    private val onRequestLocation: (User) -> Unit = {},
    private val onSetNickname: (User) -> Unit = {},
    private val onToggleFavorite: (User) -> Unit = {},
    private val onMuteToggle: (User) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_DIVIDER = 1
        const val DIVIDER_USERNAME = "_divider_favorites"
    }

    inner class VH(val binding: ItemUserBinding) : RecyclerView.ViewHolder(binding.root)
    inner class DividerVH(val binding: ItemFavoritesDividerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int =
        if (users[position].username == DIVIDER_USERNAME) TYPE_DIVIDER else TYPE_USER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_DIVIDER) {
            DividerVH(ItemFavoritesDividerBinding.inflate(inflater, parent, false))
        } else {
            VH(ItemUserBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is DividerVH) return
        val h = holder as VH
        val user = users[position]
        val ctx = h.itemView.context
        val sizePx = (48 * ctx.resources.displayMetrics.density).toInt()

        if (user.isGroup) {
            val personalFile = user.personalAvatar?.let { File(it) }?.takeIf { it.exists() }
            val groupFile = user.avatarPath?.let { File(it) }?.takeIf { it.exists() }
            when {
                personalFile != null -> h.binding.ivAvatar.load(personalFile) {
                    transformations(CircleCropTransformation())
                    crossfade(false)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    diskCachePolicy(CachePolicy.ENABLED)
                    placeholder(h.binding.ivAvatar.drawable)
                }
                groupFile != null -> h.binding.ivAvatar.load(groupFile) {
                    transformations(CircleCropTransformation())
                    crossfade(false)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    diskCachePolicy(CachePolicy.ENABLED)
                    placeholder(h.binding.ivAvatar.drawable)
                }
                else -> {
                    val letter = user.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "G"
                    h.binding.ivAvatar.load(createLetterBitmap(letter, avatarColor(user.displayName, ctx), sizePx))
                }
            }
            h.binding.viewOnlineDot.visibility = android.view.View.INVISIBLE
            h.binding.tvUsername.text = user.displayName
            h.binding.tvHandle.visibility = android.view.View.GONE
            h.binding.tvLastMessage.text = user.lastMessage ?: ""
            h.binding.tvTime.text = user.lastMessageTime?.let { formatTime(it) } ?: ""
            h.itemView.setOnClickListener { onClick(user) }
            h.binding.btnMore.visibility = android.view.View.GONE
            h.binding.tvFavStar.visibility = android.view.View.GONE
            return
        }

        val contactNick = Prefs.getContactNickname(ctx, user.username)
        val displayName = contactNick.ifEmpty { user.displayName }

        val avatarFile = File(ctx.filesDir, "avatars/${user.username}.jpg")
        if (avatarFile.exists()) {
            h.binding.ivAvatar.load(avatarFile) {
                transformations(CircleCropTransformation())
                crossfade(false)
                memoryCachePolicy(CachePolicy.ENABLED)
                diskCachePolicy(CachePolicy.ENABLED)
                placeholder(h.binding.ivAvatar.drawable)
            }
        } else {
            val letter = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            h.binding.ivAvatar.setImageBitmap(createLetterBitmap(letter, avatarColor(user.username, ctx), sizePx))
        }

        h.binding.viewOnlineDot.visibility = android.view.View.VISIBLE
        h.binding.viewOnlineDot.setBackgroundResource(
            if (user.online) R.drawable.bg_online_dot else R.drawable.bg_offline_dot
        )

        h.binding.tvUsername.text = displayName
        if (contactNick.isNotEmpty() || !user.nickname.isNullOrBlank()) {
            h.binding.tvHandle.visibility = android.view.View.VISIBLE
            h.binding.tvHandle.text = "@${user.username}"
        } else {
            h.binding.tvHandle.visibility = android.view.View.GONE
        }

        h.binding.tvLastMessage.text = when {
            !user.online && user.lastSeen != null -> formatLastSeen(user.lastSeen)
            else -> user.lastMessage ?: ""
        }

        h.binding.tvTime.text = user.lastMessageTime?.let { formatTime(it) } ?: ""

        h.itemView.setOnClickListener { onClick(user) }

        // Star / favorite button
        h.binding.tvFavStar.visibility = android.view.View.VISIBLE
        if (user.isFavorite) {
            h.binding.tvFavStar.text = "★"
            h.binding.tvFavStar.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
        } else {
            h.binding.tvFavStar.text = "☆"
            h.binding.tvFavStar.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }
        h.binding.tvFavStar.setOnClickListener { onToggleFavorite(user) }

        h.binding.tvMuteIcon.visibility =
            if (user.isMuted) android.view.View.VISIBLE else android.view.View.GONE

        h.binding.btnMore.visibility = android.view.View.VISIBLE
        h.binding.btnMore.setOnClickListener { anchor ->
            PopupMenu(anchor.context, anchor).apply {
                menuInflater.inflate(R.menu.menu_user, menu)
                for (id in listOf(R.id.action_user_emergency_call, R.id.action_user_emergency_location)) {
                    menu.findItem(id)?.let { item ->
                        val s = SpannableString(item.title)
                        s.setSpan(ForegroundColorSpan(Color.parseColor("#E53935")), 0, s.length, 0)
                        item.title = s
                    }
                }
                menu.findItem(R.id.action_user_mute)?.title =
                    if (user.isMuted) anchor.context.getString(R.string.action_unmute)
                    else anchor.context.getString(R.string.action_mute)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_user_call -> { onCall(user); true }
                        R.id.action_user_video_call -> { onVideoCall(user); true }
                        R.id.action_user_test_connection -> { onTestConnection(user); true }
                        R.id.action_user_emergency_call -> { onEmergencyCall(user); true }
                        R.id.action_user_emergency_location -> { onEmergencyWithLocation(user); true }
                        R.id.action_user_set_nickname -> { onSetNickname(user); true }
                        R.id.action_user_mute -> { onMuteToggle(user); true }
                        else -> false
                    }
                }
                show()
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && payloads[0] == "message_update") {
            if (holder is DividerVH) return
            val h = holder as VH
            val user = users[position]
            h.binding.tvLastMessage.text = when {
                !user.online && user.lastSeen != null -> formatLastSeen(user.lastSeen)
                else -> user.lastMessage ?: ""
            }
            h.binding.tvTime.text = user.lastMessageTime?.let { formatTime(it) } ?: ""
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount() = users.size

    fun moveItem(from: Int, to: Int) {
        java.util.Collections.swap(users as MutableList<User>, from, to)
        notifyItemMoved(from, to)
    }

    private fun createLetterBitmap(letter: String, color: Int, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            .also { canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, it) }
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = sizePx * 0.42f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }.also {
            val yPos = sizePx / 2f - (it.descent() + it.ascent()) / 2f
            canvas.drawText(letter, sizePx / 2f, yPos, it)
        }
        return bmp
    }

    private fun avatarColor(username: String, ctx: android.content.Context): Int {
        val colorRes = when (username.hashCode().absoluteValue % 10) {
            0 -> R.color.avatar_1; 1 -> R.color.avatar_2; 2 -> R.color.avatar_3
            3 -> R.color.avatar_4; 4 -> R.color.avatar_5; 5 -> R.color.avatar_6
            6 -> R.color.avatar_7; 7 -> R.color.avatar_8; 8 -> R.color.avatar_9
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
