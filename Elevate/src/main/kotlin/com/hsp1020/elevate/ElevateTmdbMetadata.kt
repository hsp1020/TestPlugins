package com.hsp1020.elevate

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Shared work-level TMDB language and credits cache, independent of providers. */
internal object ElevateTmdbMetadata {
    private const val TTL = 24L * 60 * 60 * 1000
    private const val SEASON_TTL = 12L * 60 * 60 * 1000
    private fun trace(message: String) = android.util.Log.d("MetadataSwapTrace", message)
    private data class Entry(
        val at: Long, val detailLoaded: Boolean, val originalLanguage: String?,
        val creditsLocale: String?, val credits: JSONObject?,
        val detailLocale: String?, val details: JSONObject?
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = LinkedHashMap<String, Entry>()
    private val flights = mutableMapOf<String, Deferred<Entry?>>()
    private val originLogoCache = LinkedHashMap<String, Pair<Long, JSONArray>>()
    private val originLogoFlights = mutableMapOf<String, Deferred<JSONArray?>>()
    private val seasonCache = LinkedHashMap<String, Pair<Long, JSONObject>>()
    private val seasonFlights = mutableMapOf<String, Deferred<JSONObject?>>()

    private fun language(raw: String?): String? {
        val value = raw?.trim()?.replace('_', '-')?.lowercase(Locale.ROOT) ?: return null
        return value.takeIf {
            it.matches(Regex("[a-z]{2,3}(-[a-z]{2})?")) &&
                it.substringBefore('-') !in setOf("und", "zxx")
        }
    }

    /** A /find response already contains original_language; reuse it. */
    fun rememberOriginalLanguage(id: String, movie: Boolean, value: String?) {
        val number = id.toLongOrNull()?.takeIf { it > 0 } ?: return
        val original = language(value) ?: return
        val key = "${if (movie) "movie" else "tv"}:$number"
        synchronized(cache) {
            val previous = cache[key]
            cache[key] = Entry(System.currentTimeMillis(), previous?.detailLoaded == true,
                original, previous?.creditsLocale, previous?.credits,
                previous?.detailLocale, previous?.details)
            if (cache.size > 128) cache.remove(cache.keys.first())
        }
    }

    suspend fun originalLanguage(
        id: String, movie: Boolean, locale: String, includeCredits: Boolean = false
    ): String? = resolve(id, movie, locale, needsDetails = false,
        needsCredits = false, prefetchCredits = includeCredits)
        ?.originalLanguage

    suspend fun credits(id: String, movie: Boolean, locale: String): JSONObject? =
        resolve(id, movie, locale, needsDetails = false,
            needsCredits = true, prefetchCredits = false)?.credits

    /** Full detail from the same response as /credits; no second TMDB request on cache hits. */
    suspend fun details(id: String, movie: Boolean, locale: String,
        includeCredits: Boolean = false): JSONObject? =
        resolve(id, movie, locale, needsDetails = true,
            needsCredits = includeCredits, prefetchCredits = includeCredits)?.details

    /** Second request only when preferred-language and English logos are absent. */
    suspend fun originalLogos(id: String, movie: Boolean, original: String): JSONArray? {
        val number = id.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val language = language(original)?.substringBefore('-') ?: return null
        val kind = if (movie) "movie" else "tv"
        val key = "$kind:$number:$language"
        val now = System.currentTimeMillis()
        val task = synchronized(originLogoCache) {
            originLogoCache[key]?.takeIf { now - it.first in 0 until TTL }
                ?.let {
                    trace("ELEVATE_TMDB_CACHE kind=logo source=ram")
                    return it.second
                }
            originLogoFlights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    ElevateMetadataDiskCache.read("tmdb_logo", key)?.let { entry ->
                        runCatching { JSONArray(entry.body) }.getOrNull()?.let { logos ->
                            synchronized(originLogoCache) {
                                originLogoCache[key] = entry.at to logos
                                if (originLogoCache.size > 128)
                                    originLogoCache.remove(originLogoCache.keys.first())
                            }
                            trace("ELEVATE_TMDB_CACHE kind=logo source=disk")
                            return@async logos
                        }
                    }
                    val apiKey = ElevateIdResolver.tmdbApiKey() ?: return@async null
                    val response = app.get(
                        "https://api.themoviedb.org/3/$kind/$number/images",
                        params = mapOf("api_key" to apiKey, "include_image_language" to language),
                        timeout = 12L
                    )
                    if (response.code !in 200..299) return@async null
                    JSONObject(response.text).optJSONArray("logos")?.also { logos ->
                        synchronized(originLogoCache) {
                            originLogoCache[key] = System.currentTimeMillis() to logos
                            if (originLogoCache.size > 128)
                                originLogoCache.remove(originLogoCache.keys.first())
                        }
                        ElevateMetadataDiskCache.write("tmdb_logo", key, logos.toString(), TTL)
                        trace("ELEVATE_TMDB_CACHE kind=logo source=network")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { null }
            }.also { active ->
                originLogoFlights[key] = active
                active.invokeOnCompletion {
                    synchronized(originLogoCache) {
                        if (originLogoFlights[key] === active) originLogoFlights.remove(key)
                    }
                }
            }
        }
        task.start()
        return task.await()
    }

    /** Shared season response, cached by title, season and locale. */
    suspend fun season(id: String, season: Int, locale: String): JSONObject? {
        val number = id.toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (season !in 0..99) return null
        val key = "$number:$season:${locale.lowercase(Locale.ROOT)}"
        val now = System.currentTimeMillis()
        val task = synchronized(seasonCache) {
            seasonCache[key]?.takeIf { now - it.first in 0 until SEASON_TTL }?.let {
                trace("ELEVATE_TMDB_CACHE kind=season source=ram season=" + season)
                return it.second
            }
            seasonFlights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    ElevateMetadataDiskCache.read("tmdb_season", key)?.let { entry ->
                        runCatching { JSONObject(entry.body) }.getOrNull()?.let { json ->
                            synchronized(seasonCache) {
                                seasonCache[key] = entry.at to json
                                if (seasonCache.size > 128)
                                    seasonCache.remove(seasonCache.keys.first())
                            }
                            trace("ELEVATE_TMDB_CACHE kind=season source=disk season=" + season)
                            return@async json
                        }
                    }
                    val apiKey = ElevateIdResolver.tmdbApiKey() ?: return@async null
                    val response = app.get("https://api.themoviedb.org/3/tv/$number/season/$season",
                        params = mapOf("api_key" to apiKey, "language" to locale), timeout = 12L)
                    if (response.code !in 200..299) return@async null
                    JSONObject(response.text).also { json ->
                        synchronized(seasonCache) {
                            seasonCache[key] = System.currentTimeMillis() to json
                            if (seasonCache.size > 128) seasonCache.remove(seasonCache.keys.first())
                        }
                        ElevateMetadataDiskCache.write("tmdb_season", key, json.toString(), SEASON_TTL)
                        trace("ELEVATE_TMDB_CACHE kind=season source=network season=" + season)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { null }
            }.also { active ->
                seasonFlights[key] = active
                active.invokeOnCompletion {
                    synchronized(seasonCache) {
                        if (seasonFlights[key] === active) seasonFlights.remove(key)
                    }
                }
            }
        }
        task.start()
        return task.await()
    }

    private suspend fun resolve(
        id: String, movie: Boolean, locale: String,
        needsDetails: Boolean, needsCredits: Boolean, prefetchCredits: Boolean
    ): Entry? {
        val number = id.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val kind = if (movie) "movie" else "tv"
        val key = "$kind:$number"
        val normalizedLocale = locale.lowercase(Locale.ROOT)
        if (needsDetails && synchronized(cache) {
                cache[key]?.takeIf { System.currentTimeMillis() - it.at in 0 until TTL }
                    ?.let { it.detailLocale == normalizedLocale && it.details != null } != true
            }) {
            ElevateMetadataDiskCache.read("tmdb_detail", key + ":" + normalizedLocale)
                ?.let { disk ->
                    runCatching { JSONObject(disk.body) }.getOrNull()?.let { detail ->
                        synchronized(cache) {
                            val previous = cache[key]?.takeIf {
                                System.currentTimeMillis() - it.at in 0 until TTL
                            }
                            if (previous?.detailLocale != normalizedLocale || previous?.details == null) {
                                cache[key] = Entry(disk.at, true,
                                    language(detail.optString("original_language"))
                                        ?: previous?.originalLanguage,
                                    previous?.creditsLocale, previous?.credits,
                                    normalizedLocale, detail)
                                if (cache.size > 128) cache.remove(cache.keys.first())
                            }
                        }
                        trace("ELEVATE_TMDB_CACHE kind=detail source=disk")
                    }
                }
        }
        while (true) {
            val (job, joining) = synchronized(cache) {
                val now = System.currentTimeMillis()
                val previous = cache[key]?.takeIf { now - it.at in 0 until TTL }
                val hasDetails = previous?.detailLoaded == true || previous?.originalLanguage != null
                val hasCredits = previous?.creditsLocale == normalizedLocale && previous?.credits != null
                val hasFullDetails = previous?.detailLocale == normalizedLocale && previous?.details != null
                if (hasDetails && (!needsCredits || hasCredits) &&
                    (!needsDetails || hasFullDetails)) {
                    if (needsDetails) trace("ELEVATE_TMDB_CACHE kind=detail source=ram")
                    return previous
                }
                val active = flights[key]
                if (active != null) active to true
                else {
                    val fetchDetails = !hasDetails || needsDetails && !hasFullDetails
                    val fetchCredits = (needsCredits || prefetchCredits && fetchDetails) && !hasCredits
                    val task = scope.async(start = CoroutineStart.LAZY) {
                        try {
                            val apiKey = ElevateIdResolver.tmdbApiKey() ?: return@async null
                            val endpoint = "https://api.themoviedb.org/3/$kind/$number" +
                                if (fetchDetails) "" else "/credits"
                            val params = mutableMapOf("api_key" to apiKey, "language" to locale)
                            if (fetchDetails && needsDetails) {
                                params["append_to_response"] = (listOf(
                                    "images", "videos", "translations",
                                    if (movie) "release_dates" else "content_ratings"
                                ) + if (fetchCredits) listOf("credits") else emptyList()).joinToString(",")
                                params["include_image_language"] = listOf(
                                    locale.substringBefore('-').lowercase(Locale.ROOT), "en"
                                ).distinct().joinToString(",")
                                params["include_video_language"] = listOf(
                                    locale, locale.substringBefore('-'), "en"
                                ).distinct().joinToString(",")
                            } else if (fetchDetails && fetchCredits) {
                                params["append_to_response"] = "credits"
                            }
                            val response = app.get(endpoint, params = params, timeout = 12L)
                            if (response.code !in 200..299) return@async null
                            val json = JSONObject(response.text)
                            val original = if (fetchDetails) language(json.optString("original_language"))
                                else null
                            // Keep only display fields; don't retain the full appended credits tree twice.
                            val foundDetail = if (fetchDetails && needsDetails) JSONObject().apply {
                                for (field in listOf("name", "title", "overview", "poster_path",
                                    "backdrop_path", "genres", "vote_average", "release_date",
                                    "first_air_date", "runtime", "episode_run_time",
                                    "status", "images", "videos", "release_dates",
                                    "content_ratings", "translations", "original_language",
                                    "original_title", "original_name")) {
                                    if (json.has(field)) put(field, json.get(field))
                                }
                            } else null
                            val foundCredits = if (fetchCredits) {
                                if (fetchDetails) json.optJSONObject("credits") else json
                            } else null
                            if (foundDetail != null) {
                                ElevateMetadataDiskCache.write("tmdb_detail",
                                    key + ":" + normalizedLocale, foundDetail.toString(), TTL)
                                trace("ELEVATE_TMDB_CACHE kind=detail source=network")
                            }
                            synchronized(cache) {
                                val current = cache[key]?.takeIf {
                                    System.currentTimeMillis() - it.at in 0 until TTL
                                }
                                val entry = Entry(System.currentTimeMillis(),
                                    (fetchDetails && needsDetails) || current?.detailLoaded == true,
                                    original ?: current?.originalLanguage,
                                    if (fetchCredits) normalizedLocale else current?.creditsLocale,
                                    if (fetchCredits) foundCredits else current?.credits,
                                    if (fetchDetails && needsDetails) normalizedLocale else current?.detailLocale,
                                    if (fetchDetails && needsDetails) foundDetail else current?.details)
                                cache[key] = entry
                                if (cache.size > 128) cache.remove(cache.keys.first())
                                entry
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) { null }
                    }
                    flights[key] = task
                    task.invokeOnCompletion {
                        synchronized(cache) { if (flights[key] === task) flights.remove(key) }
                    }
                    task to false
                }
            }
            job.start()
            val result = job.await()
            if (!joining || result == null) return result
            // A concurrent request may have fetched only the language; fetch missing credits.
        }
    }
}
