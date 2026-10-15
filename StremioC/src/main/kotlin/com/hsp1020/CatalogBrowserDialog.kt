package com.hsp1020

import com.facebook.shimmer.ShimmerFrameLayout
import com.facebook.shimmer.Shimmer
import androidx.cardview.widget.CardView
import android.view.LayoutInflater
import android.content.Context
import androidx.appcompat.app.AppCompatDialog
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Window
import android.view.ViewConfiguration
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatSpinner
import com.google.android.material.button.MaterialButton
import android.widget.LinearLayout
import android.view.Gravity
import android.util.TypedValue
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import android.net.Uri
import android.view.KeyEvent
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import kotlin.math.abs
import kotlin.math.roundToInt
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_SHOW_METADATA
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The host's loading_list placeholders and shimmer animation, without extra poster requests. */
internal class StremioLoading(context: Context, rows: Int = 1) : FrameLayout(context) {
    private val shimmer = ShimmerFrameLayout(context).apply {
        setShimmer(Shimmer.AlphaHighlightBuilder()
            .setBaseAlpha(0.2f).setHighlightAlpha(0.3f).setDuration(2000L).build())
    }

    init {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        repeat(rows) {
            val placeholder = LayoutInflater.from(context).inflate(R.layout.loading_list, list, false)
            val fill = context.colorFromAttribute(R.attr.textColor)
            fun tint(view: View) {
                if (view is CardView) view.setCardBackgroundColor(fill)
                else if (view !is ViewGroup && view.background != null) view.setBackgroundColor(fill)
                if (view is ViewGroup) for (i in 0 until view.childCount) tint(view.getChildAt(i))
            }
            tint(placeholder)
            list.addView(placeholder)
        }
        shimmer.addView(list, LayoutParams(-1, -2))
        addView(shimmer, LayoutParams(-1, -1))
        isFocusable = false
        isClickable = false
        visibility = View.GONE
    }

    fun loading(active: Boolean) {
        visibility = if (active) View.VISIBLE else View.GONE
        if (active && isAttachedToWindow) shimmer.startShimmer()
        else if (!active) shimmer.stopShimmer()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == View.VISIBLE) shimmer.startShimmer()
    }

    override fun onDetachedFromWindow() {
        shimmer.stopShimmer()
        super.onDetachedFromWindow()
    }
}

/** Shared drawable-screen check for actor, catalog and trailer transitions. */
internal fun stremioDestinationReady(activity: FragmentActivity, destination: View): Boolean {
    val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.view
        ?: return false
    val bounds = host.parent as? ViewGroup ?: return false
    return host.visibility == View.VISIBLE &&
        host.left <= bounds.paddingLeft && host.top <= bounds.paddingTop &&
        host.right >= bounds.width - bounds.paddingRight &&
        host.bottom >= bounds.height - bounds.paddingBottom &&
        !host.isLayoutRequested && destination.isShown &&
        destination.width > 0 && destination.height > 0 &&
        !destination.isLayoutRequested && destination.animation?.hasEnded() != false
}

/** Finds the actual current destination, not a stale Fragment left in the NavHost. */
internal fun stremioCurrentDestinationView(
    activity: FragmentActivity, navigation: NavController?
): View? {
    val navHost = activity.supportFragmentManager
        .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return null
    val primary = navHost.childFragmentManager.primaryNavigationFragment ?: return null
    val id = navigation?.currentDestination?.id ?: return null
    val type = primary.javaClass.name
    if (id == R.id.navigation_home &&
        type != "com.lagradost.cloudstream3.ui.home.HomeFragment") return null
    if (id == R.id.navigation_results_phone &&
        type != "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone") return null
    if (id == R.id.navigation_results_tv &&
        type != "com.lagradost.cloudstream3.ui.result.ResultFragmentTv") return null
    if (id == R.id.navigation_player &&
        type != "com.lagradost.cloudstream3.ui.player.GeneratorPlayer") return null
    return primary.view
}

/** Keeps the source dialog covering navigation until the actual destination is drawable. */
internal class StremioDetailHandoff(
    private val activity: FragmentActivity,
    private val dialog: AppCompatDialog,
    private val navigation: NavController?,
    private val onLeave: () -> Unit,
    private val onReturn: () -> Unit = { dialog.show() },
    private val onReady: () -> Unit = {}
) {
    val isActive get() = returnEntryId != null

    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var observing = false
    private var returning = false
    private var recovering = false
    private var recoveringHome = false
    private var recoveryAttempts = 0
    private var returnStarted = 0L
    private var returnTargetId: String? = null
    private var returnToOrigin = true
    private var returnEntryId: String? = null
    private var detailEntryId: String? = null
    private var pendingDetailDraw = false
    private var detailRevealEnd: (() -> Unit)? = null

    private fun currentDestinationView(): View? =
        stremioCurrentDestinationView(activity, navigation)

    private fun finishReturn(ticket: Int) {
        if (ticket != generation || !isActive || !returning ||
            navigation?.currentBackStackEntry?.id != returnTargetId) return
        val view = currentDestinationView() ?: return
        if (!stremioDestinationReady(activity, view)) return
        val toOrigin = returnToOrigin
        close()
        if (toOrigin) onReturn() else onLeave()
    }

    private fun watchReturnFrame(view: View) {
        if (!returning || !isActive || detailRevealEnd != null) return
        val observer = view.viewTreeObserver
        if (!observer.isAlive) return
        val ticket = generation
        var stable = 0
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        lateinit var release: () -> Unit
        release = {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            if (detailRevealEnd === release) detailRevealEnd = null
        }
        listener = ViewTreeObserver.OnPreDrawListener {
            if (ticket != generation || !returning || !isActive ||
                navigation?.currentBackStackEntry?.id != returnTargetId) {
                release()
            } else {
                stable = if (currentDestinationView() === view &&
                    stremioDestinationReady(activity, view)) stable + 1 else 0
                if (stable >= 2) {
                    release()
                    view.postOnAnimation { finishReturn(ticket) }
                } else view.postInvalidateOnAnimation()
            }
            true
        }
        detailRevealEnd = release
        observer.addOnPreDrawListener(listener)
        view.postInvalidateOnAnimation()
    }

    private fun scheduleReturn(target: String, toOrigin: Boolean = true) {
        if (!isActive || (returning && returnTargetId == target)) return
        detailRevealEnd?.invoke()
        returning = true
        pendingDetailDraw = false
        returnTargetId = target
        returnToOrigin = toOrigin
        returnStarted = SystemClock.uptimeMillis()
        handler.removeCallbacks(navigationTimeout)
        handler.removeCallbacks(returnTimeout)
        currentDestinationView()?.let(::watchReturnFrame)
        handler.postDelayed(returnTimeout, 1000L)
    }

    private fun recoverHome(): Boolean {
        if (!isActive || activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        val nav = navigation ?: return false
        recoveringHome = true
        val moved = runCatching {
            nav.navigate(R.id.global_to_navigation_home)
            nav.currentDestination?.id == R.id.navigation_home
        }.getOrDefault(false)
        val homeEntry = nav.currentBackStackEntry?.id
        if (moved && homeEntry != null) {
            scheduleReturn(homeEntry, false)
            return true
        }
        recoveringHome = false
        return false
    }

    private val navigationTimeout = object : Runnable {
        override fun run() {
            if (!isActive || !pendingDetailDraw) return
            if (activity.isFinishing || activity.isDestroyed) {
                close()
                onLeave()
                return
            }
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                handler.postDelayed(this, 1000L)
                return
            }
            val nav = navigation
            val origin = returnEntryId
            if (origin != null && nav?.currentBackStackEntry?.id == origin) {
                scheduleReturn(origin)
                return
            }
            if (origin != null && detailEntryId == nav?.currentBackStackEntry?.id) {
                recovering = true
                val popped = runCatching { nav?.popBackStack() == true }.getOrDefault(false)
                recovering = false
                if (popped && nav?.currentBackStackEntry?.id == origin) {
                    scheduleReturn(origin)
                    return
                }
            }
            recoveryAttempts++
            // A failed pop must not expose an unready ResultFragment.
            // First retry the return, then recover to a drawable Home destination.
            if (recoveryAttempts >= 2 && recoverHome()) return
            handler.postDelayed(this, 1000L)
        }
    }
    private val returnTimeout = object : Runnable {
        override fun run() {
            if (!isActive || !returning) return
            if (activity.isFinishing || activity.isDestroyed) {
                close()
                onLeave()
                return
            }
            if (navigation?.currentBackStackEntry?.id == returnTargetId &&
                detailRevealEnd == null) currentDestinationView()?.let(::watchReturnFrame)
            if (returnToOrigin && SystemClock.uptimeMillis() - returnStarted >= 6000L) {
                if (recoverHome()) return
            }
            // No unconditional dismiss: retain the covering dialog while the
            // destination has no drawable frame, including after popBackStack fails.
            handler.postDelayed(this, 1000L)
        }
    }
    private val activityObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY && isActive) {
            close()
            onLeave()
        }
    }

    fun begin(): Boolean {
        if (isActive || activity.isFinishing || activity.isDestroyed) return false
        val origin = navigation?.currentBackStackEntry?.id ?: return false
        generation++
        returnEntryId = origin
        detailEntryId = null
        pendingDetailDraw = true
        returning = false
        recovering = false
        recoveringHome = false
        recoveryAttempts = 0
        observing = true
        navigation.addOnDestinationChangedListener(destinationListener)
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(detailViewListener, true)
        activity.lifecycle.addObserver(activityObserver)
        handler.postDelayed(navigationTimeout, 6000L)
        return true
    }

    fun close() {
        generation++
        handler.removeCallbacks(navigationTimeout)
        handler.removeCallbacks(returnTimeout)
        returnEntryId = null
        detailEntryId = null
        returnTargetId = null
        pendingDetailDraw = false
        returning = false
        recovering = false
        recoveringHome = false
        detailRevealEnd?.invoke()
        detailRevealEnd = null
        if (observing) {
            observing = false
            navigation?.removeOnDestinationChangedListener(destinationListener)
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(detailViewListener)
            activity.lifecycle.removeObserver(activityObserver)
        }
    }

    private fun revealDetailWhenReady(detail: View) {
        detailRevealEnd?.invoke()
        val ticket = generation
        detail.doOnPreDraw {
            if (ticket != generation || !isActive || !pendingDetailDraw) return@doOnPreDraw
            val observer = detail.viewTreeObserver
            val started = SystemClock.uptimeMillis()
            var stable = 0
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            lateinit var release: () -> Unit
            release = {
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
                if (detailRevealEnd === release) detailRevealEnd = null
            }
            listener = ViewTreeObserver.OnPreDrawListener {
                if (ticket != generation || !isActive || !pendingDetailDraw ||
                    returnEntryId == null || detailEntryId != navigation?.currentBackStackEntry?.id) {
                    release()
                } else {
                    val ready = currentDestinationView() === detail &&
                        stremioDestinationReady(activity, detail)
                    stable = if (ready) stable + 1 else 0
                    if (stable >= 3 || (SystemClock.uptimeMillis() - started >= 800L && ready)) {
                        release()
                        detail.postOnAnimation {
                            if (ticket == generation && isActive && pendingDetailDraw &&
                                returnEntryId != null && detailEntryId == navigation?.currentBackStackEntry?.id &&
                                currentDestinationView() === detail &&
                                stremioDestinationReady(activity, detail)) {
                                pendingDetailDraw = false
                                handler.removeCallbacks(navigationTimeout)
                                dialog.hide()
                                onReady()
                            }
                        }
                    } else detail.postInvalidateOnAnimation()
                }
                true
            }
            detailRevealEnd = release
            observer.addOnPreDrawListener(listener)
            detail.postInvalidateOnAnimation()
        }
    }

    private val detailViewListener = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            if (returning) {
                if (navigation?.currentBackStackEntry?.id == returnTargetId &&
                    currentDestinationView() === view) watchReturnFrame(view)
                return
            }
            if (returnEntryId != null &&
                fragment.javaClass.name == "com.lagradost.cloudstream3.ui.search.SearchFragment") {
                close()
                onLeave()
                return
            }
            if (!pendingDetailDraw || !isActive) return
            if (fragment.javaClass.name !in setOf(
                    "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
                    "com.lagradost.cloudstream3.ui.result.ResultFragmentTv",
                    "com.lagradost.cloudstream3.ui.result.ResultTrailerPlayer"
                )) return
            revealDetailWhenReady(view)
        }
    }
    private val destinationListener = NavController.OnDestinationChangedListener { controller, _, _ ->
        val origin = returnEntryId
        if (isActive && origin != null) {
            val current = controller.currentBackStackEntry?.id
            when {
                current == origin -> {
                    if (detailEntryId != null) scheduleReturn(origin)
                }
                recoveringHome && controller.currentDestination?.id == R.id.navigation_home -> {
                    if (current != null) scheduleReturn(current, false)
                }
                recovering -> Unit // Recovery is still unwinding navigation.
                detailEntryId == null -> detailEntryId = current
                current != detailEntryId -> {
                    close()
                    onLeave()
                }
            }
        }
    }
}

/** One dialog, one in-flight page; only All pages are shared with Home. */
internal class CatalogBrowserDialog(
    private val activity: FragmentActivity,
    private val pluginResources: Resources,
    private val prefs: SharedPreferences,
    private val providerFor: (String) -> StremioC?,
    initialCatalog: CatalogShortcutTarget? = null,
    initialHome: CatalogHomeSnapshot? = null,
) {
    private val dialog = AppCompatDialog(activity, R.style.DialogFullscreen)
    private val scope = MainScope()
    private val manifestPrefs = activity.getSharedPreferences("StremioC_Manifest", 0)
    private val root = CoordinatorLayout(activity)
    private val typeSelector = selector()
    private val catalogSelector = selector()
    private val genreSelector = selector()
    private val typeArrow = selectorArrow()
    private val catalogArrow = selectorArrow()
    private val genreArrow = selectorArrow()
    private val checkIconId = pluginResources.getIdentifier("addon_check_icon", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
    private val selectorLabelStyle = TextView(activity).apply {
        typeface = Typeface.create(typeface, Typeface.BOLD)
    }
    private val retry = button("Retry").apply { visibility = View.GONE }
    private var initialSkeleton = false
    // The appended-page shimmer is a view overlay, not an adapter item or catalog record.
    private val pageSkeleton = ShimmerFrameLayout(activity).apply {
        setShimmer(Shimmer.AlphaHighlightBuilder()
            .setBaseAlpha(0.2f).setHighlightAlpha(0.3f).setDuration(2000L).build())
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val pageSkeletonSlots = FrameLayout(activity).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val grid = AutofitRecyclerView(activity)
    private val adapter = SearchAdapter(grid, false) { click ->
        if (!closed && !detailHandoff.isActive) when (click.action) {
            SEARCH_ACTION_SHOW_METADATA -> SearchHelper.handleSearchClickCallback(click)
            SEARCH_ACTION_LOAD -> {
                val covered = detailHandoff.begin()
                SearchHelper.handleSearchClickCallback(click)
                if (!covered) dismiss()
            }
        }
    }
    private var links = emptyList<LinkItem>()
    private data class CatalogSource(val url: String, val provider: StremioC, val catalog: StremioC.Catalog)
    private var catalogs = emptyList<CatalogSource>()
    private var selectedType = initialCatalog?.type ?: "movie"
    private val directTarget = initialCatalog?.takeIf { !it.genre.isNullOrBlank() }
    private var pendingCatalog = initialCatalog
    private var pendingGenre = initialCatalog?.genre
    private var pendingHome = initialHome?.takeIf { it.target == initialCatalog }
    private val snapshotInputPresent = initialHome != null
    private val initialTargetText = CatalogSyncTrace.target(initialCatalog)
    private var fetchingCatalogs = false
    private var provider: StremioC? = null
    private var addonUrl: String? = null
    private var catalog: StremioC.Catalog? = null
    private var genre: String? = null
    private var chosen = false
    private var generation = 0
    private var job: Job? = null
    private var offset = 0
    private var loading = false
    private var hasNext = false
    private var autoFillCount = 0
    // Stop pathological one-item pages without leaving normal short TV catalogs stranded.
    private val maxAutoFillPages = 12
    private val homeTitleFocus = if (initialCatalog != null)
        activity.currentFocus?.takeIf { it.id == R.id.home_child_more_info } else null
    private fun initialProgress(active: Boolean) {
        initialSkeleton = active
        grid.visibility = if (active) View.INVISIBLE else View.VISIBLE
        pageSkeleton.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            pageSkeleton.startShimmer()
            pageSkeleton.post {
                if (!closed && initialSkeleton) updatePageSkeleton()
            }
        } else pageSkeleton.stopShimmer()
    }
    private fun pageProgress(active: Boolean) {
        initialSkeleton = false
        pageSkeleton.visibility = if (active) View.VISIBLE else View.GONE
        if (active) pageSkeleton.startShimmer() else pageSkeleton.stopShimmer()
        // When the preceding row is complete, let the grid scroll far enough to
        // expose placeholders on the next row without inserting fake SearchResponses.
        val last = grid.layoutManager?.findViewByPosition(items.lastIndex)
        val extra = if (active && items.isNotEmpty() &&
            items.size % grid.spanCount.coerceAtLeast(1) == 0) last?.height ?: dp(190) else 0
        grid.setPadding(grid.paddingLeft, grid.paddingTop, grid.paddingRight, extra)
        grid.clipToPadding = extra == 0
        if (active) grid.post {
            if (closed || !loading || pageSkeleton.visibility != View.VISIBLE) return@post
            updatePageSkeleton()
            val lastView = grid.layoutManager?.findViewByPosition(items.lastIndex)
            if (lastView != null) {
                val nextRow = items.size % grid.spanCount.coerceAtLeast(1) == 0
                val desiredBottom = (if (nextRow) lastView.bottom + lastView.height else lastView.bottom)
                val overflow = desiredBottom - (grid.height - grid.paddingBottom)
                if (overflow > 0) grid.scrollBy(0, overflow)
            }
            updatePageSkeleton()
        }
    }

    private fun updatePageSkeleton() {
        if (pageSkeleton.visibility != View.VISIBLE) return
        val columns = grid.spanCount.coerceAtLeast(1)
        if (initialSkeleton) {
            // No results are present yet: mirror SearchAdapter's 0.68 cover ratio
            // and the native grid's 2dp poster margins without adding adapter items.
            val cellWidth = grid.itemWidth
            if (cellWidth <= dp(4) || grid.height <= 0) return
            val posterHeight = (cellWidth / 0.68).roundToInt()
            val rowHeight = posterHeight + dp(4)
            val rows = ((grid.height + rowHeight - 1) / rowHeight).coerceAtLeast(1)
            val count = rows * columns
            resizeSkeletonSlots(count)
            for (i in 0 until count) {
                val column = i % columns
                val left = if (ViewCompat.getLayoutDirection(grid) == ViewCompat.LAYOUT_DIRECTION_RTL)
                    grid.width - (column + 1) * cellWidth + dp(2)
                else column * cellWidth + dp(2)
                pageSkeletonSlots.getChildAt(i).layoutParams =
                    FrameLayout.LayoutParams(cellWidth - dp(4), posterHeight).apply {
                        leftMargin = left
                        topMargin = (i / columns) * rowHeight + dp(2)
                    }
            }
            return
        }
        if (items.isEmpty()) return
        val last = grid.layoutManager?.findViewByPosition(items.lastIndex) ?: return
        val poster = last.findViewById<View>(R.id.background_card) ?: return
        if (last.width <= 0 || last.height <= 0 || poster.width <= 0 || poster.height <= 0) return
        val posterBounds = Rect(0, 0, poster.width, poster.height)
        grid.offsetDescendantRectToMyCoords(poster, posterBounds)
        val column = (items.size - 1) % columns
        val rtl = ViewCompat.getLayoutDirection(grid) == ViewCompat.LAYOUT_DIRECTION_RTL
        val firstLeft = last.left + (if (rtl) column else -column) * last.width
        val start = items.size % columns
        val number = columns - start
        val top = (if (start == 0) last.bottom else last.top) + posterBounds.top - last.top
        resizeSkeletonSlots(number)
        for (index in 0 until number) {
            val columnIndex = start + index
            val left = firstLeft + (if (rtl) -columnIndex else columnIndex) * last.width +
                posterBounds.left - last.left
            pageSkeletonSlots.getChildAt(index).layoutParams =
                FrameLayout.LayoutParams(poster.width, poster.height).apply {
                    leftMargin = left
                    topMargin = top
                }
        }
    }
    private fun resizeSkeletonSlots(count: Int) {
        while (pageSkeletonSlots.childCount > count)
            pageSkeletonSlots.removeViewAt(pageSkeletonSlots.childCount - 1)
        while (pageSkeletonSlots.childCount < count) {
            pageSkeletonSlots.addView(CardView(activity).apply {
                // Use CloudStream's loading_poster resources, not the normal poster style.
                radius = activity.resources.getDimension(R.dimen.rounded_image_radius)
                cardElevation = 0f
                setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(activity, R.color.grayShimmer))
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
    }

    private var touchStartY = 0f
    private var closed = false
    private val navigation by lazy {
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)?.navController
    }
    private val detailHandoff by lazy {
        StremioDetailHandoff(activity, dialog, navigation, ::dismiss)
    }
    private val items = ArrayList<SearchResponse>()
    private val seen = HashSet<String>()
    private val pageSignatures = HashSet<List<String>>()
    private val hostObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> pauseRequest()
            Lifecycle.Event.ON_DESTROY -> dismiss()
            else -> Unit
        }
    }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "stremio_saved_links") {
            CatalogSyncTrace.emit("BROWSER_PREF", "event=linksChanged")
            scope.launch { reloadLinks() }
        }
    }
    private val manifestListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!fetchingCatalogs && links.any { key == stremioCatalogKey(it.link) }) {
            CatalogSyncTrace.emit("BROWSER_PREF", "event=manifestChanged")
            scope.launch { reloadLinks() }
        }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun button(label: String) = MaterialButton(activity).apply {
        id = View.generateViewId()
        text = label
        isAllCaps = false
        isFocusable = true
        minHeight = dp(48)
    }

    private fun selector() = object : AppCompatSpinner(
        activity, null, androidx.appcompat.R.attr.spinnerStyle, Spinner.MODE_DROPDOWN
    ) {
        private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var moved = false

        override fun performClick(): Boolean {
            if (!isEnabled || (adapter?.count ?: 0) <= 1) return false
            return super.performClick()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled || (adapter?.count ?: 0) <= 1) {
                isPressed = false
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = event.eventTime
                    moved = false
                    isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - downX) > touchSlop ||
                        kotlin.math.abs(event.y - downY) > touchSlop) moved = true
                    if (moved) isPressed = false
                }
                MotionEvent.ACTION_UP -> {
                    isPressed = false
                    if (!moved && event.eventTime - downAt < ViewConfiguration.getLongPressTimeout())
                        performClick()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    moved = true
                    isPressed = false
                }
            }
            return true // Never forward a long press or a drag into the popup list.
        }
    }.apply {
        id = View.generateViewId()
        minimumHeight = dp(48)
        isFocusable = true
        setPopupBackgroundDrawable(ColorDrawable(activity.colorFromAttribute(R.attr.primaryGrayBackground)))
        val cardBg = androidx.appcompat.content.res.AppCompatResources.getDrawable(activity, R.drawable.bg_color_both)?.mutate()
        val ripple = TypedValue()
        val overlay = if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true) &&
            ripple.resourceId != 0) {
            androidx.appcompat.content.res.AppCompatResources.getDrawable(activity, ripple.resourceId)
        } else null
        val selectorBackground = if (cardBg != null && overlay != null)
            android.graphics.drawable.LayerDrawable(arrayOf(cardBg, overlay))
        else cardBg ?: overlay
        background = selectorBackground?.let {
            android.graphics.drawable.InsetDrawable(it, 0, dp(4), 0, dp(4))
        }
    }

    private fun selectorArrow() = TextView(activity).apply {
        text = "▼"
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * 0.7f)
        setTextColor(activity.colorFromAttribute(R.attr.textColor))
        gravity = Gravity.CENTER
        isClickable = false
        isFocusable = false
        visibility = View.GONE
    }

    private fun selectorRow(title: String, spinner: AppCompatSpinner, arrow: TextView) =
        LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = title
                typeface = selectorLabelStyle.typeface
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(activity.colorFromAttribute(R.attr.textColor))
                setSingleLine(true)
            }, LinearLayout.LayoutParams(dp(82), dp(48)))
            addView(FrameLayout(activity).apply {
                addView(spinner, FrameLayout.LayoutParams(-1, -1))
                addView(arrow, FrameLayout.LayoutParams(dp(40), -1, Gravity.END))
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }

    private fun AppCompatSpinner.choices(title: String, labels: List<String>, selected: Int, choose: (Int) -> Unit) {
        onItemSelectedListener = null
        contentDescription = title
        isEnabled = labels.size > 1
        isClickable = labels.size > 1
        isFocusable = labels.size > 1
        when (this) {
            typeSelector -> typeArrow
            catalogSelector -> catalogArrow
            genreSelector -> genreArrow
            else -> null
        }?.visibility = if (labels.size > 1) View.VISIBLE else View.GONE
        adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                super.getView(position, convertView, parent).also { row ->
                    ((row as? TextView) ?: row.findViewById<TextView>(android.R.id.text1))?.apply {
                        typeface = selectorLabelStyle.typeface
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, selectorLabelStyle.textSize)
                        setTextColor(activity.colorFromAttribute(R.attr.textColor))
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(paddingLeft, paddingTop, dp(40), paddingBottom)
                    }
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val checked = position == this@choices.selectedItemPosition
                val color = activity.colorFromAttribute(if (checked) R.attr.colorPrimary else R.attr.textColor)
                return object : LinearLayout(activity) {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            background?.setHotspot(event.x, event.y)
                            isPressed = true
                        }
                        // Keep ListView in control of selection, scrolling and dismissal.
                        return super.dispatchTouchEvent(event)
                    }

                    override fun onDetachedFromWindow() {
                        isPressed = false
                        super.onDetachedFromWindow()
                    }
                }.apply {
                    gravity = Gravity.CENTER_VERTICAL
                    val rowColor = activity.colorFromAttribute(R.attr.primaryGrayBackground)
                    setBackgroundColor(rowColor)
                    val ripple = TypedValue()
                    if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true) &&
                        ripple.resourceId != 0) {
                        androidx.appcompat.content.res.AppCompatResources.getDrawable(activity, ripple.resourceId)?.let { overlay ->
                            background = android.graphics.drawable.LayerDrawable(arrayOf(ColorDrawable(rowColor), overlay))
                        }
                    }
                    minimumHeight = dp(48)
                    setPadding(dp(16), 0, dp(12), 0)
                    addView(TextView(activity).apply {
                        text = labels[position]
                        typeface = selectorLabelStyle.typeface
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, selectorLabelStyle.textSize)
                        setTextColor(color)
                        gravity = Gravity.CENTER_VERTICAL
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, dp(48), 1f))
                    addView(ImageView(activity).apply {
                        if (checked) setImageDrawable(pluginResources.getDrawable(checkIconId, null))
                        imageTintList = ColorStateList.valueOf(activity.colorFromAttribute(R.attr.colorPrimary))
                        contentDescription = "Selected item"
                        setPadding(dp(10), 0, dp(10), 0)
                        visibility = if (checked) View.VISIBLE else View.INVISIBLE
                    }, LinearLayout.LayoutParams(dp(40), dp(24)))
                }
            }
        }
        if (selected in labels.indices) setSelection(selected)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!closed && position in labels.indices) choose(position)
            }
        }
    }

    fun show(onDismiss: () -> Unit) {
        // Header/filters are visible immediately; neither artwork nor a timeout gates the dialog.
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.setBackgroundColor(activity.colorFromAttribute(R.attr.primaryBlackBackground))
        val headerColor = activity.colorFromAttribute(R.attr.primaryBlackBackground)
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
            setBackgroundColor(headerColor)
            addView(selectorRow("Type", typeSelector, typeArrow), ViewGroup.LayoutParams(-1, -2))
            addView(selectorRow("Catalog", catalogSelector, catalogArrow), ViewGroup.LayoutParams(-1, -2))
            addView(selectorRow("Genre", genreSelector, genreArrow), ViewGroup.LayoutParams(-1, -2))
        }
        val header = AppBarLayout(activity).apply {
            setBackgroundColor(headerColor)
            stateListAnimator = null
            setLiftOnScroll(false)
            addView(controls, AppBarLayout.LayoutParams(-1, -2).apply {
                scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                    AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
            })
        }
        // The gradient must cover the first posters, not occupy space inside the header.
        val edgeFade = View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(headerColor, headerColor and 0x00FFFFFF)
            )
            isClickable = false
            isFocusable = false
        }
        val content = FrameLayout(activity).apply {
            addView(grid, FrameLayout.LayoutParams(-1, -1))
            pageSkeleton.addView(pageSkeletonSlots, FrameLayout.LayoutParams(-1, -1))
            addView(pageSkeleton, FrameLayout.LayoutParams(-1, -1))
            addView(edgeFade, FrameLayout.LayoutParams(-1, dp(15), Gravity.TOP))
        }
        header.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { bar, offset ->
            val range = bar.totalScrollRange
            edgeFade.alpha = if (range > 0)
                (1f - kotlin.math.abs(offset).toFloat() / range).coerceIn(0f, 1f)
            else 1f
        })
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            val safeTop = dp(8) + (statusTop - position[1]).coerceAtLeast(0)
            if (view.paddingTop != safeTop) view.setPadding(0, safeTop, 0, 0)
            insets
        }
        grid.spanCount = activity.getSpanCount(false)
        root.addView(content, CoordinatorLayout.LayoutParams(-1, -1).apply {
            behavior = AppBarLayout.ScrollingViewBehavior()
        })
        root.addView(header, CoordinatorLayout.LayoutParams(-1, -2))
        root.addView(retry, CoordinatorLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM
        })
        grid.adapter = adapter
        grid.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (pageSkeleton.visibility == View.VISIBLE) grid.post { updatePageSkeleton() }
            if (right - left != oldRight - oldLeft) {
                grid.post {
                    if (!closed) {
                        grid.spanCount = activity.getSpanCount(false)
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                if (pageSkeleton.visibility == View.VISIBLE) updatePageSkeleton()
                if (dy > 0 && !view.canScrollVertically(1)) requestPage()
            }
        })
        grid.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> touchStartY = event.y
                MotionEvent.ACTION_UP -> {
                    if (touchStartY - event.y > dp(32) && !grid.canScrollVertically(1)) requestPage()
                }
            }
            false
        }
        retry.setOnClickListener {
            if (catalog == null) reloadLinks() else requestPage()
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            initialProgress(false)
            pageProgress(false)
            closed = true
            generation++
            scope.cancel()
            prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
            manifestPrefs.unregisterOnSharedPreferenceChangeListener(manifestListener)
            activity.lifecycle.removeObserver(hostObserver)
            detailHandoff.close()
            grid.adapter = null
            onDismiss()
            homeTitleFocus?.takeIf { it.isAttachedToWindow }?.let { focus ->
                focus.post {
                    if (navigation?.currentDestination?.id == R.id.navigation_home)
                        focus.requestFocus()
                }
            }
        }
        dialog.show()
        dialog.window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
        ViewCompat.requestApplyInsets(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(activity.colorFromAttribute(R.attr.primaryBlackBackground)))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        activity.lifecycle.addObserver(hostObserver)
        prefs.registerOnSharedPreferenceChangeListener(settingsListener)
        manifestPrefs.registerOnSharedPreferenceChangeListener(manifestListener)
        CatalogSyncTrace.emit("BROWSER_SHOW",
            "target=$initialTargetText inputSnapshot=$snapshotInputPresent matched=${pendingHome != null} pendingCount=${pendingHome?.items?.size} pendingSkip=${pendingHome?.nextSkip} pendingNext=${pendingHome?.hasNext}")
        reloadLinks()
    }

    fun dismiss() {
        if (closed) return
        closed = true
        detailHandoff.close()
        generation++
        scope.cancel()
        dialog.dismiss() // Detaches Spinner popups along with their anchor views.
    }

    private fun pauseRequest() {
        if (job?.isActive != true) return
        generation++
        job?.cancel()
        loading = false
        initialProgress(false)
        pageProgress(false)
        if (catalog == null || (chosen && hasNext)) {
            retry.visibility = View.VISIBLE
        }
    }

    private fun reset() {
        CatalogSyncTrace.emit("BROWSER_RESET",
            "generation=$generation selected=${CatalogSyncTrace.source(addonUrl)}/${catalog?.type}/${catalog?.id}/${genre ?: "All"} offset=$offset count=${items.size} pending=${pendingHome != null} loading=$loading")
        generation++
        fetchingCatalogs = false
        job?.cancel()
        loading = false
        initialProgress(false)
        pageProgress(false)
        offset = 0
        autoFillCount = 0
        items.clear()
        seen.clear()
        pageSignatures.clear()
        adapter.submitList(emptyList())
        hasNext = false
        chosen = false
        retry.visibility = View.GONE
    }

    private fun reloadLinks() {
        CatalogSyncTrace.emit("BROWSER_RELOAD", "target=$initialTargetText direct=${directTarget != null} pending=${pendingHome != null} selectedType=$selectedType")
        reset()
        links = if (directTarget == null) SettingsRepository.loadLinks(prefs).filter { it.enabled && it.link.isNotBlank() } else emptyList()
        provider = null
        addonUrl = null
        catalog = null
        catalogs = emptyList()
        if (directTarget != null) {
            pendingCatalog = directTarget
            pendingGenre = directTarget.genre
            selectedType = directTarget.type
        }
        typeSelector.choices("Catalog type", emptyList(), -1) {}
        catalogSelector.choices("Catalog name", emptyList(), -1) {}
        genreSelector.choices("Genre", emptyList(), -1) {}
        val sources = if (directTarget != null) {
            listOf(directTarget.addonUrl to StremioC(directTarget.addonUrl, "StremioC"))
        } else {
            links.asReversed().distinctBy { it.link }.mapNotNull { link ->
                providerFor(link.link)?.let { source ->
                    link.link to if (source.hasMainPage) source else StremioC(link.link, link.name, link.id)
                }
            }
        }
        val activeSources = sources.filter { StremioC.isCatalogAddonEnabled(it.first) }
        CatalogSyncTrace.emit("BROWSER_SOURCES",
            "target=$initialTargetText sources=${sources.size} active=${activeSources.size} providers=${activeSources.joinToString(",") { CatalogSyncTrace.provider(it.second) }}")
        if (activeSources.isNotEmpty()) initialProgress(true)
        if (activeSources.isEmpty()) {
            Toast.makeText(activity, "No Stremio catalogs available.", Toast.LENGTH_SHORT).show()
            return
        }
        val ticket = generation
        fetchingCatalogs = true
        job = scope.launch {
            val limit = Semaphore(4)
            val result = try { coroutineScope {
                activeSources.map { (url, source) ->
                    async(Dispatchers.IO) {
                        try {
                            limit.withPermit {
                                if (directTarget != null) {
                                    // Discover links already specify the exact catalog and genre.
                                    // They may refer to catalogs absent from the addon's manifest.
                                    val linked = directTarget
                                    listOf(CatalogSource(url, source, StremioC.Catalog(
                                        name = linked.catalogId,
                                        id = linked.catalogId,
                                        type = linked.type,
                                        extra = listOf(
                                            StremioC.Extra("genre", options = listOfNotNull(linked.genre)),
                                            StremioC.Extra("skip")
                                        )
                                    )))
                                } else {
                                    migrateStremioCatalogSettings(prefs, url)
                                    val key = stremioCatalogKey(url)
                                    val rank = prefs.getString("catalog_order_$key", null)?.let { raw ->
                                        runCatching {
                                            val array = org.json.JSONArray(raw)
                                            (0 until array.length()).associate { array.getString(it) to it }
                                        }.getOrNull()
                                    }.orEmpty()
                                    source.browseCatalogs().filter { catalog ->
                                        val tab = "${catalog.id}_${catalog.type}_default"
                                        catalog.browseBlockReason() == null &&
                                            prefs.getBoolean("tab_enabled_${key}_$tab", true)
                                    }.sortedBy { catalog ->
                                        rank["${catalog.id}_${catalog.type}_default"] ?: Int.MAX_VALUE
                                    }.map { CatalogSource(url, source, it) }
                                }
                            }
                        } catch (e: CancellationException) { throw e
                        } catch (_: Exception) { emptyList() }
                    }
                }.awaitAll().flatten()
            } } finally {
                if (ticket == generation) fetchingCatalogs = false
            }
            if (ticket != generation || closed) return@launch
            catalogs = result
            CatalogSyncTrace.emit("BROWSER_MANIFEST",
                "target=$initialTargetText result=${result.size} types=${result.mapNotNull { it.catalog.type }.distinct()} generation=$generation")
            if (result.isEmpty()) {
                initialProgress(false)
                Toast.makeText(activity, "Unable to load any catalog manifests.", Toast.LENGTH_SHORT).show()
                retry.visibility = View.VISIBLE
                return@launch
            }
            val types = result.mapNotNull { it.catalog.type }.distinct().sortedWith(
                compareBy<String> {
                    when (it.lowercase()) { "movie" -> 0; "series" -> 1; "anime" -> 2; else -> 3 }
                }.thenBy { it.lowercase() }
            )
            selectedType = selectedType.takeIf { it in types } ?: types.first()
            typeSelector.choices("Catalog type", types.map { type ->
                when (type.lowercase()) {
                    "movie" -> "Movies"
                    "series" -> "Series"
                    "anime" -> "Anime"
                    else -> type.replaceFirstChar { it.uppercase() }
                }
            }, types.indexOf(selectedType)) {
                if (selectedType != types[it]) selectType(types[it])
            }
            selectType(selectedType)
        }
    }

    private fun selectType(type: String) {
        CatalogSyncTrace.emit("BROWSER_SELECT_TYPE", "new=$type pendingCatalog=${CatalogSyncTrace.target(pendingCatalog)} pendingHome=${pendingHome != null}")
        reset()
        selectedType = type
        provider = null
        addonUrl = null
        catalog = null
        genre = null
        val entries = catalogs.filter { it.catalog.type == type }
        val requested = pendingCatalog?.takeIf { it.type == type }
        val target = requested?.let { request ->
            entries.firstOrNull { entry ->
                entry.catalog.id == request.catalogId &&
                    stremioManifestUrl(entry.url.fixSourceUrl()) ==
                    stremioManifestUrl(request.addonUrl.fixSourceUrl())
            }
        }
        val initial = if (requested?.genre != null) target else target ?: entries.firstOrNull()
        CatalogSyncTrace.emit("BROWSER_MATCH_CATALOG",
            "wanted=${CatalogSyncTrace.target(requested)} entries=${entries.size} exactMatch=${target != null} chosen=${initial?.catalog?.id} source=${CatalogSyncTrace.provider(initial?.provider)}")
        catalogSelector.choices("Catalog name", entries.map { entry ->
            val name = entry.catalog.name ?: entry.catalog.id ?: "Untitled"
            if (directTarget != null) name else
                "$name - " + (links.firstOrNull { it.link == entry.url }?.name ?: entry.url)
        }, initial?.let { entries.indexOf(it) } ?: -1) {
            val entry = entries.getOrNull(it) ?: return@choices
            if (catalog !== entry.catalog || addonUrl != entry.url) selectCatalog(entry)
        }
        pendingCatalog = null
        if (requested != null && target == null) {
            pendingGenre = null
            Toast.makeText(activity, "Requested catalog is not available.", Toast.LENGTH_SHORT).show()
        }
        initial?.let(::selectCatalog)
    }

    private fun selectCatalog(next: CatalogSource) {
        CatalogSyncTrace.emit("BROWSER_SELECT_CATALOG",
            "catalog=${next.catalog.type}/${next.catalog.id} p=${CatalogSyncTrace.provider(next.provider)} pendingHome=${pendingHome != null} same=${catalog === next.catalog && addonUrl == next.url}")
        if (catalog === next.catalog && addonUrl == next.url) return
        reset()
        provider = next.provider
        addonUrl = next.url
        catalog = next.catalog
        genre = null
        val blocked = next.catalog.browseBlockReason()
        if (blocked != null) {
            genreSelector.choices("Genre", emptyList(), -1) {}
            Toast.makeText(activity, blocked, Toast.LENGTH_LONG).show()
            return
        }
        val required = next.catalog.requiresExtra("genre")
        val genres: List<String?> = if (directTarget != null) {
            listOfNotNull(directTarget.genre)
        } else {
            (if (required) emptyList() else listOf(null)) +
                next.catalog.browseGenres().filter { required || !it.equals("All", ignoreCase = true) }
        }
        val initialGenre = pendingGenre
        pendingGenre = null
        val genreIndex = if (initialGenre == null) 0 else genres.indexOf(initialGenre)
        genreSelector.choices("Genre", genres.map { it ?: "All" }, genreIndex.coerceAtLeast(0)) {
            if (chosen && genre == genres[it]) return@choices
            reset()
            genre = genres[it]
            chosen = true
            hasNext = true
            CatalogSyncTrace.emit("BROWSER_GENRE_CHANGED", "genre=${genre ?: "All"} catalog=${catalog?.id} generation=$generation")
            requestPage("genreChanged")
        }
        if (initialGenre != null && genreIndex < 0) {
            Toast.makeText(activity, "Requested genre is not available.", Toast.LENGTH_SHORT).show()
            return
        }
        genre = genres.getOrNull(genreIndex)
        chosen = true
        hasNext = true
        val snapshot = pendingHome
        val sameCatalog = snapshot?.target?.catalogId == next.catalog.id
        val sameType = snapshot?.target?.type == next.catalog.type
        val sameSource = snapshot?.let {
            stremioManifestUrl(it.target.addonUrl.fixSourceUrl()) ==
                stremioManifestUrl(next.url.fixSourceUrl())
        }
        val home = snapshot?.takeIf {
            !required && genre == null && sameCatalog && sameType && sameSource == true &&
                it.items.isNotEmpty() && it.nextSkip > 0
        }
        CatalogSyncTrace.emit("BROWSER_SNAPSHOT",
            "catalog=${next.catalog.type}/${next.catalog.id} provider=${CatalogSyncTrace.provider(next.provider)} snapshot=${snapshot != null} requested=$initialTargetText genre=${genre ?: "All"} required=$required sameCatalog=$sameCatalog sameType=$sameType sameSource=$sameSource homeCount=${snapshot?.items?.size} rawSkip=${snapshot?.nextSkip} hasNext=${snapshot?.hasNext} outcome=${if (home == null) "FETCH_NEW" else "REUSE_HOME"}")
        // Inspect CloudStream's actual home row even when no snapshot was handed off.
        traceHomePresence(next)
        pendingHome = null
        if (home != null) {
            // Display the already-loaded home SearchResponses without a first-page request.
            // The browser starts at Home's raw cursor; subsequent All pages synchronize back.
            items.addAll(home.items)
            seen.addAll(home.items.map { it.url })
            offset = home.nextSkip
            hasNext = home.hasNext && next.catalog.supportsExtra("skip")
            adapter.submitList(items.toList()) { if (!closed) initialProgress(false) }
            CatalogSyncTrace.emit("BROWSER_REUSE", "count=${items.size} skip=$offset hasNext=$hasNext generation=$generation")
            autoFillIfShort(generation)
        } else requestPage("noSnapshot")
    }

    private fun traceHomePresence(next: CatalogSource) {
        val model = ViewModelProvider(activity)[HomeViewModel::class.java]
        val tab = next.provider.mainPage.firstOrNull { page ->
            val prefix = "/catalog/${next.catalog.type?.urlEncodeLocal()}/${next.catalog.id?.urlEncodeLocal()}"
            page.data.startsWith("$prefix/") || page.data.startsWith("$prefix.json")
        }
        val record = (model.page.value as? Resource.Success<*>)?.value
            ?.let { it as? Map<*, *> }?.get(tab?.name) as? HomeViewModel.ExpandableHomepageList
        val parent = activity.findViewById<RecyclerView>(R.id.home_master_recycler)
        val displayed = (parent?.adapter as? ParentItemAdapter)?.immutableCurrentList
            ?.firstOrNull { it.list.name == tab?.name }
        CatalogSyncTrace.emit("BROWSER_HOME_VISIBLE",
            "p=${CatalogSyncTrace.provider(next.provider)} api=${model.apiName.value} tab=${tab?.name} modelStatus=${model.page.value?.javaClass?.simpleName} modelPage=${record?.currentPage} modelCount=${record?.list?.list?.size} uiPage=${displayed?.currentPage} uiCount=${displayed?.list?.list?.size} uiAdapter=${parent?.adapter?.javaClass?.simpleName} sameList=${record?.list?.list?.map { it.url } == displayed?.list?.list?.map { it.url }}")
        if (record != null && tab != null) {
            val raw = CatalogShortcutTarget(next.url, next.catalog.type.orEmpty(), next.catalog.id.orEmpty())
            next.provider.homeCursorFor(raw, record.list.list)
        }
    }

    // The unfiltered catalog shares one raw cursor with Home, but filtered
    // genre pages must never be inserted into the unfiltered Home list.
    private suspend fun syncHomePage(
        source: StremioC, catalog: StremioC.Catalog, requestedOffset: Int,
        page: List<SearchResponse>, rawCount: Int, more: Boolean
    ) {
        val type = catalog.type ?: return
        val id = catalog.id ?: return
        val target = CatalogShortcutTarget(addonUrl ?: return, type, id)
        CatalogSyncTrace.emit("SYNC_BEGIN",
            "p=${CatalogSyncTrace.provider(source)} target=${CatalogSyncTrace.target(target)} offset=$requestedOffset raw=$rawCount pageCount=${page.size} hasNext=$more")
        val basePath = "/catalog/" + type.urlEncodeLocal() + "/" + id.urlEncodeLocal()
        val tab = source.mainPage.firstOrNull {
            it.data.startsWith("$basePath/") || it.data.startsWith("$basePath.json")
        }
        if (tab == null) {
            CatalogSyncTrace.emit("SYNC_REJECT", "reason=NO_TAB target=${CatalogSyncTrace.target(target)}")
            return
        }

        // CloudStream HomeViewModel owns another page counter and displayed list.
        val model = ViewModelProvider(activity)[HomeViewModel::class.java]
        if (model.apiName.value != source.name || tab.name in model.lock) {
            CatalogSyncTrace.emit("SYNC_REJECT",
                "reason=${if (model.apiName.value != source.name) "API_MISMATCH" else "HOME_LOCK"} homeApi=${model.apiName.value} source=${source.name} tab=${tab.name} locks=${model.lock.size}")
            return
        }
        val success = model.page.value as? Resource.Success<*>
        if (success == null) {
            CatalogSyncTrace.emit("SYNC_REJECT",
                "reason=HOME_NOT_SUCCESS state=${model.page.value?.javaClass?.simpleName}")
            return
        }
        val record = (success.value as? Map<*, *>)?.get(tab.name)
            as? HomeViewModel.ExpandableHomepageList
        if (record == null) {
            CatalogSyncTrace.emit("SYNC_REJECT", "reason=NO_MODEL_ROW tab=${tab.name}")
            return
        }
        val parent = activity.findViewById<RecyclerView>(R.id.home_master_recycler)
        val parentAdapter = parent?.adapter as? ParentItemAdapter
        if (parentAdapter == null) {
            CatalogSyncTrace.emit("SYNC_REJECT",
                "reason=NO_HOME_ADAPTER tab=${tab.name} parent=${parent != null} adapter=${parent?.adapter?.javaClass?.simpleName}")
            return
        }
        val displayed = parentAdapter.immutableCurrentList.firstOrNull { it.list.name == tab.name }
        if (displayed == null) {
            CatalogSyncTrace.emit("SYNC_REJECT", "reason=NO_DISPLAYED_ROW tab=${tab.name} rows=${parentAdapter.immutableCurrentList.size}")
            return
        }
        val sameItems = record.list.list.map { it.url } == displayed.list.list.map { it.url }
        val cursor = source.homeCursorFor(target, record.list.list)
        if (record.currentPage != displayed.currentPage || !sameItems || cursor != requestedOffset) {
            CatalogSyncTrace.emit("SYNC_REJECT",
                "reason=${if (record.currentPage != displayed.currentPage) "PAGE_MISMATCH" else if (!sameItems) "ITEM_MISMATCH" else "CURSOR_MISMATCH"} tab=${tab.name} modelPage=${record.currentPage} uiPage=${displayed.currentPage} modelCount=${record.list.list.size} uiCount=${displayed.list.list.size} cursor=$cursor requested=$requestedOffset")
            return
        }

        // This checks the native page-flight and offset under the provider mutex.
        val accepted = source.adoptBrowserPage(target, requestedOffset, page, rawCount)
        if (accepted == null) {
            CatalogSyncTrace.emit("SYNC_REJECT", "reason=PROVIDER_ADOPT_REJECT target=${CatalogSyncTrace.target(target)}")
            return
        }
        val urls = record.list.list.mapTo(HashSet<String>()) { it.url }
        val added = accepted.filter { urls.add(it.url) }
        record.list = record.list.copy(list = record.list.list + added)
        record.currentPage++
        record.hasNext = more
        displayed.list = displayed.list.copy(list = displayed.list.list + added)
        displayed.currentPage = record.currentPage
        displayed.hasNext = more
        CatalogSyncTrace.emit("SYNC_OK",
            "tab=${tab.name} added=${added.size} accepted=${accepted.size} homeCount=${record.list.list.size} newHomePage=${record.currentPage} nextRawSkip=${requestedOffset + rawCount} next=$more")

        for (i in 0 until parent.childCount) {
            val row = parent.getChildAt(i)
            val holder = parent.findContainingViewHolder(row) ?: continue
            if (parentAdapter.getItemOrNull(holder.bindingAdapterPosition - parentAdapter.headers)
                !== displayed) continue
            row.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let { child ->
                (child.adapter as? HomeChildItemAdapter)?.apply {
                    hasNext = more
                    submitList(displayed.list.list)
                }
            }
            break
        }
    }

    private fun autoFillIfShort(ticket: Int) {
        grid.post {
            if (closed || ticket != generation || loading || !hasNext ||
                autoFillCount >= maxAutoFillPages || items.isEmpty() ||
                retry.visibility == View.VISIBLE || grid.height <= 0) return@post
            if (grid.isLayoutRequested || grid.childCount == 0) {
                grid.doOnNextLayout { autoFillIfShort(ticket) }
            } else if (!grid.canScrollVertically(1)) {
                autoFillCount++
                CatalogSyncTrace.emit("BROWSER_AUTOFILL",
                    "count=${items.size} skip=$offset height=${grid.height} page=$autoFillCount limit=$maxAutoFillPages")
                requestPage("autoFill")
            }
        }
    }

    private fun requestPage(reason: String = "scroll") {
        val selectedProvider = provider
        val selectedCatalog = catalog
        CatalogSyncTrace.emit("BROWSER_REQUEST",
            "reason=$reason provider=${CatalogSyncTrace.provider(selectedProvider)} catalog=${selectedCatalog?.type}/${selectedCatalog?.id} genre=${genre ?: "All"} offset=$offset items=${items.size} generation=$generation chosen=$chosen loading=$loading hasNext=$hasNext closed=$closed pendingHome=${pendingHome != null}")
        if (selectedProvider == null || selectedCatalog == null) return
        if (!chosen || loading || !hasNext || closed) return
        val ticket = generation
        val selectedGenre = genre
        val requestedOffset = offset
        loading = true
        val firstPage = items.isEmpty()
        if (firstPage) initialProgress(true) else pageProgress(true)
        retry.visibility = View.GONE
        job = scope.launch {
            try {
                val (page, rawCount) = withContext(Dispatchers.IO) {
                    selectedProvider.browsePage(selectedCatalog, selectedGenre, requestedOffset)
                }
                if (ticket != generation) {
                    CatalogSyncTrace.emit("BROWSER_STALE_RESULT", "requestGeneration=$ticket current=$generation requested=$requestedOffset raw=$rawCount")
                    return@launch
                }
                val fresh = page.filter { seen.add(it.url) }
                val repeated = !pageSignatures.add(page.map { it.url })
                items.addAll(fresh)
                offset += rawCount // Never advance by the deduplicated display count.
                hasNext = selectedCatalog.supportsExtra("skip") && rawCount > 0 && !repeated && fresh.isNotEmpty()
                adapter.submitList(items.toList()) {
                    if (ticket == generation && !closed && firstPage) initialProgress(false)
                }
                CatalogSyncTrace.emit("BROWSER_PAGE_RESULT",
                    "catalog=${selectedCatalog.type}/${selectedCatalog.id} genre=${selectedGenre ?: "All"} requested=$requestedOffset raw=$rawCount parsed=${page.size} new=${fresh.size} repeated=$repeated visible=${items.size} nextSkip=$offset hasNext=$hasNext")
                if (selectedGenre == null) {
                    try {
                        syncHomePage(selectedProvider, selectedCatalog, requestedOffset, page, rawCount, hasNext)
                    } catch (e: CancellationException) { throw e
                    } catch (t: Throwable) {
                        android.util.Log.w("StremioC", "Home catalog page sync skipped", t)
                    }
                }
                retry.visibility = View.GONE
                autoFillIfShort(ticket)
            } catch (e: CancellationException) {
                CatalogSyncTrace.emit("BROWSER_CANCEL", "generation=$ticket offset=$requestedOffset")
                throw e
            } catch (e: Exception) {
                CatalogSyncTrace.emit("BROWSER_ERROR", "generation=$ticket offset=$requestedOffset type=${e.javaClass.simpleName} msgHash=${e.message?.hashCode()?.toString(16)}")
                if (ticket == generation) {
                    Toast.makeText(activity, "Unable to load catalog. Please retry.", Toast.LENGTH_SHORT).show()
                    retry.visibility = View.VISIBLE
                }
            } finally {
                if (ticket == generation) {
                    loading = false
                    if (firstPage && items.isEmpty()) initialProgress(false)
                    if (!firstPage) pageProgress(false)
                }
            }
        }
    }
}

internal data class CatalogShortcutTarget(val addonUrl: String, val type: String, val catalogId: String, val genre: String? = null)
internal data class CatalogHomeSnapshot(
    val target: CatalogShortcutTarget,
    val items: List<SearchResponse>,
    val nextSkip: Int,
    val hasNext: Boolean
)

/** Route StremioC home-row headings without modifying native poster data. */
internal class CatalogHomeEntryHook(
    private val open: (CatalogShortcutTarget, CatalogHomeSnapshot?) -> Unit,
    private val onPreviewView: (TextView?) -> Unit,
    private val onPhoneHero: (RecyclerView?) -> Unit
) {
    private val rows = HashMap<RecyclerView, Row>()
    private var master: RecyclerView? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { scanRows() }

    private fun scanRows() {
        val parent = master ?: return
        for (i in 0 until parent.childCount) bindRow(parent.getChildAt(i))
        onPreviewView(parent.findViewById<TextView>(R.id.home_preview_description))
        onPhoneHero(parent)
    }

    private val parentListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) { bindRow(view) }
        override fun onChildViewDetachedFromWindow(view: View) {
            view.findViewById<RecyclerView>(R.id.home_child_recyclerview)
                ?.let { rows.remove(it)?.dispose() }
        }
    }

    fun attach(root: View) {
        val next = root.findViewById<RecyclerView>(R.id.home_master_recycler) ?: return
        if (master === next) return
        detach()
        master = next
        next.addOnChildAttachStateChangeListener(parentListener)
        next.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        scanRows()
        next.post { if (master === next) scanRows() }
    }

    fun detach() {
        master?.removeOnChildAttachStateChangeListener(parentListener)
        master?.viewTreeObserver?.let {
            if (it.isAlive) it.removeOnGlobalLayoutListener(layoutListener)
        }
        master = null
        onPreviewView(null)
        onPhoneHero(null)
        rows.values.toList().forEach { it.dispose() }
        rows.clear()
    }

    private fun bindRow(view: View) {
        val child = view.findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: return
        val adapter = child.adapter as? HomeChildItemAdapter ?: return
        if (rows[child]?.adapter === adapter) return
        rows.remove(child)?.dispose()
        Row(view, child, adapter).also {
            rows[child] = it
            it.install()
        }
    }

    private fun resolve(row: Row, cards: List<SearchResponse>): CatalogShortcutTarget? {
        val parent = master ?: return null
        val holder = parent.findContainingViewHolder(row.view) ?: return null
        val adapter = parent.adapter as? ParentItemAdapter ?: return null
        val position = holder.bindingAdapterPosition - adapter.headers
        val title = adapter.getItemOrNull(position)?.list?.name ?: return null
        val apiName = cards.firstOrNull()?.apiName ?: return null
        val provider = APIHolder.allProviders.filterIsInstance<StremioC>()
            .firstOrNull { it.name == apiName } ?: return null
        val path = provider.mainPage.firstOrNull { it.name == title }?.data ?: return null
        val route = path.substringAfter("/catalog/", "")
        val type = Uri.decode(route.substringBefore('/')).takeIf { it.isNotBlank() } ?: return null
        val id = Uri.decode(route.substringAfter('/', "").substringBefore('/').removeSuffix(".json"))
            .takeIf { it.isNotBlank() } ?: return null
        return CatalogShortcutTarget(provider.mainUrl, type, id)
    }

    private inner class Row(val view: View, private val child: RecyclerView, val adapter: HomeChildItemAdapter) {
        private val title = view.findViewById<View>(R.id.home_child_more_info)
        private val originalFocusable = title?.isFocusable == true
        private val originalForeground = title?.foreground
        private var queued = false
        private val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = schedule()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = schedule()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = schedule()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = schedule()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = schedule()
        }

        private fun homeTarget(): CatalogShortcutTarget? =
            adapter.immutableCurrentList.takeIf { it.isNotEmpty() }?.let { resolve(this, it) }

        private fun refreshFocus() {
            if (isLayout(TV)) title?.isFocusable = homeTarget() != null || originalFocusable
        }

        private fun schedule() {
            if (queued) return
            queued = true
            child.post {
                queued = false
                if (rows[child] === this) refreshFocus()
            }
        }

        private fun openTarget(target: CatalogShortcutTarget) {
            val cards = adapter.immutableCurrentList
            val providers = APIHolder.allProviders.filterIsInstance<StremioC>()
            val provider = providers.firstOrNull { it.mainUrl == target.addonUrl }
            CatalogSyncTrace.emit("ROW_OPEN",
                "target=${CatalogSyncTrace.target(target)} ${CatalogSyncTrace.list(cards)} uiHasNext=${adapter.hasNext} selected=${CatalogSyncTrace.provider(provider)} nameMatches=${providers.count { it.name == cards.firstOrNull()?.apiName }} urlMatches=${providers.count { it.mainUrl == target.addonUrl }}")
            val cursor = provider?.homeCursorFor(target, cards)
            val snapshot = cursor?.let {
                CatalogHomeSnapshot(target, cards.toList(), it, adapter.hasNext)
            }
            CatalogSyncTrace.emit("ROW_HANDOFF",
                "target=${CatalogSyncTrace.target(target)} cursor=$cursor snapshot=${snapshot != null} visible=${cards.size}")
            open(target, snapshot)
        }

        fun install() {
            CatalogSyncTrace.emit("ROW_INSTALL",
                "titleId=${title?.id} rowItems=${adapter.immutableCurrentList.size} resolved=${CatalogSyncTrace.target(homeTarget())}")
            adapter.registerAdapterDataObserver(observer)
            refreshFocus()
            val control = title ?: return
            if (isLayout(TV)) control.foreground = themedPressedForeground(control)
            control.setOnClickListener { homeTarget()?.let(::openTarget) }
            val slop = ViewConfiguration.get(control.context).scaledTouchSlop
            var pressedTarget: CatalogShortcutTarget? = null
            var downX = 0f
            var downY = 0f
            var downAt = 0L
            var moved = false
            control.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressedTarget = homeTarget()
                        if (pressedTarget == null) return@setOnTouchListener false
                        downX = event.x
                        downY = event.y
                        downAt = event.eventTime
                        moved = false
                        v.isPressed = true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (pressedTarget == null) return@setOnTouchListener false
                        if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) moved = true
                        if (moved) v.isPressed = false
                    }
                    MotionEvent.ACTION_UP -> {
                        val target = pressedTarget ?: return@setOnTouchListener false
                        pressedTarget = null
                        v.isPressed = false
                        if (!moved && event.eventTime - downAt < ViewConfiguration.getLongPressTimeout() &&
                            homeTarget() == target) v.performClick()
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                        if (pressedTarget == null) return@setOnTouchListener false
                        pressedTarget = null
                        moved = true
                        v.isPressed = false
                    }
                    else -> return@setOnTouchListener pressedTarget != null
                }
                true
            }
            control.setOnKeyListener { _, key, event ->
                if (key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER &&
                    key != KeyEvent.KEYCODE_NUMPAD_ENTER && key != KeyEvent.KEYCODE_BUTTON_A
                ) return@setOnKeyListener false
                val target = homeTarget() ?: return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) control.performClick()
                true
            }
        }

        fun dispose() {
            adapter.unregisterAdapterDataObserver(observer)
            title?.apply {
                setOnTouchListener(null)
                setOnKeyListener(null)
                setOnClickListener(null)
                foreground = originalForeground
                isPressed = false
                if (isLayout(TV)) isFocusable = originalFocusable
            }
        }
    }
}
