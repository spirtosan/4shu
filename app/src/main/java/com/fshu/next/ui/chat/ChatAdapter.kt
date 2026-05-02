package com.fshu.next.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.gson.JsonParser
import com.fshu.next.data.model.Message
import com.fshu.next.databinding.ItemListReceivedBinding
import com.fshu.next.databinding.ItemListSentBinding
import com.fshu.next.databinding.ItemLocationReceivedBinding
import com.fshu.next.databinding.ItemLocationRequestReceivedBinding
import com.fshu.next.databinding.ItemLocationRequestSentBinding
import com.fshu.next.databinding.ItemLocationSentBinding
import com.fshu.next.databinding.ItemMessageReceivedBinding
import com.fshu.next.databinding.ItemMessageSentBinding
import com.fshu.next.util.LocationHelper
import java.text.SimpleDateFormat
import java.util.*

class ChatAdapter : ListAdapter<Message, RecyclerView.ViewHolder>(DIFF) {
    private val fmtTime = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val fmtDayTime = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
    private val fmtFullTime = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

    /** Set by the host Activity to receive long-press events with the tapped message and anchor view. */
    var onMessageLongPress: ((Message, View) -> Unit)? = null

    /** Currently selected message IDs — empty means not in selection mode. */
    private val selectedIds = mutableSetOf<Long>()

    /** Called when selection changes — passes count of selected items. 0 = exited selection mode. */
    var onSelectionChanged: ((count: Int, singleMsg: Message?) -> Unit)? = null

    /** Invoked when the user taps a checkbox in a list bubble: (listId, itemId UUID, newDoneState). */
    var onListItemToggle: ((String, String, Boolean) -> Unit)? = null

    /** username → display name map, set by the host Activity. Used to resolve checkedBy labels. */
    var nicknameMap: Map<String, String> = emptyMap()

    /** Current user's username — set by ChatActivity so reaction chips can highlight own reactions. */
    var me: String = ""

    /** Invoked when a reaction chip is tapped: (message, emoji). Activity decides add/remove. */
    var onReactionTap: ((Message, String) -> Unit)? = null

    private fun getNickname(username: String): String? = nicknameMap[username]

    fun isInSelectionMode() = selectedIds.isNotEmpty()

    fun getSelectedIds(): List<Long> = selectedIds.toList()

    fun getSelectedMessages(): List<Message> =
        currentList.filter { it.id in selectedIds }

    fun clearSelection() {
        val affected = selectedIds.toSet()
        selectedIds.clear()
        currentList.forEachIndexed { index, msg ->
            if (msg.id in affected) notifyItemChanged(index)
        }
        onSelectionChanged?.invoke(0, null)
    }

    private fun toggleSelection(msg: Message) {
        if (msg.id in selectedIds) {
            selectedIds.remove(msg.id)
        } else {
            selectedIds.add(msg.id)
        }
        val index = currentList.indexOf(msg)
        if (index >= 0) notifyItemChanged(index)
        val selected = getSelectedMessages()
        onSelectionChanged?.invoke(
            selected.size,
            if (selected.size == 1) selected.first() else null
        )
    }

    // Kept for potential future use; currently auto-respond handles sharing.
    var onLocationRequestRespond: ((Message) -> Unit)? = null

    companion object {
        private const val SENT         = 0
        private const val RECV         = 1
        private const val SENT_LIST    = 2
        private const val RECV_LIST    = 3
        private const val SENT_LOC     = 4
        private const val RECV_LOC     = 5
        private const val SENT_LOC_REQ = 6
        private const val RECV_LOC_REQ = 7
        val DIFF = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(a: Message, b: Message) = a.id == b.id
            override fun areContentsTheSame(a: Message, b: Message) = a == b
        }

        private const val TICK_SINGLE = "✓"
        private const val TICK_DOUBLE = "✓✓"
        private val COLOR_GREY = Color.parseColor("#9E9E9E")
        private val COLOR_BLUE = Color.parseColor("#4FC3F7")
    }

    override fun getItemViewType(pos: Int): Int {
        val msg = getItem(pos)
        return when {
            msg.type == "list" && msg.isSent         -> SENT_LIST
            msg.type == "list"                       -> RECV_LIST
            msg.type == "location" && msg.isSent     -> SENT_LOC
            msg.type == "location"                   -> RECV_LOC
            msg.type == "location-request" && msg.isSent -> SENT_LOC_REQ
            msg.type == "location-request"           -> RECV_LOC_REQ
            msg.isSent                               -> SENT
            else                                     -> RECV
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            SENT         -> SentVH(ItemMessageSentBinding.inflate(inf, parent, false))
            RECV         -> RecvVH(ItemMessageReceivedBinding.inflate(inf, parent, false))
            SENT_LIST    -> SentListVH(ItemListSentBinding.inflate(inf, parent, false))
            RECV_LIST    -> RecvListVH(ItemListReceivedBinding.inflate(inf, parent, false))
            SENT_LOC     -> SentLocVH(ItemLocationSentBinding.inflate(inf, parent, false))
            RECV_LOC     -> RecvLocVH(ItemLocationReceivedBinding.inflate(inf, parent, false))
            SENT_LOC_REQ -> SentLocReqVH(ItemLocationRequestSentBinding.inflate(inf, parent, false))
            else         -> RecvLocReqVH(ItemLocationRequestReceivedBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val msg = getItem(pos)
        val now = Calendar.getInstance()
        val msgCal = Calendar.getInstance().apply { timeInMillis = msg.timestamp }
        val time = when {
            msgCal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            msgCal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) ->
                fmtTime.format(Date(msg.timestamp))
            msgCal.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                fmtDayTime.format(Date(msg.timestamp))
            else ->
                fmtFullTime.format(Date(msg.timestamp))
        }
        when (holder) {
            is SentVH -> {
                bindFileContent(holder.b.ivThumbnail, holder.b.tvContent, msg, holder.b.bubble)
                bindReplyQuote(holder.b.replyQuote, holder.b.tvReplySender, holder.b.tvReplyContent, msg)
                bindReactions(holder.b.llReactions, msg)
                holder.b.tvTime.text = time
                when (msg.status) {
                    "SENDING" -> {
                        holder.b.pbSending.visibility = View.VISIBLE
                        holder.b.tvStatus.visibility = View.GONE
                    }
                    "SENT" -> {
                        holder.b.pbSending.visibility = View.GONE
                        holder.b.tvStatus.visibility = View.VISIBLE
                        holder.b.tvStatus.text = TICK_SINGLE
                        holder.b.tvStatus.setTextColor(COLOR_GREY)
                    }
                    "DELIVERED" -> {
                        holder.b.pbSending.visibility = View.GONE
                        holder.b.tvStatus.visibility = View.VISIBLE
                        holder.b.tvStatus.text = TICK_DOUBLE
                        holder.b.tvStatus.setTextColor(COLOR_GREY)
                    }
                    "READ" -> {
                        holder.b.pbSending.visibility = View.GONE
                        holder.b.tvStatus.visibility = View.VISIBLE
                        holder.b.tvStatus.text = TICK_DOUBLE
                        holder.b.tvStatus.setTextColor(COLOR_BLUE)
                    }
                    else -> {
                        holder.b.pbSending.visibility = View.GONE
                        holder.b.tvStatus.visibility = View.GONE
                    }
                }
                holder.b.bubble.setOnClickListener {
                    if (isInSelectionMode()) {
                        toggleSelection(msg)
                    } else if (msg.type == "file") {
                        openFile(it, msg)
                    }
                }
                holder.b.bubble.alpha = if (msg.id in selectedIds) 0.5f else 1.0f
            }
            is RecvVH -> {
                bindFileContent(holder.b.ivThumbnail, holder.b.tvContent, msg, holder.b.bubble)
                bindReplyQuote(holder.b.replyQuote, holder.b.tvReplySender, holder.b.tvReplyContent, msg)
                bindReactions(holder.b.llReactions, msg)
                holder.b.tvTime.text = time
                holder.b.bubble.setOnClickListener {
                    if (isInSelectionMode()) {
                        toggleSelection(msg)
                    } else if (msg.type == "file") {
                        openFile(it, msg)
                    }
                }
                holder.b.bubble.alpha = if (msg.id in selectedIds) 0.5f else 1.0f
            }
            is SentListVH -> {
                holder.b.tvTime.text = time
                when (msg.status) {
                    "SENDING" -> { holder.b.pbSending.visibility = View.VISIBLE; holder.b.tvStatus.visibility = View.GONE }
                    "SENT"    -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_SINGLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
                    "DELIVERED" -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
                    "READ"    -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_BLUE) }
                    else      -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.GONE }
                }
                bindListItems(holder.b.llItems, msg)
                holder.b.bubble.setOnLongClickListener { _ ->
                    toggleSelection(msg)
                    true
                }
                holder.b.bubble.setOnClickListener {
                    if (isInSelectionMode()) toggleSelection(msg)
                }
                holder.b.bubble.alpha = if (msg.id in selectedIds) 0.5f else 1.0f
            }
            is RecvListVH -> {
                holder.b.tvTime.text = time
                bindListItems(holder.b.llItems, msg)
                holder.b.bubble.setOnLongClickListener { _ ->
                    toggleSelection(msg)
                    true
                }
                holder.b.bubble.setOnClickListener {
                    if (isInSelectionMode()) toggleSelection(msg)
                }
                holder.b.bubble.alpha = if (msg.id in selectedIds) 0.5f else 1.0f
            }
            is SentLocVH -> {
                bindLocationContent(holder.b.tvCoords, holder.b.tvAccuracy, holder.b.btnMaps, msg)
                holder.b.tvTime.text = time
                when (msg.status) {
                    "SENDING" -> { holder.b.pbSending.visibility = View.VISIBLE; holder.b.tvStatus.visibility = View.GONE }
                    "SENT"    -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_SINGLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
                    "DELIVERED" -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
                    "READ"    -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_BLUE) }
                    else      -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.GONE }
                }
            }
            is RecvLocVH -> {
                bindLocationContent(holder.b.tvCoords, holder.b.tvAccuracy, holder.b.btnMaps, msg)
                holder.b.tvTime.text = time
            }
            is SentLocReqVH -> {
                val json = try { JsonParser.parseString(msg.content).asJsonObject } catch (e: Exception) { null }
                val fulfilled = json?.get("fulfilled")?.asBoolean ?: false
                holder.b.tvContent.text = if (fulfilled)
                    "\uD83D\uDCCD Location received \u2713"
                else
                    "\uD83D\uDCCD Location requested"
                holder.b.tvTime.text = time
                when (msg.status) {
                    "SENDING"   -> { holder.b.pbSending.visibility = View.VISIBLE; holder.b.tvStatus.visibility = View.GONE }
                    "SENT"      -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_SINGLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
                    "DELIVERED" -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
                    "READ"      -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_BLUE) }
                    else        -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.GONE }
                }
            }
            is RecvLocReqVH -> {
                val json = try { JsonParser.parseString(msg.content).asJsonObject } catch (e: Exception) { null }
                val hasCoords = json?.has("lat") == true
                val shared = json?.get("shared")?.asBoolean ?: false
                if (shared || hasCoords) {
                    holder.b.tvContent.text = "\uD83D\uDCCD Location shared automatically"
                } else {
                    holder.b.tvContent.text = "\uD83D\uDCCD ${msg.from} requested your location"
                }
                if (hasCoords && json != null) {
                    holder.b.tvCoords.visibility = View.VISIBLE
                    holder.b.tvAccuracy.visibility = View.VISIBLE
                    holder.b.btnMaps.visibility = View.VISIBLE
                    bindLocationContent(holder.b.tvCoords, holder.b.tvAccuracy, holder.b.btnMaps, msg)
                } else {
                    holder.b.tvCoords.visibility = View.GONE
                    holder.b.tvAccuracy.visibility = View.GONE
                    holder.b.btnMaps.visibility = View.GONE
                }
                // Show ticks when coords present (auto-respond fired)
                if (hasCoords || shared) {
                    when (msg.status) {
                        "SENDING"   -> { holder.b.pbSending.visibility = View.VISIBLE; holder.b.tvStatus.visibility = View.GONE }
                        "SENT"      -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_SINGLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
                        "DELIVERED" -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
                        "READ"      -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_BLUE) }
                        else        -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.GONE }
                    }
                } else {
                    holder.b.pbSending.visibility = View.GONE
                    holder.b.tvStatus.visibility = View.GONE
                }
                holder.b.tvTime.text = time
            }
        }
    }

    private fun bindReplyQuote(
        quoteBlock: View,
        senderView: TextView,
        contentView: TextView,
        msg: Message
    ) {
        if (msg.replyToId != null) {
            quoteBlock.visibility = View.VISIBLE
            senderView.text = msg.replyToSender ?: ""
            contentView.text = msg.replyToContent ?: ""
        } else {
            quoteBlock.visibility = View.GONE
        }
    }

    private fun bindFileContent(iv: ImageView, tv: TextView, msg: Message, bubble: View) {
        bubble.setOnLongClickListener { toggleSelection(msg); true }

        if (msg.type == "deleted") {
            iv.visibility = View.GONE
            tv.visibility = View.VISIBLE
            tv.setTypeface(null, android.graphics.Typeface.ITALIC)
            tv.setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
            tv.movementMethod = null
            tv.isLongClickable = false
            tv.text = if (msg.isSent) {
                "[Message deleted]"
            } else {
                try {
                    val json = JsonParser.parseString(msg.content).asJsonObject
                    val deletedBy = json.get("deletedBy")?.asString ?: msg.from
                    val deletedAt = json.get("deletedAt")?.asLong ?: msg.timestamp
                    val displayName = getNickname(deletedBy) ?: deletedBy
                    val now = Calendar.getInstance()
                    val delCal = Calendar.getInstance().apply { timeInMillis = deletedAt }
                    val timeFmt = when {
                        delCal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                        delCal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) ->
                            fmtTime.format(Date(deletedAt))
                        delCal.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                            fmtDayTime.format(Date(deletedAt))
                        else ->
                            fmtFullTime.format(Date(deletedAt))
                    }
                    "[Message deleted by $displayName · $timeFmt]"
                } catch (e: Exception) {
                    "[Message deleted]"
                }
            }
            return
        }
        // Reset styles that may linger from a recycled deleted cell
        tv.setTypeface(null, android.graphics.Typeface.NORMAL)
        tv.setTextColor(tv.context.getColor(com.fshu.next.R.color.text_primary))

        val isImage = msg.type == "file" && msg.mimeType?.startsWith("image/") == true
        if (isImage && msg.localUri != null) {
            iv.visibility = View.VISIBLE
            tv.visibility = View.GONE
            tv.isLongClickable = false
            iv.load(Uri.parse(msg.localUri))
        } else {
            iv.visibility = View.GONE
            tv.visibility = View.VISIBLE
            tv.text = msg.content
            android.text.util.Linkify.addLinks(tv, android.text.util.Linkify.WEB_URLS)
            tv.movementMethod = if (tv.urls.isNotEmpty())
                android.text.method.LinkMovementMethod.getInstance()
            else
                null
            tv.setLinkTextColor(android.graphics.Color.parseColor("#E8711A"))
            tv.setOnLongClickListener { bubble.performLongClick(); true }
            if (msg.editedAt > 0L) {
                val full = android.text.SpannableStringBuilder(tv.text)
                val start = full.length
                full.append(" · edited")
                full.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                    start, full.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                full.setSpan(android.text.style.RelativeSizeSpan(0.8f),
                    start, full.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                full.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#9E9E9E")),
                    start, full.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                tv.text = full
            }
        }
    }

    private fun bindReactions(container: LinearLayout, msg: Message) {
        container.removeAllViews()
        val json = msg.reactions
        if (json.isEmpty()) { container.visibility = View.GONE; return }
        val arr = try { JsonParser.parseString(json).asJsonArray } catch (e: Exception) {
            container.visibility = View.GONE; return
        }
        if (arr.size() == 0) { container.visibility = View.GONE; return }

        // Group by emoji → count
        val counts = linkedMapOf<String, Int>()
        for (i in 0 until arr.size()) {
            val item = try { arr[i].asJsonObject } catch (e: Exception) { continue }
            val emoji = item.get("emoji")?.asString ?: continue
            counts[emoji] = (counts[emoji] ?: 0) + 1
        }
        // Find current user's reaction
        val myEmoji: String? = run {
            for (i in 0 until arr.size()) {
                val item = try { arr[i].asJsonObject } catch (e: Exception) { continue }
                if (item.get("from")?.asString == me) return@run item.get("emoji")?.asString
            }
            null
        }

        container.visibility = View.VISIBLE
        val ctx = container.context
        val dp = ctx.resources.displayMetrics.density
        val hPad = (7 * dp).toInt()
        val vPad = (3 * dp).toInt()
        val marginEnd = (4 * dp).toInt()

        for ((emoji, count) in counts) {
            val isMine = emoji == myEmoji
            val chip = android.widget.TextView(ctx).apply {
                text = if (count > 1) "$emoji $count" else emoji
                textSize = 13f
                setPadding(hPad, vPad, hPad, vPad)
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 14 * dp
                    setColor(if (isMine) 0xFF2979AA.toInt() else 0x44000000.toInt())
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = marginEnd }
                setOnClickListener { onReactionTap?.invoke(msg, emoji) }
            }
            container.addView(chip)
        }
    }

    private fun openFile(view: View, msg: Message) {
        val uriStr = msg.localUri ?: run {
            Toast.makeText(view.context, "File not available", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = Uri.parse(uriStr)
            val mime = msg.mimeType ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            view.context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(view.context, "No app found to open this file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(view.context, "File no longer available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindListItems(container: LinearLayout, msg: Message) {
        container.removeAllViews()
        val listId = msg.listId ?: return
        val canToggle = !msg.isSent
        val arr = try { JsonParser.parseString(msg.content).asJsonArray } catch (e: Exception) { return }
        for (i in 0 until arr.size()) {
            val item = try { arr[i].asJsonObject } catch (e: Exception) { continue }
            // Skip soft-deleted items
            val deletedAt = item.get("deletedAt")
            if (deletedAt != null && !deletedAt.isJsonNull) continue
            val itemId = item.get("id")?.asString ?: continue
            val text = item.get("text")?.asString ?: ""
            val done = item.get("done")?.asBoolean ?: false
            val checkedBy = item.get("checkedBy")?.takeIf { !it.isJsonNull }?.asString
            val checkedByDisplay = if (checkedBy != null) {
                getNickname(checkedBy) ?: checkedBy
            } else null
            val label = if (done && checkedByDisplay != null) "$text  ✓ $checkedByDisplay" else text
            val cb = CheckBox(container.context).apply {
                this.text = label
                setOnCheckedChangeListener(null)
                isChecked = done
                paintFlags = if (done) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                             else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                isEnabled = canToggle
                isClickable = canToggle
                if (canToggle) {
                    setOnCheckedChangeListener { _, isChecked ->
                        paintFlags = if (isChecked) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                                     else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                        onListItemToggle?.invoke(listId, itemId, isChecked)
                    }
                }
            }
            container.addView(cb)
        }
    }

    private fun bindLocationContent(
        tvCoords: TextView,
        tvAccuracy: TextView,
        btnMaps: android.widget.Button,
        msg: Message
    ) {
        try {
            val json = JsonParser.parseString(msg.content).asJsonObject
            val lat = json.get("lat")?.asDouble ?: 0.0
            val lon = json.get("lon")?.asDouble ?: 0.0
            val accuracy = json.get("accuracy")?.asFloat ?: 0f
            val mapsUrl = json.get("mapsUrl")?.asString ?: LocationHelper.buildMapsUrl(lat, lon)
            tvCoords.text = "\uD83D\uDCCD ${LocationHelper.formatCoordinates(lat, lon)}"
            tvAccuracy.text = "\u00B1%.0fm".format(accuracy)
            btnMaps.setOnClickListener { view ->
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(view.context, "Maps not available", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            tvCoords.text = "\uD83D\uDCCD Location"
            tvAccuracy.text = ""
        }
    }

    inner class SentVH(val b: ItemMessageSentBinding) : RecyclerView.ViewHolder(b.root)
    inner class RecvVH(val b: ItemMessageReceivedBinding) : RecyclerView.ViewHolder(b.root)
    inner class SentListVH(val b: ItemListSentBinding) : RecyclerView.ViewHolder(b.root)
    inner class RecvListVH(val b: ItemListReceivedBinding) : RecyclerView.ViewHolder(b.root)
    inner class SentLocVH(val b: ItemLocationSentBinding) : RecyclerView.ViewHolder(b.root)
    inner class RecvLocVH(val b: ItemLocationReceivedBinding) : RecyclerView.ViewHolder(b.root)
    inner class SentLocReqVH(val b: ItemLocationRequestSentBinding) : RecyclerView.ViewHolder(b.root)
    inner class RecvLocReqVH(val b: ItemLocationRequestReceivedBinding) : RecyclerView.ViewHolder(b.root)
}
