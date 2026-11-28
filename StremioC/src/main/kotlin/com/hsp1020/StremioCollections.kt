package com.hsp1020

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.isBottomLayout
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
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
    val merge: String = "sequential", val shuffle: Boolean = false,
    val hideTitle: Boolean = false, val emoji: String? = null,
    val backdrop: String? = null, val logo: String? = null,
    val presentation: CollectionPresentation = CollectionPresentation(),
    val catalogCount: Int? = null
)
private fun JSONObject.string(key: String): String? =
    optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

internal data class CollectionPresentation(
    val hideTitle: Boolean = false, val pinToTop: Boolean = false,
    val viewMode: String? = null, val backdrop: String? = null,
    val afterId: String? = null, val afterType: String? = null,
    val showAll: Boolean? = null
)

internal class CollectionExport private constructor(
    private val collections: Map<String, CollectionPresentation>,
    private val folders: Map<String, FolderPresentation>
) {
    private data class FolderPresentation(val hideTitle: Boolean, val emoji: String?, val catalogCount: Int)

    fun apply(folder: StremioCollectionFolder): StremioCollectionFolder {
        val key = folder.id.removePrefix("aiom.collection:")
        if (key == folder.id) return folder
        val style = folders[key]
        val presentation = collections[key.substringBefore(':')]?.let {
            it.copy(backdrop = it.backdrop ?: folder.presentation.backdrop)
        } ?: folder.presentation
        return folder.copy(
            hideTitle = style?.hideTitle ?: folder.hideTitle, emoji = style?.emoji ?: folder.emoji,
            rows = when (presentation.viewMode) {
                "ROWS" -> true
                "TABBED_GRID", "FOLLOW_LAYOUT" -> false
                else -> folder.rows
            },
            showAll = presentation.showAll ?: folder.showAll,
            catalogCount = style?.catalogCount ?: folder.catalogCount,
            presentation = presentation, folders = folder.folders.map(::apply)
        )
    }

    companion object {
        val EMPTY = CollectionExport(emptyMap(), emptyMap())
        fun parse(raw: String): CollectionExport {
            val json = JSONObject(raw)
            require(json.string("exportType") == "aiometadataCollections" && json.optInt("exportVersion") == 1)
            val collections = mutableMapOf<String, CollectionPresentation>()
            val folders = mutableMapOf<String, FolderPresentation>()
            fun countSources(folder: JSONObject): Int = (folder.optJSONArray("sources")?.length() ?: 0) +
                folder.optJSONArray("folders")?.objects().orEmpty().sumOf(::countSources)
            fun visit(collectionId: String, entries: JSONArray?) {
                entries?.objects()?.forEach { folder ->
                    val id = folder.string("id") ?: return@forEach
                    folders["$collectionId:$id"] = FolderPresentation(
                        folder.optBoolean("hideTitle"), folder.string("coverEmoji"), countSources(folder))
                    visit(collectionId, folder.optJSONArray("folders"))
                }
            }
            json.getJSONArray("entries").objects().forEach { entry ->
                if (entry.string("kind") != "collection") return@forEach
                val id = entry.string("id") ?: return@forEach
                collections[id] = CollectionPresentation(
                    entry.optBoolean("hideTitle"), entry.optBoolean("pinToTop"),
                    entry.string("viewMode")?.takeIf { it == "ROWS" || it == "TABBED_GRID" || it == "FOLLOW_LAYOUT" },
                    entry.string("backdropImageUrl"),
                    entry.optJSONObject("after")?.string("id"), entry.optJSONObject("after")?.string("type"),
                    if (entry.has("showAllTab")) entry.optBoolean("showAllTab", true) else null
                )
                visit(id, entry.optJSONArray("folders"))
            }
            return CollectionExport(collections, folders)
        }
    }
}

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
                        item.string("posterShape") ?: "poster",
                         hideTitle = item.optBoolean("hideTitle"), emoji = item.string("coverEmoji"),
                         backdrop = item.string("background"), logo = item.string("logo"))
                }
            }?.distinctBy { it.id }.orEmpty(),
            detail.string("layout").equals("rows", true), detail.optBoolean("showAll", true),
            detail.string("merge") ?: "sequential", detail.optBoolean("shuffle"),
             hideTitle = value.optBoolean("hideTitle"), emoji = value.string("coverEmoji"),
             backdrop = value.string("background"), logo = value.string("logo"),
             catalogCount = if ((detail.optJSONArray("items")?.length() ?: 0) > 0) null else sources.size)
    }
    return requireNotNull(folder(meta))
}

private fun Context.collectionDp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

/** Folder cards use the native card layouts, but never enter the media search/load pipeline. */
internal class FolderCardAdapter(
    private val folders: List<StremioCollectionFolder>,
    private val grid: AutofitRecyclerView? = null,
    private val rows: Boolean = false,
    private val select: (StremioCollectionFolder, View) -> Unit
) : RecyclerView.Adapter<FolderCardAdapter.Holder>() {
    class Holder(val root: View, rows: Boolean) : RecyclerView.ViewHolder(root) {
        val card: View = root.findViewById(R.id.background_card)
        val image: ImageView = root.findViewById(R.id.imageView)
        val label: TextView = root.findViewById(R.id.imageText)
        var boundFolder: StremioCollectionFolder? = null
        var parentList: RecyclerView? = null
        var emoji: TextView? = null
        val count: TextView? = if (rows) root.findViewById(R.id.imageTextExtra) else null
        val art = if (rows) image.parent as View else card
        val rowMinHeight = root.layoutParams?.height ?: 0
        val labelGap = if (rows) {
            val labels = label.parent as View
            ((labels.layoutParams as ViewGroup.MarginLayoutParams).marginStart - art.layoutParams.width).coerceAtLeast(0)
        } else 0
    }
    override fun getItemCount() = folders.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val layout = if (rows) R.layout.search_result_compact else if (grid == null) {
            if (ctx.isBottomLayout()) R.layout.home_result_grid_expanded else R.layout.home_result_grid
        } else {
            if (ctx.isBottomLayout()) R.layout.search_result_grid_expanded else R.layout.search_result_grid
        }
        return Holder(android.view.LayoutInflater.from(ctx).inflate(layout, parent, false), rows).apply {
            parentList = parent as? RecyclerView
            root.isFocusable = !rows
            root.isClickable = !rows
            if (card !== root) card.isFocusable = rows
            if (rows) {
                card.foreground = tvOutlinedForeground(card, card.foreground)
                (art.layoutParams as android.widget.FrameLayout.LayoutParams).gravity = android.view.Gravity.CENTER_VERTICAL
                label.applyStremioCNativeFont(bold = true)
                count?.applyStremioCNativeFont(bold = true)
                root.findViewById<View>(R.id.imageTextProvider)?.visibility = View.GONE
            } else label.applyStremioCNativeFont()
            label.visibility = View.VISIBLE
            listOf(R.id.text_quality, R.id.text_is_dub, R.id.text_is_sub, R.id.text_rating,
                R.id.text_flag, R.id.episode_text, R.id.watchProgress, R.id.search_item_download_play)
                .forEach { root.findViewById<View>(it)?.visibility = View.GONE }
        }
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val folder = folders[position]
        holder.boundFolder = folder
        val ctx = holder.root.context
        fun cardWidth(wide: Boolean) = grid?.itemWidth?.minus(ctx.collectionDp(4))?.takeIf { it > 0 }
            ?: (if (wide) HomeChildItemAdapter.maxPosterSize else HomeChildItemAdapter.minPosterSize)
                .takeIf { it > 0 } ?: ctx.collectionDp(if (wide) 180 else 114)
        fun size(width: Int, height: Int) {
            val computing = holder.parentList?.isComputingLayout == true
            if (rows) {
                val cardMargins = holder.card.layoutParams as ViewGroup.MarginLayoutParams
                val rowHeight = maxOf(holder.rowMinHeight, height + cardMargins.topMargin + cardMargins.bottomMargin)
                resizeStremioCard(holder.root, height = rowHeight, duringLayout = computing)
                resizeStremioCard(holder.art, width, height, computing)
                val labels = holder.label.parent as View
                val params = labels.layoutParams as ViewGroup.MarginLayoutParams
                val start = width + holder.labelGap
                if (params.marginStart != start) {
                    params.marginStart = start
                    if (computing) { labels.forceLayout(); holder.card.forceLayout(); holder.root.forceLayout() }
                    else labels.layoutParams = params
                }
            } else {
                resizeStremioCard(holder.card, width, height, computing)
                if (holder.label.parent !== holder.card && holder.card !== holder.root)
                    resizeStremioCard(holder.label, width = width, duringLayout = computing)
            }
        }
        val landscape = folder.shape.equals("landscape", true)
        val width = cardWidth(landscape)
        size(width, when {
            landscape -> stremioPosterHeight(width, 16, 9)
            folder.shape.equals("square", true) -> width
            else -> stremioPosterHeight(width, 2, 3)
        })
        holder.root.contentDescription = folder.title
        holder.label.text = folder.title
        if (rows) {
            holder.count?.apply {
                text = folder.catalogCount?.let { "$it catalog" + if (it == 1) "" else "s" }.orEmpty()
                visibility = if (folder.catalogCount == null) View.GONE else View.VISIBLE
            }
            holder.card.contentDescription = listOf(folder.title, holder.count?.text?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(", ")
        }
        if (!folder.emoji.isNullOrBlank() && holder.emoji == null) {
            (holder.art as? android.widget.FrameLayout)?.let { frame ->
                holder.emoji = TextView(ctx).apply {
                    gravity = android.view.Gravity.CENTER
                    setTextSize(32f)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    frame.addView(this, android.widget.FrameLayout.LayoutParams(-1, -1))
                }
            }
        }
        val hasEmoji = !folder.emoji.isNullOrBlank() && holder.emoji != null
        val showEmoji = folder.cover.isNullOrBlank() && hasEmoji
        holder.emoji?.text = folder.emoji.orEmpty()
        holder.emoji?.visibility = if (showEmoji) View.VISIBLE else View.GONE
        holder.label.visibility = if (folder.hideTitle) View.GONE else View.VISIBLE
        holder.root.findViewById<View>(R.id.title_shadow)?.visibility = holder.label.visibility
        holder.image.dispose()
        holder.image.setImageDrawable(null)
        val url = folder.cover?.takeIf { it.isNotBlank() }
        if (showEmoji) Unit
        else if (url == null) holder.image.loadImage(R.drawable.default_cover)
        else holder.image.loadImage(url) {
            listener(onError = { _, _ ->
                if (holder.boundFolder === folder && hasEmoji) holder.emoji?.visibility = View.VISIBLE
            }, onSuccess = success@{ request, result ->
                if (holder.boundFolder !== folder || request.data.toString() != url) return@success
                val w = result.image.width
                val h = result.image.height
                if (w > 0 && h > 0) {
                    val applySize = {
                        if (holder.boundFolder === folder) {
                            val width = cardWidth(w > h)
                            size(width, stremioPosterHeight(width, w, h))
                        }
                    }
                    if (holder.parentList?.isComputingLayout == true) holder.parentList?.post { applySize() }
                    else applySize()
                }
            })
        }
        if (!rows) holder.root.setOnClickListener { select(folder, holder.root) }
        if (holder.card !== holder.root) holder.card.setOnClickListener { select(folder, if (rows) holder.card else holder.root) }
    }
    override fun onViewRecycled(holder: Holder) {
        holder.boundFolder = null
        holder.emoji?.visibility = View.GONE
        holder.image.dispose()
        holder.image.setImageDrawable(null)
        holder.root.setOnClickListener(null)
        if (holder.card !== holder.root) holder.card.setOnClickListener(null)
    }
}

/** Inserts folder rows in the native home header or under their configured catalog anchor. */
internal class StremioCollectionHome(
    private val prefs: SharedPreferences,
    private val openCatalog: (CatalogShortcutTarget, CatalogHomeSnapshot?) -> Unit
) {
    private var header: LinearLayout? = null
    private var section: LinearLayout? = null
    private var recycler: RecyclerView? = null
    private data class PlacedRow(val view: View, val anchorName: String?)
    private val placedRows = mutableListOf<PlacedRow>()
    private var placementPosted = false
    private var provider: StremioC? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var loaded: List<Pair<StremioC.Catalog, Pair<List<StremioCollectionFolder>, Int>>>? = null

    fun sync(activity: FragmentActivity?, master: RecyclerView?, selected: StremioC?) {
        if (activity == null || selected == null || !selected.hasMainPage) {
            close()
            return
        }
        if (master == null) {
            recycler = null
            if (loaded == null) { job?.cancel(); job = null }
            detach()
            return
        }
        if (provider !== selected) {
            job?.cancel()
            scope?.cancel()
            loaded = null
            provider = selected
            scope = MainScope()
            job = null
            detach()
        }
        recycler = master
        val target = master.findViewById<LinearLayout>(R.id.home_header) ?: return
        if (header !== target) {
            detach()
            header = target
        }
        if (loaded != null) {
            if (section == null) render(activity, selected)
            else placeRows(master)
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
                            catalog to semaphore.withPermit { selected.browseCollectionFolders(catalog, genre) }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { catalog to (emptyList<StremioCollectionFolder>() to 0) }
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
        provider?.invalidateCollectionExport()
        val target = header
        detach()
        header = target
    }

    private fun render(activity: FragmentActivity, source: StremioC) {
        val target = header ?: return
        if (!target.isAttachedToWindow || section != null) return
        val anchorNames = source.mainPage.associate { entry ->
            val route = entry.data.substringAfter("/catalog/", "")
            val type = Uri.decode(route.substringBefore('/'))
            val id = Uri.decode(route.substringAfter('/', "").substringBefore('/').removeSuffix(".json"))
            (id to type) to entry.name
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            loaded.orEmpty().filter { it.second.first.isNotEmpty() }
                .sortedByDescending { it.second.first.firstOrNull()?.presentation?.pinToTop == true }
                .forEach { (catalog, page) ->
                val (folders, count) = page
                val presentation = folders.first().presentation
                val anchorName = if (presentation.pinToTop) null else
                    presentation.afterId?.let { id -> presentation.afterType?.let { type -> anchorNames[id to type] } }
                val catalogId = catalog.id ?: return@forEach
                val genre = catalog.homeGenre() ?: if (catalog.requiresExtra("genre"))
                    catalog.browseGenres().firstOrNull() else null
                val shortcut = CatalogShortcutTarget(source.mainUrl, "collection", catalogId, genre)
                val snapshot = CatalogHomeSnapshot(shortcut, emptyList(), count,
                    catalog.supportsExtra("skip") && count > 0 && folders.isNotEmpty(), folders)
                val rowLayout = when {
                    isLayout(TV) -> R.layout.homepage_parent_tv
                    isLayout(EMULATOR) -> R.layout.homepage_parent_emulator
                    else -> R.layout.homepage_parent
                }
                val row = android.view.LayoutInflater.from(activity).inflate(rowLayout, this, false)
                val rowTitle = row.findViewById<TextView>(R.id.home_child_more_info)
                rowTitle?.apply {
                    text = catalog.name ?: catalogId
                    visibility = if (presentation.hideTitle) View.GONE else View.VISIBLE
                    id = View.generateViewId()
                    applyStremioCNativeFont(bold = true, header = true)
                    if (isLayout(TV)) {
                        isFocusable = true
                        foreground = themedPressedForeground(this)
                    }
                    setOnClickListener { openCatalog(shortcut, snapshot) }
                }
                row.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.apply {
                    nextFocusUpId = rowTitle?.takeIf { it.visibility == View.VISIBLE }?.id ?: View.NO_ID
                    layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                    adapter = FolderCardAdapter(folders) { folder, _ ->
                        val target = shortcut.copy(folderId = folder.id)
                        openCatalog(target, snapshot.copy(target = target))
                    }
                }
                placedRows.add(PlacedRow(row, anchorName))
            }
        }
        section = panel
        target.addView(panel, LinearLayout.LayoutParams(-1, -2))
        recycler?.let(::placeRows)
    }

    /** Reuse the existing home row attach/layout callbacks. Only move a collection
     * when its destination changes; the native adapter and its item positions stay intact. */
    private fun placeRows(master: RecyclerView) {
        val panel = section ?: return
        if (master.isComputingLayout) return
        if (placedRows.none { it.anchorName != null }) {
            placedRows.forEach { row ->
                if (row.view.parent == null) panel.addView(row.view, LinearLayout.LayoutParams(-1, -2))
            }
            panel.visibility = if (panel.childCount == 0) View.GONE else View.VISIBLE
            return
        }
        val adapter = master.adapter as? ParentItemAdapter
        val available = adapter?.immutableCurrentList?.mapTo(hashSetOf()) { it.list.name }.orEmpty()
        val visible = mutableMapOf<String, ViewGroup>()
        if (adapter != null) for (i in 0 until master.childCount) {
            val view = master.getChildAt(i)
            val holder = master.getChildViewHolder(view)
            val name = adapter.getItemOrNull(holder.bindingAdapterPosition - adapter.headers)?.list?.name ?: continue
            visible[name] = if (view is LinearLayout && view.orientation == LinearLayout.VERTICAL) view else panel
        }
        val lastPlaced = mutableMapOf<ViewGroup, View>()
        placedRows.forEach { row ->
            val target = if (row.anchorName == null || row.anchorName !in available) panel
                else visible[row.anchorName]
            val parent = row.view.parent as? ViewGroup
            if (parent !== target) {
                parent?.removeView(row.view)
                if (target != null) {
                    val previous = lastPlaced[target]
                    val index = if (previous != null) target.indexOfChild(previous) + 1
                        else if (target === panel) 0
                        else target.childCount - placedRows.count { it.view.parent === target }
                    target.addView(row.view, index.coerceIn(0, target.childCount), LinearLayout.LayoutParams(-1, -2))
                }
            }
            if (target != null) lastPlaced[target] = row.view
        }
        panel.visibility = if (panel.childCount == 0) View.GONE else View.VISIBLE
    }

    fun onNativeRowAttached(master: RecyclerView) {
        if (master !== recycler || placementPosted || placedRows.none { it.anchorName != null }) return
        placementPosted = true
        master.post {
            placementPosted = false
            if (master === recycler) placeRows(master)
        }
    }

    fun onNativeRowDetached(view: View) {
        val parent = view as? ViewGroup ?: return
        placedRows.forEach { row ->
            if (row.view.parent === parent) {
                // The native holder is leaving the layout; don't carry the anchored row into recycling.
                parent.removeViewInLayout(row.view)
                parent.forceLayout()
            }
        }
    }

    private fun detach() {
        placedRows.forEach { row ->
            row.view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.adapter = null
            (row.view.parent as? ViewGroup)?.removeView(row.view)
        }
        placedRows.clear()
        section?.let { panel -> (panel.parent as? ViewGroup)?.removeView(panel) }
        section = null
        header = null
    }

    fun close() {
        job?.cancel()
        scope?.cancel()
        job = null
        scope = null
        loaded = null
        provider = null
        recycler = null
        detach()
    }
}


internal fun collectionItemKey(item: SearchResponse): String {
    val segments = Uri.parse(item.url).pathSegments
    val meta = segments.indexOfLast { it == "meta" }
    return if (meta >= 0 && meta + 2 < segments.size)
        segments[meta + 1] + ":" + segments[meta + 2].removeSuffix(".json") else item.url
}

/** Per-folder data only. Navigation, loading UI and media clicks belong to CatalogBrowserDialog. */
internal class CollectionFolderPages(val summary: StremioCollectionFolder) {
    private data class SourcePage(
        val source: StremioCollectionSource, var catalog: StremioC.Catalog?,
        var offset: Int = 0, var more: Boolean = true, var mergedCount: Int = 0,
        val items: MutableList<SearchResponse> = mutableListOf(),
        val seen: MutableSet<String> = mutableSetOf(), val signatures: MutableSet<List<String>> = mutableSetOf(),
        val batchEnds: MutableList<Int> = mutableListOf(), var mergedBatches: Int = 0
    )
    var detail: StremioCollectionFolder? = null
        private set
    private var pages = emptyList<SourcePage>()
    private val merged = mutableListOf<SearchResponse>()
    private val mergedSeen = mutableSetOf<String>()
    private var alternatingTurn = 0
    var selectedSource = -1
    var started = false
        private set
    var error = false
    val titles get() = pages.map { it.source.title }
    fun items(source: Int = -1): List<SearchResponse> =
        if (source < 0) merged else pages.getOrNull(source)?.items.orEmpty()
    fun hasNext(source: Int = -1): Boolean = detail == null ||
        (if (source < 0) pages.any { it.more || detail?.merge == "alternating" && it.mergedBatches < it.batchEnds.size }
         else pages.getOrNull(source)?.more == true)

    suspend fun prepare(provider: StremioC, limit: Semaphore) {
        if (detail != null) return
        val (folder, catalogs) = withContext(Dispatchers.IO) {
            limit.withPermit {
                val detail = provider.browseCollectionDetail(summary.id)
                val folder = detail.copy(presentation = detail.presentation.copy(
                    backdrop = detail.presentation.backdrop ?: summary.presentation.backdrop))
                folder to if (folder.sources.isEmpty()) emptyList() else provider.browseCatalogs()
            }
        }
        pages = folder.sources.map { source ->
            val catalog = catalogs.firstOrNull { it.type == source.type && it.id == source.catalogId }
            val title = if (source.title == source.catalogId) catalog?.name?.takeIf { it.isNotBlank() } ?: source.title else source.title
            SourcePage(source.copy(title = title), catalog)
        }
        selectedSource = if (folder.showAll && pages.size > 1 || pages.isEmpty()) -1 else 0
        detail = folder
    }

    suspend fun next(provider: StremioC, limit: Semaphore, source: Int = -1) {
        error = false
        try {
            prepare(provider, limit)
            val folder = requireNotNull(detail)
            val alternating = folder.merge == "alternating"
            val turn = if (alternating && source < 0) pages.indices.map { (alternatingTurn + it) % pages.size }
                .firstOrNull { pages[it].more || pages[it].mergedBatches < pages[it].batchEnds.size } else null
            val eligible = if (source >= 0) listOfNotNull(pages.getOrNull(source)).filter { it.more }
                else if (alternating) listOfNotNull(turn?.let { pages[it] })
                    .filter { it.more && it.mergedBatches == it.batchEnds.size }
                else if (folder.merge == "sequential") listOfNotNull(pages.firstOrNull { it.more })
                else pages.filter { it.more }
            val failures = coroutineScope {
                eligible.map { page -> async {
                    try {
                        if (page.catalog == null) page.catalog = withContext(Dispatchers.IO) {
                            limit.withPermit { provider.browseCatalogs().firstOrNull {
                                it.type == page.source.type && it.id == page.source.catalogId
                            } }
                        }
                        val catalog = requireNotNull(page.catalog) { "Catalog unavailable" }
                        require(StremioC.isCatalogAddonEnabled(provider.mainUrl, provider.addonId)) { "Addon disabled" }
                        require(catalog.browseBlockReason() == null) { catalog.browseBlockReason().orEmpty() }
                        val genre = page.source.genre ?: catalog.homeGenre() ?: if (catalog.requiresExtra("genre"))
                            catalog.browseGenres().firstOrNull() else null
                        val (items, count) = withContext(Dispatchers.IO) {
                            limit.withPermit { provider.browsePage(catalog, genre, page.offset) }
                        }
                        val fresh = items.filter { page.seen.add(it.url) }
                        val repeated = !page.signatures.add(items.map { it.url })
                        page.items.addAll(fresh)
                        if (alternating && fresh.isNotEmpty()) page.batchEnds.add(page.items.size)
                        page.offset += count
                        page.more = catalog.supportsExtra("skip") && count > 0 && !repeated && fresh.isNotEmpty()
                        false
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { true }
                } }.awaitAll()
            }
            error = failures.any { it }
            if (alternating) {
                if (turn != null && !error) {
                    val page = pages[turn]
                    if (page.mergedBatches < page.batchEnds.size) {
                        val end = page.batchEnds[page.mergedBatches++]
                        appendMerged(page.items.subList(page.mergedCount, end), folder.shuffle)
                        page.mergedCount = end
                    }
                    alternatingTurn = (turn + 1) % pages.size
                }
            } else merge(folder)
            started = true
        } catch (e: CancellationException) {
            detail?.takeUnless { it.merge == "alternating" }?.let(::merge)
            throw e
        }
        catch (_: Exception) { error = true }
    }

    private fun merge(folder: StremioCollectionFolder) {
        val eligible = if (folder.merge == "sequential") {
            val stop = pages.indexOfFirst { it.more }
            if (stop < 0) pages else pages.take(stop + 1)
        } else pages
        val pending = eligible.map { it.items.drop(it.mergedCount) }
        val batch = if (folder.merge == "interleaved") buildList {
            for (i in 0 until (pending.maxOfOrNull { it.size } ?: 0))
                for (items in pending) items.getOrNull(i)?.let { add(it) }
        } else pending.flatten()
        eligible.forEach { it.mergedCount = it.items.size }
        appendMerged(batch, folder.shuffle)
    }

    private fun appendMerged(batch: List<SearchResponse>, shuffle: Boolean) {
        val fresh = batch.filter { mergedSeen.add(collectionItemKey(it)) }
        merged.addAll(if (shuffle) fresh.shuffled() else fresh)
    }
}
