package com.hsp1020

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DaddyLiveScheduleProvider : MainAPI() {
    override var mainUrl = "https://thedaddy.li"
    override var name = "DaddyLive"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    /*
     * HTTP/1.1 강제 클라이언트는 완전히 제거.
     * CloudStream 기본 app/NiceHttp가 HTTP 프로토콜을 정상 협상한다.
     */
    private val requestHeaders = mapOf(
        "user-agent" to userAgent,
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "accept-language" to "en-US,en;q=0.9"
    )

    private val scheduleMutex = Mutex()

    @Volatile
    private var scheduleCache: ScheduleSnapshot? = null

    @Suppress("ConstPropertyName")
    companion object {
        private const val posterUrl =
            "https://raw.githubusercontent.com/hsp1020/TestPlugins/refs/heads/master/DaddyLive/live-streaming2.png"

        internal const val cardPosterUrl =
            "https://raw.githubusercontent.com/hsp1020/TestPlugins/refs/heads/master/DaddyLive/live-streaming.png"

        private const val SCHEDULE_CACHE_TTL_MS = 60_000L

        fun convertGMTToLocalTime(gmtTime: String): String {
            val gmtFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            gmtFormat.timeZone = TimeZone.getTimeZone("GMT")

            val date: Date =
                gmtFormat.parse(gmtTime) ?: throw IllegalArgumentException("Invalid time format")

            val localFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            localFormat.timeZone = TimeZone.getDefault()

            return localFormat.format(date)
        }
    }

    private data class ScheduleChannel(
        val name: String,
        val streamId: String
    )

    private data class ScheduleEvent(
        val dataTitle: String,
        val eventTitle: String,
        val timeGmt: String,
        val channels: List<ScheduleChannel>
    )

    private data class ScheduleSection(
        val title: String,
        val events: List<ScheduleEvent>
    )

    private data class ScheduleSnapshot(
        val fetchedAt: Long,
        val sections: List<ScheduleSection>
    )

    data class ChannelSelection(
        val origin: String,
        val eventTitle: String,
        val channelName: String,
        val streamId: String
    )

    private suspend fun getSchedule(forceRefresh: Boolean = false): ScheduleSnapshot {
        val now = System.currentTimeMillis()
        val cached = scheduleCache

        if (
            !forceRefresh &&
            cached != null &&
            now - cached.fetchedAt < SCHEDULE_CACHE_TTL_MS
        ) {
            return cached
        }

        return scheduleMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedCached = scheduleCache

            if (
                !forceRefresh &&
                lockedCached != null &&
                lockedNow - lockedCached.fetchedAt < SCHEDULE_CACHE_TTL_MS
            ) {
                return@withLock lockedCached
            }


            val response = withContext(Dispatchers.IO) {
                app.get(
                    mainUrl,
                    headers = requestHeaders
                )
            }


            if (!response.isSuccessful) {
                throw IllegalStateException("DaddyLive schedule HTTP ${response.code}")
            }

            val doc = response.document

            val sections = doc.select("#schedule .schedule__category").map { category ->
                val sectionTitle = category.select(".card__meta").text()

                val events = category.select(".schedule__event").mapNotNull { event ->
                    val dataTitle =
                        event.select(".schedule__eventHeader").attr("data-title").trim()

                    val eventTitle =
                        event.select(".schedule__eventTitle").text().trim()

                    val time =
                        event.select(".schedule__time").text().trim()

                    if (dataTitle.isEmpty() || eventTitle.isEmpty()) {
                        return@mapNotNull null
                    }

                    val seenIds = hashSetOf<String>()

                    val channels = event.select(".schedule__channels > a").mapNotNull { channel ->
                        val href = channel.attr("href")

                        val streamId = href
                            .substringAfter("id=", "")
                            .substringBefore("&")
                            .substringBefore("#")
                            .trim()

                        if (streamId.isEmpty() || !seenIds.add(streamId)) {
                            return@mapNotNull null
                        }

                        val channelName =
                            channel.text().trim().ifEmpty { "Channel $streamId" }

                        ScheduleChannel(
                            name = channelName,
                            streamId = streamId
                        )
                    }

                    ScheduleEvent(
                        dataTitle = dataTitle,
                        eventTitle = eventTitle,
                        timeGmt = time,
                        channels = channels
                    )
                }


                ScheduleSection(
                    title = sectionTitle,
                    events = events
                )
            }

            ScheduleSnapshot(
                fetchedAt = System.currentTimeMillis(),
                sections = sections
            ).also {
                scheduleCache = it
            }
        }
    }

    private fun eventToSearchResponse(event: ScheduleEvent): SearchResponse {
        val formattedTime = try {
            convertGMTToLocalTime(event.timeGmt)
        } catch (_: Exception) {
            event.timeGmt
        }

        val encodedDataTitle = URLEncoder.encode(event.dataTitle, "UTF-8")

        return newTvSeriesSearchResponse(
            "$formattedTime - ${event.eventTitle}",
            encodedDataTitle,
            TvType.TvSeries
        ) {
            this.posterUrl = Companion.cardPosterUrl
            this.episodes = event.channels.size
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        return try {
            val snapshot = getSchedule()

            val home = snapshot.sections.map { section ->
                HomePageList(
                    section.title,
                    section.events.map { eventToSearchResponse(it) },
                    isHorizontalImages = false
                )
            }

            newHomePageResponse(home, false)
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        return try {
            val normalizedQuery =
                query.lowercase().replace(" ", "")

            getSchedule()
                .sections
                .flatMap { it.events }
                .filter {
                    it.eventTitle
                        .lowercase()
                        .replace(" ", "")
                        .contains(normalizedQuery)
                }
                .map { eventToSearchResponse(it) }
                .also {
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decodeEventTitle(url: String): String {
        val raw = url
            .removePrefix("$mainUrl/")
            .substringBefore("?")

        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
    }

    private suspend fun findEvent(dataTitle: String): ScheduleEvent? {
        val cachedMatch = getSchedule()
            .sections
            .asSequence()
            .flatMap { it.events.asSequence() }
            .firstOrNull { it.dataTitle == dataTitle }

        if (cachedMatch != null) {
            return cachedMatch
        }


        return getSchedule(forceRefresh = true)
            .sections
            .asSequence()
            .flatMap { it.events.asSequence() }
            .firstOrNull { it.dataTitle == dataTitle }
    }

    override suspend fun load(url: String): LoadResponse {

        return try {
            /*
             * 직접 stream-*.php URL이 들어오는 오래된 경로도
             * 단일 채널 Episode로 변환해 호환성을 유지한다.
             */
            val directStreamId =
                Regex("""stream-(\d+)\.php""")
                    .find(url)
                    ?.groupValues
                    ?.getOrNull(1)

            if (directStreamId != null) {
                val directSelection = ChannelSelection(
                    origin = mainUrl,
                    eventTitle = "DaddyLive Channel $directStreamId",
                    channelName = "Channel $directStreamId",
                    streamId = directStreamId
                )

                val episode = newEpisode(directSelection) {
                    this.name = directSelection.channelName
                    this.season = 1
                    this.episode = 1
                }

                return newTvSeriesLoadResponse(
                    directSelection.eventTitle,
                    url,
                    TvType.TvSeries,
                    listOf(episode)
                ) {
                    this.posterUrl = Companion.posterUrl
                    this.plot = "\u200b"
                    this.seasonNames = listOf(
                        SeasonData(
                            season = 1,
                            name = "Channels",
                            displaySeason = 1
                        )
                    )
                }
            }

            val dataTitle = decodeEventTitle(url)


            val event = findEvent(dataTitle)
                ?: throw NoSuchElementException(
                    "No scheduled event found for '$dataTitle'"
                )

            if (event.channels.isEmpty()) {
                throw NoSuchElementException(
                    "No channels found for '${event.eventTitle}'"
                )
            }

            /*
             * 핵심 리팩토링:
             * 한 경기의 모든 채널을 loadLinks()로 몰아 넣지 않는다.
             * 각 DaddyLive 채널을 CloudStream Episode 하나로 노출한다.
             *
             * 사용자가 Episode(채널)를 실제로 선택했을 때에만
             * 그 Episode.data 하나가 loadLinks()로 전달된다.
             */
            val episodes = event.channels.mapIndexed { index, channel ->
                val selection = ChannelSelection(
                    origin = mainUrl,
                    eventTitle = event.eventTitle,
                    channelName = channel.name,
                    streamId = channel.streamId
                )

                newEpisode(selection) {
                    this.name = channel.name
                    this.season = 1
                    this.episode = index + 1
                }
            }

            val formattedTime = try {
                convertGMTToLocalTime(event.timeGmt)
            } catch (_: Exception) {
                event.timeGmt
            }


            newTvSeriesLoadResponse(
                "$formattedTime - ${event.eventTitle}",
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = Companion.posterUrl
                this.plot = "\u200b"
                this.seasonNames = listOf(
                    SeasonData(
                        season = 1,
                        name = "Channels",
                        displaySeason = 1
                    )
                )
            }
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val selected =
                mapper.readValue<ChannelSelection>(data)


            var found = false

            /*
             * data에는 사용자가 선택한 Episode 한 개만 들어온다.
             * Extractor가 이 채널 하나에 대해서만 player fallback을 수행한다.
             */
            DaddyLiveExtractor().getUrl(
                data,
                null,
                subtitleCallback
            ) { link ->
                found = true
                callback(link)
            }


            found
        } catch (e: Exception) {
            false
        }
    }
}
