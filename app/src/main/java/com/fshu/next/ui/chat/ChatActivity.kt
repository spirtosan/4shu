package com.fshu.next.ui.chat

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.transform.CircleCropTransformation
import com.google.gson.JsonParser
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fshu.next.R
import com.fshu.next.databinding.ActivityChatBinding
import com.fshu.next.ui.BackgroundBottomSheet
import com.fshu.next.ui.BackgroundHelper
import com.fshu.next.ui.call.CallActivity
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PEER = "peer"
        @Volatile var isActive = false
        @Volatile var currentPeer = ""
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var peer: String
    private val vm: ChatViewModel by viewModels()
    private val adapter = ChatAdapter()

    private var lastTypingSent = 0L
    private val typingHideHandler = Handler(Looper.getMainLooper())
    private val typingHideRunnable = Runnable { supportActionBar?.subtitle = null }

    // Active reply context — null means no reply pending.
    private var pendingReplyId: Long? = null
    private var pendingReplySender: String? = null
    private var pendingReplyContent: String? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.sendFile(peer, it, contentResolver) }
    }

    // Send read receipts when the screen turns on while this chat is in the foreground.
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                vm.sendReadReceipts(peer)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peer = intent.getStringExtra(EXTRA_PEER) ?: run { finish(); return }

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getNickname(peer) ?: peer
        loadPeerAvatar()

        supportFragmentManager.setFragmentResultListener(BackgroundBottomSheet.RESULT_KEY, this) { _, _ ->
            applyBackground()
        }

        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = adapter

        adapter.onListItemToggle = { listId, itemId, done ->
            vm.checkItem(listId, itemId, done, peer)
        }
        refreshNicknameMap()


        // Selection mode callbacks
        adapter.onSelectionChanged = { count, singleMsg ->
            if (count == 0) {
                binding.selectionBar.visibility = View.GONE
                title = getNickname(peer) ?: peer
            } else {
                binding.selectionBar.visibility = View.VISIBLE
                binding.tvSelectionCount.text = "$count selected"
                // Copy available for all selections except location-request
                val canCopy = adapter.getSelectedMessages()
                    .none { it.type == "location-request" }
                binding.btnSelectionCopy.visibility =
                    if (canCopy) View.VISIBLE else View.GONE
                val canReply = count == 1 &&
                    singleMsg != null &&
                    singleMsg.type !in listOf("list", "location", "location-request")
                binding.btnSelectionReply.visibility =
                    if (canReply) View.VISIBLE else View.GONE
            }
        }

        binding.btnSelectionClose.setOnClickListener {
            adapter.clearSelection()
        }

        binding.btnSelectionCopy.setOnClickListener {
            val messages = adapter.getSelectedMessages().sortedBy { it.timestamp }
            val cm = getSystemService(ClipboardManager::class.java)

            if (messages.size == 1) {
                val msg = messages.first()
                when (msg.type) {
                    "file" -> {
                        val uriStr = msg.localUri
                        if (uriStr != null && msg.mimeType?.startsWith("image/") == true) {
                            try {
                                val uri = android.net.Uri.parse(uriStr)
                                val clip = ClipData.newUri(contentResolver, msg.filename ?: "image", uri)
                                cm.setPrimaryClip(clip)
                                Toast.makeText(this, "Image copied", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                cm.setPrimaryClip(ClipData.newPlainText("file", msg.filename ?: msg.content))
                                Toast.makeText(this, "Filename copied", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            cm.setPrimaryClip(ClipData.newPlainText("file", msg.filename ?: msg.content))
                            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "list" -> {
                        val text = formatListForCopy(msg)
                        cm.setPrimaryClip(ClipData.newPlainText("list", text))
                        Toast.makeText(this, "List copied as text", Toast.LENGTH_SHORT).show()
                    }
                    "location" -> {
                        val mapsUrl = try {
                            com.google.gson.JsonParser.parseString(msg.content)
                                .asJsonObject.get("mapsUrl")?.asString ?: msg.content
                        } catch (e: Exception) { msg.content }
                        cm.setPrimaryClip(ClipData.newPlainText("location", mapsUrl))
                        Toast.makeText(this, "Location link copied", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        cm.setPrimaryClip(ClipData.newPlainText("message", msg.content))
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                val text = messages.joinToString("\n") { msg ->
                    when (msg.type) {
                        "file"     -> "[${msg.filename ?: "File"}]"
                        "list"     -> "[Todo list]\n${formatListForCopy(msg)}"
                        "location" -> try {
                            com.google.gson.JsonParser.parseString(msg.content)
                                .asJsonObject.get("mapsUrl")?.asString ?: "[Location]"
                        } catch (e: Exception) { "[Location]" }
                        else       -> msg.content
                    }
                }
                cm.setPrimaryClip(ClipData.newPlainText("messages", text))
                Toast.makeText(this, "${messages.size} messages copied", Toast.LENGTH_SHORT).show()
            }
            adapter.clearSelection()
        }

        binding.btnSelectionReply.setOnClickListener {
            val msg = adapter.getSelectedMessages().firstOrNull() ?: return@setOnClickListener
            val preview = when (msg.type) {
                "file" -> "📎 ${msg.filename ?: "file"}"
                else   -> msg.content.take(120)
            }
            pendingReplyId = msg.id
            pendingReplySender = msg.from
            pendingReplyContent = preview
            binding.tvReplyPreviewSender.text = msg.from
            binding.tvReplyPreviewContent.text = preview
            binding.replyPreview.visibility = View.VISIBLE
            binding.etMessage.requestFocus()
            adapter.clearSelection()
        }

        binding.btnSelectionReply.setOnClickListener {
            val msg = adapter.getSelectedMessages().firstOrNull() ?: return@setOnClickListener
            val preview = when (msg.type) {
                "file" -> "📎 ${msg.filename ?: "file"}"
                else   -> msg.content.take(120)
            }
            pendingReplyId = msg.id
            pendingReplySender = msg.from
            pendingReplyContent = preview
            binding.tvReplyPreviewSender.text = msg.from
            binding.tvReplyPreviewContent.text = preview
            binding.replyPreview.visibility = View.VISIBLE
            binding.etMessage.requestFocus()
            adapter.clearSelection()
        }

        binding.btnSelectionDelete.setOnClickListener {
            val ids = adapter.getSelectedIds()
            val count = ids.size
            AlertDialog.Builder(this)
                .setTitle("Delete $count message${if (count > 1) "s" else ""}?")
                .setMessage("This will delete the selected message${if (count > 1) "s" else ""} from your device only.")
                .setPositiveButton("Delete") { _, _ ->
                    vm.deleteMessages(ids)
                    adapter.clearSelection()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Edit list on long press for list messages (only when not in selection mode)
        adapter.onMessageLongPress = { msg, _ ->
            if (msg.type == "list" && msg.isSent && !adapter.isInSelectionMode()) {
                val current = try {
                    JsonParser.parseString(msg.content).asJsonArray
                        .mapNotNull {
                            val obj = it.asJsonObject
                            val id = obj.get("id")?.asString ?: return@mapNotNull null
                            val text = obj.get("text")?.asString ?: ""
                            val deleted = obj.get("deletedAt")
                            if (deleted != null && !deleted.isJsonNull) return@mapNotNull null
                            if (text.isEmpty()) return@mapNotNull null
                            Triple(id, text, obj.get("done")?.asBoolean ?: false)
                        }
                } catch (e: Exception) { emptyList() }
                showTodoDialog(current, "Edit todo list") { items, deletedIds ->
                    vm.editList(msg, items.map { (id, text, _) -> Pair(id, text) }, deletedIds, peer)
                }
            }
        }

        binding.btnCancelReply.setOnClickListener { clearReply() }

        // Room LiveData updates the list whenever FshuService persists an incoming message/file
        vm.getMessages(peer).observe(this) { msgs ->
            adapter.submitList(msgs) {
                if (msgs.isNotEmpty()) binding.rvMessages.scrollToPosition(msgs.size - 1)
            }
        }

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotBlank()) {
                vm.sendText(peer, text, pendingReplyId, pendingReplySender, pendingReplyContent)
                binding.etMessage.text?.clear()
                clearReply()
            }
        }

        binding.btnAttach.setOnClickListener { pickFile.launch("*/*") }

        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrEmpty()) return
                val now = System.currentTimeMillis()
                if (now - lastTypingSent > 3000L) {
                    lastTypingSent = now
                    val me = Prefs.getUsername(this@ChatActivity)
                    com.fshu.next.data.remote.WebSocketClient.send(
                        mapOf("type" to "typing", "from" to me, "to" to peer)
                    )
                }
            }
        })

        lifecycleScope.launch {
            MessageBus.events.collect { json ->
                when (json.get("type")?.asString) {
                    "history-loaded" -> {
                        val peer = json.get("peer")?.asString ?: ""
                        if (peer == this@ChatActivity.peer) {
                            val count = json.get("count")?.asInt ?: 0
                            if (count > 0) runOnUiThread {
                                Toast.makeText(this@ChatActivity, "Loaded $count message(s)", Toast.LENGTH_SHORT).show()
                                binding.rvMessages.scrollToPosition(0)
                            }
                        }
                    }
                    "avatar-update" -> {
                        val uname = json.get("username")?.asString
                        if (uname == peer) runOnUiThread { loadPeerAvatar() }
                    }
                    "typing" -> {
                        val from = json.get("from")?.asString
                        if (from == peer) runOnUiThread {
                            supportActionBar?.subtitle = "typing..."
                            typingHideHandler.removeCallbacks(typingHideRunnable)
                            typingHideHandler.postDelayed(typingHideRunnable, 5000L)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        typingHideHandler.removeCallbacks(typingHideRunnable)
    }

    private fun loadPeerAvatar() {
        val avatarFile = File(filesDir, "avatars/$peer.jpg")
        val sizePx = (36 * resources.displayMetrics.density).toInt()
        if (avatarFile.exists()) {
            binding.toolbarAvatar.load(avatarFile) {
                transformations(CircleCropTransformation())
            }
        } else {
            val letter = peer.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val color = when (peer.hashCode().let { if (it < 0) -it else it } % 10) {
                0 -> getColor(R.color.avatar_1)
                1 -> getColor(R.color.avatar_2)
                2 -> getColor(R.color.avatar_3)
                3 -> getColor(R.color.avatar_4)
                4 -> getColor(R.color.avatar_5)
                5 -> getColor(R.color.avatar_6)
                6 -> getColor(R.color.avatar_7)
                7 -> getColor(R.color.avatar_8)
                8 -> getColor(R.color.avatar_9)
                else -> getColor(R.color.avatar_10)
            }
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
            binding.toolbarAvatar.setImageBitmap(bmp)
        }
    }

    private fun clearReply() {
        pendingReplyId = null
        pendingReplySender = null
        pendingReplyContent = null
        binding.replyPreview.visibility = View.GONE
    }

    private fun applyBackground() {
        BackgroundHelper.apply(
            rootView     = binding.root,
            bgImageView  = binding.ivBg,
            bgIndex      = Prefs.getChatBgIndex(this, peer),
            bgUri        = Prefs.getChatBgUri(this, peer),
            defaultColor = ContextCompat.getColor(this, R.color.bg_primary)
        )
    }

    override fun onResume() {
        super.onResume()
        isActive = true
        currentPeer = peer
        applyBackground()
        refreshNicknameMap()
        // Refresh title in case nickname was updated while in background
        title = getNickname(peer) ?: peer
        // Only send read receipts when the screen is interactive (not on the lock screen).
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isInteractive) {
            vm.sendReadReceipts(peer)
        }
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onPause() {
        super.onPause()
        isActive = false
        unregisterReceiver(screenOnReceiver)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chat, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            R.id.action_call -> {
                startActivity(Intent(this, CallActivity::class.java).apply {
                    putExtra(CallActivity.EXTRA_PEER, peer)
                    putExtra(CallActivity.EXTRA_IS_CALLER, true)
                })
                return true
            }
            R.id.action_video_call -> {
                startActivity(Intent(this, CallActivity::class.java).apply {
                    putExtra(CallActivity.EXTRA_PEER, peer)
                    putExtra(CallActivity.EXTRA_IS_CALLER, true)
                    putExtra(CallActivity.EXTRA_IS_VIDEO_CALL, true)
                })
                return true
            }
            R.id.action_export -> {
                exportConversation()
                return true
            }
            R.id.action_change_background -> {
                BackgroundBottomSheet.newInstance(BackgroundBottomSheet.SCREEN_CHAT, peer)
                    .show(supportFragmentManager, "bg_chat")
                return true
            }
            R.id.action_new_todo -> {
                showTodoDialog(emptyList(), "New todo list") { items, _ ->
                    vm.createList(peer, items.map { (id, text, _) -> Pair(id, text) })
                }
                return true
            }
            R.id.action_share_location -> {
                vm.sendCurrentLocation(peer)
                return true
            }
            R.id.action_request_location -> {
                vm.sendLocationRequest(peer)
                return true
            }
            R.id.action_load_history -> {
                showHistoryDialog()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Dialog for creating or editing a todo list.
     * [initial] — list of (uuid, text, checked); uuid="" for brand-new items.
     * [onSave]  — called with active (uuid, text, checked) triples and a set of deleted UUIDs.
     */
    private fun showTodoDialog(
        initial: List<Triple<String, String, Boolean>>,
        title: String,
        onSave: (List<Triple<String, String, Boolean>>, Set<String>) -> Unit
    ) {
        val dp  = resources.displayMetrics.density
        val p16 = (16 * dp).toInt()
        val p8  = (8  * dp).toInt()

        // Each committed row has a TextView at child index 0.
        // The row tag stores Pair<String, Boolean> = (uuid, checked).
        val committedRows = mutableListOf<LinearLayout>()
        val deletedIds = mutableSetOf<String>()

        val itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun commitRow(uuid: String, text: String, checked: Boolean = false) {
            val label = TextView(this@ChatActivity).apply {
                this.text = text
                textSize = 15f
                setPadding(p16, 0, p8, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val p48 = (48 * dp).toInt()
            val removeBtn = Button(this@ChatActivity).apply {
                this.text = "×"; textSize = 18f
                minimumWidth = 0; minWidth = 0; minimumHeight = 0; minHeight = 0
                setPadding(p16, 0, p16, 0)
                layoutParams = LinearLayout.LayoutParams(p48, p48)
            }
            val row = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                tag = Pair(uuid, checked)  // (uuid, checked state)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = p8 }
                addView(label)
                addView(removeBtn)
            }
            removeBtn.setOnClickListener {
                committedRows.remove(row)
                itemsContainer.removeView(row)
                // Track deleted existing items so server can soft-delete them
                if (uuid.isNotEmpty()) deletedIds.add(uuid)
            }
            committedRows.add(row)
            itemsContainer.addView(row)
        }

        initial.forEach { (uuid, text, checked) -> commitRow(uuid, text, checked) }

        val inputField = EditText(this).apply {
            hint = "New item"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = p8 }
        }

        val addBtn = Button(this).apply {
            this.text = "+ Add item"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        addBtn.setOnClickListener {
            val text = inputField.text.toString().trim()
            if (text.isNotEmpty()) {
                commitRow("", text)  // empty uuid — new item
                inputField.text?.clear()
                inputField.requestFocus()
            }
        }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p16, p8, p16, p8)
            addView(itemsContainer)
            addView(inputField)
            addView(addBtn)
        }

        val scroll = ScrollView(this).apply {
            minimumHeight = (300 * dp).toInt()
            setPadding(p8, p8, p8, p8)
            addView(outer)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val items = committedRows.mapNotNull { row ->
                    val text = (row.getChildAt(0) as? TextView)?.text?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST") val tagPair = (row.tag as? Pair<String, Boolean>) ?: Pair("", false)
                    val (uuid, checked) = tagPair
                    // Assign a new UUID for items that don't have one yet.
                    val finalId = if (uuid.isEmpty()) java.util.UUID.randomUUID().toString() else uuid
                    Triple(finalId, text, checked)
                }.toMutableList()
                val remaining = inputField.text.toString().trim()
                if (remaining.isNotEmpty()) {
                    items.add(Triple(java.util.UUID.randomUUID().toString(), remaining, false))
                }
                if (items.isNotEmpty()) onSave(items, deletedIds)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (adapter.isInSelectionMode()) {
            adapter.clearSelection()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun showHistoryDialog() {
        val options = arrayOf("Last 7 days", "Last 30 days", "Last 90 days", "Custom...")
        AlertDialog.Builder(this)
            .setTitle("Load history")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> vm.requestHistory(peer, 7)
                    1 -> vm.requestHistory(peer, 30)
                    2 -> vm.requestHistory(peer, 90)
                    3 -> {
                        val et = EditText(this).apply {
                            inputType = InputType.TYPE_CLASS_NUMBER
                            hint = "Days (1–90)"
                        }
                        val pad = (16 * resources.displayMetrics.density).toInt()
                        val wrap = android.widget.FrameLayout(this).apply {
                            setPadding(pad, 0, pad, 0)
                            addView(et)
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Custom period")
                            .setView(wrap)
                            .setPositiveButton("Load") { _, _ ->
                                val days = et.text.toString().toIntOrNull()?.coerceIn(1, 90) ?: 7
                                vm.requestHistory(peer, days)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun refreshNicknameMap() {
        try {
            val arr = com.google.gson.JsonParser
                .parseString(com.fshu.next.util.Prefs.getCachedUsers(this))
                .asJsonArray
            val map = mutableMapOf<String, String>()
            for (el in arr) {
                val obj = el.asJsonObject
                val un = obj.get("username")?.asString ?: continue
                val nick = obj.get("nickname")?.takeIf { !it.isJsonNull }?.asString
                map[un] = if (!nick.isNullOrBlank()) nick else un
            }
            adapter.nicknameMap = map
        } catch (_: Exception) {}
    }

    private fun getNickname(username: String): String? {
        return try {
            val arr = com.google.gson.JsonParser
                .parseString(com.fshu.next.util.Prefs.getCachedUsers(this))
                .asJsonArray
            for (el in arr) {
                val obj = el.asJsonObject
                if (obj.get("username")?.asString == username) {
                    val nick = obj.get("nickname")?.takeIf { !it.isJsonNull }?.asString
                    return if (!nick.isNullOrBlank()) nick else null
                }
            }
            null
        } catch (e: Exception) { null }
    }

    private fun formatListForCopy(msg: com.fshu.next.data.model.Message): String {
        return try {
            com.google.gson.JsonParser.parseString(msg.content).asJsonArray
                .mapNotNull { el ->
                    val obj = el.asJsonObject
                    val deleted = obj.get("deletedAt")
                    if (deleted != null && !deleted.isJsonNull) return@mapNotNull null
                    val text = obj.get("text")?.asString ?: return@mapNotNull null
                    if (text.isEmpty()) return@mapNotNull null
                    val done = obj.get("done")?.asBoolean ?: false
                    if (done) "✓ $text" else "○ $text"
                }
                .joinToString("\n")
        } catch (e: Exception) { msg.content }
    }

    private fun exportConversation() {
        val messages = adapter.currentList
        if (messages.isEmpty()) {
            Toast.makeText(this, "Nothing to export", Toast.LENGTH_SHORT).show()
            return
        }
        val me = Prefs.getUsername(this)
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault())
        val dateStr = dateFmt.format(Date())
        val filename = "Conversation with $peer - $dateStr.txt"

        val sb = StringBuilder()
        sb.appendLine("Conversation with $peer")
        sb.appendLine("Exported by $me on ${timeFmt.format(Date())}")
        sb.appendLine()
        for (msg in messages) {
            val time = timeFmt.format(Date(msg.timestamp))
            val content = when (msg.type) {
                "file" -> "[File: ${msg.filename ?: msg.content}]"
                "list" -> "[Todo list]"
                else   -> msg.content
            }
            sb.appendLine("[$time] ${msg.from}: $content")
        }
        val text = sb.toString()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val col = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val contentUri = contentResolver.insert(col, values)!!
                    contentResolver.openOutputStream(contentUri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    contentResolver.update(contentUri, done, null, null)
                    contentUri
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    val file = File(dir, filename)
                    file.writeText(text, Charsets.UTF_8)
                    FileProvider.getUriForFile(this@ChatActivity, "$packageName.fileprovider", file)
                }
                withContext(Dispatchers.Main) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share conversation"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatActivity, "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
