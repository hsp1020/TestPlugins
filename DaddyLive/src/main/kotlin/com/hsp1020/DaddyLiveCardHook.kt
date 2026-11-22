package com.hsp1020

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import java.util.IdentityHashMap

/** Apply DaddyLive's four-line titles to native Home and Search cards only. */
internal class DaddyLiveCardHook(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private var host: FragmentActivity? = null
    private var focusTree: ViewTreeObserver? = null
    private val screens = mutableMapOf<Fragment, Screen>()
    private val dialogs = mutableMapOf<View, Screen>()

    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        main.post { discoverDialogs() }
    }
    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, state: Bundle?) {
            if (fragment.javaClass.name !in SCREENS) return
            screens.remove(fragment)?.close()
            screens[fragment] = Screen(view)
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            screens.remove(fragment)?.close()
        }
    }

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        app.unregisterActivityLifecycleCallbacks(this)
        release()
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        release()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        activity.window.decorView.viewTreeObserver.also {
            focusTree = it
            if (it.isAlive) it.addOnWindowFocusChangeListener(focusListener)
        }
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                if (fragment.javaClass.name in SCREENS) {
                    fragment.view?.let { screens[fragment] = Screen(it) }
                }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
        discoverDialogs()
    }

    private fun release() {
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree = null
        dialogs.values.forEach { it.close() }
        dialogs.clear()
        screens.values.forEach { it.close() }
        screens.clear()
        host = null
    }

    private fun discoverDialogs() {
        val activity = host ?: return
        val windows = if (Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews() else emptyList()
        val active = windows.filter { window ->
            window.isAttachedToWindow && owner(window.context) === activity &&
                window.findViewById<RecyclerView>(R.id.home_expanded_recycler) != null
        }.toSet()
        dialogs.keys.toList().filter { it !in active }.forEach { window ->
            dialogs.remove(window)?.close()
        }
        active.forEach { window ->
            if (!dialogs.containsKey(window)) dialogs[window] = Screen(window)
        }
    }

    private fun owner(context: Context): Activity? {
        var current = context
        repeat(12) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

    private inner class Screen(root: View) {
        private val watchers = IdentityHashMap<RecyclerView, Watcher>()

        init {
            listOf(R.id.home_master_recycler, R.id.search_master_recycler, R.id.quick_search_master_recycler)
                .forEach { id -> root.findViewById<RecyclerView>(id)?.let { watch(it, true) } }
            listOf(R.id.search_autofit_results, R.id.quick_search_autofit_results, R.id.home_expanded_recycler)
                .forEach { id -> root.findViewById<RecyclerView>(id)?.let { watch(it, false) } }
        }

        private fun watch(list: RecyclerView, parent: Boolean) {
            if (watchers.containsKey(list)) return
            Watcher(list, parent).also {
                watchers[list] = it
                it.open()
            }
        }

        private fun unwatch(list: RecyclerView) {
            watchers.remove(list)?.close()
        }

        fun close() {
            watchers.values.toList().forEach { it.close() }
            watchers.clear()
        }

        private inner class Watcher(private val list: RecyclerView, private val parent: Boolean) {
            private val originals = IdentityHashMap<View, OriginalTitle>()
            private var observed: RecyclerView.Adapter<*>? = null
            private var scheduled = false
            private var remeasurePosted = false
            private val changes = object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = schedule()
                override fun onItemRangeChanged(start: Int, count: Int) = schedule()
                override fun onItemRangeInserted(start: Int, count: Int) = schedule()
                override fun onItemRangeRemoved(start: Int, count: Int) = schedule()
                override fun onItemRangeMoved(from: Int, to: Int, count: Int) = schedule()
            }
            private val children = object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    syncAdapter()
                    apply(view)
                    if (!parent) view.post {
                        if (view.parent === list && watchers[list] === this@Watcher) apply(view)
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    if (parent) view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let(::unwatch)
                    else restore(view)
                }
            }

            fun open() {
                list.addOnChildAttachStateChangeListener(children)
                syncAdapter()
                scan()
            }

            fun close() {
                list.removeOnChildAttachStateChangeListener(children)
                observed?.unregisterAdapterDataObserver(changes)
                observed = null
                originals.keys.toList().forEach(::restore)
                originals.clear()
                scheduled = false
                remeasurePosted = false
            }

            private fun syncAdapter() {
                val current = list.adapter
                if (observed === current) return
                observed?.unregisterAdapterDataObserver(changes)
                observed = current
                current?.registerAdapterDataObserver(changes)
            }

            private fun schedule() {
                if (scheduled) return
                scheduled = true
                list.post {
                    if (!scheduled || watchers[list] !== this@Watcher) return@post
                    scheduled = false
                    syncAdapter()
                    scan()
                }
            }

            private fun scan() {
                for (index in 0 until list.childCount) apply(list.getChildAt(index))
            }

            private fun apply(view: View) {
                if (parent) {
                    view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let { watch(it, false) }
                    return
                }
                val adapter = list.adapter as? BaseAdapter<*, *> ?: return restore(view)
                if (adapter !is SearchAdapter && adapter !is HomeChildItemAdapter) return restore(view)
                val position = list.getChildAdapterPosition(view)
                val item = if (position == RecyclerView.NO_POSITION) null
                    else adapter.getItemOrNull(position) as? SearchResponse
                if (item?.apiName != "DaddyLive") return restore(view)
                val title = view.findViewById<TextView>(R.id.imageText) ?: return
                val image = view.findViewById<ImageView>(R.id.imageView)
                val original = originals[view] ?: OriginalTitle(
                    title, title.maxLines, title.minLines,
                    Build.VERSION.SDK_INT >= 29 && title.isSingleLine,
                    image, image?.scaleType
                ).also { originals[view] = it }
                if (item.posterUrl == DaddyLiveScheduleProvider.cardPosterUrl) {
                    // The existing portrait logo is transparent; preserve the full LIVE text.
                    if (image != null && image.scaleType != ImageView.ScaleType.FIT_CENTER) {
                        image.scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                } else original.restoreImageScale()
                var changed = false
                if (Build.VERSION.SDK_INT >= 29 && title.isSingleLine) {
                    title.setSingleLine(false)
                    changed = true
                }
                if (title.maxLines != 4) {
                    title.maxLines = 4
                    changed = true
                }
                // Reserve four lines in the native wrap_content carousel row.
                // Otherwise the measured one-line row clips the remaining text.
                if (title.minLines != 4) {
                    title.minLines = 4
                    changed = true
                }
                if (changed) {
                    view.requestLayout()
                    list.requestLayout()
                    (list.parent as? View)?.requestLayout()
                    remeasureHomeRow()
                }
            }

            // Like YouTube's Home-card correction: discard RecyclerView's cached
            // two-line row measurement after expanding a DaddyLive title.
            private fun remeasureHomeRow() {
                if (parent || list.adapter !is HomeChildItemAdapter || remeasurePosted) return
                remeasurePosted = true
                list.post {
                    remeasurePosted = false
                    if (watchers[list] !== this@Watcher || !list.isAttachedToWindow) return@post
                    val adapter = list.adapter as? HomeChildItemAdapter ?: return@post
                    for (i in 0 until list.childCount) {
                        val child = list.getChildAt(i)
                        val position = list.getChildAdapterPosition(child)
                        if (position != RecyclerView.NO_POSITION &&
                            adapter.getItemOrNull(position)?.apiName == "DaddyLive") {
                            child.forceLayout()
                        }
                    }
                    list.forceLayout()
                    (list.parent as? View)?.forceLayout()
                    list.parent?.requestLayout()
                    list.requestLayout()
                }
            }

            private fun restore(view: View) {
                originals.remove(view)?.let {
                    it.restore()
                    view.requestLayout()
                }
            }
        }
    }

    private class OriginalTitle(
        private val title: TextView,
        private val max: Int,
        private val min: Int,
        private val singleLine: Boolean,
        private val image: ImageView?,
        private val imageScale: ImageView.ScaleType?
    ) {
        fun restoreImageScale() {
            if (image != null && imageScale != null && image.scaleType != imageScale) {
                image.scaleType = imageScale
            }
        }

        fun restore() {
            title.maxLines = max
            title.minLines = min
            if (singleLine) title.setSingleLine(true)
            restoreImageScale()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) {
            use(activity)
            discoverDialogs()
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) release()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private companion object {
        val SCREENS = setOf(
            "com.lagradost.cloudstream3.ui.home.HomeFragment",
            "com.lagradost.cloudstream3.ui.search.SearchFragment",
            "com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment"
        )
    }
}
