package com.hsp1020

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_SHOW_METADATA
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal data class StremioCollectionSource(
    val provider: String, val addonId: String?, val url: String?, val type: String,
    val catalogId: String, val title: String, val genre: String?
)
internal data class StremioCollectionFolder(
    val id: String, val title: String, val cover: String?, val emoji: String?,
    val shape: String, val hideTitle: Boolean, val sources: List<StremioCollectionSource>,
    val folders: List<StremioCollectionFolder>
)
internal data class StremioCollection(
    val id: String, val title: String, val pinned: Boolean, val mode: String,
    val showAll: Boolean, val folders: List<StremioCollectionFolder>
)

internal object StremioCollections {
    const val ENABLED = "collections_enabled"
    const val JSON = "collections_json"
    const val URL = "collections_url"

    private fun JSONObject.string(key: String): String? =
        optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }
    private fun http(value: String?): String? = value?.takeIf {
        val uri = Uri.parse(it)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }
    fun parse(raw: String, origin: String?): List<StremioCollection> {
        val text = raw.trim()
        val native = text.startsWith("{")
        val entries = if (native) {
            val objectValue = JSONObject(text)
            require(objectValue.optString("exportType") == "aiometadataCollections" &&
                objectValue.optInt("exportVersion") == 1) { "Unsupported collection format" }
            objectValue.getJSONArray("entries")
        } else JSONArray(text)
        val originBase = origin?.substringBefore("?")?.substringBeforeLast("/")?.let(::http)
        fun folder(value: JSONObject, depth: Int): StremioCollectionFolder? {
            require(depth <= 32) { "Collection folders are too deeply nested" }
            val id = value.string("id") ?: return null
            val title = value.string("title") ?: return null
            val sources = (value.optJSONArray("sources")?.takeIf { it.length() > 0 }
                ?: value.optJSONArray("catalogSources") ?: JSONArray()).objects().mapNotNull { rawSource ->
                val source = rawSource.optJSONObject("native") ?: rawSource
                val provider = source.string("provider") ?: if (rawSource.has("native")) "unsupported" else "addon"
                val type = source.string("type").orEmpty()
                val catalogId = source.string("catalogId").orEmpty()
                if (provider == "addon" && (type.isBlank() || catalogId.isBlank())) return@mapNotNull null
                StremioCollectionSource(provider, source.string("addonId"),
                    http(source.string("addonBaseUrl")) ?: originBase.takeIf { native && !rawSource.has("native") },
                    type, catalogId, source.string("title") ?: source.string("catalogName")
                        ?: rawSource.string("name") ?: catalogId.ifBlank { provider }, source.string("genre"))
            }.distinct()
            return StremioCollectionFolder(id, title, http(value.string("coverImageUrl")),
                value.string("coverEmoji"), value.string("tileShape") ?: value.string("shape") ?: "SQUARE",
                value.optBoolean("hideTitle"), sources,
                value.optJSONArray("folders")?.objects()?.mapNotNull { folder(it, depth + 1) }
                    ?.distinctBy { it.id }.orEmpty())
        }
        return entries.objects().mapNotNull { entry ->
            if (native && entry.optString("kind") != "collection") return@mapNotNull null
            val id = entry.string("id") ?: return@mapNotNull null
            val title = entry.string("title") ?: return@mapNotNull null
            StremioCollection(id, title, entry.optBoolean("pinToTop"),
                entry.string("viewMode") ?: "TABBED_GRID", entry.optBoolean("showAllTab", true),
                entry.optJSONArray("folders")?.objects()?.mapNotNull { folder(it, 0) }
                    ?.distinctBy { it.id }.orEmpty())
        }.distinctBy { it.id }
    }

    fun read(prefs: SharedPreferences): List<StremioCollection> =
        prefs.getString(JSON, null)?.let { raw ->
            runCatching { parse(raw, prefs.getString(URL, null)) }.getOrNull()
        }.orEmpty()

    suspend fun importCollections(value: String, prefs: SharedPreferences): Int {
        val input = value.trim()
        val url = http(input)
        val raw = if (url != null) app.get(url, timeout = 15L).text else input
        val collections = parse(raw, url)
        currentCoroutineContext().ensureActive()
        prefs.edit().putString(JSON, raw).putString(URL, url).apply()
        return collections.size
    }
}

private fun Context.collectionDp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
private fun Context.collectionText(value: String) = TextView(this).apply {
    text = value
    setTextColor(context.colorFromAttribute(R.attr.textColor))
    textSize = 16f
    setPadding(context.collectionDp(12), context.collectionDp(6),
        context.collectionDp(12), context.collectionDp(6))
}
private fun Context.collectionButton(value: String, action: () -> Unit) = Button(this).apply {
    text = value
    isAllCaps = false
    isFocusable = true
    setOnClickListener { action() }
}

internal fun showStremioCollectionSettings(context: Context, prefs: SharedPreferences,
                                            onClosed: (AlertDialog) -> Unit): AlertDialog {
    val scope = MainScope()
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = context.collectionDp(16)
        setPadding(padding, padding, padding, padding)
    }
    val enabled = Switch(context).apply {
        text = "Enable collections"
        isChecked = prefs.getBoolean(StremioCollections.ENABLED, false)
        setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(StremioCollections.ENABLED, checked).apply()
        }
    }
    val input = EditText(context).apply {
        hint = "Collection URL or JSON"
        setText(prefs.getString(StremioCollections.URL, ""))
        maxLines = 5
    }
    val status = context.collectionText("")
    fun updateCount() {
        status.text = "${StremioCollections.read(prefs).size} collections"
    }
    updateCount()
    panel.addView(enabled)
    panel.addView(input)
    panel.addView(status)
    var job: Job? = null
    val importButton = context.collectionButton("Import / Refresh") {
        if (job?.isActive == true) return@collectionButton
        val value = input.text.toString()
        if (value.isBlank()) return@collectionButton
        status.text = "Loading…"
        job = scope.launch {
            try {
                val count = withContext(Dispatchers.IO) { StremioCollections.importCollections(value, prefs) }
                prefs.edit().putBoolean(StremioCollections.ENABLED, true).apply()
                enabled.isChecked = true
                status.text = "$count collections"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status.text = "Unable to import collections. Previous collections kept." }
        }
    }
    panel.addView(importButton)
    panel.addView(context.collectionButton("Remove imported collections") {
        job?.cancel()
        prefs.edit().remove(StremioCollections.JSON).remove(StremioCollections.URL).apply()
        input.text.clear()
        updateCount()
    })
    val dialog = AlertDialog.Builder(context).setTitle("Collections")
        .setView(ScrollView(context).apply { addView(panel) })
        .setPositiveButton("Close", null).create()
    dialog.setOnDismissListener { scope.cancel(); onClosed(dialog) }
    dialog.show()
    return dialog
}

/** A collection folder is never a SearchResponse. */
private class FolderCardAdapter(
    private val folders: List<StremioCollectionFolder>,
    private val select: (StremioCollectionFolder, View) -> Unit
) : RecyclerView.Adapter<FolderCardAdapter.Holder>() {
    class Holder(val root: LinearLayout, val image: ImageView, val label: TextView, val emoji: TextView) :
        RecyclerView.ViewHolder(root) {
        var boundFolder: StremioCollectionFolder? = null
    }
    override fun getItemCount() = folders.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            val d = ctx.collectionDp(4)
            layoutParams = RecyclerView.LayoutParams(-2, -2).apply { setMargins(d, d, d, d) }
            val attr = android.util.TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, attr, true) && attr.resourceId != 0)
                foreground = androidx.core.content.ContextCompat.getDrawable(ctx, attr.resourceId)
            setOnFocusChangeListener { view, focused -> view.alpha = if (focused) 0.82f else 1f }
        }
        val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val emoji = ctx.collectionText("").apply { gravity = Gravity.CENTER; textSize = 32f }
        val frame = android.widget.FrameLayout(ctx).apply {
            addView(emoji, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(image, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        val label = ctx.collectionText("").apply { maxLines = 2 }
        root.addView(frame, LinearLayout.LayoutParams(-1, -2))
        root.addView(label, LinearLayout.LayoutParams(-1, -2))
        return Holder(root, image, label, emoji)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val folder = folders[position]
        holder.boundFolder = folder
        val ctx = holder.root.context
        val narrow = HomeChildItemAdapter.minPosterSize.takeIf { it > 0 } ?: ctx.collectionDp(114)
        val wide = HomeChildItemAdapter.maxPosterSize.takeIf { it > 0 } ?: ctx.collectionDp(180)
        val frame = holder.image.parent as View
        fun size(width: Int, height: Int) {
            holder.root.layoutParams = holder.root.layoutParams.apply { this.width = width }
            frame.layoutParams = frame.layoutParams.apply { this.height = height }
        }
        val landscape = folder.shape.equals("LANDSCAPE", true)
        val square = folder.shape.equals("SQUARE", true)
        size(if (landscape) wide else narrow, if (landscape || square) narrow else wide)
        holder.root.contentDescription = folder.title
        holder.label.text = folder.title
        holder.label.visibility = if (folder.hideTitle) View.GONE else View.VISIBLE
        holder.emoji.text = folder.emoji ?: "▣"
        holder.image.dispose()
        holder.image.setImageDrawable(null)
        holder.image.visibility = if (folder.cover == null) View.GONE else View.VISIBLE
        holder.emoji.visibility = View.VISIBLE
        folder.cover?.let { url ->
            holder.image.loadImage(url) {
                listener(onSuccess = success@{ request, result ->
                    if (holder.boundFolder !== folder || request.data.toString() != url) return@success
                    val w = result.image.width
                    val h = result.image.height
                    if (w > 0 && h > 0) {
                        val width = if (w > h) wide else narrow
                        size(width, stremioPosterHeight(width, w, h))
                        holder.emoji.visibility = View.GONE
                    }
                })
            }
        }
        holder.root.setOnClickListener { select(folder, holder.root) }
    }
    override fun onViewRecycled(holder: Holder) {
        holder.boundFolder = null
        holder.image.dispose()
        holder.image.setImageDrawable(null)
        holder.root.setOnClickListener(null)
    }
}

private fun folderRow(context: Context, folders: List<StremioCollectionFolder>,
                      select: (StremioCollectionFolder, View) -> Unit) =
    RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        adapter = FolderCardAdapter(folders, select)
        clipToPadding = false
        setPadding(context.collectionDp(8), 0, context.collectionDp(8), 0)
    }

/** Inserts real folder rows into the existing native home header; never replaces its adapter. */
internal class StremioCollectionHome(private val prefs: SharedPreferences) {
    private var header: LinearLayout? = null
    private var section: LinearLayout? = null
    private var signature: Triple<Boolean, String?, String?>? = null
    private var active: StremioFolderDialog? = null

    fun sync(activity: FragmentActivity?, master: RecyclerView?) {
        if (activity == null || master == null) {
            active?.dismiss()
            active = null
            detach()
            return
        }
        val target = master.findViewById<LinearLayout>(R.id.home_header)
        val current = Triple(prefs.getBoolean(StremioCollections.ENABLED, false),
            prefs.getString(StremioCollections.JSON, null), prefs.getString(StremioCollections.URL, null))
        if (target === header && current == signature && (section == null || section?.parent === target)) return
        if (!current.first) {
            active?.dismiss()
            active = null
        }
        detach()
        if (target == null || !current.first) return
        val collections = StremioCollections.read(prefs).filter { it.folders.isNotEmpty() }
            .sortedByDescending { it.pinned }
        header = target
        signature = current
        if (collections.isEmpty()) return
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            collections.forEach { collection ->
                addView(activity.collectionText(collection.title))
                addView(folderRow(activity, collection.folders) { folder, focus ->
                    active?.dismiss()
                    active = StremioFolderDialog(activity, collection, folder, focus).also { it.show() }
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        section = panel
        target.addView(panel, LinearLayout.LayoutParams(-1, -2))
    }
    private fun detach() {
        section?.let { panel ->
            for (i in 0 until panel.childCount) (panel.getChildAt(i) as? RecyclerView)?.adapter = null
            (panel.parent as? ViewGroup)?.removeView(panel)
        }
        section = null
        header = null
        signature = null
    }
}

private class StremioFolderDialog(
    private val activity: FragmentActivity,
    private val collection: StremioCollection,
    private val folder: StremioCollectionFolder,
    private val origin: View,
    private val onClosed: () -> Unit = {}
) {
    private val dialog = AppCompatDialog(activity, R.style.DialogFullscreen)
    private val scope = MainScope()
    private val ratios = StremioThumbnailRatios(activity.resources.displayMetrics.density)
    private val navigation = (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        as? NavHostFragment)?.navController
    private val handoff = StremioDetailHandoff(activity, dialog, navigation, ::dismiss,
        preserveNestedNavigation = true)
    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(activity.colorFromAttribute(R.attr.primaryBlackBackground))
    }
    private val grid = AutofitRecyclerView(activity).apply { spanCount = activity.getSpanCount(false) }
    private val gridAdapter = SearchAdapter(grid, false) { click(it) }
    private val status = activity.collectionText("")
    private val tabs = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    private val more = activity.collectionButton("Load more / Retry") { requestSelected() }
    private val rowPanel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val rowsMode = collection.mode.equals("ROWS", true) || collection.mode.equals("FOLLOW_LAYOUT", true)
    private val allEnabled = collection.showAll && folder.sources.size > 1
    private var selected = if (allEnabled) -1 else 0
    private val tabScroll = mutableMapOf<Int, android.os.Parcelable>()
    private var child: StremioFolderDialog? = null
    private var closed = false
    private val all = mutableListOf<SearchResponse>()
    private val allSeen = mutableSetOf<String>()
    private val limit = Semaphore(4)
    private data class State(
        val source: StremioCollectionSource,
        var provider: StremioC? = null, var catalog: StremioC.Catalog? = null,
        var offset: Int = 0, var more: Boolean = true, var loading: Boolean = false,
        var error: String? = null, val items: MutableList<SearchResponse> = mutableListOf(),
        val seen: MutableSet<String> = mutableSetOf(), val pages: MutableSet<List<String>> = mutableSetOf()
    )
    private val states = folder.sources.map { State(it) }
    private val rowAdapters = mutableListOf<HomeChildItemAdapter>()
    private val rowStatuses = mutableListOf<TextView>()
    private val rowButtons = mutableListOf<Button>()
    private val hostObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) dismiss()
    }

    private fun click(click: SearchClickCallback) {
        if (closed || handoff.isActive) return
        when (click.action) {
            SEARCH_ACTION_SHOW_METADATA -> SearchHelper.handleSearchClickCallback(click)
            SEARCH_ACTION_LOAD -> {
                val covered = handoff.begin()
                SearchHelper.handleSearchClickCallback(click)
                if (!covered) dismiss()
            }
        }
    }

    fun show() {
        root.addView(activity.collectionButton("‹  ${folder.title}") { dismiss() })
        if (folder.folders.isNotEmpty()) root.addView(folderRow(activity, folder.folders) { next, focus ->
            child?.dismiss()
            dialog.hide()
            child = StremioFolderDialog(activity, collection, next, focus) {
                child = null
                if (!closed && !activity.isDestroyed && !activity.isFinishing) {
                    if (navigation?.currentDestination?.id == R.id.navigation_home) dialog.show()
                    else dismiss()
                }
            }.also { it.show() }
        }, LinearLayout.LayoutParams(-1, -2))
        if (rowsMode) {
            states.forEachIndexed { index, state ->
                rowPanel.addView(activity.collectionText(state.source.title))
                val recycler = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                }
                val adapter = HomeChildItemAdapter(0, clickCallback = ::click)
                recycler.adapter = adapter
                ratios.watch(recycler)
                rowAdapters.add(adapter)
                rowPanel.addView(recycler, LinearLayout.LayoutParams(-1, -2))
                rowStatuses.add(activity.collectionText("").also { rowPanel.addView(it) })
                rowButtons.add(activity.collectionButton("Load more / Retry") { load(listOf(index)) }
                    .also { rowPanel.addView(it) })
            }
            root.addView(ScrollView(activity).apply { addView(rowPanel) }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            if (allEnabled) tabs.addView(activity.collectionButton("All") { select(-1) })
            states.forEachIndexed { index, state ->
                tabs.addView(activity.collectionButton(state.source.title) { select(index) })
            }
            root.addView(HorizontalScrollView(activity).apply { addView(tabs) })
            grid.adapter = gridAdapter
            ratios.watch(grid)
            root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
            grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0 && !grid.canScrollVertically(1)) requestSelected(false)
                }
            })
            root.addView(more)
        }
        root.addView(status)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            closed = true
            child?.dismiss()
            handoff.close()
            scope.cancel()
            ratios.close()
            grid.adapter = null
            for (i in 0 until rowPanel.childCount) (rowPanel.getChildAt(i) as? RecyclerView)?.adapter = null
            activity.lifecycle.removeObserver(hostObserver)
            onClosed()
            if (origin.isAttachedToWindow && origin.isShown) origin.requestFocus()
        }
        activity.lifecycle.addObserver(hostObserver)
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        update()
        requestSelected()
    }
    fun dismiss() { if (!closed) dialog.dismiss() }

    private fun select(index: Int) {
        if (selected == index) return
        grid.layoutManager?.onSaveInstanceState()?.let { tabScroll[selected] = it }
        selected = index
        update(restoreScroll = true)
        val indices = if (index < 0) states.indices.toList() else listOf(index)
        load(indices.filter { states[it].items.isEmpty() && states[it].error == null })
    }

    private fun requestSelected(retry: Boolean = true) {
        val indices = if (rowsMode || selected < 0) states.indices.toList() else listOf(selected)
        load(indices.filter { it in states.indices && (retry || states[it].error == null) })
    }

    private fun load(indices: List<Int>) {
        if (closed) return
        val requested = indices.distinct().filter { states[it].more && !states[it].loading }
        if (requested.isEmpty()) return
        requested.forEach { states[it].loading = true; states[it].error = null }
        update()
        scope.launch {
            requested.map { index ->
                async {
                    val state = states[index]
                    try {
                        val page = withContext(Dispatchers.IO) { limit.withPermit { fetch(state) } }
                        if (closed) return@async
                        val signatures = page.first.map { it.url }
                        val repeated = !state.pages.add(signatures)
                        state.items.addAll(page.first.filter { state.seen.add(it.url) })
                        state.offset += page.second
                        state.more = state.catalog?.supportsExtra("skip") == true && page.second > 0 && !repeated
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { state.error = e.message ?: "Unable to load source" }
                    finally {
                        state.loading = false
                        if (!closed && (rowsMode || selected == index)) update()
                    }
                }
            }.awaitAll()
            if (!closed) { merge(); update() }
        }
    }

    private suspend fun fetch(state: State): Pair<List<SearchResponse>, Int> {
        val source = state.source
        require(source.provider == "addon") { "Unsupported source: ${source.provider}" }
        if (state.provider == null || state.catalog == null) {
            val providers = APIHolder.allProviders.filterIsInstance<StremioC>()
                .filter { StremioC.isCatalogAddonEnabled(it.mainUrl, it.addonId) }
                .sortedByDescending { it.hasMainPage }
                .distinctBy { stremioManifestUrl(it.mainUrl.fixSourceUrl()) }
                .filter { source.url == null || stremioManifestUrl(it.mainUrl.fixSourceUrl()) ==
                    stremioManifestUrl(source.url.fixSourceUrl()) }
            val matches = providers.mapNotNull { provider ->
                val manifest = try { provider.browseManifest() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null } ?: return@mapNotNull null
                if (source.addonId != null && manifest.id != source.addonId) return@mapNotNull null
                val catalog = manifest.catalogs.firstOrNull { it.type == source.type && it.id == source.catalogId }
                    ?: return@mapNotNull null
                provider to catalog
            }
            require(matches.size == 1) { if (matches.isEmpty()) "Catalog unavailable or disabled" else "Ambiguous catalog source" }
            state.provider = matches[0].first
            state.catalog = matches[0].second
        }
        val provider = requireNotNull(state.provider)
        val catalog = requireNotNull(state.catalog)
        require(StremioC.isCatalogAddonEnabled(provider.mainUrl, provider.addonId)) { "Addon disabled" }
        require(catalog.browseBlockReason() == null) { catalog.browseBlockReason().orEmpty() }
        val genre = source.genre ?: catalog.homeGenre()
        return provider.browsePage(catalog, genre, state.offset)
    }

    private fun merge() {
        if (!allEnabled) return
        val max = states.maxOfOrNull { it.items.size } ?: 0
        for (i in 0 until max) for (state in states) {
            val item = state.items.getOrNull(i) ?: continue
            val uri = Uri.parse(item.url)
            val segments = uri.pathSegments
            val meta = segments.indexOfLast { it == "meta" }
            val key = if (meta >= 0 && meta + 2 < segments.size)
                segments[meta + 1] + ":" + segments[meta + 2].removeSuffix(".json") else item.url
            if (allSeen.add(key)) all.add(item)
        }
    }

    private fun update(restoreScroll: Boolean = false) {
        if (closed) return
        if (rowsMode) {
            states.forEachIndexed { i, state ->
                rowAdapters.getOrNull(i)?.submitList(state.items.toList())
                rowStatuses.getOrNull(i)?.text = state.error ?: if (state.loading) "Loading…" else ""
                rowButtons.getOrNull(i)?.visibility = if (state.more && !state.loading) View.VISIBLE else View.GONE
            }
        } else {
            val current = states.getOrNull(selected)
            val tab = selected
            gridAdapter.submitList(if (tab < 0) all.toList() else current?.items?.toList().orEmpty()) {
                if (restoreScroll && !closed && selected == tab) {
                    val saved = tabScroll[tab]
                    if (saved != null) grid.layoutManager?.onRestoreInstanceState(saved)
                    else grid.scrollToPosition(0)
                }
            }
            val relevant = if (selected < 0) states else listOfNotNull(current)
            more.visibility = if (relevant.any { it.more && !it.loading }) View.VISIBLE else View.GONE
            status.text = relevant.mapNotNull { it.error?.let { error -> "${it.source.title}: $error" } }
                .joinToString("\n").ifEmpty {
                    if (relevant.any { it.loading }) "Loading…" else if (relevant.all { it.items.isEmpty() }) "No results" else ""
                }
            for (i in 0 until tabs.childCount) tabs.getChildAt(i).isSelected =
                i == if (allEnabled) selected + 1 else selected
        }
    }
}
