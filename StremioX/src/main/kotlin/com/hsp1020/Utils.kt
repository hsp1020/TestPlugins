// v1.16 (Performance Refactored: 정규식/포매터 캐싱, 메모리 파편화 제거(BigDecimal 제거), 자석 링크 병렬화, O(1) 맵핑 적용)
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
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.round

const val MDBLIST_API_KEY = "d2tmbmhnrgdz7ft9re7j2gjr3"

// 최상단 상수로 빼서 매번 정규식을 다시 컴파일하는 CPU 스파이크 제거
private val QUALITY_REGEX = Regex("(\\d{3,4}[pP])")
private val TT_REGEX = Regex("^tt\\d+$")

// O(1) 조회를 위한 소스 메타데이터 룩업 테이블 (메모리 재활용)
private data class RatingMeta(val name: String, val icon: String, val sortOrder: Int)
private val RATING_SOURCES = mapOf(
    "myanimelist" to RatingMeta("MAL", " 🎎", 1),
    "mal" to RatingMeta("MAL", " 🎎", 1),
    "imdb" to RatingMeta("IMDb", "⭐", 2),
    "letterboxd" to RatingMeta("LB", "🎞️", 3),
    "lb" to RatingMeta("LB", "🎞️", 3),
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

suspend fun fetchMdbListRatings(id: String?, isTmdb: Boolean = false): List<String> {
    if (id.isNullOrBlank() || MDBLIST_API_KEY.isBlank()) return emptyList()
    
    return try {
        val idParam = if (isTmdb) "tm" else "i"
        val url = "https://mdblist.com/api/?apikey=$MDBLIST_API_KEY&$idParam=$id"
        
        val json = JSONObject(app.get(url, timeout = 15L).text)
        val ratingsArray = json.optJSONArray("ratings") ?: return emptyList()
        
        val results = mutableListOf<Pair<RatingMeta, Double>>()
        
        for (i in 0 until ratingsArray.length()) {
            val ratingObj = ratingsArray.optJSONObject(i) ?: continue
            val sourceKey = ratingObj.optString("source", "").lowercase()
            val value = if (ratingObj.has("value")) ratingObj.optDouble("value") else ratingObj.optDouble("score")
            
            if (value.isNaN() || value <= 0.0) continue
            
            // 10점 만점 정규화
            val scaledValue = when (sourceKey) {
                "letterboxd", "lb" -> value * 2.0
                "rogerebert", "re" -> value * 2.5
                "tmdb", "trakt", "metacritic", "mc", "tomatoes", "rottentomatoes", "rt", "tomatoesaudience", "audiencescore", "popcorn" -> value / 10.0
                else -> value
            }
            
            // 메모리 킬러 BigDecimal 제거 -> 원시 타입 고속 연산으로 대체
            val roundedValue = round(scaledValue * 10) / 10.0
            if (roundedValue <= 0.0) continue
            
            val meta = RATING_SOURCES[sourceKey] ?: RatingMeta(sourceKey.uppercase(), "🏷️", 99)
            results.add(meta to roundedValue)
        }
        
        // 정렬 및 문자열 포맷팅 (객체 생성 최소화)
        results.sortBy { it.first.sortOrder }
        results.map { (meta, score) ->
            // 약자 제외: 아이콘 + 점수 포맷
            "${meta.icon} ${String.format(Locale.US, "%.1f", score)}"
        }
    } catch (e: Exception) { 
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
    // 매번 컴파일하던 Regex를 상단 상수로 이동하여 CPU 소모 대폭 감소
    val qualityStr = qualities.firstNotNullOfOrNull { it?.let { str -> QUALITY_REGEX.find(str)?.groupValues?.getOrNull(1) } }
    return getQualityFromName(qualityStr)
}

fun getEpisodeSlug(season: Int? = null, episode: Int? = null): Pair<String, String> {
    if (season == null || episode == null) return "" to ""
    // 불쾌한 !! 연산자 제거 및 빠른 포맷팅
    return String.format(Locale.US, "%02d", season) to String.format(Locale.US, "%02d", episode)
}

fun isUpcoming(dateString: String?): Boolean {
    if (dateString.isNullOrBlank()) return false
    return try {
        // 무거운 SimpleDateFormat 대신 스레드 세이프하고 빠른 LocalDate 사용
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

suspend fun generateMagnetLink(trackerUrls: List<String>, hash: String?): String = coroutineScope {
    require(hash?.isNotBlank() == true)

    // 순차 통신(Blocking) -> 병렬 통신(Parallel)으로 변경. 속도 비약적 향상.
    val trackers = trackerUrls.map { url ->
        async(Dispatchers.IO) {
            try {
                app.get(url).text
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            } catch (_: Exception) {
                emptyList<String>()
            }
        }
    }.awaitAll().flatten().toSet()

    buildString {
        append("magnet:?xt=urn:btih:").append(hash)
        append("&dn=").append(URLEncoder.encode(hash, "UTF-8")) // StandardCharsets.UTF_8.name()은 상수 오버헤드

        trackers.take(10).forEach { tracker ->
            append("&tr=").append(URLEncoder.encode(tracker, "UTF-8"))
        }
    }
}

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

    // 3중 루프를 1회 순회(Single-Pass)로 통합하여 알고리즘 최적화
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

    // 언어 일치 SVG 최우선 -> 언어 일치 PNG -> 글로벌 베스트 SVG -> 글로벌 베스트 PNG 순서
    val finalLogo = bestLangSvg ?: bestLangPng ?: bestGlobalSvg ?: bestGlobalPng ?: logos.optJSONObject(0)
    
    return finalLogo?.optString("file_path")?.let { "https://image.tmdb.org/t/p/w500$it" }
}

fun normalizeId(id: String?): String {
    val clean = id?.trim() ?: ""
    return when {
        // 매번 Regex 인스턴스를 만들지 않고 캐싱된 객체 활용
        TT_REGEX.matches(clean) -> clean
        clean.isNotEmpty() && clean.all { it.isDigit() } -> "tmdb:$clean"
        else -> clean
    }
}
