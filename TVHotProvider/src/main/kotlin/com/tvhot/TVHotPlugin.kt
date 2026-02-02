package com.tvhot

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TVHotPlugin: Plugin() {
    override fun load(context: Context) {
        // TVHot 메인 클래스 등록
        registerMainAPI(TVHot())
        // BunnyPoorCdn 추출기 등록
        registerExtractorAPI(BunnyPoorCdn())
    }
}
