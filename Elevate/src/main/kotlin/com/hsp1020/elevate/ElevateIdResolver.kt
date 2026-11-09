package com.hsp1020.elevate

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/** Shared, validated external IDs for Elevate features. */
internal object ElevateIdResolver {
    private fun trace(step: String, detail: String) = Log.d("ElevateActorTrace", "ID_$step $detail")
    data class Mapping(
        val imdb: String?, val tmdb: String?, val mal: String?,
        val anilist: String?, val kitsu: String?, val movie: Boolean,
        val segment: String
    )
    private data class Cached<T>(val at: Long, val value: T)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imdbPattern = Regex("tt[0-9]{5,10}")
    private val simklLimiter = Semaphore(8)
    private val tmdbLimiter = Semaphore(6)
    private val simklCache = LinkedHashMap<String, Cached<Mapping?>>()
    private val simklFlights = mutableMapOf<String, Deferred<Mapping?>>()
    private val tmdbCache = LinkedHashMap<String, Cached<String?>>()
    private val tmdbFlights = mutableMapOf<String, Deferred<String?>>()
    private val imdbCache = LinkedHashMap<String, Cached<String?>>()
    private val imdbFlights = mutableMapOf<String, Deferred<String?>>()

    internal fun tmdbApiKey(): String? {
        val key = runCatching {
            Class.forName("com.lagradost.cloudstream3.ui.search.SearchSuggestionApi")
                .getDeclaredField("TMDB_API_KEY").apply { isAccessible = true }
                .get(null) as? String
        }.getOrNull()?.takeIf(String::isNotBlank)
        if (key != null) return key
        // Kotlin private constants can be inlined; use the core metadata provider's
        // actual instance field as a fallback, without embedding an API key.
        return runCatching {
            val type = Class.forName("com.lagradost.cloudstream3.metaproviders.TmdbProvider")
            val field = type.getDeclaredField("tmdbApiKey").apply { isAccessible = true }
            field.get(type.getDeclaredConstructor().newInstance()) as? String
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun simklClientId(): String? = runCatching {
        SimklApi::class.java.getDeclaredField("CLIENT_ID")
            .apply { isAccessible = true }.get(null) as? String
    }.getOrNull()?.takeIf(String::isNotBlank)


    /** Resolve a TMDB credit ID through Simkl's exact-ID 301 redirect. */
    suspend fun simklForTmdb(tmdbId: Int, movie: Boolean): Pair<String, String>? {
        if (tmdbId <= 0) return null
        val clientId = simklClientId() ?: AccountManager.syncApis
            .firstOrNull { it.idPrefix == "simkl" }?.authData()
            ?.let { SimklApi.getHeaders(it.token)["simkl-api-key"] }
            ?: return null
        return try {
            withTimeoutOrNull(9_000L) {
                val response = app.get("https://api.simkl.com/redirect",
                    params = mapOf(
                        "to" to "simkl",
                        "tmdb" to tmdbId.toString(),
                        "type" to if (movie) "movie" else "tv",
                        "client_id" to clientId,
                        "app-name" to "elevate",
                        "app-version" to "1.0"
                    ),
                    headers = mapOf("User-Agent" to "Elevate/1.0"),
                    timeout = 8L, allowRedirects = false)
                if (response.code !in 300..399) return@withTimeoutOrNull null
                val location = response.headers["Location"]
                    ?: response.headers["location"]
                    ?: return@withTimeoutOrNull null
                val destination = Uri.parse(location)
                if (destination.host?.lowercase() !in setOf("simkl.com", "www.simkl.com"))
                    return@withTimeoutOrNull null
                val path = destination.pathSegments
                val segment = path.getOrNull(0) ?: return@withTimeoutOrNull null
                if (if (movie) segment != "movies" else segment !in setOf("tv", "anime"))
                    return@withTimeoutOrNull null
                val id = path.getOrNull(1)?.toLongOrNull()
                    ?.takeIf { it > 0L }?.toString() ?: return@withTimeoutOrNull null
                id to segment
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            trace("SIMKL_REVERSE_ERROR", "tmdb=$tmdbId error=${error.javaClass.simpleName}")
            null
        }
    }

    suspend fun simkl(rawId: String, movie: Boolean, route: String? = null): Mapping? {
        trace("SIMKL_INPUT", "raw=$rawId movie=$movie route=$route")
        val id = rawId.toLongOrNull()?.takeIf { it > 0L }?.toString() ?: return null
        val auth = AccountManager.syncApis.firstOrNull { it.idPrefix == "simkl" }
            ?.authData()
        val clientId = simklClientId()
            ?: auth?.let { SimklApi.getHeaders(it.token)["simkl-api-key"] }
            ?: return null
        trace("SIMKL_AUTH", "id=$id auth=${auth != null} clientId=${clientId != null}")
        val headers = auth?.let { SimklApi.getHeaders(it.token) }
            ?: mapOf("simkl-api-key" to clientId)
        val segment = when (route) {
            "movie", "movies" -> "movies"
            "tv", "series", "show", "shows" -> "tv"
            "anime" -> "anime"
            else -> null
        }
        val accountKey = auth?.token?.hashCode() ?: 0
        val key = "$accountKey:$id:$movie:${segment ?: "lookup"}"
        val now = System.currentTimeMillis()
        val flight = synchronized(simklCache) {
            if (segment == null) {
                val accountPrefix = "$accountKey:$id:$movie:"
                val known = listOf("movies", "tv", "anime").mapNotNull { type ->
                    simklCache[accountPrefix + type]?.takeIf {
                        now - it.at in 0 until 3_600_000L
                    }?.value?.takeIf { it.movie == movie }
                }.distinct()
                if (known.size == 1) return known.first()
            }
            simklCache[key]?.takeIf {
                now - it.at in 0 until if (it.value == null) 300_000L else 3_600_000L
            }?.let { trace("SIMKL_CACHE", "id=$id hit=true found=${it.value != null}"); return it.value }
            simklFlights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                trace("SIMKL_REQUEST", "id=$id movie=$movie segment=$segment")
                val found = try {
                    simklLimiter.withPermit {
                    withTimeoutOrNull(16_000L) {
                        val params = mapOf("client_id" to clientId)
                        val resolvedSegment = segment ?: run {
                            trace("SIMKL_LOOKUP", "id=$id")
                            val result = JSONArray(app.get("https://api.simkl.com/search/id",
                                params = params + ("simkl" to id), headers = headers,
                                timeout = 7L).text)
                            val match = (0 until result.length())
                                .mapNotNull(result::optJSONObject).firstOrNull {
                                    it.optJSONObject("ids")?.optString("simkl") == id &&
                                        when (it.optString("type")) {
                                            "movie" -> movie
                                            "tv" -> !movie
                                            "anime" -> true
                                            else -> false
                                        }
                                } ?: return@withTimeoutOrNull null
                            when (match.optString("type")) {
                                "movie" -> "movies"
                                "tv" -> "tv"
                                "anime" -> "anime"
                                else -> return@withTimeoutOrNull null
                            }
                        }
                        trace("SIMKL_DETAIL", "id=$id segment=$resolvedSegment")
                        val detail = JSONObject(app.get("https://api.simkl.com/" +
                            resolvedSegment + "/" + id, params = params, headers = headers,
                            timeout = 7L).text)
                        val ids = detail.optJSONObject("ids") ?: return@withTimeoutOrNull null
                        val actualMovie = when (resolvedSegment) {
                            "movies" -> true
                            "tv" -> false
                            else -> detail.optString("anime_type").equals("movie", true)
                        }
                        if (ids.optString("simkl") != id ||
                            (actualMovie != movie && segment != "anime"))
                            return@withTimeoutOrNull null
                        fun numeric(field: String): String? = ids.optString(field)
                            .toLongOrNull()?.takeIf { it > 0L }?.toString()
                        Mapping(
                            ids.optString("imdb").takeIf(imdbPattern::matches),
                            numeric("tmdb"), if (resolvedSegment == "anime") numeric("mal") else null,
                            if (resolvedSegment == "anime") numeric("anilist") else null,
                            if (resolvedSegment == "anime") numeric("kitsu") else null,
                            actualMovie, resolvedSegment
                        ).takeIf { it.imdb != null || it.tmdb != null ||
                            it.mal != null || it.anilist != null || it.kitsu != null ||
                            it.segment == "anime" }
                    }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) { trace("SIMKL_ERROR", "id=$id error=${e.javaClass.simpleName}:${e.message}"); null }
                trace("SIMKL_RESULT", "id=$id found=$found")
                synchronized(simklCache) {
                    val entry = Cached(System.currentTimeMillis(), found)
                    simklCache[key] = entry
                    if (found != null) simklCache["$accountKey:$id:$movie:${found.segment}"] = entry
                    if (simklCache.size > 96) simklCache.remove(simklCache.keys.first())
                }
                // Only unambiguous movie/TV identifiers can seed TMDB-to-IMDb.
                if (found?.segment != "anime" && found?.imdb != null &&
                    found.tmdb != null) {
                    synchronized(tmdbCache) {
                        val type = if (found.movie) "movie" else "tv"
                        tmdbCache["${type}:${found.tmdb}"] =
                            Cached(System.currentTimeMillis(), found.imdb)
                        if (tmdbCache.size > 256) tmdbCache.remove(tmdbCache.keys.first())
                    }
                }
                found
            }.also { task ->
                simklFlights[key] = task
                task.invokeOnCompletion {
                    synchronized(simklCache) {
                        if (simklFlights[key] === task) simklFlights.remove(key)
                    }
                }
            }
        }
        flight.start()
        return flight.await().also { trace("SIMKL_AWAIT", "id=$id found=${it != null}") }
    }

    suspend fun imdbForTmdb(rawId: String, movie: Boolean): String? {
        val id = rawId.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: return null
        val key = "${if (movie) "movie" else "tv"}:$id"
        val now = System.currentTimeMillis()
        val flight = synchronized(tmdbCache) {
            tmdbCache[key]?.takeIf {
                now - it.at in 0 until if (it.value == null) 300_000L else 86_400_000L
            }?.let { return it.value }
            tmdbFlights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                val found = try {
                    val apiKey = tmdbApiKey()
                    if (apiKey == null) null else tmdbLimiter.withPermit {
                        withTimeoutOrNull(8_000L) {
                        val type = if (movie) "movie" else "tv"
                        val response = app.get("https://api.themoviedb.org/3/$type/$id/external_ids",
                            params = mapOf("api_key" to apiKey), timeout = 7L)
                        JSONObject(response.text).optString("imdb_id")
                            .takeIf(imdbPattern::matches)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                synchronized(tmdbCache) {
                    tmdbCache[key] = Cached(System.currentTimeMillis(), found)
                    if (tmdbCache.size > 256) tmdbCache.remove(tmdbCache.keys.first())
                }
                found
            }.also { task ->
                tmdbFlights[key] = task
                task.invokeOnCompletion {
                    synchronized(tmdbCache) {
                        if (tmdbFlights[key] === task) tmdbFlights.remove(key)
                    }
                }
            }
        }
        flight.start()
        return flight.await()
    }

    /** Public Simkl ID lookup, independent of the user's Simkl login state. */
    suspend fun tmdbForImdb(rawImdb: String, movie: Boolean): String? {
        trace("IMDB_INPUT", "raw=$rawImdb movie=$movie")
        val imdb = rawImdb.takeIf(imdbPattern::matches) ?: return null
        val clientId = simklClientId() ?: AccountManager.syncApis
            .firstOrNull { it.idPrefix == "simkl" }?.authData()
            ?.let { SimklApi.getHeaders(it.token)["simkl-api-key"] }
        val apiKey = tmdbApiKey()
        trace("IMDB_CLIENT", "imdb=$imdb tmdbKeyPresent=${apiKey != null} simklClientPresent=${clientId != null}")
        if (clientId == null && apiKey == null) return null
        val key = "${if (movie) "movie" else "tv"}:$imdb"
        val now = System.currentTimeMillis()
        val task = synchronized(imdbCache) {
            imdbCache[key]?.takeIf {
                now - it.at in 0 until if (it.value == null) 300_000L else 86_400_000L
            }?.let { trace("IMDB_CACHE", "key=$key value=${it.value}"); return it.value }
            imdbFlights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                trace("IMDB_REQUEST", "imdb=$imdb movie=$movie")
                // Prefer the direct TMDB find endpoint, then retain Simkl as fallback.
                val direct = try {
                    if (apiKey == null) null else tmdbLimiter.withPermit {
                        withTimeoutOrNull(9_000L) {
                            trace("IMDB_TMDB_REQUEST", "imdb=$imdb movie=$movie")
                            val res = app.get("https://api.themoviedb.org/3/find/$imdb",
                                params = mapOf("api_key" to apiKey,
                                    "external_source" to "imdb_id"), timeout = 8L)
                            trace("IMDB_TMDB_HTTP", "imdb=$imdb code=${res.code} bytes=${res.text.length}")
                            val rows = JSONObject(res.text).optJSONArray(
                                if (movie) "movie_results" else "tv_results")
                            (0 until (rows?.length() ?: 0)).mapNotNull {
                                rows?.optJSONObject(it)?.optInt("id")?.takeIf { id -> id > 0 }
                            }.distinct().singleOrNull()?.toString()
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (e: Exception) {
                    trace("IMDB_TMDB_ERROR", "imdb=$imdb error=${e.javaClass.simpleName}:${e.message}")
                    null
                }
                trace("IMDB_TMDB_RESULT", "imdb=$imdb tmdb=$direct")
                val result = direct ?: try {
                    if (clientId == null) null else simklLimiter.withPermit {
                        withTimeoutOrNull(10_000L) {
                            val response = app.get("https://api.simkl.com/search/id",
                                params = mapOf("client_id" to clientId, "imdb" to imdb),
                                headers = mapOf("simkl-api-key" to clientId), timeout = 8L)
                            trace("IMDB_HTTP", "imdb=$imdb code=${response.code} bytes=${response.text.length}")
                            val rows = JSONArray(response.text)
                            (0 until rows.length()).mapNotNull(rows::optJSONObject)
                                .firstNotNullOfOrNull { entry ->
                                    val ids = entry.optJSONObject("ids") ?: return@firstNotNullOfOrNull null
                                    if (ids.optString("imdb") != imdb) return@firstNotNullOfOrNull null
                                    val kind = entry.optString("type")
                                    if (kind == "movie" && !movie || kind == "tv" && movie ||
                                        kind !in setOf("movie", "tv", "anime"))
                                        return@firstNotNullOfOrNull null
                                    ids.optString("tmdb").toLongOrNull()
                                        ?.takeIf { it > 0L }?.toString()
                                }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (e: Exception) { trace("IMDB_ERROR", "imdb=$imdb error=${e.javaClass.simpleName}:${e.message}"); null }
                trace("IMDB_RESULT", "imdb=$imdb tmdb=$result")
                synchronized(imdbCache) {
                    imdbCache[key] = Cached(System.currentTimeMillis(), result)
                    if (imdbCache.size > 256) imdbCache.remove(imdbCache.keys.first())
                }
                result
            }.also { flight ->
                imdbFlights[key] = flight
                flight.invokeOnCompletion {
                    synchronized(imdbCache) {
                        if (imdbFlights[key] === flight) imdbFlights.remove(key)
                    }
                }
            }
        }
        task.start()
        return task.await().also { trace("IMDB_AWAIT", "imdb=$imdb tmdb=$it") }
    }

}

/** One XRDB version value shared by library, recommendations and actor overlays. */
internal object ElevateXrdbVersion {
    private data class Entry(val at: Long, val version: String?)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = mutableMapOf<PosterConfig, Entry>()
    private val flights = mutableMapOf<PosterConfig, Deferred<String?>>()
    private val validVersion = Regex("[a-fA-F0-9]{8}")

    suspend fun resolve(config: PosterConfig): String? {
        if (config.service != PosterService.XRDB) return null
        config.xrdbVersionOverride.takeIf { it.isNotBlank() }?.let { return it }
        if (config.xrdbProfile.isBlank()) return null
        val now = System.currentTimeMillis()
        val flight = synchronized(cache) {
            cache[config]?.takeIf { now - it.at in 0 until 30_000L }?.let {
                return it.version
            }
            flights[config] ?: scope.async(start = CoroutineStart.LAZY) {
                val found = fetch(config)
                synchronized(cache) {
                    cache[config] = Entry(System.currentTimeMillis(), found)
                    if (cache.size > 16) cache.remove(cache.keys.first())
                }
                found
            }.also { task ->
                flights[config] = task
                task.invokeOnCompletion {
                    synchronized(cache) { if (flights[config] === task) flights.remove(config) }
                }
            }
        }
        flight.start()
        return flight.await()
    }

    private suspend fun fetch(config: PosterConfig): String? {
        val base = Uri.parse(config.xrdbBase.trim().trimEnd('/'))
        if (base.scheme != "https" || base.host.isNullOrBlank() ||
            !base.userInfo.isNullOrEmpty() || !base.query.isNullOrEmpty() ||
            !base.fragment.isNullOrEmpty()) return null
        val headers = if (config.xrdbRenderKey.isBlank()) emptyMap() else
            mapOf("Authorization" to "Bearer ${config.xrdbRenderKey}")
        val profileUrl = base.buildUpon().appendPath("profile")
            .appendPath(config.xrdbProfile).build().toString()
        try {
            val body = app.get(profileUrl, headers = headers, timeout = 6L).text
            JSONObject(body).optString("versionToken").takeIf(validVersion::matches)
                ?.let { return it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { /* Public metadata may still expose the version. */ }
        val fallback = base.buildUpon().appendPath("stremio")
            .appendPath("c").appendPath(config.xrdbProfile)
            .appendPath("meta").appendPath("movie").appendPath("tt0111161.json")
            .apply {
                if (config.xrdbRenderKey.isNotBlank())
                    appendQueryParameter("key", config.xrdbRenderKey)
            }.build().toString()
        return try {
            val body = app.get(fallback, headers = headers, timeout = 8L).text
            val poster = JSONObject(body).optJSONObject("meta")?.optString("poster")
            poster?.let { Uri.parse(it).getQueryParameter("v") }
                ?.takeIf(validVersion::matches)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
    }
}
