package com.hsp1020

import androidx.appcompat.app.AppCompatDialog
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
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
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.recyclerview.widget.RecyclerView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_SHOW_METADATA
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
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

/** One dialog, one selection and one in-flight page. No homepage cursors or global UI hooks. */
internal class CatalogBrowserDialog(
    private val activity: FragmentActivity,
    private val prefs: SharedPreferences,
    private val providerFor: (String) -> StremioC?,
    private val settingsIcon: Drawable?,
    private val onOpenSettings: () -> Unit,
    initialCatalog: CatalogShortcutTarget? = null,
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
    private val selectorLabelStyle = TextView(activity).apply {
        typeface = Typeface.create(typeface, Typeface.BOLD)
    }
    private val settingsButton = ImageView(activity).apply {
        contentDescription = "Stremio Settings"
        setImageDrawable(settingsIcon)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        isClickable = true
        isFocusable = true
        val attr = TypedValue()
        if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, attr, true) && attr.resourceId != 0)
            setBackgroundResource(attr.resourceId)
        setOnClickListener {
            if (!closed) {
                settingsVisible = true
                dialog.hide()
                try { onOpenSettings() } catch (_: Exception) {
                    restoreFromSettings()
                    Toast.makeText(activity, "Unable to open settings.", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                if (origin != null) returnEntryId = origin
                activity.loadSearchResult(click.card)
                if (origin == null) dismiss() // Navigate before removing the overlay.
            }
        }
    }
    private var links = emptyList<LinkItem>()
    private data class CatalogSource(val url: String, val provider: StremioC, val catalog: StremioC.Catalog)
    private var catalogs = emptyList<CatalogSource>()
    private var visibleCatalogs = emptyList<CatalogSource>()
    private var selectedType = initialCatalog?.type ?: "movie"
    private var pendingCatalog = initialCatalog
    private var fetchingCatalogs = false
    private var catalogSettingsChanged = false
    private var settingsVisible = false
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
    private var touchStartY = 0f
    private var closed = false
    private val navigation by lazy {
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)?.navController
    }
    private var returnEntryId: String? = null
    private var detailHidePending = false
    private val destinationListener = NavController.OnDestinationChangedListener { controller, _, _ ->
        if (!closed && returnEntryId != null) {
            if (controller.currentBackStackEntry?.id == returnEntryId) {
                returnEntryId = null
                detailHidePending = false
                dialog.show()
            } else if (!detailHidePending) {
                detailHidePending = true
                val hostView = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.view
                if (hostView != null) hostView.doOnPreDraw {
                    detailHidePending = false
                    if (!closed && returnEntryId != null) dialog.hide()
                } else {
                    detailHidePending = false
                    dialog.hide()
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
        if (key == "stremio_saved_links") {
            if (settingsVisible) catalogSettingsChanged = true else scope.launch { reloadLinks() }
        } else if (key?.startsWith("tab_enabled_") == true || key?.startsWith("catalog_order_") == true)
            if (!fetchingCatalogs) catalogSettingsChanged = true
    }
    private val manifestListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!fetchingCatalogs && links.any { key == stremioCatalogKey(it.link) }) {
            if (settingsVisible) catalogSettingsChanged = true else scope.launch { reloadLinks() }
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

        override fun onTouchEvent(event: MotionEvent): Boolean {
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
        val ripple = TypedValue()
        if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true) && ripple.resourceId != 0) {
            setBackgroundResource(ripple.resourceId)
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

    private fun selectorRow(
        title: String, spinner: AppCompatSpinner, arrow: TextView, button: View? = null
    ) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            text = title
            typeface = selectorLabelStyle.typeface
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
        }, LinearLayout.LayoutParams(dp(82), dp(48)))
        addView(FrameLayout(activity).apply {
            addView(spinner, FrameLayout.LayoutParams(-1, -1))
            addView(arrow, FrameLayout.LayoutParams(dp(40), -1, Gravity.END))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        if (button != null) addView(button, LinearLayout.LayoutParams(dp(48), dp(48)))
        else addView(View(activity), LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    fun restoreFromSettings() {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        settingsVisible = false
        dialog.show()
        if (catalogSettingsChanged) {
            catalogSettingsChanged = false
            reloadLinks()
        }
    }

    private fun AppCompatSpinner.choices(title: String, labels: List<String>, selected: Int, choose: (Int) -> Unit) {
        onItemSelectedListener = null
        contentDescription = title
        when (this) {
            typeSelector -> typeArrow
            catalogSelector -> catalogArrow
            genreSelector -> genreArrow
            else -> null
        }?.visibility = if (labels.size > 1) View.VISIBLE else View.GONE
        adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
            private fun boldRow(row: View, selectedItem: Boolean) = row.also {
                ((it as? TextView) ?: it.findViewById<TextView>(android.R.id.text1))?.apply {
                    typeface = selectorLabelStyle.typeface
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, selectorLabelStyle.textSize)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    if (selectedItem) setPadding(paddingLeft, paddingTop, dp(40), paddingBottom)
                }
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                boldRow(super.getView(position, convertView, parent), true)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) =
                boldRow(super.getDropDownView(position, convertView, parent), false)
        }.apply {
            setDropDownViewResource(R.layout.sort_bottom_single_choice)
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
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.setBackgroundColor(activity.colorFromAttribute(R.attr.primaryBlackBackground))
        val headerColor = activity.colorFromAttribute(R.attr.primaryBlackBackground)
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
            setBackgroundColor(headerColor)
            addView(selectorRow("Type", typeSelector, typeArrow, settingsButton), ViewGroup.LayoutParams(-1, -2))
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
            closed = true
            generation++
            scope.cancel()
            prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
            manifestPrefs.unregisterOnSharedPreferenceChangeListener(manifestListener)
            activity.lifecycle.removeObserver(hostObserver)
            navigation?.removeOnDestinationChangedListener(destinationListener)
            grid.adapter = null
            onDismiss()
        }
        dialog.show()
        dialog.window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
        ViewCompat.requestApplyInsets(root)
        navigation?.addOnDestinationChangedListener(destinationListener)
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
            retry.text = "Retry"
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
        links = SettingsRepository.loadLinks(prefs).filter { it.link.isNotBlank() }
        provider = null
        addonUrl = null
        catalog = null
        catalogs = emptyList()
        visibleCatalogs = emptyList()
        typeSelector.isEnabled = false
        catalogSelector.isEnabled = false
        genreSelector.isEnabled = false
        typeSelector.choices("Catalog type", emptyList(), -1) {}
        catalogSelector.choices("Catalog name", emptyList(), -1) {}
        genreSelector.choices("Genre", emptyList(), -1) {}
        val sources = links.asReversed().distinctBy { it.link }.mapNotNull { link ->
            providerFor(link.link)?.let { link.link to it }
        }
        if (sources.isEmpty()) {
            Toast.makeText(activity, "No registered Stremio catalogs.", Toast.LENGTH_SHORT).show()
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
            typeSelector.isEnabled = types.size > 1
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
        visibleCatalogs = catalogs.filter { it.catalog.type == type }
        val entries = visibleCatalogs
        val requested = pendingCatalog?.takeIf { it.type == type }
        val target = requested?.let { request ->
            entries.firstOrNull { entry ->
                entry.catalog.id == request.catalogId &&
                    stremioManifestUrl(entry.url.fixSourceUrl()) ==
                    stremioManifestUrl(request.addonUrl.fixSourceUrl())
            }
        }
        val initial = target ?: entries.firstOrNull()
        catalogSelector.isEnabled = entries.size > 1
        catalogSelector.choices("Catalog name", entries.map { entry ->
            "${entry.catalog.name ?: entry.catalog.id ?: "Untitled"} - " +
                (links.firstOrNull { it.link == entry.url }?.name ?: entry.url)
        }, initial?.let { entries.indexOf(it) } ?: -1) {
            val entry = entries.getOrNull(it) ?: return@choices
            if (catalog !== entry.catalog || addonUrl != entry.url) selectCatalog(entry)
        }
        pendingCatalog = null
        if (requested != null && target == null)
            Toast.makeText(activity, "Requested catalog is no longer available.", Toast.LENGTH_SHORT).show()
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
            genreSelector.isEnabled = false
            genreSelector.choices("Genre", emptyList(), -1) {}
            Toast.makeText(activity, blocked, Toast.LENGTH_LONG).show()
            return
        }
        val required = next.catalog.requiresExtra("genre")
        val genres: List<String?> = (if (required) emptyList() else listOf(null)) +
            next.catalog.browseGenres().filter { required || !it.equals("All", ignoreCase = true) }
        genreSelector.isEnabled = genres.size > 1
        genreSelector.choices("Genre", genres.map { it ?: "All" }, 0) {
            if (chosen && genre == genres[it]) return@choices
            reset()
            genre = genres[it]
            chosen = true
            hasNext = true
            requestPage()
        }
        genre = genres.firstOrNull()
        chosen = true
        hasNext = true
        requestPage()
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
                retry.visibility = View.GONE
                grid.doOnNextLayout {
                    if (!closed && ticket == generation && !loading && hasNext && autoFillCount < 1 &&
                        items.isNotEmpty() && grid.height > 0 && !grid.canScrollVertically(1)) {
                        autoFillCount++ // At most one page without an explicit scroll.
                        requestPage()
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (ticket == generation) {
                    Toast.makeText(activity, "Unable to load catalog. Please retry.", Toast.LENGTH_SHORT).show()
                    retry.text = "Retry"
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
