package com.tvhot

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TVHotPlugin: Plugin() {
    override fun load(context: Context) {
        // TVHot 메인 클래스만 등록
        registerMainAPI(TVHot())
        // BunnyPoorCdn 추출기는 제거 (더 이상 필요 없음)
    }
}
