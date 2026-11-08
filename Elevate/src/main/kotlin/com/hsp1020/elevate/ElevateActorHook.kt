package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.util.Log
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import androidx.navigation.NavOptions
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import androidx.preference.PreferenceManager
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.readIdFromString
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SimklSyncServices
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.result.ActorAdaptor
import com.lagradost.cloudstream3.ui.settings.getCurrentLocale
import com.lagradost.cloudstream3.ui.result.ResultData
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.WeakHashMap

/** Universal cast binding based on sync IDs, never on provider names or URL schemas. */
internal class ElevateActorHook(private val appContext: Application) :
    Application.ActivityLifecycleCallbacks {
    private fun trace(step: String, detail: String) = Log.d("ElevateActorTrace", "$step $detail")
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindings = mutableMapOf<Fragment, Binding>()
    private var host: FragmentActivity? = null
    private val responseField = runCatching {
        ResultViewModel2::class.java.getDeclaredField("currentResponse")
            .apply { isAccessible = true }
    }.getOrNull().also { Log.d("ElevateActorTrace", "REFLECTION currentResponse=${it != null}") }
    private data class CastResult(val actors: List<ActorData>,
        val people: List<ElevateActorPersonRef>?)
    private data class Cached(val at: Long, val result: CastResult)
    private val tmdbCache = LinkedHashMap<String, Cached>()
    private val tmdbFlights = mutableMapOf<String, Deferred<CastResult?>>()
    private val anilistCache = LinkedHashMap<String, Cached>()
    private val anilistFlights = mutableMapOf<String, Deferred<CastResult?>>()

    private data class Ids(
        val anilist: String? = null, val mal: String? = null, val kitsu: String? = null,
        val tmdb: String? = null, val imdb: String? = null, val simkl: String? = null
    ) {
        fun hasNonKitsu() = listOf(anilist, mal, tmdb, imdb, simkl).any { it != null }
    }

    private fun positive(raw: String?): String? =
        raw?.substringBefore(':')?.toLongOrNull()?.takeIf { it > 0 }?.toString()

    private fun ids(page: ResultData, response: LoadResponse?): Ids {
        val saved = LoadResponse.readIdFromString(page.syncData["simkl"])
        fun direct(key: String): String? = response?.syncData?.get(key)
            ?: page.syncData[key]
        fun number(key: String) = positive(direct(key)?.removePrefix("$key:"))
        return Ids(
            anilist = positive(response?.getAniListId()) ?:
                positive(saved[SimklSyncServices.AniList]) ?: number("anilist"),
            mal = positive(response?.getMalId()) ?:
                positive(saved[SimklSyncServices.Mal]) ?: number("mal"),
            kitsu = positive(response?.getKitsuId()) ?: number("kitsu"),
            tmdb = positive(response?.getTMDbId()) ?:
                positive(saved[SimklSyncServices.Tmdb]) ?: number("tmdb"),
            imdb = (response?.getImdbId() ?: saved[SimklSyncServices.Imdb] ?: direct("imdb"))
                ?.takeIf { it.matches(Regex("tt[0-9]{5,10}")) },
            simkl = positive(saved[SimklSyncServices.Simkl]) ?: number("simkl")
        )
    }

    private suspend fun anilist(id: String): CastResult? {
        val now = System.currentTimeMillis()
        val flight = synchronized(anilistCache) {
            anilistCache[id]?.takeIf { now - it.at in 0 until 43_200_000L }
                ?.let { return it.result }
            anilistFlights[id] ?: io.async(start = CoroutineStart.LAZY) {
                trace("ANILIST_START", "id=$id")
                val cast = try {
                    AccountManager.aniListApi.load(null, id)?.actors
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (e: Exception) {
                    trace("ANILIST_ERROR", "id=$id error=${e.javaClass.simpleName}:${e.message}")
                    null
                }
                trace("ANILIST_RESULT", "id=$id count=${cast?.size ?: 0}")
                val found = cast?.takeIf { it.isNotEmpty() }?.let { CastResult(it, null) }
                if (found != null) synchronized(anilistCache) {
                    anilistCache[id] = Cached(System.currentTimeMillis(), found)
                    if (anilistCache.size > 64) anilistCache.remove(anilistCache.keys.first())
                }
                found
            }.also { task ->
                anilistFlights[id] = task
                task.invokeOnCompletion {
                    synchronized(anilistCache) {
                        if (anilistFlights[id] === task) anilistFlights.remove(id)
                    }
                }
            }
        }
        flight.start()
        return flight.await()
    }
    private suspend fun animeMapped(source: String, id: String): CastResult? {
        trace("ANIME_MAP_START", "source=$source id=$id")
        val mapped = ElevateAnimeIdResolver.resolve(source, id)
        trace("ANIME_MAP_RESULT", "source=$source id=$id anilist=${mapped?.anilist}")
        if (mapped == null) return null
        return mapped.anilist?.let { anilist(it) }
    }

    private suspend fun tmdb(raw: String, movie: Boolean): CastResult? {
        val id = positive(raw)
        val apiKey = ElevateIdResolver.tmdbApiKey()
        trace("TMDB_PRECHECK", "raw=$raw id=$id movie=$movie apiKeyPresent=${!apiKey.isNullOrBlank()}")
        if (id == null || apiKey == null) return null
        val language = getCurrentLocale(appContext)
        val key = "${if (movie) "movie" else "tv"}:$id:$language:${apiKey.hashCode()}"
        val now = System.currentTimeMillis()
        val task = synchronized(tmdbCache) {
            tmdbCache[key]?.takeIf { now - it.at in 0 until 43_200_000L }
                ?.let { trace("TMDB_CACHE_HIT", "key=$key actors=${it.result.actors.size}"); return it.result }
            tmdbFlights[key] ?: io.async(start = CoroutineStart.LAZY) {
                trace("TMDB_REQUEST_START", "type=${if (movie) "movie" else "tv"} id=$id")
                val found = try {
                    withTimeoutOrNull(15_000L) {
                        val kind = if (movie) "movie" else "tv"
                        val res = app.get("https://api.themoviedb.org/3/$kind/$id/credits",
                            params = mapOf("api_key" to apiKey, "language" to language), timeout = 12L)
                        trace("TMDB_HTTP", "id=$id status=${res.code} bytes=${res.text.length}")
                        val json = JSONObject(res.text)
                        val pairs = ArrayList<Pair<ActorData, ElevateActorPersonRef>>()
                        val crew = json.optJSONArray("crew")
                        if (crew != null) for (i in 0 until crew.length()) {
                            val obj = crew.optJSONObject(i) ?: continue
                            val job = obj.optString("job")
                            if (job != "Director" && job != "Writer") continue
                            val name = obj.optString("name").takeIf(String::isNotBlank) ?: continue
                            val personId = obj.optInt("id").takeIf { it > 0 } ?: continue
                            val photo = obj.optString("profile_path")
                                .takeIf { it.startsWith("/") }
                                ?.let { "https://image.tmdb.org/t/p/w185$it" }
                            val existing = pairs.indexOfFirst { it.second.id == personId }
                            if (existing >= 0) {
                                val roles = listOfNotNull(pairs[existing].first.roleString, job)
                                    .flatMap { it.split(", ") }.distinct().joinToString(", ")
                                pairs[existing] = ActorData(Actor(name, photo), roleString = roles) to
                                    ElevateActorPersonRef(personId, name, photo, true)
                            } else pairs += ActorData(Actor(name, photo), roleString = job) to
                                ElevateActorPersonRef(personId, name, photo, true)
                        }
                        val cast = json.optJSONArray("cast")
                        if (cast != null) for (i in 0 until cast.length()) {
                            val obj = cast.optJSONObject(i) ?: continue
                            val personId = obj.optInt("id").takeIf { it > 0 } ?: continue
                            val name = obj.optString("name").takeIf(String::isNotBlank) ?: continue
                            val photo = obj.optString("profile_path")
                                .takeIf { it.startsWith("/") }
                                ?.let { "https://image.tmdb.org/t/p/w185$it" }
                            pairs += ActorData(Actor(name, photo),
                                roleString = obj.optString("character").takeIf(String::isNotBlank)) to
                                ElevateActorPersonRef(personId, name, photo, false)
                        }
                        trace("TMDB_PARSED", "id=$id crew=${crew?.length() ?: 0} cast=${cast?.length() ?: 0} valid=${pairs.size}")
                        pairs.takeIf { it.isNotEmpty() }?.let { rows ->
                            CastResult(rows.map { it.first }, rows.map { it.second })
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (e: Exception) { trace("TMDB_ERROR", "id=$id error=${e.javaClass.simpleName}:${e.message}"); null }
                trace("TMDB_RESULT", "id=$id actors=${found?.actors?.size ?: 0} timeoutOrEmpty=${found == null}")
                if (found != null) synchronized(tmdbCache) {
                    tmdbCache[key] = Cached(System.currentTimeMillis(), found)
                    if (tmdbCache.size > 64) tmdbCache.remove(tmdbCache.keys.first())
                }
                found
            }.also { flight ->
                tmdbFlights[key] = flight
                flight.invokeOnCompletion {
                    synchronized(tmdbCache) {
                        if (tmdbFlights[key] === flight) tmdbFlights.remove(key)
                    }
                }
            }
        }
        trace("TMDB_FLIGHT", "key=$key active=${tmdbFlights.containsKey(key)}")
        task.start()
        return task.await()
    }

    /** Provider actors remain available only after Elevate API resolution has failed. */
    private suspend fun resolve(page: ResultData, response: LoadResponse?, ids: Ids,
        selectedSeason: Int?, multiSeason: Boolean): CastResult? {
        val type = response?.type
        val kind = runCatching {
            JSONObject(response?.url ?: page.url).optString("type").lowercase()
        }.getOrNull()
        val movie = type in setOf(TvType.Movie, TvType.AnimeMovie) ||
            (type == null || type == TvType.Others) && kind == "movie"
        val anime = type in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA) ||
            (type in setOf(TvType.Others, TvType.Cartoon) &&
                (ids.anilist != null || ids.mal != null))
        trace("RESOLVE_START", "url=${page.url.take(140)} type=$type ids=$ids season=$selectedSeason multi=$multiSeason anime=$anime")
        // Resolve selected seasons independently, never apply a series-wide anime
        // character list to another season just because it is present on LoadResponse.
        if (anime && !movie && selectedSeason != null && ids.imdb != null) {
            val mapping = ElevateAnimeIdResolver.resolveImdbSeason(ids.imdb, selectedSeason)
            val seasonal = mapping?.anilist?.let { anilist(it) }
            trace("ANIME_SEASON_RESULT", "imdb=${ids.imdb} season=$selectedSeason anilist=${mapping?.anilist} actors=${seasonal?.actors?.size ?: 0}")
            if (seasonal != null) return seasonal
        }
        val options = if (anime) {
            if (multiSeason) emptyList() else listOf("anilist", "mal")
        } else listOf("tmdb", "imdb", "simkl")
        trace("RESOLVE_ORDER", "anime=$anime movie=$movie options=$options")
        for (candidate in options) {
            val result = when (candidate) {
                "anilist" -> ids.anilist?.let { anilist(it) }
                "mal" -> ids.mal?.let { animeMapped("mal", it) }
                "tmdb" -> ids.tmdb?.let { tmdb(it, movie) }
                "imdb" -> ids.imdb?.let { imdb ->
                    ElevateIdResolver.tmdbForImdb(imdb, movie)?.let { tmdb(it, movie) }
                }
                "simkl" -> ids.simkl?.let { simkl ->
                    ElevateIdResolver.simkl(simkl, movie)?.tmdb?.let { tmdb(it, movie) }
                }
                else -> null
            }
            trace("RESOLVE_STEP_RESULT", "candidate=$candidate actors=${result?.actors?.size ?: 0}")
            if (result != null) return result
        }
        return if (anime && !multiSeason && !ids.hasNonKitsu() && ids.kitsu != null)
            animeMapped("kitsu", ids.kitsu) else null
    }
    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            Log.d("ElevateActorTrace", "FRAGMENT_CREATED name=${fragment.javaClass.name}")
            attach(fragment, view)
            view.post { if (fragment.view === view) attach(fragment, view) }
        }
        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            Log.d("ElevateActorTrace", "FRAGMENT_RESUMED name=${fragment.javaClass.name}")
            fragment.view?.let { attach(fragment, it) }
            bindings[fragment]?.render()
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            bindings.remove(fragment)?.stop()
        }
    }

    private fun isResultFragment(fragment: Fragment): Boolean {
        var type: Class<*>? = fragment.javaClass
        while (type != null) {
            if (type.name == "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone" ||
                type.name == "com.lagradost.cloudstream3.ui.result.ResultFragmentTv") return true
            type = type.superclass
        }
        return false
    }

    private fun attach(fragment: Fragment, root: View) {
        // ResultTrailerPlayer inherits ResultFragmentPhone.
        if (bindings.containsKey(fragment) || !isResultFragment(fragment)) return
        val list = root.findViewById<RecyclerView>(R.id.result_cast_items)
        trace("ATTACH_CHECK", "fragment=${fragment.javaClass.name} castList=${list != null} adapter=${list?.adapter?.javaClass?.simpleName}")
        if (list == null) return
        list.visibility = View.GONE
        bindings[fragment] = Binding(fragment, list).also { it.start() }
    }

    private inner class Binding(val fragment: Fragment, val list: RecyclerView) {
        private val model = ViewModelProvider(fragment)[ResultViewModel2::class.java]
        private val castText: TextView? =
            fragment.view?.findViewById(R.id.result_cast_text)
        private val controller = ElevateActorInformation.Controller(fragment)
        private val cards = WeakHashMap<View, Pair<ActorData, ElevateActorPersonRef>>()
        private val press = DecelerateInterpolator()
        private var longPressed = false
        private val touch = View.OnTouchListener { card, event ->
            if (pendingAnimation != null) return@OnTouchListener false
            val portrait = card.findViewById<View>(R.id.voice_actor_image_holder2)
                ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressed = false
                    portrait.animate().cancel()
                    portrait.clearAnimation()
                    portrait.scaleX = 1f
                    portrait.scaleY = 1f
                    portrait.animate().scaleX(0.8f).scaleY(0.8f)
                        .setDuration(200L).setInterpolator(press).start()
                }
                MotionEvent.ACTION_UP -> if (!longPressed) portrait.animate().cancel()
                MotionEvent.ACTION_CANCEL -> if (!longPressed) {
                    portrait.animate().cancel()
                    portrait.scaleX = 1f
                    portrait.scaleY = 1f
                }
            }
            false
        }
        private val searchLongPress = View.OnLongClickListener { card ->
            val adapter = list.adapter as? ActorAdaptor
            val pos = list.getChildAdapterPosition(card)
            val actor = if (pos != RecyclerView.NO_POSITION)
                adapter?.getItemOrNull(pos) else null
            val voiceShown = actor != null && adapter?.inverted?.get(actor) == true &&
                !actor.voiceActor?.image.isNullOrBlank()
            val query = if (voiceShown) actor?.voiceActor?.name else actor?.actor?.name
            val activity = fragment.activity
            val nav = activity?.let {
                it.resources.getIdentifier("navigation_search", "id", it.packageName)
            } ?: 0
            trace("LONG_PRESS", "pos=$pos query=$query activity=${activity != null} nav=$nav")
            if (query.isNullOrBlank() || activity == null || nav == 0)
                false else {
                longPressed = true
                card.findViewById<View>(R.id.voice_actor_image_holder2)?.let {
                    it.animate().cancel()
                    it.scaleX = 1f
                    it.scaleY = 1f
                }
                val enter = activity.resources.getIdentifier("enter_anim", "anim",
                    activity.packageName)
                val exit = activity.resources.getIdentifier("exit_anim", "anim",
                    activity.packageName)
                activity.navigate(nav, Bundle().apply {
                    putString("search_query", query)
                    putBoolean("global_detail_search", true)
                }, NavOptions.Builder().setEnterAnim(enter).setExitAnim(exit)
                    .setPopEnterAnim(enter).setPopExitAnim(exit).build())
                true
            }
        }
        private var active = false
        private var restoringCast = false
        private var pageUrl: String? = null
        private var pageIdentity: String? = null
        private fun seasonChanged() {
            val index = model.selectedSeasonIndex.value ?: return
            if (model.seasonSelections.value?.getOrNull(index) == null) return
            // Identity dedup prevents a request when the same season is selected again.
            (model.page.value as? Resource.Success<ResultData>)?.value?.let(::update)
        }
        private val seasonIndexObserver = Observer<Int> { seasonChanged() }
        private val seasonChoicesObserver = Observer<List<*>> { seasonChanged() }
        private var people: List<ElevateActorPersonRef>? = null
        private var lastActors: List<ActorData>? = null
        private var loadJob: Job? = null
        private var pendingAnimation: android.view.animation.Animation? = null
        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (active && lastActors == null) {
                if (list.visibility != View.GONE) list.visibility = View.GONE
                if (castText?.visibility != View.GONE) castText?.visibility = View.GONE
            }
            attachClicks()
        }
        private var tree: ViewTreeObserver? = null
        private var observedAdapter: RecyclerView.Adapter<*>? = null
        private val dataObserver = object : RecyclerView.AdapterDataObserver() {
            private fun rebound() {
                list.post {
                    if (active && fragment.isAdded) {
                        trace("ADAPTER_CHANGED", "count=${list.adapter?.itemCount} children=${list.childCount}")
                        attachClicks()
                    }
                }
            }
            override fun onChanged() = rebound()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = rebound()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = rebound()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = rebound()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = rebound()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = rebound()
        }
        private val observer = Observer<Resource<ResultData>?> { state ->
            val page = (state as? Resource.Success<ResultData>)?.value
            trace("PAGE_OBSERVER", "state=${state?.javaClass?.simpleName} url=${page?.url?.take(100)} actors=${page?.actors?.size ?: 0}")
            if (page == null) return@Observer
            update(page)
        }
        private fun castEnabled(): Boolean =
            PreferenceManager.getDefaultSharedPreferences(appContext).getBoolean(
                appContext.getString(R.string.show_cast_in_details_key), true)

        fun start() {
            active = true
            list.visibility = View.GONE
            castText?.visibility = View.GONE
            trace("BIND_START", "fragment=${fragment.javaClass.name} adapter=${list.adapter?.javaClass?.simpleName}")
            controller.start()
            model.page.observe(fragment.viewLifecycleOwner, observer)
            model.selectedSeasonIndex.observe(fragment.viewLifecycleOwner, seasonIndexObserver)
            model.seasonSelections.observe(fragment.viewLifecycleOwner, seasonChoicesObserver)
            tree = list.viewTreeObserver.also { it.addOnGlobalLayoutListener(layoutListener) }
            (model.page.value as? Resource.Success<ResultData>)?.value?.let(::update)
        }
        private fun update(page: ResultData) {
            val response = runCatching { responseField?.get(model) as? LoadResponse }
                .getOrNull()?.takeIf { it.url == page.url }
            val season = model.selectedSeasonIndex.value?.takeIf { it >= 0 }
                ?.let { model.seasonSelections.value?.getOrNull(it)?.second }
            val multiSeason = (model.seasonSelections.value?.size ?: 0) > 1
            val ids = ids(page, response)
            trace("PAGE_UPDATE", "url=${page.url.take(140)} response=${response != null} responseType=${response?.type} ids=$ids nativeActors=${page.actors?.size ?: 0} enabled=${castEnabled()}")
            val identity = page.url + "|" + ids + "|" + (response?.type ?: "unknown") +
                "|" + (season ?: "pending") + "|" + multiSeason
            if (identity == pageIdentity) {
                trace("PAGE_SAME", "identity=${identity.take(180)} adapterCount=${list.adapter?.itemCount}")
                attachClicks()
                return
            }
            val previousIdentity = pageIdentity
            pageIdentity = identity
            pageUrl = page.url
            trace("PAGE_CHANGED", "old=${previousIdentity?.take(100)} new=${identity.take(180)}")
            loadJob?.cancel()
            restoringCast = false
            people = null
            lastActors = null
            cards.clear()
            val adapter = list.adapter as? ActorAdaptor
            adapter?.submitList(emptyList())
            list.visibility = View.GONE
            castText?.visibility = View.GONE
            if (!castEnabled()) { trace("RESOLVE_SKIPPED", "castDisabled"); return }
            // Episode-based anime need the native season selection before lookup.
            // Waiting prevents an initial season-1 cast from flashing on season 2.
            if (response is TvSeriesLoadResponse &&
                (response.type in setOf(TvType.Anime, TvType.OVA) ||
                    (response.type in setOf(TvType.Others, TvType.Cartoon) &&
                        (ids.anilist != null || ids.mal != null))) && season == null) {
                trace("SEASON_PENDING", "url=${page.url.take(120)}")
                return
            }
            loadJob = main.launch {
                trace("LOAD_JOB_START", "identity=${identity.take(180)}")
                val loaded = resolve(page, response, ids, season, multiSeason)
                // API failure is terminal: restore provider actors as the fallback.
                val fallback = page.actors?.takeIf { it.isNotEmpty() }
                    ?: response?.actors.orEmpty()
                val resolved = loaded ?: CastResult(fallback, null)
                trace("LOAD_JOB_RESULT", "actors=${resolved.actors.size} people=${resolved.people?.size ?: 0} nativeFallback=${loaded == null}")
                if (!fragment.isAdded || pageIdentity != identity ||
                    (model.page.value as? Resource.Success<ResultData>)?.value?.url != page.url ||
                    !ElevateFeaturePrefs.enabled(appContext, ElevateFeature.ACTOR_INFORMATION))
                    run { trace("LOAD_REJECTED", "added=${fragment.isAdded} sameIdentity=${pageIdentity == identity} enabled=${ElevateFeaturePrefs.enabled(appContext, ElevateFeature.ACTOR_INFORMATION)}"); return@launch }
                people = resolved.people
                lastActors = resolved.actors
                (list.adapter as? ActorAdaptor)?.let { adapter ->
                    trace("ADAPTER_SUBMIT", "before=${adapter.itemCount} new=${resolved.actors.size} refs=${resolved.people?.size ?: 0}")
                    adapter.submitList(resolved.actors)
                    list.post {
                        if (list.adapter === adapter && pageIdentity == identity) {
                            trace("ADAPTER_REBIND", "count=${adapter.itemCount} visible=${list.childCount}")
                            adapter.notifyDataSetChanged()
                            attachClicks()
                        }
                    }
                }
                list.visibility = if (castEnabled() && resolved.actors.isNotEmpty())
                    View.VISIBLE else View.GONE
                castText?.visibility = if (castEnabled() && loaded == null &&
                    resolved.actors.isEmpty() && page.actorsText != null)
                    View.VISIBLE else View.GONE
                list.post { attachClicks() }
            }
        }
        fun render() = attachClicks()

        private var lastBindTrace: String? = null
        private fun attachClicks() {
            if (!active || !ElevateFeaturePrefs.enabled(appContext, ElevateFeature.ACTOR_INFORMATION)) return
            val refs = people
            val actors = lastActors
            if (actors == null) {
                if (list.visibility != View.GONE) list.visibility = View.GONE
                if (castText?.visibility != View.GONE) castText?.visibility = View.GONE
            }
            if (!actors.isNullOrEmpty() && castEnabled()) {
                list.visibility = View.VISIBLE
                castText?.visibility = View.GONE
            }
            val adapter = list.adapter as? ActorAdaptor ?: run { trace("CLICK_NO_ADAPTER", "adapter=${list.adapter?.javaClass?.name}"); return }
            if (observedAdapter !== adapter) {
                observedAdapter?.unregisterAdapterDataObserver(dataObserver)
                adapter.registerAdapterDataObserver(dataObserver)
                observedAdapter = adapter
                trace("ADAPTER_OBSERVER", "attached=${adapter.javaClass.simpleName}")
            }
            // Native back navigation can resubmit the original actors without changing the page ID.
            if (!actors.isNullOrEmpty() && castEnabled() &&
                (adapter.itemCount != actors.size || actors.indices.any {
                    adapter.getItemOrNull(it) !== actors[it]
                })) {
                if (!restoringCast) {
                    restoringCast = true
                    trace("CAST_RESTORE", "current=${adapter.itemCount} expected=${actors.size}")
                    adapter.submitList(actors, Runnable {
                        restoringCast = false
                        if (active && list.adapter === adapter) {
                            adapter.notifyDataSetChanged()
                            list.post { attachClicks() }
                        }
                    })
                }
                return
            }
            val signature = "${adapter.itemCount}:${list.childCount}:${refs?.size}:${actors?.size}:${pageIdentity?.hashCode()}"
            if (signature != lastBindTrace) { trace("CLICK_BIND_SCAN", "signature=$signature"); lastBindTrace = signature }
            for (index in 0 until list.childCount) {
                val card = list.getChildAt(index)
                val pos = list.getChildAdapterPosition(card)
                if (pos == RecyclerView.NO_POSITION) continue
                card.setOnLongClickListener(searchLongPress)
                // AniList: leave native click and cast/voice-actor inversion untouched.
                val person = refs?.getOrNull(pos) ?: continue
                val actor = actors?.getOrNull(pos) ?: continue
                if (person.id == null || adapter.getItemOrNull(pos) !== actor) {
                    trace("CLICK_BIND_SKIP", "pos=$pos person=${person.id} sameActor=${adapter.getItemOrNull(pos) === actor}")
                    continue
                }
                cards[card] = actor to person
                card.setOnTouchListener(touch)
                val click = object : View.OnClickListener {
                    override fun onClick(view: View) {
                        trace("CARD_CLICK", "pos=$pos person=${person.id} pending=${pendingAnimation != null} pageUrl=$pageUrl")
                        if (!active || !ElevateFeaturePrefs.enabled(appContext, ElevateFeature.ACTOR_INFORMATION)) return
                        if (pendingAnimation != null || pageUrl !=
                            (model.page.value as? Resource.Success<ResultData>)?.value?.url)
                            return
                        val current = list.getChildAdapterPosition(view)
                        if (current != pos || adapter.getItemOrNull(current) !== actor) {
                            trace("CARD_CLICK_REJECT", "pos=$pos current=$current same=${adapter.getItemOrNull(current) === actor}")
                            return
                        }
                        card.findViewById<View>(R.id.voice_actor_image_holder2)?.let {
                            it.animate().cancel()
                            it.scaleX = 1f
                            it.scaleY = 1f
                        }
                        val holder = list.findViewHolderForAdapterPosition(pos)
                        if (holder is ViewHolderState<*>) {
                            @Suppress("UNCHECKED_CAST")
                            adapter.onUpdateContent(holder as ViewHolderState<Any>, actor, pos)
                            // Core's rebind installs its old click listener.
                            view.setOnClickListener(this)
                            view.setOnLongClickListener(searchLongPress)
                            view.setOnTouchListener(touch)
                        }
                        val running = view.findViewById<View>(
                            R.id.voice_actor_image_holder2)?.animation
                        trace("CARD_CLICK_DISPATCH", "pos=$pos running=${running != null}")
                        if (running == null || running.hasEnded()) controller.click(person, view)
                        else {
                            pendingAnimation = running
                            running.setAnimationListener(object :
                                android.view.animation.Animation.AnimationListener {
                                override fun onAnimationStart(a: android.view.animation.Animation?) = Unit
                                override fun onAnimationRepeat(a: android.view.animation.Animation?) = Unit
                                override fun onAnimationEnd(a: android.view.animation.Animation?) {
                                    if (pendingAnimation !== running) return
                                    pendingAnimation = null
                                    running.setAnimationListener(null)
                                    if (fragment.isAdded && view.isAttachedToWindow &&
                                        list.getChildAdapterPosition(view) == pos &&
                                        adapter.getItemOrNull(pos) === actor)
                                        controller.click(person, view).also { trace("CARD_ANIMATION_DISPATCH", "pos=$pos person=${person.id}") }
                                }
                            })
                        }
                    }
                }
                card.setOnClickListener(click)
                trace("CLICK_BOUND", "pos=$pos person=${person.id}")
                // Long-press always opens the existing in-app CloudStream search.
            }
        }

        private fun RecyclerView.childrenToReset() {
            for (index in 0 until childCount) {
                getChildAt(index).findViewById<View>(
                    R.id.voice_actor_image_holder2)?.let { portrait ->
                    portrait.animate().cancel()
                    portrait.scaleX = 1f
                    portrait.scaleY = 1f
                }
            }
        }
        fun stop() {
            active = false
            restoringCast = false
            trace("BIND_STOP", "fragment=${fragment.javaClass.name} page=${pageUrl?.take(100)}")
            pendingAnimation?.setAnimationListener(null)
            pendingAnimation = null
            list.childrenToReset()
            loadJob?.cancel()
            model.page.removeObserver(observer)
            model.selectedSeasonIndex.removeObserver(seasonIndexObserver)
            model.seasonSelections.removeObserver(seasonChoicesObserver)
            tree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
            tree = null
            observedAdapter?.unregisterAdapterDataObserver(dataObserver)
            observedAdapter = null
            cards.clear()
            controller.stop()
            (list.adapter as? ActorAdaptor)?.let { adapter ->
                val page = (model.page.value as? Resource.Success<ResultData>)?.value
                adapter.submitList(page?.actors.orEmpty())
                list.post {
                    if (list.adapter === adapter) adapter.notifyDataSetChanged()
                }
                list.visibility = if (castEnabled() && !page?.actors.isNullOrEmpty())
                    View.VISIBLE else View.GONE
                castText?.visibility = if (page?.actorsText == null) View.GONE else View.VISIBLE
            }
        }
    }

    fun start() {
        trace("HOOK_START", "feature=${ElevateFeaturePrefs.enabled(appContext, ElevateFeature.ACTOR_INFORMATION)}")
        appContext.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        trace("HOST_ATTACH", "activity=${activity.javaClass.name}")
        detach()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                f.view?.let { attach(f, it) }
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun detach() {
        trace("HOST_DETACH", "bindings=${bindings.size}")
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        bindings.values.toList().forEach { it.stop() }
        bindings.clear()
        host = null
    }

    fun stop() {
        appContext.unregisterActivityLifecycleCallbacks(this)
        detach()
        main.cancel()
        io.cancel()
        synchronized(tmdbCache) { tmdbCache.clear() }
        synchronized(anilistCache) { anilistCache.clear() }
    }
    override fun onActivityResumed(activity: Activity) {
        trace("ACTIVITY_RESUMED", "activity=${activity.javaClass.name}")
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) detach()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
