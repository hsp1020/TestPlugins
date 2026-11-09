package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.widget.AppCompatSpinner
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.result.ResultData
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.SyncViewModel
import kotlinx.coroutines.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Independently owns native detail-page watch history controls for every provider. */
internal class ElevateWatchHistoryHook(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private val probes = mutableMapOf<Fragment, SeasonSyncProbe>()
    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, state: Bundle?
        ) { attach(fragment, view) }

        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            fragment.view?.let { attach(fragment, it) }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            probes.remove(fragment)?.stop()
        }
    }

    private fun isDetail(fragment: Fragment): Boolean {
        var type: Class<*>? = fragment.javaClass
        while (type != null) {
            if (type.name in setOf(
                "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
                "com.lagradost.cloudstream3.ui.result.ResultFragmentTv"
            )) return true
            type = type.superclass
        }
        return false
    }

    private fun attach(fragment: Fragment, root: View) {
        if (!isDetail(fragment) || probes.containsKey(fragment)) return
        val tv = fragment.javaClass.name.endsWith("ResultFragmentTv")
        if (tv && root.findViewById<View>(R.id.result_play_parent) == null) return
        if (!tv && root.findViewById<View>(R.id.result_sync_names) == null) return
        probes[fragment] = SeasonSyncProbe(fragment, root).also { it.start() }
    }

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
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
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        probes.values.toList().forEach { it.stop() }
        probes.clear()
        host = null
    }

    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        detach()
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (activity === host) detach()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private data class SeasonTrackerIds(
        val mal: Int?, val aniList: Int?, val kitsu: Int?,
        val simkl: Int? = null, val tvdb: Int? = null,
        val tvdbSeason: Int? = null, val tmdbSeason: Int? = null,
        val localSimkl: Boolean = false
    )

    private class SeasonSyncProbe(
        private val fragment: Fragment, private val root: View
    ) {
        private companion object {
            val IMDb = Regex("tt\\d+")
            val allowed = setOf("mal", "anilist", "kitsu", "simkl")
            val titles = mapOf("mal" to "MAL", "anilist" to "AniList",
                "kitsu" to "Kitsu", "simkl" to "Simkl")

            val pendingWrites = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        }

        private data class Draft(
            var status: SyncWatchType, var watched: Int, var score: Score?,
            var explicitNone: Boolean = false
        ) {
            fun snapshot() = copy()
        }

        private data class Tracker(
            val prefix: String, val id: String, val repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            var baseline: Draft? = null, var draft: Draft? = null,
            var total: Int? = null, var totalSource: String = "unknown",
            var error: String? = null, var loading: Boolean = true, var saving: Boolean = false,
            var writeBlocked: String? = null, var simklSeason: SimklSeasonSnapshot? = null,
            var simklAnime: AnimeSnapshot? = null, var ratingLoaded: Boolean = true,
            var seasonMapping: SeasonTrackerIds? = null
        )

        private data class SimklSeasonSnapshot(
            val ids: JSONObject,
            val seasonNumber: Int,
            val available: List<Int>,
            val watched: Set<Int>,
            val otherSeasons: Map<Int, Set<Int>>,
            val listStatus: SyncWatchType?
        )

        private fun simklSeasonStatus(snap: SimklSeasonSnapshot): SyncWatchType {
            if (snap.watched.size == snap.available.size) return SyncWatchType.COMPLETED
            val global = snap.listStatus
            if (global == SyncWatchType.ONHOLD || global == SyncWatchType.DROPPED ||
                global == SyncWatchType.PLANTOWATCH || global == SyncWatchType.REWATCHING)
                return global
            return if (snap.watched.isEmpty()) SyncWatchType.NONE
                else SyncWatchType.WATCHING
        }

        private fun simklIds(id: String): JSONObject? {
            val obj = runCatching { JSONObject(id) }.getOrNull() ?: return null
            val ids = JSONObject()
            for ((key, out) in listOf("Imdb" to "imdb", "Simkl" to "simkl",
                "Mal" to "mal", "AniList" to "anilist", "Tvdb" to "tvdb")) {
                val value = obj.optString(key).takeIf { it.isNotBlank() }
                if (value != null) ids.put(out, value)
            }
            return ids.takeIf { it.length() > 0 }
        }

        private suspend fun simklSeasonRead(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            id: String, requested: Int
        ): SimklSeasonSnapshot? {
            val headers = SimklApi.getHeaders(repo.authToken() ?: return null) +
                mapOf("content-type" to "application/json")
            val ids = simklIds(id)
            if (ids == null) {
                return null
            }
            return try {
                val payload = JSONArray().put(ids)
                val response = app.post("https://api.simkl.com/sync/watched?extended=episodes",
                    headers=headers, requestBody=payload.toString().toRequestBody())
                if (response.code !in 200..299) {
                    response.okhttpResponse.close()
                    return null
                }
                val records = JSONArray(response.text)
                val record = (0 until records.length()).mapNotNull { records.optJSONObject(it) }
                    .singleOrNull { row ->
                        ids.keys().asSequence().any { key ->
                            row.optString(key).isNotBlank() &&
                                row.optString(key) == ids.optString(key)
                        }
                    }
                val rows = record?.optJSONArray("seasons")
                val seasons = (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }
                val parsed = linkedMapOf<Int, Pair<List<Int>,Set<Int>>>()
                var malformed = false
                for (part in seasons) {
                    val n = part.optInt("number")
                    val episodes = part.optJSONArray("episodes")
                    if (n <= 0 || episodes == null) { malformed = true; break }
                    val all = mutableListOf<Int>()
                    val viewed = mutableSetOf<Int>()
                    for (i in 0 until episodes.length()) {
                        val episode = episodes.optJSONObject(i)
                        val ep = episode?.optInt("number") ?: 0
                        val watchedFlag = episode?.opt("watched")
                        if (ep <= 0 || watchedFlag !is Boolean) {
                            malformed = true; break
                        }
                        all += ep
                        if (watchedFlag) viewed += ep
                    }
                    if (malformed || all.size != all.distinct().size || parsed.containsKey(n)) {
                        malformed = true; break
                    }
                    parsed[n] = all.sorted() to viewed
                }
                val chosen = parsed[requested]
                // A season without watched history may not appear in /sync/watched.
                // Ask Simkl's native episode constructor for the canonical
                // season catalog. Never synthesize episode numbers from a
                // different provider's displayed episode count.
                val fallback = if (!malformed && chosen == null) {
                    val state = repo.status(id).getOrNull() as? SimklApi.SimklSyncStatus
                    val episodes = state?.episodeConstructor?.getEpisodes()
                        ?.filter { it.season == requested && it.episode > 0 }
                        ?.map { it.episode }
                        ?.sorted()
                    val actualStatus = simklTvLibraryStatus(repo, ids)
                    val safe = state != null && actualStatus != null &&
                        !episodes.isNullOrEmpty() &&
                        episodes.distinct().size == episodes.size &&
                        (record != null || state.watchedEpisodes == 0)
                    if (safe) Pair(episodes!! to emptySet<Int>(), actualStatus!!) else null
                } else null
                val selected = chosen ?: fallback?.first
                val listStatus = record?.takeIf { it.has("list") }
                    ?.let { simklWatchlistStatus(it.opt("list")) } ?: fallback?.second
                val result = if (!malformed && selected != null && selected.first.isNotEmpty())
                    SimklSeasonSnapshot(ids, requested, selected.first, selected.second,
                        parsed.filterKeys { it != requested }.mapValues { it.value.second }, listStatus)
                else null
                result
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                  null
              }
        }

        private suspend fun simklLibraryObject(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            endpoint: String
        ): JSONObject? {
            val headers=SimklApi.getHeaders(repo.authToken() ?: return null)
            val response=try {
                app.get("https://api.simkl.com/sync/all-items/$endpoint",
                    headers=headers,cacheTime=0)
            } catch(e: CancellationException) { throw e }
              catch(e: Exception) { return null }
            if(response.code !in 200..299) { response.okhttpResponse.close(); return null }
            val body=response.text.trim()
            return if(body=="null") JSONObject()
                else runCatching { JSONObject(body) }.getOrNull()
        }

        private suspend fun simklScores(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            targets: List<JSONObject>, anime: Boolean
        ): List<Score?>? {
            if(targets.isEmpty())return null
            val kind=if(anime)"anime" else "shows"
            val root=simklLibraryObject(repo,kind) ?: return null
            val rows=root.optJSONArray(kind) ?: if(root.length()==0) JSONArray() else return null
            val scores=mutableListOf<Score?>()
            for(ids in targets) {
                var found: JSONObject?=null
                for(i in 0 until rows.length()) {
                    val item=rows.optJSONObject(i) ?: return null
                    val rowIds=item.optJSONObject("show")?.optJSONObject("ids")
                        ?: item.optJSONObject("ids") ?: return null
                    if(ids.keys().asSequence().any { key ->
                        ids.optString(key).isNotBlank() &&
                            ids.optString(key)==rowIds.optString(key)
                    }) {
                        if(found!=null)return null
                        found=item
                    }
                }
                val raw=found?.opt("user_rating")
                val rating=when(raw) {
                    null, JSONObject.NULL -> null
                    is Number -> raw.toInt()
                    else -> raw.toString().toIntOrNull() ?: return null
                }
                if(rating!=null && rating !in 1..10)return null
                scores+=rating?.let { Score.from10(it) }
            }
            return scores
        }

        private suspend fun simklSaveScores(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            targets: List<JSONObject>, score: Score?, anime: Boolean
        ): Boolean {
            if(targets.isEmpty())return false
            val value=score?.toInt(10) ?: 0
            if(value !in 0..10)return false
            val headers=SimklApi.getHeaders(repo.authToken() ?: return false)+
                mapOf("content-type" to "application/json")
            val items=JSONArray().also { array ->
                targets.forEach { ids ->
                    val entry=JSONObject().put("ids",ids)
                    if(value>0)entry.put("rating",value)
                    array.put(entry)
                }
            }
            val action=if(value>0)"ratings" else "ratings/remove"
            val response=try {
                app.post("https://api.simkl.com/sync/$action",headers=headers,
                    requestBody=JSONObject().put("shows",items).toString().toRequestBody())
            }catch(e:CancellationException){throw e}
             catch(e:Exception){return false}
            if(response.code !in 200..299) { response.okhttpResponse.close(); return false }
            val ack=runCatching { JSONObject(response.text) }.getOrNull() ?: return false
            val saved=ack.optJSONObject(if(value>0)"added" else "deleted")
                ?.optInt("shows",-1) ?: -1
            val missing=ack.optJSONObject("not_found")
            if(saved!=targets.size ||
                (missing?.optJSONArray("shows")?.length() ?: 0)>0 ||
                (missing?.optJSONArray("anime")?.length() ?: 0)>0)return false
            for(attempt in 1..3) {
                if(attempt>1)kotlinx.coroutines.delay(if(attempt==2)350L else 900L)
                val actual=simklScores(repo,targets,anime)
                if(actual!=null && actual.all { (it?.toInt(10) ?: 0)==value })
                    return true
            }
            return false
        }

        /** Read the authoritative uncached watchlist status, including
         * untracked shows and seasons with no watched episodes. */
        private suspend fun simklTvLibraryStatus(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            ids: JSONObject
        ): SyncWatchType? {
            val root = simklLibraryObject(repo, "shows?extended=ids_only") ?: return null
            val rows = root.optJSONArray("shows") ?: if (root.length() == 0)
                JSONArray() else return null
            var found: SyncWatchType? = null
            for (i in 0 until rows.length()) {
                val entry = rows.optJSONObject(i) ?: return null
                val keys = entry.optJSONObject("show")?.optJSONObject("ids")
                    ?: entry.optJSONObject("ids") ?: return null
                val matches = ids.keys().asSequence().any { key ->
                    ids.optString(key).isNotBlank() &&
                        ids.optString(key) == keys.optString(key)
                }
                if (matches) {
                    if (found != null || !entry.has("status")) return null
                    found = simklWatchlistStatus(entry.opt("status")) ?: return null
                }
            }
            return found ?: SyncWatchType.NONE
        }

        private suspend fun simklTvLibraryMembership(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            ids: JSONObject
        ): Boolean? {
            val root=simklLibraryObject(repo,"shows?extended=ids_only") ?: return null
            val rows=root.optJSONArray("shows") ?: if(root.length()==0) {
                JSONArray()
            } else return null
            for(i in 0 until rows.length()) {
                val entry=rows.optJSONObject(i) ?: return null
                val rowIds=entry.optJSONObject("show")?.optJSONObject("ids")
                    ?: entry.optJSONObject("ids") ?: return null
                if(ids.keys().asSequence().any { k ->
                    ids.optString(k).isNotBlank() && ids.optString(k)==rowIds.optString(k)
                })return true
            }
            return false
        }

        private suspend fun simklSeasonSave(
            tracker: Tracker, draft: Draft, baseline: Draft, sn: Int
        ): Boolean {
            val initial = tracker.simklSeason ?: return false
            if (draft.score != baseline.score || draft.watched !in 0..initial.available.size)
                return false
            val headers = SimklApi.getHeaders(tracker.repo.authToken() ?: return false) +
                mapOf("content-type" to "application/json")
            val url = "https://api.simkl.com/sync/"

            val clearSelectedSeason = draft.status == SyncWatchType.NONE && draft.explicitNone

            val seasonCompleted = draft.status == SyncWatchType.COMPLETED
            val requestedCount = when {
                clearSelectedSeason -> 0
                seasonCompleted -> initial.available.size
                else -> draft.watched
            }
            val countDiff = requestedCount - initial.watched.size
            val autoWatching = baseline.status == SyncWatchType.NONE &&
                draft.status == SyncWatchType.WATCHING && countDiff > 0
            val progressDemotion = draft.status == SyncWatchType.WATCHING &&
                baseline.status == SyncWatchType.COMPLETED && countDiff < 0

            val statusChanged = !clearSelectedSeason && !seasonCompleted &&
                draft.status != baseline.status && !autoWatching &&
                !progressDemotion && initial.listStatus != draft.status
            val fullDelete = ElevateWatchHistoryRules.canRemoveWholeSeries(
                clearSelectedSeason, initial.otherSeasons
            )
            if (statusChanged && (initial.listStatus == null ||
                    simklWatchlistDestination(draft.status) == null)) return false

            if(fullDelete) {

                val fresh=simklSeasonRead(tracker.repo,tracker.id,sn)
                val safe=fresh!=null && fresh.available==initial.available &&
                    fresh.watched==initial.watched &&
                    fresh.otherSeasons==initial.otherSeasons &&
                    fresh.otherSeasons.values.all { it.isEmpty() }
                if(!safe)return false
                val presentBefore=simklTvLibraryMembership(tracker.repo,initial.ids)
                    ?: return false
                if(presentBefore) {
                    val payload=JSONObject().put("shows",JSONArray().put(
                        JSONObject().put("ids",initial.ids)))
                    val response=try {
                        app.post(url+"history/remove",headers=headers,
                            requestBody=payload.toString().toRequestBody())
                    }catch(e:CancellationException){throw e}
                     catch(e:Exception){
                        return false
                     }
                    val ack=runCatching { JSONObject(response.text) }.getOrNull()
                    val deleted=ack?.optJSONObject("deleted")?.optInt("shows",-1) ?: -1
                    val notFound=ack?.optJSONObject("not_found")?.optJSONArray("shows")?.length() ?: 0
                    if(response.code !in 200..299 || deleted<=0 || notFound!=0)return false
                }else if(initial.watched.isNotEmpty() || fresh?.listStatus!=SyncWatchType.NONE) {
                    return false
                }
                // A successful HTTP acknowledgement is not proof that the
                // selected show has actually left the server-side library.
                var removed = false
                for (attempt in 1..3) {
                    if (attempt > 1) kotlinx.coroutines.delay(if (attempt == 2) 350L else 900L)
                    if (simklTvLibraryMembership(tracker.repo, initial.ids) == false) {
                        removed = true
                        break
                    }
                }
                if (!removed) return false
                tracker.simklSeason=fresh!!.copy(watched=emptySet(),listStatus=SyncWatchType.NONE)
                tracker.repo.requireLibraryRefresh=true
                return true
            }

            if (countDiff == 0 && !statusChanged) {

                val current = simklSeasonRead(tracker.repo, tracker.id, sn)
                val unchanged = current != null &&
                    current.available == initial.available &&
                    current.watched == initial.watched &&
                    current.otherSeasons == initial.otherSeasons &&
                    (if (clearSelectedSeason) current.watched.isEmpty()
                     else if (seasonCompleted) current.watched.size == current.available.size
                     else true)
                if (unchanged) tracker.simklSeason = current
                return unchanged
            }

            val fresh = simklSeasonRead(tracker.repo, tracker.id, sn)
            if (fresh == null || fresh.available != initial.available ||
                fresh.watched != initial.watched ||
                fresh.otherSeasons != initial.otherSeasons) {
                return false
            }

            if (countDiff != 0) {
                val delta = ElevateWatchHistoryRules.episodeDelta(
                    initial.available, initial.watched, requestedCount
                ) ?: return false
                val expected = if (countDiff > 0) initial.watched + delta
                    else initial.watched - delta.toSet()
                val scope = JSONObject().put("ids", initial.ids)
                    .put("seasons", JSONArray().put(JSONObject()
                        .put("number", sn).put("episodes", JSONArray().also { arr ->
                            delta.forEach { arr.put(JSONObject().put("number", it)) }
                        })))
                val endpoint = if (countDiff > 0) "history" else "history/remove"
                val response = try {
                    app.post(url + endpoint, headers = headers,
                        requestBody = JSONObject().put("shows", JSONArray().put(scope))
                            .toString().toRequestBody())
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                  return false }
                val missing = runCatching {
                    JSONObject(response.text).optJSONObject("not_found")
                        ?.optJSONArray("shows")?.length() ?: 0
                }.getOrDefault(-1)
                if (response.code !in 200..299 || missing != 0) return false
                var confirmed = false
                for (attempt in 1..3) {
                    if (attempt > 1) kotlinx.coroutines.delay(if (attempt == 2) 350L else 900L)
                    val current = simklSeasonRead(tracker.repo, tracker.id, sn)
                    confirmed = current?.watched == expected &&
                        current.otherSeasons == initial.otherSeasons
                    if (confirmed) {
                        tracker.simklSeason = current
                        break
                    }
                }
                if (!confirmed) return false
            }

            if (statusChanged) {

                val destination = simklWatchlistDestination(draft.status) ?: return false
                val payload = JSONObject().put("shows", JSONArray().put(
                    JSONObject().put("ids", initial.ids).put("to", destination)))
                val response = try {
                    app.post(url + "add-to-list", headers = headers,
                        requestBody = payload.toString().toRequestBody())
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                  return false }
                if (response.code !in 200..299) { response.okhttpResponse.close(); return false }
                val result = runCatching { JSONObject(response.text) }.getOrNull()
                    ?: return false
                if ((result.optJSONObject("not_found")?.optJSONArray("shows")?.length() ?: 0) > 0)
                    return false
                val accepted = result.optJSONObject("added")?.optJSONArray("shows")
                    ?.optJSONObject(0)?.opt("to")?.let { simklWatchlistStatus(it) }
                    ?.takeIf { it != SyncWatchType.NONE } ?: draft.status
                for (attempt in 1..3) {
                    if (attempt > 1) kotlinx.coroutines.delay(if (attempt == 2) 350L else 900L)
                    val current = simklSeasonRead(tracker.repo, tracker.id, sn)
                    if (current?.listStatus == accepted &&
                        current.watched == (tracker.simklSeason ?: initial).watched &&
                        current.otherSeasons == (tracker.simklSeason ?: initial).otherSeasons) {
                        tracker.simklSeason = current
                        tracker.repo.requireLibraryRefresh = true
                        return true
                    }
                }
                return false
            }
            return true
        }

        private data class AnimeSlot(val simkl: Int, val native: Int, val tvdbSeason: Int, val tvdbEpisode: Int) {
            val key get() = "$simkl:$native"
        }
        private data class AnimeSnapshot(
            val tvdbId: Int?, val tvdbSeason: Int?, val mapped: List<AnimeSlot>,
            val watched: Set<String>, val outside: Set<String>,
            val mode: String, val sourceIds: Set<Int>,
            val status: SyncWatchType?, val statusEditable: Boolean
        )

        private fun animeSeasonDisplayStatus(snap: AnimeSnapshot): SyncWatchType {
            val count=snap.watched.size
            if(!snap.statusEditable && snap.status==SyncWatchType.COMPLETED &&
                snap.mapped.isNotEmpty() && count<snap.mapped.size)
                return if(count==0) SyncWatchType.NONE else SyncWatchType.WATCHING
            return snap.status ?: when {
                count==0 -> SyncWatchType.NONE
                count==snap.mapped.size -> SyncWatchType.COMPLETED
                else -> SyncWatchType.WATCHING
            }
        }

        private data class AnimeWatchGrid(
            val watched: Set<String>, val statuses: Map<Int, SyncWatchType>
        )
        private val animeEpisodeCache = mutableMapOf<Int, List<AnimeSlot>>()

        private fun jsonInt(obj: JSONObject?, key: String): Int? =
            obj?.opt(key)?.toString()?.toIntOrNull()?.takeIf { it > 0 }

        private fun simklWatchlistStatus(value: Any?): SyncWatchType? = when (value) {
            null, JSONObject.NULL -> SyncWatchType.NONE
            "watching" -> SyncWatchType.WATCHING
            "plantowatch" -> SyncWatchType.PLANTOWATCH
            "completed" -> SyncWatchType.COMPLETED
            "hold" -> SyncWatchType.ONHOLD
            "dropped" -> SyncWatchType.DROPPED
            else -> null
        }

        private fun simklWatchlistDestination(status: SyncWatchType): String? = when (status) {
            SyncWatchType.WATCHING, SyncWatchType.REWATCHING -> "watching"
            SyncWatchType.PLANTOWATCH -> "plantowatch"
            SyncWatchType.COMPLETED -> "completed"
            SyncWatchType.ONHOLD -> "hold"
            SyncWatchType.DROPPED -> "dropped"
            else -> null
        }

        private suspend fun simklAnimeWatched(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            ids: Set<Int>
        ): AnimeWatchGrid? {
            if(ids.isEmpty())return null
            val headers=SimklApi.getHeaders(repo.authToken() ?: return null)+
                mapOf("content-type" to "application/json")
            val request=JSONArray().also { array ->
                ids.forEach { id ->
                    array.put(JSONObject().put("ids",JSONObject().put("simkl",id)))
                }
            }
            val response=try {
                app.post("https://api.simkl.com/sync/watched?extended=episodes",
                    headers=headers,requestBody=request.toString().toRequestBody())
            }catch(e: CancellationException){throw e}
             catch(e: Exception){
                  return null}
            if(response.code !in 200..299) { response.okhttpResponse.close(); return null }
            val rows=runCatching { JSONArray(response.text) }.getOrNull() ?: return null
            if(rows.length()!=ids.size)return null
            val watched=linkedSetOf<String>()
            val observed=linkedSetOf<String>()
            val returned=linkedSetOf<Int>()
            val statuses=linkedMapOf<Int, SyncWatchType>()
            for(i in 0 until rows.length()){
                val row=rows.optJSONObject(i) ?: return null
                val id=jsonInt(row.optJSONObject("ids"),"simkl") ?: return null
                val rootId=jsonInt(row,"simkl")
                if(id !in ids || (rootId!=null && rootId!=id) || !returned.add(id))return null
                if(!row.has("list"))return null
                statuses[id]=simklWatchlistStatus(row.opt("list")) ?: return null
                val seasons=row.optJSONArray("seasons") ?: return null
                for(j in 0 until seasons.length()){
                    val part=seasons.optJSONObject(j) ?: return null
                    val episodes=part.optJSONArray("episodes") ?: return null
                    if(jsonInt(part,"number")==null)return null
                    for(k in 0 until episodes.length()){
                        val ep=episodes.optJSONObject(k) ?: return null
                        val number=jsonInt(ep,"number") ?: return null
                        val flag=ep.opt("watched")
                        if(flag !is Boolean || !observed.add("$id:$number"))return null
                        if(flag)watched+="$id:$number"
                    }
                }
            }
            return AnimeWatchGrid(watched,statuses).takeIf { returned==ids }
        }

        private suspend fun simklAnimeSetStatus(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            ids: Set<Int>, requested: SyncWatchType
        ): SyncWatchType? {
            val destination=simklWatchlistDestination(requested) ?: return null
            if(ids.isEmpty())return null
            val headers=SimklApi.getHeaders(repo.authToken() ?: return null)+
                mapOf("content-type" to "application/json")

            val body=JSONObject().put("shows",JSONArray().also { items ->
                ids.forEach { id ->
                    items.put(JSONObject().put("to",destination)
                        .put("ids",JSONObject().put("simkl",id)))
                }
            })
            val response=try {
                app.post("https://api.simkl.com/sync/add-to-list",
                    headers=headers,requestBody=body.toString().toRequestBody())
            }catch(e: CancellationException){throw e}
             catch(e: Exception){
                  return null}
            if(response.code !in 200..299) { response.okhttpResponse.close(); return null }
            val result=runCatching { JSONObject(response.text) }.getOrNull() ?: return null
            val notFound=result.optJSONObject("not_found")
            if((notFound?.optJSONArray("anime")?.length() ?: 0)>0 ||
               (notFound?.optJSONArray("shows")?.length() ?: 0)>0 ||
               (notFound?.optJSONArray("movies")?.length() ?: 0)>0)return null
            val added=result.optJSONObject("added") ?: return null
            val echoed=linkedSetOf<Int>()
            val actual=linkedSetOf<SyncWatchType>()
            for(group in listOf("shows","anime")){
                val rows=added.optJSONArray(group) ?: continue
                for(i in 0 until rows.length()){
                    val item=rows.optJSONObject(i) ?: return null
                    val id=jsonInt(item.optJSONObject("ids"),"simkl") ?: return null
                    if(id !in ids || !echoed.add(id))return null
                    val state=simklWatchlistStatus(item.opt("to")) ?: return null
                    if(state==SyncWatchType.NONE)return null
                    actual+=state
                }
            }
            val stored=actual.singleOrNull()?.takeIf { echoed==ids }
            if(stored!=requested) {
                return null
            }
            return stored
        }

        private suspend fun simklAnimeLibraryStatus(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            ids: Set<Int>
        ): SyncWatchType? {
            if(ids.isEmpty())return null
            val body=simklLibraryObject(repo,"anime") ?: return null

            val anime=body.optJSONArray("anime") ?: if(!body.has("anime") &&
                body.keys().asSequence().all { it in setOf("shows","movies") })
                JSONArray() else return null
            val statuses=linkedMapOf<Int, SyncWatchType>()
            for(i in 0 until anime.length()) {
                val item=anime.optJSONObject(i) ?: continue
                val id=jsonInt(item.optJSONObject("show")?.optJSONObject("ids"),"simkl")
                    ?: continue
                if(id !in ids)continue
                val status=simklWatchlistStatus(item.opt("status")) ?: return null
                if(status==SyncWatchType.NONE || statuses.put(id,status)!=null)return null
            }
            if(statuses.isEmpty())return SyncWatchType.NONE
            return ids.map { statuses[it] ?: return null }.distinct().singleOrNull()
        }

        private suspend fun simklAnimeRemoveFromLibrary(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            ids: Set<Int>
        ): Boolean {
            if(ids.isEmpty())return false
            val headers=SimklApi.getHeaders(repo.authToken() ?: return false)+
                mapOf("content-type" to "application/json")
            val payload=JSONObject().put("shows",JSONArray().also { items ->
                ids.forEach { id ->
                    items.put(JSONObject().put("ids",JSONObject().put("simkl",id)))
                }
            })
            val response=try {
                app.post("https://api.simkl.com/sync/history/remove",
                    headers=headers,requestBody=payload.toString().toRequestBody())
            }catch(e:CancellationException){throw e}
             catch(e:Exception) {
                return false
             }
            if(response.code !in 200..299) { response.okhttpResponse.close(); return false }
            val ack=runCatching { JSONObject(response.text) }.getOrNull() ?: return false

            val deleted=ack.optJSONObject("deleted")?.optInt("shows",-1) ?: -1
            val notFound=ack.optJSONObject("not_found")
            val missing=(notFound?.optJSONArray("shows")?.length() ?: 0)+
                (notFound?.optJSONArray("anime")?.length() ?: 0)
            return deleted==ids.size && missing==0
        }

        private suspend fun simklAnimeRead(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            mapped: SeasonTrackerIds, targetSeason: Int
        ): AnimeSnapshot? {
            val auth = repo.authToken()
            val headers = SimklApi.getHeaders(auth ?: return null)
            val candidates = linkedSetOf<Int>()
            val tvdbSearchMatches = linkedSetOf<Int>()
            val malSearchMatches = linkedSetOf<Int>()
            mapped.simkl?.let { candidates += it }
            suspend fun search(field: String, value: Int) {
                try {
                    val resp=app.get("https://api.simkl.com/search/id",
                        params=mapOf(field to value.toString()),headers=headers,cacheTime=0)
                    val array=if (resp.code in 200..299)
                        runCatching { JSONArray(resp.text) }.getOrNull()
                        else { resp.okhttpResponse.close(); null }
                    val found=mutableListOf<Int>()
                    for(i in 0 until (array?.length() ?: 0)){
                        val item=array?.optJSONObject(i)
                        val ids=item?.optJSONObject("ids")
                            ?: item?.optJSONObject("anime")?.optJSONObject("ids")
                        val id=jsonInt(ids,"simkl")
                        if (id != null) found+=id
                    }
                    candidates.addAll(found.take(24))
                    if (field=="tvdb")tvdbSearchMatches.addAll(found.take(24))
                    if (field=="mal")malSearchMatches.addAll(found.take(24))
                }catch(e: CancellationException){throw e}
                 catch(e: Exception){
                 }
            }

            mapped.mal?.let { search("mal",it) }
            mapped.tvdb?.let { search("tvdb",it) }
            if(candidates.isEmpty()){
                return null
            }
            val tvdbSeason=mapped.tvdbSeason
            val tvdbId=mapped.tvdb
            val slots=mutableListOf<AnimeSlot>()
            val validated=linkedSetOf<Int>()
            for(id in candidates.take(24)){
                try{
                    val detail=app.get("https://api.simkl.com/anime/$id",
                        headers=headers,cacheTime=600)
                    if(detail.code !in 200..299){
                        detail.okhttpResponse.close()
                        continue
                    }
                    val info=runCatching { JSONObject(detail.text) }.getOrNull()
                    if(info == null){
                        continue
                    }
                    val ids=info.optJSONObject("ids")
                    if(ids == null){
                        continue
                    }
                    if(jsonInt(ids,"simkl") != id){
                        continue
                    }

                    val malMatched=mapped.mal != null &&
                        jsonInt(ids,"mal")==mapped.mal
                    val direct=mapped.simkl==id
                    val row=animeEpisodeCache[id] ?: run {
                        val list=app.get("https://api.simkl.com/anime/episodes/$id",
                            headers=headers,cacheTime=600)
                        if(list.code !in 200..299) {
                            list.okhttpResponse.close()
                            throw IllegalStateException("episodes-http-${list.code}")
                        }
                        val array=JSONArray(list.text)
                        val found=(0 until array.length()).mapNotNull { i ->
                            val ep=array.optJSONObject(i)
                            val native=jsonInt(ep,"episode") ?: return@mapNotNull null
                            if(ep?.optString("type")=="special")return@mapNotNull null
                            val tvdb=ep?.optJSONObject("tvdb")
                            val season=jsonInt(tvdb,"season") ?: 0
                            val episode=jsonInt(tvdb,"episode") ?: 0
                            AnimeSlot(id,native,season,episode)
                        }
                        if(found.map { it.native }.distinct().size != found.size)
                            throw IllegalStateException("duplicate-native-episodes")
                        animeEpisodeCache[id]=found
                        found
                    }
                    if(tvdbId!=null && tvdbSeason!=null){
                        val correctParent=jsonInt(ids,"tvdb")==tvdbId ||
                            id in tvdbSearchMatches || (direct && malMatched)
                        if(!correctParent){
                            continue
                        }
                        val subset=row.filter { it.tvdbSeason == tvdbSeason && it.tvdbEpisode > 0 }
                        if(subset.isNotEmpty()){
                            slots+=subset
                            validated+=id
                        }
                    } else if (candidates.size == 1 &&
                        ((malMatched && (direct || id in malSearchMatches)) ||
                        (mapped.localSimkl && direct))) {
                        slots+=row.map { it.copy(tvdbEpisode=it.native) }
                        validated+=id
                    }
                }catch(e: CancellationException){throw e}
                 catch(e: Exception){
                 }
            }
            val sorted=slots.sortedWith(compareBy<AnimeSlot> { it.tvdbEpisode }.thenBy { it.simkl })
            val keys=sorted.map { it.tvdbEpisode }

            if(sorted.isEmpty() || keys.distinct().size != keys.size){
                return null
            }
            val grid=simklAnimeWatched(repo,validated) ?: return null
            val targetKeys=sorted.mapTo(linkedSetOf()) { it.key }
            val unifiedStatus=grid.statuses.values.distinct().singleOrNull()

            val seasonLocalTitles=validated.all { id ->
                val episodes=animeEpisodeCache[id].orEmpty()
                episodes.isNotEmpty() && (tvdbSeason==null ||
                    episodes.all { it.tvdbSeason==tvdbSeason })
            }
            val result=AnimeSnapshot(tvdbId,tvdbSeason,sorted,
                grid.watched.intersect(targetKeys),grid.watched-targetKeys,
                if(tvdbId!=null && tvdbSeason!=null)"tvdb" else "native",validated,
                unifiedStatus,unifiedStatus!=null && seasonLocalTitles)
            return result
        }

        private suspend fun simklAnimeSave(tracker: Tracker, draft: Draft,
            initial: Draft, seasonNumber: Int): Boolean {
            val before=tracker.simklAnime ?: return false
            if(draft.status==SyncWatchType.NONE && draft.explicitNone) {
                // Removing one anime entry can erase episodes beyond the
                // selected season when Simkl combines several season slots.
                if (!before.statusEditable || before.outside.isNotEmpty()) return false
                val current = simklAnimeLibraryStatus(tracker.repo, before.sourceIds)
                    ?: return false
                if (current == SyncWatchType.NONE && before.watched.isEmpty()) return true
                if (!simklAnimeRemoveFromLibrary(tracker.repo, before.sourceIds)) return false
                var confirmed = false
                for (attempt in 1..3) {
                    if (attempt > 1) kotlinx.coroutines.delay(if (attempt == 2) 350L else 900L)
                    if (simklAnimeLibraryStatus(tracker.repo, before.sourceIds) ==
                        SyncWatchType.NONE) {
                        confirmed = true
                        break
                    }
                }
                if (confirmed) tracker.simklAnime = before.copy(
                    watched=emptySet(), outside=emptySet(), status=SyncWatchType.NONE)
                return confirmed
            }
            if(draft.score!=initial.score)return false
            if(draft.watched !in 0..before.mapped.size)return false
            val diff=draft.watched-before.watched.size
            val autoWatching=initial.status==SyncWatchType.NONE &&
                draft.status==SyncWatchType.WATCHING && diff>0
            val progressDemotion=!before.statusEditable &&
                initial.status==SyncWatchType.COMPLETED &&
                draft.status==SyncWatchType.WATCHING && diff<0
            val statusChanged=draft.status!=initial.status && !autoWatching && !progressDemotion
            if(statusChanged && (!before.statusEditable ||
                simklWatchlistDestination(draft.status)==null))return false
            if(diff==0 && !statusChanged)return true

            val expected=if(diff>0) {
                val changes=before.mapped.filterNot { it.key in before.watched }.take(diff)
                if(changes.size!=diff)return false
                before.watched+changes.map { it.key }
            } else if(diff<0) {
                val changes=before.mapped.filter { it.key in before.watched }.takeLast(-diff)
                if(changes.size!=-diff)return false
                before.watched-changes.map { it.key }.toSet()
            } else before.watched

            if(diff!=0) {
                val changes=if(diff>0)
                    before.mapped.filterNot { it.key in before.watched }.take(diff)
                else before.mapped.filter { it.key in before.watched }.takeLast(-diff)
                val body=if(before.mode=="tvdb"){
                    val episodes=JSONArray().also { a ->
                        changes.forEach { a.put(JSONObject().put("number",it.tvdbEpisode)) }
                    }
                    JSONObject().put("shows",JSONArray().put(JSONObject()
                        .put("ids",JSONObject().put("tvdb",before.tvdbId))
                        .put("use_tvdb_anime_seasons",true)
                        .put("seasons",JSONArray().put(JSONObject()
                            .put("number",before.tvdbSeason)
                            .put("episodes",episodes)))))
                }else{
                    val id=before.sourceIds.singleOrNull() ?: return false
                    JSONObject().put("anime",JSONArray().put(JSONObject()
                        .put("ids",JSONObject().put("simkl",id))
                        .put("episodes",JSONArray().also { a ->
                            changes.forEach { a.put(JSONObject().put("number",it.native)) }
                        })))
                }
                val endpoint=if(diff>0)"history" else "history/remove"
                val headers=SimklApi.getHeaders(tracker.repo.authToken() ?: return false)+
                    mapOf("content-type" to "application/json")
                val response=try {
                    app.post("https://api.simkl.com/sync/$endpoint",
                        headers=headers,requestBody=body.toString().toRequestBody())
                }catch(e: CancellationException){throw e}
                 catch(e: Exception){
                  return false}
                val failures=runCatching {
                    val obj=JSONObject(response.text)
                    val notFound=obj.optJSONObject("not_found")
                    (notFound?.optJSONArray("shows")?.length() ?: 0)+
                        (notFound?.optJSONArray("anime")?.length() ?: 0)
                }.getOrNull()
                if(response.code !in 200..299 || failures==null || failures>0)return false
            }

            val acceptedStatus=if(statusChanged)
                simklAnimeSetStatus(tracker.repo,before.sourceIds,draft.status)
                    ?: return false
            else null

            if(statusChanged && diff==0) {
                for(attempt in 1..3) {
                    if(attempt>1)kotlinx.coroutines.delay(if(attempt==2)350L else 900L)
                    val stored=simklAnimeLibraryStatus(tracker.repo,before.sourceIds)
                    if(stored!=null && stored==acceptedStatus) {
                        tracker.simklAnime=before.copy(status=stored)
                        return true
                    }
                }
                return false
            }

            for(attempt in 1..3) {
                if(attempt>1)kotlinx.coroutines.delay(if(attempt==2)350L else 900L)
                val mapped=tracker.seasonMapping ?: return false
                val after=simklAnimeRead(tracker.repo,mapped,seasonNumber)

                val watchedMatches=diff==0 && statusChanged ||
                    after?.watched==expected
                val outsidePreserved=after?.outside==before.outside
                val statusMatches=!statusChanged || after?.status==acceptedStatus
                if(after!=null && watchedMatches && outsidePreserved && statusMatches) {
                    tracker.simklAnime=after
                    return true
                }
            }
            return false
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val writeJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
        private val observers = mutableListOf<() -> Unit>()
        private var active = false
        private var generation = 0L
        private var pageUrl: String? = null
        private val resultModel by lazy { ViewModelProvider(fragment)[ResultViewModel2::class.java] }
        private val responseField = runCatching {
            ResultViewModel2::class.java.getDeclaredField("currentResponse")
                .apply { isAccessible = true }
        }.getOrNull()
        private var mediaIds = ElevateMediaIdentity.Ids()
        private var animePage = false
        private var episodeBased = true
        private var rawSyncs: Map<String,String> = emptyMap()
        private var seasonEpisodeIds: Map<Int, Map<String, String>> = emptyMap()
        private var responseSeasons: Set<Int> = emptySet()
        private var seasonNumbers: List<Int> = emptyList()
        private var selectionIndex: Int? = null
        private var season: Int? = null
        private data class SeasonInput(
            val season: Int, val options: List<Int>, val sync: Map<String, String>,
            val identity: ElevateMediaIdentity.Ids, val episodeIds: Map<String, String>
        )
        private var resolvedSeasonInputs: SeasonInput? = null
        private var startedFor: String? = null
        private var selected: String? = null
        private var preferred: String? = null
        private var statuses = linkedMapOf<String, Tracker>()
        private var fetchJob: Job? = null
        private var applying = false
        private var combo: AppCompatSpinner? = null
        private var comboContainer: View? = null
        private var oldNames: TextView? = null
        private var syncModel: SyncViewModel? = null
        private val isTv = fragment.javaClass.name.endsWith("ResultFragmentTv")
        private val tvUi: ElevateWatchHistoryTvUi? by lazy {
            if (!isTv) null else ElevateWatchHistoryTvUi(
                fragment, root,
                onProvider = { key ->
                    if (key in statuses && selected != key) {
                        selected = key
                        preferred = key
                        render()
                    }
                },
                onStatus = { status -> changeStatus(status) },
                onEpisodes = { delta -> adjust(delta) },
                onScore = { delta -> adjustScore(delta) },
                onSave = { save() }
            )
        }
        private val saveButton by lazy { root.findViewById<View>(R.id.result_sync_set_score) }
        private val minus by lazy { root.findViewById<View>(R.id.result_sync_sub_episode) }
        private val plus by lazy { root.findViewById<View>(R.id.result_sync_add_episode) }
        private val current by lazy { root.findViewById<android.widget.EditText>(R.id.result_sync_current_episodes) }
        private val maximum by lazy { root.findViewById<TextView>(R.id.result_sync_max_episodes) }
        private val bar by lazy { root.findViewById<android.widget.ProgressBar>(R.id.result_sync_episodes) }
        private val slider by lazy { root.findViewById<com.google.android.material.slider.Slider>(R.id.result_sync_rating) }
        private val scoreLabel by lazy { root.findViewById<TextView>(R.id.result_sync_score_text) }
        private val checks by lazy { root.findViewById<ListView>(R.id.result_sync_check) }
        private val holder by lazy { root.findViewById<View>(R.id.result_sync_holder) }
        private val shimmer by lazy { root.findViewById<View>(R.id.result_sync_loading_shimmer) }
        private fun valid(g: Long, n: Int?): Boolean =
            active && generation == g && season == n

        private fun isWriteInFlight(tracker: Tracker): Boolean =
            tracker.saving || pendingWrites.contains("${tracker.prefix}:${tracker.id}")

        private suspend fun refreshAfterVerifiedWrite(tracker: Tracker, provider: String) {
            val repo = tracker.repo
            repo.requireLibraryRefresh = true
            if (provider == "simkl") {
                repo.authUser()?.let { account ->
                    val key = account.id.toString()
                    removeKey(SimklApi.SIMKL_CACHED_LIST_TIME, key)
                    removeKey(SimklApi.SIMKL_CACHED_LIST, key)
                }
            }
        }

        private fun <T> observe(
            data: androidx.lifecycle.LiveData<T>, owner: androidx.lifecycle.LifecycleOwner,
            handler: (T) -> Unit
        ) {
            val observer = Observer<T> { if (active) handler(it) }
            data.observe(owner, observer)
            observers += { data.removeObserver(observer) }
        }

        fun start() {
            if (active) return
            active = true
            val vm = ViewModelProvider(fragment)[ResultViewModel2::class.java]
            syncModel = if (fragment is com.lagradost.cloudstream3.ui.result.ResultFragmentPhone)
                ViewModelProvider(fragment)[SyncViewModel::class.java] else null
            val owner = fragment.viewLifecycleOwner
            observe(vm.page, owner) { value ->
                when(value) {
                    is Resource.Success -> {
                        val data = value.value
                        if (pageUrl != data.url) {
                            generation++
                            pageUrl = data.url
                            updateMetadata(data)
                            seasonNumbers = emptyList()
                            selectionIndex = null
                            season = null
                            resolvedSeasonInputs = null
                            reset()
                        } else {
                            updateMetadata(data)
                        }
                        resolve()
                    }
                    is Resource.Failure -> {}
                    else -> {}
                }
            }
            observe(vm.seasonSelections, owner) { items ->
                seasonNumbers = items.map { it.second }
                resolve()
            }
            observe(vm.selectedSeasonIndex, owner) { index ->
                selectionIndex = index
                resolve()
            }
            syncModel?.let { model ->
                observe(model.userData, owner) {
                    if (combo != null) {
                        root.post { if (active) render() }
                    }
                }
                observe(model.metadata, owner) { result ->
                    if (combo != null) {
                        root.post { if (active) render() }
                    }
                }
                observe(model.synced, owner) {
                    if (combo != null) root.post { if (active) render() }
                }
            }
        }


        private fun updateMetadata(page: ResultData) {
            val response = runCatching { responseField?.get(resultModel) as? LoadResponse }
                .getOrNull()?.takeIf { it.url == page.url }
            mediaIds = ElevateMediaIdentity.collect(response,
                extraSync = page.syncData, urls = listOf(page.url))
            episodeBased = response?.type !in setOf(TvType.Movie, TvType.AnimeMovie) &&
                !page.url.contains("/meta/movie/", ignoreCase = true)
            // Episode data is season-local. A page-level MAL/Kitsu ID is not
            // automatically valid for every season in a multi-season title.
            val found = linkedMapOf<Int, MutableMap<String, MutableSet<String>>>()
            val episodes = (response as? TvSeriesLoadResponse)?.episodes.orEmpty()
            responseSeasons = episodes.mapNotNull { it.season ?: 1 }
                .filter { it > 0 }.toSet()
            episodes.forEach { episode ->
                val seasonNumber = episode.season ?: 1
                if (seasonNumber <= 0) return@forEach
                val ids = ElevateMediaIdentity.collect(episodeData = episode.data,
                    urls = listOf(episode.data))
                val matches = found.getOrPut(seasonNumber) { linkedMapOf() }
                ids.asMap().filterKeys { it in allowed }.forEach { (key, value) ->
                    matches.getOrPut(key) { linkedSetOf() }.add(value)
                }
            }
            seasonEpisodeIds = found.mapValues { (_, values) ->
                values.mapNotNull { (key, ids) ->
                    ids.singleOrNull()?.let { key to it }
                }.toMap()
            }
            animePage = ElevateMediaIdentity.anime(response, null, mediaIds) ||
                seasonEpisodeIds.values.any { ids ->
                    ids.keys.any { it == "mal" || it == "anilist" || it == "kitsu" }
                }
            rawSyncs = LinkedHashMap(page.syncData).apply {
                mediaIds.asMap().forEach { (key, value) ->
                    if (!containsKey(key)) {
                        put(key, if (key == "simkl") JSONObject().put("Simkl", value).toString()
                            else value)
                    }
                }
            }
        }

        private fun isSpecialSeason(season: Int): Boolean =
            resultModel.seasonSelections.value?.any { choice ->
                choice.second == season &&
                    ElevateWatchHistoryRules.specialSeasonLabel(choice.first.toString())
            } == true

        private fun resolve() {
            val url = pageUrl ?: return
            // The core posts ResultData before seasonSelections and the selected
            // season index. Starting a provisional season 1 at that point treats
            // a multi-season anime as a single-season one. When the real season
            // list arrives, the old season-only guard prevents ID remapping and
            // MAL/AniList/Kitsu tracker setup until the user changes seasons.
            val chosen = ElevateWatchHistoryRules.selectedSeason(
                seasonNumbers, selectionIndex, episodeBased
            ) ?: if (seasonNumbers.isEmpty() && episodeBased)
                responseSeasons.singleOrNull() else null
            if (chosen == null || chosen <= 0) return
            val inputs = SeasonInput(chosen, seasonNumbers.toList(), rawSyncs.toMap(),
                mediaIds, seasonEpisodeIds[chosen].orEmpty())
            if (inputs == resolvedSeasonInputs) return
            resolvedSeasonInputs = inputs
            season = chosen
            generation++
            begin()
        }

        private fun reset() {
            fetchJob?.cancel()
            fetchJob = null
            statuses.clear()
            selected = null
            startedFor = null
            if (active) render()
        }

        private fun begin() {
            val url = pageUrl ?: return
            val sn = season ?: return
            val initialIds = mediaIds
            val initialSync = rawSyncs
            val initialAnime = animePage
            val multi = maxOf(seasonNumbers.size, responseSeasons.size) > 1
            // Explicit episode IDs or an independent single-season URL can
            // identify Kitsu/MAL/AniList even when IMDb is entirely absent.
            val localIds = ElevateWatchHistoryRules.seasonIdsFor(
                sn, seasonEpisodeIds
            ).ifEmpty {
                if (initialAnime && !multi)
                    ElevateWatchHistoryRules.originalSeasonId(initialIds.original, true)
                else emptyMap()
            }
            val movie = !episodeBased
            reset()
            val gen = generation
            // Do not inflate the custom spinner during the result-page
            // transition. Create it only if a matched sync provider exists.
            fetchJob = scope.launch {
                // A Simkl-only work may be anime even when the provider uses TvSeries.
                // Resolve its segment and IDs through Elevate's shared identity cache.
                val effectiveIds = if (initialIds.simkl != null &&
                    (!initialAnime || initialIds.imdb == null)) {
                    try { withContext(Dispatchers.IO) {
                        ElevateMediaIdentity.enrichSimkl(initialIds, movie)
                    } } catch (e: CancellationException) { throw e }
                      catch (_: Exception) { initialIds }
                } else initialIds
                if (!valid(gen, sn)) return@launch
                val isAnime = initialAnime || effectiveIds.simklAnime
                val seasonAwareAnime = isAnime && multi
                val syncs = LinkedHashMap(initialSync).apply {
                    effectiveIds.asMap().forEach { (key, value) ->
                        if (!containsKey(key)) {
                            put(key, if (key == "simkl")
                                JSONObject().put("Simkl", value).toString() else value)
                        }
                    }
                }
                if (!isAnime && !syncs.containsKey("simkl")) {
                    effectiveIds.imdb?.let { imdb ->
                        syncs["simkl"] = JSONObject().put("Imdb", imdb).toString()
                    }
                }
                val mode = ElevateWatchHistoryRules.mode(movie, isAnime,
                    maxOf(seasonNumbers.size, responseSeasons.size))
                val generalSimkl = mode ==
                    ElevateWatchHistoryRules.MediaMode.GENERAL_SERIES &&
                    syncs.containsKey("simkl")
                if (mode == ElevateWatchHistoryRules.MediaMode.GENERAL_SERIES &&
                    !generalSimkl) {
                    teardownUi()
                    return@launch
                }
                var mappedSeason: SeasonTrackerIds? = null
                val ids = if (mode == ElevateWatchHistoryRules.MediaMode.MOVIE ||
                    (mode == ElevateWatchHistoryRules.MediaMode.SINGLE_ANIME &&
                     localIds.isEmpty())) {
                    syncs.filterKeys { it in allowed }
                } else if (mode == ElevateWatchHistoryRules.MediaMode.SINGLE_ANIME) {
                    // An independent one-season provider page may expose an
                    // original Kitsu/MAL ID that differs from the page-wide
                    // syncData. Use that season-local identity, not season 1.
                    val mapped = try { withContext(Dispatchers.IO) {
                        val original = listOf("mal", "anilist", "kitsu")
                            .firstNotNullOfOrNull { prefix ->
                                localIds[prefix]?.let { prefix to it }
                            }
                        original?.let { (prefix, value) ->
                            ElevateAnimeIdResolver.resolveTrackingSource(prefix, value)
                        }
                    } } catch (e: CancellationException) { throw e }
                      catch (_: Exception) { null }
                    val candidates = linkedMapOf<String, String>().apply {
                        mapped?.mal?.let { put("mal", it) }
                        mapped?.anilist?.let { put("anilist", it) }
                        mapped?.kitsu?.let { put("kitsu", it) }
                        mapped?.simkl?.let { put("simkl", it) }
                    }
                    val accepted = candidates
                    linkedMapOf<String, String>().apply {
                        accepted.forEach { (key, value) ->
                            put(key, if (key == "simkl")
                                JSONObject().put("Simkl", value).toString() else value)
                        }
                        localIds.forEach { (key, value) ->
                            putIfAbsent(key, if (key == "simkl")
                                JSONObject().put("Simkl", value).toString() else value)
                        }
                        if (!containsKey("simkl")) {
                            get("mal")?.let { mal ->
                                put("simkl", JSONObject().put("Mal", mal).toString())
                            }
                        }
                    }
                } else if (generalSimkl) syncs.filterKeys { it == "simkl" } else {
                    if (isSpecialSeason(sn)) emptyMap() else {
                        val imdb = effectiveIds.imdb ?: IMDb.find(url)?.value
                        val mapping = try { withContext(Dispatchers.IO) {
                            val byImdb = imdb?.let {
                                ElevateAnimeIdResolver.resolveTrackingImdbSeason(it, sn)
                                    ?.takeIf { mapping -> mapping.matchesSeason(sn) }
                            }
                            if (byImdb != null) byImdb else {
                                // A single source ID can be mapped only when the AIO
                                // response explicitly identifies the selected season.
                                val candidates = listOf("mal", "anilist", "kitsu")
                                var verified: ElevateAnimeIdResolver.Mapping? = null
                                for (prefix in candidates) {
                                    val value = localIds[prefix]
                                        ?: effectiveIds.asMap()[prefix]
                                        ?: continue
                                    val mapped = ElevateAnimeIdResolver.resolveTrackingSource(prefix, value)
                                    if (mapped != null) {
                                        verified = mapped
                                        break
                                    }
                                }
                                verified
                            }
                        } } catch (e: CancellationException) { throw e }
                          catch (_: Exception) { null }
                        val verified = mapping
                        mappedSeason = SeasonTrackerIds(
                            verified?.mal?.toIntOrNull() ?: localIds["mal"]?.toIntOrNull(),
                            verified?.anilist?.toIntOrNull() ?: localIds["anilist"]?.toIntOrNull(),
                            verified?.kitsu?.toIntOrNull() ?: localIds["kitsu"]?.toIntOrNull(),
                            verified?.simkl?.toIntOrNull() ?: localIds["simkl"]?.toIntOrNull(),
                            verified?.tvdb?.toIntOrNull(), verified?.tvdbSeason,
                            verified?.tmdbSeason,
                            localSimkl = localIds["simkl"] != null)
                        linkedMapOf<String, String>().apply {
                            // Explicit episode IDs can identify independent seasons
                            // even if AIOStreams is temporarily unavailable.
                            verified?.mal?.let { put("mal", it) }
                            verified?.anilist?.let { put("anilist", it) }
                            verified?.kitsu?.let { put("kitsu", it) }
                            localIds.forEach { (prefix, value) ->
                                if (prefix == "simkl") {
                                    putIfAbsent("simkl", JSONObject().put("Simkl", value).toString())
                                } else if (prefix in allowed) putIfAbsent(prefix, value)
                            }
                            if (verified?.simkl != null) {
                                put("simkl", JSONObject().put("Simkl", verified.simkl).toString())
                            } else if (!containsKey("simkl")) {
                                mappedSeason?.mal?.let { mal ->
                                    put("simkl", JSONObject().put("Mal", mal.toString()).toString())
                                }
                            }
                        }
                    }
                }
                if (!valid(gen, sn)) return@launch
                val matched = AccountManager.syncApis.filter { ids.containsKey(it.idPrefix) }
                    .filter { it.authUser() != null }
                if (episodeBased && seasonNumbers.isEmpty() &&
                    responseSeasons.singleOrNull() != sn) {
                    teardownUi()
                    return@launch
                }
                if (matched.isEmpty()) {
                    teardownUi()
                    return@launch
                }
                statuses = linkedMapOf<String,Tracker>().apply {
                    matched.forEach { repo ->
                        put(repo.idPrefix, Tracker(repo.idPrefix, ids.getValue(repo.idPrefix), repo))
                    }
                }
                selected = preferred?.takeIf { it in statuses } ?: statuses.keys.firstOrNull()
                updateOptions()
                render()
                val simklSeasonReplies =
                    java.util.concurrent.ConcurrentHashMap<String, SimklSeasonSnapshot>()
                val simklAnimeReplies =
                    java.util.concurrent.ConcurrentHashMap<String, AnimeSnapshot>()
                val ratingLoaded = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
                val replies = matched.map { repo ->
                    async(Dispatchers.IO) {
                        val id = ids.getValue(repo.idPrefix)
                        val state = repo.status(id)

                        val meta = if (repo.idPrefix == "mal" || repo.idPrefix == "kitsu")
                            null else repo.load(id)
                        val remote = state.getOrNull()
                        val simklExact = if (generalSimkl && repo.idPrefix == "simkl")
                            simklSeasonRead(repo, id, sn) else null
                        if (generalSimkl && repo.idPrefix == "simkl" && simklExact != null)
                            simklSeasonReplies["simkl"] = simklExact
                        val animeExact = if (seasonAwareAnime &&
                            repo.idPrefix == "simkl" && mappedSeason != null) {
                            simklAnimeRead(repo, mappedSeason!!, sn)
                        } else null
                        if (seasonAwareAnime && repo.idPrefix == "simkl" &&
                            animeExact != null) simklAnimeReplies["simkl"] = animeExact
                        var total = when {
                            generalSimkl && repo.idPrefix == "simkl" -> simklExact?.available?.size
                            seasonAwareAnime && repo.idPrefix == "simkl" ->
                                animeExact?.mapped?.size
                            else -> meta?.getOrNull()?.totalEpisodes?.takeIf { it > 0 }
                                ?: remote?.maxEpisodes?.takeIf { it > 0 }
                        }
                        var origin = if (simklExact != null) "simkl-season-episode-keys"
                            else if (animeExact != null) "simkl-anime-tvdb-episode-map"
                            else if (repo.idPrefix == "simkl" && multi) "unverified"
                            else if (meta?.getOrNull()?.totalEpisodes?.let { it > 0 } == true)
                                "provider-metadata"
                            else if (total != null) "provider-status" else "unknown"
                        if (repo.idPrefix == "kitsu") {
                            for (host in listOf("https://kitsu.io/api/edge", "https://kitsu.app/api/edge")) {
                                try {
                                    val response = app.get(
                                        "$host/anime/$id?fields[anime]=episodeCount",
                                        cacheTime = 0)
                                    val attributes = JSONObject(response.text)
                                        .optJSONObject("data")?.optJSONObject("attributes")
                                    val episodes = attributes?.opt("episodeCount")?.toString()
                                        ?.toIntOrNull()?.takeIf { it > 0 }
                                    if (episodes != null) {
                                        total = episodes
                                        origin = "kitsu-episodeCount"
                                        break
                                    }
                                } catch (e: Exception) {
                                }
                            }
                        }
                        if (total == null && repo.idPrefix == "mal") {
                            try {
                                val token = repo.authToken()?.accessToken
                                if (token != null) {
                                    val response = app.get(
                                        "https://api.myanimelist.net/v2/anime/$id?fields=num_episodes",
                                        headers = mapOf("Authorization" to "Bearer $token"), cacheTime = 0)
                                    total = JSONObject(response.text).optInt("num_episodes").takeIf { it > 0 }
                                    if (total != null) origin = "mal-lightweight"
                                }
                            } catch (e: Exception) {
                            }
                        }
                        val fields = if (seasonAwareAnime &&
                            repo.idPrefix == "simkl") animeExact?.let {
                            val watched=it.watched.size
                            val projected=animeSeasonDisplayStatus(it)
                            val targets=it.sourceIds.map { source ->
                                JSONObject().put("simkl",source)
                            }
                            val scores=simklScores(repo,targets,true)
                            ratingLoaded["simkl"]=scores!=null
                            val score=scores?.distinctBy { value -> value?.toInt(10) }
                                ?.singleOrNull()
                            Draft(projected,watched,score)
                        } else remote?.let {
                            Draft(if (generalSimkl && repo.idPrefix == "simkl" && simklExact != null)
                                simklSeasonStatus(simklExact) else it.status,
                                if (generalSimkl && repo.idPrefix == "simkl")
                                    simklExact?.watched?.size ?: 0
                                else it.watchedEpisodes ?: 0, it.score)
                        }
                        Triple(repo.idPrefix, fields, Pair(total, origin))
                    }
                }.awaitAll()
                if (!valid(gen, sn)) { return@launch }
                statuses["simkl"]?.let { simkl ->
                    simkl.simklSeason = simklSeasonReplies["simkl"]
                    simkl.simklAnime = simklAnimeReplies["simkl"]
                    simkl.ratingLoaded = ratingLoaded["simkl"] ?: true
                    simkl.seasonMapping = mappedSeason
                }
                val providerTotals = replies.mapNotNull { it.third.first }.filter { it > 0 }
                val commonTotal = if (!multi) providerTotals.maxOrNull()
                    else providerTotals.distinct().singleOrNull()
                replies.forEach { (prefix, draft, count) ->
                    val target = statuses[prefix] ?: return@forEach
                    target.loading = false
                    target.baseline = draft?.snapshot()
                    target.draft = draft?.snapshot()
                    val trackerTotal = count.first
                    target.total = trackerTotal ?: commonTotal
                    target.totalSource = if (trackerTotal != null) count.second
                        else if (commonTotal != null) {
                            if (multi) "season-mapped-peer" else "single-season-peer"
                        } else "unknown"
                    target.error = if (draft == null) "status-unavailable" else null

                    target.writeBlocked = when {
                        generalSimkl && prefix == "simkl" && target.simklSeason == null ->
                            "simkl-season-episodes-unverified"
                        seasonAwareAnime && prefix == "simkl" &&
                            target.simklAnime == null -> "simkl-anime-episode-map-unverified"
                        else -> null
                    }
                }
                updateOptions()
                render()
            }
        }

        private fun ensureUi() {
            if (isTv) {
                tvUi?.available(statuses.isNotEmpty())
                return
            }
            if (combo != null) return
            val names = root.findViewById<TextView>(R.id.result_sync_names) ?: return
            val parent = names.parent as? android.widget.LinearLayout ?: return
            val index = parent.indexOfChild(names)
            oldNames = names
            val activity = fragment.requireActivity()
            val dp = root.resources.displayMetrics.density

            val spinner = AppCompatSpinner(activity)
            val width = (208 * dp).toInt()
            spinner.dropDownWidth = width
            spinner.contentDescription = "Sync provider"
            val container = FrameLayout(activity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    width, (48 * dp).toInt()
                ).apply { bottomMargin = (10 * dp).toInt() }
                addView(spinner, FrameLayout.LayoutParams(-1, -1))
            }
            parent.addView(container, index)
            names.visibility = View.GONE
            combo = spinner
            comboContainer = container
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (applying) return
                    val key = statuses.keys.toList().getOrNull(position) ?: return
                    if (key == selected) return
                    selected = key
                    preferred = key
                    render()
                }
            }
            plus?.setOnClickListener { adjust(1) }
            minus?.setOnClickListener { adjust(-1) }
            saveButton?.setOnClickListener { save() }
            checks?.setOnItemClickListener { _, _, pos, _ ->
                changeStatus(SyncWatchType.fromInternalId(pos - 1))
            }
            slider?.clearOnChangeListeners()
            slider?.addOnChangeListener { _, value, fromUser ->
                if (!fromUser || applying) return@addOnChangeListener
                val tracker = statuses[selected] ?: return@addOnChangeListener
                if (isWriteInFlight(tracker) || tracker.writeBlocked != null) {
                    return@addOnChangeListener
                }
                val draft = tracker.draft ?: return@addOnChangeListener
                draft.score = Score.from(value, 10)
                render()
            }
            current?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (applying || current?.hasFocus() != true) return
                    val tracker = statuses[selected] ?: return
                    if (isWriteInFlight(tracker) || tracker.writeBlocked != null) {
                        return
                    }
                    val draft = tracker.draft ?: return
                    val entered = s?.toString()?.toIntOrNull() ?: return
                    val clamped = entered.coerceIn(0, tracker.total?.takeIf { it > 0 } ?: Int.MAX_VALUE)
                    draft.watched = clamped
                    reconcileManualProgress(tracker,draft)
                    root.post { if (active && selected == tracker.prefix) render() }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        private var lastProviderOptions: List<String> = emptyList()

        private fun updateOptions() {
            if (!active || !fragment.isAdded) return
            if (isTv) {
                ensureUi()
                render()
                return
            }
            if (combo == null) ensureUi()
            val spinner = combo ?: return
            applying = true
            try {
                val names = statuses.keys.map { titles[it] ?: it }
                if (spinner.adapter == null || names != lastProviderOptions) {
                    val activity = fragment.requireActivity()
                    spinner.useElevateSettingsDropdown(activity, names)
                    lastProviderOptions = names
                }
                spinner.isEnabled = statuses.size > 1
                spinner.isClickable = statuses.size > 1
                spinner.isFocusable = statuses.size > 1
                val position = statuses.keys.indexOf(selected).coerceAtLeast(0)
                if (names.isNotEmpty() && spinner.selectedItemPosition != position)
                    spinner.setSelection(position, false)
            } finally { applying = false }
        }

        private fun changeStatus(status: SyncWatchType) {
            val tracker = statuses[selected] ?: return
            if (isWriteInFlight(tracker) || tracker.writeBlocked != null) {
                render()
                return
            }
            val edit = tracker.draft ?: return
            edit.status = status
            val tv = tracker.simklSeason
            val anime = tracker.simklAnime
            if (anime != null && edit.status == SyncWatchType.COMPLETED)
                edit.watched = anime.mapped.size
            if (tv != null) {
                if (edit.status == SyncWatchType.COMPLETED)
                    edit.watched = tv.available.size
                else if (edit.status == SyncWatchType.NONE) edit.watched = 0
            } else if (tracker.prefix == "mal" &&
                edit.status == SyncWatchType.COMPLETED &&
                tracker.totalSource == "mal-lightweight") {
                tracker.total?.takeIf { it > 0 }?.let { edit.watched = it }
            }
            edit.explicitNone = edit.status == SyncWatchType.NONE
            render()
        }

        private fun adjustScore(delta: Int) {
            val tracker = statuses[selected] ?: return
            if (isWriteInFlight(tracker) || tracker.writeBlocked != null ||
                (tracker.simklAnime != null && !tracker.ratingLoaded)) return
            val draft = tracker.draft ?: return
            val score = ((draft.score?.toInt(10) ?: 0) + delta).coerceIn(0, 10)
            draft.score = Score.from(score.toFloat(), 10)
            render()
        }

        private fun reconcileManualProgress(tracker: Tracker, edit: Draft) {
            val total=tracker.total?.takeIf { it>0 }
            if(total!=null && edit.status==SyncWatchType.COMPLETED &&
                edit.watched<total) {
                edit.status=SyncWatchType.WATCHING
                edit.explicitNone=false
            }
        }

        private fun adjust(delta: Int) {
            val tracker = statuses[selected] ?: return
            if (isWriteInFlight(tracker) || tracker.writeBlocked != null) {
                return
            }
            val draft = tracker.draft ?: return
            val upper = tracker.total?.takeIf { it > 0 } ?: Int.MAX_VALUE
            draft.watched = (draft.watched.toLong() + delta).coerceIn(0L, upper.toLong()).toInt()
            reconcileManualProgress(tracker,draft)
            render()
        }

        private fun render() {
            if (!active) return
            val target = statuses[selected]
            if (isTv) {
                val data = target?.draft
                tvUi?.render(ElevateWatchHistoryTvUi.State(
                    season = season,
                    providers = statuses.keys.map { key -> key to (titles[key] ?: key) },
                    selected = selected,
                    status = data?.status,
                    watched = data?.watched,
                    maximum = target?.total,
                    score = data?.score?.toInt(10),
                    enabled = target != null && !target.loading && data != null &&
                        target.writeBlocked == null && !isWriteInFlight(target),
                    statusEnabled = target?.simklAnime?.statusEditable != false &&
                        (target?.simklSeason?.listStatus != null || target?.simklSeason == null),
                    scoreEnabled = target?.simklAnime == null || target?.ratingLoaded == true,
                    error = target?.error ?: target?.writeBlocked
                ))
                return
            }
            if (combo == null) return
            if (target == null || target.loading || target.draft == null) {

                applying = true
                try {
                    holder?.visibility = View.VISIBLE
                    shimmer?.visibility = View.GONE
                    saveButton?.isEnabled = false
                    plus?.isEnabled = false
                    minus?.isEnabled = false
                    if (maximum?.text?.toString() != "/ ?") maximum?.text = "/ ?"
                    if (bar?.progress != 0) bar?.progress = 0
                    if (!current?.text.isNullOrEmpty()) current?.setText("")
                    if (checks?.checkedItemCount != 0) checks?.clearChoices()
                    if (scoreLabel?.text?.toString() != "?") scoreLabel?.text = "?"
                } finally { applying = false }

                return
            }
            val data = target.draft!!
            applying = true
            try {
                holder?.visibility = View.VISIBLE
                shimmer?.visibility = View.GONE
                (shimmer as? com.facebook.shimmer.ShimmerFrameLayout)?.stopShimmer()
                val max = target.total?.takeIf { it > 0 }
                val maxLabel = if (max == null) "/ ?" else "/ $max"
                if (maximum?.text?.toString() != maxLabel) maximum?.text = maxLabel
                val barMax = (max ?: maxOf(1, data.watched)).coerceAtMost(1000000) * 1000
                val barProgress = data.watched.coerceAtMost(1000000) * 1000
                if (bar?.max != barMax) bar?.max = barMax
                if (bar?.progress != barProgress) bar?.progress = barProgress
                val newText = data.watched.toString()
                if (current?.text?.toString() != newText) {
                    current?.setText(newText)
                    current?.setSelection(newText.length)
                }
                val checked = data.status.internalId + 1
                if (checks?.checkedItemPosition != checked)
                    checks?.setItemChecked(checked, true)

                checks?.isEnabled = !isWriteInFlight(target) && target.writeBlocked == null &&
                    (target.simklSeason == null || target.simklSeason?.listStatus != null) &&
                    (target.simklAnime == null || target.simklAnime?.statusEditable == true)
                slider?.isEnabled = !isWriteInFlight(target) && target.writeBlocked == null &&
                    (target.simklAnime == null || target.ratingLoaded)
                val valScore = data.score?.toFloat(10) ?: 0f
                val value = valScore.coerceIn(0f,10f).toInt().toFloat()
                if (slider?.value != value) slider?.value = value
                val scoreText = data.score?.toInt(10)?.toString()?.plus("/10") ?: "?"
                if (scoreLabel?.text?.toString() != scoreText) scoreLabel?.text = scoreText
                saveButton?.isEnabled = !isWriteInFlight(target) && target.writeBlocked == null
                current?.isEnabled = !isWriteInFlight(target) && target.writeBlocked == null
                plus?.isEnabled = !isWriteInFlight(target) && target.writeBlocked == null
                minus?.isEnabled = !isWriteInFlight(target) && target.writeBlocked == null
            } catch (e: Exception) {
            } finally { applying = false }
        }

        private suspend fun malDirectVerify(
            tracker: Tracker, expected: com.lagradost.cloudstream3.syncproviders.SyncAPI.AbstractSyncStatus,
            compareStatus: Boolean, compareProgress: Boolean, compareScore: Boolean
        ): Boolean {
            val token=tracker.repo.authToken()?.accessToken
            val id=tracker.id.toIntOrNull()
            if(token==null || id==null)return false
            val response=try {
                app.get("https://api.myanimelist.net/v2/anime/$id?fields=my_list_status",
                    headers=mapOf("Authorization" to "Bearer $token"),cacheTime=0)
            }catch(e:CancellationException){throw e}
             catch(e:Exception){
                return false
             }
            if(response.code !in 200..299) { response.okhttpResponse.close(); return false }
            val body=runCatching { JSONObject(response.text) }.getOrNull()
            val actual=body?.optJSONObject("my_list_status") ?: return false
            val wantedStatus=when(expected.status){
                SyncWatchType.WATCHING -> "watching"
                SyncWatchType.COMPLETED -> "completed"
                SyncWatchType.ONHOLD -> "on_hold"
                SyncWatchType.DROPPED -> "dropped"
                SyncWatchType.PLANTOWATCH -> "plan_to_watch"
                else -> null
            }
            val statusOk=!compareStatus || (wantedStatus!=null &&
                actual.optString("status")==wantedStatus)
            val progressOk=!compareProgress ||
                actual.optInt("num_episodes_watched",-1)==(expected.watchedEpisodes ?: 0)
            val scoreOk=!compareScore ||
                actual.optInt("score",-1)==(expected.score?.toInt(10) ?: 0)
            val valid=statusOk && progressOk && scoreOk
            return valid
        }

        private fun save() {
            if (!active) return
            val tracker = statuses[selected] ?: return
            val draft = tracker.draft?.snapshot() ?: return
            val initial = tracker.baseline ?: return
            val gen = generation
            val sn = season ?: return
            val prefix = tracker.prefix
            val id = tracker.id
            val before = initial.snapshot()
            if (tracker.writeBlocked != null || isWriteInFlight(tracker)) {
                return
            }
            if (prefix == "mal" && draft.status == SyncWatchType.COMPLETED &&
                before.status != SyncWatchType.COMPLETED) {

                val total = tracker.total?.takeIf {
                    it > 0 && tracker.totalSource == "mal-lightweight"
                }
                if (total == null) {
                    return
                }
                draft.watched = total
                tracker.draft?.watched = total
            }
            if (draft.status == SyncWatchType.NONE && draft.watched > 0 && !draft.explicitNone) {
                draft.status = SyncWatchType.WATCHING
                tracker.draft?.status = SyncWatchType.WATCHING
            }
            val updated = draft != before
            if (!updated) { return }
            if (draft.status == SyncWatchType.NONE && draft.explicitNone &&
                before.status == SyncWatchType.NONE && before.watched == 0 &&
                before.score == null) return
            if (draft.status == SyncWatchType.NONE && !draft.explicitNone &&
                (draft.score != before.score || draft.watched != before.watched)) {
                return
            }
            if (draft.watched < 0 || tracker.total?.let { it > 0 && draft.watched > it } == true) {
                return
            }
            if (tracker.simklAnime != null && !tracker.ratingLoaded &&
                draft.score != before.score) return
            val animeProgressDemotion=tracker.simklAnime?.statusEditable==false &&
                before.status==SyncWatchType.COMPLETED &&
                draft.status==SyncWatchType.WATCHING && draft.watched<before.watched
            if (tracker.simklAnime != null && draft.status != before.status &&
                !animeProgressDemotion &&
                !(before.status == SyncWatchType.NONE &&
                  draft.status == SyncWatchType.WATCHING && draft.watched > 0) &&
                (tracker.simklAnime?.statusEditable != true ||
                 (draft.status != SyncWatchType.NONE &&
                  simklWatchlistDestination(draft.status)==null))) {
                return
            }
            val operationKey="$prefix:$id"
            if(!pendingWrites.add(operationKey)) {
                return
            }
            var writeAcceptedButUnconfirmed=false
            tracker.saving = true
            render()

            val task = saveScope.launch(start = CoroutineStart.LAZY) {
                val result = try { withContext(Dispatchers.IO) {
                    if(prefix=="mal" && draft.status==SyncWatchType.NONE &&
                        draft.explicitNone) {
                        malRemoveFromLibrary(tracker.repo,id)
                    } else if (tracker.simklSeason != null) {
                        val changed=draft.status!=before.status || draft.watched!=before.watched ||
                            (draft.status==SyncWatchType.NONE && draft.explicitNone)
                        val ratingChanged=draft.score!=before.score &&
                            !(draft.status==SyncWatchType.NONE && draft.explicitNone)
                        (!changed || simklSeasonSave(tracker,draft.copy(score=before.score),before,sn)) &&
                            (!ratingChanged || simklSaveScores(tracker.repo,
                                listOf(tracker.simklSeason!!.ids),draft.score,false))
                    } else if (tracker.simklAnime != null) {
                        val changed=draft.status!=before.status || draft.watched!=before.watched ||
                            (draft.status==SyncWatchType.NONE && draft.explicitNone)
                        val ratingChanged=draft.score!=before.score &&
                            !(draft.status==SyncWatchType.NONE && draft.explicitNone)
                        (!changed || simklAnimeSave(tracker,draft.copy(score=before.score),before,sn)) &&
                            (!ratingChanged || simklSaveScores(tracker.repo,
                                tracker.simklAnime!!.sourceIds.map { source ->
                                    JSONObject().put("simkl",source)
                                },draft.score,true))
                    } else {
                    val fresh = tracker.repo.status(id)
                    val remote = fresh.getOrNull()
                    if (remote == null) {
                        false
                    } else {
                        if (draft.status != before.status) remote.status = draft.status
                        if (draft.watched != before.watched) remote.watchedEpisodes = draft.watched
                        if (draft.score != before.score) remote.score = draft.score
                        if (remote.status == SyncWatchType.NONE && (remote.watchedEpisodes ?: 0) > 0
                            && !draft.explicitNone) remote.status = SyncWatchType.WATCHING
                        if (remote.status != SyncWatchType.NONE && remote.watchedEpisodes == null)
                            remote.watchedEpisodes = 0
                        val issued = tracker.repo.updateStatus(id, remote)
                        var verified = false
                        val maxAttempts=if(prefix=="mal")7 else 5
                        for (attempt in 1..maxAttempts) {
                            if (attempt > 1) kotlinx.coroutines.delay(
                                when(attempt) {
                                    2->350L;3->900L;4->1800L;5->3000L
                                    6->4000L;else->5000L
                                })
                            val read = tracker.repo.status(id)
                            val got = read.getOrNull()
                            verified = got != null &&
                                (draft.status == before.status || got.status == remote.status) &&
                                (draft.watched == before.watched || (got.watchedEpisodes ?: 0) == (remote.watchedEpisodes ?: 0)) &&
                                (draft.score == before.score || got.score == remote.score)
                            if (verified) break
                        }
                        if(!verified && prefix=="mal" && issued.getOrNull()==true) {
                            for(attempt in 1..2) {
                                if(attempt>1)kotlinx.coroutines.delay(2000L)
                                verified=malDirectVerify(tracker,remote,
                                    draft.status!=before.status,
                                    draft.watched!=before.watched,
                                    draft.score!=before.score)
                                if(verified)break
                            }
                            if(!verified) {
                                writeAcceptedButUnconfirmed=true
                            }
                        }
                        if (!verified && prefix == "kitsu" && remote.status != SyncWatchType.NONE) {
                            val fallback = kitsuFallback(id, remote, tracker.repo)
                            if (fallback) {
                                val after = tracker.repo.status(id).getOrNull()
                                verified = after != null &&
                                    (draft.status == before.status || after.status == remote.status) &&
                                    (draft.watched == before.watched ||
                                        (after.watchedEpisodes ?: 0) == (remote.watchedEpisodes ?: 0)) &&
                                    (draft.score == before.score || after.score == remote.score)
                            }
                        }
                        verified
                    }
                    }
                } } catch(e:CancellationException) {
                    false
                } catch(e:Exception) {
                    false
                }
                if(result || writeAcceptedButUnconfirmed) {
                    try {
                        withContext(Dispatchers.IO) { refreshAfterVerifiedWrite(tracker,prefix) }
                    } catch(e:CancellationException) {
                    } catch(e:Exception) {
                    }
                }
                pendingWrites.remove(operationKey)
                if (valid(gen, sn) && statuses[prefix] === tracker) {
                    tracker.saving = false
                    if (result) {
                        tracker.error = null
                        val anime = tracker.simklAnime
                        if (anime != null) {
                            val count=anime.watched.size
                            val actual=animeSeasonDisplayStatus(anime)
                            val savedScore=if(draft.status==SyncWatchType.NONE && draft.explicitNone)
                                null else draft.score
                            tracker.baseline=Draft(actual,count,savedScore)
                            tracker.draft?.let { current ->
                                if(current == draft) {
                                    current.status=actual
                                    current.watched=count
                                    current.score=savedScore
                                    current.explicitNone=false
                                }
                            }
                        } else if (tracker.simklSeason != null) {
                            val actual = tracker.simklSeason!!
                            tracker.baseline = Draft(simklSeasonStatus(actual),
                                actual.watched.size,
                                if(draft.status==SyncWatchType.NONE && draft.explicitNone)
                                    null else draft.score)
                            tracker.draft = tracker.baseline?.snapshot()
                        } else if(prefix=="mal" && draft.status==SyncWatchType.NONE &&
                            draft.explicitNone) {
                            tracker.baseline=Draft(SyncWatchType.NONE,0,null)
                            tracker.draft=tracker.baseline?.snapshot()
                        } else {
                            tracker.baseline = draft.snapshot()
                            tracker.draft?.let { if (it == draft) it.explicitNone = false }
                        }
                    } else tracker.error = if(writeAcceptedButUnconfirmed)
                        "server-write-readback-pending" else "save-or-readback-failed"
                    render()
                }
                if(result || writeAcceptedButUnconfirmed) {
                    MainActivity.reloadLibraryEvent(true)
                    val model=syncModel
                    if(active && model!=null) {
                        model.updateUserData()
                        model.updateSynced()
                    }
                }
            }
            writeJobs.add(task)
            task.invokeOnCompletion {
                pendingWrites.remove(operationKey)
                writeJobs.remove(task)
            }
            task.start()
        }

        private suspend fun malRemoveFromLibrary(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            id: String
        ): Boolean {
            val animeId=id.toIntOrNull()?.takeIf { it>0 } ?: return false
            val token=repo.authToken()?.accessToken ?: return false
            val headers=mapOf("Authorization" to "Bearer $token")
            val url="https://api.myanimelist.net/v2/anime/$animeId/my_list_status"
            val deleted=try {
                app.delete(url,headers=headers)
            }catch(e: CancellationException){throw e}
             catch(e: Exception){
                  return false}
            val deleteStatus = deleted.code
            deleted.okhttpResponse.close()
            if(deleteStatus !in 200..299 && deleteStatus != 404)return false
            repeat(3){ attempt ->
                if(attempt>0)kotlinx.coroutines.delay(if(attempt==1)350L else 900L)
                val response=try {
                    app.get("https://api.myanimelist.net/v2/anime/$animeId",
                        headers=headers,params=mapOf("fields" to "my_list_status"),
                        cacheTime=0)
                }catch(e: CancellationException){throw e}
                 catch(e: Exception){
                  return@repeat}
                if(response.code in 200..299) {
                    val data=runCatching { JSONObject(response.text) }.getOrNull()
                    if(data!=null && (data.isNull("my_list_status") ||
                        !data.has("my_list_status")))return true
                } else response.okhttpResponse.close()
            }
            return false
        }

        private suspend fun kitsuFallback(
            id: String, expected: SyncAPI.AbstractSyncStatus,
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo
        ): Boolean {
            val status = when (expected.status) {
                SyncWatchType.WATCHING, SyncWatchType.REWATCHING -> "current"
                SyncWatchType.COMPLETED -> "completed"
                SyncWatchType.PLANTOWATCH -> "planned"
                SyncWatchType.ONHOLD -> "on_hold"
                SyncWatchType.DROPPED -> "dropped"
                else -> return false
            }
            val user = repo.authData() ?: return false
            val headers = mapOf("Authorization" to "Bearer ${user.token.accessToken}",
                "content-type" to "application/vnd.api+json")
            val payload = JSONObject().put("status", status)
                .put("progress", expected.watchedEpisodes ?: 0)
            expected.score?.let { payload.put("ratingTwenty", it.toInt(20)) }
            for (host in listOf("https://kitsu.io/api/edge", "https://kitsu.app/api/edge")) {
                try {
                    val find = app.get(
                        "$host/library-entries?filter[userId]=${user.user.id}&filter[animeId]=$id",
                        headers = headers, cacheTime = 0)
                    val entry = if (find.code in 200..299)
                        JSONObject(find.text).optJSONArray("data")?.optJSONObject(0)
                            ?.optString("id")?.takeIf { it.isNotBlank() } else null
                    if (find.code !in 200..299) {
                        find.okhttpResponse.close()
                        continue
                    }
                    val data = JSONObject().put("type","libraryEntries").put("attributes",payload)
                    if (entry != null) data.put("id",entry)
                    else data.put("relationships", JSONObject()
                        .put("anime", JSONObject().put("data",
                            JSONObject().put("type","anime").put("id",id)))
                        .put("user", JSONObject().put("data",
                            JSONObject().put("type","users").put("id",user.user.id.toString()))))
                    val body = JSONObject().put("data",data).toString().toRequestBody()
                    val response = if (entry == null)
                        app.post("$host/library-entries", headers=headers, requestBody=body)
                    else app.patch("$host/library-entries/$entry", headers=headers, requestBody=body)

                    val writeStatus = response.code
                    response.okhttpResponse.close()
                    if (writeStatus in 200..299) return true
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                }
            }
            return false
        }

        private fun teardownUi() {
            if (isTv) {
                tvUi?.available(false)
                return
            }
            val spinner = combo ?: return
            (comboContainer?.parent as? android.view.ViewGroup)?.removeView(comboContainer)
            comboContainer = null
            combo = null
            lastProviderOptions = emptyList()
            oldNames?.visibility = View.VISIBLE
            oldNames = null
            syncModel?.let { model ->
                saveButton?.setOnClickListener { model.publishUserData() }
                plus?.setOnClickListener { model.setEpisodesDelta(1) }
                minus?.setOnClickListener { model.setEpisodesDelta(-1) }
                checks?.setOnItemClickListener { _, _, index, _ ->
                    model.setStatus(index - 1)
                }
                slider?.clearOnChangeListeners()
                slider?.addOnChangeListener { view, value, fromUser ->
                    if (fromUser) model.setScore(Score.from(value, view.valueTo.toInt()))
                }
            }
        }

        fun stop() {
            if (!active) return
            active = false
            generation++
            fetchJob?.cancel()
            writeJobs.toList().forEach { it.cancel() }
            saveScope.cancel()
            scope.cancel()
            observers.forEach { it() }
            observers.clear()
            teardownUi()
            tvUi?.release()
            syncModel = null
        }
    }

}
