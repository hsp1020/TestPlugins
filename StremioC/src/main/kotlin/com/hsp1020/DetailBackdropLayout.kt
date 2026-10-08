package com.hsp1020

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
    private val originalLogoScaleX = logo?.scaleX ?: 1f
    private val originalLogoScaleY = logo?.scaleY ?: 1f
    private val originalTitleAlpha = title?.alpha ?: 1f
    private val originalLogoAlpha = logo?.alpha ?: 1f
    private val originalPictureAlpha = picture?.alpha ?: 1f
    private val shadow = holder?.getChildAt(1) // Existing CloudStream bottom shadow.
    private val originalShadowVisibility = shadow?.visibility
    private val edges = mutableListOf<View>()
    private var titleOverlay: TextView? = null
    private var baseTitlePaint: TextPaint? = null
    private var lastFitText = ""
    private var lastFitWidth = -1
    private var lastFitHeight = -1
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
    private fun scaleLogo(doubleSize: Boolean) {
        val factor = if (doubleSize) 2f else 1f
        val x = originalLogoScaleX * factor
        val y = originalLogoScaleY * factor
        if (logo?.scaleX != x) logo?.scaleX = x
        if (logo?.scaleY != y) logo?.scaleY = y
    }
    // Fit the complete title into at most four lines and the actual backdrop image height.
    // No minimum readable size or ellipsis: extremely long titles may become very small.
    private fun fitTitle(overlay: TextView, text: String, width: Int, maxHeight: Int) {
        if (text.isEmpty() || (text == lastFitText && width == lastFitWidth && maxHeight == lastFitHeight)) return
        val paint = TextPaint(baseTitlePaint ?: overlay.paint)
        fun fits(size: Float): Boolean {
            paint.textSize = size
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (width - 2).coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(overlay.includeFontPadding)
                .setLineSpacing(overlay.lineSpacingExtra, overlay.lineSpacingMultiplier)
                .setBreakStrategy(overlay.breakStrategy)
                .setHyphenationFrequency(overlay.hyphenationFrequency)
                .build()
            return layout.lineCount <= 4 && layout.height <= maxHeight
        }
        var size = paint.textSize
        if (!fits(size)) {
            var low = 0f
            var high = size
            repeat(18) {
                val mid = (low + high) / 2f
                if (fits(mid)) low = mid else high = mid
            }
            size = low.coerceAtLeast(0.00001f) // Numeric safeguard only, not a readability floor.
        }
        if (overlay.textSize != size) overlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        lastFitText = text
        lastFitWidth = width
        lastFitHeight = maxHeight
    }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        runCatching { update() }; true
    }
    private fun splitMode() = phoneHero == null ||
        root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        root.resources.configuration.smallestScreenWidthDp >= 600
    private fun visibility(show: Boolean) {
        picture?.alpha = if (show) originalPictureAlpha else 0f
        logo?.alpha = if (show) originalLogoAlpha else 0f
    }
    private fun stage() {
        visibility(false)
        title?.alpha = 0f
        titleOverlay?.visibility = View.GONE
    }
    fun start() {
        val container = holder ?: return
        if (started || picture == null) return
        started = true
        if (splitMode()) stage() // Hide both before either asynchronous image load completes.
        val directions = listOf(
            GradientDrawable.Orientation.LEFT_RIGHT,
            GradientDrawable.Orientation.RIGHT_LEFT,
            GradientDrawable.Orientation.TOP_BOTTOM,
            GradientDrawable.Orientation.BOTTOM_TOP
        )
        directions.forEachIndexed { index, direction ->
            edges += View(root.context).also {
                it.background = GradientDrawable(direction, intArrayOf(Color.BLACK, Color.TRANSPARENT))
                container.addView(it, 1 + index, FrameLayout.LayoutParams(0, 0))
            }
        }
        titleOverlay = TextView(root.context).also { overlay ->
            title?.let {
                overlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.textSize * 2f)
                overlay.typeface = it.typeface
                overlay.setTextColor(it.currentTextColor)
            }
            baseTitlePaint = TextPaint(overlay.paint)
            lastFitWidth = -1
            overlay.maxLines = 4
            overlay.ellipsize = null
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
            visibility(true)
            logo?.translationX = originalLogoX
            logo?.translationY = originalLogoY
            scaleLogo(false)
            shadow?.visibility = originalShadowVisibility ?: View.VISIBLE
            return
        }
        val landscape = root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val tablet = root.resources.configuration.smallestScreenWidthDp >= 600
        val split = tv || landscape || tablet
        val bannerHeight = when {
            tv || tablet -> dp(400)
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
            edges.forEach { place(it, 0, 0, 0, 0) }
            shadow?.visibility = originalShadowVisibility ?: View.VISIBLE
            showTitle(false)
            visibility(true)
            logo?.translationX = originalLogoX
            logo?.translationY = originalLogoY
            scaleLogo(false)
            return
        }

        // logo visibility becomes VISIBLE before Coil has finished decoding its drawable.
        val hasLogo = logo?.visibility == View.VISIBLE && logo.drawable != null && logo.width > 0
        scaleLogo(hasLogo)
        val titleText = if (!hasLogo && title?.visibility == View.VISIBLE)
            title.text?.toString()?.trim()?.replace('\n', ' ')?.replace('\r', ' ').orEmpty() else ""
        val labelWidth = if (hasLogo) (logo!!.width * logo.scaleX).roundToInt() else if (titleText.isNotEmpty())
            min(((baseTitlePaint ?: titleOverlay!!.paint).measureText(titleText) + dp(24)).roundToInt(),
                (container.width * 0.36f).roundToInt()) else 0
        val gap = if (labelWidth > 0) dp(45) else 0
        val maxWidth = (container.width - labelWidth - gap - dp(24)).coerceAtLeast(1)
        val drawable = image.drawable
        val iw = drawable?.intrinsicWidth ?: 0
        val ih = drawable?.intrinsicHeight ?: 0
        val ready = iw > 0 && ih > 0 && (hasLogo || titleText.isNotEmpty())
        if (!ready) { stage(); return }
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
        // Keep the pair hidden until BOTH assets are ready AND the final measured layout is stable.
        val stable = image.width == imageWidth && image.height == imageHeight &&
            image.left == imageLeft && image.top == imageTop
        if (!stable) stage()
        place(image, imageWidth, imageHeight, imageLeft, imageTop)
        val featherX = min(dp(72), imageWidth / 5)
        val featherY = min(dp(72), imageHeight / 5)
        place(edges[0], featherX, imageHeight, imageLeft, imageTop)
        place(edges[1], featherX, imageHeight, imageLeft + imageWidth - featherX, imageTop)
        place(edges[2], imageWidth, featherY, imageLeft, imageTop)
        place(edges[3], imageWidth, featherY, imageLeft, imageTop + imageHeight - featherY)
        // Avoid double-darkening the bottom: the built-in shadow covered the whole parent.
        shadow?.visibility = View.INVISIBLE
        if (hasLogo) {
            // Account for the centered pivot when scaling the original ImageView.
            logo?.translationX = groupLeft - logo!!.left + (logo.scaleX - 1f) * logo.pivotX
            logo?.translationY = (bannerHeight - logo.height * logo.scaleY) / 2f -
                logo.top + (logo.scaleY - 1f) * logo.pivotY
        } else {
            logo?.translationX = originalLogoX
            logo?.translationY = originalLogoY
        }
        titleOverlay?.let { overlay ->
            if (overlay.text.toString() != titleText) overlay.text = titleText
            if (titleText.isNotEmpty()) fitTitle(overlay, titleText, labelWidth, imageHeight)
            val p = overlay.layoutParams as? FrameLayout.LayoutParams
            if (p != null && (p.width != labelWidth || p.gravity != (Gravity.START or Gravity.CENTER_VERTICAL) || p.marginStart != groupLeft)) {
                p.width = labelWidth
                p.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                p.marginStart = groupLeft
                overlay.layoutParams = p
            }
        }
        if (stable) {
            showTitle(titleText.isNotEmpty())
            visibility(true)
        } else stage()
    }
    fun stop() {
        if (!started) return
        started = false
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDraw)
        showTitle(false)
        titleOverlay?.let { holder?.removeView(it) }; titleOverlay = null
        baseTitlePaint = null
        edges.forEach { holder?.removeView(it) }; edges.clear()
        shadow?.visibility = originalShadowVisibility ?: View.VISIBLE
        visibility(true)
        logo?.translationX = originalLogoX
        logo?.translationY = originalLogoY
        scaleLogo(false)
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
