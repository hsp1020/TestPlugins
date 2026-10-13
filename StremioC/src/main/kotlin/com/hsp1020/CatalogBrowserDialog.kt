package com.hsp1020

import android.app.Dialog
import android.content.SharedPreferences
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_SHOW_METADATA
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    private val dialog = Dialog(activity)
    private val scope = MainScope()
    private val manifestPrefs = activity.getSharedPreferences("StremioC_Manifest", 0)
    private val source = (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        as? NavHostFragment)?.navController?.currentBackStackEntry
    private val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val addonButton = button("Addon: select")
    private val catalogButton = button("Catalog: select")
    private val genreButton = button("Genre: select")
    private val status = TextView(activity).apply {
        typeface = Typeface.DEFAULT
        setPadding(dp(12), dp(6), dp(12), dp(6))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val retry = button("Load catalog")
    private val grid = AutofitRecyclerView(activity)
    private val adapter = SearchAdapter(grid, false) { click ->
        if (click.action == SEARCH_ACTION_LOAD || click.action == SEARCH_ACTION_SHOW_METADATA) {
            // Keep this exact adapter/list/layout manager alive while the source entry is stopped.
            if (source == null) {
                status.text = "Open the browser from the main CloudStream window."
            } else {
                pauseRequest()
                waitingForDetail = true
                dialog.hide()
                activity.loadSearchResult(click.card)
                root.post {
                    if (!closed && source.lifecycle.currentState == Lifecycle.State.RESUMED) {
                        waitingForDetail = false
                        dialog.show() // Navigation was unavailable; don't strand a hidden dialog.
                    }
                }
            }
        }
    }
    private var links = emptyList<LinkItem>()
    private var catalogs = emptyList<StremioC.Catalog>()
    private var provider: StremioC? = null
    private var addonUrl: String? = null
    private var catalog: StremioC.Catalog? = null
    private var genre: String? = null
    private var chosen = false
    private var generation = 0
    private var job: Job? = null
    private var picker: AlertDialog? = null
    private var offset = 0
    private var loading = false
    private var hasNext = false
    private var waitingForDetail = false
    private var closed = false
    private var initialSelector = ""
    private val items = ArrayList<SearchResponse>()
    private val seen = HashSet<String>()
    private val pageSignatures = HashSet<List<String>>()
    private val sourceObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> {
                pauseRequest()
                waitingForDetail = true
                dialog.hide()
            }
            Lifecycle.Event.ON_RESUME -> if (waitingForDetail && !closed) {
                waitingForDetail = false
                dialog.show()
            }
            Lifecycle.Event.ON_DESTROY -> dismiss()
            else -> Unit
        }
    }
    private val hostObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> { pauseRequest(); dialog.hide() }
            Lifecycle.Event.ON_RESUME -> if (!closed && !waitingForDetail) dialog.show()
            Lifecycle.Event.ON_DESTROY -> dismiss()
            else -> Unit
        }
    }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "stremio_saved_links") scope.launch { reloadLinks() }
    }
    private val manifestListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == addonUrl?.let(::stremioCatalogKey)) scope.launch {
            addonUrl?.let { selectAddon(it) }
        }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun button(label: String) = Button(activity).apply {
        id = View.generateViewId()
        text = label
        isAllCaps = false
        typeface = Typeface.DEFAULT
        isFocusable = true
        minHeight = dp(48)
    }

    fun show(firstSelector: String, onDismiss: () -> Unit) {
        initialSelector = firstSelector
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.setPadding(dp(12), dp(8), dp(12), dp(8))
        listOf(addonButton, catalogButton, genreButton, status).forEach {
            root.addView(it, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(retry, ViewGroup.LayoutParams(-1, -2))
        grid.adapter = adapter
        grid.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                grid.post {
                    if (!closed) {
                        grid.spanCount = (grid.width / dp(125)).coerceIn(2, 8)
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
        addonButton.setOnClickListener { pickAddon() }
        catalogButton.setOnClickListener { pickCatalog() }
        genreButton.setOnClickListener { pickGenre() }
        retry.setOnClickListener { requestPage() }
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            closed = true
            picker?.dismiss()
            scope.cancel()
            prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
            manifestPrefs.unregisterOnSharedPreferenceChangeListener(manifestListener)
            source?.lifecycle?.removeObserver(sourceObserver)
            activity.lifecycle.removeObserver(hostObserver)
            grid.adapter = null
            onDismiss()
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        source?.lifecycle?.addObserver(sourceObserver)
        activity.lifecycle.addObserver(hostObserver)
        prefs.registerOnSharedPreferenceChangeListener(settingsListener)
        manifestPrefs.registerOnSharedPreferenceChangeListener(manifestListener)
        reloadLinks()
        if (firstSelector == "Addon" && links.size > 1) pickAddon()
    }

    fun dismiss() = dialog.dismiss()

    private fun pauseRequest() {
        if (job?.isActive != true) return
        generation++
        job?.cancel()
        loading = false
        if (catalog == null) {
            catalogButton.isEnabled = provider != null
            status.text = "Select Catalog to resume."
        } else if (chosen && hasNext) {
            retry.text = if (offset == 0) "Load catalog" else "Load more"
            retry.visibility = View.VISIBLE
            status.text = "${items.size} items · ${genre ?: "All"}"
        }
    }

    private fun reset() {
        generation++
        job?.cancel()
        loading = false
        offset = 0
        items.clear()
        seen.clear()
        pageSignatures.clear()
        adapter.submitList(emptyList())
        hasNext = false
        chosen = false
        retry.visibility = View.GONE
    }

    private fun reloadLinks() {
        picker?.dismiss()
        links = SettingsRepository.loadLinks(prefs).filter { it.link.isNotBlank() }
        addonButton.visibility = if (links.size > 1) View.VISIBLE else View.GONE
        val link = links.firstOrNull { it.link == addonUrl } ?: links.firstOrNull()
        if (link == null) {
            reset()
            provider = null
            addonUrl = null
            catalogs = emptyList()
            catalog = null
            catalogButton.isEnabled = false
            genreButton.visibility = View.GONE
            status.text = "Add a Stremio addon in settings first."
        } else selectAddon(link.link)
    }

    private fun pick(title: String, labels: List<String>, selected: Int, choose: (Int) -> Unit) {
        if (labels.isEmpty()) return
        picker?.dismiss()
        picker = AlertDialog.Builder(activity).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { whichDialog, index ->
                whichDialog.dismiss()
                choose(index)
            }.setNegativeButton(android.R.string.cancel, null).create()
        picker?.show()
        picker?.listView?.requestFocus()
    }

    private fun pickAddon() = pick("Addon", links.map { it.name }, links.indexOfFirst { it.link == addonUrl }) {
        selectAddon(links[it].link)
    }

    private fun selectAddon(url: String) {
        reset()
        picker?.dismiss()
        catalog = null
        catalogs = emptyList()
        addonUrl = url
        provider = providerFor(url)
        addonButton.text = "Addon: ${links.firstOrNull { it.link == url }?.name.orEmpty()}"
        catalogButton.text = "Catalog: select"
        catalogButton.isEnabled = false
        genreButton.visibility = View.GONE
        status.text = "Loading catalog choices…"
        val selectedProvider = provider
        if (selectedProvider == null) {
            status.text = "Save addon settings and restart CloudStream to register this provider."
            return
        }
        val ticket = generation
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { selectedProvider.browseCatalogs() }
                if (ticket != generation) return@launch
                catalogs = result
                catalogButton.isEnabled = result.isNotEmpty()
                status.text = if (result.isEmpty()) "No catalogs in this manifest." else "Choose a catalog."
                if (result.size == 1) selectCatalog(result.first()) else {
                    catalogButton.requestFocus()
                    if (initialSelector == "Catalog" || initialSelector == "Genre") pickCatalog()
                    if (initialSelector == "Catalog") initialSelector = ""
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (ticket == generation) {
                    status.text = "Unable to load manifest. Select Catalog to retry."
                    catalogButton.isEnabled = true
                }
            }
        }
    }

    private fun pickCatalog() {
        if (catalogs.isEmpty()) { addonUrl?.let { selectAddon(it) }; return }
        pick("Catalog", catalogs.map { "${it.name ?: it.id} · ${it.type} (${it.id})" }, catalogs.indexOf(catalog)) {
            selectCatalog(catalogs[it])
        }
    }

    private fun selectCatalog(next: StremioC.Catalog) {
        reset()
        catalog = next
        genre = null
        catalogButton.text = "Catalog: ${next.name ?: next.id} · ${next.type}"
        genreButton.text = "Genre: select"
        genreButton.visibility = if (next.supportsExtra("genre")) View.VISIBLE else View.GONE
        val blocked = next.browseBlockReason()
        genreButton.isEnabled = blocked == null && next.browseGenres().isNotEmpty()
        status.text = blocked ?: when {
            next.requiresExtra("genre") -> "Choose a genre."
            genreButton.isEnabled -> "Choose a genre or All."
            else -> "Choose Load catalog."
        }
        if (blocked == null && !genreButton.isEnabled) genreButton.text = "Genre: All (no options provided)"
        if (blocked == null && !genreButton.isEnabled) {
            chosen = true // A genre-less feed still waits for the user's Load action.
            hasNext = true
            retry.text = "Load catalog"
            retry.visibility = View.VISIBLE
            retry.requestFocus()
        } else if (genreButton.isEnabled) {
            genreButton.requestFocus()
            if (initialSelector == "Genre") pickGenre()
        }
        initialSelector = ""
    }

    private fun pickGenre() {
        val selectedCatalog = catalog ?: return
        val values: List<String?> = (if (selectedCatalog.requiresExtra("genre")) emptyList() else listOf(null)) +
            selectedCatalog.browseGenres()
        pick("Genre", values.map { it ?: "All" }, if (chosen) values.indexOf(genre) else -1) {
            if (chosen && genre == values[it] && items.isNotEmpty()) return@pick
            reset()
            genre = values[it]
            chosen = true
            hasNext = true
            genreButton.text = "Genre: ${genre ?: "All"}"
            requestPage()
        }
    }

    private fun requestPage() {
        val selectedProvider = provider ?: return
        val selectedCatalog = catalog ?: return
        if (!chosen || loading || !hasNext || waitingForDetail) return
        val ticket = generation
        val selectedGenre = genre
        val requestedOffset = offset
        loading = true
        retry.visibility = View.GONE
        status.text = "Loading…"
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
                status.text = when {
                    items.isEmpty() -> "No results."
                    repeated || (rawCount > 0 && fresh.isEmpty()) -> "No new items. Pagination stopped."
                    else -> "${items.size} items · ${selectedGenre ?: "All"}"
                }
                retry.text = "Load more"
                retry.visibility = if (hasNext) View.VISIBLE else View.GONE
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (ticket == generation) {
                    status.text = "Unable to load catalog. Please retry; the server may be rate limited."
                    retry.text = "Retry"
                    retry.visibility = View.VISIBLE
                }
            } finally {
                if (ticket == generation) loading = false
            }
        }
    }
}
