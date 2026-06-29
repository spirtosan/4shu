package com.fshu.next.ui.gallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.fshu.next.R
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.data.model.Message
import com.fshu.next.databinding.ActivityMediaGalleryBinding
import com.fshu.next.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MediaGalleryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PEER = "peer"
        const val EXTRA_GROUP_ID = "group_id"

        fun startForDm(context: Context, peer: String) {
            context.startActivity(Intent(context, MediaGalleryActivity::class.java).apply {
                putExtra(EXTRA_PEER, peer)
            })
        }

        fun startForGroup(context: Context, groupId: String) {
            context.startActivity(Intent(context, MediaGalleryActivity::class.java).apply {
                putExtra(EXTRA_GROUP_ID, groupId)
            })
        }
    }

    private lateinit var binding: ActivityMediaGalleryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.media_gallery_title)

        val peer = intent.getStringExtra(EXTRA_PEER)
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)
        val me = Prefs.getUsername(this)

        val spanCount = 3
        val gridLM = GridLayoutManager(this, spanCount)
        binding.rvGallery.layoutManager = gridLM

        lifecycleScope.launch {
            val messages = withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(this@MediaGalleryActivity)
                when {
                    groupId != null -> db.messageDao().getGroupImages(groupId)
                    peer != null -> db.messageDao().getDmImages(me, peer)
                    else -> emptyList()
                }
            }

            if (messages.isEmpty()) {
                binding.rvGallery.visibility = View.GONE
                binding.tvEmpty.visibility = View.VISIBLE
                return@launch
            }

            val items = buildGalleryItems(messages)
            val screenWidth = resources.displayMetrics.widthPixels
            val imageSize = screenWidth / spanCount

            val adapter = GalleryAdapter(items, imageSize)

            gridLM.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if (adapter.getItemViewType(position) == GalleryAdapter.TYPE_HEADER) spanCount else 1
            }

            binding.rvGallery.adapter = adapter
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun buildGalleryItems(messages: List<Message>): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        var currentLabel = ""
        val now = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val monthYearFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

        for (msg in messages) {
            val msgCal = Calendar.getInstance().apply { timeInMillis = msg.timestamp }
            val label = when {
                sameDay(msgCal, now) -> getString(R.string.date_today)
                sameDay(msgCal, yesterday) -> getString(R.string.date_yesterday)
                else -> monthYearFmt.format(Date(msg.timestamp))
            }
            if (label != currentLabel) {
                result.add(GalleryItem.Header(label))
                currentLabel = label
            }
            result.add(GalleryItem.Image(msg))
        }
        return result
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    // ── Sealed item type ──────────────────────────────────────────────────────

    sealed class GalleryItem {
        data class Header(val label: String) : GalleryItem()
        data class Image(val message: Message) : GalleryItem()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class GalleryAdapter(
        private val items: List<GalleryItem>,
        private val imageSize: Int
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            const val TYPE_HEADER = 0
            const val TYPE_IMAGE = 1
        }

        val imageMessages: List<Message> =
            items.filterIsInstance<GalleryItem.Image>().map { it.message }

        override fun getItemViewType(position: Int) = when (items[position]) {
            is GalleryItem.Header -> TYPE_HEADER
            is GalleryItem.Image -> TYPE_IMAGE
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> {
                    val tv = inf.inflate(R.layout.item_media_header, parent, false) as TextView
                    HeaderVH(tv)
                }
                else -> {
                    val v = inf.inflate(R.layout.item_media_grid, parent, false)
                    v.layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, imageSize
                    )
                    ImageVH(v, v.findViewById(R.id.ivThumb))
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is GalleryItem.Header -> (holder as HeaderVH).tv.text = item.label
                is GalleryItem.Image -> {
                    val vh = holder as ImageVH
                    val uri = android.net.Uri.parse(item.message.localUri)
                    vh.iv.load(uri) { crossfade(true) }
                    val imgIdx = imageMessages.indexOf(item.message)
                    vh.iv.setOnClickListener {
                        MediaViewerActivity.setImages(imageMessages)
                        startActivity(
                            Intent(this@MediaGalleryActivity, MediaViewerActivity::class.java)
                                .putExtra(MediaViewerActivity.EXTRA_START_INDEX, imgIdx)
                        )
                    }
                }
            }
        }

        inner class HeaderVH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        inner class ImageVH(view: View, val iv: ImageView) : RecyclerView.ViewHolder(view)
    }
}
