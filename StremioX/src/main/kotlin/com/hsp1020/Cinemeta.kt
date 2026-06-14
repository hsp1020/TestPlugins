package com.hsp1020

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

// 공식 Cinemeta 주소로 원복 및 StremioC 상속
class Cinemeta : StremioC("https://extendedratings.com/proxy/f873acb8-3861-444c-aca2-81a4c6bdd6cb, "Cinemeta") {
    
    override val hasMainPage = true
    
    // 앱 필터링 설정에 의해 카탈로그가 숨겨지는 현상을 방지하기 위해 Others 포함
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

    // 공식 Cinemeta 기반 동적 탭 생성
    override val mainPage = mainPageOf(
        *buildList {
            val movieCatalogName = "Popular"
            val movieType = "Movie"
            
            add("$mainUrl/catalog/movie/top/skip=###.json" to "$movieCatalogName - $movieType")
            movieGenres.forEach { genre ->
                add("$mainUrl/catalog/movie/top/genre=$genre&skip=###.json" to "$movieCatalogName - $movieType ($genre)")
            }

            val seriesCatalogName = "Popular"
            val seriesType = "Series"
            
            add("$mainUrl/catalog/series/top/skip=###.json" to "$seriesCatalogName - $seriesType")
            seriesGenres.forEach { genre ->
                add("$mainUrl/catalog/series/top/genre=$genre&skip=###.json" to "$seriesCatalogName - $seriesType ($genre)")
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

                newMovieSearchResponse(title, metaUrl, type) {
                    this.posterUrl = meta.poster
                    this.score = meta.imdbRating?.toDoubleOrNull()?.let { Score.from10(it) }
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

    private data class CinemetaDirectResponse(@JsonProperty("metas") val metas: List<CinemetaMeta>?)
    
    private data class CinemetaMeta(
        @JsonProperty("id") val id: String, 
        @JsonProperty("type") val type: String?, 
        @JsonProperty("name") val name: String?, 
        @JsonProperty("poster") val poster: String?, 
        @JsonProperty("imdbRating") val imdbRating: String?
    )
}
