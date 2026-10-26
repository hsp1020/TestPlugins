package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.result.START_ACTION_LOAD_EP
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper.handleSearchClickCallback
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

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
    private var popupList: RecyclerView? = null
    private var popupAdapter: SearchAdapter? = null
    private var popupScanPending = false
    private var returningPopup: WeakReference<AutofitRecyclerView>? = null
    private var returnDrawTree: ViewTreeObserver? = null
    private var returnDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var focusTree: ViewTreeObserver? = null
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        list?.post { if (enabled) discoverPopup() }
    }
    private var enabled = false
    private var pendingTree: ViewTreeObserver? = null
    private var pendingDraw: ViewTreeObserver.OnPreDrawListener? = null

    private inner class LiveClick(
        val original: (SearchClickCallback) -> Unit
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
        // Native parent rows can be attached/bound after this fragment callback.
        // Match the first verified working version's deferred re-scan.
        recycler.post { if (home === fragment && list === recycler) scan() }
    }

    // Same public window/card-view approach as YouTubeCardHook. No access to
    // HomeFragment.bottomSheetDialog or SearchAdapter.clickCallback.
    private fun discoverPopup() {
        val activity = host ?: return
        val windows: List<View> = if (Build.VERSION.SDK_INT >= 29) {
            WindowInspector.getGlobalWindowViews()
        } else {
            // Match the existing YouTube compatibility path on Android 9 and below.
            runCatching {
                val type = Class.forName("android.view.WindowManagerGlobal")
                val manager = type.getMethod("getInstance").invoke(null)
                @Suppress("UNCHECKED_CAST")
                type.getDeclaredField("mViews").apply { isAccessible = true }
                    .get(manager) as List<View>
            }.getOrDefault(emptyList())
        }
        for (window in windows) {
            if (!window.isAttachedToWindow || windowOwner(window.context) !== activity) continue
            val recycler = window.findViewById<RecyclerView>(R.id.home_expanded_recycler)
                ?: continue
            if (recycler.adapter !is SearchAdapter) continue
            if (popupList !== recycler || popupAdapter !== recycler.adapter) attachPopup(recycler)
            return
        }
        unbindPopup()
    }

    private fun windowOwner(context: Context): Activity? {
        var current = context
        repeat(12) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

    private fun attachPopup(recycler: RecyclerView) {
        unbindPopup()
        val adapter = recycler.adapter as? SearchAdapter ?: return
        popupList = recycler
        popupAdapter = adapter
        adapter.registerAdapterDataObserver(popupChanges)
        recycler.addOnChildAttachStateChangeListener(popupAttaches)
        scanPopup()
    }

    private val popupChanges = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = schedulePopupScan()
        override fun onItemRangeChanged(start: Int, count: Int) = schedulePopupScan()
        override fun onItemRangeInserted(start: Int, count: Int) = schedulePopupScan()
        override fun onItemRangeRemoved(start: Int, count: Int) = schedulePopupScan()
        override fun onItemRangeMoved(from: Int, to: Int, count: Int) = schedulePopupScan()
    }

    private val popupAttaches = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            val recycler = popupList ?: return
            view.post {
                if (popupList === recycler && view.parent === recycler) {
                    applyPopupCard(recycler, view)
                }
            }
        }
        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }

    private fun schedulePopupScan() {
        val recycler = popupList ?: return
        if (popupScanPending) return
        popupScanPending = true
        recycler.post {
            popupScanPending = false
            if (popupList === recycler) scanPopup()
        }
    }

    private fun scanPopup() {
        val recycler = popupList ?: return
        if (recycler.adapter !== popupAdapter) {
            attachPopup(recycler)
            return
        }
        for (index in 0 until recycler.childCount) {
            applyPopupCard(recycler, recycler.getChildAt(index))
        }
    }

    private fun applyPopupCard(recycler: RecyclerView, view: View) {
        val adapter = recycler.adapter as? SearchAdapter ?: return
        val position = recycler.getChildViewHolder(view).bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        val card = adapter.getItemOrNull(position) as? SearchResponse ?: return
        if (card.type != TvType.Live) return
        val background = view.findViewById<View>(R.id.background_card) ?: return

        // Only live cards are changed. Native SearchAdapter still owns the
        // poster, focus, long-press, and every other card's click handling.
        val click = View.OnClickListener { clicked ->
            val currentPosition = recycler.getChildViewHolder(view).bindingAdapterPosition
            if (currentPosition == RecyclerView.NO_POSITION) return@OnClickListener
            val current = (recycler.adapter as? SearchAdapter)
                ?.getItemOrNull(currentPosition) as? SearchResponse ?: return@OnClickListener
            val api = if (enabled && current.type == TvType.Live) {
                getApiFromNameNull(current.apiName)
            } else null
            if (api == null) {
                // During adapter changes or while switching OFF, retain native
                // navigation instead of swallowing a card click.
                handleSearchClickCallback(
                    SearchClickCallback(SEARCH_ACTION_LOAD, clicked, currentPosition, current)
                )
                return@OnClickListener
            }
            val id = current.url.replace(api.mainUrl, "").replace("/", "").hashCode()
            if (Globals.isLayout(Globals.PHONE)) {
                returningPopup = (recycler as? AutofitRecyclerView)?.let { WeakReference(it) }
            }
            loadSearchResult(current, START_ACTION_LOAD_EP, id)
            // HomeFragment.onDestroyView() performs its native ownHide().
        }
        view.setOnClickListener(click)
        background.setOnClickListener(click)
    }

    private fun clearReturnDraw() {
        returnDrawTree?.takeIf { it.isAlive }?.let { tree ->
            returnDrawListener?.let(tree::removeOnPreDrawListener)
        }
        returnDrawTree = null
        returnDrawListener = null
    }

    /** Correct only bound popup image heights before the first Home return frame. */
    private fun repairReturningPopup() {
        val recycler = returningPopup?.get()
        returningPopup = null
        clearReturnDraw()
        if (!enabled || !Globals.isLayout(Globals.PHONE) ||
            recycler == null || !recycler.isAttachedToWindow) return
        val adapter = recycler.adapter as? SearchAdapter ?: return
        val tree = recycler.viewTreeObserver.takeIf { it.isAlive } ?: return
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                clearReturnDraw()
                if (!enabled || recycler.adapter !== adapter ||
                    recycler.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) {
                    return true
                }

                val columns = recycler.spanCount
                val width = recycler.measuredWidth
                if (columns <= 0 || width <= 0 ||
                    width > recycler.resources.displayMetrics.widthPixels) return true
                val ratio = when (columns) {
                    recycler.context.getSpanCount(true) -> 1.8
                    recycler.context.getSpanCount(false) -> 0.68
                    else -> return true
                }
                val height = ((width / columns) / ratio).roundToInt()
                var changed = false
                for (index in 0 until recycler.childCount) {
                    val card = recycler.getChildAt(index)
                    if (recycler.getChildViewHolder(card).bindingAdapterPosition ==
                        RecyclerView.NO_POSITION) continue
                    val image = card.findViewById<ImageView>(R.id.imageView) ?: continue
                    val params = image.layoutParams
                    if (params.height > 0 && params.height != height) {
                        params.height = height
                        image.layoutParams = params
                        changed = true
                    }
                }
                // Request only the layout pass caused by corrected heights; skip its bad frame.
                return !changed
            }
        }
        returnDrawTree = tree
        returnDrawListener = listener
        tree.addOnPreDrawListener(listener)
    }

    private fun unbindPopup() {
        val recycler = popupList
        val adapter = popupAdapter
        popupList = null
        popupAdapter = null
        popupScanPending = false
        recycler?.removeOnChildAttachStateChangeListener(popupAttaches)
        adapter?.unregisterAdapterDataObserver(popupChanges)
        // Native SearchResultBuilder.bind() restores the click listeners;
        // do not retain or reflectively access the private native callback.
        if (recycler != null && adapter != null && recycler.isAttachedToWindow) {
            recycler.post {
                if (recycler.adapter === adapter && adapter.itemCount > 0) {
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
            }
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
            if (fragment.javaClass.name == HOME_CLASS) {
                bindHome(fragment, view)
                repairReturningPopup()
            }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (fragment === home) unbindHome()
        }
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        returningPopup = null
        clearReturnDraw()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree = null
        unbindHome()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        activity.window.decorView.viewTreeObserver.also { tree ->
            focusTree = tree
            if (tree.isAlive) tree.addOnWindowFocusChangeListener(focusListener)
        }
        fun scanFragments(fm: FragmentManager) {
            for (fragment in fm.fragments) {
                if (fragment.javaClass.name == HOME_CLASS) fragment.view?.let { bindHome(fragment, it) }
                if (fragment.isAdded) scanFragments(fragment.childFragmentManager)
            }
        }
        scanFragments(activity.supportFragmentManager)
        activity.window.decorView.post { if (host === activity) discoverPopup() }
    }

    fun start() {
        enabled = true
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        enabled = false
        returningPopup = null
        clearReturnDraw()
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree = null
        unbindHome()
        host = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) {
            returningPopup = null
            clearReturnDraw()
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
            focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
            focusTree = null
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
