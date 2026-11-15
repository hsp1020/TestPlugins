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
    internal data class Mapping(
        val kitsu: String?, val mal: String?, val anilist: String?,
        val imdb: String?, val tmdb: String?, val fromEpisode: Int? = null,
        val simkl: String? = null, val tvdb: String? = null,
        val tvdbSeason: Int? = null, val tmdbSeason: Int? = null,
        val traktSeason: Int? = null,
        val imdbSeason: Int? = null, val tmdbFromEpisode: Int? = null
    ) {
        fun matchesSeason(season: Int): Boolean =
            ElevateWatchHistoryRules.consistentSeason(season,
                listOf(imdbSeason, tvdbSeason, tmdbSeason, traktSeason))
    }
    internal data class EpisodeMapping(val mal: String?, val anilist: String?, val episode: Int,
        val kitsu: String? = null, val season: Int? = null)

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
        return resolveByField("imdbId", id, season)
    }

    /** Same AIOStreams flight/cache as title and season mapping, with episode offset retained. */
    suspend fun resolveEpisode(
        imdb: String?, mal: String?, anilist: String?, kitsu: String?,
        season: Int?, episode: Int, trace: String? = null
    ): EpisodeMapping? {
        if (episode <= 0) {
            if (trace != null) ElevateSkipTrace.log(trace, "AIO_RESOLVE_SKIP",
                "reason=invalidEpisode inputEpisode=$episode")
            return null
        }
        val (field, raw) = when {
            imdb != null -> "imdbId" to imdb
            mal != null -> "malId" to mal
            anilist != null -> "anilistId" to anilist
            kitsu != null -> "kitsuId" to kitsu
            else -> {
                if (trace != null) ElevateSkipTrace.log(trace, "AIO_RESOLVE_SKIP",
                    "reason=noIds inputSeason=$season inputEpisode=$episode")
                return null
            }
        }
        val id = if (field == "imdbId")
            raw.takeIf { it.matches(Regex("tt[0-9]+")) }
        else raw.toLongOrNull()?.takeIf { it > 0L }?.toString()
        if (id == null) {
            if (trace != null) ElevateSkipTrace.log(trace, "AIO_RESOLVE_SKIP",
                "reason=invalidId selectedField=$field raw=$raw")
            return null
        }
        if (trace != null) ElevateSkipTrace.log(trace, "AIO_RESOLVE_REQUEST",
            "selectedField=$field selectedId=$id inputSeason=$season inputEpisode=$episode " +
                "requestSeason=${if (field == "imdbId") season else null}")
        val mapped = resolveByField(field, id, if (field == "imdbId") season else null, trace = trace)
        if (mapped == null) {
            if (trace != null) ElevateSkipTrace.log(trace, "AIO_RESOLVE_RESULT",
                "mapping=null selectedField=$field id=$id")
            return null
        }
        val local = if (field == "imdbId" && mapped.fromEpisode != null)
            (episode - mapped.fromEpisode + 1).takeIf { it > 0 }
        else episode
        if (local == null) {
            if (trace != null) ElevateSkipTrace.log(trace, "AIO_RESOLVE_RESULT",
                "invalidLocalEpisode=true inputEpisode=$episode fromEpisode=${mapped.fromEpisode}")
            return null
        }
        val result = EpisodeMapping(mapped.mal, mapped.anilist, local, mapped.kitsu,
            mapped.imdbSeason ?: mapped.tvdbSeason ?: mapped.tmdbSeason)
        if (trace != null) ElevateSkipTrace.log(trace, "AIO_RESOLVE_RESULT",
            "mapping=$mapped selectedLocalEpisode=$local localEpisodeSource=" +
                "${if (field == "imdbId" && mapped.fromEpisode != null) "AIOStreams.fromEpisode" else "inputEpisode"} " +
                "chosenSeason=${result.season} chosenSeasonSource=imdb>tvdb>tmdb result=$result")
        return result
    }

    /** Kitsu Stremio metadata exposes per-episode absoluteEpisode; never infer offsets. */
    suspend fun kitsuAbsoluteEpisodes(
        rawId: String?, trace: String? = null
    ): Map<Int, Int> {
        val id = rawId?.removePrefix("kitsu:")?.substringBefore(':')
            ?.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: run {
                if (trace != null) ElevateSkipTrace.log(trace, "KITSU_ABSOLUTE_SKIP",
                    "reason=missingOrInvalidKitsuId rawId=$rawId")
                return emptyMap()
            }
        val now = System.currentTimeMillis()
        synchronized(kitsuCache) {
            kitsuCache[id]?.takeIf {
                now - it.first < if (it.second.isEmpty()) 300_000L else 86_400_000L
            }?.let {
                if (trace != null) ElevateSkipTrace.log(trace, "KITSU_ABSOLUTE_CACHE",
                    "id=$id hit=true count=${it.second.size}")
                return it.second
            }
        }
        if (trace != null) ElevateSkipTrace.log(trace, "KITSU_ABSOLUTE_CACHE",
            "id=$id hit=false requesting=true")
        val flight = synchronized(kitsuCache) {
            kitsuFlights[id] ?: scope.async(start = CoroutineStart.LAZY) {
                val result = try {
                    withTimeoutOrNull(10_000L) {
                        limiter.withPermit {
                            val text = app.get("https://anime-kitsu.strem.fun/meta/series/kitsu:" +
                                id + ".json", timeout = 8L).text
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
                        if (trace != null) ElevateSkipTrace.log(trace, "KITSU_FETCH_TIMEOUT",
                            "id=$id limitMs=10000")
                        emptyMap()
                    }
                } catch (e: CancellationException) { throw e }
                  catch (ex: Exception) {
                      if (trace != null) ElevateSkipTrace.log(trace, "KITSU_FETCH_ERROR",
                          "id=$id exception=${ex.javaClass.simpleName} message=${ex.message?.take(160)}")
                      emptyMap()
                  }
                synchronized(kitsuCache) {
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
        if (trace != null) ElevateSkipTrace.log(trace, "KITSU_ABSOLUTE_RESULT",
            "id=$id count=${result.size} episodes=${result.entries.joinToString(limit = 30)}")
        return result
    }

    private suspend fun resolveByField(
        field: String, id: String, season: Int?, allowTrackerOnly: Boolean = false,
        trace: String? = null
    ): Mapping? {
        val key = field + ":" + id + (season?.let { ":$it" } ?: "") +
            (if (allowTrackerOnly) ":watch-history" else "")
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.takeIf {
                now - it.first < if (it.second == null) 300_000L else 86_400_000L
            }?.let {
                if (trace != null) ElevateSkipTrace.log(trace, "AIO_RESOLVE_CACHE",
                    "key=$key hit=true mapping=${it.second}")
                return it.second
            }
        }
        val flight = synchronized(cache) {
            cache[key]?.takeIf {
                System.currentTimeMillis() - it.first in 0 until
                    if (it.second == null) 300_000L else 86_400_000L
            }?.let {
                if (trace != null) ElevateSkipTrace.log(trace, "AIO_RESOLVE_CACHE",
                    "key=$key hit=true secondCheck=true mapping=${it.second}")
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
                        if (trace != null) ElevateSkipTrace.log(trace, "AIO_HOST_REQUEST",
                            "host=$host field=$field id=$id season=$season")
                        val root = try {
                            JSONObject(app.get(url, timeout = 5L).text)
                        } catch (e: CancellationException) { throw e }
                          catch (ex: Exception) {
                              if (trace != null) ElevateSkipTrace.log(trace, "AIO_HOST_ERROR",
                                  "host=$host exception=${ex.javaClass.simpleName}")
                              continue
                          }
                        if (!root.optBoolean("success")) {
                            if (trace != null) ElevateSkipTrace.log(trace, "AIO_HOST_REJECT",
                                "host=$host reason=successFalse")
                            continue
                        }
                        val data = root.optJSONObject("data")
                        if (data == null) {
                            if (trace != null) ElevateSkipTrace.log(trace, "AIO_HOST_REJECT",
                                "host=$host reason=missingData")
                            continue
                        }
                        val ids = data.optJSONObject("mappings")
                        if (ids == null) {
                            if (trace != null) ElevateSkipTrace.log(trace, "AIO_HOST_REJECT",
                                "host=$host reason=missingMappings")
                            continue
                        }
                        // Match the exact IMDb identity and season validation in StremioC.
                        if (ids.optString(field) != id) {
                            if (trace != null) ElevateSkipTrace.log(trace, "AIO_HOST_REJECT",
                                "host=$host reason=identityMismatch got=${ids.optString(field)}")
                            continue
                        }
                        if (field == "imdbId" && season != null && season > 1) {
                            val mappedSeason = ElevateWatchHistoryRules.preferredSeason(
                                listOf("imdb", "tvdb", "trakt", "tmdb").map { source ->
                                    data.optJSONObject(source)?.optInt("seasonNumber")
                                        ?.takeIf { it > 0 }
                                })
                            if (mappedSeason != season) {
                                if (trace != null) ElevateSkipTrace.log(trace, "AIO_HOST_REJECT",
                                    "host=$host reason=seasonMismatch requested=$season got=$mappedSeason")
                                continue
                            }
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
                            if (trace != null) ElevateSkipTrace.log(trace, "AIO_HOST_REJECT",
                                "host=$host reason=noAnimeIds")
                            continue
                        }
                        val episodeInfo = listOf("imdb", "tvdb", "trakt", "tmdb")
                            .mapNotNull(data::optJSONObject).firstOrNull {
                                season == null || it.optInt("seasonNumber") == season
                            }
                        if (trace != null) ElevateSkipTrace.log(trace, "AIO_HOST_MATCH",
                            "host=$host requestSeason=$season kitsu=$kitsu mal=$mal anilist=$anilist " +
                                "selectedFromEpisode=${episodeInfo?.optInt("fromEpisode")} " +
                                "imdbSeason=${data.optJSONObject("imdb")?.optInt("seasonNumber")} " +
                                "imdbFromEpisode=${data.optJSONObject("imdb")?.optInt("fromEpisode")} " +
                                "tvdbSeason=${data.optJSONObject("tvdb")?.optInt("seasonNumber")} " +
                                "tvdbFromEpisode=${data.optJSONObject("tvdb")?.optInt("fromEpisode")} " +
                                "tmdbSeason=${data.optJSONObject("tmdb")?.optInt("seasonNumber")} " +
                                "tmdbFromEpisode=${data.optJSONObject("tmdb")?.optInt("fromEpisode")}")
                        result = Mapping(
                            kitsu, mal, anilist,
                            ids.optString("imdbId").takeIf { it.matches(Regex("tt[0-9]+")) },
                            positive("themoviedbId"),
                            episodeInfo?.optInt("fromEpisode")?.takeIf { it > 0 },
                            positive("simklId"), positive("thetvdbId"),
                            data.optJSONObject("tvdb")?.optInt("seasonNumber")?.takeIf { it > 0 },
                            data.optJSONObject("tmdb")?.optInt("seasonNumber")?.takeIf { it > 0 },
                            data.optJSONObject("trakt")?.optInt("seasonNumber")?.takeIf { it > 0 },
                            data.optJSONObject("imdb")?.optInt("seasonNumber")?.takeIf { it > 0 },
                            data.optJSONObject("tmdb")?.optInt("fromEpisode")?.takeIf { it > 0 }
                        )
                        break
                    }
                    result
                }
            }
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { null }
                synchronized(cache) { cache[key] = System.currentTimeMillis() to found }
                found
            }.also { task ->
                flights[key] = task
                task.invokeOnCompletion {
                    synchronized(cache) { if (flights[key] === task) flights.remove(key) }
                }
            }
        }
        if (trace != null) ElevateSkipTrace.log(trace, "AIO_RESOLVE_CACHE",
            "key=$key hit=false inflightOrNetwork=true")
        flight.start()
        val result = flight.await()
        if (trace != null) ElevateSkipTrace.log(trace, "AIO_RESOLVE_DONE",
            "key=$key mapping=$result")
        return result
    }
}
