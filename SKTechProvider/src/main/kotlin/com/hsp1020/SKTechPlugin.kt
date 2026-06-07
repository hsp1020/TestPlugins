package com.hsp1020 // 패키지명 교정 완료

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SKTechPlugin: Plugin() {
    override fun load(context: Context) {
        LiveEventsProvider.context = context
        
        // Register Live Events provider
        registerMainAPI(LiveEventsProvider())
    }
}
