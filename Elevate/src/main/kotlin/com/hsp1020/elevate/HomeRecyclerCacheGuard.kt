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
    private val listenerDiagnostics = mutableSetOf<String>()
    private val removalLogCount = mutableMapOf<String, Int>()
    private val readFailureLogged = mutableSetOf<String>()
    // Weak keys: no RecyclerView retention after recycling/destruction.
    // Avoid cloning/filtering the same listener list on every layout pass.
    private val inspectedListeners = java.util.WeakHashMap<RecyclerView, Long>()
    private fun signature(list: List<RecyclerView.OnScrollListener>): Long =
        (list.size.toLong() shl 32) xor
            System.identityHashCode(list.lastOrNull()).toLong()

    private val listenerField by lazy {
        val field = runCatching {
            RecyclerView::class.java.getDeclaredField("mScrollListeners").apply {
                isAccessible = true
            }
        }.onFailure {
            Log.w(RECYCLER_LOG_TAG, "stage=reflection screen=all success=false reason=" +
                it.javaClass.simpleName)
        }.getOrNull()
        if (field != null)
            Log.i(RECYCLER_LOG_TAG, "stage=reflection screen=all success=true field=mScrollListeners")
        field
    }

    /** Bounded diagnostics: one class sample per outcome and first six removals per screen. */
    private fun prune(
        recycler: RecyclerView, owner: String, callSite: String, screen: String
    ) {
        val field = listenerField ?: return
        val read = runCatching {
            @Suppress("UNCHECKED_CAST")
            field.get(recycler) as? List<RecyclerView.OnScrollListener>
        }
        val current = read.getOrElse {
            if (readFailureLogged.add(screen))
                Log.w(RECYCLER_LOG_TAG, "stage=read screen=$screen success=false reason=" +
                    it.javaClass.simpleName)
            return
        } ?: run {
            // RecyclerView leaves the field null until its first listener is added.
            // This is a normal uninitialized state, not a reflection failure.
            if (listenerDiagnostics.add("$screen:uninitialized"))
                Log.i(RECYCLER_LOG_TAG, "stage=read screen=$screen status=uninitialized")
            return
        }
        val beforeSignature = signature(current)
        if (inspectedListeners[recycler] == beforeSignature) return
        val snapshot = current.toList()
        val matches = snapshot.filter {
            val name = it.javaClass.name
            name.startsWith(owner) && name.contains(callSite)
        }
        val state = when {
            snapshot.isEmpty() -> "empty"
            matches.isNotEmpty() -> "match"
            else -> "no-match"
        }
        if (listenerDiagnostics.add("$screen:$state")) {
            val sampled = matches.firstOrNull() ?: snapshot.firstOrNull()
            Log.i(RECYCLER_LOG_TAG,
                "stage=identify screen=$screen status=$state before=" + snapshot.size +
                    " matched=" + matches.size +
                    " class=" + (sampled?.javaClass?.name ?: "none"))
        }
        if (matches.size < 2) {
            inspectedListeners[recycler] = beforeSignature
            return
        }
        var removed = 0
        for (old in matches.dropLast(1)) {
            recycler.removeOnScrollListener(old)
            removed++
        }
        if (removed > 0) {
            val after = runCatching {
                @Suppress("UNCHECKED_CAST")
                field.get(recycler) as? List<RecyclerView.OnScrollListener>
            }.getOrNull()
            if (after != null) inspectedListeners[recycler] = signature(after)
            else inspectedListeners.remove(recycler)
            val count = removalLogCount[screen] ?: 0
            if (count < 6) {
                val remaining = after?.count {
                    it.javaClass.name.startsWith(owner) &&
                        it.javaClass.name.contains(callSite)
                }
                Log.i(RECYCLER_LOG_TAG,
                    "stage=pruned screen=$screen before=" + snapshot.size +
                        " matched=" + matches.size + " removed=$removed" +
                        " after=" + (after?.size ?: -1) +
                        " remainingNative=" + (remaining ?: -1))
            }
            removalLogCount[screen] = count + 1
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
                    "showEpisodesOverlay", "player")
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
            Log.i(RECYCLER_LOG_TAG, "stage=provider-change generation=" + generation)
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
                        Log.i(RECYCLER_LOG_TAG, "stage=home-data-ready generation=" +
                            generation + " rows=" + lists.size)
                        observeParent()
                        queueScan()
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
        Log.i(RECYCLER_LOG_TAG, "stage=repair-setting enabled=" + value)
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
        listenerDiagnostics.clear()
        removalLogCount.clear()
        readFailureLogged.clear()
        inspectedListeners.clear()
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
        if (listenerDiagnostics.add("home:attached"))
            Log.i(RECYCLER_LOG_TAG, "stage=attached screen=home found=" + (rv != null))
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
        if (listenerDiagnostics.add("player:attached"))
            Log.i(RECYCLER_LOG_TAG, "stage=attached screen=player found=true")
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
        if (listenerDiagnostics.add("home:scan"))
            Log.i(RECYCLER_LOG_TAG,
                "stage=scan screen=home visibleRows=${rv.childCount} adapterPresent=${rv.adapter != null}")
        for (i in 0 until rv.childCount) {
            val view = rv.getChildAt(i)
            val child = view.findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: continue
            prune(child,
                "com.lagradost.cloudstream3.ui.home.ParentItemAdapter" + "$",
                "onBindContent", "home")
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
        if (!repairEnabled || outer.isComputingLayout || child.isComputingLayout) return
        val parent = outer.adapter as? ParentItemAdapter ?: return
        val row = (0 until outer.childCount).map { outer.getChildAt(it) }
            .firstOrNull {
                it.findViewById<RecyclerView>(R.id.home_child_recyclerview) === child
            } ?: return
        val holder = outer.findContainingViewHolder(row) ?: return
        val rowPosition = holder.bindingAdapterPosition
        if (rowPosition < parent.headers || rowPosition == RecyclerView.NO_POSITION) return
        val item = parent.getItemOrNull(rowPosition - parent.headers) ?: return
        if (latest.none {
                it.list.name == item.list.name &&
                    it.list.list.firstOrNull()?.url == item.list.list.firstOrNull()?.url
            }) return
        if (row.findViewById<TextView>(R.id.home_child_more_info)?.text?.toString() != item.list.name)
            return
        val adapter = child.adapter as? HomeChildItemAdapter ?: return
        val seq = generation
        val max = minOf(2, adapter.itemCount, item.list.list.size)
        if (max == 0) return
        // If child adapter is stale, refresh the parent row, not the stale child.
        val wrongData = (0 until max).firstOrNull { pos ->
            item.list.list[pos].url != adapter.getItemOrNull(pos)?.url
        }
        if (wrongData != null) {
            val key = "parent:" + seq + ":" + System.identityHashCode(row) + ":" + item.list.name
            if (!repaired.add(key)) return
            Log.i(RECYCLER_LOG_TAG,
                "stage=repair-parent generation=" + seq + " row=" + rowPosition +
                    " stalePos=" + wrongData)
            outer.post {
                if (seq == generation && repairEnabled &&
                    outer.adapter === parent && !outer.isComputingLayout &&
                    parent.getItemOrNull(rowPosition - parent.headers)?.list?.name == item.list.name
                ) {
                    parent.notifyItemChanged(rowPosition)
                } else {
                    repaired.remove(key)
                }
            }
            return
        }
        for (pos in 0 until max) {
            val card = adapter.getItemOrNull(pos) ?: continue
            val bound = child.findViewHolderForAdapterPosition(pos) ?: continue
            val shownTitle = bound.itemView.findViewById<TextView>(R.id.imageText)?.text?.toString()
            // Once per provider generation, reload the first two images even if
            // their title is correct, because the recycled drawable may be stale.
            val forceRefresh = seq > 0
            if (!forceRefresh && shownTitle == card.name) continue
            val key = "card:" + seq + ":" + System.identityHashCode(bound.itemView) +
                ":" + pos + ":" + card.url
            if (!repaired.add(key)) continue
            Log.i(RECYCLER_LOG_TAG,
                "stage=repair-card generation=" + seq + " pos=" + pos +
                    " forced=" + forceRefresh)
            child.post {
                if (seq == generation && repairEnabled &&
                    child.adapter === adapter && !child.isComputingLayout &&
                    adapter.getItemOrNull(pos)?.url == card.url &&
                    child.findViewHolderForAdapterPosition(pos)?.itemView === bound.itemView
                ) {
                    adapter.notifyItemChanged(pos)
                } else {
                    repaired.remove(key)
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
        const val RECYCLER_LOG_TAG = "Elevate-RecyclerGuard"
    }
}
