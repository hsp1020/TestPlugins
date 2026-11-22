package com.hsp1020

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.player.DownloadedPlayerActivity
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer

@CloudstreamPlugin
class DaddyLivePlugin : Plugin() {
    private var playerCleanup: PlayerExitCleanup? = null
    private var cardHook: DaddyLiveCardHook? = null

    override fun load(context: Context) {
        registerMainAPI(DaddyLiveScheduleProvider())
        registerExtractorAPI(DaddyLiveExtractor())
        (context.applicationContext as? Application)?.let { application ->
            val hook = DaddyLiveCardHook(application)
            cardHook = hook
            Handler(Looper.getMainLooper()).post { if (cardHook === hook) hook.start() }
        }
        playerCleanup = (context.applicationContext as? Application)?.let { application ->
            PlayerExitCleanup(application).also { it.start() }
        }
    }

    override fun beforeUnload() {
        cardHook?.let { hook -> Handler(Looper.getMainLooper()).post { hook.stop() } }
        cardHook = null
        playerCleanup?.stop()
        playerCleanup = null
        DaddyLiveExtractor.releaseSessionsOnPlayerExit("plugin_unload")
    }
}

/** Observe native and Elevate PiP GeneratorPlayer exits without treating pause/PiP as exit. */
private class PlayerExitCleanup(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val hosts = LinkedHashMap<FragmentActivity, FragmentManager.FragmentLifecycleCallbacks>()
    private var running = false

    private val checkExit = Runnable {
        if (running && hosts.keys.none { hasPlayer(it) })
            DaddyLiveExtractor.releaseSessionsOnPlayerExit("player_exit")
    }

    fun start() {
        if (running) return
        running = true
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? FragmentActivity)?.let(::watch)
    }

    fun stop() {
        running = false
        main.removeCallbacks(checkExit)
        application.unregisterActivityLifecycleCallbacks(this)
        hosts.forEach { (host, callback) ->
            host.supportFragmentManager.unregisterFragmentLifecycleCallbacks(callback)
        }
        hosts.clear()
    }

    private fun watch(host: FragmentActivity) {
        if ((host !is MainActivity && host !is DownloadedPlayerActivity) ||
            hosts.containsKey(host)) return
        val callback = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentCreated(fm: FragmentManager, fragment: Fragment, savedInstanceState: Bundle?) {
                if (fragment is GeneratorPlayer) DaddyLiveExtractor.onPlayerCreated()
            }

            override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
                if (fragment is GeneratorPlayer && !host.isChangingConfigurations) checkLater()
            }

            override fun onFragmentDestroyed(fm: FragmentManager, fragment: Fragment) {
                if (fragment is GeneratorPlayer && !host.isChangingConfigurations) checkLater()
            }
        }
        host.supportFragmentManager.registerFragmentLifecycleCallbacks(callback, true)
        hosts[host] = callback
    }

    private fun checkLater() {
        main.removeCallbacks(checkExit)
        // Allow player replacements and PiP Activity handoff to settle before checking for exit.
        main.postDelayed(checkExit, 1000L)
    }

    private fun hasPlayer(host: FragmentActivity): Boolean {
        if (host.isFinishing || host.isDestroyed) return false
        fun containsPlayer(manager: FragmentManager): Boolean =
            manager.fragments.any { fragment ->
                fragment.isAdded && (
                    fragment is GeneratorPlayer && fragment.view != null ||
                    containsPlayer(fragment.childFragmentManager)
                )
            }
        return containsPlayer(host.supportFragmentManager)
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        (activity as? FragmentActivity)?.let(::watch)
        if (activity is DownloadedPlayerActivity) DaddyLiveExtractor.onPlayerCreated()
    }

    override fun onActivityStarted(activity: Activity) {
        (activity as? FragmentActivity)?.let(::watch)
    }

    override fun onActivityResumed(activity: Activity) {
        (activity as? FragmentActivity)?.let {
            watch(it)
            if (hasPlayer(it)) DaddyLiveExtractor.onPlayerCreated()
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        val host = activity as? FragmentActivity ?: return
        hosts.remove(host)?.let { callback ->
            host.supportFragmentManager.unregisterFragmentLifecycleCallbacks(callback)
            if (!host.isChangingConfigurations) checkLater()
        }
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
