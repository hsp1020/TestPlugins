// v1.14 (Feature: 사이트명 약자 텍스트 제거. 오직 아이콘과 점수만 표기)
package com.hsp1020

import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.sequences.forEach

const val MDBLIST_API_KEY = "d2tmbmhnrgdz7ft9re7j2gjr3"

suspend fun fetchMdbListRatings(id: String?, isTmdb: Boolean = false): List<String> {
    if (id.isNullOrBlank() || MDBLIST_API_KEY.isBlank()) return emptyList()
    return try {
        val idParam = if (isTmdb) "tm" else "i"
        val url = "https://mdblist.com/api/?apikey=$MDBLIST_API_KEY&$idParam=$id"
        
        val json = JSONObject(app.get(url, timeout = 15L).text)
        val ratingsArray = json.optJSONArray("ratings")
        val fetchedMap = mutableMapOf<String, String>()
        
        if (ratingsArray != null) {
            for (i in 0 until ratingsArray.length()) {
                val ratingObj = ratingsArray.optJSONObject(i) ?: continue
                val source = ratingObj.optString("source", "").lowercase()
                val value = if (ratingObj.has("value")) ratingObj.optDouble("value") else ratingObj.optDouble("score")
                if (value.isNaN()) continue
                
                var scaledValue = value
                when (source) {
                    "letterboxd", "lb" -> scaledValue *= 2.0
                    "rogerebert", "re" -> scaledValue *= 2.5
                    "tmdb", "trakt", "metacritic", "mc", "tomatoes", "rottentomatoes", "rt", "tomatoesaudience", "audiencescore", "popcorn" -> scaledValue /= 10.0
                }
                
                scaledValue = java.math.BigDecimal(scaledValue.toString())
                    .setScale(1, java.math.RoundingMode.HALF_UP)
                    .toDouble()
                
                if (scaledValue <= 0.0) continue
                
                val key = when (source) {
                    "myanimelist", "mal" -> "MAL"
                    "imdb" -> "IMDb"
                    "audiencescore", "tomatoesaudience" -> "Popcorn"
                    "metacriticuser" -> "MCU"
                    "letterboxd" -> "LB"
                    "trakt" -> "Trakt"
                    "tmdb" -> "TMDB"
                    "metacritic" -> "MC"
                    "rottentomatoes", "tomatoes" -> "RT"
                    "rogerebert" -> "RE"
                    else -> source.uppercase()
                }

                val icon = when (key) {
                    "MAL" -> "🐱"
                    "IMDb" -> "⭐"
                    "Popcorn" -> "🍿"
                    "MCU" -> "👥"
                    "LB" -> "🎞️"
                    "Trakt" -> "Ⓣ"
                    "TMDB" -> "🎬"
                    "MC" -> "🎯"
                    "RT" -> "🍅"
                    "RE" -> "👍"
                    else -> "🏷️"
                }
                
                val displayValue = String.format(Locale.US, "%.1f", scaledValue)
                // [수정됨] 약자(key) 제거 후 오직 "아이콘 + 점수"만 할당
                fetchedMap[key] = "$icon $displayValue"
            }
        }
        
        val orderedKeys = listOf("MAL", "IMDb", "LB", "MCU", "Popcorn", "Trakt", "TMDB", "MC", "RT", "RE")
        val finalRatings = mutableListOf<String>()
        for (key in orderedKeys) {
            fetchedMap[key]?.let { finalRatings.add(it) }
        }
        for ((key, formattedStr) in fetchedMap) {
            if (!orderedKeys.contains(key)) finalRatings.add(formattedStr)
        }
        finalRatings
    } catch (e: Exception) { 
        emptyList() 
    }
}

fun String.fixSourceUrl(): String = this.replace("/manifest.json", "").replace("stremio://", "https://")
fun fixSourceName(name: String?, title: String?): String = when {
    name?.contains("[RD+]", true) == true -> "[RD+] $title"
    name?.contains("[RD download]", true) == true -> "[RD download] $title"
    !name.isNullOrEmpty() && !title.isNullOrEmpty() -> "$name $title"
    else -> title ?: name ?: ""
}
fun getQuality(qualities: List<String?>): Int {
    fun String.getQuality(): String? = Regex("(\\d{3,4}[pP])").find(this)?.groupValues?.getOrNull(1)
    return getQualityFromName(qualities.firstNotNullOfOrNull { it?.getQuality() })
}
fun getEpisodeSlug(season: Int? = null, episode: Int? = null): Pair<String, String> = if (season == null && episode == null) "" to "" else (if (season!! < 10) "0$season" else "$season") to (if (episode!! < 10) "0$episode" else "$episode")
fun isUpcoming(dateString: String?): Boolean = try { val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()); val dateTime = dateString?.let { format.parse(it)?.time } ?: return false; unixTimeMS < dateTime } catch (t: Throwable) { logError(t); false }
fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) return url; if (url.isEmpty()) return ""
    return if (url.startsWith("//")) "https:$url" else if (url.startsWith('/')) domain + url else "$domain/$url"
}
data class TorrentioResponse(@SerializedName("streams") val streams: List<TorrentioStream> = emptyList())
data class TorrentioStream(@SerializedName("name") val name: String? = null, @SerializedName("title") val title: String? = null, @SerializedName("infoHash") val infoHash: String? = null, @SerializedName("fileIdx") val fileIdx: Int? = null)
suspend fun generateMagnetLink(trackerUrls: List<String>, hash: String?): String {
    require(hash?.isNotBlank() == true); val trackers = mutableSetOf<String>()
    trackerUrls.forEach { url -> try { app.get(url).text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { trackers.add(it) } } catch (_: Exception) {} }
    return buildString {
        append("magnet:?xt=urn:btih:").append(hash)
        if (hash.isNotBlank()) append("&dn=").append(URLEncoder.encode(hash, StandardCharsets.UTF_8.name()))
        trackers.take(10).forEach { tracker -> append("&tr=").append(URLEncoder.encode(tracker, StandardCharsets.UTF_8.name())) }
    }
}
suspend fun fetchTmdbLogoUrl(tmdbAPI: String, apiKey: String, type: TvType, tmdbId: Int?, appLangCode: String?): String? {
    if (tmdbId == null) return null
    val url = if (type == TvType.Movie) "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey" else "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"
    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null; if (logos.length() == 0) return null
    val lang = appLangCode?.trim()?.lowercase()?.substringBefore("-")
    fun path(o: JSONObject) = o.optString("file_path"); fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true); fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"
    var svgFallback: JSONObject? = null
    for (i in 0 until logos.length()) { val logo = logos.optJSONObject(i) ?: continue; val p = path(logo); if (p.isBlank()) continue; val l = logo.optString("iso_639_1").trim().lowercase(); if (l == lang) { if (!isSvg(logo)) return urlOf(logo); if (svgFallback == null) svgFallback = logo } }
    svgFallback?.let { return urlOf(it) }; var best: JSONObject? = null; var bestSvg: JSONObject? = null
    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0
    fun better(a: JSONObject?, b: JSONObject): Boolean { if (a == null) return true; val aAvg = a.optDouble("vote_average", 0.0); val aCnt = a.optInt("vote_count", 0); val bAvg = b.optDouble("vote_average", 0.0); val bCnt = b.optInt("vote_count", 0); return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt) }
    for (i in 0 until logos.length()) { val logo = logos.optJSONObject(i) ?: continue; if (!voted(logo)) continue; if (isSvg(logo)) { if (better(bestSvg, logo)) bestSvg = logo } else { if (better(best, logo)) best = logo } }
    best?.let { return urlOf(it) }; bestSvg?.let { return urlOf(it) }; return null
}
fun normalizeId(id: String?): String {
    val clean = id?.trim() ?: ""
    return when { clean.matches(Regex("^tt\\d+$")) -> clean; clean.all { it.isDigit() } -> "tmdb:$clean"; else -> clean }
}
