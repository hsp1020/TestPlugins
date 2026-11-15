package com.hsp1020.elevate

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Shared AIOStreams title ID mapping; no dependency on the StremioC plugin. */
internal object ElevateAnimeIdResolver {
    internal data class TmdbRange(val season: Int, val start: Int, val end: Int?)
    internal data class Mapping(
        val kitsu: String?, val mal: String?, val anilist: String?,
        val imdb: String?, val tmdb: String?, val fromEpisode: Int? = null,
        val simkl: String? = null, val tvdb: String? = null,
        val tvdbSeason: Int? = null, val tmdbSeason: Int? = null,
        val traktSeason: Int? = null,
        val imdbSeason: Int? = null, val tmdbFromEpisode: Int? = null,
        val tmdbRanges: List<TmdbRange> = emptyList()
    ) {
        fun matchesSeason(season: Int): Boolean =
            ElevateWatchHistoryRules.consistentSeason(season,
                listOf(imdbSeason, tvdbSeason, tmdbSeason, traktSeason))
    }
    internal data class EpisodeMapping(val mal: String?, val anilist: String?, val episode: Int,
        val kitsu: String? = null, val season: Int? = null,
        val imdb: String? = null, val tmdb: String? = null,
        val imdbSeason: Int? = null, val tmdbSeason: Int? = null,
        val tmdbFromEpisode: Int? = null)

    private val hosts = listOf(
        "aiostreams.elfhosted.com", "aiostreams.fortheweak.cloud",
        "aiostreamsfortheweebsstable.midnightignite.me",
        "aio.atbphosting.com", "aiostreams.stremio.ru", "aiostreams.viren070.me"
    )
    private val kitsuCache = object : LinkedHashMap<String, Pair<Long, Map<Int, Int>>>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Map<Int, Int>>>) =
            size > 48
    }
    private val kitsuFlights = mutableMapOf<String, Deferred<Map<Int, Int>>>()
    private val imdbKitsuFlights = mutableMapOf<String, Deferred<Map<Pair<Int, Int>, Int>>>()
    private val imdbKitsuCache = mutableMapOf<String, Pair<Long, Map<Pair<Int, Int>, Int>>>()
    private val limiter = Semaphore(3)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flights = mutableMapOf<String, Deferred<Mapping?>>()
    private val cache = object : LinkedHashMap<String, Pair<Long, Mapping?>>(48, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Mapping?>>) =
            size > 96
    }

    suspend fun resolve(source: String, rawId: String): Mapping? {
        val field = when (source) {
            "kitsu" -> "kitsuId"
            "mal" -> "malId"
            "anilist" -> "anilistId"
            else -> return null
        }
        val id = rawId.removePrefix(source + ":").substringBefore(':')
            .toLongOrNull()?.takeIf { it > 0L }?.toString() ?: return null
        return resolveByField(field, id, null)
    }

    /** Season watch-history lookups may use verified TVDB/Simkl-only mappings.
     * The existing resolver methods keep their original anime-ID contract. */
    suspend fun resolveTrackingSource(source: String, rawId: String): Mapping? {
        val field = when (source) {
            "kitsu" -> "kitsuId"
            "mal" -> "malId"
            "anilist" -> "anilistId"
            else -> return null
        }
        val id = rawId.removePrefix(source + ":").substringBefore(':')
            .toLongOrNull()?.takeIf { it > 0L }?.toString() ?: return null
        return resolveByField(field, id, null, allowTrackerOnly = true)
    }

    suspend fun resolveTrackingImdbSeason(rawId: String, season: Int): Mapping? {
        val id = rawId.takeIf { it.matches(Regex("tt[0-9]+")) } ?: return null
        if (season <= 0) return null
        return resolveByField("imdbId", id, season, allowTrackerOnly = true)
    }

    /** Resolve an IMDb title at the selected season, independently of StremioC. */
    suspend fun resolveImdbSeason(rawId: String, season: Int): Mapping? {
        val id = rawId.takeIf { it.matches(Regex("tt[0-9]+")) } ?: return null
        if (season <= 0) return null
        return resolveByField("imdbId", id, season, allowSplitSeasons = true)
    }

    /** Same AIOStreams flight/cache as title and season mapping, with episode offset retained. */
    suspend fun resolveEpisode(
        imdb: String?, mal: String?, anilist: String?, kitsu: String?,
        season: Int?, episode: Int, trace: String? = null
    ): EpisodeMapping? {
        if (episode <= 0) {

            return null
        }
        val (field, raw) = when {
            imdb != null -> "imdbId" to imdb
            mal != null -> "malId" to mal
            anilist != null -> "anilistId" to anilist
            kitsu != null -> "kitsuId" to kitsu
            else -> {

                return null
            }
        }
        val id = if (field == "imdbId")
            raw.takeIf { it.matches(Regex("tt[0-9]+")) }
        else raw.toLongOrNull()?.takeIf { it > 0L }?.toString()
        if (id == null) {

            return null
        }

        val mapped = resolveByField(field, id, if (field == "imdbId") season else null, trace = trace)
        if (mapped == null) {

            return null
        }
        val local = if (field == "imdbId" && mapped.fromEpisode != null)
            (episode - mapped.fromEpisode + 1).takeIf { it > 0 }
        else episode
        if (local == null) {

            return null
        }
        val result = EpisodeMapping(mapped.mal, mapped.anilist, local, mapped.kitsu,
            mapped.imdbSeason ?: mapped.tvdbSeason ?: mapped.tmdbSeason,
            mapped.imdb, mapped.tmdb, mapped.imdbSeason, mapped.tmdbSeason,
            mapped.tmdbFromEpisode)

        return result
    }

    /** Kitsu Stremio metadata exposes per-episode absoluteEpisode; never infer offsets. */
    suspend fun kitsuAbsoluteEpisodes(
        rawId: String?, trace: String? = null
    ): Map<Int, Int> {
        val id = rawId?.removePrefix("kitsu:")?.substringBefore(':')
            ?.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: run {

                return emptyMap()
            }
        val now = System.currentTimeMillis()
        synchronized(kitsuCache) {
            kitsuCache[id]?.takeIf {
                !ElevateMetadataCacheDiagnostics.enabled() && now - it.first < if (it.second.isEmpty()) 300_000L else 86_400_000L
            }?.let {

                return it.second
            }
        }

        val flight = synchronized(kitsuCache) {
            kitsuFlights[id] ?: scope.async(start = CoroutineStart.LAZY) {
                val result = try {
                    withTimeoutOrNull(10_000L) {
                        limiter.withPermit {
                            val text = app.get("https://anime-kitsu.strem.fun/meta/series/kitsu:" +
                                id + ".json", headers = ElevateMetadataCacheDiagnostics.requestHeaders(),
                                timeout = 8L).text
                            val videos = JSONObject(text).optJSONObject("meta")?.optJSONArray("videos")
                            buildMap<Int, Int> {
                                if (videos != null) for (n in 0 until videos.length()) {
                                    val video = videos.optJSONObject(n) ?: continue
                                    val local = video.optInt("episode").takeIf { it > 0 } ?: continue
                                    val absolute = video.optInt("absoluteEpisode").takeIf { it > 0 }
                                        ?: continue
                                    put(local, absolute)
                                }
                            }
                        }
                    } ?: run {

                        emptyMap()
                    }
                } catch (e: CancellationException) { throw e }
                  catch (ex: Exception) {

                      emptyMap()
                  }
                if (!ElevateMetadataCacheDiagnostics.enabled()) synchronized(kitsuCache) {
                    kitsuCache[id] = System.currentTimeMillis() to result
                }
                result
            }.also { task ->
                kitsuFlights[id] = task
                task.invokeOnCompletion {
                    synchronized(kitsuCache) {
                        if (kitsuFlights[id] === task) kitsuFlights.remove(id)
                    }
                }
            }
        }
        flight.start()
        val result = flight.await()

        return result
    }

    /** IMDb-form Kitsu metadata preserves the addon season/episode alongside absoluteEpisode. */
    suspend fun kitsuImdbAbsolutes(imdb: String): Map<Pair<Int, Int>, Int> {
        if (!imdb.matches(Regex("tt[0-9]+"))) return emptyMap()
        val now = System.currentTimeMillis()
        synchronized(imdbKitsuCache) {
            imdbKitsuCache[imdb]?.takeIf {
                !ElevateMetadataCacheDiagnostics.enabled() &&
                    now - it.first < if (it.second.isEmpty()) 300_000L else 86_400_000L
            }?.let { return it.second }
        }
        val flight = synchronized(imdbKitsuCache) {
            imdbKitsuFlights[imdb] ?: scope.async(start = CoroutineStart.LAZY) {
                val result = try {
                    withTimeoutOrNull(10_000L) {
                        limiter.withPermit {
                            val raw = app.get("https://anime-kitsu.strem.fun/meta/series/$imdb.json",
                                headers = ElevateMetadataCacheDiagnostics.requestHeaders(),
                                timeout = 8L).text
                            val videos = JSONObject(raw).optJSONObject("meta")?.optJSONArray("videos")
                            buildMap<Pair<Int, Int>, Int> {
                                if (videos != null) for (i in 0 until videos.length()) {
                                    val video = videos.optJSONObject(i) ?: continue
                                    val season = video.optInt("season").takeIf { it >= 0 } ?: continue
                                    val number = video.optInt("episode", video.optInt("number"))
                                        .takeIf { it > 0 } ?: continue
                                    val absolute = video.optInt("absoluteEpisode")
                                        .takeIf { it > 0 } ?: continue
                                    put(season to number, absolute)
                                }
                            }
                        }
                    } ?: emptyMap()
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { emptyMap() }
                if (!ElevateMetadataCacheDiagnostics.enabled()) synchronized(imdbKitsuCache) {
                    imdbKitsuCache[imdb] = System.currentTimeMillis() to result
                }
                result
            }.also { task ->
                imdbKitsuFlights[imdb] = task
                task.invokeOnCompletion {
                    synchronized(imdbKitsuCache) {
                        if (imdbKitsuFlights[imdb] === task) imdbKitsuFlights.remove(imdb)
                    }
                }
            }
        }
        flight.start()
        return flight.await()
    }

    private suspend fun resolveByField(
        field: String, id: String, season: Int?, allowTrackerOnly: Boolean = false,
        trace: String? = null, allowSplitSeasons: Boolean = false
    ): Mapping? {
        val key = field + ":" + id + (season?.let { ":$it" } ?: "") +
            (if (allowTrackerOnly) ":watch-history" else "") +
            (if (allowSplitSeasons) ":episode-mapping" else "")
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.takeIf {
                !ElevateMetadataCacheDiagnostics.enabled() && now - it.first < if (it.second == null) 300_000L else 86_400_000L
            }?.let {

                return it.second
            }
        }
        val flight = synchronized(cache) {
            cache[key]?.takeIf {
                !ElevateMetadataCacheDiagnostics.enabled() &&
                    System.currentTimeMillis() - it.first in 0 until
                    if (it.second == null) 300_000L else 86_400_000L
            }?.let {

                return it.second
            }
            flights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                val found = try {
            withTimeoutOrNull(15_000L) {
                limiter.withPermit {
                    var result: Mapping? = null
                    for (host in hosts) {
                        val url = "https://" + host + "/api/v1/anime?idType=" + field +
                            "&idValue=" + id + (season?.let { "&season=$it" } ?: "")

                        val requestedAt = android.os.SystemClock.elapsedRealtime()
                        val root = try {
                            JSONObject(app.get(url,
                                headers = ElevateMetadataCacheDiagnostics.requestHeaders(),
                                timeout = 5L).text)
                        } catch (e: CancellationException) { throw e }
                          catch (ex: Exception) {
                              if (allowSplitSeasons) android.util.Log.i("MetaEpisodeMap",
                                  "phase=AIO_RETRY season=$season host=$host reason=HTTP_OR_PARSE_ERROR elapsedMs=${android.os.SystemClock.elapsedRealtime() - requestedAt}")
                              continue
                          }
                        if (!root.optBoolean("success") ||
                            root.optString("detail") != "OK") {
                            if (allowSplitSeasons) android.util.Log.i("MetaEpisodeMap",
                                "phase=AIO_RETRY season=$season host=$host reason=API_UNSUCCESSFUL elapsedMs=${android.os.SystemClock.elapsedRealtime() - requestedAt}")
                            continue
                        }
                        val data = root.optJSONObject("data")
                        if (data == null) {

                            continue
                        }
                        val ids = data.optJSONObject("mappings")
                        if (ids == null || ids.length() == 0) {
                            continue
                        }
                        // An OK response ends host retries; mismatched IDs cannot be used.
                        if (ids.optString(field) != id) {
                            if (allowSplitSeasons) android.util.Log.i("MetaEpisodeMap",
                                "phase=AIO_ACCEPT_UNUSABLE season=$season host=$host reason=ID_MISMATCH")
                            break
                        }
                        val ranges = data.optJSONArray("episodeMappings")?.let { entries ->
                            (0 until entries.length()).mapNotNull { index ->
                                val row = entries.optJSONObject(index) ?: return@mapNotNull null
                                val tmdbSeason = row.optInt("tmdbSeason").takeIf { it > 0 }
                                    ?: return@mapNotNull null
                                val start = row.optInt("start").takeIf { it > 0 }
                                    ?: return@mapNotNull null
                                val end = row.optInt("end").takeIf { it >= start }
                                TmdbRange(tmdbSeason, start, end)
                            }
                        }.orEmpty()
                        val mappedSeason = if (field == "imdbId" && season != null && season > 1)
                            ElevateWatchHistoryRules.preferredSeason(
                                listOf("imdb", "tvdb", "trakt", "tmdb").map { source ->
                                    data.optJSONObject(source)?.optInt("seasonNumber")
                                        ?.takeIf { it > 0 }
                                }) else null
                        val seasonMismatch = field == "imdbId" && season != null &&
                            season > 1 && mappedSeason != season
                        if (seasonMismatch) {
                            if (!allowSplitSeasons) break
                            android.util.Log.i("MetaEpisodeMap",
                                "phase=AIO_SEASON_UNRESOLVED season=$season mappedSeason=$mappedSeason ranges=${ranges.size} host=$host")
                        }
                        fun positive(name: String): String? = ids.optString(name)
                            .toLongOrNull()?.takeIf { it > 0L }?.toString()
                        val kitsu = positive("kitsuId")
                        val mal = positive("malId")
                        val anilist = positive("anilistId")
                        val hasAnimeIds = kitsu != null || mal != null || anilist != null
                        val hasTrackingIds = positive("simklId") != null ||
                            positive("thetvdbId") != null
                        if (!hasAnimeIds && !(allowTrackerOnly && hasTrackingIds)) {
                            if (allowSplitSeasons) android.util.Log.i("MetaEpisodeMap",
                                "phase=AIO_ACCEPT_UNUSABLE season=$season host=$host reason=NO_ANIME_IDS")
                            break
                        }
                        val episodeInfo = listOf("imdb", "tvdb", "trakt", "tmdb")
                            .mapNotNull(data::optJSONObject).firstOrNull {
                                season == null || it.optInt("seasonNumber") == season
                            }

                        result = Mapping(
                            kitsu, mal, anilist,
                            ids.optString("imdbId").takeIf { it.matches(Regex("tt[0-9]+")) },
                            positive("themoviedbId"),
                            episodeInfo?.optInt("fromEpisode")?.takeIf { it > 0 },
                            positive("simklId"), positive("thetvdbId"),
                            data.optJSONObject("tvdb")?.optInt("seasonNumber")?.takeIf { it > 0 },
                            data.optJSONObject("tmdb")?.optInt("seasonNumber")?.takeIf {
                                it > 0 && !seasonMismatch
                            },
                            data.optJSONObject("trakt")?.optInt("seasonNumber")?.takeIf { it > 0 },
                            data.optJSONObject("imdb")?.optInt("seasonNumber")?.takeIf { it > 0 },
                            data.optJSONObject("tmdb")?.optInt("fromEpisode")?.takeIf {
                                it > 0 && !seasonMismatch
                            },
                            ranges
                        )
                        if (allowSplitSeasons) android.util.Log.i("MetaEpisodeMap",
                            "phase=AIO_ACCEPT season=$season host=$host ranges=${ranges.size} kitsu=$kitsu elapsedMs=${android.os.SystemClock.elapsedRealtime() - requestedAt}")
                        break
                    }
                    result
                }
            }
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { null }
                if (!ElevateMetadataCacheDiagnostics.enabled()) synchronized(cache) {
                    cache[key] = System.currentTimeMillis() to found
                }
                found
            }.also { task ->
                flights[key] = task
                task.invokeOnCompletion {
                    synchronized(cache) { if (flights[key] === task) flights.remove(key) }
                }
            }
        }

        flight.start()
        val result = flight.await()

        return result
    }
}
