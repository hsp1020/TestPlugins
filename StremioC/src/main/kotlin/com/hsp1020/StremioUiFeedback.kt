package com.hsp1020

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.cardview.widget.CardView
import com.facebook.shimmer.Shimmer
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.chip.Chip
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute

/** The host's loading_list placeholders and shimmer animation, without extra poster requests. */
internal class StremioLoading(context: Context, rows: Int = 1) : FrameLayout(context) {
    private val shimmer = ShimmerFrameLayout(context).apply {
        setShimmer(Shimmer.AlphaHighlightBuilder()
            .setBaseAlpha(0.2f).setHighlightAlpha(0.3f).setDuration(2000L).build())
    }

    init {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        repeat(rows) {
            val placeholder = LayoutInflater.from(context).inflate(R.layout.loading_list, list, false)
            val fill = context.colorFromAttribute(R.attr.textColor)
            fun tint(view: View) {
                if (view is CardView) view.setCardBackgroundColor(fill)
                else if (view !is ViewGroup && view.background != null) view.setBackgroundColor(fill)
                if (view is ViewGroup) for (i in 0 until view.childCount) tint(view.getChildAt(i))
            }
            tint(placeholder)
            list.addView(placeholder)
        }
        shimmer.addView(list, LayoutParams(-1, -2))
        addView(shimmer, LayoutParams(-1, -1))
        isFocusable = false
        isClickable = false
        visibility = View.GONE
    }

    fun loading(active: Boolean) {
        visibility = if (active) View.VISIBLE else View.GONE
        if (active && isAttachedToWindow) shimmer.startShimmer()
        else if (!active) shimmer.stopShimmer()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == View.VISIBLE) shimmer.startShimmer()
    }

    override fun onDetachedFromWindow() {
        shimmer.stopShimmer()
        super.onDetachedFromWindow()
    }
}

internal fun themedPressedForeground(view: View): Drawable? {
    val out = TypedValue()
    return if (view.context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, out, true
        ) && out.resourceId != 0)
        AppCompatResources.getDrawable(view.context, out.resourceId)?.mutate()
    else null
}

/** Use the episode foreground ripple, not a second hardcoded Chip ripple. */
internal fun applyEpisodeChipFeedback(chip: Chip) {
    chip.rippleColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
    chip.foreground = themedPressedForeground(chip)
    chip.stateListAnimator = null
    chip.setTag(R.id.tv_no_focus_tag, true)
}
