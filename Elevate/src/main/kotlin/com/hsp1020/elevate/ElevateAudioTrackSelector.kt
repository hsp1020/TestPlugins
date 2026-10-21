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
        refreshPlayer()
        true
    }

    private fun refreshPlayer() {
        val active = fragment?.takeIf { it.isAdded && it.view != null }
        val next = active?.playerView?.player
        if (next === player) return
        val old = player
        if (old != null) {
            old.removeListener(audioListener)
        }
        player = next
        selected = false
        if (next != null && active != null) {
            next.addListener(audioListener)
            select(next.currentTracks)
        }
    }

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, saved: Bundle?
        ) {
            watch(f)
        }
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) =
            watch(f)
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            detach(f)
        }
    }

    fun start() {
        if (started) return
        started = true
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let { use(it) }
    }

    fun stop() {
        if (started) application.unregisterActivityLifecycleCallbacks(this)
        started = false
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        detach()
        activity = null
    }

    private fun use(next: FragmentActivity) {
        if (!started) return
        if (activity !== next) {
            activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
            detach()
            activity = next
            next.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        }
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { f ->
                if (f.view != null) watch(f)
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
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

    private fun watch(candidate: Fragment) {
        if (candidate !is GeneratorPlayer) return
        if (!candidate.isAdded || candidate.view == null || fragment === candidate) return
        detach()
        fragment = candidate
        val vm = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
        model = vm
        val loadingObserver = Observer<VideoLive<Resource<Unit>>> {
            checkSource()
        }
        val linksObserver = Observer<VideoLive<Set<VideoLink>>> {
            checkSource()
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
        }
        checkSource()
        // Catch a player already connected before the observer was registered.
        refreshPlayer()
    }

    private fun checkSource() {
        val vm = model ?: return
        val state = vm.state
        val generator = state.generatorState ?: return
        val response = generator.response ?: return
        val name = response.apiName
        if (lastInstance == state.instance && lastProvider == name) return
        lastInstance = state.instance
        lastProvider = name
        sourceEligible = isSupportedProvider(name)
        selected = false
        val episode = generator.meta as? ResultEpisode
        val flag = animeFlag(episode)
        isAnime = flag ?: ((episode?.tvType ?: response.type) in setOf(
            TvType.Anime, TvType.AnimeMovie, TvType.OVA
        ))
        val activePlayer = player
        if (activePlayer != null) {
            select(activePlayer.currentTracks)
        } else {
            refreshPlayer()
        }
    }

    private fun select(tracks: Tracks) {
        val current = player ?: return
        if (!sourceEligible || selected) return
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) return
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
        if (match == null) return
        selected = true
        current.removeListener(audioListener)
        val (group, index) = match
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
    }

    private fun stopWaiting() {
        waitingOn?.let { if (it.isAlive) it.removeOnPreDrawListener(onDraw) }
        waitingOn = null
    }

    private fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        stopWaiting()
        removeObserver?.invoke()
        removeObserver = null
        player?.removeListener(audioListener)
        player = null
        model = null
        lastInstance = null
        lastProvider = null
        sourceEligible = false
        fragment = null
        selected = false
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity) {
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
