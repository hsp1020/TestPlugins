package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import java.util.IdentityHashMap

/**
 * Repairs the first two visible cards per Home row after a provider switch.
 * Uses native parent/child adapter updates; no polling or image-cache clearing.
 */
internal class HomeRecyclerCacheGuard(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private data class CardStamp(val url: String?, val posterUrl: String?)
    private data class RowStamp(val size: Int, val firstTwo: List<CardStamp>, val last: String?)
    private data class ChildWatch(
        val adapter: RecyclerView.Adapter<*>,
        val observer: RecyclerView.AdapterDataObserver
    )

    private val main = Handler(Looper.getMainLooper())
    private var enabled = false
    private var started = false
    private var activity: FragmentActivity? = null
    private var model: HomeViewModel? = null
    private var home: Fragment? = null
    private var root: RecyclerView? = null
    private var provider: String? = null
    private var pendingProvider: String? = null
    private var loadingObserved = false
    private var expectedRows: Map<String, RowStamp>? = null
    private var generation = 0
    private var queued = false
    private val traced = HashSet<String>()
    private var observedParent: RecyclerView.Adapter<*>? = null
    private val observedChildren = LinkedHashMap<RecyclerView, ChildWatch>()
    private var watchingAttaches = false
    private val refreshPayload = Any()
    private val refreshed = IdentityHashMap<View, CardStamp>()
    private val parentRepairs = HashSet<RecyclerView>()
    private var drawTree: ViewTreeObserver? = null
    private var drawObserver: ViewTreeObserver.OnPreDrawListener? = null

    private fun traceOnce(event: String, detail: String) {
        if (traced.add("$generation:$event:$detail"))
            Log.i(LOG_TAG, "generation=$generation event=$event provider=$provider " +
                "pending=$pendingProvider $detail")
    }

    private val apiObserver = Observer<String> { next ->
        if (provider != null && provider != next) {
            disarm()
            refreshed.clear()
            parentRepairs.clear()
            generation++
            pendingProvider = next
            loadingObserved = false
            expectedRows = null
            queued = false
            traced.clear()
            traceOnce("provider-switch", "from=$provider to=$next")
            arm()
        }
        provider = next
    }

    private val pageObserver =
        Observer<Resource<Map<String, HomeViewModel.ExpandableHomepageList>>> { result ->
            val pending = pendingProvider ?: return@Observer
            when (result) {
                is Resource.Loading -> {
                    loadingObserved = true
                    expectedRows = null
                    traceOnce("page-loading", "status=waiting")
                }
                is Resource.Failure -> {
                    traceOnce("page-failure", "status=cancelled")
                    finish()
                }
                is Resource.Success -> {
                    if (!loadingObserved || model?.apiName?.value != pending) {
                        traceOnce("page-ignored", "reason=no-current-loading")
                        return@Observer
                    }
                    val rows = LinkedHashMap<String, RowStamp>()
                    result.value.values.forEach { entry ->
                        rows[entry.list.name] = stamp(entry.list)
                    }
                    expectedRows = rows
                    traceOnce("page-ready", "catalogs=" + rows.size + " mode=any-provider")
                    arm()
                    queueRefresh()
                }
            }
        }

    private fun onParentChanged() = queueRefresh()

    private val parentObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = onParentChanged()
        override fun onItemRangeChanged(p: Int, n: Int) = onParentChanged()
        override fun onItemRangeInserted(p: Int, n: Int) = onParentChanged()
        override fun onItemRangeRemoved(p: Int, n: Int) = onParentChanged()
        override fun onItemRangeMoved(from: Int, to: Int, n: Int) = onParentChanged()
    }

    private val parentAttaches = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) = queueRefresh()
        override fun onChildViewDetachedFromWindow(view: View) {
            view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let { child ->
                observedChildren.remove(child)?.let {
                    it.adapter.unregisterAdapterDataObserver(it.observer)
                }
                parentRepairs.remove(child)
            }
        }
    }

    private fun watchChild(recycler: RecyclerView) {
        val adapter = recycler.adapter ?: return
        if (observedChildren[recycler]?.adapter === adapter) return
        observedChildren.remove(recycler)?.let {
            it.adapter.unregisterAdapterDataObserver(it.observer)
        }
        val listener = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = queueRefresh()
            override fun onItemRangeChanged(p: Int, n: Int) = queueRefresh()
            override fun onItemRangeInserted(p: Int, n: Int) = queueRefresh()
            override fun onItemRangeRemoved(p: Int, n: Int) = queueRefresh()
            override fun onItemRangeMoved(from: Int, to: Int, n: Int) = queueRefresh()
        }
        adapter.registerAdapterDataObserver(listener)
        observedChildren[recycler] = ChildWatch(adapter, listener)
    }

    private fun arm() {
        val recycler = root ?: return
        if (pendingProvider == null) return
        if (!watchingAttaches) {
            recycler.addOnChildAttachStateChangeListener(parentAttaches)
            watchingAttaches = true
        }
        val adapter = recycler.adapter
        if (observedParent !== adapter) {
            observedParent?.unregisterAdapterDataObserver(parentObserver)
            observedParent = adapter
            adapter?.registerAdapterDataObserver(parentObserver)
        }
    }

    private fun disarm() {
        drawObserver?.let { if (drawTree?.isAlive == true) drawTree?.removeOnPreDrawListener(it) }
        drawObserver = null
        drawTree = null
        observedParent?.unregisterAdapterDataObserver(parentObserver)
        observedParent = null
        observedChildren.values.forEach {
            it.adapter.unregisterAdapterDataObserver(it.observer)
        }
        observedChildren.clear()
        if (watchingAttaches) {
            root?.removeOnChildAttachStateChangeListener(parentAttaches)
            watchingAttaches = false
        }
    }

    private fun finish() {
        disarm()
        refreshed.clear()
        parentRepairs.clear()
        pendingProvider = null
        loadingObserved = false
        expectedRows = null
        queued = false
    }

    private fun queueRefresh() {
        if (queued || expectedRows == null || pendingProvider == null) return
        val recycler = root ?: return
        val seq = generation
        queued = true
        recycler.post {
            if (enabled && seq == generation && recycler === root) {
                queued = false
                refreshVisible()
            }
        }
    }

    private fun stamp(list: com.lagradost.cloudstream3.HomePageList): RowStamp {
        val cards = list.list
        return RowStamp(
            cards.size,
            cards.take(2).map { CardStamp(it.url, it.posterUrl) },
            cards.lastOrNull()?.url
        )
    }

    /** Check again after native RecyclerView has had a chance to bind its updates. */
    private fun afterNextDraw() {
        if (drawObserver != null) return
        val recycler = root ?: return
        val tree = recycler.viewTreeObserver
        if (!tree.isAlive) {
            recycler.post { queueRefresh() }
            return
        }
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (tree.isAlive) tree.removeOnPreDrawListener(this)
                if (drawObserver === this) {
                    drawObserver = null
                    drawTree = null
                    queueRefresh()
                }
                return true
            }
        }
        drawTree = tree
        drawObserver = listener
        tree.addOnPreDrawListener(listener)
    }

    private fun refreshVisible() {
        if (drawObserver != null) return
        val expected = expectedRows ?: return
        val recycler = root ?: return
        arm()
        val parent = recycler.adapter as? ParentItemAdapter ?: return
        if (recycler.isComputingLayout) {
            recycler.post { queueRefresh() }
            return
        }
        // The native parent and child differ may commit on separate frames.
        if (parent.itemCount - parent.headers != expected.size) return
        for (i in 0 until expected.size) {
            val item = parent.getItemOrNull(i) ?: return
            if (expected[item.list.name] != stamp(item.list)) return
        }

        var visibleRows = 0
        var waitingForChild = false
        var rebound = 0
        for (i in 0 until recycler.childCount) {
            val row = recycler.getChildAt(i)
            val child = row.findViewById<RecyclerView>(R.id.home_child_recyclerview)
                ?: continue
            val holder = recycler.findContainingViewHolder(row) ?: continue
            val parentPosition = holder.bindingAdapterPosition
            val rowModel = parent.getItemOrNull(parentPosition - parent.headers) ?: continue
            val adapter = child.adapter as? HomeChildItemAdapter ?: continue
            visibleRows++
            watchChild(child)
            if (child.isComputingLayout) {
                child.post { queueRefresh() }
                return
            }
            val title = row.findViewById<TextView>(R.id.home_child_more_info)?.text?.toString()
            val headerMismatch = title != null && title != rowModel.list.name
            val childMismatch = (0 until 2).any { position ->
                val current = adapter.getItemOrNull(position)
                val wanted = rowModel.list.list.getOrNull(position)
                current?.url != wanted?.url || current?.posterUrl != wanted?.posterUrl
            }
            if (headerMismatch || childMismatch) {
                waitingForChild = true
                if (parentPosition != RecyclerView.NO_POSITION && parentRepairs.add(child)) {
                    // Native parent payload update submits its current list to the child.
                    // A stale header needs the native full row bind instead.
                    afterNextDraw()
                    if (headerMismatch) parent.notifyItemChanged(parentPosition)
                    else parent.notifyItemChanged(parentPosition, refreshPayload)
                    traceOnce("parent-refresh", "catalog=${rowModel.list.name} full=$headerMismatch")
                }
                continue
            }
            parentRepairs.remove(child)
            for (position in 0 until minOf(2, adapter.itemCount)) {
                val card = adapter.getItemOrNull(position) ?: continue
                val bound = child.findViewHolderForAdapterPosition(position) ?: continue
                val key = CardStamp(card.url, card.posterUrl)
                val previous = refreshed[bound.itemView]
                if (previous == key) {
                    // A bound title is available without inspecting Coil or the bitmap.
                    val shown = bound.itemView.findViewById<TextView>(R.id.imageText)
                        ?.text?.toString()
                    if (shown != null && shown != card.name) {
                        traceOnce("card-unresolved",
                            "catalog=${rowModel.list.name} position=$position")
                    }
                    continue
                }
                refreshed[bound.itemView] = key
                // Even when the title matches, a recycled ImageView can retain
                // the old provider's poster. Only these first two need a rebind.
                afterNextDraw()
                adapter.notifyItemChanged(position, refreshPayload)
                rebound++
            }
        }
        if (rebound > 0) {
            traceOnce("refresh", "catalogs=$visibleRows cards=$rebound")
            return
        }
        if (waitingForChild || (visibleRows == 0 && expected.isNotEmpty())) return
        traceOnce("refresh-complete", "catalogs=$visibleRows")
        finish()
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, v: View, saved: Bundle?
        ) {
            if (fragment.javaClass.name == HOME_CLASS) bindHome(fragment, v)
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (fragment === home) unbindHome()
        }
    }

    fun start() {
        enabled = true
        main.post {
            if (!enabled || started) return@post
            started = true
            app.registerActivityLifecycleCallbacks(this)
            (CommonActivity.activity as? MainActivity)?.let(::bindActivity)
        }
    }

    fun stop() {
        enabled = false
        main.post {
            if (!started) return@post
            started = false
            app.unregisterActivityLifecycleCallbacks(this)
            unbindActivity()
        }
    }

    private fun bindActivity(next: FragmentActivity) {
        if (!started || activity === next) return
        unbindActivity()
        activity = next
        val vm = ViewModelProvider(next)[HomeViewModel::class.java]
        model = vm
        provider = vm.apiName.value
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        vm.apiName.observe(next, apiObserver)
        vm.page.observe(next, pageObserver)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                if (fragment.javaClass.name == HOME_CLASS)
                    fragment.view?.let { bindHome(fragment, it) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
    }

    private fun unbindHome() {
        disarm()
        refreshed.clear()
        parentRepairs.clear()
        queued = false
        root = null
        home = null
    }

    private fun bindHome(fragment: Fragment, view: View) {
        val recycler = view.findViewById<RecyclerView>(R.id.home_master_recycler)
        if (home === fragment && root === recycler) return
        unbindHome()
        home = fragment
        root = recycler
        arm()
        queueRefresh()
    }

    private fun unbindActivity() {
        generation++
        model?.apiName?.removeObserver(apiObserver)
        model?.page?.removeObserver(pageObserver)
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        finish()
        unbindHome()
        model = null
        activity = null
        provider = null
        traced.clear()
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) bindActivity(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity) unbindActivity()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private companion object {
        const val HOME_CLASS = "com.lagradost.cloudstream3.ui.home.HomeFragment"
        const val LOG_TAG = "Elevate-HomePoster"
    }
}
