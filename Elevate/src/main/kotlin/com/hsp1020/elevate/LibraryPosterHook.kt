package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
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
import java.net.URI
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
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
import coil3.request.crossfade
import coil3.request.placeholder
import org.json.JSONObject

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
        val sameItem = image.getTag(R.id.imageView) == id
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
                // Coil disposes the current request on replacement; preserve a cover for
                // the same title until its updated artwork is actually ready.
                val retained = image.drawable.takeIf { sameItem }
                bound[image] = entry
                image.loadImage(poster) {
                    if (retained != null) placeholder(retained)
                    crossfade(false)
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
            // A version refresh must not restore every visible cover to the original.
            // Existing artwork remains while the replacement is being loaded.
            bound.clear()
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

private class ApiKeyEyeIcon(private val iconColor: Int, private val visible: Boolean, private val iconSize: Int) : Drawable() {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = iconColor
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val scale = minOf(bounds.width(), bounds.height()) / 24f
            val saved = canvas.save()
            canvas.translate(bounds.exactCenterX() - 12f * scale, bounds.exactCenterY() - 12f * scale)
            canvas.scale(scale, scale)
            val outline = Path().apply {
                moveTo(2f, 12f)
                cubicTo(5f, 8f, 8f, 6f, 12f, 6f)
                cubicTo(16f, 6f, 19f, 8f, 22f, 12f)
                cubicTo(19f, 16f, 16f, 18f, 12f, 18f)
                cubicTo(8f, 18f, 5f, 16f, 2f, 12f)
                close()
            }
            canvas.drawPath(outline, pen)
            canvas.drawCircle(12f, 12f, 3f, pen)
            if (!visible) canvas.drawLine(3f, 21f, 21f, 3f, pen)
            canvas.restoreToCount(saved)
        }

        override fun setAlpha(alpha: Int) {
            pen.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            pen.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = iconSize
        override fun getIntrinsicHeight(): Int = iconSize
    }

internal enum class PosterService(val title: String, val host: String) {
    RPDB("RPDB", "api.ratingposterdb.com"),
    XRDB("XRDB", "xrdb.elfhosted.com"),
    OPDB("OpenPosterDB", "openposterdb.com"),
    AIORATINGS("AIORatings", "api.aioratings.com"),
    TOP_POSTERS("TOP Posters", "api.top-posters.com"),
    EASY_RATINGS("EasyRatingsDB", "easyratingsdb.com")
}

internal data class PosterIds(
    val imdb: String? = null,
    val tmdb: String? = null,
    val mal: String? = null,
    val anilist: String? = null,
    val kitsu: String? = null
) {
    val available get() = imdb != null || tmdb != null ||
        mal != null || anilist != null || kitsu != null
}

internal data class PosterConfig(
    val service: PosterService,
    val key: String,
    val xrdbProfile: String,
    val xrdbBase: String,
    val xrdbRenderKey: String
) {
    val ready get() = if (service == PosterService.XRDB) xrdbBase.isNotBlank() else key.isNotBlank()

    fun image(ids: PosterIds, isMovie: Boolean): String? {
        if (!ready || !ids.available) return null
        val kind = if (isMovie) "movie" else "series"
        if (service == PosterService.XRDB) {
            val base = Uri.parse(xrdbBase.trim().trimEnd('/'))
            if (base.scheme != "https" || base.host.isNullOrBlank() ||
                !base.userInfo.isNullOrBlank() || !base.query.isNullOrBlank() ||
                !base.fragment.isNullOrBlank()) return null
            val artworkId = ids.imdb
                ?: ids.tmdb?.let { "tmdb:${kind}:${it}" }
                ?: ids.mal?.let { "mal:${it}" }
                ?: ids.anilist?.let { "anilist:${it}" }
                ?: ids.kitsu?.let { "kitsu:${it}" }
                ?: return null
            return base.buildUpon().appendPath("poster").appendPath(artworkId)
                .apply {
                    if (xrdbProfile.isNotBlank()) appendQueryParameter("config", xrdbProfile)
                    if (xrdbRenderKey.isNotBlank()) appendQueryParameter("key", xrdbRenderKey)
                }.build().toString()
        }
        if (ids.imdb == null && ids.tmdb == null) return null
        val type = if (ids.imdb != null) "imdb" else "tmdb"
        val id = ids.imdb ?: "${kind}-${ids.tmdb ?: return null}"
        val url = when (service) {
            PosterService.RPDB, PosterService.OPDB, PosterService.AIORATINGS ->
                "https://${service.host}/${Uri.encode(key)}/${type}/poster-default/${id}.jpg"
            PosterService.TOP_POSTERS ->
                "https://${service.host}/${Uri.encode(key)}/${type}/poster/${id}.jpg"
            PosterService.EASY_RATINGS -> {
                val externalId = ids.imdb ?: "tmdb:${ids.tmdb ?: return null}"
                "https://${service.host}/${Uri.encode(key)}/poster/${externalId}.jpg?type=${kind}"
            }
            PosterService.XRDB -> return null
        }
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        return url.takeIf { parsed.scheme == "https" && !parsed.host.isNullOrBlank() }
    }
}

internal object LibraryPosterPrefs {
    const val FILE = "ElevateLibraryPosters"
    private const val DEFAULT_XRDB_BASE = "https://xrdb.elfhosted.com"

    fun get(context: Context): PosterConfig {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val legacyUrl = prefs.getString("template", "").orEmpty().trim()
        val legacy = runCatching { Uri.parse(legacyUrl).takeIf { it.scheme == "https" } }.getOrNull()
        val legacyBase = legacy?.host?.takeIf { it.isNotBlank() }?.let {
            "https://${it}${legacy.encodedPath?.substringBefore("/poster/").orEmpty()}"
        }
        return PosterConfig(
            PosterService.entries.firstOrNull { it.name == prefs.getString("service", null) }
                ?: PosterService.RPDB,
            prefs.getString("key", "").orEmpty().trim(),
            prefs.getString("xrdb_profile", null) ?: legacy?.getQueryParameter("config").orEmpty(),
            prefs.getString("xrdb_base", null) ?: legacyBase ?: DEFAULT_XRDB_BASE,
            prefs.getString("xrdb_render_key", null) ?: legacy?.getQueryParameter("key").orEmpty()
        )
    }

    fun controls(context: Context, content: LinearLayout, dp: (Int) -> Int): () -> Unit {
        val saved = get(context)
        fun title(parent: LinearLayout, label: String, smaller: Boolean = false) {
            parent.addView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (smaller) 12f else 15f)
                if (smaller) alpha = 0.75f
                setPadding(0, dp(8), 0, dp(4))
            })
        }
        fun field(value: String, hintText: String, secret: Boolean = false) =
            EditText(context).apply {
                setSingleLine(true)
                hint = hintText
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(value)
            }
        fun addField(parent: LinearLayout, field: EditText, secret: Boolean = false) {
            if (!secret) {
                parent.addView(field, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                return
            }
            val row = FrameLayout(context)
            row.addView(field, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            field.setPadding(field.paddingLeft, field.paddingTop,
                field.paddingRight + dp(48), field.paddingBottom)
            var revealed = false
            val toggle = ImageButton(context).apply {
                val ripple = TypedValue()
                if (context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless, ripple, true
                    ) && ripple.resourceId != 0) {
                    setBackgroundResource(ripple.resourceId)
                } else {
                    background = null
                }
                scaleType = ImageView.ScaleType.CENTER
                isFocusable = true
                isFocusableInTouchMode = false
                contentDescription = "Show API key"
                setImageDrawable(ApiKeyEyeIcon(field.currentTextColor, false, dp(24)))
                setOnClickListener {
                    val start = field.selectionStart.coerceAtLeast(0)
                    val end = field.selectionEnd.coerceAtLeast(0)
                    revealed = !revealed
                    field.transformationMethod =
                        if (revealed) null else PasswordTransformationMethod.getInstance()
                    val length = field.text?.length ?: 0
                    field.setSelection(start.coerceAtMost(length), end.coerceAtMost(length))
                    setImageDrawable(ApiKeyEyeIcon(field.currentTextColor, revealed, dp(24)))
                    contentDescription = if (revealed) "Hide API key" else "Show API key"
                }
            }
            row.addView(toggle, FrameLayout.LayoutParams(dp(48), dp(48),
                Gravity.END or Gravity.CENTER_VERTICAL))
            parent.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        title(content, "Library Poster Service")
        val service = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                PosterService.entries.map { it.title })
            setSelection(saved.service.ordinal)
        }
        content.addView(service)

        val keyGroup = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title(keyGroup, "Service API Key / Token")
        val key = field(saved.key, "API Key", secret = true)
        addField(keyGroup, key, secret = true)
        content.addView(keyGroup)

        val xrdbGroup = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title(xrdbGroup, "XRDB Profile UUID / Alias")
        val profile = field(saved.xrdbProfile, "Saved profile UUID or alias")
        addField(xrdbGroup, profile)
        title(xrdbGroup, "XRDB Server URL")
        val base = field(saved.xrdbBase, DEFAULT_XRDB_BASE)
        addField(xrdbGroup, base)
        title(xrdbGroup, "Profile is optional for the server's default appearance. " +
            "The public XRDB instance does not need an API key.", true)
        val privateKeyGroup = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title(privateKeyGroup, "Instance API Key (only for protected self-hosted XRDB)")
        val renderKey = field(saved.xrdbRenderKey, "XRDB_API_KEY", secret = true)
        addField(privateKeyGroup, renderKey, secret = true)
        xrdbGroup.addView(privateKeyGroup)
        content.addView(xrdbGroup)

        fun updateFields() {
            val xrdb = PosterService.entries[service.selectedItemPosition] == PosterService.XRDB
            keyGroup.visibility = if (xrdb) View.GONE else View.VISIBLE
            xrdbGroup.visibility = if (xrdb) View.VISIBLE else View.GONE
            val host = runCatching { Uri.parse(base.text.toString().trim()).host?.lowercase() }.getOrNull()
            privateKeyGroup.visibility = if (xrdb && host != null &&
                host !in setOf("xrdb.elfhosted.com", "extendedratings.com", "www.extendedratings.com")
            ) View.VISIBLE else View.GONE
        }
        service.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) = updateFields()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        base.doAfterTextChanged { updateFields() }
        updateFields()

        return {
            val isPublic = runCatching {
                Uri.parse(base.text.toString().trim()).host?.lowercase() in
                    setOf("xrdb.elfhosted.com", "extendedratings.com", "www.extendedratings.com")
            }.getOrDefault(false)
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("service", PosterService.entries[service.selectedItemPosition].name)
                .putString("key", key.text.toString().trim())
                .putString("xrdb_profile", profile.text.toString().trim())
                .putString("xrdb_base", base.text.toString().trim())
                .putString("xrdb_render_key", if (isPublic) "" else renderKey.text.toString().trim())
                .remove("template")
                .apply()
        }
    }
}
