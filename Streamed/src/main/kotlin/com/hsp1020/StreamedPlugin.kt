package com.hsp1020

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class StreamedPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Streamed())
        registerExtractorAPI(EmbedStreams(context))
        registerExtractorAPI(EmbedSporty(context))
        registerExtractorAPI(EmbedST(context)) // embed.st 처리용 익스트랙터 추가 등록
    }
}
