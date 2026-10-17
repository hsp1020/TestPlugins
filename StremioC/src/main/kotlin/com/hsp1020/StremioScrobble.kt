package com.hsp1020

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val main = Handler(Looper.getMainLooper())
    private val trackerMappings = linkedMapOf<String, SeasonTrackerIds?>()
    private var player: Media3Player? = null
    private var active: Item? = null
    private var lastEvent: String? = null
    private var lastPosition = 0L
    private var lastDuration = 0L
    private var seekPending = false
    private var seekPosition = 0L
    private var seekAtMs = 0L
    private var lastProgressAtMs = 0L
    private var seekReport: Runnable? = null

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

    private fun progress(item: Item, current: Media3Player?): Pair<Long, Long> {
        val saved = DataStoreHelper.getViewPos(item.id)
        val position = current?.currentPosition?.takeIf { it >= 0L }
            ?: saved?.position ?: lastPosition
        val duration = current?.duration?.takeIf { it > 0L }
            ?: saved?.duration?.takeIf { it > 0L } ?: lastDuration
        return position.coerceAtLeast(0L) to duration.coerceAtLeast(0L)
    }


    /** A single 15-second progress timer, independent of view drawing. */
    private fun emit(event: String, item: Item, current: Media3Player? = player, played: Boolean? = null) {
        if (lastEvent == "stop" ||
            (event == "start" && (current?.isPlaying != true || lastEvent == "start")) ||
            (event != "start" && lastEvent == null) ||
            (event != "progress" && lastEvent == event)) return

        val (pos, duration) = progress(item, current)
        lastPosition = pos
        lastDuration = duration
        if (event != "progress") lastEvent = event
        if (event == "start") lastProgressAtMs = SystemClock.elapsedRealtime()

        val target = playbackBase() ?: return
        val report = Report(item, event, pos, duration, target, played)
        if (queue.trySend(report).isFailure && event != "progress") {
            // A saturated progress queue must never silently lose a final stop.
            senderScope.launch { runCatching { queue.send(report) } }
        }
    }

    private fun refreshEpisode() {
        val state = model?.state?.generatorState ?: return
        val response = state.response ?: return
        val episode = state.meta as? ResultEpisode ?: return
        if (active?.key == response.url + "#" + episode.id) return

        val next = snapshot()
        active?.let { old ->
            // Do not read the next episode's position for the previous stop.
            if (lastEvent != null && lastEvent != "stop") emit("stop", old, null)
        }
        active = next
        lastEvent = null
        lastPosition = 0L
        lastDuration = 0L
        seekPending = false
        lastProgressAtMs = 0L
        seekReport?.let(main::removeCallbacks)
        seekReport = null
        if (next != null && player?.isPlaying == true) emit("start", next)
    }

    private fun attachPlayer() {
        val next = fragment?.playerView?.player
        if (next === player) return
        player?.removeListener(listener)
        player = next
        next?.addListener(listener)
    }

    private fun checkProgress() {
        val item = active ?: return
        val current = player ?: return
        if (!current.isPlaying || lastEvent != "start") return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressAtMs < 15_000L) return
        lastProgressAtMs = now
        val (pos, duration) = progress(item, current)
        if (seekPending) {
            val elapsed = (SystemClock.elapsedRealtime() - seekAtMs).coerceAtLeast(0L)
            // Moving the seekbar past 80% is not continuous playback.
            if (pos > seekPosition && pos - seekPosition <= elapsed * 2 + 10_000L)
                seekPending = false
        }
        if (!seekPending && duration > 0L && pos.toDouble() / duration >= 0.80) {
            // The AIOMetadata /playback progress event itself marks nothing watched.
            // A single completed stop is interpreted by Simkl, MDBList and anime lists.
            emit("stop", item, current, played = true)
        } else {
            emit("progress", item, current)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (fragment == null) return
            attachPlayer()
            refreshEpisode()
            if (player?.isPlaying == true) {
                active?.let { if (lastEvent != "start" && lastEvent != "stop") emit("start", it) }
                checkProgress()
            }
            main.postDelayed(this, if (player == null || active == null) 500L else 15_000L)
        }
    }

    // Dynamic proxy avoids default-method ABI differences across Media3 host versions.
    private val listener = java.lang.reflect.Proxy.newProxyInstance(
        Media3Player.Listener::class.java.classLoader,
        arrayOf(Media3Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onIsPlayingChanged" -> {
                refreshEpisode()
                if (args?.getOrNull(0) == true) active?.let { emit("start", it) }
                null
            }
            "onPlayWhenReadyChanged" -> {
                if (args?.getOrNull(0) == false) {
                    refreshEpisode()
                    active?.let { emit("pause", it) }
                }
                null
            }
            "onPlaybackStateChanged" -> {
                if (args?.getOrNull(0) == Media3Player.STATE_ENDED) {
                    refreshEpisode()
                    active?.let { emit("stop", it) }
                }
                null
            }
            "onPositionDiscontinuity" -> {
                refreshEpisode()
                val reason = args?.lastOrNull() as? Int
                if (reason == Media3Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Media3Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    active?.let { item ->
                        val (position, duration) = progress(item, player)
                        lastPosition = position
                        lastDuration = duration
                        seekPosition = position
                        seekAtMs = SystemClock.elapsedRealtime()
                        seekPending = true
                        seekReport?.let(main::removeCallbacks)
                        seekReport = Runnable { active?.let { emit("progress", it) } }
                            .also { main.postDelayed(it, 750L) }
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
        if (fragment !is GeneratorPlayer || fragment === this.fragment) return
        detach()
        this.fragment = fragment
        model = ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
        main.post(tick)
    }

    fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        main.removeCallbacks(tick)
        seekReport?.let(main::removeCallbacks)
        seekReport = null
        active?.let { if (lastEvent != null && lastEvent != "stop") emit("stop", it) }
        player?.removeListener(listener)
        player = null
        fragment = null
        model = null
        active = null
        lastEvent = null
        lastPosition = 0L
        lastDuration = 0L
        seekPending = false
        lastProgressAtMs = 0L
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
            val provider = APIHolder.allProviders.filterIsInstance<StremioC>()
                .firstOrNull { it.name.equals(item.api, true) }
                ?: APIHolder.allProviders.filterIsInstance<StremioC>().firstOrNull()
            val mapped = if (trackerMappings.containsKey(item.key)) trackerMappings[item.key]
                else provider?.playbackTrackerIds(data).also {
                    trackerMappings[item.key] = it
                    if (trackerMappings.size > 12) trackerMappings.remove(trackerMappings.keys.first())
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
