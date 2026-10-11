package com.hsp1020

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import kotlinx.coroutines.*
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
    private var previousHolderBackground: Drawable? = null
    private var appliedHolderBackground: Drawable? = null
    private var fadeColor = Color.BLACK
    private data class ArtworkColors(val left: Int, val center: Int, val right: Int)
    private var artworkColors: ArtworkColors? = null
    private var trackedArtwork: Drawable? = null
    private val paletteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var paletteJob: Job? = null

    private fun blend(a: Int, b: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        fun mix(x: Int, y: Int) = (x + (y - x) * t).roundToInt().coerceIn(0, 255)
        return Color.rgb(mix(Color.red(a), Color.red(b)), mix(Color.green(a), Color.green(b)),
            mix(Color.blue(a), Color.blue(b)))
    }

    // Palette-inspired whole-image histogram with center prominence and side color averages.
    // Runs on Dispatchers.Default, not in the layout/pre-draw callback.
    private fun sampleArtwork(source: Bitmap): ArtworkColors? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val hardware = android.os.Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE
        if (hardware && source.byteCount > 12 * 1024 * 1024) return null
        val software = if (hardware) source.copy(Bitmap.Config.ARGB_8888, false) ?: return null else source
        try {
            val w = 72
            val h = 40
            val thumb = Bitmap.createScaledBitmap(software, w, h, true)
            try {
                val pixels = IntArray(w * h)
                thumb.getPixels(pixels, 0, w, 0, 0, w, h)
                val count = IntArray(4096)
                val reds = IntArray(4096)
                val greens = IntArray(4096)
                val blues = IntArray(4096)
                val side = Array(2) { LongArray(4) }
                val total = LongArray(4)
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    if (Color.alpha(pixel) < 128) continue
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val x = i % w
                    val bucket = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                    val weight = if (x >= w / 4 && x < 3 * w / 4) 2 else 1
                    count[bucket] += weight
                    reds[bucket] += r * weight
                    greens[bucket] += g * weight
                    blues[bucket] += b * weight
                    total[0]++
                    total[1] += r.toLong()
                    total[2] += g.toLong()
                    total[3] += b.toLong()
                    val region = if (x < w / 3) 0 else if (x >= 2 * w / 3) 1 else -1
                    if (region >= 0) {
                        val chroma = max(r, max(g, b)) - min(r, min(g, b))
                        val strength = 1L + chroma / 80
                        side[region][0] += strength
                        side[region][1] += strength * r
                        side[region][2] += strength * g
                        side[region][3] += strength * b
                    }
                }
                if (total[0] == 0L) return null
                fun average(a: LongArray, fallback: Int) = if (a[0] == 0L) fallback else
                    Color.rgb((a[1] / a[0]).toInt(), (a[2] / a[0]).toInt(), (a[3] / a[0]).toInt())
                val base = average(total, fadeColor)
                var best = -1
                var bestScore = -1L
                for (i in count.indices) {
                    if (count[i] == 0) continue
                    val chroma = max(reds[i], max(greens[i], blues[i])) -
                        min(reds[i], min(greens[i], blues[i]))
                    val score = count[i].toLong() * (80 + chroma / count[i] / 2)
                    if (score > bestScore) { bestScore = score; best = i }
                }
                val prominent = if (best < 0) base else Color.rgb(reds[best] / count[best],
                    greens[best] / count[best], blues[best] / count[best])
                val center = blend(base, prominent, 0.45f)
                val left = blend(average(side[0], center), center, 0.28f)
                val right = blend(average(side[1], center), center, 0.28f)
                return ArtworkColors(blend(left, fadeColor, 0.12f),
                    blend(center, fadeColor, 0.12f), blend(right, fadeColor, 0.12f))
            } finally {
                if (thumb !== software) thumb.recycle()
            }
        } finally {
            if (software !== source) software.recycle()
        }
    }

    // The original CloudStream background is retained by identity and never mutated.
    private fun replaceBackground(background: Drawable) {
        val container = holder ?: return
        if (appliedHolderBackground == null) previousHolderBackground = container.background
        else if (container.background !== appliedHolderBackground) return
        appliedHolderBackground = background
        container.background = background
        edges.forEach { it.invalidate() }
    }
    private fun useThemeBackground(enabled: Boolean) {
        val container = holder ?: return
        if (enabled) {
            if (appliedHolderBackground == null) replaceBackground(ColorDrawable(fadeColor))
        } else {
            val ours = appliedHolderBackground ?: return
            if (container.background === ours) container.background = previousHolderBackground
            appliedHolderBackground = null
            previousHolderBackground = null
        }
    }
    private fun showArtworkPalette(colors: ArtworkColors?) {
        artworkColors = colors
        if (colors == null) {
            replaceBackground(ColorDrawable(fadeColor))
        } else {
            val horizontal = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(colors.left, colors.center, colors.right))
            // The final third fades into the actual app theme, not a fixed black.
            val bottom = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.TRANSPARENT, fadeColor))
            replaceBackground(LayerDrawable(arrayOf(horizontal, bottom)))
        }
    }
    private fun paletteBitmap(drawable: Drawable?): Bitmap? {
        if (drawable is BitmapDrawable) return drawable.bitmap
        // CloudStream's Coil 3 loader uses a 200ms CrossfadeDrawable. Its public
        // "end" is the finished image; reflection avoids a new plugin dependency.
        if (drawable?.javaClass?.name != "coil3.transition.CrossfadeDrawable") return null
        val end = runCatching { drawable.javaClass.getMethod("getEnd").invoke(drawable) }.getOrNull()
        return (end as? BitmapDrawable)?.bitmap
    }
    private fun trackBackdrop(drawable: Drawable?) {
        if (trackedArtwork === drawable) return
        trackedArtwork = drawable
        paletteJob?.cancel()
        showArtworkPalette(null)
        val bitmap = paletteBitmap(drawable) ?: return
        paletteJob = paletteScope.launch {
            val colors = runCatching { sampleArtwork(bitmap) }.getOrNull() ?: return@launch
            if (!isActive) return@launch
            withContext(Dispatchers.Main.immediate) {
                if (started && !splitFallback && splitMode() && picture?.drawable === drawable)
                    showArtworkPalette(colors)
            }
        }
    }
    // Draw the holder's real background in its own coordinate space and smoothly
    // reveal it at the artwork's edges, without changing the Coil ImageView.
    private class EdgeFadeView(private val frame: FrameLayout, private val side: Int) : View(frame.context) {
        private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) { mask.shader = null; return }
            val colors = intArrayOf(Color.WHITE, Color.argb(215, 255, 255, 255),
                Color.argb(128, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.TRANSPARENT)
            val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            val x0 = if (side == 1) w.toFloat() else 0f
            val y0 = if (side == 3) h.toFloat() else 0f
            val x1 = if (side == 0) w.toFloat() else 0f
            val y1 = if (side == 2) h.toFloat() else 0f
            mask.shader = LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            val backdrop = frame.background ?: return
            if (width <= 0 || height <= 0 || mask.shader == null) return
            // DST_IN is isolated to the edge strip; it cannot erase the image or logo.
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            val saved = canvas.save()
            canvas.translate(-left.toFloat(), -top.toFloat())
            backdrop.draw(canvas)
            canvas.restoreToCount(saved)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
            canvas.restoreToCount(layer)
        }
    }
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
    // Asset alphas are staged once during start(); no re-staging on pre-draw.
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
    private fun placeEdge(view: View, width: Int, height: Int, left: Int, top: Int) {
        place(view, width, height, left, top)
        if (view.width != width || view.height != height || view.left != left || view.top != top) {
            view.layout(left, top, left + width, top + height)
            view.invalidate() // Moving a strip changes its holder-background sample coordinates.
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
        runCatching { update() }.onFailure {
            android.util.Log.e("StremioCBackdrop", "Split layout update failed", it)
            recoverOriginalLayout()
        }; true
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
        paletteJob?.cancel()
        trackedArtwork = null
        artworkColors = null
        useThemeBackground(false)
        if (!splitMode()) { visibility(true); return }
        splitFallback = true // Do not re-enter the split layout and cause a visible jump.
        nativeRecoveryFrames = 0
        edges.forEach { placeEdge(it, 0, 0, 0, 0) }
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
    private fun maskPortraitTitle(): Boolean =
        detailUrl?.let { StremioC.expectedLogoByUrl[it] } == true &&
            !(detailUrl?.let { StremioC.fallbackLogoTitles[it] } == true &&
              logo?.visibility == View.VISIBLE && logo.drawable != null && logo.width > 0)
    private fun updatePortraitTitle() {
        if (!portraitMode()) return
        if (detailUrl?.let { StremioC.expectedLogoByUrl[it] } == true) {
            val alpha = if (maskPortraitTitle()) 0f else originalTitleAlpha
            if (title?.alpha != alpha) title?.alpha = alpha
        }
        if (detailUrl?.let { StremioC.fallbackLogoTitles[it] } == true &&
            logo?.visibility == View.VISIBLE && logo.drawable != null &&
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
        if (portraitMode() && maskPortraitTitle()) title?.alpha = 0f
        started = true
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
        // Use the host app's active theme color so the edge fade also matches light themes.
        val colorAttr = context.resources.getIdentifier("primaryBlackBackground", "attr", context.packageName)
        val colors = context.theme.obtainStyledAttributes(
            intArrayOf(if (colorAttr != 0) colorAttr else android.R.attr.colorBackground)
        )
        fadeColor = try { colors.getColor(0, Color.BLACK) } finally { colors.recycle() }
        if (splitMode()) useThemeBackground(true)
        repeat(4) { index ->
            edges += EdgeFadeView(container, index).also {
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
        runCatching { update() }.onFailure {
            android.util.Log.e("StremioCBackdrop", "Split layout start failed", it)
            recoverOriginalLayout()
        }
    }
    private fun showTitle(show: Boolean, hideOriginal: Boolean = false) {
        val visibility = if (show) View.VISIBLE else View.GONE
        if (titleOverlay?.visibility != visibility) titleOverlay?.visibility = visibility
        val alpha = if (show || hideOriginal) 0f else originalTitleAlpha
        if (title?.alpha != alpha) title?.alpha = alpha
    }
    private fun update() {
        // An already-queued pre-draw can run after stop() removed the edge views.
        if (!started) return
        if (portraitTitleOnly) { updatePortraitTitle(); return }
        if (splitFallback) { revealRecoveredLayout(); return }
        if (edges.size < 4) return
        val container = holder ?: return
        val image = picture ?: return
        // A not-yet-measured tablet/TV is loading, not a failed layout.
        if (container.width <= 0 || root.width <= 0) return
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
            if (trackedArtwork != null) {
                paletteJob?.cancel()
                trackedArtwork = null
                artworkColors = null
            }
            useThemeBackground(false)
            image.scaleType = originalScale ?: ImageView.ScaleType.CENTER_CROP
            originalPicture?.let { place(image, it.width, it.height, it.marginStart, it.topMargin) }
            edges.forEach { placeEdge(it, 0, 0, 0, 0) }
            shadow?.visibility = originalShadowVisibility ?: View.VISIBLE
            showTitle(false, hideOriginal = maskPortraitTitle())
            visibility(true)
            logo?.translationX = originalLogoX
            logo?.translationY = originalLogoY
            scaleLogo(false)
            updatePortraitTitle()
            return
        }

        useThemeBackground(true)
        // logo visibility becomes VISIBLE before Coil has finished decoding its drawable.
        val hasLogo = logo?.visibility == View.VISIBLE && logo.drawable != null && logo.width > 0
        val expectedLogo = detailUrl?.let { StremioC.expectedLogoByUrl[it] } == true
        val logoPending = expectedLogo && !hasLogo
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
        trackBackdrop(drawable)
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
        // Persist the target geometry, then commit it *in this pre-draw*.
        // Comparing current bounds and re-staging on every frame caused the
        // tablet/TV to alternate between visible and alpha=0 indefinitely.
        place(image, imageWidth, imageHeight, imageLeft, imageTop)
        if (image.width != imageWidth || image.height != imageHeight ||
            image.left != imageLeft || image.top != imageTop) {
            image.layout(imageLeft, imageTop, imageLeft + imageWidth, imageTop + imageHeight)
        }
        val featherX = min(dp(112), imageWidth / 4)
        val featherY = min(dp(84), imageHeight / 4)
        placeEdge(edges[0], featherX, imageHeight, imageLeft, imageTop)
        placeEdge(edges[1], featherX, imageHeight, imageLeft + imageWidth - featherX, imageTop)
        placeEdge(edges[2], imageWidth, featherY, imageLeft, imageTop)
        placeEdge(edges[3], imageWidth, featherY, imageLeft, imageTop + imageHeight - featherY)
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
            if (overlay.text.toString() != titleText) overlay.text = titleText
            if (titleText.isNotEmpty()) {
                fitTitle(overlay, titleText, labelWidth,
                    if (foreignLogo) (imageHeight - requestedLogoHeight - verticalGap).coerceAtLeast(1) else imageHeight)
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
            }
            if (overlay.gravity != (if (foreignLogo) Gravity.CENTER else Gravity.START)) {
                overlay.gravity = if (foreignLogo) Gravity.CENTER else Gravity.START
            }
        }
        // All geometry has been committed. Reveal only assets with decoded data.
        // Later drawable arrivals/parent relayouts are repositioned in pre-draw,
        // WITHOUT staging already visible assets back to alpha=0.
        if (image.width == imageWidth && image.height == imageHeight &&
            image.left == imageLeft && image.top == imageTop) {
            showTitle(titleText.isNotEmpty() && !logoPending, hideOriginal = expectedLogo && !foreignLogo)
            if (iw > 0 && ih > 0) image.alpha = originalPictureAlpha
            if (hasLogo) logo?.alpha = originalLogoAlpha
        }
    }
    fun stop() {
        if (!started) return
        started = false
        paletteJob?.cancel()
        paletteScope.cancel()
        trackedArtwork = null
        artworkColors = null
        useThemeBackground(false)
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDraw)
        if (forcedPortraitTitle && logo?.visibility == View.VISIBLE && logo.drawable != null)
            title?.visibility = View.GONE
        forcedPortraitTitle = false
        if (portraitTitleOnly) { portraitTitleOnly = false; title?.alpha = originalTitleAlpha; return }
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
