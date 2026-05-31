package com.hsp1020

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DaddyLiveScheduleProvider : MainAPI() {
    override var mainUrl = "https://dlhd.pk"
    override var name = "DaddyLive"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    @Suppress("ConstPropertyName")
    companion object {
        private const val posterUrl =
            "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/DaddyLive/daddylive.jpg"

        fun convertGMTToLocalTime(gmtTime: String): String {
            val gmtFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            gmtFormat.timeZone = TimeZone.getTimeZone("GMT")

            val date: Date =
                gmtFormat.parse(gmtTime) ?: throw IllegalArgumentException("Invalid time format")

            val localFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            localFormat.timeZone = TimeZone.getDefault()

            return localFormat.format(date)
        }

        fun convertStringToLocalDate(objectKey: String): String {
            val dateString = objectKey.substringBeforeLast(" -")
            val cleanedDateString = dateString.replace(Regex("(?<=\\d)(st|nd|rd|th)"), "")
            val dateFormat = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.ENGLISH)
            val date = dateFormat.parse(cleanedDateString)

            val calendar = Calendar.getInstance()
            calendar.time = date!!

            val outputFormat = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
            return outputFormat.format(calendar.time)
        }
    }

    private fun searchResponseBuilder(doc: Element): List<LiveSearchResponse> {
        return doc.select(".schedule__event").map { e ->
            val dataTitle = e.select(".schedule__eventHeader").attr("data-title")
            val eventTitle = e.select(".schedule__eventTitle").text()
            val time = e.select(".schedule__time").text()
            val formattedTime = convertGMTToLocalTime(time)
            newLiveSearchResponse("$formattedTime - $eventTitle", dataTitle){
                this.posterUrl = Companion.posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val schedule = doc.select(".schedule__category").map {
            val sectionTitle = it.select(".card__meta").text()
            val events = searchResponseBuilder(it)
            HomePageList(
                sectionTitle,
                events,
                false
            )
        }
        return newHomePageResponse(
            schedule,
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(mainUrl).document
        val schedule = doc.select(".schedule__category").map {
            searchResponseBuilder(it)
        }.flatten()
        val matches = schedule.filter {
            query.lowercase().replace(" ", "") in
                    it.name.lowercase().replace(" ", "")
        }
        return matches
    }

    override suspend fun load(url: String): LoadResponse {
        // 직접 watch.php?id=* 등으로 강제 주소 진입했을 경우 대안 라우팅 처리
        if (url.contains("watch.php") || url.contains("stream-")) {
            val doc = app.get(url).document
            val eventTitle = doc.select("h2").text().ifEmpty { doc.title().substringBefore(" | Watch") }
            val iframeSrc = doc.select("iframe#playerFrame").attr("src")
            val id = if (iframeSrc.isNotEmpty()) {
                iframeSrc.substringAfter("stream-").substringBefore(".php")
            } else {
                url.substringAfter("id=")
            }
            val channels = listOf(Channel(eventTitle, "$mainUrl/%s/stream-$id.php"))
            return newLiveStreamLoadResponse(eventTitle, url, dataUrl = channels.toJson()) {
                this.posterUrl = Companion.posterUrl
            }
        }

        // 기존 메인화면 스케줄 아이템 클릭 시 라우팅 처리
        val doc = app.get(mainUrl).document
        val dataTitle = url.removePrefix("$mainUrl/")
        val event = doc.select(".schedule__event").firstOrNull {
            it.select("div.schedule__eventHeader").attr("data-title") == dataTitle
        } ?: throw NoSuchElementException("No scheduled event found for $dataTitle")

        val eventTitle = event.select(".schedule__eventTitle").text()
        val channels = event.select(".schedule__channels > a").map {
            val id = it.attr("href").substringAfter("id=")
            Channel(it.text(), "$mainUrl/%s/stream-$id.php")
        }
        Log.d("DDL Schedule - Channels", channels.toJson())
        val time = event.select(".schedule__time").text()
        val formattedTime = convertGMTToLocalTime(time)
        return newLiveStreamLoadResponse("$formattedTime - $eventTitle", url, dataUrl = channels.toJson()){
            this.posterUrl = Companion.posterUrl
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // 요구사항대로 플레이어를 stream 하나만 등록하여 트래픽 낭비 최적화
        val players = listOf("stream")
        val channels = parseJson<List<Channel>>(data)

        val links = channels.map {
            players.map { l ->
                val url = it.channelId.format(l)
                Log.d("DDL - Servers", url)
                it.channelName + " - $l" to url
            }
        }.flatten()

        DaddyLiveExtractor().getUrl(links.toJson(), null, subtitleCallback, callback)
        return true
    }

    data class Channel(
        val channelName: String,
        val channelId: String
    )
}
