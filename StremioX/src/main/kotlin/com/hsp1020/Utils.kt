// 파일명: hsp1020/Utils.kt
package com.hsp1020

import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.getQualityFromName
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
    "metacriticuser" to RatingMeta("MCU", "👥", 4),
    "audiencescore" to RatingMeta("Popcorn", "🍿", 5),
    "tomatoesaudience" to RatingMeta("Popcorn", "🍿", 5),
    "popcorn" to RatingMeta("Popcorn", "🍿", 5),
    "trakt" to RatingMeta("Trakt", "Ⓣ", 6),
    "tmdb" to RatingMeta("TMDB", "🎬", 7),
    "metacritic" to RatingMeta("MC", "🎯", 8),
    "mc" to RatingMeta("MC", "🎯", 8),
    "rottentomatoes" to RatingMeta("RT", "🍅", 9),
    "tomatoes" to RatingMeta("RT", "🍅", 9),
    "rt" to RatingMeta("RT", "🍅", 9),
    "rogerebert" to RatingMeta("RE", "👍", 10),
    "re" to RatingMeta("RE", "👍", 10)
)

// 🔥 [V3 패치] 캐시 스탬피드를 유발하던 미사용 자석 링크(Magnet) 트래커 변수 완전 폐기

suspend fun fetchMdbListRatings(id: String?, isTmdb: Boolean = false): List<String> {
    if (id.isNullOrBlank()) return emptyList()

    val context = com.lagradost.cloudstream3.CloudStreamApp.context ?: return emptyList()
    val prefs = context.getSharedPreferences("StremioX", android.content.Context.MODE_PRIVATE)
    
    val mdbListEnabled = prefs.getBoolean("mdblist_enabled", false)
    val mdbListApiKey = prefs.getString("mdblist_api_key", "") ?: ""
    
    if (!mdbListEnabled || mdbListApiKey.isBlank()) return emptyList()
    
    return try {
        val idParam = if (isTmdb) "tm" else "i"
        val url = "https://mdblist.com/api/?apikey=$mdbListApiKey&$idParam=$id"
        
        val json = JSONObject(app.get(url, timeout = 15L).text)
        val ratingsArray = json.optJSONArray("ratings") ?: return emptyList()
        
        val results = mutableListOf<Pair<RatingMeta, Double>>()
        
        for (i in 0 until ratingsArray.length()) {
            val ratingObj = ratingsArray.optJSONObject(i) ?: continue
            val sourceKey = ratingObj.optString("source", "").lowercase()
            val value = if (ratingObj.has("value")) ratingObj.optDouble("value") else ratingObj.optDouble("score")
            
            if (value.isNaN() || value <= 0.0) continue
            
            val scaledValue = when (sourceKey) {
                "letterboxd", "lb" -> value * 2.0
                "rogerebert", "re" -> value * 2.5
                "tmdb", "trakt", "metacritic", "mc", "tomatoes", "rottentomatoes", "rt", "tomatoesaudience", "audiencescore", "popcorn" -> value / 10.0
                else -> value
            }
            
            val roundedValue = round(scaledValue * 10) / 10.0
            if (roundedValue <= 0.0) continue
            
            val meta = RATING_SOURCES[sourceKey] ?: RatingMeta(sourceKey.uppercase(), "🏷️", 99)
            results.add(meta to roundedValue)
        }
        
        results.sortBy { it.first.sortOrder }
        results.map { (meta, score) ->
            "${meta.icon} ${String.format(Locale.US, "%.1f", score)}"
        }
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
    @SerializedName("streams") val streams: List<TorrentioStream> = emptyList()
)

data class TorrentioStream(
    @SerializedName("name") val name: String? = null, 
    @SerializedName("title") val title: String? = null, 
    @SerializedName("infoHash") val infoHash: String? = null, 
    @SerializedName("fileIdx") val fileIdx: Int? = null
)

// 🔥 [V3 패치]generateMagnetLink 함수 완전 삭제

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
