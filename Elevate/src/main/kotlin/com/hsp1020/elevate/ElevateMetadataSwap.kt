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
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.TvSeriesLoadResponse
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.TimeZone
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import java.lang.reflect.InvocationTargetException

private fun metadataSwapTrace(message: String) {
    android.util.Log.d("MetadataSwapTrace", message)
}

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

    private data class AioVideo(
        val season: Int?, val number: Int?,
        val meta: EpisodeMeta, val id: String?, val imdbId: String?,
        val imdbSeason: Int?, val imdbEpisode: Int?, val streams: JSONArray?
    )

    private data class Metadata(
        val title: String?, val overview: String?, val poster: String?,
        val background: String?, val logo: String?, val genres: List<String>,
        val year: String?, val rating: Double?, val duration: String?,
        val certification: String?, val ongoing: Boolean?,
        val trailers: List<TrailerChoice>, val episodes: Map<Pair<Int, Int>, EpisodeMeta>,
        val actors: List<ActorData>? = null, val people: String? = null,
        val logoForeign: Boolean = false, val source: String = "TMDB",
        val originalTitle: String? = null, val englishTitle: String? = null,
        val videos: List<AioVideo> = emptyList()
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

        fun toPage(original: ResultData, movie: Boolean, includeActors: Boolean): ResultData {
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
                durationText = if (movie) duration?.let { txt(it) } else null,
                contentRatingText = certification?.let { txt(it) },
                onGoingText = ongoing?.let {
                    txt(if (it) R.string.status_ongoing else R.string.status_completed)
                },
                actors = if (includeActors) actors else original.actors,
                actorsText = if (includeActors) null else original.actorsText
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

    private fun addonVideos(meta: JSONObject): List<AioVideo> {
        val array = meta.optJSONArray("videos") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val ep = array.optJSONObject(i) ?: return@mapNotNull null
            val season = ep.optInt("season").takeIf { ep.has("season") && !ep.isNull("season") }
            val number = ep.optInt("episode", ep.optInt("number"))
                .takeIf { (ep.has("episode") && !ep.isNull("episode")) ||
                    (ep.has("number") && !ep.isNull("number")) }
            AioVideo(season, number, ep.episodeMeta(false), ep.string("id"),
                ep.string("imdb_id"), ep.optInt("imdbSeason").takeIf { it > 0 },
                ep.optInt("imdbEpisode").takeIf { it > 0 },
                ep.optJSONArray("streams")?.takeIf { it.length() > 0 })
        }
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
        json: JSONObject, id: String, movie: Boolean, locale: String,
        addonLogoPreferred: Boolean
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
        val preferredLogo = select(logos, preferred)
        val selected = if (addonLogoPreferred || preferredLogo != null) preferredLogo
            else select(logos, "en")
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
        json: JSONObject, id: String, movie: Boolean, locale: String,
        addonLogoPreferred: Boolean
    ): Metadata? {
        val title = json.string("title", "name") ?: return null
        val overview = json.string("overview")
        val poster = json.string("poster_path")?.takeIf { it.startsWith("/") }
            ?.let { "https://image.tmdb.org/t/p/w780$it" }
        val backdrop = json.string("backdrop_path")?.takeIf { it.startsWith("/") }
            ?.let { "https://image.tmdb.org/t/p/original$it" }
        val (logo, foreign) = tmdbLogo(json, id, movie, locale, addonLogoPreferred)
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

    private fun metadataFromAddon(json: JSONObject, includeActors: Boolean): Metadata? {
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
        val (actors, people) = if (includeActors) addonActors(meta) else null to null
        val videos = addonVideos(meta)
        return Metadata(title, meta.string("description", "overview"),
            meta.string("poster"), meta.string("background", "backdrop"),
            (meta.string("logo", "logoUrl")
                ?: meta.optJSONObject("_providerArt")?.string("logo")),
            meta.names("genres").ifEmpty { meta.names("genre") },
            year, rating, meta.string("runtime"), age, status,
            addonTrailers(meta), videos.mapNotNull { video ->
                val season = video.season
                val number = video.number
                if (season != null && number != null)
                    (season to number) to video.meta else null
            }.toMap(), actors, people, source = "AIO",
            originalTitle = meta.string("original_title", "originalTitle"),
            englishTitle = meta.string("english_title", "englishTitle"), videos = videos)
    }

    private val prefs = application.getSharedPreferences(ElevateFeaturePrefs.FILE, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val seasonRequests = Semaphore(35)
    private val screens = mutableMapOf<Fragment, Screen>()
    private var host: FragmentActivity? = null
    private val responseField = runCatching {
        ResultViewModel2::class.java.getDeclaredField("currentResponse").apply { isAccessible = true }
    }.getOrNull()
    private val currentIdField = runCatching {
        ResultViewModel2::class.java.getDeclaredField("currentId").apply { isAccessible = true }
    }.getOrNull()
    private val rebuildMethod = ResultViewModel2::class.java.declaredMethods.firstOrNull {
        it.name == "postEpisodes" && it.parameterTypes.size == 4 &&
            it.parameterTypes[0] == LoadResponse::class.java
    }?.apply { isAccessible = true }
    private val trailerField = runCatching {
        ResultViewModel2::class.java.getDeclaredField("_trailers").apply { isAccessible = true }
    }.getOrNull()
    private val episodeField = runCatching {
        ResultViewModel2::class.java.getDeclaredField("_episodes").apply { isAccessible = true }
    }.getOrNull()
    private val pageField = runCatching {
        ResultViewModel2::class.java.getDeclaredField("_page").apply { isAccessible = true }
    }.getOrNull()

    /** Re-run CloudStream's full season/range/index construction, not only _episodes UI. */
    private suspend fun rebuildHostEpisodes(model: ResultViewModel2, value: TvSeriesLoadResponse): Boolean {
        val method = rebuildMethod ?: return false
        val mainId = runCatching { currentIdField?.get(model) as? Int }.getOrNull()
            ?: return false
        return try {
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                try {
                    method.invoke(model, value, mainId, false, continuation)
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            metadataSwapTrace("ELEVATE_AIO_REBUILD error=${error.javaClass.simpleName}")
            false
        }
    }

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
        private var pendingPage: Resource.Loading? = null
        private var nativeResponse: LoadResponse? = null
        private var original: ResultData? = null
        private var swapped: ResultData? = null
        private var originalEpisodes: List<ResultEpisode>? = null
        private var swappedEpisodes: List<ResultEpisode>? = null
        private var nativeSeries: TvSeriesLoadResponse? = null
        private var rebuiltSeries: TvSeriesLoadResponse? = null
        private var aioRebuilt = false
        private var rebuildingHost = false
        private val suppressedTrailers = emptyList<ExtractedTrailerData>()
        private var nativeTrailerBackup: List<ExtractedTrailerData>? = null
        private var metadata: Metadata? = null
        private var detailId: String? = null
        private var detailLocale: String = ""
        private var job: Job? = null
        private var episodeJob: Job? = null
        private var serial = 0
        private val observer = Observer<Resource<ResultData>?> { state ->
            when (state) {
                is Resource.Loading -> if (state !== pendingPage) {
                    metadataSwapTrace("ELEVATE_NATIVE_LOADING fragment=${fragment.javaClass.simpleName}" +
                        " urlHash=${state.url?.hashCode()?.let(Integer::toHexString)}")
                    job?.cancel()
                    episodeJob?.cancel()
                    serial++
                    original = null
                    swapped = null
                    originalEpisodes = null
                    swappedEpisodes = null
                    metadata = null
                    pendingPage = null
                    nativeResponse = null
                    nativeSeries = null
                    rebuiltSeries = null
                    aioRebuilt = false
                }
                is Resource.Success -> {
                    val page = state.value
                    val response = runCatching { responseField?.get(model) as? LoadResponse }.getOrNull()
                    if (page === swapped || (original?.url == page.url &&
                            (response === nativeResponse ||
                                page.syncData["metadata_swap_source"] != null))) {
                        metadataSwapTrace("ELEVATE_REEMIT_SKIP gen=$serial" +
                            " own=${page === swapped} source=${page.syncData["metadata_swap_source"]}" +
                            " responseSame=${response === nativeResponse}" +
                            " urlHash=${Integer.toHexString(page.url.hashCode())}")
                    } else update(page)
                }
                is Resource.Failure -> metadataSwapTrace(
                    "ELEVATE_NATIVE_FAILURE gen=$serial error=${state.errorString.take(90)}")
                else -> Unit
            }
        }
        private val trailerObserver = Observer<List<ExtractedTrailerData>> { list ->
            if (list === suppressedTrailers) return@Observer
            nativeTrailerBackup = list
            if (metadata != null && swapped != null) suppressNativeTrailers()
        }
        private val episodeObserver = Observer<Resource<List<ResultEpisode>>?> { state ->
            val list = (state as? Resource.Success<List<ResultEpisode>>)?.value ?: return@Observer
            if (list === swappedEpisodes || aioRebuilt || rebuildingHost) {
                metadataSwapTrace("ELEVATE_EPISODES_OWN gen=$serial rebuilt=$aioRebuilt" +
                    " rebuilding=$rebuildingHost count=${list.size}")
                return@Observer
            }
            originalEpisodes = list
            metadataSwapTrace("ELEVATE_EPISODES_NATIVE gen=$serial count=${list.size}" +
                " waiting=${pendingPage != null} metadataReady=${metadata != null}")
            if (pendingPage != null || metadata != null) nativeEpisodesLoading()
            updateEpisodes()
        }

        @Suppress("UNCHECKED_CAST")
        private fun nativeEpisodesLoading() {
            @Suppress("UNCHECKED_CAST")
            val live = episodeField?.get(model) as?
                MutableLiveData<Resource<List<ResultEpisode>>?> ?: return
            if (live.value !is Resource.Loading) live.value = Resource.Loading()
        }

        private fun nativePageLoading(page: ResultData) {
            @Suppress("UNCHECKED_CAST")
            val live = pageField?.get(model) as? MutableLiveData<Resource<ResultData>?> ?: return
            val loading = Resource.Loading(page.url)
            pendingPage = loading
            metadataSwapTrace("ELEVATE_PAGE_LOADING gen=$serial urlHash=${Integer.toHexString(page.url.hashCode())}")
            live.value = loading
        }

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
            metadataSwapTrace("ELEVATE_SCREEN_START fragment=${fragment.javaClass.simpleName}" +
                " pageState=${model.page.value?.javaClass?.simpleName}" +
                " pageField=${pageField != null} responseField=${responseField != null}")
            model.page.observe(fragment.viewLifecycleOwner, observer)
            model.episodes.observe(fragment.viewLifecycleOwner, episodeObserver)
            model.trailers.observe(fragment.viewLifecycleOwner, trailerObserver)
        }

        private fun update(page: ResultData) {
            if (original?.url != page.url) {
                originalEpisodes = null
                swappedEpisodes = null
                nativeSeries = null
                rebuiltSeries = null
                aioRebuilt = false
            }
            val previous = swapped
            original = page
            swapped = null
            metadata = null
            detailId = null
            nativeResponse = runCatching { responseField?.get(model) as? LoadResponse }.getOrNull()
            pendingPage = null
            job?.cancel()
            episodeJob?.cancel()
            val generation = ++serial
            val featureOn = prefs.getBoolean(ElevateFeature.METADATA_SWAP.name, false)
            val policy = page.syncData["metadata_swap_allowed"]
            val provider = prefs.getString(ElevateFeaturePrefs.METADATA_SWAP_PROVIDER, "TMDB")
            val includeActors = !ElevateFeaturePrefs.enabled(
                application, ElevateFeature.ACTOR_INFORMATION)
            metadataSwapTrace("ELEVATE_GATE gen=$generation pageHash=${Integer.toHexString(page.url.hashCode())}" +
                " featureOn=$featureOn policy=${policy ?: "absent"}" +
                " originalSyncKeys=${page.syncData.size}")
            if (!featureOn || policy == "false") {
                if (previous != null &&
                    (model.page.value as? Resource.Success<ResultData>)?.value === previous)
                    nativePage(model, page)
                if (previous != null) originalEpisodes?.let(::nativeEpisodes)
                metadataSwapTrace("ELEVATE_SKIP gen=$generation reason=${if (!featureOn) "feature_off" else "stremioc_policy_false"}")
                return
            }
            val manifest = prefs.getString(ElevateFeaturePrefs.METADATA_SWAP_ADDON_URL, "").orEmpty()
            if (provider == "AIO" && manifest.isBlank()) {
                if (previous != null &&
                    (model.page.value as? Resource.Success<ResultData>)?.value === previous)
                    nativePage(model, page)
                if (previous != null) originalEpisodes?.let(::nativeEpisodes)
                metadataSwapTrace("ELEVATE_SKIP gen=$generation reason=aio_manifest_empty")
                return
            }
            nativePageLoading(page)
            nativeEpisodesLoading()
            metadataSwapTrace("ELEVATE_LOAD_BEGIN gen=$generation responseType=${nativeResponse?.type}" +
                " responseHash=${nativeResponse?.let(System::identityHashCode)}" +
                " actorOwner=${if (includeActors) "swap" else "actor_hook"}" +
                " provider=$provider policy=${policy ?: "absent"}")
            job = scope.launch {
                val currentResponse = runCatching { responseField?.get(model) as? LoadResponse }.getOrNull()
                val response = currentResponse?.takeIf { it.url == page.url }
                val ids = ElevateMediaIdentity.collect(
                    response, extraSync = page.syncData, urls = listOf(page.url))
                val movie = response?.type in setOf(TvType.Movie, TvType.AnimeMovie) ||
                    runCatching { JSONObject(page.url).optString("type") == "movie" }.getOrDefault(false)
                val locale = getCurrentLocale(application).replace('_', '-')
                metadataSwapTrace("ELEVATE_IDS gen=$generation provider=$provider" +
                    " responsePresent=${currentResponse != null} responseMatches=${response != null}" +
                    " responseType=${response?.type} movie=$movie" +
                    " imdb=${ids.imdb != null} tmdb=${ids.tmdb != null}" +
                    " mal=${ids.mal != null} anilist=${ids.anilist != null}" +
                    " kitsu=${ids.kitsu != null} simkl=${ids.simkl != null}" +
                    " original=${ids.original != null} locale=$locale")
                val resolved = try {
                    withContext(Dispatchers.IO) {
                        val aioIds = if (provider == "AIO" &&
                            ids.original == null && ids.imdb == null && ids.tmdb == null &&
                            !ids.animeIds && ids.simkl != null)
                            ElevateMediaIdentity.enrichSimkl(ids, movie) else ids
                        val aio = if (provider == "AIO")
                            Addon.load(manifest, movie, aioIds, page.url, locale, policy == "true")
                                ?.let { metadataFromAddon(it, includeActors) }
                        else null
                        if (provider == "AIO") metadataSwapTrace(
                            "ELEVATE_AIO_RESULT gen=$generation parsed=${aio != null}" +
                                " logo=${!aio?.logo.isNullOrBlank()} episodes=${aio?.episodes?.size ?: 0}")
                        val id = tmdbId(aioIds, movie)
                        metadataSwapTrace("ELEVATE_TMDB_ID gen=$generation resolved=${id != null}" +
                            " idHash=${id?.hashCode()?.let(Integer::toHexString) ?: "none"}")
                        val tmdb = id?.let { key ->
                            ElevateTmdbMetadata.details(key, movie, locale,
                                includeCredits = includeActors)?.let { json ->
                                metadataFromTmdb(json, key, movie, locale,
                                    addonLogoPreferred = provider == "AIO" &&
                                        !aio?.logo.isNullOrBlank()).let { details ->
                                    if (details == null || !includeActors) details else {
                                        val (actors, people) = tmdbActors(
                                            ElevateTmdbMetadata.credits(key, movie, locale))
                                        details.copy(actors = actors, people = people)
                                    }
                                }
                            }
                        }
                        metadataSwapTrace("ELEVATE_TMDB_RESULT gen=$generation found=${tmdb != null}" +
                            " logo=${!tmdb?.logo.isNullOrBlank()} cast=${tmdb?.actors?.size ?: 0}")
                        (if (provider == "AIO") aio?.withFallback(tmdb)
                            else tmdb) to id
                    }
                } catch (cancelled: CancellationException) {
                    metadataSwapTrace("ELEVATE_CANCEL gen=$generation stage=resolve")
                    throw cancelled
                } catch (error: Exception) {
                    metadataSwapTrace("ELEVATE_ERROR gen=$generation stage=resolve" +
                        " type=${error.javaClass.simpleName}")
                    null to null
                }
                val current = resolved.first
                if (current == null) {
                    if (generation != serial || original !== page ||
                        model.page.value !== pendingPage) {
                        metadataSwapTrace("ELEVATE_STALE gen=$generation stage=no_metadata")
                        return@launch
                    }
                    metadataSwapTrace("ELEVATE_SKIP gen=$generation reason=no_metadata fallback=original")
                    pendingPage = null
                    nativePage(model, page)
                    originalEpisodes?.let(::nativeEpisodes)
                    return@launch
                }
                val generationMatches = generation == serial
                val originalMatches = original === page
                val fragmentReady = fragment.isAdded && fragment.view != null
                val featureStillOn = prefs.getBoolean(ElevateFeature.METADATA_SWAP.name, false)
                val providerMatches = prefs.getString(
                    ElevateFeaturePrefs.METADATA_SWAP_PROVIDER, "TMDB") == provider
                val manifestMatches = provider != "AIO" || prefs.getString(
                    ElevateFeaturePrefs.METADATA_SWAP_ADDON_URL, "") == manifest
                val pageMatches = model.page.value === pendingPage
                if (!generationMatches || !originalMatches || !fragmentReady ||
                    !featureStillOn || !providerMatches || !manifestMatches || !pageMatches) {
                    metadataSwapTrace("ELEVATE_STALE gen=$generation generation=$generationMatches" +
                        " original=$originalMatches fragment=$fragmentReady feature=$featureStillOn" +
                        " provider=$providerMatches manifest=$manifestMatches page=$pageMatches")
                    return@launch
                }
                metadata = current
                detailId = resolved.second
                detailLocale = locale
                swapped = current.toPage(page, movie, includeActors)
                pendingPage = null
                swapped?.let { nativePage(model, it) }
                metadataSwapTrace("ELEVATE_FIELDS gen=$generation movie=$movie" +
                    " titleChanged=${swapped?.title != page.title}" +
                    " plotChanged=${swapped?.plotText != page.plotText}" +
                    " posterChanged=${swapped?.posterImage != page.posterImage}" +
                    " logoChanged=${swapped?.logoUrl != page.logoUrl}" +
                    " runtimeCleared=${!movie && swapped?.durationText == null}" +
                    " castCount=${current.actors?.size ?: 0}")
                metadataSwapTrace("ELEVATE_APPLY gen=$generation source=${current.source}" +
                    " pagePublished=${(model.page.value as? Resource.Success<ResultData>)?.value === swapped}" +
                    " title=${current.title != null} logo=${current.logo != null}" +
                    " trailerCount=${current.trailers.size} episodeCount=${current.episodes.size}")
                suppressNativeTrailers()
                updateEpisodes()
            }
        }

        private fun buildAioSeries(
            response: TvSeriesLoadResponse,
            source: Metadata,
            tmdb: Map<Pair<Int, Int>, EpisodeMeta>
        ): TvSeriesLoadResponse {
            val path = response.url.substringBefore('?').substringAfter("/meta/", "")
            val type = path.substringBefore('/')
            val originalId = Uri.decode(path.substringAfterLast('/').removeSuffix(".json"))
            val addonUrl = response.url.substringBefore("/meta/", "")
            val specialSeason = if (source.videos.any { it.season == 0 })
                (source.videos.mapNotNull { it.season?.takeIf { n -> n > 0 } }
                    .maxOrNull() ?: 0) + 1 else null
            val episodes = source.videos.mapIndexed { index, video ->
                val season = if (video.season == 0) specialSeason else video.season
                val number = video.number ?: index + 1
                val payload = JSONObject().put("type", type)
                    .put("id", video.id ?: originalId)
                    .put("season", season).put("episode", number)
                    .put("addonUrl", addonUrl)
                    .put("isAnime", response.type == TvType.Anime)
                    .put("watchProgressAllowed",
                        response.syncData["watch_progress_allowed"] == "true")
                    .put("watchProgressPreferKitsu",
                        response.syncData["watch_progress_prefer_kitsu"] == "true")
                response.syncData["imdb"]?.let { payload.put("imdbId", it) }
                response.syncData["tmdb"]?.toIntOrNull()?.let { payload.put("tmdbId", it) }
                response.syncData["mal"]?.toIntOrNull()?.let { payload.put("malId", it) }
                response.syncData["anilist"]?.toIntOrNull()?.let { payload.put("anilistId", it) }
                response.syncData["kitsu"]?.let { payload.put("kitsuId", it) }
                if (originalId.startsWith("mal:") || originalId.startsWith("kitsu:") ||
                    originalId.startsWith("anilist:"))
                    payload.put("originalAnimeId", originalId)
                video.imdbSeason?.let { payload.put("imdbSeason", it) }
                video.imdbEpisode?.let { payload.put("imdbEpisode", it) }
                video.imdbId?.let { payload.put("imdbId", it) }
                video.streams?.let { payload.put("embeddedStreams", it.toString()) }
                val key = video.season?.let { s -> video.number?.let { n -> s to n } }
                val info = video.meta.withFallback(key?.let(tmdb::get))
                info.name?.let { payload.put("embeddedStreamTitle", it) }
                @Suppress("DEPRECATION_ERROR")
                val episode = Episode(payload.toString())
                episode.season = season
                episode.episode = number
                episode.name = info.name ?: "Episode $number"
                episode.posterUrl = info.poster
                episode.description = info.description
                episode.score = info.rating?.let { Score.from10(it) }
                episode.date = date(info.date)
                episode.runTime = info.runTime
                episode
            }
            val names = source.videos.mapNotNull { it.season }.distinct().map { season ->
                if (season == 0) SeasonData(specialSeason ?: 0, "Season S")
                else SeasonData(season)
            }
            return response.copy(episodes = episodes, seasonNames = names, comingSoon = false)
        }

        private fun restoreNativeSeries() {
            val base = nativeSeries
            val changed = rebuiltSeries
            aioRebuilt = false
            rebuildingHost = false
            nativeSeries = null
            rebuiltSeries = null
            if (base != null && changed != null && responseField?.get(model) === changed) {
                responseField.set(model, base)
                scope.launch { rebuildHostEpisodes(model, base) }
            }
        }

        private fun updateEpisodes() {
            val source = metadata ?: return
            val baseRef = originalEpisodes
            val completeAio = source.source == "AIO" &&
                original?.syncData?.get("metadata_swap_allowed") == "true"
            if (!completeAio && baseRef == null) return
            val base = baseRef.orEmpty()
            val generation = serial
            metadataSwapTrace("ELEVATE_EPISODES_START gen=$generation originalCount=${base.size}" +
                " source=${source.source} tmdbIdAvailable=${detailId != null}")
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
                        val aioBySeason = if (completeAio)
                            source.episodes.entries.groupBy { it.key.first } else emptyMap()
                        val seasons = if (completeAio) aioBySeason.keys.toList()
                        else base.mapNotNull { episode ->
                            episode.seasonIndex?.takeIf {
                                it == episode.season && it in 0..99 && episode.episode > 0 &&
                                    (activeSeason == null || it == activeSeason)
                            }
                        }.distinct()
                        metadataSwapTrace("ELEVATE_SEASON_PLAN gen=" + generation +
                            " selected=" + (activeSeason?.toString() ?: "all") +
                            " seasonCount=" + seasons.size + " concurrency=35")
                        val responses = seasons.map { season ->
                            async(Dispatchers.IO) {
                                if (source.source == "AIO" && (if (completeAio)
                                    aioBySeason[season].orEmpty().map { it.value }
                                else base.filter {
                                    it.seasonIndex == season && it.season == season &&
                                        it.episode > 0
                                }.mapNotNull { source.episodes[season to it.episode] }).let { values ->
                                    values.isNotEmpty() && values.all { info ->
                                        info.name != null && info.description != null &&
                                            info.poster != null && info.date != null &&
                                            info.runTime != null && info.rating != null
                                    }
                                }) {
                                    metadataSwapTrace("ELEVATE_SEASON_SKIP gen=" + generation +
                                        " season=" + season + " reason=aio_complete")
                                    return@async emptyMap<Pair<Int, Int>, EpisodeMeta>()
                                }
                                val response = withTimeoutOrNull(10_000L) {
                                    seasonRequests.withPermit {
                                        ElevateTmdbMetadata.season(id, season, detailLocale)
                                    }
                                }
                                val found = LinkedHashMap<Pair<Int, Int>, EpisodeMeta>()
                                val values = response?.optJSONArray("episodes")
                                if (values != null) for (i in 0 until values.length()) {
                                    val ep = values.optJSONObject(i) ?: continue
                                    val number = ep.optInt("episode_number")
                                        .takeIf { it > 0 } ?: continue
                                    found[season to number] = ep.episodeMeta(true)
                                }
                                metadataSwapTrace("ELEVATE_SEASON_RESULT gen=" + generation +
                                    " season=" + season + " ok=" + (response != null) +
                                    " episodes=" + found.size)
                                found
                            }
                        }.awaitAll()
                        for (items in responses) result.putAll(items)
                        result
                    }
                }
                if (generation != serial || metadata !== source ||
                    originalEpisodes !== baseRef || !fragment.isAdded) {
                    metadataSwapTrace("ELEVATE_EPISODES_STALE gen=$generation")
                    return@launch
                }
                metadataSwapTrace("ELEVATE_EPISODES_RESOLVED gen=$generation tmdbMatches=${tmdbEpisodes.size}" +
                    " addonMatches=${source.episodes.size}")
                if (completeAio) {
                    val response = nativeSeries
                        ?: (runCatching { responseField?.get(model) as? TvSeriesLoadResponse }.getOrNull())
                    if (response != null && response.url == original?.url) {
                        val rebuilt = buildAioSeries(response, source, tmdbEpisodes)
                        if (nativeSeries == null) nativeSeries = response
                            rebuildingHost = true
                            responseField?.set(model, rebuilt)
                            val published = rebuildHostEpisodes(model, rebuilt)
                            if (published) {
                                rebuiltSeries = rebuilt
                                aioRebuilt = true
                            } else responseField?.set(model, response)
                            rebuildingHost = false
                            metadataSwapTrace("ELEVATE_AIO_REBUILD gen=$generation" +
                                " published=$published originalEpisodes=${response.episodes.size}" +
                                " aioEpisodes=${rebuilt.episodes.size}" +
                                " seasons=${rebuilt.episodes.mapNotNull { it.season }.distinct().size}")
                            if (published) {
                                metadataSwapTrace("ELEVATE_EPISODES_FINAL gen=$generation" +
                                    " mode=full_aio count=${rebuilt.episodes.size}")
                                return@launch
                            }
                    }
                    // Do not publish a partial original/AIO episode merge.
                    metadataSwapTrace("ELEVATE_EPISODES_FALLBACK gen=$generation reason=aio_rebuild_failed")
                    val fallback = originalEpisodes.orEmpty()
                    swappedEpisodes = fallback
                    nativeEpisodes(fallback)
                    return@launch
                }
                var matched = 0
                var unmatched = 0
                val result = base.map { episode ->
                    val season = episode.seasonIndex?.takeIf {
                        it == episode.season && it in 0..99 && episode.episode > 0
                    }
                    val key = season?.let { it to episode.episode }
                    val addon = key?.let { source.episodes[it] }
                    val tmdb = key?.let { tmdbEpisodes[it] }
                    val info = if (source.source == "AIO") addon?.withFallback(tmdb) ?: tmdb
                        else tmdb
                    if (info == null) unmatched++ else matched++
                    // Preserve the existing display replacement policy; only instrument matches.
                    // .copy retains playback URL, episode ID, season mapping and watch progress.
                    episode.copy(
                        name = info?.name, description = info?.description,
                        poster = info?.poster, score = info?.rating?.let { Score.from10(it) },
                        airDate = date(info?.date), runTime = info?.runTime
                    )
                }
                metadataSwapTrace("ELEVATE_EPISODES_MATCH gen=" + generation +
                    " matched=" + matched + " unmatched=" + unmatched +
                    " baseSeasons=" + base.mapNotNull { it.season }.distinct().size +
                    " aioSeasons=" + source.episodes.keys.map { it.first }.distinct().size +
                    " idsPreserved=" + result.indices.all {
                        result[it].id == base[it].id && result[it].data == base[it].data
                    })
                swappedEpisodes = result
                nativeEpisodes(result)
                metadataSwapTrace("ELEVATE_EPISODES_FINAL gen=$generation mode=merged count=${result.size}")
                metadataSwapTrace("ELEVATE_UI_STATE gen=" + generation +
                    " seasonOptions=" + (model.seasonSelections.value?.size ?: 0) +
                    " selectedSeason=" + (model.selectedSeasonIndex.value ?: -1) +
                    " rangeOptions=" + (model.rangeSelections.value?.size ?: 0) +
                    " visibleEpisodes=" + result.size)
                metadataSwapTrace("ELEVATE_EPISODES_APPLY gen=$generation count=${result.size}" +
                    " published=${(model.episodes.value as? Resource.Success<List<ResultEpisode>>)?.value === result}")
            }
        }

        fun refresh() {
            val current = original ?: return
            metadataSwapTrace("ELEVATE_REFRESH gen=$serial urlHash=${Integer.toHexString(current.url.hashCode())}")
            job?.cancel()
            episodeJob?.cancel()
            serial++
            metadata = null
            detailId = null
            restoreNativeSeries()
            restoreNativeTrailers()
            val visibleEpisodes = (model.episodes.value as?
                Resource.Success<List<ResultEpisode>>)?.value
            if (visibleEpisodes === swappedEpisodes) swappedEpisodes = null
            update(current)
        }

        fun stop() {
            metadataSwapTrace("ELEVATE_SCREEN_STOP gen=$serial swapped=${swapped != null}")
            job?.cancel()
            episodeJob?.cancel()
            serial++
            model.page.removeObserver(observer)
            model.episodes.removeObserver(episodeObserver)
            model.trailers.removeObserver(trailerObserver)
            restoreNativeSeries()
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
            nativeSeries = null
            rebuiltSeries = null
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
        private data class Cached(val at: Long, val ttlMs: Long, val bytes: ByteArray)
        private const val META_MAX_ITEMS = 48
        private const val META_MAX_BYTES = 12L * 1024 * 1024
        private const val MAX_RAW_BYTES = 2 * 1024 * 1024
        private var metaBytes = 0L
        private val versions = mutableMapOf<String, String>()
        private data class Fetched(val json: JSONObject, val ttlMs: Long)
        private val manifests = LinkedHashMap<String, Cached>(16, 0.75f, true)
        private val metadata = LinkedHashMap<String, Cached>(48, 0.75f, true)
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

        private fun remember(
            kind: String, cache: LinkedHashMap<String, Cached>,
            key: String, at: Long, ttlMs: Long, bytes: ByteArray
        ) {
            if (bytes.size > MAX_RAW_BYTES) return
            synchronized(cache) {
                cache.remove(key)?.let { if (kind == "meta") metaBytes -= it.bytes.size }
                cache[key] = Cached(at, ttlMs, bytes)
                if (kind == "meta") metaBytes += bytes.size
                val limit = if (kind == "meta") META_MAX_ITEMS else 100
                val iterator = cache.entries.iterator()
                while ((cache.size > limit ||
                        (kind == "meta" && metaBytes > META_MAX_BYTES)) && iterator.hasNext()) {
                    val old = iterator.next().value
                    if (kind == "meta") metaBytes -= old.bytes.size
                    iterator.remove()
                }
                metadataSwapTrace("ELEVATE_CACHE_RAM kind=$kind entries=${cache.size}" +
                    " bytes=${if (kind == "meta") metaBytes else bytes.size.toLong()}")
            }
        }

        private suspend fun shared(
            key: String, kind: String, cache: LinkedHashMap<String, Cached>,
            flights: MutableMap<String, Deferred<JSONObject?>>,
            request: suspend () -> Fetched?
        ): JSONObject? {
            val now = System.currentTimeMillis()
            val ram = synchronized(cache) {
                cache[key]?.takeIf { now >= it.at && now - it.at < it.ttlMs }?.bytes
            }
            if (ram != null) runCatching { JSONObject(String(ram, Charsets.UTF_8)) }
                .getOrNull()?.let {
                    metadataSwapTrace("ELEVATE_CACHE_HIT kind=$kind source=ram")
                    return it
                }
            val job = synchronized(cache) {
                flights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                    val disk = ElevateMetadataDiskCache.read(kind, key)
                    if (disk != null) {
                        val parsed = runCatching { JSONObject(disk.body) }.getOrNull()
                        if (parsed != null) {
                            remember(kind, cache, key, disk.at, disk.ttlMs,
                                disk.body.toByteArray(Charsets.UTF_8))
                            metadataSwapTrace("ELEVATE_CACHE_HIT kind=$kind source=disk")
                            return@async parsed
                        }
                    }
                    val fetched = request()
                    if (fetched != null && fetched.ttlMs > 0L) {
                        val body = fetched.json.toString()
                        val raw = body.toByteArray(Charsets.UTF_8)
                        if (raw.size <= MAX_RAW_BYTES) {
                            remember(kind, cache, key, System.currentTimeMillis(), fetched.ttlMs, raw)
                            scope.launch {
                                ElevateMetadataDiskCache.write(kind, key, body, fetched.ttlMs)
                            }
                            metadataSwapTrace("ELEVATE_CACHE_STORE kind=$kind" +
                                " bytes=${raw.size} ttlMs=${fetched.ttlMs}")
                        }
                    } else metadataSwapTrace("ELEVATE_CACHE_BYPASS kind=$kind" +
                        " reason=" + (if (fetched == null) "request_failed" else "cache_policy"))
                    fetched?.json
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

        private fun cacheTtl(
            headers: List<Pair<String, String>>, payload: JSONObject, movie: Boolean?
        ): Long {
            fun header(name: String): String? =
                headers.firstOrNull { it.first.equals(name, true) }?.second
            val directives = header("Cache-Control").orEmpty()
                .lowercase(Locale.ROOT).split(',').map { it.trim() }
            if (directives.any {
                it == "no-store" || it == "no-cache" || it == "private" ||
                    it == "max-age=0" || it == "s-maxage=0"
            }) return 0L
            if (header("Vary").orEmpty().split(',').any {
                    it.trim().isNotEmpty() && !it.trim().equals("Accept-Encoding", true)
                }) return 0L
            val max = 7L * 24 * 60 * 60 * 1000
            val age = header("Age")?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val maxAge = directives.firstOrNull { it.startsWith("max-age=") }
                ?.substringAfter('=')?.trim('"', ' ')?.toLongOrNull()
            if (maxAge != null) return (maxAge - age).coerceIn(0L, max / 1000L) * 1000L
            val expires = header("Expires")
            if (!expires.isNullOrBlank()) {
                fun parseDate(value: String?): Long? = runCatching {
                    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                        .parse(value ?: "")?.time
                }.getOrNull()
                parseDate(expires)?.let {
                    return (it - (parseDate(header("Date")) ?: System.currentTimeMillis()) -
                        age * 1000L).coerceIn(0L, max)
                }
            }
            payload.optLong("cacheMaxAge", -1L).takeIf { it >= 0L }?.let {
                return (it - age).coerceIn(0L, max / 1000L) * 1000L
            }
            return when (movie) {
                null -> 3_600_000L
                true -> 3L * 24 * 60 * 60 * 1000
                false -> 12L * 60 * 60 * 1000
            }
        }

        private suspend fun request(url: String, movie: Boolean?): Fetched? = try {
            val response = app.get(url, timeout = 12L)
            if (response.code !in 200..299) null else {
                val text = response.text
                val raw = JSONObject(text)
                val meta = if (movie == null) null else raw.optJSONObject("meta") ?: raw
                var ttl = cacheTtl(response.headers.toList(), raw, movie)
                if (meta != null) {
                    val wantedType = url.substringAfter("/meta/").substringBefore('/')
                    val wantedId = Uri.decode(url.substringBefore('?')
                        .substringAfterLast('/').removeSuffix(".json"))
                    val foundId = meta.optString("id").trim()
                    val foundType = meta.optString("type").trim()
                    val idValid = wantedId == foundId ||
                        (wantedId.startsWith("mal:") || wantedId.startsWith("kitsu:") ||
                            wantedId.startsWith("anilist:")) &&
                        (foundId.matches(Regex("tt[0-9]+")) || foundId.startsWith("tmdb:"))
                    val typeValid = wantedType == foundType ||
                        (wantedType == "tv" && foundType == "series")
                    val transient = meta.has("streams") || meta.optString("url").isNotBlank() ||
                        (meta.optJSONArray("videos")?.let { videos ->
                            (0 until videos.length()).any { i ->
                                val video = videos.optJSONObject(i)
                                video != null && listOf("streams", "sources", "stream", "url")
                                    .any { video.has(it) }
                            }
                        } == true)
                    if (!idValid || !typeValid || transient ||
                        text.contains("data:image/", true)) {
                        ttl = 0L
                        metadataSwapTrace("ELEVATE_CACHE_VALIDATE id=" + idValid +
                            " type=" + typeValid + " transient=" + transient)
                    }
                }
                val result = if (meta == null) raw else {
                    val data = meta
                    val slim = JSONObject()
                    for (field in listOf("id", "type", "name", "title", "description",
                        "overview", "poster", "background", "backdrop", "logo", "logoUrl",
                        "genres", "genre", "year", "releaseInfo", "released", "imdbRating",
                        "rating", "runtime", "status", "contentRating", "certification",
                        "app_extras", "trailers", "trailerStreams", "_providerArt",
                        "original_title", "originalTitle", "english_title", "englishTitle")) {
                        if (data.has(field)) slim.put(field, data.get(field))
                    }
                    data.optJSONArray("videos")?.let { videos ->
                        slim.put("videos", JSONArray().apply {
                            for (i in 0 until videos.length()) {
                                val video = videos.optJSONObject(i) ?: continue
                                put(JSONObject().apply {
                                    for (field in listOf("season", "episode", "number", "id",
                                        "imdb_id", "imdbSeason", "imdbEpisode", "tvdb_id",
                                        "streams", "title", "name", "overview", "description",
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
                Fetched(result, ttl)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }

        suspend fun load(rawManifest: String, movie: Boolean, ids: ElevateMediaIdentity.Ids,
            sourceUrl: String, locale: String, stremioManaged: Boolean): JSONObject? {
            val manifest = manifestUrl(rawManifest)
            if (manifest == null) {
                metadataSwapTrace("ELEVATE_ADDON_SKIP reason=invalid_manifest")
                return null
            }
            val uri = manifest.toString()
            val manifestHash = Integer.toHexString(uri.hashCode())
            val definition = shared(uri, "manifest", manifests, manifestFlights) {
                request(uri, null)
            }
            if (definition == null) {
                metadataSwapTrace("ELEVATE_ADDON_SKIP manifestHash=$manifestHash reason=manifest_failed")
                return null
            }
            val resources = definition.optJSONArray("resources")
            if (resources == null) {
                metadataSwapTrace("ELEVATE_ADDON_SKIP manifestHash=$manifestHash reason=no_resources")
                return null
            }
            val supportsMeta = (0 until resources.length()).any {
                when (val item = resources.opt(it)) {
                    is String -> item == "meta"
                    is JSONObject -> item.optString("name") == "meta"
                    else -> false
                }
            }
            if (!supportsMeta) {
                metadataSwapTrace("ELEVATE_ADDON_SKIP manifestHash=$manifestHash reason=meta_unsupported")
                return null
            }
            val allowedTypes = definition.optJSONArray("types")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            val candidates = if (movie) listOf("movie") else
                if (ids.animeIds) listOf("anime", "series", "tv") else listOf("series", "tv", "anime")
            val type = candidates.firstOrNull { it in allowedTypes }
            if (type == null) {
                metadataSwapTrace("ELEVATE_ADDON_SKIP manifestHash=$manifestHash" +
                    " reason=no_compatible_type movie=$movie animeIds=${ids.animeIds}" +
                    " allowedTypes=${allowedTypes.joinToString(",")}")
                return null
            }
            val sourceId = sourceUrl.substringBefore('?').substringAfter("/meta/", "")
                .takeIf { it.contains('/') }?.substringAfter('/', "")
                ?.removeSuffix(".json")?.takeIf(String::isNotBlank)?.let(Uri::decode)
            val prefixes = definition.optJSONArray("idPrefixes")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            val id = if (stremioManaged) sourceId else listOfNotNull(
                ids.original, ids.imdb, ids.tmdb?.let { "tmdb:$it" },
                ids.mal?.let { "mal:$it" }, ids.anilist?.let { "anilist:$it" },
                ids.kitsu?.let { "kitsu:$it" }
            ).distinct().firstOrNull { candidate ->
                prefixes.isEmpty() || prefixes.any { candidate.startsWith(it, true) }
            }
            if (id == null) {
                metadataSwapTrace("ELEVATE_ADDON_SKIP manifestHash=$manifestHash reason=no_compatible_id")
                return null
            }
            metadataSwapTrace("ELEVATE_ADDON_ID type=$type source=${if (stremioManaged) "stremioc_meta" else "media_identity"}")
            val version = definition.optString("version").trim()
            synchronized(metadata) {
                val last = versions.put(uri, version)
                if (last != null && last != version) {
                    val base = uri.removeSuffix("/manifest.json")
                    val entries = metadata.entries.iterator()
                    while (entries.hasNext()) {
                        val item = entries.next()
                        if (item.key.contains("|$base/meta/")) {
                            metaBytes -= item.value.bytes.size
                            entries.remove()
                        }
                    }
                    metadataSwapTrace("ELEVATE_CACHE_VERSION_INVALIDATE manifestHash=$manifestHash" +
                        " remaining=${metadata.size} bytes=$metaBytes")
                }
            }
            val url = manifest.buildUpon().encodedPath(
                manifest.encodedPath.orEmpty().removeSuffix("/manifest.json") +
                    "/meta/$type/${Uri.encode(id)}.json"
            ).build().toString()
            val key = version + "|" + locale.lowercase(Locale.ROOT) + "|" + url
            val result = shared(key, "meta", metadata, metaFlights) {
                request(url, movie)
            }
            val found = result?.optJSONObject("meta") != null
            metadataSwapTrace("ELEVATE_ADDON_META manifestHash=$manifestHash type=$type" +
                " received=${result != null} validMeta=$found")
            if (found) return result
            metadataSwapTrace("ELEVATE_ADDON_SKIP manifestHash=$manifestHash reason=no_valid_meta")
            return null
        }
    }
}
