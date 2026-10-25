package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

internal class HomeRecyclerCacheGuard(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var enabled = false
    private var repairEnabled = false
    private var started = false
    private var activity: FragmentActivity? = null
    private var model: HomeViewModel? = null
    private var home: Fragment? = null
    private var root: RecyclerView? = null
    private var parentAdapter: RecyclerView.Adapter<*>? = null
    private var provider: String? = null
    private var pendingProvider: String? = null
    private var expected: List<HomeViewModel.ExpandableHomepageList>? = null
    private var generation = 0
    private val watched = mutableMapOf<RecyclerView, RowWatch>()
    private val repaired = mutableSetOf<String>()
    private var scanScheduled = false
    private var homeTree: ViewTreeObserver? = null
    private val homeLayout = ViewTreeObserver.OnGlobalLayoutListener { queueScan() }
    private val playerScreens = LinkedHashMap<Fragment, PlayerWatch>()

    private val listenerField by lazy {
        runCatching {
            RecyclerView::class.java.getDeclaredField("mScrollListeners").apply {
                isAccessible = true
            }
        }.getOrNull()
    }

    /** Remove only old listeners from the same native call site. */
    private fun prune(recycler: RecyclerView, owner: String, callSite: String) {
        val field = listenerField ?: return
        val snapshot = runCatching {
            @Suppress("UNCHECKED_CAST")
            (field.get(recycler) as? List<RecyclerView.OnScrollListener>)?.toList()
        }.getOrNull() ?: return
        if (snapshot.size < 2) return
        val matches = snapshot.filter {
            val name = it.javaClass.name
            name.startsWith(owner) && name.contains(callSite)
        }
        if (matches.size > 1) {
            matches.dropLast(1).forEach(recycler::removeOnScrollListener)
        }
    }

    private inner class PlayerWatch(private val fragment: Fragment, val view: View) {
        private val tree = view.viewTreeObserver
        private var queued = false
        private val listener = ViewTreeObserver.OnGlobalLayoutListener { schedule() }
        private val action = Runnable {
            queued = false
            if (!fragment.isAdded || fragment.view !== view) return@Runnable
            view.findViewById<RecyclerView>(R.id.player_episode_list)?.let {
                prune(it,
                    "com.lagradost.cloudstream3.ui.player.GeneratorPlayer" + "$",
                    "showEpisodesOverlay")
            }
        }
        fun start() {
            if (tree.isAlive) tree.addOnGlobalLayoutListener(listener)
            schedule()
        }
        private fun schedule() {
            if (queued) return
            queued = true
            main.postDelayed(action, 120L)
        }
        fun close() {
            if (tree.isAlive) tree.removeOnGlobalLayoutListener(listener)
            main.removeCallbacks(action)
            queued = false
        }
    }
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
            generation++
            pendingProvider = next
            expected = null
            repaired.clear()
            clearRows()
        }
        provider = next
    }
    private val pageObserver =
        Observer<Resource<Map<String, HomeViewModel.ExpandableHomepageList>>> { result ->
            if (pendingProvider == null) return@Observer
            when (result) {
                is Resource.Loading -> expected = null
                is Resource.Failure -> {
                    pendingProvider = null
                    expected = null
                    clearRows()
                }
                is Resource.Success -> {
                    val lists = result.value.values.toList()
                    if (lists.any { row ->
                            row.list.list.firstOrNull()?.apiName == pendingProvider
                        } || lists.all { it.list.list.isEmpty() }
                    ) {
                        expected = lists
                        observeParent()
                        scanRows()
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
            queueScan()
        }
    }
    private val parentViews = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
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
            if (f.javaClass.name == PLAYER_CLASS) bindPlayer(f, v)
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f === home) unbindHome()
            playerScreens.remove(f)?.close()
        }
    }

    /** Home repair toggle affects stale-poster repair only, not native listener cleanup. */
    fun setRepairEnabled(value: Boolean) {
        if (repairEnabled == value) return
        repairEnabled = value
        main.post {
            if (!started) return@post
            val vm = model ?: return@post
            vm.apiName.removeObserver(apiObserver)
            vm.page.removeObserver(pageObserver)
            generation++
            clearRows()
            repaired.clear()
            expected = null
            pendingProvider = null
            if (repairEnabled) {
                provider = vm.apiName.value
                val owner = activity ?: return@post
                vm.apiName.observe(owner, apiObserver)
                vm.page.observe(owner, pageObserver)
            }
            queueScan()
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
        if (repairEnabled) {
            vm.apiName.observe(next, apiObserver)
            vm.page.observe(next, pageObserver)
        }
        fun find(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                if (f.javaClass.name == HOME_CLASS) f.view?.let { bindHome(f, it) }
                if (f.javaClass.name == PLAYER_CLASS) f.view?.let { bindPlayer(f, it) }
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
        playerScreens.values.forEach { it.close() }
        playerScreens.clear()
        model = null
        activity = null
        provider = null
        pendingProvider = null
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
        homeTree = rv?.viewTreeObserver?.also {
            if (it.isAlive) it.addOnGlobalLayoutListener(homeLayout)
        }
        observeParent()
        queueScan()
    }
    private fun bindPlayer(f: Fragment, v: View) {
        if (playerScreens[f]?.view === v) return
        playerScreens.remove(f)?.close()
        playerScreens[f] = PlayerWatch(f, v).also { it.start() }
    }
    private fun unbindHome() {
        homeTree?.let { if (it.isAlive) it.removeOnGlobalLayoutListener(homeLayout) }
        homeTree = null
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
        if (rv.isComputingLayout) {
            queueScan()
            return
        }
        observeParent()
        for (i in 0 until rv.childCount) {
            val view = rv.getChildAt(i)
            val child = view.findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: continue
            prune(child,
                "com.lagradost.cloudstream3.ui.home.ParentItemAdapter" + "$",
                "onBindContent")
            if (repairEnabled && expected != null) watchRow(view)
        }
    }
    private fun watchRow(view: View) {
        if (!repairEnabled || expected == null) return
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
        val latest = expected ?: return
        val outer = root ?: return
        if (outer.isComputingLayout || child.isComputingLayout) return
        val parent = outer.adapter as? ParentItemAdapter ?: return
        val row = (0 until outer.childCount).map { outer.getChildAt(it) }
            .firstOrNull {
                it.findViewById<RecyclerView>(R.id.home_child_recyclerview) === child
            } ?: return
        val holder = outer.findContainingViewHolder(row) ?: return
        val item = parent.getItemOrNull(holder.bindingAdapterPosition - parent.headers) ?: return
        if (!repairEnabled || latest.none {
                it.list.name == item.list.name &&
                    it.list.list.firstOrNull()?.url == item.list.list.firstOrNull()?.url
            }) return
        if (row.findViewById<TextView>(R.id.home_child_more_info)?.text?.toString() != item.list.name) return
        val adapter = child.adapter as? HomeChildItemAdapter ?: return
        for (pos in 0 until minOf(2, adapter.itemCount)) {
            val card = adapter.getItemOrNull(pos) ?: continue
            if (item.list.list.getOrNull(pos)?.url != card.url) continue
            val bound = child.findViewHolderForAdapterPosition(pos) ?: continue
            if (bound.itemView.findViewById<TextView>(R.id.imageText)?.text?.toString() == card.name) continue
            val key = "${System.identityHashCode(bound.itemView)}:${card.url}"
            if (key in repaired) continue
            val seq = generation
            child.post {
                if (seq == generation && expected != null && !child.isComputingLayout &&
                    child.adapter === adapter && adapter.getItemOrNull(pos)?.url == card.url &&
                    child.findViewHolderForAdapterPosition(pos)?.itemView === bound.itemView &&
                    repaired.add(key)
                ) {
                    adapter.notifyItemChanged(pos)
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
        const val PLAYER_CLASS = "com.lagradost.cloudstream3.ui.player.GeneratorPlayer"
    }
}
