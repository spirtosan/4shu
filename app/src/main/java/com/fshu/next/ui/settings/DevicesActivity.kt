package com.fshu.next.ui.settings

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.JsonObject
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityDevicesBinding
import com.fshu.next.databinding.ItemDeviceBinding
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DevicesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDevicesBinding

    data class DeviceItem(val deviceId: String, val deviceName: String?, val lastSeen: Long, val isCurrent: Boolean)

    private val deviceItems = mutableListOf<DeviceItem>()
    private lateinit var deviceAdapter: DeviceAdapter

    inner class DeviceAdapter(
        private val items: List<DeviceItem>,
        private val onRemove: (DeviceItem) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {
        inner class VH(val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            holder.b.tvDeviceItemName.text = if (item.isCurrent)
                "⭐ ${item.deviceName ?: item.deviceId.take(8)}"
            else
                item.deviceName ?: item.deviceId.take(8)
            holder.b.tvDeviceItemLastSeen.text = if (item.lastSeen > 0)
                sdf.format(Date(item.lastSeen))
            else
                "Never seen"
            holder.b.btnRemoveDevice.visibility = if (item.isCurrent) View.GONE else View.VISIBLE
            holder.b.btnRemoveDevice.setOnClickListener { onRemove(item) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = getString(R.string.label_devices)
            setDisplayHomeAsUpEnabled(true)
        }

        deviceAdapter = DeviceAdapter(deviceItems) { item ->
            lifecycleScope.launch {
                val ch = Channel<JsonObject>(1)
                val job = launch {
                    MessageBus.events.collect {
                        if (it.get("type")?.asString == "device-list") ch.trySend(it)
                    }
                }
                WebSocketClient.send(mapOf("type" to "device-remove", "deviceId" to item.deviceId))
                val result = withTimeoutOrNull(5_000) { ch.receive() }
                job.cancel()
                result?.let { updateDeviceList(it) }
            }
        }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@DevicesActivity)
            adapter = deviceAdapter
        }

        binding.btnRenameDevice.setOnClickListener {
            val currentName = Prefs.getDeviceName(this).ifEmpty { Build.MODEL }
            val et = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setText(currentName)
                setSelection(text.length)
            }
            val pad = (16 * resources.displayMetrics.density).toInt()
            val wrap = FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(et) }
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_device_name_title))
                .setView(wrap)
                .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                    val newName = et.text.toString().trim().ifEmpty { Build.MODEL }
                    Prefs.setDeviceName(this, newName)
                    WebSocketClient.deviceName = newName
                    lifecycleScope.launch {
                        val ch = Channel<JsonObject>(1)
                        val job = launch {
                            MessageBus.events.collect {
                                if (it.get("type")?.asString == "device-list") ch.trySend(it)
                            }
                        }
                        WebSocketClient.send(mapOf("type" to "device-rename", "deviceName" to newName))
                        val result = withTimeoutOrNull(5_000) { ch.receive() }
                        job.cancel()
                        result?.let { updateDeviceList(it) }
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
    }

    private fun refreshDevices() {
        if (!WebSocketClient.isConnected) return
        lifecycleScope.launch {
            val ch = Channel<JsonObject>(1)
            val job = launch {
                MessageBus.events.collect {
                    if (it.get("type")?.asString == "device-list") ch.trySend(it)
                }
            }
            WebSocketClient.send(mapOf("type" to "device-list"))
            val result = withTimeoutOrNull(5_000) { ch.receive() }
            job.cancel()
            result?.let { updateDeviceList(it) }
        }
    }

    private fun updateDeviceList(json: JsonObject) {
        val currentDeviceId = json.get("currentDeviceId")?.asString ?: ""
        val arr = json.getAsJsonArray("devices") ?: return
        deviceItems.clear()
        for (el in arr) {
            val obj = el.asJsonObject
            val did = obj.get("device_id")?.asString ?: continue
            deviceItems.add(DeviceItem(
                deviceId   = did,
                deviceName = obj.get("device_name")?.takeIf { !it.isJsonNull }?.asString,
                lastSeen   = obj.get("last_seen")?.asLong ?: 0L,
                isCurrent  = did == currentDeviceId
            ))
        }
        deviceItems.sortWith(compareByDescending<DeviceItem> { it.isCurrent }.thenByDescending { it.lastSeen })
        deviceAdapter.notifyDataSetChanged()
        val current = deviceItems.find { it.isCurrent }
        val name = current?.deviceName?.takeIf { it.isNotEmpty() }
            ?: Prefs.getDeviceName(this).ifEmpty { Build.MODEL }
        binding.tvCurrentDevice.text = "⭐ $name"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
