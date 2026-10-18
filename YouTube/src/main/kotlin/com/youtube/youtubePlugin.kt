package com.hsp1020.youtube

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YoutubeTokenPlugin : Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("YouTube", Context.MODE_PRIVATE)
        registerMainAPI(YoutubeProvider(sharedPref))
        registerExtractorAPI(YoutubeExtractor())
        openSettings = { ctx ->
            (ctx as? AppCompatActivity)?.let { activity ->
                YoutubeSettingsBottomSheet.show(activity.supportFragmentManager, sharedPref)
            }
        }
    }
}
