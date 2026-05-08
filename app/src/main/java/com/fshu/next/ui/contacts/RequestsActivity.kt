package com.fshu.next.ui.contacts

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
import com.fshu.next.databinding.ActivityRequestsBinding
import com.fshu.next.databinding.ItemRequestReceivedBinding
import com.fshu.next.databinding.ItemRequestSentBinding
import com.fshu.next.util.MessageBus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RequestsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRequestsBinding

    data class ReceivedItem(
        val fromUser: String,
        val createdAt: Long,
        val intro: String?,
        val displayName: String?
    )

    data class SentItem(
        val contact: String,
        val status: String,
        val createdAt: Long
    )

    private val receivedList = mutableListOf<ReceivedItem>()
    private val sentList = mutableListOf<SentItem>()
    private lateinit var receivedAdapter: ReceivedAdapter
    private lateinit var sentAdapter: SentAdapter

    inner class ReceivedAdapter : RecyclerView.Adapter<ReceivedAdapter.VH>() {
        inner class VH(val b: ItemRequestReceivedBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemRequestReceivedBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = receivedList.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = receivedList[position]
            drawLetterAvatar(holder.b.ivAvatar, item.fromUser, 44)
            holder.b.tvUsername.text = item.fromUser
            if (!item.displayName.isNullOrEmpty() && item.displayName != item.fromUser) {
                holder.b.tvNickname.text = item.displayName
                holder.b.tvNickname.visibility = View.VISIBLE
            } else {
                holder.b.tvNickname.visibility = View.GONE
            }
            if (!item.intro.isNullOrEmpty()) {
                holder.b.tvIntro.text = "\"${item.intro}\""
                holder.b.tvIntro.visibility = View.VISIBLE
            } else {
                holder.b.tvIntro.visibility = View.GONE
            }
            holder.b.tvTimestamp.text = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(item.createdAt))
            holder.b.btnAccept.setOnClickListener {
                WebSocketClient.send(mapOf("type" to "contact-accept", "from" to item.fromUser))
                receivedList.removeAt(holder.adapterPosition)
                notifyItemRemoved(holder.adapterPosition)
                updateVisibility()
                Toast.makeText(this@RequestsActivity, getString(R.string.toast_request_accepted), Toast.LENGTH_SHORT).show()
            }
            holder.b.btnDecline.setOnClickListener {
                WebSocketClient.send(mapOf("type" to "contact-decline", "from" to item.fromUser))
                receivedList.removeAt(holder.adapterPosition)
                notifyItemRemoved(holder.adapterPosition)
                updateVisibility()
                Toast.makeText(this@RequestsActivity, getString(R.string.toast_request_declined), Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class SentAdapter : RecyclerView.Adapter<SentAdapter.VH>() {
        inner class VH(val b: ItemRequestSentBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemRequestSentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = sentList.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = sentList[position]
            drawLetterAvatar(holder.b.ivAvatar, item.contact, 44)
            holder.b.tvUsername.text = item.contact
            holder.b.tvStatus.text = item.status
            holder.b.btnCancel.setOnClickListener {
                WebSocketClient.send(mapOf("type" to "contact-cancel", "to" to item.contact))
                sentList.removeAt(holder.adapterPosition)
                notifyItemRemoved(holder.adapterPosition)
                updateVisibility()
                Toast.makeText(this@RequestsActivity, getString(R.string.toast_request_cancelled), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRequestsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.title_contact_requests)
            setDisplayHomeAsUpEnabled(true)
        }

        receivedAdapter = ReceivedAdapter()
        sentAdapter = SentAdapter()

        binding.rvReceived.apply {
            layoutManager = LinearLayoutManager(this@RequestsActivity)
            adapter = receivedAdapter
            isNestedScrollingEnabled = false
        }
        binding.rvSent.apply {
            layoutManager = LinearLayoutManager(this@RequestsActivity)
            adapter = sentAdapter
            isNestedScrollingEnabled = false
        }

        lifecycleScope.launch {
            MessageBus.events.collect { handleMessage(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (WebSocketClient.isConnected) {
            WebSocketClient.send(mapOf("type" to "contact-list"))
        }
    }

    private fun handleMessage(msg: JsonObject) {
        when (msg.get("type")?.asString) {
            "request-message" -> {
                WebSocketClient.send(mapOf("type" to "contact-list"))
            }
            "contact-list" -> {
                receivedList.clear()
                sentList.clear()
                msg.getAsJsonArray("pendingReceived")?.forEach { el ->
                    val obj = el.asJsonObject
                    val fromUser = obj.get("from_user")?.asString ?: return@forEach
                    val nickname = obj.get("nickname")?.takeIf { !it.isJsonNull }?.asString
                    val showNickname = obj.get("show_nickname")?.asInt ?: 1
                    receivedList.add(ReceivedItem(
                        fromUser  = fromUser,
                        createdAt = obj.get("created_at")?.asLong ?: System.currentTimeMillis(),
                        intro     = obj.get("intro")?.takeIf { !it.isJsonNull }?.asString,
                        displayName = if (showNickname == 1) nickname else null
                    ))
                }
                msg.getAsJsonArray("pendingSent")?.forEach { el ->
                    val obj = el.asJsonObject
                    sentList.add(SentItem(
                        contact   = obj.get("contact")?.asString ?: return@forEach,
                        status    = obj.get("status")?.asString ?: "pending",
                        createdAt = obj.get("created_at")?.asLong ?: System.currentTimeMillis()
                    ))
                }
                receivedAdapter.notifyDataSetChanged()
                sentAdapter.notifyDataSetChanged()
                updateVisibility()
            }
        }
    }

    private fun updateVisibility() {
        val hasReceived = receivedList.isNotEmpty()
        val hasSent = sentList.isNotEmpty()
        binding.tvHeaderReceived.visibility = if (hasReceived) View.VISIBLE else View.GONE
        binding.tvHeaderSent.visibility = if (hasSent) View.VISIBLE else View.GONE
        binding.dividerSections.visibility = if (hasReceived && hasSent) View.VISIBLE else View.GONE
        binding.tvEmpty.visibility = if (!hasReceived && !hasSent) View.VISIBLE else View.GONE
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
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(this@RequestsActivity, colorRes) }
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
