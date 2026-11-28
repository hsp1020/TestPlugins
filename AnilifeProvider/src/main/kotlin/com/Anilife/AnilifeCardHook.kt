package com.anilife

import android.app.Activity
import android.app.Application
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
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import coil3.request.SuccessResult
import coil3.util.CoilUtils
import java.util.IdentityHashMap
import kotlin.math.roundToInt

internal class AnilifeCardHook(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private var host: FragmentActivity? = null
    private val watchers = IdentityHashMap<RecyclerView, Watcher>()
    private var focusTree: ViewTreeObserver? = null
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        main.post { discoverDialog() }
    }
    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, saved: Bundle?) {
            if (f.javaClass.name == HOME) {
                v.findViewById<RecyclerView>(R.id.home_master_recycler)?.let { watch(it, true) }
            }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name == HOME) closeAll()
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
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree = null
        closeAll()
        host = null
    }
    private fun closeAll() {
        val closing = watchers.values.toList().asReversed()
        watchers.clear()
        closing.forEach { it.close() }
    }
    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        release()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        focusTree = activity.window.decorView.viewTreeObserver.also {
            if (it.isAlive) it.addOnWindowFocusChangeListener(focusListener)
        }
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { fragment ->
                if (fragment.javaClass.name == HOME) {
                    fragment.view?.findViewById<RecyclerView>(R.id.home_master_recycler)
                        ?.let { watch(it, true) }
                }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
        main.post { discoverDialog() }
    }
    private fun discoverDialog() {
        if (Build.VERSION.SDK_INT < 29 || host == null) return
        val active = WindowInspector.getGlobalWindowViews().mapNotNull { root ->
            root.findViewById<RecyclerView>(R.id.home_expanded_recycler)
                ?.takeIf { it.adapter is SearchAdapter }
        }
        watchers.keys.toList().filter {
            it.id == R.id.home_expanded_recycler && it !in active
        }.forEach { watchers.remove(it)?.close() }
        active.forEach { watch(it, false) }
    }
    private fun watch(list: RecyclerView, parent: Boolean) {
        if (!watchers.containsKey(list)) {
            Watcher(list, parent).also { watchers[list] = it; it.open() }
        }
    }
    private data class CardState(
        val card: View, val image: ImageView, val title: TextView,
        val originalWidth: Int, val originalHeight: Int,
        val originalImageHeight: Int, val originalTitleWidth: Int,
        var identity: String = "", var posterUrl: String = "", var applied: Boolean = false
    )
    private inner class Watcher(private val list: RecyclerView, private val parent: Boolean) {
        private val states = IdentityHashMap<View, CardState>()
        private val pending = IdentityHashMap<View, CardState>()
        private var preDrawTree: ViewTreeObserver? = null
        private val imageReady = ViewTreeObserver.OnPreDrawListener {
            val ready = ArrayList<View>()
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                val (view, state) = iterator.next()
                if (states[view] !== state || view.parent !== list) {
                    iterator.remove()
                    continue
                }
                val result = CoilUtils.result(state.image)
                if (result != null && result.request.data.toString() == state.posterUrl) {
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
        private var observed: RecyclerView.Adapter<*>? = null
        private var posted = false
        private var closed = false
        private val changes = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = schedule()
            override fun onItemRangeChanged(start: Int, count: Int) = schedule()
            override fun onItemRangeInserted(start: Int, count: Int) = schedule()
            override fun onItemRangeRemoved(start: Int, count: Int) = schedule()
            override fun onItemRangeMoved(from: Int, to: Int, count: Int) = schedule()
        }
        private val children = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) { apply(view) }
            override fun onChildViewDetachedFromWindow(view: View) {
                if (parent) {
                    view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let {
                        watchers.remove(it)?.close()
                    }
                } else restore(view)
            }
        }
        fun open() {
            list.addOnChildAttachStateChangeListener(children)
            sync()
            scan()
        }
        fun close() {
            if (closed) return
            closed = true
            posted = false
            list.removeOnChildAttachStateChangeListener(children)
            observed?.unregisterAdapterDataObserver(changes)
            observed = null
            pending.clear()
            clearPreDraw()
            states.keys.toList().forEach(::restore)
            if (parent) {
                for (i in 0 until list.childCount) {
                    list.getChildAt(i).findViewById<RecyclerView>(R.id.home_child_recyclerview)
                        ?.let { watchers.remove(it)?.close() }
                }
            }
        }
        private fun sync() {
            if (closed || observed === list.adapter) return
            observed?.unregisterAdapterDataObserver(changes)
            observed = list.adapter
            observed?.registerAdapterDataObserver(changes)
        }
        private fun schedule() {
            if (posted) return
            posted = true
            list.post {
                if (!posted || watchers[list] !== this) return@post
                posted = false
                scan()
            }
        }
        private fun scan() {
            sync()
            for (i in 0 until list.childCount) apply(list.getChildAt(i))
        }
        private fun apply(view: View) {
            if (parent) {
                view.findViewById<RecyclerView>(R.id.home_child_recyclerview)
                    ?.let { watch(it, false) }
                return
            }
            val adapter = list.adapter as? BaseAdapter<*, *> ?: return
            val position = list.getChildAdapterPosition(view)
            val item = if (position == RecyclerView.NO_POSITION) null
                else adapter.getItemOrNull(position) as? SearchResponse
            if (item?.apiName != "Anilife" || item.posterUrl.isNullOrBlank()) { restore(view); return }
            val state = states[view] ?: run {
                val card = view.findViewById<View>(R.id.background_card) ?: return
                val image = view.findViewById<ImageView>(R.id.imageView) ?: return
                val title = view.findViewById<TextView>(R.id.imageText) ?: return
                CardState(card, image, title, card.layoutParams.width,
                    card.layoutParams.height, image.layoutParams.height,
                    title.layoutParams.width).also { states[view] = it }
            }
            val posterUrl = item.posterUrl ?: return restore(view)
            val identity = item.url + "|" + posterUrl
            if (state.identity != identity) {
                disarm(view)
                restoreSize(state)
                state.identity = identity
                state.posterUrl = posterUrl
            }
            val result = CoilUtils.result(state.image)
            if (result == null || result.request.data.toString() != posterUrl) {
                restoreSize(state)
                pending[view] = state
                if (preDrawTree == null) {
                    list.viewTreeObserver.takeIf { it.isAlive }?.let {
                        preDrawTree = it
                        it.addOnPreDrawListener(imageReady)
                    }
                }
                return
            }
            disarm(view)
            val loaded = result as? SuccessResult ?: run { restoreSize(state); return }
            if (state.applied) return
            val w = loaded.image.width
            val h = loaded.image.height
            if (w <= 8 || h <= 8) return
            val landscape = w.toFloat() / h > 1.15f
            val width = if (list is AutofitRecyclerView) {
                (list.itemWidth - dp(4)).coerceAtLeast(1)
            } else if (landscape) HomeChildItemAdapter.maxPosterSize
            else HomeChildItemAdapter.minPosterSize
            if (width <= 0) return
            val height = if (list is AutofitRecyclerView) {
                (width / (if (landscape) 1.8f else 0.68f)).roundToInt().coerceAtLeast(1)
            } else if (landscape) HomeChildItemAdapter.minPosterSize
            else HomeChildItemAdapter.maxPosterSize
            if (list is AutofitRecyclerView) {
                state.card.layoutParams = state.card.layoutParams.apply { this.width = width }
                state.image.layoutParams = state.image.layoutParams.apply { this.height = height }
            } else {
                state.card.layoutParams = state.card.layoutParams.apply {
                    this.width = width
                    this.height = height
                }
                if (state.title.parent !== state.card) {
                    state.title.layoutParams = state.title.layoutParams.apply { this.width = width }
                }
            }
            state.applied = true
        }
        private fun disarm(view: View) {
            pending.remove(view)
            if (pending.isEmpty()) clearPreDraw()
        }
        private fun restoreSize(state: CardState) {
            if (!state.applied) return
            state.card.layoutParams = state.card.layoutParams.apply {
                width = state.originalWidth; height = state.originalHeight
            }
            state.image.layoutParams = state.image.layoutParams.apply {
                height = state.originalImageHeight
            }
            state.title.layoutParams = state.title.layoutParams.apply {
                width = state.originalTitleWidth
            }
            state.applied = false
        }
        private fun restore(view: View) {
            disarm(view)
            states.remove(view)?.let(::restoreSize)
        }
    }
    private fun dp(value: Int): Int =
        (value * app.resources.displayMetrics.density + 0.5f).toInt()

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) release()
    }
    override fun onActivityCreated(activity: Activity, saved: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object { const val HOME = "com.lagradost.cloudstream3.ui.home.HomeFragment" }
}
