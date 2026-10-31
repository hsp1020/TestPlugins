package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.util.CoilUtils
import com.hsp1020.shared.NativePosterCardState
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * Honors original thumbnail proportions using the finished Coil result of the
 * already-bound poster. Does not fetch images, replace URLs or affect YouTube.
 */
internal class OriginalThumbnailRatioHook(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private val screens = mutableMapOf<Fragment, Screen>()
    private enum class Role { HOME_PARENT, SEARCH_PARENT, GRID, SEARCH_ROW, RESUME }

    private inner class Screen(val root: View) {
        private val watchers = mutableMapOf<RecyclerView, Watcher>()

        init {
            root.findViewById<RecyclerView>(R.id.home_master_recycler)
                ?.let { watch(it, Role.HOME_PARENT) }
            root.findViewById<RecyclerView>(R.id.search_master_recycler)
                ?.let { watch(it, Role.SEARCH_PARENT) }
            root.findViewById<RecyclerView>(R.id.quick_search_master_recycler)
                ?.let { watch(it, Role.SEARCH_PARENT) }
            root.findViewById<RecyclerView>(R.id.search_autofit_results)
                ?.let { watch(it, Role.GRID) }
            root.findViewById<RecyclerView>(R.id.quick_search_autofit_results)
                ?.let { watch(it, Role.GRID) }
            root.findViewById<RecyclerView>(R.id.home_watch_child_recyclerview)
                ?.let { watch(it, Role.RESUME) }
        }

        private fun watch(recycler: RecyclerView, role: Role) {
            if (recycler in watchers) return
            Watcher(recycler, role).also { watchers[recycler] = it; it.open() }
        }

        private fun unwatch(recycler: RecyclerView) {
            watchers.remove(recycler)?.close()
        }

        fun close() {
            watchers.values.toList().asReversed().forEach { it.close() }
            watchers.clear()
        }

        private inner class Watcher(
            private val recycler: RecyclerView, private val role: Role
        ) {
            private val isParent get() = role == Role.HOME_PARENT || role == Role.SEARCH_PARENT
            private val cards = IdentityHashMap<View, Card>()
            private val pending = IdentityHashMap<View, Card>()
            private var preDrawTree: ViewTreeObserver? = null
            private val imageReady = ViewTreeObserver.OnPreDrawListener {
                val ready = ArrayList<View>()
                val iterator = pending.entries.iterator()
                while (iterator.hasNext()) {
                    val (view, card) = iterator.next()
                    if (cards[view] !== card || view.parent !== recycler) {
                        iterator.remove()
                        continue
                    }
                    val result = CoilUtils.result(card.image)
                    if (result != null && result.request.data.toString() == card.posterUrl) {
                        iterator.remove()
                        ready.add(view)
                    }
                }
                if (pending.isEmpty()) clearPreDraw()
                ready.forEach(::apply)
                true
            }

            private fun clearPreDraw() {
                preDrawTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(imageReady)
                preDrawTree = null
            }

            private fun awaitImage(view: View, card: Card) {
                pending[view] = card
                if (preDrawTree == null) {
                    recycler.viewTreeObserver.takeIf { it.isAlive }?.let {
                        preDrawTree = it
                        it.addOnPreDrawListener(imageReady)
                    }
                }
            }

            private fun stopWaiting(view: View) {
                pending.remove(view)
                if (pending.isEmpty()) clearPreDraw()
            }

            private var observed: RecyclerView.Adapter<*>? = null
            private var scheduled = false
            private val adapterChanges = object : RecyclerView.AdapterDataObserver() {
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
                    if (!isParent) view.post {
                        if (watchers[recycler] === this@Watcher && view.parent === recycler) {
                            apply(view)
                        }
                    }
                }
                override fun onChildViewDetachedFromWindow(view: View) {
                    if (isParent) {
                        view.findViewById<RecyclerView>(R.id.home_child_recyclerview)
                            ?.let(::unwatch)
                        if (role == Role.HOME_PARENT) {
                            view.findViewById<RecyclerView>(R.id.home_watch_child_recyclerview)
                                ?.let(::unwatch)
                        }
                    } else restore(view)
                }
            }
            private val layout = View.OnLayoutChangeListener {
                _, _, _, _, _, _, _, _, _ -> schedule()
            }

            fun open() {
                recycler.addOnChildAttachStateChangeListener(children)
                recycler.addOnLayoutChangeListener(layout)
                syncAdapter()
                scan()
            }

            fun close() {
                recycler.removeOnChildAttachStateChangeListener(children)
                recycler.removeOnLayoutChangeListener(layout)
                observed?.unregisterAdapterDataObserver(adapterChanges)
                observed = null
                pending.clear()
                clearPreDraw()
                cards.keys.toList().forEach(::restore)
                scheduled = false
            }

            private fun syncAdapter() {
                val current = recycler.adapter
                if (observed === current) return
                observed?.unregisterAdapterDataObserver(adapterChanges)
                observed = current
                current?.registerAdapterDataObserver(adapterChanges)
            }

            private fun schedule() {
                if (scheduled) return
                scheduled = true
                recycler.post {
                    if (!scheduled || watchers[recycler] !== this@Watcher) return@post
                    scheduled = false
                    syncAdapter()
                    scan()
                }
            }

            private fun scan() {
                for (i in 0 until recycler.childCount) apply(recycler.getChildAt(i))
            }

            private fun apply(view: View) {
                if (isParent) {
                    view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let {
                        if (role == Role.SEARCH_PARENT) watch(it, Role.SEARCH_ROW)
                    }
                    if (role == Role.HOME_PARENT) {
                        view.findViewById<RecyclerView>(R.id.home_watch_child_recyclerview)
                            ?.let { watch(it, Role.RESUME) }
                    }
                    return
                }

                val adapter = recycler.adapter
                if (adapter !is SearchAdapter && adapter !is HomeChildItemAdapter) {
                    restore(view)
                    return
                }
                val position = recycler.getChildViewHolder(view).bindingAdapterPosition
                val item = if (position == RecyclerView.NO_POSITION) null
                    else adapter.getItemOrNull(position) as? SearchResponse
                if (item == null || item.apiName == "YouTube" ||
                    item.posterUrl.isNullOrBlank()) {
                    restore(view)
                    return
                }

                val image = view.findViewById<ImageView>(R.id.imageView) ?: return restore(view)
                val holder = view.findViewById<View>(R.id.background_card) ?: return restore(view)
                val title = view.findViewById<TextView>(R.id.imageText) ?: return restore(view)
                val card = cards[view]?.takeIf {
                    it.image === image && it.holder === holder && it.title === title
                } ?: run {
                    restore(view)
                    Card(title, holder, image).also { cards[view] = it }
                }
                val posterUrl = item.posterUrl ?: return restore(view)
                val key = item.apiName + ":" + item.url + ":" + posterUrl
                if (card.identity != key) {
                    stopWaiting(view)
                    card.layout.restoreSize()
                    card.identity = key
                    card.posterUrl = posterUrl
                }

                // The drawable may be a placeholder or from a recycled card.
                // A matching finished Coil request is the authoritative image.
                val result = CoilUtils.result(image)
                if (result == null || result.request.data.toString() != posterUrl) {
                    card.layout.restoreSize()
                    awaitImage(view, card)
                    return
                }
                stopWaiting(view)
                if (result is ErrorResult) {
                    card.layout.restoreSize()
                    return
                }
                val imageResult = result as? SuccessResult ?: return
                val width = imageResult.image.width
                val height = imageResult.image.height
                if (width <= 0 || height <= 0 || width <= height) {
                    card.layout.restoreSize()
                    return
                }

                val cardWidth = when (role) {
                    Role.SEARCH_ROW -> HomeChildItemAdapter.maxPosterSize.takeIf { it > 0 }
                        ?: dp(180)
                    Role.RESUME -> card.layout.originalCardWidth.takeIf { it > 0 }
                        ?: holder.width.takeIf { it > 0 } ?: return
                    else -> (recycler as? AutofitRecyclerView)?.itemWidth
                        ?.minus(dp(4))?.takeIf { it > 0 }
                        ?: holder.width.takeIf { it > 0 } ?: return
                }
                val cardHeight = (cardWidth.toFloat() * height / width).roundToInt()
                    .coerceAtLeast(1)
                if (role == Role.GRID || adapter is SearchAdapter) {
                    card.layout.setCardWidth(cardWidth)
                    card.layout.setImageHeight(cardHeight)
                } else {
                    card.layout.setCardSize(cardWidth, cardHeight)
                }
                if (role != Role.RESUME && !holder.contains(title)) {
                    card.layout.setTitleWidth(cardWidth)
                }
            }

            private fun dp(value: Int): Int =
                (value * recycler.resources.displayMetrics.density + 0.5f).toInt()

            private fun restore(view: View) {
                stopWaiting(view)
                cards.remove(view)?.layout?.restoreSize()
            }
        }
    }

    private class Card(
        val title: TextView,
        val holder: View,
        val image: ImageView
    ) {
        val layout = NativePosterCardState(title, holder, image)
        var identity: String? = null
        var posterUrl: String = ""
    }

    private fun View.contains(child: View): Boolean {
        var ancestor = child.parent
        while (ancestor is View) {
            if (ancestor === this) return true
            ancestor = ancestor.parent
        }
        return false
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            if (fragment.javaClass.name in supportedScreens) view.post {
                if (fragment.view === view) watch(fragment, view)
            }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            screens.remove(fragment)?.close()
        }
    }

    private fun watch(fragment: Fragment, root: View) {
        if (fragment.javaClass.name !in supportedScreens ||
            screens[fragment]?.root === root) return
        screens.remove(fragment)?.close()
        screens[fragment] = Screen(root)
    }

    private fun bind(activity: FragmentActivity) {
        if (host === activity) return
        release()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { fragment ->
                fragment.view?.let { watch(fragment, it) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun release() {
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        screens.values.forEach { it.close() }
        screens.clear()
        host = null
    }

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::bind)
    }

    fun stop() {
        app.unregisterActivityLifecycleCallbacks(this)
        release()
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity) bind(activity)
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) bind(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) release()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        val supportedScreens = setOf(
            "com.lagradost.cloudstream3.ui.home.HomeFragment",
            "com.lagradost.cloudstream3.ui.search.SearchFragment",
            "com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment"
        )
    }
}
