package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.result.START_ACTION_LOAD_EP
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.utils.AppContextUtils.ownHide
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult

/**
 * Only intercept Home and expanded-catalog live card clicks. Reuse the
 * same native START_ACTION_LOAD_EP route verified in commit 31597b6.
 * Do not use resume-latest or a separate result-fragment observer.
 */
internal class LiveHomeAutoplayHook(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private var home: Fragment? = null
    private var list: RecyclerView? = null
    private var parentAdapter: RecyclerView.Adapter<*>? = null
    private var popupAdapter: SearchAdapter? = null
    private var originalPopupClick: ((SearchClickCallback) -> Unit)? = null
    private var enabled = false
    private var pendingTree: ViewTreeObserver? = null
    private var pendingDraw: ViewTreeObserver.OnPreDrawListener? = null

    private inner class LiveClick(
        val original: (SearchClickCallback) -> Unit,
        val afterLive: (() -> Unit)? = null
    ) : (SearchClickCallback) -> Unit {
        override fun invoke(event: SearchClickCallback) {
            val card = event.card
            if (!enabled || event.action != SEARCH_ACTION_LOAD || card.type != TvType.Live) {
                original(event)
                return
            }
            val api = getApiFromNameNull(card.apiName)
            if (api == null) {
                original(event)
                return
            }
            // Restore the working native ResultFragment auto-start path.
            // The core builds a single movie/live episode with this ID.
            val id = card.url.replace(api.mainUrl, "").replace("/", "").hashCode()
            loadSearchResult(card, START_ACTION_LOAD_EP, id)
            afterLive?.invoke()
        }
    }

    private fun wrapRow(row: View) {
        val child = row.findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: return
        val adapter = child.adapter as? HomeChildItemAdapter ?: return
        if (adapter.clickCallback is LiveClick) return
        adapter.clickCallback = LiveClick(adapter.clickCallback)
    }

    private fun unwrapRow(row: View) {
        val adapter = row.findViewById<RecyclerView>(R.id.home_child_recyclerview)
            ?.adapter as? HomeChildItemAdapter ?: return
        val click = adapter.clickCallback
        if (click is LiveClick) adapter.clickCallback = click.original
    }

    private fun scan() {
        val recycler = list ?: return
        val current = recycler.adapter
        if (current !== parentAdapter) {
            parentAdapter?.unregisterAdapterDataObserver(changes)
            parentAdapter = current
            current?.registerAdapterDataObserver(changes)
        }
        for (i in 0 until recycler.childCount) wrapRow(recycler.getChildAt(i))
    }

    // Native parent rebinding resets child callbacks; re-wrap once on pre-draw.
    private val changes = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = scheduleScan()
        override fun onItemRangeChanged(start: Int, count: Int) = scheduleScan()
        override fun onItemRangeInserted(start: Int, count: Int) = scheduleScan()
        override fun onItemRangeRemoved(start: Int, count: Int) = scheduleScan()
        override fun onItemRangeMoved(from: Int, to: Int, count: Int) = scheduleScan()
    }
    private val attaches = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            if (list?.adapter !== parentAdapter) scan()
            wrapRow(view)
        }
        override fun onChildViewDetachedFromWindow(view: View) { unwrapRow(view) }
    }

    private fun clearScheduled() {
        pendingDraw?.let { listener ->
            pendingTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        pendingDraw = null
        pendingTree = null
    }

    private fun scheduleScan() {
        if (pendingDraw != null) return
        val tree = list?.viewTreeObserver?.takeIf { it.isAlive } ?: return
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                clearScheduled()
                scan()
                return true
            }
        }
        pendingTree = tree
        pendingDraw = listener
        tree.addOnPreDrawListener(listener)
    }

    private fun bindHome(fragment: Fragment, root: View) {
        val recycler = root.findViewById<RecyclerView>(R.id.home_master_recycler) ?: return
        if (home === fragment && list === recycler) return
        unbindHome()
        home = fragment
        list = recycler
        recycler.addOnChildAttachStateChangeListener(attaches)
        scan()
        // The expanded Home catalog uses a separate native SearchAdapter.
        val activity = host ?: return
        ViewModelProvider(activity)[HomeViewModel::class.java].popup
            .observe(fragment.viewLifecycleOwner) { value ->
                if (value == null) unbindPopup()
                else root.post { if (home === fragment) bindPopup(fragment) }
            }
        // Native parent rows can be attached/bound after this fragment callback.
        // Match the first verified working version's deferred re-scan.
        recycler.post { if (home === fragment && list === recycler) scan() }
    }

    private fun bindPopup(fragment: Fragment) {
        val dialog = try {
            fragment.javaClass.getDeclaredField("bottomSheetDialog").apply {
                isAccessible = true
            }.get(fragment) as? BottomSheetDialog
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return

        val adapter = dialog.findViewById<RecyclerView>(R.id.home_expanded_recycler)
            ?.adapter as? SearchAdapter ?: return
        if (adapter === popupAdapter) return
        unbindPopup()
        try {
            val field = SearchAdapter::class.java.getDeclaredField("clickCallback")
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val original = field.get(adapter) as? ((SearchClickCallback) -> Unit) ?: return
            if (original is LiveClick) return
            field.set(adapter, LiveClick(original) { dialog.ownHide() })
            popupAdapter = adapter
            originalPopupClick = original
            // The native SearchAdapter binds its click listener, so rebind
            // only this popup once to apply the existing click callback.
            if (adapter.itemCount > 0) adapter.notifyItemRangeChanged(0, adapter.itemCount)
        } catch (_: ReflectiveOperationException) {
            unbindPopup()
        } catch (_: SecurityException) {
            unbindPopup()
        }
    }

    private fun unbindPopup() {
        val adapter = popupAdapter
        val original = originalPopupClick
        popupAdapter = null
        originalPopupClick = null
        if (adapter != null && original != null) try {
            val field = SearchAdapter::class.java.getDeclaredField("clickCallback")
                .apply { isAccessible = true }
            if (field.get(adapter) is LiveClick) field.set(adapter, original)
        } catch (_: ReflectiveOperationException) {
        } catch (_: SecurityException) {
        }
    }

    private fun unbindHome() {
        unbindPopup()
        clearScheduled()
        parentAdapter?.unregisterAdapterDataObserver(changes)
        parentAdapter = null
        list?.removeOnChildAttachStateChangeListener(attaches)
        list?.let { recycler ->
            for (i in 0 until recycler.childCount) unwrapRow(recycler.getChildAt(i))
        }
        list = null
        home = null
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            if (fragment.javaClass.name == HOME_CLASS) bindHome(fragment, view)
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (fragment === home) unbindHome()
        }
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        unbindHome()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        fun scanFragments(fm: FragmentManager) {
            for (fragment in fm.fragments) {
                if (fragment.javaClass.name == HOME_CLASS) fragment.view?.let { bindHome(fragment, it) }
                if (fragment.isAdded) scanFragments(fragment.childFragmentManager)
            }
        }
        scanFragments(activity.supportFragmentManager)
    }

    fun start() {
        enabled = true
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        enabled = false
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        unbindHome()
        host = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) {
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
            unbindHome()
            host = null
        }
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
