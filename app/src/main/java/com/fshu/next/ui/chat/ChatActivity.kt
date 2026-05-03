package com.fshu.next.ui.chat

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
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
import android.widget.FrameLayout
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
import com.fshu.next.util.VoiceRecorder
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
        const val EXTRA_GROUP_ID = "group_id"
        @Volatile var isActive = false
        @Volatile var currentPeer = ""
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var peer: String
    private var groupId: String? = null
    private var isGroupChat = false
    private val vm: ChatViewModel by viewModels()
    private val adapter = ChatAdapter()

    private var lastTypingSent = 0L
    private val typingHideHandler = Handler(Looper.getMainLooper())
    private val typingHideRunnable = Runnable { supportActionBar?.subtitle = null }

    // Active reply context — null means no reply pending.
    private var pendingReplyId: Long? = null
    private var pendingReplySender: String? = null
    private var pendingReplyContent: String? = null

    private lateinit var voiceRecorder: VoiceRecorder
    private var pendingGroupAvatarGroupId: String? = null

    private val pickGroupAvatarLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val gid = pendingGroupAvatarGroupId ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri) ?: return@launch
                val original = BitmapFactory.decodeStream(stream)
                stream.close()
                val side = minOf(original.width, original.height)
                val cropped = Bitmap.createBitmap(original, (original.width - side) / 2, (original.height - side) / 2, side, side)
                val scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                com.fshu.next.data.remote.WebSocketClient.send(mapOf("type" to "group-avatar-upload", "groupId" to gid, "data" to b64))
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatActivity, "Group photo updated", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatActivity, "Failed to upload photo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val pickPersonalAvatarLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val gid = pendingGroupAvatarGroupId ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri) ?: return@launch
                val original = BitmapFactory.decodeStream(stream)
                stream.close()
                val side = minOf(original.width, original.height)
                val cropped = Bitmap.createBitmap(original, (original.width - side) / 2, (original.height - side) / 2, side, side)
                val scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                val dir = File(filesDir, "avatars").also { it.mkdirs() }
                val file = File(dir, "personal_$gid.jpg")
                file.writeBytes(baos.toByteArray())
                val db = com.fshu.next.data.local.AppDatabase.getInstance(this@ChatActivity)
                db.groupDao().updatePersonalAvatar(gid, file.absolutePath)
                val group = db.groupDao().getById(gid) ?: return@launch
                runOnUiThread { loadGroupAvatarInto(group, binding.toolbarAvatar, (36 * resources.displayMetrics.density).toInt()) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatActivity, "Failed to set personal photo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.sendFile(peer, it, contentResolver) }
    }

    // Send read receipts when the screen turns on while this chat is in the foreground.
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON && !isGroupChat) {
                vm.sendReadReceipts(peer)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra(EXTRA_GROUP_ID)
        isGroupChat = groupId != null
        peer = if (isGroupChat) groupId!! else
            (intent.getStringExtra(EXTRA_PEER) ?: run { finish(); return })
        voiceRecorder = VoiceRecorder(this)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (isGroupChat) {
            adapter.isGroupChat = true
            title = peer
            loadGroupInfo()
        } else {
            title = getNickname(peer) ?: peer
            loadPeerAvatar()
        }

        supportFragmentManager.setFragmentResultListener(BackgroundBottomSheet.RESULT_KEY, this) { _, _ ->
            applyBackground()
        }

        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = adapter

        adapter.me = vm.getMe()
        adapter.onListItemToggle = { listId, itemId, done ->
            vm.checkItem(listId, itemId, done, peer)
        }
        adapter.onReactionTap = { msg, emoji ->
            val myEmoji = findMyEmoji(msg)
            if (emoji == myEmoji) vm.sendReaction(msg, null) else vm.sendReaction(msg, emoji)
        }
        refreshNicknameMap()


        // Selection mode callbacks
        adapter.onSelectionChanged = { count, singleMsg ->
            if (count == 0) {
                binding.selectionBar.visibility = View.GONE
                if (!isGroupChat) title = getNickname(peer) ?: peer
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
                val canEdit = count == 1 &&
                    singleMsg != null && singleMsg.isSent && singleMsg.remoteId > 0L &&
                    singleMsg.type !in listOf("file", "list", "location", "location-request", "deleted")
                binding.btnSelectionEdit.visibility =
                    if (canEdit) View.VISIBLE else View.GONE
                val canReact = count == 1 && singleMsg != null &&
                    singleMsg.remoteId > 0L && singleMsg.type != "deleted"
                binding.btnSelectionReact.visibility =
                    if (canReact) View.VISIBLE else View.GONE
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

        binding.btnSelectionEdit.setOnClickListener {
            val msg = adapter.getSelectedMessages().firstOrNull() ?: return@setOnClickListener
            val et = EditText(this).apply {
                setText(msg.content)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setSelection(msg.content.length)
            }
            val pad = (16 * resources.displayMetrics.density).toInt()
            val wrap = android.widget.FrameLayout(this).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(et)
            }
            AlertDialog.Builder(this)
                .setTitle("Edit message")
                .setView(wrap)
                .setPositiveButton("Save") { _, _ ->
                    val newText = et.text.toString().trim()
                    if (newText.isNotBlank() && newText != msg.content) {
                        vm.editMessage(msg, newText, peer)
                    }
                    adapter.clearSelection()
                }
                .setNegativeButton("Cancel") { _, _ -> adapter.clearSelection() }
                .show()
        }

        binding.btnSelectionReact.setOnClickListener {
            val msg = adapter.getSelectedMessages().firstOrNull() ?: return@setOnClickListener
            val myEmoji = findMyEmoji(msg)
            val emojis = arrayOf("👍", "👎", "❤️", "😂", "😮", "😢", "😡", "🔥", "👏", "🎉")
            val labels = emojis.map { if (it == myEmoji) "$it ✓" else it }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("React")
                .setItems(labels) { _, which ->
                    val picked = emojis[which]
                    if (picked == myEmoji) vm.sendReaction(msg, null)
                    else vm.sendReaction(msg, picked)
                    adapter.clearSelection()
                }
                .setNegativeButton("Cancel") { _, _ -> adapter.clearSelection() }
                .show()
        }

        binding.btnSelectionDelete.setOnClickListener {
            val ids = adapter.getSelectedIds()
            val selected = adapter.getSelectedMessages()
            val count = ids.size
            val single = selected.firstOrNull()
            val canDeleteForAll = count == 1 && single?.isSent == true && (single.remoteId) > 0L

            if (canDeleteForAll && single != null) {
                AlertDialog.Builder(this)
                    .setTitle("Delete message?")
                    .setItems(arrayOf("Delete for everyone", "Delete for me only")) { _, which ->
                        if (which == 0) vm.deleteForEveryone(single)
                        else vm.deleteMessages(ids)
                        adapter.clearSelection()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
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
        if (isGroupChat) {
            vm.getGroupMessages(peer).observe(this) { msgs ->
                adapter.submitList(msgs) {
                    if (msgs.isNotEmpty()) binding.rvMessages.scrollToPosition(msgs.size - 1)
                }
            }
        } else {
            vm.getMessages(peer).observe(this) { msgs ->
                adapter.submitList(msgs) {
                    if (msgs.isNotEmpty()) binding.rvMessages.scrollToPosition(msgs.size - 1)
                }
            }
        }

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotBlank()) {
                if (isGroupChat) {
                    vm.sendGroupText(peer, text)
                } else {
                    vm.sendText(peer, text, pendingReplyId, pendingReplySender, pendingReplyContent)
                }
                binding.etMessage.text?.clear()
                clearReply()
            }
        }

        if (isGroupChat) {
            binding.btnAttach.isEnabled = false
            binding.btnMic.isEnabled = false
        }
        binding.btnAttach.setOnClickListener { pickFile.launch("*/*") }

        binding.btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (androidx.core.app.ActivityCompat.checkSelfPermission(
                            this, android.Manifest.permission.RECORD_AUDIO
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        androidx.core.app.ActivityCompat.requestPermissions(
                            this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 101
                        )
                        return@setOnTouchListener true
                    }
                    binding.etMessage.visibility = View.GONE
                    binding.tvRecordingState.visibility = View.VISIBLE
                    voiceRecorder.start("voice_temp.m4a") { secs ->
                        runOnUiThread {
                            binding.tvRecordingState.text =
                                "🎤 ${secs / 60}:${"%02d".format(secs % 60)}"
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    binding.etMessage.visibility = View.VISIBLE
                    binding.tvRecordingState.visibility = View.GONE
                    binding.tvRecordingState.text = "🎤 0:00"
                    val result = voiceRecorder.stop()
                    if (result != null && result.durationSecs >= 1) {
                        vm.sendVoice(peer, result.file, result.waveform, result.durationSecs)
                    } else if (result != null) {
                        Toast.makeText(this, "Hold longer to record", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }

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
                    "group-removed" -> {
                        val removedId = json.get("groupId")?.asString
                        if (removedId == groupId) runOnUiThread {
                            val reason = json.get("reason")?.asString
                            when (reason) {
                                "kicked" -> AlertDialog.Builder(this@ChatActivity)
                                    .setTitle("Removed from group")
                                    .setMessage("You were removed from this group by an admin.")
                                    .setPositiveButton("OK") { _, _ -> finish() }
                                    .setCancelable(false)
                                    .show()
                                "deleted" -> AlertDialog.Builder(this@ChatActivity)
                                    .setTitle("Group deleted")
                                    .setMessage("This group was deleted.")
                                    .setPositiveButton("OK") { _, _ -> finish() }
                                    .setCancelable(false)
                                    .show()
                                else -> {
                                    Toast.makeText(this@ChatActivity, "You left the group", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            }
                        }
                    }
                    "group-state" -> {
                        val stateId = json.get("groupId")?.asString
                        if (stateId == groupId) runOnUiThread { loadGroupInfo() }
                    }
                    "group-avatar-update" -> {
                        val updatedId = json.get("groupId")?.asString
                        if (updatedId == groupId) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val db = com.fshu.next.data.local.AppDatabase.getInstance(this@ChatActivity)
                                val group = db.groupDao().getById(groupId!!) ?: return@launch
                                val sizePx = (36 * resources.displayMetrics.density).toInt()
                                runOnUiThread { loadGroupAvatarInto(group, binding.toolbarAvatar, sizePx) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        typingHideHandler.removeCallbacks(typingHideRunnable)
        ChatAdapter.stopAll()
        voiceRecorder.release()
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
        if (!isGroupChat) {
            title = getNickname(peer) ?: peer
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isInteractive) {
                vm.sendReadReceipts(peer)
            }
        }
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onPause() {
        super.onPause()
        isActive = false
        ChatAdapter.stopAll()
        unregisterReceiver(screenOnReceiver)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chat, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_call)?.isVisible = !isGroupChat
        menu.findItem(R.id.action_video_call)?.isVisible = !isGroupChat
        menu.findItem(R.id.action_group_info)?.isVisible = isGroupChat
        menu.findItem(R.id.action_export)?.isVisible = !isGroupChat
        menu.findItem(R.id.action_new_todo)?.isVisible = !isGroupChat
        menu.findItem(R.id.action_share_location)?.isVisible = !isGroupChat
        menu.findItem(R.id.action_request_location)?.isVisible = !isGroupChat
        menu.findItem(R.id.action_load_history)?.isVisible = !isGroupChat
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            R.id.action_group_info -> { showGroupInfo(); return true }
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission granted. Hold mic to record.", Toast.LENGTH_SHORT).show()
        }
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

    private fun findMyEmoji(msg: com.fshu.next.data.model.Message): String? {
        val me = vm.getMe()
        return try {
            val arr = com.google.gson.JsonParser.parseString(msg.reactions).asJsonArray
            for (i in 0 until arr.size()) {
                val item = arr[i].asJsonObject
                if (item.get("from")?.asString == me) return item.get("emoji")?.asString
            }
            null
        } catch (e: Exception) { null }
    }

    private fun loadGroupInfo() {
        val gid = groupId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = com.fshu.next.data.local.AppDatabase.getInstance(this@ChatActivity)
            val group = db.groupDao().getById(gid) ?: return@launch
            val members = db.groupMemberDao().getMembersOf(gid)
            val sizePx = (36 * resources.displayMetrics.density).toInt()
            runOnUiThread {
                title = group.name
                supportActionBar?.subtitle = "${members.size} members"
                loadGroupAvatarInto(group, binding.toolbarAvatar, sizePx)
            }
        }
    }

    private fun loadGroupAvatarInto(group: com.fshu.next.data.model.Group, iv: android.widget.ImageView, sizePx: Int) {
        val personalFile = group.personalAvatar?.let { File(it) }?.takeIf { it.exists() }
        val groupFile = group.avatarPath?.let { File(it) }?.takeIf { it.exists() }
        when {
            personalFile != null -> iv.load(personalFile) { transformations(CircleCropTransformation()) }
            groupFile != null -> iv.load(groupFile) { transformations(CircleCropTransformation()) }
            else -> {
                val letter = group.name.firstOrNull()?.uppercaseChar()?.toString() ?: "G"
                val colorIdx = group.groupId.hashCode().let { if (it < 0) -it else it } % 10
                val color = when (colorIdx) {
                    0 -> getColor(R.color.avatar_1); 1 -> getColor(R.color.avatar_2)
                    2 -> getColor(R.color.avatar_3); 3 -> getColor(R.color.avatar_4)
                    4 -> getColor(R.color.avatar_5); 5 -> getColor(R.color.avatar_6)
                    6 -> getColor(R.color.avatar_7); 7 -> getColor(R.color.avatar_8)
                    8 -> getColor(R.color.avatar_9); else -> getColor(R.color.avatar_10)
                }
                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }.also {
                    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, it)
                }
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE; textSize = sizePx * 0.42f
                    textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
                }.also {
                    val yPos = sizePx / 2f - (it.descent() + it.ascent()) / 2f
                    canvas.drawText(letter, sizePx / 2f, yPos, it)
                }
                iv.setImageBitmap(bmp)
            }
        }
    }

    private fun showGroupInfo() {
        val gid = groupId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = com.fshu.next.data.local.AppDatabase.getInstance(this@ChatActivity)
            val group = db.groupDao().getById(gid) ?: return@launch
            val members = db.groupMemberDao().getMembersOf(gid)
            val me = Prefs.getUsername(this@ChatActivity)
            val myRole = members.find { it.username == me }?.role ?: "member"
            val isOwnerOrAdmin = myRole == "owner" || myRole == "admin"

            runOnUiThread {
                val dp = resources.displayMetrics.density
                val p16 = (16 * dp).toInt()
                val p8  = (8  * dp).toInt()

                val root = LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(p16, p8, p16, p8)
                }

                // Avatar section
                val avatarSizePx = (72 * dp).toInt()
                val dialogAvatarIv = android.widget.ImageView(this@ChatActivity).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(avatarSizePx, avatarSizePx).also {
                        it.gravity = android.view.Gravity.CENTER_HORIZONTAL
                    }
                }
                loadGroupAvatarInto(group, dialogAvatarIv, avatarSizePx)

                val avatarContainer = LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = p8 }
                    addView(dialogAvatarIv)
                    if (isOwnerOrAdmin) {
                        addView(TextView(this@ChatActivity).apply {
                            text = "Change group photo"
                            textSize = 13f
                            setTextColor(0xFFE8711A.toInt())
                            gravity = android.view.Gravity.CENTER
                            setPadding(0, (4 * dp).toInt(), 0, (2 * dp).toInt())
                            setOnClickListener {
                                pendingGroupAvatarGroupId = gid
                                pickGroupAvatarLauncher.launch("image/*")
                            }
                        })
                    }
                    addView(TextView(this@ChatActivity).apply {
                        text = "Set my photo for this group"
                        textSize = 13f
                        setTextColor(0xFF2196F3.toInt())
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, (2 * dp).toInt(), 0, 0)
                        setOnClickListener {
                            pendingGroupAvatarGroupId = gid
                            pickPersonalAvatarLauncher.launch("image/*")
                        }
                    })
                }
                root.addView(avatarContainer)

                root.addView(TextView(this@ChatActivity).apply {
                    text = "${members.size} member${if (members.size != 1) "s" else ""}"
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, p8)
                })

                val memberList = LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }

                val sorted = members.sortedWith(
                    compareBy({ when (it.role) { "owner" -> 0; "admin" -> 1; else -> 2 } }, { it.username })
                )
                for (m in sorted) {
                    val nick = Prefs.getContactNickname(this@ChatActivity, m.username).ifEmpty { m.username }
                    val roleIcon = when (m.role) { "owner" -> " 👑"; "admin" -> " ⭐"; else -> "" }

                    val row = LinearLayout(this@ChatActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, p8 / 2, 0, p8 / 2)
                    }
                    row.addView(TextView(this@ChatActivity).apply {
                        text = "$nick$roleIcon"
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    if (isOwnerOrAdmin && m.role != "owner" && m.username != me) {
                        row.addView(Button(this@ChatActivity).apply {
                            text = "Remove"
                            textSize = 12f
                            setTextColor(Color.parseColor("#E53935"))
                            background = null
                            minHeight = 0
                            minimumHeight = 0
                            setOnClickListener {
                                AlertDialog.Builder(this@ChatActivity)
                                    .setTitle("Remove $nick?")
                                    .setPositiveButton("Remove") { _, _ ->
                                        com.fshu.next.data.remote.WebSocketClient.send(mapOf(
                                            "type" to "group-kick",
                                            "groupId" to gid,
                                            "username" to m.username
                                        ))
                                        val kickedUsername = m.username
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            val myPriv = Prefs.getEcPrivateKey(this@ChatActivity)
                                            val newKey = com.fshu.next.util.CryptoHelper.generateGroupKey()
                                            val remaining = db.groupMemberDao().getMembersOf(gid)
                                                .filter { it.username != kickedUsername }
                                            val keys = remaining.mapNotNull { member ->
                                                val pubHex = if (member.username == me)
                                                    Prefs.getEcPublicKey(this@ChatActivity)
                                                else
                                                    Prefs.getPeerPublicKey(this@ChatActivity, member.username)
                                                if (pubHex.isEmpty()) return@mapNotNull null
                                                mapOf(
                                                    "username"     to member.username,
                                                    "encryptedKey" to com.fshu.next.util.CryptoHelper.encryptGroupKeyForMember(newKey, pubHex, myPriv)
                                                )
                                            }
                                            if (keys.isNotEmpty()) {
                                                com.fshu.next.data.remote.WebSocketClient.send(mapOf(
                                                    "type"    to "group-key-rotate",
                                                    "groupId" to gid,
                                                    "keys"    to keys
                                                ))
                                                db.groupDao().updateGroupKey(
                                                    gid, with(com.fshu.next.util.EcdhHelper) { newKey.toHex() }
                                                )
                                            }
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        })
                    }
                    memberList.addView(row)
                }

                root.addView(ScrollView(this@ChatActivity).apply {
                    addView(memberList)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (220 * dp).toInt()
                    )
                })

                val dialog = AlertDialog.Builder(this@ChatActivity)
                    .setTitle(group.name)
                    .setView(root)
                    .setNegativeButton("Close", null)
                    .create()

                if (isOwnerOrAdmin) {
                    dialog.setButton(DialogInterface.BUTTON_NEUTRAL, "Add member") { _, _ ->
                        showAddMemberDialog(gid, members.map { it.username })
                    }
                }

                if (myRole == "owner") {
                    dialog.setButton(DialogInterface.BUTTON_POSITIVE, "Delete group") { _, _ ->
                        AlertDialog.Builder(this@ChatActivity)
                            .setTitle("Delete \"${group.name}\"?")
                            .setMessage("This will delete the group for all members.")
                            .setPositiveButton("Delete") { _, _ ->
                                com.fshu.next.data.remote.WebSocketClient.send(mapOf(
                                    "type" to "group-delete",
                                    "groupId" to gid
                                ))
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                } else {
                    dialog.setButton(DialogInterface.BUTTON_POSITIVE, "Leave group") { _, _ ->
                        AlertDialog.Builder(this@ChatActivity)
                            .setTitle("Leave \"${group.name}\"?")
                            .setPositiveButton("Leave") { _, _ ->
                                com.fshu.next.data.remote.WebSocketClient.send(mapOf(
                                    "type" to "group-leave",
                                    "groupId" to gid
                                ))
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }

                dialog.show()
                dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                    ?.setTextColor(Color.parseColor("#E53935"))
            }
        }
    }

    private fun showAddMemberDialog(groupId: String, existingUsernames: List<String>) {
        val edit = EditText(this).apply {
            hint = "Username to add"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle("Add member")
            .setView(wrap)
            .setPositiveButton("Add") { _, _ ->
                val target = edit.text.toString().trim()
                if (target.isEmpty() || target in existingUsernames) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = com.fshu.next.data.local.AppDatabase.getInstance(this@ChatActivity)
                    val group = db.groupDao().getById(groupId)
                    val groupKeyHex = group?.groupKey ?: ""
                    if (groupKeyHex.isEmpty()) {
                        runOnUiThread { Toast.makeText(this@ChatActivity, "Group key not available", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    val memberPubKey = Prefs.getPeerPublicKey(this@ChatActivity, target)
                    if (memberPubKey.isEmpty()) {
                        runOnUiThread { Toast.makeText(this@ChatActivity, "Cannot invite: public key not found for $target", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    val groupKeyBytes = with(com.fshu.next.util.EcdhHelper) { groupKeyHex.fromHex() }
                    val myPriv = Prefs.getEcPrivateKey(this@ChatActivity)
                    val encryptedKey = com.fshu.next.util.CryptoHelper.encryptGroupKeyForMember(groupKeyBytes, memberPubKey, myPriv)
                    com.fshu.next.data.remote.WebSocketClient.send(mapOf(
                        "type"         to "group-invite",
                        "groupId"      to groupId,
                        "username"     to target,
                        "encryptedKey" to encryptedKey
                    ))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
