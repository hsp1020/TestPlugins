package com.hsp1020

import android.graphics.Color
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import kotlin.math.max

/** Retain CloudStream's phone pager, image requests and action buttons; restyle only its pages. */
internal class PhoneHeroHook(
    private val activity: FragmentActivity,
    private val ratingImages: (CharSequence, Float) -> CharSequence?
) {
    private val home by lazy { ViewModelProvider(activity)[HomeViewModel::class.java] }
    private var pager: ViewPager2? = null
    private var recycler: RecyclerView? = null
    private val pages = HashMap<View, Page>()
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun scheduleRefresh() {
        val list = recycler ?: return
        list.post { if (recycler === list) refresh() }
    }
    private val previewObserver = Observer<Resource<Pair<Boolean, List<LoadResponse>>>> { scheduleRefresh() }
    private val selectionListener = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) { scheduleRefresh() }
    }
    private val childListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            view.post { if (recycler?.indexOfChild(view)?.let { it >= 0 } == true) refresh() }
        }
        override fun onChildViewDetachedFromWindow(view: View) {
            pages.remove(view)?.restore()
        }
    }

    fun attach(parent: RecyclerView) {
        val next = parent.findViewById<ViewPager2>(R.id.home_preview_viewpager) ?: run {
            detach()
            return
        }
        if (pager === next) return
        detach()
        val list = next.getChildAt(0) as? RecyclerView ?: return
        pager = next
        recycler = list
        list.addOnChildAttachStateChangeListener(childListener)
        next.registerOnPageChangeCallback(selectionListener)
        home.preview.observe(activity, previewObserver)
        scheduleRefresh()
    }

    private fun refresh() {
        val list = recycler ?: return
        val response = home.preview.value
        if (response !is Resource.Success) return
        val items = response.value.second
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val position = runCatching { list.getChildViewHolder(child).bindingAdapterPosition }
                .getOrDefault(RecyclerView.NO_POSITION)
            if (position in items.indices) {
                pages.getOrPut(child) { Page(child) }.bind(items[position])
            }
        }
    }

    fun detach() {
        pager?.unregisterOnPageChangeCallback(selectionListener)
        recycler?.removeOnChildAttachStateChangeListener(childListener)
        if (pager != null) home.preview.removeObserver(previewObserver)
        pages.values.forEach { it.restore() }
        pages.clear()
        recycler = null
        pager = null
    }

    private inner class Page(private val root: View) {
        private val image = root.findViewById<ImageView>(R.id.home_scroll_preview)
        private val logo = root.findViewById<ImageView>(R.id.home_preview_logo)
        private val title = root.findViewById<TextView>(R.id.home_scroll_preview_title)
        private val originalTags = root.findViewById<TextView>(R.id.home_scroll_preview_tags)
        private val holder = logo?.parent as? LinearLayout

        private val holderParams = (holder?.layoutParams as? FrameLayout.LayoutParams)?.let { FrameLayout.LayoutParams(it) }
        private val logoParams = (logo?.layoutParams as? LinearLayout.LayoutParams)?.let { LinearLayout.LayoutParams(it) }
        private val originalTitleGravity = title?.gravity
        private val originalTitleParams = (title?.layoutParams as? LinearLayout.LayoutParams)?.let { LinearLayout.LayoutParams(it) }
        private val originalTitleSize = title?.textSize
        private val originalTitlePadding = title?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
        private val originalTagsParams = (originalTags?.layoutParams as? LinearLayout.LayoutParams)?.let { LinearLayout.LayoutParams(it) }
        private val originalTagsAlpha = originalTags?.alpha
        private val originalScale = image?.scaleType
        private var lastKey: Int? = null
        private var previousDrawable: android.graphics.drawable.Drawable? = null
        private var previousWidth = -1
        private var previousHeight = -1

        private val rating = TextView(activity).apply { setTextColor(Color.WHITE); textSize = 14f; maxLines = 2 }
        private val plot = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) 5 else 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val gradient = View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xBB000000.toInt(), 0x77000000, Color.TRANSPARENT))
            isClickable = false
            isFocusable = false
        }
        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            val lines = if (root.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) 5 else 3
            if (plot.maxLines != lines) plot.maxLines = lines
            applyCropIfNeeded()
            true
        }

        init {
            (root as? FrameLayout)?.addView(gradient, 1, FrameLayout.LayoutParams(-1, -1))
            holderParams?.let { old ->
                holder?.layoutParams = FrameLayout.LayoutParams(old).apply {
                    gravity = Gravity.START or Gravity.BOTTOM
                    marginStart = dp(20)
                    marginEnd = dp(20)
                    bottomMargin = dp(104)
                }
            }
            logoParams?.let { old ->
                logo?.layoutParams = LinearLayout.LayoutParams(old).apply {
                    gravity = Gravity.START
                    width = dp(220)
                    height = dp(72)
                }
            }
            title?.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            originalTitleParams?.let { old ->
                title?.layoutParams = LinearLayout.LayoutParams(old).apply { gravity = Gravity.START }
            }
            title?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
            title?.apply { setPadding(0, paddingTop, 0, paddingBottom) }
            originalTags?.alpha = 0f
            originalTagsParams?.let { old ->
                originalTags?.layoutParams = LinearLayout.LayoutParams(old).apply { height = 0 }
            }
            holder?.addView(rating, LinearLayout.LayoutParams(-1, -2))
            holder?.addView(plot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            image?.scaleType = ImageView.ScaleType.MATRIX
            image?.viewTreeObserver?.addOnPreDrawListener(preDraw)
        }

        fun bind(item: LoadResponse) {
            val key = listOf(item.url, item.plot, item.year, item.duration, item.score).hashCode()
            if (key == lastKey) return
            lastKey = key
            val parsed = item.plot.html()
            val isMdb = parsed.firstOrNull() == '\u2063'
            val separator = if (isMdb) parsed.indexOf('\n') else -1
            val line = SpannableStringBuilder()
            if (isMdb) {
                line.append(parsed, 0, if (separator >= 0) separator else parsed.length)
            } else {
                item.score?.toStringNull(0.1, 10, 1, false)?.let { line.append("★ ").append(it) }
            }
            item.year?.let { if (line.isNotEmpty()) line.append("    "); line.append(it.toString()) }
            item.duration?.takeIf { it > 0 }?.let {
                if (line.isNotEmpty()) line.append("    ")
                line.append(it.toString()).append("m")
            }
            rating.text = ratingImages(line, rating.textSize) ?: line
            rating.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
            plot.text = if (separator >= 0) parsed.subSequence(separator + 1, parsed.length).trimStart() else if (isMdb) "" else parsed
            plot.visibility = if (plot.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        private fun applyCropIfNeeded() {
            val v = image ?: return
            val d = v.drawable ?: return
            val vw = v.width - v.paddingLeft - v.paddingRight
            val vh = v.height - v.paddingTop - v.paddingBottom
            if (vw <= 0 || vh <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
            if (previousDrawable === d && previousWidth == vw && previousHeight == vh) return
            previousDrawable = d
            previousWidth = vw
            previousHeight = vh
            val dw = d.intrinsicWidth.toFloat()
            val dh = d.intrinsicHeight.toFloat()
            val scale = max(vw / dw, vh / dh)
            val dx = (vw - dw * scale) * 0.5f
            val dy = (vh - dh * scale) * 0.20f
            v.imageMatrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate((dx + 0.5f).toInt().toFloat(), (dy + 0.5f).toInt().toFloat())
            }
        }

        fun restore() {
            image?.viewTreeObserver?.let { if (it.isAlive) it.removeOnPreDrawListener(preDraw) }
            image?.scaleType = originalScale ?: ImageView.ScaleType.CENTER_CROP
            (gradient.parent as? ViewGroup)?.removeView(gradient)
            (rating.parent as? ViewGroup)?.removeView(rating)
            (plot.parent as? ViewGroup)?.removeView(plot)
            holderParams?.let { holder?.layoutParams = it }
            logoParams?.let { logo?.layoutParams = it }
            originalTitleGravity?.let { title?.gravity = it }
            originalTitleParams?.let { title?.layoutParams = it }
            originalTitleSize?.let { title?.setTextSize(TypedValue.COMPLEX_UNIT_PX, it) }
            originalTitlePadding?.let { title?.setPadding(it[0], it[1], it[2], it[3]) }
            originalTagsParams?.let { originalTags?.layoutParams = it }
            originalTagsAlpha?.let { originalTags?.alpha = it }
        }
    }
}
