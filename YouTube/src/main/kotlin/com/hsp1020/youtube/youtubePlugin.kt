package com.hsp1020.youtube

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YoutubeTokenPlugin : Plugin() {
    private val main = Handler(Looper.getMainLooper())
    private var cards: YouTubeCardHook? = null

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("YouTube", Context.MODE_PRIVATE)
        registerMainAPI(YoutubeProvider(sharedPref))
        registerExtractorAPI(YoutubeExtractor())
        openSettings = { ctx ->
            (ctx as? AppCompatActivity)?.let { activity ->
                YoutubeSettingsBottomSheet.show(activity.supportFragmentManager, sharedPref)
            }
        }
        (context.applicationContext as? Application)?.let { application ->
            val hook = YouTubeCardHook(application)
            cards = hook
            main.post { if (cards === hook) hook.start() }
        }
    }

    override fun beforeUnload() {
        val hook = cards
        cards = null
        openSettings = null
        if (hook != null) main.post { hook.stop() }
        super.beforeUnload()
    }
}
