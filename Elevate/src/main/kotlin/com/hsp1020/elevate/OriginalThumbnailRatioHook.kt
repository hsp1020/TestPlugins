package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Adapt only bound cards, using their already-loaded artwork. No network or image decoding. */
internal class OriginalThumbnailRatioHook(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private val screens = mutableMapOf<Fragment, Screen>()
    private enum class Role { HOME_PARENT, SEARCH_PARENT, GRID, RESUME }

    private inner class Screen(val root: View) {
        private val watches = mutableMapOf<RecyclerView, Watch>()
        init {
            root.findViewById<RecyclerView>(R.id.home_master_recycler)?.let { watch(it, Role.HOME_PARENT) }
            root.findViewById<RecyclerView>(R.id.search_master_recycler)?.let { watch(it, Role.SEARCH_PARENT) }
            root.findViewById<RecyclerView>(R.id.quick_search_master_recycler)?.let { watch(it, Role.SEARCH_PARENT) }
            root.findViewById<RecyclerView>(R.id.search_autofit_results)?.let { watch(it, Role.GRID) }
            root.findViewById<RecyclerView>(R.id.quick_search_autofit_results)?.let { watch(it, Role.GRID) }
            root.findViewById<RecyclerView>(R.id.home_watch_child_recyclerview)?.let { watch(it, Role.RESUME) }
        }
        private fun watch(recycler: RecyclerView, role: Role) {
            if (recycler !in watches) Watch(recycler, role).also { watches[recycler] = it; it.open() }
        }
        private fun unwatch(recycler: RecyclerView) { watches.remove(recycler)?.close() }
        fun close() {
            watches.values.toList().asReversed().forEach { it.close() }
            watches.clear()
        }

        private inner class Watch(private val recycler: RecyclerView, val role: Role) {
            private val parent get() = role == Role.HOME_PARENT || role == Role.SEARCH_PARENT
            private val cards = IdentityHashMap<View, Card>()
            private var adapter: RecyclerView.Adapter<*>? = null
            private var queued = false
            private val changes = object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = schedule()
                override fun onItemRangeChanged(start: Int, count: Int) = schedule()
                override fun onItemRangeInserted(start: Int, count: Int) = schedule()
                override fun onItemRangeRemoved(start: Int, count: Int) = schedule()
                override fun onItemRangeMoved(from: Int, to: Int, count: Int) = schedule()
            }
            private val children = object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    apply(view)
                    if (!parent) view.post {
                        if (watches[recycler] === this@Watch && view.parent === recycler) apply(view)
                    }
                }
                override fun onChildViewDetachedFromWindow(view: View) {
                    if (role == Role.SEARCH_PARENT) {
                        view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let(::unwatch)
                    } else if (role == Role.HOME_PARENT) {
                        view.findViewById<RecyclerView>(R.id.home_watch_child_recyclerview)?.let(::unwatch)
                    } else restore(view)
                }
            }
            private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedule() }
            fun open() {
                recycler.addOnChildAttachStateChangeListener(children)
                recycler.addOnLayoutChangeListener(layout)
                scan()
            }
            fun close() {
                recycler.removeOnChildAttachStateChangeListener(children)
                recycler.removeOnLayoutChangeListener(layout)
                adapter?.unregisterAdapterDataObserver(changes)
                adapter = null
                cards.keys.toList().forEach(::restore)
                queued = false
            }
            private fun schedule() {
                if (queued) return
                queued = true
                recycler.post {
                    if (!queued || watches[recycler] !== this@Watch) return@post
                    queued = false
                    scan()
                }
            }
            private fun scan() {
                val next = recycler.adapter
                if (adapter !== next) {
                    adapter?.unregisterAdapterDataObserver(changes)
                    adapter = next
                    next?.registerAdapterDataObserver(changes)
                }
                for (i in 0 until recycler.childCount) apply(recycler.getChildAt(i))
            }
            private fun apply(view: View) {
                if (parent) {
                    if (role == Role.SEARCH_PARENT)
                        view.findViewById<RecyclerView>(R.id.home_child_recyclerview)
                            ?.let { watch(it, Role.GRID) }
                    else
                        view.findViewById<RecyclerView>(R.id.home_watch_child_recyclerview)
                            ?.let { watch(it, Role.RESUME) }
                    return
                }
                val index = recycler.getChildAdapterPosition(view)
                if (index == RecyclerView.NO_POSITION) return restore(view)
                val item: SearchResponse = when (role) {
                    Role.GRID -> (recycler.adapter as? SearchAdapter)?.getItemOrNull(index)
                    Role.RESUME -> (recycler.adapter as? HomeChildItemAdapter)?.getItemOrNull(index)
                    else -> null
                } ?: return restore(view)
                if (item.apiName == "YouTube" || item.posterUrl.isNullOrBlank()) return restore(view)
                val image = view.findViewById<ImageView>(R.id.imageView) ?: return restore(view)
                val holder = view.findViewById<View>(R.id.background_card) ?: return restore(view)
                val state = cards[view]?.takeIf { it.image === image && it.holder === holder }
                    ?: run {
                        restore(view)
                        Card(image, holder, role == Role.RESUME).also { card ->
                            cards[view] = card
                            card.listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                                if (cards[view] === card) apply(view)
                            }.also(image::addOnLayoutChangeListener)
                        }
                    }
                val identity = item.apiName + ":" + item.url + ":" + item.posterUrl
                if (state.identity != identity) {
                    state.restore()
                    state.identity = identity
                }
                val drawable = image.drawable ?: return state.restore()
                val w = drawable.intrinsicWidth
                val h = drawable.intrinsicHeight
                if (w <= 0 || h <= 0) return state.restore()
                val ratio = w.toFloat() / h
                val width = if (role == Role.RESUME)
                    HomeChildItemAdapter.maxPosterSize.takeIf { it > 0 }
                        ?: (180f * recycler.resources.displayMetrics.density).roundToInt()
                else (recycler as? AutofitRecyclerView)?.itemWidth
                    ?.minus((4f * recycler.resources.displayMetrics.density).roundToInt())
                    ?.takeIf { it > 0 } ?: holder.width
                if (width > 0) state.landscape(width, (width / ratio).roundToInt())
            }
            private fun restore(view: View) {
                cards.remove(view)?.let {
                    it.listener?.let(it.image::removeOnLayoutChangeListener)
                    it.restore()
                }
            }
        }
    }

    private class Card(val image: ImageView, val holder: View, val resume: Boolean) {
        private val originalWidth = holder.layoutParams.width
        private val originalHeight = holder.layoutParams.height
        private val originalImageHeight = image.layoutParams.height
        var identity: String? = null
        var listener: View.OnLayoutChangeListener? = null
        fun landscape(width: Int, height: Int) = setSize(
            if (resume) width else image.layoutParams.width, height
        )
        fun restore() = setSize(
            if (resume) originalWidth else image.layoutParams.width,
            if (resume) originalHeight else originalImageHeight
        )
        private fun setSize(width: Int, height: Int) {
            val target = if (resume) holder else image
            val params = target.layoutParams
            if (params.width == width && params.height == height) return
            params.width = width
            params.height = height
            target.layoutParams = params
        }
    }

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            if (fragment.javaClass.name in supported) view.post {
                if (fragment.view === view) watch(fragment, view)
            }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            screens.remove(fragment)?.close()
        }
    }
    private fun watch(fragment: Fragment, root: View) {
        if (fragment.javaClass.name !in supported || screens[fragment]?.root === root) return
        screens.remove(fragment)?.close()
        screens[fragment] = Screen(root)
    }
    private fun bind(activity: FragmentActivity) {
        if (host === activity) return
        unbind()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { fragment ->
                fragment.view?.let { watch(fragment, it) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }
    private fun unbind() {
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
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
        unbind()
    }
    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity) bind(activity)
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) bind(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) unbind()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        val supported = setOf(
            "com.lagradost.cloudstream3.ui.home.HomeFragment",
            "com.lagradost.cloudstream3.ui.search.SearchFragment",
            "com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment"
        )
    }
}
