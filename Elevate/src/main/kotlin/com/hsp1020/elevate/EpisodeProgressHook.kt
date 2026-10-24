package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute

internal class EpisodeProgressHook(private val application: Application) : Application.ActivityLifecycleCallbacks {
    private var activity: FragmentActivity? = null
    private val screens = mutableMapOf<Fragment, Screen>()

    private val callback = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            bind(fragment, view)
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            screens.remove(fragment)?.stop()
        }
    }

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        release()
    }

    private fun use(next: FragmentActivity) {
        if (activity === next) return
        release()
        activity = next
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(callback, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                fragment.view?.let { bind(fragment, it) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
    }

    private fun release() {
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callback)
        screens.values.forEach(Screen::stop)
        screens.clear()
        activity = null
    }

    private fun bind(fragment: Fragment, root: View) {
        val type = fragment.javaClass.name
        if (type !in DETAIL_CLASSES) return
        val previous = screens[fragment]
        if (previous != null && previous.root === root) return
        previous?.stop()
        screens[fragment] = Screen(fragment, root).also(Screen::start)
    }

    private inner class Screen(
        val fragment: Fragment,
        val root: View
    ) {
        private val tree = root.viewTreeObserver
        private var list: RecyclerView? = null
        private var adapter: RecyclerView.Adapter<*>? = null
        private var refreshScheduled = false
        private val rows = mutableMapOf<View, RowProgress>()
        private var created = 0
        private var refreshCount = 0
        private var totalRefreshUs = 0L
        private var slowestRefreshUs = 0L
        private var lastReport = SystemClock.elapsedRealtime()
        private val primaryColor by lazy { root.context.colorFromAttribute(R.attr.colorPrimary) }
        private val rowHeight by lazy {
            (3f * root.resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1)
        }
        private fun reportPerformance(force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastReport < 2000L) return
            if (created != 0 || refreshCount != 0)
                Log.i("Elevate-EpisodePerf",
                    "stage=episode-row-window created=" + created +
                    " updated=" + refreshCount +
                    " totalUs=" + totalRefreshUs + " maxUs=" + slowestRefreshUs +
                    " attached=" + rows.size +
                    " elapsedMs=" + (now - lastReport))
            created = 0
            refreshCount = 0
            totalRefreshUs = 0
            slowestRefreshUs = 0
            lastReport = now
        }
        private val dataObserver = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = scheduleRefresh()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = scheduleRefresh()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = scheduleRefresh()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = scheduleRefresh()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = scheduleRefresh()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = scheduleRefresh()
        }

        private val children = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                // Adapter inserts/layout are already on the main thread.
                // Never allocate overlays from RecyclerView's layout traversal.
                scheduleRefresh()
            }
            override fun onChildViewDetachedFromWindow(view: View) {
                rows.remove(view)?.restore()
            }
        }
        private val listLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleRefresh() }
        private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener { syncList() }

        fun start() {
            if (tree.isAlive) tree.addOnGlobalLayoutListener(globalLayout)
            syncList()
        }

        fun stop() {
            if (tree.isAlive) tree.removeOnGlobalLayoutListener(globalLayout)
            reportPerformance(force = true)
            releaseList()
        }

        private fun syncList() {
            if (!fragment.isAdded || fragment.view !== root) return
            val target = root.findViewById<RecyclerView>(R.id.result_episodes)
            var changed = false
            if (target !== list) {
                releaseList()
                list = target
                target?.addOnChildAttachStateChangeListener(children)
                target?.addOnLayoutChangeListener(listLayout)
                changed = true
            }
            val nextAdapter = target?.adapter
            if (adapter !== nextAdapter) {
                adapter?.unregisterAdapterDataObserver(dataObserver)
                adapter = nextAdapter
                nextAdapter?.registerAdapterDataObserver(dataObserver)
                changed = true
            }
            if (changed) scheduleRefresh()
        }

        private fun releaseList() {
            adapter?.unregisterAdapterDataObserver(dataObserver)
            adapter = null
            list?.removeOnChildAttachStateChangeListener(children)
            list?.removeOnLayoutChangeListener(listLayout)
            rows.values.toList().forEach(RowProgress::restore)
            rows.clear()
            list = null
        }

        private fun scheduleRefresh() {
            if (refreshScheduled) return
            val recycler = list ?: return
            refreshScheduled = true
            recycler.postOnAnimation {
                refreshScheduled = false
                if (list === recycler && fragment.view === root) refreshRows()
            }
        }

        private fun refreshRows() {
            val recycler = list ?: return
            // Coalesced once per frame, after any RecyclerView layout pass.
            for (i in 0 until recycler.childCount) refreshRow(recycler.getChildAt(i))
            reportPerformance()
        }

        private fun refreshRow(row: View) {
            val beginNs = SystemClock.elapsedRealtimeNanos()
            val nativeProgress = row.findViewById<ProgressBar>(R.id.episode_progress)
            val poster = row.findViewById<ImageView>(R.id.episode_poster)
            val originalOverlay = row.findViewById<View>(R.id.watch_progress_container)
            val frame = poster?.parent as? FrameLayout
            if (nativeProgress == null || poster == null || frame == null || originalOverlay == null) {
                rows.remove(row)?.restore()
                return
            }

            val current = rows[row]
            val state = if (current != null && current.native === nativeProgress && current.frame === frame && current.overlay === originalOverlay) {
                current
            } else {
                current?.restore()
                RowProgress(nativeProgress, frame, originalOverlay,
                    SlimProgressView(frame.context, primaryColor).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, rowHeight, Gravity.BOTTOM)
                    }).also {
                    frame.addView(it.horizontal)
                    rows[row] = it
                    created++
                }
            }
            // Keep the latest horizontal thumbnail progress design.
            // Without update() the newly created SlimProgressView remains GONE
            // and CloudStream's original circular overlay stays visible.
            state.update()
            val elapsedUs = (SystemClock.elapsedRealtimeNanos() - beginNs) / 1000L
            refreshCount++
            totalRefreshUs += elapsedUs
            if (elapsedUs > slowestRefreshUs) slowestRefreshUs = elapsedUs
            if (elapsedUs >= 8000L)
                Log.w("Elevate-EpisodePerf", "stage=episode-row-slow durationUs=" +
                    elapsedUs + " created=" + created + " attached=" + rows.size)
        }
    }

    // The native ProgressBar constructor parses theme XML and animated vector
    // drawables even when the control is only a 3dp, noninteractive indicator.
    // A lightweight, equivalent bar avoids these per-row allocations.
    private class SlimProgressView(context: Context, primary: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val foreground = primary
        private val track = Color.argb(179, 255, 255, 255)
        var progress: Int = 0
            set(value) {
                val bounded = value.coerceIn(0, 1000)
                if (field == bounded) return
                field = bounded
                invalidate()
            }
        init {
            visibility = GONE
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = track
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = foreground
            canvas.drawRect(0f, 0f, width * progress / 1000f, height.toFloat(), paint)
        }
    }

    private class RowProgress(
        val native: ProgressBar,
        val frame: FrameLayout,
        val overlay: View,
        val horizontal: SlimProgressView
    ) {
        private val originalOverlayVisibility = overlay.visibility

        fun update() {
            val visible = native.visibility == View.VISIBLE && native.max > 0 && native.progress > 0
            val amount = if (visible) (native.progress.toFloat() / native.max * 1000f).toInt().coerceIn(0, 1000) else 0
            if (horizontal.progress != amount) horizontal.progress = amount
            val target = if (visible) View.VISIBLE else View.GONE
            if (horizontal.visibility != target) horizontal.visibility = target
            if (overlay.visibility != View.INVISIBLE) overlay.visibility = View.INVISIBLE
        }

        fun restore() {
            if (horizontal.parent === frame) frame.removeView(horizontal)
            overlay.visibility = originalOverlayVisibility
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity) release()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        val DETAIL_CLASSES = setOf(
            "com.lagradost.cloudstream3.ui.result.ResultTrailerPlayer",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentTv"
        )
    }
}
