// [v1.4]
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

    // [v1.4] 수정: 오디오 메커니즘 추적을 위한 추출기 반환값 전면 디버깅(println) 적용
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = if (data.startsWith("http")) data else "https://youtube.com/watch?v=$data"
        println("[v1.4] ===================================================")
        println("[v1.4] loadLinks 실행됨 - 입력 데이터: $data")
        println("[v1.4] loadLinks 대상 URL: $url")
        println("[v1.4] ===================================================")
        
        return loadExtractor(
            url,
            subtitleCallback,
            { link ->
                // 사용자의 요청에 따라 모든 오디오/비디오 관련 링크 메타데이터를 무자비하게 출력
                println("[v1.4] ---------------------------------------------------")
                println("[v1.4] [추출된 ExtractorLink 캡처됨]")
                println("[v1.4] Name(이름): ${link.name}")
                println("[v1.4] Quality(화질): ${link.quality}")
                println("[v1.4] URL(주소): ${link.url}")
                println("[v1.4] isM3u8(M3u8여부): ${link.isM3u8}")
                println("[v1.4] Referer: ${link.referer}")
                
                // 매니페스트 식별 및 원인 분석
                if (link.isM3u8 || link.url.contains(".m3u8") || link.url.contains("manifest") || link.url.contains(".mpd") || link.url.contains("dash")) {
                    println("[v1.4] [핵심 경고] 이 링크는 개별 오디오 스트림이 아닌 M3U8 또는 DASH 매니페스트입니다!")
                    println("[v1.4] 즉, und aac / eng aac 트랙 4개는 이 플러그인 단계를 무사히 거친 후, 앱 내장 플레이어(ExoPlayer)가 저 주소를 열어 자체적으로 파싱해 생성하는 것일 확률이 사실상 100%입니다.")
                } else {
                    println("[v1.4] [정보] 일반 다이렉트 스트림 링크입니다.")
                }
                println("[v1.4] ---------------------------------------------------")
                
                // 원본 콜백으로 그대로 전달하여 영상 재생이 차단되지 않도록 복구
                callback.invoke(link)
            }
        )
    }
}
