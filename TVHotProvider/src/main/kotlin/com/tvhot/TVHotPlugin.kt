package com.tvhot

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TVHotPlugin: Plugin() {
    override fun load(context: Context) {
        // TVHot 메인 프로바이더 등록
        registerMainAPI(TVHot())
        
        // Extractor 등록 (BunnyPoorCdn)
        registerExtractorAPI(BunnyPoorCdn())
    }
}
