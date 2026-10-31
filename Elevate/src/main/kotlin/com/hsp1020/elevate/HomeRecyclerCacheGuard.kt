package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import java.util.WeakHashMap

/**
 * Rebinds only the first two cards of each Home row after provider changes.
 * Native attach/data events also cover rows that become visible later.
 * Cancels stale Coil targets; never clears the shared image cache.
 */
internal class HomeRecyclerCacheGuard(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private data class CardStamp(val url: String?, val posterUrl: String?)
    private data class RowStamp(val size: Int, val firstTwo: List<CardStamp>, val last: String?)
    private data class ChildWatch(
        val adapter: RecyclerView.Adapter<*>,
        val observer: RecyclerView.AdapterDataObserver,
        val attaches: RecyclerView.OnChildAttachStateChangeListener
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
    private var observedParent: RecyclerView.Adapter<*>? = null
    private val observedChildren = LinkedHashMap<RecyclerView, ChildWatch>()
    private var watchingAttaches = false
    private val refreshPayload = Any()
    // Weak keys: recycled holders are never retained by this feature.
    private val refreshed = WeakHashMap<View, CardStamp>()
    private val parentRepairs = HashSet<RecyclerView>()

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
                }
                is Resource.Failure -> {
                    finish()
                }
                is Resource.Success -> {
                    if (!loadingObserved || model?.apiName?.value != pending) {
                        return@Observer
                    }
                    val rows = LinkedHashMap<String, RowStamp>()
                    result.value.values.forEach { entry ->
                        rows[entry.list.name] = stamp(entry.list)
                    }
                    expectedRows = rows
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
                unwatchChild(child)
                parentRepairs.remove(child)
            }
        }
    }

    private fun unwatchChild(recycler: RecyclerView) {
        observedChildren.remove(recycler)?.let {
            it.adapter.unregisterAdapterDataObserver(it.observer)
            recycler.removeOnChildAttachStateChangeListener(it.attaches)
        }
    }

    private fun watchChild(recycler: RecyclerView) {
        val adapter = recycler.adapter ?: return
        if (observedChildren[recycler]?.adapter === adapter) return
        unwatchChild(recycler)
        val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = queueRefresh()
            override fun onItemRangeChanged(p: Int, n: Int) = queueRefresh()
            override fun onItemRangeInserted(p: Int, n: Int) = queueRefresh()
            override fun onItemRangeRemoved(p: Int, n: Int) = queueRefresh()
            override fun onItemRangeMoved(from: Int, to: Int, n: Int) = queueRefresh()
        }
        val attaches = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val position = recycler.getChildViewHolder(view).bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION || position < 2) queueRefresh()
            }
            override fun onChildViewDetachedFromWindow(view: View) {
                refreshed.remove(view)
            }
        }
        adapter.registerAdapterDataObserver(observer)
        recycler.addOnChildAttachStateChangeListener(attaches)
        observedChildren[recycler] = ChildWatch(adapter, observer, attaches)
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
        observedChildren.keys.toList().forEach(::unwatchChild)
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

    private fun refreshVisible() {
        val expected = expectedRows ?: return
        val recycler = root ?: return
        arm()
        val parent = recycler.adapter as? ParentItemAdapter ?: return
        if (recycler.isComputingLayout) return
        // Loading may finish before the native parent AsyncListDiffer commits.
        if (parent.itemCount - parent.headers != expected.size) return
        for (i in 0 until expected.size) {
            val row = parent.getItemOrNull(i) ?: return
            if (expected[row.list.name] != stamp(row.list)) return
        }

        for (i in 0 until recycler.childCount) {
            val row = recycler.getChildAt(i)
            val child = row.findViewById<RecyclerView>(R.id.home_child_recyclerview)
                ?: continue
            val parentHolder = recycler.findContainingViewHolder(row) ?: continue
            val parentPosition = parentHolder.bindingAdapterPosition
            val rowModel = parent.getItemOrNull(parentPosition - parent.headers) ?: continue
            val adapter = child.adapter as? HomeChildItemAdapter ?: continue
            watchChild(child)
            if (child.isComputingLayout) continue
            val title = row.findViewById<TextView>(R.id.home_child_more_info)?.text?.toString()
            val headerMismatch = title != null && title != rowModel.list.name
            val childMismatch = (0 until 2).any { position ->
                val current = adapter.getItemOrNull(position)
                val wanted = rowModel.list.list.getOrNull(position)
                current?.url != wanted?.url || current?.posterUrl != wanted?.posterUrl
            }
            if (headerMismatch || childMismatch) {
                if (parentPosition != RecyclerView.NO_POSITION && parentRepairs.add(child)) {
                    // Updating the parent with a payload submits the current
                    // native list to the child; a stale row title needs full bind.
                    if (headerMismatch) parent.notifyItemChanged(parentPosition)
                    else parent.notifyItemChanged(parentPosition, refreshPayload)
                }
                continue
            }
            parentRepairs.remove(child)
            for (position in 0 until minOf(2, adapter.itemCount)) {
                val card = adapter.getItemOrNull(position) ?: continue
                val holder = child.findViewHolderForAdapterPosition(position)
                    as? ViewHolderState<Boolean> ?: continue
                if (holder.bindingAdapterPosition != position) continue
                val stamp = CardStamp(card.url, card.posterUrl)
                if (refreshed[holder.itemView] == stamp) continue

                // A reused ImageView can keep the previous provider's bitmap
                // even when its data/title already match. Explicitly dispose
                // only this visible target, not Coil's global cache.
                holder.itemView.findViewById<ImageView>(R.id.imageView)?.apply {
                    dispose()
                    setImageDrawable(null)
                }
                // Bind the already-attached native ViewHolder synchronously:
                // a notifyItemChanged request alone does not guarantee that
                // RecyclerView has executed its bind before we finish.
                adapter.onBindViewHolder(holder, position, mutableListOf(refreshPayload))
                if (child.adapter === adapter &&
                    holder.bindingAdapterPosition == position &&
                    holder.itemView.findViewById<TextView>(R.id.imageText)?.text?.toString() == card.name
                ) {
                    refreshed[holder.itemView] = stamp
                }
            }
        }
        // Do NOT finish after the initially visible rows. The lightweight
        // attach listeners must remain until provider change or Home teardown,
        // so offscreen rows are covered without polling or pre-draw listeners.
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
