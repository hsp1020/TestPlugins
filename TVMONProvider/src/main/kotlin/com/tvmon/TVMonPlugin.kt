package com.tvmom
import com.tvmon.TVMon // 예시 경로
import com.tvmon.BunnyPoorCdn
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TVMonPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(TVMon())
        registerExtractorAPI(BunnyPoorCdn())
    }
}
