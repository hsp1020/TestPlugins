package com.hsp1020.elevate

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Shared AIOStreams title ID mapping; no dependency on the StremioC plugin. */
internal object ElevateAnimeIdResolver {
    internal data class Mapping(
        val kitsu: String?, val mal: String?, val anilist: String?,
        val imdb: String?, val tmdb: String?
    )

    private val hosts = listOf(
        "aiostreams.elfhosted.com", "aiostreams.fortheweak.cloud",
        "aiostreamsfortheweebsstable.midnightignite.me",
        "aio.atbphosting.com", "aiostreams.stremio.ru", "aiostreams.viren070.me"
    )
    private val limiter = Semaphore(3)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flights = mutableMapOf<String, Deferred<Mapping?>>()
    private val cache = object : LinkedHashMap<String, Pair<Long, Mapping?>>(48, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Mapping?>>) =
            size > 96
    }

    suspend fun resolve(source: String, rawId: String): Mapping? {
        val field = when (source) {
            "kitsu" -> "kitsuId"
            "mal" -> "malId"
            "anilist" -> "anilistId"
            else -> return null
        }
        val id = rawId.removePrefix(source + ":").substringBefore(':')
            .toLongOrNull()?.takeIf { it > 0L }?.toString() ?: return null
        val key = field + ":" + id
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.takeIf {
                now - it.first < if (it.second == null) 300_000L else 86_400_000L
            }?.let { return it.second }
        }
        val flight = synchronized(cache) {
            cache[key]?.takeIf {
                System.currentTimeMillis() - it.first in 0 until
                    if (it.second == null) 300_000L else 86_400_000L
            }?.let { return it.second }
            flights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                val found = try {
            withTimeoutOrNull(15_000L) {
                limiter.withPermit {
                    var result: Mapping? = null
                    for (host in hosts) {
                        val url = "https://" + host + "/api/v1/anime?idType=" + field +
                            "&idValue=" + id
                        val root = try {
                            JSONObject(app.get(url, timeout = 5L).text)
                        } catch (e: CancellationException) { throw e }
                          catch (_: Exception) { continue }
                        if (!root.optBoolean("success")) continue
                        val ids = root.optJSONObject("data")?.optJSONObject("mappings")
                            ?: continue
                        // Never map an unrelated anime title based on a search result.
                        if (ids.optString(field) != id) continue
                        fun positive(name: String): String? = ids.optString(name)
                            .toLongOrNull()?.takeIf { it > 0L }?.toString()
                        val kitsu = positive("kitsuId")
                        val mal = positive("malId")
                        val anilist = positive("anilistId")
                        if (kitsu == null && mal == null && anilist == null) continue
                        result = Mapping(
                            kitsu, mal, anilist,
                            ids.optString("imdbId").takeIf { it.matches(Regex("tt[0-9]+")) },
                            positive("themoviedbId")
                        )
                        break
                    }
                    result
                }
            }
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { null }
                synchronized(cache) { cache[key] = System.currentTimeMillis() to found }
                found
            }.also { task ->
                flights[key] = task
                task.invokeOnCompletion {
                    synchronized(cache) { if (flights[key] === task) flights.remove(key) }
                }
            }
        }
        flight.start()
        return flight.await()
    }
}
