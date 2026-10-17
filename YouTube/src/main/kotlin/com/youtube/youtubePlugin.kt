package com.hsp1020.youtube

import android.app.Application
import android.content.Context
import com.hsp1020.pip.PipSubtitleLifecycle
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YoutubeTokenPlugin: Plugin() {
    private var pipSubtitles: PipSubtitleLifecycle? = null

    override fun load(context: Context) {

        val sharedPref = context.getSharedPreferences("YouTube", Context.MODE_PRIVATE)

        registerMainAPI(com.hsp1020.youtube.YoutubeProvider(sharedPref))
        pipSubtitles?.stop()
        pipSubtitles = (context.applicationContext as? Application)?.let { app ->
            PipSubtitleLifecycle(app) { it.equals("YouTube", ignoreCase = true) }
                .also { it.start() }
        }

        openSettings = { ctx ->

            val activity = ctx as? AppCompatActivity

            if (activity != null) {

                com.hsp1020.youtube.YoutubeSettingsBottomSheet.show(activity.supportFragmentManager, sharedPref)
            }
        }
    }

    override fun beforeUnload() {
        pipSubtitles?.stop()
        pipSubtitles = null
    }
}




















