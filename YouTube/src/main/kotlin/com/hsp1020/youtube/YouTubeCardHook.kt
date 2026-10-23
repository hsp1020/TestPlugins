package com.hsp1020.youtube

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import java.util.IdentityHashMap
import kotlin.math.roundToInt

internal class YouTubeCardHook(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private val screens = mutableMapOf<Fragment, Screen>()
    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, view: View, state: Bundle?) {
            attach(f, view)
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            screens.remove(f)?.close()
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

    private fun release() {
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        screens.values.toList().forEach { it.close() }
        screens.clear()
        host = null
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        release()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                fragment.view?.let { attach(fragment, it) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun attach(fragment: Fragment, root: View) {
        if (fragment.javaClass.name !in SCREENS) return
        if (screens[fragment]?.root === root) return
        screens.remove(fragment)?.close()
        screens[fragment] = Screen(root)
    }

    private inner class Screen(val root: View) {
        private val watchers = mutableMapOf<RecyclerView, Watcher>()

        init {
            root.findViewById<RecyclerView>(R.id.home_master_recycler)?.let { watch(it, Role.HOME_PARENT) }
            root.findViewById<RecyclerView>(R.id.search_master_recycler)?.let { watch(it, Role.SEARCH_PARENT) }
            root.findViewById<RecyclerView>(R.id.quick_search_master_recycler)?.let { watch(it, Role.SEARCH_PARENT) }
            root.findViewById<RecyclerView>(R.id.search_autofit_results)?.let { watch(it, Role.SEARCH_GRID) }
            root.findViewById<RecyclerView>(R.id.quick_search_autofit_results)?.let { watch(it, Role.SEARCH_GRID) }
        }

        fun close() {
            watchers.values.toList().asReversed().forEach { it.close() }
            watchers.clear()
        }

        private fun watch(list: RecyclerView, role: Role) {
            if (watchers.containsKey(list)) return
            Watcher(list, role).also { watchers[list] = it; it.open() }
        }

        private fun unwatch(list: RecyclerView) {
            watchers.remove(list)?.close()
        }

        private inner class Watcher(private val list: RecyclerView, private val role: Role) {
            private val modified = IdentityHashMap<View, CardState>()
            private var observed: RecyclerView.Adapter<*>? = null
            private var posted = false

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
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    if (isParent) {
                        view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let(::unwatch)
                    } else {
                        restore(view)
                    }
                }
            }
            private val resized = View.OnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) schedule()
            }
            private val isParent get() = role == Role.HOME_PARENT || role == Role.SEARCH_PARENT

            fun open() {
                list.addOnChildAttachStateChangeListener(children)
                list.addOnLayoutChangeListener(resized)
                syncAdapter()
                scan()
            }

            fun close() {
                list.removeOnChildAttachStateChangeListener(children)
                list.removeOnLayoutChangeListener(resized)
                observed?.unregisterAdapterDataObserver(changes)
                observed = null
                modified.keys.toList().forEach(::restore)
                modified.clear()
                posted = false
            }

            private fun syncAdapter() {
                val current = list.adapter
                if (observed === current) return
                observed?.unregisterAdapterDataObserver(changes)
                observed = current
                current?.registerAdapterDataObserver(changes)
            }

            private fun schedule() {
                if (posted) return
                posted = true
                list.post {
                    if (!posted || watchers[list] !== this) return@post
                    posted = false
                    syncAdapter()
                    scan()
                }
            }

            private fun scan() {
                for (i in 0 until list.childCount) apply(list.getChildAt(i))
            }

            private fun apply(view: View) {
                if (isParent) {
                    view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let {
                        watch(it, if (role == Role.HOME_PARENT) Role.HOME_CARD else Role.SEARCH_CARD)
                    }
                    return
                }

                val adapter = list.adapter as? BaseAdapter<*, *> ?: return restore(view)
                if (adapter !is SearchAdapter && adapter !is HomeChildItemAdapter) return restore(view)
                val position = list.getChildViewHolder(view).bindingAdapterPosition
                val item = if (position == RecyclerView.NO_POSITION) null
                    else adapter.getItemOrNull(position) as? SearchResponse
                if (item?.apiName != "YouTube") return restore(view)
                val title = view.findViewById<TextView>(R.id.imageText) ?: return
                val card = view.findViewById<View>(R.id.background_card) ?: return
                val image = view.findViewById<ImageView>(R.id.imageView) ?: return
                val state = modified[view] ?: CardState(title, card, image).also { modified[view] = it }
                val expanded = !card.contains(title)
                val lines = if (expanded) 4 else 2
                if (title.maxLines != lines) title.maxLines = lines
                if (expanded && title.minLines != 1) title.minLines = 1

                val video = role != Role.HOME_CARD && item.url.contains("/watch?v=")
                if (!video) {
                    state.restoreSize()
                    return
                }

                if (role == Role.SEARCH_GRID) {
                    val baseWidth = card.width.takeIf { it > 0 }
                        ?: (list as? AutofitRecyclerView)?.itemWidth?.minus(dp(4))?.takeIf { it > 0 }
                        ?: return
                    val height = (baseWidth * 9f / 16f).roundToInt()
                    state.setImageHeight(height)
                } else {
                    val width = HomeChildItemAdapter.maxPosterSize.takeIf { it > 0 } ?: dp(180)
                    state.setCardSize(width, (width * 9f / 16f).roundToInt())
                }
            }

            private fun restore(view: View) {
                modified.remove(view)?.restore()
            }
        }
    }

    private class CardState(
        private val title: TextView,
        private val card: View,
        private val image: ImageView
    ) {
        private val max = title.maxLines
        private val min = title.minLines
        private val cardWidth = card.layoutParams.width
        private val cardHeight = card.layoutParams.height
        private val imageHeight = image.layoutParams.height
        private var changedCard = false
        private var changedImage = false

        fun setCardSize(width: Int, height: Int) {
            val params = card.layoutParams
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                card.layoutParams = params
            }
            changedCard = true
        }

        fun setImageHeight(height: Int) {
            val params = image.layoutParams
            if (params.height != height) {
                params.height = height
                image.layoutParams = params
            }
            changedImage = true
        }

        fun restoreSize() {
            if (changedCard) {
                val params = card.layoutParams
                if (params.width != cardWidth || params.height != cardHeight) {
                    params.width = cardWidth
                    params.height = cardHeight
                    card.layoutParams = params
                }
                changedCard = false
            }
            if (changedImage) {
                val params = image.layoutParams
                if (params.height != imageHeight) {
                    params.height = imageHeight
                    image.layoutParams = params
                }
                changedImage = false
            }
        }

        fun restore() {
            title.maxLines = max
            title.minLines = min
            restoreSize()
        }
    }

    private fun View.contains(other: View): Boolean {
        var parent = other.parent
        while (parent is View) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }

    private fun dp(value: Int): Int =
        (value * app.resources.displayMetrics.density + 0.5f).toInt()

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) release()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private enum class Role { HOME_PARENT, SEARCH_PARENT, HOME_CARD, SEARCH_CARD, SEARCH_GRID }

    private companion object {
        val SCREENS = setOf(
            "com.lagradost.cloudstream3.ui.home.HomeFragment",
            "com.lagradost.cloudstream3.ui.search.SearchFragment",
            "com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment"
        )
    }
}
