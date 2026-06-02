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
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder
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
    override val hasDownloadSupport = false
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private fun debugLog(message: String, throwable: Throwable? = null) {
        val errMessage = throwable?.message ?: "Unknown Error"
        val formatted = if (throwable != null) {
            "[DDL-Provider] $message | Exception: $errMessage"
        } else {
            "[DDL-Provider] $message"
        }
        
        if (throwable != null) {
            // 인자 개수 초과 에러 방지를 위해 에러 태그와 조립된 문자열 2개만 전달
            Log.e("DaddyLive-DEBUG", "$message: $errMessage")
            println(formatted)
        } else {
            Log.d("DaddyLive-DEBUG", message)
            println(formatted)
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        private const val posterUrl =
            "https://raw.githubusercontent.com/hsp1020/TestPlugins/refs/heads/master/DaddyLive/live-streaming2.png"

        fun convertGMTToLocalTime(gmtTime: String): String {
            val gmtFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            gmtFormat.timeZone = TimeZone.getTimeZone("GMT")

            val date: Date =
                gmtFormat.parse(gmtTime) ?: throw IllegalArgumentException("Invalid time format")

            val localFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            localFormat.timeZone =
                TimeZone.getDefault()

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
        return try {
            val elements = doc.select(".schedule__event")
            debugLog("searchResponseBuilder: Found ${elements.size} raw schedule elements.")
            elements.map { e ->
                val dataTitle = e.select(".schedule__eventHeader").attr("data-title")
                val eventTitle = e.select(".schedule__eventTitle").text()
                val time = e.select(".schedule__time").text()
                val formattedTime = convertGMTToLocalTime(time)
                newLiveSearchResponse("$formattedTime - $eventTitle", dataTitle){
                    this.posterUrl = Companion.posterUrl
                }
            }
        } catch (e: Exception) {
            debugLog("searchResponseBuilder Failed to map elements", e)
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        debugLog("getMainPage: Initiating call for main page: $mainUrl")
        return try {
            val response = app.get(mainUrl)
            debugLog("getMainPage: Received response with status ${response.code}")
            val doc = response.document
            
            val categories = doc.select(".schedule__category")
            debugLog("getMainPage: Parsing schedule categories. Total categories: ${categories.size}")
            
            val schedule = categories.map {
                val sectionTitle = it.select(".card__meta").text()
                val events = searchResponseBuilder(it)
                debugLog("getMainPage: Category '$sectionTitle' has ${events.size} events parsed.")
                HomePageList(sectionTitle, events, isHorizontalImages = true)
            }
            debugLog("getMainPage successfully loaded with ${schedule.size} homepage sections.")
            newHomePageResponse(schedule, false)
        } catch (e: Exception) {
            debugLog("getMainPage FAILED", e)
            throw e
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        debugLog("search: Initiated with query '$query'")
        return try {
            val response = app.get(mainUrl)
            debugLog("search: Page loaded with status ${response.code}")
            val doc = response.document
            val schedule = doc.select(".schedule__category").map {
                searchResponseBuilder(it)
            }.flatten()
            
            val matches = schedule.filter {
                query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "")
            }
            debugLog("search: Filtered results. Found ${matches.size} matches for '$query'")
            matches
        } catch (e: Exception) {
            debugLog("search FAILED for query '$query'", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        debugLog("load: Loading detailed stream information for URL: $url")
        return try {
            if (url.contains("watch.php") || url.contains("stream-")) {
                debugLog("load: Direct watch link detected. Commencing iframe extraction flow.")
                val response = app.get(url)
                debugLog("load: Watch page loaded with status ${response.code}")
                val doc = response.document
                val eventTitle = doc.select("h2").text().ifEmpty { doc.title().substringBefore(" | Watch") }
                val iframeSrc = doc.select("iframe#playerFrame").attr("src")
                debugLog("load: Found iframe#playerFrame src: '$iframeSrc'")
                
                val id = if (iframeSrc.isNotEmpty()) {
                    iframeSrc.substringAfter("stream-").substringBefore(".php")
                } else {
                    url.substringAfter("id=")
                }
                debugLog("load: Extracted Channel Stream ID: $id")
                
                val channels = listOf(Channel(eventTitle, "$mainUrl/%s/stream-$id.php"))
                debugLog("load SUCCESS: Built dynamic channel payload: ${channels.toJson()}")
                return newLiveStreamLoadResponse(eventTitle, url, dataUrl = channels.toJson()) {
                    this.posterUrl = Companion.posterUrl
                }
            }

            debugLog("load: Standard schedule load detected. Fetching mainUrl schedule matrix.")
            val response = app.get(mainUrl)
            debugLog("load: Main schedule page loaded with status ${response.code}")
            val doc = response.document
            
            val rawDataTitle = url.removePrefix("$mainUrl/")
            val dataTitle = try {
                val decoded = URLDecoder.decode(rawDataTitle, "UTF-8")
                debugLog("load: Decoded raw title '$rawDataTitle' -> '$decoded'")
                decoded
            } catch (e: Exception) {
                debugLog("load: URLDecoder failed. Reverting to raw string: '$rawDataTitle'", e)
                rawDataTitle
            }

            debugLog("load: Searching DOM matrix for data-title matched with: '$dataTitle'")
            val event = doc.select(".schedule__event").firstOrNull {
                val candidate = it.select("div.schedule__eventHeader").attr("data-title")
                candidate == dataTitle
            }
            
            if (event == null) {
                debugLog("load ERROR: FAILED to match any event in HTML with data-title: '$dataTitle'")
                throw NoSuchElementException("No scheduled event found for '$dataTitle'")
            }

            val eventTitle = event.select(".schedule__eventTitle").text()
            val channels = event.select(".schedule__channels > a").map {
                val id = it.attr("href").substringAfter("id=")
                Channel(it.text(), "$mainUrl/%s/stream-$id.php")
            }
            debugLog("load SUCCESS: Matched title='$eventTitle', generated channels=${channels.size}")
            Log.d("DDL Schedule - Channels", channels.toJson())
            
            val time = event.select(".schedule__time").text()
            val formattedTime = convertGMTToLocalTime(time)
            
            newLiveStreamLoadResponse("$formattedTime - $eventTitle", url, dataUrl = channels.toJson()){
                this.posterUrl = Companion.posterUrl
            }
        } catch (e: Exception) {
            debugLog("load FAILED for URL: $url", e)
            throw e
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        debugLog("loadLinks: Loading links using channel payload: $data")
        return try {
            val players = listOf("stream", "cast", "watch", "plus", "casting", "player")
            val channels = mapper.readValue<List<Channel>>(data)
            debugLog("loadLinks: Parsed channel count: ${channels.size}")

            val links = channels.map {
                players.map { l ->
                    val url = it.channelId.format(l)
                    debugLog("loadLinks: Formatted server player URL: $url")
                    it.channelName + " - $l" to url
                }
            }.flatten()

            debugLog("loadLinks: Transporting generated payload to Extractor. URL count: ${links.size}")
            DaddyLiveExtractor().getUrl(links.toJson(), null, subtitleCallback, callback)
            debugLog("loadLinks SUCCESS: Finished delegating links.")
            true
        } catch (e: Exception) {
            debugLog("loadLinks FAILED", e)
            false
        }
    }

    data class Channel(
        val channelName: String,
        val channelId: String
    )
}
