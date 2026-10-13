package com.hsp1020

import androidx.appcompat.app.AppCompatDialog
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.widget.AppCompatSpinner
import com.google.android.material.button.MaterialButton
import android.widget.LinearLayout
import android.util.TypedValue
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_SHOW_METADATA
import com.lagradost.cloudstream3.ui.search.SearchAdapter
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
) {
    private val dialog = AppCompatDialog(activity, R.style.DialogFullscreen)
    private val scope = MainScope()
    private val manifestPrefs = activity.getSharedPreferences("StremioC_Manifest", 0)
    private val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val typeSelector = selector()
    private val catalogSelector = selector()
    private val genreSelector = selector()
    private val retry = button("Retry").apply { visibility = View.GONE }
    private val grid = AutofitRecyclerView(activity)
    private val adapter = SearchAdapter(grid, false) { click ->
        if (!closed && (click.action == SEARCH_ACTION_LOAD || click.action == SEARCH_ACTION_SHOW_METADATA)) {
            val origin = navigation?.currentBackStackEntry?.id
            if (origin != null) {
                returnEntryId = origin
                dialog.hide() // Preserve this dialog's list, cursor and scroll position.
            } else {
                dismiss() // Keep existing navigation if this host has no NavController.
            }
            activity.loadSearchResult(click.card)
        }
    }
    private var links = emptyList<LinkItem>()
    private data class CatalogSource(val url: String, val provider: StremioC, val catalog: StremioC.Catalog)
    private var catalogs = emptyList<CatalogSource>()
    private var visibleCatalogs = emptyList<CatalogSource>()
    private var selectedType = "movie"
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
    private var touchStartY = 0f
    private var closed = false
    private val navigation by lazy {
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)?.navController
    }
    private var returnEntryId: String? = null
    private val destinationListener = NavController.OnDestinationChangedListener { controller, _, _ ->
        if (!closed && returnEntryId != null && controller.currentBackStackEntry?.id == returnEntryId) {
            returnEntryId = null
            dialog.show()
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
        if (!fetchingCatalogs && links.any { key == stremioCatalogKey(it.link) }) scope.launch { reloadLinks() }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun button(label: String) = MaterialButton(activity).apply {
        id = View.generateViewId()
        text = label
        isAllCaps = false
        isFocusable = true
        minHeight = dp(48)
    }

    private fun selector() = AppCompatSpinner(activity, null, androidx.appcompat.R.attr.spinnerStyle, Spinner.MODE_DROPDOWN).apply {
        id = View.generateViewId()
        minimumHeight = dp(48)
        isFocusable = true
        val ripple = TypedValue()
        if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true) && ripple.resourceId != 0) {
            setBackgroundResource(ripple.resourceId)
        }
    }

    private fun AppCompatSpinner.choices(title: String, labels: List<String>, selected: Int, choose: (Int) -> Unit) {
        onItemSelectedListener = null
        contentDescription = title
        adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
            private fun boldRow(row: View, selectedItem: Boolean) = row.also {
                ((it as? TextView) ?: it.findViewById<TextView>(android.R.id.text1))?.apply {
                    typeface = Typeface.create(typeface, Typeface.BOLD)
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
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
            addView(typeSelector, ViewGroup.LayoutParams(-1, -2))
            addView(catalogSelector, ViewGroup.LayoutParams(-1, -2))
            addView(genreSelector, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(controls)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            val safeTop = dp(8) + (statusTop - position[1]).coerceAtLeast(0)
            if (view.paddingTop != safeTop) view.setPadding(0, safeTop, 0, 0)
            insets
        }
        grid.spanCount = activity.getSpanCount(false)
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(retry, ViewGroup.LayoutParams(-1, -2))
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
                                source.browseCatalogs().map { CatalogSource(url, source, it) }
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
            typeSelector.choices("Catalog type", types, types.indexOf(selectedType)) {
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
        val labels = entries.map { it.catalog.name ?: it.catalog.id ?: "Untitled" }
        val duplicates = labels.groupingBy { it }.eachCount()
        catalogSelector.isEnabled = entries.size > 1
        catalogSelector.choices("Catalog name", labels.mapIndexed { index, name ->
            if ((duplicates[name] ?: 0) > 1) {
                "${name} · ${links.firstOrNull { it.link == entries[index].url }?.name ?: entries[index].url}"
            } else name
        }, if (labels.isEmpty()) -1 else 0) {
            val entry = entries.getOrNull(it) ?: return@choices
            if (catalog !== entry.catalog || addonUrl != entry.url) selectCatalog(entry)
        }
        if (visibleCatalogs.isNotEmpty()) selectCatalog(visibleCatalogs.first())
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
                if (ticket == generation) loading = false
            }
        }
    }
}
