package com.lagradost.cloudstream3.plugins.tvchak

import android.app.Application
import com.hsp1020.pip.PipSubtitleLifecycle
import com.lagradost.cloudstream3.CloudStreamApp

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TvchakPlugin : BasePlugin() {
    private var pipSubtitles: PipSubtitleLifecycle? = null

    override fun load() {
        // 프로바이더 인스턴스 주입 (이제 같은 패키지라 완벽하게 인식함)
        registerMainAPI(TvchakProvider())
        pipSubtitles?.stop()
        pipSubtitles = (CloudStreamApp.context?.applicationContext as? Application)?.let { app ->
            PipSubtitleLifecycle(app) { it.equals("TVChak", ignoreCase = true) }
                .also { it.start() }
        }
    }

    override fun beforeUnload() {
        pipSubtitles?.stop()
        pipSubtitles = null
    }
}
