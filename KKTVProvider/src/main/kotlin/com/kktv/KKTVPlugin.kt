// v1.1 - 쌩웹뷰(Raw WebView) 생성을 위한 Context 전역 저장 로직 추가
package com.kktv
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KKTVPlugin: Plugin() {
    override fun load(context: Context) {
        // [수정된 부분] KingkongTv() 를 KKTVProvider() 로 바꿔주세요!
        registerMainAPI(KKTVProvider()) 
    }
}
