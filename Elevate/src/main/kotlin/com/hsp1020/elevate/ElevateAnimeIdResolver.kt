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
        val imdbSeason: Int? = null
    ) {
        fun matchesSeason(season: Int): Boolean =
            ElevateWatchHistoryRules.consistentSeason(season,
                listOf(imdbSeason, tvdbSeason, tmdbSeason, traktSeason))
    }
    internal data class EpisodeMapping(val mal: String?, val anilist: String?, val episode: Int)

    private val hosts = listOf(
        "aiostreams.elfhosted.com", "aiostreams.fortheweak.cloud",
        "aiostreamsfortheweebsstable.midnightignite.me",
        "aio.atbphosting.com", "aiostreams.stremio.ru", "aiostreams.viren070.me"
    )
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
        season: Int?, episode: Int
    ): EpisodeMapping? {
        if (episode <= 0) return null
        val (field, raw) = when {
            imdb != null -> "imdbId" to imdb
            mal != null -> "malId" to mal
            anilist != null -> "anilistId" to anilist
            kitsu != null -> "kitsuId" to kitsu
            else -> return null
        }
        val id = if (field == "imdbId")
            raw.takeIf { it.matches(Regex("tt[0-9]+")) } ?: return null
        else raw.toLongOrNull()?.takeIf { it > 0L }?.toString() ?: return null
        val mapped = resolveByField(field, id, if (field == "imdbId") season else null)
            ?: return null
        val local = if (field == "imdbId" && mapped.fromEpisode != null)
            (episode - mapped.fromEpisode + 1).takeIf { it > 0 } ?: return null
        else episode
        return EpisodeMapping(mapped.mal, mapped.anilist, local)
    }

    private suspend fun resolveByField(
        field: String, id: String, season: Int?, allowTrackerOnly: Boolean = false
    ): Mapping? {
        val key = field + ":" + id + (season?.let { ":$it" } ?: "") +
            (if (allowTrackerOnly) ":watch-history" else "")
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.takeIf {
                now - it.first < if (it.second == null) 300_000L else 86_400_000L
            }?.let { return it.second }
        }
        val flight = synchronized(cache) {
            cache[key]?.takeIf {
                System.currentTimeMillis() - it.first in 0 until
                    if (it.second == null) 300_000L else 86_400_000L
            }?.let { return it.second }
            flights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                val found = try {
            withTimeoutOrNull(15_000L) {
                limiter.withPermit {
                    var result: Mapping? = null
                    for (host in hosts) {
                        val url = "https://" + host + "/api/v1/anime?idType=" + field +
                            "&idValue=" + id + (season?.let { "&season=$it" } ?: "")
                        val root = try {
                            JSONObject(app.get(url, timeout = 5L).text)
                        } catch (e: CancellationException) { throw e }
                          catch (_: Exception) { continue }
                        if (!root.optBoolean("success")) continue
                        val data = root.optJSONObject("data") ?: continue
                        val ids = data.optJSONObject("mappings") ?: continue
                        // Match the exact IMDb identity and season validation in StremioC.
                        if (ids.optString(field) != id) continue
                        if (field == "imdbId" && season != null && season > 1) {
                            val mappedSeason = ElevateWatchHistoryRules.preferredSeason(
                                listOf("imdb", "tvdb", "trakt", "tmdb").map { source ->
                                    data.optJSONObject(source)?.optInt("seasonNumber")
                                        ?.takeIf { it > 0 }
                                })
                            if (mappedSeason != season) continue
                        }
                        fun positive(name: String): String? = ids.optString(name)
                            .toLongOrNull()?.takeIf { it > 0L }?.toString()
                        val kitsu = positive("kitsuId")
                        val mal = positive("malId")
                        val anilist = positive("anilistId")
                        val hasAnimeIds = kitsu != null || mal != null || anilist != null
                        val hasTrackingIds = positive("simklId") != null ||
                            positive("thetvdbId") != null
                        if (!hasAnimeIds && !(allowTrackerOnly && hasTrackingIds))
                            continue
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
                            data.optJSONObject("tmdb")?.optInt("seasonNumber")?.takeIf { it > 0 },
                            data.optJSONObject("trakt")?.optInt("seasonNumber")?.takeIf { it > 0 },
                            data.optJSONObject("imdb")?.optInt("seasonNumber")?.takeIf { it > 0 }
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
        flight.start()
        return flight.await()
    }
}
