package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player as Media3Player
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keep CloudStream's existing timestamp lookup and native skip button. Only replace the
 * effective stamps when the independent Elevate providers return something preferable.
 * No StremioC types, no per-frame draw observers, no player-core modifications.
 */
internal class ElevateSkipController(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = application.getSharedPreferences(ElevateFeaturePrefs.FILE, Context.MODE_PRIVATE)
    private var activity: FragmentActivity? = null
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var mediaPlayer: Media3Player? = null
    private var unobserve: (() -> Unit)? = null
    private var job: Job? = null
    private var generation = 0
    private var activeKey: String? = null
    private var activeInstance = -1
    private var activeEpisode = -1
    private var native = emptyList<VideoSkipStamp>()
    private var custom = emptyList<VideoSkipStamp>()
    private var published = emptyList<VideoSkipStamp>()
    private val autoSkipped = hashSetOf<String>()
    private var lastPosition: Long? = null
    private var suppressedUntilOutside = false
    private var polling = false

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("AUTO_SKIP_") == true) main.post { scheduleAutoSkip() }
    }
    private val playerListener = object : Media3Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Media3Player.STATE_READY) refresh()
        }
        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?, reason: Int
        ) = refresh()
        override fun onTimelineChanged(
            timeline: androidx.media3.common.Timeline, reason: Int
        ) = refresh()
    }
    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: android.view.View,
                                           state: Bundle?) = watch(f)
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) = watch(f)
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f === fragment) detach()
        }
    }

    fun start() {
        settings.registerOnSharedPreferenceChangeListener(preferenceListener)
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        settings.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        application.unregisterActivityLifecycleCallbacks(this)
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallbacks)
        detach()
        activity = null
        scope.cancel()
    }

    private fun use(next: FragmentActivity) {
        if (activity === next) return
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallbacks)
        detach()
        activity = next
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { item ->
                if (item.view != null) watch(item)
                if (item.isAdded) scan(item.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
    }

    private fun watch(candidate: Fragment) {
        if (candidate !is GeneratorPlayer || !candidate.isAdded || candidate.view == null) return
        if (candidate === fragment) {
            refresh()
            return
        }
        detach()
        fragment = candidate
        val vm = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
        model = vm
        val stampsObserver = Observer<VideoLive<List<VideoSkipStamp>>> { event ->
            if (event != null && event.instance == vm.state.instance) {
                val incoming = event.value
                // Our own LiveData notification is not a native result.
                if (incoming != published || incoming.none { it.source.startsWith("Elevate/") }) {
                    if (incoming.none { it.source.startsWith("Elevate/") }) native = incoming
                    publish()
                }
            }
        }
        val linksObserver = Observer<VideoLive<Set<VideoLink>>> { refresh() }
        vm.currentStamps.observe(candidate.viewLifecycleOwner, stampsObserver)
        vm.currentLinks.observe(candidate.viewLifecycleOwner, linksObserver)
        unobserve = {
            vm.currentStamps.removeObserver(stampsObserver)
            vm.currentLinks.removeObserver(linksObserver)
        }
        refresh()
    }

    private fun detach() {
        // First unsubscribe: restoring native stamps must not trigger our own observer.
        unobserve?.invoke()
        unobserve = null
        generation++
        job?.cancel()
        job = null
        mediaPlayer?.removeListener(playerListener)
        mediaPlayer = null
        polling = false
        main.removeCallbacks(autoTick)
        restoreNative()
        model = null
        fragment = null
        activeKey = null
        activeEpisode = -1
        activeInstance = -1
        native = emptyList()
        custom = emptyList()
        published = emptyList()
        autoSkipped.clear()
        lastPosition = null
        suppressedUntilOutside = false
    }

    private fun restoreNative() {
        val vm = model ?: return
        val instance = activeInstance
        val episode = activeEpisode
        if (instance < 0 || episode < 0) return
        vm.modifyState {
            if (this.instance != instance ||
                (generatorState?.meta as? ResultEpisode)?.id != episode ||
                stamps.none { it.source.startsWith("Elevate/") }) this
            else set(native)
        }
    }

    private fun refresh() {
        val vm = model ?: return
        val f = fragment ?: return
        val nextPlayer = f.playerView?.player
        if (nextPlayer !== mediaPlayer) {
            mediaPlayer?.removeListener(playerListener)
            mediaPlayer = nextPlayer
            nextPlayer?.addListener(playerListener)
        }
        val state = vm.state
        val gen = state.generatorState ?: return
        val episode = gen.meta as? ResultEpisode ?: return
        val response = gen.response ?: return
        val duration = nextPlayer?.duration?.takeIf { it > 0L }
            ?: episode.duration.takeIf { it > 0L } ?: return
        val identity = ElevateSkipRepository.identity(response, episode, duration)
        val key = state.instance.toString() + ":" + episode.id + ":" + identity.key
        if (activeKey == key) {
            scheduleAutoSkip()
            return
        }
        generation++
        job?.cancel()
        val serial = generation
        val priorInstance = activeInstance
        val priorEpisode = activeEpisode
        if (priorInstance != state.instance || priorEpisode != episode.id) {
            native = state.stamps.filterNot { it.source.startsWith("Elevate/") }
        }
        activeInstance = state.instance
        activeEpisode = episode.id
        activeKey = key
        custom = emptyList()
        published = emptyList()
        autoSkipped.clear()
        lastPosition = null
        suppressedUntilOutside = false
        scheduleAutoSkip()
        job = scope.launch {
            val result = ElevateSkipRepository.fetch(identity)
            if (serial != generation || activeKey != key) return@launch
            custom = result
            publish()
        }
    }

    private fun publish() {
        val vm = model ?: return
        val combined = ElevateSkipRepository.merge(custom, native)
        val instance = activeInstance
        val episode = activeEpisode
        if (instance < 0 || episode < 0) return
        if (combined == vm.state.stamps) {
            published = combined
            return
        }
        vm.modifyState {
            if (this.instance != instance || (generatorState?.meta as? ResultEpisode)?.id != episode) {
                this
            } else {
                set(combined)
            }
        }
        published = combined
    }

    private fun autoEnabled(): Boolean = ElevateSkipPreferences.autoTypes.any { name ->
        settings.getBoolean(name, false)
    }

    private fun scheduleAutoSkip() {
        val next = autoEnabled() && fragment?.view != null
        if (polling == next) return
        main.removeCallbacks(autoTick)
        polling = next
        if (polling) main.postDelayed(autoTick, 600L)
    }

    private val autoTick = object : Runnable {
        override fun run() {
            if (!polling) return
            tryAutoSkip()
            if (polling) main.postDelayed(this, 650L)
        }
    }

    private fun tryAutoSkip() {
        val player = mediaPlayer ?: return
        val vm = model ?: return
        if (!player.isPlaying || player.duration <= 0L ||
            vm.state.instance != activeInstance ||
            (vm.state.generatorState?.meta as? ResultEpisode)?.id != activeEpisode) return
        val current = player.currentPosition
        val previous = lastPosition
        lastPosition = current
        // A resume/initial seek is user intent, not permission to skip the containing interval.
        val manualSeek = if (previous == null) current > 2_500L
            else current < previous - 1000L || current > previous + 3500L
        val active = published.firstOrNull {
            current >= it.timestamp.startMs && current < it.timestamp.endMs
        }
        if (manualSeek) suppressedUntilOutside = true
        if (active == null) {
            suppressedUntilOutside = false
            return
        }
        if (suppressedUntilOutside) return
        val type = when (active.timestamp.type) {
            com.lagradost.cloudstream3.utils.videoskip.SkipType.Opening,
            com.lagradost.cloudstream3.utils.videoskip.SkipType.Intro,
            com.lagradost.cloudstream3.utils.videoskip.SkipType.MixedOpening -> "AUTO_SKIP_OPENING"
            com.lagradost.cloudstream3.utils.videoskip.SkipType.Recap -> "AUTO_SKIP_RECAP"
            com.lagradost.cloudstream3.utils.videoskip.SkipType.Credits ->
                if (vm.state.generatorState?.response?.type in setOf(
                        com.lagradost.cloudstream3.TvType.Movie,
                        com.lagradost.cloudstream3.TvType.AnimeMovie)) {
                    "AUTO_SKIP_MOVIE_CREDITS"
                } else "AUTO_SKIP_ENDING"
            else -> "AUTO_SKIP_ENDING"
        }
        if (!settings.getBoolean(type, false)) return
        val stamp = active.timestamp
        val key = active.source + ":" + stamp.type + ":" + stamp.startMs + ":" + stamp.endMs
        if (!autoSkipped.add(key)) return
        val target = stamp.endMs.coerceAtMost(player.duration - 1L)
        if (target > current && target > 0L) {
            player.seekTo(target)
            fragment?.context?.let { ctx ->
                val korean = androidx.core.os.ConfigurationCompat
                    .getLocales(ctx.resources.configuration).get(0)?.language == "ko"
                val prefix = if (korean) "자동 건너뛰기: " else "Auto-skipped: "
                android.widget.Toast.makeText(ctx, prefix + ctx.getString(stamp.type.res),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity) {
            this.activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallbacks)
            detach()
            this.activity = null
        }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}

internal object ElevateSkipPreferences {
    val autoTypes = listOf("AUTO_SKIP_OPENING", "AUTO_SKIP_ENDING", "AUTO_SKIP_RECAP",
        "AUTO_SKIP_MOVIE_CREDITS")
}
