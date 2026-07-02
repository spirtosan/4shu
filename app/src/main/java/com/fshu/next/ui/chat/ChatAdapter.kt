package com.fshu.next.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.color.MaterialColors
import com.google.gson.JsonParser
import com.fshu.next.data.model.ListItem
import com.fshu.next.data.model.Message
import com.fshu.next.databinding.ItemListReceivedBinding
import com.fshu.next.databinding.ItemListSentBinding
import com.fshu.next.poll.ParsedPoll
import com.fshu.next.poll.PollMode
import com.fshu.next.poll.PollParser
import com.fshu.next.poll.PollStatus
import com.fshu.next.poll.PollTally
import com.fshu.next.databinding.ItemLocationReceivedBinding
import com.fshu.next.databinding.ItemLocationRequestReceivedBinding
import com.fshu.next.databinding.ItemLocationRequestSentBinding
import com.fshu.next.databinding.ItemLocationSentBinding
import com.fshu.next.databinding.ItemMessageReceivedBinding
import com.fshu.next.databinding.ItemMessageSentBinding
import com.fshu.next.databinding.ItemVoiceReceivedBinding
import com.fshu.next.databinding.ItemVoiceSentBinding
import com.fshu.next.util.LocationHelper
import java.lang.ref.WeakReference
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

    /** Invoked when the user taps a poll option row to cast/change/retract their ballot: (listId, optionId). */
    var onPollVote: ((String, String) -> Unit)? = null

    /** username → display name map, set by the host Activity. Used to resolve checkedBy labels. */
    var nicknameMap: Map<String, String> = emptyMap()

    /** Current user's username — set by ChatActivity so reaction chips can highlight own reactions. */
    var me: String = ""

    /** True when displaying a group conversation — shows sender name on received bubbles. */
    var isGroupChat: Boolean = false

    /** Invoked when a reaction chip is tapped: (message, emoji). Activity decides add/remove. */
    var onReactionTap: ((Message, String) -> Unit)? = null

    /** Current search query — non-empty triggers orange highlight on matching text in bubbles. */
    var searchQuery: String = ""

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
        private const val SENT_VOICE   = 8
        private const val RECV_VOICE   = 9
        private const val SENT_SOS     = 10
        private const val RECV_SOS     = 11

        // ── Voice playback state ──────────────────────────────────────────────
        private var activePlayer: MediaPlayer? = null
        private var activeMsgId: Long = -1L
        private val playHandler = Handler(Looper.getMainLooper())
        private var activeWaveRef: WeakReference<WaveformView>? = null
        private var activeDurRef: WeakReference<TextView>? = null
        private var activePlayBtnRef: WeakReference<android.widget.ImageButton>? = null
        private var totalDurSecs: Int = 0

        fun stopAll() {
            playHandler.removeCallbacksAndMessages(null)
            activeWaveRef?.get()?.progress = 0f
            activeWaveRef = null
            activeDurRef = null
            activePlayBtnRef?.get()?.setImageResource(android.R.drawable.ic_media_play)
            activePlayBtnRef = null
            activePlayer?.release()
            activePlayer = null
            activeMsgId = -1L
        }

        val DIFF = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(a: Message, b: Message) = a.id == b.id
            override fun areContentsTheSame(a: Message, b: Message) = a == b
        }

        private const val TICK_SINGLE = "✓"
        private const val TICK_DOUBLE = "✓✓"
        private val COLOR_GREY = Color.parseColor("#9E9E9E")
        private val COLOR_BLUE = Color.parseColor("#4FC3F7")
        private val COLOR_POLL_OPEN = Color.parseColor("#4CAF50")
    }

    override fun getItemViewType(pos: Int): Int {
        val msg = getItem(pos)
        return when {
            msg.type == "list" && msg.isSent         -> SENT_LIST
            msg.type == "list"                       -> RECV_LIST
            msg.type == "location" && msg.isSent          -> SENT_LOC
            msg.type == "location"                        -> RECV_LOC
            msg.type == "emergency-location" && msg.isSent -> SENT_LOC
            msg.type == "emergency-location"              -> RECV_LOC
            msg.type == "location-request" && msg.isSent -> SENT_LOC_REQ
            msg.type == "location-request"           -> RECV_LOC_REQ
            msg.type == "voice" && msg.isSent        -> SENT_VOICE
            msg.type == "voice"                      -> RECV_VOICE
            msg.type == "sos-message" && msg.isSent  -> SENT_SOS
            msg.type == "sos-message"                -> RECV_SOS
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
            SENT_VOICE   -> SentVoiceVH(ItemVoiceSentBinding.inflate(inf, parent, false))
            RECV_VOICE   -> RecvVoiceVH(ItemVoiceReceivedBinding.inflate(inf, parent, false))
            SENT_SOS     -> SentSosVH(ItemLocationSentBinding.inflate(inf, parent, false))
            RECV_SOS     -> RecvSosVH(ItemLocationReceivedBinding.inflate(inf, parent, false))
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
                if (isGroupChat) {
                    holder.b.tvSender.visibility = View.VISIBLE
                    holder.b.tvSender.text = nicknameMap[msg.from] ?: msg.from
                    val colors = intArrayOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(),
                        0xFF8E24AA.toInt(), 0xFFFF8F00.toInt(), 0xFF00ACC1.toInt())
                    holder.b.tvSender.setTextColor(
                        colors[msg.from.hashCode().let { if (it < 0) -it else it } % colors.size]
                    )
                } else {
                    holder.b.tvSender.visibility = View.GONE
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
            is SentListVH -> {
                holder.b.tvTime.text = time
                when (msg.status) {
                    "SENDING" -> { holder.b.pbSending.visibility = View.VISIBLE; holder.b.tvStatus.visibility = View.GONE }
                    "SENT"    -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_SINGLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
                    "DELIVERED" -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
                    "READ"    -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_BLUE) }
                    else      -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.GONE }
                }
                bindListOrPoll(
                    holder.b.tvHeader, holder.b.tvPollQuestion, holder.b.llPollMeta,
                    holder.b.tvPollMode, holder.b.tvPollStatus, holder.b.llItems,
                    holder.b.tvPollTotal, msg
                )
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
                bindListOrPoll(
                    holder.b.tvHeader, holder.b.tvPollQuestion, holder.b.llPollMeta,
                    holder.b.tvPollMode, holder.b.tvPollStatus, holder.b.llItems,
                    holder.b.tvPollTotal, msg
                )
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
            is SentSosVH -> {
                bindSosContent(holder.b.tvCoords, holder.b.tvAccuracy, holder.b.btnMaps, msg)
                holder.b.tvTime.text = time
                when (msg.status) {
                    "SENDING"   -> { holder.b.pbSending.visibility = View.VISIBLE; holder.b.tvStatus.visibility = View.GONE }
                    "SENT"      -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_SINGLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
                    "DELIVERED" -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
                    "READ"      -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_BLUE) }
                    else        -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.GONE }
                }
            }
            is RecvSosVH -> {
                bindSosContent(holder.b.tvCoords, holder.b.tvAccuracy, holder.b.btnMaps, msg)
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
            is SentVoiceVH -> bindVoiceSent(holder, msg, time)
            is RecvVoiceVH -> bindVoiceRecv(holder, msg, time)
            is RecvLocReqVH -> {
                val json = try { JsonParser.parseString(msg.content).asJsonObject } catch (e: Exception) { null }
                val hasCoords = json?.has("lat") == true
                val shared = json?.get("shared")?.asBoolean ?: false
                if (shared || hasCoords) {
                    holder.b.tvContent.text = holder.b.tvContent.context.getString(com.fshu.next.R.string.label_location_shared_auto)
                } else {
                    holder.b.tvContent.text = holder.b.tvContent.context.getString(com.fshu.next.R.string.label_location_requested_by, msg.from)
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
        tv.setTextColor(MaterialColors.getColor(tv.context, com.fshu.next.R.attr.colorTextPrimary, android.graphics.Color.BLACK))

        val isImage = msg.type == "file" && msg.mimeType?.startsWith("image/") == true
        if (isImage && msg.localUri != null) {
            iv.visibility = View.VISIBLE
            tv.visibility = View.GONE
            tv.isLongClickable = false
            iv.load(Uri.parse(msg.localUri))
            iv.setOnLongClickListener { bubble.performLongClick(); true }
            iv.setOnClickListener { openFile(iv, msg) }
        } else {
            iv.visibility = View.GONE
            tv.visibility = View.VISIBLE
            val isEncryptedBlob = msg.type != "deleted" &&
                (msg.groupId != null || !msg.isSent) &&
                msg.content.length >= 64 &&
                msg.content.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            if (isEncryptedBlob) {
                tv.text = tv.context.getString(com.fshu.next.R.string.label_encrypted_message)
                tv.setTypeface(null, android.graphics.Typeface.ITALIC)
                tv.setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
                tv.movementMethod = null
                return
            }
            tv.text = msg.content
            android.text.util.Linkify.addLinks(tv, android.text.util.Linkify.WEB_URLS)
            tv.movementMethod = if (tv.urls.isNotEmpty())
                android.text.method.LinkMovementMethod.getInstance()
            else
                null
            tv.setLinkTextColor(android.graphics.Color.parseColor("#E8711A"))
            tv.setOnLongClickListener { bubble.performLongClick(); true }
            if (msg.type == "file") tv.setOnClickListener { openFile(tv, msg) }
            else tv.setOnClickListener(null)
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
            if (searchQuery.isNotEmpty()) {
                applySearchHighlight(tv, msg.content, searchQuery)
            }
        }
    }

    private fun applySearchHighlight(tv: TextView, content: String, query: String) {
        val queryLower = query.lowercase()
        val contentLower = content.lowercase()
        var idx = contentLower.indexOf(queryLower)
        if (idx < 0) return
        val spannable = android.text.SpannableStringBuilder(tv.text)
        while (idx >= 0) {
            spannable.setSpan(
                android.text.style.BackgroundColorSpan(0x55E8711A.toInt()),
                idx, idx + query.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            idx = contentLower.indexOf(queryLower, idx + queryLower.length)
        }
        tv.text = spannable
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
            Toast.makeText(view.context, view.context.getString(com.fshu.next.R.string.toast_file_not_available), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(view.context, view.context.getString(com.fshu.next.R.string.toast_no_app_to_open_file), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(view.context, view.context.getString(com.fshu.next.R.string.toast_file_no_longer_available), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Dispatches a type="list" message to poll or todo rendering. Detection (SPEC_T5.md v2 §3b):
     * a list is a poll iff it holds an active item whose packed text has kind:"meta" — plain
     * todo item text never parses as JSON with that shape, so this is backward-compatible with
     * every existing DM todo list. A malformed/partial poll (meta present, parse otherwise fails —
     * e.g. options still mid-sync) falls back to a "Poll unavailable" placeholder rather than
     * crashing the list bubble or misrendering as a todo list.
     */
    private fun bindListOrPoll(
        header: TextView,
        questionView: TextView,
        metaRow: LinearLayout,
        modeView: TextView,
        statusView: TextView,
        itemsContainer: LinearLayout,
        totalView: TextView,
        msg: Message
    ) {
        itemsContainer.removeAllViews()
        val listId = msg.listId
        val rawArr = try { JsonParser.parseString(msg.content).asJsonArray } catch (e: Exception) { null }
        val items: List<ListItem> = rawArr?.mapNotNull { el ->
            val o = try { el.asJsonObject } catch (e: Exception) { return@mapNotNull null }
            val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            ListItem(
                id = id,
                text = o.get("text")?.takeIf { !it.isJsonNull }?.asString ?: "",
                done = o.get("done")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                checkedBy = o.get("checkedBy")?.takeIf { !it.isJsonNull }?.asString,
                checkedAt = o.get("checkedAt")?.takeIf { !it.isJsonNull }?.asLong,
                deletedAt = o.get("deletedAt")?.takeIf { !it.isJsonNull }?.asLong
            )
        } ?: emptyList()

        val isPoll = items.any { item ->
            item.deletedAt == null && try {
                JsonParser.parseString(item.text).asJsonObject.get("kind")?.asString == "meta"
            } catch (e: Exception) { false }
        }

        if (!isPoll) {
            header.text = header.context.getString(com.fshu.next.R.string.label_todo_list)
            questionView.visibility = View.GONE
            metaRow.visibility = View.GONE
            totalView.visibility = View.GONE
            bindTodoItems(itemsContainer, msg, listId, items)
            return
        }

        header.text = header.context.getString(com.fshu.next.R.string.label_poll)
        val parsed = try {
            PollParser.parse(items)
        } catch (e: Exception) {
            android.util.Log.w("ChatAdapter", "Malformed poll list $listId", e)
            questionView.visibility = View.GONE
            metaRow.visibility = View.GONE
            totalView.visibility = View.GONE
            bindPollUnavailable(itemsContainer)
            return
        }
        bindPoll(questionView, metaRow, modeView, statusView, itemsContainer, totalView, parsed, listId)
    }

    private fun bindTodoItems(container: LinearLayout, msg: Message, listId: String?, items: List<ListItem>) {
        if (listId == null) return
        val canToggle = !msg.isSent
        for (item in items) {
            if (item.deletedAt != null) continue
            val checkedByDisplay = item.checkedBy?.let { getNickname(it) ?: it }
            val label = if (item.done && checkedByDisplay != null) "${item.text}  ✓ $checkedByDisplay" else item.text
            val cb = CheckBox(container.context).apply {
                this.text = label
                setOnCheckedChangeListener(null)
                isChecked = item.done
                paintFlags = if (item.done) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                             else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                isEnabled = canToggle
                isClickable = canToggle
                if (canToggle) {
                    setOnCheckedChangeListener { _, isChecked ->
                        paintFlags = if (isChecked) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                                     else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                        onListItemToggle?.invoke(listId, item.id, isChecked)
                    }
                }
            }
            container.addView(cb)
        }
    }

    /**
     * Poll render + voting (T5 Phase 2 Block B render, Block C voting, Block C.3 sizing/selection
     * polish). Each option row's tap target casts/changes/retracts the current user's ballot via
     * [onPollVote] — enabled on both sent and received bubbles (unlike todo checkboxes'
     * `canToggle = !msg.isSent`), since a poll creator must be able to vote in their own poll same
     * as any other member. Long-press toggles the named-voters expansion (Block C.3 — the count
     * text is no longer its own tap target, since a text-sized hit rect was too small to hit
     * reliably next to a full-row vote tap).
     */
    private fun bindPoll(
        questionView: TextView,
        metaRow: LinearLayout,
        modeView: TextView,
        statusView: TextView,
        optionsContainer: LinearLayout,
        totalView: TextView,
        parsed: ParsedPoll,
        listId: String?
    ) {
        val def = parsed.definition
        val tally = PollTally.tally(def, parsed.ballots)
        val totalVotes = tally.counts.values.sum()
        val ctx = optionsContainer.context
        val dp = ctx.resources.displayMetrics.density
        // Already-parsed ballot for me — same lookup ChatViewModel.voteOption uses, no new parsing.
        val mySelected = parsed.ballots.firstOrNull { it.voterId == me }?.selected.orEmpty()

        questionView.visibility = View.VISIBLE
        questionView.text = def.question

        metaRow.visibility = View.VISIBLE
        modeView.text = ctx.getString(
            if (def.mode == PollMode.MULTI) com.fshu.next.R.string.poll_mode_multi
            else com.fshu.next.R.string.poll_mode_single
        )
        statusView.text = ctx.getString(
            if (def.status == PollStatus.CLOSED) com.fshu.next.R.string.poll_status_closed
            else com.fshu.next.R.string.poll_status_open
        )
        statusView.setTextColor(if (def.status == PollStatus.CLOSED) COLOR_GREY else COLOR_POLL_OPEN)

        for (option in def.options) {
            val count = tally.counts[option.optionId] ?: 0
            val voters = tally.votersByOption[option.optionId].orEmpty()
            val isMine = option.optionId in mySelected

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (6 * dp).toInt() }
                minimumHeight = (56 * dp).toInt()
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            }
            val labelRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            // My-vote indicator — filled checkbox (multi) / filled radio (single), read-only
            // (row itself is the tap target, not this widget). Block C.3: previously a voter had
            // no way to tell which options were theirs in the collapsed bubble.
            val selectionIndicator = (
                if (def.mode == PollMode.MULTI) CheckBox(ctx) else RadioButton(ctx)
            ).apply {
                isChecked = isMine
                isClickable = false
                isFocusable = false
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = (4 * dp).toInt() }
            }
            val labelTv = TextView(ctx).apply {
                text = option.label
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val countTv = TextView(ctx).apply {
                val countText = ctx.resources.getQuantityString(com.fshu.next.R.plurals.poll_vote_count, count, count)
                // Trailing glyph hints "more info via long-press" — decorative, not translatable
                // text (same convention as the emoji literals elsewhere in this codebase).
                text = if (voters.isNotEmpty()) "$countText ⋯" else countText
                textSize = 12f
                setTextColor(COLOR_GREY)
            }
            labelRow.addView(selectionIndicator)
            labelRow.addView(labelTv)
            labelRow.addView(countTv)

            val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = if (totalVotes > 0) totalVotes else 1
                progress = count
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt()
                ).also { it.topMargin = (2 * dp).toInt() }
            }

            row.addView(labelRow)
            row.addView(bar)

            // Named voters — collapsed by default (SPEC_T5.md v2 §5 Decision 2: named-only), now
            // toggled by long-pressing the row (Block C.3 — the count text alone was too small a
            // target to hit reliably, and sat right next to the full-row vote tap).
            var votersTv: TextView? = null
            if (voters.isNotEmpty()) {
                votersTv = TextView(ctx).apply {
                    text = voters.joinToString(", ") { getNickname(it) ?: it }
                    textSize = 11f
                    setTextColor(COLOR_GREY)
                    visibility = View.GONE
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                }
                row.addView(votersTv)
            }

            // Tap = vote, long-press = expand/collapse named voters (Block C.3). One-item
            // list-edit per tap, no debounce (SPEC_T5.md v2 §5.5).
            if (listId != null) {
                row.isClickable = true
                row.isFocusable = true
                val outValue = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                row.setBackgroundResource(outValue.resourceId)
                row.setOnClickListener {
                    onPollVote?.invoke(listId, option.optionId)
                }
                val voterList = votersTv
                if (voterList != null) {
                    row.setOnLongClickListener {
                        voterList.visibility = if (voterList.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        true
                    }
                }
            }

            optionsContainer.addView(row)
        }

        totalView.visibility = View.VISIBLE
        totalView.text = ctx.resources.getQuantityString(com.fshu.next.R.plurals.poll_total_votes, totalVotes, totalVotes)
    }

    private fun bindPollUnavailable(container: LinearLayout) {
        val tv = TextView(container.context).apply {
            text = context.getString(com.fshu.next.R.string.poll_unavailable)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.ITALIC)
            setTextColor(COLOR_GREY)
        }
        container.addView(tv)
    }

    private fun bindSosContent(
        tvCoords: TextView,
        tvAccuracy: TextView,
        btnMaps: android.widget.Button,
        msg: Message
    ) {
        try {
            val json = JsonParser.parseString(msg.content).asJsonObject
            val text = json.get("text")?.asString ?: ""
            val lat = json.get("lat")?.asDouble ?: 0.0
            val lon = json.get("lon")?.asDouble ?: 0.0
            val accuracy = json.get("accuracy")?.asFloat ?: 0f
            val mapsUrl = json.get("mapsUrl")?.asString ?: LocationHelper.buildMapsUrl(lat, lon)
            val coordsStr = LocationHelper.formatCoordinates(lat, lon)
        tvCoords.text = "🆘 $text\n$coordsStr"
            tvAccuracy.text = tvAccuracy.context.getString(com.fshu.next.R.string.location_accuracy_format, accuracy)
            btnMaps.setOnClickListener { view ->
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(view.context, view.context.getString(com.fshu.next.R.string.toast_maps_not_available), Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            tvCoords.text = "🆘 SOS"
            tvAccuracy.text = ""
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
            tvCoords.text = tvCoords.context.getString(com.fshu.next.R.string.label_location_coords, LocationHelper.formatCoordinates(lat, lon))
            tvAccuracy.text = tvAccuracy.context.getString(com.fshu.next.R.string.location_accuracy_format, accuracy)
            btnMaps.setOnClickListener { view ->
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(view.context, view.context.getString(com.fshu.next.R.string.toast_maps_not_available), Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            tvCoords.text = tvCoords.context.getString(com.fshu.next.R.string.label_location_fallback)
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
    inner class SentVoiceVH(val b: ItemVoiceSentBinding) : RecyclerView.ViewHolder(b.root)
    inner class RecvVoiceVH(val b: ItemVoiceReceivedBinding) : RecyclerView.ViewHolder(b.root)
    inner class SentSosVH(val b: ItemLocationSentBinding) : RecyclerView.ViewHolder(b.root)
    inner class RecvSosVH(val b: ItemLocationReceivedBinding) : RecyclerView.ViewHolder(b.root)

    // ── Voice binding ─────────────────────────────────────────────────────────

    private fun bindVoiceSent(holder: SentVoiceVH, msg: Message, time: String) {
        holder.b.bubble.setOnLongClickListener { toggleSelection(msg); true }
        holder.b.waveformView.setOnLongClickListener { holder.b.bubble.performLongClick(); true }
        holder.b.btnPlay.setOnLongClickListener { holder.b.bubble.performLongClick(); true }
        holder.b.bubble.alpha = if (msg.id in selectedIds) 0.5f else 1.0f
        holder.b.tvTime.text = time
        when (msg.status) {
            "SENDING"   -> { holder.b.pbSending.visibility = View.VISIBLE; holder.b.tvStatus.visibility = View.GONE }
            "SENT"      -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_SINGLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
            "DELIVERED" -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_GREY) }
            "READ"      -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.VISIBLE; holder.b.tvStatus.text = TICK_DOUBLE; holder.b.tvStatus.setTextColor(COLOR_BLUE) }
            else        -> { holder.b.pbSending.visibility = View.GONE; holder.b.tvStatus.visibility = View.GONE }
        }
        bindVoicePlayer(msg, holder.b.waveformView, holder.b.tvDuration, holder.b.btnPlay)
        bindReactions(holder.b.llReactions, msg)
    }

    private fun bindVoiceRecv(holder: RecvVoiceVH, msg: Message, time: String) {
        holder.b.bubble.setOnLongClickListener { toggleSelection(msg); true }
        holder.b.waveformView.setOnLongClickListener { holder.b.bubble.performLongClick(); true }
        holder.b.btnPlay.setOnLongClickListener { holder.b.bubble.performLongClick(); true }
        holder.b.bubble.alpha = if (msg.id in selectedIds) 0.5f else 1.0f
        holder.b.tvTime.text = time
        bindVoicePlayer(msg, holder.b.waveformView, holder.b.tvDuration, holder.b.btnPlay)
        bindReactions(holder.b.llReactions, msg)
    }

    private fun bindVoicePlayer(
        msg: Message,
        waveView: WaveformView,
        durView: TextView,
        playBtn: android.widget.ImageButton
    ) {
        val amps = parseWaveform(msg.voiceWaveform)
        waveView.amplitudes = amps
        durView.text = fmtDur(msg.voiceDuration)

        val isActive = activeMsgId == msg.id
        if (isActive) {
            // Re-attach references after RecyclerView rebind
            activeWaveRef = WeakReference(waveView)
            activeDurRef = WeakReference(durView)
            activePlayBtnRef = WeakReference(playBtn)
            val player = activePlayer
            if (player != null && player.isPlaying) {
                playBtn.setImageResource(android.R.drawable.ic_media_pause)
                waveView.progress = if (player.duration > 0) player.currentPosition.toFloat() / player.duration else 0f
            } else {
                playBtn.setImageResource(android.R.drawable.ic_media_play)
            }
        } else {
            waveView.progress = 0f
            playBtn.setImageResource(android.R.drawable.ic_media_play)
        }

        playBtn.isEnabled = msg.localUri != null
        playBtn.alpha = if (msg.localUri != null) 1f else 0.4f

        playBtn.setOnClickListener {
            val uri = msg.localUri ?: run {
                Toast.makeText(it.context, it.context.getString(com.fshu.next.R.string.toast_downloading), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (activeMsgId == msg.id) {
                val player = activePlayer
                if (player != null) {
                    if (player.isPlaying) {
                        player.pause()
                        playBtn.setImageResource(android.R.drawable.ic_media_play)
                        playHandler.removeCallbacksAndMessages(null)
                    } else {
                        player.start()
                        playBtn.setImageResource(android.R.drawable.ic_media_pause)
                        scheduleProgressUpdate()
                    }
                }
            } else {
                stopAll()
                startVoicePlayback(msg, uri, waveView, durView, playBtn)
            }
        }
    }

    private fun startVoicePlayback(
        msg: Message,
        uriStr: String,
        waveView: WaveformView,
        durView: TextView,
        playBtn: android.widget.ImageButton
    ) {
        activeMsgId = msg.id
        totalDurSecs = msg.voiceDuration
        activeWaveRef = WeakReference(waveView)
        activeDurRef = WeakReference(durView)
        activePlayBtnRef = WeakReference(playBtn)
        try {
            val player = MediaPlayer().apply {
                setDataSource(playBtn.context, Uri.parse(uriStr))
                setOnCompletionListener {
                    playHandler.removeCallbacksAndMessages(null)
                    activeWaveRef?.get()?.progress = 0f
                    activeDurRef?.get()?.text = fmtDur(totalDurSecs)
                    activePlayBtnRef?.get()?.setImageResource(android.R.drawable.ic_media_play)
                    release()
                    if (activeMsgId == msg.id) {
                        activePlayer = null
                        activeMsgId = -1L
                    }
                }
                prepare()
                start()
            }
            activePlayer = player
            playBtn.setImageResource(android.R.drawable.ic_media_pause)
            scheduleProgressUpdate()
        } catch (e: Exception) {
            android.util.Log.e("ChatAdapter", "Voice playback failed", e)
            Toast.makeText(playBtn.context, playBtn.context.getString(com.fshu.next.R.string.toast_cannot_play_voice), Toast.LENGTH_SHORT).show()
            activeMsgId = -1L
        }
    }

    private fun scheduleProgressUpdate() {
        playHandler.removeCallbacksAndMessages(null)
        val tick = object : Runnable {
            override fun run() {
                val player = activePlayer ?: return
                if (!player.isPlaying) return
                val dur = player.duration.coerceAtLeast(1)
                val pos = player.currentPosition
                activeWaveRef?.get()?.progress = pos.toFloat() / dur
                val remaining = ((dur - pos) / 1000).toInt()
                activeDurRef?.get()?.text = fmtDur(remaining)
                playHandler.postDelayed(this, 80)
            }
        }
        playHandler.post(tick)
    }

    private fun parseWaveform(json: String?): FloatArray {
        if (json.isNullOrEmpty()) return FloatArray(0)
        return try {
            val arr = JsonParser.parseString(json).asJsonArray
            FloatArray(arr.size()) { arr[it].asFloat }
        } catch (e: Exception) { FloatArray(0) }
    }

    private fun fmtDur(secs: Int): String = "%d:%02d".format(secs / 60, secs % 60)
}
