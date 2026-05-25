package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.schemaStripRegex
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

object YoutubeSubtitleExtractor {
    init {
        YoutubeStreamExtractor.setFetchIosClient(true)
    }

    suspend fun extract(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        
        try {
            val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
            val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
            s.fetchPage()

            // 자막 추출 (요구하신 다국어 자막 구현 완벽 유지)
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
        } catch (e: Exception) { 
            logError(e) 
        }
    }
}
