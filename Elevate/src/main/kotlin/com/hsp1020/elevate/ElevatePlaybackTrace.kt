package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Activity lifecycle evidence; optional memory checkpoints during a manual profile only. */
internal class ElevatePlaybackTrace(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private var running = false
    private val handler = Handler(Looper.getMainLooper())
    private val hosts = WeakHashMap<FragmentActivity, FragmentManager.FragmentLifecycleCallbacks>()
    private val listeners = WeakHashMap<ExoPlayer, AnalyticsListener>()
    private val watchers = WeakHashMap<Fragment, Runnable>()

    private fun log(stage: String, detail: String) =
        Log.i("ElevateHlsStartupTrace", "stage=$stage $detail")

    private fun observe(fragment: Fragment) {
        if (fragment !is AbstractPlayerFragment<*>) return
        watchers.remove(fragment)?.let(handler::removeCallbacks)
        val weak = WeakReference(fragment)
        var checks = 0
        val watcher = object : Runnable {
            override fun run() {
                val target = weak.get() as? AbstractPlayerFragment<*> ?: return
                if (!running || !target.isAdded) return
                val player = target.playerView?.player as? ExoPlayer
                if (player != null && !listeners.containsKey(player)) attach(player)
                if (++checks < 120) handler.postDelayed(this, 250L)
            }
        }
        watchers[fragment] = watcher
        handler.post(watcher)
    }

    private fun attach(player: ExoPlayer) {
        val start = SystemClock.elapsedRealtime()
        val id = System.identityHashCode(player)
        fun elapsed() = SystemClock.elapsedRealtime() - start
        val listener = object : AnalyticsListener {
            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                log("STATE", "id=$id ms=${elapsed()} state=$state")
            }
            override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
                log("LOAD_START", "id=$id ms=${elapsed()} task=${info.loadTaskId} dataType=${data.dataType} track=${data.trackType} host=${info.uri.host} ext=${info.uri.lastPathSegment?.substringAfterLast('.')}")
            }
            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
                log("LOAD_DONE", "id=$id ms=${elapsed()} task=${info.loadTaskId} dataType=${data.dataType} bytes=${info.bytesLoaded} loadMs=${info.loadDurationMs} host=${info.uri.host}")
            }
            override fun onLoadError(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
                log("LOAD_ERROR", "id=$id ms=${elapsed()} task=${info.loadTaskId} dataType=${data.dataType} error=${error.javaClass.simpleName} canceled=$wasCanceled")
            }
            override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, data: MediaLoadData) {
                log("FORMAT", "id=$id ms=${elapsed()} track=${data.trackType} mime=${data.trackFormat?.sampleMimeType}")
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                log("VIDEO_DECODER", "id=$id ms=${elapsed()} initMs=$initializationDurationMs decoder=$decoderName")
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                log("AUDIO_DECODER", "id=$id ms=${elapsed()} initMs=$initializationDurationMs decoder=$decoderName")
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                log("FIRST_FRAME", "id=$id ms=${elapsed()}")
            }
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
                log("ERROR", "id=$id ms=${elapsed()} code=${error.errorCode}")
            }
        }
        listeners[player] = listener
        player.addAnalyticsListener(listener)
        log("ATTACH", "id=$id state=${player.playbackState}")
    }

    private fun track(activity: Activity) {
        val host = activity as? FragmentActivity ?: return
        if (hosts.containsKey(host)) return
        val callback = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) { observe(f) }
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) { observe(f) }
            override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                watchers.remove(f)?.let(handler::removeCallbacks)
            }
        }
        hosts[host] = callback
        host.supportFragmentManager.registerFragmentLifecycleCallbacks(callback, true)
        fun scan(fragments: List<Fragment>) {
            fragments.forEach { fragment ->
                observe(fragment)
                scan(fragment.childFragmentManager.fragments)
            }
        }
        scan(host.supportFragmentManager.fragments)
    }

    private fun untrack(activity: Activity) {
        val host = activity as? FragmentActivity ?: return
        hosts.remove(host)?.let { host.supportFragmentManager.unregisterFragmentLifecycleCallbacks(it) }
    }


    fun start() {
        if (running) return
        running = true
        application.registerActivityLifecycleCallbacks(this)
        ElevateActivityResultOwner.bootstrap()
    }

    fun stop() {
        if (!running) return
        running = false
        application.unregisterActivityLifecycleCallbacks(this)
        ElevateActivityResultOwner.reset()
        hosts.toMap().forEach { (host, callback) -> host.supportFragmentManager.unregisterFragmentLifecycleCallbacks(callback) }
        hosts.clear()
        watchers.values.forEach(handler::removeCallbacks)
        watchers.clear()
        listeners.toMap().forEach { (player, listener) -> player.removeAnalyticsListener(listener) }
        listeners.clear()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit

    override fun onActivityPostCreated(activity: Activity, state: Bundle?) {
        ElevateActivityResultOwner.track(activity, "POST_CREATE")
        DownloadedPlayerLeakCleanup.trackLivePlayers(activity)
        track(activity)
    }

    override fun onActivityPreDestroyed(activity: Activity) {
        ElevateActivityResultOwner.onDestroy(activity)
        ReceiverRegistrationTrace.snapshot(activity, "PRE_DESTROY")
        DownloadedPlayerLeakCleanup.onDestroy(activity)
        untrack(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        ElevateActivityResultOwner.onDestroy(activity)
        // Fallback on older Android versions; callback removal is idempotent.
        DownloadedPlayerLeakCleanup.onDestroy(activity)
        untrack(activity)
        ReceiverRegistrationTrace.snapshot(activity, "DESTROYED")
        ElevateJavaAllocationProfile.checkpoint(application, "ACTIVITY_DESTROY")
    }

    override fun onActivityStarted(activity: Activity) {
        // Also covers Android versions without onActivityPostCreated().
        ElevateActivityResultOwner.track(activity, "STARTED")
        DownloadedPlayerLeakCleanup.trackLivePlayers(activity)
        track(activity)
    }
    override fun onActivityResumed(activity: Activity) {
        ReceiverRegistrationTrace.snapshot(activity, "RESUMED")
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) {
        ElevateJavaAllocationProfile.checkpoint(application, "ACTIVITY_STOP")
    }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
