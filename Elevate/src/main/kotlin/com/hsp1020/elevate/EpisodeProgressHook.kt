package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
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
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.NEXT_WATCH_EPISODE_PERCENTAGE
import com.lagradost.cloudstream3.ui.result.EpisodeAdapter
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getVideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute

internal class EpisodeProgressHook(
    private val application: Application,
    private var progressEnabled: Boolean,
    private var blurEnabled: Boolean
) : Application.ActivityLifecycleCallbacks {
    private val activities = LinkedHashSet<FragmentActivity>()
    private val screens = mutableMapOf<Fragment, Screen>()
    private var knownProviderCount = -1
    private var youtubeProviders = emptySet<String>()

    private fun isYoutubeProvider(apiName: String): Boolean {
        if (apiName.contains("youtube", ignoreCase = true)) return true
        val providers = APIHolder.allProviders
        if (knownProviderCount != providers.size) {
            youtubeProviders = providers.asSequence()
                .filter { it.sourcePlugin?.contains("youtube", ignoreCase = true) == true }
                .map { it.name }.toSet()
            knownProviderCount = providers.size
        }
        return apiName in youtubeProviders
    }

    fun setFeatures(progress: Boolean, blur: Boolean) {
        if (progressEnabled == progress && blurEnabled == blur) return
        progressEnabled = progress
        blurEnabled = blur
        screens.values.forEach(Screen::onResume)
    }

    private val callback = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            bind(fragment, view)
        }

        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            screens[fragment]?.onResume()
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
        if (!activities.add(next)) return
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(callback, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                fragment.view?.let { bind(fragment, it) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
    }

    private fun release(activity: FragmentActivity? = null) {
        val targets = activity?.let(::listOf) ?: activities.toList()
        targets.forEach { it.supportFragmentManager.unregisterFragmentLifecycleCallbacks(callback) }
        activities.removeAll(targets.toSet())
        val removed = screens.filter { (_, screen) -> activity == null || screen.fragment.activity === activity }
        removed.forEach { (fragment, screen) -> screen.stop(); screens.remove(fragment) }
    }

    private fun bind(fragment: Fragment, root: View) {
        val type = fragment.javaClass.name
        if (type !in DETAIL_CLASSES && fragment !is GeneratorPlayer) return
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
        private val isPlayer = fragment is GeneratorPlayer
        private val playerOverlay =
            if (isPlayer) root.findViewById<View>(R.id.player_episode_overlay) else null
        private var overlayWasVisible = false
        private fun listVisible() = !isPlayer || playerOverlay?.isShown == true
        private var list: RecyclerView? = null
        private var adapter: RecyclerView.Adapter<*>? = null
        private var refreshTree: ViewTreeObserver? = null
        private var fullRefreshPending = false
        private val dirtyRows = LinkedHashSet<View>()
        private val rows = mutableMapOf<View, RowProgress>()
        private val blurredPosters = mutableMapOf<View, ImageView>()
        private val blurEffect by lazy {
            val radius = 18f * root.resources.displayMetrics.density
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        }
        private val primaryColor by lazy { root.context.colorFromAttribute(R.attr.colorPrimary) }
        private val rowHeight by lazy {
            (3f * root.resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1)
        }
        private val dataObserver = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = scheduleRefresh()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) =
                scheduleRange(positionStart, itemCount)
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
                scheduleRange(positionStart, itemCount)
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = scheduleRefresh()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = scheduleRefresh()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = scheduleRefresh()
        }

        private val children = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                // Adapter inserts/layout are already on the main thread.
                // Never allocate overlays from RecyclerView's layout traversal.
                scheduleRow(view)
            }
            override fun onChildViewDetachedFromWindow(view: View) {
                dirtyRows.remove(view)
                rows.remove(view)?.restore()
                clearBlur(view)
            }
        }
        private val listLayout = View.OnLayoutChangeListener { _, left, top, right, bottom,
                                                               oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)
                scheduleRefresh()
        }
        private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener {
            syncList()
            if (isPlayer) {
                val visible = listVisible()
                if (visible && !overlayWasVisible) scheduleRefresh()
                overlayWasVisible = visible
            }
        }
        // RecyclerView handles pending adapter changes during layout, before pre-draw.
        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            cancelRefresh()
            val recycler = list
            if (recycler != null && fragment.view === root && fragment.isResumed && listVisible()) {
                if (fullRefreshPending) {
                    fullRefreshPending = false
                    dirtyRows.clear()
                    for (i in 0 until recycler.childCount) refreshRow(recycler.getChildAt(i))
                } else {
                    val changed = dirtyRows.toList()
                    dirtyRows.clear()
                    changed.forEach { row ->
                        if (row.parent === recycler) refreshRow(row)
                    }
                }
            } else {
                dirtyRows.clear()
                fullRefreshPending = false
            }
            true
        }

        fun onResume() {
            syncList()
            scheduleRefresh()
        }

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
            val target = root.findViewById<RecyclerView>(
                if (isPlayer) R.id.player_episode_list else R.id.result_episodes
            )
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
            cancelRefresh()
            adapter?.unregisterAdapterDataObserver(dataObserver)
            adapter = null
            list?.removeOnChildAttachStateChangeListener(children)
            list?.removeOnLayoutChangeListener(listLayout)
            rows.values.toList().forEach(RowProgress::restore)
            rows.clear()
            blurredPosters.keys.toList().forEach(::clearBlur)
            dirtyRows.clear()
            fullRefreshPending = false
            list = null
        }

        private fun scheduleRange(start: Int, count: Int) {
            if (count <= 0 || fullRefreshPending) return
            val recycler = list ?: return
            val end = start.toLong() + count
            for (i in 0 until recycler.childCount) {
                val child = recycler.getChildAt(i)
                val position = recycler.getChildViewHolder(child).bindingAdapterPosition
                if (position >= start && position.toLong() < end) dirtyRows.add(child)
            }
            if (dirtyRows.isNotEmpty()) postRefresh()
        }

        private fun scheduleRow(row: View) {
            if (!fullRefreshPending) dirtyRows.add(row)
            postRefresh()
        }

        private fun scheduleRefresh() {
            fullRefreshPending = true
            dirtyRows.clear()
            postRefresh()
        }

        private fun postRefresh() {
            if (!fragment.isResumed || !listVisible()) return // Update only visible episode lists.
            val observer = list?.viewTreeObserver ?: return
            if (!observer.isAlive) return
            if (refreshTree === observer) return
            cancelRefresh()
            refreshTree = observer
            observer.addOnPreDrawListener(preDraw)
        }

        private fun cancelRefresh() {
            val previous = refreshTree ?: return
            refreshTree = null
            if (previous.isAlive) previous.removeOnPreDrawListener(preDraw)
            // A detached view's observer may have been merged into the active one.
            val active = list?.viewTreeObserver
            if (active !== previous && active?.isAlive == true) {
                active.removeOnPreDrawListener(preDraw)
            }
        }

        private fun clearBlur(row: View) {
            val poster = blurredPosters.remove(row) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) poster.setRenderEffect(null)
        }

        private fun updateBlur(row: View, poster: ImageView?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !blurEnabled || poster == null) {
                clearBlur(row)
                return
            }
            val recycler = list
            val adapter = recycler?.adapter as? EpisodeAdapter
            val adapterPosition = recycler?.getChildViewHolder(row)?.bindingAdapterPosition
                ?: RecyclerView.NO_POSITION
            val episode = if (adapter == null || adapterPosition == RecyclerView.NO_POSITION) null
                else adapter.getItemOrNull(adapterPosition - adapter.headers)
            val shouldBlur = episode != null && !episode.poster.isNullOrBlank() &&
                !isYoutubeProvider(episode.apiName) && run {
                val saved = getViewPos(episode.id)
                val duration = saved?.duration?.takeIf { it > 0L } ?: episode.duration
                val position = saved?.position ?: episode.position
                val watched = getVideoWatchState(episode.id) == VideoWatchState.Watched ||
                    episode.videoWatchState == VideoWatchState.Watched ||
                    (duration > 0L && position.toDouble() / duration >=
                        NEXT_WATCH_EPISODE_PERCENTAGE / 100.0)
                !watched
            }
            if (!shouldBlur) {
                clearBlur(row)
            } else if (blurredPosters[row] !== poster) {
                clearBlur(row)
                poster.setRenderEffect(blurEffect)
                blurredPosters[row] = poster
            }
        }

        private fun refreshRow(row: View) {
            val nativeProgress = row.findViewById<ProgressBar>(R.id.episode_progress)
            val poster = row.findViewById<ImageView>(R.id.episode_poster)
            updateBlur(row, poster)
            if (!progressEnabled) {
                rows.remove(row)?.restore()
                return
            }
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
                }
            }
            // Keep the latest horizontal thumbnail progress design.
            // Without update() the newly created SlimProgressView remains GONE
            // and CloudStream's original circular overlay stays visible.
            state.update()

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
        if (activity is MainActivity || PipLibraryNavigation.isPlayerActivity(activity)) {
            (activity as? FragmentActivity)?.let(::use)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is FragmentActivity && activity in activities) release(activity)
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
