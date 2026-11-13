package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import java.lang.ref.WeakReference

/**
 * Opt-in player identity/lifecycle probe; never changes player or buffer settings.
 * Correlate VIEW_DESTROY with CloudStream's CS3ExoPlayer releasePlayer log.
 * Enable before reloading Elevate: adb shell setprop log.tag.ElevateMedia3Trace DEBUG
 */
internal class ElevatePlaybackTrace(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private companion object { const val TAG = "ElevateMedia3Trace" }
    private val main = Handler(Looper.getMainLooper())
    private var host: FragmentActivity? = null
    private var running = false

    private class Snapshot {
        var player: WeakReference<androidx.media3.common.Player>? = null
        var playerId: Int? = null
        var playbackState = -1
        var mediaHash: Int? = null
    }
    private val watched = LinkedHashMap<GeneratorPlayer, Snapshot>()

    private fun observe(fragment: GeneratorPlayer, phase: String) {
        val entry = watched[fragment] ?: return
        val player = fragment.playerView?.player
        if (player !== entry.player?.get()) {
            val previous = entry.playerId
            entry.playerId = player?.let(System::identityHashCode)
            entry.player = player?.let { WeakReference(it) }
            entry.playbackState = -1
            entry.mediaHash = null
            Log.d(TAG, "PLAYER_BIND fragment=${System.identityHashCode(fragment)} phase=$phase " +
                "previous=$previous current=${entry.playerId}")
        }
        val state = player?.playbackState ?: -1
        val media = player?.currentMediaItem?.mediaId?.hashCode()
        if (state != entry.playbackState || media != entry.mediaHash) {
            entry.playbackState = state
            entry.mediaHash = media
            Log.d(TAG, "PLAYER_STATE fragment=${System.identityHashCode(fragment)} " +
                "player=${entry.playerId} state=$state mediaHash=$media phase=$phase")
        }
    }

    private val probe = object : Runnable {
        override fun run() {
            if (!running) return
            watched.keys.toList().forEach { observe(it, "POLL") }
            main.postDelayed(this, 750L)
        }
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            if (fragment is GeneratorPlayer) {
                watched.putIfAbsent(fragment, Snapshot())
                view.post { if (fragment.view === view) observe(fragment, "VIEW_CREATED") }
            }
        }
        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            if (fragment is GeneratorPlayer) {
                watched.putIfAbsent(fragment, Snapshot())
                observe(fragment, "RESUME")
            }
        }
        override fun onFragmentPaused(fm: FragmentManager, fragment: Fragment) {
            if (fragment is GeneratorPlayer) observe(fragment, "PAUSE")
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (fragment is GeneratorPlayer) {
                observe(fragment, "VIEW_DESTROY")
                Log.d(TAG, "VIEW_DESTROY fragment=${System.identityHashCode(fragment)} " +
                    "lastPlayer=${watched[fragment]?.playerId} (release not proven)")
                watched.remove(fragment)
            }
        }
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        unbind()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { fragment ->
                if (fragment is GeneratorPlayer && fragment.view != null) {
                    watched.putIfAbsent(fragment, Snapshot())
                    observe(fragment, "SCAN")
                }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    fun start() {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        running = true
        application.registerActivityLifecycleCallbacks(this)
        PipLibraryNavigation.currentPlayerHost()?.let(::use)
        main.post(probe)
    }
    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(probe)
        application.unregisterActivityLifecycleCallbacks(this)
        unbind()
    }
    private fun unbind() {
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        host = null
        watched.clear()
    }
    override fun onActivityStarted(activity: Activity) {
        PipLibraryNavigation.playerHost(activity)?.let(::use)
    }
    override fun onActivityResumed(activity: Activity) {
        PipLibraryNavigation.playerHost(activity)?.let(::use)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) unbind()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
