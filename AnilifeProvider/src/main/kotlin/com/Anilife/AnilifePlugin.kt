package com.anilife

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import android.app.Application
import android.os.Handler
import android.os.Looper

@CloudstreamPlugin
class AnilifePlugin: Plugin() {
    private val main = Handler(Looper.getMainLooper())
    private var cards: AnilifeCardHook? = null

    override fun load(context: Context) {
        // Anilife 프로바이더 등록
        registerMainAPI(Anilife())
        (context.applicationContext as? Application)?.let { application ->
            val hook = AnilifeCardHook(application)
            cards = hook
            main.post { if (cards === hook) hook.start() }
        }
    }

    override fun beforeUnload() {
        val hook = cards
        cards = null
        if (hook != null) main.post { hook.stop() }
        super.beforeUnload()
    }
}
