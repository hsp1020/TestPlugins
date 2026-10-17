package com.linkkf

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.app.Application
import android.content.Context
import com.hsp1020.pip.PipSubtitleLifecycle

@CloudstreamPlugin
class LinkkfPlugin : Plugin() {
    private var pipSubtitles: PipSubtitleLifecycle? = null

    override fun load(context: Context) {
        // All providers should be added in this manner
        registerMainAPI(Linkkf())
        pipSubtitles?.stop()
        pipSubtitles = (context.applicationContext as? Application)?.let { app ->
            PipSubtitleLifecycle(app) { it.equals("Linkkf", ignoreCase = true) }
                .also { it.start() }
        }
    }

    override fun beforeUnload() {
        pipSubtitles?.stop()
        pipSubtitles = null
    }
}
