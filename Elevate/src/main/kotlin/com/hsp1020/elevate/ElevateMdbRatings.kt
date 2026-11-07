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
import android.view.ViewGroup
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.lagradost.cloudstream3.ui.home.HomeViewModel
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
import kotlinx.coroutines.Job
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
    private var homeBinding: HomeBinding? = null
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
        homeBinding?.onApiKeyChanged()
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

    /** Map Kitsu/MAL/AniList through AIOStreams; preserve Simkl for Simkl-only IDs. */
    private suspend fun resolveAnimeId(source: String, id: String, movie: Boolean, hash: Int): Key? {
        val mapped = ElevateAnimeIdResolver.resolve(source, id) ?: return null
        val imdb = mapped.imdb?.takeIf(imdbPattern::matches)
        val tmdb = mapped.tmdb?.takeIf { it.toLongOrNull()?.let { n -> n > 0L } == true }
        return when {
            imdb != null -> Key("imdb", imdb, movie, hash)
            tmdb != null -> Key("tmdb", tmdb, movie, hash)
            else -> null
        }
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
        private val nativeRating = root.findViewById<TextView>(com.lagradost.cloudstream3.R.id.result_meta_rating)
        private var ratingHidden = false
        private val ratingRefresh = Runnable { syncNativeRating() }
        private val ratingWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (active && !values.isNullOrEmpty()) {
                    nativeRating?.removeCallbacks(ratingRefresh)
                    nativeRating?.post(ratingRefresh)
                }
            }
        }
        private fun syncNativeRating() {
            val rating = nativeRating ?: return
            if (!values.isNullOrEmpty() && sourceText(view.text ?: "").isNotEmpty()) {
                ratingHidden = true
                if (rating.visibility != View.GONE) rating.visibility = View.GONE
            } else if (ratingHidden) {
                val native = (model.page.value as? Resource.Success<ResultData>)?.value?.ratingText
                rating.visibility = when {
                    native == null -> View.INVISIBLE
                    native.asString(rating.context).isBlank() -> View.GONE
                    else -> View.VISIBLE
                }
                ratingHidden = false
            }
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { if (!rendering) render() }
        fun start() {
            view.addTextChangedListener(this)
            nativeRating?.addTextChangedListener(ratingWatcher)
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
            syncNativeRating()
            if (!values.isNullOrEmpty()) {
                nativeRating?.removeCallbacks(ratingRefresh)
                nativeRating?.post(ratingRefresh)
            }
        }
        fun stop() {
            active = false
            generation++
            view.removeCallbacks(tvPopup)
            nativeRating?.removeCallbacks(ratingRefresh)
            nativeRating?.removeTextChangedListener(ratingWatcher)
            values = null
            syncNativeRating()
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

    private fun heroMovie(item: LoadResponse): Boolean =
        item.type in setOf(TvType.Movie, TvType.AnimeMovie) ||
            item.url.contains("/meta/movie/") ||
            runCatching { JSONObject(item.url).optString("type").equals("movie", true) }
                .getOrDefault(false)

    /** Resolve the selected home hero using the same MDB key and anime/Simkl mapping. */
    private fun heroIds(item: LoadResponse, hash: Int): Pair<Key?, List<Pair<String, String>>> {
        val saved = readIdFromString(item.syncData["simkl"])
        val extra = runCatching { JSONObject(item.url) }.getOrNull()
        val meta = metaId.find(item.url)?.groupValues?.getOrNull(1)
            ?.let(android.net.Uri::decode)
        val movie = heroMovie(item)
        val imdb = listOf(item.getImdbId(), saved[SimklSyncServices.Imdb],
            item.syncData["imdb"], extra?.optString("imdbId"), extra?.optString("imdb_id"),
            meta, item.url.substringBefore(':')).firstOrNull {
                it != null && imdbPattern.matches(it)
            }
        val tmdb = listOf(item.getTMDbId(), saved[SimklSyncServices.Tmdb],
            item.syncData["tmdb"], extra?.optString("tmdbId"), extra?.optString("tmdb_id"),
            extra?.optString("moviedb_id"), meta?.takeIf { it.startsWith("tmdb:") },
            item.url.takeIf { it.startsWith("tmdb:") }).firstNotNullOfOrNull { raw ->
                raw?.substringAfterLast(':')?.toLongOrNull()?.takeIf { it > 0 }?.toString()
            }
        val direct = when {
            imdb != null -> Key("imdb", imdb, movie, hash)
            tmdb != null -> Key("tmdb", tmdb, movie, hash)
            else -> null
        }
        if (direct != null) return direct to emptyList()
        val alternatives = linkedMapOf<String, String>()
        fun add(source: String, raw: String?) {
            val id = raw?.removePrefix("$source:")?.substringBefore(':')
                ?.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: return
            alternatives.putIfAbsent(source, id)
        }
        add("mal", item.getMalId())
        add("kitsu", item.getKitsuId())
        add("anilist", item.getAniListId())
        add("mal", saved[SimklSyncServices.Mal])
        add("anilist", saved[SimklSyncServices.AniList])
        for (source in listOf("mal", "kitsu", "anilist", "simkl")) {
            add(source, item.syncData[source])
            add(source, extra?.optString(source))
            add(source, meta?.takeIf { it.startsWith("$source:") })
            add(source, item.url.takeIf { it.startsWith("$source:") })
        }
        add("simkl", saved[SimklSyncServices.Simkl])
        return null to alternatives.map { it.key to it.value }
    }

    /** UI-only home binding: no changes to native LoadResponses or StremioC. */
    private inner class HomeBinding(val fragment: Fragment, val root: View) {
        private val model = ViewModelProvider(fragment.requireActivity())[HomeViewModel::class.java]
        private val master = root.findViewById<RecyclerView>(
            com.lagradost.cloudstream3.R.id.home_master_recycler)
        private var header: View? = null
        private var pager: ViewPager2? = null
        private var description: TextView? = null
        private var nativeScore: TextView? = null
        private var scoreHidden = false
        private var decoratedTv: String? = null
        private var selected: LoadResponse? = null
        private var identity = ""
        private var ratings: List<String>? = null
        private var batch = 0
        private var homeSource = model.apiName.value
        private val prefetchSlots = Semaphore(2)
        private val requests = mutableMapOf<String, Pair<Job, Boolean>>()
        private val results = mutableMapOf<String, List<String>>()
        private var queued = false
        private var shown = false
        private var editing = false
        private var phonePage: View? = null
        private var phoneLabel: TextView? = null
        private var phoneDefault: TextView? = null
        private var phoneDefaultVisibility = View.VISIBLE

        private val refresh = Runnable { queued = false; if (homeBinding === this) update() }
        fun schedule() {
            if (!queued) {
                queued = true
                main.post(refresh)
            }
        }
        private val previewObserver =
            Observer<Resource<Pair<Boolean, List<LoadResponse>>>> { state ->
                when (state) {
                    is Resource.Success -> {
                        bindHeader()
                        preload(state.value.second)
                    }
                    else -> clearRequests()
                }
                schedule()
            }
        private val sourceObserver = Observer<String> { source ->
            if (homeSource != source) {
                homeSource = source
                clearRequests()
            }
            schedule()
        }
        private val selectedPage = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = schedule()
        }
        private val children = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = schedule()
            override fun onChildViewDetachedFromWindow(view: View) {
                if (header === view) {
                    releaseHeader()
                    schedule()
                }
            }
        }
        private val layout = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (header == null) schedule()
            else {
                val visible = pager?.isShown == true
                if (shown != visible) {
                    shown = visible
                    schedule()
                } else if (visible && description == null && !ratings.isNullOrEmpty()) {
                    // A phone hero's native score row can be rebound after MDB arrives.
                    renderPhone()
                }
            }
        }
        private val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { if (!editing) schedule() }
        }
        fun start() {
            master?.addOnChildAttachStateChangeListener(children)
            master?.viewTreeObserver?.takeIf { it.isAlive }?.addOnGlobalLayoutListener(layout)
            model.apiName.observe(fragment.viewLifecycleOwner, sourceObserver)
            model.preview.observe(fragment.viewLifecycleOwner, previewObserver)
            schedule()
        }

        fun onApiKeyChanged() {
            clearRequests()
            (model.preview.value as? Resource.Success<Pair<Boolean, List<LoadResponse>>>)
                ?.value?.second?.let {
                    bindHeader()
                    preload(it)
                }
            schedule()
        }

        private fun ratingId(item: LoadResponse, apiKey: String): String =
            listOf(item.apiName, item.url, item.type, apiKey.hashCode()).joinToString("|")

        private fun clearRequests() {
            batch++
            requests.values.forEach { it.first.cancel() }
            requests.clear()
            results.clear()
            restoreTv()
            clearPhone()
            selected = null
            identity = ""
            ratings = null
        }

        private suspend fun fetchHero(item: LoadResponse, apiKey: String): List<String> {
            val (direct, other) = heroIds(item, apiKey.hashCode())
            val target = direct ?: resolveOther(other, heroMovie(item), apiKey.hashCode())
                ?: return emptyList()
            return get(target, apiKey)
        }

        private fun requestHero(item: LoadResponse, apiKey: String, foreground: Boolean) {
            val key = ratingId(item, apiKey)
            if (results.containsKey(key)) return
            requests[key]?.let { (job, background) ->
                if (!foreground || !background) return
                // Promote a queued prefetch without starting a second MDB network request.
                job.cancel()
                requests.remove(key)
            }
            val ticket = batch
            val job = io.launch {
                val values = try {
                    if (foreground) fetchHero(item, apiKey)
                    else prefetchSlots.withPermit { fetchHero(item, apiKey) }
                } catch (e: CancellationException) { return@launch }
                  catch (_: Exception) { emptyList() }
                val runningJob = coroutineContext[Job]
                main.post {
                    if (homeBinding === this@HomeBinding && batch == ticket &&
                        requests[key]?.first === runningJob) {
                        requests.remove(key)
                        results[key] = values
                        if (identity == key) {
                            ratings = values
                            schedule()
                        }
                    }
                }
            }
            requests[key] = job to !foreground
        }

        private fun preload(items: List<LoadResponse>) {
            val apiKey = preferences.getString("MDB_RATINGS_API_KEY", "").orEmpty().trim()
            if (apiKey.isEmpty() || items.isEmpty()) return
            val current = pager?.currentItem?.takeIf { it in items.indices } ?: 0
            requestHero(items[current], apiKey, foreground = true)
            items.forEachIndexed { index, item ->
                if (index != current) requestHero(item, apiKey, foreground = false)
            }
        }
        private fun bindHeader() {
            val list = master ?: return
            val next = (0 until list.childCount).asSequence().map(list::getChildAt)
                .firstOrNull {
                    it.findViewById<View>(com.lagradost.cloudstream3.R.id.home_header) != null
                }
            val nextPager = next?.findViewById<ViewPager2>(
                com.lagradost.cloudstream3.R.id.home_preview_viewpager)
            if (next === header && nextPager === pager) return
            releaseHeader()
            header = next
            pager = nextPager
            description = next?.findViewById(com.lagradost.cloudstream3.R.id.home_preview_description)
            nativeScore = next?.findViewById(com.lagradost.cloudstream3.R.id.home_preview_score)
            description?.addTextChangedListener(textWatcher)
            pager?.registerOnPageChangeCallback(selectedPage)
            shown = pager?.isShown == true
        }
        private fun restoreTv() {
            description?.let { view ->
                val text = view.text ?: ""
                if (decoratedTv != null && text.toString() == decoratedTv) {
                    editing = true
                    try { view.setText(sourceText(text), TextView.BufferType.SPANNABLE) }
                    finally { editing = false }
                }
                decoratedTv = null
                if (view.text.isNullOrBlank()) view.visibility = View.GONE
            }
            if (scoreHidden) {
                nativeScore?.visibility = if (selected?.score == null) View.GONE else View.VISIBLE
                scoreHidden = false
            }
        }
        private fun clearPhone() {
            (phoneLabel?.parent as? ViewGroup)?.removeView(phoneLabel)
            phoneLabel = null
            phonePage = null
            phoneDefault?.visibility = if (phoneDefault?.text.isNullOrBlank())
                View.GONE else phoneDefaultVisibility
            phoneDefault = null
        }
        private fun releaseHeader() {
            pager?.unregisterOnPageChangeCallback(selectedPage)
            description?.removeTextChangedListener(textWatcher)
            restoreTv()
            clearPhone()
            header = null
            pager = null
            description = null
            nativeScore = null
            shown = false
        }
        private fun renderTv() {
            val view = description ?: return
            val original = view.text ?: ""
            val plain = if (decoratedTv != null && original.toString() == decoratedTv)
                sourceText(original) else original
            val values = ratings
            val next = if (values.isNullOrEmpty()) plain else decorate(
                SpannableStringBuilder("\u2063" + values.joinToString("  ") + "\n").append(plain),
                view.textSize)
            val hasImages = (original as? Spanned)
                ?.getSpans(0, original.length, RatingSpan::class.java)?.isNotEmpty() == true
            if (original.toString() != next.toString() ||
                !values.isNullOrEmpty() && !hasImages) {
                editing = true
                try { view.setText(next, TextView.BufferType.SPANNABLE) }
                finally { editing = false }
            }
            decoratedTv = if (values.isNullOrEmpty()) null else next.toString()
            view.visibility = if (!values.isNullOrEmpty() || plain.isNotEmpty())
                View.VISIBLE else View.GONE
            if (!values.isNullOrEmpty()) {
                nativeScore?.visibility = View.GONE
                scoreHidden = true
            } else if (scoreHidden) {
                nativeScore?.visibility = if (selected?.score == null) View.GONE else View.VISIBLE
                scoreHidden = false
            }
        }
        private fun renderPhone() {
            val viewPager = pager ?: return
            val list = viewPager.getChildAt(0) as? RecyclerView ?: return
            val index = viewPager.currentItem
            val page = (0 until list.childCount).asSequence().map(list::getChildAt)
                .firstOrNull {
                    runCatching { list.getChildViewHolder(it).bindingAdapterPosition }
                        .getOrDefault(RecyclerView.NO_POSITION) == index
                }
            val values = ratings
            if (page == null || values.isNullOrEmpty()) {
                clearPhone()
                return
            }
            val tags = page.findViewById<TextView>(
                com.lagradost.cloudstream3.R.id.home_scroll_preview_tags) ?: return
            val holder = tags.parent as? LinearLayout ?: return
            // Prefer the existing fallback-score position when a provider supplies one.
            val fallback = (0 until holder.childCount).asSequence()
                .map(holder::getChildAt).filterIsInstance<TextView>()
                .firstOrNull { it !== tags && it !== phoneLabel &&
                    it.text?.toString()?.trimStart()?.startsWith("★ ") == true }
            if (phonePage !== page || phoneLabel?.parent !== holder) {
                clearPhone()
                phonePage = page
                phoneLabel = TextView(holder.context).apply {
                    textSize = 14f
                    setTextColor(android.graphics.Color.WHITE)
                    setShadowLayer(4f, 0f, 2f, 0xB0000000.toInt())
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }.also { label ->
                    val anchor = fallback ?: tags
                    holder.addView(label, holder.indexOfChild(anchor).coerceAtLeast(0),
                        LinearLayout.LayoutParams(-1, -2).apply {
                            topMargin = (4 * holder.resources.displayMetrics.density).roundToInt()
                        })
                }
            }
            val label = phoneLabel ?: return
            label.gravity = if (tags.alpha == 0f) Gravity.START else Gravity.CENTER
            val text = decorate("\u2063" + values.joinToString("  "), label.textSize)
            if (label.text.toString() != text.toString() ||
                (label.text as? Spanned)?.getSpans(0, label.text.length, RatingSpan::class.java)
                    .isNullOrEmpty()) label.setText(text, TextView.BufferType.SPANNABLE)

            // A phone hero may already show a fallback star rating in its own row.
            if (fallback !== phoneDefault) {
                phoneDefault?.visibility = if (phoneDefault?.text.isNullOrBlank())
                    View.GONE else phoneDefaultVisibility
                phoneDefault = fallback
                phoneDefaultVisibility = fallback?.visibility ?: View.VISIBLE
            }
            fallback?.visibility = View.GONE
        }
        private fun update() {
            if (fragment.view !== root) return
            bindHeader()
            val viewPager = pager ?: return
            if (!viewPager.isShown) {
                restoreTv()
                clearPhone()
                return
            }
            val current = (model.preview.value as?
                Resource.Success<Pair<Boolean, List<LoadResponse>>>)?.value?.second
                ?.getOrNull(viewPager.currentItem)
            val apiKey = preferences.getString("MDB_RATINGS_API_KEY", "").orEmpty().trim()
            val next = if (current == null || apiKey.isEmpty()) "" else
                ratingId(current, apiKey)
            if (next != identity) {
                selected = current
                restoreTv()
                clearPhone()
                identity = next
                ratings = results[next]
            }
            if (next.isNotEmpty() && current != null)
                requestHero(current, apiKey, foreground = true)
            if (description != null) renderTv() else renderPhone()
        }
        fun stop() {
            clearRequests()
            main.removeCallbacks(refresh)
            model.apiName.removeObserver(sourceObserver)
            model.preview.removeObserver(previewObserver)
            master?.removeOnChildAttachStateChangeListener(children)
            master?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layout)
            releaseHeader()
            selected = null
            ratings = null
            identity = ""
        }
    }

    private fun attachHome(fragment: Fragment, root: View) {
        if (homeBinding?.fragment === fragment && homeBinding?.root === root) {
            homeBinding?.schedule()
        } else {
            homeBinding?.stop()
            homeBinding = HomeBinding(fragment, root).also { it.start() }
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
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, saved: Bundle?) {
            if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment")
                attachHome(f, v)
            attach(f, v)
        }
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment")
                f.view?.let { attachHome(f, it) }
            bindings[f]?.let { it.render() } ?: f.view?.let { attach(f, it) }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            bindings.remove(f)?.stop()
            if (homeBinding?.fragment === f) {
                homeBinding?.stop()
                homeBinding = null
            }
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
        homeBinding?.stop()
        homeBinding = null
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        observePreview(activity)
        fun scan(manager: FragmentManager) {
            for (fragment in manager.fragments) {
                fragment.view?.let {
                    if (fragment.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment")
                        attachHome(fragment, it)
                    attach(fragment, it)
                }
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
        homeBinding?.stop()
        homeBinding = null
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
            homeBinding?.stop()
            homeBinding = null
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
