package com.hsp1020

import androidx.appcompat.app.AppCompatDialog
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.widget.AppCompatSpinner
import com.google.android.material.button.MaterialButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
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
    private val addonSelector = selector()
    private val catalogSelector = selector()
    private val genreSelector = selector()
    private val status = TextView(activity).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val retry = button("Load catalog")
    private val grid = AutofitRecyclerView(activity)
    private val adapter = SearchAdapter(grid, false) { click ->
        if (!closed && (click.action == SEARCH_ACTION_LOAD || click.action == SEARCH_ACTION_SHOW_METADATA)) {
            // Dismiss, don't hide: the host destination may be a dialog, so ON_STOP
            // is not a reliable signal and a resume observer can cover the result page.
            dismiss()
            activity.loadSearchResult(click.card)
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
    private var offset = 0
    private var loading = false
    private var hasNext = false
    private var closed = false
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
        if (key == addonUrl?.let(::stremioCatalogKey)) scope.launch {
            addonUrl?.let { selectAddon(it) }
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

    private fun selector() = AppCompatSpinner(activity, null, androidx.appcompat.R.attr.spinnerStyle, Spinner.MODE_DROPDOWN).apply {
        id = View.generateViewId()
        minimumHeight = dp(48)
        isFocusable = true
    }

    private fun AppCompatSpinner.choices(title: String, labels: List<String>, selected: Int, choose: (Int) -> Unit) {
        onItemSelectedListener = null
        contentDescription = title
        adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, listOf(title) + labels) {
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = position > 0
        }.apply {
            setDropDownViewResource(R.layout.sort_bottom_single_choice)
        }
        setSelection(selected + 1)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!closed && position > 0) choose(position - 1)
            }
        }
    }

    fun show(onDismiss: () -> Unit) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.setBackgroundColor(activity.colorFromAttribute(R.attr.primaryBlackBackground))
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
            addView(addonSelector, ViewGroup.LayoutParams(-1, -2))
            addView(LinearLayout(activity).apply {
                addView(catalogSelector, LinearLayout.LayoutParams(0, -2, 1f))
                addView(genreSelector, LinearLayout.LayoutParams(0, -2, 1f))
            }, ViewGroup.LayoutParams(-1, -2))
            addView(status, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(controls)
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
        retry.setOnClickListener {
            if (catalog == null) addonUrl?.let(::selectAddon) else requestPage()
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            closed = true
            generation++
            scope.cancel()
            prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
            manifestPrefs.unregisterOnSharedPreferenceChangeListener(manifestListener)
            activity.lifecycle.removeObserver(hostObserver)
            grid.adapter = null
            onDismiss()
        }
        dialog.show()
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
        generation++
        scope.cancel()
        dialog.dismiss() // Detaches Spinner popups along with their anchor views.
    }

    private fun pauseRequest() {
        if (job?.isActive != true) return
        generation++
        job?.cancel()
        loading = false
        if (catalog == null) {
            retry.visibility = if (provider != null) View.VISIBLE else View.GONE
            retry.text = "Retry"
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
        links = SettingsRepository.loadLinks(prefs).filter { it.link.isNotBlank() }
        addonSelector.isEnabled = links.size > 1
        val link = links.firstOrNull { it.link == addonUrl } ?: links.firstOrNull()
        if (link == null) {
            reset()
            provider = null
            addonUrl = null
            catalogs = emptyList()
            catalog = null
            addonSelector.choices("Addon", emptyList(), -1) {}
            catalogSelector.choices("Catalog", emptyList(), -1) {}
            catalogSelector.isEnabled = false
            genreSelector.isEnabled = false
            genreSelector.choices("Genre", emptyList(), -1) {}
            status.text = "Add a Stremio addon in settings first."
        } else selectAddon(link.link)
    }

    private fun selectAddon(url: String) {
        reset()
        catalog = null
        catalogs = emptyList()
        addonUrl = url
        provider = providerFor(url)
        addonSelector.choices("Addon", links.map { it.name }, links.indexOfFirst { it.link == url }) {
            if (addonUrl != links[it].link) selectAddon(links[it].link)
        }
        catalogSelector.choices("Catalog", emptyList(), -1) {}
        catalogSelector.isEnabled = false
        genreSelector.isEnabled = false
        genreSelector.choices("Genre", emptyList(), -1) {}
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
                catalogSelector.isEnabled = result.isNotEmpty()
                catalogSelector.choices("Catalog", result.map { "${it.name ?: it.id} - ${it.type.orEmpty().replaceFirstChar { c -> c.uppercase() }}" }, -1) {
                    if (catalog !== result[it]) selectCatalog(result[it])
                }
                status.text = if (result.isEmpty()) "No catalogs in this manifest." else "Choose a catalog."
                if (result.size == 1) selectCatalog(result.first()) else {
                    catalogSelector.requestFocus()
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (ticket == generation) {
                    status.text = "Unable to load manifest. Please retry."
                    retry.text = "Retry"
                    retry.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun selectCatalog(next: StremioC.Catalog) {
        reset()
        catalog = next
        genre = null
        catalogSelector.setSelection(catalogs.indexOf(next) + 1)
        genreSelector.choices("Genre", emptyList(), -1) {}
        val blocked = next.browseBlockReason()
        genreSelector.isEnabled = blocked == null && next.browseGenres().isNotEmpty()
        status.text = blocked ?: when {
            next.requiresExtra("genre") -> "Choose a genre."
            genreSelector.isEnabled -> "Choose a genre or All."
            else -> "Choose Load catalog."
        }

        if (blocked == null && !genreSelector.isEnabled) {
            chosen = true // A genre-less feed still waits for the user's Load action.
            hasNext = true
            retry.text = "Load catalog"
            retry.visibility = View.VISIBLE
            retry.requestFocus()
        } else if (genreSelector.isEnabled) {
            genreSelector.requestFocus()
            configureGenres(next)
        }
    }

    private fun configureGenres(selectedCatalog: StremioC.Catalog) {
        val values: List<String?> = (if (selectedCatalog.requiresExtra("genre")) emptyList() else listOf(null)) +
            selectedCatalog.browseGenres()
        genreSelector.choices("Genre", values.map { it ?: "All" }, if (chosen) values.indexOf(genre) else -1) {
            if (chosen && genre == values[it]) return@choices
            reset()
            genre = values[it]
            chosen = true
            hasNext = true
            requestPage()
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
