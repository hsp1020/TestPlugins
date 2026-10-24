package com.hsp1020.elevate

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.animeSkipApi
import com.lagradost.cloudstream3.syncproviders.PlainAuthRepo
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.videoskip.SkipStamp
import com.lagradost.cloudstream3.utils.videoskip.SkipType
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Independent from StremioC; all identifiers originate in CloudStream response metadata. */
internal object ElevateSkipRepository {
    private const val DAY = 86_400_000L
    private const val MISS = 900_000L
    private val imdbPattern = Regex("^tt[0-9]+$")
    private val hosts = listOf(
        "aiostreams.elfhosted.com", "aiostreams.fortheweak.cloud",
        "aiostreamsfortheweebsstable.midnightignite.me",
        "aio.atbphosting.com", "aiostreams.stremio.ru", "aiostreams.viren070.me"
    )

    internal data class Identity(
        val imdb: String?, val tmdb: String?, val mal: String?, val anilist: String?,
        val kitsu: String?, val season: Int?, val episode: Int, val anime: Boolean,
        val movie: Boolean, val duration: Long, val absoluteEpisode: Int?
    ) {
        val key: String
            get() = listOf(imdb, tmdb, mal, anilist, kitsu, season, episode, anime, movie, duration)
                .joinToString("|")
    }
    private data class Mapping(val mal: String?, val anilist: String?, val episode: Int)
    private data class Timed<T>(val data: T, val until: Long)
    private val mappingCache = ConcurrentHashMap<String, Timed<Mapping?>>()
    private val resultCache = ConcurrentHashMap<String, Timed<List<VideoSkipStamp>>>()

    private fun JSONObject.nonEmpty(key: String): String? =
        optString(key).trim().takeUnless { it.isEmpty() || it == "null" }
    private fun JSONObject.positive(key: String): Int? =
        optInt(key).takeIf { it > 0 }
    private fun JSONObject.node(key: String): JSONObject? = optJSONObject(key)
    private fun id(value: String?): String? = value?.takeIf { it.isNotBlank() && it != "null" }

    fun identity(response: LoadResponse, episode: ResultEpisode, duration: Long): Identity {
        val extra = runCatching { JSONObject(episode.data) }.getOrNull()
        val imdb = id(response.getImdbId()) ?: id(extra?.nonEmpty("imdbId"))
            ?: response.url.substringBefore(':').takeIf { imdbPattern.matches(it) }
        val tmdb = id(response.getTMDbId()) ?: id(extra?.nonEmpty("tmdbId"))
        val original = extra?.nonEmpty("originalAnimeId")
        val mal = id(extra?.positive("malId")?.toString()) ?: response.getMalId()
            ?: original?.takeIf { it.startsWith("mal:") }?.substringAfter(':')?.substringBefore(':')
        val anilist = id(extra?.positive("anilistId")?.toString()) ?: response.getAniListId()
            ?: original?.takeIf { it.startsWith("anilist:") }?.substringAfter(':')?.substringBefore(':')
        val kitsu = id(extra?.nonEmpty("kitsuId")) ?: response.getKitsuId()
            ?: original?.takeIf { it.startsWith("kitsu:") }?.substringAfter(':')?.substringBefore(':')
        val tags = response.tags.orEmpty().map { it.lowercase() }
        val anime = response.type in setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie) ||
            episode.tvType in setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie) ||
            extra?.optBoolean("isAnime") == true ||
            tags.any { it == "anime" } ||
            (tags.any { it == "animation" } && tags.any { it == "japanese" }) ||
            mal != null || anilist != null || kitsu != null
        val movie = response.type in setOf(TvType.Movie, TvType.AnimeMovie) ||
            episode.tvType in setOf(TvType.Movie, TvType.AnimeMovie)
        return Identity(imdb, tmdb, mal, anilist, kitsu,
            episode.season ?: extra?.positive("imdbSeason"), episode.episode,
            anime, movie, duration, episode.totalEpisodeIndex)
    }

    private suspend fun request(url: String): JSONObject? =
        try { JSONObject(app.get(url, timeout = 7L).text) }
        catch (ex: CancellationException) { throw ex }
        catch (_: Exception) { null }

    private suspend fun mapped(i: Identity): Mapping? {
        if (!i.anime) return null
        val (kind, value) = when {
            i.imdb != null -> "imdbId" to i.imdb
            i.mal != null -> "malId" to i.mal
            i.anilist != null -> "anilistId" to i.anilist
            i.kitsu != null -> "kitsuId" to i.kitsu
            else -> return null
        }
        val query = "idType=" + kind + "&idValue=" + value +
            (if (kind == "imdbId" && i.season != null) "&season=" + i.season else "") +
            (if (kind == "imdbId") "&episode=" + i.episode else "")
        val now = System.currentTimeMillis()
        mappingCache[query]?.takeIf { it.until > now }?.let { return it.data }
        var result: Mapping? = null
        for (host in hosts) {
            val root = request("https://" + host + "/api/v1/anime?" + query) ?: continue
            if (!root.optBoolean("success")) continue
            val data = root.node("data") ?: break
            val ids = data.node("mappings") ?: continue
            if (ids.nonEmpty(kind) != value) continue
            val season = i.season
            if (kind == "imdbId" && season != null && season > 1) {
                val matches = listOf("imdb", "tvdb", "trakt", "tmdb").any {
                    data.node(it)?.positive("seasonNumber") == season
                }
                if (!matches) continue
            }
            val episodeMap = listOf("imdb", "tvdb", "trakt", "tmdb")
                .mapNotNull { data.node(it) }
                .firstOrNull { candidate ->
                    i.season == null || candidate.positive("seasonNumber") == i.season
                }
            val fromEpisode = episodeMap?.positive("fromEpisode")
            val local = if (kind == "imdbId" && fromEpisode != null) {
                (i.episode - fromEpisode + 1).takeIf { it > 0 }
            } else i.episode
            if (local == null) continue
            result = Mapping(ids.positive("malId")?.toString(),
                ids.positive("anilistId")?.toString(), local)
            if (result.mal != null || result.anilist != null) break
        }
        mappingCache[query] = Timed(result, now + if (result == null) MISS else DAY)
        return result
    }

    private fun category(type: SkipType): String = when (type) {
        SkipType.Opening, SkipType.Intro, SkipType.MixedOpening -> "opening"
        SkipType.Ending, SkipType.Credits, SkipType.MixedEnding -> "ending"
        SkipType.Recap -> "recap"
        SkipType.Preview -> "preview"
    }
    private fun valid(stamp: SkipStamp, duration: Long): Boolean =
        stamp.startMs >= 0 && stamp.endMs > stamp.startMs &&
            stamp.startMs < duration && stamp.endMs <= duration

    /** Nuvio: first segment per category, in provider priority order. */
    private fun choose(vararg groups: List<VideoSkipStamp>): List<VideoSkipStamp> {
        val result = LinkedHashMap<String, VideoSkipStamp>()
        for (group in groups) for (stamp in group) {
            result.putIfAbsent(category(stamp.timestamp.type), stamp)
        }
        return result.values.sortedBy { it.timestamp.startMs }
    }

    fun merge(elevate: List<VideoSkipStamp>, native: List<VideoSkipStamp>): List<VideoSkipStamp> =
        choose(elevate, native.filterNot { it.source.startsWith("Elevate/") })

    private fun stamps(source: String, intervals: List<SkipStamp>, i: Identity): List<VideoSkipStamp> =
        intervals.filter { valid(it, i.duration) }.map { stamp ->
            // A substantial tail after an ending may contain a post-credit scene.
            // Do not allow the native next-episode action to skip that tail.
            val trailing = i.duration - stamp.endMs
            VideoSkipStamp(stamp, !i.movie && trailing in 0L..5_000L,
                "Elevate/" + source)
        }

    private fun JSONObject.segment(name: String, type: SkipType): SkipStamp? {
        val obj = node(name) ?: return null
        val start = if (obj.has("start_ms") && !obj.isNull("start_ms")) obj.optLong("start_ms")
            else (obj.optDouble("start_sec", Double.NaN) * 1000).toLong()
        val end = if (obj.has("end_ms") && !obj.isNull("end_ms")) obj.optLong("end_ms")
            else (obj.optDouble("end_sec", Double.NaN) * 1000).toLong()
        return SkipStamp(type, start, end).takeIf { start >= 0 && end > start }
    }

    private suspend fun introDb(i: Identity): List<VideoSkipStamp> {
        val imdb = i.imdb ?: return emptyList()
        val args = if (i.movie) "&is_movie=true"
            else "&season=" + (i.season ?: return emptyList()) + "&episode=" + i.episode
        val root = request("https://api.introdb.app/segments?imdb_id=" + imdb + args)
            ?: return emptyList()
        val postCredits = if (i.movie) root.segment("post_credits", SkipType.Credits) else null
        val credits = root.segment("outro", if (i.movie) SkipType.Credits else SkipType.Ending)
            ?.let { seg ->
                if (postCredits != null && postCredits.startMs > seg.startMs) {
                    seg.copy(endMs = postCredits.startMs)
                } else seg
            }
        return stamps("IntroDb", listOfNotNull(
            if (i.movie) null else root.segment("intro", SkipType.Opening),
            if (i.movie) null else root.segment("recap", SkipType.Recap),
            credits
        ), i)
    }

    private suspend fun theIntroDB(i: Identity): List<VideoSkipStamp> {
        val idParam = i.tmdb?.let { "tmdb_id=" + it } ?: i.imdb?.let { "imdb_id=" + it }
            ?: return emptyList()
        val suffix = if (i.movie) "" else "&season=" + (i.season ?: return emptyList()) +
            "&episode=" + i.episode
        val root = request("https://api.theintrodb.org/v4/media?" + idParam + suffix)
            ?: return emptyList()
        val intervals = ArrayList<SkipStamp>()
        for ((key, type) in listOf("intro" to SkipType.Intro, "recap" to SkipType.Recap,
            "credits" to SkipType.Credits, "preview" to SkipType.Preview)) {
            val items = root.optJSONArray(key) ?: continue
            for (n in 0 until items.length()) {
                val stamp = items.optJSONObject(n) ?: continue
                intervals.add(SkipStamp(type, stamp.optLong("start_ms"),
                    if (stamp.isNull("end_ms")) i.duration else stamp.optLong("end_ms", i.duration)))
            }
        }
        return stamps("TheIntroDB", intervals, i)
    }

    private suspend fun aniSkip(i: Identity, mapped: Mapping?): List<VideoSkipStamp> {
        if (!i.anime) return emptyList()
        val mal = mapped?.mal ?: i.mal ?: return emptyList()
        val episode = mapped?.episode ?: i.episode
        if (episode <= 0) return emptyList()
        val url = "https://api.aniskip.com/v2/skip-times/" + mal + "/" + episode +
            "?types[]=op&types[]=ed&types[]=recap&types[]=mixed-op&types[]=mixed-ed&episodeLength=" +
            i.duration / 1000L
        val root = request(url) ?: return emptyList()
        if (!root.optBoolean("found")) return emptyList()
        val items = root.optJSONArray("results") ?: return emptyList()
        val intervals = ArrayList<SkipStamp>()
        for (n in 0 until items.length()) {
            val obj = items.optJSONObject(n) ?: continue
            val type = when (obj.optString("skipType")) {
                "op" -> SkipType.Opening
                "ed" -> SkipType.Ending
                "recap" -> SkipType.Recap
                "mixed-op" -> SkipType.MixedOpening
                "mixed-ed" -> SkipType.MixedEnding
                else -> continue
            }
            val part = obj.node("interval") ?: continue
            val start = (part.optDouble("startTime") * 1000.0).toLong()
            val end = (part.optDouble("endTime") * 1000.0).toLong()
            intervals.add(SkipStamp(type, start, end))
        }
        return stamps("AniSkip", intervals, i)
    }

    private suspend fun animeSkip(i: Identity, mapped: Mapping?): List<VideoSkipStamp> {
        if (!i.anime) return emptyList()
        val anilist = mapped?.anilist ?: i.anilist ?: return emptyList()
        val payload = try { PlainAuthRepo(animeSkipApi).authData()?.token?.payload }
            catch (ex: CancellationException) { throw ex }
            catch (_: Exception) { null }
        val clientId = payload?.let {
            runCatching { JSONObject(it).nonEmpty("clientId") }.getOrNull()
        } ?: return emptyList()
        val episode = mapped?.episode ?: i.episode
        suspend fun query(graphql: String): JSONObject? =
            try {
                JSONObject(app.post("https://api.anime-skip.com/graphql",
                    json = mapOf("query" to graphql),
                    headers = mapOf("X-Client-ID" to clientId, "Content-Type" to "application/json"),
                    timeout = 7L).text).node("data")
            } catch (ex: CancellationException) { throw ex }
              catch (_: Exception) { null }
        val shows = query("{ findShowsByExternalId(service: ANILIST, serviceId: \"" +
            anilist + "\") { id } }")?.optJSONArray("findShowsByExternalId") ?: return emptyList()
        for (s in 0 until shows.length()) {
            val showId = shows.optJSONObject(s)?.nonEmpty("id") ?: continue
            val episodes = query("{ findEpisodesByShowId(showId: \"" + showId +
                "\") { season number absoluteNumber timestamps { at type { name } } } }")
                ?.optJSONArray("findEpisodesByShowId") ?: continue
            for (n in 0 until episodes.length()) {
                val ep = episodes.optJSONObject(n) ?: continue
                if (ep.optString("number").toIntOrNull() != episode &&
                    ep.optString("absoluteNumber").toIntOrNull() != episode) continue
                val marks = ep.optJSONArray("timestamps") ?: continue
                val sorted = (0 until marks.length()).mapNotNull { marks.optJSONObject(it) }
                    .sortedBy { it.optDouble("at", Double.MAX_VALUE) }
                val segments = ArrayList<SkipStamp>()
                for (m in sorted.indices) {
                    val mark = sorted[m]
                    val type = when (mark.node("type")?.optString("name")?.lowercase()) {
                        "intro", "new intro" -> SkipType.Opening
                        "credits", "new credits" -> SkipType.Ending
                        "mixed credits" -> SkipType.MixedEnding
                        "mixed intro" -> SkipType.MixedOpening
                        "recap" -> SkipType.Recap
                        "preview" -> SkipType.Preview
                        else -> continue
                    }
                    val start = (mark.optDouble("at") * 1000.0).toLong()
                    val end = if (m + 1 < sorted.size) {
                        (sorted[m + 1].optDouble("at") * 1000.0).toLong()
                    } else i.duration
                    segments.add(SkipStamp(type, start, end))
                }
                val result = stamps("AnimeSkip", segments, i)
                if (result.isNotEmpty()) return result
            }
        }
        return emptyList()
    }

    suspend fun fetch(i: Identity): List<VideoSkipStamp> = withContext(Dispatchers.IO) {
        if (i.duration <= 0L || i.duration > 24L * 60L * 60L * 1000L) return@withContext emptyList()
        val now = System.currentTimeMillis()
        resultCache[i.key]?.takeIf { it.until > now }?.let { return@withContext it.data }
        val result = withTimeoutOrNull(17_000L) {
            coroutineScope {
                val mapped = async { mapped(i) }
                val intro = async { introDb(i) }
                val theIntro = async { theIntroDB(i) }
                val ani = async { aniSkip(i, mapped.await()) }
                val anime = async { animeSkip(i, mapped.await()) }
                choose(intro.await(), anime.await(), ani.await(), theIntro.await())
            }
        } ?: emptyList()
        resultCache[i.key] = Timed(result, now + if (result.isEmpty()) MISS else DAY)
        result
    }
}
