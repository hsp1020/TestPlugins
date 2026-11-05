package com.hsp1020.elevate

import android.content.Context
import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.getAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.getKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.readIdFromString
import com.lagradost.cloudstream3.SimklSyncServices
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
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
    private val simklMappings = LinkedHashMap<String, Pair<Long, Map<String, String>>>()
    private val downloadedMappings = LinkedHashMap<String, Pair<Long, Item>>()
    private val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Report>(64)
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var tree: ViewTreeObserver? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastSkipReason: String? = null
    private fun trace(stage: String, detail: String) {
        Log.d("ElevateHistoryDiag", "stage=" + stage + " " + detail)
    }
    private fun skip(reason: String) {
        if (lastSkipReason != reason) {
            lastSkipReason = reason
            trace("SKIP", "reason=" + reason)
        }
    }
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
        val ids: Map<String, String>, val cineKitsu: Pair<Int?, Int?>?, val urlId: String?,
        val expectedDurationMs: Long?, val offline: ExtractorUri? = null
    ) { val key: String get() = url + "#" + id }

    private data class Report(
        val item: Item, val event: String, val position: Long,
        val duration: Long, val target: String, val played: Boolean? = null
    )

    init {
        senderScope.launch {
            for (report in queue) try {
                send(report)
            } catch (e: Exception) {
                Log.e("ElevateHistoryDiag", "stage=SEND_FAILED event=" +
                    report.event + " type=" + e.javaClass.simpleName)
            }
        }
    }

    private fun snapshot(): Item? {
        val state = model?.state?.generatorState ?: run { skip("no_generator_state"); return null }
        val response = state.response ?: run {
            val file = state.meta as? ExtractorUri
                ?: run { skip("no_load_response"); return null }
            val id = file.id ?: run { skip("offline_no_id"); return null }
            val parentId = file.parentId ?: run { skip("offline_no_parent"); return null }
            val movie = file.tvType in setOf(TvType.Movie, TvType.AnimeMovie)
            trace("SNAPSHOT", "downloaded=true id=" + id + " parent=" + parentId)
            return Item(id, "download:$parentId", "Download", movie,
                if (movie) null else file.season, if (movie) null else file.episode,
                file.tvType in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA),
                null, null, emptyMap(), null, null, null, file)
        }
        val episode = state.meta as? ResultEpisode ?: run { skip("no_episode"); return null }
        val ids = linkedMapOf<String, String>()
        val saved = readIdFromString(response.syncData["simkl"])
        saved[SimklSyncServices.Imdb]?.let { ids["imdb"] = it }
        saved[SimklSyncServices.Tmdb]?.let { ids["tmdb"] = it }
        saved[SimklSyncServices.Simkl]?.let { ids["simkl"] = it }
        response.getImdbId()?.let { ids["imdb"] = it }
        response.getTMDbId()?.let { ids["tmdb"] = it }
        response.getKitsuId()?.let { ids["kitsu"] = it }
        response.getMalId()?.let { ids["mal"] = it }
        response.getAniListId()?.let { ids["anilist"] = it }
        for (key in listOf("imdb", "tmdb", "kitsu", "mal", "anilist")) {
            response.syncData[key]?.takeIf { it.isNotBlank() }?.let { ids.putIfAbsent(key, it) }
        }
        response.syncData["simkl"]?.takeIf { it.all(Char::isDigit) }?.let { ids["simkl"] = it }
        val stremio = APIHolder.allProviders.firstOrNull {
            it.name.equals(response.apiName, true) &&
                it.javaClass.name == "com.hsp1020.StremioC"
        }
        if (response.apiName == "StremioC" && stremio == null) {
            skip("stremioc_provider_missing")
            return null
        }
        val movie = episode.tvType in setOf(TvType.Movie, TvType.AnimeMovie)
        val urlId = Regex("/meta/(?:movie|series)/([^/?]+)[.]json").find(response.url)
            ?.groupValues?.getOrNull(1)?.let(Uri::decode)
        val expectedMinutes = if (movie) (response as? MovieLoadResponse)?.duration
            ?: episode.runTime else episode.runTime
        // CineStream Kitsu titles use season 1 locally, but carry IMDb season mapping.
        val cineKitsu = if (!movie && response.apiName.equals("CineStream", true))
            runCatching {
                JSONObject(episode.data).takeIf { it.optBoolean("isKitsu") }?.let { data ->
                    data.optInt("imdbSeason").takeIf { it > 0 } to
                        data.optInt("imdbEpisode").takeIf { it > 0 }
                }
            }.getOrNull() else null
        lastSkipReason = null
        trace("SNAPSHOT", "provider=" + response.apiName + " movie=" + movie +
            " ids=" + ids.keys + " stremio=" + (stremio != null) +
            " episode=" + episode.episode + " season=" + episode.season +
            " cineKitsu=" + (cineKitsu != null))
        return Item(episode.id, response.url, response.apiName, movie,
            if (movie) null else episode.season, if (movie) null else episode.episode,
            episode.tvType in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA),
            stremio, if (stremio != null) episode.data else null, ids, cineKitsu, urlId,
            expectedMinutes?.takeIf { it > 0 }?.toLong()?.times(60_000L))
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
            val minimum = item.expectedDurationMs?.let { (it + 1L) / 2L } ?: 60_000L
            val passed = actual >= minimum
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
            skip("runtime_not_approved")
            return
        }
        val target = playbackBase() ?: run {
            skip("missing_or_invalid_history_url")
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
        trace("QUEUE", "provider=" + item.api + " event=" + event +
            " position=" + pos + " duration=" + duration)
        val report = Report(item, event, pos, duration, target, played)
        if (queue.trySend(report).isFailure) {

            senderScope.launch { runCatching { queue.send(report) } }
        }
    }

    private fun refreshEpisode(): Boolean {
        val state = model?.state?.generatorState ?: run { skip("refresh_no_state"); return false }
        val key = when (val meta = state.meta) {
            is ResultEpisode -> state.response?.let { it.url + "#" + meta.id }
            is ExtractorUri -> "download:" + meta.parentId + "#" + meta.id
            else -> null
        } ?: run { skip("refresh_no_playable_metadata"); return false }
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
        trace("PLAYER", "attached=" + (next != null) +
            " host=" + fragment?.activity?.javaClass?.simpleName)
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
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) = detach(f)
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
            trace("HOST_KEEP", "playerHost=" + host?.javaClass?.simpleName +
                " ignored=" + activity.javaClass.simpleName)
            return
        }
        if (host !== activity) {
            trace("HOST", "selected=" + activity.javaClass.simpleName)
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
        trace("WATCH", "host=" + fragment.activity?.javaClass?.simpleName)
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
        trace("STREMIO_IDS", "enabled=" + result["_historyEnabled"] +
            " keys=" + result.keys)
        return result
    }

    private suspend fun resolveSimkl(id: String, movie: Boolean): Map<String, String> {
        if (id.toLongOrNull()?.let { it > 0L } != true) return emptyMap()
        val key = (if (movie) "movie:" else "series:") + id
        val now = System.currentTimeMillis()
        simklMappings[key]?.takeIf {
            now - it.first < (if (it.second.isEmpty()) 300_000L else 3_600_000L)
        }?.let { return it.second }
        val ids = try {
            val auth = AccountManager.syncApis.firstOrNull { it.idPrefix == "simkl" }
                ?.authData()
            if (auth == null) {
                trace("SIMKL_ID_LOOKUP", "reason=no_native_simkl_login")
                emptyMap()
            } else {
                val headers = SimklApi.getHeaders(auth.token)
                val clientId = headers["simkl-api-key"]
                if (clientId.isNullOrBlank()) emptyMap() else {
                    val params = mapOf("client_id" to clientId)
                    val search = org.json.JSONArray(app.get("https://api.simkl.com/search/id",
                        params = params + ("simkl" to id), headers = headers, timeout = 15L).text)
                    val item = (0 until search.length()).mapNotNull { search.optJSONObject(it) }
                        .firstOrNull {
                            it.optJSONObject("ids")?.optString("simkl") == id &&
                                when (it.optString("type")) {
                                    "movie" -> movie
                                    "tv" -> !movie
                                    "anime" -> true
                                    else -> false
                                }
                        }
                    val segment = when (item?.optString("type")) {
                        "movie" -> "movies"
                        "tv" -> "tv"
                        "anime" -> "anime"
                        else -> null
                    }
                    if (segment == null) emptyMap() else {
                        val detail = org.json.JSONObject(app.get(
                            "https://api.simkl.com/" + segment + "/" + id,
                            params = params, headers = headers, timeout = 15L).text)
                        val externalIds = detail.optJSONObject("ids")
                        val isMovie = when (segment) {
                            "movies" -> true
                            "tv" -> false
                            else -> detail.optString("anime_type").equals("movie", true)
                        }
                        if (externalIds == null || externalIds.optString("simkl") != id ||
                            isMovie != movie) emptyMap() else buildMap {
                            externalIds.optString("imdb").takeIf { it.matches(Regex("tt[0-9]+")) }
                                ?.let { put("imdb", it) }
                            externalIds.optString("tmdb").takeIf {
                                it.toLongOrNull()?.let { n -> n > 0L } == true
                            }?.let { put("tmdb", it) }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            trace("SIMKL_ID_LOOKUP", "reason=request_failed type=" +
                error.javaClass.simpleName)
            emptyMap()
        }
        trace("SIMKL_ID_LOOKUP", "resolved=" + ids.keys + " found=" + ids.isNotEmpty())
        simklMappings[key] = now to ids
        if (simklMappings.size > 48) simklMappings.remove(simklMappings.keys.first())
        return ids
    }


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
            trace("OFFLINE_CATALOG", "resolved=false candidates=" + candidates.size +
                " historyEnabled=" + candidates.values.count { it.second })
            return episodeJson
        }
        trace("OFFLINE_CATALOG", "resolved=true candidates=" + candidates.size)
        return data.put("addonUrl", candidates.values.first().first).toString()
    }

    /** Resolve offline metadata on the sender thread; never block playback callbacks. */
    private suspend fun resolveDownloaded(item: Item): Item? {
        val file = item.offline ?: return item
        val header = application.getKey<DownloadObjects.DownloadHeaderCached>(
            DOWNLOAD_HEADER_CACHE, file.parentId.toString()
        ) ?: run { trace("SKIP", "reason=download_header_missing"); return null }
        val cacheKey = listOf(header.apiName, header.url, file.parentId, file.id,
            file.season, file.episode).joinToString("|")
        val now = System.currentTimeMillis()
        downloadedMappings[cacheKey]?.takeIf { now - it.first < 3_600_000L }
            ?.let { return it.second }

        val stremio = header.apiName.equals("StremioC", true)
        val cine = header.apiName.equals("CineStream", true)
        val detail = if (stremio || cine) {
            try {
                withTimeoutOrNull(8_000L) {
                    APIHolder.getApiFromNameNull(header.apiName)?.load(header.url)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else null
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
            trace("SKIP", "reason=download_stremioc_mapping_unavailable")
            return null // Never bypass StremioC's per-catalog History Sync toggle.
        }

        val ids = linkedMapOf<String, String>()
        header.url.substringBefore(':').takeIf { it.matches(Regex("tt[0-9]+")) }
            ?.let { ids["imdb"] = it }
        detail?.getImdbId()?.let { ids["imdb"] = it }
        detail?.getTMDbId()?.let { ids["tmdb"] = it }
        detail?.getKitsuId()?.let { ids["kitsu"] = it }
        detail?.getMalId()?.let { ids["mal"] = it }
        detail?.getAniListId()?.let { ids["anilist"] = it }
        detail?.syncData?.get("simkl")?.let { saved ->
            val parsed = readIdFromString(saved)
            parsed[SimklSyncServices.Imdb]?.let { ids.putIfAbsent("imdb", it) }
            parsed[SimklSyncServices.Tmdb]?.let { ids.putIfAbsent("tmdb", it) }
            parsed[SimklSyncServices.Simkl]?.let { ids["simkl"] = it }
            saved.takeIf { it.all(Char::isDigit) }?.let { ids["simkl"] = it }
        }
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
        val cineKitsu = if (cine && extra?.optBoolean("isKitsu") == true)
            extra.optInt("imdbSeason").takeIf { it > 0 } to
                extra.optInt("imdbEpisode").takeIf { it > 0 }
        else null
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
            ids = ids, cineKitsu = cineKitsu, urlId = urlId
        )
        trace("DOWNLOADED_IDS", "provider=" + header.apiName +
            " ids=" + ids.keys + " stremio=" + stremio +
            " cineKitsu=" + (cineKitsu != null))
        downloadedMappings[cacheKey] = now to resolved
        if (downloadedMappings.size > 20)
            downloadedMappings.remove(downloadedMappings.keys.first())
        return resolved
    }

    private suspend fun send(report: Report) {
        if (!ElevateFeaturePrefs.enabled(application, ElevateFeature.HISTORY_SYNC)) return
        val item = report.item.offline?.let { resolveDownloaded(report.item) ?: return }
            ?: report.item
        trace("PREPARE", "provider=" + item.api + " event=" + report.event)
        val overrides = stremioOverrides(item)
        if (item.stremioProvider != null && overrides["_historyEnabled"] != "true") {
            trace("SKIP", "reason=stremioc_catalog_disabled_or_mapping_failed")
            return
        }
        val ids = item.ids.toMutableMap()
        ids.putAll(overrides.filterKeys { !it.startsWith("_") })
        if (ids["imdb"]?.matches(Regex("tt[0-9]+")) != true &&
            ids["tmdb"]?.toLongOrNull()?.let { it > 0L } != true) {
            ids["simkl"]?.let { ids.putAll(resolveSimkl(it, item.movie)) }
        }
        trace("IDS", "provider=" + item.api + " keys=" + ids.keys)
        val imdb = ids["imdb"]?.takeIf { it.matches(Regex("tt[0-9]+")) }
        val tmdb = ids["tmdb"]?.takeIf { it.toLongOrNull()?.let { n -> n > 0L } == true }
        val kitsu = ids["kitsu"]?.takeIf { it.toLongOrNull()?.let { n -> n > 0L } == true }
        val mal = ids["mal"]?.takeIf { it.toLongOrNull()?.let { n -> n > 0L } == true }
        val anime = item.anime || overrides["_anime"] == "true"
        val base = when {
            item.stremioProvider != null && anime && kitsu != null -> "kitsu:" + kitsu
            item.cineKitsu != null && kitsu != null -> "kitsu:" + kitsu
            imdb != null -> imdb
            tmdb != null -> "tmdb:" + tmdb
            kitsu != null -> "kitsu:" + kitsu
            mal != null -> "mal:" + mal
            else -> item.urlId?.takeIf {
                it.matches(Regex("tt[0-9]+|(?:tmdb|kitsu|mal):[0-9]+"))
            } ?: run {
                trace("SKIP", "reason=no_server_supported_id provider=" + item.api)
                return
            }
        }
        val season = if (base.startsWith("kitsu:") || base.startsWith("mal:")) 1
            else overrides["_season"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: item.cineKitsu?.first ?: item.season
        val episode = if (base.startsWith("kitsu:") || base.startsWith("mal:")) item.episode
            else overrides["_episode"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: item.cineKitsu?.second ?: item.episode
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
        trace("SEND", "provider=" + item.api + " event=" + report.event +
            " kind=" + type + " idType=" + base.substringBefore(':').let {
                if (it.startsWith("tt")) "imdb" else it
            })
        val response = app.post(
            report.target + "/playback/" + type + "/" + Uri.encode(videoId) + ".json",
            headers = mapOf("Content-Type" to "application/json"),
            requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()),
            timeout = 15L
        )
        trace("HTTP", "provider=" + item.api + " event=" + report.event +
            " status=" + response.okhttpResponse.code)
        response.okhttpResponse.close()
    }
}
