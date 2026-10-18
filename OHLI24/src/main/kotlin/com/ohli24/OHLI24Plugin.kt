package com.ohli24

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OHLI24Plugin: BasePlugin() {
    override fun load() {
        registerMainAPI(OHLI24())
        registerExtractorAPI(Cdndania())
        registerExtractorAPI(MichealCDN())
    }
}
