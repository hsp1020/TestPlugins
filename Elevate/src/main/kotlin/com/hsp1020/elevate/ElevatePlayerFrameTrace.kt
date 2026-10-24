package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player as Media3Player
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import java.security.MessageDigest

/** Passive diagnostic: observes the core player but never reloads or relayouts it. */
internal class ElevatePlayerFrameTrace(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var tree: ViewTreeObserver? = null
    private var onDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var onGlobalLayout: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var unsubscribe: (() -> Unit)? = null
    private val layoutListeners = mutableMapOf<View, View.OnLayoutChangeListener>()
    private var session = 0
    private var event = 0
    private var lastPlayer: Any? = null
    private var listeningPlayer: Media3Player? = null
    private var loadSequence = 0
    private var loadingPhase = "unknown"
    private var lastMediaKey: String? = null
    private var lastMediaPlayerId = 0
    private var lastMediaInstance: Int? = null
    private var lastMediaEpisode: Int? = null
    private var lastMediaTime = 0L
    private var observed = false
    private var lastInstance: Int? = null
    private var lastState: Int? = null
    private var lastDraw = 0L
    private var layoutWindow = 0L
    private var layouts = 0
    private var layoutPasses = 0
    private var unchangedLayouts = 0
    private var lastSlowGapLog = 0L
    private var lastLinksLoggedCount = -1
    private var lastLinksAt = 0L
    private var lastLoadingAt = 0L
    private var lastPlayerChangedAt = 0L

    private fun log(stage: String, detail: String = "") {
        Log.i("Elevate-PlayerTrace", "session=" + session + " event=" + (++event) +
            " uptimeMs=" + SystemClock.elapsedRealtime() + " stage=" + stage + " " + detail)
    }

    private fun source(): String {
        val state = model?.state ?: return "model=none"
        return "instance=" + state.instance +
            " episode=" + (state.generatorState?.meta as? ResultEpisode)?.id +
            " observedLoadSeq=" + loadSequence + " loading=" + loadingPhase +
            " provider=" + (state.generatorState?.response?.apiName ?: "none")
    }

    // Only calculate on link/media changes; never print URLs or tokens.
    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }

    private fun recordMedia(trigger: String, player: Media3Player) {
        val uri = runCatching {
            player.currentMediaItem?.localConfiguration?.uri?.toString()
        }.getOrNull() ?: return
        val key = fingerprint(uri)
        val playerId = System.identityHashCode(player)
        val instance = model?.state?.instance
        val episode = (model?.state?.generatorState?.meta as? ResultEpisode)?.id
        if (key == lastMediaKey && playerId == lastMediaPlayerId &&
            instance == lastMediaInstance && episode == lastMediaEpisode) return
        val now = SystemClock.elapsedRealtime()
        val sameMedia = key == lastMediaKey && instance == lastMediaInstance &&
            episode == lastMediaEpisode && lastMediaPlayerId != 0 &&
            lastMediaPlayerId != playerId
        log("media-observed", "playerId=" + playerId + " mediaKey=" + key +
            " trigger=" + trigger + " sameMediaNewPlayer=" + sameMedia +
            " sincePreviousMs=" + (if (sameMedia) now - lastMediaTime else -1) +
            " " + source())
        lastMediaKey = key
        lastMediaPlayerId = playerId
        lastMediaInstance = instance
        lastMediaEpisode = episode
        lastMediaTime = now
    }

    // Observer callbacks do not expose the direct caller of core load/release methods.
    // Proxy avoids host/app Media3 listener ABI mismatch.
    private val playerListener = java.lang.reflect.Proxy.newProxyInstance(
        Media3Player.Listener::class.java.classLoader,
        arrayOf(Media3Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onMediaItemTransition" -> {
                listeningPlayer?.let { recordMedia("media-transition", it) }
                log("media-transition", "reason=" + args?.getOrNull(1) + " " + source())
                null
            }
            "onPlayerError" -> {
                val error = args?.getOrNull(0) as? androidx.media3.common.PlaybackException
                log("player-error", "errorCode=" + error?.errorCode + " " + source())
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ElevatePlayerTraceListener"
            else -> null
        }
    } as Media3Player.Listener

    private val lifecycle = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, saved: Bundle?) {
            attach(f)
        }
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            attach(f)
            if (f === fragment) log("resumed", source())
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f === fragment) {
                log("view-destroyed", source())
                detach()
            }
        }
    }

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let { bindHost(it) }
    }
    fun stop() {
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(lifecycle)
        detach()
        host = null
    }
    private fun bindHost(activity: FragmentActivity) {
        if (host === activity) return
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(lifecycle)
        detach()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(lifecycle, true)
        fun scan(fm: FragmentManager) {
            for (f in fm.fragments) {
                if (f.view != null) attach(f)
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }
    private fun attach(f: Fragment) {
        if (f !is GeneratorPlayer || !f.isAdded || f.view == null || fragment === f) return
        detach()
        fragment = f
        model = ViewModelProvider(f)[PlayerGeneratorViewModel::class.java]
        session++
        event = 0
        log("fragment-attach", source())
        val vm = model ?: return
        val links = Observer<VideoLive<Set<VideoLink>>> { update ->
            if (update != null) {
                lastLinksAt = SystemClock.elapsedRealtime()
                val count = update.value.size
                // Large search results can deliver hundreds of updates. Hash only
                // first/zero and sparse milestones instead of every item arrival.
                val milestone = count <= 2 || count == 4 || count == 8 ||
                    count == 16 || count == 32 || count == 64 ||
                    count == 128 || count == 256 || count < lastLinksLoggedCount
                if (milestone && count != lastLinksLoggedCount) {
                    val urls = update.value.mapNotNull { it.first?.url }.sorted()
                    val key = if (urls.isEmpty()) "none" else fingerprint(urls.joinToString("\n"))
                    log("links-event", "eventInstance=" + update.instance +
                        " count=" + count + " linksKey=" + key + " " + source())
                    lastLinksLoggedCount = count
                }
            }
            sample("links")
        }
        val loading = Observer<VideoLive<Resource<Unit>>> { update ->
            if (update != null) {
                val phase = when (update.value) {
                    is Resource.Loading -> "Loading"
                    is Resource.Success -> "Success"
                    is Resource.Failure -> "Failure"
                    else -> "Other"
                }
                if (update.instance == vm.state.instance) {
                    lastLoadingAt = SystemClock.elapsedRealtime()
                    if (phase == "Loading" && loadingPhase != "Loading") loadSequence++
                    loadingPhase = phase
                }
                log("loading-event", "eventInstance=" + update.instance +
                    " phase=" + phase + " " + source())
            }
            sample("loading")
        }
        vm.currentLinks.observe(f.viewLifecycleOwner, links)
        vm.loadingLinks.observe(f.viewLifecycleOwner, loading)
        unsubscribe = {
            vm.currentLinks.removeObserver(links)
            vm.loadingLinks.removeObserver(loading)
        }
        val listener = ViewTreeObserver.OnPreDrawListener {
            sample("draw")
            val now = SystemClock.elapsedRealtime()
            if (lastDraw != 0L && now - lastDraw >= 80L && now - lastSlowGapLog >= 500L) {
                log("slow-frame-gap", "milliseconds=" + (now - lastDraw) + " " + source())
                lastSlowGapLog = now
            }
            if (layoutWindow != 0L && now - layoutWindow >= 1000L) {
                if (layouts > 0 || layoutPasses > 1) {
                    log("layout-summary", "passes=" + layoutPasses + " callbacks=" + layouts +
                        " sameBounds=" + unchangedLayouts +
                        " sinceLinksMs=" + (if (lastLinksAt > 0) now - lastLinksAt else -1) +
                        " sinceLoadingMs=" + (if (lastLoadingAt > 0) now - lastLoadingAt else -1) +
                        " sincePlayerMs=" + (if (lastPlayerChangedAt > 0) now - lastPlayerChangedAt else -1) +
                        " " + source())
                }
                layouts = 0
                layoutPasses = 0
                unchangedLayouts = 0
                layoutWindow = now
            }
            lastDraw = now
            true
        }
        f.view?.viewTreeObserver?.takeIf { it.isAlive }?.let {
            tree = it
            onDraw = listener
            onGlobalLayout = ViewTreeObserver.OnGlobalLayoutListener { layoutPasses++ }
            it.addOnPreDrawListener(listener)
            it.addOnGlobalLayoutListener(onGlobalLayout!!)
            layoutWindow = SystemClock.elapsedRealtime()
        }
        for (name in listOf("exo_content_frame", "extra_brightness_overlay",
            "start_panel", "end_panel")) {
            val root = f.view ?: continue
            val id = root.resources.getIdentifier(name, "id", root.context.packageName)
            val view = if (id > 0) root.findViewById<View>(id) else null
            if (view == null) continue
            val onChange = View.OnLayoutChangeListener { v, l, t, r, b, ol, ot, oright, ob ->
                layouts++
                if (l == ol && t == ot && r == oright && b == ob) unchangedLayouts++
                if (layouts <= 5) {
                    log("layout", "view=" + name + " sameBounds=" +
                        (l == ol && t == ot && r == oright && b == ob) +
                        " old=" + (oright-ol) + "x" + (ob-ot) +
                        " new=" + (r-l) + "x" + (b-t) +
                        " requested=" + v.isLayoutRequested)
                }
            }
            view.addOnLayoutChangeListener(onChange)
            layoutListeners[view] = onChange
        }
        sample("initial")
    }
    private fun sample(trigger: String) {
        val player = fragment?.playerView?.player
        val instance = model?.state?.instance
        if (lastInstance != instance) {
            log("instance", "old=" + lastInstance + " new=" + instance +
                " trigger=" + trigger + " " + source())
            lastInstance = instance
        }
        if (!observed || lastPlayer !== player) {
            val oldId = lastPlayer?.let(System::identityHashCode) ?: 0
            val newId = player?.let(System::identityHashCode) ?: 0
            listeningPlayer?.let { runCatching { it.removeListener(playerListener) } }
            listeningPlayer = player
            player?.let { runCatching { it.addListener(playerListener) } }
            val now = SystemClock.elapsedRealtime()
            log("player-attachment", "oldId=" + oldId + " newId=" + newId +
                " trigger=" + trigger + " sinceLinksMs=" +
                (if (lastLinksAt > 0) now - lastLinksAt else -1) +
                " sinceLoadingMs=" + (if (lastLoadingAt > 0) now - lastLoadingAt else -1) +
                " " + source())
            lastPlayerChangedAt = now
            lastPlayer = player
            lastState = null
            observed = true
            if (player != null) recordMedia("attachment", player)
        }
        if (player != null) {
            val state = runCatching { player.playbackState }.getOrNull()
            if (state != lastState) {
                log("playback-state", "playerId=" + System.identityHashCode(player) +
                    " old=" + lastState + " new=" + state + " trigger=" + trigger +
                    " " + source())
                lastState = state
            }
        }
    }
    private fun detach() {
        if (fragment != null) log("fragment-detach", source())
        tree?.let { tr ->
            if (tr.isAlive) {
                onDraw?.let { tr.removeOnPreDrawListener(it) }
                onGlobalLayout?.let { tr.removeOnGlobalLayoutListener(it) }
            }
        }
        tree = null
        onDraw = null
        onGlobalLayout = null
        listeningPlayer?.let { runCatching { it.removeListener(playerListener) } }
        listeningPlayer = null
        layoutListeners.forEach { (v, listener) -> v.removeOnLayoutChangeListener(listener) }
        layoutListeners.clear()
        unsubscribe?.invoke()
        unsubscribe = null
        fragment = null
        model = null
        observed = false
        lastPlayer = null
        lastState = null
        lastInstance = null
        lastDraw = 0L
        layouts = 0
        layoutPasses = 0
        unchangedLayouts = 0
        layoutWindow = 0L
        lastSlowGapLog = 0L
        lastLinksLoggedCount = -1
        lastLinksAt = 0L
        lastLoadingAt = 0L
        lastPlayerChangedAt = 0L
        loadSequence = 0
        loadingPhase = "unknown"
        lastMediaKey = null
        lastMediaPlayerId = 0
        lastMediaInstance = null
        lastMediaEpisode = null
        lastMediaTime = 0L
    }
    override fun onActivityCreated(a: Activity, state: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityResumed(a: Activity) { (a as? MainActivity)?.let { bindHost(it) } }
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {
        if (host === a) {
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(lifecycle)
            detach()
            host = null
        }
    }
}
