package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.schemaStripRegex
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    init {
        YoutubeStreamExtractor.setFetchIosClient(true)
    }

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/watch?v=$id"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        
        try {
            val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
            val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
            s.fetchPage()

            // 1. 자막 추출 (다국어 유지)
            val subs = s.subtitlesDefault?.filterNotNull() ?: emptyList()
            subs.forEach { ss ->
                try {
                    val lang = ss.locale?.language ?: return@forEach
                    var subUrl = ss.content ?: ss.getUrl() ?: return@forEach
                    
                    if (subUrl.startsWith("http")) {
                        subUrl = subUrl.replace(Regex("&fmt=[^&]+"), "")
                        if (!subUrl.contains("fmt=srt")) {
                            subUrl = "$subUrl&fmt=srt"
                        }
                    }
                    subtitleCallback(newSubtitleFile(lang, subUrl))
                } catch (e: Exception) { 
                }
            }

            // 2. 403 에러 방지용 NewPipe 직접 추출 (Muxed Streams - 720p/360p 백업)
            val muxedList = s.videoStreams ?: emptyList()
            muxedList.forEach { vs ->
                try {
                    val mUrl = vs.content ?: return@forEach
                    val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                    val label = if (height > 0) "${height}p" else "Video"
                    
                    // 수정된 부분: referer와 quality를 중괄호 {} 내부로 이동
                    callback(
                        newExtractorLink(
                            "YouTube (NewPipe)",
                            "YouTube $label",
                            mUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = "https://www.youtube.com/"
                            this.quality = height
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
                                "Origin" to "https://www.youtube.com"
                            )
                        }
                    )
                } catch (e: Exception) { 
                }
            }
        } catch (e: Exception) { 
            logError(e) 
        }
    }
}
