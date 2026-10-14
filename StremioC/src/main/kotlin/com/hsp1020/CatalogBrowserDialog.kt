package com.hsp1020

import androidx.appcompat.app.AppCompatDialog
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
import android.widget.AbsListView
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
import android.graphics.Canvas
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
    private val loadingIndicator = TextView(activity).apply {
        text = "loading.."
        typeface = selectorLabelStyle.typeface
        setTextSize(TypedValue.COMPLEX_UNIT_PX, selectorLabelStyle.textSize)
        setTextColor(activity.colorFromAttribute(R.attr.textColor))
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        visibility = View.GONE
    }
    private val grid = AutofitRecyclerView(activity)
    private val adapter = SearchAdapter(grid, false) { click ->
        if (!closed) when (click.action) {
            SEARCH_ACTION_SHOW_METADATA -> SearchHelper.handleSearchClickCallback(click)
            SEARCH_ACTION_LOAD -> {
                val origin = navigation?.currentBackStackEntry?.id
                if (origin != null) {
                    returnEntryId = origin
                    pendingDetailDraw = true
                    detailEntryId = null
                }
                // Use CloudStream's own SEARCH_ACTION_LOAD handler; keep the dialog
                // on top until the destination's detail view can actually draw.
                SearchHelper.handleSearchClickCallback(click)
                if (origin == null) dismiss()
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
    private var initialRevealFinished = false
    private val homeTitleFocus = if (initialCatalog != null)
        activity.currentFocus?.takeIf { it.id == R.id.home_child_more_info } else null
    private val initialRevealListener = ViewTreeObserver.OnPreDrawListener {
        if (!initialRevealFinished && chosen && items.isNotEmpty() &&
            grid.childCount > 0 && (0 until grid.childCount).all { index ->
                val image = grid.getChildAt(index).findViewById<ImageView>(R.id.imageView)
                image != null && image.width > 0 && image.drawable != null
            }) finishInitialReveal()
        true
    }
    private fun finishInitialReveal() {
        if (initialRevealFinished || closed) return
        initialRevealFinished = true
        if (root.viewTreeObserver.isAlive)
            root.viewTreeObserver.removeOnPreDrawListener(initialRevealListener)
        root.alpha = 1f
    }
    private var touchStartY = 0f
    private var closed = false
    private val navigation by lazy {
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)?.navController
    }
    private var returnEntryId: String? = null
    private var detailEntryId: String? = null
    private var pendingDetailDraw = false
    private var detailRevealEnd: (() -> Unit)? = null

    // Match the stable-frame guard used for quick-search -> detail return.
    // Hiding a fullscreen Dialog in the very first pre-draw exposes the Home
    // destination while the ResultFragment is still entering.
    private fun revealDetailWhenReady(detail: View) {
        detailRevealEnd?.invoke()
        detail.doOnPreDraw {
            if (closed || !pendingDetailDraw) return@doOnPreDraw
            val observer = detail.viewTreeObserver
            val started = SystemClock.uptimeMillis()
            var stable = 0
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            val release = {
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
                detailRevealEnd = null
            }
            listener = ViewTreeObserver.OnPreDrawListener {
                if (closed || !pendingDetailDraw || returnEntryId == null ||
                    detailEntryId != navigation?.currentBackStackEntry?.id) {
                    release()
                } else {
                    val hostView = activity.supportFragmentManager
                        .findFragmentById(R.id.nav_host_fragment)?.view
                    val bounds = hostView?.parent as? ViewGroup
                    val ready = hostView != null && bounds != null &&
                        hostView.visibility == View.VISIBLE &&
                        hostView.left <= bounds.paddingLeft &&
                        hostView.top <= bounds.paddingTop &&
                        hostView.right >= bounds.width - bounds.paddingRight &&
                        hostView.bottom >= bounds.height - bounds.paddingBottom &&
                        !hostView.isLayoutRequested && detail.width > 0 && detail.height > 0 &&
                        !detail.isLayoutRequested && detail.animation?.hasEnded() != false
                    stable = if (ready) stable + 1 else 0
                    if (stable >= 3 || (SystemClock.uptimeMillis() - started >= 800L &&
                        detail.isShown && detail.width > 0 && detail.height > 0)) {
                        release()
                        detail.postOnAnimation {
                            if (!closed && pendingDetailDraw && returnEntryId != null &&
                                detailEntryId == navigation?.currentBackStackEntry?.id) {
                                pendingDetailDraw = false
                                dialog.hide()
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
            if (returnEntryId != null &&
                fragment.javaClass.name == "com.lagradost.cloudstream3.ui.search.SearchFragment") {
                dismiss() // Search breaks the browser -> detail -> back chain.
                return
            }
            if (!pendingDetailDraw || closed) return
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
        if (!closed && origin != null) {
            val current = controller.currentBackStackEntry?.id
            when {
                current == origin -> {
                    // Navigation through Home on the way to Search must not resurrect
                    // the browser. Check again immediately before Home is drawn.
                    val hostView = activity.supportFragmentManager
                        .findFragmentById(R.id.nav_host_fragment)?.view
                    hostView?.doOnPreDraw {
                        if (!closed && returnEntryId == origin && detailEntryId != null &&
                            navigation?.currentBackStackEntry?.id == origin) {
                            returnEntryId = null
                            detailEntryId = null
                            pendingDetailDraw = false
                            dialog.show()
                        }
                    }
                }
                detailEntryId == null -> detailEntryId = current
                current != detailEntryId -> {
                    // Detail -> quick search / cast search / another page:
                    // discard the browser, so navigation Home stays Home.
                    dismiss()
                }
            }
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
        if (key == "stremio_saved_links") scope.launch { reloadLinks() }
    }
    private val manifestListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!fetchingCatalogs && links.any { key == stremioCatalogKey(it.link) })
            scope.launch { reloadLinks() }
    }

    // Temporary passive diagnostics for short vs. long dropdown taps.
    private fun traceDropdown(message: String) {
        android.util.Log.d("StremioCDropdown", "${SystemClock.uptimeMillis()} $message")
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
            val handled = super.performClick()
            traceDropdown("OPEN selector=${contentDescription} handled=$handled count=${adapter?.count} width=$width")
            return handled
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
                    private var downAt = 0L
                    private var pressedAt = 0L
                    private var firstPressedFrame = false

                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        val action = event.actionMasked
                        if (action == MotionEvent.ACTION_DOWN) downAt = event.eventTime
                        val handled = super.dispatchTouchEvent(event)
                        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP ||
                            action == MotionEvent.ACTION_CANCEL) {
                            traceDropdown("ROW_TOUCH selector=$title pos=$position action=$action handled=$handled " +
                                "duration=${if (downAt == 0L) -1 else event.eventTime - downAt}ms " +
                                "x=${event.x.toInt()} width=$width pressed=$isPressed")
                        }
                        return handled
                    }

                    override fun setPressed(pressed: Boolean) {
                        val changed = isPressed != pressed
                        super.setPressed(pressed)
                        if (changed) {
                            val now = SystemClock.uptimeMillis()
                            if (pressed) { pressedAt = now; firstPressedFrame = false }
                            val list = parent as? AbsListView
                            traceDropdown("ROW_PRESS selector=$title pos=$position pressed=$pressed " +
                                "held=${if (pressedAt == 0L) -1 else now - pressedAt}ms " +
                                "width=$width listPressed=${list?.isPressed} selectorBounds=${list?.selector?.bounds} " +
                                "background=${background?.javaClass?.simpleName}")
                        }
                    }

                    override fun dispatchDraw(canvas: Canvas) {
                        if (isPressed && !firstPressedFrame) {
                            firstPressedFrame = true
                            val list = parent as? AbsListView
                            traceDropdown("ROW_DRAW selector=$title pos=$position " +
                                "afterPress=${SystemClock.uptimeMillis() - pressedAt}ms " +
                                "width=$width textWidth=${getChildAt(0)?.width} iconWidth=${getChildAt(1)?.width} " +
                                "listSelector=${list?.selector?.bounds}")
                        }
                        super.dispatchDraw(canvas)
                    }

                    override fun onAttachedToWindow() {
                        super.onAttachedToWindow()
                        traceDropdown("ROW_ATTACH selector=$title pos=$position parent=${parent?.javaClass?.simpleName}")
                    }

                    override fun onDetachedFromWindow() {
                        traceDropdown("ROW_DETACH selector=$title pos=$position pressed=$isPressed " +
                            "held=${if (pressedAt == 0L) -1 else SystemClock.uptimeMillis() - pressedAt}ms")
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
                traceDropdown("SELECT selector=$title pos=$position id=$id " +
                    "viewPressed=${view?.isPressed} viewWidth=${view?.width} closed=$closed")
                if (!closed && position in labels.indices) choose(position)
            }
        }
    }

    fun show(onDismiss: () -> Unit) {
        root.alpha = 0f // Release title, selectors and loaded poster cards in one frame.
        root.viewTreeObserver.addOnPreDrawListener(initialRevealListener)
        root.postDelayed({ finishInitialReveal() }, 3000L) // Broken images/network must not trap an invisible UI.
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
        root.addView(loadingIndicator, CoordinatorLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        })
        root.addView(retry, CoordinatorLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM
        })
        grid.adapter = adapter
        grid.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
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
            if (root.viewTreeObserver.isAlive)
                root.viewTreeObserver.removeOnPreDrawListener(initialRevealListener)
            closed = true
            generation++
            scope.cancel()
            prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
            manifestPrefs.unregisterOnSharedPreferenceChangeListener(manifestListener)
            activity.lifecycle.removeObserver(hostObserver)
            navigation?.removeOnDestinationChangedListener(destinationListener)
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(detailViewListener)
            detailRevealEnd?.invoke()
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
        navigation?.addOnDestinationChangedListener(destinationListener)
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(detailViewListener, true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(activity.colorFromAttribute(R.attr.primaryBlackBackground)))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        activity.lifecycle.addObserver(hostObserver)
        prefs.registerOnSharedPreferenceChangeListener(settingsListener)
        manifestPrefs.registerOnSharedPreferenceChangeListener(manifestListener)
        reloadLinks()
    }

    fun dismiss() {
        if (closed) return
        closed = true
        returnEntryId = null
        detailEntryId = null
        pendingDetailDraw = false
        detailRevealEnd?.invoke()
        generation++
        scope.cancel()
        dialog.dismiss() // Detaches Spinner popups along with their anchor views.
    }

    private fun pauseRequest() {
        if (job?.isActive != true) return
        generation++
        job?.cancel()
        loading = false
        loadingIndicator.visibility = View.GONE
        if (catalog == null || (chosen && hasNext)) {
            retry.visibility = View.VISIBLE
        }
    }

    private fun reset() {
        generation++
        fetchingCatalogs = false
        job?.cancel()
        loading = false
        loadingIndicator.visibility = View.GONE
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
        links = if (directTarget == null) SettingsRepository.loadLinks(prefs).filter { it.link.isNotBlank() } else emptyList()
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
                providerFor(link.link)?.let { link.link to it }
            }
        }
        if (sources.isEmpty()) {
            Toast.makeText(activity, "No Stremio catalogs available.", Toast.LENGTH_SHORT).show()
            return
        }
        val ticket = generation
        fetchingCatalogs = true
        job = scope.launch {
            val limit = Semaphore(4)
            val result = try { coroutineScope {
                sources.map { (url, source) ->
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
            if (result.isEmpty()) {
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
        val home = pendingHome?.takeIf { snapshot ->
            !required && genre == null &&
                snapshot.target.catalogId == next.catalog.id &&
                snapshot.target.type == next.catalog.type &&
                stremioManifestUrl(snapshot.target.addonUrl.fixSourceUrl()) ==
                    stremioManifestUrl(next.url.fixSourceUrl()) &&
                snapshot.items.isNotEmpty() && snapshot.nextSkip > 0
        }
        pendingHome = null
        if (home != null) {
            // Display the already-loaded home SearchResponses without a first-page request.
            // The browser starts at Home's raw cursor; subsequent All pages synchronize back.
            items.addAll(home.items)
            seen.addAll(home.items.map { it.url })
            offset = home.nextSkip
            hasNext = home.hasNext && next.catalog.supportsExtra("skip")
            adapter.submitList(items.toList())
            autoFillIfShort(generation)
        } else requestPage()
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
        val basePath = "/catalog/" + type.urlEncodeLocal() + "/" + id.urlEncodeLocal()
        val tab = source.mainPage.firstOrNull {
            it.data.startsWith("$basePath/") || it.data.startsWith("$basePath.json")
        } ?: return

        // CloudStream HomeViewModel owns another page counter and displayed list.
        val model = ViewModelProvider(activity)[HomeViewModel::class.java]
        if (model.apiName.value != source.name || tab.name in model.lock) return
        val success = model.page.value as? Resource.Success<*> ?: return
        val record = (success.value as? Map<*, *>)?.get(tab.name)
            as? HomeViewModel.ExpandableHomepageList ?: return
        val parent = activity.findViewById<RecyclerView>(R.id.home_master_recycler) ?: return
        val parentAdapter = parent.adapter as? ParentItemAdapter ?: return
        val displayed = parentAdapter.immutableCurrentList.firstOrNull { it.list.name == tab.name }
            ?: return
        if (record.currentPage != displayed.currentPage ||
            record.list.list.map { it.url } != displayed.list.list.map { it.url } ||
            source.homeCursorFor(target, record.list.list) != requestedOffset
        ) return

        // This checks the native page-flight and offset under the provider mutex.
        val accepted = source.adoptBrowserPage(target, requestedOffset, page, rawCount) ?: return
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
        val selectedProvider = provider ?: return
        val selectedCatalog = catalog ?: return
        if (!chosen || loading || !hasNext || closed) return
        val ticket = generation
        val selectedGenre = genre
        val requestedOffset = offset
        loading = true
        loadingIndicator.visibility = View.VISIBLE
        retry.visibility = View.GONE
        job = scope.launch {
            try {
                val (page, rawCount) = withContext(Dispatchers.IO) {
                    selectedProvider.browsePage(selectedCatalog, selectedGenre, requestedOffset)
                }
                if (ticket != generation) return@launch
                val fresh = page.filter { seen.add(it.url) }
                val repeated = !pageSignatures.add(page.map { it.url })
                items.addAll(fresh)
                offset += rawCount // Never advance by the deduplicated display count.
                hasNext = selectedCatalog.supportsExtra("skip") && rawCount > 0 && !repeated && fresh.isNotEmpty()
                adapter.submitList(items.toList())
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
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (ticket == generation) {
                    Toast.makeText(activity, "Unable to load catalog. Please retry.", Toast.LENGTH_SHORT).show()
                    retry.visibility = View.VISIBLE
                }
            } finally {
                if (ticket == generation) {
                    loading = false
                    loadingIndicator.visibility = View.GONE
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
            val provider = APIHolder.allProviders.filterIsInstance<StremioC>()
                .firstOrNull { it.mainUrl == target.addonUrl }
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
                            homeTarget() == target) openTarget(target)
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
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) openTarget(target)
                true
            }
        }

        fun dispose() {
            adapter.unregisterAdapterDataObserver(observer)
            title?.apply {
                setOnTouchListener(null)
                setOnKeyListener(null)
                isPressed = false
                if (isLayout(TV)) isFocusable = originalFocusable
            }
        }
    }
}
