package com.tvwiki

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TVWikiPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(TVHot())
        registerExtractorAPI(BunnyPoorCdn())
    }
}
