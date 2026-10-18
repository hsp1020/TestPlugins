package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.util.Log
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
import java.util.WeakHashMap

internal class EpisodeProgressHook(private val application: Application) : Application.ActivityLifecycleCallbacks {
    private var activity: FragmentActivity? = null
    private val screens = mutableMapOf<Fragment, Screen>()

    private fun trace(event: String) = Log.i(TAG, event)

    private fun viewLabel(view: View?): String = if (view == null) "null" else
        "${view.javaClass.simpleName}@${System.identityHashCode(view)}(id=${view.id},vis=${view.visibility},size=${view.width}x${view.height})"

    private val callback = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            trace("FRAGMENT_CREATED name=${fragment.javaClass.name} added=${fragment.isAdded} root=${viewLabel(view)}")
            bind(fragment, view)
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            trace("FRAGMENT_DESTROYED name=${fragment.javaClass.name} tracked=${screens.containsKey(fragment)}")
            screens.remove(fragment)?.stop()
        }
    }

    fun start() {
        trace("HOOK_START currentActivity=${CommonActivity.activity?.javaClass?.name} application=${application.javaClass.name}")
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        trace("HOOK_STOP trackedScreens=${screens.size}")
        application.unregisterActivityLifecycleCallbacks(this)
        release()
    }

    private fun use(next: FragmentActivity) {
        if (activity === next) return
        trace("ACTIVITY_BIND name=${next.javaClass.name} fragments=${next.supportFragmentManager.fragments.size}")
        release()
        activity = next
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(callback, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                trace("FRAGMENT_SCAN name=${fragment.javaClass.name} added=${fragment.isAdded} visible=${fragment.isVisible} hasView=${fragment.view != null}")
                fragment.view?.let { bind(fragment, it) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
    }

    private fun release() {
        if (activity != null) trace("ACTIVITY_RELEASE trackedScreens=${screens.size}")
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callback)
        screens.values.forEach(Screen::stop)
        screens.clear()
        activity = null
    }

    private fun bind(fragment: Fragment, root: View) {
        val type = fragment.javaClass.name
        val home = type == HOME_CLASS
        if (!home && type !in DETAIL_CLASSES) return
        val previous = screens[fragment]
        if (previous != null && previous.root === root) return
        trace("SCREEN_BIND kind=${if (home) "home" else "detail"} fragment=$type added=${fragment.isAdded} root=${viewLabel(root)} replacement=${previous != null}")
        previous?.stop()
        screens[fragment] = Screen(fragment, root, home).also(Screen::start)
    }

    private inner class Screen(
        val fragment: Fragment,
        val root: View,
        private val home: Boolean
    ) {
        private val tree = root.viewTreeObserver
        private var list: RecyclerView? = null
        private var adapter: RecyclerView.Adapter<*>? = null
        private val rows = mutableMapOf<View, RowProgress>()
        private val rowStates = WeakHashMap<View, String>()
        private var lastListState: String? = null
        private val screenLabel = if (home) "home" else "detail"

        private fun rowTraceOnce(row: View, status: String) {
            if (rowStates[row] == status) return
            rowStates[row] = status
            trace("ROW_STATUS screen=$screenLabel row=${viewLabel(row)} $status")
        }

        private val dataObserver = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = scheduleRefresh("all")
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = scheduleRefresh("change:$positionStart:$itemCount")
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = scheduleRefresh("payload:$positionStart:$itemCount")
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = scheduleRefresh("insert:$positionStart:$itemCount")
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = scheduleRefresh("remove:$positionStart:$itemCount")
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = scheduleRefresh("move:$fromPosition:$toPosition:$itemCount")
        }

        private val children = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                trace("ROW_ATTACH screen=$screenLabel row=${viewLabel(view)}")
                refreshRow(view)
            }
            override fun onChildViewDetachedFromWindow(view: View) {
                trace("ROW_DETACH screen=$screenLabel row=${viewLabel(view)}")
                rows.remove(view)?.restore()
                rowStates.remove(view)
            }
        }
        private val listLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshRows() }
        private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener { syncList() }

        fun start() {
            trace("SCREEN_START screen=$screenLabel root=${viewLabel(root)} treeAlive=${tree.isAlive}")
            if (tree.isAlive) tree.addOnGlobalLayoutListener(globalLayout)
            syncList()
        }

        fun stop() {
            trace("SCREEN_STOP screen=$screenLabel rowBindings=${rows.size}")
            if (tree.isAlive) tree.removeOnGlobalLayoutListener(globalLayout)
            releaseList()
        }

        private fun syncList() {
            if (!fragment.isAdded || fragment.view !== root) {
                val status = "INACTIVE added=${fragment.isAdded} sameView=${fragment.view === root} currentView=${viewLabel(fragment.view)}"
                if (lastListState != status) {
                    trace("LIST screen=$screenLabel $status")
                    lastListState = status
                }
                return
            }
            val id = if (home) R.id.home_watch_child_recyclerview else R.id.result_episodes
            val target = root.findViewById<RecyclerView>(id)
            val status = "target=${viewLabel(target)} adapter=${target?.adapter?.javaClass?.name} items=${target?.adapter?.itemCount} visibleChildren=${target?.childCount} root=${viewLabel(root)}"
            if (lastListState != status) {
                trace("LIST screen=$screenLabel $status")
                lastListState = status
            }
            if (target !== list) {
                trace("LIST_REPLACE screen=$screenLabel old=${viewLabel(list)} new=${viewLabel(target)}")

                releaseList()
                list = target
                target?.addOnChildAttachStateChangeListener(children)
                target?.addOnLayoutChangeListener(listLayout)
            }
            val nextAdapter = target?.adapter
            if (adapter !== nextAdapter) {
                trace("ADAPTER_REPLACE screen=$screenLabel old=${adapter?.javaClass?.name} new=${nextAdapter?.javaClass?.name}")
                adapter?.unregisterAdapterDataObserver(dataObserver)
                adapter = nextAdapter
                nextAdapter?.registerAdapterDataObserver(dataObserver)
            }
            refreshRows()
        }

        private fun releaseList() {
            if (list != null || rows.isNotEmpty()) trace("LIST_RELEASE screen=$screenLabel rows=${rows.size} list=${viewLabel(list)}")
            adapter?.unregisterAdapterDataObserver(dataObserver)
            adapter = null
            list?.removeOnChildAttachStateChangeListener(children)
            list?.removeOnLayoutChangeListener(listLayout)
            rows.values.toList().forEach(RowProgress::restore)
            rows.clear()
            rowStates.clear()
            list = null
        }

        private fun scheduleRefresh(reason: String) {
            val recycler = list ?: return
            trace("ADAPTER_EVENT screen=$screenLabel reason=$reason items=${recycler.adapter?.itemCount} visible=${recycler.childCount}")
            recycler.postOnAnimation {
                if (list === recycler && fragment.view === root) refreshRows()
            }
        }

        private fun refreshRows() {
            val recycler = list ?: return
            for (i in 0 until recycler.childCount) refreshRow(recycler.getChildAt(i))
        }

        private fun refreshRow(row: View) {
            val nativeProgress = row.findViewById<ProgressBar>(
                if (home) R.id.watchProgress else R.id.episode_progress
            )
            val poster = row.findViewById<ImageView>(
                if (home) R.id.imageView else R.id.episode_poster
            )
            val originalOverlay = row.findViewById<View>(R.id.watch_progress_container)
            val frame = if (home) row.findViewById<FrameLayout>(R.id.background_card)
                        else poster?.parent as? FrameLayout
            if (nativeProgress == null || poster == null || frame == null || originalOverlay == null) {
                rowTraceOnce(row, "UNMATCHED progress=${viewLabel(nativeProgress)} poster=${viewLabel(poster)} posterParent=${viewLabel(poster?.parent as? View)} frame=${viewLabel(frame)} overlay=${viewLabel(originalOverlay)}")
                rows.remove(row)?.restore()
                return
            }

            rowTraceOnce(row, "MATCH progress=${viewLabel(nativeProgress)} max=${nativeProgress.max} value=${nativeProgress.progress} poster=${viewLabel(poster)} frame=${viewLabel(frame)} overlay=${viewLabel(originalOverlay)}")
            val current = rows[row]
            val state = if (current != null && current.native === nativeProgress && current.frame === frame && current.overlay === originalOverlay) {
                current
            } else {
                current?.restore()
                RowProgress(nativeProgress, frame, originalOverlay, createProgressBar(frame.context), screenLabel).also {
                    frame.addView(it.horizontal)
                    rows[row] = it
                    trace("BAR_CREATED screen=$screenLabel row=${viewLabel(row)} frame=${viewLabel(frame)} native=${viewLabel(nativeProgress)} overlay=${viewLabel(originalOverlay)} horizontal=${viewLabel(it.horizontal)}")
                }
            }
            state.update()
        }

        private fun createProgressBar(context: Context): ProgressBar {
            val primary = context.colorFromAttribute(R.attr.colorPrimary)
            val gray = context.colorFromAttribute(R.attr.grayTextColor)
            val track = (gray and 0x00ffffff) or ((Color.alpha(gray) * 0.30f).toInt() shl 24)
            trace("COLORS screen=$screenLabel primary=${Integer.toHexString(primary)} unplayed=${Integer.toHexString(track)}")
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
        val horizontal: ProgressBar,
        val screenLabel: String
    ) {
        private val originalOverlayVisibility = overlay.visibility
        private var lastReport: String? = null

        fun update() {
            val visible = native.visibility == View.VISIBLE && native.max > 0 && native.progress > 0
            val amount = if (visible) (native.progress.toFloat() / native.max * 1000f).toInt().coerceIn(0, 1000) else 0
            if (horizontal.progress != amount) horizontal.progress = amount
            val target = if (visible) View.VISIBLE else View.GONE
            if (horizontal.visibility != target) horizontal.visibility = target
            val priorOverlayVisibility = overlay.visibility
            if (overlay.visibility != View.INVISIBLE) overlay.visibility = View.INVISIBLE
            val report = "screen=$screenLabel native=${native.javaClass.simpleName} nativeVis=${native.visibility} max=${native.max} progress=${native.progress} drawable=${native.progressDrawable?.javaClass?.simpleName} frame=${frame.width}x${frame.height} bar=${horizontal.width}x${horizontal.height} barVis=${horizontal.visibility} barProgress=${horizontal.progress} overlayBefore=$priorOverlayVisibility overlayAfter=${overlay.visibility}"
            if (lastReport != report) {
                Log.i(TAG, "BAR_UPDATE $report")
                lastReport = report
            }
        }

        fun restore() {
            Log.i(TAG, "BAR_RESTORE screen=$screenLabel progress=${native.progress} overlayOriginal=$originalOverlayVisibility overlayNow=${overlay.visibility}")
            if (horizontal.parent === frame) frame.removeView(horizontal)
            overlay.visibility = originalOverlayVisibility
        }
    }

    override fun onActivityResumed(activity: Activity) {
        trace("ACTIVITY_RESUMED name=${activity.javaClass.name} isMain=${activity is MainActivity}")
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
        const val TAG = "ElevateProgress"
        const val HOME_CLASS = "com.lagradost.cloudstream3.ui.home.HomeFragment"
        val DETAIL_CLASSES = setOf(
            "com.lagradost.cloudstream3.ui.result.ResultTrailerPlayer",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentTv"
        )
    }
}
