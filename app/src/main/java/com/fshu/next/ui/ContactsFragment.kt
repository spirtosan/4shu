package com.fshu.next.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.fshu.next.MainActivity
import com.fshu.next.R
import com.fshu.next.UserAdapter
import com.fshu.next.data.model.User
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.FragmentContactsBinding
import com.fshu.next.databinding.ItemContactBinding
import android.widget.PopupMenu
import com.fshu.next.ui.chat.ChatActivity
import com.fshu.next.ui.contacts.RequestsActivity
import com.fshu.next.ui.search.SearchActivity
import com.fshu.next.ui.search.UserProfileActivity
import com.fshu.next.util.MessageBus
import com.fshu.next.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!

    private val contactItems = mutableListOf<User>()
    private lateinit var contactsAdapter: ContactsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        contactsAdapter = ContactsAdapter(contactItems) { user ->
            startActivity(Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_PEER, user.username)
            })
        }
        binding.rvContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContacts.adapter = contactsAdapter

        binding.llRequestsSection.setOnClickListener {
            startActivity(Intent(requireContext(), RequestsActivity::class.java))
        }

        binding.fabAddContact.setOnClickListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }

        lifecycleScope.launch {
            MessageBus.events.collect { json ->
                if (json.get("type")?.asString == "contact-list") {
                    val count = json.getAsJsonArray("pendingReceived")?.size() ?: 0
                    updateRequestsRow(count)
                }
            }
        }

        refreshContacts()

        lifecycleScope.launch(Dispatchers.IO) {
            val me = Prefs.getUsername(requireContext())
            val db = com.fshu.next.data.local.AppDatabase.getInstance(requireContext())
            val count = db.contactDao().getPendingReceived(me).size
            withContext(Dispatchers.Main) { updateRequestsRow(count) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshContacts()
        if (WebSocketClient.isConnected) {
            WebSocketClient.send(mapOf("type" to "contact-list"))
        }
    }

    internal fun refreshContacts() {
        val main = requireActivity() as? MainActivity ?: return
        val contacts = main.getConversationsList()
            .filter { !it.isGroup && it.username != UserAdapter.DIVIDER_USERNAME }
            .sortedBy { it.displayName.lowercase() }
        contactItems.clear()
        contactItems.addAll(contacts)
        if (_binding != null) contactsAdapter.notifyDataSetChanged()
    }

    private fun updateRequestsRow(count: Int) {
        val b = _binding ?: return
        b.tvRequestsCount.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) b.tvRequestsCount.text = if (count > 9) "9+" else count.toString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class ContactsAdapter(
        private val items: List<User>,
        private val onClick: (User) -> Unit
    ) : RecyclerView.Adapter<ContactsAdapter.VH>() {

        inner class VH(val b: ItemContactBinding) : RecyclerView.ViewHolder(b.root) {
            init { b.root.setOnClickListener { onClick(items[bindingAdapterPosition]) } }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val user = items[position]
            val b = holder.b
            val ctx = b.root.context

            val nick = Prefs.getContactNickname(ctx, user.username)
            b.tvContactName.text = nick.ifEmpty { user.displayName }
            b.tvContactHandle.text = if (nick.isNotEmpty()) "@${user.username}" else ""
            b.tvMuteIcon.visibility = if (user.isMuted) View.VISIBLE else View.GONE

            val avatarFile = File(ctx.filesDir, "avatars/${user.username}.jpg")
            if (avatarFile.exists()) {
                b.ivContactAvatar.load(avatarFile) { transformations(CircleCropTransformation()) }
            } else {
                val letter = user.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                val colorRes = when (user.username.hashCode().let { if (it < 0) -it else it } % 10) {
                    0 -> R.color.avatar_1; 1 -> R.color.avatar_2; 2 -> R.color.avatar_3
                    3 -> R.color.avatar_4; 4 -> R.color.avatar_5; 5 -> R.color.avatar_6
                    6 -> R.color.avatar_7; 7 -> R.color.avatar_8; 8 -> R.color.avatar_9
                    else -> R.color.avatar_10
                }
                val sizePx = (44 * ctx.resources.displayMetrics.density).toInt()
                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(colorRes) }.also {
                    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, it)
                }
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = sizePx * 0.42f
                    textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
                }.also {
                    canvas.drawText(letter, sizePx / 2f, sizePx / 2f - (it.descent() + it.ascent()) / 2f, it)
                }
                b.ivContactAvatar.setImageBitmap(bmp)
            }

            b.btnMore.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menu.add(0, R.id.action_send_message, 0, getString(R.string.action_send_message))
                    menu.add(0, R.id.action_view_profile, 1, getString(R.string.action_view_profile))
                    menu.add(0, R.id.action_user_mute,   2,
                        if (user.isMuted) getString(R.string.action_unmute)
                        else getString(R.string.action_mute))
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            R.id.action_send_message -> {
                                startActivity(Intent(requireContext(), ChatActivity::class.java).apply {
                                    putExtra(ChatActivity.EXTRA_PEER, user.username)
                                })
                                true
                            }
                            R.id.action_view_profile -> {
                                startActivity(Intent(requireContext(), UserProfileActivity::class.java).apply {
                                    putExtra("username", user.username)
                                })
                                true
                            }
                            R.id.action_user_mute -> {
                                (requireActivity() as? com.fshu.next.MainActivity)?.showMuteDialog(user)
                                true
                            }
                            else -> false
                        }
                    }
                    show()
                }
            }
        }
    }
}
