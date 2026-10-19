package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
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

internal class HomeRecyclerCacheGuard(
    private val application: Application
) : Application.ActivityLifecycleCallbacks {
    private var activity: FragmentActivity? = null
    private var model: HomeViewModel? = null
    private var homeView: View? = null
    private var homeFragment: Fragment? = null
    private var master: RecyclerView? = null
    private val touchedChildren = LinkedHashSet<RecyclerView>()
    private var previousProvider: String? = null
    private var switched = false
    private var sawLoading = false
    private var restoring = false
    private var started = false
    private var pendingDraw: ViewTreeObserver.OnPreDrawListener? = null

    private val providerObserver = Observer<String> { provider ->
        val previous = previousProvider
        previousProvider = provider
        if (previous != null && previous != provider) beginSwitch()
    }

    private val pageObserver = Observer<Resource<Map<String, HomeViewModel.ExpandableHomepageList>>> { resource ->
        if (!switched) return@Observer
        when (resource) {
            is Resource.Loading -> {
                sawLoading = true
                pruneOldViewCache()
            }
            is Resource.Success -> {
                val provider = previousProvider
                val matchesProvider = resource.value.values.any { row ->
                    row.list.list.firstOrNull()?.apiName == provider
                }
                if (sawLoading || matchesProvider || resource.value.isEmpty()) finishAfterNextDraw()
            }
            is Resource.Failure -> if (sawLoading) finishAfterNextDraw()
        }
    }

    private val childrenListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            if (switched) findChildRecycler(view)?.let(::pruneChildCache)
        }
        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }

    private val fragmentListener = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
        ) {
            if (f.javaClass.name == HOME_CLASS) bindHome(f, v)
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f === homeFragment) unbindHome()
        }
    }

    fun start() {
        if (started) return
        started = true
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::watchActivity)
    }

    fun stop() {
        if (!started) return
        started = false
        application.unregisterActivityLifecycleCallbacks(this)
        clearActivity()
    }

    private fun watchActivity(next: FragmentActivity) {
        if (!started || activity === next) return
        clearActivity()
        activity = next
        val vm = ViewModelProvider(next)[HomeViewModel::class.java]
        model = vm
        previousProvider = vm.apiName.value
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentListener, true)
        vm.apiName.observe(next, providerObserver)
        vm.page.observe(next, pageObserver)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                if (f.javaClass.name == HOME_CLASS) f.view?.let { bindHome(f, it) }
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
    }

    private fun clearActivity() {
        model?.apiName?.removeObserver(providerObserver)
        model?.page?.removeObserver(pageObserver)
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentListener)
        unbindHome()
        model = null
        activity = null
        previousProvider = null
        switched = false
        sawLoading = false
    }

    private fun bindHome(fragment: Fragment, root: View) {
        if (homeView === root) return
        unbindHome()
        homeFragment = fragment
        homeView = root
        master = root.findViewById<RecyclerView>(R.id.home_master_recycler)?.also {
            it.addOnChildAttachStateChangeListener(childrenListener)
        }
        if (switched) pruneOldViewCache()
    }

    private fun unbindHome() {
        cancelDraw()
        restoreCaches()
        master?.removeOnChildAttachStateChangeListener(childrenListener)
        master = null
        homeView = null
        homeFragment = null
    }

    private fun beginSwitch() {
        cancelDraw()
        switched = true
        sawLoading = false
        pruneOldViewCache()
    }

    private fun findChildRecycler(row: View): RecyclerView? =
        row.findViewById(R.id.home_child_recyclerview)

    private fun pruneChildCache(recycler: RecyclerView) {
        touchedChildren.add(recycler)
        recycler.setItemViewCacheSize(0)
    }

    private fun pruneOldViewCache() {
        val parent = master ?: return
        parent.setItemViewCacheSize(0)
        for (index in 0 until parent.childCount) {
            findChildRecycler(parent.getChildAt(index))?.let(::pruneChildCache)
        }
    }

    private fun finishAfterNextDraw() {
        if (!switched || restoring) return
        val parent = master ?: run {
            switched = false
            sawLoading = false
            return
        }
        restoring = true
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                cancelDraw()
                parent.post {
                    if (master === parent && started) {
                        restoreCaches()
                        repairStaleFirstCards(parent)
                    }
                    switched = false
                    sawLoading = false
                    restoring = false
                }
                return true
            }
        }
        pendingDraw = listener
        parent.viewTreeObserver.takeIf { it.isAlive }?.addOnPreDrawListener(listener)
            ?: run {
                restoring = false
                restoreCaches()
                switched = false
                sawLoading = false
            }
    }

    private fun cancelDraw() {
        pendingDraw?.let { listener ->
            master?.viewTreeObserver?.let { observer ->
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            }
        }
        pendingDraw = null
        restoring = false
    }

    private fun restoreCaches() {
        master?.setItemViewCacheSize(DEFAULT_CACHE_SIZE)
        touchedChildren.forEach { it.setItemViewCacheSize(DEFAULT_CACHE_SIZE) }
        touchedChildren.clear()
    }

    private fun repairStaleFirstCards(parent: RecyclerView) {
        for (rowIndex in 0 until parent.childCount) {
            val child = findChildRecycler(parent.getChildAt(rowIndex)) ?: continue
            val adapter = child.adapter as? HomeChildItemAdapter ?: continue
            for (index in 0 until minOf(DEFAULT_CACHE_SIZE, adapter.itemCount)) {
                val holder = child.findViewHolderForAdapterPosition(index) ?: continue
                val shown = holder.itemView.findViewById<TextView>(R.id.imageText)?.text?.toString()
                val expected = adapter.getItemOrNull(index)?.name
                if (shown != null && expected != null && shown != expected) {
                    adapter.notifyItemChanged(index)
                }
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) watchActivity(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity) clearActivity()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        const val DEFAULT_CACHE_SIZE = 2
        const val HOME_CLASS = "com.lagradost.cloudstream3.ui.home.HomeFragment"
    }
}
