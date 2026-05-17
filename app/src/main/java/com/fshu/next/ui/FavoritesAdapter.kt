package com.fshu.next.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.fshu.next.R
import com.fshu.next.data.model.User
import com.fshu.next.databinding.ItemFavoriteBinding
import com.fshu.next.util.Prefs
import java.io.File

class FavoritesAdapter(
    private val onClick: (User) -> Unit
) : ListAdapter<User, FavoritesAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<User>() {
        override fun areItemsTheSame(a: User, b: User) = a.username == b.username
        override fun areContentsTheSame(a: User, b: User) = a == b
    }

    inner class VH(val binding: ItemFavoriteBinding) : RecyclerView.ViewHolder(binding.root) {
        init { binding.root.setOnClickListener { onClick(getItem(bindingAdapterPosition)) } }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFavoriteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val user = getItem(position)
        val b = holder.binding
        val ctx = b.root.context

        val nick = Prefs.getContactNickname(ctx, user.username)
        b.tvFavName.text = nick.ifEmpty { user.displayName }

        val cornerPx = 12f * ctx.resources.displayMetrics.density
        val avatarFile = File(ctx.filesDir, "avatars/${user.username}.jpg")
        if (avatarFile.exists()) {
            b.ivFavAvatar.load(avatarFile)
        } else {
            val letter = user.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val colorRes = when (user.username.hashCode().let { if (it < 0) -it else it } % 10) {
                0 -> R.color.avatar_1; 1 -> R.color.avatar_2; 2 -> R.color.avatar_3
                3 -> R.color.avatar_4; 4 -> R.color.avatar_5; 5 -> R.color.avatar_6
                6 -> R.color.avatar_7; 7 -> R.color.avatar_8; 8 -> R.color.avatar_9
                else -> R.color.avatar_10
            }
            val sizePx = (52 * ctx.resources.displayMetrics.density).toInt()
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(colorRes) }.also { p ->
                canvas.drawRoundRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), cornerPx, cornerPx, p)
            }
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = sizePx * 0.42f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }.also { p ->
                val yPos = sizePx / 2f - (p.descent() + p.ascent()) / 2f
                canvas.drawText(letter, sizePx / 2f, yPos, p)
            }
            b.ivFavAvatar.setImageBitmap(bmp)
        }
    }
}
