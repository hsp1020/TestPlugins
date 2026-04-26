// [v1.6]
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

    // [v1.6] 수정: 오디오 스트림 분리 및 itag 그룹핑을 통한 더빙 트랙(1,3) 자동 폐기 로직 추가
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = if (data.startsWith("http")) data else "https://youtube.com/watch?v=$data"
        println("[v1.6] ===================================================")
        println("[v1.6] loadLinks 실행됨 - 입력 데이터: $data")
        
        val audioLinks = mutableListOf<ExtractorLink>()

        val result = loadExtractor(
            url,
            subtitleCallback
        ) { link ->
            // ExoPlayer로 넘어가기 전 URL의 mime 속성으로 오디오 트랙 식별 및 가로채기
            if (link.url.contains("mime=audio")) {
                audioLinks.add(link)
            } else {
                // 비디오 트랙은 즉시 플레이어로 무사 통과
                callback.invoke(link)
            }
        }

        // 수집된 오디오 링크들을 URL 내부의 itag(코덱/음질 고유 식별번호) 기준으로 그룹화
        val groupedAudio = audioLinks.groupBy { audioLink ->
            val itagMatch = Regex("itag=(\\d+)").find(audioLink.url)
            itagMatch?.groupValues?.get(1) ?: audioLink.url
        }

        println("[v1.6] 오디오 트랙 필터링 시작 - 총 그룹 수: ${groupedAudio.size}")

        // 1, 3 더빙 / 2, 4 원본 패턴에 따라 각 그룹의 마지막 트랙(2, 4번)만 살리고 앞의 더빙 찌꺼기는 전부 폐기
        groupedAudio.forEach { (itag, links) ->
            println("[v1.6] ---------------------------------------------------")
            println("[v1.6] itag=$itag 그룹 발견 (총 ${links.size}개 트랙)")
            links.forEachIndexed { index, link ->
                println("[v1.6]   [인덱스 $index] url=${link.url}")
            }
            
            // 그룹의 마지막 요소(원본 트랙)를 추출
            val originalAudioLink = links.last()
            println("[v1.6] ---> itag=$itag 그룹에서 최종 선택된 트랙: [인덱스 ${links.lastIndex}]")
            
            // 걸러낸 진짜 원본 오디오 트랙만 ExoPlayer로 넘김
            callback.invoke(originalAudioLink)
        }
        
        println("[v1.6] ===================================================")
        return result
    }
}
