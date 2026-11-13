package com.hsp1020.elevate

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.json.JSONObject
import java.util.Locale

/** Shared work-level TMDB language and credits cache, independent of providers. */
internal object ElevateTmdbMetadata {
    private const val TTL = 12L * 60 * 60 * 1000
    private data class Entry(
        val at: Long, val detailLoaded: Boolean, val originalLanguage: String?,
        val creditsLocale: String?, val credits: JSONObject?
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = LinkedHashMap<String, Entry>()
    private val flights = mutableMapOf<String, Deferred<Entry?>>()

    private fun language(raw: String?): String? {
        val value = raw?.trim()?.replace('_', '-')?.lowercase(Locale.ROOT) ?: return null
        return value.takeIf {
            it.matches(Regex("[a-z]{2,3}(-[a-z]{2})?")) &&
                it.substringBefore('-') !in setOf("und", "zxx")
        }
    }

    /** A /find response already contains original_language; reuse it. */
    fun rememberOriginalLanguage(id: String, movie: Boolean, value: String?) {
        val number = id.toLongOrNull()?.takeIf { it > 0 } ?: return
        val original = language(value) ?: return
        val key = "${if (movie) "movie" else "tv"}:$number"
        synchronized(cache) {
            val previous = cache[key]
            cache[key] = Entry(System.currentTimeMillis(), previous?.detailLoaded == true,
                original, previous?.creditsLocale, previous?.credits)
            if (cache.size > 128) cache.remove(cache.keys.first())
        }
    }

    suspend fun originalLanguage(
        id: String, movie: Boolean, locale: String, includeCredits: Boolean = false
    ): String? = resolve(id, movie, locale, needsCredits = false, prefetchCredits = includeCredits)
        ?.originalLanguage

    suspend fun credits(id: String, movie: Boolean, locale: String): JSONObject? =
        resolve(id, movie, locale, needsCredits = true, prefetchCredits = false)?.credits

    private suspend fun resolve(
        id: String, movie: Boolean, locale: String,
        needsCredits: Boolean, prefetchCredits: Boolean
    ): Entry? {
        val number = id.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val kind = if (movie) "movie" else "tv"
        val key = "$kind:$number"
        val normalizedLocale = locale.lowercase(Locale.ROOT)
        while (true) {
            val (job, joining) = synchronized(cache) {
                val now = System.currentTimeMillis()
                val previous = cache[key]?.takeIf { now - it.at in 0 until TTL }
                val hasDetails = previous?.detailLoaded == true || previous?.originalLanguage != null
                val hasCredits = previous?.creditsLocale == normalizedLocale
                if (hasDetails && (!needsCredits || hasCredits)) return previous
                val active = flights[key]
                if (active != null) active to true
                else {
                    val fetchDetails = !hasDetails
                    val fetchCredits = (needsCredits || prefetchCredits && fetchDetails) && !hasCredits
                    val task = scope.async(start = CoroutineStart.LAZY) {
                        try {
                            val apiKey = ElevateIdResolver.tmdbApiKey() ?: return@async null
                            val endpoint = "https://api.themoviedb.org/3/$kind/$number" +
                                if (fetchDetails) "" else "/credits"
                            val params = mutableMapOf("api_key" to apiKey, "language" to locale)
                            if (fetchDetails && fetchCredits) params["append_to_response"] = "credits"
                            val response = app.get(endpoint, params = params, timeout = 12L)
                            if (response.code !in 200..299) return@async null
                            val json = JSONObject(response.text)
                            val original = if (fetchDetails) language(json.optString("original_language"))
                                else null
                            val foundCredits = if (fetchCredits) {
                                if (fetchDetails) json.optJSONObject("credits") else json
                            } else null
                            synchronized(cache) {
                                val current = cache[key]?.takeIf {
                                    System.currentTimeMillis() - it.at in 0 until TTL
                                }
                                val entry = Entry(System.currentTimeMillis(),
                                    fetchDetails || current?.detailLoaded == true,
                                    original ?: current?.originalLanguage,
                                    if (fetchCredits) normalizedLocale else current?.creditsLocale,
                                    if (fetchCredits) foundCredits else current?.credits)
                                cache[key] = entry
                                if (cache.size > 128) cache.remove(cache.keys.first())
                                entry
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) { null }
                    }
                    flights[key] = task
                    task.invokeOnCompletion {
                        synchronized(cache) { if (flights[key] === task) flights.remove(key) }
                    }
                    task to false
                }
            }
            job.start()
            val result = job.await()
            if (!joining || result == null) return result
            // A concurrent request may have fetched only the language; fetch missing credits.
        }
    }
}
