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
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.result.ACTION_CLICK_DEFAULT
import com.lagradost.cloudstream3.ui.result.EpisodeClickEvent
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultFragment
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.AppContextUtils.ownHide

/**
 * Intercept only live Home card clicks. Let CloudStream load the actual
 * ResultEpisode, then invoke its native player action exactly once.
 * Neither Continue Watching progress nor provider URL hashes are used.
 */
internal class LiveHomeAutoplayHook(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private var home: Fragment? = null
    private var list: RecyclerView? = null
    private var parentAdapter: RecyclerView.Adapter<*>? = null
    private var patchedParent: ParentItemAdapter? = null
    private var originalParentClick: ((SearchClickCallback) -> Unit)? = null
    private var popupDialog: BottomSheetDialog? = null
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
            val activity = CommonActivity.activity ?: run {
                original(event)
                return
            }
            val resultsId = if (Globals.isLayout(Globals.TV or Globals.EMULATOR)) {
                R.id.global_to_navigation_results_tv
            } else {
                R.id.global_to_navigation_results_phone
            }
            activity.navigate(resultsId, ResultFragment.newInstance(card).apply {
                putBoolean(AUTOPLAY_MARKER, true)
            })
            afterLive?.invoke()
        }
    }

    // ParentItemAdapter resets its children's clickCallback on every native rebind.
    // Wrap the parent callback once, so new/recycled rows inherit the hook.
    private fun patchParent(adapter: RecyclerView.Adapter<*>?) {
        if (adapter === patchedParent) return
        restoreParent()
        val parent = adapter as? ParentItemAdapter ?: return
        try {
            val field = ParentItemAdapter::class.java.getDeclaredField("clickCallback")
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val original = field.get(parent) as? ((SearchClickCallback) -> Unit) ?: return
            if (original is LiveClick) return
            field.set(parent, LiveClick(original))
            patchedParent = parent
            originalParentClick = original
        } catch (_: ReflectiveOperationException) {
            // Existing child adapter hook remains a fallback.
        } catch (_: SecurityException) {
            // Reflection can be restricted on some runtimes.
        }
    }

    private fun restoreParent() {
        val parent = patchedParent ?: return
        val original = originalParentClick
        patchedParent = null
        originalParentClick = null
        if (original != null) try {
            val field = ParentItemAdapter::class.java.getDeclaredField("clickCallback")
                .apply { isAccessible = true }
            if (field.get(parent) is LiveClick) field.set(parent, original)
        } catch (_: ReflectiveOperationException) {
        } catch (_: SecurityException) {
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
            patchParent(current)
        }
        for (i in 0 until recycler.childCount) wrapRow(recycler.getChildAt(i))
    }

    // Observe parent rebinding as native ParentItemAdapter resets child callbacks.
    // Re-wrap once before the next draw, after RecyclerView has applied its binds.
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
        // The native expanded Home catalog is a BottomSheetDialog, not a
        // HomeChildItemAdapter. Observe the native popup state, never poll.
        val activity = host ?: return
        ViewModelProvider(activity)[HomeViewModel::class.java].popup
            .observe(fragment.viewLifecycleOwner) { value ->
                if (value == null) unbindPopup()
                else root.post { if (home === fragment) bindPopup(fragment) }
            }
        if (recycler.adapter == null) {
            recycler.post { if (home === fragment && list === recycler) scan() }
        }
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
            popupDialog = dialog
            popupAdapter = adapter
            originalPopupClick = original
            // Unlike HomeChildItemAdapter, SearchAdapter captures clickCallback
            // while binding; refresh only once when the popup is first hooked.
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
        popupDialog = null
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
        restoreParent()
        list = null
        home = null
    }

    private fun playLoadedLive(fragment: Fragment) {
        val args = fragment.arguments ?: return
        if (!args.getBoolean(AUTOPLAY_MARKER, false)) return
        // Observe the real native episode; do not drop the request before
        // the provider's result arrives.
        val model = ViewModelProvider(fragment)[ResultViewModel2::class.java]
        val observer = object : Observer<Resource<Pair<UiText, ResultEpisode>>?> {
            override fun onChanged(result: Resource<Pair<UiText, ResultEpisode>>?) {
                val episode = (result as? Resource.Success)?.value?.second ?: return
                model.movie.removeObserver(this)
                if (episode.tvType == TvType.Live && args.getBoolean(AUTOPLAY_MARKER, false)) {
                    args.remove(AUTOPLAY_MARKER)
                    model.handleAction(EpisodeClickEvent(ACTION_CLICK_DEFAULT, episode))
                }
            }
        }
        model.movie.observe(fragment.viewLifecycleOwner, observer)
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            when (fragment.javaClass.name) {
                HOME_CLASS -> bindHome(fragment, view)
                RESULT_PHONE, RESULT_TV -> playLoadedLive(fragment)
            }
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
        const val RESULT_PHONE = "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone"
        const val RESULT_TV = "com.lagradost.cloudstream3.ui.result.ResultFragmentTv"
        const val AUTOPLAY_MARKER = "elevate_live_home_autoplay"
    }
}
