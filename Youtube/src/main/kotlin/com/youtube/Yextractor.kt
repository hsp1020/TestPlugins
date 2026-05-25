package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.schemaStripRegex
import com.lagradost.cloudstream3.ar.youtube.YoutubeUtils
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube (Subtitles Only)"

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
        println("[YT_DEBUG] [getUrl] 자막 전용 추출기 호출됨. 원본 URL: $url")
        val cleanedUrl = url.replace(schemaStripRegex, "")
        
        try {
            // NewPipe 라이브러리를 활용하여 영상의 모든 자막(다국어 포함)을 추출 (기존 자막 언어 구현 유지)
            val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
            val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
            s.fetchPage()

            val subs = s.subtitlesDefault?.filterNotNull() ?: emptyList()
            println("[YT_DEBUG] [getUrl] 수집된 자막 트랙 개수: ${subs.size}")

            subs.forEach { ss ->
                try {
                    val lang = ss.locale?.language ?: return@forEach
                    var subUrl = ss.content ?: ss.getUrl() ?: return@forEach
                    
                    if (subUrl.startsWith("http")) {
                        // 자막 포맷을 Cloudstream 호환성이 높은 srt로 강제 변경
                        subUrl = subUrl.replace(Regex("&fmt=[^&]+"), "")
                        if (!subUrl.contains("fmt=srt")) {
                            subUrl = "$subUrl&fmt=srt"
                        }
                    }
                    subtitleCallback(newSubtitleFile(lang, subUrl))
                } catch (e: Exception) { 
                    println("[YT_DEBUG] [getUrl] 개별 자막 처리 중 에러: ${e.message}")
                }
            }
            
            // 주의: 로컬 DASH 서버 및 영상 스트림(비디오/오디오) 병합 추출 로직은
            // 앱 자체 내장 추출기 사용을 위해 의도적으로 완전히 제거되었습니다.
            
        } catch (e: Exception) { 
            println("[YT_DEBUG] [getUrl] NewPipe 자막 분석 도중 에러가 감지되었습니다: ${e.message}")
            logError(e) 
        }
    }
}
