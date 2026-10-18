package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
        private val rows = mutableMapOf<View, RowProgress>()
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
                refreshRow(view)
            }
            override fun onChildViewDetachedFromWindow(view: View) {
                rows.remove(view)?.restore()
            }
        }
        private val listLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshRows() }
        private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener { syncList() }

        fun start() {
            if (tree.isAlive) tree.addOnGlobalLayoutListener(globalLayout)
            syncList()
        }

        fun stop() {
            if (tree.isAlive) tree.removeOnGlobalLayoutListener(globalLayout)
            releaseList()
        }

        private fun syncList() {
            if (!fragment.isAdded || fragment.view !== root) return
            val target = root.findViewById<RecyclerView>(R.id.result_episodes)
            if (target !== list) {
                releaseList()
                list = target
                target?.addOnChildAttachStateChangeListener(children)
                target?.addOnLayoutChangeListener(listLayout)
            }
            val nextAdapter = target?.adapter
            if (adapter !== nextAdapter) {
                adapter?.unregisterAdapterDataObserver(dataObserver)
                adapter = nextAdapter
                nextAdapter?.registerAdapterDataObserver(dataObserver)
            }
            refreshRows()
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
            val recycler = list ?: return
            recycler.postOnAnimation {
                if (list === recycler && fragment.view === root) refreshRows()
            }
        }

        private fun refreshRows() {
            val recycler = list ?: return
            for (i in 0 until recycler.childCount) refreshRow(recycler.getChildAt(i))
        }

        private fun refreshRow(row: View) {
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
                RowProgress(nativeProgress, frame, originalOverlay, createProgressBar(frame.context)).also {
                    frame.addView(it.horizontal)
                    rows[row] = it
                }
            }
            state.update()
        }

        private fun createProgressBar(context: Context): ProgressBar {
            val primary = context.colorFromAttribute(R.attr.colorPrimary)
            val track = 0xB3FFFFFF.toInt()
            val fill = GradientDrawable().apply { setColor(primary) }
            val background = GradientDrawable().apply { setColor(track) }
            val layers = LayerDrawable(arrayOf(
                background,
                ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            return ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                progressDrawable = layers
                max = 1000
                visibility = View.GONE
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                val height = (3f * resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM)
            }
        }
    }

    private class RowProgress(
        val native: ProgressBar,
        val frame: FrameLayout,
        val overlay: View,
        val horizontal: ProgressBar
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
