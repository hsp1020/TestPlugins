package com.kktv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KKTVPlugin: Plugin() {
    override fun load(context: Context) {
        // 이 부분을 CUTVProvider() 로 변경해주세요!
        registerMainAPI(CUTVProvider()) 
    }
}
