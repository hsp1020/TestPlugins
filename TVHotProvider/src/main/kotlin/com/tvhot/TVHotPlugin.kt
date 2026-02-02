package com.tvhot

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TVHotPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TVHot())
        registerExtractorAPI(BunnyPoorCdn())
    }
}
