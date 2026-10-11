package com.hsp1020

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.RectF
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
    private var trackedArtwork: Drawable? = null
    private var trackedBitmap: Bitmap? = null
    private val paletteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var paletteJob: Job? = null
    private var viewStartedMs = 0L
    private var drawableReadyMs = 0L
    private var firstRevealLogged = false
    private fun perf(event: String, values: String) {
        android.util.Log.d("StremioCPerf", "$event detailKey=${detailUrl?.hashCode() ?: 0} $values")
    }
    private fun noteDrawableReady(drawable: Drawable?) {
        if (viewStartedMs == 0L || drawableReadyMs != 0L) return
        val bitmap = paletteBitmap(drawable) ?: return
        drawableReadyMs = android.os.SystemClock.elapsedRealtime()
        perf("drawable_ready",
            "fromViewMs=${drawableReadyMs - viewStartedMs} size=${bitmap.width}x${bitmap.height}")
    }
    private fun noteFirstReveal(image: ImageView) {
        if (firstRevealLogged || drawableReadyMs == 0L ||
            image.alpha <= 0f || image.visibility != View.VISIBLE) return
        firstRevealLogged = true
        val now = android.os.SystemClock.elapsedRealtime()
        perf("reveal_ready",
            "fromViewMs=${now - viewStartedMs} fromDrawableMs=${now - drawableReadyMs} split=${splitMode()}")
    }

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
        val w = 72
        val h = 40
        val hardware = android.os.Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE
        // Hardware artwork is downsampled by the renderer before the small readback.
        // Older devices / failing drivers retain the original bounded fallback.
        val gpuThumb = if (hardware && android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                val recording = Picture()
                val canvas = recording.beginRecording(w, h)
                canvas.drawBitmap(source, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                recording.endRecording()
                Bitmap.createBitmap(recording, w, h, Bitmap.Config.ARGB_8888)
            } catch (_: Exception) { null }
        } else null
        if (hardware && gpuThumb == null && source.byteCount > 12 * 1024 * 1024) return null
        val software = if (hardware && gpuThumb == null)
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return null else source
        val thumb = gpuThumb ?: Bitmap.createScaledBitmap(software, w, h, true)
        try {
                val pixels = IntArray(w * h)
                thumb.getPixels(pixels, 0, w, 0, 0, w, h)
                val bins = IntArray(4096 * 4)
                val side = LongArray(8)
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
                    val offset = bucket * 4
                    bins[offset] += weight
                    bins[offset + 1] += r * weight
                    bins[offset + 2] += g * weight
                    bins[offset + 3] += b * weight
                    total[0]++
                    total[1] += r.toLong()
                    total[2] += g.toLong()
                    total[3] += b.toLong()
                    val region = if (x < w / 3) 0 else if (x >= 2 * w / 3) 1 else -1
                    if (region >= 0) {
                        val chroma = max(r, max(g, b)) - min(r, min(g, b))
                        val strength = 1L + chroma / 80
                        val offset = region * 4
                        side[offset] += strength
                        side[offset + 1] += strength * r
                        side[offset + 2] += strength * g
                        side[offset + 3] += strength * b
                    }
                }
                if (total[0] == 0L) return null
                fun average(a: LongArray, offset: Int, fallback: Int) =
                    if (a[offset] == 0L) fallback else Color.rgb(
                        (a[offset + 1] / a[offset]).toInt(),
                        (a[offset + 2] / a[offset]).toInt(),
                        (a[offset + 3] / a[offset]).toInt())
                val base = average(total, 0, fadeColor)
                var best = -1
                var bestScore = -1L
                for (i in 0 until 4096) {
                    val offset = i * 4
                    val count = bins[offset]
                    if (count == 0) continue
                    val chroma = max(bins[offset + 1], max(bins[offset + 2], bins[offset + 3])) -
                        min(bins[offset + 1], min(bins[offset + 2], bins[offset + 3]))
                    val score = count.toLong() * (80 + chroma / count / 2)
                    if (score > bestScore) { bestScore = score; best = offset }
                }
                val prominent = if (best < 0) base else Color.rgb(
                    bins[best + 1] / bins[best], bins[best + 2] / bins[best],
                    bins[best + 3] / bins[best])
                val center = blend(base, prominent, 0.45f)
                val left = blend(average(side, 0, center), center, 0.28f)
                val right = blend(average(side, 4, center), center, 0.28f)
                return ArtworkColors(blend(left, fadeColor, 0.12f),
                    blend(center, fadeColor, 0.12f), blend(right, fadeColor, 0.12f))
        } finally {
            if (thumb !== software && thumb !== source) thumb.recycle()
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
        edge?.invalidate()
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
        if (colors == null) {
            replaceBackground(ColorDrawable(fadeColor))
        } else {
            val horizontal = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(colors.left, colors.center, colors.right))
            val bottom = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, fadeColor))
            // Keep the bottom scrim at 5% of the current banner height (API 21+).
            replaceBackground(object : LayerDrawable(arrayOf(horizontal, bottom)) {
                override fun onBoundsChange(bounds: Rect) {
                    super.onBoundsChange(bounds)
                    val fadeHeight = (bounds.height() * 0.05f).roundToInt()
                    getDrawable(1).setBounds(bounds.left,
                        (bounds.bottom - fadeHeight).coerceAtLeast(bounds.top), bounds.right, bounds.bottom)
                }
            })
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
        val bitmap = paletteBitmap(drawable)
        // Coil may replace its crossfade wrapper while retaining the decoded bitmap.
        if (bitmap != null && trackedBitmap === bitmap) return
        trackedBitmap = bitmap
        paletteJob?.cancel()
        showArtworkPalette(null) // Never retain colors from the previous image.
        if (bitmap == null) return
        paletteJob = paletteScope.launch {
            val computeStartedMs = android.os.SystemClock.elapsedRealtime()
            val colors = runCatching { sampleArtwork(bitmap) }.getOrNull()
            val computeMs = android.os.SystemClock.elapsedRealtime() - computeStartedMs
            if (!isActive) return@launch
            perf("palette_computed", "elapsedMs=$computeMs available=${colors != null}")
            if (colors == null) return@launch
            withContext(Dispatchers.Main.immediate) {
                if (started && !splitFallback && splitMode() && trackedBitmap === bitmap &&
                    paletteBitmap(picture?.drawable) === bitmap) {
                    showArtworkPalette(colors)
                    perf("palette_applied",
                        "fromDrawableMs=${android.os.SystemClock.elapsedRealtime() - drawableReadyMs} computeMs=$computeMs")
                }
            }
        }
    }
    // Four original narrow compositing strips in one View, without full-screen layers.
    private class EdgeFadeView(private val frame: FrameLayout) : View(frame.context) {
        private val masks = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        } }
        private val areas = Array(4) { RectF() }
        private val colors = intArrayOf(Color.WHITE, Color.argb(215, 255, 255, 255),
            Color.argb(128, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.TRANSPARENT)
        private val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        private var fx = 0
        private var fy = 0
        fun setFeather(x: Int, y: Int) {
            if (fx == x && fy == y) return
            fx = x; fy = y
            updateMasks(); invalidate()
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateMasks()
        }
        private fun updateMasks() {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0 || fx <= 0 || fy <= 0) return
            areas[0].set(0f, 0f, fx.toFloat(), h)
            areas[1].set(w - fx, 0f, w, h)
            areas[2].set(0f, 0f, w, fy.toFloat())
            areas[3].set(0f, h - fy, w, h)
            for (i in 0..3) {
                val r = areas[i]
                masks[i].shader = LinearGradient(
                    if (i == 1) r.right else r.left, if (i == 3) r.bottom else r.top,
                    if (i == 0) r.right else r.left, if (i == 2) r.bottom else r.top,
                    colors, stops, Shader.TileMode.CLAMP)
            }
        }
        override fun onDraw(canvas: Canvas) {
            val background = frame.background ?: return
            if (width <= 0 || height <= 0 || fx <= 0 || fy <= 0) return
            for (i in 0..3) {
                val rect = areas[i]
                val layer = canvas.saveLayer(rect, null)
                val saved = canvas.save()
                canvas.translate(-left.toFloat(), -top.toFloat())
                background.draw(canvas)
                canvas.restoreToCount(saved)
                canvas.drawRect(rect, masks[i])
                canvas.restoreToCount(layer)
            }
        }
    }
    private val originalShadowVisibility = shadow?.visibility
    private var edge: EdgeFadeView? = null
    private var topScrim: View? = null
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
    private data class LayoutKey(
        val rootWidth: Int, val rootHeight: Int, val holderWidth: Int,
        val orientation: Int, val smallestWidth: Int,
        val picture: Drawable?, val logo: Drawable?, val logoVisibility: Int,
        val logoWidth: Int, val logoHeight: Int, val titleVisibility: Int,
        val expectedLogo: Boolean, val fallbackLogo: Boolean,
        val text: String?, val imageX: Int, val imageY: Int,
        val imageWidth: Int, val imageHeight: Int
    )
    private var lastLayout: LayoutKey? = null

    private fun layoutKey(container: FrameLayout, image: ImageView): LayoutKey {
        val logoView = logo
        return LayoutKey(root.width, root.height, container.width,
            root.resources.configuration.orientation,
            root.resources.configuration.smallestScreenWidthDp,
            image.drawable, logoView?.drawable, logoView?.visibility ?: View.GONE,
            logoView?.width ?: 0, logoView?.height ?: 0, title?.visibility ?: View.GONE,
            detailUrl?.let { StremioC.expectedLogoByUrl[it] } == true,
            detailUrl?.let { StremioC.fallbackLogoTitles[it] } == true,
            title?.text?.toString(), image.left, image.top, image.width, image.height)
    }
    private val density = root.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
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
    private fun placeEdge(view: EdgeFadeView, width: Int, height: Int, left: Int, top: Int) {
        place(view, width, height, left, top)
        if (view.width != width || view.height != height || view.left != left || view.top != top) {
            view.layout(left, top, left + width, top + height)
            view.invalidate() // Moving a strip changes its holder-background sample coordinates.
        }
    }
    private fun scaleLogo(factor: Float = 1f) {
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
    private fun restoreOriginalGeometry() {
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
        scaleLogo()
        (tvColumn?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { p ->
            originalTvMargin?.let { p.topMargin = it; tvColumn.layoutParams = p }
        }
    }
    private fun recoverOriginalLayout() {
        if (splitFallback || portraitTitleOnly) return
        paletteJob?.cancel()
        trackedArtwork = null
        trackedBitmap = null
        lastLayout = null
        useThemeBackground(false)
        if (!splitMode()) { visibility(true); return }
        splitFallback = true // Do not re-enter the split layout and cause a visible jump.
        nativeRecoveryFrames = 0
        edge?.let { placeEdge(it, 0, 0, 0, 0) }
        topScrim?.visibility = View.INVISIBLE
        restoreOriginalGeometry()
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
        viewStartedMs = android.os.SystemClock.elapsedRealtime()
        perf("view_start", "split=${splitMode()}")
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
        edge = EdgeFadeView(container).also {
            container.addView(it, 1, FrameLayout.LayoutParams(0, 0))
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
        // result_top_bar and result_top_holder are siblings; render above the entire hero.
        val barAttr = context.resources.getIdentifier("primaryGrayBackground", "attr", context.packageName)
        val barColors = context.theme.obtainStyledAttributes(
            intArrayOf(if (barAttr != 0) barAttr else android.R.attr.colorBackground))
        val barColor = try { barColors.getColor(0, fadeColor) } finally { barColors.recycle() }
        topScrim = View(context).also {
            it.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(barColor, barColor and 0x00FFFFFF))
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            it.visibility = if (splitMode()) View.VISIBLE else View.INVISIBLE
            ((phoneHero as? FrameLayout) ?: container).addView(it,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
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
        val edgeView = edge ?: return
        val container = holder ?: return
        val image = picture ?: return
        noteDrawableReady(image.drawable)
        // A not-yet-measured tablet/TV is loading, not a failed layout.
        if (container.width <= 0 || root.width <= 0) return
        val landscape = root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val tablet = root.resources.configuration.smallestScreenWidthDp >= 600
        val split = tv || landscape || tablet
        val bannerHeight = when {
            tv || tablet -> dp(400)
            landscape -> min(dp(300), (root.height * 0.70f).roundToInt())
            else -> dp(260)
        }
        if (split) {
            // Only resize when the banner height changes; avoid per-frame relayout.
            topScrim?.let { scrim ->
                val targetHeight = (bannerHeight * 0.05f).roundToInt().coerceAtLeast(1)
                val params = scrim.layoutParams
                if (params.height != targetHeight) {
                    params.height = targetHeight
                    scrim.layoutParams = params
                }
            }
            val logoView = logo
            val key = layoutKey(container, image)
            if (key == lastLayout &&
                !root.isLayoutRequested && !container.isLayoutRequested &&
                !image.isLayoutRequested && logoView?.isLayoutRequested != true &&
                edgeView.width == image.width && edgeView.height == image.height &&
                edgeView.left == image.left && edgeView.top == image.top &&
                (image.drawable == null || image.alpha == originalPictureAlpha) &&
                (logoView?.drawable == null || logoView.alpha == originalLogoAlpha) &&
                shadow?.visibility == View.INVISIBLE) return
            lastLayout = null
        }
        if (tv) {
            height(container, bannerHeight)
            (tvColumn?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { p ->
                if (p.topMargin != dp(350)) { p.topMargin = dp(350); tvColumn?.layoutParams = p }
            }
        } else height(phoneHero, bannerHeight)
        if (!split) {
            if (topScrim?.visibility != View.INVISIBLE) topScrim?.visibility = View.INVISIBLE
            lastLayout = null
            if (trackedArtwork != null) {
                paletteJob?.cancel()
                trackedArtwork = null
                trackedBitmap = null
                    }
            useThemeBackground(false)
            image.scaleType = originalScale ?: ImageView.ScaleType.CENTER_CROP
            originalPicture?.let { place(image, it.width, it.height, it.marginStart, it.topMargin) }
            edge?.let { placeEdge(it, 0, 0, 0, 0) }
            shadow?.visibility = originalShadowVisibility ?: View.VISIBLE
            showTitle(false, hideOriginal = maskPortraitTitle())
            visibility(true)
            noteFirstReveal(image)
            logo?.translationX = originalLogoX
            logo?.translationY = originalLogoY
            scaleLogo()
            updatePortraitTitle()
            return
        }

        useThemeBackground(true)
        if (topScrim?.visibility != View.VISIBLE) topScrim?.visibility = View.VISIBLE
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
        // Reserve 16:9 geometry while the image or logo is still decoding.
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
        // Commit geometry before reveal; never restage assets every frame.
        place(image, imageWidth, imageHeight, imageLeft, imageTop)
        if (image.width != imageWidth || image.height != imageHeight ||
            image.left != imageLeft || image.top != imageTop) {
            image.layout(imageLeft, imageTop, imageLeft + imageWidth, imageTop + imageHeight)
        }
        val featherX = min(dp(112), imageWidth / 4)
        val featherY = min(dp(84), imageHeight / 4)
        edgeView.setFeather(featherX, featherY)
        placeEdge(edgeView, imageWidth, imageHeight, imageLeft, imageTop)
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
                    if (overlay.isLayoutRequested || overlay.measuredWidth != labelWidth ||
                        overlay.measuredHeight <= 0) {
                        overlay.measure(View.MeasureSpec.makeMeasureSpec(labelWidth, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    }
                    titleHeight = overlay.measuredHeight
                }
            }
        }
        if (hasLogo && baseLogoHeight > 0f) {
            val availableHeight = (imageHeight - titleHeight - verticalGap).coerceAtLeast(0)
            logoFactor = min(logoFactor, availableHeight / baseLogoHeight)
        }
        if (hasLogo) scaleLogo(logoFactor)
        val logoWidth = if (hasLogo) (logo!!.width * abs(logo.scaleX)).roundToInt() else 0
        val logoHeight = if (hasLogo) (logo!!.height * abs(logo.scaleY)).roundToInt() else 0
        val groupTop = ((bannerHeight - logoHeight - verticalGap - titleHeight) / 2f).coerceAtLeast(0f)
        if (hasLogo) {
            // Account for the centered pivot when scaling the original ImageView.
            val newX = groupLeft + (labelWidth - logoWidth) / 2 -
                logo!!.left + (logo.scaleX - 1f) * logo.pivotX
            val newY = (if (foreignLogo) groupTop else (bannerHeight - logoHeight) / 2f) -
                logo.top + (logo.scaleY - 1f) * logo.pivotY
            if (logo.translationX != newX) logo.translationX = newX
            if (logo.translationY != newY) logo.translationY = newY
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
        // Reveal only decoded assets after final layout.
        if (image.width == imageWidth && image.height == imageHeight &&
            image.left == imageLeft && image.top == imageTop) {
            showTitle(titleText.isNotEmpty() && !logoPending, hideOriginal = expectedLogo && !foreignLogo)
            if (iw > 0 && ih > 0 && image.alpha != originalPictureAlpha)
                image.alpha = originalPictureAlpha
            noteFirstReveal(image)
            if (hasLogo && logo?.alpha != originalLogoAlpha)
                logo?.alpha = originalLogoAlpha
            // Cache only ready assets after their geometry has finished relayout.
            if (iw > 0 && ih > 0 && !logoPending && !root.isLayoutRequested &&
                !container.isLayoutRequested && !image.isLayoutRequested) {
                lastLayout = layoutKey(container, image)
            }
        }
    }
    fun stop() {
        if (!started) return
        started = false
        paletteJob?.cancel()
        paletteScope.cancel()
        trackedArtwork = null
        trackedBitmap = null
        lastLayout = null
        useThemeBackground(false)
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDraw)
        if (forcedPortraitTitle && logo?.visibility == View.VISIBLE && logo.drawable != null)
            title?.visibility = View.GONE
        forcedPortraitTitle = false
        if (portraitTitleOnly) { portraitTitleOnly = false; title?.alpha = originalTitleAlpha; return }
        showTitle(false)
        titleOverlay?.let { holder?.removeView(it) }; titleOverlay = null
        baseTitlePaint = null
        edge?.let { holder?.removeView(it) }; edge = null
        topScrim?.let { ((phoneHero as? FrameLayout) ?: holder)?.removeView(it) }; topScrim = null
        restoreOriginalGeometry()
        visibility(true)
    }
}
