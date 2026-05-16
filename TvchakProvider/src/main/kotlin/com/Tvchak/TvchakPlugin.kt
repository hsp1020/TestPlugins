package com.lagradost.cloudstream3.plugins.tvchak

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TvchakPlugin : BasePlugin() {
    override fun load() {
        // 프로바이더 인스턴스 주입
        registerMainAPI(TvchakProvider())

    }
}
