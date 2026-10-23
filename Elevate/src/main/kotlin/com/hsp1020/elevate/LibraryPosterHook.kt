package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.ui.library.PageAdapter
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

internal class LibraryPosterHook(private val appContext: Application) :
    Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val semaphore = Semaphore(3)
    private val mapCache = ConcurrentHashMap<String, CacheEntry>()
    private val unavailable = mutableSetOf<String>()
    private val bound = WeakHashMap<ImageView, Binding>()
    private val pending = mutableMapOf<ImageView, Job>()
    private val watches = mutableMapOf<RecyclerView, PageWatch>()
    private var host: FragmentActivity? = null
    private var library: Fragment? = null
    private var pagerRecycler: RecyclerView? = null
    private var pagerAdapter: RecyclerView.Adapter<*>? = null
    private var revision = 0
    private var config = LibraryPosterPrefs.get(appContext)
    private var xrdbVersion: String? = null
    private var xrdbCheckAt = 0L
    private var xrdbCheck: Job? = null

    private data class CacheEntry(val value: PosterIds?, val time: Long)
    private data class Binding(
        val identity: String, val url: String, val original: String?,
        val headers: Map<String, String>?
    )

    private val settings = appContext.getSharedPreferences(LibraryPosterPrefs.FILE, Context.MODE_PRIVATE)
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.post {
            val changed = LibraryPosterPrefs.get(appContext)
            if (config != changed) {
                config = changed
                xrdbCheck?.cancel()
                xrdbCheck = null
                xrdbVersion = null
                xrdbCheckAt = 0L
                revision++
                cancelPending()
                restoreAll()
                unavailable.clear()
                rescan()
                if (library?.isResumed == true) checkXrdbVersion()
            }
        }
    }
    private val fragmentListener = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, state: Bundle?) {
            if (f.javaClass.name == LIBRARY_CLASS) bind(f, v)
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (library === f) unbind()
        }
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (f === library) checkXrdbVersion()
        }
    }
    private val parentListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) = watch(view)
        override fun onChildViewDetachedFromWindow(view: View) {
            view.findViewById<RecyclerView>(R.id.page_recyclerview)?.let { watches.remove(it)?.stop() }
        }
    }
    private val parentObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = queueRescan()
        override fun onItemRangeChanged(p: Int, n: Int) = queueRescan()
        override fun onItemRangeInserted(p: Int, n: Int) = queueRescan()
        override fun onItemRangeRemoved(p: Int, n: Int) = queueRescan()
    }

    fun start() {
        settings.registerOnSharedPreferenceChangeListener(settingsListener)
        appContext.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        settings.unregisterOnSharedPreferenceChangeListener(settingsListener)
        appContext.unregisterActivityLifecycleCallbacks(this)
        detachActivity()
        scope.cancel()
        mapCache.clear()
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        detachActivity()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentListener, true)
        fun scan(manager: FragmentManager) {
            for (fragment in manager.fragments) {
                if (fragment.javaClass.name == LIBRARY_CLASS) {
                    fragment.view?.let { bind(fragment, it) }
                }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun detachActivity() {
        unbind()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentListener)
        host = null
    }

    private fun bind(next: Fragment, view: View) {
        if (library === next) return
        unbind()
        library = next
        val pager = view.findViewById<ViewPager2>(R.id.viewpager)
        pagerRecycler = pager?.getChildAt(0) as? RecyclerView
        pagerRecycler?.addOnChildAttachStateChangeListener(parentListener)
        observePager()
        queueRescan()
        if (next.isResumed) checkXrdbVersion()
    }

    private fun unbind() {
        xrdbCheck?.cancel()
        xrdbCheck = null
        revision++
        cancelPending()
        restoreAll()
        watches.values.toList().forEach { it.stop() }
        watches.clear()
        pagerAdapter?.unregisterAdapterDataObserver(parentObserver)
        pagerAdapter = null
        pagerRecycler?.removeOnChildAttachStateChangeListener(parentListener)
        pagerRecycler = null
        library = null
    }

    private fun observePager() {
        val next = pagerRecycler?.adapter
        if (next === pagerAdapter) return
        pagerAdapter?.unregisterAdapterDataObserver(parentObserver)
        pagerAdapter = next
        next?.registerAdapterDataObserver(parentObserver)
    }

    private fun queueRescan() {
        pagerRecycler?.post { rescan() }
    }

    private fun rescan() {
        if (library?.view == null) return
        val outer = pagerRecycler ?: return
        observePager()
        for (i in 0 until outer.childCount) watch(outer.getChildAt(i))
        watches.values.toList().forEach { it.scan() }
    }

    private fun watch(view: View) {
        val recycler = view.findViewById<RecyclerView>(R.id.page_recyclerview) ?: return
        if (!watches.containsKey(recycler)) {
            watches[recycler] = PageWatch(recycler).also { it.start() }
        }
    }

    private inner class PageWatch(private val recycler: RecyclerView) {
        private var adapter: RecyclerView.Adapter<*>? = null
        private var queued = false
        private var rebinding = false
        private val attachListener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = queue()
            override fun onChildViewDetachedFromWindow(view: View) {
                view.findViewById<ImageView>(R.id.imageView)?.let { image ->
                    pending.remove(image)?.cancel()
                    bound.remove(image)
                }
            }
        }
        private val changes = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = queue(true)
            override fun onItemRangeChanged(p: Int, n: Int) = queue(true)
            override fun onItemRangeInserted(p: Int, n: Int) = queue(true)
            override fun onItemRangeRemoved(p: Int, n: Int) = queue(true)
            override fun onItemRangeMoved(from: Int, to: Int, n: Int) = queue(true)
        }

        fun start() {
            recycler.addOnChildAttachStateChangeListener(attachListener)
            queue()
        }

        private fun sync() {
            val next = recycler.adapter
            if (adapter === next) return
            adapter?.unregisterAdapterDataObserver(changes)
            adapter = next
            next?.registerAdapterDataObserver(changes)
        }

        private fun queue(rebind: Boolean = false) {
            if (rebind) rebinding = true
            if (queued) return
            queued = true
            recycler.postOnAnimation {
                queued = false
                if (watches[recycler] !== this || library?.view == null) return@postOnAnimation
                if (rebinding) {
                    rebinding = false
                    for (i in 0 until recycler.childCount) {
                        recycler.getChildAt(i).findViewById<ImageView>(R.id.imageView)?.let {
                            pending.remove(it)?.cancel()
                            bound.remove(it)
                        }
                    }
                }
                scan()
            }
        }

        fun scan() {
            sync()
            if (!config.ready || library?.view == null) return
            val native = recycler.adapter as? PageAdapter ?: return
            for (i in 0 until recycler.childCount) {
                val view = recycler.getChildAt(i)
                val position = recycler.getChildViewHolder(view).bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) continue
                val card = native.getItemOrNull(position) ?: continue
                if (view.findViewById<TextView>(R.id.imageText)?.text?.toString() != card.name) continue
                val image = view.findViewById<ImageView>(R.id.imageView) ?: continue
                update(image, card)
            }
        }

        fun stop() {
            adapter?.unregisterAdapterDataObserver(changes)
            recycler.removeOnChildAttachStateChangeListener(attachListener)
            for (i in 0 until recycler.childCount) {
                recycler.getChildAt(i).findViewById<ImageView>(R.id.imageView)?.let { image ->
                    pending.remove(image)?.cancel()
                    bound.remove(image)?.let { restore(image, it) }
                }
            }
        }
    }

    private fun update(image: ImageView, item: SyncAPI.LibraryItem) {
        val id = "${item.apiName}:${item.syncId}:${item.url}:${item.name}"
        if (bound[image]?.identity == id || pending[image]?.isActive == true &&
            image.getTag(R.id.imageView) == id) return
        pending.remove(image)?.cancel()
        bound.remove(image)
        image.setTag(R.id.imageView, id)
        val token = revision
        val setting = config
        val version = xrdbVersion
        pending[image] = scope.launch {
            try {
                val ids = withContext(Dispatchers.IO) {
                    resolve(item, setting.service == PosterService.XRDB)
                } ?: return@launch
                val originalPoster = setting.image(ids, item.type == TvType.Movie) ?: return@launch
                val poster = if (setting.service == PosterService.XRDB && version != null) {
                    Uri.parse(originalPoster).buildUpon().appendQueryParameter("v", version)
                        .build().toString()
                } else originalPoster
                if (revision != token || image.getTag(R.id.imageView) != id ||
                    !image.isAttachedToWindow || poster in unavailable) return@launch
                val entry = Binding(id, poster, item.posterUrl, item.posterHeaders)
                bound[image] = entry
                image.loadImage(poster) {
                    listener(onError = { _, _ ->
                        if (bound[image] === entry && image.isAttachedToWindow) {
                            unavailable.add(poster)
                            bound.remove(image)
                            restore(image, entry)
                        }
                    })
                }
            } finally {
                if (image.getTag(R.id.imageView) == id) pending.remove(image)
            }
        }
    }

    private fun restore(image: ImageView, item: Binding) {
        if (!image.isAttachedToWindow || image.getTag(R.id.imageView) != item.identity) return
        if (item.original.isNullOrBlank()) image.loadImage(R.drawable.default_cover)
        else image.loadImage(item.original, item.headers)
    }

    private fun restoreAll() {
        bound.entries.toList().forEach { (image, entry) -> restore(image, entry) }
        bound.clear()
    }

    private fun cancelPending() {
        pending.values.toList().forEach { it.cancel() }
        pending.clear()
    }

    private fun checkXrdbVersion() {
        val snapshot = config
        if (snapshot.service != PosterService.XRDB || snapshot.xrdbProfile.isBlank() ||
            library?.view == null) return
        if (xrdbCheck?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (xrdbCheckAt != 0L && now - xrdbCheckAt in 0 until 5_000L) return
        xrdbCheckAt = now
        xrdbCheck = scope.launch {
            val token = withContext(Dispatchers.IO) { fetchXrdbVersion(snapshot) }
            if (config != snapshot || library?.view == null ||
                token == null || token == xrdbVersion) return@launch
            xrdbVersion = token
            revision++
            cancelPending()
            restoreAll()
            unavailable.clear()
            rescan()
        }
    }

    private suspend fun fetchXrdbVersion(snapshot: PosterConfig): String? {
        val base = Uri.parse(snapshot.xrdbBase.trim().trimEnd('/'))
        if (base.scheme != "https" || base.host.isNullOrBlank() ||
            !base.userInfo.isNullOrEmpty() || !base.query.isNullOrEmpty() ||
            !base.fragment.isNullOrEmpty()) return null

        fun valid(value: String?): String? =
            value?.takeIf { XRDB_VERSION.matches(it) }

        val headers = if (snapshot.xrdbRenderKey.isBlank()) emptyMap() else
            mapOf("Authorization" to "Bearer ${snapshot.xrdbRenderKey}")
        val profileUrl = base.buildUpon().appendPath("profile")
            .appendPath(snapshot.xrdbProfile).build().toString()
        try {
            val body = app.get(profileUrl, headers = headers, timeout = 6L).text
            valid(JSONObject(body).optString("versionToken"))?.let { return it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Password-protected profiles cannot expose the profile API without a password.
        }

        // The public XRDB Stremio metadata endpoint embeds the current version
        // in meta.poster, including for password-protected profiles.
        val fallback = base.buildUpon().appendPath("stremio")
            .appendPath("c").appendPath(snapshot.xrdbProfile)
            .appendPath("meta").appendPath("movie").appendPath("tt0111161.json")
            .apply {
                if (snapshot.xrdbRenderKey.isNotBlank()) {
                    appendQueryParameter("key", snapshot.xrdbRenderKey)
                }
            }.build().toString()
        return try {
            val body = app.get(fallback, headers = headers, timeout = 8L).text
            val poster = JSONObject(body).optJSONObject("meta")?.optString("poster")
            valid(poster?.let { Uri.parse(it).getQueryParameter("v") })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolve(item: SyncAPI.LibraryItem, forXrdb: Boolean): PosterIds? {
        fun positive(value: String?): String? = value?.trim()
            ?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
        fun ids(
            imdb: String?, tmdb: String?, mal: String? = null,
            anilist: String? = null, kitsu: String? = null
        ): PosterIds? = PosterIds(
            imdb?.trim()?.takeIf { IMDB.matches(it) },
            positive(tmdb), positive(mal), positive(anilist), positive(kitsu)
        ).takeIf { it.available }
        fun canonical(value: PosterIds?): Boolean =
            value?.imdb != null || value?.tmdb != null
        fun usable(value: PosterIds?): Boolean =
            value != null && (forXrdb || canonical(value))

        val syncData = DataStoreHelper.getBookmarkedData(item.id)
            ?.takeIf { it.url == item.url }?.syncData
        val saved = ids(
            syncData?.get("imdb"), syncData?.get("tmdb"),
            syncData?.get("mal"), syncData?.get("anilist"), syncData?.get("kitsu")
        )
        if (usable(saved)) return saved

        val source = "${item.url} ${item.syncId}"
        ids(IMDB.find(source)?.value, TMDB.find(source)?.groupValues?.get(1))
            ?.let { return it }

        val host = runCatching { URI(item.url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        val provider = when (host) {
            "simkl.com" -> "simkl"
            "anilist.co" -> "anilist"
            "myanimelist.net" -> "mal"
            "kitsu.app" -> "kitsu"
            else -> return null
        }
        // CloudStream's Simkl anime cards store the Simkl ID in syncId
        // (and may use a /tv/ URL even for anime). It is never an IMDb/TMDB ID.
        val numeric = positive(item.syncId) ?: return null

        var alternate = saved
        if (provider == "simkl") {
            val cached = runCatching {
                val account = AccountManager.syncApis.firstOrNull { it.idPrefix == "simkl" }
                val userId = account?.authData()?.user?.id?.toString()
                val list = userId?.let {
                    getKey<com.fasterxml.jackson.databind.JsonNode>(SimklApi.SIMKL_CACHED_LIST, it)
                }
                var entryIds: PosterIds? = null
                for (section in listOf("movies", "shows", "anime")) {
                    val entries = list?.get(section) ?: continue
                    for (entry in entries) {
                        val itemIds = entry.get(if (section == "movies") "movie" else "show")
                            ?.get("ids") ?: continue
                        if (itemIds.get("simkl")?.asText() != numeric) continue
                        entryIds = ids(
                            itemIds.get("imdb")?.asText(),
                            itemIds.get("tmdb")?.asText(),
                            if (section == "anime") itemIds.get("mal")?.asText() else null,
                            if (section == "anime") itemIds.get("anilist")?.asText() else null
                        )
                        break
                    }
                    if (entryIds != null) break
                }
                entryIds
            }.getOrNull()
            if (usable(cached)) return cached
            if (cached != null) alternate = cached
        } else {
            val direct = when (provider) {
                "mal" -> ids(null, null, mal = numeric)
                "anilist" -> ids(null, null, anilist = numeric)
                else -> ids(null, null, kitsu = numeric)
            }
            if (usable(direct)) return direct
            alternate = direct
        }

        // Only unresolved anime IDs enter AIOStreams. Never query its anime
        // endpoint with a raw Simkl ID, as that lookup type is unsupported.
        val candidates = listOfNotNull(
            alternate?.mal?.let { "malId" to it },
            alternate?.anilist?.let { "anilistId" to it },
            alternate?.kitsu?.let { "kitsuId" to it }
        )
        for ((kind, id) in candidates) {
            val mapping = fetchAnimeIds(kind, id)
            if (canonical(mapping)) return mapping
        }
        return null
    }

    private suspend fun fetchAnimeIds(kind: String, id: String): PosterIds? {
        val query = "idType=${kind}&idValue=${id}"
        fun fresh(entry: CacheEntry): Boolean {
            val age = System.currentTimeMillis() - entry.time
            val ttl = if (entry.value == null) 900_000L else 86_400_000L
            return age >= 0 && age < ttl
        }
        mapCache[query]?.takeIf(::fresh)?.let { return it.value }
        return semaphore.withPermit {
            mapCache[query]?.takeIf(::fresh)?.let { return@withPermit it.value }
            val result = withTimeoutOrNull(14_000L) {
                for (server in ANIME_HOSTS) {
                    val json = try {
                        app.get("https://${server}/api/v1/anime?${query}", timeout = 4L).text
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        continue
                    }
                    val root = runCatching { JSONObject(json) }.getOrNull() ?: continue
                    if (!root.optBoolean("success")) continue
                    if (root.isNull("data")) break
                    val mapped = root.optJSONObject("data")?.optJSONObject("mappings") ?: continue
                    // Do not apply a mapping for a different season / anime title.
                    if (mapped.optString(kind) != id) continue
                    val imdb = mapped.optString("imdbId").takeIf { IMDB.matches(it) }
                    val tmdb = mapped.optString("themoviedbId")
                        .takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
                    if (imdb != null || tmdb != null) {
                        return@withTimeoutOrNull PosterIds(imdb, tmdb)
                    }
                }
                null
            }
            mapCache[query] = CacheEntry(result, System.currentTimeMillis())
            if (mapCache.size > 500) mapCache.keys.firstOrNull()?.let { mapCache.remove(it) }
            result
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) {
            use(activity)
            if (library?.isResumed == true) checkXrdbVersion()
        }
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) detachActivity()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private companion object {
        const val LIBRARY_CLASS = "com.lagradost.cloudstream3.ui.library.LibraryFragment"
        val XRDB_VERSION = Regex("[a-fA-F0-9]{8}")
        val IMDB = Regex("tt[0-9]{5,10}")
        val TMDB = Regex("tmdb:(?:(?:movie|series|tv):)?([0-9]+)")
        val ANIME_HOSTS = listOf("aiostreams.elfhosted.com",
            "aiostreams.fortheweak.cloud", "aiostreamsfortheweebsstable.midnightignite.me",
            "aio.atbphosting.com", "aiostreams.stremio.ru", "aiostreams.viren070.me")
    }
}
