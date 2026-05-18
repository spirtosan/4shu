package com.fshu.next.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.fshu.next.R
import com.fshu.next.databinding.BottomSheetEmergencyBinding

class EmergencyBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onPriorityCallClick()
        fun onEmergencyCallClick()
        fun onSosMessageClick()
        fun onRequestLocationClick()
    }

    var listener: Listener? = null
    private var _binding: BottomSheetEmergencyBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetEmergencyBinding.inflate(inflater, container, false)
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
        binding.emergPriorityCall.setOnClickListener {
            listener?.onPriorityCallClick(); dismiss()
        }
        binding.emergEmergencyCall.setOnClickListener {
            listener?.onEmergencyCallClick(); dismiss()
        }
        binding.emergSosMessage.setOnClickListener {
            listener?.onSosMessageClick(); dismiss()
        }
        binding.emergRequestLocation.setOnClickListener {
            listener?.onRequestLocationClick(); dismiss()
        }
    }

    override fun getTheme() = R.style.BottomSheetStyle

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "EmergencyBottomSheet"
    }
}

private fun Float.dpToPx(): Float =
    this * android.content.res.Resources.getSystem().displayMetrics.density
