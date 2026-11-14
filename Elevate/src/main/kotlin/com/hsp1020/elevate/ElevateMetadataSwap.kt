package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.result.ResultData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ExtractedTrailerData
import com.lagradost.cloudstream3.ui.result.ResultFragmentPhone
import com.lagradost.cloudstream3.ui.result.ResultFragmentTv
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.settings.getCurrentLocale
import com.lagradost.cloudstream3.utils.txt
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.TimeZone

/** Detail presentation only: playback, episode identity and sync data remain provider-owned. */
internal class ElevateMetadataSwap(private val application: Application) :
    Application.ActivityLifecycleCallbacks {

    private data class EpisodeMeta(
        val name: String?, val description: String?, val poster: String?,
        val date: String?, val runTime: Int?, val rating: Double?
    ) {
        fun withFallback(other: EpisodeMeta?): EpisodeMeta = EpisodeMeta(
            name ?: other?.name, description ?: other?.description,
            poster ?: other?.poster, date ?: other?.date,
            runTime ?: other?.runTime, rating ?: other?.rating
        )
    }

    private data class Metadata(
        val title: String?, val overview: String?, val poster: String?,
        val background: String?, val logo: String?, val genres: List<String>,
        val year: String?, val rating: Double?, val duration: String?,
        val certification: String?, val ongoing: Boolean?,
        val trailers: List<TrailerChoice>, val episodes: Map<Pair<Int, Int>, EpisodeMeta>,
        val actors: List<ActorData>? = null, val people: String? = null,
        val logoForeign: Boolean = false, val source: String = "TMDB",
        val originalTitle: String? = null, val englishTitle: String? = null
    ) {
        fun withFallback(other: Metadata?): Metadata {
            if (other == null) return this
            // StremioC's normal-source rule: the addon's logo wins; the TMDB logo
            // language (including absence) still determines the title fallback.
            return copy(
                title = title ?: other.title, overview = overview ?: other.overview,
                poster = poster ?: other.poster, background = background ?: other.background,
                logo = logo ?: other.logo, genres = genres.ifEmpty { other.genres },
                year = year ?: other.year, rating = rating ?: other.rating,
                duration = duration ?: other.duration,
                certification = certification ?: other.certification,
                ongoing = ongoing ?: other.ongoing,
                trailers = other.trailers.ifEmpty { trailers },
                episodes = episodes, actors = actors ?: other.actors,
                people = people ?: other.people,
                logoForeign = (logo ?: other.logo) != null &&
                    (other.logo == null || other.logoForeign),
                originalTitle = originalTitle ?: other.originalTitle,
                englishTitle = englishTitle ?: other.englishTitle
            )
        }

        fun toPage(original: ResultData): ResultData {
            val sync = HashMap(original.syncData)
            sync["metadata_swap_source"] = source
            sync["logo_expected"] = (!logo.isNullOrBlank()).toString()
            sync["logo_foreign_language"] = logoForeign.toString()
            sync["trailer_choices"] = JSONArray().apply {
                trailers.forEach { item ->
                    put(JSONObject().apply {
                        put("url", item.url)
                        put("title", item.title)
                        put("type", item.type)
                        item.language?.let { put("language", it) }
                    })
                }
            }.toString()
            people?.let { sync["tmdb_people"] = it }
            originalTitle?.let { sync["metadata_swap_original_title"] = it }
            englishTitle?.let { sync["metadata_swap_english_title"] = it }
            return original.copy(
                syncData = sync,
                title = title ?: original.title, // Required identity label, not an artwork fallback.
                titleText = txt(title ?: original.title),
                plotText = txt(overview.orEmpty()),
                posterImage = poster, posterBackgroundImage = background,
                backgroundPosterUrl = background, logoUrl = logo,
                posterHeaders = null, tags = genres,
                yearText = year?.let { txt(it) },
                ratingText = rating?.let {
                    txt(R.string.rating_format, String.format(Locale.US, "%.1f", it))
                },
                durationText = duration?.let { txt(it) },
                contentRatingText = certification?.let { txt(it) },
                onGoingText = ongoing?.let {
                    txt(if (it) R.string.status_ongoing else R.string.status_completed)
                },
                actors = actors, actorsText = null
            )
        }
    }

    private fun JSONObject.string(vararg names: String): String? =
        names.firstNotNullOfOrNull { key ->
            optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
        }

    private fun JSONObject.names(key: String): List<String> =
        optJSONArray(key)?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                when (val item = array.opt(index)) {
                    is JSONObject -> item.string("name")
                    is String -> item.trim().takeIf(String::isNotEmpty)
                    else -> null
                }
            }.distinct()
        } ?: emptyList()

    private fun minutes(raw: String?): Int? {
        val value = raw?.trim() ?: return null
        value.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        val match = Regex("(?i)^(?:(\\d+)\\s*h(?:ours?|rs?)?\\s*)?(?:(\\d+)\\s*m(?:in(?:utes?)?)?\\s*)?$")
            .matchEntire(value) ?: return null
        if (match.groupValues[1].isEmpty() && match.groupValues[2].isEmpty()) return null
        val h = match.groupValues[1].ifEmpty { "0" }.toIntOrNull() ?: return null
        val m = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
        return (h.toLong() * 60 + m).takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun date(raw: String?): Long? {
        val value = raw?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
            ?: return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
                isLenient = false
                timeZone = TimeZone.getDefault()
            }.parse(value)?.time
        }.getOrNull()
    }

    private fun JSONObject.episodeMeta(tmdb: Boolean): EpisodeMeta {
        val still = if (tmdb) string("still_path")?.takeIf { it.startsWith("/") }
            ?.let { "https://image.tmdb.org/t/p/w500$it" } else string("thumbnail", "poster")
        return EpisodeMeta(
            string(if (tmdb) "name" else "title", "name"),
            string("overview", "description"), still,
            string(if (tmdb) "air_date" else "firstAired", "released"),
            if (tmdb) optInt("runtime").takeIf { it > 0 } else minutes(string("runtime")),
            optDouble(if (tmdb) "vote_average" else "rating", Double.NaN)
                .takeIf { it.isFinite() && it > 0 && it <= 10 }
        )
    }

    private fun addonEpisodes(meta: JSONObject): Map<Pair<Int, Int>, EpisodeMeta> {
        val array = meta.optJSONArray("videos") ?: return emptyMap()
        val result = LinkedHashMap<Pair<Int, Int>, EpisodeMeta>()
        for (i in 0 until array.length()) {
            val ep = array.optJSONObject(i) ?: continue
            val season = ep.optInt("season", -1).takeIf { it in 0..99 } ?: continue
            val number = ep.optInt("episode", ep.optInt("number", -1))
                .takeIf { it > 0 } ?: continue
            result[season to number] = ep.episodeMeta(false)
        }
        return result
    }

    private fun addonActors(meta: JSONObject): Pair<List<ActorData>?, String?> {
        val extra = meta.optJSONObject("app_extras") ?: return null to null
        val rows = ArrayList<ActorData>()
        val refs = JSONArray()
        for ((field, role) in listOf("directors" to "Director", "writers" to "Writer",
            "cast" to "")) {
            val people = extra.optJSONArray(field) ?: continue
            for (i in 0 until people.length()) {
                val obj = people.optJSONObject(i) ?: continue
                val name = obj.string("name") ?: continue
                val photo = obj.string("photo")
                rows += ActorData(Actor(name, photo),
                    roleString = if (role.isNotEmpty()) role else obj.string("character"))
                refs.put(JSONObject().put("name", name)
                    .put("id", obj.optInt("tmdbId").takeIf { it > 0 } ?: JSONObject.NULL)
                    .put("crew", role.isNotEmpty()))
            }
        }
        return rows.takeIf { it.isNotEmpty() } to refs.takeIf { it.length() > 0 }?.toString()
    }

    private fun trailerUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return if (value.matches(Regex("[a-zA-Z0-9_-]{11}")))
            "https://www.youtube.com/watch?v=$value"
        else value.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun addonTrailers(meta: JSONObject): List<TrailerChoice> {
        val streams = meta.optJSONArray("trailerStreams")
        val regular = meta.optJSONArray("trailers")
        val list = ArrayList<TrailerChoice>()
        for (array in listOfNotNull(streams, regular)) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = trailerUrl(item.string("ytId", "source", "url")) ?: continue
                val choice = TrailerChoice(url, item.string("title", "type") ?: "Trailer",
                    item.string("type") ?: "Trailer", item.string("language", "lang", "isoLanguage"))
                if (choice.youtubeId != null) list += choice
            }
            if (list.isNotEmpty()) break // Existing StremioC trailerStreams-first rule.
        }
        return list.distinctBy { it.youtubeId }.take(30)
    }

    /** StremioC trailer sorting: best available app-language tier, Trailer before Teaser. */
    private fun tmdbTrailers(json: JSONObject, locale: String): List<TrailerChoice> {
        val array = json.optJSONObject("videos")?.optJSONArray("results") ?: return emptyList()
        val language = locale.substringBefore('-').lowercase(Locale.ROOT)
        val region = locale.substringAfter('-', "").uppercase(Locale.ROOT)
        val ranked = ArrayList<Triple<Int, JSONObject, String>>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (!item.string("site").equals("YouTube", true)) continue
            if (item.string("type") !in listOf("Trailer", "Teaser")) continue
            val url = trailerUrl(item.string("key")) ?: continue
            val choice = TrailerChoice(url, item.string("name", "type") ?: "Trailer",
                item.string("type") ?: "Trailer", item.string("iso_639_1"))
            if (choice.youtubeId == null) continue
            val lang = item.string("iso_639_1")
            val country = item.string("iso_3166_1")
            val rank = when {
                lang.equals(language, true) && region.isNotBlank() &&
                    country.equals(region, true) -> 0
                lang.equals(language, true) -> 1
                lang.equals("en", true) -> 2
                else -> continue
            }
            ranked += Triple(rank, item, url)
        }
        val rank = ranked.minOfOrNull { it.first } ?: return emptyList()
        return ranked.filter { it.first == rank }
            .sortedWith(compareBy<Triple<Int, JSONObject, String>>(
                { if (it.second.string("type") == "Trailer") 0 else 1 },
                { it.second.string("published_at") ?: "9999-12-31" }
            )).map { (_, item, url) ->
                TrailerChoice(url, item.string("name", "type") ?: "Trailer",
                    item.string("type") ?: "Trailer", item.string("iso_639_1"))
            }.distinctBy { it.youtubeId }.take(30)
    }

    private suspend fun tmdbLogo(
        json: JSONObject, id: String, movie: Boolean, locale: String
    ): Pair<String?, Boolean> {
        val preferred = locale.substringBefore('-').lowercase(Locale.ROOT)
        fun select(array: JSONArray?, lang: String): JSONObject? {
            if (array == null) return null
            return (0 until array.length()).mapNotNull(array::optJSONObject)
                .filter { it.string("file_path")?.startsWith("/") == true &&
                    it.string("iso_639_1").equals(lang, true) }
                .maxByOrNull { it.optDouble("vote_average", 0.0) }
        }
        val logos = json.optJSONObject("images")?.optJSONArray("logos")
        val original = json.string("original_language")?.lowercase(Locale.ROOT)
        val selected = select(logos, preferred) ?: select(logos, "en")
            ?: original?.takeIf { it != preferred && it != "en" }?.let { lang ->
                select(ElevateTmdbMetadata.originalLogos(id, movie, lang), lang)
            }
        val path = selected?.string("file_path")
        return path?.let {
            "https://image.tmdb.org/t/p/w500${if (it.endsWith(".svg", true)) it.dropLast(4) + ".png" else it}"
        } to (selected != null && !selected.string("iso_639_1").equals(preferred, true))
    }

    private fun certification(json: JSONObject, movie: Boolean, locale: String): String? {
        val region = locale.substringAfter('-', "US").uppercase(Locale.ROOT)
        val results = json.optJSONObject(if (movie) "release_dates" else "content_ratings")
            ?.optJSONArray("results") ?: return null
        fun from(country: String): String? {
            for (i in 0 until results.length()) {
                val entry = results.optJSONObject(i) ?: continue
                if (!entry.string("iso_3166_1").equals(country, true)) continue
                if (!movie) return entry.string("rating")
                val dates = entry.optJSONArray("release_dates") ?: continue
                for (j in 0 until dates.length()) {
                    dates.optJSONObject(j)?.string("certification")?.let { return it }
                }
            }
            return null
        }
        return from(region) ?: from("US")
    }

    private suspend fun metadataFromTmdb(
        json: JSONObject, id: String, movie: Boolean, locale: String
    ): Metadata? {
        val title = json.string("title", "name") ?: return null
        val overview = json.string("overview")
        val poster = json.string("poster_path")?.takeIf { it.startsWith("/") }
            ?.let { "https://image.tmdb.org/t/p/w780$it" }
        val backdrop = json.string("backdrop_path")?.takeIf { it.startsWith("/") }
            ?.let { "https://image.tmdb.org/t/p/original$it" }
        val (logo, foreign) = tmdbLogo(json, id, movie, locale)
        val year = json.string("release_date", "first_air_date")?.take(4)
            ?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        val rating = json.optDouble("vote_average", Double.NaN).takeIf {
            it.isFinite() && it > 0.0
        }
        val minutes = json.optInt("runtime").takeIf { it > 0 }
            ?: json.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
        val status = json.string("status")?.let { value ->
            when (value.lowercase(Locale.ROOT)) {
                "returning series", "in production" -> true
                "ended", "canceled", "cancelled" -> false
                else -> null
            }
        }
        val originalName = json.string("original_title", "original_name")
        val english = json.optJSONObject("translations")?.optJSONArray("translations")
            ?.let { rows ->
                (0 until rows.length()).mapNotNull(rows::optJSONObject)
                    .firstOrNull {
                        it.string("iso_639_1") == "en" || it.string("iso_3166_1") == "US"
                    }?.optJSONObject("data")?.string("title", "name")
            } ?: if (json.string("original_language") == "en") originalName else null
        return Metadata(title, overview, poster, backdrop, logo, json.names("genres"),
            year, rating, minutes?.let { "$it mins" },
            certification(json, movie, locale), status, tmdbTrailers(json, locale),
            emptyMap(), logoForeign = foreign,
            originalTitle = originalName, englishTitle = english ?: originalName)
    }

    private fun metadataFromAddon(json: JSONObject): Metadata? {
        val meta = json.optJSONObject("meta") ?: json
        val title = meta.string("name", "title")
        if (title == null && meta.string("description", "overview", "poster",
                "background", "backdrop", "logo", "logoUrl", "runtime",
                "imdbRating", "rating") == null &&
            meta.optJSONArray("videos") == null &&
            meta.optJSONObject("_providerArt")?.string("logo") == null) return null
        val year = meta.string("year", "releaseInfo", "released")?.take(4)
            ?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        val rating = meta.string("imdbRating", "rating")?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 && it > 0 }
        val extra = meta.optJSONObject("app_extras")
        val locale = getCurrentLocale(application).replace('_', '-')
            .substringAfter('-', "US").uppercase(Locale.ROOT)
        val age = extra?.string("certificationLocal")
            ?: extra?.optJSONObject("certifications")?.string(locale, "US")
            ?: meta.string("contentRating", "certification")
        val status = meta.string("status")?.let { value ->
            when (value.lowercase(Locale.ROOT)) {
                "returning series", "in production" -> true
                "ended", "canceled", "cancelled" -> false
                else -> null
            }
        }
        val (actors, people) = addonActors(meta)
        return Metadata(title, meta.string("description", "overview"),
            meta.string("poster"), meta.string("background", "backdrop"),
            (meta.string("logo", "logoUrl")
                ?: meta.optJSONObject("_providerArt")?.string("logo")),
            meta.names("genres").ifEmpty { meta.names("genre") },
            year, rating, meta.string("runtime"), age, status,
            addonTrailers(meta), addonEpisodes(meta), actors, people, source = "AIO",
            originalTitle = meta.string("original_title", "originalTitle"),
            englishTitle = meta.string("english_title", "englishTitle"))
    }

    private val prefs = application.getSharedPreferences(ElevateFeaturePrefs.FILE, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val screens = mutableMapOf<Fragment, Screen>()
    private var host: FragmentActivity? = null
    private val responseField = runCatching {
        ResultViewModel2::class.java.getDeclaredField("currentResponse").apply { isAccessible = true }
    }.getOrNull()
    private val trailerField = runCatching {
        ResultViewModel2::class.java.getDeclaredField("_trailers").apply { isAccessible = true }
    }.getOrNull()
    private val episodeField = runCatching {
        ResultViewModel2::class.java.getDeclaredField("_episodes").apply { isAccessible = true }
    }.getOrNull()
    private val pageField = runCatching {
        ResultViewModel2::class.java.getDeclaredField("_page").apply { isAccessible = true }
    }.getOrNull()

    private fun nativePage(model: ResultViewModel2, page: ResultData) {
        @Suppress("UNCHECKED_CAST")
        val live = pageField?.get(model) as? MutableLiveData<Resource<ResultData>?> ?: return
        live.value = Resource.Success(page)
    }


    /** Uses the existing Elevate resolver; no StremioC class or preference dependency. */
    private suspend fun tmdbId(ids: ElevateMediaIdentity.Ids, movie: Boolean): String? {
        ids.tmdb?.let { return it }
        ids.imdb?.let { ElevateIdResolver.tmdbForImdb(it, movie) }?.let { return it }
        ids.simkl?.let { ElevateMediaIdentity.simklMapping(ids, movie)?.tmdb }?.let { return it }
        for ((source, value) in listOfNotNull(
            ids.mal?.let { "mal" to it },
            ids.anilist?.let { "anilist" to it },
            ids.kitsu?.let { "kitsu" to it })) {
            val mapped = ElevateAnimeIdResolver.resolve(source, value) ?: continue
            (mapped.tmdb ?: mapped.imdb?.let {
                ElevateIdResolver.tmdbForImdb(it, movie)
            })?.let { return it }
        }
        return null
    }

    private fun tmdbActors(credits: JSONObject?): Pair<List<ActorData>?, String?> {
        if (credits == null) return null to null
        val actors = ArrayList<ActorData>()
        val people = JSONArray()
        for ((field, role) in listOf("crew" to "crew", "cast" to "cast")) {
            val array = credits.optJSONArray(field) ?: continue
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val job = item.string("job")
                if (role == "crew" && job !in listOf("Director", "Writer")) continue
                val name = item.string("name") ?: continue
                val image = item.string("profile_path")?.takeIf { it.startsWith("/") }
                    ?.let { "https://image.tmdb.org/t/p/w185$it" }
                actors += ActorData(Actor(name, image),
                    roleString = if (role == "crew") job else item.string("character"))
                people.put(JSONObject().put("name", name)
                    .put("id", item.optInt("id").takeIf { it > 0 } ?: JSONObject.NULL)
                    .put("crew", role == "crew"))
            }
        }
        return actors.takeIf { it.isNotEmpty() } to
            people.takeIf { it.length() > 0 }?.toString()
    }

    private inner class Screen(private val fragment: Fragment) {
        private val model = ViewModelProvider(fragment)[ResultViewModel2::class.java]
        private var original: ResultData? = null
        private var swapped: ResultData? = null
        private var originalEpisodes: List<ResultEpisode>? = null
        private var swappedEpisodes: List<ResultEpisode>? = null
        private val suppressedTrailers = emptyList<ExtractedTrailerData>()
        private var nativeTrailerBackup: List<ExtractedTrailerData>? = null
        private var metadata: Metadata? = null
        private var detailId: String? = null
        private var detailLocale: String = ""
        private var job: Job? = null
        private var episodeJob: Job? = null
        private var serial = 0
        private val observer = Observer<Resource<ResultData>?> { result ->
            val page = (result as? Resource.Success<ResultData>)?.value ?: return@Observer
            if (page !== swapped) update(page)
        }
        private val trailerObserver = Observer<List<ExtractedTrailerData>> { list ->
            if (list === suppressedTrailers) return@Observer
            nativeTrailerBackup = list
            if (metadata != null && swapped != null) suppressNativeTrailers()
        }
        private val episodeObserver = Observer<Resource<List<ResultEpisode>>?> { state ->
            val list = (state as? Resource.Success<List<ResultEpisode>>)?.value
                ?: return@Observer
            if (list === swappedEpisodes) return@Observer
            originalEpisodes = list
            updateEpisodes()
        }

        @Suppress("UNCHECKED_CAST")
        private fun nativeEpisodes(items: List<ResultEpisode>) {
            val live = episodeField?.get(model) as?
                MutableLiveData<Resource<List<ResultEpisode>>?> ?: return
            live.value = Resource.Success(items)
        }

        @Suppress("UNCHECKED_CAST")
        private fun nativeTrailers(items: List<ExtractedTrailerData>) {
            val live = trailerField?.get(model) as? MutableLiveData<List<ExtractedTrailerData>>
                ?: return
            live.value = items
        }

        private fun suppressNativeTrailers() {
            if (model.trailers.value !== suppressedTrailers)
                nativeTrailers(suppressedTrailers)
        }

        private fun restoreNativeTrailers() {
            val backup = nativeTrailerBackup
            if (model.trailers.value === suppressedTrailers && backup != null)
                nativeTrailers(backup)
        }

        fun start() {
            model.page.observe(fragment.viewLifecycleOwner, observer)
            model.episodes.observe(fragment.viewLifecycleOwner, episodeObserver)
            model.trailers.observe(fragment.viewLifecycleOwner, trailerObserver)
            (model.page.value as? Resource.Success<ResultData>)?.value?.let(::update)
            (model.episodes.value as? Resource.Success<List<ResultEpisode>>)
                ?.value?.let { originalEpisodes = it; updateEpisodes() }
        }

        private fun update(page: ResultData) {
            if (original?.url != page.url) {
                originalEpisodes = null
                swappedEpisodes = null
            }
            original = page
            swapped = null
            metadata = null
            detailId = null
            job?.cancel()
            episodeJob?.cancel()
            val generation = ++serial
            if (!prefs.getBoolean(ElevateFeature.METADATA_SWAP.name, false) ||
                page.syncData["metadata_swap_allowed"] == "false") return
            val provider = prefs.getString(ElevateFeaturePrefs.METADATA_SWAP_PROVIDER, "TMDB")
            val manifest = prefs.getString(ElevateFeaturePrefs.METADATA_SWAP_ADDON_URL, "").orEmpty()
            if (provider == "AIO" && manifest.isBlank()) return
            job = scope.launch {
                val response = runCatching { responseField?.get(model) as? LoadResponse }
                    .getOrNull()?.takeIf { it.url == page.url }
                val ids = ElevateMediaIdentity.collect(
                    response, extraSync = page.syncData, urls = listOf(page.url))
                val movie = response?.type in setOf(TvType.Movie, TvType.AnimeMovie) ||
                    runCatching { JSONObject(page.url).optString("type") == "movie" }.getOrDefault(false)
                val locale = getCurrentLocale(application).replace('_', '-')
                val resolved = try {
                    withContext(Dispatchers.IO) {
                        val aio = if (provider == "AIO")
                            Addon.load(manifest, movie, ids)?.let { metadataFromAddon(it) }
                        else null
                        val id = tmdbId(ids, movie)
                        val tmdb = id?.let { key ->
                            ElevateTmdbMetadata.details(key, movie, locale,
                                includeCredits = true)?.let { json ->
                                metadataFromTmdb(json, key, movie, locale).let { details ->
                                    if (details == null) null else {
                                        val (actors, people) = tmdbActors(
                                            ElevateTmdbMetadata.credits(key, movie, locale))
                                        details.copy(actors = actors, people = people)
                                    }
                                }
                            }
                        }
                        (if (provider == "AIO") aio?.withFallback(tmdb) ?: tmdb
                            else tmdb) to id
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null to null }
                val current = resolved.first ?: return@launch
                if (generation != serial || original !== page ||
                    !fragment.isAdded || fragment.view == null ||
                    !prefs.getBoolean(ElevateFeature.METADATA_SWAP.name, false) ||
                    prefs.getString(ElevateFeaturePrefs.METADATA_SWAP_PROVIDER, "TMDB") != provider ||
                    (provider == "AIO" &&
                        prefs.getString(ElevateFeaturePrefs.METADATA_SWAP_ADDON_URL, "") != manifest) ||
                    (model.page.value as? Resource.Success<ResultData>)?.value !== page) return@launch
                metadata = current
                detailId = resolved.second
                detailLocale = locale
                swapped = current.toPage(page)
                swapped?.let { nativePage(model, it) }
                suppressNativeTrailers()
                updateEpisodes()
            }
        }

        private fun updateEpisodes() {
            val source = metadata ?: return
            val base = originalEpisodes ?: return
            val generation = serial
            episodeJob?.cancel()
            episodeJob = scope.launch {
                val tmdbEpisodes = withContext(Dispatchers.IO) {
                    val id = detailId
                    if (id == null) emptyMap() else {
                        val result = LinkedHashMap<Pair<Int, Int>, EpisodeMeta>()
                        // Match only true season/episode numbers; never assume a remapped
                        // display season is the same TMDB season.
                        val activeSeason = model.selectedSeasonIndex.value
                            ?.let { index -> model.seasonSelections.value?.getOrNull(index)?.second }
                        val seasons = base.mapNotNull { episode ->
                            episode.seasonIndex?.takeIf {
                                it == episode.season && it in 0..99 && episode.episode > 0 &&
                                    (activeSeason == null || it == activeSeason)
                            }
                        }.distinct()
                        for (season in seasons) {
                            if (source.source == "AIO" && base.filter {
                                    it.seasonIndex == season && it.season == season &&
                                        it.episode > 0
                                }.all { episode ->
                                    val info = source.episodes[season to episode.episode]
                                    info != null && info.name != null &&
                                        info.description != null && info.poster != null &&
                                        info.date != null && info.runTime != null &&
                                        info.rating != null
                                }) continue
                            val response = ElevateTmdbMetadata.season(id, season, detailLocale)
                                ?: continue
                            val values = response.optJSONArray("episodes") ?: continue
                            for (i in 0 until values.length()) {
                                val ep = values.optJSONObject(i) ?: continue
                                val number = ep.optInt("episode_number").takeIf { it > 0 } ?: continue
                                result[season to number] = ep.episodeMeta(true)
                            }
                        }
                        result
                    }
                }
                if (generation != serial || metadata !== source ||
                    originalEpisodes !== base || !fragment.isAdded) return@launch
                val result = base.map { episode ->
                    val season = episode.seasonIndex?.takeIf {
                        it == episode.season && it in 0..99 && episode.episode > 0
                    }
                    val key = season?.let { it to episode.episode }
                    val addon = key?.let { source.episodes[it] }
                    val tmdb = key?.let { tmdbEpisodes[it] }
                    val info = if (source.source == "AIO") addon?.withFallback(tmdb) ?: tmdb
                        else tmdb
                    // Clear old provider display fields even when TMDB has no match.
                    // .copy preserves id, data, playback position, season and episode.
                    episode.copy(
                        name = info?.name, description = info?.description,
                        poster = info?.poster, score = info?.rating?.let { Score.from10(it) },
                        airDate = date(info?.date), runTime = info?.runTime
                    )
                }
                swappedEpisodes = result
                nativeEpisodes(result)
            }
        }

        fun refresh() {
            val current = original ?: return
            job?.cancel()
            episodeJob?.cancel()
            serial++
            metadata = null
            detailId = null
            restoreNativeTrailers()
            val visible = (model.page.value as? Resource.Success<ResultData>)?.value
            if (visible === swapped) nativePage(model, current)
            else update(current)
            val items = originalEpisodes
            if (items != null && (model.episodes.value as?
                    Resource.Success<List<ResultEpisode>>)?.value === swappedEpisodes) {
                swappedEpisodes = null
                nativeEpisodes(items)
            }
        }

        fun stop() {
            job?.cancel()
            episodeJob?.cancel()
            serial++
            model.page.removeObserver(observer)
            model.episodes.removeObserver(episodeObserver)
            model.trailers.removeObserver(trailerObserver)
            restoreNativeTrailers()
            if ((model.episodes.value as? Resource.Success<List<ResultEpisode>>)
                    ?.value === swappedEpisodes)
                originalEpisodes?.let(::nativeEpisodes)
            val visible = (model.page.value as? Resource.Success<ResultData>)?.value
            if (visible === swapped) original?.let { nativePage(model, it) }
            swapped = null
            original = null
            originalEpisodes = null
            swappedEpisodes = null
            metadata = null
        }
    }

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment,
            view: android.view.View, savedInstanceState: Bundle?) {
            if (fragment !is ResultFragmentPhone && fragment !is ResultFragmentTv) return
            if (screens.containsKey(fragment)) return
            screens[fragment] = Screen(fragment).also { it.start() }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            screens.remove(fragment)?.stop()
        }
    }

    private fun attach(activity: FragmentActivity) {
        if (host === activity) return
        detach()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { fragment ->
                fragment.view?.let { callbacks.onFragmentViewCreated(manager, fragment, it, null) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun detach() {
        screens.values.toList().forEach { it.stop() }
        screens.clear()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        host = null
    }

    fun refresh() = screens.values.toList().forEach { it.refresh() }
    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        (com.lagradost.cloudstream3.CommonActivity.activity as? FragmentActivity)?.let(::attach)
    }
    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        detach()
        scope.cancel()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) {
        (activity as? FragmentActivity)?.let(::attach)
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (activity === host) detach()
    }

    /** Stremio manifest/meta contract, using the configured addon, not the StremioC provider. */
    private object Addon {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private data class Cached(val at: Long, val json: JSONObject?)
        private val manifests = LinkedHashMap<String, Cached>()
        private val metadata = LinkedHashMap<String, Cached>()
        private val manifestFlights = mutableMapOf<String, Deferred<JSONObject?>>()
        private val metaFlights = mutableMapOf<String, Deferred<JSONObject?>>()

        private fun manifestUrl(raw: String): Uri? {
            val url = raw.trim().replace("stremio://", "https://")
            val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrBlank() ||
                parsed.authority.orEmpty().contains('@')) return null
            val path = parsed.encodedPath.orEmpty().trimEnd('/')
            return parsed.buildUpon().encodedPath(
                if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
            ).fragment(null).build()
        }

        private suspend fun shared(key: String, cache: LinkedHashMap<String, Cached>,
            flights: MutableMap<String, Deferred<JSONObject?>>, ttl: Long,
            request: suspend () -> JSONObject?): JSONObject? {
            val now = System.currentTimeMillis()
            val job = synchronized(cache) {
                cache[key]?.takeIf { now - it.at in 0 until ttl }?.let { return it.json }
                flights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                    val json = request()
                    synchronized(cache) {
                        cache[key] = Cached(System.currentTimeMillis(), json)
                        if (cache.size > 100) cache.remove(cache.keys.first())
                    }
                    json
                }.also { active ->
                    flights[key] = active
                    active.invokeOnCompletion {
                        synchronized(cache) { if (flights[key] === active) flights.remove(key) }
                    }
                }
            }
            job.start()
            return job.await()
        }

        private suspend fun request(url: String): JSONObject? = try {
            val result = app.get(url, timeout = 12L)
            if (result.code !in 200..299) null else {
                val raw = JSONObject(result.text)
                if (!url.contains("/meta/")) raw else {
                    // A series can carry hundreds of episodes; cache only display metadata.
                    val data = raw.optJSONObject("meta") ?: raw
                    val slim = JSONObject()
                    for (field in listOf("name", "title", "description", "overview", "poster",
                        "background", "backdrop", "logo", "logoUrl", "genres", "genre",
                        "year", "releaseInfo", "released", "imdbRating", "rating",
                        "runtime", "status", "contentRating", "certification",
                        "app_extras", "trailers", "trailerStreams", "_providerArt",
                        "original_title", "originalTitle", "english_title", "englishTitle")) {
                        if (data.has(field)) slim.put(field, data.get(field))
                    }
                    // Preserve only episode display metadata, never stream data/IDs.
                    data.optJSONArray("videos")?.let { videos ->
                        slim.put("videos", JSONArray().apply {
                            for (i in 0 until videos.length()) {
                                val video = videos.optJSONObject(i) ?: continue
                                put(JSONObject().apply {
                                    for (field in listOf("season", "episode", "number",
                                        "title", "name", "overview", "description",
                                        "thumbnail", "poster", "released", "firstAired",
                                        "runtime", "rating")) {
                                        if (video.has(field)) put(field, video.get(field))
                                    }
                                })
                            }
                        })
                    }
                    JSONObject().put("meta", slim)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { null }

        suspend fun load(rawManifest: String, movie: Boolean, ids: ElevateMediaIdentity.Ids): JSONObject? {
            val manifest = manifestUrl(rawManifest) ?: return null
            val uri = manifest.toString()
            val definition = shared(uri, manifests, manifestFlights, 3_600_000L) {
                request(uri)
            } ?: return null
            val resources = definition.optJSONArray("resources") ?: return null
            val supportsMeta = (0 until resources.length()).any {
                when (val item = resources.opt(it)) {
                    is String -> item == "meta"
                    is JSONObject -> item.optString("name") == "meta"
                    else -> false
                }
            }
            if (!supportsMeta) return null
            val allowedTypes = definition.optJSONArray("types")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            val candidates = if (movie) listOf("movie") else
                if (ids.animeIds) listOf("anime", "series", "tv") else listOf("series", "tv", "anime")
            val type = candidates.firstOrNull { it in allowedTypes } ?: return null
            val prefixes = definition.optJSONArray("idPrefixes")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            val values = listOfNotNull(ids.original, ids.imdb, ids.tmdb?.let { "tmdb:$it" },
                ids.mal?.let { "mal:$it" }, ids.anilist?.let { "anilist:$it" },
                ids.kitsu?.let { "kitsu:$it" }).distinct().filter { id ->
                prefixes.isEmpty() || prefixes.any { id.startsWith(it, ignoreCase = true) }
            }
            for (id in values.take(4)) {
                val url = manifest.buildUpon().encodedPath(
                    manifest.encodedPath.orEmpty().removeSuffix("/manifest.json") +
                        "/meta/$type/${Uri.encode(id)}.json"
                ).build().toString()
                val result = shared(url, metadata, metaFlights, 43_200_000L) {
                    request(url)
                }
                if (result?.optJSONObject("meta") != null) return result
            }
            return null
        }
    }
}
