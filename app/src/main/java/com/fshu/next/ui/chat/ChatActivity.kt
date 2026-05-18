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
import com.fshu.next.ui.search.UserProfileActivity
import com.fshu.next.ui.BackgroundHelper
import com.fshu.next.ui.call.CallActivity
import com.fshu.next.service.FshuService
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
        const val REQUEST_PICK_PHONE_CONTACT = 3001
        @Volatile var isActive = false
        @Volatile var currentPeer = ""
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var peer: String
    private var groupId: String? = null
    private var isGroupChat = false
    private val vm: ChatViewModel by viewModels()
    private val adapter = ChatAdapter()

    private var myGroupRole = "member"
    private var isAutoLocationEnabled = false

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
                    Toast.makeText(this@ChatActivity, getString(R.string.toast_group_photo_updated), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatActivity, getString(R.string.toast_failed_to_upload_photo), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@ChatActivity, getString(R.string.toast_failed_to_set_personal_photo), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.sendFile(peer, it, contentResolver) }
    }

    private var cameraImageUri: android.net.Uri? = null
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            cameraImageUri?.let { vm.sendFile(peer, it, contentResolver) }
        }
    }

    private val pickPhoneContactLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            val cursor = contentResolver.query(
                uri,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            ) ?: return@registerForActivityResult
            cursor.use {
                if (it.moveToFirst()) {
                    val name = it.getString(0) ?: return@registerForActivityResult
                    val phone = it.getString(1) ?: return@registerForActivityResult
                    sendPhoneContactMessage(name, phone)
                }
            }
        }
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
            binding.toolbarAvatar.setOnClickListener {
                startActivity(Intent(this, UserProfileActivity::class.java).apply {
                    putExtra("username", peer)
                })
            }
        }
        binding.toolbar.setOnClickListener {
            if (!isGroupChat) {
                startActivity(Intent(this, UserProfileActivity::class.java).apply {
                    putExtra("username", peer)
                })
            }
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
                binding.tvSelectionCount.text = getString(R.string.label_n_selected, count)
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
                    singleMsg.type != "deleted"
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
                                Toast.makeText(this, getString(R.string.toast_image_copied), Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                cm.setPrimaryClip(ClipData.newPlainText("file", msg.filename ?: msg.content))
                                Toast.makeText(this, getString(R.string.toast_filename_copied), Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            cm.setPrimaryClip(ClipData.newPlainText("file", msg.filename ?: msg.content))
                            Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                        }
                    }
                    "list" -> {
                        val text = formatListForCopy(msg)
                        cm.setPrimaryClip(ClipData.newPlainText("list", text))
                        Toast.makeText(this, getString(R.string.toast_list_copied), Toast.LENGTH_SHORT).show()
                    }
                    "location" -> {
                        val mapsUrl = try {
                            com.google.gson.JsonParser.parseString(msg.content)
                                .asJsonObject.get("mapsUrl")?.asString ?: msg.content
                        } catch (e: Exception) { msg.content }
                        cm.setPrimaryClip(ClipData.newPlainText("location", mapsUrl))
                        Toast.makeText(this, getString(R.string.toast_location_link_copied), Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        cm.setPrimaryClip(ClipData.newPlainText("message", msg.content))
                        Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, resources.getQuantityString(R.plurals.toast_messages_copied, messages.size, messages.size), Toast.LENGTH_SHORT).show()
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
                .setTitle(getString(R.string.dialog_edit_message_title))
                .setView(wrap)
                .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                    val newText = et.text.toString().trim()
                    if (newText.isNotBlank() && newText != msg.content) {
                        vm.editMessage(msg, newText, peer)
                    }
                    adapter.clearSelection()
                }
                .setNegativeButton(getString(R.string.btn_cancel)) { _, _ -> adapter.clearSelection() }
                .show()
        }

        binding.btnSelectionReact.setOnClickListener {
            val msg = adapter.getSelectedMessages().firstOrNull() ?: return@setOnClickListener
            val myEmoji = findMyEmoji(msg)
            val emojis = arrayOf("👍", "👎", "❤️", "😂", "😮", "😢", "😡", "🔥", "👏", "🎉")
            val labels = emojis.map { if (it == myEmoji) "$it ✓" else it }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_react_title))
                .setItems(labels) { _, which ->
                    val picked = emojis[which]
                    if (picked == myEmoji) vm.sendReaction(msg, null)
                    else vm.sendReaction(msg, picked)
                    adapter.clearSelection()
                }
                .setNegativeButton(getString(R.string.btn_cancel)) { _, _ -> adapter.clearSelection() }
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
                    .setTitle(getString(R.string.dialog_delete_message_title))
                    .setItems(arrayOf(getString(R.string.delete_option_for_everyone), getString(R.string.delete_option_for_me))) { _, which ->
                        if (which == 0) vm.deleteForEveryone(single)
                        else vm.deleteMessages(ids)
                        adapter.clearSelection()
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(resources.getQuantityString(R.plurals.dialog_delete_messages_title, count, count))
                    .setMessage(resources.getQuantityString(R.plurals.dialog_delete_messages_body, count))
                    .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                        vm.deleteMessages(ids)
                        adapter.clearSelection()
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
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
                showTodoDialog(current, getString(R.string.dialog_edit_todo_title)) { items, deletedIds ->
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
        binding.btnAttach.setOnClickListener {
            val sheet = AttachmentBottomSheet()
            sheet.listener = object : AttachmentBottomSheet.Listener {
                override fun onGalleryClick() { pickFile.launch("image/*") }
                override fun onCameraClick() { launchCamera() }
                override fun onFileClick() { pickFile.launch("*/*") }
                override fun onLocationClick() { vm.sendCurrentLocation(peer) }
                override fun onContactClick() { showContactPicker() }
            }
            sheet.show(supportFragmentManager, AttachmentBottomSheet.TAG)
        }

        binding.btnCall.visibility = if (isGroupChat) View.GONE else View.VISIBLE
        binding.btnCall.setOnClickListener {
            startActivity(Intent(this, CallActivity::class.java).apply {
                putExtra(CallActivity.EXTRA_PEER, peer)
                putExtra(CallActivity.EXTRA_IS_CALLER, true)
            })
        }
        binding.btnCall.setOnLongClickListener {
            val sheet = EmergencyBottomSheet()
            sheet.listener = object : EmergencyBottomSheet.Listener {
                override fun onPriorityCallClick() {
                    startActivity(Intent(this@ChatActivity, CallActivity::class.java).apply {
                        putExtra(CallActivity.EXTRA_PEER, peer)
                        putExtra(CallActivity.EXTRA_IS_CALLER, true)
                        putExtra(CallActivity.EXTRA_IS_EMERGENCY, true)
                    })
                }
                override fun onEmergencyCallClick() {
                    startActivity(Intent(this@ChatActivity, CallActivity::class.java).apply {
                        putExtra(CallActivity.EXTRA_PEER, peer)
                        putExtra(CallActivity.EXTRA_IS_CALLER, true)
                        putExtra(CallActivity.EXTRA_IS_EMERGENCY, true)
                    })
                    vm.sendEmergencyLocation(peer)
                }
                override fun onSosMessageClick() {
                    vm.sendSosMessage(peer)
                }
                override fun onRequestLocationClick() {
                    vm.sendLocationRequest(peer)
                }
            }
            sheet.show(supportFragmentManager, EmergencyBottomSheet.TAG)
            true
        }

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
                    binding.tvRecordingState.text = getString(R.string.label_voice_recording)
                    val result = voiceRecorder.stop()
                    if (result != null && result.durationSecs >= 1) {
                        vm.sendVoice(peer, result.file, result.waveform, result.durationSecs)
                    } else if (result != null) {
                        Toast.makeText(this, getString(R.string.toast_hold_longer_to_record), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }

        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrBlank()) {
                    binding.btnSend.visibility = View.GONE
                    binding.btnMic.visibility = View.VISIBLE
                } else {
                    binding.btnSend.visibility = View.VISIBLE
                    binding.btnMic.visibility = View.GONE
                }
            }
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
                    "avatar-update" -> {
                        val uname = json.get("username")?.asString
                        if (uname == peer) runOnUiThread { loadPeerAvatar() }
                    }
                    "typing" -> {
                        val from = json.get("from")?.asString
                        if (from == peer) runOnUiThread {
                            supportActionBar?.subtitle = getString(R.string.typing_indicator)
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
                                    .setTitle(getString(R.string.dialog_removed_from_group_title))
                                    .setMessage(getString(R.string.dialog_removed_from_group_message))
                                    .setPositiveButton(getString(R.string.btn_ok)) { _, _ -> finish() }
                                    .setCancelable(false)
                                    .show()
                                "deleted" -> AlertDialog.Builder(this@ChatActivity)
                                    .setTitle(getString(R.string.dialog_group_deleted_title))
                                    .setMessage(getString(R.string.dialog_group_deleted_message))
                                    .setPositiveButton(getString(R.string.btn_ok)) { _, _ -> finish() }
                                    .setCancelable(false)
                                    .show()
                                else -> {
                                    Toast.makeText(this@ChatActivity, getString(R.string.toast_you_left_group), Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            }
                        }
                    }
                    "group-state" -> {
                        val stateId = json.get("groupId")?.asString
                        if (stateId == groupId) runOnUiThread { loadGroupInfo() }
                    }
                    "group-error" -> {
                        val gid = json.get("groupId")?.asString
                        if (gid == groupId) {
                            val reason = json.get("reason")?.asString ?: ""
                            val message = json.get("message")?.asString ?: ""
                            val errorText = when (reason) {
                                "unauthorized"       -> getString(R.string.error_group_unauthorized)
                                "user-not-found"     -> getString(R.string.error_user_not_found)
                                "already-member"     -> getString(R.string.error_already_member)
                                "owner-cannot-leave" -> getString(R.string.error_owner_cannot_leave)
                                else -> message.ifEmpty { getString(R.string.error_generic) }
                            }
                            runOnUiThread {
                                Toast.makeText(this@ChatActivity, errorText, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    "group-avatar-update" -> {
                        val updatedId = json.get("groupId")?.asString
                        if (updatedId == groupId) {
                            lifecycleScope.launch(Dispatchers.IO) io@{
                                val db = com.fshu.next.data.local.AppDatabase.getInstance(this@ChatActivity)
                                val group = db.groupDao().getById(groupId!!) ?: return@io
                                val sizePx = (36 * resources.displayMetrics.density).toInt()
                                runOnUiThread { loadGroupAvatarInto(group, binding.toolbarAvatar, sizePx) }
                            }
                        }
                    }
                    "contact-accepted-refresh",
                    "messages-updated"         -> { /* Room LiveData re-delivers the updated list automatically */ }
                    "auto-location-peers" -> {
                        val arr = json.getAsJsonArray("peers") ?: return@collect
                        val enabled = arr.any { !it.isJsonNull && it.asString == peer }
                        isAutoLocationEnabled = enabled
                        runOnUiThread { invalidateOptionsMenu() }
                    }
                }
            }
        }
    }

    private fun launchCamera() {
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), 102
            )
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "photo_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        cameraImageUri = uri
        takePictureLauncher.launch(uri)
    }

    private fun showContactPicker() {
        val options = arrayOf(
            getString(R.string.contact_pick_phone),
            getString(R.string.contact_pick_fshu)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.contact_pick_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickPhoneContact()
                    1 -> pickFshuContact()
                }
            }
            .show()
    }

    private fun pickPhoneContact() {
        val intent = Intent(
            Intent.ACTION_PICK,
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        )
        pickPhoneContactLauncher.launch(intent)
    }

    private fun pickFshuContact() {
        lifecycleScope.launch(Dispatchers.IO) {
            val me = Prefs.getUsername(this@ChatActivity)
            val db = com.fshu.next.data.local.AppDatabase.getInstance(this@ChatActivity)
            val contacts = db.contactDao().getAcceptedContacts(me).distinctBy { it.contact }
            val items = contacts.map { c ->
                val display = Prefs.getContactNickname(this@ChatActivity, c.contact)
                    .takeIf { it.isNotBlank() } ?: c.contact
                ContactItem(c.contact, display)
            }
            withContext(Dispatchers.Main) {
                if (items.isEmpty()) {
                    Toast.makeText(this@ChatActivity, getString(R.string.no_contacts), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val names = items.map { it.displayName }.toTypedArray()
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle(getString(R.string.contact_pick_fshu))
                    .setItems(names) { _, which ->
                        val contact = items[which]
                        sendContactMessage(contact.username, contact.displayName)
                    }
                    .show()
            }
        }
    }

    private fun sendContactMessage(username: String, displayName: String) {
        Toast.makeText(this, "Send contact: $displayName — coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun sendPhoneContactMessage(name: String, phone: String) {
        Toast.makeText(this, "Send contact: $name — coming soon", Toast.LENGTH_SHORT).show()
    }

    data class ContactItem(val username: String, val displayName: String)

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
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
        BackgroundHelper.apply(
            rootView     = binding.root,
            bgImageView  = binding.ivBg,
            bgIndex      = Prefs.getChatBgIndex(this, peer),
            bgUri        = Prefs.getChatBgUri(this, peer),
            defaultColor = tv.data
        )
    }

    override fun onResume() {
        super.onResume()
        isActive = true
        currentPeer = peer
        FshuService.stopSosAlarm(peer)
        applyBackground()
        refreshNicknameMap()
        if (!isGroupChat) {
            title = getNickname(peer) ?: peer
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isInteractive) {
                vm.sendReadReceipts(peer)
            }
            isAutoLocationEnabled = Prefs.isAutoLocationEnabled(this, peer)
            com.fshu.next.data.remote.WebSocketClient.send(mapOf("type" to "get-auto-location"))
            invalidateOptionsMenu()
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
        menu.findItem(R.id.action_call)?.isVisible = false  // replaced by btn_call toolbar view
        menu.findItem(R.id.action_video_call)?.isVisible = !isGroupChat
        menu.findItem(R.id.action_group_info)?.isVisible = isGroupChat
        val isOwner = myGroupRole == "owner"
        menu.findItem(R.id.action_leave_group)?.isVisible = isGroupChat && !isOwner
        menu.findItem(R.id.action_delete_group)?.isVisible = isGroupChat && isOwner
        menu.findItem(R.id.action_export)?.isVisible = !isGroupChat
        menu.findItem(R.id.action_new_todo)?.isVisible = !isGroupChat
        menu.findItem(R.id.action_share_location)?.isVisible = !isGroupChat
        menu.findItem(R.id.action_request_location)?.isVisible = !isGroupChat
        menu.findItem(R.id.action_auto_location)?.apply {
            isVisible = !isGroupChat
            title = if (isAutoLocationEnabled)
                getString(R.string.auto_location_on)
            else
                getString(R.string.auto_location_off)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            R.id.action_group_info -> { showGroupInfo(); return true }
            R.id.action_leave_group -> {
                val gid = groupId ?: return true
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_leave_group_title, title))
                    .setMessage(getString(R.string.dialog_leave_group_confirm))
                    .setPositiveButton(getString(R.string.btn_leave)) { _, _ ->
                        com.fshu.next.data.remote.WebSocketClient.send(mapOf(
                            "type" to "group-leave",
                            "groupId" to gid
                        ))
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
                    .also { d ->
                        d.getButton(AlertDialog.BUTTON_POSITIVE)
                            ?.setTextColor(android.graphics.Color.parseColor("#E53935"))
                    }
                return true
            }
            R.id.action_delete_group -> {
                val gid = groupId ?: return true
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_delete_group_title, title))
                    .setMessage(getString(R.string.dialog_delete_group_message))
                    .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                        com.fshu.next.data.remote.WebSocketClient.send(mapOf(
                            "type" to "group-delete",
                            "groupId" to gid
                        ))
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
                    .also { d ->
                        d.getButton(AlertDialog.BUTTON_POSITIVE)
                            ?.setTextColor(android.graphics.Color.parseColor("#E53935"))
                    }
                return true
            }
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
                showTodoDialog(emptyList(), getString(R.string.dialog_new_todo_title)) { items, _ ->
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
            R.id.action_auto_location -> {
                isAutoLocationEnabled = !isAutoLocationEnabled
                com.fshu.next.data.remote.WebSocketClient.send(mapOf(
                    "type" to "set-auto-location",
                    "peer" to peer,
                    "enabled" to isAutoLocationEnabled
                ))
                invalidateOptionsMenu()
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
            this.text = getString(R.string.btn_add_item)
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
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
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
            .setNegativeButton(getString(R.string.btn_cancel), null)
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
            Toast.makeText(this, getString(R.string.toast_mic_permission_granted), Toast.LENGTH_SHORT).show()
        }
        if (requestCode == 102 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            launchCamera()
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
            val group = db.groupDao().getById(gid)
            if (group == null) {
                // Group not in local DB — request from server; group-state event will re-trigger this.
                com.fshu.next.data.remote.WebSocketClient.send(
                    mapOf("type" to "group-info-request", "groupId" to gid)
                )
                return@launch
            }
            val members = db.groupMemberDao().getMembersOf(gid)
            val me = Prefs.getUsername(this@ChatActivity)
            val role = members.find { it.username == me }?.role ?: "member"
            val sizePx = (36 * resources.displayMetrics.density).toInt()
            runOnUiThread {
                title = group.name
                supportActionBar?.subtitle = resources.getQuantityString(R.plurals.group_members_subtitle, members.size, members.size)
                loadGroupAvatarInto(group, binding.toolbarAvatar, sizePx)
                myGroupRole = role
                invalidateOptionsMenu()
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
                            text = getString(R.string.group_info_change_photo)
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
                        text = getString(R.string.group_info_set_personal_photo)
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
                    text = resources.getQuantityString(R.plurals.group_members_subtitle, members.size, members.size)
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
                            text = getString(R.string.btn_remove)
                            textSize = 12f
                            setTextColor(Color.parseColor("#E53935"))
                            background = null
                            minHeight = 0
                            minimumHeight = 0
                            setOnClickListener {
                                AlertDialog.Builder(this@ChatActivity)
                                    .setTitle(getString(R.string.dialog_remove_member_title, nick))
                                    .setPositiveButton(getString(R.string.btn_remove)) { _, _ ->
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
                                    .setNegativeButton(getString(R.string.btn_cancel), null)
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
                    .setNegativeButton(getString(R.string.btn_close), null)
                    .create()

                if (isOwnerOrAdmin) {
                    dialog.setButton(DialogInterface.BUTTON_NEUTRAL, getString(R.string.btn_add_member)) { _, _ ->
                        showAddMemberDialog(gid, members.map { it.username })
                    }
                }

                if (myRole == "owner") {
                    dialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.btn_delete_group)) { _, _ ->
                        AlertDialog.Builder(this@ChatActivity)
                            .setTitle(getString(R.string.dialog_delete_group_title, group.name))
                            .setMessage(getString(R.string.dialog_delete_group_message))
                            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                                com.fshu.next.data.remote.WebSocketClient.send(mapOf(
                                    "type" to "group-delete",
                                    "groupId" to gid
                                ))
                            }
                            .setNegativeButton(getString(R.string.btn_cancel), null)
                            .show()
                    }
                } else {
                    dialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.btn_leave_group)) { _, _ ->
                        AlertDialog.Builder(this@ChatActivity)
                            .setTitle(getString(R.string.dialog_leave_group_title, group.name))
                            .setPositiveButton(getString(R.string.btn_leave)) { _, _ ->
                                com.fshu.next.data.remote.WebSocketClient.send(mapOf(
                                    "type" to "group-leave",
                                    "groupId" to gid
                                ))
                            }
                            .setNegativeButton(getString(R.string.btn_cancel), null)
                            .show()
                    }
                }

                dialog.show()
                dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                    ?.setTextColor(Color.parseColor("#E53935"))
            }
        }
    }

    private fun showAddMemberDialog(groupId: String, existingMembers: List<String>) {
        val me = Prefs.getUsername(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val db = com.fshu.next.data.local.AppDatabase.getInstance(this@ChatActivity)
            val contacts = db.contactDao().getAcceptedContacts(me)
            val candidates = contacts.filter { it.contact !in existingMembers && it.contact != me }
            runOnUiThread {
                if (candidates.isEmpty()) {
                    Toast.makeText(this@ChatActivity, getString(R.string.toast_no_contacts_to_add), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val names = candidates.map { c ->
                    Prefs.getContactNickname(this@ChatActivity, c.contact).takeIf { it.isNotBlank() } ?: c.contact
                }.toTypedArray()
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle(getString(R.string.btn_add_member))
                    .setItems(names) { _, index ->
                        addMemberByUsername(groupId, existingMembers, candidates[index].contact)
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
        }
    }

    private fun addMemberByUsername(groupId: String, existingMembers: List<String>, targetUsername: String) {
        if (targetUsername.isBlank()) {
            Toast.makeText(this, getString(R.string.error_username_empty), Toast.LENGTH_SHORT).show()
            return
        }
        if (existingMembers.contains(targetUsername)) {
            Toast.makeText(this, getString(R.string.error_already_member), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val db = com.fshu.next.data.local.AppDatabase.getInstance(this@ChatActivity)
            val group = db.groupDao().getById(groupId)
            val groupKeyHex = group?.groupKey ?: ""
            if (groupKeyHex.isEmpty()) {
                runOnUiThread { Toast.makeText(this@ChatActivity, getString(R.string.toast_group_key_not_available), Toast.LENGTH_SHORT).show() }
                return@launch
            }
            val memberPubKey = Prefs.getPeerPublicKey(this@ChatActivity, targetUsername)
            if (memberPubKey.isEmpty()) {
                runOnUiThread { Toast.makeText(this@ChatActivity, getString(R.string.toast_cannot_invite_no_key, targetUsername), Toast.LENGTH_SHORT).show() }
                return@launch
            }
            val groupKeyBytes = with(com.fshu.next.util.EcdhHelper) { groupKeyHex.fromHex() }
            val myPriv = Prefs.getEcPrivateKey(this@ChatActivity)
            val encryptedKey = com.fshu.next.util.CryptoHelper.encryptGroupKeyForMember(groupKeyBytes, memberPubKey, myPriv)
            com.fshu.next.data.remote.WebSocketClient.send(mapOf(
                "type"         to "group-invite",
                "groupId"      to groupId,
                "username"     to targetUsername,
                "encryptedKey" to encryptedKey
            ))
        }
    }

    private fun exportConversation() {
        val messages = adapter.currentList
        if (messages.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_nothing_to_export), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@ChatActivity, getString(R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
