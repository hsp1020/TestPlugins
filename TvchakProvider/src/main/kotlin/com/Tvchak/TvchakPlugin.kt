package com.lagradost.cloudstream3.plugins.tvchak

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TvchakPlugin : BasePlugin() {
    override fun load() {
        // 프로바이더 인스턴스 주입 (이제 같은 패키지라 완벽하게 인식함)
        registerMainAPI(TvchakProvider())
    }
}
