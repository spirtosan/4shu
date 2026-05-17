package com.fshu.next.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.fshu.next.MainActivity
import com.fshu.next.R
import com.fshu.next.UserAdapter
import com.fshu.next.data.model.User
import com.fshu.next.databinding.FragmentChatsBinding
import com.fshu.next.databinding.ItemUserBinding
import com.fshu.next.ui.chat.ChatActivity
import com.fshu.next.ui.settings.MyProfileActivity
import com.fshu.next.util.Prefs
import java.io.File

class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!

    private lateinit var favoritesAdapter: FavoritesAdapter
    private val searchResults = mutableListOf<User>()
    private lateinit var searchAdapter: SearchAdapter

    private val favoritesObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = refreshFavorites()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = refreshFavorites()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = refreshFavorites()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = refreshFavorites()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = refreshFavorites()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = refreshFavorites()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainActivity = requireActivity() as MainActivity

        // Conversations
        binding.rvConversations.layoutManager = LinearLayoutManager(requireContext())
        val convsAdapter = mainActivity.getConversationsAdapter()
        binding.rvConversations.adapter = convsAdapter
        convsAdapter.registerAdapterDataObserver(favoritesObserver)
        convsAdapter.notifyDataSetChanged()
        setupDragAndDrop(mainActivity)

        // Favorites
        favoritesAdapter = FavoritesAdapter { user ->
            startActivity(Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_PEER, user.username)
            })
        }
        binding.rvFavorites.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvFavorites.adapter = favoritesAdapter
        refreshFavorites()

        // Search
        searchAdapter = SearchAdapter(searchResults) { user ->
            val intent = if (user.isGroup) {
                Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra(ChatActivity.EXTRA_GROUP_ID, user.groupId)
                }
            } else {
                Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra(ChatActivity.EXTRA_PEER, user.username)
                }
            }
            startActivity(intent)
        }
        binding.rvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSearchResults.adapter = searchAdapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                if (q.isEmpty()) showMainContent() else filterConversations(q, mainActivity)
            }
        })

        loadOwnAvatar()
        binding.ownAvatar.setOnClickListener {
            startActivity(Intent(requireContext(), MyProfileActivity::class.java))
        }
    }

    private fun setupDragAndDrop(mainActivity: MainActivity) {
        val cb = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                val users = mainActivity.getConversationsList()
                val fromUser = users.getOrNull(from) ?: return false
                val toUser = users.getOrNull(to) ?: return false
                if (fromUser.isGroup || toUser.isGroup) return false
                if (fromUser.username == UserAdapter.DIVIDER_USERNAME || toUser.username == UserAdapter.DIVIDER_USERNAME) return false
                if (fromUser.isFavorite != toUser.isFavorite) return false
                mainActivity.getConversationsAdapter().moveItem(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                mainActivity.saveUserOrderFromFragment()
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(binding.rvConversations)
    }

    fun refreshFavorites() {
        val b = _binding ?: return
        val main = requireActivity() as? MainActivity ?: return
        val favs = main.getConversationsList().filter {
            it.isFavorite && !it.isGroup && it.username != UserAdapter.DIVIDER_USERNAME
        }
        b.llFavoritesSection.visibility = if (favs.isEmpty()) View.GONE else View.VISIBLE
        if (favs.isNotEmpty()) favoritesAdapter.submitList(favs)
    }

    private fun filterConversations(query: String, mainActivity: MainActivity) {
        val filtered = mainActivity.getConversationsList().filter { u ->
            u.username != UserAdapter.DIVIDER_USERNAME &&
            (u.displayName.contains(query, ignoreCase = true) || u.username.contains(query, ignoreCase = true))
        }
        searchResults.clear()
        searchResults.addAll(filtered)
        searchAdapter.notifyDataSetChanged()
        binding.rvSearchResults.visibility = View.VISIBLE
        binding.llMainContent.visibility = View.GONE
    }

    private fun showMainContent() {
        binding.rvSearchResults.visibility = View.GONE
        binding.llMainContent.visibility = View.VISIBLE
    }

    fun loadOwnAvatar() {
        val b = _binding ?: return
        try {
            val me = Prefs.getUsername(requireContext())
            if (me.isEmpty()) return
            val avatarFile = File(requireContext().filesDir, "avatars/$me.jpg")
            val sizePx = (36 * resources.displayMetrics.density).toInt()
            if (avatarFile.exists()) {
                b.ownAvatar.load(avatarFile) { transformations(CircleCropTransformation()) }
            } else {
                val letter = me.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                val colorRes = when (me.hashCode().let { if (it < 0) -it else it } % 10) {
                    0 -> R.color.avatar_1; 1 -> R.color.avatar_2; 2 -> R.color.avatar_3
                    3 -> R.color.avatar_4; 4 -> R.color.avatar_5; 5 -> R.color.avatar_6
                    6 -> R.color.avatar_7; 7 -> R.color.avatar_8; 8 -> R.color.avatar_9
                    else -> R.color.avatar_10
                }
                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = requireContext().getColor(colorRes) }.also {
                    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, it)
                }
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = sizePx * 0.42f
                    textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
                }.also {
                    canvas.drawText(letter, sizePx / 2f, sizePx / 2f - (it.descent() + it.ascent()) / 2f, it)
                }
                b.ownAvatar.setImageBitmap(bmp)
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        refreshFavorites()
        loadOwnAvatar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? MainActivity)?.getConversationsAdapter()?.unregisterAdapterDataObserver(favoritesObserver)
        _binding = null
    }

    private inner class SearchAdapter(
        private val items: List<User>,
        private val onClick: (User) -> Unit
    ) : RecyclerView.Adapter<SearchAdapter.VH>() {

        inner class VH(val b: ItemUserBinding) : RecyclerView.ViewHolder(b.root) {
            init { b.root.setOnClickListener { onClick(items[bindingAdapterPosition]) } }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val user = items[position]
            val b = holder.b
            b.tvUsername.text = user.username
            val localNick = Prefs.getContactNickname(requireContext(), user.username)
            if (localNick.isNotEmpty() && localNick != user.username) {
                b.tvHandle.visibility = View.VISIBLE
                b.tvHandle.text = localNick
            } else {
                b.tvHandle.visibility = View.GONE
            }
            b.tvLastMessage.text = user.lastMessage ?: ""
            b.tvTime.text = ""
            b.tvFavStar.visibility = View.GONE
            b.tvMuteIcon.visibility = View.GONE
            b.btnMore.visibility = View.GONE
            b.viewOnlineDot.visibility = if (user.online) View.VISIBLE else View.GONE
            val avatarFile = if (user.isGroup && user.groupId != null) {
                File(requireContext().filesDir, "avatars/group_${user.groupId}.jpg").takeIf { it.exists() }
            } else {
                File(requireContext().filesDir, "avatars/${user.username}.jpg").takeIf { it.exists() }
            }
            if (avatarFile != null) {
                b.ivAvatar.load(avatarFile) { transformations(CircleCropTransformation()) }
            } else {
                b.ivAvatar.setImageBitmap(null)
            }
        }
    }
}
