package com.fshu.next.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.fshu.next.R
import com.fshu.next.databinding.BottomSheetAttachmentBinding

class AttachmentBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onGalleryClick()
        fun onCameraClick()
        fun onFileClick()
        fun onLocationClick()
        fun onContactClick()
    }

    var listener: Listener? = null
    private var _binding: BottomSheetAttachmentBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAttachmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val typedValue = android.util.TypedValue()
        requireActivity().theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurface, typedValue, true)
        val bgColor = typedValue.data
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(bgColor)
            cornerRadii = floatArrayOf(
                28f.dpToPx(), 28f.dpToPx(),
                28f.dpToPx(), 28f.dpToPx(),
                0f, 0f, 0f, 0f
            )
        }
        binding.root.background = bg
        binding.attGallery.setOnClickListener { listener?.onGalleryClick(); dismiss() }
        binding.attCamera.setOnClickListener { listener?.onCameraClick(); dismiss() }
        binding.attFile.setOnClickListener { listener?.onFileClick(); dismiss() }
        binding.attLocation.setOnClickListener { listener?.onLocationClick(); dismiss() }
        binding.attContact.setOnClickListener { listener?.onContactClick(); dismiss() }
    }

    override fun getTheme() = R.style.BottomSheetStyle

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AttachmentBottomSheet"
    }
}

private fun Float.dpToPx(): Float =
    this * android.content.res.Resources.getSystem().displayMetrics.density
