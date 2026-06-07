package com.cncverse

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SKTechPlugin: Plugin() {
    override fun load(context: Context) {
        LiveEventsProvider.context = context
        
        // 스포츠 중계용 라이브 이벤트 프로바이더만 안전하게 단독 등록합니다.
        registerMainAPI(LiveEventsProvider())
    }
}
