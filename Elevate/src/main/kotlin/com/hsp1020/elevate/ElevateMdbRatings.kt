package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Observer
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.getKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.getAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.readIdFromString
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.SimklSyncServices
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.ui.result.ResultData
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToInt

/** MDB ratings on native result screens, independent of any metadata provider plugin. */
internal class ElevateMdbRatings(
    private val application: Application,
    private val artwork: Resources?
) : Application.ActivityLifecycleCallbacks {
    private val preferences = application.getSharedPreferences(ElevateFeaturePrefs.FILE, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(3)
    private var host: FragmentActivity? = null
    private var previewModel: ResultViewModel2? = null
    private var previewObserver: Observer<Resource<ResultData>?>? = null
    private val bindings = mutableMapOf<Fragment, Binding>()
    private data class Key(val provider: String, val id: String, val movie: Boolean, val apiKeyHash: Int)
    private data class Cached(val until: Long, val values: List<String>)
    private val cache = object : LinkedHashMap<Key, Cached>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Cached>) = size > 300
    }
    private val inflight = mutableMapOf<Key, Deferred<List<String>>>()
    private val simklMappings = LinkedHashMap<String, Pair<Long, Key?>>()
    private val icons = linkedMapOf(
        "Ⓜ️" to "rating_mal", "⭐" to "rating_imdb",
        "🟢" to "rating_letterboxd", "Ⓣ" to "rating_trakt",
        "🎬" to "rating_tmdb", "🎯" to "rating_metacritic", "👍" to "rating_roger_ebert",
        "🍅\u2060\u2060" to "rating_rotten_tomatoes_certified",
        "🍅\u2060" to "rating_rotten_tomatoes_rotten", "🍅" to "rating_rotten_tomatoes",
        "🍿\u2060\u2060" to "rating_audience_verified_hot",
        "🍿\u2060" to "rating_audience_stale", "🍿" to "rating_audience_score"
    )
    private val drawableIds = mutableMapOf<String, Int>()
    private class RatingSpan(drawable: android.graphics.drawable.Drawable) :
        ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)
    private val number = Regex("^\\s+\\d+(?:\\.\\d+)?(?:\\s|$)")
    private val imdbPattern = Regex("^tt[0-9]+$")
    private val responseField = runCatching {
        ResultViewModel2::class.java.getDeclaredField("currentResponse").apply { isAccessible = true }
    }.getOrNull()
    private val metaId = Regex("/meta/(?:movie|series|tv)/([^/?]+)[.]json")
    private val refreshKey = Runnable {
        bindings.values.toList().forEach { binding ->
            (binding.model.page.value as? Resource.Success<ResultData>)?.value?.let(binding::update)
        }
    }
    private val keyListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "MDB_RATINGS_API_KEY") {
            main.removeCallbacks(refreshKey)
            main.postDelayed(refreshKey, 700L)
        }
    }

    private fun sourceText(text: CharSequence): CharSequence =
        if (text.startsWith('\u2063')) {
            val end = text.indexOf('\n')
            if (end >= 0) text.subSequence(end + 1, text.length) else ""
        } else text

    private fun decorate(text: CharSequence, size: Float): CharSequence {
        val result = SpannableStringBuilder(text)
        val end = result.indexOf('\n').let { if (it < 0) result.length else it }
        val line = result.subSequence(0, end).toString()
        val height = (size * .92f).roundToInt().coerceAtLeast(12)
        for ((symbol, asset) in icons) {
            var start = 1
            while (true) {
                val at = line.indexOf(symbol, start)
                if (at < 0) break
                if (number.containsMatchIn(line.substring(at + symbol.length))) {
                    val id = drawableIds.getOrPut(asset) {
                        artwork?.getIdentifier(asset, "drawable", "com.example") ?: 0
                    }
                    if (id != 0) {
                        val drawable = runCatching { artwork?.getDrawable(id, null)?.mutate() }.getOrNull()
                        if (drawable != null) {
                            val width = (height.toFloat() * drawable.intrinsicWidth.coerceAtLeast(1) /
                                drawable.intrinsicHeight.coerceAtLeast(1)).roundToInt().coerceIn(height / 2, height * 3)
                            drawable.setBounds(0, 0, width, height)
                            result.setSpan(RatingSpan(drawable), at, at + symbol.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
                start = at + symbol.length
            }
        }
        return result
    }

    private data class RatingSource(val symbol: String, val rank: Int)
    private val sources = mapOf(
        "myanimelist" to RatingSource("Ⓜ️", 1), "mal" to RatingSource("Ⓜ️", 1),
        "imdb" to RatingSource("⭐", 2), "letterboxd" to RatingSource("🟢", 3),
        "lb" to RatingSource("🟢", 3), "audiencescore" to RatingSource("🍿", 5),
        "tomatoesaudience" to RatingSource("🍿", 5), "popcorn" to RatingSource("🍿", 5),
        "audience" to RatingSource("🍿", 5), "trakt" to RatingSource("Ⓣ", 6),
        "tmdb" to RatingSource("🎬", 7), "metacritic" to RatingSource("🎯", 8),
        "mc" to RatingSource("🎯", 8), "rottentomatoes" to RatingSource("🍅", 9),
        "tomatoes" to RatingSource("🍅", 9), "rt" to RatingSource("🍅", 9),
        "rogerebert" to RatingSource("👍", 10), "re" to RatingSource("👍", 10)
    )
    private suspend fun query(key: Key, apiKey: String): List<String> {
        val kind = if (key.movie) "movie" else "show"
        val response = try {
            val modern = "https://api.mdblist.com/" + key.provider + "/" + kind + "/" + key.id +
                "/?apikey=" + android.net.Uri.encode(apiKey) + "&append_to_response=keyword"
            JSONObject(app.get(modern, timeout = 15L).text).also {
                if (it.optJSONArray("ratings") == null) error("MDBList missing ratings")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val field = if (key.provider == "tmdb") "tm" else "i"
            JSONObject(app.get("https://mdblist.com/api/?apikey=" + android.net.Uri.encode(apiKey) +
                "&" + field + "=" + key.id, timeout = 15L).text)
        }
        val ratings = response.optJSONArray("ratings") ?: return emptyList()
        val keywords = response.optJSONArray("keywords")
        val marks = (0 until (keywords?.length() ?: 0)).mapNotNull { n ->
            val value = keywords?.opt(n)
            (if (value is JSONObject) value.optString("name") else value as? String)
                ?.substringAfterLast('.')?.lowercase()
        }.toSet()
        val results = mutableListOf<Triple<Int, String, Double>>()
        for (n in 0 until ratings.length()) {
            val rating = ratings.optJSONObject(n) ?: continue
            val source = rating.optString("source", "").lowercase()
            if (source == "metacriticuser") continue
            val value = if (rating.has("value")) rating.optDouble("value") else rating.optDouble("score")
            if (!value.isFinite() || value <= 0) continue
            val scaled = when (source) {
                "rogerebert", "re" -> value * 2.5
                "letterboxd", "lb" -> rating.optDouble("score").takeUnless { it.isNaN() }
                    ?.div(10.0) ?: if (value > 5.0) value else value * 2.0
                "tmdb", "trakt", "metacritic", "mc", "tomatoes", "rottentomatoes",
                "rt", "tomatoesaudience", "audiencescore", "popcorn", "audience" -> value / 10.0
                else -> value
            }
            val score = round(scaled * 10.0) / 10.0
            if (!score.isFinite() || score <= 0) continue
            val known = sources[source] ?: RatingSource("🏷️", 99)
            val symbol = when (known.symbol) {
                "🍅" -> when {
                    "certified-fresh" in marks && scaled >= 7.0 -> "🍅\u2060\u2060"
                    scaled < 6.0 -> "🍅\u2060"
                    else -> "🍅"
                }
                "🍿" -> when {
                    "certified-hot" in marks && scaled >= 8.0 -> "🍿\u2060\u2060"
                    scaled < 6.0 -> "🍿\u2060"
                    else -> "🍿"
                }
                else -> known.symbol
            }
            results.add(Triple(known.rank, symbol, score))
        }
        return results.sortedBy { it.first }.map { (_, glyph, score) ->
            glyph + " " + String.format(Locale.US, "%.1f", score)
        }
    }
    private suspend fun get(key: Key, apiKey: String): List<String> {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[key]?.takeIf { it.until > now }?.let { return it.values } }
        val flight = synchronized(inflight) {
            inflight[key] ?: io.async(start = CoroutineStart.LAZY) {
                val result = try {
                    withTimeoutOrNull(32_000L) { semaphore.withPermit { query(key, apiKey) } }
                        ?: emptyList()
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { emptyList() }
                synchronized(cache) {
                    cache[key] = Cached(System.currentTimeMillis() +
                        if (result.isEmpty()) 120_000L else 12 * 60 * 60 * 1000L, result)
                }
                result
            }.also { started ->
                inflight[key] = started
                started.invokeOnCompletion {
                    synchronized(inflight) { if (inflight[key] === started) inflight.remove(key) }
                }
                started.start()
            }
        }
        return flight.await()
    }

    // Resolve Simkl-only titles with the same authenticated ID route as History Sync.
    private suspend fun resolveSimkl(id: String, movie: Boolean, hash: Int): Key? {
        if (id.toLongOrNull()?.let { it > 0 } != true) return null
        val lookup = listOf(id, movie, hash).joinToString("|")
        val now = System.currentTimeMillis()
        synchronized(simklMappings) {
            simklMappings[lookup]?.takeIf {
                now - it.first < if (it.second == null) 300_000L else 3_600_000L
            }?.let { return it.second }
        }
        val found = try {
            val auth = AccountManager.syncApis.firstOrNull { it.idPrefix == "simkl" }?.authData()
            val headers = auth?.let { SimklApi.getHeaders(it.token) }
            val clientId = headers?.get("simkl-api-key")
            if (headers == null || clientId.isNullOrBlank()) null else {
                val params = mapOf("client_id" to clientId)
                val result = JSONArray(app.get("https://api.simkl.com/search/id",
                    params = params + ("simkl" to id), headers = headers, timeout = 15L).text)
                val match = (0 until result.length()).mapNotNull { result.optJSONObject(it) }
                    .firstOrNull {
                        it.optJSONObject("ids")?.optString("simkl") == id &&
                            when (it.optString("type")) {
                                "movie" -> movie
                                "tv" -> !movie
                                "anime" -> true
                                else -> false
                            }
                    }
                val segment = when (match?.optString("type")) {
                    "movie" -> "movies"
                    "tv" -> "tv"
                    "anime" -> "anime"
                    else -> null
                }
                if (segment == null) null else {
                    val detail = JSONObject(app.get("https://api.simkl.com/" + segment + "/" + id,
                        params = params, headers = headers, timeout = 15L).text)
                    val ids = detail.optJSONObject("ids")
                    val isMovie = if (segment == "movies") true else if (segment == "tv") false
                        else detail.optString("anime_type").equals("movie", true)
                    if (ids == null || ids.optString("simkl") != id || isMovie != movie) null
                    else {
                        val imdb = ids.optString("imdb").takeIf(imdbPattern::matches)
                        val tmdb = ids.optString("tmdb").takeIf {
                            it.toLongOrNull()?.let { number -> number > 0 } == true
                        }
                        when {
                            imdb != null -> Key("imdb", imdb, movie, hash)
                            tmdb != null -> Key("tmdb", tmdb, movie, hash)
                            else -> null
                        }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { null }
        synchronized(simklMappings) {
            simklMappings[lookup] = System.currentTimeMillis() to found
            if (simklMappings.size > 48) simklMappings.remove(simklMappings.keys.first())
        }
        return found
    }

    /** Resolve an anime external ID using Simkl's documented 301 Location mapping. */
    private suspend fun resolveAnimeId(source: String, id: String, movie: Boolean, hash: Int): Key? {
        if (source !in setOf("mal", "kitsu", "anilist") ||
            id.toLongOrNull()?.let { it > 0 } != true) return null
        val lookup = listOf(source, id, movie, hash).joinToString("|")
        val now = System.currentTimeMillis()
        synchronized(simklMappings) {
            simklMappings[lookup]?.takeIf {
                now - it.first < if (it.second == null) 300_000L else 3_600_000L
            }?.let { return it.second }
        }
        val found = try {
            withTimeoutOrNull(28_000L) {
                semaphore.withPermit {
                    val auth = AccountManager.syncApis.firstOrNull { it.idPrefix == "simkl" }?.authData()
                    val headers = auth?.let { SimklApi.getHeaders(it.token) }
                    val clientId = headers?.get("simkl-api-key")
                    if (headers == null || clientId.isNullOrBlank()) null else {
                        val params = mapOf("client_id" to clientId)
                        val redirect = app.get("https://api.simkl.com/redirect",
                            params = params + ("to" to "simkl") + (source to id),
                            headers = headers, allowRedirects = false, timeout = 12L)
                        val location = try {
                            if (redirect.code == 301) redirect.headers["Location"]
                                ?: redirect.headers["location"] else null
                        } finally { redirect.okhttpResponse.close() }
                        val uri = location?.let(android.net.Uri::parse)
                        val parts = uri?.pathSegments.orEmpty()
                        val simklId = parts.getOrNull(1)?.takeIf {
                            it.toLongOrNull()?.let { n -> n > 0 } == true
                        }
                        if (uri?.host != "simkl.com" || parts.firstOrNull() != "anime" ||
                            simklId == null) null else {
                            val detail = JSONObject(app.get("https://api.simkl.com/anime/" + simklId,
                                params = params, headers = headers, timeout = 12L).text)
                            val ids = detail.optJSONObject("ids")
                            if (ids == null || ids.optString("simkl") != simklId ||
                                ids.optString(source).let { it.isNotBlank() && it != id } ||
                                detail.optString("anime_type").equals("movie", true) != movie) null
                            else {
                                val imdb = ids.optString("imdb").takeIf(imdbPattern::matches)
                                val tmdb = ids.optString("tmdb").takeIf {
                                    it.toLongOrNull()?.let { n -> n > 0 } == true
                                }
                                when {
                                    imdb != null -> Key("imdb", imdb, movie, hash)
                                    tmdb != null -> Key("tmdb", tmdb, movie, hash)
                                    else -> null
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { null }
        synchronized(simklMappings) {
            simklMappings[lookup] = System.currentTimeMillis() to found
            if (simklMappings.size > 48) simklMappings.remove(simklMappings.keys.first())
        }
        return found
    }

    /** Keep all native anime ID candidates; never guess by title. */
    private fun otherIds(page: ResultData, model: ResultViewModel2): List<Pair<String, String>> {
        val response = runCatching { responseField?.get(model) as? LoadResponse }.getOrNull()
            ?.takeIf { it.url == page.url }
        val saved = readIdFromString(page.syncData["simkl"])
        val ids = linkedMapOf<String, String>()
        fun add(source: String, value: String?) {
            val number = value?.removePrefix(source + ":")?.toLongOrNull()
            if (number != null && number > 0L) ids.putIfAbsent(source, number.toString())
        }
        add("mal", response?.getMalId())
        add("kitsu", response?.getKitsuId())
        add("anilist", response?.getAniListId())
        add("mal", saved[SimklSyncServices.Mal])
        add("anilist", saved[SimklSyncServices.AniList])
        for (source in listOf("mal", "kitsu", "anilist")) {
            add(source, response?.syncData?.get(source))
            add(source, page.syncData[source])
        }
        val animeId = Regex("^(mal|kitsu|anilist):([0-9]+)(?::.*)?$")
        for (candidate in listOf(page.url, response?.url.orEmpty())) {
            val meta = metaId.find(candidate)?.groupValues?.getOrNull(1)
                ?.let(android.net.Uri::decode)
            for (raw in listOfNotNull(meta, candidate)) animeId.matchEntire(raw)?.let {
                add(it.groupValues[1], it.groupValues[2])
            }
            val json = runCatching { JSONObject(candidate) }.getOrNull()
            for ((source, names) in mapOf(
                "mal" to listOf("malId", "mal_id"),
                "kitsu" to listOf("kitsuId", "kitsu_id"),
                "anilist" to listOf("anilistId", "anilist_id", "aniListId")
            )) for (name in names) add(source, json?.optString(name))
        }
        add("simkl", saved[SimklSyncServices.Simkl])
        add("simkl", page.syncData["simkl"])
        add("simkl", response?.syncData?.get("simkl"))
        return ids.map { it.key to it.value }
    }

    private suspend fun resolveOther(
        candidates: List<Pair<String, String>>, movie: Boolean, hash: Int
    ): Key? {
        for ((source, id) in candidates) {
            val found = if (source == "simkl") resolveSimkl(id, movie, hash)
                else resolveAnimeId(source, id, movie, hash)
            if (found != null) return found
        }
        return null
    }


    private fun ids(page: ResultData, model: ResultViewModel2): Pair<Key?, String?> {
        val apiKey = preferences.getString("MDB_RATINGS_API_KEY", "").orEmpty().trim()
        if (apiKey.isBlank()) return null to null
        val response = runCatching { responseField?.get(model) as? LoadResponse }.getOrNull()
            ?.takeIf { it.url == page.url }
        val saved = readIdFromString(page.syncData["simkl"])
        var imdb = response?.getImdbId()
            ?: saved[SimklSyncServices.Imdb]
            ?: page.syncData["imdb"]
        var tmdb = response?.getTMDbId()
            ?: saved[SimklSyncServices.Tmdb]
            ?: page.syncData["tmdb"]
        val candidates = listOf(page.url, response?.url.orEmpty())
        for (candidate in candidates) {
            if (imdbPattern.matches(candidate.substringBefore(':'))) {
                imdb = candidate.substringBefore(':')
            }
            val meta = metaId.find(candidate)?.groupValues?.getOrNull(1)?.let(android.net.Uri::decode)
            if (imdb == null && meta != null && imdbPattern.matches(meta)) imdb = meta
            if (tmdb == null && meta?.startsWith("tmdb:") == true)
                tmdb = meta.removePrefix("tmdb:")
            val extra = runCatching { JSONObject(candidate) }.getOrNull()
            if (imdb == null) imdb = listOf("imdbId", "imdb_id").firstNotNullOfOrNull {
                extra?.optString(it)?.takeIf { value -> imdbPattern.matches(value) }
            }
            if (tmdb == null) tmdb = listOf("tmdbId", "tmdb_id", "_tmdbId", "moviedb_id")
                .firstNotNullOfOrNull {
                    extra?.optString(it)?.removePrefix("tmdb:")?.takeIf { value ->
                        value.toLongOrNull()?.let { n -> n > 0 } == true
                    }
                }
        }
        val movie = response?.type in setOf(TvType.Movie, TvType.AnimeMovie) ||
            (response == null && page.url.contains("/meta/movie/"))
        val numericTmdb = tmdb?.let {
            when {
                it.toLongOrNull() != null -> it
                it.startsWith("tmdb:") -> it.substringAfterLast(':')
                else -> null
            }
        }
        val key = when {
            imdb?.let(imdbPattern::matches) == true -> Key("imdb", imdb, movie, apiKey.hashCode())
            numericTmdb?.toLongOrNull()?.let { it > 0 } == true ->
                Key("tmdb", numericTmdb, movie, apiKey.hashCode())
            else -> null
        }
        return key to apiKey
    }

    private inner class Binding(val fragment: Fragment, val root: View, val view: TextView) : TextWatcher {
        val model = ViewModelProvider(fragment)[ResultViewModel2::class.java]
        var active = true
        var rendering = false
        var current: Key? = null
        var identity = ""
        var values: List<String>? = null
        var generation = 0
        private var popupBound = false
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { if (!rendering) render() }
        fun start() {
            view.addTextChangedListener(this)
            model.page.observe(fragment.viewLifecycleOwner) { state ->
                val data = (state as? Resource.Success<ResultData>)?.value ?: return@observe
                update(data)
            }
            (model.page.value as? Resource.Success<ResultData>)?.value?.let(::update)
        }
        fun update(data: ResultData) {
            if (!active || fragment.view !== root) return
            val (key, apiKey) = ids(data, model)
            val candidates = if (key == null) otherIds(data, model) else emptyList()
            val movie = key?.movie ?: (data.url.contains("/meta/movie/") ||
                runCatching { responseField?.get(model) as? LoadResponse }.getOrNull()
                    ?.type in setOf(TvType.Movie, TvType.AnimeMovie))
            val selected = key?.toString() ?: if (candidates.isNotEmpty() && !apiKey.isNullOrBlank())
                listOf(candidates.joinToString("|"), movie, apiKey.hashCode()).joinToString(":") else ""
            if (selected == identity) { render(); return }
            identity = selected
            generation++
            current = key
            values = null
            render()
            if (apiKey.isNullOrBlank() || (key == null && candidates.isEmpty())) return
            val requested = generation
            io.launch {
                val target = key ?: resolveOther(candidates, movie, apiKey.hashCode())
                    ?: return@launch
                val found = try { get(target, apiKey) }
                    catch (e: CancellationException) { return@launch }
                main.post {
                    if (!active || fragment.view !== root || bindings[fragment] !== this@Binding ||
                        identity != selected || generation != requested) return@post
                    current = target
                    values = found
                    render()
                }
            }
        }
        // CloudStream can reassign the TV plot click handler while binding its text.
        // Rebind once after layout, not on every draw frame.
        private val tvPopup = Runnable {
            if (active && fragment.view === root && !values.isNullOrEmpty()) {
                view.setOnClickListener {
                    val data = (model.page.value as? Resource.Success<ResultData>)?.value
                        ?: return@setOnClickListener
                    val raw = data.plotText.asString(view.context).html()
                    AlertDialog.Builder(view.context, com.lagradost.cloudstream3.R.style.AlertDialogCustom)
                        .setTitle(data.plotHeaderText.asString(view.context))
                        .setMessage(decorate(
                            SpannableStringBuilder("\u2063" + values!!.joinToString("  ") + "\n").append(raw),
                            view.textSize))
                        .show()
                }
                popupBound = true
            }
        }
        private fun bindTvPopup() {
            if (fragment.javaClass.name !=
                "com.lagradost.cloudstream3.ui.result.ResultFragmentTv") return
            view.removeCallbacks(tvPopup)
            view.post(tvPopup)
        }
        fun render() {
            if (!active || fragment.view !== root || rendering) return
            val old = view.text ?: return
            val plain = sourceText(old)
            val next = if (values.isNullOrEmpty() || plain.isEmpty()) plain else decorate(
                SpannableStringBuilder("\u2063" + values!!.joinToString("  ") + "\n").append(plain),
                view.textSize)
            if (old.toString() != next.toString() || old.startsWith('\u2063') &&
                (old as? Spanned)?.getSpans(0, old.length, RatingSpan::class.java)
                    .isNullOrEmpty() && !values.isNullOrEmpty()) {
                rendering = true
                try { view.setText(next, TextView.BufferType.SPANNABLE) }
                finally { rendering = false }
            }
            if (!values.isNullOrEmpty()) bindTvPopup()
        }
        fun stop() {
            active = false
            generation++
            view.removeCallbacks(tvPopup)
            view.removeTextChangedListener(this)
            val old = view.text
            if (old?.startsWith('\u2063') == true) view.setText(sourceText(old), TextView.BufferType.SPANNABLE)
            if (popupBound && fragment.view === root) {
                view.setOnClickListener {
                    val data = (model.page.value as? Resource.Success<ResultData>)?.value ?: return@setOnClickListener
                    AlertDialog.Builder(view.context, com.lagradost.cloudstream3.R.style.AlertDialogCustom)
                        .setTitle(data.plotHeaderText.asString(view.context))
                        .setMessage(data.plotText.asString(view.context).html())
                        .show()
                }
            }
        }
    }
    private fun attach(fragment: Fragment, root: View) {
        if (bindings.containsKey(fragment) || fragment.javaClass.name !in setOf(
            "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentTv",
            "com.lagradost.cloudstream3.ui.result.ResultTrailerPlayer"
        )) return
        val view = root.findViewById<TextView>(com.lagradost.cloudstream3.R.id.result_description) ?: return
        runCatching { Binding(fragment, root, view).also { bindings[fragment] = it; it.start() } }
    }
    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, saved: Bundle?) =
            attach(f, v)
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            bindings[f]?.let { it.render() } ?: f.view?.let { attach(f, it) }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            bindings.remove(f)?.stop()
        }
    }
    private fun renderPreview(activity: FragmentActivity, data: ResultData) {
        val dialog = runCatching {
            activity.javaClass.getDeclaredField("bottomPreviewPopup").apply {
                isAccessible = true
            }.get(activity) as? Dialog
        }.getOrNull()?.takeIf { it.isShowing } ?: return
        val id = activity.resources.getIdentifier(
            "resultview_preview_description", "id", activity.packageName)
        if (id == 0) return
        val description = dialog.findViewById<TextView>(id) ?: return
        val model = previewModel ?: return
        val (key, apiKey) = ids(data, model)
        if (apiKey.isNullOrBlank()) return
        val candidates = if (key == null) otherIds(data, model) else emptyList()
        if (key == null && candidates.isEmpty()) return
        val movie = data.url.contains("/meta/movie/") ||
            runCatching { responseField?.get(model) as? LoadResponse }.getOrNull()
                ?.type in setOf(TvType.Movie, TvType.AnimeMovie)
        io.launch {
            val target = key ?: resolveOther(candidates, movie, apiKey.hashCode())
                ?: return@launch
            val values = try { get(target, apiKey) }
                catch (e: CancellationException) { return@launch }
            if (values.isEmpty()) return@launch
            main.post {
                if (host !== activity || !dialog.isShowing || previewModel !== model ||
                    (model.page.value as? Resource.Success<ResultData>)?.value?.url != data.url) return@post
                val raw = sourceText(description.text ?: return@post)
                description.setText(decorate(
                    SpannableStringBuilder("\u2063" + values.joinToString("  ") + "\n").append(raw),
                    description.textSize), TextView.BufferType.SPANNABLE)
                description.setOnClickListener {
                    val plot = data.plotText.asString(activity).html()
                    AlertDialog.Builder(activity, com.lagradost.cloudstream3.R.style.AlertDialogCustom)
                        .setMessage(decorate(
                            SpannableStringBuilder("\u2063" + values.joinToString("  ") + "\n").append(plot),
                            description.textSize))
                        .setTitle(data.plotHeaderText.asString(activity)).show()
                }
            }
        }
    }
    private fun detachPreview() {
        previewObserver?.let { observer -> previewModel?.page?.removeObserver(observer) }
        previewObserver = null
        previewModel = null
    }
    private fun observePreview(activity: FragmentActivity) {
        val model = ViewModelProvider(activity)[ResultViewModel2::class.java]
        val observer = Observer<Resource<ResultData>?> { state ->
            (state as? Resource.Success<ResultData>)?.value?.let { data ->
                main.post { if (host === activity) renderPreview(activity, data) }
            }
        }
        model.page.observe(activity, observer)
        previewModel = model
        previewObserver = observer
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        detachPreview()
        bindings.values.toList().forEach { it.stop() }
        bindings.clear()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        observePreview(activity)
        fun scan(manager: FragmentManager) {
            for (fragment in manager.fragments) {
                fragment.view?.let { attach(fragment, it) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }
    fun start() {
        preferences.registerOnSharedPreferenceChangeListener(keyListener)
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }
    fun stop() {
        preferences.unregisterOnSharedPreferenceChangeListener(keyListener)
        main.removeCallbacks(refreshKey)
        application.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        detachPreview()
        host = null
        bindings.values.toList().forEach { it.stop() }
        bindings.clear()
        io.cancel()
        synchronized(cache) { cache.clear() }
        synchronized(simklMappings) { simklMappings.clear() }
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) {
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
            detachPreview()
            host = null
            bindings.values.toList().forEach { it.stop() }
            bindings.clear()
        }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
