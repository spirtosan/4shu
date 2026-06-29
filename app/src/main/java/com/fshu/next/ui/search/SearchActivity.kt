package com.fshu.next.ui.search

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivitySearchBinding
import com.fshu.next.util.MessageBus
import com.google.gson.JsonObject
import kotlinx.coroutines.launch

data class SearchResult(
    val username: String,
    val nickname: String?,
    val hasAvatar: Boolean,
    val contactStatus: String?,
    val isBlocked: Boolean
)

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val searchAdapter = SearchAdapter()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var currentQuery = ""
    private var currentOffset = 0

    private inner class SearchAdapter : RecyclerView.Adapter<SearchAdapter.VH>() {
        val results = mutableListOf<SearchResult>()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
            val tvUsername: TextView = view.findViewById(R.id.tvUsername)
            val tvNickname: TextView = view.findViewById(R.id.tvNickname)
            val tvContactStatus: TextView = view.findViewById(R.id.tvContactStatus)
            val btnAdd: Button = view.findViewById(R.id.btnAdd)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_result, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = results[position]
            holder.tvUsername.text = item.username

            if (item.nickname != null) {
                holder.tvNickname.text = item.nickname
                holder.tvNickname.visibility = View.VISIBLE
            } else {
                holder.tvNickname.visibility = View.GONE
            }

            when (item.contactStatus) {
                "accepted" -> {
                    holder.tvContactStatus.text = "Contact"
                    holder.tvContactStatus.setTextColor(Color.parseColor("#4CAF50"))
                    holder.tvContactStatus.visibility = View.VISIBLE
                    holder.btnAdd.visibility = View.GONE
                }
                "pending" -> {
                    holder.tvContactStatus.text = "Pending"
                    holder.tvContactStatus.setTextColor(Color.parseColor("#E8711A"))
                    holder.tvContactStatus.visibility = View.VISIBLE
                    holder.btnAdd.visibility = View.GONE
                }
                else -> {
                    holder.tvContactStatus.visibility = View.GONE
                    holder.btnAdd.visibility = if (item.isBlocked) View.GONE else View.VISIBLE
                }
            }

            drawLetterAvatar(holder.ivAvatar, item.username, 40)

            val openProfile = {
                startActivity(Intent(this@SearchActivity, UserProfileActivity::class.java)
                    .putExtra("username", item.username))
            }
            holder.itemView.setOnClickListener { openProfile() }
            holder.btnAdd.setOnClickListener { openProfile() }
        }

        override fun getItemCount() = results.size
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Find People"

        binding.rvSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvSearchResults.adapter = searchAdapter

        lifecycleScope.launch {
            MessageBus.events.collect { handleMessage(it) }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                if (text.length < 3) {
                    binding.tvHint.visibility = View.VISIBLE
                    binding.tvEmpty.visibility = View.GONE
                    binding.btnLoadMore.visibility = View.GONE
                    searchAdapter.results.clear()
                    searchAdapter.notifyDataSetChanged()
                    return
                }
                binding.tvHint.visibility = View.GONE
                searchRunnable = Runnable {
                    currentQuery = text
                    currentOffset = 0
                    searchAdapter.results.clear()
                    searchAdapter.notifyDataSetChanged()
                    performSearch()
                }
                searchHandler.postDelayed(searchRunnable!!, 500)
            }
        })

        binding.btnLoadMore.setOnClickListener {
            currentOffset += 10
            performSearch(append = true)
        }

        // T4: focus search field and show keyboard immediately on open (Motorola OEM reliability)
        binding.etSearch.post {
            if (binding.etSearch.text.isNullOrEmpty()) {
                binding.etSearch.requestFocus()
                WindowInsetsControllerCompat(window, binding.etSearch)
                    .show(WindowInsetsCompat.Type.ime())
                getSystemService(InputMethodManager::class.java)
                    .showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun performSearch(append: Boolean = false) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        WebSocketClient.send(mapOf(
            "type" to "user-search",
            "query" to currentQuery,
            "offset" to currentOffset
        ))
        if (!append) {
            searchAdapter.results.clear()
            searchAdapter.notifyDataSetChanged()
        }
    }

    private fun handleMessage(json: JsonObject) {
        when (json.get("type")?.asString) {
            "user-search-result" -> runOnUiThread {
                binding.progressBar.visibility = View.GONE
                val arr = json.getAsJsonArray("users") ?: return@runOnUiThread
                val newResults = arr.mapNotNull { el ->
                    val obj = el.asJsonObject
                    val username = obj.get("username")?.asString ?: return@mapNotNull null
                    SearchResult(
                        username = username,
                        nickname = obj.get("nickname")?.takeIf { !it.isJsonNull }?.asString,
                        hasAvatar = obj.get("hasAvatar")?.asBoolean ?: false,
                        contactStatus = obj.get("contactStatus")?.takeIf { !it.isJsonNull }?.asString,
                        isBlocked = obj.get("isBlocked")?.asBoolean ?: false
                    )
                }
                searchAdapter.results.addAll(newResults)
                searchAdapter.notifyDataSetChanged()
                val hasMore = json.get("hasMore")?.asBoolean ?: false
                binding.btnLoadMore.visibility = if (hasMore) View.VISIBLE else View.GONE
                binding.tvEmpty.visibility = if (searchAdapter.results.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun drawLetterAvatar(iv: ImageView, username: String, sizeDp: Int) {
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
        val letter = username.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val colorRes = when (username.hashCode().let { if (it < 0) -it else it } % 10) {
            0 -> R.color.avatar_1
            1 -> R.color.avatar_2
            2 -> R.color.avatar_3
            3 -> R.color.avatar_4
            4 -> R.color.avatar_5
            5 -> R.color.avatar_6
            6 -> R.color.avatar_7
            7 -> R.color.avatar_8
            8 -> R.color.avatar_9
            else -> R.color.avatar_10
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(this@SearchActivity, colorRes) }
            .also { canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, it) }
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.42f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }.also {
            val yPos = sizePx / 2f - (it.descent() + it.ascent()) / 2f
            canvas.drawText(letter, sizePx / 2f, yPos, it)
        }
        iv.setImageBitmap(bmp)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
    }
}
