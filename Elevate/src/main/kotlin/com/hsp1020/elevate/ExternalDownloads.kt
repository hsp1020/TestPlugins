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
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.utils.SubtitleHelper
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
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TARGET, app.json().toString()).apply()
    }

    fun clearSelection(c: Context) {
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
        val map = pendingMap(c)
        for (item in items) map[item.id] = item
        unstableRetries = 0
        savePending(c)
        watch(c)
        scheduleCheck(c, 2400L)
    }

    @Synchronized private fun removePending(c: Context, id: Int) {
        if (pendingMap(c).remove(id) == null) {
            return
        }
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

    /** Keep video and subtitle stems identical for native offline subtitle discovery. */
    private fun episodeStem(id: Int, title: String, season: Int?, episode: Int?): String {
        val suffix = if (season != null && episode != null) {
            " S%02dE%02d".format(java.util.Locale.US, season, episode)
        } else ""
        return "$title$suffix [CS-$id]"
            .replace(Regex("""[\\/:*?"<>|]"""), " ").trim().take(190)
    }

    private fun fileName(queued: DownloadObjects.DownloadQueueWrapper, link: ExtractorLink): String {
        val item = queued.downloadItem
        val ep = item?.episode
        val resume = queued.resumePackage?.item?.ep
        val title = item?.resultName ?: resume?.mainName ?: ep?.headerName ?: "Video"
        val ext = if (link.isM3u8) "mp4" else {
            Uri.parse(link.url).lastPathSegment.orEmpty().substringAfterLast('.', "")
                .lowercase().takeIf { it in setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v") }
                ?: "mp4"
        }
        return episodeStem(queued.id, title, ep?.season ?: resume?.season,
            ep?.episode ?: resume?.episode) + ".$ext"
    }

    private data class Transfer(
        val queued: DownloadObjects.DownloadQueueWrapper?,
        val url: String,
        val filename: String,
        val headers: Map<String, String>
    )

    private fun subtitleTransfer(stem: String, subtitle: SubtitleData, label: String): Transfer {
        val suffix = label.replace(Regex("""[\\/:*?"<>|]"""), " ")
            .replace(Regex("\\s+"), " ").trim().take(100).ifBlank { "Subtitle" }
        val url = subtitle.getFixedUrl()
        val extension = if (url.contains(".srt", ignoreCase = true)) "srt" else "vtt"
        return Transfer(null, url, "$stem $suffix.$extension", subtitle.headers)
    }

    private fun matchingSubtitles(subs: List<SubtitleData>): List<SubtitleData> {
        val languages = SubtitlesFragment.getDownloadSubsLanguageTagIETF()
        return subs.filter { subtitle ->
            languages.any { language ->
                subtitle.languageCode == language || subtitle.originalName.contains(
                    SubtitleHelper.fromTagToEnglishLanguageName(language) ?: language
                )
            }
        }.take(3)
    }

    /** Manual subtitle downloads use the same external app as video downloads. */
    fun handoffManualSubtitle(
        context: Context, episode: ResultEpisode, subtitle: SubtitleData, label: String
    ): Boolean {
        val app = selected(context) ?: return false
        val stored = context.getKey<DownloadObjects.DownloadedFileInfo>(
            VideoDownloadManager.KEY_DOWNLOAD_INFO, episode.id.toString()
        )?.takeIf { it.relativePath.isEmpty() && it.basePath == basePath(context) }
        val current = stored?.displayName
            ?: pending(context).firstOrNull { it.id == episode.id }?.expectedFile
        val stem = current?.substringBeforeLast('.')
            ?: episodeStem(episode.id, episode.headerName, episode.season, episode.episode)
        return dispatch(context, app, listOf(subtitleTransfer(stem, subtitle, label)))
    }

    private fun headersFor(link: ExtractorLink): Map<String, String> {
        val result = LinkedHashMap<String, String>(link.headers.size + 1)
        for ((key, value) in link.headers) result[key.lowercase(java.util.Locale.ROOT)] = value
        if (link.referer.isNotBlank() && "referer" !in result) result["referer"] = link.referer
        return result
    }

    /**
     * Preserve the existing 1DM/ADM batch intent formats. Subtitles are sent
     * alongside video, grouped by HTTP headers; only videos enter pending recognition.
     */
    private fun dispatch(context: Context, app: App, transfers: List<Transfer>): Boolean {
        val pkg = ComponentName.unflattenFromString(app.component)?.packageName ?: return false
        val valid = transfers.filter { transfer ->
            val scheme = Uri.parse(transfer.url).scheme
            if (scheme !in listOf("http", "https")) {
                toast(context, "External download requires an HTTP(S) link")
                false
            } else true
        }
        if (valid.isEmpty()) return false
        var launched = false
        for ((headers, group) in valid.groupBy { it.headers }) {
            val first = group.first()
            val bundle = Bundle().apply { headers.forEach { (key, value) -> putString(key, value) } }
            val intent = if (pkg == "com.dv.adm") {
                Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(pkg, "com.dv.adm.AEditor")
                    putExtra("com.dv.get.ACTION_LIST_ADD", group.joinToString("<line>") {
                        "${it.url}<info>${it.filename}"
                    })
                    putExtra("com.dv.get.ACTION_LIST_OPEN", false)
                    basePath(context)?.takeIf { it.startsWith("/") }?.let {
                        putExtra("com.dv.get.ACTION_LIST_PATH", it)
                    }
                    if (headers.isNotEmpty()) putExtra("headers", bundle)
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(first.url)).apply {
                    component = ComponentName(pkg, "idm.internet.download.manager.Downloader")
                    putExtra("force_editor", false)
                    putExtra("hide_browser_option", true)
                    if (group.size == 1) putExtra("extra_filename", first.filename)
                    else {
                        putStringArrayListExtra("url_list", ArrayList(group.map { it.url }))
                        putStringArrayListExtra("url_list.filename", ArrayList(group.map { it.filename }))
                    }
                    if (headers.isNotEmpty()) putExtra("extra_headers", bundle)
                    headers["referer"]?.let { putExtra("extra_referer", it) }
                    headers["user-agent"]?.let { putExtra("extra_useragent", it) }
                    headers["cookie"]?.let { putExtra("extra_cookies", it) }
                }
            }
            try {
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                launched = true
                val videos = group.mapNotNull { transfer ->
                    transfer.queued?.let { fromQueue(context, it, transfer.filename) }
                }
                if (videos.isNotEmpty()) addPendingBatch(context.applicationContext, videos)
            } catch (e: Exception) {
                toast(context, "Cannot open " + app.label + ": " + e.message)
            }
        }
        return launched
    }

    fun handoffBatch(
        context: Context,
        requests: List<Pair<DownloadObjects.DownloadQueueWrapper, ExtractorLink>>,
        subtitles: Map<Int, List<SubtitleData>> = emptyMap()
    ) {
        val app = selected(context) ?: return
        val transfers = requests.flatMap { (queued, link) ->
            if (Uri.parse(link.url).scheme !in listOf("http", "https")) {
                toast(context, "External download requires an HTTP(S) link")
                return@flatMap emptyList()
            }
            val filename = fileName(queued, link)
            val video = Transfer(queued, link.url, filename, headersFor(link))
            val stem = filename.substringBeforeLast('.')
            val subs = matchingSubtitles(subtitles[queued.id].orEmpty())
            val duplicates = HashMap<String, Int>()
            listOf(video) + subs.map { sub ->
                val label = sub.name.trim().ifBlank { "Subtitle" }
                val count = (duplicates[label] ?: 0) + 1
                duplicates[label] = count
                subtitleTransfer(stem, sub, if (count == 1) label else "$label $count")
            }
        }
        dispatch(context, app, transfers)
    }

    fun handoff(activity: Activity, queued: DownloadObjects.DownloadQueueWrapper, link: ExtractorLink) =
        handoffBatch(activity, listOf(queued to link))

    @Synchronized private fun register(c: Context, p: Pending, name: String, size: Long, base: String?) {
        if (!ElevateFeaturePrefs.enabled(c, ElevateFeature.EXTERNAL_DOWNLOADS)) return
        if (VideoDownloadManager.getDownloadFileInfo(c, p.id) != null) {
            removePending(c, p.id)
            return
        }
        // The same metadata keys and filesystem resolution used by native downloads.
        c.setKey(
            VideoDownloadManager.KEY_DOWNLOAD_INFO, p.id.toString(),
            DownloadObjects.DownloadedFileInfo(size, "", name, basePath = base)
        )
        if (VideoDownloadManager.getDownloadFileInfo(c, p.id) == null) {
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
    }

    private fun isVideo(name: String) =
        name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v")

    /** One observer per pending directory; no periodic polling and no extra worker thread. */
    @Synchronized private fun watch(c: Context) {
        val path = basePath(c)
        val needed = pendingMap(c).isNotEmpty()
        val key = path ?: "@default"
        if (!needed) {
            stopWatching(c)
            return
        }
        if (watchPath == key) {
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
                            scheduleCheck(c, 2400L)
                        }
                    }
                }.also { it.startWatching() }
            }.onSuccess {
                fileObserver = it
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
                    scheduleCheck(c, 2400L)
                }
            }
            runCatching { c.contentResolver.registerContentObserver(uri, true, observer) }
                .onSuccess {
                    contentObserver = observer
                }
        }
    }

    @Synchronized private fun stopWatching(c: Context) {
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
            return
        }
        scheduled?.let(main::removeCallbacks)
        val task = Runnable {
            synchronized(this) { scheduled = null }
            checkFolder(c)
        }
        scheduled = task
        main.postDelayed(task, delayMs)
    }

    /** Event-driven scan with a single non-blocking stability delay per batch. */
    fun checkFolder(c: Context) {
        if (!ElevateFeaturePrefs.enabled(c, ElevateFeature.EXTERNAL_DOWNLOADS)) {
            return
        }
        if (!checking.compareAndSet(false, true)) {
            return
        }
        scanner.launch {
            var unstableFile = false
            try {
                val requests = pending(c)
                if (requests.isEmpty()) {
                    return@launch
                }
                val base = basePath(c)
                val dir = directory(c, base)
                if (dir == null) {
                    return@launch
                }
                // One directory listing, one lookup map, no nested filtering or per-file sleep.
                val byName = HashMap<String, Pending>(requests.size)
                for (p in requests) if (p.expectedFile.isNotBlank()) {
                    byName[p.expectedFile.lowercase(java.util.Locale.ROOT)] = p
                }
                val candidates = ArrayList<Triple<Pending, SafeFile, Long>>(requests.size)
                val now = System.currentTimeMillis()
                val files = dir.listFiles().orEmpty()
                for (file in files) {
                    val name = file.name() ?: continue
                    val pending = byName[name.lowercase(java.util.Locale.ROOT)] ?: continue
                    if (!isVideo(name)) {
                        continue
                    }
                    val size = file.length() ?: continue
                    if (size <= 1024L) {
                        unstableFile = true
                        continue
                    }
                    val modified = file.lastModified()
                    if (modified != null && (modified < pending.created - 2000L ||
                                modified > now - 1500L)) {
                        if (modified >= pending.created - 2000L) unstableFile = true
                        continue
                    }
                    candidates.add(Triple(pending, file, size))
                }
                if (candidates.isEmpty()) return@launch
                delay(750L)
                val stableAt = System.currentTimeMillis()
                for ((pending, file, size) in candidates) {
                    if (file.length() != size || size <= 1024L) {
                        unstableFile = true
                        continue
                    }
                    val modified = file.lastModified()
                    if (modified != null && modified > stableAt - 1500L) {
                        unstableFile = true
                        continue
                    }
                    val name = file.name() ?: continue
                    if (!name.equals(pending.expectedFile, ignoreCase = true)) {
                        continue
                    }
                    runCatching { register(c, pending, name, size, base) }
                        .onFailure {
                            android.util.Log.w("ElevateExternal", "Failed to register completed file", it)
                        }
                }
            } catch (e: Exception) {
                android.util.Log.w("ElevateExternal", "Cannot inspect CloudStream download folder", e)
            } finally {
                checking.set(false)
                if (unstableFile && unstableRetries < 2) {
                    unstableRetries++
                    scheduleCheck(c, 6_000L)
                } else if (!unstableFile) unstableRetries = 0
            }
        }
    }

    fun startRecognition(c: Context) {
        unstableRetries = 0
        watch(c)
        scheduleCheck(c, 2400L)
    }

    @Synchronized fun stopRecognition(c: Context) {
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
        app.registerActivityLifecycleCallbacks(this)
        ExternalDownloads.startRecognition(app)
    }
    fun stop() {
        ExternalDownloads.stopRecognition(app)
        app.unregisterActivityLifecycleCallbacks(this)
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) {
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
