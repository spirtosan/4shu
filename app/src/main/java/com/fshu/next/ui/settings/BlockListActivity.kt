package com.fshu.next.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.JsonObject
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityBlockListBinding
import com.fshu.next.databinding.ItemBlockBinding
import com.fshu.next.util.MessageBus
import kotlinx.coroutines.launch

class BlockListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockListBinding

    data class BlockItem(val username: String)

    private val blockList = mutableListOf<BlockItem>()
    private lateinit var blockAdapter: BlockAdapter

    inner class BlockAdapter : RecyclerView.Adapter<BlockAdapter.VH>() {
        inner class VH(val b: ItemBlockBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemBlockBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = blockList.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = blockList[position]
            drawLetterAvatar(holder.b.ivAvatar, item.username, 44)
            holder.b.tvUsername.text = item.username
            holder.b.btnUnblock.setOnClickListener {
                WebSocketClient.send(mapOf("type" to "unblock", "target" to item.username))
                blockList.removeAt(holder.bindingAdapterPosition)
                notifyItemRemoved(holder.bindingAdapterPosition)
                updateVisibility()
                Toast.makeText(this@BlockListActivity, getString(R.string.toast_unblocked, item.username), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.title_blocked_users)
            setDisplayHomeAsUpEnabled(true)
        }

        blockAdapter = BlockAdapter()
        binding.rvBlocks.apply {
            layoutManager = LinearLayoutManager(this@BlockListActivity)
            adapter = blockAdapter
        }

        lifecycleScope.launch {
            MessageBus.events.collect { handleMessage(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (WebSocketClient.isConnected) {
            WebSocketClient.send(mapOf("type" to "block-list"))
        }
    }

    private fun handleMessage(msg: JsonObject) {
        if (msg.get("type")?.asString != "block-list") return
        blockList.clear()
        msg.getAsJsonArray("blocks")?.forEach { el ->
            val username = el.asJsonObject.get("username")?.asString ?: return@forEach
            blockList.add(BlockItem(username))
        }
        blockAdapter.notifyDataSetChanged()
        updateVisibility()
    }

    private fun updateVisibility() {
        val empty = blockList.isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvBlocks.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun drawLetterAvatar(iv: ImageView, username: String, sizeDp: Int) {
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
        val letter = username.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val colorRes = when (username.hashCode().let { if (it < 0) -it else it } % 10) {
            0 -> R.color.avatar_1; 1 -> R.color.avatar_2; 2 -> R.color.avatar_3
            3 -> R.color.avatar_4; 4 -> R.color.avatar_5; 5 -> R.color.avatar_6
            6 -> R.color.avatar_7; 7 -> R.color.avatar_8; 8 -> R.color.avatar_9
            else -> R.color.avatar_10
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(this@BlockListActivity, colorRes) }
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
        iv.setImageBitmap(bmp)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
