package com.hsp1020

import android.content.Context
import android.net.Uri
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
    private val queue = Channel<Report>(Channel.UNLIMITED)
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var tree: ViewTreeObserver? = null
    private var player: Media3Player? = null
    private var active: Item? = null
    private var lastEvent: String? = null
    private var lastPosition = 0L
    private var lastDuration = 0L

    private data class Item(
        val id: Int, val url: String, val api: String,
        val movie: Boolean, val season: Int?, val episode: Int?,
        val anime: Boolean, val source: StremioC.LoadData?,
        val ids: Map<String, String>, val urlId: String?
    ) { val key: String get() = url + "#" + id }

    private data class Report(
        val item: Item, val event: String, val position: Long,
        val duration: Long, val target: String
    )

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
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

    private fun emit(event: String, item: Item, current: Media3Player? = player) {
        if (lastEvent == event || event == "start" && current?.isPlaying != true ||
            event != "start" && lastEvent == null) return
        val (pos, duration) = progress(item, current)
        lastPosition = pos
        lastDuration = duration
        lastEvent = event
        val target = playbackBase() ?: return
        queue.trySend(Report(item, event, pos, duration, target))
    }

    private fun refreshEpisode() {
        val next = snapshot()
        if (active?.key == next?.key) return
        active?.let { old ->
            // Never read the *new* episode's player position for the old stop.
            if (lastEvent != null && lastEvent != "stop") emit("stop", old, null)
        }
        active = next
        lastEvent = null
        lastPosition = 0L
        lastDuration = 0L
    }

    // A Proxy avoids host/extension Media3 default-method ABI incompatibilities.
    private val listener = java.lang.reflect.Proxy.newProxyInstance(
        Media3Player.Listener::class.java.classLoader,
        arrayOf(Media3Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onIsPlayingChanged" -> {
                if (args?.getOrNull(0) == true) {
                    refreshEpisode()
                    active?.let { emit("start", it) }
                }
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
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "StremioScrobbleListener"
            else -> null
        }
    } as Media3Player.Listener

    private val draw = ViewTreeObserver.OnPreDrawListener {
        refreshEpisode()
        val next = fragment?.playerView?.player
        if (next !== player) {
            player?.removeListener(listener)
            player = next
            next?.addListener(listener)
        }
        if (next?.isPlaying == true) active?.let { emit("start", it) }
        true
    }

    fun watch(fragment: Fragment) {
        if (fragment !is GeneratorPlayer || fragment === this.fragment) return
        detach()
        this.fragment = fragment
        model = ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
        fragment.view?.viewTreeObserver?.takeIf { it.isAlive }?.let {
            tree = it
            it.addOnPreDrawListener(draw)
        }
    }

    fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        active?.let { if (lastEvent != null && lastEvent != "stop") emit("stop", it) }
        tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(draw)
        player?.removeListener(listener)
        tree = null
        player = null
        fragment = null
        model = null
        active = null
        lastEvent = null
        lastPosition = 0L
        lastDuration = 0L
    }

    fun stop() {
        detach()
        queue.close() // Final stop drains on IO without blocking fragment destruction.
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
            val mapped = provider?.playbackTrackerIds(data)
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
            body.put("played", report.duration > 0L &&
                report.position.toDouble() / report.duration >= 0.80)
        val type = if (item.movie) "movie" else "series"
        app.post(
            report.target + "/playback/" + type + "/" + Uri.encode(videoId) + ".json",
            headers = mapOf("Content-Type" to "application/json"),
            requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()),
            timeout = 15L
        )
    }
}
