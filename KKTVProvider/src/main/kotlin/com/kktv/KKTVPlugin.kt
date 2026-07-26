package com.kktv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KKTVPlugin : Plugin() {
    override fun load(context: Context) {
        // 프로바이더 등록
        registerMainAPI(KKTVProvider())
    }
}
