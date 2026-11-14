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
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.SubtitleHelper
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
    private var selected = false
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var originJob: Job? = null
    private var originAttempted = false
    private var originLanguage: String? = null
    private var lookupTmdb: String? = null
    private var lookupImdb: String? = null
    private var lookupSimkl: String? = null
    private var lookupAnimeIds = emptyList<Pair<String, String>>()
    private var lookupMovie = false
    private var unresolvedOffline: ExtractorUri? = null
    private var sourceVersion = 0
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

    private fun normalizedLanguage(value: String?): String? {
        val code = value?.trim()?.replace('_', '-')?.lowercase(Locale.ROOT) ?: return null
        return code.takeIf {
            it.matches(Regex("[a-z]{2,3}(-[a-z]{2})?")) &&
                it.substringBefore('-') !in setOf("und", "zxx")
        }?.substringBefore('-')
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
        if (response == null && offline == null) return
        val name = response?.apiName.orEmpty()
        val episode = generator.meta as? ResultEpisode
        val episodeId = episode?.id ?: offline?.id
        if (lastInstance == state.instance && lastProvider == name &&
            lastEpisodeId == episodeId) return
        originJob?.cancel()
        originJob = null
        sourceVersion++
        lastInstance = state.instance
        lastProvider = name
        lastEpisodeId = episodeId
        selected = false
        originAttempted = false
        val offlineIdentity = if (offline != null && response == null)
            ElevateOfflineMedia.identity(application, offline.parentId) else null
        unresolvedOffline = offline?.takeIf { response == null && offlineIdentity == null }
        originLanguage = normalizedLanguage(response?.syncData?.get("original_language"))
            ?: normalizedLanguage(runCatching {
                JSONObject(episode?.data.orEmpty()).optString("original_language")
            }.getOrNull())
            ?: normalizedLanguage(offline?.parentId?.let {
                ElevateOfflineMedia.originalLanguage(application, it)
            })
        val ids = ElevateMediaIdentity.collect(response, episode?.data)
        lookupTmdb = offlineIdentity?.tmdb ?: ids.tmdb
        lookupImdb = offlineIdentity?.imdb ?: ids.imdb
        lookupSimkl = offlineIdentity?.simkl ?: ids.simkl
        lookupAnimeIds = listOfNotNull(
            (offlineIdentity?.mal ?: ids.mal)?.let { "mal" to it },
            (offlineIdentity?.anilist ?: ids.anilist)?.let { "anilist" to it },
            (offlineIdentity?.kitsu ?: ids.kitsu)?.let { "kitsu" to it }
        )
        val type = episode?.tvType ?: offline?.tvType ?: response?.type
        val declaredMovie = runCatching {
            JSONObject(response?.url.orEmpty()).optString("type") == "movie"
        }.getOrDefault(false)
        lookupMovie = offlineIdentity?.movie ?: (type in setOf(TvType.Movie, TvType.AnimeMovie) || declaredMovie)
        player?.let { select(it.currentTracks) } ?: refreshPlayer()
    }

    private fun select(tracks: Tracks) {
        val current = player ?: return
        if (selected) return
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) return
        val appLanguage = fragment?.context?.resources?.configuration?.let {
            ConfigurationCompat.getLocales(it).get(0)?.language
        }?.let(::normalizedLanguage)
        fun match(language: String?): Pair<Tracks.Group, Int>? {
            val wanted = language ?: return null
            return groups.firstNotNullOfOrNull { group ->
                (0 until group.length).firstOrNull { i ->
                    val raw = group.getTrackFormat(i).language?.trim().orEmpty()
                    val code = SubtitleHelper.fromCodeToLangTagIETF(raw)
                        ?: SubtitleHelper.fromCodeToLangTagIETF(
                            raw.replace('_', '-').substringBefore('-'))
                    group.isTrackSupported(i) &&
                        code?.substringBefore('-')?.equals(wanted, ignoreCase = true) == true
                }?.let { group to it }
            }
        }
        val appMatch = match(appLanguage)
        if (appMatch != null) {
            choose(current, appMatch)
            return
        }
        val available = groups.sumOf { group ->
            (0 until group.length).count { group.isTrackSupported(it) }
        }
        if (originLanguage == null && !originAttempted && available > 1 &&
            (lookupTmdb != null || lookupImdb != null || lookupSimkl != null ||
                lookupAnimeIds.isNotEmpty() || unresolvedOffline?.parentId != null)) {
            originAttempted = true
            val version = sourceVersion
            val sourcePlayer = current
            val initialParameters = current.trackSelectionParameters
            val tmdb = lookupTmdb
            val imdb = lookupImdb
            val simkl = lookupSimkl
            val animeIds = lookupAnimeIds
            val movie = lookupMovie
            val offlineFile = unresolvedOffline
            originJob = scope.launch {
                val resolved = try {
                    // Recover legacy offline IDs only if no saved identifier is usable.
                    val recovered = if (tmdb == null && imdb == null && simkl == null &&
                        animeIds.isEmpty()) offlineFile?.let { file ->
                        val parentId = file.parentId ?: return@let null
                        val header = withContext(Dispatchers.IO) {
                            application.getKey<DownloadObjects.DownloadHeaderCached>(
                                DOWNLOAD_HEADER_CACHE, parentId.toString())
                        }
                        header?.let { ElevateSkipRepository.identityDownloaded(it, file, 0L) }
                    } else null
                    val resolvedMovie = recovered?.movie ?: movie
                    val direct = (tmdb ?: recovered?.tmdb) ?:
                        (imdb ?: recovered?.imdb)?.let {
                            ElevateIdResolver.tmdbForImdb(it, resolvedMovie)
                        }
                    val mapped = if (direct == null && (simkl ?: recovered?.simkl) != null)
                        ElevateMediaIdentity.simklMapping(
                            ElevateMediaIdentity.Ids(simkl = simkl ?: recovered?.simkl),
                            resolvedMovie) else null
                    var tmdbId = direct ?: mapped?.tmdb ?: mapped?.imdb?.let {
                        ElevateIdResolver.tmdbForImdb(it, resolvedMovie)
                    }
                    if (tmdbId == null) for ((source, id) in animeIds + listOfNotNull(
                        recovered?.mal?.let { "mal" to it },
                        recovered?.anilist?.let { "anilist" to it },
                        recovered?.kitsu?.let { "kitsu" to it }
                    )) {
                        val animeMapping = ElevateAnimeIdResolver.resolve(source, id) ?: continue
                        tmdbId = animeMapping.tmdb ?: animeMapping.imdb?.let {
                            ElevateIdResolver.tmdbForImdb(it, resolvedMovie)
                        }
                        if (tmdbId != null) break
                    }
                    tmdbId?.let {
                        ElevateTmdbMetadata.originalLanguage(it, resolvedMovie,
                            com.lagradost.cloudstream3.ui.settings.getCurrentLocale(application),
                            includeCredits = ElevateFeaturePrefs.enabled(application,
                                ElevateFeature.ACTOR_INFORMATION))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                  catch (_: Exception) { null }
                if (sourceVersion != version) return@launch
                originJob = null
                originLanguage = normalizedLanguage(resolved)
                val activePlayer = player ?: return@launch
                if (activePlayer === sourcePlayer &&
                    activePlayer.trackSelectionParameters != initialParameters) {
                    // Never override an audio track manually chosen while metadata was loading.
                    selected = true
                    activePlayer.removeListener(audioListener)
                    return@launch
                }
                select(activePlayer.currentTracks)
            }
            return
        }
        if (originJob != null) return
        val fallback = match(originLanguage) ?: match("en") ?: return
        choose(current, fallback)
    }

    private fun choose(current: Media3Player, match: Pair<Tracks.Group, Int>) {
        selected = true
        current.removeListener(audioListener)
        val (group, index) = match
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
    }

    private fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        main.removeCallbacks(attachRetry)
        attachAttempts = 0
        originJob?.cancel()
        originJob = null
        originAttempted = false
        originLanguage = null
        lookupTmdb = null
        lookupImdb = null
        lookupSimkl = null
        lookupAnimeIds = emptyList()
        unresolvedOffline = null
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
