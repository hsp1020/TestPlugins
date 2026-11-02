package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.database.ContentObserver
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.provider.MediaStore
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.safefile.SafeFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A single external target; the download path is CloudStream's own General setting. */
internal object ExternalDownloads {
    private const val PREFS = "ElevateExternalDownloads"
    private const val TARGET = "selected_app"
    private const val PENDING = "pending"
    internal fun trace(event: String) {
        Log.i("ExternalDLTrace", "up=" + SystemClock.elapsedRealtime() + " pid=" + android.os.Process.myPid() +
            " thread=" + Thread.currentThread().name + " " + event)
    }
    internal fun errorInfo(error: Throwable): String {
        val frame = error.stackTrace.firstOrNull()
        return error.javaClass.name + " at " + (frame?.className ?: "?") + ":" + (frame?.lineNumber ?: -1)
    }
    private val checking = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val scanner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingCache: LinkedHashMap<Int, Pending>? = null
    private var scheduled: Runnable? = null
    private var watchPath: String? = null
    private var fileObserver: FileObserver? = null
    private var contentObserver: ContentObserver? = null
    private var unstableRetries = 0
    private val supported = setOf(
        "idm.internet.download.manager", "idm.internet.download.manager.plus",
        "idm.internet.download.manager.adm.lite", "com.dv.adm"
    )

    data class App(val component: String, val label: String, val mode: String) {
        fun json() = JSONObject().put("component", component).put("label", label).put("mode", mode)
        companion object {
            fun parse(j: JSONObject) = App(j.getString("component"), j.getString("label"), j.getString("mode"))
        }
    }

    // Retain the old app selection when upgrading, but expose only one target.
    fun selected(c: Context): App? = runCatching {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        (prefs.getString(TARGET, null)?.let { App.parse(JSONObject(it)) }
            ?: JSONArray(prefs.getString("apps", "[]")).let { array ->
                if (array.length() == 0) null else App.parse(array.getJSONObject(0))
            })?.takeIf { ComponentName.unflattenFromString(it.component)?.packageName in supported }
    }.getOrNull()

    fun select(c: Context, app: App) {
        trace("selection.change target=" + app.label + " package=" + ComponentName.unflattenFromString(app.component)?.packageName)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TARGET, app.json().toString()).apply()
    }

    fun clearSelection(c: Context) {
        trace("selection.clear")
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(TARGET).remove("apps").apply()
    }

    data class Pending(
        val id: Int, val parentId: Int, val title: String, val episodeName: String?,
        val episode: Int, val season: Int?, val api: String, val resultUrl: String,
        val type: String, val poster: String?, val childPoster: String?,
        val description: String?, val expectedFile: String, val created: Long
    ) {
        fun json() = JSONObject().apply {
            put("id", id); put("parentId", parentId); put("title", title)
            put("episodeName", episodeName); put("episode", episode); put("season", season)
            put("api", api); put("resultUrl", resultUrl); put("type", type)
            put("poster", poster); put("childPoster", childPoster)
            put("description", description); put("expectedFile", expectedFile)
            put("created", created)
        }
        companion object {
            fun parse(j: JSONObject) = Pending(
                j.getInt("id"), j.getInt("parentId"), j.getString("title"),
                j.optString("episodeName").takeUnless { j.isNull("episodeName") },
                j.getInt("episode"), j.optInt("season").takeUnless { j.isNull("season") },
                j.getString("api"), j.optString("resultUrl"), j.getString("type"),
                j.optString("poster").takeUnless { j.isNull("poster") },
                j.optString("childPoster").takeUnless { j.isNull("childPoster") },
                j.optString("description").takeUnless { j.isNull("description") },
                j.optString("expectedFile"), j.optLong("created")
            )
        }
    }

    /** Deserialize only once per process; persist only actual changes. */
    @Synchronized private fun pendingMap(c: Context): LinkedHashMap<Int, Pending> {
        pendingCache?.let { return it }
        val result = LinkedHashMap<Int, Pending>()
        runCatching {
            val array = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PENDING, "[]"))
            for (i in 0 until array.length()) {
                val item = runCatching { Pending.parse(array.getJSONObject(i)) }.getOrNull() ?: continue
                if (item.created <= 0L || System.currentTimeMillis() - item.created > 30L * 24 * 60 * 60 * 1000) continue
                result[item.id] = item
            }
        }
        pendingCache = result
        return result
    }

    @Synchronized private fun pending(c: Context): List<Pending> =
        pendingMap(c).values.toList()

    @Synchronized private fun savePending(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PENDING, JSONArray(pendingMap(c).values.map { it.json() }).toString()).apply()
    }

    @Synchronized private fun addPendingBatch(c: Context, items: List<Pending>) {
        trace("pending.add ids=" + items.joinToString(",") { it.id.toString() } + " count=" + items.size)
        val map = pendingMap(c)
        for (item in items) map[item.id] = item
        unstableRetries = 0
        savePending(c)
        watch(c)
        scheduleCheck(c, 2400L)
        trace("pending.add.complete total=" + map.size)
    }

    @Synchronized private fun removePending(c: Context, id: Int) {
        if (pendingMap(c).remove(id) == null) {
            trace("pending.remove.missing id=" + id)
            return
        }
        trace("pending.remove id=" + id)
        savePending(c)
        watch(c)
    }

    fun toast(c: Context, message: String) {
        main.post { Toast.makeText(c.applicationContext, message, Toast.LENGTH_LONG).show() }
    }

    private fun basePath(c: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(c).getString(c.getString(R.string.download_path_key), null)

    private fun directory(c: Context, path: String?): SafeFile? = when {
        path.isNullOrBlank() -> DownloadFileManagement.getDefaultDir(c)
        path.startsWith("content://") -> SafeFile.fromUri(c, Uri.parse(path))
        else -> SafeFile.fromFilePath(c, path)
    }

    private fun fromQueue(c: Context, queued: DownloadObjects.DownloadQueueWrapper, filename: String): Pending {
        val item = queued.downloadItem
        val ep = item?.episode
        val resume = queued.resumePackage?.item?.ep
        val header = c.getKey<DownloadObjects.DownloadHeaderCached>(
            DOWNLOAD_HEADER_CACHE, queued.parentId.toString()
        )
        return Pending(
            queued.id, queued.parentId,
            item?.resultName ?: header?.name ?: resume?.mainName ?: ep?.headerName.orEmpty(),
            ep?.name ?: resume?.name,
            ep?.episode ?: resume?.episode ?: 0, ep?.season ?: resume?.season,
            item?.apiName ?: header?.apiName ?: resume?.sourceApiName.orEmpty(),
            item?.resultUrl ?: header?.url.orEmpty(),
            (item?.resultType ?: header?.type ?: resume?.type ?: ep?.tvType ?: TvType.Others).name,
            item?.resultPoster ?: header?.poster ?: resume?.poster,
            ep?.poster ?: resume?.poster, ep?.description, filename, System.currentTimeMillis()
        )
    }

    /** Deterministic episode name, independent of the URL basename. */
    private fun fileName(queued: DownloadObjects.DownloadQueueWrapper, link: ExtractorLink): String {
        val item = queued.downloadItem
        val ep = item?.episode
        val resume = queued.resumePackage?.item?.ep
        val title = item?.resultName ?: resume?.mainName ?: ep?.headerName ?: "Video"
        val season = ep?.season ?: resume?.season
        val episode = ep?.episode ?: resume?.episode
        val suffix = if (season != null && episode != null) {
            " S%02dE%02d".format(java.util.Locale.US, season, episode)
        } else ""
        val base = "$title$suffix [CS-${queued.id}]"
            .replace(Regex("""[\\/:*?"<>|]"""), " ").trim().take(190)
        val ext = if (link.isM3u8) "mp4" else {
            Uri.parse(link.url).lastPathSegment.orEmpty().substringAfterLast('.', "")
                .lowercase().takeIf { it in setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v") }
                ?: "mp4"
        }
        return "$base.$ext"
    }

    private data class Transfer(
        val queued: DownloadObjects.DownloadQueueWrapper,
        val link: ExtractorLink,
        val filename: String,
        val headers: Map<String, String>
    )

    private fun headersFor(link: ExtractorLink): Map<String, String> {
        val result = LinkedHashMap<String, String>(link.headers.size + 1)
        for ((key, value) in link.headers) result[key.lowercase(java.util.Locale.ROOT)] = value
        if (link.referer.isNotBlank() && "referer" !in result) result["referer"] = link.referer
        return result
    }

    /**
     * 1DM 20.0: url_list and url_list.filename arrays.
     * ADM 14.0.43: ACTION_LIST_ADD entries separated by <line>, each URL/name by <info>.
     * Transfer together only when the HTTP headers match: both apps use batch-wide headers.
     */
    fun handoffBatch(
        context: Context,
        requests: List<Pair<DownloadObjects.DownloadQueueWrapper, ExtractorLink>>
    ) {
        trace("handoff.enter count=" + requests.size + " ids=" +
            requests.joinToString(",") { it.first.id.toString() } + " context=" +
            System.identityHashCode(context) + " finishing=" + (context as? Activity)?.isFinishing +
            " destroyed=" + (context as? Activity)?.isDestroyed)
        val app = selected(context) ?: run { trace("handoff.noSelection"); return }
        val pkg = ComponentName.unflattenFromString(app.component)?.packageName ?: run {
            trace("handoff.badComponent"); return
        }
        trace("handoff.target package=" + pkg + " mode=" + app.mode)
        val transfers = requests.mapNotNull { (queued, link) ->
            val scheme = Uri.parse(link.url).scheme
            val transferHeaders = headersFor(link)
            trace("handoff.validate id=" + queued.id + " scheme=" + scheme + " hls=" + link.isM3u8 +
                " headerKeys=" + transferHeaders.keys.joinToString(","))
            if (scheme !in listOf("http", "https")) {
                trace("handoff.rejectedScheme id=" + queued.id)
                toast(context, "External download requires an HTTP(S) link")
                null
            } else Transfer(queued, link, fileName(queued, link), transferHeaders)
        }
        if (transfers.isEmpty()) {
            trace("handoff.noValidTransfers")
            return
        }
        trace("handoff.valid count=" + transfers.size)
        // Do not combine requests with different Referer/Cookie/User-Agent values.
        val groups = transfers.groupBy { it.headers }
        trace("handoff.groups count=" + groups.size + " sizes=" + groups.values.joinToString(",") { it.size.toString() })
        for ((headers, group) in groups) {
            val first = group.first()
            trace("handoff.group ids=" + group.joinToString(",") { it.queued.id.toString() } +
                " target=" + pkg + " count=" + group.size + " headerKeys=" + headers.keys.joinToString(","))
            val bundle = Bundle().apply { headers.forEach { (key, value) -> putString(key, value) } }
            val intent = if (pkg == "com.dv.adm") {
                Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(pkg, "com.dv.adm.AEditor")
                    putExtra("com.dv.get.ACTION_LIST_ADD", group.joinToString("<line>") {
                        "${it.link.url}<info>${it.filename}"
                    })
                    putExtra("com.dv.get.ACTION_LIST_OPEN", false)
                    basePath(context)?.takeIf { it.startsWith("/") }?.let {
                        putExtra("com.dv.get.ACTION_LIST_PATH", it)
                    }
                    if (headers.isNotEmpty()) putExtra("headers", bundle)
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(first.link.url)).apply {
                    component = ComponentName(pkg, "idm.internet.download.manager.Downloader")
                    putExtra("force_editor", false)
                    putExtra("hide_browser_option", true)
                    if (group.size == 1) putExtra("extra_filename", first.filename)
                    else {
                        putStringArrayListExtra("url_list", ArrayList(group.map { it.link.url }))
                        putStringArrayListExtra("url_list.filename", ArrayList(group.map { it.filename }))
                    }
                    if (headers.isNotEmpty()) putExtra("extra_headers", bundle)
                    headers["referer"]?.let { putExtra("extra_referer", it) }
                    headers["user-agent"]?.let { putExtra("extra_useragent", it) }
                    headers["cookie"]?.let { putExtra("extra_cookies", it) }
                }
            }
            try {
                trace("intent.launch.begin ids=" + group.joinToString(",") { it.queued.id.toString() } +
                    " package=" + pkg + " action=" + intent.action + " component=" + intent.component?.className +
                    " scheme=" + intent.data?.scheme + " queryLength=" + (intent.data?.encodedQuery?.length ?: 0) +
                    " extras=" + (intent.extras?.keySet()?.joinToString(",") ?: "none"))
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                trace("intent.launch.return ids=" + group.joinToString(",") { it.queued.id.toString() } +
                    " package=" + pkg + " note=acceptedByAndroidNotDownloadConfirmed")
                trace("handoff.pending.begin count=" + group.size)
                addPendingBatch(context.applicationContext, group.map {
                    fromQueue(context, it.queued, it.filename)
                })
                trace("handoff.pending.end count=" + group.size)
            } catch (e: Exception) {
                trace("intent.launch.error package=" + pkg + " " + errorInfo(e))
                toast(context, "Cannot open " + app.label + ": " + e.message)
            }
        }
        trace("handoff.exit count=" + transfers.size + " target=" + pkg)
    }

    fun handoff(activity: Activity, queued: DownloadObjects.DownloadQueueWrapper, link: ExtractorLink) =
        handoffBatch(activity, listOf(queued to link))

    @Synchronized private fun register(c: Context, p: Pending, name: String, size: Long, base: String?) {
        trace("recognition.register.begin id=" + p.id + " bytes=" + size)
        if (!ElevateFeaturePrefs.enabled(c, ElevateFeature.EXTERNAL_DOWNLOADS)) return
        if (VideoDownloadManager.getDownloadFileInfo(c, p.id) != null) {
            trace("recognition.register.alreadyPresent id=" + p.id)
            removePending(c, p.id)
            return
        }
        // The same metadata keys and filesystem resolution used by native downloads.
        c.setKey(
            VideoDownloadManager.KEY_DOWNLOAD_INFO, p.id.toString(),
            DownloadObjects.DownloadedFileInfo(size, "", name, basePath = base)
        )
        if (VideoDownloadManager.getDownloadFileInfo(c, p.id) == null) {
            trace("recognition.register.infoNotReadable id=" + p.id)
            return
        }
        c.setKey(
            getFolderName(DOWNLOAD_EPISODE_CACHE, p.parentId.toString()), p.id.toString(),
            DownloadObjects.DownloadEpisodeCached(
                p.episodeName, p.childPoster, p.episode, p.season, p.parentId,
                description = p.description, cacheTime = System.currentTimeMillis(), id = p.id
            )
        )
        c.setKey(
            DOWNLOAD_HEADER_CACHE, p.parentId.toString(),
            DownloadObjects.DownloadHeaderCached(
                p.api, p.resultUrl, TvType.valueOf(p.type), p.title, p.poster,
                System.currentTimeMillis(), p.parentId
            )
        )
        removePending(c, p.id)
        trace("recognition.register.complete id=" + p.id)
    }

    private fun isVideo(name: String) =
        name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v")

    /** One observer per pending directory; no periodic polling and no extra worker thread. */
    @Synchronized private fun watch(c: Context) {
        val path = basePath(c)
        val needed = pendingMap(c).isNotEmpty()
        val key = path ?: "@default"
        trace("watch.evaluate needed=" + needed + " mode=" +
            (if (path == null) "default" else if (path.startsWith("content://")) "SAF" else "file") +
            " existing=" + (watchPath != null))
        if (!needed) {
            stopWatching(c)
            return
        }
        if (watchPath == key) {
            trace("watch.unchanged")
            return
        }
        stopWatching(c)
        watchPath = key
        @Suppress("DEPRECATION")
        val physical = if (path.isNullOrBlank()) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        } else path.takeUnless { it.startsWith("content://") }
        if (physical != null) {
            runCatching {
                object : FileObserver(physical, FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE) {
                    override fun onEvent(event: Int, name: String?) {
                        if (name != null && isVideo(name)) {
                            trace("watch.file.event event=" + event + " type=video")
                            scheduleCheck(c, 2400L)
                        }
                    }
                }.also { it.startWatching() }
            }.onSuccess {
                fileObserver = it
                trace("watch.file.registered")
            }
                .onFailure { android.util.Log.w("ElevateExternal", "File observer unavailable", it) }
        }
        val uri = when {
            path?.startsWith("content://") == true -> Uri.parse(path)
            path.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else -> null
        }
        if (uri != null) {
            val observer = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    trace("watch.media.event selfChange=" + selfChange)
                    scheduleCheck(c, 2400L)
                }
            }
            runCatching { c.contentResolver.registerContentObserver(uri, true, observer) }
                .onSuccess {
                    contentObserver = observer
                    trace("watch.media.registered")
                }
        }
    }

    @Synchronized private fun stopWatching(c: Context) {
        trace("watch.stop file=" + (fileObserver != null) + " media=" + (contentObserver != null))
        fileObserver?.stopWatching()
        fileObserver = null
        contentObserver?.let { runCatching { c.contentResolver.unregisterContentObserver(it) } }
        contentObserver = null
        watchPath = null
        if (pendingCache?.isEmpty() == true) {
            scheduled?.let(main::removeCallbacks)
            scheduled = null
        }
    }

    @Synchronized private fun scheduleCheck(c: Context, delayMs: Long) {
        if (pendingMap(c).isEmpty()) {
            trace("scan.schedule.skip emptyPending")
            return
        }
        trace("scan.schedule pending=" + pendingMap(c).size + " delayMs=" + delayMs +
            " replacing=" + (scheduled != null))
        scheduled?.let(main::removeCallbacks)
        val task = Runnable {
            synchronized(this) { scheduled = null }
            checkFolder(c)
        }
        scheduled = task
        val posted = main.postDelayed(task, delayMs)
        trace("scan.schedule.posted ok=" + posted)
    }

    /** Event-driven scan with a single non-blocking stability delay per batch. */
    fun checkFolder(c: Context) {
        if (!ElevateFeaturePrefs.enabled(c, ElevateFeature.EXTERNAL_DOWNLOADS)) {
            trace("scan.skip disabled")
            return
        }
        if (!checking.compareAndSet(false, true)) {
            trace("scan.skip alreadyRunning")
            return
        }
        trace("scan.begin")
        scanner.launch {
            var unstableFile = false
            try {
                val requests = pending(c)
                if (requests.isEmpty()) {
                    trace("scan.emptyPending")
                    return@launch
                }
                val base = basePath(c)
                val dir = directory(c, base)
                if (dir == null) {
                    trace("scan.noDirectory pending=" + requests.size)
                    return@launch
                }
                trace("scan.directory.ready pending=" + requests.size + " mode=" +
                    (if (base == null) "default" else if (base.startsWith("content://")) "SAF" else "file"))
                // One directory listing, one lookup map, no nested filtering or per-file sleep.
                val byName = HashMap<String, Pending>(requests.size)
                for (p in requests) if (p.expectedFile.isNotBlank()) {
                    byName[p.expectedFile.lowercase(java.util.Locale.ROOT)] = p
                }
                val candidates = ArrayList<Triple<Pending, SafeFile, Long>>(requests.size)
                val now = System.currentTimeMillis()
                val files = dir.listFiles().orEmpty()
                trace("scan.list count=" + files.size + " pending=" + requests.size)
                for (file in files) {
                    val name = file.name() ?: continue
                    val pending = byName[name.lowercase(java.util.Locale.ROOT)] ?: continue
                    if (!isVideo(name)) {
                        trace("scan.reject.id=" + pending.id + " reason=notVideo")
                        continue
                    }
                    val size = file.length() ?: continue
                    if (size <= 1024L) {
                        trace("scan.reject.id=" + pending.id + " reason=size bytes=" + size)
                        unstableFile = true
                        continue
                    }
                    val modified = file.lastModified()
                    if (modified != null && (modified < pending.created - 2000L ||
                                modified > now - 1500L)) {
                        trace("scan.reject.id=" + pending.id + " reason=mtime deltaMs=" +
                            (now - modified))
                        if (modified >= pending.created - 2000L) unstableFile = true
                        continue
                    }
                    trace("scan.candidate id=" + pending.id + " bytes=" + size)
                    candidates.add(Triple(pending, file, size))
                }
                trace("scan.candidates count=" + candidates.size)
                if (candidates.isEmpty()) return@launch
                delay(750L)
                trace("scan.stabilityWait.done count=" + candidates.size)
                val stableAt = System.currentTimeMillis()
                for ((pending, file, size) in candidates) {
                    if (file.length() != size || size <= 1024L) {
                        trace("scan.unstable id=" + pending.id + " reason=size")
                        unstableFile = true
                        continue
                    }
                    val modified = file.lastModified()
                    if (modified != null && modified > stableAt - 1500L) {
                        trace("scan.unstable id=" + pending.id + " reason=mtime")
                        unstableFile = true
                        continue
                    }
                    val name = file.name() ?: continue
                    if (!name.equals(pending.expectedFile, ignoreCase = true)) {
                        trace("scan.unstable id=" + pending.id + " reason=nameMismatch")
                        continue
                    }
                    trace("scan.register.attempt id=" + pending.id + " bytes=" + size)
                    runCatching { register(c, pending, name, size, base) }
                        .onFailure {
                            trace("scan.register.error id=" + pending.id + " " + errorInfo(it))
                            android.util.Log.w("ElevateExternal", "Failed to register completed file", it)
                        }
                }
            } catch (e: Exception) {
                trace("scan.error " + errorInfo(e))
                android.util.Log.w("ElevateExternal", "Cannot inspect CloudStream download folder", e)
            } finally {
                checking.set(false)
                if (unstableFile && unstableRetries < 2) {
                    unstableRetries++
                    trace("scan.retry.unstable attempt=" + unstableRetries)
                    scheduleCheck(c, 6_000L)
                } else if (!unstableFile) unstableRetries = 0
                trace("scan.end")
            }
        }
    }

    fun startRecognition(c: Context) {
        trace("recognition.start")
        unstableRetries = 0
        watch(c)
        scheduleCheck(c, 2400L)
    }

    @Synchronized fun stopRecognition(c: Context) {
        trace("recognition.stop")
        unstableRetries = 0
        stopWatching(c)
        scheduled?.let(main::removeCallbacks)
        scheduled = null
        pendingCache = null
    }
}

internal class ExternalDownloaderLifecycle(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    fun start() {
        ExternalDownloads.trace("recognition.lifecycle.start")
        app.registerActivityLifecycleCallbacks(this)
        ExternalDownloads.startRecognition(app)
    }
    fun stop() {
        ExternalDownloads.trace("recognition.lifecycle.stop")
        ExternalDownloads.stopRecognition(app)
        app.unregisterActivityLifecycleCallbacks(this)
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) {
            ExternalDownloads.trace("recognition.lifecycle.resumed id=" + System.identityHashCode(activity))
            ExternalDownloads.startRecognition(app)
        }
    }
    override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityPaused(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) = Unit
}
