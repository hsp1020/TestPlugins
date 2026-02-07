package com.movieking

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovieKingPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(MovieKing())
        registerExtractorAPI(BcbcRedExtractor())
    }
}
