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
import com.lagradost.cloudstream3.ui.player.DownloadedPlayerActivity
import java.lang.ref.WeakReference

/**
 * Player identity/lifecycle and heap snapshots; never changes player or buffer settings.
 * INFO events appear in CloudStream's saved Logcat without ADB configuration.
 * Correlate VIEW_DESTROY with CloudStream's CS3ExoPlayer releasePlayer log.
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
    private var probeScheduled = false
    private val stoppedActivities = HashMap<Int, Long>()
    private var stopSequence = 0L

    private fun snapshotHeap(fragmentId: Int, phase: String) {
        try {
            val runtime = Runtime.getRuntime()
            val memory = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(memory)
            val usedKiB = (runtime.totalMemory() - runtime.freeMemory()) / 1024L
            val nativeKiB = android.os.Debug.getNativeHeapAllocatedSize() / 1024L
            Log.i(TAG, "HEAP fragment=$fragmentId phase=$phase " +
                "javaUsedKiB=$usedKiB nativeAllocatedKiB=$nativeKiB totalPssKiB=${memory.totalPss}")
        } catch (error: Exception) {
            Log.i(TAG, "HEAP_UNAVAILABLE phase=$phase reason=${error.javaClass.simpleName}")
        }
    }

    private fun scheduleProbe() {
        if (!running || watched.isEmpty() || probeScheduled) return
        probeScheduled = true
        main.post(probe)
    }

    private fun observe(fragment: GeneratorPlayer, phase: String) {
        val entry = watched[fragment] ?: return
        val player = fragment.playerView?.player
        if (player !== entry.player?.get()) {
            val previous = entry.playerId
            entry.playerId = player?.let(System::identityHashCode)
            entry.player = player?.let { WeakReference(it) }
            entry.playbackState = -1
            entry.mediaHash = null
            Log.i(TAG, "PLAYER_BIND fragment=${System.identityHashCode(fragment)} phase=$phase " +
                "previous=$previous current=${entry.playerId}")
            if (player != null) snapshotHeap(System.identityHashCode(fragment), "PLAYER_BIND")
        }
        val state = player?.playbackState ?: -1
        val media = player?.currentMediaItem?.mediaId?.hashCode()
        if (state != entry.playbackState || media != entry.mediaHash) {
            entry.playbackState = state
            entry.mediaHash = media
            Log.i(TAG, "PLAYER_STATE fragment=${System.identityHashCode(fragment)} " +
                "player=${entry.playerId} state=$state mediaHash=$media phase=$phase")
        }
    }

    private val probe = object : Runnable {
        override fun run() {
            probeScheduled = false
            if (!running || watched.isEmpty()) return
            watched.keys.toList().forEach { observe(it, "POLL") }
            probeScheduled = true
            main.postDelayed(this, 750L)
        }
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            if (fragment is GeneratorPlayer) {
                watched.putIfAbsent(fragment, Snapshot())
                scheduleProbe()
                view.post { if (fragment.view === view) observe(fragment, "VIEW_CREATED") }
            }
        }
        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            if (fragment is GeneratorPlayer) {
                watched.putIfAbsent(fragment, Snapshot())
                observe(fragment, "RESUME")
                scheduleProbe()
            }
        }
        override fun onFragmentPaused(fm: FragmentManager, fragment: Fragment) {
            if (fragment is GeneratorPlayer) observe(fragment, "PAUSE")
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (fragment is GeneratorPlayer) {
                observe(fragment, "VIEW_DESTROY")
                Log.i(TAG, "VIEW_DESTROY fragment=${System.identityHashCode(fragment)} " +
                    "lastPlayer=${watched[fragment]?.playerId} (release not proven)")
                val fragmentId = System.identityHashCode(fragment)
                snapshotHeap(fragmentId, "VIEW_DESTROY")
                main.postDelayed({
                    if (running) snapshotHeap(fragmentId, "AFTER_EXIT_5S")
                }, 5_000L)
                watched.remove(fragment)
                if (watched.isEmpty()) {
                    main.removeCallbacks(probe)
                    probeScheduled = false
                }
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
        scheduleProbe()
    }

    fun start() {
        running = true
        application.registerActivityLifecycleCallbacks(this)
        PipLibraryNavigation.currentPlayerHost()?.let(::use)
        scheduleProbe()
    }
    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(probe)
        probeScheduled = false
        application.unregisterActivityLifecycleCallbacks(this)
        stoppedActivities.clear()
        unbind()
    }
    private fun unbind() {
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        host = null
        watched.clear()
        main.removeCallbacks(probe)
        probeScheduled = false
    }
    override fun onActivityStarted(activity: Activity) {
        PipLibraryNavigation.playerHost(activity)?.let(::use)
    }
    override fun onActivityResumed(activity: Activity) {
        stoppedActivities.remove(System.identityHashCode(activity))
        PipLibraryNavigation.playerHost(activity)?.let(::use)
    }
    override fun onActivityDestroyed(activity: Activity) {
        stoppedActivities.remove(System.identityHashCode(activity))
        if (host === activity || activity is DownloadedPlayerActivity) {
            Log.i(TAG, "ACTIVITY_DESTROY activity=${System.identityHashCode(activity)}")
            snapshotHeap(System.identityHashCode(activity), "ACTIVITY_DESTROY")
        }
        if (host === activity) unbind()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) {
        if (host !== activity && activity !is DownloadedPlayerActivity) return
        val activityId = System.identityHashCode(activity)
        val token = ++stopSequence
        stoppedActivities[activityId] = token
        Log.i(TAG, "ACTIVITY_STOP activity=$activityId playerFragments=${watched.size} " +
            "pip=${activity.isInPictureInPictureMode}")
        snapshotHeap(activityId, "ACTIVITY_STOP")
        main.postDelayed({
            if (running && stoppedActivities[activityId] == token) {
                stoppedActivities.remove(activityId)
                snapshotHeap(activityId, "AFTER_ACTIVITY_STOP_5S")
            }
        }, 5_000L)
    }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
