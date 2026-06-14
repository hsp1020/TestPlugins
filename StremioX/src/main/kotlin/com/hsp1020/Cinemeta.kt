package com.hsp1020

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class Cinemeta : StremioC("https://extendedratings.com/proxy/3be73f8c-91ff-449a-bfe1-cf5d95b09c2a", "Cinemeta") {
    
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)

    // ==========================================
    // [StremioC 완벽 동기화] 안정성 방어 상태 변수들
    // ==========================================
    private val catalogSkipState = ConcurrentHashMap<String, Int>()
    private val catalogSentIds = ConcurrentHashMap<String, MutableSet<String>>()
    
    private val pageMutex = Mutex()
    private val activePageRequests = mutableMapOf<Int, Deferred<HomePageResponse>>()

    private inline fun <T> lruSet(maxSize: Int): MutableSet<T> {
        return Collections.synchronizedSet(Collections.newSetFromMap(object : java.util.LinkedHashMap<T, Boolean>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<T, Boolean>): Boolean {
                return size > maxSize
            }
        }))
    }

    private val movieGenres = listOf(
        "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", 
        "Drama", "Family", "Fantasy", "History", "Horror", "Mystery", "Romance", "Sci-Fi", 
        "Sport", "Thriller", "War", "Western"
    )

    private val seriesGenres = movieGenres + listOf(
        "Reality-TV", "Talk-Show", "Game-Show"
    )

    // Popular - Series를 Popular - Movie 바로 뒤에 배치
    override val mainPage = mainPageOf(
        *buildList {
            val movieCatalogName = "Popular"
            val movieType = "Movie"
            
            val seriesCatalogName = "Popular"
            val seriesType = "Series"
            
            // 1. Popular - Movie 우선 배치
            add("$mainUrl/catalog/movie/top/skip=###.json" to "$movieCatalogName - $movieType")
            
            // 2. Popular - Series를 바로 뒤에 배치
            add("$mainUrl/catalog/series/top/skip=###.json" to "$seriesCatalogName - $seriesType")

            // 3. Movie 장르 목록 배치
            movieGenres.forEach { genre ->
                add("$mainUrl/catalog/movie/top/genre=$genre&skip=###.json" to "$genre - $movieType")
            }

            // 4. Series 장르 목록 배치
            seriesGenres.forEach { genre ->
                add("$mainUrl/catalog/series/top/genre=$genre&skip=###.json" to "$genre - $seriesType")
            }
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = coroutineScope {
        var isNewRequest = false
        
        val deferred = pageMutex.withLock {
            if (page <= 1) {
                catalogSentIds.clear()
                activePageRequests.clear()
                catalogSkipState.clear()
            }
            
            activePageRequests.getOrPut(page) {
                isNewRequest = true
                async { fetchCinemetaData(request) }
            }
        }
        
        return@coroutineScope try {
            val response = deferred.await()
            if (!isNewRequest && page > 1) {
                newHomePageResponse(emptyList(), hasNext = true)
            } else {
                response
            }
        } finally {
            pageMutex.withLock {
                activePageRequests.remove(page)
            }
        }
    }

    private suspend fun fetchCinemetaData(request: MainPageRequest): HomePageResponse {
        val currentSkip = catalogSkipState[request.name] ?: 0
        val targetUrl = request.data.replace("###", currentSkip.toString())
        val cacheKey = "Cinemeta_${request.name}_$currentSkip"

        val resultPair = StremioC.getOrFetchCachedPage(cacheKey) {
            val jsonText = StremioC.customSession.get(targetUrl).text
            val response = try {
                StremioC.jsonMapper.readValue(jsonText, CinemetaDirectResponse::class.java)
            } catch (e: Exception) { null }

            val metas = response?.metas ?: emptyList()
            
            val mappedItems = metas.mapNotNull { meta ->
                val type = if (meta.type == "series") TvType.TvSeries else TvType.Movie
                val title = meta.name ?: meta.id
                val metaUrl = "$mainUrl/meta/${meta.type ?: "movie"}/${meta.id}.json"

                // [수정] 평점 배지(this.score) 설정부를 제거하여 홈화면 포스터를 깔끔하게 연출합니다.
                newMovieSearchResponse(title, metaUrl, type) {
                    this.posterUrl = meta.poster
                }
            }
            Pair(mappedItems, metas.size)
        }

        if (resultPair != null) {
            catalogSkipState[request.name] = currentSkip + resultPair.second
            
            var seenForThisCatalog = catalogSentIds[request.name]
            if (seenForThisCatalog == null) {
                synchronized(catalogSentIds) {
                    seenForThisCatalog = catalogSentIds.getOrPut(request.name) { 
                        lruSet(500) 
                    }
                }
            }
            
            val filteredItems = resultPair.first.filter { item -> seenForThisCatalog!!.add(item.url) }
            return newHomePageResponse(listOf(HomePageList(request.name, filteredItems)), hasNext = resultPair.second > 0)
        }

        return newHomePageResponse(emptyList(), hasNext = false)
    }

    // ==========================================
    // 모든 경우(All cases)에 지정된 AIO Metadata 주소로 직접 연동하여 로드
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val parsed = parseIdAndType(url)
        if (parsed != null) {
            val (id, type) = parsed
            val aioUrl = "https://aiometadata.elfhosted.com/stremio/b345b590-872c-4e13-bc4f-852f4e70c9ef/meta/$type/${id.urlEncodeLocal()}.json"
            return super.load(aioUrl)
        }
        return super.load(url)
    }

    private fun parseIdAndType(url: String): Pair<String, String>? {
        return try {
            if (url.startsWith("{")) {
                val root = StremioC.jsonMapper.readTree(url)
                val id = root.get("id")?.asText() ?: root.get("imdbId")?.asText()
                val type = root.get("type")?.asText() ?: "movie"
                if (id != null) Pair(id, type) else null
            } else {
                val cleanUrl = url.substringBefore("?")
                if (cleanUrl.contains("/meta/")) {
                    val parts = cleanUrl.substringAfter("/meta/").split("/")
                    if (parts.size >= 2) {
                        val type = parts[0]
                        val id = parts[1].removeSuffix(".json")
                        Pair(id, type)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun String.urlEncodeLocal(): String = try {
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    } catch (e: Exception) {
        this
    }

    private data class CinemetaDirectResponse(@JsonProperty("metas") val metas: List<CinemetaMeta>?)
    
    private data class CinemetaMeta(
        @JsonProperty("id") val id: String, 
        @JsonProperty("type") val type: String?, 
        @JsonProperty("name") val name: String?, 
        @JsonProperty("poster") val poster: String?, 
        @JsonProperty("imdbRating") val imdbRating: String?
    )
}
