package com.fshu.next.ui

import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import android.widget.ImageView
import coil.load
import com.fshu.next.util.Prefs

object BackgroundHelper {

    data class GradientOption(
        val name: String,
        val colors: IntArray,
        val orientation: GradientDrawable.Orientation
    )

    val gradients = listOf(
        GradientOption("WhatsApp", intArrayOf(0xFFDCF8C6.toInt(), 0xFF128C7E.toInt()), GradientDrawable.Orientation.TOP_BOTTOM),
        GradientOption("Ocean",    intArrayOf(0xFF4776E6.toInt(), 0xFF8E54E9.toInt()), GradientDrawable.Orientation.TL_BR),
        GradientOption("Sunset",   intArrayOf(0xFFFF8C00.toInt(), 0xFFFF1493.toInt()), GradientDrawable.Orientation.TOP_BOTTOM),
        GradientOption("Dark",     intArrayOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt()), GradientDrawable.Orientation.TOP_BOTTOM),
        GradientOption("Teal",     intArrayOf(0xFF11998E.toInt(), 0xFF38EF7D.toInt()), GradientDrawable.Orientation.TOP_BOTTOM),
        GradientOption("Soft",     intArrayOf(0xFFBDC3C7.toInt(), 0xFFFFFFFF.toInt()), GradientDrawable.Orientation.TOP_BOTTOM),
    )

    fun makeGradientDrawable(index: Int): GradientDrawable {
        val opt = gradients[index]
        return GradientDrawable(opt.orientation, opt.colors).apply {
            cornerRadius = 0f
        }
    }

    /**
     * Apply the stored background to [rootView] and [bgImageView].
     * [defaultColor] is used when bgIndex == BG_DEFAULT.
     */
    fun apply(
        rootView: View,
        bgImageView: ImageView,
        bgIndex: Int,
        bgUri: String?,
        defaultColor: Int
    ) {
        when {
            bgIndex == Prefs.BG_DEFAULT -> {
                bgImageView.visibility = View.GONE
                rootView.setBackgroundColor(defaultColor)
            }
            bgIndex == Prefs.BG_CUSTOM && bgUri != null -> {
                rootView.background = null
                bgImageView.visibility = View.VISIBLE
                bgImageView.load(Uri.parse(bgUri))
            }
            bgIndex in gradients.indices -> {
                bgImageView.visibility = View.GONE
                rootView.background = makeGradientDrawable(bgIndex)
            }
            else -> {
                bgImageView.visibility = View.GONE
                rootView.setBackgroundColor(defaultColor)
            }
        }
    }
}
