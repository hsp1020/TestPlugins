package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Debug
import android.os.Process
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.player.VideoLive
import java.lang.reflect.Proxy
import java.io.File

/** Temporary read-only trace of downloaded playback startup, with a separate 1s heartbeat. */
internal class ElevateDownloadStartupTrace(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    companion object {
        private const val TAG = "ElevateDownloadStart"
        @Volatile private var active: Probe? = null
        fun mark(name: String, details: String = "") {
            active?.log("ELEVATE_$name", details)
        }
    }

    private var running = false
    private var session: Probe? = null
    fun start() {
        if (running) return
        running = true
        app.registerActivityLifecycleCallbacks(this)
    }
    fun stop() {
        if (!running) return
        running = false
        app.unregisterActivityLifecycleCallbacks(this)
        session?.finish("PLUGIN_STOP")
        session = null
        active = null
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (activity !is FragmentActivity ||
            activity.javaClass.name != "com.lagradost.cloudstream3.ui.player.DownloadedPlayerActivity") return
        session?.finish("NEW_ACTIVITY")
        session = Probe(activity).also { active = it; it.start(state != null) }
    }
    override fun onActivityPostCreated(activity: Activity, state: Bundle?) {
        session?.takeIf { it.activity === activity }?.log("ACTIVITY_POST_CREATE")
    }
    override fun onActivityStarted(activity: Activity) {
        session?.takeIf { it.activity === activity }?.log("ACTIVITY_STARTED")
    }
    override fun onActivityResumed(activity: Activity) {
        session?.takeIf { it.activity === activity }?.log("ACTIVITY_RESUMED")
    }
    override fun onActivityPaused(activity: Activity) {
        session?.takeIf { it.activity === activity }?.log("ACTIVITY_PAUSED")
    }
    override fun onActivityStopped(activity: Activity) {
        session?.takeIf { it.activity === activity }?.log("ACTIVITY_STOPPED")
    }
    override fun onActivityDestroyed(activity: Activity) {
        val p = session?.takeIf { it.activity === activity } ?: return
        p.finish("ACTIVITY_DESTROYED")
        session = null
        if (active === p) active = null
    }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private class Probe(val activity: FragmentActivity) {
        private val id = System.identityHashCode(activity)
        private val startedMs = SystemClock.elapsedRealtime()
        private val main = Handler(Looper.getMainLooper())
        private val clockThread = HandlerThread("ElevateDownloadClock")
        private var clock: Handler? = null
        @Volatile private var enabled = false
        @Volatile private var snapshot = "fragment=false player=false"
        @Volatile private var sampledMs = startedMs
        @Volatile private var firstFrameMs = 0L
        private var fragment: GeneratorPlayer? = null
        private var vm: PlayerGeneratorViewModel? = null
        private var player: Media3Player? = null
        private var removeObservers: (() -> Unit)? = null
        private var independentProbeStarted = false
        private var playbackFileUri: Uri? = null
        private val processCpuStartMs = Process.getElapsedCpuTime()
        private var tickNo = 0

        /** Available OS counters: they do not attribute I/O to any one player or extractor. */
        private fun processIo(): String = runCatching {
            val io = File("/proc/self/io").readLines().associate { line ->
                val parts = line.split(':', limit = 2)
                parts[0].trim() to parts.getOrElse(1) { "" }.trim()
            }
            "readBytes=${io["read_bytes"]} readChars=${io["rchar"]} " +
                "sysRead=${io["syscr"]} writeBytes=${io["write_bytes"]}"
        }.getOrElse { "unavailable=${it.javaClass.simpleName}" }

        private fun formatTracks(type: Int, limit: Int = 12): String {
            val p = player ?: return "none"
            return p.currentTracks.groups.filter { it.type == type }
                .flatMap { group ->
                    (0 until group.length).map { idx ->
                        val f = group.getTrackFormat(idx)
                        "${f.language ?: "und"}:${f.sampleMimeType}:" +
                            "selected=${group.isTrackSelected(idx)}:" +
                            "supported=${group.isTrackSupported(idx)}"
                    }
                }.take(limit).joinToString(";")
        }


        fun log(phase: String, detail: String = "") {
            if (enabled) Log.i(TAG, "id=$id elapsedMs=${SystemClock.elapsedRealtime() - startedMs} " +
                "phase=$phase ${detail.take(350)}")
        }
        private fun stateName(s: Int): String = when (s) {
            Media3Player.STATE_IDLE -> "IDLE"
            Media3Player.STATE_BUFFERING -> "BUFFERING"
            Media3Player.STATE_READY -> "READY"
            Media3Player.STATE_ENDED -> "ENDED"
            else -> s.toString()
        }
        private fun firstFrame(origin: String) {
            if (firstFrameMs == 0L) firstFrameMs = SystemClock.elapsedRealtime()
            log("FIRST_VIDEO_FRAME", "origin=$origin")
            if (!independentProbeStarted) {
                independentProbeStarted = true
                val file = vm?.state?.generatorState?.meta as? ExtractorUri
                val uri = playbackFileUri ?: file?.uri?.takeIf { it.scheme == "content" || it.scheme == "file" }
                if (uri != null) {
                    log("PROBE_URI", "source=${if (playbackFileUri != null) "media3" else "generator"} " +
                        "scheme=${uri.scheme} authority=${uri.authority}")
                    ElevateOfflineFileProbe.start(activity.applicationContext, uri,
                        id, startedMs, player?.currentPosition ?: 0L)
                } else {
                    log("PROBE_NOT_STARTED", "reason=no_resolved_playback_uri")
                }
            }
        }
        // Proxies match the already-established Media3 ABI bridging used by Elevate.
        private val playerListener = Proxy.newProxyInstance(
            Media3Player.Listener::class.java.classLoader,
            arrayOf(Media3Player.Listener::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "DownloadStartupPlayerListener"
                "onPlaybackStateChanged" -> {
                    log("MEDIA3_STATE", "state=${stateName(args?.getOrNull(0) as? Int ?: -1)}"); null
                }
                "onIsLoadingChanged", "onIsPlayingChanged", "onPlayWhenReadyChanged",
                "onPlaybackSuppressionReasonChanged", "onMediaItemTransition" -> {
                    log("MEDIA3_${method.name}", "arg=${args?.getOrNull(0)?.javaClass?.simpleName}"); null
                }
                "onPositionDiscontinuity" -> {
                    val from = args?.getOrNull(0) as? Media3Player.PositionInfo
                    val to = args?.getOrNull(1) as? Media3Player.PositionInfo
                    log("MEDIA3_POSITION_DISCONTINUITY",
                        "fromMs=${from?.positionMs} toMs=${to?.positionMs} reason=${args?.getOrNull(2)}")
                    null
                }
                "onTimelineChanged" -> {
                    log("MEDIA3_TIMELINE", "reason=${args?.getOrNull(1)} " +
                        "durationMs=${player?.duration} seekable=${player?.isCurrentMediaItemSeekable} " +
                        "positionMs=${player?.currentPosition}")
                    null
                }
                "onTracksChanged" -> {
                    log("MEDIA3_TRACKS", "groups=${player?.currentTracks?.groups?.size} " +
                        "audio=${formatTracks(C.TRACK_TYPE_AUDIO)} " +
                        "text=${formatTracks(C.TRACK_TYPE_TEXT, 5)}"); null
                }
                "onVideoSizeChanged", "onSurfaceSizeChanged" -> {
                    log("MEDIA3_${method.name}", "resolution=${player?.videoSize?.width}x${player?.videoSize?.height}"); null
                }
                "onRenderedFirstFrame" -> { firstFrame("PLAYER_LISTENER"); null }
                "onPlayerError", "onPlayerErrorChanged" -> {
                    val e = args?.getOrNull(0) as? PlaybackException
                    log("MEDIA3_ERROR", "code=${e?.errorCode} type=${e?.javaClass?.simpleName} message=${e?.message}"); null
                }
                else -> null
            }
        } as Media3Player.Listener

        private val analyticsListener = Proxy.newProxyInstance(
            AnalyticsListener::class.java.classLoader,
            arrayOf(AnalyticsListener::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "DownloadStartupAnalytics"
                "onLoadStarted", "onLoadCompleted", "onLoadCanceled", "onLoadError" -> {
                    val info = args?.getOrNull(1) as? LoadEventInfo
                    val data = args?.getOrNull(2) as? MediaLoadData
                    val spec = info?.dataSpec
                    val actualUri = spec?.uri
                    if (data?.dataType == C.DATA_TYPE_MEDIA &&
                        data?.trackType == C.TRACK_TYPE_UNKNOWN &&
                        (actualUri?.scheme == "content" || actualUri?.scheme == "file")) {
                        playbackFileUri = actualUri
                    }
                    log("MEDIA3_${method.name}", "task=${info?.loadTaskId} durationMs=${info?.loadDurationMs} " +
                        "bytes=${info?.bytesLoaded} type=${data?.dataType} trackType=${data?.trackType} " +
                        "mime=${data?.trackFormat?.sampleMimeType} startMs=${data?.mediaStartTimeMs} " +
                        "endMs=${data?.mediaEndTimeMs} source=${spec?.uri?.scheme}://" +
                        "${spec?.uri?.authority} position=${spec?.position} length=${spec?.length} " +
                        "error=${(args?.getOrNull(3) as? Throwable)?.javaClass?.simpleName}"); null
                }
                "onVideoDecoderInitialized", "onAudioDecoderInitialized",
                "onVideoDecoderReleased", "onAudioDecoderReleased" -> {
                    log("MEDIA3_${method.name}", "decoder=${args?.getOrNull(1)} initMs=${args?.getOrNull(3)}"); null
                }
                "onVideoInputFormatChanged", "onAudioInputFormatChanged" -> {
                    val format = args?.getOrNull(1) as? Format
                    log("MEDIA3_${method.name}", "mime=${format?.sampleMimeType} " +
                        "size=${format?.width}x${format?.height} language=${format?.language} " +
                        "channels=${format?.channelCount} bitrate=${format?.bitrate}"); null
                }
                "onReadingStarted", "onVideoEnabled", "onAudioEnabled",
                "onVideoDisabled", "onAudioDisabled", "onAudioPositionAdvancing",
                "onAudioUnderrun", "onDroppedVideoFrames", "onVideoFrameProcessingOffset",
                "onDownstreamFormatChanged", "onUpstreamDiscarded", "onBandwidthEstimate" -> {
                    log("MEDIA3_${method.name}", "args=${args?.size}"); null
                }
                "onRenderedFirstFrame" -> { firstFrame("ANALYTICS"); null }
                else -> null
            }
        } as AnalyticsListener

        private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment,
                                               v: android.view.View, state: Bundle?) {
                if (f is GeneratorPlayer) watch(f)
            }
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                if (f is GeneratorPlayer) watch(f)
            }
            override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                if (f === fragment) { log("FRAGMENT_VIEW_DESTROYED"); detach() }
            }
        }
        private fun scan(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                if (f is GeneratorPlayer && f.view != null) watch(f)
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        private fun watch(f: GeneratorPlayer) {
            if (!enabled || fragment === f || f.view == null || !f.isAdded) return
            detach()
            fragment = f
            log("FRAGMENT_ATTACHED", "id=${System.identityHashCode(f)}")
            runCatching {
                val model = ViewModelProvider(f)[PlayerGeneratorViewModel::class.java]
                vm = model
                val loading = Observer<VideoLive<Resource<Unit>>> {
                    log("VM_LOADING_LINKS", "event=${it?.javaClass?.simpleName}")
                }
                val links = Observer<VideoLive<Set<VideoLink>>> {
                    log("VM_CURRENT_LINKS", "event=${it?.javaClass?.simpleName}")
                }
                model.loadingLinks.observe(f.viewLifecycleOwner, loading)
                model.currentLinks.observe(f.viewLifecycleOwner, links)
                removeObservers = {
                    model.loadingLinks.removeObserver(loading)
                    model.currentLinks.removeObserver(links)
                }
                log("VM_READY", "instance=${model.state.instance} " +
                    "fileSource=${(model.state.generatorState?.meta as? ExtractorUri)?.uri?.scheme} " +
                    "uriAuthority=${(model.state.generatorState?.meta as? ExtractorUri)?.uri?.authority}")
            }.onFailure { log("VM_OBSERVER_ERROR", "type=${it.javaClass.simpleName}") }
        }
        private fun bind(next: Media3Player?) {
            if (next === player) return
            val old = player
            old?.removeListener(playerListener)
            (old as? ExoPlayer)?.let { runCatching { it.removeAnalyticsListener(analyticsListener) } }
            player = next
            log("PLAYER_ATTACH", "attached=${next != null} replaced=${old != null}")
            next?.addListener(playerListener)
            (next as? ExoPlayer)?.let {
                runCatching { it.addAnalyticsListener(analyticsListener) }.onFailure { e ->
                    log("ANALYTICS_ATTACH_ERROR", "type=${e.javaClass.simpleName}")
                }
            }
        }
        private fun detach() {
            removeObservers?.invoke()
            removeObservers = null
            bind(null)
            fragment = null
            vm = null
        }
        private fun sample() {
            if (fragment == null) scan(activity.supportFragmentManager)
            val nowPlayer = fragment?.playerView?.player
            bind(nowPlayer)
            val st = vm?.state
            val file = st?.generatorState?.meta as? ExtractorUri
            val groups = nowPlayer?.currentTracks?.groups.orEmpty()
            snapshot = "fragment=${fragment != null} vm=${st?.instance} offline=${file != null} " +
                "fileId=${file?.id} uriScheme=${file?.uri?.scheme} player=${nowPlayer != null} " +
                "state=${nowPlayer?.playbackState?.let(::stateName)} loading=${nowPlayer?.isLoading} " +
                "playWhenReady=${nowPlayer?.playWhenReady} playing=${nowPlayer?.isPlaying} " +
                "positionMs=${nowPlayer?.currentPosition} durationMs=${nowPlayer?.duration} " +
                "bufferedMs=${nowPlayer?.bufferedPosition} " +
                "tracks=${groups.count { it.type == C.TRACK_TYPE_VIDEO }}/" +
                "${groups.count { it.type == C.TRACK_TYPE_AUDIO }}/${groups.count { it.type == C.TRACK_TYPE_TEXT }} " +
                "video=${nowPlayer?.videoSize?.width}x${nowPlayer?.videoSize?.height} " +
                "audioTracks=${formatTracks(C.TRACK_TYPE_AUDIO, 4)} " +
                "bufferedDurationMs=${nowPlayer?.totalBufferedDuration}"
            sampledMs = SystemClock.elapsedRealtime()
        }
        private val inspect = object : Runnable {
            override fun run() {
                if (!enabled) return
                runCatching { sample() }.onFailure { log("SAMPLE_ERROR", "type=${it.javaClass.simpleName}") }
                val now = SystemClock.elapsedRealtime()
                when {
                    firstFrameMs != 0L && now - firstFrameMs >= 3000L -> finish("FIRST_FRAME_PLUS_3S")
                    now - startedMs >= 90_000L -> finish("TIMEOUT_90S")
                    else -> main.postDelayed(this, 250L)
                }
            }
        }
        private val pulse = object : Runnable {
            override fun run() {
                if (!enabled) return
                val now = SystemClock.elapsedRealtime()
                val memory = Runtime.getRuntime()
                val io = processIo()
                tickNo++
                Log.i(TAG, "id=$id elapsedMs=${now - startedMs} phase=SECOND " +
                    "second=${(now - startedMs) / 1000} sampleAgeMs=${now - sampledMs} " +
                    "heapMiB=${(memory.totalMemory() - memory.freeMemory()) / 1048576L} " +
                    "nativeHeapMiB=${Debug.getNativeHeapAllocatedSize() / 1048576L} " +
                    "processCpuMs=${Process.getElapsedCpuTime() - processCpuStartMs} " +
                    "io=[$io] $snapshot")
                if (now - startedMs < 90_000L) clock?.postDelayed(this, 1000L)
            }
        }
        fun start(restored: Boolean) {
            enabled = true
            log("START", "restored=$restored pid=${Process.myPid()} androidSdk=${android.os.Build.VERSION.SDK_INT}")
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
            clockThread.start()
            clock = Handler(clockThread.looper)
            main.post(inspect)
            clock?.postDelayed(pulse, 1000L)
        }
        fun finish(reason: String) {
            if (!enabled) return
            log("FINISH", "reason=$reason firstFrameElapsedMs=" +
                (if (firstFrameMs == 0L) "NONE" else "${firstFrameMs - startedMs}"))
            enabled = false
            main.removeCallbacks(inspect)
            clock?.removeCallbacks(pulse)
            clockThread.quitSafely()
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(callbacks)
            detach()
            if (active === this) active = null
        }
    }
}
