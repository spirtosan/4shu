package com.fshu.next.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.JsonObject
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityAdminPanelBinding
import com.fshu.next.util.MessageBus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AdminUser(
    val username: String,
    val createdAt: String,
    val isAdmin: Boolean
)

data class AdminInvite(
    val token: String,
    val createdBy: String,
    val expiresAt: Long,
    val usedBy: String?,
    val url: String
)

class AdminPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminPanelBinding
    private val userItems = mutableListOf<AdminUser>()
    private lateinit var adapter: AdminUserAdapter
    private val inviteItems = mutableListOf<AdminInvite>()
    private lateinit var inviteAdapter: AdminInviteAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = AdminUserAdapter(
            userItems,
            onResetPassword = { user -> showResetPasswordDialog(user) },
            onRemove = { user -> showRemoveDialog(user) }
        )
        binding.rvAdminUsers.layoutManager = LinearLayoutManager(this)
        binding.rvAdminUsers.adapter = adapter
        binding.rvAdminUsers.isNestedScrollingEnabled = false

        inviteAdapter = AdminInviteAdapter(
            inviteItems,
            onCopy = { invite ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("invite", invite.url))
                Toast.makeText(this, "Invite link copied!", Toast.LENGTH_SHORT).show()
            },
            onRevoke = { invite -> revokeInvite(invite) }
        )
        binding.rvAdminInvites.layoutManager = LinearLayoutManager(this)
        binding.rvAdminInvites.adapter = inviteAdapter
        binding.rvAdminInvites.isNestedScrollingEnabled = false

        binding.fabAddUser.setOnClickListener { showAddUserDialog() }
        binding.btnGenerateInvite.setOnClickListener { generateInvite() }

        loadUsers()
        loadInvites()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_admin_panel, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        if (item.itemId == R.id.action_server_info) { requestServerInfo(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun requestServerInfo() {
        lifecycleScope.launch {
            val ch = Channel<JsonObject>(1)
            val job = launch {
                MessageBus.events.collect {
                    val t = it.get("type")?.asString
                    if (t == "admin-server-info" || t == "admin-error") ch.trySend(it)
                }
            }
            WebSocketClient.send(mapOf("type" to "admin-server-info"))
            val result = withTimeoutOrNull(5_000) { ch.receive() }
            job.cancel()
            if (result == null) { Toast.makeText(this@AdminPanelActivity, getString(R.string.toast_no_response), Toast.LENGTH_SHORT).show(); return@launch }
            if (result.get("type")?.asString == "admin-error") { Toast.makeText(this@AdminPanelActivity, result.get("message")?.asString ?: getString(R.string.toast_error), Toast.LENGTH_SHORT).show(); return@launch }
            val disk = result.get("disk")?.asString ?: "N/A"
            val filesCount = result.get("filesCount")?.asInt ?: 0
            val filesMB = result.get("filesMB")?.asString ?: "0 MB"
            val historyCount = result.get("historyCount")?.asInt ?: 0
            androidx.appcompat.app.AlertDialog.Builder(this@AdminPanelActivity)
                .setTitle(getString(R.string.dialog_server_info_title))
                .setMessage(getString(R.string.admin_server_info_format, disk, filesCount, filesMB, historyCount))
                .setPositiveButton(getString(R.string.btn_ok), null)
                .show()
        }
    }

    private fun loadUsers() {
        binding.tvAdminStatus.text = getString(R.string.label_loading)
        binding.tvAdminStatus.visibility = View.VISIBLE
        lifecycleScope.launch {
            val ch = Channel<JsonObject>(1)
            val job = launch {
                MessageBus.events.collect {
                    val t = it.get("type")?.asString
                    if (t == "admin-users" || t == "admin-error") ch.trySend(it)
                }
            }
            WebSocketClient.send(mapOf("type" to "admin-list-users"))
            val result = withTimeoutOrNull(5_000) { ch.receive() }
            job.cancel()
            if (result == null) {
                binding.tvAdminStatus.text = getString(R.string.label_no_response_server)
                return@launch
            }
            if (result.get("type")?.asString == "admin-error") {
                binding.tvAdminStatus.text = result.get("message")?.asString ?: getString(R.string.label_access_denied)
                return@launch
            }
            val arr = result.getAsJsonArray("users") ?: run {
                binding.tvAdminStatus.text = getString(R.string.label_invalid_response)
                return@launch
            }
            val list = arr.map { el ->
                val obj = el.asJsonObject
                AdminUser(
                    username   = obj.get("username")?.asString ?: "",
                    createdAt  = obj.get("createdAt")?.asString ?: "",
                    isAdmin    = obj.get("admin")?.asBoolean ?: false
                )
            }
            userItems.clear()
            userItems.addAll(list)
            adapter.notifyDataSetChanged()
            binding.tvAdminStatus.visibility = View.GONE
        }
    }

    private fun loadInvites() {
        lifecycleScope.launch {
            val ch = Channel<JsonObject>(1)
            val job = launch {
                MessageBus.events.collect {
                    if (it.get("type")?.asString == "admin-invite-list") ch.trySend(it)
                }
            }
            WebSocketClient.send(mapOf("type" to "admin-invite-list"))
            val result = withTimeoutOrNull(5_000) { ch.receive() }
            job.cancel()
            if (result != null) updateInviteList(result)
        }
    }

    private fun generateInvite() {
        lifecycleScope.launch {
            val ch = Channel<JsonObject>(2)
            val job = launch {
                MessageBus.events.collect {
                    val t = it.get("type")?.asString
                    if (t == "admin-invite-created" || t == "admin-invite-list") ch.trySend(it)
                }
            }
            WebSocketClient.send(mapOf("type" to "admin-invite-create"))
            val created = withTimeoutOrNull(5_000) { ch.receive() }
            if (created?.get("type")?.asString == "admin-invite-created") {
                val url = created.get("url")?.asString ?: ""
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("invite", url))
                Toast.makeText(this@AdminPanelActivity, "Invite link copied!", Toast.LENGTH_SHORT).show()
            }
            WebSocketClient.send(mapOf("type" to "admin-invite-list"))
            val listResult = withTimeoutOrNull(5_000) { ch.receive() }
            job.cancel()
            if (listResult?.get("type")?.asString == "admin-invite-list") updateInviteList(listResult)
        }
    }

    private fun revokeInvite(invite: AdminInvite) {
        lifecycleScope.launch {
            val ch = Channel<JsonObject>(2)
            val job = launch {
                MessageBus.events.collect {
                    val t = it.get("type")?.asString
                    if (t == "admin-result" || t == "admin-error" || t == "admin-invite-list") ch.trySend(it)
                }
            }
            WebSocketClient.send(mapOf("type" to "admin-invite-revoke", "token" to invite.token))
            withTimeoutOrNull(3_000) { ch.receive() }
            WebSocketClient.send(mapOf("type" to "admin-invite-list"))
            val listResult = withTimeoutOrNull(5_000) { ch.receive() }
            job.cancel()
            if (listResult?.get("type")?.asString == "admin-invite-list") updateInviteList(listResult)
        }
    }

    private fun updateInviteList(result: JsonObject) {
        val arr = result.getAsJsonArray("invites") ?: return
        val list = arr.mapNotNull { el ->
            val obj = el.asJsonObject
            val usedBy = obj.get("used_by")?.takeIf { !it.isJsonNull }?.asString
            if (usedBy != null) return@mapNotNull null
            AdminInvite(
                token     = obj.get("token")?.asString ?: return@mapNotNull null,
                createdBy = obj.get("created_by")?.asString ?: "",
                expiresAt = obj.get("expires_at")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                usedBy    = null,
                url       = obj.get("url")?.asString ?: ""
            )
        }
        inviteItems.clear()
        inviteItems.addAll(list)
        inviteAdapter.notifyDataSetChanged()
    }

    private fun showAddUserDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_user, null)
        val etUsername = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDialogUsername)
        val etPassword = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDialogPassword)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_add_user_title))
            .setView(view)
            .setPositiveButton(getString(R.string.btn_add)) { _, _ ->
                val username = etUsername.text?.toString()?.trim() ?: ""
                val password = etPassword.text?.toString()?.trim() ?: ""
                if (username.isBlank() || password.isBlank()) {
                    Toast.makeText(this, getString(R.string.toast_username_password_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                sendAdminAction(mapOf("type" to "admin-add-user", "username" to username, "password" to password))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showResetPasswordDialog(user: AdminUser) {
        val view = layoutInflater.inflate(R.layout.dialog_reset_password, null)
        val etPassword = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDialogNewPassword)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_reset_password_title, user.username))
            .setView(view)
            .setPositiveButton(getString(R.string.btn_reset)) { _, _ ->
                val newPass = etPassword.text?.toString()?.trim() ?: ""
                if (newPass.isBlank()) {
                    Toast.makeText(this, getString(R.string.toast_password_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                sendAdminAction(mapOf("type" to "admin-reset-password", "username" to user.username, "newPassword" to newPass))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showRemoveDialog(user: AdminUser) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_remove_user_title))
            .setMessage(getString(R.string.dialog_remove_user_message, user.username))
            .setPositiveButton(getString(R.string.btn_remove)) { _, _ ->
                sendAdminAction(mapOf("type" to "admin-remove-user", "username" to user.username))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun sendAdminAction(payload: Map<String, Any>) {
        lifecycleScope.launch {
            val ch = Channel<JsonObject>(1)
            val job = launch {
                MessageBus.events.collect {
                    val t = it.get("type")?.asString
                    if (t == "admin-result" || t == "admin-error") ch.trySend(it)
                }
            }
            WebSocketClient.send(payload)
            val result = withTimeoutOrNull(5_000) { ch.receive() }
            job.cancel()
            when {
                result == null ->
                    Toast.makeText(this@AdminPanelActivity, getString(R.string.toast_no_response_from_server), Toast.LENGTH_SHORT).show()
                result.get("type")?.asString == "admin-error" ->
                    Toast.makeText(this@AdminPanelActivity, result.get("message")?.asString ?: getString(R.string.toast_error), Toast.LENGTH_SHORT).show()
                else -> {
                    Toast.makeText(this@AdminPanelActivity, result.get("message")?.asString ?: getString(R.string.toast_done), Toast.LENGTH_SHORT).show()
                    loadUsers()
                }
            }
        }
    }

    inner class AdminUserAdapter(
        private val items: List<AdminUser>,
        private val onResetPassword: (AdminUser) -> Unit,
        private val onRemove: (AdminUser) -> Unit
    ) : RecyclerView.Adapter<AdminUserAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvUsername: TextView = view.findViewById(R.id.tvUsername)
            val tvCreatedAt: TextView = view.findViewById(R.id.tvCreatedAt)
            val tvAdminBadge: TextView = view.findViewById(R.id.tvAdminBadge)
            val btnReset: Button = view.findViewById(R.id.btnResetPassword)
            val btnRemove: Button = view.findViewById(R.id.btnRemoveUser)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_admin_user, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val user = items[position]
            holder.tvUsername.text = user.username
            holder.tvCreatedAt.text = if (user.createdAt.isNotBlank()) "Created: ${user.createdAt}" else ""
            holder.tvAdminBadge.visibility = if (user.isAdmin) View.VISIBLE else View.GONE
            holder.btnReset.setOnClickListener { onResetPassword(user) }
            holder.btnRemove.setOnClickListener { onRemove(user) }
        }
    }

    inner class AdminInviteAdapter(
        private val items: List<AdminInvite>,
        private val onCopy: (AdminInvite) -> Unit,
        private val onRevoke: (AdminInvite) -> Unit
    ) : RecyclerView.Adapter<AdminInviteAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvToken: TextView = view.findViewById(R.id.tvInviteToken)
            val tvExpiry: TextView = view.findViewById(R.id.tvInviteExpiry)
            val btnCopy: Button = view.findViewById(R.id.btnCopyInvite)
            val btnRevoke: Button = view.findViewById(R.id.btnRevokeInvite)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_admin_invite, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val invite = items[position]
            holder.tvToken.text = "${invite.token.take(8)}..."
            holder.tvExpiry.text = if (invite.expiresAt > 0) {
                "Expires: ${SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(invite.expiresAt))}"
            } else {
                "No expiry"
            }
            holder.btnCopy.setOnClickListener { onCopy(invite) }
            holder.btnRevoke.setOnClickListener { onRevoke(invite) }
        }
    }
}
