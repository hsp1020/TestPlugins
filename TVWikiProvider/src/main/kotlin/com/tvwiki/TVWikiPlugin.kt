package com.tvhot

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TVHotPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(TVHot())
        registerExtractorAPI(BunnyPoorCdn())
    }
}
