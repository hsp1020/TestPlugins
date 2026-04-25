// v1.6 (Feature: MDBList 평점 10점 만점 정규화, 0점 필터링 및 "Site Score" 텍스트 포맷 적용)
package com.hsp1020

import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
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
    if (id.isNullOrBlank() || MDBLIST_API_KEY.isBlank()) {
        println("[Utils.kt v1.6-MDBList] API 키/ID 누락 조회 스킵 ID: $id")
        return emptyList()
    }
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
                
                // MDBList의 'score'는 모든 평점을 0~100 기준으로 환산해둔 값입니다.
                // 이를 10.0으로 나누면 어떤 사이트든 10점 만점 척도로 깔끔하게 변환됩니다.
                val scoreInt = ratingObj.optInt("score", 0)
                
                // 데이터가 없는 0.0점 처리 제외
                if (scoreInt <= 0) continue
                
                val formattedScore = (scoreInt / 10.0).toString()
                
                val key = when (source) {
                    "myanimelist", "mal" -> "mal"
                    "imdb" -> "imdb"
                    "audiencescore" -> "popcorn"
                    "metacriticuser" -> "mcu"
                    "letterboxd" -> "lb"
                    "trakt" -> "trakt"
                    "tmdb" -> "tmdb"
                    "metacritic" -> "mc"
                    "rottentomatoes" -> "rt"
                    "rogerebert" -> "re"
                    else -> source
                }
                
                val siteName = when (key) {
                    "rt" -> "RT"
                    "popcorn" -> "Popcorn"
                    "mc" -> "MC"
                    "mcu" -> "MCU"
                    "lb" -> "LB"
                    "re" -> "RE"
                    "imdb" -> "IMDb"
                    "trakt" -> "Trakt"
                    "tmdb" -> "TMDB"
                    "mal" -> "MAL"
                    else -> key.uppercase()
                }
                
                // 지정된 "[Site] [Score]" 형식으로 저장 (예: "IMDb 8.4")
                fetchedMap[key] = "$siteName $formattedScore"
            }
        }
        
        val orderedKeys = listOf("mal", "imdb", "popcorn", "mcu", "lb", "trakt", "tmdb", "mc", "rt", "re")
        val finalRatings = mutableListOf<String>()
        
        for (key in orderedKeys) fetchedMap[key]?.let { finalRatings.add(it) }
        for ((key, formattedStr) in fetchedMap) if (!orderedKeys.contains(key)) finalRatings.add(formattedStr)
        
        finalRatings
    } catch (e: Exception) { emptyList() }
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
    fun path(o: JSONObject) = o.optString("file_path")
    fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"
    var svgFallback: JSONObject? = null
    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue; val p = path(logo); if (p.isBlank()) continue
        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) { if (!isSvg(logo)) return urlOf(logo); if (svgFallback == null) svgFallback = logo }
    }
    svgFallback?.let { return urlOf(it) }
    var best: JSONObject? = null; var bestSvg: JSONObject? = null
    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0
    fun better(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return true; val aAvg = a.optDouble("vote_average", 0.0); val aCnt = a.optInt("vote_count", 0); val bAvg = b.optDouble("vote_average", 0.0); val bCnt = b.optInt("vote_count", 0)
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }
    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue; if (!voted(logo)) continue
        if (isSvg(logo)) { if (better(bestSvg, logo)) bestSvg = logo } else { if (better(best, logo)) best = logo }
    }
    best?.let { return urlOf(it) }; bestSvg?.let { return urlOf(it) }; return null
}
fun normalizeId(id: String?): String {
    val clean = id?.trim() ?: ""
    return when { clean.matches(Regex("^tt\\d+$")) -> clean; clean.all { it.isDigit() } -> "tmdb:$clean"; else -> clean }
}
