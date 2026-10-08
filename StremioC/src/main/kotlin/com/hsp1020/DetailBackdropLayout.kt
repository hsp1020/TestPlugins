package com.hsp1020

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.*

/** StremioC detail backdrop only. Original CloudStream toolbar and player remain untouched. */
internal class DetailBackdropLayout(private val root: View) {
    private fun find(name: String): View? {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        return if (id == 0) null else root.findViewById(id)
    }
    private val phoneHero = find("result_top_holder")
    private val tv = phoneHero == null
    private val holder = find(if (tv) "background_poster_holder" else "result_poster_background_holder") as? FrameLayout
    private val picture = find(if (tv) "background_poster" else "result_poster_background") as? ImageView
    private val logo = find(if (tv) "background_poster_watermark_badge_holder" else "background_poster_watermark_badge") as? ImageView
    private val title = find("result_title") as? TextView
    private val trailer = if (tv) null else find("result_smallscreen_holder")
    private val tvColumn = if (tv)
        ((find("result_finish_loading") as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0) else null
    private val originalHeroHeight = phoneHero?.layoutParams?.height
    private val originalHolderHeight = holder?.layoutParams?.height
    private val originalPicture = (picture?.layoutParams as? FrameLayout.LayoutParams)?.let { FrameLayout.LayoutParams(it) }
    private val originalScale = picture?.scaleType
    private val originalMatrix = picture?.imageMatrix?.let(::Matrix)
    private val originalTvMargin = (tvColumn?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin
    private val originalLogoX = logo?.translationX ?: 0f
    private val originalLogoY = logo?.translationY ?: 0f
    private val originalTitleAlpha = title?.alpha ?: 1f
    private var fade: View? = null
    private var titleOverlay: TextView? = null
    private var started = false
    private fun dp(v: Int) = (v * root.resources.displayMetrics.density).roundToInt()
    private fun height(view: View?, value: Int) {
        val p = view?.layoutParams ?: return
        if (p.height != value) { p.height = value; view.layoutParams = p }
    }
    private fun place(view: View?, width: Int, height: Int, left: Int, top: Int) {
        val p = view?.layoutParams as? FrameLayout.LayoutParams ?: return
        if (p.width != width || p.height != height || p.gravity != (Gravity.TOP or Gravity.START) ||
            p.marginStart != left || p.topMargin != top) {
            p.width = width; p.height = height; p.gravity = Gravity.TOP or Gravity.START
            p.marginStart = left; p.topMargin = top; view.layoutParams = p
        }
    }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        runCatching { update() }; true
    }
    fun start() {
        val container = holder ?: return
        if (started || picture == null) return
        started = true
        fade = View(root.context).also {
            it.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.BLACK, Color.TRANSPARENT))
            container.addView(it, 1, FrameLayout.LayoutParams(0, 0))
        }
        titleOverlay = TextView(root.context).also { overlay ->
            title?.let {
                overlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.textSize)
                overlay.typeface = it.typeface
                overlay.setTextColor(it.currentTextColor)
            }
            overlay.maxLines = 2
            overlay.ellipsize = TextUtils.TruncateAt.END
            overlay.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            overlay.visibility = View.GONE
            container.addView(overlay, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.viewTreeObserver.addOnPreDrawListener(preDraw)
        update()
    }
    private fun showTitle(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        if (titleOverlay?.visibility != visibility) titleOverlay?.visibility = visibility
        val alpha = if (show) 0f else originalTitleAlpha
        if (title?.alpha != alpha) title?.alpha = alpha
    }
    private fun update() {
        val container = holder ?: return
        val image = picture ?: return
        if (container.width <= 0 || root.width <= 0) return
        // Leave the built-in trailer player in charge while it is active.
        if (!tv && trailer?.visibility == View.GONE) {
            showTitle(false)
            logo?.translationX = originalLogoX
            logo?.translationY = originalLogoY
            return
        }
        val landscape = root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val tablet = root.resources.configuration.smallestScreenWidthDp >= 600
        val split = tv || landscape
        val bannerHeight = when {
            tv || (landscape && tablet) -> dp(400)
            landscape -> min(dp(300), (root.height * 0.70f).roundToInt())
            else -> dp(260)
        }
        if (tv) {
            height(container, bannerHeight)
            (tvColumn?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { p ->
                if (p.topMargin != dp(350)) { p.topMargin = dp(350); tvColumn?.layoutParams = p }
            }
        } else height(phoneHero, bannerHeight)
        if (!split) {
            image.scaleType = originalScale ?: ImageView.ScaleType.CENTER_CROP
            originalPicture?.let { place(image, it.width, it.height, it.marginStart, it.topMargin) }
            place(fade, 0, 0, 0, 0)
            showTitle(false)
            logo?.translationX = originalLogoX
            logo?.translationY = originalLogoY
            return
        }

        val hasLogo = logo?.visibility == View.VISIBLE && logo.drawable != null && logo.width > 0
        val titleText = if (!hasLogo && title?.visibility == View.VISIBLE)
            title.text?.toString()?.trim().orEmpty() else ""
        val labelWidth = if (hasLogo) logo!!.width else if (titleText.isNotEmpty())
            min((title!!.paint.measureText(titleText) + dp(24)).roundToInt(),
                (container.width * 0.36f).roundToInt()) else 0
        val gap = if (labelWidth > 0) dp(24) else 0
        val maxWidth = (container.width - labelWidth - gap - dp(24)).coerceAtLeast(1)
        val drawable = image.drawable
        val iw = drawable?.intrinsicWidth ?: 0
        val ih = drawable?.intrinsicHeight ?: 0
        val imageWidth = if (iw > 0 && ih > 0)
            min(maxWidth, (bannerHeight.toDouble() * iw / ih).roundToInt().coerceAtLeast(1))
            else maxWidth
        val imageHeight = if (iw > 0 && ih > 0)
            min(bannerHeight, (imageWidth.toDouble() * ih / iw).roundToInt().coerceAtLeast(1))
            else bannerHeight
        val groupLeft = ((container.width - labelWidth - gap - imageWidth) / 2).coerceAtLeast(0)
        val imageLeft = groupLeft + labelWidth + gap
        val imageTop = (bannerHeight - imageHeight) / 2
        if (image.scaleType != ImageView.ScaleType.FIT_CENTER) image.scaleType = ImageView.ScaleType.FIT_CENTER
        place(image, imageWidth, imageHeight, imageLeft, imageTop)
        place(fade, min(dp(72), imageWidth / 5), imageHeight, imageLeft, imageTop)
        if (hasLogo) {
            logo?.translationX = groupLeft - logo!!.left.toFloat()
            logo?.translationY = bannerHeight / 2f - (logo.top + logo.height / 2f)
        } else {
            logo?.translationX = originalLogoX
            logo?.translationY = originalLogoY
        }
        titleOverlay?.let { overlay ->
            if (overlay.text.toString() != titleText) overlay.text = titleText
            val p = overlay.layoutParams as? FrameLayout.LayoutParams
            if (p != null && (p.width != labelWidth || p.gravity != (Gravity.START or Gravity.CENTER_VERTICAL) || p.marginStart != groupLeft)) {
                p.width = labelWidth
                p.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                p.marginStart = groupLeft
                overlay.layoutParams = p
            }
        }
        showTitle(titleText.isNotEmpty())
    }
    fun stop() {
        if (!started) return
        started = false
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDraw)
        showTitle(false)
        titleOverlay?.let { holder?.removeView(it) }; titleOverlay = null
        fade?.let { holder?.removeView(it) }; fade = null
        logo?.translationX = originalLogoX
        logo?.translationY = originalLogoY
        originalHeroHeight?.let { height(phoneHero, it) }
        originalHolderHeight?.let { height(holder, it) }
        originalPicture?.let { picture?.layoutParams = FrameLayout.LayoutParams(it) }
        originalScale?.let { picture?.scaleType = it }
        if (tv && originalScale == ImageView.ScaleType.MATRIX)
            picture?.drawable?.let { picture?.setImageDrawable(it) }
        else originalMatrix?.let { picture?.imageMatrix = it }
        (tvColumn?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { p ->
            originalTvMargin?.let { p.topMargin = it; tvColumn.layoutParams = p }
        }
    }
}
