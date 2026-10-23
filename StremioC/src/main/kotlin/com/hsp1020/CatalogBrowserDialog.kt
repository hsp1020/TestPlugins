package com.hsp1020

import com.facebook.shimmer.ShimmerFrameLayout
import com.facebook.shimmer.Shimmer
import androidx.cardview.widget.CardView
import android.view.LayoutInflater
import android.content.Context
import androidx.appcompat.app.AppCompatDialog
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.ViewConfiguration
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatSpinner
import androidx.appcompat.widget.SearchView
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
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.NavHostFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.ui.search.SearchViewModel
import com.lagradost.cloudstream3.ui.search.ExpandableSearchList
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.loadHomepageList
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import android.widget.ProgressBar
import android.view.inputmethod.EditorInfo
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
        type !in setOf(
            "com.lagradost.cloudstream3.ui.result.ResultTrailerPlayer",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone"
        )) return null
    if (id == R.id.navigation_results_tv &&
        type != "com.lagradost.cloudstream3.ui.result.ResultFragmentTv") return null
    if (id == R.id.navigation_player &&
        type != "com.lagradost.cloudstream3.ui.player.GeneratorPlayer") return null
    return primary.view
}

internal class StremioDetailHandoff(
    private val activity: FragmentActivity,
    private val dialog: AppCompatDialog,
    private val navigation: NavController?,
    private val onLeave: () -> Unit,
    private val onReturn: () -> Unit = { dialog.show() },
    private val onReady: () -> Unit = {},
    private val onReturnStart: () -> Unit = {},
    private val preserveNestedNavigation: Boolean = false
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
        if (!returning || !isActive || detailRevealEnd != null ||
            !view.isAttachedToWindow) return
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
        if (toOrigin) onReturnStart()
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
                    if (stable >= 3) {
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
                val ticket = generation
                view.doOnPreDraw {
                    if (ticket == generation && returning &&
                        navigation?.currentBackStackEntry?.id == returnTargetId &&
                        currentDestinationView() === view) watchReturnFrame(view)
                }
                return
            }
            if (!preserveNestedNavigation && returnEntryId != null &&
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
                recovering -> Unit
                detailEntryId == null -> detailEntryId = current
                current != detailEntryId && preserveNestedNavigation &&
                    controller.currentDestination?.id in setOf(
                        R.id.navigation_search, R.id.navigation_quick_search,
                        R.id.navigation_results_phone, R.id.navigation_results_tv,
                        R.id.navigation_player
                    ) -> {
                    if (pendingDetailDraw) {
                        pendingDetailDraw = false
                        handler.removeCallbacks(navigationTimeout)
                        dialog.hide()
                        onReady()
                    }
                }
                current != detailEntryId -> {
                    close()
                    onLeave()
                }
            }
        }
    }
}

private fun nativeCatalogRipple(context: Context, borderless: Boolean = false): android.graphics.drawable.Drawable? {
    val value = TypedValue()
    val attribute = if (borderless) android.R.attr.selectableItemBackgroundBorderless
        else android.R.attr.selectableItemBackground
    if (!context.theme.resolveAttribute(attribute, value, true) || value.resourceId == 0) return null
    return androidx.appcompat.content.res.AppCompatResources.getDrawable(context, value.resourceId)?.mutate()
}

internal fun catalogStyledSelector(activity: FragmentActivity): AppCompatSpinner {
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    return object : AppCompatSpinner(
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
            return true 
        }
    }.apply {
        id = View.generateViewId()
        minimumHeight = dp(44)
        isFocusable = true
        setPopupBackgroundDrawable(ColorDrawable(activity.colorFromAttribute(R.attr.primaryGrayBackground)))
        val cardBg = androidx.appcompat.content.res.AppCompatResources.getDrawable(activity, R.drawable.bg_color_both)?.mutate()
        val ripple = nativeCatalogRipple(activity)
        val backgroundDrawable = if (android.os.Build.VERSION.SDK_INT < 23 && cardBg != null && ripple != null)
            android.graphics.drawable.LayerDrawable(arrayOf(cardBg, ripple))
        else cardBg ?: ripple
        background = backgroundDrawable?.let {
            android.graphics.drawable.InsetDrawable(it, 0, dp(4), 0, dp(4))
        }
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            foreground = ripple?.let { android.graphics.drawable.InsetDrawable(it, 0, dp(4), 0, dp(4)) }
        }
    }
}

internal fun catalogStyledAdapter(
    activity: FragmentActivity, labels: List<String>, spinner: AppCompatSpinner,
    fontTypeface: Typeface?, textSize: Float, checkDrawable: android.graphics.drawable.Drawable?
): ArrayAdapter<String> {
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        return object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                super.getView(position, convertView, parent).also { row ->
                    ((row as? TextView) ?: row.findViewById<TextView>(android.R.id.text1))?.apply {
                        typeface = fontTypeface
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                        setTextColor(activity.colorFromAttribute(R.attr.textColor))
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(paddingLeft, paddingTop, dp(40), paddingBottom)
                    }
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val checked = position == spinner.selectedItemPosition
                val color = activity.colorFromAttribute(if (checked) R.attr.colorPrimary else R.attr.textColor)
                return object : LinearLayout(activity) {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            background?.setHotspot(event.x, event.y)
                            isPressed = true
                        }

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
                    val ripple = nativeCatalogRipple(activity)
                    if (android.os.Build.VERSION.SDK_INT >= 23) {
                        foreground = ripple
                    } else if (ripple != null) {
                        background = android.graphics.drawable.LayerDrawable(
                            arrayOf(ColorDrawable(rowColor), ripple))
                    }
                    minimumHeight = dp(48)
                    setPadding(dp(16), 0, dp(12), 0)
                    addView(TextView(activity).apply {
                        text = labels[position]
                        typeface = fontTypeface
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                        setTextColor(color)
                        gravity = Gravity.CENTER_VERTICAL
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, dp(48), 1f))
                    addView(ImageView(activity).apply {
                        if (checked) setImageDrawable(checkDrawable)
                        imageTintList = ColorStateList.valueOf(activity.colorFromAttribute(R.attr.colorPrimary))
                        contentDescription = "Selected item"
                        setPadding(dp(10), 0, dp(10), 0)
                        visibility = if (checked) View.VISIBLE else View.INVISIBLE
                    }, LinearLayout.LayoutParams(dp(40), dp(24)))
                }
            }
        }

}

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
                if (covered) preserveNavigation()
                SearchHelper.handleSearchClickCallback(click)
                if (!covered) dismiss()
            }
        }
    }

    private val inlineStore = ViewModelStore()
    private val inlineModel = ViewModelProvider(
        inlineStore, ViewModelProvider.NewInstanceFactory()
    )[SearchViewModel::class.java]
    private val inlineResults = RecyclerView(activity).apply {
        layoutManager = GridLayoutManager(activity, 1)
        setRecycledViewPool(ParentItemAdapter.sharedPool)
        setBackgroundColor(activity.colorFromAttribute(R.attr.primaryBlackBackground))
        visibility = View.GONE
    }
    private var inlineSheet: BottomSheetDialog? = null
    private val inlineAdapter = ParentItemAdapter(
        "StremioCCatalogInlineSearch".hashCode(),
        { click ->
            if (!closed && !detailHandoff.isActive) when (click.action) {
                SEARCH_ACTION_SHOW_METADATA -> SearchHelper.handleSearchClickCallback(click)
                SEARCH_ACTION_LOAD -> {
                    val covered = detailHandoff.begin()
                    if (covered) preserveNavigation()
                    SearchHelper.handleSearchClickCallback(click)
                    if (!covered) dismiss()
                }
            }
        },
        { item ->
            inlineSheet?.dismiss()
            inlineSheet = activity.loadHomepageList(
                item, dismissCallback = { inlineSheet = null },
                expandCallback = { provider -> inlineModel.expandAndReturn(provider) }
            )
        },
        expandCallback = { provider -> scope.launch { inlineModel.expandAndReturn(provider) } }
    )
    private val inlineLoading = ProgressBar(activity).apply { visibility = View.GONE }
    private val inlineEmpty = TextView(activity).apply {
        text = "No results."
        gravity = Gravity.CENTER
        setTextColor(activity.colorFromAttribute(R.attr.textColor))
        visibility = View.GONE
    }
    private var inlineActive = false
    private var inlineSubmitted = false
    private var inlineProviders = emptyList<String>()
    private var inlineInput: SearchView? = null
    private var inlineSelectors = emptyList<View>()
    private var inlineHeader: AppBarLayout? = null
    private var inlineHeaderFlags = 0
    private var inlineRetryVisibility = View.GONE
    private val inlineListObserver = Observer<Map<String, ExpandableSearchList>> { lists ->
        if (!closed && inlineActive && inlineSubmitted) {
            val groups = inlineProviders.mapNotNull { name ->
                lists[name]?.let { result ->
                    HomeViewModel.ExpandableHomepageList(
                        HomePageList(name, activity.filterSearchResultByFilmQuality(result.list)),
                        result.currentPage, result.hasNext
                    )
                }
            }
            inlineAdapter.submitList(groups)
            if (groups.any { it.list.list.isNotEmpty() }) inlineEmpty.visibility = View.GONE
        }
    }
    private val inlineStatusObserver = Observer<Resource<ExpandableSearchList>> { state ->
        if (!closed && inlineActive && inlineSubmitted) {
            inlineLoading.visibility = if (state is Resource.Loading) View.VISIBLE else View.GONE
            if (state is Resource.Success || state is Resource.Failure) {
                inlineEmpty.visibility = if (inlineAdapter.immutableCurrentList.all {
                    it.list.list.isEmpty()
                }) View.VISIBLE else View.GONE
            }
        }
    }
    private fun catalogSearchProviders(): List<String> {
        val saved = SettingsRepository.loadLinks(prefs).asSequence()
            .filter { it.enabled && it.link.isNotBlank() && StremioC.isCatalogAddonEnabled(it.link) }
            .map { stremioManifestUrl(it.link.fixSourceUrl()) }.toSet()
        return APIHolder.allProviders.filterIsInstance<StremioC>()
            .filter { !it.hasMainPage && it.mainUrl.isNotBlank() &&
                stremioManifestUrl(it.mainUrl.fixSourceUrl()) in saved }
            .map { it.name }.distinct()
    }
    private fun enterInlineSearch() {
        if (closed || inlineActive) return
        inlineActive = true
        inlineRetryVisibility = retry.visibility
        inlineSelectors.forEach { it.visibility = View.GONE }
        grid.visibility = View.INVISIBLE
        inlineResults.visibility = View.VISIBLE
        inlineLoading.visibility = View.GONE
        inlineEmpty.visibility = View.GONE
        retry.visibility = View.GONE
        inlineHeader?.let { header ->
            header.setExpanded(true, false)
            (header.getChildAt(0).layoutParams as? AppBarLayout.LayoutParams)?.let { params ->
                params.scrollFlags = 0
                header.getChildAt(0).layoutParams = params
            }
        }
    }
    private fun exitInlineSearch() {
        if (!inlineActive) return
        inlineActive = false
        inlineSubmitted = false
        inlineModel.searchAndCancel("", providersActive = inlineProviders.toSet())
        inlineAdapter.submitList(emptyList())
        inlineResults.visibility = View.GONE
        inlineLoading.visibility = View.GONE
        inlineEmpty.visibility = View.GONE
        inlineSelectors.forEach { it.visibility = View.VISIBLE }
        grid.visibility = if (initialSkeleton) View.INVISIBLE else View.VISIBLE
        retry.visibility = inlineRetryVisibility
        inlineHeader?.let { header ->
            (header.getChildAt(0).layoutParams as? AppBarLayout.LayoutParams)?.let { params ->
                params.scrollFlags = inlineHeaderFlags
                header.getChildAt(0).layoutParams = params
            }
        }
        inlineInput?.clearFocus()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(inlineInput?.windowToken, 0)
    }
    private fun submitInlineSearch(value: String) {
        val query = value.trim()
        if (query.length < 2 || closed) return
        val providers = catalogSearchProviders()
        if (providers.isEmpty()) {
            Toast.makeText(activity, "No searchable catalog addons.", Toast.LENGTH_SHORT).show()
            return
        }
        enterInlineSearch()
        inlineProviders = providers
        inlineSubmitted = true
        inlineLoading.visibility = View.VISIBLE
        inlineEmpty.visibility = View.GONE
        inlineAdapter.submitList(emptyList())
        inlineModel.reloadRepos()
        inlineModel.searchAndCancel(query, providersActive = providers.toSet(), isQuickSearch = false)
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(inlineInput?.windowToken, 0)
        inlineInput?.clearFocus()
    }
    private var links = emptyList<LinkItem>()
    private data class CatalogSource(val url: String, val provider: StremioC, val catalog: StremioC.Catalog)
    private var catalogs = emptyList<CatalogSource>()
    private var selectedType = initialCatalog?.type ?: "movie"
    private val directTarget = initialCatalog?.takeIf { !it.genre.isNullOrBlank() }
    private var pendingCatalog = initialCatalog
    private var pendingGenre = initialCatalog?.genre
    private var pendingHome = initialHome?.takeIf { it.target == initialCatalog }
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

    private val maxAutoFillPages = 12
    private val homeTitleFocus = if (initialCatalog != null)
        activity.currentFocus?.takeIf { it.id == R.id.home_child_more_info } else null
    private fun initialProgress(active: Boolean) {
        initialSkeleton = active
        grid.visibility = if (active || inlineActive) View.INVISIBLE else View.VISIBLE
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
    private var pendingBackPress: Runnable? = null
    private var backButton: ImageView? = null
    private val navigation by lazy {
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)?.navController
    }
    private val detailHandoff by lazy {
        StremioDetailHandoff(activity, dialog, navigation, ::dismiss,
            onReturn = {
                if (!closed) {
                    fitWindowToNavigation(force = true)
                    if (!dialog.isShowing) dialog.show()
                }
            },
            onReady = ::releaseNavigation,
            onReturnStart = {
                releaseNavigation()
                if (!closed) {
                    fitWindowToNavigation(force = true)
                    dialog.show()
                }
            },
            preserveNestedNavigation = true)
    }
    private var navigationSnapshot: BitmapDrawable? = null
    private var pendingDestinationCheck: Runnable? = null
    private var suspendedForNavigation = false
    private var originEntryId: String? = null
    private val windowBoundsListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        fitWindowToNavigation()
    }
    private val destinationListener = NavController.OnDestinationChangedListener { controller, destination, _ ->
        if (closed || detailHandoff.isActive) return@OnDestinationChangedListener
        val entryId = controller.currentBackStackEntry?.id
        if (destination.id == R.id.navigation_search || destination.id == R.id.navigation_library) {
            cancelDestinationCheck()
            if (dialog.window?.decorView?.visibility == View.VISIBLE) {
                suspendedForNavigation = true
                cancelPendingBackPress()
                dialog.hide()
                releaseNavigation()
            }
            return@OnDestinationChangedListener
        }
        when {
            destination.id == R.id.navigation_home &&
                (suspendedForNavigation || entryId != originEntryId) -> {
                cancelDestinationCheck()
                val selected = ViewModelProvider(activity)[HomeViewModel::class.java].apiName.value
                if (selected != null && APIHolder.allProviders.none {
                        it is StremioC && it.hasMainPage && it.name == selected
                    }) dismiss()
                else {
                    suspendedForNavigation = false
                    originEntryId = entryId
                    dialog.show()
                    fitWindowToNavigation(force = true)
                }
            }
            suspendedForNavigation -> Unit
            originEntryId != null && entryId != originEntryId ->
                awaitDestination(destination.id, entryId)
        }
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
            scope.launch { reloadLinks() }
        }
    }
    private val manifestListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!fetchingCatalogs && links.any { key == stremioCatalogKey(it.link) }) {
            scope.launch { reloadLinks() }
        }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun cancelPendingBackPress() {
        pendingBackPress?.let { pending -> backButton?.removeCallbacks(pending) }
        pendingBackPress = null
        backButton?.isPressed = false
    }

    private fun preserveNavigation() {
        releaseNavigation()
        val source = listOf(R.id.nav_rail_view, R.id.nav_view)
            .mapNotNull { activity.findViewById<View>(it) }
            .firstOrNull { it.isShown && it.width > 0 && it.height > 0 } ?: return
        val decor = activity.window.decorView
        val origin = IntArray(2)
        val location = IntArray(2)
        decor.getLocationOnScreen(origin)
        source.getLocationOnScreen(location)
        val snapshot = runCatching {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                source.draw(Canvas(it))
            }
        }.getOrNull() ?: return
        navigationSnapshot = BitmapDrawable(activity.resources, snapshot).apply {
            setBounds(location[0] - origin[0], location[1] - origin[1],
                location[0] - origin[0] + source.width,
                location[1] - origin[1] + source.height)
            decor.overlay.add(this)
        }
    }

    private fun releaseNavigation() {
        navigationSnapshot?.let { activity.window.decorView.overlay.remove(it) }
        navigationSnapshot = null
    }

    private fun cancelDestinationCheck() {
        pendingDestinationCheck?.let { activity.window.decorView.removeCallbacks(it) }
        pendingDestinationCheck = null
    }

    private fun awaitDestination(destinationId: Int, entryId: String?) {
        cancelDestinationCheck()
        val decor = activity.window.decorView
        var stable = 0
        lateinit var check: Runnable
        check = Runnable {
            if (closed || detailHandoff.isActive ||
                navigation?.currentDestination?.id != destinationId) {
                pendingDestinationCheck = null
                return@Runnable
            }
            val host = activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            val fragment = host?.childFragmentManager?.primaryNavigationFragment
            val view = fragment?.view
            val destinationClass = (navigation?.currentDestination as? FragmentNavigator.Destination)?.className
            val ready = fragment != null && fragment.isAdded &&
                (destinationClass == null || fragment.javaClass.name == destinationClass) &&
                view?.isAttachedToWindow == true && view.isShown &&
                view.width > 0 && view.height > 0 && !view.isLayoutRequested &&
                view.animation?.hasEnded() != false
            stable = if (ready) stable + 1 else 0
            if (stable >= 2) {
                pendingDestinationCheck = null
                if (destinationId == R.id.navigation_home) {
                    originEntryId = entryId
                    dialog.show()
                    fitWindowToNavigation(force = true)
                } else {
                    suspendedForNavigation = true
                    cancelPendingBackPress()
                    dialog.hide()
                    releaseNavigation()
                }
            } else decor.postOnAnimation(check)
        }
        pendingDestinationCheck = check
        decor.postOnAnimation(check)
    }

    private fun fitWindowToNavigation(force: Boolean = false) {
        if (closed || (!dialog.isShowing && !force) ||
            ((detailHandoff.isActive || pendingDestinationCheck != null ||
                suspendedForNavigation) && !force)) return
        val window = dialog.window ?: return
        val decor = activity.window.decorView
        val width = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val height = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val activityOrigin = IntArray(2)
        decor.getLocationOnScreen(activityOrigin)
        val rail = activity.findViewById<View>(R.id.nav_rail_view)
        val home = navigation?.currentDestination?.id == R.id.navigation_home
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val railRight = if (rail?.isShown == true && rail.width > 0) {
            val at = IntArray(2)
            rail.getLocationOnScreen(at)
            (at[0] - activityOrigin[0] + rail.width).coerceIn(0, width)
        } else if (home && landscape) {
            activity.resources.getDimensionPixelSize(R.dimen.nav_rail_view_width).coerceAtMost(width)
        } else 0
        val bottomNavigation = activity.findViewById<View>(R.id.nav_view)
        val bottom = if (bottomNavigation?.isShown == true && bottomNavigation.height > 0) {
            val at = IntArray(2)
            bottomNavigation.getLocationOnScreen(at)
            (at[1] - activityOrigin[1]).coerceIn(0, height)
        } else if (home && !landscape) {
            (height - activity.resources.getDimensionPixelSize(R.dimen.nav_view_height)).coerceAtLeast(0)
        } else height
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setGravity(Gravity.TOP or Gravity.END)
        window.setLayout((width - railRight).coerceAtLeast(1), bottom.coerceAtLeast(1))
    }

    private fun button(label: String) = MaterialButton(activity).apply {
        id = View.generateViewId()
        text = label
        isAllCaps = false
        isFocusable = true
        minHeight = dp(48)
    }

    private fun selector() = catalogStyledSelector(activity)

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
            }, LinearLayout.LayoutParams(dp(82), dp(44)))
            addView(FrameLayout(activity).apply {
                addView(spinner, FrameLayout.LayoutParams(-1, -1))
                addView(arrow, FrameLayout.LayoutParams(dp(40), -1, Gravity.END))
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
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
        adapter = catalogStyledAdapter(activity, labels, this, selectorLabelStyle.typeface,
            selectorLabelStyle.textSize,
            if (checkIconId != 0) pluginResources.getDrawable(checkIconId, null) else null)
        if (selected in labels.indices) setSelection(selected)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!closed && position in labels.indices) choose(position)
            }
        }
    }

    fun show(onDismiss: () -> Unit) {

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.setBackgroundColor(activity.colorFromAttribute(R.attr.primaryBlackBackground))
        val headerColor = activity.colorFromAttribute(R.attr.primaryBlackBackground)
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
            setBackgroundColor(headerColor)
            val navigationRow = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
                val back = ImageView(activity).apply {
                    setImageResource(R.drawable.ic_baseline_arrow_back_24)
                    imageTintList = ColorStateList.valueOf(activity.colorFromAttribute(R.attr.white))
                    contentDescription = activity.getString(R.string.go_back)
                    isFocusable = true
                    isClickable = true
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    val ripple = TypedValue()
                    if (activity.theme.resolveAttribute(
                            android.R.attr.selectableItemBackgroundBorderless, ripple, true
                        ) && ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setOnClickListener {
                        if (pendingBackPress != null || closed || !dialog.isShowing || !isShown ||
                            detailHandoff.isActive || suspendedForNavigation) return@setOnClickListener
                        val finish = Runnable {
                            pendingBackPress = null
                            isPressed = false
                            if (!closed && dialog.isShowing && isShown &&
                                !detailHandoff.isActive && !suspendedForNavigation &&
                                !activity.isFinishing && !activity.isDestroyed)
                                dialog.onBackPressedDispatcher.onBackPressed()
                        }
                        pendingBackPress = finish
                        postOnAnimation {
                            if (pendingBackPress !== finish) return@postOnAnimation
                            if (closed || !dialog.isShowing || !isShown ||
                                detailHandoff.isActive || suspendedForNavigation ||
                                activity.isFinishing || activity.isDestroyed) {
                                pendingBackPress = null
                                return@postOnAnimation
                            }
                            isPressed = true
                            postDelayed(finish, 120L)
                        }
                    }
                }
                backButton = back
                addView(back, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = dp(7)
                })
                val searchField = FrameLayout(activity).apply {
                    setBackgroundResource(R.drawable.search_background)
                    val input = SearchView(activity).apply {
                        queryHint = activity.getString(R.string.search_hint)
                        setIconifiedByDefault(false)
                        isIconified = false
                        findViewById<View>(androidx.appcompat.R.id.search_mag_icon)?.visibility = View.GONE
                        imeOptions = EditorInfo.IME_ACTION_SEARCH
                        setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                            override fun onQueryTextSubmit(query: String): Boolean {
                                submitInlineSearch(query)
                                return true
                            }
                            override fun onQueryTextChange(newText: String): Boolean {
                                if (newText.isBlank()) exitInlineSearch()
                                else if (inlineActive) {
                                    inlineSubmitted = false
                                    inlineLoading.visibility = View.GONE
                                    inlineEmpty.visibility = View.GONE
                                    inlineAdapter.submitList(emptyList())
                                }
                                return true
                            }
                        })
                        clearFocus()
                    }
                    inlineInput = input
                    addView(ImageView(activity).apply {
                        setImageResource(R.drawable.search_icon)
                        imageTintList = ColorStateList.valueOf(activity.colorFromAttribute(R.attr.white))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        isClickable = false
                        isFocusable = false
                    }, FrameLayout.LayoutParams(dp(24), dp(24),
                        Gravity.START or Gravity.CENTER_VERTICAL))
                    addView(input, FrameLayout.LayoutParams(-1, -1).apply {
                        marginStart = dp(13)
                    })
                    setOnClickListener {
                        input.requestFocus()
                        input.findViewById<android.widget.EditText>(
                            androidx.appcompat.R.id.search_src_text
                        )?.let { editor ->
                            editor.requestFocus()
                            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                                ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                }
                addView(searchField, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginStart = dp(45)
                })
            }
            addView(navigationRow, LinearLayout.LayoutParams(-1, dp(44)))
            inlineSelectors = listOf(
                selectorRow("Type", typeSelector, typeArrow),
                selectorRow("Catalog", catalogSelector, catalogArrow),
                selectorRow("Genre", genreSelector, genreArrow)
            )
            inlineSelectors.forEach { selector ->
                addView(selector, LinearLayout.LayoutParams(-1, dp(44)).apply {
                    marginStart = dp(10)
                    marginEnd = dp(12)
                })
            }
        }
        val header = AppBarLayout(activity).apply {
            setBackgroundColor(headerColor)
            stateListAnimator = null
            setLiftOnScroll(false)
            addView(controls, AppBarLayout.LayoutParams(-1, -2).apply {
                scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                    AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
                inlineHeaderFlags = scrollFlags
            })
        }

        inlineHeader = header
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
            inlineResults.adapter = inlineAdapter
            addView(inlineResults, FrameLayout.LayoutParams(-1, -1))
            addView(inlineEmpty, FrameLayout.LayoutParams(-1, -1))
            addView(inlineLoading, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
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
        inlineModel.currentSearch.observe(activity, inlineListObserver)
        inlineModel.searchResponse.observe(activity, inlineStatusObserver)
        dialog.setOnDismissListener {
            cancelPendingBackPress()
            backButton = null
            initialProgress(false)
            pageProgress(false)
            closed = true
            generation++
            scope.cancel()
            prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
            manifestPrefs.unregisterOnSharedPreferenceChangeListener(manifestListener)
            activity.lifecycle.removeObserver(hostObserver)
            cancelDestinationCheck()
            inlineModel.currentSearch.removeObserver(inlineListObserver)
            inlineModel.searchResponse.removeObserver(inlineStatusObserver)
            inlineStore.clear()
            inlineSheet?.dismiss()
            inlineSheet = null
            inlineInput?.setOnQueryTextListener(null)
            inlineResults.adapter = null
            releaseNavigation()
            navigation?.removeOnDestinationChangedListener(destinationListener)
            activity.window.decorView.removeOnLayoutChangeListener(windowBoundsListener)
            activity.findViewById<View>(R.id.nav_rail_view)
                ?.removeOnLayoutChangeListener(windowBoundsListener)
            activity.findViewById<View>(R.id.nav_view)
                ?.removeOnLayoutChangeListener(windowBoundsListener)
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
        originEntryId = navigation?.currentBackStackEntry?.id
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
        ViewCompat.requestApplyInsets(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(activity.colorFromAttribute(R.attr.primaryBlackBackground)))
        fitWindowToNavigation()
        dialog.window?.setWindowAnimations(0)
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
        inlineInput?.clearFocus()
        activity.window.decorView.addOnLayoutChangeListener(windowBoundsListener)
        activity.findViewById<View>(R.id.nav_rail_view)
            ?.addOnLayoutChangeListener(windowBoundsListener)
        activity.findViewById<View>(R.id.nav_view)
            ?.addOnLayoutChangeListener(windowBoundsListener)
        navigation?.addOnDestinationChangedListener(destinationListener)
        activity.lifecycle.addObserver(hostObserver)
        prefs.registerOnSharedPreferenceChangeListener(settingsListener)
        manifestPrefs.registerOnSharedPreferenceChangeListener(manifestListener)
        reloadLinks()
    }

    fun dismiss() {
        if (closed) return
        closed = true
        cancelPendingBackPress()
        detailHandoff.close()
        generation++
        scope.cancel()
        dialog.dismiss() 
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
            links.distinctBy { it.link }.mapNotNull { link ->
                providerFor(link.link)?.let { source ->
                    link.link to if (source.hasMainPage) source else StremioC(link.link, link.name, link.id)
                }
            }
        }
        val activeSources = sources.filter { StremioC.isCatalogAddonEnabled(it.first) }
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
            requestPage()
        }
        if (initialGenre != null && genreIndex < 0) {
            Toast.makeText(activity, "Requested genre is not available.", Toast.LENGTH_SHORT).show()
            return
        }
        genre = genres.getOrNull(genreIndex)
        chosen = true
        hasNext = true
        val liveSnapshot = liveHomeSnapshot(next)
        val snapshot = liveSnapshot ?: pendingHome
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
        pendingHome = null
        if (home != null) {

            items.addAll(home.items)
            seen.addAll(home.items.map { it.url })
            offset = home.nextSkip
            hasNext = home.hasNext && next.catalog.supportsExtra("skip")
            adapter.submitList(items.toList()) { if (!closed) initialProgress(false) }
            autoFillIfShort(generation)
        } else {
            requestPage()
        }
    }

    private fun liveHomeSnapshot(source: CatalogSource): CatalogHomeSnapshot? {
        if (genre != null) {
            return null
        }
        val type = source.catalog.type ?: return null
        val id = source.catalog.id ?: return null
        val path = "/catalog/" + type.urlEncodeLocal() + "/" + id.urlEncodeLocal()
        val tab = source.provider.mainPage.firstOrNull {
            it.data.startsWith("$path/") || it.data.startsWith("$path.json")
        } ?: return null
        val model = ViewModelProvider(activity)[HomeViewModel::class.java]
        if (model.apiName.value != source.provider.name) return null
        val record = ((model.page.value as? Resource.Success<*>)?.value as? Map<*, *>)
            ?.get(tab.name) as? HomeViewModel.ExpandableHomepageList ?: return null
        val parent = activity.findViewById<RecyclerView>(R.id.home_master_recycler)
        val parentAdapter = parent?.adapter as? ParentItemAdapter ?: return null
        val displayed = parentAdapter.immutableCurrentList
            .firstOrNull { it.list.name == tab.name } ?: return null
        if (record.currentPage != displayed.currentPage ||
            record.list.list.map { it.url } != displayed.list.list.map { it.url }) return null
        val items = record.list.list.takeIf { it.isNotEmpty() } ?: return null
        val target = CatalogShortcutTarget(source.url, type, id)
        val cursor = source.provider.homeCursorFor(target, items) ?: return null
        return CatalogHomeSnapshot(target, items.toList(), cursor, displayed.hasNext)
    }

    private suspend fun syncHomePage(
        source: StremioC, catalog: StremioC.Catalog, requestedOffset: Int,
        page: List<SearchResponse>, rawCount: Int, more: Boolean
    ) {
        val type = catalog.type ?: return
        val id = catalog.id ?: return
        val target = CatalogShortcutTarget(addonUrl ?: return, type, id)
        val basePath = "/catalog/" + type.urlEncodeLocal() + "/" + id.urlEncodeLocal()
        val tab = source.mainPage.firstOrNull {
            it.data.startsWith("$basePath/") || it.data.startsWith("$basePath.json")
        }
        if (tab == null) {
            return
        }

        val model = ViewModelProvider(activity)[HomeViewModel::class.java]
        if (model.apiName.value != source.name || tab.name in model.lock) {
            return
        }
        val success = model.page.value as? Resource.Success<*>
        if (success == null) {
            return
        }
        val record = (success.value as? Map<*, *>)?.get(tab.name)
            as? HomeViewModel.ExpandableHomepageList
        if (record == null) {
            return
        }
        val parent = activity.findViewById<RecyclerView>(R.id.home_master_recycler)
        val parentAdapter = parent?.adapter as? ParentItemAdapter
        if (parentAdapter == null) {
            return
        }
        val displayed = parentAdapter.immutableCurrentList.firstOrNull { it.list.name == tab.name }
        if (displayed == null) {
            return
        }
        val sameItems = record.list.list.map { it.url } == displayed.list.list.map { it.url }
        val cursor = source.homeCursorFor(target, record.list.list)
        if (record.currentPage != displayed.currentPage || !sameItems || cursor != requestedOffset) {
            return
        }

        val accepted = source.adoptBrowserPage(target, requestedOffset, page, rawCount)
        if (accepted == null) {
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
                requestPage()
            }
        }
    }

    private fun requestPage() {
        val selectedProvider = provider
        val selectedCatalog = catalog
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
                    return@launch
                }
                val fresh = page.filter { seen.add(it.url) }
                val repeated = !pageSignatures.add(page.map { it.url })
                items.addAll(fresh)
                offset += rawCount 
                hasNext = selectedCatalog.supportsExtra("skip") && rawCount > 0 && !repeated && fresh.isNotEmpty()
                adapter.submitList(items.toList()) {
                    if (ticket == generation && !closed && firstPage) initialProgress(false)
                }
                if (selectedGenre == null) {
                    try {
                        syncHomePage(selectedProvider, selectedCatalog, requestedOffset, page, rawCount, hasNext)
                    } catch (e: CancellationException) { throw e
                    } catch (t: Throwable) {
                    }
                }
                retry.visibility = View.GONE
                autoFillIfShort(ticket)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
            val cursor = provider?.homeCursorFor(target, cards)
            val snapshot = cursor?.let {
                CatalogHomeSnapshot(target, cards.toList(), it, adapter.hasNext)
            }
            open(target, snapshot)
        }

        fun install() {
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
