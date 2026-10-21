package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.OptIn
import androidx.core.os.ConfigurationCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.SubtitleHelper
import org.json.JSONObject
import java.util.Locale

@OptIn(UnstableApi::class)
internal class ElevateAudioTrackSelector(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private var activity: FragmentActivity? = null
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var removeObserver: (() -> Unit)? = null
    private var player: Media3Player? = null
    private var waitingOn: ViewTreeObserver? = null
    private var lastInstance: Int? = null
    private var lastProvider: String? = null
    private var sourceEligible = false
    private var isAnime = false
    private var selected = false
    private var started = false
    private val diagnostics = ElevateAudioDiagnostics()

    // A proxy is required because the host app may use a different Media3 Listener ABI.
    private val audioListener = java.lang.reflect.Proxy.newProxyInstance(
        Media3Player.Listener::class.java.classLoader,
        arrayOf(Media3Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onTracksChanged" -> {
                (args?.getOrNull(0) as? Tracks)?.let(::select)
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ElevateAudioTrackSelectorListener"
            else -> null
        }
    } as Media3Player.Listener

    // Keep monitoring the view: ExoPlayer can be attached after link loading or replaced
    // when the user changes stream mirrors without replacing the GeneratorPlayer fragment.
    private val onDraw = ViewTreeObserver.OnPreDrawListener {
        refreshPlayer("pre_draw")
        true
    }

    private fun refreshPlayer(reason: String) {
        val active = fragment?.takeIf { it.isAdded && it.view != null }
        val next = active?.playerView?.player
        if (next === player) return
        val old = player
        if (old != null) {
            old.removeListener(audioListener)
            diagnostics.detach(if (next == null) "player_disconnected" else "player_replaced")
        }
        player = next
        selected = false
        ElevateAudioTrace.log("PLAYER_CONNECTION",
            "reason=$reason old=${old?.let(System::identityHashCode)} " +
                "new=${next?.let(System::identityHashCode)} " +
                "sourceEligible=$sourceEligible instance=$lastInstance")
        if (next != null && active != null) {
            diagnostics.bind(next, active)
            next.addListener(audioListener)
            select(next.currentTracks)
        }
    }

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, saved: Bundle?
        ) {
            if (f is GeneratorPlayer) {
                ElevateAudioTrace.log("FRAGMENT_VIEW_CREATED", "fragment=${System.identityHashCode(f)}")
            }
            watch(f, "view_created")
        }
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) =
            watch(f, "resumed")
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f === fragment) {
                ElevateAudioTrace.log("FRAGMENT_VIEW_DESTROYED", "fragment=${System.identityHashCode(f)}")
            }
            detach(f)
        }
    }

    fun start() {
        ElevateAudioTrace.log("SELECTOR_START",
            "alreadyStarted=$started app=${application.javaClass.simpleName} " +
                "currentActivity=${CommonActivity.activity?.javaClass?.name}")
        if (started) return
        started = true
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let { use(it, "startup_activity") }
            ?: ElevateAudioTrace.log("START_WAITING_FOR_ACTIVITY", "MainActivity unavailable")
    }

    fun stop() {
        ElevateAudioTrace.log("SELECTOR_STOP", "wasStarted=$started host=${activity?.javaClass?.name}")
        if (started) application.unregisterActivityLifecycleCallbacks(this)
        started = false
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        detach()
        activity = null
    }

    private fun use(next: FragmentActivity, source: String) {
        if (!started) return
        if (activity !== next) {
            ElevateAudioTrace.log("ACTIVITY_BIND",
                "source=$source old=${activity?.javaClass?.name} new=${next.javaClass.name}")
            activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
            detach()
            activity = next
            next.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        } else {
            ElevateAudioTrace.log("ACTIVITY_RESCAN", "source=$source")
        }
        var scanned = 0
        var candidates = 0
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { f ->
                scanned++
                if (f is GeneratorPlayer) {
                    candidates++
                    ElevateAudioTrace.log("PLAYER_FRAGMENT_DISCOVERED",
                        "source=$source class=${f.javaClass.name} added=${f.isAdded} " +
                            "hasView=${f.view != null} id=${System.identityHashCode(f)}")
                }
                if (f.view != null) watch(f, "scan_$source")
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
        ElevateAudioTrace.log("FRAGMENT_SCAN_COMPLETE",
            "source=$source scanned=$scanned playerCandidates=$candidates")
    }

    private fun isSupportedProvider(name: String): Boolean {
        when (name.lowercase(Locale.ROOT)) {
            "stremioc", "streamplay", "streamplay-anime",
            "cinestream", "cinetmdb", "cinesimkl" -> return true
        }
        return APIHolder.allProviders.any { provider ->
            provider.name.equals(name, ignoreCase = true) &&
                provider.javaClass.name == "com.hsp1020.StremioC"
        }
    }

    private fun animeFlag(episode: ResultEpisode?): Boolean? {
        val data = episode?.data?.takeIf { it.trimStart().startsWith("{") } ?: return null
        return runCatching {
            JSONObject(data).takeIf { it.has("isAnime") && !it.isNull("isAnime") }
                ?.getBoolean("isAnime")
        }.getOrNull()
    }

    private fun watch(candidate: Fragment, reason: String) {
        if (candidate !is GeneratorPlayer) return
        if (!candidate.isAdded || candidate.view == null) {
            ElevateAudioTrace.log("WATCH_REJECTED",
                "reason=$reason added=${candidate.isAdded} view=${candidate.view != null}")
            return
        }
        if (fragment === candidate) {
            ElevateAudioTrace.log("WATCH_ALREADY_ATTACHED", "reason=$reason")
            return
        }
        ElevateAudioTrace.log("WATCH_ENTER",
            "reason=$reason fragment=${System.identityHashCode(candidate)} " +
                "previous=${fragment?.let(System::identityHashCode)}")
        detach()
        fragment = candidate
        val vm = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
        model = vm
        val loadingObserver = Observer<VideoLive<Resource<Unit>>> { event ->
            ElevateAudioTrace.log("LOADING_LINKS_EVENT",
                "eventInstance=${event?.instance} stateInstance=${vm.state.instance} " +
                    "loading=${vm.state.loading.javaClass.simpleName} " +
                    "generator=${vm.state.generatorState != null}")
            checkSource("loading_links")
        }
        val linksObserver = Observer<VideoLive<Set<VideoLink>>> { event ->
            val count = event?.value?.size
            ElevateAudioTrace.log("LINKS_EVENT",
                "eventInstance=${event?.instance} count=$count stateInstance=${vm.state.instance}")
            checkSource("links_updated")
        }
        vm.loadingLinks.observe(candidate.viewLifecycleOwner, loadingObserver)
        vm.currentLinks.observe(candidate.viewLifecycleOwner, linksObserver)
        removeObserver = {
            vm.loadingLinks.removeObserver(loadingObserver)
            vm.currentLinks.removeObserver(linksObserver)
        }
        val observer = candidate.view?.viewTreeObserver?.takeIf { it.isAlive }
        if (observer != null) {
            observer.addOnPreDrawListener(onDraw)
            waitingOn = observer
            ElevateAudioTrace.log("PLAYER_MONITOR_ACTIVE", "source=pre_draw continuous=true")
        } else {
            ElevateAudioTrace.log("PLAYER_MONITOR_UNAVAILABLE", "reason=view_tree_not_alive")
        }
        checkSource("watch_initial")
        // Catch a player already connected before the observer was registered.
        refreshPlayer("watch_initial")
    }

    private fun checkSource(reason: String) {
        val vm = model ?: run {
            ElevateAudioTrace.log("SOURCE_SKIP", "reason=$reason no_view_model")
            return
        }
        val state = vm.state
        val generator = state.generatorState ?: run {
            ElevateAudioTrace.log("SOURCE_SKIP",
                "reason=$reason instance=${state.instance} generator_null")
            return
        }
        val response = generator.response ?: run {
            ElevateAudioTrace.log("SOURCE_SKIP",
                "reason=$reason instance=${state.instance} response_null " +
                    "meta=${generator.meta?.javaClass?.simpleName}")
            return
        }
        val name = response.apiName
        if (lastInstance == state.instance && lastProvider == name) return
        ElevateAudioTrace.log("SOURCE_RESOLVED",
            "reason=$reason instance=${state.instance} apiName=$name type=${response.type} " +
                "meta=${generator.meta?.javaClass?.simpleName}")
        lastInstance = state.instance
        lastProvider = name
        sourceEligible = isSupportedProvider(name)
        selected = false
        val episode = generator.meta as? ResultEpisode
        val flag = animeFlag(episode)
        isAnime = flag ?: ((episode?.tvType ?: response.type) in setOf(
            TvType.Anime, TvType.AnimeMovie, TvType.OVA
        ))
        diagnostics.episode(
            state.instance, name, response.type.toString(), episode?.tvType?.toString(),
            flag, isAnime, state.links.size, state.subtitles.size
        )
        ElevateAudioTrace.log("PROVIDER_GATE",
            "instance=${state.instance} apiName=$name enabled=$sourceEligible " +
                "dataAnime=$flag finalAnime=$isAnime")
        val activePlayer = player
        if (activePlayer != null) {
            ElevateAudioTrace.log("SOURCE_READY_WITH_PLAYER",
                "instance=${state.instance} player=${System.identityHashCode(activePlayer)}")
            select(activePlayer.currentTracks)
        } else {
            refreshPlayer("source_ready")
        }
    }

    private fun select(tracks: Tracks) {
        val current = player ?: return
        if (!sourceEligible) {
            ElevateAudioTrace.log("AUTO_SKIPPED", "reason=source_not_ready_or_unsupported instance=$lastInstance")
            return
        }
        if (selected) {
            diagnostics.snapshot("SELECT_SKIPPED_ALREADY_SELECTED")
            return
        }
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) {
            diagnostics.attempt(emptyList(), false, null, null, selected, "no_audio_groups")
            return
        }
        val appLanguage = fragment?.context?.resources?.configuration?.let {
            ConfigurationCompat.getLocales(it).get(0)?.language
        }
        val languages = listOfNotNull(appLanguage, if (isAnime) "ja" else "en",
            "en".takeIf { isAnime }).map { it.lowercase(Locale.ROOT) }.distinct()
        val match = languages.firstNotNullOfOrNull { language ->
            groups.firstNotNullOfOrNull { group ->
                (0 until group.length).firstOrNull { i ->
                    val raw = group.getTrackFormat(i).language?.trim().orEmpty()
                    val code = SubtitleHelper.fromCodeToLangTagIETF(raw)
                        ?: SubtitleHelper.fromCodeToLangTagIETF(
                            raw.replace('_', '-').substringBefore('-')
                        )
                    group.isTrackSupported(i) &&
                        code?.substringBefore('-')?.equals(language, ignoreCase = true) == true
                }?.let { group to it }
            }
        }
        diagnostics.attempt(languages, true, match?.first?.getTrackFormat(match.second)?.language,
            match?.second, selected, if (match == null) "no_priority_match" else "priority_match")
        if (match == null) {
            ElevateAudioTrace.log("AUTO_WAITING_FOR_TRACKS",
                "instance=$lastInstance groups=${groups.size} priority=$languages")
            return
        }
        selected = true
        current.removeListener(audioListener)
        diagnostics.beforeAuto()
        val (group, index) = match
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
        diagnostics.afterAuto()
        ElevateAudioTrace.log("AUTO_APPLIED",
            "instance=$lastInstance language=${group.getTrackFormat(index).language} index=$index")
    }

    private fun stopWaiting() {
        waitingOn?.let { if (it.isAlive) it.removeOnPreDrawListener(onDraw) }
        waitingOn = null
    }

    private fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        if (fragment != null) {
            ElevateAudioTrace.log("WATCH_DETACH",
                "fragment=${fragment?.let(System::identityHashCode)} instance=$lastInstance")
        }
        stopWaiting()
        removeObserver?.invoke()
        removeObserver = null
        player?.removeListener(audioListener)
        diagnostics.close()
        player = null
        model = null
        lastInstance = null
        lastProvider = null
        sourceEligible = false
        fragment = null
        selected = false
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity, "activity_resumed")
    }
    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity) use(activity, "activity_started")
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity) {
            ElevateAudioTrace.log("ACTIVITY_DESTROYED", "activity=${activity.javaClass.name}")
            this.activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
            detach()
            this.activity = null
        }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
