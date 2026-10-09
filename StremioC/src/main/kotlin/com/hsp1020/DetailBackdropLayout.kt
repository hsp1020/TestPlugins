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
import androidx.preference.PreferenceManager
import kotlin.math.*

/** StremioC detail backdrop only. Original CloudStream toolbar and player remain untouched. */
internal class DetailBackdropLayout(private val root: View, private val detailUrl: String? = null) {
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
    private var portraitTitleOnly = false
    private var forcedPortraitTitle = false
    private var pendingSplitFrames = 0
    private var splitFallback = false
    private var nativeRecoveryFrames = 0
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
    private fun scaleLogo(doubleSize: Boolean, factor: Float = if (doubleSize) 2f else 1f) {
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
        runCatching { update() }.onFailure { recoverOriginalLayout() }; true
    }
    private fun splitMode() = phoneHero == null ||
        root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        root.resources.configuration.smallestScreenWidthDp >= 600
    private fun visibility(show: Boolean) {
        picture?.alpha = if (show) originalPictureAlpha else 0f
        logo?.alpha = if (show) originalLogoAlpha else 0f
    }
    private fun stage() {
        // Neither async drawable may paint at CloudStream's original geometry.
        picture?.alpha = 0f
        logo?.alpha = 0f
        title?.alpha = 0f
        titleOverlay?.visibility = View.GONE
    }
    private fun recoverOriginalLayout() {
        if (splitFallback || portraitTitleOnly) return
        if (!splitMode()) { visibility(true); return }
        splitFallback = true // Do not re-enter the split layout and cause a visible jump.
        nativeRecoveryFrames = 0
        edges.forEach { place(it, 0, 0, 0, 0) }
        shadow?.visibility = originalShadowVisibility ?: View.VISIBLE
        originalHeroHeight?.let { height(phoneHero, it) }
        originalHolderHeight?.let { height(holder, it) }
        originalPicture?.let { picture?.layoutParams = FrameLayout.LayoutParams(it) }
        originalScale?.let { picture?.scaleType = it }
        if (tv && originalScale == ImageView.ScaleType.MATRIX)
            picture?.drawable?.let { picture?.setImageDrawable(it) }
        else originalMatrix?.let { picture?.imageMatrix = it }
        logo?.translationX = originalLogoX
        logo?.translationY = originalLogoY
        scaleLogo(false)
        (tvColumn?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { p ->
            originalTvMargin?.let { p.topMargin = it; tvColumn.layoutParams = p }
        }
        // The restored native layoutParams must pass through measure/layout before
        // we reveal them; otherwise fallback itself can produce a visible jump.
        stage()
    }
    private fun revealRecoveredLayout() {
        val old = originalPicture
        val image = picture
        val placed = image != null && (old == null ||
            ((old.width <= 0 || image.width == old.width) &&
             (old.height <= 0 || image.height == old.height) &&
             image.left == old.marginStart && image.top == old.topMargin))
        if (placed || ++nativeRecoveryFrames >= 8) {
            showTitle(false)
            visibility(true)
        }
    }
    private fun portraitMode() = phoneHero != null && !splitMode()
    private fun updatePortraitTitle() {
        if (!portraitMode()) return
        if (detailUrl?.let { StremioC.fallbackLogoTitles[it] } != true) return
        if (logo?.visibility == View.VISIBLE && logo.drawable != null &&
            !title?.text.isNullOrBlank() && title?.visibility != View.VISIBLE) {
            title?.visibility = View.VISIBLE
            forcedPortraitTitle = true
        }
    }
    fun start() {
        val container = holder ?: return
        if (started || picture == null) return
        // CloudStream uses this same preference for its built-in trailer UI.
        val context = root.context
        val keyId = context.resources.getIdentifier("show_trailers_key", "string", context.packageName)
        val trailersEnabled = keyId == 0 || PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(keyId), true)
        if (trailersEnabled && !portraitMode()) return
        started = true
        pendingSplitFrames = 0
        splitFallback = false
        nativeRecoveryFrames = 0
        if (trailersEnabled) {
            // Only correct the native title; do not touch trailer layout or overlays.
            portraitTitleOnly = true
            root.viewTreeObserver.addOnPreDrawListener(preDraw)
            updatePortraitTitle()
            return
        }
        if (splitMode()) stage() // Hide both assets before their first native-layout draw.
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
        runCatching { update() }.onFailure { recoverOriginalLayout() }
    }
    private fun showTitle(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        if (titleOverlay?.visibility != visibility) titleOverlay?.visibility = visibility
        val alpha = if (show) 0f else originalTitleAlpha
        if (title?.alpha != alpha) title?.alpha = alpha
    }
    private fun update() {
        if (portraitTitleOnly) { updatePortraitTitle(); return }
        if (splitFallback) { revealRecoveredLayout(); return }
        val container = holder ?: return
        val image = picture ?: return
        if (container.width <= 0 || root.width <= 0) {
            if (splitMode() && ++pendingSplitFrames >= 8) recoverOriginalLayout()
            return
        }
        // With trailers enabled we never enter this layout path (see start()).
        // A GONE trailer holder means inactive, not an active player.
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
            updatePortraitTitle()
            return
        }

        // logo visibility becomes VISIBLE before Coil has finished decoding its drawable.
        val hasLogo = logo?.visibility == View.VISIBLE && logo.drawable != null && logo.width > 0
        val foreignLogo = hasLogo && detailUrl?.let { StremioC.fallbackLogoTitles[it] } == true
        val titleText = if (foreignLogo || !hasLogo)
            title?.text?.toString()?.trim()?.replace('\n', ' ')?.replace('\r', ' ').orEmpty() else ""
        val maxLabelWidth = (container.width * 0.36f).roundToInt().coerceAtLeast(1)
        val titleWidth = if (titleText.isNotEmpty())
            min(((baseTitlePaint ?: titleOverlay!!.paint).measureText(titleText) + dp(24)).roundToInt(),
                maxLabelWidth) else 0
        val logoFullWidth = if (hasLogo) (logo!!.width * abs(originalLogoScaleX) * 2f).roundToInt() else 0
        val labelWidth = max(min(logoFullWidth, maxLabelWidth), titleWidth)
        val gap = if (labelWidth > 0) dp(45) else 0
        val maxWidth = (container.width - labelWidth - gap - dp(24)).coerceAtLeast(1)
        val drawable = image.drawable
        val iw = drawable?.intrinsicWidth ?: 0
        val ih = drawable?.intrinsicHeight ?: 0
        // A missing logo is not a reason to hide a loaded backdrop forever.
        // Without a decoded backdrop, position a 16:9 slot; keep its alpha at 0.
        // The logo/title can still recover independently when their data arrives.
        val imageWidth = if (iw > 0 && ih > 0)
            min(maxWidth, (bannerHeight.toDouble() * iw / ih).roundToInt().coerceAtLeast(1))
            else min(maxWidth, (bannerHeight.toDouble() * 16 / 9).roundToInt().coerceAtLeast(1))
        val imageHeight = if (iw > 0 && ih > 0)
            min(bannerHeight, (imageWidth.toDouble() * ih / iw).roundToInt().coerceAtLeast(1))
            else min(bannerHeight, (imageWidth.toDouble() * 9 / 16).roundToInt().coerceAtLeast(1))
        val groupLeft = ((container.width - labelWidth - gap - imageWidth) / 2).coerceAtLeast(0)
        val imageLeft = groupLeft + labelWidth + gap
        val imageTop = (bannerHeight - imageHeight) / 2
        if (image.scaleType != ImageView.ScaleType.FIT_CENTER) image.scaleType = ImageView.ScaleType.FIT_CENTER
        // Reveal each drawable only after final dimensions AND coordinates are committed.
        var stable = image.width == imageWidth && image.height == imageHeight &&
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
        // Enforce both the 36%-wide label and the actual FIT_CENTER image bounds.
        val baseLogoWidth = if (hasLogo) logo!!.width * abs(originalLogoScaleX) else 0f
        val baseLogoHeight = if (hasLogo) logo!!.height * abs(originalLogoScaleY) else 0f
        var logoFactor = if (hasLogo && baseLogoWidth > 0f)
            min(2f, min(labelWidth, imageWidth).toFloat() / baseLogoWidth) else 1f
        val requestedLogoHeight = (baseLogoHeight * logoFactor).roundToInt()
        val verticalGap = if (foreignLogo && titleText.isNotEmpty()) dp(8).coerceAtMost((imageHeight - 2).coerceAtLeast(0)) else 0
        var titleHeight = 0
        titleOverlay?.let { overlay ->
            if (overlay.text.toString() != titleText) { overlay.text = titleText; stable = false }
            if (titleText.isNotEmpty()) {
                val previousSize = overlay.textSize
                fitTitle(overlay, titleText, labelWidth,
                    if (foreignLogo) (imageHeight - requestedLogoHeight - verticalGap).coerceAtLeast(1) else imageHeight)
                if (overlay.textSize != previousSize) stable = false
                if (foreignLogo) {
                    overlay.measure(View.MeasureSpec.makeMeasureSpec(labelWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    titleHeight = overlay.measuredHeight
                }
            }
        }
        if (hasLogo && baseLogoHeight > 0f) {
            val availableHeight = (imageHeight - titleHeight - verticalGap).coerceAtLeast(0)
            logoFactor = min(logoFactor, availableHeight / baseLogoHeight)
        }
        if (hasLogo) scaleLogo(true, logoFactor)
        val logoWidth = if (hasLogo) (logo!!.width * abs(logo.scaleX)).roundToInt() else 0
        val logoHeight = if (hasLogo) (logo!!.height * abs(logo.scaleY)).roundToInt() else 0
        val groupTop = ((bannerHeight - logoHeight - verticalGap - titleHeight) / 2f).coerceAtLeast(0f)
        if (hasLogo) {
            // Account for the centered pivot when scaling the original ImageView.
            logo?.translationX = groupLeft + (labelWidth - logoWidth) / 2 -
                logo!!.left + (logo.scaleX - 1f) * logo.pivotX
            logo?.translationY = (if (foreignLogo) groupTop else (bannerHeight - logoHeight) / 2f) -
                logo.top + (logo.scaleY - 1f) * logo.pivotY
        } else {
            logo?.translationX = originalLogoX
            logo?.translationY = originalLogoY
        }
        titleOverlay?.let { overlay ->
            val gravity = if (foreignLogo) Gravity.TOP or Gravity.START else Gravity.START or Gravity.CENTER_VERTICAL
            val top = if (foreignLogo) (groupTop + logoHeight + verticalGap).roundToInt() else 0
            val p = overlay.layoutParams as? FrameLayout.LayoutParams
            if (p != null && (p.width != labelWidth || p.gravity != gravity ||
                        p.marginStart != groupLeft || p.topMargin != top)) {
                p.width = labelWidth
                p.gravity = gravity
                p.marginStart = groupLeft
                p.topMargin = top
                overlay.layoutParams = p
                stable = false
            }
            if (overlay.gravity != (if (foreignLogo) Gravity.CENTER else Gravity.START)) {
                overlay.gravity = if (foreignLogo) Gravity.CENTER else Gravity.START
                stable = false
            }
        }
        if (stable) {
            pendingSplitFrames = 0
            showTitle(titleText.isNotEmpty())
            image.alpha = if (iw > 0 && ih > 0) originalPictureAlpha else 0f
            logo?.alpha = if (hasLogo) originalLogoAlpha else 0f
        } else {
            stage()
            // Bounded recovery: if layout coordinates cannot settle, restore the
            // native screen once, rather than displaying half-repositioned art.
            if (++pendingSplitFrames >= 8) recoverOriginalLayout()
        }
    }
    fun stop() {
        if (!started) return
        started = false
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDraw)
        if (forcedPortraitTitle && logo?.visibility == View.VISIBLE && logo.drawable != null)
            title?.visibility = View.GONE
        forcedPortraitTitle = false
        if (portraitTitleOnly) { portraitTitleOnly = false; return }
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
