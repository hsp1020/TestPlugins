package com.hsp1020

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
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
    val type: String, val catalogId: String, val title: String, val genre: String?
)
internal data class StremioCollectionFolder(
    val id: String, val title: String, val cover: String?, val shape: String,
    val sources: List<StremioCollectionSource> = emptyList(),
    val folders: List<StremioCollectionFolder> = emptyList(),
    val rows: Boolean = false, val showAll: Boolean = true,
    val merge: String = "sequential", val shuffle: Boolean = false
)
private fun JSONObject.string(key: String): String? =
    optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

internal fun parseStremioCollectionMeta(raw: String, id: String): StremioCollectionFolder {
    val meta = JSONObject(raw).getJSONObject("meta")
    require(meta.string("type").equals("collection", true) && meta.string("id") == id) {
        "Invalid collection metadata"
    }
    fun folder(value: JSONObject): StremioCollectionFolder? {
        if (!value.string("type").equals("collection", true)) return null
        val folderId = value.string("id") ?: return null
        val detail = value.optJSONObject("collection") ?: JSONObject()
        val sources = detail.optJSONArray("sources")?.objects()?.mapNotNull { source ->
            val type = source.string("type") ?: return@mapNotNull null
            val catalogId = source.string("catalogId") ?: return@mapNotNull null
            if (type.equals("collection", true)) return@mapNotNull null
            StremioCollectionSource(type, catalogId, source.string("name") ?: catalogId, source.string("genre"))
        }?.distinctBy { Triple(it.type, it.catalogId, it.genre) }.orEmpty()
        return StremioCollectionFolder(folderId, value.string("name") ?: folderId,
            value.string("poster") ?: value.string("landscapePoster"),
            value.string("posterShape") ?: "poster", sources,
            detail.optJSONArray("items")?.objects()?.mapNotNull { item ->
                if (!item.string("type").equals("collection", true)) null
                else item.string("id")?.let { childId ->
                    StremioCollectionFolder(childId, item.string("name") ?: childId,
                        item.string("poster") ?: item.string("landscapePoster"),
                        item.string("posterShape") ?: "poster")
                }
            }?.distinctBy { it.id }.orEmpty(),
            detail.string("layout").equals("rows", true), detail.optBoolean("showAll", true),
            detail.string("merge") ?: "sequential", detail.optBoolean("shuffle"))
    }
    return requireNotNull(folder(meta))
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

/** A collection folder is never a SearchResponse. */
internal class FolderCardAdapter(
    private val folders: List<StremioCollectionFolder>,
    private val grid: AutofitRecyclerView? = null,
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
        val landscape = folder.shape.equals("landscape", true)
        val square = folder.shape.equals("square", true)
        fun cardWidth(isWide: Boolean) = grid?.itemWidth?.minus(ctx.collectionDp(8))?.takeIf { it > 0 }
            ?: if (isWide) wide else narrow
        val defaultWidth = cardWidth(landscape)
        size(defaultWidth, when { landscape -> stremioPosterHeight(defaultWidth, 16, 9)
            square -> defaultWidth; else -> stremioPosterHeight(defaultWidth, 2, 3) })
        holder.root.contentDescription = folder.title
        holder.label.text = folder.title
        holder.label.visibility = View.VISIBLE
        holder.emoji.text = "▣"
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
                        val width = cardWidth(w > h)
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
        adapter = FolderCardAdapter(folders, select = select)
        clipToPadding = false
        setPadding(context.collectionDp(8), 0, context.collectionDp(8), 0)
    }

/** Inserts real folder rows into the existing native home header; never replaces its adapter. */
internal class StremioCollectionHome(private val prefs: SharedPreferences) {
    private var header: LinearLayout? = null
    private var section: LinearLayout? = null
    private var provider: StremioC? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var loaded: List<Pair<StremioC.Catalog, List<StremioCollectionFolder>>>? = null
    private var active: StremioFolderDialog? = null

    fun sync(activity: FragmentActivity?, master: RecyclerView?, selected: StremioC?) {
        if (activity == null || selected == null || !selected.hasMainPage) {
            close()
            return
        }
        if (master == null) {
            if (loaded == null) { job?.cancel(); job = null }
            detach()
            return
        }
        if (provider !== selected) {
            active?.dismiss()
            active = null
            job?.cancel()
            scope?.cancel()
            loaded = null
            provider = selected
            scope = MainScope()
            job = null
            detach()
        }
        val target = master.findViewById<LinearLayout>(R.id.home_header) ?: return
        if (header !== target) {
            detach()
            header = target
        }
        if (loaded != null) {
            if (section == null) render(activity, selected)
            return
        }
        if (job != null) return
        job = scope?.launch {
            val result = withContext(Dispatchers.IO) {
                val manifest = try { selected.browseManifest() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                val key = stremioCatalogKey(selected.mainUrl)
                migrateStremioCatalogSettings(prefs, selected.mainUrl)
                val order = prefs.getString("catalog_order_$key", null)?.let { raw ->
                    runCatching {
                        val array = JSONArray(raw)
                        (0 until array.length()).associate { array.getString(it) to it }
                    }.getOrNull()
                }.orEmpty()
                val catalogs = manifest?.catalogs.orEmpty().filter { catalog ->
                    if (!catalog.type.equals("collection", true) || catalog.browseBlockReason() != null) return@filter false
                    val tab = "${catalog.id}_${catalog.type}_default"
                    prefs.getBoolean("tab_enabled_${key}_$tab", true) &&
                        prefs.getBoolean("tab_home_${key}_$tab", true)
                }.sortedBy { order["${it.id}_${it.type}_default"] ?: Int.MAX_VALUE }
                val semaphore = Semaphore(4)
                coroutineScope {
                    catalogs.map { catalog -> async {
                        val genre = catalog.homeGenre() ?: if (catalog.requiresExtra("genre"))
                            catalog.browseGenres().firstOrNull() else null
                        try {
                            catalog to semaphore.withPermit { selected.browseCollectionFolders(catalog, genre).first }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { catalog to emptyList() }
                    } }.awaitAll()
                }
            }
            if (provider === selected) {
                loaded = result
                render(activity, selected)
            }
        }
    }

    fun refresh() {
        job?.cancel()
        job = null
        loaded = null
        val target = header
        detach()
        header = target
    }

    private fun render(activity: FragmentActivity, source: StremioC) {
        val target = header ?: return
        if (!target.isAttachedToWindow || section != null) return
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            loaded.orEmpty().filter { it.second.isNotEmpty() }.forEach { (catalog, folders) ->
                addView(activity.collectionText(catalog.name ?: catalog.id.orEmpty()))
                addView(folderRow(activity, folders) { folder, focus ->
                    active?.dismiss()
                    active = StremioFolderDialog(activity, source, folder, focus).also { it.show() }
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
    }

    fun close() {
        active?.dismiss()
        active = null
        job?.cancel()
        scope?.cancel()
        job = null
        scope = null
        loaded = null
        provider = null
        detach()
    }
}

internal class StremioFolderDialog(
    private val activity: FragmentActivity,
    private val provider: StremioC,
    private var folder: StremioCollectionFolder,
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
    private val rowsMode get() = folder.rows
    private val allEnabled get() = folder.showAll && folder.sources.size > 1
    private var selected = 0
    private val tabScroll = mutableMapOf<Int, android.os.Parcelable>()
    private var child: StremioFolderDialog? = null
    private var closed = false
    private val all = mutableListOf<SearchResponse>()
    private val allSeen = mutableSetOf<String>()
    private val limit = Semaphore(4)
    private data class State(
        val source: StremioCollectionSource,
        var catalog: StremioC.Catalog? = null,
        var offset: Int = 0, var more: Boolean = true, var loading: Boolean = false,
        var error: String? = null, var mergedCount: Int = 0,
        val items: MutableList<SearchResponse> = mutableListOf(),
        val seen: MutableSet<String> = mutableSetOf(), val pages: MutableSet<List<String>> = mutableSetOf()
    )
    private var states = emptyList<State>()
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

    private fun render() {
        root.removeAllViews()
        root.addView(activity.collectionButton("‹  ${folder.title}") { dismiss() })
        if (folder.folders.isNotEmpty()) root.addView(folderRow(activity, folder.folders) { next, focus ->
            child?.dismiss()
            dialog.hide()
            child = StremioFolderDialog(activity, provider, next, focus) {
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
        update()
        requestSelected()
    }

    fun show() {
        root.addView(activity.collectionButton("‹  ${folder.title}") { dismiss() })
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
        loadFolder()
    }

    private fun loadFolder() {
        status.text = "Loading…"
        scope.launch {
            try {
                folder = withContext(Dispatchers.IO) { provider.browseCollectionDetail(folder.id) }
                if (closed) return@launch
                states = folder.sources.map { State(it) }
                selected = if (allEnabled) -1 else 0
                render()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (!closed) {
                    status.text = "Unable to load collection."
                    root.addView(activity.collectionButton("Retry") {
                        root.removeViewAt(root.childCount - 1)
                        loadFolder()
                    })
                }
            }
        }
    }
    fun dismiss() { if (!closed) dialog.dismiss() }

    private fun select(index: Int) {
        if (selected == index) return
        grid.layoutManager?.onSaveInstanceState()?.let { tabScroll[selected] = it }
        selected = index
        if (index < 0 && states.none { it.loading }) merge()
        update(restoreScroll = true)
        val indices = if (index < 0) states.indices.toList() else listOf(index)
        load(indices.filter { states[it].items.isEmpty() && states[it].error == null })
    }

    private fun requestSelected(retry: Boolean = true) {
        val indices = if (rowsMode || selected < 0) states.indices.toList() else listOf(selected)
        load(indices.filter { it in states.indices && (retry || states[it].error == null) })
    }

    private fun load(indices: List<Int>) {
        if (closed || (!rowsMode && selected < 0 && states.any { it.loading })) return
        val eligible = indices.distinct().filter { states[it].more && !states[it].loading }
        val requested = if (!rowsMode && selected < 0 && folder.merge == "sequential") {
            val first = states.indexOfFirst { it.more }
            eligible.filter { it == first }
        } else eligible
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
            if (!closed) {
                merge()
                update()
                if (!rowsMode && selected < 0 && folder.merge == "sequential" &&
                    requested.none { states[it].more || states[it].error != null } &&
                    states.any { it.more } && all.isEmpty()) requestSelected(false)
            }
        }
    }

    private suspend fun fetch(state: State): Pair<List<SearchResponse>, Int> {
        val source = state.source
        if (state.catalog == null) {
            state.catalog = provider.browseCatalogs().firstOrNull {
                it.type == source.type && it.id == source.catalogId
            } ?: throw IllegalStateException("Catalog unavailable")
        }
        val catalog = requireNotNull(state.catalog)
        require(StremioC.isCatalogAddonEnabled(provider.mainUrl, provider.addonId)) { "Addon disabled" }
        require(catalog.browseBlockReason() == null) { catalog.browseBlockReason().orEmpty() }
        val genre = source.genre ?: catalog.homeGenre() ?: if (catalog.requiresExtra("genre"))
            catalog.browseGenres().firstOrNull() else null
        return provider.browsePage(catalog, genre, state.offset)
    }

    private fun merge() {
        if (!allEnabled || selected >= 0 || rowsMode) return
        val eligible = if (folder.merge == "sequential") {
            val stop = states.indexOfFirst { it.more }
            if (stop < 0) states else states.take(stop + 1)
        } else states
        val pending = eligible.map { it.items.drop(it.mergedCount) }
        val max = pending.maxOfOrNull { it.size } ?: 0
        val merged = if (folder.merge == "interleaved") buildList {
            for (i in 0 until max) for (items in pending) items.getOrNull(i)?.let { add(it) }
        } else pending.flatten()
        eligible.forEach { it.mergedCount = it.items.size }
        val fresh = mutableListOf<SearchResponse>()
        for (item in merged) {
            val uri = Uri.parse(item.url)
            val segments = uri.pathSegments
            val meta = segments.indexOfLast { it == "meta" }
            val key = if (meta >= 0 && meta + 2 < segments.size)
                segments[meta + 1] + ":" + segments[meta + 2].removeSuffix(".json") else item.url
            if (allSeen.add(key)) fresh.add(item)
        }
        all.addAll(if (folder.shuffle) fresh.shuffled() else fresh)
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
