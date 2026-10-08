// 파일명: hsp1020/Utils.kt
package com.hsp1020

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.round

private val QUALITY_REGEX = Regex("(\\d{3,4}[pP])")
private val TT_REGEX = Regex("^tt\\d+$")

private data class RatingMeta(val name: String, val icon: String, val sortOrder: Int)
private val RATING_SOURCES = mapOf(
    "myanimelist" to RatingMeta("MAL", " Ⓜ️", 1),
    "mal" to RatingMeta("MAL", " Ⓜ️", 1),
    "imdb" to RatingMeta("IMDb", "⭐", 2),
    "letterboxd" to RatingMeta("LB", "🟢", 3),
    "lb" to RatingMeta("LB", "🟢", 3),
    "audiencescore" to RatingMeta("Popcorn", "🍿", 5),
    "tomatoesaudience" to RatingMeta("Popcorn", "🍿", 5),
    "popcorn" to RatingMeta("Popcorn", "🍿", 5),
    "audience" to RatingMeta("Popcorn", "🍿", 5),
    "trakt" to RatingMeta("Trakt", "Ⓣ", 6),
    "tmdb" to RatingMeta("TMDB", "🎬", 7),
    "metacritic" to RatingMeta("MC", "🎯", 8),
    "mc" to RatingMeta("MC", "🎯", 8),
    "rottentomatoes" to RatingMeta("RT", "🍅", 9),
    "tomatoes" to RatingMeta("RT", "🍅", 9),
    "rt" to RatingMeta("RT", "🍅", 9)
)

suspend fun fetchMdbListRatings(id: String?, isTmdb: Boolean = false, mediaType: String = "movie"): List<String> {
    if (id.isNullOrBlank()) return emptyList()

    val context = com.lagradost.cloudstream3.CloudStreamApp.context ?: return emptyList()
    val prefs = context.getSharedPreferences("StremioC", android.content.Context.MODE_PRIVATE)
    
    val mdbListEnabled = prefs.getBoolean("mdblist_enabled", false)
    val mdbListApiKey = prefs.getString("mdblist_api_key", "") ?: ""
    
    if (!mdbListEnabled || mdbListApiKey.isBlank()) return emptyList()
    
    return try {
        val provider = if (isTmdb) "tmdb" else "imdb"
        val type = if (mediaType == "movie") "movie" else "show"
        val modernUrl = "https://api.mdblist.com/$provider/$type/$id/?apikey=$mdbListApiKey&append_to_response=keyword"
        val json = try {
            JSONObject(app.get(modernUrl, timeout = 15L).text).also {
                if (it.optJSONArray("ratings") == null) error("MDBList missing ratings")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val idParam = if (isTmdb) "tm" else "i"
            JSONObject(app.get("https://mdblist.com/api/?apikey=$mdbListApiKey&$idParam=$id", timeout = 15L).text)
        }
        val ratingsArray = json.optJSONArray("ratings") ?: return emptyList()
        val keywords = json.optJSONArray("keywords")
        val certifications = (0 until (keywords?.length() ?: 0)).mapNotNull { i ->
            val value = keywords?.opt(i)
            (if (value is JSONObject) value.optString("name") else value as? String)
                ?.substringAfterLast('.')?.lowercase()
        }.toSet()

        val results = mutableListOf<Pair<RatingMeta, Double>>()
        
        for (i in 0 until ratingsArray.length()) {
            val ratingObj = ratingsArray.optJSONObject(i) ?: continue
            val sourceKey = ratingObj.optString("source", "").lowercase()
            val value = if (ratingObj.has("value")) ratingObj.optDouble("value") else ratingObj.optDouble("score")
            
            if (value.isNaN() || value <= 0.0) continue
            
            val scaledValue = when (sourceKey) {
                "letterboxd", "lb" -> ratingObj.optDouble("score").takeUnless { it.isNaN() }
                    ?.div(10.0) ?: if (value > 5.0) value else value * 2.0
                "tmdb", "trakt", "metacritic", "mc", "tomatoes", "rottentomatoes", "rt", "tomatoesaudience", "audiencescore", "popcorn", "audience" -> value / 10.0
                else -> value
            }
            
            val roundedValue = round(scaledValue * 10) / 10.0
            if (roundedValue <= 0.0) continue
            
            if (sourceKey == "metacriticuser" || sourceKey == "rogerebert" || sourceKey == "re") continue
            val meta = RATING_SOURCES[sourceKey] ?: RatingMeta(sourceKey.uppercase(), "🏷️", 99)
            val icon = when (meta.name) {
                "RT" -> when {
                    "certified-fresh" in certifications && scaledValue >= 7.0 -> "🍅\u2060\u2060"
                    scaledValue < 6.0 -> "🍅\u2060"
                    else -> "🍅"
                }
                "Popcorn" -> when {
                    "certified-hot" in certifications && scaledValue >= 8.0 -> "🍿\u2060\u2060"
                    scaledValue < 6.0 -> "🍿\u2060"
                    else -> "🍿"
                }
                else -> meta.icon
            }
            results.add(meta.copy(icon = icon) to roundedValue)
        }
        
        results.sortBy { it.first.sortOrder }
        results.map { (meta, score) ->
            "${meta.icon} ${String.format(Locale.US, "%.1f", score)}"
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

fun String.fixSourceUrl(): String = this.replace("/manifest.json", "").replace("stremio://", "https://")

fun fixSourceName(name: String?, title: String?): String {
    return when {
        name?.contains("[RD+]", ignoreCase = true) == true -> "[RD+] $title"
        name?.contains("[RD download]", ignoreCase = true) == true -> "[RD download] $title"
        !name.isNullOrEmpty() && !title.isNullOrEmpty() -> "$name $title"
        else -> title ?: name ?: ""
    }
}

fun getQuality(qualities: List<String?>): Int {
    val qualityStr = qualities.firstNotNullOfOrNull { it?.let { str -> QUALITY_REGEX.find(str)?.groupValues?.getOrNull(1) } }
    return getQualityFromName(qualityStr)
}

fun getEpisodeSlug(season: Int? = null, episode: Int? = null): Pair<String, String> {
    if (season == null || episode == null) return "" to ""
    return String.format(Locale.US, "%02d", season) to String.format(Locale.US, "%02d", episode)
}

fun isUpcoming(dateString: String?): Boolean {
    if (dateString.isNullOrBlank()) return false
    return try {
        val localDate = LocalDate.parse(dateString)
        val epochMilli = localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        unixTimeMS < epochMilli
    } catch (t: Throwable) {
        logError(t)
        false
    }
}

fun fixUrl(url: String, domain: String): String {
    if (url.isEmpty() || url.startsWith("http")) return url
    return if (url.startsWith("//")) "https:$url" 
           else if (url.startsWith("/")) "$domain$url" 
           else "$domain/$url"
}

data class TorrentioResponse(
    @JsonProperty("streams") val streams: List<TorrentioStream> = emptyList()
)

data class TorrentioStream(
    @JsonProperty("name") val name: String? = null, 
    @JsonProperty("title") val title: String? = null, 
    @JsonProperty("infoHash") val infoHash: String? = null, 
    @JsonProperty("fileIdx") val fileIdx: Int? = null
)

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String, 
    apiKey: String, 
    type: TvType, 
    tmdbId: Int?, 
    appLangCode: String?
): String? {
    if (tmdbId == null) return null

    val url = if (type == TvType.Movie) "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey" else "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val lang = appLangCode?.trim()?.lowercase()?.substringBefore("-")

    var bestLangSvg: JSONObject? = null
    var bestLangPng: JSONObject? = null
    var bestGlobalSvg: JSONObject? = null
    var bestGlobalPng: JSONObject? = null

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val path = logo.optString("file_path")
        if (path.isBlank()) continue

        val isSvg = path.endsWith(".svg", true)
        val l = logo.optString("iso_639_1").trim().lowercase()
        val votes = logo.optDouble("vote_average", 0.0)
        val count = logo.optInt("vote_count", 0)
        
        val hasVotes = votes > 0 && count > 0

        fun better(existing: JSONObject?): Boolean {
            if (existing == null) return true
            val eAvg = existing.optDouble("vote_average", 0.0)
            val eCnt = existing.optInt("vote_count", 0)
            return votes > eAvg || (votes == eAvg && count > eCnt)
        }

        if (l == lang) {
            if (isSvg) {
                if (better(bestLangSvg)) bestLangSvg = logo
            } else {
                if (better(bestLangPng)) bestLangPng = logo
            }
        } else if (hasVotes) {
            if (isSvg) {
                if (better(bestGlobalSvg)) bestGlobalSvg = logo
            } else {
                if (better(bestGlobalPng)) bestGlobalPng = logo
            }
        }
    }

    val finalLogo = bestLangSvg ?: bestLangPng ?: bestGlobalSvg ?: bestGlobalPng ?: logos.optJSONObject(0)
    
    return finalLogo?.optString("file_path")?.let { "https://image.tmdb.org/t/p/w500$it" }
}

fun normalizeId(id: String?): String {
    val clean = id?.trim() ?: ""
    return when {
        TT_REGEX.matches(clean) -> clean
        clean.isNotEmpty() && clean.all { it.isDigit() } -> "tmdb:$clean"
        else -> clean
    }
}
