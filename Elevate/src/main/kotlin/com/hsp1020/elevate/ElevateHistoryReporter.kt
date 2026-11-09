package com.hsp1020.elevate

import android.content.Context
import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.function.Consumer
import android.net.Uri
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.UUID

@OptIn(UnstableApi::class)
internal class ElevateHistoryReporter(private val application: Application) : Application.ActivityLifecycleCallbacks {
    private val prefs = application.getSharedPreferences(ElevateFeaturePrefs.FILE, Context.MODE_PRIVATE)
    private var host: FragmentActivity? = null
    private val downloadedMappings = LinkedHashMap<String, Pair<Long, Item>>()
    private val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Report>(64)
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var tree: ViewTreeObserver? = null
    private val main = Handler(Looper.getMainLooper())
    private var player: Media3Player? = null
    private var active: Item? = null
    private var lastEvent: String? = null
    private var lastPosition = 0L
    private var lastDuration = 0L

    private var approvedDurationMs = 0L
    private var pendingSeekReport: Runnable? = null
    private var lastSeekReportAtMs = 0L
    private var seekCompletionPending = false
    private var seekPlaybackStartedAtMs = 0L
    private data class Item(
        val id: Int, val url: String, val api: String,
        val movie: Boolean, val season: Int?, val episode: Int?,
        val anime: Boolean, val stremioProvider: Any?, val stremioData: String?,
        val ids: Map<String, String>, val imdbNumbers: Pair<Int?, Int?>?, val urlId: String?,
        val expectedDurationMs: Long?, val offline: ExtractorUri? = null
    ) { val key: String get() = url + "#" + id }

    private data class Report(
        val item: Item, val event: String, val position: Long,
        val duration: Long, val target: String, val played: Boolean? = null
    )

    private fun originalPlaybackId(url: String, ids: Map<String, String>): String? {
        val json = runCatching { JSONObject(url) }.getOrNull()
        val raw = json?.optString("id")?.takeIf { it.isNotBlank() }
            ?: Regex("/meta/(?:movie|series)/([^/?]+)[.]json").find(url)
                ?.groupValues?.getOrNull(1)?.let(Uri::decode)
            ?: return null
        return when {
            raw.matches(Regex("tt[0-9]+")) -> raw
            raw.matches(Regex("(?:tmdb|kitsu|mal):[0-9]+")) -> raw
            raw.toLongOrNull()?.let { it > 0L } == true &&
                json?.optString("type") in setOf("movie", "tv") &&
                raw == ids["tmdb"] -> "tmdb:$raw"
            else -> null
        }
    }

    init {
        senderScope.launch {
            for (report in queue) try {
                send(report)
            } catch (e: Exception) {
                Log.e("ElevateHistory", "stage=SEND_FAILED event=" +
                    report.event + " type=" + e.javaClass.simpleName)
            }
        }
    }

    private fun snapshot(): Item? {
        val state = model?.state?.generatorState ?: run { return null }
        val response = state.response ?: run {
            val file = state.meta as? ExtractorUri
                ?: run { return null }
            val id = file.id ?: run { return null }
            val parentId = file.parentId ?: run { return null }
            val movie = file.tvType in setOf(TvType.Movie, TvType.AnimeMovie)
            return Item(id, "download:$parentId", "Download", movie,
                if (movie) null else file.season, if (movie) null else file.episode,
                file.tvType in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA),
                null, null, emptyMap(), null, null, null, file)
        }
        val episode = state.meta as? ResultEpisode ?: run { return null }
        val collected = ElevateMediaIdentity.collect(response, episode.data)
        val ids = collected.asMap().toMutableMap()
        val stremio = APIHolder.allProviders.firstOrNull {
            it.name.equals(response.apiName, true) &&
                it.javaClass.name == "com.hsp1020.StremioC"
        }
        if (response.apiName == "StremioC" && stremio == null) {
            return null
        }
        val movie = episode.tvType in setOf(TvType.Movie, TvType.AnimeMovie)
        val imdbNumbers = if (collected.imdbSeason != null || collected.imdbEpisode != null)
            collected.imdbSeason to collected.imdbEpisode else null
        val urlId = Regex("/meta/(?:movie|series)/([^/?]+)[.]json").find(response.url)
            ?.groupValues?.getOrNull(1)?.let(Uri::decode)
        val expectedMinutes = if (movie) (response as? MovieLoadResponse)?.duration
            ?: episode.runTime else episode.runTime
        return Item(episode.id, response.url, response.apiName, movie,
            if (movie) null else episode.season, if (movie) null else episode.episode,
            ElevateMediaIdentity.anime(response, episode, collected),
            stremio, if (stremio != null) episode.data else null, ids, imdbNumbers, urlId,
            expectedMinutes?.takeIf { it in 1 until 1_440 }?.toLong()?.times(60_000L))
    }

    private fun playbackBase(): String? {
        if (!ElevateFeaturePrefs.enabled(application, ElevateFeature.HISTORY_SYNC)) return null
        val url = prefs.getString("HISTORY_SYNC_URL", null)?.trim()
            ?.replace("stremio://", "https://") ?: return null
        return runCatching {
            val uri = URI(url)
            val parts = uri.path.split('/').filter { it.isNotEmpty() }
            val index = parts.indexOf("stremio")
            val user = parts.getOrNull(index + 1)
            if (uri.scheme !in listOf("https", "http") || uri.rawAuthority.isNullOrBlank() ||
                index < 0 || user.isNullOrBlank()) null
            else uri.scheme + "://" + uri.rawAuthority + "/stremio/" + user
        }.getOrNull()
    }

    private fun playbackPosition(item: Item, current: Media3Player?): Pair<Long, Long> {
        val position = current?.currentPosition?.takeIf { it >= 0L }
        val duration = current?.duration?.takeIf { it > 0L }
        if (position != null && duration != null) return position to duration
        val saved = DataStoreHelper.getViewPos(item.id)
        return (position ?: saved?.position ?: lastPosition).coerceAtLeast(0L) to
            (duration ?: saved?.duration?.takeIf { it > 0L } ?: lastDuration).coerceAtLeast(0L)
    }

    private fun approvedRuntime(item: Item, current: Media3Player?): Boolean {
        val actual = current?.duration?.takeIf { it > 0L }
        if (actual != null) {
            val minimum = item.expectedDurationMs?.let { (it + 1L) / 2L } ?: 300_000L
            val passed = actual >= minimum && (item.expectedDurationMs?.let {
                it > Long.MAX_VALUE - it / 2L || actual <= it + it / 2L
            } ?: true)
            if (active?.key == item.key && current === player)
                approvedDurationMs = if (passed) actual else 0L
            return passed
        }

        return current == null && active?.key == item.key && approvedDurationMs > 0L
    }

    private fun emit(event: String, item: Item, current: Media3Player? = player, played: Boolean? = null, allowRepeat: Boolean = false) {
        if (lastEvent == "stop" ||
            (event == "start" && current?.isPlaying != true) ||
            (event != "start" && lastEvent == null) ||
            (event != "start" && !allowRepeat && lastEvent == event)) {
            return
        }

        if (!approvedRuntime(item, current)) {
            return
        }
        val target = playbackBase() ?: run {
            return
        }
        val openingSession = event == "start" && lastEvent == null
        val (pos, rawDuration) = playbackPosition(item, current)
        val duration = if (current == null) approvedDurationMs else rawDuration
        lastPosition = pos
        lastDuration = duration
        lastEvent = event
        if (openingSession && duration > 0L && pos.toDouble() / duration >= 0.80) {

            seekCompletionPending = true
            seekPlaybackStartedAtMs = SystemClock.elapsedRealtime()
        }
        val report = Report(item, event, pos, duration, target, played)
        if (queue.trySend(report).isFailure) {

            senderScope.launch { runCatching { queue.send(report) } }
        }
    }

    private fun refreshEpisode(): Boolean {
        val state = model?.state?.generatorState ?: run { return false }
        val key = when (val meta = state.meta) {
            is ResultEpisode -> state.response?.let { it.url + "#" + meta.id }
            is ExtractorUri -> "download:" + meta.parentId + "#" + meta.id
            else -> null
        } ?: run { return false }
        if (active?.key == key) return false

        val next = snapshot()
        active?.let { old ->

            if (lastEvent != null && lastEvent != "stop") emit("stop", old, null)
        }
        approvedDurationMs = 0L
        cancelCompletion()
        cancelPendingSeek()
        lastSeekReportAtMs = 0L
        active = next
        lastEvent = null
        lastPosition = 0L
        lastDuration = 0L
        seekCompletionPending = false
        seekPlaybackStartedAtMs = 0L
        if (next != null && player?.isPlaying == true) {
            emit("start", next)
            scheduleCompletion()
        }
        return true
    }

    private fun attachPlayer() {
        val next = fragment?.playerView?.player
        if (next === player) return
        cancelPendingSeek()
        lastSeekReportAtMs = 0L
        cancelCompletion()
        player?.removeListener(listener)
        player = next
        next?.addListener(listener)
        val changed = refreshEpisode()

        if (!changed) approvedDurationMs = 0L
        if (!changed && next?.isPlaying == true) {
            active?.let { emit("start", it) }
            scheduleCompletion()
        }
    }

    private fun scheduleCompletion() {
        main.removeCallbacks(completion)
        val current = player ?: return
        if (lastEvent == "stop" || !current.isPlaying) return
        val item = active ?: return
        if (!approvedRuntime(item, current)) return
        val (position, duration) = playbackPosition(item, current)
        if (duration <= 0L) return
        val threshold = (duration * 0.80).toLong()
        val remaining = (threshold - position).coerceAtLeast(0L)
        val speed = current.playbackParameters.speed.toDouble().coerceAtLeast(0.1)
        val timeToThreshold = (remaining / speed).toLong().coerceAtLeast(1000L)
        if (seekCompletionPending && seekPlaybackStartedAtMs == 0L)
            seekPlaybackStartedAtMs = SystemClock.elapsedRealtime()
        val seekWait = if (seekCompletionPending)
            (60_000L - (SystemClock.elapsedRealtime() - seekPlaybackStartedAtMs)).coerceAtLeast(0L)
            else 0L
        main.postDelayed(completion, maxOf(timeToThreshold, seekWait))
    }

    private val completion = Runnable {
        val item = active
        val current = player
        if (item != null && current?.isPlaying == true && lastEvent != "stop" &&
            approvedRuntime(item, current)) {
            val (position, duration) = playbackPosition(item, current)
            val seekWaitSatisfied = seekPlaybackStartedAtMs != 0L &&
                SystemClock.elapsedRealtime() - seekPlaybackStartedAtMs >= 60_000L
            if (duration > 0L && position.toDouble() / duration >= 0.80 &&
                (!seekCompletionPending || seekWaitSatisfied)) {
                emit("stop", item, current, played = true)
            } else scheduleCompletion()
        }
    }

    private fun cancelCompletion() {
        main.removeCallbacks(completion)
    }

    private fun reportSeek(item: Item) {
        if (lastEvent == null || lastEvent == "stop") return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastSeekReportAtMs
        if (lastSeekReportAtMs == 0L || elapsed >= 15_000L) {
            cancelPendingSeek()
            emit(if (player?.isPlaying == true) "start" else "pause", item, allowRepeat = true)
            lastSeekReportAtMs = now
            return
        }
        if (pendingSeekReport != null) return
        pendingSeekReport = Runnable {
            pendingSeekReport = null
            if (active?.key == item.key && lastEvent != null && lastEvent != "stop") {

                emit(if (player?.isPlaying == true) "start" else "pause", item, allowRepeat = true)
                lastSeekReportAtMs = SystemClock.elapsedRealtime()
            }
        }.also { main.postDelayed(it, 15_000L - elapsed) }
    }

    private fun cancelPendingSeek() {
        pendingSeekReport?.let(main::removeCallbacks)
        pendingSeekReport = null
    }

    private val layout = ViewTreeObserver.OnGlobalLayoutListener {
        attachPlayer()

        if (player?.isPlaying == true) refreshEpisode()
    }

    private val listener = java.lang.reflect.Proxy.newProxyInstance(
        Media3Player.Listener::class.java.classLoader,
        arrayOf(Media3Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onIsPlayingChanged" -> {
                val changed = refreshEpisode()
                if (args?.getOrNull(0) == true) {

                    cancelPendingSeek()
                    lastSeekReportAtMs = 0L
                    if (!changed) active?.let { emit("start", it) }
                    scheduleCompletion()
                } else {
                    if (seekCompletionPending) seekPlaybackStartedAtMs = 0L
                    cancelCompletion()
                }
                null
            }
            "onPlayWhenReadyChanged" -> {
                if (args?.getOrNull(0) == false) {
                    cancelPendingSeek()
                    lastSeekReportAtMs = 0L
                    if (seekCompletionPending) seekPlaybackStartedAtMs = 0L
                    cancelCompletion()
                    refreshEpisode()
                    active?.let { emit("pause", it) }
                }
                null
            }
            "onPlaybackStateChanged" -> {
                when (args?.getOrNull(0)) {
                    Media3Player.STATE_READY -> {
                        refreshEpisode()

                        if (player?.isPlaying == true && lastEvent == null)
                            active?.let { emit("start", it) }
                        scheduleCompletion()
                    }
                    Media3Player.STATE_ENDED -> {
                        cancelPendingSeek()
                        lastSeekReportAtMs = 0L
                        cancelCompletion()
                        refreshEpisode()
                        active?.let { emit("stop", it, played = true) }
                    }
                }
                null
            }
            "onPlaybackParametersChanged" -> {
                scheduleCompletion()
                null
            }
            "onMediaItemTransition" -> {
                val changed = refreshEpisode()
                if (!changed) approvedDurationMs = 0L
                if (!changed && player?.isPlaying == true && lastEvent != "stop") {
                    active?.let { emit("start", it) }
                    scheduleCompletion()
                }
                null
            }
            "onTimelineChanged" -> {
                if (player?.isPlaying == true && lastEvent == null)
                    active?.let { emit("start", it) }
                scheduleCompletion()
                null
            }
            "onPositionDiscontinuity" -> {
                refreshEpisode()
                val reason = args?.lastOrNull() as? Int
                if (reason == Media3Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Media3Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    val current = player
                    val item = active
                    if (item != null && current != null && lastEvent != "stop") {
                        val (position, duration) = playbackPosition(item, current)
                        seekCompletionPending = duration > 0L &&
                            position.toDouble() / duration >= 0.80
                        seekPlaybackStartedAtMs = if (seekCompletionPending && current.isPlaying)
                            SystemClock.elapsedRealtime() else 0L
                        scheduleCompletion()
                        reportSeek(item)
                    }
                }
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ElevateHistoryReporterListener"
            else -> null
        }
    } as Media3Player.Listener

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: android.view.View, savedInstanceState: Bundle?
        ) = watch(f)
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) = watch(f)
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            detach(f)
        }
    }

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        (PipLibraryNavigation.currentPlayerHost()
            ?: CommonActivity.activity as? FragmentActivity)?.let(::use)
    }

    private fun containsPlayer(manager: FragmentManager): Boolean =
        manager.fragments.any { candidate ->
            (candidate is GeneratorPlayer && candidate.view != null) ||
                (candidate.isAdded && containsPlayer(candidate.childFragmentManager))
        }

    private fun use(activity: FragmentActivity) {
        // The visible online GeneratorPlayer may stay in MainActivity while an
        // auxiliary DownloadedPlayerActivity is resumed (e.g. PiP navigation).
        // Do not detach its observer for an Activity without that fragment.
        if (host !== activity &&
            host?.let { containsPlayer(it.supportFragmentManager) } == true &&
            !containsPlayer(activity.supportFragmentManager)) {
            return
        }
        if (host !== activity) {
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
            detach()
            host = activity
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        }
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { child ->
                if (child.view != null) watch(child)
                if (child.isAdded) scan(child.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is FragmentActivity &&
            (activity is com.lagradost.cloudstream3.MainActivity ||
                activity is com.lagradost.cloudstream3.ui.player.DownloadedPlayerActivity)) {
            use(activity)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity === host) {
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
            detach()
            host = null
        }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    fun watch(fragment: Fragment) {
        if (fragment !is GeneratorPlayer) return
        if (fragment === this.fragment) {
            attachPlayer()
            refreshEpisode()
            return
        }
        detach()
        this.fragment = fragment
        model = ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
        fragment.view?.viewTreeObserver?.takeIf { it.isAlive }?.let {
            tree = it
            it.addOnGlobalLayoutListener(layout)
        }
        fragment.view?.post {
            if (this.fragment === fragment) {
                attachPlayer()
                refreshEpisode()
            }
        }
    }

    fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        cancelCompletion()
        cancelPendingSeek()
        lastSeekReportAtMs = 0L
        active?.let { if (lastEvent != null && lastEvent != "stop") emit("stop", it) }
        tree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layout)
        player?.removeListener(listener)
        tree = null
        player = null
        fragment = null
        model = null
        active = null
        lastEvent = null
        lastPosition = 0L
        lastDuration = 0L
        approvedDurationMs = 0L
        seekCompletionPending = false
        seekPlaybackStartedAtMs = 0L
    }

    fun stop() {
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        host = null
        application.unregisterActivityLifecycleCallbacks(this)
        detach()
        queue.close()
    }

    private suspend fun stremioOverrides(item: Item): Map<String, String> {
        val provider = item.stremioProvider ?: return emptyMap()
        val result = withTimeoutOrNull(12_000L) {
            val completion = CompletableDeferred<Map<String, String>>()
            try {
                val method = provider.javaClass.getMethod("resolvePlaybackIds",
                    String::class.java, java.lang.Integer.TYPE, Consumer::class.java)
                method.invoke(provider, item.stremioData.orEmpty(), item.season ?: 0,
                    Consumer<Map<String, String>> { completion.complete(it) })
                completion.await()
            } catch (_: Exception) {
                emptyMap()
            }
        } ?: emptyMap()
        return result
    }

    private suspend fun resolveSimkl(id: String, movie: Boolean): Map<String, String> =
        ElevateMediaIdentity.enrichSimkl(
            ElevateMediaIdentity.Ids(simkl = id), movie
        ).asMap()


    /**
     * Offline StremioC headers store the unified metadata URL, not the catalog
     * that originally enabled metadata sync. Restore a catalog only when its
     * history permission is unambiguous; never override an explicit OFF.
     */
    private fun restoreDownloadedStremioCatalog(episodeJson: String, metaUrl: String): String {
        if (!metaUrl.contains("/meta/")) return episodeJson
        fun base(url: String): String = url.substringBefore('?')
            .removeSuffix("/manifest.json").trimEnd('/').lowercase()
        val prefs = application.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
        val syncBase = base(prefs.getString("history_sync_url", "").orEmpty())
        if (syncBase.isBlank() || base(metaUrl.substringBefore("/meta/")) != syncBase)
            return episodeJson
        val data = runCatching { JSONObject(episodeJson) }.getOrNull() ?: return episodeJson
        val declared = base(data.optString("addonUrl").takeUnless {
            it.equals("null", ignoreCase = true)
        }.orEmpty())
        if (declared.isNotBlank() && declared != syncBase) return episodeJson

        val links = runCatching {
            org.json.JSONArray(prefs.getString("stremio_saved_links", "[]"))
        }.getOrNull() ?: return episodeJson
        val candidates = linkedMapOf<String, Pair<String, Boolean>>()
        for (i in 0 until links.length()) {
            val entry = links.optJSONObject(i) ?: continue
            if (!entry.optBoolean("enabled", true) ||
                !entry.optBoolean("isSyncEnabled", true)) continue
            val url = entry.optString("link").trim().takeIf { it.isNotEmpty() } ?: continue
            candidates[base(url)] = url to entry.optBoolean("isHistorySyncEnabled", true)
        }
        if (candidates.isEmpty() || candidates.values.any { !it.second }) {
            return episodeJson
        }
        return data.put("addonUrl", candidates.values.first().first).toString()
    }

    /** Resolve offline metadata on the sender thread; never block playback callbacks. */
    private suspend fun resolveDownloaded(item: Item): Item? {
        val file = item.offline ?: return item
        val header = application.getKey<DownloadObjects.DownloadHeaderCached>(
            DOWNLOAD_HEADER_CACHE, file.parentId.toString()
        ) ?: run { return null }
        val cacheKey = listOf(header.apiName, header.url, file.parentId, file.id,
            file.season, file.episode).joinToString("|")
        val now = System.currentTimeMillis()
        downloadedMappings[cacheKey]?.takeIf { now - it.first < 3_600_000L }
            ?.let { return it.second }

        val stremio = header.apiName.equals("StremioC", true)
        val detail = try {
            withTimeoutOrNull(8_000L) {
                APIHolder.getApiFromNameNull(header.apiName)?.load(header.url)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val episodeData = when (detail) {
            is TvSeriesLoadResponse -> {
                val episodeNumber = file.episode
                val matches = detail.episodes.filter {
                    episodeNumber != null && episodeNumber > 0 && it.episode == episodeNumber
                }
                (matches.firstOrNull { it.season == file.season }
                    ?: matches.singleOrNull())?.data
            }
            is MovieLoadResponse -> detail.dataUrl
            else -> null
        }
        val stremioProvider = if (stremio) APIHolder.allProviders.firstOrNull {
            it.name.equals(header.apiName, true) &&
                it.javaClass.name == "com.hsp1020.StremioC"
        } else null
        if (stremio && (stremioProvider == null || episodeData.isNullOrBlank())) {
            return null // Never bypass StremioC's per-catalog History Sync toggle.
        }

        val collected = ElevateMediaIdentity.collect(
            detail, episodeData, urls = listOf(header.url), episodeFirst = true
        )
        val ids = collected.asMap().toMutableMap()
        val imdbNumbers = if (collected.imdbSeason != null || collected.imdbEpisode != null)
            collected.imdbSeason to collected.imdbEpisode else null
        if (detail == null || ids.isEmpty()) {
            ElevateSkipRepository.identityDownloaded(header, file, 0L)?.let { mapped ->
                mapped.imdb?.let { ids.putIfAbsent("imdb", it) }
                mapped.tmdb?.let { ids.putIfAbsent("tmdb", it) }
                mapped.kitsu?.let { ids.putIfAbsent("kitsu", it) }
                mapped.mal?.let { ids.putIfAbsent("mal", it) }
                mapped.anilist?.let { ids.putIfAbsent("anilist", it) }
            }
        }
        val extra = runCatching { JSONObject(episodeData.orEmpty()) }.getOrNull()
        val movie = file.tvType in setOf(TvType.Movie, TvType.AnimeMovie) ||
            header.type in setOf(TvType.Movie, TvType.AnimeMovie) ||
            detail?.type in setOf(TvType.Movie, TvType.AnimeMovie)
        val anime = file.tvType in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA) ||
            header.type in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA) ||
            detail?.type in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA) ||
            ids.keys.any { it in setOf("kitsu", "mal", "anilist") } ||
            extra?.optBoolean("isAnime") == true
        val urlId = Regex("/meta/(?:movie|series)/([^/?]+)[.]json").find(header.url)
            ?.groupValues?.getOrNull(1)?.let(Uri::decode)
        val resolved = item.copy(
            url = header.url, api = header.apiName, movie = movie,
            season = if (movie) null else file.season,
            episode = if (movie) null else file.episode,
            anime = anime, stremioProvider = stremioProvider,
            stremioData = if (stremio) episodeData?.let {
                restoreDownloadedStremioCatalog(it, header.url)
            } else null,
            ids = ids, imdbNumbers = imdbNumbers, urlId = urlId
        )
        downloadedMappings[cacheKey] = now to resolved
        if (downloadedMappings.size > 20)
            downloadedMappings.remove(downloadedMappings.keys.first())
        return resolved
    }

    private suspend fun send(report: Report) {
        if (!ElevateFeaturePrefs.enabled(application, ElevateFeature.HISTORY_SYNC)) return
        val item = report.item.offline?.let { resolveDownloaded(report.item) ?: return }
            ?: report.item
        val overrides = stremioOverrides(item)
        if (item.stremioProvider != null && overrides["_historyEnabled"] != "true") {
            return
        }
        val ids = item.ids.toMutableMap()
        ids.putAll(overrides.filterKeys { !it.startsWith("_") })
        if (ids["imdb"]?.matches(Regex("tt[0-9]+")) != true &&
            ids["tmdb"]?.toLongOrNull()?.let { it > 0L } != true) {
            ids["simkl"]?.let { ids.putAll(resolveSimkl(it, item.movie)) }
        }
        val imdb = ids["imdb"]?.takeIf { it.matches(Regex("tt[0-9]+")) }
        val tmdb = ids["tmdb"]?.takeIf { it.toLongOrNull()?.let { n -> n > 0L } == true }
        val anilist = (ids["anilist"] ?: item.urlId?.takeIf { it.startsWith("anilist:") })
            ?.removePrefix("anilist:")?.substringBefore(':')
            ?.takeIf { it.toLongOrNull()?.let { n -> n > 0L } == true }
        if (imdb == null && tmdb == null &&
            ids["kitsu"]?.removePrefix("kitsu:")?.toLongOrNull()?.let { it > 0L } != true &&
            ids["mal"]?.removePrefix("mal:")?.toLongOrNull()?.let { it > 0L } != true) {
            anilist?.let { ElevateAnimeIdResolver.resolve("anilist", it) }?.let { mapped ->
                mapped.kitsu?.let { ids["kitsu"] = it }
                mapped.mal?.let { ids["mal"] = it }
            }
        }
        val kitsu = ids["kitsu"]?.removePrefix("kitsu:")
            ?.takeIf { it.toLongOrNull()?.let { n -> n > 0L } == true }
        val mal = ids["mal"]?.removePrefix("mal:")
            ?.takeIf { it.toLongOrNull()?.let { n -> n > 0L } == true }
        val anime = item.anime || overrides["_anime"] == "true"
        val originalId = originalPlaybackId(item.url, ids)
        val base = when {
            item.stremioProvider != null && anime && kitsu != null -> "kitsu:" + kitsu
            originalId != null -> originalId
            imdb != null -> imdb
            tmdb != null -> "tmdb:" + tmdb
            kitsu != null -> "kitsu:" + kitsu
            mal != null -> "mal:" + mal
            else -> item.urlId?.takeIf {
                it.matches(Regex("tt[0-9]+|(?:tmdb|kitsu|mal):[0-9]+"))
            } ?: run {
                return
            }
        }
        val season = if (base.startsWith("kitsu:") || base.startsWith("mal:")) 1
            else overrides["_season"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: (if (base.startsWith("tt")) item.imdbNumbers?.first else null)
                ?: item.season
        val episode = if (base.startsWith("kitsu:") || base.startsWith("mal:")) item.episode
            else overrides["_episode"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: (if (base.startsWith("tt")) item.imdbNumbers?.second else null)
                ?: item.episode
        val videoId = if (item.movie) base else {
            if (episode == null || episode <= 0 || season == null || season <= 0) return
            if (base.startsWith("kitsu:") || base.startsWith("mal:")) base + ":" + episode
            else base + ":" + season + ":" + episode
        }
        val body = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("event", report.event)
            .put("metaId", base)
            .put("videoId", videoId)
            .put("positionMs", report.position)
            .put("durationMs", report.duration)
            .put("ids", JSONObject(ids))
        if (!item.movie) {
            body.put("season", season)
            body.put("episode", episode)
        }
        if (report.event == "stop")
            body.put("played", report.played ?: (report.duration > 0L &&
                report.position.toDouble() / report.duration >= 0.80))
        val type = if (item.movie) "movie" else "series"
        val response = app.post(
            report.target + "/playback/" + type + "/" + Uri.encode(videoId) + ".json",
            headers = mapOf("Content-Type" to "application/json"),
            requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()),
            timeout = 15L
        )
        response.okhttpResponse.close()
    }
}
