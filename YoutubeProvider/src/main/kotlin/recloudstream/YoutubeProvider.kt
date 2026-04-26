// [v1.3]
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.InfoItem
//import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.Locale
import kotlin.concurrent.thread

class YoutubeProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Others,
        TvType.Live,
        TvType.TvSeries
    )

    private val service = ServiceList.YouTube

    // Make mainPage dynamic and updatable
    override var mainPage: List<MainPageData> = emptyList()

    init {
        val kiosks = service.kioskList.availableKiosks
        mainPage = kiosks.map { id ->
            val fallbackName = id.split("_").joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            MainPageData(fallbackName, id)
        }

        thread {
            val localizedPages = kiosks.map { id ->
                var localizedLabel = id
                try {
                    val extractor = service.kioskList.getExtractorById(id, null)
                    // extractor.forceContentCountry(Localisation to be handled later)
                    extractor.fetchPage()
                    localizedLabel = extractor.name
                } catch (e: Exception) {
                    localizedLabel = id.split("_").joinToString(" ") { word ->
                        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    }
                }
                MainPageData(localizedLabel, id)
            }
            mainPage = localizedPages
        }
    }

    private val pageCache = mutableMapOf<String, org.schabi.newpipe.extractor.Page?>()
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val key = request.data
        if (page == 1) pageCache.remove(key)

        val extractor = getKioskExtractor(request.data)

        // val userCountry = Locale.getDefault().country
        // extractor.forceContentCountry(ContentCountry(userCountry.ifBlank { "US" }))

        val pageData = try {
            if (page == 1) {
                extractor.fetchPage()

                extractor.initialPage.also {
                    pageCache[key] = it.nextPage
                }
            } else {
                val next = pageCache[key] ?: return newHomePageResponse(emptyList(), false)
                extractor.getPage(next).also {
                    pageCache[key] = it.nextPage
                }
            }
        } catch (e: Exception) {
            return newHomePageResponse(emptyList(), false)
        }

        val results = pageData.items.map {
            it.toSearchResponse()
        }

        val headerName = try {
            extractor.name.ifEmpty { request.name }
        } catch (e: Exception) {
            request.name
        }.ifEmpty { "Trending" }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    headerName,
                    results,
                    true
                )
            ),
            pageData.hasNextPage()
        )
    }

    private val searchPageCache = mutableMapOf<String, org.schabi.newpipe.extractor.Page?>()
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val extractor = service.getSearchExtractor(query)

        // Localisation to be handled later
        // extractor.forceContentCountry(ContentCountry(Locale.getDefault().country))

        val pageData = if (!searchPageCache.containsKey(query)) {
            extractor.fetchPage()
            extractor.initialPage.also {
                searchPageCache[query] = it.nextPage
            }
        } else {
            val next = searchPageCache[query] ?: return newSearchResponseList(emptyList(), false)
            extractor.getPage(next).also {
                searchPageCache[query] = it.nextPage
            }
        }

        val results = pageData.items.map {
            it.toSearchResponse()
        }

        return newSearchResponseList(
            results,
            pageData.hasNextPage()
        )
    }

    private fun getKioskExtractor(kioskId: String?): KioskExtractor<out InfoItem> {
        return if (kioskId.isNullOrBlank()) {
            service.kioskList.getDefaultKioskExtractor(null)
        } else {
            service.kioskList.getExtractorById(kioskId, null)
        }
    }

    private fun InfoItem.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            name ?: "Unknown",
            url ?: "",
            TvType.Others
        ) {
            posterUrl = thumbnails.lastOrNull()?.url
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val urlType = getUrlType(url)

        return when (urlType) {
            UrlType.Video -> loadVideo(url)
            UrlType.Channel -> loadChannel(url)
            UrlType.Playlist -> loadPlaylist(url)
            UrlType.Unknown -> throw RuntimeException("Unsupported YouTube URL")
        }
    }

    private enum class UrlType {
        Video, Channel, Playlist, Unknown
    }

    private fun getUrlType(url: String): UrlType {
        return when {
            url.contains("/watch?v=") || url.contains("youtu.be/") -> UrlType.Video
            url.contains("/channel/") || url.contains("/@") || url.contains("/c/") -> UrlType.Channel
            url.contains("/playlist?list=") || url.contains("/watch?v=") && url.contains("&list=") -> UrlType.Playlist
            else -> UrlType.Unknown
        }
    }

    private suspend fun loadVideo(url: String): LoadResponse {
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()

        val info = StreamInfo.getInfo(extractor)

        return newMovieLoadResponse(
            info.name,
            url,
            if (info.streamType?.name?.contains("LIVE") == true)
                TvType.Live else TvType.Others,
            url
        ) {
            plot = info.description.content.toString()
            posterUrl = info.thumbnails.lastOrNull()?.url
            duration = info.duration.toInt()

            info.uploaderName?.takeIf { it.isNotBlank() }?.let { uploader ->
                actors = listOf(
                    ActorData(
                        Actor(
                            uploader,
                            info.uploaderAvatars.lastOrNull()?.url ?: ""
                        )
                    )
                )
            }

            tags = info.tags?.take(5)?.toList()
        }
    }

    private suspend fun loadChannel(url: String): LoadResponse {
        val extractor = ServiceList.YouTube.getChannelExtractor(url)
        extractor.fetchPage()

        val channelName = extractor.name
        val channelDescription = extractor.description
        val channelAvatar = extractor.avatars.lastOrNull()?.url
        val channelBanner = extractor.banners.lastOrNull()?.url

        val tabs = extractor.tabs
        val videosTab = tabs.firstOrNull { it.url.contains("/videos") } ?: tabs.firstOrNull()
            ?: throw RuntimeException("No videos tab found")

        val videosExtractor = ServiceList.YouTube.getChannelTabExtractor(videosTab)
        val episodes = mutableListOf<Episode>()

        var page = videosExtractor.initialPage
        episodes.addAll(page.items.map { item ->
            newEpisode(item.url) {
                name = item.name
                posterUrl = item.thumbnails.lastOrNull()?.url
            }
        })

        while (page.hasNextPage()) {
            page = videosExtractor.getPage(page.nextPage)
            episodes.addAll(page.items.map { item ->
                newEpisode(item.url) {
                    name = item.name
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            })
        }

        return newTvSeriesLoadResponse(
            channelName,
            url,
            TvType.TvSeries,
            episodes
        ) {
            plot = channelDescription
            posterUrl = channelBanner
            backgroundPosterUrl = channelBanner
            tags = listOf("Channel")
            actors = listOf(
                ActorData(
                    Actor(
                        channelName,
                        channelAvatar ?: ""
                    )
                )
            )
        }
    }

    private suspend fun loadPlaylist(url: String): LoadResponse {
        val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
        extractor.fetchPage()

        val playlistName = extractor.name
        val playlistDescription = extractor.description.content.toString()
        val playlistThumbnail = extractor.thumbnails.lastOrNull()?.url
        val uploaderName = extractor.uploaderName

        val episodes = mutableListOf<Episode>()

        var page = extractor.getInitialPage()
        episodes.addAll(page.items.map { item ->
            newEpisode(item.url) {
                name = item.name
                posterUrl = item.thumbnails.lastOrNull()?.url
            }
        })

        while (page.hasNextPage()) {
            page = extractor.getPage(page.nextPage)
            episodes.addAll(page.items.map { item ->
                newEpisode(item.url) {
                    name = item.name
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            })
        }

        return newTvSeriesLoadResponse(
            playlistName,
            url,
            TvType.TvSeries,
            episodes
        ) {
            plot = playlistDescription
            posterUrl = playlistThumbnail
            tags = if (uploaderName.isNotBlank()) listOf("Channel: $uploaderName") else listOf("Playlist")
            if (uploaderName.isNotBlank()) {
                actors = listOf(
                    ActorData(
                        Actor(
                            uploaderName,
                            extractor.uploaderAvatars.lastOrNull()?.url ?: ""
                        )
                    )
                )
            }
        }
    }

    // [v1.3] 수정: 사용자의 정확한 단서(1,3 더빙 / 2,4 원본)를 기반으로 중복 트랙 폐기 및 원본 추출 로직 적용
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 기형적 URL 방지 (기존 v1.2 로직 유지)
        val url = if (data.startsWith("http")) data else "https://youtube.com/watch?v=$data"
        println("[v1.3] loadLinks called with URL: $url")
        
        val allLinks = mutableListOf<ExtractorLink>()
        
        // 1. 추출기가 반환하는 모든 링크를 일단 allLinks에 싹 다 긁어모음
        val result = loadExtractor(
            url,
            subtitleCallback,
            { link ->
                allLinks.add(link)
                println("[v1.3] Captured Raw Link: name=${link.name}, quality=${link.quality}, url=${link.url}")
            }
        )

        // 2. 트랙 이름(예: und aac)과 화질을 기준으로 그룹화
        val groupedLinks = allLinks.groupBy { "${it.name}_${it.quality}" }
        
        groupedLinks.forEach { (_, links) ->
            // 가설 검증: URL 내부에 더빙 찌꺼기 파라미터가 없는 녀석들을 우선 색출
            val likelyOriginals = links.filterNot { 
                it.url.contains("audio_track_id=") || 
                it.url.contains("translated_audio") ||
                it.url.contains("dubbed")
            }
            
            // 필터링에 성공했다면 그 중 마지막 녀석을, 
            // URL 필터링으로도 못 걸러냈다면 사용자가 짚어준 규칙대로 무조건 쌍의 마지막 녀석(2, 4번)을 원본으로 취급
            val finalLink = if (likelyOriginals.isNotEmpty()) {
                likelyOriginals.last()
            } else {
                links.last()
            }
            
            println("[v1.3] Selected Link for playback: name=${finalLink.name}, quality=${finalLink.quality}")
            
            // 3. 살아남은 진짜 원본 트랙만 실제 화면(플레이어)으로 넘겨줌
            callback.invoke(finalLink)
        }

        return result
    }
}
