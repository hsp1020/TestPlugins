package com.hsp1020

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.widget.NestedScrollView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Adjusts ONLY the existing StremioC result fragment's backdrop; no global app UI changes. */
internal class DetailBackdropLayout(private val root: View) {
    private fun find(name: String): View? {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        return if (id == 0) null else root.findViewById(id)
    }

    private val phoneHero = find("result_top_holder")
    private val tv = phoneHero == null
    private val backdropHolder = (find(if (tv) "background_poster_holder" else "result_poster_background_holder") as? FrameLayout)
    private val backdrop = find(if (tv) "background_poster" else "result_poster_background") as? ImageView
    private val logo = find(if (tv) "background_poster_watermark_badge_holder" else "background_poster_watermark_badge") as? ImageView
    private val toolbar = if (tv) null else find("result_top_bar") as? FrameLayout
    private val scroller = if (tv) null else find("result_scroll") as? NestedScrollView
    private val trailerHolder = if (tv) null else find("result_smallscreen_holder")
    private val tvColumn = if (tv) {
        ((find("result_finish_loading") as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0)
    } else null

    private val initialHeroHeight = phoneHero?.layoutParams?.height
    private val initialHolderHeight = backdropHolder?.layoutParams?.height
    private val initialPoster = (backdrop?.layoutParams as? FrameLayout.LayoutParams)?.let { FrameLayout.LayoutParams(it) }
    private val initialScaleType = backdrop?.scaleType
    private val initialImageMatrix = backdrop?.imageMatrix?.let { Matrix(it) }
    private val initialTvMargin = (tvColumn?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin
    private val initialToolbarClip = toolbar?.clipToPadding
    private var overlayImage: ImageView? = null
    private var toolbarFade: View? = null
    private var backdropFade: View? = null
    private var lastKey: List<Any?>? = null
    private var imageInToolbar: Drawable? = null
    private var applied = false
    private val fadeDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.BLACK, Color.TRANSPARENT)
    )

    private fun dp(value: Int): Int = (value * root.resources.displayMetrics.density).roundToInt()
    private fun setHeight(view: View?, target: Int) {
        val params = view?.layoutParams ?: return
        if (params.height != target) { params.height = target; view.layoutParams = params }
    }

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        runCatching { update() }
        true
    }
    private val scrollChanged = ViewTreeObserver.OnScrollChangedListener {
        updateToolbarVisibility()
    }

    fun start() {
        if (backdrop == null || backdropHolder == null) return
        applied = true
        if (!tv && toolbar != null) {
            // The original toolbar has side padding; the photo must still reach screen edges.
            toolbar.clipToPadding = false
            // Insert below the original back/cast/other buttons, never reparent any app button.
            overlayImage = ImageView(toolbar.context).also {
                it.scaleType = ImageView.ScaleType.MATRIX
                toolbar.addView(it, 0, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
                ))
            }
            toolbarFade = View(toolbar.context).also {
                it.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.BLACK, Color.TRANSPARENT))
                toolbar.addView(it, 1, FrameLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
            }
            scroller?.viewTreeObserver?.addOnScrollChangedListener(scrollChanged)
        }
        backdropFade = View(root.context).also {
            it.background = fadeDrawable
            backdropHolder.addView(it, 1, FrameLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        }
        root.viewTreeObserver.addOnPreDrawListener(preDraw)
        update()
    }

    private fun update() {
        val holder = backdropHolder ?: return
        val picture = backdrop ?: return
        if (root.width <= 0 || holder.width <= 0) return
        // The built-in trailer player owns result_top_holder while video is active.
        if (!tv && trailerHolder?.visibility == View.GONE) {
            overlayImage?.visibility = View.GONE
            toolbarFade?.visibility = View.GONE
            return
        }

        val orientation = root.resources.configuration.orientation
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val tablet = root.resources.configuration.smallestScreenWidthDp >= 600
        val totalHeight = when {
            tv || (landscape && tablet) -> dp(300)
            landscape -> min(dp(300), (root.height * 0.70f).roundToInt())
            else -> dp(260)
        }
        val toolbarHeight = if (tv) 0 else (toolbar?.height ?: dp(50))
        val pictureHeight = max(dp(80), totalHeight - toolbarHeight)
        val logoVisible = logo?.visibility == View.VISIBLE && logo.drawable != null
        val logoRight = if (logoVisible) logo!!.right + dp(16) else 0
        val split = landscape || tv
        val left = if (split && logoVisible && holder.width - logoRight >= dp(120)) logoRight else 0
        val pictureWidth = holder.width - left
        val photo = picture.drawable
        val key: List<Any?> = listOf(root.width, root.height, orientation, tablet, totalHeight,
            holder.width, toolbarHeight, logoVisible, logoRight, left, photo, picture.layoutParams.height,
            phoneHero?.layoutParams?.height, backdropHolder?.layoutParams?.height)
        if (key == lastKey) { updateToolbarVisibility(); return }
        lastKey = key

        if (tv) {
            setHeight(holder, totalHeight)
            setHeight(picture, totalHeight)
            // 225dp original content offset must follow 275dp -> 300dp hero growth.
            (tvColumn?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { p ->
                val target = dp(250)
                if (p.topMargin != target) {
                    p.topMargin = target
                    tvColumn?.let { column -> column.layoutParams = p }
                }
            }
        } else {
            setHeight(phoneHero, pictureHeight)
        }
        (picture.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
            if (p.width != pictureWidth || p.gravity != Gravity.END) {
                p.width = pictureWidth
                p.gravity = Gravity.END
                picture.layoutParams = p
            }
        }
        // Dark gradient only at the new left edge of the picture.
        fun resizeFade(fade: View?, start: Int) {
            val p = fade?.layoutParams as? FrameLayout.LayoutParams ?: return
            val fadeWidth = if (start == 0) 0 else min(dp(72), pictureWidth / 5)
            if (p.width != fadeWidth || p.marginStart != start) {
                p.width = fadeWidth
                p.marginStart = start
                fade.layoutParams = p
            }
        }
        resizeFade(backdropFade, left)
        if (!tv) {
            val topImage = overlayImage
            val p = topImage?.layoutParams as? FrameLayout.LayoutParams
            if (p != null && p.width != pictureWidth) {
                p.width = pictureWidth
                topImage.layoutParams = p
            }
            resizeFade(toolbarFade, left)
            topImage?.translationX = (toolbar?.paddingRight ?: 0).toFloat()
            toolbarFade?.translationX = -(toolbar?.paddingLeft ?: 0).toFloat()
            if (photo !== imageInToolbar) {
                imageInToolbar = photo
                topImage?.setImageDrawable(photo?.constantState?.newDrawable(root.resources)?.mutate())
            }
            if (photo != null && photo.intrinsicWidth > 0 && photo.intrinsicHeight > 0) {
                // One common CENTER_CROP matrix over toolbar + hero, split at toolbarHeight.
                // This keeps the picture continuous without downloading it twice.
                val scale = max(pictureWidth.toFloat() / photo.intrinsicWidth,
                    totalHeight.toFloat() / photo.intrinsicHeight)
                val dx = (pictureWidth - photo.intrinsicWidth * scale) / 2f
                val dy = (totalHeight - photo.intrinsicHeight * scale) / 2f
                picture.scaleType = ImageView.ScaleType.MATRIX
                picture.imageMatrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(dx, dy - toolbarHeight)
                }
                topImage?.imageMatrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(dx, dy)
                }
            }
            updateToolbarVisibility()
        }
    }

    private fun updateToolbarVisibility() {
        if (tv) return
        val picture = backdrop ?: return
        val active = trailerHolder?.visibility != View.GONE &&
            (scroller?.scrollY ?: 0) < (phoneHero?.height ?: 0) &&
            picture.drawable != null && overlayImage?.drawable != null
        overlayImage?.visibility = if (active) View.VISIBLE else View.GONE
        toolbarFade?.visibility = if (active && (backdropFade?.layoutParams?.width ?: 0) > 0)
            View.VISIBLE else View.GONE
    }

    fun stop() {
        if (!applied) return
        applied = false
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDraw)
        scroller?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollChanged)
        overlayImage?.let { toolbar?.removeView(it) }
        toolbarFade?.let { toolbar?.removeView(it) }
        backdropFade?.let { backdropHolder?.removeView(it) }
        overlayImage = null; toolbarFade = null; backdropFade = null
        initialToolbarClip?.let { toolbar?.clipToPadding = it }
        val picture = backdrop ?: return
        initialHeroHeight?.let { setHeight(phoneHero, it) }
        initialHolderHeight?.let { setHeight(backdropHolder, it) }
        initialPoster?.let { picture.layoutParams = FrameLayout.LayoutParams(it) }
        initialScaleType?.let { picture.scaleType = it }
        initialImageMatrix?.let { picture.imageMatrix = it }
        (tvColumn?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            initialTvMargin?.let {
                params.topMargin = it
                tvColumn?.let { column -> column.layoutParams = params }
            }
        }
    }
}
