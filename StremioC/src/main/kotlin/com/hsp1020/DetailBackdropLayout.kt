package com.hsp1020

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
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
    private val trailerHolder = if (tv) null else find("result_smallscreen_holder")
    private val titleView = find("result_title") as? TextView
    private val tvColumn = if (tv) {
        ((find("result_finish_loading") as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0)
    } else null

    private val initialHeroHeight = phoneHero?.layoutParams?.height
    private val initialHolderHeight = backdropHolder?.layoutParams?.height
    private val initialPoster = (backdrop?.layoutParams as? FrameLayout.LayoutParams)?.let { FrameLayout.LayoutParams(it) }
    private val initialScaleType = backdrop?.scaleType
    private val initialImageMatrix = backdrop?.imageMatrix?.let { Matrix(it) }
    private val initialTvMargin = (tvColumn?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin
    private var backdropFade: View? = null
    private var lastKey: List<Any?>? = null
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

    fun start() {
        if (backdrop == null || backdropHolder == null) return
        applied = true
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
        if (!tv && trailerHolder?.visibility == View.GONE) return

        val orientation = root.resources.configuration.orientation
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val tablet = root.resources.configuration.smallestScreenWidthDp >= 600
        val totalHeight = when {
            tv || (landscape && tablet) -> dp(400)
            landscape -> min(dp(300), (root.height * 0.70f).roundToInt())
            else -> dp(260)
        }
        val split = landscape || tv
        val logoVisible = logo?.visibility == View.VISIBLE && logo.drawable != null
        val logoRight = if (split && logoVisible && logo!!.width > 0) logo.right + dp(16) else 0
        // CloudStream's bindLogo() shows result_title when the logo URL is absent or fails.
        // Measure that text without relocating or duplicating the original title view.
        val titleWidth = if (split && !logoVisible && titleView?.visibility == View.VISIBLE) {
            val text = titleView.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) 0 else (titleView.paint.measureText(text) + dp(24)).roundToInt()
        } else 0
        val maxLeft = (holder.width - dp(160)).coerceAtLeast(0)
        val left = when {
            !split -> 0
            logoVisible -> if (logoRight > 0 && logoRight <= maxLeft) logoRight else 0
            titleWidth > 0 -> min(titleWidth, min(maxLeft, (holder.width * 0.42f).roundToInt()))
            else -> 0
        }
        val pictureWidth = holder.width - left
        val photo = picture.drawable
        val key: List<Any?> = listOf(root.width, root.height, orientation, tablet, totalHeight,
            holder.width, logoVisible, logoRight, titleWidth, left, photo, picture.layoutParams.height,
            phoneHero?.layoutParams?.height, backdropHolder?.layoutParams?.height)
        if (key == lastKey) return
        lastKey = key

        if (tv) {
            setHeight(holder, totalHeight)
            setHeight(picture, totalHeight)
            // Original TV layout has a 225dp top margin under a 275dp backdrop.
            (tvColumn?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { p ->
                val target = dp(350)
                if (p.topMargin != target) {
                    p.topMargin = target
                    tvColumn?.let { column -> column.layoutParams = p }
                }
            }
        } else {
            // The toolbar is outside this hero; never overlay the app buttons.
            setHeight(phoneHero, totalHeight)
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
    }

    fun stop() {
        if (!applied) return
        applied = false
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDraw)
        backdropFade?.let { backdropHolder?.removeView(it) }
        backdropFade = null
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
