package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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

/**
 * One-shot refresh after the native Home parent AND child lists have committed.
 * No ongoing layout polling, card-specific repair rules, provider allowlists,
 * global image-cache clearing, or independent image requests.
 */
internal class HomeRecyclerCacheGuard(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private data class RowStamp(val size: Int, val first: String?, val last: String?)
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

    private fun traceOnce(event: String, detail: String) {
        if (traced.add("$generation:$event:$detail"))
            Log.i(LOG_TAG, "generation=$generation event=$event provider=$provider " +
                "pending=$pendingProvider $detail")
    }

    private val apiObserver = Observer<String> { next ->
        if (provider != null && provider != next) {
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
        override fun onChildViewDetachedFromWindow(view: View) = Unit
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
            queued = false
            if (enabled && seq == generation && recycler === root) refreshVisible()
        }
    }

    private fun stamp(list: com.lagradost.cloudstream3.HomePageList): RowStamp {
        val cards = list.list
        return RowStamp(cards.size, cards.firstOrNull()?.url, cards.lastOrNull()?.url)
    }

    private fun refreshVisible() {
        val expected = expectedRows ?: return
        val recycler = root ?: return
        arm()
        val parent = recycler.adapter as? ParentItemAdapter ?: return
        if (recycler.isComputingLayout) {
            recycler.post { queueRefresh() }
            return
        }
        // The LiveData Success may arrive before AsyncListDiffer commits.
        if (parent.itemCount - parent.headers != expected.size) {
            traceOnce("await-parent", "reason=count expected=" + expected.size)
            return
        }
        for (i in 0 until expected.size) {
            val item = parent.getItemOrNull(i) ?: return
            if (expected[item.list.name] != stamp(item.list)) {
                traceOnce("await-parent", "reason=uncommitted-row")
                return
            }
        }

        val redraw = ArrayList<Pair<HomeChildItemAdapter, Int>>()
        var visibleRows = 0
        for (i in 0 until recycler.childCount) {
            val row = recycler.getChildAt(i)
            val child = row.findViewById<RecyclerView>(R.id.home_child_recyclerview)
                ?: continue
            val holder = recycler.findContainingViewHolder(row) ?: continue
            val position = holder.bindingAdapterPosition - parent.headers
            if (position < 0) continue
            val rowModel = parent.getItemOrNull(position) ?: continue
            val adapter = child.adapter as? HomeChildItemAdapter ?: continue
            visibleRows++
            watchChild(child)
            if (child.isComputingLayout) {
                child.post { queueRefresh() }
                return
            }
            for (index in 0 until child.childCount) {
                val childHolder = child.getChildViewHolder(child.getChildAt(index))
                val cardPosition = childHolder.bindingAdapterPosition
                if (cardPosition == RecyclerView.NO_POSITION) continue
                val actual = adapter.getItemOrNull(cardPosition)
                val wanted = rowModel.list.list.getOrNull(cardPosition)
                // Only a one-time adapter commit guard, not a poster-age check.
                if (actual?.url != wanted?.url || actual?.posterUrl != wanted?.posterUrl) {
                    traceOnce("await-child", "catalog=" + rowModel.list.name +
                        " position=" + cardPosition)
                    return
                }
                if (actual != null) redraw.add(adapter to cardPosition)
            }
        }

        traceOnce("refresh", "catalogs=$visibleRows cards=" + redraw.size)
        // Disconnect all temporary observers before our own notifications.
        finish()
        redraw.forEach { (adapter, position) ->
            // CloudStream BaseAdapter routes nonempty payloads through
            // onUpdateContent -> onBindContent -> Coil's dispose + load.
            adapter.notifyItemChanged(position, refreshPayload)
        }
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
