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
import java.util.IdentityHashMap

/**
 * Repairs a visibly stale first card only after its row and the new provider data
 * have actually been attached. Never changes image caches or the shared view pool.
 */
internal class HomeRecyclerCacheGuard(
    private val application: Application
) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var enabled = false
    private var started = false
    private var activity: FragmentActivity? = null
    private var model: HomeViewModel? = null
    private var fragment: Fragment? = null
    private var master: RecyclerView? = null
    private var previousProvider: String? = null
    private var targetProvider: String? = null
    private var latestPageReady = false
    private var activeUntil = 0L
    private var generation = 0
    private val watchers = IdentityHashMap<RecyclerView, RowWatch>()
    private val repairs = HashSet<String>()

    private val providerObserver = Observer<String> { next ->
        val old = previousProvider
        previousProvider = next
        if (old != null && old != next) {
            generation++
            targetProvider = next
            latestPageReady = false
            activeUntil = SystemClock.uptimeMillis() + WINDOW_MS
            repairs.clear()
            // The old cards must never be refreshed with the new provider's identity.
            clearRowWatchers()
        }
    }

    private val pageObserver = Observer<Resource<Map<String, HomeViewModel.ExpandableHomepageList>>> { data ->
        val target = targetProvider ?: return@Observer
        when (data) {
            is Resource.Loading -> latestPageReady = false
            is Resource.Success -> {
                // Ignore an outdated result from a provider that was just cancelled.
                val values = data.value.values.flatMap { it.list.list.take(1) }
                if (values.isNotEmpty() && values.none { it.apiName == target }) return@Observer
                latestPageReady = true
                activeUntil = SystemClock.uptimeMillis() + WINDOW_MS
                val current = generation
                // Parent AsyncListDiffer and child RecyclerViews can settle on different frames.
                for (delay in CHECKPOINTS) {
                    main.postDelayed({
                        if (isCurrent(current)) scanVisibleRows(current)
                    }, delay)
                }
                main.postDelayed({
                    if (generation == current) {
                        clearRowWatchers()
                        targetProvider = null
                        latestPageReady = false
                    }
                }, WINDOW_MS + 500L)
            }
            is Resource.Failure -> {
                latestPageReady = false
                targetProvider = null
                clearRowWatchers()
            }
        }
    }

    private val parentAttachment = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            if (!canCheck()) return
            val current = generation
            main.post {
                if (isCurrent(current)) {
                    watchRow(view)
                    verifyVisible(current)
                }
            }
        }
        override fun onChildViewDetachedFromWindow(view: View) {
            view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let { recycler ->
                watchers.remove(recycler)?.close()
            }
        }
    }

    private val fragmentCallback = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
        ) {
            if (f.javaClass.name == HOME_CLASS) attachHome(f, v)
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f === fragment) detachHome()
        }
    }

    fun start() {
        enabled = true
        main.post {
            if (!enabled || started) return@post
            started = true
            application.registerActivityLifecycleCallbacks(this)
            (CommonActivity.activity as? MainActivity)?.let(::attachActivity)
        }
    }

    fun stop() {
        enabled = false
        main.post {
            if (!started) return@post
            started = false
            application.unregisterActivityLifecycleCallbacks(this)
            detachActivity()
        }
    }

    private fun attachActivity(next: FragmentActivity) {
        if (!started || activity === next) return
        detachActivity()
        activity = next
        val vm = ViewModelProvider(next)[HomeViewModel::class.java]
        model = vm
        previousProvider = vm.apiName.value
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallback, true)
        vm.apiName.observe(next, providerObserver)
        vm.page.observe(next, pageObserver)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                if (f.javaClass.name == HOME_CLASS) f.view?.let { attachHome(f, it) }
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
    }

    private fun detachActivity() {
        generation++
        model?.apiName?.removeObserver(providerObserver)
        model?.page?.removeObserver(pageObserver)
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        detachHome()
        model = null
        activity = null
        previousProvider = null
        targetProvider = null
        latestPageReady = false
        repairs.clear()
    }

    private fun attachHome(homeFragment: Fragment, root: View) {
        val recycler = root.findViewById<RecyclerView>(R.id.home_master_recycler)
        if (fragment === homeFragment && master === recycler) return
        detachHome()
        fragment = homeFragment
        master = recycler
        recycler?.addOnChildAttachStateChangeListener(parentAttachment)
    }

    private fun detachHome() {
        clearRowWatchers()
        master?.removeOnChildAttachStateChangeListener(parentAttachment)
        master = null
        fragment = null
    }

    private fun canCheck(): Boolean =
        started && latestPageReady && targetProvider != null &&
            SystemClock.uptimeMillis() <= activeUntil

    private fun isCurrent(seq: Int): Boolean = generation == seq && canCheck()

    private fun clearRowWatchers() {
        watchers.values.toList().forEach { it.close() }
        watchers.clear()
    }

    private fun watchRow(row: View) {
        val recycler = row.findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: return
        val watcher = watchers[recycler]
        if (watcher == null) {
            RowWatch(recycler).also {
                watchers[recycler] = it
                it.start()
            }
        } else {
            watcher.sync()
        }
    }

    private inner class RowWatch(private val recycler: RecyclerView) {
        private var adapter: RecyclerView.Adapter<*>? = null
        private val events = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = queue()
            override fun onItemRangeInserted(start: Int, count: Int) = queue()
            override fun onItemRangeRemoved(start: Int, count: Int) = queue()
            override fun onItemRangeChanged(start: Int, count: Int) = queue()
            override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) = queue()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = queue()
        }
        private val attachments = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = queue()
            override fun onChildViewDetachedFromWindow(view: View) = Unit
        }
        private fun queue() {
            val seq = generation
            recycler.post {
                if (isCurrent(seq)) verifyVisible(seq)
            }
        }
        fun start() {
            recycler.addOnChildAttachStateChangeListener(attachments)
            sync()
            queue()
        }
        fun sync() {
            val next = recycler.adapter
            if (adapter === next) return
            adapter?.unregisterAdapterDataObserver(events)
            adapter = next
            next?.registerAdapterDataObserver(events)
            queue()
        }
        fun close() {
            adapter?.unregisterAdapterDataObserver(events)
            adapter = null
            recycler.removeOnChildAttachStateChangeListener(attachments)
        }
    }

    private fun scanVisibleRows(seq: Int) {
        if (!isCurrent(seq)) return
        val outer = master ?: return
        for (i in 0 until outer.childCount) watchRow(outer.getChildAt(i))
        verifyVisible(seq)
    }

    private fun verifyVisible(seq: Int) {
        if (!isCurrent(seq)) return
        val outer = master ?: return
        if (outer.isComputingLayout) {
            outer.post { if (isCurrent(seq)) verifyVisible(seq) }
            return
        }
        for (rowIndex in 0 until outer.childCount) {
            val child = outer.getChildAt(rowIndex).findViewById<RecyclerView>(
                R.id.home_child_recyclerview
            ) ?: continue
            val adapter = child.adapter as? HomeChildItemAdapter ?: continue
            if (child.isComputingLayout) continue
            for (position in 0 until minOf(2, adapter.itemCount)) {
                val item = adapter.getItemOrNull(position) ?: continue
                val holder = child.findViewHolderForAdapterPosition(position) ?: continue
                val displayed = holder.itemView.findViewById<TextView>(R.id.imageText)
                    ?.text?.toString() ?: continue
                if (displayed == item.name) continue
                val key = "${System.identityHashCode(holder.itemView)}:$position:${item.url}"
                if (!repairs.add(key)) continue
                Log.w(
                    TAG, "provider=${targetProvider} row=$rowIndex position=$position " +
                        "old=$displayed expected=${item.name} holder=${System.identityHashCode(holder)}"
                )
                // Never notify while an adapter is computing a layout.
                child.post {
                    if (!isCurrent(seq) || child.isComputingLayout) return@post
                    if (adapter !== child.adapter || adapter.getItemOrNull(position)?.url != item.url) return@post
                    adapter.notifyItemChanged(position)
                }
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) attachActivity(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity) detachActivity()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        const val TAG = "ELEVATE_HOME_FIX"
        const val WINDOW_MS = 12_000L
        const val HOME_CLASS = "com.lagradost.cloudstream3.ui.home.HomeFragment"
        val CHECKPOINTS = longArrayOf(0, 60, 150, 300, 600, 1_200, 2_500, 4_000, 7_000, 11_000)
    }
}
