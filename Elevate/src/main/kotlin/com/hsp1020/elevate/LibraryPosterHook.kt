package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
                revision++
                cancelPending()
                restoreAll()
                unavailable.clear()
                rescan()
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
    }

    private fun unbind() {
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
        pending[image] = scope.launch {
            try {
                val ids = withContext(Dispatchers.IO) { resolve(item) } ?: return@launch
                val poster = setting.image(ids, item.type == TvType.Movie) ?: return@launch
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

    private suspend fun resolve(item: SyncAPI.LibraryItem): PosterIds? {
        val local = DataStoreHelper.getBookmarkedData(item.id)?.takeIf { it.url == item.url }?.syncData
        fun ids(imdb: String?, tmdb: String?): PosterIds? {
            return PosterIds(
                imdb?.takeIf { IMDB.matches(it) },
                tmdb?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
            ).takeIf { it.available }
        }
        ids(local?.get("imdb"), local?.get("tmdb"))?.let { return it }
        val source = "${item.url} ${item.syncId}"
        ids(IMDB.find(source)?.value, TMDB.find(source)?.groupValues?.get(1))?.let { return it }
        val host = runCatching { URI(item.url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        val provider = when (host) {
            "simkl.com" -> "simkl"
            "anilist.co" -> "anilist"
            "myanimelist.net" -> "mal"
            "kitsu.app" -> "kitsu"
            else -> return null
        }
        val numeric = item.syncId.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: return null
        val cached = runCatching {
            val account = AccountManager.syncApis.firstOrNull { it.idPrefix == "simkl" }
            val userId = account?.authData()?.user?.id?.toString()
            val library = userId?.let {
                getKey<SimklApi.AllItemsResponse>(SimklApi.SIMKL_CACHED_LIST, it)
            }
            val all = library?.movies.orEmpty().map { it.movie.ids } +
                library?.shows.orEmpty().map { it.show.ids } +
                library?.anime.orEmpty().map { it.show.ids }
            all.firstOrNull {
                when (provider) {
                    "simkl" -> it.simkl.toString() == numeric
                    "mal" -> it.mal == numeric
                    "anilist" -> it.anilist == numeric
                    else -> false
                }
            }?.let { ids(it.imdb, it.tmdb) }
        }.getOrNull()
        if (cached != null) return cached
        if (provider == "simkl") return null

        val kind = when (provider) {
            "mal" -> "malId"; "anilist" -> "anilistId"; else -> "kitsuId"
        }
        val query = "idType=${kind}&idValue=${numeric}"
        val now = System.currentTimeMillis()
        fun fresh(entry: CacheEntry): Boolean {
            val ttl = if (entry.value == null) 900_000L else 86_400_000L
            return now - entry.time in 0 until ttl
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
                    } catch (_: Exception) { continue }
                    val root = runCatching { JSONObject(json) }.getOrNull() ?: continue
                    if (!root.optBoolean("success")) continue
                    if (root.isNull("data")) break
                    val mapped = root.optJSONObject("data")?.optJSONObject("mappings") ?: continue
                    if (mapped.optString(kind) != numeric) continue
                    ids(mapped.optString("imdbId"), mapped.optString("themoviedbId"))?.let {
                        return@withTimeoutOrNull it
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
        if (activity is MainActivity) use(activity)
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
        val IMDB = Regex("tt[0-9]{5,10}")
        val TMDB = Regex("tmdb:(?:(?:movie|series|tv):)?([0-9]+)")
        val ANIME_HOSTS = listOf("aiostreams.elfhosted.com",
            "aiostreams.fortheweak.cloud", "aiostreamsfortheweebsstable.midnightignite.me",
            "aio.atbphosting.com", "aiostreams.stremio.ru", "aiostreams.viren070.me")
    }
}
