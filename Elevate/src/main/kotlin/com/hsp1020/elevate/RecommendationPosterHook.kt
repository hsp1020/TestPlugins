package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import coil3.request.crossfade
import coil3.request.placeholder
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.getAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.getKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.readIdFromString
import com.lagradost.cloudstream3.SimklSyncServices
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONArray

/** Apply the existing Elevate poster configuration to native recommendation cards. */
internal class RecommendationPosterHook(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val screens = mutableMapOf<Fragment, Screen>()
    private val windows = IdentityHashMap<View, TaggedWindow>()
    private var focusTree: ViewTreeObserver? = null
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        main.post { discoverWindows() }
    }
    private var host: FragmentActivity? = null
    private var setting = LibraryPosterPrefs.get(application)
    private var revision = 0
    private val unavailable = mutableSetOf<String>()
    private val simklLimiter = Semaphore(2)
    private val detailLimiter = Semaphore(5)
    private data class DetailIds(val ids: PosterIds, val simkl: String?, val movie: Boolean)
    private val detailMappings = LinkedHashMap<String, Pair<Long, DetailIds?>>()
    private val simklMappings = LinkedHashMap<String, Pair<Long, PosterIds?>>()

    private val preferences = application.getSharedPreferences(LibraryPosterPrefs.FILE, Context.MODE_PRIVATE)

    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.post {
            val next = LibraryPosterPrefs.get(application)
            if (setting != next) {
                setting = next
                revision++
                unavailable.clear()
                screens.values.toList().forEach { it.refresh() }
                windows.values.toList().forEach { it.refresh() }
            }
        }
    }
    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
        ) {
            attach(f, v)
            v.post { if (f.view === v) attach(f, v) }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            screens.remove(f)?.close()
        }

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            screens[f]?.schedule() ?: f.view?.let { attach(f, it) }
        }
    }

    fun start() {
        preferences.registerOnSharedPreferenceChangeListener(changes)
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        preferences.unregisterOnSharedPreferenceChangeListener(changes)
        application.unregisterActivityLifecycleCallbacks(this)
        detach()
        scope.cancel()
        unavailable.clear()
        synchronized(simklMappings) { simklMappings.clear() }
        synchronized(detailMappings) { detailMappings.clear() }
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        detach()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        activity.window.decorView.viewTreeObserver.let { tree ->
            focusTree = tree
            if (tree.isAlive) tree.addOnWindowFocusChangeListener(focusListener)
        }
        main.post { discoverWindows() }
        fun scan(manager: FragmentManager) {
            for (fragment in manager.fragments) {
                fragment.view?.let { attach(fragment, it) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun detach() {
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree = null
        windows.values.toList().forEach { it.close() }
        windows.clear()
        screens.values.toList().forEach { it.close() }
        screens.clear()
        host = null
    }

    private fun attach(fragment: Fragment, root: View) {
        val list = root.findViewById<RecyclerView>(R.id.result_recommendations_list) ?: return
        if (screens[fragment]?.list === list) return
        screens.remove(fragment)?.close()
        screens[fragment] = Screen(list).also { it.open() }
    }

    /**
     * Observe ordinary TMDB-identified image views in dialogs. The images expose
     * standard TMDB media identities, not plugin objects, callbacks or settings.
     */
    private fun discoverWindows() {
        val activity = host ?: return
        val roots: List<View> = if (Build.VERSION.SDK_INT >= 29) {
            WindowInspector.getGlobalWindowViews()
        } else runCatching {
            val type = Class.forName("android.view.WindowManagerGlobal")
            val manager = type.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            type.getDeclaredField("mViews").apply { isAccessible = true }
                .get(manager) as List<View>
        }.getOrDefault(emptyList())
        val active = roots.filter { root ->
            root !== activity.window.decorView && root.isAttachedToWindow &&
                owner(root.context) === activity
        }.toSet()
        windows.keys.toList().filter { it !in active }.forEach {
            windows.remove(it)?.close()
        }
        active.forEach { window ->
            if (!windows.containsKey(window)) {
                windows[window] = TaggedWindow(window).also { it.open() }
            }
        }
    }

    private fun owner(context: Context): Activity? {
        var current = context
        repeat(12) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

    private inner class TaggedWindow(private val root: View) {
        private val applied = IdentityHashMap<ImageView, Pair<String, String?>>()
        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { scan() }
        private var tree: ViewTreeObserver? = null

        fun open() {
            root.viewTreeObserver.let {
                tree = it
                if (it.isAlive) it.addOnGlobalLayoutListener(layoutListener)
            }
            scan()
        }

        fun close() {
            tree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
            tree = null
            applied.forEach { (image, data) -> restore(image, data.second) }
            applied.clear()
        }

        fun refresh() {
            applied.forEach { (image, data) -> restore(image, data.second) }
            applied.clear()
            scan()
        }

        private fun restore(image: ImageView, original: String?) {
            if (!image.isAttachedToWindow) return
            if (original.isNullOrBlank()) image.loadImage(R.drawable.default_cover)
            else image.loadImage(original) { crossfade(false) }
        }

        private fun scan() {
            if (!setting.ready || !root.isAttachedToWindow) return
            val found = IdentityHashMap<ImageView, Boolean>()
            fun walk(view: View) {
                if (view is ImageView) {
                    val data = view.tag as? Pair<*, *>
                    val identity = data?.first as? String
                    val match = identity?.let { TMDB_ARTWORK.matchEntire(it) }
                    if (match != null) {
                        found[view] = true
                        val original = data?.second as? String
                        val value = identity!! to original
                        if (applied[view] != value) {
                            val movie = match.groupValues[1] == "movie"
                            val config = setting
                            val poster = config.image(PosterIds(tmdb = match.groupValues[2]), movie)
                            if (poster != null && poster !in unavailable) {
                                applied[view] = value
                                val image = view
                                val retained = image.drawable
                                image.loadImage(poster) {
                                    if (retained != null) placeholder(retained)
                                    crossfade(false)
                                    listener(onError = { _, _ ->
                                        if (applied[image] == value) {
                                            unavailable.add(poster)
                                            restore(image, original)
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) walk(view.getChildAt(index))
                }
            }
            walk(root)
            applied.keys.toList().filter { it !in found }.forEach { applied.remove(it) }
        }
    }

    private data class Cover(
        val key: String, val generation: Int, val original: String?,
        val headers: Map<String, String>?, var custom: String? = null,
        var failed: Boolean = false
    )

    private inner class Screen(val list: RecyclerView) {
        private val covers = IdentityHashMap<ImageView, Cover>()
        private val pending = IdentityHashMap<ImageView, Job>()
        private var adapter: RecyclerView.Adapter<*>? = null
        private var queued = false
        private var rebind = false

        private val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = schedule(true)
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = schedule(true)
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = schedule(true)
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = schedule(true)
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                schedule(true)
        }
        private val children = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                list.post { if (screens.values.any { it === this@Screen }) apply(view, false) }
            }
            override fun onChildViewDetachedFromWindow(view: View) {
                view.findViewById<ImageView>(R.id.imageView)?.let {
                    pending.remove(it)?.cancel()
                    covers.remove(it)
                }
            }
        }
        private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedule() }

        fun open() {
            list.addOnChildAttachStateChangeListener(children)
            list.addOnLayoutChangeListener(layout)
            syncAdapter()
            schedule()
        }

        fun close() {
            list.removeOnChildAttachStateChangeListener(children)
            list.removeOnLayoutChangeListener(layout)
            adapter?.unregisterAdapterDataObserver(observer)
            adapter = null
            pending.values.toList().forEach { it.cancel() }
            pending.clear()
            covers.forEach { (image, cover) -> restore(image, cover) }
            covers.clear()
        }

        private fun syncAdapter() {
            val next = list.adapter
            if (adapter === next) return
            adapter?.unregisterAdapterDataObserver(observer)
            adapter = next
            next?.registerAdapterDataObserver(observer)
            rebind = true
        }

        fun schedule(force: Boolean = false) {
            rebind = rebind || force
            if (queued) return
            queued = true
            list.post {
                queued = false
                if (screens.values.none { it === this@Screen }) return@post
                syncAdapter()
                val changed = rebind
                rebind = false
                for (index in 0 until list.childCount) apply(list.getChildAt(index), changed)
            }
        }

        fun refresh() {
            pending.values.toList().forEach { it.cancel() }
            pending.clear()
            covers.forEach { (image, cover) -> restore(image, cover) }
            covers.clear()
            schedule(true)
        }

        private fun restore(image: ImageView, cover: Cover) {
            if (!image.isAttachedToWindow) return
            if (cover.original.isNullOrBlank()) {
                image.loadImage(R.drawable.default_cover)
            } else {
                image.loadImage(cover.original, cover.headers) { crossfade(false) }
            }
        }

        private fun loadCustom(image: ImageView, cover: Cover, poster: String) {
            val previous = image.drawable
            image.loadImage(poster) {
                if (previous != null) placeholder(previous)
                crossfade(false)
                listener(
                    onError = { _, _ ->
                        if (covers[image] === cover) {
                            unavailable.add(poster)
                            cover.custom = null
                            cover.failed = true
                            restore(image, cover)
                        }
                    }
                )
            }
        }

        private fun apply(view: View, forced: Boolean) {
            if (view.parent !== list || !setting.ready) return
            val native = list.adapter as? SearchAdapter ?: return
            val position = list.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            val card = native.getItemOrNull(position) as? SearchResponse ?: return
            val image = view.findViewById<ImageView>(R.id.imageView) ?: return
            if (view.findViewById<TextView>(R.id.imageText)?.text?.toString() != card.name) return
            val key = card.apiName + "|" + card.url + "|" + card.name + "|" +
                card.type + "|" + card.posterUrl
            val old = covers[image]
            if (old?.key == key && old.generation == revision) {
                if (forced && old.custom != null) loadCustom(image, old, old.custom!!)
                return
            }
            pending.remove(image)?.cancel()
            val current = Cover(key, revision, card.posterUrl, card.posterHeaders)
            covers[image] = current
            val (ids, simkl) = directIds(card)
            val config = setting
            // Some providers label every search card as Movie but keep the real
            // media type in the card URL (for example TMDB recommendations).
            val mediaType = runCatching { JSONObject(card.url).optString("type") }
                .getOrNull()?.lowercase()
            val movie = when (mediaType) {
                "movie" -> true
                "tv", "series" -> false
                else -> card.type == TvType.Movie || card.type == TvType.AnimeMovie
            }
            pending[image] = scope.launch {
                try {
                    // Other providers often expose their IDs only in LoadResponse.syncData,
                    // never in the recommendation SearchResponse URL.
                    val detail = if (!ids.available && simkl == null) detailIds(card) else null
                    val resolvedIds = detail?.ids ?: ids
                    val resolvedSimkl = detail?.simkl ?: simkl
                    val resolvedMovie = detail?.movie ?: movie
                    val mapped = if (resolvedIds.imdb != null || resolvedIds.tmdb != null ||
                        config.service == PosterService.XRDB && resolvedIds.available) resolvedIds
                    else withContext(Dispatchers.IO) {
                        animeIds(resolvedIds) ?: resolvedSimkl?.let { simklIds(it, resolvedMovie) }
                    }
                    val poster = mapped?.let { config.image(it, resolvedMovie) }
                    if (covers[image] !== current || revision != current.generation ||
                        !image.isAttachedToWindow || view.parent !== list) return@launch
                    if (poster == null || poster in unavailable) {
                        current.failed = true
                        return@launch
                    }
                    current.custom = poster
                    loadCustom(image, current, poster)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (covers[image] === current) current.failed = true
                } finally {
                    if (covers[image] === current) pending.remove(image)
                }
            }
        }
    }

    /** Resolve exactly the same load-time IDs that History Sync and MDB Ratings use. */
    private suspend fun detailIds(card: SearchResponse): DetailIds? {
        val key = card.apiName + "|" + card.url + "|" + card.type
        val now = System.currentTimeMillis()
        synchronized(detailMappings) {
            detailMappings[key]?.takeIf {
                now - it.first < if (it.second == null) 120_000L else 1_800_000L
            }?.let { return it.second }
        }
        val found = try {
            withContext(Dispatchers.IO) {
                detailLimiter.withPermit {
                    // Limit the metadata fetch itself, not time spent waiting for a slot.
                    withTimeoutOrNull(8_000L) {
                        val provider = APIHolder.getApiFromNameNull(card.apiName)
                            ?: APIHolder.getApiFromUrlNull(card.url)
                            ?: return@withTimeoutOrNull null
                        val response = provider.load(card.url) ?: return@withTimeoutOrNull null
                        val saved = readIdFromString(response.syncData["simkl"])
                        fun valid(value: String?): String? = value?.substringAfterLast(':')
                            ?.toLongOrNull()?.takeIf { it > 0 }?.toString()
                        fun firstId(vararg candidates: String?): String? =
                            candidates.firstNotNullOfOrNull { valid(it) }
                        val ids = PosterIds(
                            imdb = listOf(response.getImdbId(), response.syncData["imdb"],
                                saved[SimklSyncServices.Imdb], IMDB.find(response.url)?.value)
                                .firstNotNullOfOrNull { it?.takeIf(IMDB_EXACT::matches) },
                            tmdb = firstId(response.getTMDbId(), response.syncData["tmdb"],
                                saved[SimklSyncServices.Tmdb],
                                TMDB.find(response.url)?.groupValues?.get(1)),
                            mal = firstId(response.getMalId(), response.syncData["mal"],
                                saved[SimklSyncServices.Mal],
                                MAL.find(response.url)?.groupValues?.get(1)),
                            anilist = firstId(response.getAniListId(),
                                response.syncData["anilist"], saved[SimklSyncServices.AniList],
                                ANILIST.find(response.url)?.groupValues?.get(1)),
                            kitsu = firstId(response.getKitsuId(), response.syncData["kitsu"],
                                KITSU.find(response.url)?.groupValues?.get(1))
                        )
                        val simkl = firstId(saved[SimklSyncServices.Simkl],
                            response.syncData["simkl"],
                            SIMKL.find(response.url)?.groupValues?.get(1))
                        if (!ids.available && simkl == null) return@withTimeoutOrNull null
                        DetailIds(ids, simkl, when (response.type) {
                            TvType.Others -> card.type in setOf(TvType.Movie, TvType.AnimeMovie)
                            else -> response.type in setOf(TvType.Movie, TvType.AnimeMovie)
                        })
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: NotImplementedError) {
            null
        } catch (_: Exception) {
            null
        }
        synchronized(detailMappings) {
            detailMappings[key] = System.currentTimeMillis() to found
            if (detailMappings.size > 160) detailMappings.remove(detailMappings.keys.first())
        }
        return found
    }

    private suspend fun animeIds(ids: PosterIds): PosterIds? {
        for ((source, id) in listOfNotNull(
            ids.mal?.let { "mal" to it },
            ids.anilist?.let { "anilist" to it },
            ids.kitsu?.let { "kitsu" to it }
        )) {
            val mapped = ElevateAnimeIdResolver.resolve(source, id) ?: continue
            if (mapped.imdb != null || mapped.tmdb != null)
                return PosterIds(mapped.imdb, mapped.tmdb, ids.mal, ids.anilist, ids.kitsu)
        }
        return null
    }

    /** Resolve Simkl-only recommendation IDs without requiring History Sync or MDB Ratings. */
    private suspend fun simklIds(id: String, movie: Boolean): PosterIds? {
        val key = (if (movie) "movie:" else "series:") + id
        val now = System.currentTimeMillis()
        synchronized(simklMappings) {
            simklMappings[key]?.takeIf {
                now - it.first < if (it.second == null) 300_000L else 3_600_000L
            }?.let { return it.second }
        }
        val found = try {
            withTimeoutOrNull(30_000L) {
                simklLimiter.withPermit {
                    val auth = AccountManager.syncApis.firstOrNull { it.idPrefix == "simkl" }
                        ?.authData() ?: return@withPermit null
                    val headers = SimklApi.getHeaders(auth.token)
                    val clientId = headers["simkl-api-key"]?.takeIf(String::isNotBlank)
                        ?: return@withPermit null
                    val params = mapOf("client_id" to clientId)
                    val search = JSONArray(app.get("https://api.simkl.com/search/id",
                        params = params + ("simkl" to id),
                        headers = headers, timeout = 12L).text)
                    val match = (0 until search.length()).mapNotNull { search.optJSONObject(it) }
                        .firstOrNull {
                            it.optJSONObject("ids")?.optString("simkl") == id &&
                                when (it.optString("type")) {
                                    "movie" -> movie
                                    "tv" -> !movie
                                    "anime" -> true
                                    else -> false
                                }
                        } ?: return@withPermit null
                    val segment = when (match.optString("type")) {
                        "movie" -> "movies"
                        "tv" -> "tv"
                        "anime" -> "anime"
                        else -> return@withPermit null
                    }
                    val detail = JSONObject(app.get("https://api.simkl.com/" +
                        segment + "/" + id, params = params, headers = headers,
                        timeout = 12L).text)
                    val external = detail.optJSONObject("ids") ?: return@withPermit null
                    val correctType = when (segment) {
                        "movies" -> true
                        "tv" -> false
                        else -> detail.optString("anime_type").equals("movie", true)
                    }
                    if (external.optString("simkl") != id || correctType != movie)
                        return@withPermit null
                    val imdb = external.optString("imdb").takeIf(IMDB_EXACT::matches)
                    val tmdb = external.optString("tmdb").toLongOrNull()
                        ?.takeIf { it > 0L }?.toString()
                    PosterIds(imdb, tmdb).takeIf { it.available }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        synchronized(simklMappings) {
            simklMappings[key] = System.currentTimeMillis() to found
            if (simklMappings.size > 64) simklMappings.remove(simklMappings.keys.first())
        }
        return found
    }

    private fun directIds(card: SearchResponse): Pair<PosterIds, String?> {
        var imdb: String? = null
        var tmdb: String? = null
        var mal: String? = null
        var anilist: String? = null
        var kitsu: String? = null
        var simkl: String? = null
        fun positive(raw: String?): String? = raw?.removePrefix("tmdb:")
            ?.toLongOrNull()?.takeIf { it > 0 }?.toString()
        fun read(text: String?) {
            if (text.isNullOrBlank()) return
            val value = Uri.decode(text)
            if (imdb == null) imdb = IMDB.find(value)?.value
            if (tmdb == null) tmdb = TMDB.find(value)?.groupValues?.get(1)
            if (mal == null) mal = MAL.find(value)?.groupValues?.get(1)
            if (anilist == null) anilist = ANILIST.find(value)?.groupValues?.get(1)
            if (kitsu == null) kitsu = KITSU.find(value)?.groupValues?.get(1)
            if (simkl == null) simkl = SIMKL.find(value)?.groupValues?.get(1)
            val json = if (value.trimStart().startsWith("{"))
                runCatching { JSONObject(value) }.getOrNull() else null
            if (json != null) {
                val nested = json.optJSONObject("ids")
                fun field(vararg keys: String): String? =
                    keys.firstNotNullOfOrNull { key ->
                        listOfNotNull(json.optString(key).takeIf(String::isNotBlank),
                            nested?.optString(key)?.takeIf(String::isNotBlank)).firstOrNull()
                    }
                if (imdb == null) imdb = field("imdb", "imdbId", "imdb_id")
                    ?.takeIf(IMDB_EXACT::matches)
                if (tmdb == null) tmdb = positive(field("tmdb", "tmdbId", "tmdb_id",
                    "_tmdbId", "moviedb_id"))
                if (mal == null) mal = positive(field("mal", "malId", "mal_id"))
                if (anilist == null) anilist = positive(field("anilist", "aniListId", "anilistId"))
                if (kitsu == null) kitsu = positive(field("kitsu", "kitsuId", "kitsu_id"))
                if (simkl == null) simkl = positive(field("simkl", "simklId"))
            }
            val uri = runCatching { Uri.parse(value) }.getOrNull()
            if (uri?.scheme in listOf("http", "https")) {
                fun query(vararg keys: String): String? =
                    keys.firstNotNullOfOrNull { runCatching { uri?.getQueryParameter(it) }.getOrNull() }
                if (imdb == null) imdb = query("imdb", "imdb_id", "imdbId")
                    ?.takeIf(IMDB_EXACT::matches)
                if (tmdb == null) tmdb = positive(query("tmdb", "tmdb_id", "tmdbId"))
                if (mal == null) mal = positive(query("mal", "malId"))
                if (anilist == null) anilist = positive(query("anilist", "anilistId"))
                if (kitsu == null) kitsu = positive(query("kitsu", "kitsuId"))
                if (simkl == null) simkl = positive(query("simkl", "simklId"))
                if (simkl == null && uri?.host?.removePrefix("www.") == "simkl.com") {
                    val parts = uri?.pathSegments.orEmpty()
                    if (parts.size >= 2 && parts[0] in listOf("movies", "tv", "anime"))
                        simkl = positive(parts[1])
                }
                if (tmdb == null && uri?.host?.removePrefix("www.") == "themoviedb.org") {
                    val parts = uri?.pathSegments.orEmpty()
                    if (parts.size >= 2 && parts[0] in listOf("movie", "tv"))
                        tmdb = positive(parts[1])
                }
            }
        }
        read(card.url)
        read(card.posterUrl)
        // A numeric JSON "id" is only a TMDB ID when the provider itself
        // declares a TMDB origin. Never guess numeric IDs on scraper sites.
        if (tmdb == null) {
            val json = runCatching { JSONObject(card.url) }.getOrNull()
            val candidate = if (json?.optString("type") in setOf("movie", "tv", "series"))
                positive(json?.optString("id")) else null
            if (candidate != null) {
                val source = APIHolder.getApiFromNameNull(card.apiName)?.mainUrl.orEmpty()
                val host = runCatching { Uri.parse(source).host?.removePrefix("www.") }.getOrNull()
                if (host == "themoviedb.org") tmdb = candidate
            }
        }
        return PosterIds(imdb, tmdb, mal, anilist, kitsu) to simkl
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) {
            use(activity)
            main.post { discoverWindows() }
        }
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) detach()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private companion object {
        val IMDB = Regex("(?<![A-Za-z0-9])tt[0-9]{5,10}(?![0-9])")
        val IMDB_EXACT = Regex("tt[0-9]{5,10}")
        val TMDB = Regex("tmdb:(?:(?:movie|series|tv):)?([0-9]+)")
        val MAL = Regex("mal:([0-9]+)")
        val ANILIST = Regex("anilist:([0-9]+)")
        val KITSU = Regex("kitsu:([0-9]+)")
        val SIMKL = Regex("simkl:([0-9]+)")
        val TMDB_ARTWORK = Regex("tmdb:(movie|series):([0-9]+)")
    }
}
