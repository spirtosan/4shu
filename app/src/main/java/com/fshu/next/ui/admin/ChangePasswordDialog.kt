package com.fshu.next.ui.admin

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonObject
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ChangePasswordDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_change_password, null)
        val etCurrent = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etCurrentPassword)
        val etNew = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etNewPassword)
        val etConfirm = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etConfirmPassword)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_change_password_title))
            .setView(view)
            .setPositiveButton(getString(R.string.btn_change), null)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val current = etCurrent.text?.toString()?.trim() ?: ""
                val new = etNew.text?.toString()?.trim() ?: ""
                val confirm = etConfirm.text?.toString()?.trim() ?: ""
                when {
                    current.isBlank() || new.isBlank() || confirm.isBlank() ->
                        Toast.makeText(requireContext(), getString(R.string.toast_all_fields_required), Toast.LENGTH_SHORT).show()
                    new != confirm ->
                        Toast.makeText(requireContext(), getString(R.string.toast_passwords_dont_match), Toast.LENGTH_SHORT).show()
                    else -> sendChangePassword(dialog, current, new)
                }
            }
        }
        return dialog
    }

    private fun sendChangePassword(dialog: AlertDialog, current: String, new: String) {
        val username = Prefs.getUsername(requireContext())
        val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        btn.isEnabled = false
        lifecycleScope.launch {
            val ch = Channel<JsonObject>(1)
            val job = launch {
                MessageBus.events.collect {
                    val t = it.get("type")?.asString
                    if (t == "change-password-ok" || t == "change-password-error") ch.trySend(it)
                }
            }
            WebSocketClient.send(mapOf(
                "type" to "change-password",
                "username" to username,
                "currentPassword" to current,
                "newPassword" to new
            ))
            val result = withTimeoutOrNull(5_000) { ch.receive() }
            job.cancel()
            btn.isEnabled = true
            when {
                result == null ->
                    Toast.makeText(requireContext(), getString(R.string.toast_no_response_from_server), Toast.LENGTH_SHORT).show()
                result.get("type")?.asString == "change-password-error" ->
                    Toast.makeText(requireContext(), result.get("message")?.asString ?: getString(R.string.toast_error), Toast.LENGTH_SHORT).show()
                else -> {
                    Prefs.setPassword(requireContext(), new)
                    Toast.makeText(requireContext(), getString(R.string.toast_password_changed), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }
    }
}
