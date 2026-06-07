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
        registerExtractorAPI(EmbedST(context)) // 가독성에 맞춰 깔끔하게 정렬하여 등록
    }
}
