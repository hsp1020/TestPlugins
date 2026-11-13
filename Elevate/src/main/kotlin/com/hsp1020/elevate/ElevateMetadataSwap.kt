package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.result.ResultData
import com.lagradost.cloudstream3.ui.result.ResultFragmentPhone
import com.lagradost.cloudstream3.ui.result.ResultFragmentTv
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.settings.getCurrentLocale
import com.lagradost.cloudstream3.utils.txt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Detail presentation only: playback, episode identity and sync data remain provider-owned. */
internal class ElevateMetadataSwap(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private data class Metadata(
        val title: String?, val overview: String?, val poster: String?,
        val background: String?, val logo: String?, val genres: List<String>,
        val year: String?, val rating: Double?, val duration: String?
    ) {
        fun toPage(original: ResultData): ResultData {
            val name = title ?: original.title
            return original.copy(
                title = name,
                titleText = title?.let { txt(it) } ?: original.titleText,
                plotText = overview?.let { txt(it) } ?: original.plotText,
                posterImage = poster ?: original.posterImage,
                posterBackgroundImage = background ?: original.posterBackgroundImage,
                backgroundPosterUrl = background ?: original.backgroundPosterUrl,
                // A source logo may carry the old title. Show the new title when no new logo exists.
                logoUrl = logo,
                posterHeaders = if (poster != null && background != null) null else original.posterHeaders,
                tags = genres.ifEmpty { original.tags },
                yearText = year?.let { txt(it) } ?: original.yearText,
                ratingText = rating?.let {
                    txt(R.string.rating_format, String.format(Locale.US, "%.1f", it))
                } ?: original.ratingText,
                durationText = duration?.let { txt(it) } ?: original.durationText
            )
        }
    }

    private fun JSONObject.string(vararg names: String): String? =
        names.firstNotNullOfOrNull { key ->
            optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
        }

    private fun JSONObject.names(key: String): List<String> =
        optJSONArray(key)?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                when (val item = array.opt(index)) {
                    is JSONObject -> item.string("name")
                    is String -> item.trim().takeIf(String::isNotEmpty)
                    else -> null
                }
            }.distinct()
        } ?: emptyList()

    private fun metadataFromTmdb(json: JSONObject): Metadata? {
        val title = json.string("title", "name")
        val overview = json.string("overview")
        val poster = json.string("poster_path")?.takeIf { it.startsWith("/") }
            ?.let { "https://image.tmdb.org/t/p/w780$it" }
        val backdrop = json.string("backdrop_path")?.takeIf { it.startsWith("/") }
            ?.let { "https://image.tmdb.org/t/p/original$it" }
        if (title == null && overview == null && poster == null && backdrop == null) return null
        val year = json.string("release_date", "first_air_date")?.take(4)
            ?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        val rating = json.optDouble("vote_average", Double.NaN).takeIf {
            !it.isNaN() && it > 0.0
        }
        val minutes = json.optInt("runtime").takeIf { it > 0 }
            ?: json.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
        return Metadata(title, overview, poster, backdrop, null, json.names("genres"),
            year, rating, minutes?.let { "$it mins" })
    }

    private fun metadataFromAddon(json: JSONObject): Metadata? {
        val meta = json.optJSONObject("meta") ?: json
        val title = meta.string("name", "title")
        val description = meta.string("description", "overview")
        val poster = meta.string("poster")
        val background = meta.string("background", "backdrop")
        if (title == null && description == null && poster == null && background == null) return null
        val year = meta.string("year", "releaseInfo", "released")?.take(4)
            ?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        val rating = meta.string("imdbRating", "rating")?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 && it > 0 }
        return Metadata(title, description, poster, background,
            meta.string("logo", "logoUrl"), meta.names("genres").ifEmpty { meta.names("genre") },
            year, rating, meta.string("runtime"))
    }

    private val prefs = application.getSharedPreferences(ElevateFeaturePrefs.FILE, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val screens = mutableMapOf<Fragment, Screen>()
    private var host: FragmentActivity? = null
    private val responseField = runCatching {
        ResultViewModel2::class.java.getDeclaredField("currentResponse").apply { isAccessible = true }
    }.getOrNull()
    private val pageField = runCatching {
        ResultViewModel2::class.java.getDeclaredField("_page").apply { isAccessible = true }
    }.getOrNull()

    private fun nativePage(model: ResultViewModel2, page: ResultData) {
        @Suppress("UNCHECKED_CAST")
        val live = pageField?.get(model) as? MutableLiveData<Resource<ResultData>?> ?: return
        live.value = Resource.Success(page)
    }

    private inner class Screen(private val fragment: Fragment) {
        private val model = ViewModelProvider(fragment)[ResultViewModel2::class.java]
        private var original: ResultData? = null
        private var swapped: ResultData? = null
        private var job: Job? = null
        private var serial = 0
        private val observer = Observer<Resource<ResultData>?> { result ->
            val page = (result as? Resource.Success<ResultData>)?.value ?: return@Observer
            if (page !== swapped) update(page)
        }

        fun start() {
            model.page.observe(fragment.viewLifecycleOwner, observer)
            (model.page.value as? Resource.Success<ResultData>)?.value?.let(::update)
        }

        private fun update(page: ResultData) {
            original = page
            swapped = null
            job?.cancel()
            val generation = ++serial
            if (!prefs.getBoolean(ElevateFeature.METADATA_SWAP.name, false)) return
            val provider = prefs.getString(ElevateFeaturePrefs.METADATA_SWAP_PROVIDER, "TMDB")
            val manifest = prefs.getString(ElevateFeaturePrefs.METADATA_SWAP_ADDON_URL, "").orEmpty()
            if (provider == "AIO" && manifest.isBlank()) return
            job = scope.launch {
                val response = runCatching { responseField?.get(model) as? LoadResponse }
                    .getOrNull()?.takeIf { it.url == page.url }
                val ids = ElevateMediaIdentity.collect(
                    response, extraSync = page.syncData, urls = listOf(page.url))
                val movie = response?.type in setOf(TvType.Movie, TvType.AnimeMovie) ||
                    runCatching { JSONObject(page.url).optString("type") == "movie" }.getOrDefault(false)
                val metadata = try {
                    withContext(Dispatchers.IO) {
                        if (provider == "AIO") Addon.load(manifest, movie, ids)?.let { metadataFromAddon(it) }
                        else {
                            var id = ids.tmdb ?: ids.imdb?.let {
                                ElevateIdResolver.tmdbForImdb(it, movie)
                            }
                            if (id == null && ids.simkl != null) {
                                id = ElevateMediaIdentity.simklMapping(ids, movie)?.tmdb
                            }
                            if (id == null) {
                                for ((source, value) in listOfNotNull(
                                    ids.mal?.let { "mal" to it },
                                    ids.anilist?.let { "anilist" to it },
                                    ids.kitsu?.let { "kitsu" to it })) {
                                    val mapped = ElevateAnimeIdResolver.resolve(source, value) ?: continue
                                    id = mapped.tmdb ?: mapped.imdb?.let {
                                        ElevateIdResolver.tmdbForImdb(it, movie)
                                    }
                                    if (id != null) break
                                }
                            }
                            id?.let {
                                ElevateTmdbMetadata.details(it, movie, getCurrentLocale(application),
                                    includeCredits = prefs.getBoolean(ElevateFeature.ACTOR_INFORMATION.name, false))
                            }?.let { metadataFromTmdb(it) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (metadata == null || generation != serial || original !== page ||
                    !fragment.isAdded || fragment.view == null ||
                    !prefs.getBoolean(ElevateFeature.METADATA_SWAP.name, false) ||
                    prefs.getString(ElevateFeaturePrefs.METADATA_SWAP_PROVIDER, "TMDB") != provider ||
                    (provider == "AIO" &&
                        prefs.getString(ElevateFeaturePrefs.METADATA_SWAP_ADDON_URL, "") != manifest) ||
                    (model.page.value as? Resource.Success<ResultData>)?.value !== page) return@launch
                swapped = metadata.toPage(page)
                swapped?.let { nativePage(model, it) }
            }
        }

        fun refresh() {
            val current = original ?: return
            job?.cancel()
            serial++
            val visible = (model.page.value as? Resource.Success<ResultData>)?.value
            if (visible === swapped) nativePage(model, current)
            else update(current)
        }

        fun stop() {
            job?.cancel()
            serial++
            model.page.removeObserver(observer)
            val visible = (model.page.value as? Resource.Success<ResultData>)?.value
            if (visible === swapped) original?.let { nativePage(model, it) }
            swapped = null
            original = null
        }
    }

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment,
            view: android.view.View, savedInstanceState: Bundle?) {
            if (fragment !is ResultFragmentPhone && fragment !is ResultFragmentTv) return
            if (screens.containsKey(fragment)) return
            screens[fragment] = Screen(fragment).also { it.start() }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            screens.remove(fragment)?.stop()
        }
    }

    private fun attach(activity: FragmentActivity) {
        if (host === activity) return
        detach()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { fragment ->
                fragment.view?.let { callbacks.onFragmentViewCreated(manager, fragment, it, null) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun detach() {
        screens.values.toList().forEach { it.stop() }
        screens.clear()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        host = null
    }

    fun refresh() = screens.values.toList().forEach { it.refresh() }
    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        (com.lagradost.cloudstream3.CommonActivity.activity as? FragmentActivity)?.let(::attach)
    }
    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        detach()
        scope.cancel()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) {
        (activity as? FragmentActivity)?.let(::attach)
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (activity === host) detach()
    }

    /** Stremio manifest/meta contract, using the configured addon, not the StremioC provider. */
    private object Addon {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private data class Cached(val at: Long, val json: JSONObject?)
        private val manifests = LinkedHashMap<String, Cached>()
        private val metadata = LinkedHashMap<String, Cached>()
        private val manifestFlights = mutableMapOf<String, Deferred<JSONObject?>>()
        private val metaFlights = mutableMapOf<String, Deferred<JSONObject?>>()

        private fun manifestUrl(raw: String): Uri? {
            val url = raw.trim().replace("stremio://", "https://")
            val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrBlank() ||
                parsed.authority.orEmpty().contains('@')) return null
            val path = parsed.encodedPath.orEmpty().trimEnd('/')
            return parsed.buildUpon().encodedPath(
                if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
            ).fragment(null).build()
        }

        private suspend fun shared(key: String, cache: LinkedHashMap<String, Cached>,
            flights: MutableMap<String, Deferred<JSONObject?>>, ttl: Long,
            request: suspend () -> JSONObject?): JSONObject? {
            val now = System.currentTimeMillis()
            val job = synchronized(cache) {
                cache[key]?.takeIf { now - it.at in 0 until ttl }?.let { return it.json }
                flights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                    val json = request()
                    synchronized(cache) {
                        cache[key] = Cached(System.currentTimeMillis(), json)
                        if (cache.size > 100) cache.remove(cache.keys.first())
                    }
                    json
                }.also { active ->
                    flights[key] = active
                    active.invokeOnCompletion {
                        synchronized(cache) { if (flights[key] === active) flights.remove(key) }
                    }
                }
            }
            job.start()
            return job.await()
        }

        private suspend fun request(url: String): JSONObject? = try {
            val result = app.get(url, timeout = 12L)
            if (result.code !in 200..299) null else {
                val raw = JSONObject(result.text)
                if (!url.contains("/meta/")) raw else {
                    // A series can carry hundreds of episodes; cache only display metadata.
                    val data = raw.optJSONObject("meta") ?: raw
                    val slim = JSONObject()
                    for (field in listOf("name", "title", "description", "overview", "poster",
                        "background", "backdrop", "logo", "logoUrl", "genres", "genre",
                        "year", "releaseInfo", "released", "imdbRating", "rating", "runtime")) {
                        if (data.has(field)) slim.put(field, data.get(field))
                    }
                    JSONObject().put("meta", slim)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { null }

        suspend fun load(rawManifest: String, movie: Boolean, ids: ElevateMediaIdentity.Ids): JSONObject? {
            val manifest = manifestUrl(rawManifest) ?: return null
            val uri = manifest.toString()
            val definition = shared(uri, manifests, manifestFlights, 3_600_000L) {
                request(uri)
            } ?: return null
            val resources = definition.optJSONArray("resources") ?: return null
            val supportsMeta = (0 until resources.length()).any {
                when (val item = resources.opt(it)) {
                    is String -> item == "meta"
                    is JSONObject -> item.optString("name") == "meta"
                    else -> false
                }
            }
            if (!supportsMeta) return null
            val allowedTypes = definition.optJSONArray("types")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            val candidates = if (movie) listOf("movie") else
                if (ids.animeIds) listOf("anime", "series", "tv") else listOf("series", "tv", "anime")
            val type = candidates.firstOrNull { it in allowedTypes } ?: return null
            val prefixes = definition.optJSONArray("idPrefixes")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            val values = listOfNotNull(ids.original, ids.imdb, ids.tmdb?.let { "tmdb:$it" },
                ids.mal?.let { "mal:$it" }, ids.anilist?.let { "anilist:$it" },
                ids.kitsu?.let { "kitsu:$it" }).distinct().filter { id ->
                prefixes.isEmpty() || prefixes.any { id.startsWith(it, ignoreCase = true) }
            }
            for (id in values.take(4)) {
                val url = manifest.buildUpon().encodedPath(
                    manifest.encodedPath.orEmpty().removeSuffix("/manifest.json") +
                        "/meta/$type/${Uri.encode(id)}.json"
                ).build().toString()
                val result = shared(url, metadata, metaFlights, 43_200_000L) {
                    request(url)
                }
                if (result?.optJSONObject("meta") != null) return result
            }
            return null
        }
    }
}
