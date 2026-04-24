package com.fshu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.fshu.data.local.AppDatabase
import com.fshu.data.model.Message
import com.fshu.data.model.User
import com.fshu.data.remote.WebSocketClient
import com.fshu.databinding.ActivityMainBinding
import com.fshu.service.FshuService
import com.fshu.ui.BackgroundBottomSheet
import com.fshu.ui.BackgroundHelper
import com.fshu.ui.ConnectionTestSheet
import com.fshu.ui.admin.AdminPanelActivity
import com.fshu.ui.admin.ChangePasswordDialog
import com.fshu.ui.call.CallActivity
import com.fshu.ui.chat.ChatActivity
import com.fshu.ui.login.LoginActivity
import com.fshu.ui.passphrase.PassphraseSetupActivity
import com.fshu.ui.settings.SettingsActivity
import com.fshu.util.CryptoHelper
import com.fshu.util.LocationHelper
import com.fshu.util.MessageBus
import com.fshu.util.Prefs
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

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
            else -> super.onOptionsItemSelected(item)
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
            "message", "file", "list", "location", "location-request", "location-response" -> {
                if (users.isNotEmpty()) launchEnrich(users.toList())
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
