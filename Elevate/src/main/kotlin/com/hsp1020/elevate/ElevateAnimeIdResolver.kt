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
    internal data class TvdbRange(val season: Int, val start: Int, val end: Int?, val offset: Int)
    internal data class Mapping(
        val kitsu: String?, val mal: String?, val anilist: String?,
        val imdb: String?, val tmdb: String?, val fromEpisode: Int? = null,
        val simkl: String? = null, val tvdb: String? = null,
        val tvdbSeason: Int? = null, val tmdbSeason: Int? = null,
        val traktSeason: Int? = null,
        val imdbSeason: Int? = null, val tmdbFromEpisode: Int? = null,
        val tmdbRanges: List<TmdbRange> = emptyList(),
        val tvdbFromEpisode: Int? = null, val tvdbAbsoluteOrder: Boolean? = null,
        val tvdbRanges: List<TvdbRange> = emptyList()
    ) {
        fun matchesSeason(season: Int): Boolean =
            ElevateWatchHistoryRules.consistentSeason(season,
                listOf(imdbSeason, tvdbSeason, tmdbSeason, traktSeason))
    }
    internal data class VerifiedEpisode(val mapping: Mapping, val localEpisode: Int,
        val via: String)
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
    private val imdbKitsuCache =
        object : LinkedHashMap<String, Pair<Long, Map<Pair<Int, Int>, Int>>>(24, .75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Pair<Long, Map<Pair<Int, Int>, Int>>>
            ) = size > 48
        }
    private val verifiedCache = object : LinkedHashMap<String, Pair<Long, VerifiedEpisode?>>(48, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, VerifiedEpisode?>>) = size > 96
    }
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

    /** Resolve a specific TVDB episode without confusing split anime cours. */
    suspend fun resolveTvdbEpisode(rawId: String, season: Int, episode: Int): Mapping? {
        val id = rawId.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: return null
        if (season <= 0 || episode <= 0) return null
        return resolveByField("thetvdbId", id, season,
            allowTrackerOnly = true, episode = episode)?.takeIf {
            it.tvdb == id && it.tvdbSeason == season &&
                it.tvdbAbsoluteOrder == false && it.kitsu != null &&
                (it.tvdbFromEpisode == null || episode >= it.tvdbFromEpisode)
        }
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

    /** Verified episode-local identity. Never turn an unverified title-wide ID into a season ID. */
    suspend fun verifiedEpisode(imdb: String?, season: Int?, episode: Int,
        original: String? = null, feature: String = "UNKNOWN"): VerifiedEpisode? {
        val source = original?.let { Regex("^(kitsu|mal|anilist):([0-9]+)$").matchEntire(it) }
        val key = listOf(imdb, season, episode, original).joinToString("|")
        val now = System.currentTimeMillis()
        if (episode <= 0) return null
        synchronized(verifiedCache) {
            verifiedCache[key]?.takeIf { now - it.first < if (it.second == null) 120_000L else 3_600_000L }
                ?.let {
                    audit(feature, "CACHE", "S$season E$episode result=${it.second?.via ?: "MISS"}")
                    return it.second
                }
        }
        audit(feature, "INPUT", "imdb=$imdb S$season E$episode original=$original")
        val resolved = withTimeoutOrNull(20_000L) {
            if (source != null) {
                val kind = source.groupValues[1]
                val id = source.groupValues[2]
                val mapped = resolve(kind, id)
                if (mapped != null && when (kind) {
                    "kitsu" -> mapped.kitsu == id
                    "mal" -> mapped.mal == id
                    else -> mapped.anilist == id
                }) VerifiedEpisode(mapped, episode, "ORIGINAL_${kind.uppercase()}")
                else null
            } else if (imdb != null && imdb.matches(Regex("tt[0-9]+")) &&
                season != null && season >= 0) {
                // The IMDb-form Kitsu metadata carries exact S/E -> Kitsu local E pairs.
                val exact = try {
                    val meta = JSONObject(app.get(
                        "https://anime-kitsu.strem.fun/meta/series/$imdb.json", timeout = 8L
                    ).text).optJSONObject("meta")
                    val videos = meta?.optJSONArray("videos")
                    (0 until (videos?.length() ?: 0)).mapNotNull { index ->
                        val v = videos?.optJSONObject(index) ?: return@mapNotNull null
                        if (v.optInt("season", -1) != season ||
                            v.optInt("episode", v.optInt("number", -1)) != episode) return@mapNotNull null
                        val id = v.optString("kitsu_id").toLongOrNull()?.takeIf { it > 0 }
                            ?.toString() ?: return@mapNotNull null
                        val local = v.optInt("kitsuEpisode").takeIf { it > 0 }
                            ?: return@mapNotNull null
                        id to local
                    }.distinct().singleOrNull()
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { null }
                audit(feature, "EXACT", "S$season E$episode status=${if (exact == null) "MISS_OR_AMBIGUOUS" else "MATCH"} kitsu=${exact?.first} local=${exact?.second}")
                if (exact != null) {
                    val m = resolve("kitsu", exact.first)
                    if (m != null && m.kitsu == exact.first)
                        return@withTimeoutOrNull VerifiedEpisode(m, exact.second, "KITSU_IMDB")
                }
                if (season == 0) return@withTimeoutOrNull null
                val mapped = resolveByField("imdbId", imdb, season, allowSplitSeasons = true)
                val rangeCandidates = mapped?.let { candidate ->
                    candidate.tvdbRanges.mapNotNull { r ->
                        val absolute = episode.toLong() - r.offset
                        if (r.season != season || absolute < r.start ||
                            r.end != null && absolute > r.end ||
                            absolute !in 1L..Int.MAX_VALUE.toLong()) null
                        else (candidate.tvdbFromEpisode?.let { episode - it + 1 }
                            ?: absolute.toInt()).takeIf { it > 0 }
                    }
                }?.distinct().orEmpty()
                if (rangeCandidates.size == 1 && mapped != null && mapped.kitsu != null &&
                    kitsuEpisodeExists(mapped.kitsu, rangeCandidates.single()))
                    return@withTimeoutOrNull VerifiedEpisode(mapped, rangeCandidates.single(), "AIO_TVDB_RANGE")
                val tvdbId = mapped?.tvdb
                audit(feature, "TVDB_INPUT", "S$season E$episode tvdb=$tvdbId ranges=${rangeCandidates.size}")
                if (tvdbId != null) {
                    val tvdb = resolveTvdbEpisode(tvdbId, season, episode)
                    val local = if (tvdb != null && tvdb.tvdb == tvdbId &&
                        tvdb.tvdbSeason == season && tvdb.tvdbAbsoluteOrder == false)
                        (episode - (tvdb.tvdbFromEpisode ?: 1) + 1).takeIf { it > 0 } else null
                    audit(feature, "TVDB_VERIFY", "wanted=$tvdbId:S$season E$episode actual=${tvdb?.tvdb}:S${tvdb?.tvdbSeason} local=$local")
                    if (local != null && tvdb != null && tvdb.kitsu != null &&
                        kitsuEpisodeExists(tvdb.kitsu, local)) {
                        val checked = resolve("kitsu", tvdb.kitsu)
                        if (checked != null && checked.tvdb == tvdbId &&
                            checked.tvdbSeason == season)
                            return@withTimeoutOrNull VerifiedEpisode(checked, local, "AIO_TVDB")
                    }
                }
                if (tvdbId != null && mapped != null && mapped.kitsu != null) {
                    franchiseEpisode(mapped.kitsu, tvdbId, mapped.tvdbSeason, season, episode, feature)
                } else null
            } else null
        }
        synchronized(verifiedCache) { verifiedCache[key] = System.currentTimeMillis() to resolved }
        audit(feature, "RESULT", "status=${if (resolved == null) "UNVERIFIED" else "MATCH"} via=${resolved?.via} kitsu=${resolved?.mapping?.kitsu} mal=${resolved?.mapping?.mal} anilist=${resolved?.mapping?.anilist} local=${resolved?.localEpisode}")
        return resolved
    }

    private fun audit(feature: String, phase: String, message: String) {
        android.util.Log.i("ElevateSeasonAudit", "feature=$feature phase=$phase $message")
    }

    private suspend fun kitsuMeta(kitsu: String): JSONObject? = try {
        JSONObject(app.get("https://anime-kitsu.strem.fun/meta/series/kitsu:$kitsu.json",
            timeout = 8L).text).optJSONObject("meta")?.takeIf {
            it.optString("id") == "kitsu:$kitsu" || it.optString("kitsu_id") == kitsu
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    private fun hasLocal(meta: JSONObject, kitsu: String, local: Int): Boolean {
        val videos = meta.optJSONArray("videos") ?: return false
        return (0 until videos.length()).count { index ->
            val v = videos.optJSONObject(index)
            v != null && v.optString("id") == "kitsu:$kitsu:$local" &&
                v.optInt("episode", v.optInt("number")) == local &&
                v.optInt("season", 1) > 0
        } == 1
    }

    private suspend fun kitsuEpisodeExists(kitsu: String, local: Int): Boolean =
        kitsuMeta(kitsu)?.let { hasLocal(it, kitsu, local) } == true

    private suspend fun franchiseEpisode(seed: String, tvdb: String, seedSeason: Int?,
        season: Int, episode: Int, feature: String): VerifiedEpisode? {
        val preferred = when {
            seedSeason != null && season > seedSeason -> "Sequel"
            seedSeason != null && season < seedSeason -> "Prequel"
            else -> null
        }
        val visited = mutableSetOf(seed)
        var frontier = listOf(seed)
        for (depth in 0..3) {
            val links = mutableListOf<Pair<String, String>>()
            val matches = mutableListOf<VerifiedEpisode>()
            for (id in frontier) {
                val meta = kitsuMeta(id) ?: continue
                if (depth > 0) {
                    val candidate = resolve("kitsu", id)
                    val local = if (candidate != null && candidate.kitsu == id &&
                        candidate.tvdb == tvdb && candidate.tvdbSeason == season &&
                        candidate.tvdbAbsoluteOrder == false)
                        (episode - (candidate.tvdbFromEpisode ?: 1) + 1).takeIf { it > 0 }
                        else null
                    if (local != null && hasLocal(meta, id, local))
                        matches.add(VerifiedEpisode(candidate!!, local, "KITSU_FRANCHISE"))
                    audit(feature, "FRANCHISE_CHECK", "depth=$depth kitsu=$id tvdb=${candidate?.tvdb} season=${candidate?.tvdbSeason} local=$local valid=${local != null && hasLocal(meta, id, local)}")
                }
                if (depth == 3) continue
                val array = meta.optJSONArray("links") ?: continue
                for (index in 0 until array.length()) {
                    val link = array.optJSONObject(index) ?: continue
                    if (!link.optString("category").equals("Franchise", true)) continue
                    val relation = when {
                        link.optString("name").startsWith("Sequel:", true) -> "Sequel"
                        link.optString("name").startsWith("Prequel:", true) -> "Prequel"
                        else -> continue
                    }
                    val next = link.optString("url").removePrefix("stremio:///detail/series/kitsu:")
                    if (!link.optString("url").startsWith("stremio:///detail/series/kitsu:") ||
                        next.toLongOrNull()?.let { it > 0 } != true) continue
                    links.add(next to relation)
                }
            }
            if (matches.size > 1) {
                audit(feature, "FRANCHISE_RESULT", "status=AMBIGUOUS depth=$depth count=${matches.size}")
                return null
            }
            if (matches.size == 1) return matches.single().also {
                audit(feature, "FRANCHISE_RESULT", "status=MATCH depth=$depth kitsu=${it.mapping.kitsu} local=${it.localEpisode}")
            }
            if (depth == 3) break
            val next = mutableListOf<String>()
            for ((id, _) in (if (preferred == null) links else
                links.sortedBy { if (it.second == preferred) 0 else 1 })) {
                if (id in visited) continue
                if (visited.size >= 12) {
                    audit(feature, "FRANCHISE_RESULT", "status=LIMIT visited=${visited.size}")
                    return null
                }
                visited.add(id)
                next.add(id)
            }
            if (next.isEmpty()) break
            frontier = next
        }
        audit(feature, "FRANCHISE_RESULT", "status=MISS visited=${visited.size}")
        return null
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
                 now - it.first < if (it.second.isEmpty()) 300_000L else 86_400_000L
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
                                id + ".json", 
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

        return result
    }

    /** IMDb-form Kitsu metadata preserves the addon season/episode alongside absoluteEpisode. */
    suspend fun kitsuImdbAbsolutes(imdb: String): Map<Pair<Int, Int>, Int> {
        if (!imdb.matches(Regex("tt[0-9]+"))) return emptyMap()
        val now = System.currentTimeMillis()
        synchronized(imdbKitsuCache) {
            imdbKitsuCache[imdb]?.takeIf {
                
                    now - it.first < if (it.second.isEmpty()) 300_000L else 86_400_000L
            }?.let { return it.second }
        }
        val flight = synchronized(imdbKitsuCache) {
            imdbKitsuFlights[imdb] ?: scope.async(start = CoroutineStart.LAZY) {
                val result = try {
                    withTimeoutOrNull(10_000L) {
                        limiter.withPermit {
                            val raw = app.get("https://anime-kitsu.strem.fun/meta/series/$imdb.json",
                                
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
                synchronized(imdbKitsuCache) {
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
        trace: String? = null, allowSplitSeasons: Boolean = false, episode: Int? = null
    ): Mapping? {
        val key = field + ":" + id + (season?.let { ":$it" } ?: "") +
            (episode?.let { ":episode=$it" } ?: "") +
            (if (allowTrackerOnly) ":watch-history" else "") +
            (if (allowSplitSeasons) ":episode-mapping" else "")
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.takeIf {
                 now - it.first < if (it.second == null) 300_000L else 86_400_000L
            }?.let {

                return it.second
            }
        }
        val flight = synchronized(cache) {
            cache[key]?.takeIf {
                
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
                            "&idValue=" + id + (season?.let { "&season=$it" } ?: "") +
                            (episode?.let { "&episode=$it" } ?: "")

                        val requestedAt = android.os.SystemClock.elapsedRealtime()
                        val root = try {
                            JSONObject(app.get(url,
                                
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
                        val tvdbRanges = data.optJSONArray("episodeMappings")?.let { entries ->
                            (0 until entries.length()).mapNotNull { index ->
                                val row = entries.optJSONObject(index) ?: return@mapNotNull null
                                val seasonNumber = row.optInt("tvdbSeason").takeIf { it > 0 }
                                    ?: return@mapNotNull null
                                val start = row.optInt("start").takeIf { it > 0 }
                                    ?: return@mapNotNull null
                                val end = row.optInt("end").takeIf { it >= start }
                                val offset = row.optInt("offset").takeIf { row.has("offset") &&
                                    !row.isNull("offset") } ?: return@mapNotNull null
                                TvdbRange(seasonNumber, start, end, offset)
                            }
                        }.orEmpty()
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
                            ranges,
                            data.optJSONObject("tvdb")?.optInt("fromEpisode")?.takeIf { it > 0 },
                            data.optJSONObject("tvdb")?.let { tvdb ->
                                if (tvdb.has("absoluteOrder") && !tvdb.isNull("absoluteOrder"))
                                    tvdb.optBoolean("absoluteOrder") else null
                            },
                            tvdbRanges
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
                synchronized(cache) {
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
