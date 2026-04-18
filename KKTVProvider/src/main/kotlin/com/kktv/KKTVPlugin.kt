// v1.1 - 쌩웹뷰(Raw WebView) 생성을 위한 Context 전역 저장 로직 추가
package com.KingkongTv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KingkongTvPlugin : Plugin() {
    companion object {
        var pluginContext: Context? = null
    }

    override fun load(context: Context) {
        // WebView 생성을 위해 context 저장
        pluginContext = context
        registerMainAPI(KingkongTv())
    }
}
