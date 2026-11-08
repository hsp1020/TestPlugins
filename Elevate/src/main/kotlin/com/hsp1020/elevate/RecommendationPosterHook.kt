package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.json.JSONArray

/** Apply the existing Elevate poster configuration to native recommendation cards. */
internal class RecommendationPosterHook(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val screens = mutableMapOf<Fragment, Screen>()
    private var host: FragmentActivity? = null
    private var setting = LibraryPosterPrefs.get(application)
    private var revision = 0
    private val unavailable = mutableSetOf<String>()
    private val detailLimiter = Semaphore(10)
    private data class DetailIds(val ids: PosterIds, val simkl: String?, val movie: Boolean)
    private data class DetailLookup(val ids: DetailIds?)
    private val detailMappings = LinkedHashMap<String, Pair<Long, DetailLookup>>()
    private val detailFlights = mutableMapOf<String, Deferred<DetailLookup>>()

    private val preferences = application.getSharedPreferences(LibraryPosterPrefs.FILE, Context.MODE_PRIVATE)

    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.post {
            val next = LibraryPosterPrefs.get(application)
            if (setting != next) {
                setting = next
                revision++
                unavailable.clear()
                screens.values.toList().forEach { it.refresh() }
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
        synchronized(detailMappings) {
            detailMappings.clear()
            detailFlights.clear()
        }
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        detach()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
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

    private data class Cover(
        val key: String, val generation: Int, val original: String?,
        val headers: Map<String, String>?, val title: String,
        var custom: String? = null, var failed: Boolean = false,
        var fallback: PosterIds? = null, var fallbackMovie: Boolean = false,
        var fallbackAttempted: Boolean = false,
        var alternativePosters: MutableList<String> = mutableListOf()
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
                    PosterWipe.cancel(it)
                    covers.remove(it)
                }
            }
        }
        private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedule() }
        private val scrolling = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = schedule()
        }
        fun open() {
            list.addOnChildAttachStateChangeListener(children)
            list.addOnLayoutChangeListener(layout)
            list.addOnScrollListener(scrolling)
            syncAdapter()
            schedule()
        }

        fun close() {
            list.removeOnChildAttachStateChangeListener(children)
            list.removeOnLayoutChangeListener(layout)
            list.removeOnScrollListener(scrolling)
            adapter?.unregisterAdapterDataObserver(observer)
            adapter = null
            pending.values.toList().forEach { it.cancel() }
            pending.clear()
            covers.forEach { (image, cover) ->
                PosterWipe.cancel(image)
                restore(image, cover)
            }
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
            list.postOnAnimation {
                queued = false
                if (screens.values.none { it === this@Screen }) return@postOnAnimation
                syncAdapter()
                val changed = rebind
                rebind = false
                for (index in 0 until list.childCount) apply(list.getChildAt(index), changed)
            }
        }

        fun refresh() {
            pending.values.toList().forEach { it.cancel() }
            pending.clear()
            covers.forEach { (image, cover) ->
                PosterWipe.cancel(image)
                restore(image, cover)
            }
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

        private suspend fun loadCustom(image: ImageView, cover: Cover, poster: String) {
            if (covers[image] !== cover || revision != cover.generation ||
                !image.isAttachedToWindow) return
            val traceXrdb = setting.service == PosterService.XRDB
            if (traceXrdb) ElevatePosterTrace.log("recommendation", "REQUEST", cover.title,
                "artwork=${ElevatePosterTrace.artwork(poster)}" +
                " url=${ElevatePosterTrace.url(poster)}")
            val result = PosterWipe.load(image, poster) {
                covers[image] === cover && revision == cover.generation
            }
            if (traceXrdb) ElevatePosterTrace.log("recommendation", "RESULT", cover.title,
                "artwork=${ElevatePosterTrace.artwork(poster)}" +
                " success=${result.success} error=${result.error}")
            if (covers[image] !== cover || revision != cover.generation ||
                result.error == "stale") return
            if (result.success) {
                return
            }
            unavailable.add(poster)
            while (cover.alternativePosters.isNotEmpty()) {
                val alternate = cover.alternativePosters.removeAt(0)
                if (alternate == poster || alternate in unavailable) continue
                cover.custom = alternate
                loadCustom(image, cover, alternate)
                return
            }
            val fallback = cover.fallback?.takeUnless { cover.fallbackAttempted }
            if (fallback != null) {
                cover.fallbackAttempted = true
                val mapped = fallback.takeIf { it.imdb != null || it.tmdb != null }
                    ?: withContext(Dispatchers.IO) { animeIds(fallback) }
                if (covers[image] !== cover || revision != cover.generation ||
                    !image.isAttachedToWindow) return
                val config = setting
                val selected = mapped?.preferImdb(cover.fallbackMovie)
                val version = if (config.service == PosterService.XRDB)
                    ElevateXrdbVersion.resolve(config) else null
                val options = listOfNotNull(
                    selected?.let { config.image(it, cover.fallbackMovie, version) },
                    selected?.takeIf { it.imdb != null && it.tmdb != null }?.let {
                        config.image(it.copy(imdb = null), cover.fallbackMovie, version)
                    }
                ).distinct().filter { it != poster && it !in unavailable }
                if (options.isNotEmpty()) {
                    cover.alternativePosters.addAll(options.drop(1))
                    cover.custom = options.first()
                    loadCustom(image, cover, options.first())
                    return
                }
            }
            if (setting.service == PosterService.XRDB)
                ElevatePosterTrace.log("recommendation", "NATIVE_FALLBACK", cover.title,
                    "allCustomCandidatesFailed=true")
            cover.custom = null
            cover.failed = true
            // Native artwork was never replaced on a failed request.
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
                if (forced && old.custom != null && pending[image]?.isActive != true) {
                    pending[image] = scope.launch { loadCustom(image, old, old.custom!!) }
                }
                return
            }
            pending.remove(image)?.cancel()
            PosterWipe.cancel(image)
            PosterWipe.ensurePlaceholder(image)
            val current = Cover(key, revision, card.posterUrl, card.posterHeaders, card.name)
            covers[image] = current
            val (ids, simkl) = directIds(card)
            val config = setting
            val sourceAnimeId = if (config.service == PosterService.XRDB)
                xrdbOriginalAnimeId(card.url) else null
            if (config.service == PosterService.XRDB)
                ElevatePosterTrace.log("recommendation", "SOURCE", card.name,
                    "provider=${card.apiName} type=${card.type} simkl=$simkl" +
                    " sourceAnime=$sourceAnimeId ids=${ElevatePosterTrace.ids(ids)}" +
                    " sourceUrl=${ElevatePosterTrace.url(card.url)}")
            // Some providers label every search card as Movie but keep the real
            // media type in the card URL (for example TMDB recommendations).
            val mediaType = runCatching { JSONObject(card.url).optString("type") }
                .getOrNull()?.lowercase()
            val simklType = runCatching {
                Uri.parse(card.url).takeIf { it.host?.removePrefix("www.") == "simkl.com" }
                    ?.pathSegments?.firstOrNull()
            }.getOrNull()
            val movie = when (mediaType ?: simklType) {
                "movie", "movies" -> true
                "tv", "series", "show", "shows", "anime" -> false
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
                    val initial = DetailIds(resolvedIds, resolvedSimkl, resolvedMovie)
                    val mapped = when {
                        resolvedIds.imdb != null || resolvedIds.tmdb != null ||
                            config.service == PosterService.XRDB && resolvedIds.available -> initial
                        resolvedSimkl != null -> {
                            ElevateIdResolver.simkl(resolvedSimkl, resolvedMovie, simklType)
                                ?.let { DetailIds(PosterIds(it.imdb, it.tmdb, it.mal,
                                    it.anilist, it.kitsu), null, it.movie) } ?: initial
                        }
                        else -> initial
                    }
                    val candidate = mapped.ids
                    val hasAnimeId = candidate.mal != null || candidate.anilist != null ||
                        candidate.kitsu != null
                    val animeIdentity = hasAnimeId && (simklType == "anime" ||
                        card.type in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA) ||
                        (candidate.imdb == null && candidate.tmdb == null))
                    // One existing AIOStreams mapping for the season, shared by all
                    // poster services that require an IMDb/TMDB artwork identifier.
                    val season = if (animeIdentity && sourceAnimeId == null) {
                        withContext(Dispatchers.IO) { animeIds(candidate) }
                    } else null
                    // Never discard a verified IMDb/TMDB ID when mapping fails.
                    val posterIds = season ?: candidate
                    val selected = if (sourceAnimeId != null) posterIds
                        else posterIds.preferImdb(mapped.movie)
                    val version = if (config.service == PosterService.XRDB)
                        ElevateXrdbVersion.resolve(config) else null
                    if (config.service == PosterService.XRDB)
                        ElevatePosterTrace.log("recommendation", "MAPPING", card.name,
                            "candidate=${ElevatePosterTrace.ids(candidate)}" +
                            " season=${ElevatePosterTrace.ids(season)}" +
                            " selected=${ElevatePosterTrace.ids(selected)}" +
                            " animeIdentity=$animeIdentity movie=${mapped.movie}" +
                            " sourceAnime=$sourceAnimeId version=$version")
                    val primary = config.image(selected, mapped.movie, version, sourceAnimeId)
                    val alternatives = listOfNotNull(
                        if (sourceAnimeId != null && selected.imdb != null)
                            config.image(selected, mapped.movie, version) else null,
                        if (selected.imdb != null && selected.tmdb != null)
                            config.image(selected.copy(imdb = null), mapped.movie, version)
                        else null
                    )
                    val posters = listOfNotNull(primary).plus(alternatives).distinct()
                        .filter { it !in unavailable }
                    val poster = posters.firstOrNull()
                    if (config.service == PosterService.XRDB)
                        ElevatePosterTrace.log("recommendation", "CANDIDATES", card.name,
                            "urls=${posters.joinToString(" -> ") { ElevatePosterTrace.url(it) }}" +
                            " artwork=${posters.map(ElevatePosterTrace::artwork)}")
                    current.alternativePosters = posters.drop(1).toMutableList()
                    if (sourceAnimeId != null || animeIdentity &&
                        (config.service == PosterService.XRDB ||
                            season != null && (candidate.imdb != null || candidate.tmdb != null))) {
                        // XRDB anime IDs fall back through AIOStreams; other poster
                        // services can retry the already-known IMDb/TMDB identifier.
                        current.fallback = candidate
                        current.fallbackMovie = mapped.movie
                    }
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
                } catch (error: Exception) {
                    if (covers[image] === current) {
                        current.failed = true
                    }
                } finally {
                    if (covers[image] === current) pending.remove(image)
                }
            }
        }
    }

    /** Lightweight, exact-title IMDb lookup for web-only 4K HDHUB cards. */
    private suspend fun fourKHdHubIds(card: SearchResponse): DetailIds? {
        val title = card.name.trim().replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*$"), "")
        if (title.isBlank()) return null
        val expectedYear = Regex("\\((\\d{4})\\)\\s*$")
            .find(card.name)?.groupValues?.get(1)
        fun normalized(text: String) = text.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val normalizedTitle = normalized(title)
        val matches = coroutineScope {
            listOf("movie", "series").map { kind ->
                async(Dispatchers.IO) {
                    try {
                        val url = "https://v3-cinemeta.strem.io/catalog/" + kind +
                            "/top/search=" + Uri.encode(title) + ".json"
                        val results = JSONObject(app.get(url, timeout = 3L).text)
                            .optJSONArray("metas") ?: return@async emptyList<DetailIds>()
                        buildList {
                            for (index in 0 until results.length()) {
                                val meta = results.optJSONObject(index) ?: continue
                                val imdb = meta.optString("id").takeIf(IMDB_EXACT::matches)
                                    ?: continue
                                if (normalized(meta.optString("name")) != normalizedTitle) continue
                                val year = meta.optString("releaseInfo")
                                    .take(4).takeIf { it.matches(Regex("\\d{4}")) }
                                    ?: meta.optString("year")
                                if (expectedYear != null && expectedYear != year) continue
                                add(DetailIds(PosterIds(imdb = imdb), null, kind == "movie"))
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }.distinctBy { it.ids.imdb to it.movie }
        // Reject homonymous or ambiguous results rather than applying the wrong poster.
        return matches.singleOrNull()
    }

    /** Prefer IDs already on the card; only unresolved cards need provider.load(). */
    private suspend fun detailIds(card: SearchResponse): DetailIds? {
        val key = card.apiName + "|" + card.url + "|" + card.type
        val now = System.currentTimeMillis()
        val cached = synchronized(detailMappings) {
            detailMappings[key]?.takeIf {
                now - it.first < if (it.second.ids == null) 120_000L else 1_800_000L
            }
        }
        if (cached != null) {
            return cached.second.ids
        }
        val flight = synchronized(detailMappings) {
            detailMappings[key]?.takeIf {
                System.currentTimeMillis() - it.first < if (it.second.ids == null) 120_000L else 1_800_000L
            }?.let {
                return it.second.ids
            }
            detailFlights[key] ?: run {
                val task = scope.async(Dispatchers.IO) {
                    val outcome = detailLimiter.withPermit {
                        val ids = try {
                            withTimeout(8_000L) {
                                if (card.apiName == "4K HDHUB") {
                                    fourKHdHubIds(card)?.let {

                                        return@withTimeout it
                                    }
                                }
                                val provider = APIHolder.getApiFromNameNull(card.apiName)
                                    ?: APIHolder.getApiFromUrlNull(card.url)
                                    ?: run {

                                        return@withTimeout null
                                    }
                                val response = provider.load(card.url) ?: run {

                                    return@withTimeout null
                                }
                                val saved = readIdFromString(response.syncData["simkl"])
                                fun valid(value: String?): String? = value?.substringAfterLast(':')
                                    ?.toLongOrNull()?.takeIf { it > 0 }?.toString()
                                fun firstId(vararg candidates: String?): String? =
                                    candidates.firstNotNullOfOrNull { valid(it) }
                                val result = PosterIds(
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
                                if (!result.available && simkl == null) null else {

                                    DetailIds(result, simkl, when (response.type) {
                                        TvType.Others -> card.type in setOf(TvType.Movie, TvType.AnimeMovie)
                                        else -> response.type in setOf(TvType.Movie, TvType.AnimeMovie)
                                    })
                                }
                            }
                        } catch (cancelled: TimeoutCancellationException) {

                            null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: NotImplementedError) {

                            null
                        } catch (error: Exception) {

                            null
                        }
                        DetailLookup(ids)
                    }
                    synchronized(detailMappings) {
                        detailFlights.remove(key)
                        detailMappings[key] = System.currentTimeMillis() to outcome
                        if (detailMappings.size > 160)
                            detailMappings.remove(detailMappings.keys.first())
                    }
                    outcome
                }
                detailFlights[key] = task
                task.invokeOnCompletion {
                    synchronized(detailMappings) {
                        if (detailFlights[key] === task) detailFlights.remove(key)
                    }
                }
                task
            }
        }
        val outcome = flight.await()
        return outcome.ids
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
                val host = uri?.host?.removePrefix("www.")
                val parts = uri?.pathSegments.orEmpty()
                if (anilist == null && host == "anilist.co" &&
                    parts.size >= 2 && parts[0] == "anime")
                    anilist = positive(parts[1])
                if (mal == null && host == "myanimelist.net" &&
                    parts.size >= 2 && parts[0] == "anime")
                    mal = positive(parts[1])
                if (kitsu == null && host == "kitsu.app" &&
                    parts.size >= 2 && parts[0] == "anime")
                    kitsu = positive(parts[1])
                if (simkl == null && host == "simkl.com") {
                    if (parts.size >= 2 && parts[0] in listOf("movie", "movies", "show", "shows", "tv", "anime"))
                        simkl = positive(parts[1])
                }
                if (tmdb == null && host == "themoviedb.org") {
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
                // These providers use TMDB JSON IDs but need not expose a TMDB mainUrl.
                // Never interpret arbitrary scraper JSON ids as TMDB identifiers.
                if (card.apiName in setOf("StreamPlay", "CineTmdb")) {
                    tmdb = candidate
                } else {
                    val source = APIHolder.getApiFromNameNull(card.apiName)?.mainUrl.orEmpty()
                    val host = runCatching { Uri.parse(source).host?.removePrefix("www.") }.getOrNull()
                    if (host == "themoviedb.org") tmdb = candidate
                }
            }
        }
        return PosterIds(imdb, tmdb, mal, anilist, kitsu) to simkl
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) {
            use(activity)
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
    }
}
