package com.hsp1020

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.mapper
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*

class DaddyLiveScheduleProvider : MainAPI() {
    override var mainUrl = "https://dlhd.pk"
    override var name = "DaddyLive"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true

    private fun debugLog(message: String, throwable: Throwable? = null) {
        val errMessage = throwable?.message ?: "Unknown Error"
        val formatted = if (throwable != null) {
            "[DDL-DEBUG] $message | Exception: $errMessage"
        } else {
            "[DDL-DEBUG] $message"
        }
        
        if (throwable != null) {
            Log.e("DDL-DEBUG", "[DDL-DEBUG] $message: $errMessage")
            println(formatted)
        } else {
            Log.d("DDL-DEBUG", "[DDL-DEBUG] $message")
            println(formatted)
        }
    }

    companion object {
        private const val posterUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/DaddyLive/daddylive.jpg"
        fun convertGMTToLocalTime(gmtTime: String): String {
            val gmtFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("GMT") }
            val date = gmtFormat.parse(gmtTime) ?: return gmtTime
            val localFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getDefault() }
            return localFormat.format(date)
        }
    }

    private fun searchResponseBuilder(doc: Element): List<LiveSearchResponse> {
        return doc.select(".schedule__event").map { e ->
            val dataTitle = e.select(".schedule__eventHeader").attr("data-title")
            val eventTitle = e.select(".schedule__eventTitle").text()
            val time = e.select(".schedule__time").text()
            newLiveSearchResponse("${convertGMTToLocalTime(time)} - $eventTitle", dataTitle) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val schedule = doc.select(".schedule__category").map {
            HomePageList(it.select(".card__meta").text(), searchResponseBuilder(it), false)
        }
        return newHomePageResponse(schedule, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(mainUrl).document
        val schedule = doc.select(".schedule__category").flatMap { searchResponseBuilder(it) }
        return schedule.filter { query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "") }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("watch.php") || url.contains("stream-")) {
            val doc = app.get(url).document
            val eventTitle = doc.select("h2").text().ifEmpty { doc.title().substringBefore(" | Watch") }
            val iframeSrc = doc.select("iframe#playerFrame").attr("src")
            val id = if (iframeSrc.isNotEmpty()) iframeSrc.substringAfter("stream-").substringBefore(".php") else url.substringAfter("id=")
            
            val channels = listOf(Channel(eventTitle, "$mainUrl/%s/stream-$id.php"))
            // AppUtils.toJson() 대신 mapper 직접 호출로 ABI 충돌 원천 차단
            val channelsJson = mapper.writeValueAsString(channels)

            return newLiveStreamLoadResponse(eventTitle, url, dataUrl = channelsJson) { this.posterUrl = posterUrl }
        }

        val doc = app.get(mainUrl).document
        val rawDataTitle = url.removePrefix("$mainUrl/")
        val dataTitle = try { URLDecoder.decode(rawDataTitle, "UTF-8") } catch (e: Exception) { rawDataTitle }

        val event = doc.select(".schedule__event").firstOrNull { it.select("div.schedule__eventHeader").attr("data-title") == dataTitle }
            ?: throw NoSuchElementException("No scheduled event found")

        val eventTitle = event.select(".schedule__eventTitle").text()
        
        val channels = event.select(".schedule__channels > a").map {
            Channel(it.text(), "$mainUrl/%s/stream-${it.attr("href").substringAfter("id=")}.php")
        }
        val channelsJson = mapper.writeValueAsString(channels)
        
        val time = event.select(".schedule__time").text()
        return newLiveStreamLoadResponse("${convertGMTToLocalTime(time)} - $eventTitle", url, dataUrl = channelsJson) { this.posterUrl = posterUrl }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val players = listOf("stream", "cast", "watch", "plus", "casting", "player")
        
        // AppUtils.parseJson 대신 mapper.readValue 안전 호출
        val channels = try {
            mapper.readValue(data, object : TypeReference<List<Channel>>() {})
        } catch (e: Exception) {
            debugLog("loadLinks: JSON Parsing Error", e)
            emptyList()
        }

        val links = channels.flatMap { channel ->
            players.map { player ->
                channel.channelName + " - $player" to channel.channelId.format(player)
            }
        }
        
        val linksJson = mapper.writeValueAsString(links)
        DaddyLiveExtractor().getUrl(linksJson, null, subtitleCallback, callback)
        return true
    }

    data class Channel(val channelName: String, val channelId: String)
}
