package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
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
    private var skipButtonTimer: ElevateSkipButtonTimer? = null
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
    // Preserve explicit Media3 seek origins/destinations, as Nuvio Mobile does.
    // No sampled position-difference heuristic, including for small seeks.
    private var lastManualSeek: Pair<Long, Long>? = null
    private val manualSeekProtected = hashSetOf<String>()
    private var ownSeek: Pair<Long, Long>? = null
    private var polling = false
    // Keep the native MaterialButton and its click/focus/animation handlers intact.
    // Move the touch-layout skip button only; leave the native TV constraints untouched.
    private data class SkipButtonPosition(
        val button: View,
        val topToTop: Int,
        val topToBottom: Int,
        val bottomToTop: Int,
        val bottomToBottom: Int,
        val topMargin: Int,
        val bottomMargin: Int,
        val marginEnd: Int
    )
    private var savedButtonPosition: SkipButtonPosition? = null

    private fun placeNativeSkipButton() {
        // Match GeneratorPlayer.pickLayout(): TV/emulator already uses the native
        // bottom-right layout. Never touch its margins or layout constraints.
        if (isLayout(TV or EMULATOR)) return
        val host = fragment?.playerView ?: return
        val button = host.findViewById<View>(R.id.skip_chapter_button) ?: return
        val bottomBar = host.findViewById<View>(R.id.bottom_player_bar) ?: return
        val params = button.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (button.parent !== bottomBar.parent) return
        if (savedButtonPosition?.button === button) return
        restoreNativeSkipButton()
        // TV already places the skip button at the lower right with native D-pad
        // navigation. Never override that layout or its original margins.
        if (params.bottomToTop == bottomBar.id) return
        // On touch layouts, bottom_player_bar can change size/translation when
        // controls appear. Anchor to the fixed player surface, never to the bar.
        savedButtonPosition = SkipButtonPosition(
            button, params.topToTop, params.topToBottom, params.bottomToTop,
            params.bottomToBottom, params.topMargin, params.bottomMargin, params.marginEnd
        )
        params.topToTop = ConstraintLayout.LayoutParams.UNSET
        params.topToBottom = ConstraintLayout.LayoutParams.UNSET
        params.bottomToTop = ConstraintLayout.LayoutParams.UNSET
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        params.topMargin = 0
        // Native touch layout: 30dp seek row + 60dp control row + 10dp
        // bottom margin. Reserve another 24dp above all controls and the
        // progress scrubber, whether they are currently shown or hidden.
        // This is a fixed constraint, not a measured or animated position.
        params.bottomMargin = (124 * button.resources.displayMetrics.density + 0.5f).toInt()
        params.marginEnd = (24 * button.resources.displayMetrics.density + 0.5f).toInt()
        button.layoutParams = params
    }

    private fun restoreNativeSkipButton() {
        val prior = savedButtonPosition ?: return
        savedButtonPosition = null
        val params = prior.button.layoutParams as? ConstraintLayout.LayoutParams ?: return
        params.topToTop = prior.topToTop
        params.topToBottom = prior.topToBottom
        params.bottomToTop = prior.bottomToTop
        params.bottomToBottom = prior.bottomToBottom
        params.topMargin = prior.topMargin
        params.bottomMargin = prior.bottomMargin
        params.marginEnd = prior.marginEnd
        prior.button.layoutParams = params
    }

    private var attachAttempts = 0
    private val attachRetry = object : Runnable {
        override fun run() {
            if (fragment == null || mediaPlayer != null || attachAttempts >= 12) return
            attachAttempts++
            refresh()
            if (mediaPlayer == null) main.postDelayed(this, 300L)
        }
    }

    private fun retryPlayerAttach() {
        if (mediaPlayer != null || fragment == null) return
        attachAttempts = 0
        main.removeCallbacks(attachRetry)
        main.postDelayed(attachRetry, 300L)
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("AUTO_SKIP_") == true) main.post {
            scheduleAutoSkip()
            skipButtonTimer?.refreshVisibility()
        }
    }
    // CloudStream may bundle a newer Media3 Player.Listener than our compileOnly API.
    // Implementing the interface directly can trigger AbstractMethodError when ExoPlayer
    // invokes a method that does not exist in the compile-time interface version.
    // A proxy handles every runtime callback safely without invoking interface defaults.
    private val playerListener = java.lang.reflect.Proxy.newProxyInstance(
        Media3Player.Listener::class.java.classLoader,
        arrayOf(Media3Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onPlaybackStateChanged", "onIsPlayingChanged" -> {
                refresh()
                skipButtonTimer?.refreshVisibility()
                null
            }
            "onPositionDiscontinuity" -> {
                if (args?.size == 3 &&
                    args[2] == Media3Player.DISCONTINUITY_REASON_SEEK) {
                    val from = (args[0] as? Media3Player.PositionInfo)?.positionMs
                    val to = (args[1] as? Media3Player.PositionInfo)?.positionMs
                    if (from != null && to != null) recordSeek(from, to)
                }
                null
            }
            "onMediaItemTransition", "onTimelineChanged" -> {
                refresh()
                skipButtonTimer?.refreshVisibility()
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ElevateSkipPlayerListener"
            else -> null
        }
    } as Media3Player.Listener
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
        placeNativeSkipButton()
        val vm = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
        model = vm
        skipButtonTimer = ElevateSkipButtonTimer(candidate, vm) { stamp ->
            suppressNativeSkipButton(stamp)
        }.also { it.attach() }
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
        val linksObserver = Observer<VideoLive<Set<VideoLink>>> {
            refresh()
            retryPlayerAttach()
        }
        vm.currentStamps.observe(candidate.viewLifecycleOwner, stampsObserver)
        vm.currentLinks.observe(candidate.viewLifecycleOwner, linksObserver)
        unobserve = {
            vm.currentStamps.removeObserver(stampsObserver)
            vm.currentLinks.removeObserver(linksObserver)
        }
        refresh()
        retryPlayerAttach()
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
        main.removeCallbacks(attachRetry)
        attachAttempts = 0
        skipButtonTimer?.dispose()
        skipButtonTimer = null
        restoreNativeSkipButton()
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
        manualSeekProtected.clear()
        lastManualSeek = null
        ownSeek = null
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
        placeNativeSkipButton()
        skipButtonTimer?.attach()
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
            skipButtonTimer?.refreshVisibility()
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
        lastManualSeek?.let { protectManualSeek(it.first, it.second) }
        skipButtonTimer?.refreshVisibility()
        if (autoEnabled()) tryAutoSkip()
    }

    private fun stampKey(stamp: VideoSkipStamp): String =
        "${stamp.source}:${stamp.timestamp.type}:${stamp.timestamp.startMs}:${stamp.timestamp.endMs}"

    private fun protectManualSeek(from: Long, to: Long) {
        for (stamp in published) {
            val data = stamp.timestamp
            if (from in data.startMs until data.endMs ||
                to in data.startMs until data.endMs) {
                manualSeekProtected.add(stampKey(stamp))
            }
        }
    }

    private fun recordSeek(from: Long, to: Long) {
        val internal = ownSeek?.let { (target, time) ->
            android.os.SystemClock.uptimeMillis() - time < 1500L &&
                kotlin.math.abs(to - target) <= 1200L
        } == true
        ownSeek = null
        if (internal) return
        lastManualSeek = from to to
        protectManualSeek(from, to)
        skipButtonTimer?.refreshVisibility()
    }

    private fun autoPreference(stamp: VideoSkipStamp): String =
        when (stamp.timestamp.type) {
            com.lagradost.cloudstream3.utils.videoskip.SkipType.Opening,
            com.lagradost.cloudstream3.utils.videoskip.SkipType.Intro,
            com.lagradost.cloudstream3.utils.videoskip.SkipType.MixedOpening -> "AUTO_SKIP_OPENING"
            com.lagradost.cloudstream3.utils.videoskip.SkipType.Recap -> "AUTO_SKIP_RECAP"
            com.lagradost.cloudstream3.utils.videoskip.SkipType.Credits ->
                if (model?.state?.generatorState?.response?.type in setOf(
                        com.lagradost.cloudstream3.TvType.Movie,
                        com.lagradost.cloudstream3.TvType.AnimeMovie)) {
                    "AUTO_SKIP_MOVIE_CREDITS"
                } else "AUTO_SKIP_ENDING"
            com.lagradost.cloudstream3.utils.videoskip.SkipType.Preview -> ""
            else -> "AUTO_SKIP_ENDING"
        }

    // Called from native skip button's layout event, before its first visible frame.
    // Buffering suppression is independent of auto-skip settings.
    private fun suppressNativeSkipButton(stamp: VideoSkipStamp?): Boolean {
        val player = mediaPlayer
        if (player == null || player.playbackState != Media3Player.STATE_READY) return true
        return stamp != null && settings.getBoolean(autoPreference(stamp), false) &&
            stampKey(stamp) !in manualSeekProtected &&
            stampKey(stamp) !in autoSkipped
    }

    private fun autoEnabled(): Boolean = ElevateSkipPreferences.autoTypes.any { name ->
        settings.getBoolean(name, false)
    }

    private fun scheduleAutoSkip() {
        val next = autoEnabled() && fragment?.view != null
        if (polling == next) return
        main.removeCallbacks(autoTick)
        polling = next
        if (polling) main.post(autoTick)
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
        val active = published.firstOrNull {
            current >= it.timestamp.startMs && current < it.timestamp.endMs
        } ?: return
        if (!settings.getBoolean(autoPreference(active), false)) return
        val key = stampKey(active)
        if (key in manualSeekProtected || key in autoSkipped) return
        val stamp = active.timestamp
        if (!autoSkipped.add(key)) return
        val target = stamp.endMs.coerceAtMost(player.duration - 1L)
        if (target > current && target > 0L) {
            ownSeek = target to android.os.SystemClock.uptimeMillis()
            player.seekTo(target)
            // CloudStream's skip_type_format and SkipType resources are already
            // localized for its selected app language. Do not hardcode ko/en.
            fragment?.context?.let { ctx ->
                val totalSeconds = target / 1000L
                val clock = if (totalSeconds >= 3600L) {
                    java.lang.String.format(java.util.Locale.ROOT, "%d:%02d:%02d",
                        totalSeconds / 3600L, (totalSeconds / 60L) % 60L, totalSeconds % 60L)
                } else {
                    java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d",
                        totalSeconds / 60L, totalSeconds % 60L)
                }
                android.widget.Toast.makeText(
                    ctx, active.uiText + " → " + clock, android.widget.Toast.LENGTH_SHORT
                ).show()
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
