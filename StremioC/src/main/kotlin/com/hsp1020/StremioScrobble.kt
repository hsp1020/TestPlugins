package com.hsp1020

import android.content.Context
import android.net.Uri
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
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper
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

/** Plugin-only Media3 events; CloudStream owns all persisted playback positions. */
@OptIn(UnstableApi::class)
internal class StremioScrobble(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
    private val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Report>(64)
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var tree: ViewTreeObserver? = null
    private val main = Handler(Looper.getMainLooper())
    private val trackerMappings = linkedMapOf<String, SeasonTrackerIds?>()
    private var player: Media3Player? = null
    private var active: Item? = null
    private var lastEvent: String? = null
    private var lastPosition = 0L
    private var lastDuration = 0L
    private var pendingSeekReport: Runnable? = null
    private var lastSeekReportAtMs = 0L
    private var seekedPastThresholdAt = 0L

    private data class Item(
        val id: Int, val url: String, val api: String,
        val movie: Boolean, val season: Int?, val episode: Int?,
        val anime: Boolean, val source: StremioC.LoadData?,
        val ids: Map<String, String>, val urlId: String?
    ) { val key: String get() = url + "#" + id }

    private data class Report(
        val item: Item, val event: String, val position: Long,
        val duration: Long, val target: String, val played: Boolean? = null
    )

    init {
        senderScope.launch {
            for (report in queue) try { send(report) } catch (_: Exception) { }
        }
    }

    private fun snapshot(): Item? {
        val state = model?.state?.generatorState ?: return null
        val response = state.response ?: return null
        if (response.apiName != "StremioC" && APIHolder.allProviders.none {
                it is StremioC && it.name.equals(response.apiName, true)
            }) return null
        val episode = state.meta as? ResultEpisode ?: return null
        val data = runCatching {
            com.lagradost.cloudstream3.utils.AppUtils.parseJson<StremioC.LoadData>(episode.data)
        }.getOrNull()
        val ids = linkedMapOf<String, String>()
        response.syncData.forEach { (name, value) ->
            if (name in setOf("imdb", "tmdb", "kitsu", "mal", "anilist") && value.isNotBlank())
                ids[name] = value
        }
        data?.imdbId?.let { ids["imdb"] = it }
        data?.tmdbId?.let { ids["tmdb"] = it.toString() }
        data?.kitsuId?.removePrefix("kitsu:")?.let { ids["kitsu"] = it }
        data?.malId?.let { ids["mal"] = it.toString() }
        data?.anilistId?.let { ids["anilist"] = it.toString() }
        val movie = episode.tvType in setOf(TvType.Movie, TvType.AnimeMovie)
        val urlId = Regex("/meta/(?:movie|series)/([^/?]+)[.]json").find(response.url)
            ?.groupValues?.getOrNull(1)?.let(Uri::decode)
        return Item(episode.id, response.url, response.apiName, movie,
            if (movie) null else episode.season, if (movie) null else episode.episode,
            data?.isAnime == true || episode.tvType in setOf(TvType.Anime, TvType.AnimeMovie),
            data, ids, urlId)
    }

    private fun playbackBase(): String? {
        val url = prefs.getString("history_sync_url", null)?.trim()
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


    /** Only state transitions and Seek send reports; no recurring polling. */
    private fun emit(event: String, item: Item, current: Media3Player? = player, played: Boolean? = null, allowRepeat: Boolean = false) {
        if (lastEvent == "stop" ||
            (event == "start" && current?.isPlaying != true) ||
            (event != "start" && lastEvent == null) ||
            (event != "start" && !allowRepeat && lastEvent == event)) return

        val (pos, duration) = playbackPosition(item, current)
        lastPosition = pos
        lastDuration = duration
        lastEvent = event
        if (event == "start" && duration > 0L && pos.toDouble() / duration >= 0.80 &&
            seekedPastThresholdAt == 0L) {
            seekedPastThresholdAt = SystemClock.elapsedRealtime()
        }
        val target = playbackBase() ?: return
        val report = Report(item, event, pos, duration, target, played)
        if (queue.trySend(report).isFailure) {
            // Lifecycle reports must not be lost if earlier requests are slow.
            senderScope.launch { runCatching { queue.send(report) } }
        }
    }

    /** Deserialize only when the actual episode changes. */
    private fun refreshEpisode(): Boolean {
        val state = model?.state?.generatorState ?: return false
        val response = state.response ?: return false
        val episode = state.meta as? ResultEpisode ?: return false
        if (active?.key == response.url + "#" + episode.id) return false

        val next = snapshot()
        active?.let { old ->
            // The old stop must use its stored position, never the new episode's player.
            if (lastEvent != null && lastEvent != "stop") emit("stop", old, null)
        }
        cancelCompletion()
        cancelPendingSeek()
        lastSeekReportAtMs = 0L
        active = next
        lastEvent = null
        lastPosition = 0L
        lastDuration = 0L
        seekedPastThresholdAt = 0L
        if (next != null && player?.isPlaying == true) {
            emit("start", next)
            scheduleCompletion()
        }
        return true
    }

    /** Rebind if CloudStream replaces the Media3 player (for example, mirror change). */
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
        if (!changed && next?.isPlaying == true) {
            active?.let { emit("start", it) }
            scheduleCompletion()
        }
    }

    /**
     * One scheduled threshold check, not a timer that runs every 15 seconds.
     * A seek directly past 80% must first continue playing before it counts.
     */
    private fun scheduleCompletion() {
        main.removeCallbacks(completion)
        val current = player ?: return
        if (lastEvent == "stop" || !current.isPlaying) return
        val (position, duration) = active?.let { playbackPosition(it, current) } ?: return
        if (duration <= 0L) return
        val threshold = (duration * 0.80).toLong()
        val remaining = (threshold - position).coerceAtLeast(0L)
        val speed = current.playbackParameters.speed.toDouble().coerceAtLeast(0.1)
        val timeToThreshold = (remaining / speed).toLong().coerceAtLeast(1000L)
        val seekGrace = if (seekedPastThresholdAt != 0L)
            (15_000L - (SystemClock.elapsedRealtime() - seekedPastThresholdAt)).coerceAtLeast(0L)
            else 0L
        main.postDelayed(completion, maxOf(timeToThreshold, seekGrace))
    }

    private val completion = Runnable {
        val item = active
        val current = player
        if (item != null && current?.isPlaying == true && lastEvent != "stop") {
            val (position, duration) = playbackPosition(item, current)
            if (duration > 0L && position.toDouble() / duration >= 0.80 &&
                (seekedPastThresholdAt == 0L ||
                    SystemClock.elapsedRealtime() - seekedPastThresholdAt >= 15_000L)) {
                emit("stop", item, current, played = true)
            } else scheduleCompletion()
        }
    }

    private fun cancelCompletion() {
        main.removeCallbacks(completion)
    }

    /** Seek-only rate limit: first report now, further seeks at most once per 15 seconds. */
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
                // Read the actual latest position at send time, not the original seek target.
                emit(if (player?.isPlaying == true) "start" else "pause", item, allowRepeat = true)
                lastSeekReportAtMs = SystemClock.elapsedRealtime()
            }
        }.also { main.postDelayed(it, 15_000L - elapsed) }
    }

    private fun cancelPendingSeek() {
        pendingSeekReport?.let(main::removeCallbacks)
        pendingSeekReport = null
    }

    // Layout events are only used to discover a new player, not to parse episode JSON.
    private val layout = ViewTreeObserver.OnGlobalLayoutListener {
        attachPlayer()
        // Cheap key comparison; snapshot JSON is parsed only for a new episode.
        if (player?.isPlaying == true) refreshEpisode()
    }


    // Dynamic proxy avoids default-method ABI differences across Media3 versions.
    private val listener = java.lang.reflect.Proxy.newProxyInstance(
        Media3Player.Listener::class.java.classLoader,
        arrayOf(Media3Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onIsPlayingChanged" -> {
                val changed = refreshEpisode()
                if (args?.getOrNull(0) == true) {
                    // A real resume reports the current position immediately.
                    cancelPendingSeek()
                    lastSeekReportAtMs = 0L
                    if (!changed) active?.let { emit("start", it) }
                    scheduleCompletion()
                } else cancelCompletion()
                null
            }
            "onPlayWhenReadyChanged" -> {
                if (args?.getOrNull(0) == false) {
                    cancelPendingSeek()
                    lastSeekReportAtMs = 0L
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
                        scheduleCompletion() // Runtime may only become available when READY.
                    }
                    Media3Player.STATE_ENDED -> {
                        cancelPendingSeek()
                        lastSeekReportAtMs = 0L
                        cancelCompletion()
                        refreshEpisode()
                        active?.let { emit("stop", it) }
                    }
                }
                null
            }
            "onPlaybackParametersChanged" -> {
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
                        seekedPastThresholdAt = if (duration > 0L && position.toDouble() / duration >= 0.80)
                            SystemClock.elapsedRealtime() else 0L
                        scheduleCompletion()
                        reportSeek(item)
                    }
                }
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "StremioScrobbleListener"
            else -> null
        }
    } as Media3Player.Listener

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
        seekedPastThresholdAt = 0L
    }

    fun stop() {
        detach()
        queue.close() // Drain final events on IO without blocking view destruction.
    }

    private suspend fun send(report: Report) {
        val item = report.item
        val ids = item.ids.toMutableMap()
        val data = item.source
        if (item.anime && data != null &&
            ((item.season ?: 0) > 1 || ids["imdb"].isNullOrBlank() && ids["kitsu"].isNullOrBlank())) {
            val mapped = if (trackerMappings.containsKey(item.key)) trackerMappings[item.key]
                else {
                    val provider = APIHolder.allProviders.filterIsInstance<StremioC>()
                        .firstOrNull { it.name.equals(item.api, true) }
                        ?: APIHolder.allProviders.filterIsInstance<StremioC>().firstOrNull()
                    provider?.playbackTrackerIds(data).also {
                        trackerMappings[item.key] = it
                        if (trackerMappings.size > 12) trackerMappings.remove(trackerMappings.keys.first())
                    }
                }
            mapped?.kitsu?.let { ids["kitsu"] = it.toString() }
            mapped?.mal?.let { ids["mal"] = it.toString() }
            mapped?.aniList?.let { ids["anilist"] = it.toString() }
        }
        val imdb = ids["imdb"]?.takeIf { it.matches(Regex("tt[0-9]+")) }
        val tmdb = ids["tmdb"]?.takeIf { it.matches(Regex("[0-9]+")) }
        val kitsu = ids["kitsu"]?.takeIf { it.matches(Regex("[0-9]+")) }
        val mal = ids["mal"]?.takeIf { it.matches(Regex("[0-9]+")) }
        val base = when {
            item.anime && kitsu != null -> "kitsu:" + kitsu
            imdb != null -> imdb
            tmdb != null -> "tmdb:" + tmdb
            kitsu != null -> "kitsu:" + kitsu
            mal != null -> "mal:" + mal
            else -> item.urlId?.takeIf {
                it.matches(Regex("tt[0-9]+|(?:tmdb|kitsu|mal):[0-9]+"))
            } ?: return
        }
        val episode = item.episode
        val season = if (base.startsWith("kitsu:") || base.startsWith("mal:")) 1
            else data?.imdbSeason?.takeIf { it > 0 } ?: item.season
        val videoId = if (item.movie) base else {
            if (episode == null || episode <= 0 || season == null || season <= 0) return
            if (base.startsWith("kitsu:") || base.startsWith("mal:")) base + ":" + episode
            else base + ":" + season + ":" + (data?.imdbEpisode?.takeIf { it > 0 } ?: episode)
        }
        val body = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("event", report.event)
            .put("metaId", base)
            .put("videoId", videoId)
            .put("positionMs", report.position)
            .put("durationMs", report.duration)
            .put("ids", JSONObject(ids as Map<*, *>))
        if (!item.movie) {
            body.put("season", season)
            body.put("episode", episode)
        }
        if (report.event == "stop")
            body.put("played", report.played ?: (report.duration > 0L &&
                report.position.toDouble() / report.duration >= 0.80))
        val type = if (item.movie) "movie" else "series"
        app.post(
            report.target + "/playback/" + type + "/" + Uri.encode(videoId) + ".json",
            headers = mapOf("Content-Type" to "application/json"),
            requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()),
            timeout = 15L
        )
    }
}
