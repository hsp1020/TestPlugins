package com.hsp1020

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class Cinemeta : StremioC("https://v3-cinemeta.strem.io", "Cinemeta") {
    
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ==========================================
    // [StremioC 완벽 동기화] 안정성 방어 상태 변수들
    // ==========================================
    private val catalogSkipState = ConcurrentHashMap<String, Int>()
    private val catalogSentIds = ConcurrentHashMap<String, MutableSet<String>>() // 중복 아이템 렌더링 방지용
    
    private val pageMutex = Mutex() // 동시 스크롤 요청 방어용
    private val activePageRequests = mutableMapOf<Int, Deferred<HomePageResponse>>()

    // StremioC 내부의 lruSet 로직 복제 (최대 사이즈 이상의 캐시 자동 삭제)
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

    override val mainPage = buildList {
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
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = coroutineScope {
        var isNewRequest = false
        
        // [StremioC 완벽 동기화] 중복 요청 방어 및 초기화 로직
        val deferred = pageMutex.withLock {
            if (page <= 1) {
                catalogSentIds.clear()
                activePageRequests.clear()
                catalogSkipState.clear()
                // StremioC 내부 캐시 초기화는 Companion object 이므로 생략해도 무방
            }
            
            activePageRequests.getOrPut(page) {
                isNewRequest = true
                async { fetchCinemetaData(request) } // 실제 데이터 패치 호출
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

        // [StremioC 완벽 동기화] StremioC의 Companion Cache 재사용
        val resultPair = StremioC.getOrFetchCachedPage(cacheKey) {
            val jsonText = customSession.get(targetUrl).text
            val response = try {
                jsonMapper.readValue(jsonText, CinemetaDirectResponse::class.java)
            } catch (e: Exception) { null }

            val metas = response?.metas ?: emptyList()
            
            val mappedItems = metas.mapNotNull { meta ->
                val type = if (meta.type == "series") TvType.TvSeries else TvType.Movie
                val title = meta.name ?: meta.id
                val metaUrl = "$mainUrl/meta/${meta.type ?: "movie"}/${meta.id}.json"

                // StremioC.toSearchResponse 와 동일한 형태로 SearchResponse 생성
                newMovieSearchResponse(title, metaUrl, type) {
                    this.posterUrl = meta.poster
                    this.score = meta.imdbRating?.toDoubleOrNull()?.let { Score.from10(it) }
                }
            }
            Pair(mappedItems, metas.size)
        }

        if (resultPair != null) {
            catalogSkipState[request.name] = currentSkip + resultPair.second
            
            // [StremioC 완벽 동기화] 이미 본 ID는 걸러내기 (중복 데이터 제거)
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

    // JSON DTO
    private data class CinemetaDirectResponse(@JsonProperty("metas") val metas: List<CinemetaMeta>?)
    
    private data class CinemetaMeta(
        @JsonProperty("id") val id: String, 
        @JsonProperty("type") val type: String?, 
        @JsonProperty("name") val name: String?, 
        @JsonProperty("poster") val poster: String?, 
        @JsonProperty("imdbRating") val imdbRating: String?
    )
}
