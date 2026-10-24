package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player as Media3Player
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.animeSkipApi
import com.lagradost.cloudstream3.syncproviders.PlainAuthRepo
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.videoskip.SkipStamp
import com.lagradost.cloudstream3.utils.videoskip.SkipType
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

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
    // Nuvio Mobile-style: hide before the first READY, not during rebuffering.
    private var initialLoadCompleted = false
    private var initialLoadInstance = -1
    private var initialLoadEpisode = -1
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
                    if (from != null && to != null) {
                        skipButtonTimer?.onPlaybackSeek(from, to)
                        recordSeek(from, to)
                    }
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
        initialLoadCompleted = false
        initialLoadInstance = -1
        initialLoadEpisode = -1
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
        if (instance < 0 || activeKey == null) return
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
        val nextPlayer = f.playerView?.player
        val state = vm.state
        val nextEpisode = (state.generatorState?.meta as? ResultEpisode)?.id ?: -1
        if (nextPlayer !== mediaPlayer || state.instance != initialLoadInstance ||
            nextEpisode != initialLoadEpisode) {
            initialLoadCompleted = false
            initialLoadInstance = state.instance
            initialLoadEpisode = nextEpisode
        }
        if (nextPlayer !== mediaPlayer) {
            mediaPlayer?.removeListener(playerListener)
            mediaPlayer = nextPlayer
            nextPlayer?.addListener(playerListener)
        }
        if (nextPlayer?.playbackState == Media3Player.STATE_READY) initialLoadCompleted = true
        skipButtonTimer?.attach()
        val gen = state.generatorState ?: return
        val episode = gen.meta as? ResultEpisode ?: return
        val response = gen.response ?: return
        // Some HLS streams expose a temporary 30-second duration before their
        // real timeline becomes available. Prefer a known full episode duration.
        val playerDuration = nextPlayer?.duration?.takeIf { it > 0L }
        val episodeDuration = episode.duration.takeIf { it > 0L }
        val duration = if (playerDuration != null && episodeDuration != null &&
            playerDuration < 60_000L && episodeDuration > playerDuration) {
            episodeDuration
        } else playerDuration ?: episodeDuration ?: return
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
        manualSeekProtected.clear()
        lastManualSeek = null
        ownSeek = null
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
        if (instance < 0 || activeKey == null) return
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
        if (internal) {
            return
        }
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

    // Preserve the native manual button even with auto-skip enabled: auto-skip can
    // be ineligible (not playing, unknown duration, late timestamps or failed seek).
    // Hide only before the first READY, never for ordinary rebuffering.
    private fun suppressNativeSkipButton(@Suppress("UNUSED_PARAMETER") stamp: VideoSkipStamp?): Boolean =
        mediaPlayer == null || !initialLoadCompleted

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
        if (vm.state.instance != activeInstance ||
            (vm.state.generatorState?.meta as? ResultEpisode)?.id != activeEpisode) {
            
            return
        }
        if (published.isEmpty()) { return }
        if (player.duration <= 0L) { return }
        val current = player.currentPosition
        val active = published.firstOrNull {
            current >= it.timestamp.startMs && current < it.timestamp.endMs
        } ?: return
        if (!settings.getBoolean(autoPreference(active), false)) {
            
            return
        }
        val key = stampKey(active)
        if (key in manualSeekProtected) { return }
        if (key in autoSkipped) { return }
        if (!player.isPlaying) { return }
        val stamp = active.timestamp
        // A provisional HLS timeline (often 30s) must not turn an intro skip
        // into a seek to the wrong point. Keep the manual fallback available.
        if (player.duration <= stamp.endMs) {
            
            return
        }
        val target = stamp.endMs.coerceAtMost(player.duration - 1L)
        if (target > current && target > 0L && autoSkipped.add(key)) {
            

            ownSeek = target to android.os.SystemClock.uptimeMillis()
            player.seekTo(target)
            // Use the native skip button's UiText for localized or custom labels.
            fragment?.context?.let { ctx ->
                val totalSeconds = target / 1000L
                val clock = if (totalSeconds >= 3600L) {
                    java.lang.String.format(java.util.Locale.ROOT, "%d:%02d:%02d",
                        totalSeconds / 3600L, (totalSeconds / 60L) % 60L, totalSeconds % 60L)
                } else {
                    java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d",
                        totalSeconds / 60L, totalSeconds % 60L)
                }
                val localizedLabel = active.uiText.asString(ctx)
                val toastMessage: String = "$localizedLabel → $clock"
                android.widget.Toast.makeText(
                    ctx, toastMessage, android.widget.Toast.LENGTH_SHORT
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

/**
 * Nuvio-style timeout indicator on the existing CloudStream skip button.
 *
 * The core calls onTimestamp(...) to show this button, then schedules a 6-second hide.
 * The first native width expansion is an event we can observe without polling frames.
 * Invalidating its public skipIndex cancels only the pending native hide; a 10-second
 * countdown takes over, without replacing the button, click handling or D-pad focus.
 */
internal class ElevateSkipButtonTimer(
    private val fragment: GeneratorPlayer,
    private val model: PlayerGeneratorViewModel,
    private val shouldHide: (com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp?) -> Boolean
) {
    private val main = Handler(Looper.getMainLooper())
    private var button: View? = null
    private var originalAlpha = 1f
    private var originalEnabled = true
    private var originalFocusable = true
    private var originalAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var indicator: FrameLayout? = null
    private var fill: View? = null
    private var active = false
    private var elapsedMs = 0L
    private var lastTickMs = 0L
    private var intervalKey: String? = null
    private var autoHidden = false
    private var hiddenByTimer = false
    private var lastRestoreAtMs = 0L
    private var lastNativeHideAtMs = 0L
    private var tickQueued = false
    private var completedManualKey: String? = null
    private var manualPendingKey: String? = null
    private var manualPendingAtMs = 0L

    private fun stampKey(stamp: VideoSkipStamp): String =
        "${stamp.source}:${stamp.timestamp.type}:${stamp.timestamp.startMs}:${stamp.timestamp.endMs}"

    // Preserve CloudStream's existing click handler; this runs immediately
    // before the native SkipCurrentChapter seek starts.
    private fun markManualSkip() {
        val stamp = currentInterval() ?: return
        if (shouldHide(stamp)) return
        completedManualKey = stampKey(stamp)
        manualPendingKey = completedManualKey
        manualPendingAtMs = SystemClock.uptimeMillis()
        // Do NOT disable the view or start a close animation inside ACTION_UP.
        // CloudStream's native performClick must still execute SkipCurrentChapter.
        fragment.skipIndex++
    }

    fun onPlaybackSeek(from: Long, to: Long) {
        val returned = model.state.stamps.firstOrNull {
            to >= it.timestamp.startMs && to < it.timestamp.endMs
        }
        if (to < from && returned != null) {
            completedManualKey = null
            manualPendingKey = null
            manualPendingAtMs = 0L
            active = false
            intervalKey = null
        } else if (manualPendingKey != null) {
            val started = model.state.stamps.firstOrNull { stampKey(it) == manualPendingKey }
            if (started == null || to < started.timestamp.endMs - 1000L) {
                completedManualKey = null
            }
            manualPendingKey = null
            manualPendingAtMs = 0L
        }
        refreshVisibility()
    }

    private fun queueTick(delayMs: Long = 0L) {
        if (tickQueued || button == null) return
        tickQueued = true
        main.postDelayed(tick, delayMs)
    }

    private fun dp(value: Float): Int =
        (value * (button?.resources?.displayMetrics?.density ?: 1f) + 0.5f).toInt()

    private fun isSuppressed(): Boolean = shouldHide(currentInterval())

    /** Suppression affects interaction, not the native six-second display timer. */
    fun refreshVisibility() {
        val b = button ?: return
        val stamp = currentInterval()
        val suppressed = isSuppressed() || (stamp != null && stampKey(stamp) == completedManualKey)
        val alpha = if (suppressed) 0f else originalAlpha
        if (b.alpha != alpha) b.alpha = alpha
        val enabled = !suppressed && originalEnabled
        if (b.isEnabled != enabled) b.isEnabled = enabled
        val focusable = !suppressed && originalFocusable
        if (b.isFocusable != focusable) b.isFocusable = focusable
        val accessibility = if (suppressed) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else originalAccessibility
        if (b.importantForAccessibility != accessibility) b.importantForAccessibility = accessibility
        if (suppressed && b.hasFocus()) b.clearFocus()
        if (suppressed) indicator?.visibility = View.GONE
        // Do not restart or cancel the countdown on the native button's layout
        // transitions: CloudStream animates its width for 500ms.
        queueTick()
    }

    private fun showNative(stamp: com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp,
                           now: Long): Boolean {
        val b = button ?: return false
        // The native hide animator's onEnd sets GONE even when cancelled.
        // Never reopen during that animation; wait for its callback to finish.
        if (now - lastNativeHideAtMs < NATIVE_ANIMATION_MS + 100L ||
            now - lastRestoreAtMs < NATIVE_ANIMATION_MS + 100L) return false
        lastRestoreAtMs = now
        // CloudStream's timestampShowState can remain true even after a stale
        // hide-animation onEnd makes the view GONE. Normalize that state first.
        fragment.onTimestamp(stamp)
        // Cancel the core's independent 6-second delayed hide; Elevate's
        // countdown is authoritative for this active interval.
        fragment.skipIndex++
        if (b.alpha != originalAlpha) b.alpha = originalAlpha
        return b.visibility == View.VISIBLE
    }

    private val tick = object : Runnable {
        override fun run() {
            tickQueued = false
            val b = button ?: return
            if (fragment.view == null) return
            val now = SystemClock.uptimeMillis()
            if (manualPendingKey != null &&
                now - manualPendingAtMs >= 1500L) {
                manualPendingKey = null
                completedManualKey = null
                manualPendingAtMs = 0L
            }
            val stamp = currentInterval()
            if (stamp == null || isSuppressed() || stampKey(stamp) == completedManualKey) {
                if (active) {
                    reset()
                    // Only clear when this interval was actually ours.
                    fragment.onTimestamp(null)
                }
                queueTick(IDLE_UPDATE_MS)
                return
            }

            val data = stamp.timestamp
            val key = "${data.type}:${data.startMs}:${data.endMs}"
            if (!active || intervalKey != key) {
                active = true
                intervalKey = key
                elapsedMs = 0L
                autoHidden = false
                hiddenByTimer = false
                lastRestoreAtMs = 0L
                lastTickMs = now
                fill?.scaleX = 0f
                // Invalidate any native six-second runnable already posted.
                fragment.skipIndex++
            }

            // GeneratorPlayer.onTimestamp() posts a six-second runnable which
            // captures skipIndex. Core can post another runnable at ANY point
            // while the chapter is active. Keep its snapshot stale before it
            // executes, instead of trying to restore the button afterwards.
            // This mutates only an Int: no layout, player or coroutine work.
            fragment.skipIndex++

            val controlsVisible = fragment.isUIShowing()
            if (!controlsVisible && !autoHidden) {
                elapsedMs += (now - lastTickMs).coerceAtLeast(0L)
                if (elapsedMs >= HIDE_TIMEOUT_MS) autoHidden = true
            }
            lastTickMs = now
            val shouldShow = !autoHidden || controlsVisible
            if (shouldShow) {
                // This also restores the button when controls open after the
                // 10-second timeout, matching Nuvio's (!autoHidden || controls).
                val wasHiddenByUs = hiddenByTimer
                hiddenByTimer = false
                if (wasHiddenByUs && controlsVisible && !showNative(stamp, now)) {
                    // Keep retry eligibility while the 500ms hide animation
                    // is finishing; a single premature attempt is not enough.
                    hiddenByTimer = true
                }
                if (b.alpha != originalAlpha) b.alpha = originalAlpha
                indicator?.visibility =
                    if (controlsVisible || autoHidden) View.GONE else View.VISIBLE
                indicator?.alpha = if (controlsVisible) 0f else 1f
                fill?.scaleX =
                    (elapsedMs / HIDE_TIMEOUT_MS.toFloat()).coerceIn(0f, 1f)
            } else {
                indicator?.visibility = View.GONE
                if (!hiddenByTimer ||
                    (b.visibility == View.VISIBLE && b.width > dp(25f) &&
                        now - lastNativeHideAtMs >= NATIVE_ANIMATION_MS + 100L)) {
                    hiddenByTimer = true
                    lastNativeHideAtMs = now
                    fragment.onTimestamp(null)
                }
            }
            queueTick(UPDATE_MS)
        }
    }

    private fun currentInterval(): com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp? {
        val position = fragment.playerView?.player?.currentPosition ?: return null
        if (position < 0L) return null
        return model.state.stamps.firstOrNull {
            val stamp = it.timestamp
            position >= stamp.startMs && position < stamp.endMs
        }
    }

    fun attach() {
        val host = fragment.playerView ?: return
        val next = host.findViewById<View>(R.id.skip_chapter_button) ?: return
        if (next === button) return
        dispose()
        val parent = next.parent as? ConstraintLayout ?: return
        button = next
        originalAlpha = next.alpha
        originalEnabled = next.isEnabled
        originalFocusable = next.isFocusable
        originalAccessibility = next.importantForAccessibility

        val strip = FrameLayout(next.context).apply {
            id = View.generateViewId()
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            elevation = next.elevation + dp(2f)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(2f).toFloat()
            }
            clipToOutline = true
        }
        strip.addView(View(next.context).apply {
            setBackgroundColor(Color.argb(38, 255, 255, 255))
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val progress = View(next.context).apply {
            pivotX = 0f
            scaleX = 0f
            setBackgroundColor(Color.argb(217, 30, 30, 30))
        }
        strip.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val params = ConstraintLayout.LayoutParams(0, dp(3f)).apply {
            startToStart = next.id
            endToEnd = next.id
            bottomToBottom = next.id
        }
        parent.addView(strip, params)
        indicator = strip
        fill = progress
        next.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP && v.isPressed) markManualSkip()
            false
        }
        next.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_CENTER ||
                code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                markManualSkip()
            }
            false
        }
        refreshVisibility()
    }

    private fun reset() {
        active = false
        autoHidden = false
        hiddenByTimer = false
        elapsedMs = 0L
        lastTickMs = 0L
        lastRestoreAtMs = 0L
        lastNativeHideAtMs = 0L
        intervalKey = null
        indicator?.visibility = View.GONE
    }

    fun dispose() {
        reset()
        val previous = button
        main.removeCallbacks(tick)
        completedManualKey = null
        manualPendingKey = null
        manualPendingAtMs = 0L
        tickQueued = false
        previous?.setOnTouchListener(null)
        previous?.setOnKeyListener(null)
        previous?.alpha = originalAlpha
        previous?.isEnabled = originalEnabled
        previous?.isFocusable = originalFocusable
        previous?.importantForAccessibility = originalAccessibility
        (indicator?.parent as? ViewGroup)?.removeView(indicator)
        button = null
        indicator = null
        fill = null
    }

    companion object {
        private const val HIDE_TIMEOUT_MS = 10_000L
        private const val UPDATE_MS = 125L
        private const val IDLE_UPDATE_MS = 300L
        private const val NATIVE_ANIMATION_MS = 550L
    }
}

/** Independent from StremioC; all identifiers originate in CloudStream response metadata. */
internal object ElevateSkipRepository {
    private const val DAY = 86_400_000L
    private const val MISS = 900_000L
    private val imdbPattern = Regex("^tt[0-9]+$")
    private val hosts = listOf(
        "aiostreams.elfhosted.com", "aiostreams.fortheweak.cloud",
        "aiostreamsfortheweebsstable.midnightignite.me",
        "aio.atbphosting.com", "aiostreams.stremio.ru", "aiostreams.viren070.me"
    )

    internal data class Identity(
        val imdb: String?, val tmdb: String?, val mal: String?, val anilist: String?,
        val kitsu: String?, val season: Int?, val episode: Int, val anime: Boolean,
        val movie: Boolean, val duration: Long, val absoluteEpisode: Int?
    ) {
        val key: String
            get() = listOf(imdb, tmdb, mal, anilist, kitsu, season, episode, anime, movie, duration)
                .joinToString("|")
    }
    private data class Mapping(val mal: String?, val anilist: String?, val episode: Int)
    private data class Timed<T>(val data: T, val until: Long)
    private val mappingCache = ConcurrentHashMap<String, Timed<Mapping?>>()
    private val resultCache = ConcurrentHashMap<String, Timed<List<VideoSkipStamp>>>()

    private fun JSONObject.nonEmpty(key: String): String? =
        optString(key).trim().takeUnless { it.isEmpty() || it == "null" }
    private fun JSONObject.positive(key: String): Int? =
        optInt(key).takeIf { it > 0 }
    private fun JSONObject.node(key: String): JSONObject? = optJSONObject(key)
    private fun id(value: String?): String? = value?.takeIf { it.isNotBlank() && it != "null" }

    fun identity(response: LoadResponse, episode: ResultEpisode, duration: Long): Identity {
        val extra = runCatching { JSONObject(episode.data) }.getOrNull()
        val imdb = id(response.getImdbId()) ?: id(extra?.nonEmpty("imdbId"))
            ?: response.url.substringBefore(':').takeIf { imdbPattern.matches(it) }
        val tmdb = id(response.getTMDbId()) ?: id(extra?.nonEmpty("tmdbId"))
        val original = extra?.nonEmpty("originalAnimeId")
        val mal = id(extra?.positive("malId")?.toString()) ?: response.getMalId()
            ?: original?.takeIf { it.startsWith("mal:") }?.substringAfter(':')?.substringBefore(':')
        val anilist = id(extra?.positive("anilistId")?.toString()) ?: response.getAniListId()
            ?: original?.takeIf { it.startsWith("anilist:") }?.substringAfter(':')?.substringBefore(':')
        val kitsu = id(extra?.nonEmpty("kitsuId")) ?: response.getKitsuId()
            ?: original?.takeIf { it.startsWith("kitsu:") }?.substringAfter(':')?.substringBefore(':')
        val tags = response.tags.orEmpty().map { it.lowercase() }
        val anime = response.type in setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie) ||
            episode.tvType in setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie) ||
            extra?.optBoolean("isAnime") == true ||
            tags.any { it == "anime" } ||
            (tags.any { it == "animation" } && tags.any { it == "japanese" }) ||
            mal != null || anilist != null || kitsu != null
        val movie = response.type in setOf(TvType.Movie, TvType.AnimeMovie) ||
            episode.tvType in setOf(TvType.Movie, TvType.AnimeMovie)
        return Identity(imdb, tmdb, mal, anilist, kitsu,
            episode.season ?: extra?.positive("imdbSeason"), episode.episode,
            anime, movie, duration, episode.totalEpisodeIndex)
    }

    private suspend fun request(url: String): JSONObject? =
        try { JSONObject(app.get(url, timeout = 7L).text) }
        catch (ex: CancellationException) { throw ex }
        catch (_: Exception) { null }

    private suspend fun mapped(i: Identity): Mapping? {
        if (!i.anime) return null
        val (kind, value) = when {
            i.imdb != null -> "imdbId" to i.imdb
            i.mal != null -> "malId" to i.mal
            i.anilist != null -> "anilistId" to i.anilist
            i.kitsu != null -> "kitsuId" to i.kitsu
            else -> return null
        }
        val query = "idType=" + kind + "&idValue=" + value +
            (if (kind == "imdbId" && i.season != null) "&season=" + i.season else "") +
            (if (kind == "imdbId") "&episode=" + i.episode else "")
        val now = System.currentTimeMillis()
        mappingCache[query]?.takeIf { it.until > now }?.let { return it.data }
        var result: Mapping? = null
        for (host in hosts) {
            val root = request("https://" + host + "/api/v1/anime?" + query) ?: continue
            if (!root.optBoolean("success")) continue
            val data = root.node("data") ?: break
            val ids = data.node("mappings") ?: continue
            if (ids.nonEmpty(kind) != value) continue
            val season = i.season
            if (kind == "imdbId" && season != null && season > 1) {
                val matches = listOf("imdb", "tvdb", "trakt", "tmdb").any {
                    data.node(it)?.positive("seasonNumber") == season
                }
                if (!matches) continue
            }
            val episodeMap = listOf("imdb", "tvdb", "trakt", "tmdb")
                .mapNotNull { data.node(it) }
                .firstOrNull { candidate ->
                    i.season == null || candidate.positive("seasonNumber") == i.season
                }
            val fromEpisode = episodeMap?.positive("fromEpisode")
            val local = if (kind == "imdbId" && fromEpisode != null) {
                (i.episode - fromEpisode + 1).takeIf { it > 0 }
            } else i.episode
            if (local == null) continue
            result = Mapping(ids.positive("malId")?.toString(),
                ids.positive("anilistId")?.toString(), local)
            if (result.mal != null || result.anilist != null) break
        }
        if (mappingCache.size >= 600) mappingCache.clear()
        mappingCache[query] = Timed(result, now + if (result == null) MISS else DAY)
        return result
    }

    private fun category(type: SkipType): String = when (type) {
        SkipType.Opening, SkipType.Intro, SkipType.MixedOpening -> "opening"
        SkipType.Ending, SkipType.Credits, SkipType.MixedEnding -> "ending"
        SkipType.Recap -> "recap"
        SkipType.Preview -> "preview"
    }
    private fun valid(stamp: SkipStamp, duration: Long): Boolean =
        stamp.startMs >= 0 && stamp.endMs > stamp.startMs &&
            stamp.startMs < duration && stamp.endMs <= duration

    /** Nuvio: first segment per category, in provider priority order. */
    private fun choose(vararg groups: List<VideoSkipStamp>): List<VideoSkipStamp> {
        val result = LinkedHashMap<String, VideoSkipStamp>()
        for (group in groups) for (stamp in group) {
            result.putIfAbsent(category(stamp.timestamp.type), stamp)
        }
        return result.values.sortedBy { it.timestamp.startMs }
    }

    fun merge(elevate: List<VideoSkipStamp>, native: List<VideoSkipStamp>): List<VideoSkipStamp> =
        choose(elevate, native.filterNot { it.source.startsWith("Elevate/") })

    private fun stamps(source: String, intervals: List<SkipStamp>, i: Identity): List<VideoSkipStamp> =
        intervals.filter { valid(it, i.duration) }.map { stamp ->
            // A substantial tail after an ending may contain a post-credit scene.
            // Do not allow the native next-episode action to skip that tail.
            val trailing = i.duration - stamp.endMs
            VideoSkipStamp(stamp, !i.movie && trailing in 0L..5_000L,
                "Elevate/" + source)
        }

    private fun JSONObject.segment(name: String, type: SkipType): SkipStamp? {
        val obj = node(name) ?: return null
        val start = if (obj.has("start_ms") && !obj.isNull("start_ms")) obj.optLong("start_ms")
            else (obj.optDouble("start_sec", Double.NaN) * 1000).toLong()
        val end = if (obj.has("end_ms") && !obj.isNull("end_ms")) obj.optLong("end_ms")
            else (obj.optDouble("end_sec", Double.NaN) * 1000).toLong()
        return SkipStamp(type, start, end).takeIf { start >= 0 && end > start }
    }

    private suspend fun introDb(i: Identity): List<VideoSkipStamp> {
        val imdb = i.imdb ?: return emptyList()
        val args = if (i.movie) "&is_movie=true"
            else "&season=" + (i.season ?: return emptyList()) + "&episode=" + i.episode
        val root = request("https://api.introdb.app/segments?imdb_id=" + imdb + args)
            ?: return emptyList()
        val postCredits = if (i.movie) root.segment("post_credits", SkipType.Credits) else null
        val credits = root.segment("outro", if (i.movie) SkipType.Credits else SkipType.Ending)
            ?.let { seg ->
                if (postCredits != null && postCredits.startMs > seg.startMs) {
                    seg.copy(endMs = postCredits.startMs)
                } else seg
            }
        return stamps("IntroDb", listOfNotNull(
            if (i.movie) null else root.segment("intro", SkipType.Opening),
            if (i.movie) null else root.segment("recap", SkipType.Recap),
            credits
        ), i)
    }

    private suspend fun theIntroDB(i: Identity): List<VideoSkipStamp> {
        val idParam = i.tmdb?.let { "tmdb_id=" + it } ?: i.imdb?.let { "imdb_id=" + it }
            ?: return emptyList()
        val suffix = if (i.movie) "" else "&season=" + (i.season ?: return emptyList()) +
            "&episode=" + i.episode
        val root = request("https://api.theintrodb.org/v4/media?" + idParam + suffix)
            ?: return emptyList()
        // A successful v4 media response has type=tv/movie. Error payloads may
        // be JSON objects without type; never treat them as timestamp arrays.
        val mediaType = root.optString("type")
        if (mediaType != "tv" && mediaType != "movie") {
            return emptyList()
        }
        val intervals = ArrayList<SkipStamp>()
        for ((key, type) in listOf("intro" to SkipType.Intro, "recap" to SkipType.Recap,
            "credits" to SkipType.Credits, "preview" to SkipType.Preview)) {
            val items = root.optJSONArray(key) ?: continue
            for (n in 0 until items.length()) {
                val stamp = items.optJSONObject(n) ?: continue
                // v4: null start means start of media; null end means end of media.
                val start = if (stamp.isNull("start_ms")) 0L else stamp.optLong("start_ms", -1L)
                val end = if (stamp.isNull("end_ms")) i.duration
                    else stamp.optLong("end_ms", -1L)
                if (start >= 0L && end > start) intervals.add(SkipStamp(type, start, end))
            }
        }
        return stamps("TheIntroDB", intervals, i)
    }

    private suspend fun aniSkip(i: Identity, mapped: Mapping?): List<VideoSkipStamp> {
        if (!i.anime) return emptyList()
        val mal = mapped?.mal ?: i.mal ?: return emptyList()
        val episode = mapped?.episode ?: i.episode
        if (episode <= 0) return emptyList()
        val url = "https://api.aniskip.com/v2/skip-times/" + mal + "/" + episode +
            "?types[]=op&types[]=ed&types[]=recap&types[]=mixed-op&types[]=mixed-ed&episodeLength=" +
            i.duration / 1000L
        val root = request(url) ?: return emptyList()
        if (!root.optBoolean("found")) return emptyList()
        val items = root.optJSONArray("results") ?: return emptyList()
        val intervals = ArrayList<SkipStamp>()
        for (n in 0 until items.length()) {
            val obj = items.optJSONObject(n) ?: continue
            val type = when (obj.optString("skipType")) {
                "op" -> SkipType.Opening
                "ed" -> SkipType.Ending
                "recap" -> SkipType.Recap
                "mixed-op" -> SkipType.MixedOpening
                "mixed-ed" -> SkipType.MixedEnding
                else -> continue
            }
            val part = obj.node("interval") ?: continue
            val start = (part.optDouble("startTime") * 1000.0).toLong()
            val end = (part.optDouble("endTime") * 1000.0).toLong()
            intervals.add(SkipStamp(type, start, end))
        }
        return stamps("AniSkip", intervals, i)
    }

    private suspend fun animeSkip(i: Identity, mapped: Mapping?): List<VideoSkipStamp> {
        if (!i.anime) return emptyList()
        val anilist = mapped?.anilist ?: i.anilist ?: return emptyList()
        val payload = try { PlainAuthRepo(animeSkipApi).authData()?.token?.payload }
            catch (ex: CancellationException) { throw ex }
            catch (_: Exception) { null }
        val clientId = payload?.let {
            runCatching { JSONObject(it).nonEmpty("clientId") }.getOrNull()
        } ?: return emptyList()
        val episode = mapped?.episode ?: i.episode
        suspend fun query(graphql: String): JSONObject? =
            try {
                JSONObject(app.post("https://api.anime-skip.com/graphql",
                    json = mapOf("query" to graphql),
                    headers = mapOf("X-Client-ID" to clientId, "Content-Type" to "application/json"),
                    timeout = 7L).text).node("data")
            } catch (ex: CancellationException) { throw ex }
              catch (_: Exception) { null }
        val shows = query("{ findShowsByExternalId(service: ANILIST, serviceId: \"" +
            anilist + "\") { id } }")?.optJSONArray("findShowsByExternalId") ?: return emptyList()
        for (s in 0 until shows.length()) {
            val showId = shows.optJSONObject(s)?.nonEmpty("id") ?: continue
            val episodes = query("{ findEpisodesByShowId(showId: \"" + showId +
                "\") { season number absoluteNumber timestamps { at type { name } } } }")
                ?.optJSONArray("findEpisodesByShowId") ?: continue
            for (n in 0 until episodes.length()) {
                val ep = episodes.optJSONObject(n) ?: continue
                if (ep.optString("number").toIntOrNull() != episode &&
                    ep.optString("absoluteNumber").toIntOrNull() != episode) continue
                val marks = ep.optJSONArray("timestamps") ?: continue
                val sorted = (0 until marks.length()).mapNotNull { marks.optJSONObject(it) }
                    .sortedBy { it.optDouble("at", Double.MAX_VALUE) }
                val segments = ArrayList<SkipStamp>()
                for (m in sorted.indices) {
                    val mark = sorted[m]
                    val type = when (mark.node("type")?.optString("name")?.lowercase()) {
                        "intro", "new intro" -> SkipType.Opening
                        "credits", "new credits" -> SkipType.Ending
                        "mixed credits" -> SkipType.MixedEnding
                        "mixed intro" -> SkipType.MixedOpening
                        "recap" -> SkipType.Recap
                        "preview" -> SkipType.Preview
                        else -> continue
                    }
                    val start = (mark.optDouble("at") * 1000.0).toLong()
                    val end = if (m + 1 < sorted.size) {
                        (sorted[m + 1].optDouble("at") * 1000.0).toLong()
                    } else i.duration
                    segments.add(SkipStamp(type, start, end))
                }
                val result = stamps("AnimeSkip", segments, i)
                if (result.isNotEmpty()) return result
            }
        }
        return emptyList()
    }

    private suspend fun provider(block: suspend () -> List<VideoSkipStamp>): List<VideoSkipStamp> =
        try { withTimeoutOrNull(8_000L) { block() }.orEmpty() }
        catch (ex: CancellationException) { throw ex }
        catch (ex: Exception) {
            emptyList()
        }

    suspend fun fetch(i: Identity): List<VideoSkipStamp> = withContext(Dispatchers.IO) {
        if (i.duration <= 0L || i.duration > 24L * 60L * 60L * 1000L) {
            return@withContext emptyList()
        }
        val now = System.currentTimeMillis()
        resultCache[i.key]?.takeIf { it.until > now }?.let {
            return@withContext it.data
        }
        val result = withTimeoutOrNull(19_000L) {
            coroutineScope {
                val mapping = async {
                    try { withTimeoutOrNull(8_000L) { mapped(i) } }
                    catch (ex: CancellationException) { throw ex }
                    catch (ex: Exception) {
                        null
                    }
                }
                val intro = async { provider { introDb(i) } }
                val theIntro = async { provider { theIntroDB(i) } }
                val ani = async { provider { aniSkip(i, mapping.await()) } }
                val anime = async { provider { animeSkip(i, mapping.await()) } }
                val groups = listOf(intro.await(), anime.await(), ani.await(), theIntro.await())
                choose(*groups.toTypedArray())
            }
        } ?: emptyList<VideoSkipStamp>()
        if (resultCache.size >= 400) resultCache.clear()
        resultCache[i.key] = Timed(result, now + if (result.isEmpty()) MISS else DAY)
        result
    }
}
