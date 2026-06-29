package com.fshu.next.ui.gallery

import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.fshu.next.R
import com.fshu.next.data.model.Message
import com.fshu.next.databinding.ActivityMediaViewerBinding
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MediaViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_INDEX = "startIndex"

        @Volatile private var _images: List<Message> = emptyList()

        fun setImages(images: List<Message>) { _images = images }
    }

    private lateinit var binding: ActivityMediaViewerBinding
    private var toolbarVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

        binding.viewPager.adapter = PhotoPagerAdapter(_images) { toggleToolbar() }
        binding.viewPager.setCurrentItem(startIndex, false)

        hideSystemBars()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_media_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_save_to_device -> { saveCurrentImage(); true }
            R.id.action_share -> { shareCurrentImage(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleToolbar() {
        toolbarVisible = !toolbarVisible
        if (toolbarVisible) {
            binding.toolbar.visibility = View.VISIBLE
            showSystemBars()
        } else {
            binding.toolbar.visibility = View.GONE
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun currentMessage() = _images.getOrNull(binding.viewPager.currentItem)

    private fun saveCurrentImage() {
        val msg = currentMessage() ?: return
        val localUri = msg.localUri ?: return
        val uri = android.net.Uri.parse(localUri)
        val filename = msg.filename ?: "image_${System.currentTimeMillis()}.jpg"
        val mimeType = msg.mimeType ?: "image/jpeg"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    }
                    val destUri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: throw Exception("MediaStore insert failed")
                    contentResolver.openOutputStream(destUri)?.use { out ->
                        contentResolver.openInputStream(uri)?.use { inp -> inp.copyTo(out) }
                    }
                    contentResolver.update(
                        destUri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null, null
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    dir.mkdirs()
                    val file = File(dir, filename)
                    contentResolver.openInputStream(uri)?.use { inp ->
                        file.outputStream().use { out -> inp.copyTo(out) }
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MediaViewerActivity,
                        getString(R.string.toast_image_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MediaViewerActivity,
                        getString(R.string.toast_image_save_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun shareCurrentImage() {
        val msg = currentMessage() ?: return
        val localUri = msg.localUri ?: return
        val uri = android.net.Uri.parse(localUri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = msg.mimeType ?: "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, null))
    }

    // ── ViewPager2 adapter (PhotoView per page) ───────────────────────────────

    private class PhotoPagerAdapter(
        private val images: List<Message>,
        private val onTap: () -> Unit
    ) : RecyclerView.Adapter<PhotoPagerAdapter.PhotoVH>() {

        override fun getItemCount() = images.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoVH {
            val pv = PhotoView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
            }
            return PhotoVH(pv)
        }

        override fun onBindViewHolder(holder: PhotoVH, position: Int) {
            val msg = images[position]
            val uri = android.net.Uri.parse(msg.localUri ?: return)
            holder.pv.load(uri)
            holder.pv.setOnViewTapListener { _, _, _ -> onTap() }
        }

        class PhotoVH(val pv: PhotoView) : RecyclerView.ViewHolder(pv)
    }
}
