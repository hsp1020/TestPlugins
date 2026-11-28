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

/** Folder cards use the native card layouts, but never enter the media search/load pipeline. */
internal class FolderCardAdapter(
    private val folders: List<StremioCollectionFolder>,
    private val grid: AutofitRecyclerView? = null,
    private val select: (StremioCollectionFolder, View) -> Unit
) : RecyclerView.Adapter<FolderCardAdapter.Holder>() {
    class Holder(val root: View) : RecyclerView.ViewHolder(root) {
        val card: View = root.findViewById(R.id.background_card)
        val image: ImageView = root.findViewById(R.id.imageView)
        val label: TextView = root.findViewById(R.id.imageText)
        var boundFolder: StremioCollectionFolder? = null
    }
    override fun getItemCount() = folders.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val layout = if (grid == null) {
            if (ctx.isBottomLayout()) R.layout.home_result_grid_expanded else R.layout.home_result_grid
        } else {
            if (ctx.isBottomLayout()) R.layout.search_result_grid_expanded else R.layout.search_result_grid
        }
        return Holder(android.view.LayoutInflater.from(ctx).inflate(layout, parent, false)).apply {
            root.isFocusable = true
            root.isClickable = true
            if (card !== root) card.isFocusable = false
            label.applyStremioCNativeFont()
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
            holder.card.layoutParams = holder.card.layoutParams.apply {
                this.width = width
                this.height = height
            }
            if (holder.label.parent !== holder.card && holder.card !== holder.root) {
                holder.label.layoutParams = holder.label.layoutParams.apply { this.width = width }
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
        holder.image.dispose()
        holder.image.setImageDrawable(null)
        val url = folder.cover
        if (url == null) holder.image.loadImage(R.drawable.default_cover)
        else holder.image.loadImage(url) {
            listener(onSuccess = success@{ request, result ->
                if (holder.boundFolder !== folder || request.data.toString() != url) return@success
                val w = result.image.width
                val h = result.image.height
                if (w > 0 && h > 0) size(cardWidth(w > h), stremioPosterHeight(cardWidth(w > h), w, h))
            })
        }
        holder.root.setOnClickListener { select(folder, holder.root) }
        if (holder.card !== holder.root) holder.card.setOnClickListener { select(folder, holder.root) }
    }
    override fun onViewRecycled(holder: Holder) {
        holder.boundFolder = null
        holder.image.dispose()
        holder.image.setImageDrawable(null)
        holder.root.setOnClickListener(null)
        if (holder.card !== holder.root) holder.card.setOnClickListener(null)
    }
}

/** Inserts real folder rows into the existing native home header; never replaces its adapter. */
internal class StremioCollectionHome(
    private val prefs: SharedPreferences,
    private val openCatalog: (CatalogShortcutTarget, CatalogHomeSnapshot?) -> Unit
) {
    private var header: LinearLayout? = null
    private var section: LinearLayout? = null
    private var provider: StremioC? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var loaded: List<Pair<StremioC.Catalog, List<StremioCollectionFolder>>>? = null

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
                val catalogId = catalog.id ?: return@forEach
                val genre = catalog.homeGenre() ?: if (catalog.requiresExtra("genre"))
                    catalog.browseGenres().firstOrNull() else null
                val shortcut = CatalogShortcutTarget(source.mainUrl, "collection", catalogId, genre)
                val rowLayout = when {
                    isLayout(TV) -> R.layout.homepage_parent_tv
                    isLayout(EMULATOR) -> R.layout.homepage_parent_emulator
                    else -> R.layout.homepage_parent
                }
                val row = android.view.LayoutInflater.from(activity).inflate(rowLayout, this, false)
                row.findViewById<TextView>(R.id.home_child_more_info)?.apply {
                    text = catalog.name ?: catalogId
                    applyStremioCNativeFont(bold = true, header = true)
                    if (isLayout(TV)) {
                        isFocusable = true
                        foreground = themedPressedForeground(this)
                    }
                    setOnClickListener { openCatalog(shortcut, null) }
                }
                row.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.apply {
                    layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                    adapter = FolderCardAdapter(folders) { folder, _ ->
                        openCatalog(shortcut.copy(folderId = folder.id), null)
                    }
                }
                addView(row)
            }
        }
        section = panel
        target.addView(panel, LinearLayout.LayoutParams(-1, -2))
    }

    private fun detach() {
        section?.let { panel ->
            for (i in 0 until panel.childCount)
                panel.getChildAt(i).findViewById<RecyclerView>(R.id.home_child_recyclerview)?.adapter = null
            (panel.parent as? ViewGroup)?.removeView(panel)
        }
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
        val seen: MutableSet<String> = mutableSetOf(), val signatures: MutableSet<List<String>> = mutableSetOf()
    )
    var detail: StremioCollectionFolder? = null
        private set
    private var pages = emptyList<SourcePage>()
    private val merged = mutableListOf<SearchResponse>()
    private val mergedSeen = mutableSetOf<String>()
    var selectedSource = -1
    var started = false
        private set
    var error = false
    val titles get() = pages.map { it.source.title }
    fun items(source: Int = -1): List<SearchResponse> =
        if (source < 0) merged else pages.getOrNull(source)?.items.orEmpty()
    fun hasNext(source: Int = -1): Boolean = detail == null ||
        (if (source < 0) pages.any { it.more } else pages.getOrNull(source)?.more == true)

    suspend fun prepare(provider: StremioC, limit: Semaphore) {
        if (detail != null) return
        val (folder, catalogs) = withContext(Dispatchers.IO) {
            limit.withPermit {
                val folder = provider.browseCollectionDetail(summary.id)
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
            val eligible = if (source >= 0) listOfNotNull(pages.getOrNull(source)).filter { it.more }
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
                        page.offset += count
                        page.more = catalog.supportsExtra("skip") && count > 0 && !repeated && fresh.isNotEmpty()
                        false
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { true }
                } }.awaitAll()
            }
            merge(folder)
            started = true
            error = failures.any { it }
        } catch (e: CancellationException) {
            detail?.let(::merge)
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
        val fresh = batch.filter { mergedSeen.add(collectionItemKey(it)) }
        merged.addAll(if (folder.shuffle) fresh.shuffled() else fresh)
    }
}
