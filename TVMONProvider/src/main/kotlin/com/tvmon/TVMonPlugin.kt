package com.tvmom

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TVMonPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(TVHot())
        registerExtractorAPI(BunnyPoorCdn())
    }
}
