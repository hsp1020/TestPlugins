package com.kktv

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KKTVPlugin : Plugin() {
    override fun load(context: Context) {
        // 1. 설정 저장용 SharedPreferences 생성
        val sharedPref = context.getSharedPreferences("CUTV_PREFS", Context.MODE_PRIVATE)
        
        // 2. CUTVProvider에 SharedPreferences를 주입하며 메인 API 등록
        registerMainAPI(CUTVProvider(sharedPref))

        // 3. Showbox 로직 차용: 설정 톱니바퀴 클릭 시 프래그먼트 호출
        val activity = context as AppCompatActivity
        openSettings = {
            val frag = SettingsFragment(this, sharedPref)
            frag.show(activity.supportFragmentManager, "CUTVSettingsFrag")
        }
    }
}
