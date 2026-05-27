package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.schemaStripRegex
import com.lagradost.cloudstream3.ar.youtube.YoutubeUtils
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import java.util.concurrent.ConcurrentHashMap

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    companion object {
        // 간소화된 캐시
        private val cacheLinks = ConcurrentHashMap<String, List<ExtractorLink>>()
        private val cacheSubtitles = ConcurrentHashMap<String, List<SubtitlesStream>>()

        init {
            // iOS 클라이언트 사용: PO Token 문제가 완전히 해결되지 않더라도, 
            // 플레이어에게 URL을 직접 전달하기 때문에 1분 후 차단될 가능성이 매우 낮습니다.
            YoutubeStreamExtractor.setFetchIosClient(true)
        }
    }

    override fun getExtractorUrl(id: String): String = "$mainUrl/watch?v=$id"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl

        // 캐시된 데이터 사용
        val cached = cacheLinks[videoId]
        if (cached != null) {
            cached.forEach { callback(it) }
            cacheSubtitles[videoId]?.mapNotNull { convertSubtitle(it) }?.forEach { subtitleCallback(it) }
            return
        }

        try {
            println("[YT_DEBUG] Fetching streams for $videoId")

            val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
            val extractor = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
            extractor.fetchPage()

            val links = mutableListOf<ExtractorLink>()
            val seenUrls = mutableSetOf<String>()

            // --- 비디오 스트림 ---
            for (vs in extractor.videoOnlyStreams ?: emptyList()) {
                val streamUrl = vs.content ?: continue
                if (!seenUrls.add(streamUrl)) continue

                val quality = vs.height ?: 0
                val label = quality.toString()

                links.add(
                    newExtractorLink(
                        source = name,
                        name = label,
                        url = streamUrl,
                        type = INFER_TYPE  // 플레이어가 직접 처리
                    ) {
                        this.quality = quality
                        this.referer = mainUrl
                        this.headers = mapOf(
                            "User-Agent" to YoutubeUtils.IOS_USER_AGENT,
                            "Origin" to "https://www.youtube.com",
                            "Referer" to "https://www.youtube.com"
                        )
                    }
                )
            }

            // --- 오디오 스트림 ---
            for (audio in extractor.audioStreams ?: emptyList()) {
                val streamUrl = audio.content ?: continue
                if (!seenUrls.add(streamUrl)) continue

                val bitrate = audio.bitrate ?: 128000
                val lang = audio.audioTrackId?.substringBefore(".") ?: "Default"

                links.add(
                    newExtractorLink(
                        source = name,
                        name = "$lang ${bitrate/1000}kbps",
                        url = streamUrl,
                        type = INFER_TYPE  // 플레이어가 직접 처리
                    ) {
                        this.quality = bitrate
                        this.referer = mainUrl
                        this.headers = mapOf(
                            "User-Agent" to YoutubeUtils.IOS_USER_AGENT,
                            "Origin" to "https://www.youtube.com",
                            "Referer" to "https://www.youtube.com"
                        )
                    }
                )
            }

            // --- 자막 ---
            val subs = extractor.subtitlesDefault?.filterNotNull() ?: emptyList()
            cacheSubtitles[videoId] = subs

            // --- 외부 다운로더 링크 (안전 장치) ---
            links.add(
                newExtractorLink("YouTube Downloader", "Cobalt Web Download(4K)",
                    "https://qwkuns.me/#https://www.youtube.com/watch?v=$videoId", type = INFER_TYPE) {
                    this.quality = 2160
                    this.referer = mainUrl
                }
            )
            links.add(
                newExtractorLink("YouTube Downloader", "SOSYouTube Web Download(1080p)",
                    "https://www.sosyoutube.com/watch?v=$videoId", type = INFER_TYPE) {
                    this.quality = 1080
                    this.referer = mainUrl
                }
            )

            cacheLinks[videoId] = links

            // --- 콜백 ---
            links.forEach { callback(it) }
            subs.mapNotNull { convertSubtitle(it) }.forEach { subtitleCallback(it) }

            println("[YT_DEBUG] Successfully fetched ${links.size} links and ${subs.size} subtitles")

        } catch (e: Exception) {
            logError(e)
            println("[YT_DEBUG] Extraction failed: ${e.message}")
        }
    }

    private fun convertSubtitle(sub: SubtitlesStream): SubtitleFile? {
        return try {
            val lang = sub.locale?.language ?: return null
            var url = sub.content ?: sub.getUrl() ?: return null
            if (url.startsWith("http")) {
                url = url.replace(Regex("&fmt=[^&]+"), "")
                if (!url.contains("fmt=srt")) url += "&fmt=srt"
            }
            newSubtitleFile(lang, url)
        } catch (_: Exception) {
            null
        }
    }
}
