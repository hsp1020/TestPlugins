package com.soop

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SOOPPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SOOP())
    }
}
