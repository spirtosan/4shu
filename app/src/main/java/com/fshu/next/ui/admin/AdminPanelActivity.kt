package com.fshu.next.ui.admin

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

data class AdminUser(
    val username: String,
    val createdAt: String,
    val isAdmin: Boolean
)

class AdminPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminPanelBinding
    private val userItems = mutableListOf<AdminUser>()
    private lateinit var adapter: AdminUserAdapter

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

        binding.fabAddUser.setOnClickListener { showAddUserDialog() }

        loadUsers()
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
            if (result == null) { Toast.makeText(this@AdminPanelActivity, "No response", Toast.LENGTH_SHORT).show(); return@launch }
            if (result.get("type")?.asString == "admin-error") { Toast.makeText(this@AdminPanelActivity, result.get("message")?.asString ?: "Error", Toast.LENGTH_SHORT).show(); return@launch }
            val disk = result.get("disk")?.asString ?: "N/A"
            val filesCount = result.get("filesCount")?.asInt ?: 0
            val filesMB = result.get("filesMB")?.asString ?: "0 MB"
            val historyCount = result.get("historyCount")?.asInt ?: 0
            androidx.appcompat.app.AlertDialog.Builder(this@AdminPanelActivity)
                .setTitle("Server info")
                .setMessage("Disk:\n$disk\n\nFiles: $filesCount ($filesMB)\nHistory files: $historyCount")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun loadUsers() {
        binding.tvAdminStatus.text = "Loading…"
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
                binding.tvAdminStatus.text = "No response from server"
                return@launch
            }
            if (result.get("type")?.asString == "admin-error") {
                binding.tvAdminStatus.text = result.get("message")?.asString ?: "Access denied"
                return@launch
            }
            val arr = result.getAsJsonArray("users") ?: run {
                binding.tvAdminStatus.text = "Invalid response"
                return@launch
            }
            val list = arr.map { el ->
                val obj = el.asJsonObject
                AdminUser(
                    username  = obj.get("username")?.asString ?: "",
                    createdAt = obj.get("createdAt")?.asString ?: "",
                    isAdmin   = obj.get("admin")?.asBoolean ?: false
                )
            }
            userItems.clear()
            userItems.addAll(list)
            adapter.notifyDataSetChanged()
            binding.tvAdminStatus.visibility = View.GONE
        }
    }

    private fun showAddUserDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_user, null)
        val etUsername = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDialogUsername)
        val etPassword = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDialogPassword)
        AlertDialog.Builder(this)
            .setTitle("Add user")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val username = etUsername.text?.toString()?.trim() ?: ""
                val password = etPassword.text?.toString()?.trim() ?: ""
                if (username.isBlank() || password.isBlank()) {
                    Toast.makeText(this, "Username and password required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                sendAdminAction(mapOf("type" to "admin-add-user", "username" to username, "password" to password))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResetPasswordDialog(user: AdminUser) {
        val view = layoutInflater.inflate(R.layout.dialog_reset_password, null)
        val etPassword = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDialogNewPassword)
        AlertDialog.Builder(this)
            .setTitle("Reset password for ${user.username}")
            .setView(view)
            .setPositiveButton("Reset") { _, _ ->
                val newPass = etPassword.text?.toString()?.trim() ?: ""
                if (newPass.isBlank()) {
                    Toast.makeText(this, "Password required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                sendAdminAction(mapOf("type" to "admin-reset-password", "username" to user.username, "newPassword" to newPass))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRemoveDialog(user: AdminUser) {
        AlertDialog.Builder(this)
            .setTitle("Remove user")
            .setMessage("Remove ${user.username}? This cannot be undone.")
            .setPositiveButton("Remove") { _, _ ->
                sendAdminAction(mapOf("type" to "admin-remove-user", "username" to user.username))
            }
            .setNegativeButton("Cancel", null)
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
                    Toast.makeText(this@AdminPanelActivity, "No response from server", Toast.LENGTH_SHORT).show()
                result.get("type")?.asString == "admin-error" ->
                    Toast.makeText(this@AdminPanelActivity, result.get("message")?.asString ?: "Error", Toast.LENGTH_SHORT).show()
                else -> {
                    Toast.makeText(this@AdminPanelActivity, result.get("message")?.asString ?: "Done", Toast.LENGTH_SHORT).show()
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
}
