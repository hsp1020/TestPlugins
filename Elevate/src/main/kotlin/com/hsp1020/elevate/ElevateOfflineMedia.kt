package com.hsp1020.elevate

import android.app.Application
import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.services.DownloadQueueService
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.videoskip.SkipStamp
import com.lagradost.cloudstream3.utils.videoskip.SkipType
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Small durable cache, independent of provider and downloader implementation. */
internal object ElevateOfflineMedia {
    private const val PREFS = "elevate_offline_playback"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(i: ElevateSkipRepository.Identity) = listOf(
        i.imdb, i.tmdb, i.mal, i.anilist, i.kitsu, i.movie, i.season, i.episode
    ).joinToString("|")
    private fun JSONObject.string(name: String) =
        optString(name).takeUnless { it.isBlank() || it == "null" }

    fun anime(ctx: Context, parentId: Int?): Boolean? {
        val id = parentId ?: return null
        val p = prefs(ctx)
        return if (p.contains("anime:$id")) p.getBoolean("anime:$id", false) else null
    }
    fun putAnime(ctx: Context, parentId: Int, flag: Boolean) {
        prefs(ctx).edit().putBoolean("anime:$parentId", flag).apply()
    }

    fun identity(ctx: Context, parentId: Int?): ElevateSkipRepository.Identity? {
        val raw = prefs(ctx).getString("identity:${parentId ?: return null}", null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            ElevateSkipRepository.Identity(
                j.string("imdb"), j.string("tmdb"), j.string("mal"),
                j.string("anilist"), j.string("kitsu"),
                j.optInt("season").takeIf { j.has("season") && !j.isNull("season") && it >= 0 },
                j.optInt("episode"), j.optBoolean("anime"), j.optBoolean("movie"),
                0L, null
            )
        }.getOrNull()
    }
    fun putIdentity(ctx: Context, parentId: Int, i: ElevateSkipRepository.Identity) {
        val j = JSONObject().apply {
            put("imdb", i.imdb); put("tmdb", i.tmdb); put("mal", i.mal)
            put("anilist", i.anilist); put("kitsu", i.kitsu)
            put("season", i.season); put("episode", i.episode)
            put("anime", i.anime); put("movie", i.movie)
        }
        prefs(ctx).edit().putString("identity:$parentId", j.toString()).apply()
    }

    fun stamps(ctx: Context, i: ElevateSkipRepository.Identity): List<VideoSkipStamp>? {
        val raw = prefs(ctx).getString("stamps:${key(i)}", null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            val sourceDuration = j.getLong("duration")
            // Skip points from a substantially different release/runtime are unsafe.
            if (i.duration <= 0L || kotlin.math.abs(sourceDuration - i.duration) >
                maxOf(30_000L, i.duration / 10L)) return null
            val array = j.getJSONArray("items")
            (0 until array.length()).mapNotNull { n ->
                val stamp = array.getJSONObject(n)
                val start = stamp.getLong("start")
                val end = stamp.getLong("end")
                if (start < 0 || end <= start || end > i.duration) null
                else VideoSkipStamp(
                    SkipStamp(SkipType.valueOf(stamp.getString("type")), start, end),
                    stamp.optBoolean("next"), stamp.optString("source", "Elevate/Cached")
                )
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
    fun putStamps(ctx: Context, i: ElevateSkipRepository.Identity, list: List<VideoSkipStamp>) {
        if (list.isEmpty() || i.duration <= 0L) return
        val items = JSONArray()
        list.forEach { stamp ->
            items.put(JSONObject().apply {
                put("type", stamp.timestamp.type.name)
                put("start", stamp.timestamp.startMs)
                put("end", stamp.timestamp.endMs)
                put("next", stamp.skipToNextEpisode)
                put("source", stamp.source)
            })
        }
        val p = prefs(ctx)
        val editor = p.edit().putString("stamps:${key(i)}",
            JSONObject().put("duration", i.duration).put("items", items).toString())
        // Bound retained episodes; the in-memory API cache remains unchanged.
        val old = p.all.keys.filter { it.startsWith("stamps:") }
        if (old.size >= 240) old.take(old.size - 200).forEach(editor::remove)
        editor.apply()
    }
}

/** Record confirmed metadata while CloudStream queues a native download. */
internal class ElevateDownloadMetadataCapture(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = LinkedHashSet<Int>()
    private fun schedule(item: DownloadObjects.DownloadQueueWrapper) {
        val task = item.downloadItem ?: return
        synchronized(seen) {
            if (!seen.add(task.episode.id)) return
            if (seen.size > 200) seen.remove(seen.first())
        }
        scope.launch { capture(task) }
    }
    fun start() {
        scope.launch(Dispatchers.Unconfined) {
            DownloadQueueManager.queue.collect { queued -> queued.forEach(::schedule) }
        }
        scope.launch {
            DownloadQueueService.downloadInstances.collect { items ->
                items.forEach { schedule(it.downloadQueueWrapper) }
            }
        }
    }
    fun stop() = scope.cancel()

    private suspend fun capture(item: DownloadObjects.DownloadQueueItem) {
        val needAnime = ElevateFeaturePrefs.enabled(application, ElevateFeature.AUDIO_TRACKS)
        val needSkip = ElevateFeaturePrefs.enabled(application, ElevateFeature.SKIP)
        if (!needAnime && !needSkip) return
        val episode = item.episode
        val parent = item.resultId
        val animeTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
        val direct = runCatching {
            val j = JSONObject(episode.data)
            if (j.has("isAnime") && !j.isNull("isAnime")) j.getBoolean("isAnime") else null
        }.getOrNull()
        val knownAnime = direct ?: if (item.resultType in animeTypes ||
            episode.tvType in animeTypes) true else null

        if (needAnime && knownAnime != null)
            ElevateOfflineMedia.putAnime(application, parent, knownAnime)
        if (!needSkip && (knownAnime != null || !needAnime)) return
        val response = try {
            withTimeoutOrNull(8_000L) {
                APIHolder.getApiFromNameNull(item.apiName)?.load(item.resultUrl)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { null } ?: return
        // Duration in ResultEpisode is watched-media milliseconds; runTime is seconds,
        // while LoadResponse.duration is minutes. New downloads often lack a watched duration.
        val duration = episode.duration.takeIf { it in 60_000L..86_400_000L }
            ?: episode.runTime?.toLong()?.times(1000L)
                ?.takeIf { it in 60_000L..86_400_000L }
            ?: response.duration?.toLong()?.times(60_000L)
                ?.takeIf { it in 60_000L..86_400_000L }
            ?: 0L
        val identity = ElevateSkipRepository.identity(response, episode, duration)
        if (needAnime && knownAnime == null && identity.anime) {
            ElevateOfflineMedia.putAnime(application, parent, true)
        }
        if (listOf(identity.imdb, identity.tmdb, identity.mal,
                identity.anilist, identity.kitsu).any { !it.isNullOrBlank() }) {
            ElevateOfflineMedia.putIdentity(application, parent, identity)
            if (needSkip && duration > 0L) ElevateSkipRepository.fetch(identity)
        }
    }
}
