package com.fshu.next

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import coil.load
import coil.transform.CircleCropTransformation
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.data.model.Message
import com.fshu.next.data.model.User
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityMainBinding
import com.fshu.next.service.FshuService
import com.fshu.next.ui.AppLockManager
import com.fshu.next.ui.BackgroundBottomSheet
import com.fshu.next.ui.BackgroundHelper
import com.fshu.next.ui.ConnectionTestSheet
import com.fshu.next.ui.admin.AdminPanelActivity
import com.fshu.next.ui.admin.ChangePasswordDialog
import com.fshu.next.ui.call.CallActivity
import com.fshu.next.ui.chat.ChatActivity
import com.fshu.next.ui.login.LoginActivity
import com.fshu.next.ui.passphrase.PassphraseSetupActivity
import com.fshu.next.ui.settings.SettingsActivity
import com.fshu.next.util.CrashHandler
import com.fshu.next.util.CryptoHelper
import com.fshu.next.util.LocationHelper
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val users = mutableListOf<User>()
    private lateinit var adapter: UserAdapter
    private var pendingEmergencyLocationUser: User? = null
    private var enrichJob: kotlinx.coroutines.Job? = null

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingEmergencyLocationUser?.let { showEmergencyLocationDialog(it) }
        } else {
            Toast.makeText(this, "Location permission required for this feature", Toast.LENGTH_SHORT).show()
        }
        pendingEmergencyLocationUser = null
    }

    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri) ?: return@launch
                val original = BitmapFactory.decodeStream(stream)
                stream.close()
                val side = minOf(original.width, original.height)
                val x = (original.width - side) / 2
                val y = (original.height - side) / 2
                val cropped = Bitmap.createBitmap(original, x, y, side, side)
                val scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                WebSocketClient.send(mapOf("type" to "avatar-upload", "data" to b64))
                val me = Prefs.getUsername(this@MainActivity)
                val dir = File(filesDir, "avatars").also { it.mkdirs() }
                File(dir, "$me.jpg").writeBytes(baos.toByteArray())
                withContext(kotlinx.coroutines.Dispatchers.Main) { loadMyAvatar() }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to upload avatar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashHandler.install(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        loadMyAvatar()

        adapter = UserAdapter(
            users,
            onClick = { user ->
                startActivity(Intent(this, ChatActivity::class.java).apply {
                    putExtra(ChatActivity.EXTRA_PEER, user.username)
                })
            },
            onCall = { user ->
                startActivity(Intent(this, CallActivity::class.java).apply {
                    putExtra(CallActivity.EXTRA_PEER, user.username)
                    putExtra(CallActivity.EXTRA_IS_CALLER, true)
                })
            },
            onVideoCall = { user ->
                startActivity(Intent(this, CallActivity::class.java).apply {
                    putExtra(CallActivity.EXTRA_PEER, user.username)
                    putExtra(CallActivity.EXTRA_IS_CALLER, true)
                    putExtra(CallActivity.EXTRA_IS_VIDEO_CALL, true)
                })
            },
            onTestConnection = { user ->
                ConnectionTestSheet.newInstance(user.username, user.online)
                    .show(supportFragmentManager, "conn_test")
            },
            onEmergencyCall = { user ->
                AlertDialog.Builder(this)
                    .setTitle("Emergency call")
                    .setMessage("Send emergency call to ${user.username}?\nThis will override silent mode on their device.")
                    .setPositiveButton("Send Emergency") { _, _ ->
                        startActivity(Intent(this, CallActivity::class.java).apply {
                            putExtra(CallActivity.EXTRA_PEER, user.username)
                            putExtra(CallActivity.EXTRA_IS_CALLER, true)
                            putExtra(CallActivity.EXTRA_IS_EMERGENCY, true)
                        })
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onEmergencyWithLocation = { user ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                    pendingEmergencyLocationUser = user
                    requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    showEmergencyLocationDialog(user)
                }
            },
            onRequestLocation = { user ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val me = Prefs.getUsername(this@MainActivity)
                    val requestId = UUID.randomUUID().toString()
                    val ts = System.currentTimeMillis()
                    val contentJson = """{"requestId":"$requestId"}"""
                    AppDatabase.getInstance(this@MainActivity).messageDao().insert(
                        Message(from = me, to = user.username, content = contentJson,
                            type = "location-request", timestamp = ts, isSent = true, status = "SENT")
                    )
                    WebSocketClient.send(mapOf(
                        "type" to "location-request", "from" to me, "to" to user.username,
                        "requestId" to requestId, "timestamp" to ts
                    ))
                }
            }
        )
        binding.rvUsers.layoutManager = LinearLayoutManager(this)
        binding.rvUsers.adapter = adapter

        val dragCallback = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or
            androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                adapter.moveItem(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {}
            override fun clearView(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ) {
                super.clearView(rv, vh)
                saveUserOrder()
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(dragCallback).attachToRecyclerView(binding.rvUsers)

        startService(Intent(this, FshuService::class.java))

        // Load cached user list immediately so the list is populated before server update arrives.
        loadCachedUsers()

        lifecycleScope.launch {
            MessageBus.events.collect { handleMessage(it) }
        }

        // If the service already emitted "users" before our collector registered (common on
        // first launch when SharedFlow has no replay buffer), process it now so the list
        // isn't blank until the next server broadcast.
        FshuService.lastUsersJson?.let { handleMessage(it) }

        supportFragmentManager.setFragmentResultListener(BackgroundBottomSheet.RESULT_KEY, this) { _, _ ->
            applyBackground()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_admin_panel)?.isVisible = Prefs.isAdmin(this)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_admin_panel -> {
                startActivity(Intent(this, AdminPanelActivity::class.java))
                true
            }
            R.id.action_reconnect -> {
                Toast.makeText(this, "Reconnecting…", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, FshuService::class.java).apply {
                    action = FshuService.ACTION_RECONNECT
                }
                startService(intent)
                lifecycleScope.launch {
                    val result = withTimeoutOrNull(5_000) {
                        MessageBus.events
                            .filter { it.get("type")?.asString == "connection-status" }
                            .first()
                    }
                    when {
                        result == null ->
                            Toast.makeText(this@MainActivity,
                                "No response", Toast.LENGTH_SHORT).show()
                        result.get("connected")?.asBoolean == true ->
                            Toast.makeText(this@MainActivity,
                                "Connected ✓", Toast.LENGTH_SHORT).show()
                        else ->
                            Toast.makeText(this@MainActivity,
                                "Failed ✗", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            R.id.action_change_background -> {
                BackgroundBottomSheet.newInstance(BackgroundBottomSheet.SCREEN_MAIN)
                    .show(supportFragmentManager, "bg_main")
                true
            }
            R.id.action_set_nickname -> {
                val current = Prefs.getMyNickname(this)
                val et = android.widget.EditText(this).apply {
                    setText(current)
                    hint = "Your nickname (max 20 chars)"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
                    filters = arrayOf(android.text.InputFilter.LengthFilter(20))
                }
                val pad = (16 * resources.displayMetrics.density).toInt()
                val wrap = android.widget.FrameLayout(this).apply {
                    setPadding(pad, pad / 2, pad, 0)
                    addView(et)
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Set my nickname")
                    .setMessage("This will be visible to everyone. Leave empty to use your username.")
                    .setView(wrap)
                    .setPositiveButton("Save") { _, _ ->
                        val nickname = et.text.toString().trim()
                        Prefs.setMyNickname(this, nickname)
                        if (WebSocketClient.isConnected) {
                            WebSocketClient.send(mapOf(
                                "type" to "set-nickname",
                                "nickname" to nickname
                            ))
                        }
                        Toast.makeText(this,
                            if (nickname.isEmpty()) "Nickname cleared" else "Nickname set to \"$nickname\"",
                            Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            R.id.action_set_avatar -> {
                pickAvatarLauncher.launch("image/*")
                true
            }
            R.id.action_crash_log -> {
                val crashes = CrashHandler.getAllCrashes(this)
                if (crashes.isEmpty()) {
                    Toast.makeText(this, "No crash logs found", Toast.LENGTH_SHORT).show()
                } else {
                    val fullLog = crashes.joinToString("\n\n")
                    val tv = android.widget.TextView(this).apply {
                        text = fullLog
                        setPadding(32, 16, 32, 16)
                        setTextIsSelectable(true)
                        textSize = 11f
                    }
                    val scroll = android.widget.ScrollView(this).apply {
                        addView(tv)
                    }
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Crash logs (${crashes.size})")
                        .setView(scroll)
                        .setPositiveButton("Copy all") { _, _ ->
                            val clipboard = getSystemService(
                                android.content.ClipboardManager::class.java)
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("crash log", fullLog))
                            Toast.makeText(this, "Copied to clipboard",
                                Toast.LENGTH_SHORT).show()
                        }
                        .setNeutralButton("Clear") { _, _ ->
                            CrashHandler.clearCrashes(this)
                            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadMyAvatar() {
        try {
            val me = Prefs.getUsername(this)
            if (me.isEmpty()) return
            val avatarFile = File(filesDir, "avatars/$me.jpg")
            val sizePx = (36 * resources.displayMetrics.density).toInt()
            val iv = binding.toolbarMyAvatar ?: return
            if (avatarFile.exists()) {
                iv.load(avatarFile) {
                    transformations(CircleCropTransformation())
                }
            } else {
                val letter = me.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                val color = when (me.hashCode().let { if (it < 0) -it else it } % 10) {
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
                iv.setImageBitmap(bmp)
            }
            iv.setOnClickListener { pickAvatarLauncher.launch("image/*") }
        } catch (e: Exception) {
            Log.e("MainActivity", "loadMyAvatar failed", e)
        }
    }

    private fun showEmergencyLocationDialog(user: User) {
        AlertDialog.Builder(this)
            .setTitle("Emergency call + location")
            .setMessage("This will call ${user.username} AND immediately share your current location.\nOnly use in genuine emergencies.")
            .setPositiveButton("Send Emergency") { _, _ ->
                startActivity(Intent(this, CallActivity::class.java).apply {
                    putExtra(CallActivity.EXTRA_PEER, user.username)
                    putExtra(CallActivity.EXTRA_IS_CALLER, true)
                    putExtra(CallActivity.EXTRA_IS_EMERGENCY, true)
                })
                lifecycleScope.launch(Dispatchers.IO) {
                    val me = Prefs.getUsername(this@MainActivity)
                    val location = LocationHelper.getCurrentLocation(this@MainActivity)
                    if (location != null) {
                        val mapsUrl = LocationHelper.buildMapsUrl(location.latitude, location.longitude)
                        val ts = System.currentTimeMillis()
                        val contentJson = """{"lat":${location.latitude},"lon":${location.longitude},"accuracy":${location.accuracy},"timestamp":$ts,"mapsUrl":"$mapsUrl"}"""
                        val id = AppDatabase.getInstance(this@MainActivity).messageDao().insert(
                            Message(from = me, to = user.username, content = contentJson,
                                type = "location", timestamp = ts, isSent = true, status = "SENDING")
                        )
                        WebSocketClient.send(mapOf(
                            "type" to "emergency-location", "from" to me, "to" to user.username,
                            "lat" to location.latitude, "lon" to location.longitude,
                            "accuracy" to location.accuracy, "timestamp" to ts,
                            "messageId" to id
                        ))
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyBackground() {
        BackgroundHelper.apply(
            rootView     = binding.root,
            bgImageView  = binding.ivBg,
            bgIndex      = Prefs.getMainBgIndex(this),
            bgUri        = Prefs.getMainBgUri(this),
            defaultColor = ContextCompat.getColor(this, R.color.bg_primary)
        )
    }

    override fun onStart() {
        super.onStart()
        if (AppLockManager.shouldLock(this)) {
            AppLockManager.showPrompt(
                activity = this,
                onSuccess = { /* unlocked, continue normally */ },
                onFailed = { finishAffinity() }
            )
        }
    }

    override fun onStop() {
        super.onStop()
        AppLockManager.onAppBackground()
    }

    override fun onResume() {
        super.onResume()
        applyBackground()
        // Refresh last-message previews whenever the user returns to this screen.
        if (users.isNotEmpty()) {
            launchEnrich(users.toList())
        }
    }

    private fun launchEnrich(list: List<User>) {
        enrichJob?.cancel()
        enrichJob = lifecycleScope.launch { enrichWithLastMessages(list) }
    }

    private fun loadCachedUsers() {
        val me = Prefs.getUsername(this)
        val json = Prefs.getCachedUsers(this)
        val list = try {
            JsonParser.parseString(json).asJsonArray.mapNotNull { element ->
                val obj = element.asJsonObject
                val username = obj.get("username")?.asString ?: return@mapNotNull null
                val nickname = obj.get("nickname")?.takeIf { !it.isJsonNull }?.asString
                val lastSeen = obj.get("lastSeen")?.takeIf { !it.isJsonNull }?.asLong
                User(username, online = false, nickname = nickname, lastSeen = lastSeen)
            }.filter { it.username != me }
        } catch (e: Exception) { emptyList() }
        if (list.isNotEmpty()) {
            launchEnrich(list)
        }
    }

    private fun applyUserOrder(list: List<User>): List<User> {
        val order = Prefs.getUserOrder(this)
        if (order.isEmpty()) return list
        val rankOf = order.withIndex().associate { (i, u) -> u to i }
        return list.sortedWith(compareBy { rankOf[it.username] ?: Int.MAX_VALUE })
    }

    private fun saveUserOrder() {
        Prefs.setUserOrder(this, users.map { it.username })
    }

    private fun handleMessage(json: JsonObject) {
        when (json.get("type")?.asString) {
            "auth-ok" -> runOnUiThread { invalidateOptionsMenu() }
            "users" -> {
                val me = Prefs.getUsername(this)
                val arr = json.getAsJsonArray("users") ?: return
                val list = arr.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val username = obj.get("username")?.asString ?: return@mapNotNull null
                    if (username.startsWith("_")) return@mapNotNull null
                    val online = obj.get("online")?.asBoolean ?: false
                    val lastSeen = obj.get("lastSeen")?.takeIf { !it.isJsonNull }?.asLong
                    val nickname = obj.get("nickname")?.takeIf { !it.isJsonNull }?.asString
                    User(username, online, lastSeen = lastSeen, nickname = nickname)
                }.filter { it.username != me }

                // Persist for next launch
                val cacheArr = JsonArray()
                list.forEach { u ->
                    val obj = JsonObject()
                    obj.addProperty("username", u.username)
                    obj.addProperty("online", u.online)
                    if (u.nickname != null) obj.addProperty("nickname", u.nickname)
                    obj.addProperty("lastSeen", u.lastSeen)
                    cacheArr.add(obj)
                }
                Prefs.setCachedUsers(this, cacheArr.toString())

                launchEnrich(list)
            }
            "users-update" -> {
                val arr = json.getAsJsonArray("onlineUsers") ?: return
                val onlineSet = arr.map { it.asString }.toSet()
                val updated = users.map { it.copy(online = it.username in onlineSet) }
                users.clear()
                users.addAll(updated)
                adapter.notifyDataSetChanged()
            }
            "avatar-update" -> {
                try {
                    val uname = json.get("username")?.asString ?: return
                    val me = Prefs.getUsername(this)
                    if (uname == me) {
                        runOnUiThread { loadMyAvatar() }
                    } else {
                        val idx = users.indexOfFirst { it.username == uname }
                        if (idx >= 0) runOnUiThread { adapter.notifyItemChanged(idx) }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "avatar-update handler failed", e)
                }
            }
            "message", "file", "list", "location", "location-request", "location-response" -> {
                val from = json.get("from")?.asString ?: return
                val to = json.get("to")?.asString ?: return
                val me = Prefs.getUsername(this)
                val peer = if (from == me) to else from
                val type = json.get("type")?.asString
                val ts = json.get("timestamp")?.asLong ?: System.currentTimeMillis()
                val senderName = if (from == me) "You" else {
                    users.find { it.username == from }?.displayName ?: from
                }
                val preview = when (type) {
                    "list"             -> "$senderName: 📝 Todo list"
                    "file"             -> "$senderName: 📎 ${json.get("filename")?.asString ?: "File"}"
                    "location"         -> "$senderName: 📍 Location"
                    "location-request" -> "$senderName: 📍 Location requested"
                    else -> {
                        val rawContent = json.get("content")?.asString ?: ""
                        val messageId = json.get("messageId")?.asLong ?: 0L
                        val decrypted = if (CryptoHelper.isReady(this) && messageId != 0L) {
                            CryptoHelper.decrypt(CryptoHelper.getKey(this, peer), messageId, ts, rawContent) ?: rawContent
                        } else rawContent
                        "$senderName: $decrypted"
                    }
                }
                runOnUiThread {
                    val idx = users.indexOfFirst { it.username == peer }
                    if (idx >= 0) {
                        users[idx] = users[idx].copy(lastMessage = preview, lastMessageTime = ts)
                        adapter.notifyItemChanged(idx)
                    }
                }
            }
        }
    }

    private suspend fun enrichWithLastMessages(list: List<User>) {
        val me = Prefs.getUsername(this)
        val db = AppDatabase.getInstance(this)
        val enriched = withContext(Dispatchers.IO) {
            list.map { user ->
                val last = db.messageDao().getLastMessage(user.username, me)
                val preview = when (last?.type) {
                    "list"             -> "\uD83D\uDCDD Todo list"
                    "file"             -> "\uD83D\uDCCE ${last.filename ?: last.content}"
                    "location"         -> "\uD83D\uDCCD Location"
                    "location-request" -> "\uD83D\uDCCD Location requested"
                    else               -> last?.content
                }
                user.copy(lastMessage = preview, lastMessageTime = last?.timestamp)
            }
        }
        runOnUiThread {
            // Preserve online status that may have been updated by a pong while DB was loading.
            data class OnlineState(val online: Boolean, val lastSeen: Long?)
            val stateMap = users.associate { it.username to OnlineState(it.online, it.lastSeen) }
            val merged = enriched.map { u ->
                val s = stateMap[u.username]
                u.copy(online = s?.online ?: u.online, lastSeen = s?.lastSeen ?: u.lastSeen)
            }
            users.clear()
            users.addAll(applyUserOrder(merged))
            adapter.notifyDataSetChanged()
        }
    }
}
