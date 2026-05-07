package com.fshu.next.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.fshu.next.R
import com.fshu.next.databinding.ItemBgOptionBinding
import com.fshu.next.util.Prefs

class BackgroundBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val SCREEN_CHAT = "chat"
        const val SCREEN_MAIN = "main"
        const val RESULT_KEY  = "bg_changed"
        private const val ARG_SCREEN = "screen"
        private const val ARG_PEER   = "peer"

        fun newInstance(screen: String, peer: String? = null) = BackgroundBottomSheet().apply {
            arguments = bundleOf(ARG_SCREEN to screen, ARG_PEER to peer)
        }
    }

    private val screen get() = arguments?.getString(ARG_SCREEN) ?: SCREEN_CHAT
    private val peer   get() = arguments?.getString(ARG_PEER)

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleCustomUri(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_background_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val currentIndex = if (screen == SCREEN_CHAT) Prefs.getChatBgIndex(requireContext(), peer ?: "")
                           else Prefs.getMainBgIndex(requireContext())

        val adapter = BgOptionAdapter(currentIndex) { index ->
            if (index == Prefs.BG_CUSTOM) pickImage.launch("image/*")
            else saveAndNotify(index, null)
        }

        view.findViewById<RecyclerView>(R.id.rv_bg_options).apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
        }

        view.findViewById<MaterialButton>(R.id.btn_reset_bg).setOnClickListener {
            saveAndNotify(Prefs.BG_DEFAULT, null)
        }
    }

    private fun handleCustomUri(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Not all URI providers support persistable permissions; proceed anyway.
        }
        saveAndNotify(Prefs.BG_CUSTOM, uri.toString())
    }

    private fun saveAndNotify(index: Int, uri: String?) {
        val ctx = requireContext()
        if (screen == SCREEN_CHAT) {
            Prefs.setChatBgIndex(ctx, peer ?: "", index)
            Prefs.setChatBgUri(ctx, peer ?: "", uri)
        } else {
            Prefs.setMainBgIndex(ctx, index)
            Prefs.setMainBgUri(ctx, uri)
        }
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle.EMPTY)
        dismiss()
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    private inner class BgOptionAdapter(
        private var selectedIndex: Int,
        private val onSelect: (Int) -> Unit
    ) : RecyclerView.Adapter<BgOptionAdapter.VH>() {

        // Items: indices 0-5 are gradients, index 6 is Custom
        private val itemCount_ = BackgroundHelper.gradients.size + 1

        inner class VH(val binding: ItemBgOptionBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemBgOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = itemCount_

        override fun onBindViewHolder(holder: VH, position: Int) {
            val isCustom = position == BackgroundHelper.gradients.size
            val isSelected = position == selectedIndex ||
                    (isCustom && selectedIndex == Prefs.BG_CUSTOM)

            if (isCustom) {
                // Gray background with a "+" label
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f
                    setColor(0xFFE0E0E0.toInt())
                }
                holder.binding.vPreview.background = bg
                holder.binding.tvCustomPlus.visibility = View.VISIBLE
                holder.binding.tvName.text = getString(R.string.bg_custom)
            } else {
                val opt = BackgroundHelper.gradients[position]
                val bg = GradientDrawable(opt.orientation, opt.colors).apply { cornerRadius = 16f }
                holder.binding.vPreview.background = bg
                holder.binding.tvCustomPlus.visibility = View.GONE
                holder.binding.tvName.text = opt.name
            }

            val showCheck = isSelected
            holder.binding.vSelectedOverlay.visibility = if (showCheck) View.VISIBLE else View.GONE
            holder.binding.tvCheck.visibility = if (showCheck) View.VISIBLE else View.GONE

            holder.itemView.setOnClickListener {
                val prev = selectedIndex
                selectedIndex = if (isCustom) Prefs.BG_CUSTOM else position
                // Only refresh prev if it was a real selection (not BG_DEFAULT = -1)
                if (prev >= 0 && prev < itemCount_) notifyItemChanged(prev)
                notifyItemChanged(position)
                onSelect(if (isCustom) Prefs.BG_CUSTOM else position)
            }
        }
    }
}
