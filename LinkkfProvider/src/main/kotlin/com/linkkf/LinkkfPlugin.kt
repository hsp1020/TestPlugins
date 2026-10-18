package com.linkkf

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LinkkfPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Linkkf())
    }
}
