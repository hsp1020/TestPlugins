package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
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

internal class HomeRecyclerCacheGuard(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var enabled = false
    private var started = false
    private var activity: FragmentActivity? = null
    private var model: HomeViewModel? = null
    private var home: Fragment? = null
    private var root: RecyclerView? = null
    private var parentAdapter: RecyclerView.Adapter<*>? = null
    private var provider: String? = null
    private var pendingProvider: String? = null
    private var previousProvider: String? = null
    private var observedPendingLoading = false
    private var expected: List<HomeViewModel.ExpandableHomepageList>? = null
    private var generation = 0
    private val watched = mutableMapOf<RecyclerView, RowWatch>()
    private val repaired = mutableSetOf<String>()
    private var scanScheduled = false
    // Diagnostics only; de-duplicate per provider generation, without a
    // hardcoded allowlist of provider names or altering repair decisions.
    private val traced = HashSet<String>()
    private fun traceOnce(key: String, event: String, detail: String) {
        if (traced.add(generation.toString() + ":" + key))
            Log.i("Elevate-HomePoster", "generation=" + generation +
                " event=" + event + " provider=" + provider +
                " pending=" + pendingProvider + " " + detail)
    }
    private fun hash(url: String?): String = url?.hashCode()?.toString() ?: "null"
    private fun queueScan() {
        if (scanScheduled) return
        val rv = root ?: return
        val seq = generation
        scanScheduled = true
        rv.post {
            scanScheduled = false
            if (seq == generation && rv === root && !rv.isComputingLayout) scanRows()
        }
    }

    private val apiObserver = Observer<String> { next ->
        if (provider != null && provider != next) {
            val previous = provider
            generation++
            pendingProvider = next
            previousProvider = previous
            observedPendingLoading = false
            expected = null
            repaired.clear()
            traced.clear()
            clearRows()
            traceOnce("switch", "provider-switch", "from=" + previous + " to=" + next)
        }
        provider = next
    }
    private val pageObserver =
        Observer<Resource<Map<String, HomeViewModel.ExpandableHomepageList>>> { result ->
            if (pendingProvider == null) {
                if (result is Resource.Success)
                    traceOnce("no-pending-success", "page-ignored", "reason=no-pending-provider")
                return@Observer
            }
            when (result) {
                is Resource.Loading -> {
                    observedPendingLoading = true
                    expected = null
                    traceOnce("loading", "page-loading", "expectedCleared=true")
                }
                is Resource.Failure -> {
                    traceOnce("failure", "page-failure", "pendingCleared=true")
                    pendingProvider = null
                    previousProvider = null
                    observedPendingLoading = false
                    expected = null
                    clearRows()
                }
                is Resource.Success -> {
                    val lists = result.value.values.toList()
                    val providerPresent = lists.any { row ->
                        row.list.list.firstOrNull()?.apiName == pendingProvider
                    }
                    val allEmpty = lists.all { it.list.list.isEmpty() }
                    val sourceNames = lists.mapNotNull {
                        it.list.list.firstOrNull()?.apiName
                    }.toSet()
                    // The selected homepage can be an aggregate (e.g. OTT)
                    // whose catalogs legitimately contain several other API
                    // names. The VM's current provider and its fresh Loading
                    // event identify this response; never require a card's
                    // apiName to equal the aggregate's display name.
                    val groupedPage = !providerPresent && !allEmpty &&
                        observedPendingLoading && model?.apiName?.value == pendingProvider &&
                        sourceNames.isNotEmpty() &&
                        sourceNames.any { it != previousProvider }
                    traceOnce("page-success", "page-success", "rows=" + lists.size +
                        " providerPresent=" + providerPresent + " allEmpty=" + allEmpty +
                        " groupedPage=" + groupedPage + " loadingSeen=" + observedPendingLoading +
                        " sourceCount=" + sourceNames.size)
                    // Log every catalog from every selected home provider. Only
                    // identifiers/hashes are logged; no poster URLs/API tokens.
                    lists.forEachIndexed { index, row ->
                        val one = row.list.list.getOrNull(0)
                        val two = row.list.list.getOrNull(1)
                        traceOnce("page-row:" + index, "page-row",
                            "index=" + index + " row=" + row.list.name +
                                " count=" + row.list.list.size +
                                " firstApi=" + one?.apiName +
                                " firstUrlHash=" + hash(one?.url) +
                                " firstPosterHash=" + hash(one?.posterUrl) +
                                " secondApi=" + two?.apiName +
                                " secondUrlHash=" + hash(two?.url) +
                                " secondPosterHash=" + hash(two?.posterUrl))
                    }
                    if (providerPresent || allEmpty || groupedPage) {
                        expected = lists
                        traceOnce("page-accepted", "page-accepted",
                            "mode=" + if (providerPresent) "direct" else if (allEmpty) "empty" else "grouped")
                        observeParent()
                        scanRows()
                        queueScan()
                    } else {
                        traceOnce("page-rejected", "page-rejected",
                            "reason=no-content-with-selected-provider")
                    }
                }
            }
        }

    private val parentUpdates = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = queue()
        override fun onItemRangeInserted(p: Int, n: Int) = queue()
        override fun onItemRangeRemoved(p: Int, n: Int) = queue()
        override fun onItemRangeChanged(p: Int, n: Int) = queue()
        override fun onItemRangeMoved(from: Int, to: Int, n: Int) = queue()
        private fun queue() {
            if (expected == null) return
            queueScan()
        }
    }
    private val parentViews = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            if (expected == null) return
            if (view.parent === root) queueScan()
        }
        override fun onChildViewDetachedFromWindow(view: View) {
            view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let {
                watched.remove(it)?.close()
            }
        }
    }
    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, saved: Bundle?
        ) {
            if (f.javaClass.name == HOME_CLASS) bindHome(f, v)
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f === home) unbindHome()
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
        traceOnce("activity-start", "activity-bound", "providerOnBind=" + provider)
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        vm.apiName.observe(next, apiObserver)
        vm.page.observe(next, pageObserver)
        fun find(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                if (f.javaClass.name == HOME_CLASS) f.view?.let { bindHome(f, it) }
                if (f.isAdded) find(f.childFragmentManager)
            }
        }
        find(next.supportFragmentManager)
    }
    private fun unbindActivity() {
        generation++
        scanScheduled = false
        model?.apiName?.removeObserver(apiObserver)
        model?.page?.removeObserver(pageObserver)
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        unbindHome()
        model = null
        activity = null
        provider = null
        pendingProvider = null
        previousProvider = null
        observedPendingLoading = false
        expected = null
        repaired.clear()
    }
    private fun bindHome(f: Fragment, v: View) {
        val rv = v.findViewById<RecyclerView>(R.id.home_master_recycler)
        if (home === f && root === rv) return
        unbindHome()
        home = f
        root = rv
        rv?.addOnChildAttachStateChangeListener(parentViews)
        observeParent()
        queueScan()
    }
    private fun unbindHome() {
        clearRows()
        parentAdapter?.unregisterAdapterDataObserver(parentUpdates)
        parentAdapter = null
        root?.removeOnChildAttachStateChangeListener(parentViews)
        root = null
        home = null
    }
    private fun observeParent() {
        val next = root?.adapter
        if (parentAdapter === next) return
        parentAdapter?.unregisterAdapterDataObserver(parentUpdates)
        parentAdapter = next
        next?.registerAdapterDataObserver(parentUpdates)
    }
    private fun clearRows() {
        watched.values.toList().forEach { it.close() }
        watched.clear()
    }
    private fun scanRows() {
        val rv = root ?: return
        if (expected == null) {
            traceOnce("scan-empty", "scan-skipped", "reason=no-expected-page")
            return
        }
        if (rv.isComputingLayout) {
            queueScan()
            return
        }
        observeParent()
        traceOnce("scan", "scan-start", "visibleRows=" + rv.childCount +
            " adapter=" + rv.adapter?.javaClass?.simpleName)
        for (i in 0 until rv.childCount) watchRow(rv.getChildAt(i))
    }
    private fun watchRow(view: View) {
        if (expected == null) return
        val rv = view.findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: return
        watched.getOrPut(rv) { RowWatch(rv).also { it.start() } }.sync()
        verify(rv)
    }

    private inner class RowWatch(private val recycler: RecyclerView) {
        private var adapter: RecyclerView.Adapter<*>? = null
        private var verifyScheduled = false
        private val updates = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = queue()
            override fun onItemRangeInserted(p: Int, n: Int) = queue()
            override fun onItemRangeRemoved(p: Int, n: Int) = queue()
            override fun onItemRangeChanged(p: Int, n: Int) = queue()
            override fun onItemRangeMoved(from: Int, to: Int, n: Int) = queue()
        }
        private val views = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = queue()
            override fun onChildViewDetachedFromWindow(view: View) = Unit
        }
        private fun queue() {
            if (verifyScheduled) return
            verifyScheduled = true
            val seq = generation
            recycler.post {
                verifyScheduled = false
                if (seq == generation && watched[recycler] === this@RowWatch &&
                    !recycler.isComputingLayout) verify(recycler)
            }
        }
        fun start() {
            recycler.addOnChildAttachStateChangeListener(views)
            sync()
        }
        fun sync() {
            val next = recycler.adapter
            if (adapter === next) return
            adapter?.unregisterAdapterDataObserver(updates)
            adapter = next
            adapter?.registerAdapterDataObserver(updates)
            queue()
        }
        fun close() {
            adapter?.unregisterAdapterDataObserver(updates)
            adapter = null
            verifyScheduled = false
            recycler.removeOnChildAttachStateChangeListener(views)
        }
    }

    private fun verify(child: RecyclerView) {
        val latest = expected ?: run {
            traceOnce("verify:no-page", "verify-skipped", "reason=no-expected-data")
            return
        }
        val outer = root ?: run {
            traceOnce("verify:root-missing", "verify-skipped", "reason=no-home-recycler")
            return
        }
        if (outer.isComputingLayout || child.isComputingLayout) {
            traceOnce("verify:layout", "verify-skipped", "reason=computing-layout")
            return
        }
        val parent = outer.adapter as? ParentItemAdapter ?: run {
            traceOnce("verify:parent-missing", "verify-skipped", "reason=wrong-or-missing-parent-adapter")
            return
        }
        val row = (0 until outer.childCount).map { outer.getChildAt(it) }
            .firstOrNull {
                it.findViewById<RecyclerView>(R.id.home_child_recyclerview) === child
            } ?: run {
                traceOnce("verify:row-not-found", "verify-skipped", "reason=child-not-in-visible-parent")
                return
            }
        val holder = outer.findContainingViewHolder(row) ?: run {
            traceOnce("verify:holder-missing", "verify-skipped", "reason=no-parent-viewholder")
            return
        }
        val item = parent.getItemOrNull(holder.bindingAdapterPosition - parent.headers) ?: run {
            traceOnce("verify:item-missing", "verify-skipped", "reason=parent-item-out-of-range")
            return
        }
        val rowKey = item.list.name + ":" + System.identityHashCode(child)
        val current = item.list.list.firstOrNull()
        val wanted = latest.firstOrNull { it.list.name == item.list.name }
        val expectedFirst = wanted?.list?.list?.firstOrNull()
        traceOnce("row:" + rowKey, "row-compare",
            "row=" + item.list.name + " parentCount=" + item.list.list.size +
                " expectedCount=" + (wanted?.list?.list?.size ?: -1) +
                " parentApi=" + current?.apiName + " expectedApi=" + expectedFirst?.apiName +
                " parentFirstHash=" + hash(current?.url) +
                " expectedFirstHash=" + hash(expectedFirst?.url) +
                " parentPosterHash=" + hash(current?.posterUrl) +
                " expectedPosterHash=" + hash(expectedFirst?.posterUrl))
        if (latest.none {
                it.list.name == item.list.name &&
                    it.list.list.firstOrNull()?.url == item.list.list.firstOrNull()?.url
            }) {
            traceOnce("skip:parent:" + rowKey, "row-skipped",
                "row=" + item.list.name + " reason=parent-first-url-does-not-match-page")
            return
        }
        if (row.findViewById<TextView>(R.id.home_child_more_info)?.text?.toString() != item.list.name) {
            traceOnce("skip:header:" + rowKey, "row-skipped",
                "row=" + item.list.name + " reason=header-title-mismatch")
            return
        }
        val adapter = child.adapter as? HomeChildItemAdapter ?: run {
            traceOnce("verify:child-adapter:" + rowKey, "row-skipped",
                "row=" + item.list.name + " reason=missing-home-child-adapter")
            return
        }
        for (pos in 0 until minOf(2, adapter.itemCount)) {
            val card = adapter.getItemOrNull(pos) ?: continue
            val parentCard = item.list.list.getOrNull(pos)
            val bound = child.findViewHolderForAdapterPosition(pos)
            val boundTitle = bound?.itemView
                ?.findViewById<TextView>(R.id.imageText)?.text?.toString()
            val shownImage = bound?.itemView
                ?.findViewById<android.widget.ImageView>(R.id.imageView)?.drawable
            val imageState = shownImage?.constantState
            val parentMatches = parentCard?.url == card.url
            val titleMatches = boundTitle == card.name
            traceOnce("card:" + rowKey + ":" + pos, "card-compare",
                "row=" + item.list.name + " pos=" + pos +
                    " cardApi=" + card.apiName +
                    " childHash=" + hash(card.url) + " parentHash=" + hash(parentCard?.url) +
                    " childPosterHash=" + hash(card.posterUrl) +
                    " parentPosterHash=" + hash(parentCard?.posterUrl) +
                    " hasView=" + (bound != null) +
                    " drawableClass=" + shownImage?.javaClass?.simpleName +
                    " drawableStateId=" + (if (imageState != null)
                        System.identityHashCode(imageState) else 0) +
                    " parentMatches=" + parentMatches +
                    " titleMatches=" + titleMatches)
            if (!parentMatches) {
                // A stale child adapter cannot be fixed by rebinding its own
                // ViewHolder: it still holds the previous provider's items.
                // Ask the native parent to rebind this row so onBindContent()
                // submits the CURRENT parent list to the child adapter.
                val parentPosition = holder.bindingAdapterPosition
                val parentKey = "parent:" + generation + ":" +
                    System.identityHashCode(row) + ":" + item.list.name
                traceOnce("mismatch:" + rowKey + ":" + pos, "child-parent-mismatch",
                    "row=" + item.list.name + " pos=" + pos +
                        " parentHash=" + hash(parentCard?.url) +
                        " childHash=" + hash(card.url))
                if (parentPosition != RecyclerView.NO_POSITION &&
                    repaired.add(parentKey)
                ) {
                    val seq = generation
                    outer.post {
                        if (seq == generation && expected != null &&
                            !outer.isComputingLayout && outer.adapter === parent &&
                            holder.bindingAdapterPosition == parentPosition
                        ) {
                            Log.i("Elevate-HomePoster",
                                "generation=" + seq + " event=parent-refresh-dispatched" +
                                    " row=" + item.list.name + " pos=" + parentPosition)
                            parent.notifyItemChanged(parentPosition)
                        } else {
                            repaired.remove(parentKey)
                        }
                    }
                }
                return
            }
            if (bound == null) continue
            // A recycled ImageView can still display the previous provider's
            // image even when its new title and URL already match. Rebind
            // at most once per visible holder/card in each provider generation.
            val refreshAfterSwitch = generation > 1
            if (titleMatches && !refreshAfterSwitch) {
                traceOnce("skip:title:" + rowKey + ":" + pos, "card-skipped",
                    "row=" + item.list.name + " pos=" + pos +
                        " reason=title-matches-no-provider-switch")
                continue
            }
            val key = "${System.identityHashCode(bound.itemView)}:${card.url}"
            if (key in repaired) {
                traceOnce("skip:cached:" + rowKey + ":" + pos, "card-skipped",
                    "row=" + item.list.name + " pos=" + pos + " reason=already-repaired")
                continue
            }
            val seq = generation
            child.post {
                if (seq == generation && expected != null && !child.isComputingLayout &&
                    child.adapter === adapter && adapter.getItemOrNull(pos)?.url == card.url &&
                    child.findViewHolderForAdapterPosition(pos)?.itemView === bound.itemView &&
                    repaired.add(key)
                ) {
                    Log.i("Elevate-HomePoster",
                        "generation=" + seq + " event=repair-executed" +
                            " provider=" + provider + " row=" + item.list.name +
                            " pos=" + pos + " cardHash=" + hash(card.url))
                    Log.i("Elevate-HomePoster", "generation=" + seq +
                        " event=refresh-dispatched row=" + item.list.name +
                        " pos=" + pos + " forcedBySwitch=" + (boundTitle == card.name))
                    adapter.notifyItemChanged(pos)
                } else {
                    traceOnce("repair:not-executed:" + rowKey + ":" + pos, "repair-not-executed",
                        "row=" + item.list.name + " pos=" + pos +
                            " reason=generation-view-or-adapter-changed")
                }
            }
        }
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
    }
}
