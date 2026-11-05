package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val main = Handler(Looper.getMainLooper())
    private var attachAttempts = 0
    // Native player can attach after network callbacks; a real layout event is
    // a cheap fallback after the finite initial attach window.
    private var observedRoot: View? = null
    private val rootLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        refreshPlayer()
    }
    private val attachRetry = object : Runnable {
        override fun run() {
            if (fragment == null || player != null || attachAttempts >= 16) return
            attachAttempts++
            refreshPlayer()
            if (player == null && fragment != null)
                main.postDelayed(this, 125L)
        }
    }
    private fun requestAttach() {
        main.removeCallbacks(attachRetry)
        if (player != null || fragment == null) return
        attachAttempts = 0
        main.post(attachRetry)
    }
    private var lastInstance: Int? = null
    private var lastProvider: String? = null
    private var isAnime = false
    private var selected = false
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var animeJob: Job? = null
    private var awaitingAnime = false
    private var pendingTrackParameters: TrackSelectionParameters? = null
    private var lastEpisodeId: Int? = null


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

    private fun refreshPlayer() {
        val active = fragment?.takeIf { it.isAdded && it.view != null }
        val next = active?.playerView?.player
        if (next === player) return
        if (next != null) main.removeCallbacks(attachRetry)
        val old = player
        if (old != null) {
            old.removeListener(audioListener)
        }
        player = next
        selected = false
        pendingTrackParameters = null
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
        PipLibraryNavigation.currentPlayerHost()?.let { use(it) }
    }

    fun stop() {
        if (started) application.unregisterActivityLifecycleCallbacks(this)
        started = false
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        detach()
        activity = null
        scope.cancel()
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

    private fun animeFlag(episode: ResultEpisode?): Boolean? {
        val data = episode?.data?.takeIf { it.trimStart().startsWith("{") } ?: return null
        return runCatching {
            JSONObject(data).takeIf { it.has("isAnime") && !it.isNull("isAnime") }
                ?.getBoolean("isAnime")
        }.getOrNull()
    }

    private fun watch(candidate: Fragment) {
        if (candidate !is GeneratorPlayer) return
        if (!candidate.isAdded || candidate.view == null) return
        if (fragment === candidate) {
            refreshPlayer()
            if (player == null) requestAttach()
            return
        }
        detach()
        fragment = candidate
        observedRoot = candidate.view?.also { it.addOnLayoutChangeListener(rootLayout) }
        val vm = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
        model = vm
        val loadingObserver = Observer<VideoLive<Resource<Unit>>> {
            checkSource()
            refreshPlayer()
            if (player == null) requestAttach()
        }
        val linksObserver = Observer<VideoLive<Set<VideoLink>>> {
            checkSource()
            refreshPlayer()
            if (player == null) requestAttach()
        }
        vm.loadingLinks.observe(candidate.viewLifecycleOwner, loadingObserver)
        vm.currentLinks.observe(candidate.viewLifecycleOwner, linksObserver)
        removeObserver = {
            vm.loadingLinks.removeObserver(loadingObserver)
            vm.currentLinks.removeObserver(linksObserver)
        }
        checkSource()
        // No frame observer: Media3 track changes are delivered by audioListener.
        // Mirror changes are signalled by the ViewModel's link/loading events.
        refreshPlayer()
        if (player == null) requestAttach()
    }

    private fun checkSource() {
        val vm = model ?: return
        val state = vm.state
        val generator = state.generatorState ?: return
        val response = generator.response
        val offline = generator.meta as? ExtractorUri
        if (response == null && offline == null) {
            return
        }
        val name = response?.apiName.orEmpty()
        val episode = generator.meta as? ResultEpisode
        val episodeId = episode?.id ?: offline?.id
        if (lastInstance == state.instance && lastProvider == name &&
            lastEpisodeId == episodeId) return
        animeJob?.cancel()
        animeJob = null
        awaitingAnime = false
        pendingTrackParameters = null
        lastInstance = state.instance
        lastProvider = name
        lastEpisodeId = episodeId
        selected = false
        val flag = animeFlag(episode)
        val tags = response?.tags.orEmpty().map { it.trim().lowercase(Locale.ROOT) }.toSet()
        isAnime = (flag ?: ((episode?.tvType ?: offline?.tvType ?: response?.type) in setOf(
            TvType.Anime, TvType.AnimeMovie, TvType.OVA
        ))) || "anime" in tags || ("animation" in tags && "japanese" in tags)

        if (offline != null && response == null && !isAnime) {
            // The native download generator often labels anime as TvSeries.
            // Resolve the saved title ID without blocking the player or picking English first.
            awaitingAnime = true
            val expectedFragment = fragment
            val expectedInstance = state.instance
            animeJob = scope.launch {
                val header = withContext(Dispatchers.IO) {
                    application.getKey<DownloadObjects.DownloadHeaderCached>(
                        DOWNLOAD_HEADER_CACHE, offline.parentId.toString()
                    )
                }
                val detected = header?.let { ElevateSkipRepository.isDownloadedAnime(it, offline) }
                if (fragment !== expectedFragment || lastInstance != expectedInstance ||
                    lastEpisodeId != episodeId) return@launch
                awaitingAnime = false
                if (detected != null) isAnime = detected
                val current = player
                if (current != null) {
                    if (pendingTrackParameters != null &&
                        pendingTrackParameters != current.trackSelectionParameters) {
                        // Do not override a manual track change while metadata was loading.
                        selected = true
                        current.removeListener(audioListener)
                    } else {
                        select(current.currentTracks)
                    }
                }
                pendingTrackParameters = null
            }
        }
        val activePlayer = player
        if (activePlayer != null) {
            select(activePlayer.currentTracks)
        } else {
            refreshPlayer()
        }
    }

    private fun select(tracks: Tracks) {
        val current = player ?: return
        if (selected) {
            return
        }
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) {
            return
        }
        if (awaitingAnime) {
            if (pendingTrackParameters == null) {
                pendingTrackParameters = current.trackSelectionParameters
            }
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
        if (match == null) {
            return
        }
        selected = true
        current.removeListener(audioListener)
        val (group, index) = match
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
        main.postDelayed({
            if (player === current) {
            }
        }, 250L)
    }

    private fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        main.removeCallbacks(attachRetry)
        attachAttempts = 0
        animeJob?.cancel()
        animeJob = null
        awaitingAnime = false
        pendingTrackParameters = null
        observedRoot?.removeOnLayoutChangeListener(rootLayout)
        observedRoot = null
        removeObserver?.invoke()
        removeObserver = null
        player?.removeListener(audioListener)
        player = null
        model = null
        lastInstance = null
        lastProvider = null
        lastEpisodeId = null
        fragment = null
        selected = false
    }

    override fun onActivityResumed(activity: Activity) {
        PipLibraryNavigation.playerHost(activity)?.let(::use)
    }
    override fun onActivityStarted(activity: Activity) {
        PipLibraryNavigation.playerHost(activity)?.let(::use)
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
