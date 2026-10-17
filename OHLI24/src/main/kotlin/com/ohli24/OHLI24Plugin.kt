package com.ohli24

import android.app.Application
import com.hsp1020.pip.PipSubtitleLifecycle
import com.lagradost.cloudstream3.CloudStreamApp

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OHLI24Plugin: BasePlugin() {
    private var pipSubtitles: PipSubtitleLifecycle? = null

    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(OHLI24())
        registerExtractorAPI(Cdndania())
        registerExtractorAPI(MichealCDN())
        pipSubtitles?.stop()
        pipSubtitles = (CloudStreamApp.context?.applicationContext as? Application)?.let { app ->
            PipSubtitleLifecycle(app) { it.equals("OHLI24", ignoreCase = true) }
                .also { it.start() }
        }
    }

    override fun beforeUnload() {
        pipSubtitles?.stop()
        pipSubtitles = null
    }
}
