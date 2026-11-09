package com.hsp1020.elevate

import android.net.Uri
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.readIdFromString
import com.lagradost.cloudstream3.SimklSyncServices
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import org.json.JSONObject

/** Common, validated identity input; each feature retains its own output-ID priority. */
internal object ElevateMediaIdentity {
    data class Ids(
        val imdb: String? = null, val tmdb: String? = null,
        val mal: String? = null, val anilist: String? = null,
        val kitsu: String? = null, val simkl: String? = null,
        val original: String? = null,
        val imdbSeason: Int? = null, val imdbEpisode: Int? = null,
        val simklAnime: Boolean = false
    ) {
        val animeIds: Boolean get() = mal != null || anilist != null || kitsu != null
        fun merge(fallback: Ids) = Ids(
            imdb ?: fallback.imdb, tmdb ?: fallback.tmdb,
            mal ?: fallback.mal, anilist ?: fallback.anilist,
            kitsu ?: fallback.kitsu, simkl ?: fallback.simkl,
            original ?: fallback.original,
            imdbSeason ?: fallback.imdbSeason, imdbEpisode ?: fallback.imdbEpisode,
            simklAnime || fallback.simklAnime
        )
        fun asMap(): Map<String, String> = buildMap {
            imdb?.let { put("imdb", it) }; tmdb?.let { put("tmdb", it) }
            mal?.let { put("mal", it) }; anilist?.let { put("anilist", it) }
            kitsu?.let { put("kitsu", it) }; simkl?.let { put("simkl", it) }
        }
    }

    private val imdbPattern = Regex("tt[0-9]+")
    private val originPattern = Regex("(?:tt[0-9]+|(?:kitsu|mal|anilist|tmdb):[0-9]+)")
    private val metaPattern = Regex("/meta/(?:movie|series)/([^/?]+)\\.json")
    private val typedPattern = Regex("(?:tmdb:(?:tv:|movie:|series:)?|mal:|anilist:|kitsu:)[0-9]+")
    private val embeddedImdbPattern = Regex("tt[0-9]{5,10}")
    private val embeddedIdPattern = Regex("(?:tmdb:(?:tv:|movie:|series:)?|kitsu:|mal:|anilist:)[0-9]+")
    private fun imdb(value: String?): String? = value?.trim()?.takeIf(imdbPattern::matches)
    private fun number(value: String?, prefix: String): String? = value?.trim()
        ?.removePrefix("$prefix:")
        ?.let { if (prefix == "tmdb") it.removePrefix("tv:").removePrefix("movie:").removePrefix("series:") else it }
        ?.substringBefore(':')
        ?.toLongOrNull()?.takeIf { it > 0L }?.toString()
    private fun JSONObject.first(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        optString(key).trim().takeUnless { it.isBlank() || it == "null" }
    }
    private fun parseJson(value: String?): Ids {
        val obj = value?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Ids()
        val nested = obj.optJSONObject("ids")
        fun field(vararg keys: String) = obj.first(*keys) ?: nested?.first(*keys)
        val raw = field("originalAnimeId") ?: field("id")
        val origin = raw?.takeIf { it.matches(originPattern) }
        fun positive(field: String) = obj.optInt(field).takeIf { it > 0 }
        return Ids(
            imdb(field("imdbId", "imdb_id", "imdb")) ?: imdb(origin),
            number(field("tmdbId", "tmdb_id", "_tmdbId", "moviedb_id", "tmdb"), "tmdb")
                ?: number(origin?.takeIf { it.startsWith("tmdb:") }, "tmdb"),
            number(field("malId", "mal_id", "mal"), "mal")
                ?: number(origin?.takeIf { it.startsWith("mal:") }, "mal"),
            number(field("anilistId", "aniListId", "anilist_id", "anilist"), "anilist")
                ?: number(origin?.takeIf { it.startsWith("anilist:") }, "anilist"),
            number(field("kitsuId", "kitsu_id", "kitsu"), "kitsu")
                ?: number(origin?.takeIf { it.startsWith("kitsu:") }, "kitsu"),
            number(field("simklId", "simkl"), "simkl"), origin,
            positive("imdbSeason"), positive("imdbEpisode")
        )
    }
    private fun parseSync(values: Map<String, String>?): Ids {
        if (values == null) return Ids()
        val saved = readIdFromString(values["simkl"])
        return Ids(
            imdb(values["imdb"]) ?: imdb(saved[SimklSyncServices.Imdb]),
            number(values["tmdb"], "tmdb") ?: number(saved[SimklSyncServices.Tmdb], "tmdb"),
            number(values["mal"], "mal") ?: number(saved[SimklSyncServices.Mal], "mal"),
            number(values["anilist"], "anilist") ?: number(saved[SimklSyncServices.AniList], "anilist"),
            number(values["kitsu"], "kitsu"),
            number(values["simkl"], "simkl") ?: number(saved[SimklSyncServices.Simkl], "simkl")
        )
    }
    // CloudStream syncData can include unverified title-search tracker IDs.
    private fun parseUntrustedSync(values: Map<String, String>?): Ids =
        parseSync(values).copy(mal = null, anilist = null, kitsu = null)
    private fun parseUrl(value: String?): Ids {
        if (value.isNullOrBlank()) return Ids()
        val raw = runCatching { Uri.decode(value) }.getOrDefault(value)
        val json = parseJson(raw)
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val parts = uri?.pathSegments.orEmpty()
        val host = uri?.host?.lowercase()?.removePrefix("www.")
        val meta = metaPattern.find(raw)
            ?.groupValues?.getOrNull(1)?.let(Uri::decode)
        val direct = listOfNotNull(meta, raw.takeIf { '/' !in it }).firstOrNull { item ->
            item.matches(imdbPattern) || item.matches(typedPattern)
        }
        val embeddedImdb = embeddedImdbPattern.find(raw)?.value
        val embeddedId = embeddedIdPattern.find(raw)?.value
        fun fromDirect(key: String) = number(direct?.takeIf { it.startsWith("$key:") }, key)
        fun query(vararg names: String) = names.firstNotNullOfOrNull { name ->
            runCatching { uri?.getQueryParameter(name) }.getOrNull()
        }
        fun fromHost(name: String, section: String): String? =
            if (host == name && parts.size >= 2 && parts[0] == section)
                number(parts[1], "") else null
        return json.merge(Ids(
            imdb = imdb(direct) ?: imdb(embeddedImdb) ?: query("imdb", "imdb_id", "imdbId")?.let(::imdb),
            tmdb = fromDirect("tmdb") ?: number(embeddedId?.takeIf { it.startsWith("tmdb:") }, "tmdb")
                ?: fromHost("themoviedb.org", "tv")
                ?: fromHost("themoviedb.org", "movie") ?: number(query("tmdb", "tmdb_id", "tmdbId"), "tmdb"),
            mal = fromDirect("mal") ?: number(embeddedId?.takeIf { it.startsWith("mal:") }, "mal")
                ?: fromHost("myanimelist.net", "anime") ?: number(query("mal", "malId"), "mal"),
            anilist = fromDirect("anilist") ?: number(embeddedId?.takeIf { it.startsWith("anilist:") }, "anilist")
                ?: fromHost("anilist.co", "anime") ?: number(query("anilist", "anilistId"), "anilist"),
            kitsu = fromDirect("kitsu") ?: number(embeddedId?.takeIf { it.startsWith("kitsu:") }, "kitsu")
                ?: fromHost("kitsu.app", "anime") ?: fromHost("kitsu.io", "anime")
                ?: number(query("kitsu", "kitsuId"), "kitsu"),
            simkl = if (host == "simkl.com" && parts.size >= 2 &&
                parts[0] in setOf("anime", "movie", "movies", "tv", "show", "shows"))
                number(parts[1], "simkl") else number(query("simkl", "simklId"), "simkl"),
            original = direct
        ))
    }
    fun collect(
        response: LoadResponse? = null, episodeData: String? = null,
        extraSync: Map<String, String>? = null, urls: List<String> = emptyList(),
        episodeFirst: Boolean = false
    ): Ids {
        val native = if (response == null) Ids() else Ids(
            imdb(response.getImdbId()), number(response.getTMDbId(), "tmdb")
        )
        val episodeIds = parseJson(episodeData)
        val preferred = native.merge(episodeIds)
        val input = (if (episodeFirst) preferred.copy(
            mal = episodeIds.mal ?: native.mal,
            anilist = episodeIds.anilist ?: native.anilist,
            kitsu = episodeIds.kitsu ?: native.kitsu
        ) else preferred).merge(parseUntrustedSync(response?.syncData))
            .merge(parseUntrustedSync(extraSync))
        val fromUrls = (listOfNotNull(response?.url) + urls).fold(Ids()) { ids, url ->
            ids.merge(parseUrl(url))
        }
        return input.merge(fromUrls).copy(original = fromUrls.original ?: input.original)
    }
    fun fromMap(ids: Map<String, String>): Ids = parseSync(ids)
    fun anime(response: LoadResponse?, episode: ResultEpisode?, ids: Ids): Boolean {
        val type = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
        val tags = response?.tags.orEmpty().map { it.lowercase() }
        val episodeFlag = episode?.data?.let { runCatching { JSONObject(it).optBoolean("isAnime") }.getOrDefault(false) } == true
        return response?.type in type || episode?.tvType in type || episodeFlag ||
            tags.any { it == "anime" } ||
            (tags.contains("animation") && tags.contains("japanese")) ||
            ids.animeIds || ids.simklAnime
    }
    suspend fun enrichSimkl(ids: Ids, movie: Boolean): Ids {
        val raw = ids.simkl ?: return ids
        val mapped = ElevateIdResolver.simkl(raw, movie) ?: return ids
        return ids.merge(Ids(mapped.imdb, mapped.tmdb, mapped.mal,
            mapped.anilist, mapped.kitsu, simklAnime = mapped.segment == "anime"))
    }
}
