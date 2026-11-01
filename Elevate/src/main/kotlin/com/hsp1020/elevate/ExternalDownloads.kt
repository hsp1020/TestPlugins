package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
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
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.safefile.SafeFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** A single external target; the download path is CloudStream's own General setting. */
internal object ExternalDownloads {
    private const val PREFS = "ElevateExternalDownloads"
    private const val TARGET = "selected_app"
    private const val PENDING = "pending"
    private val checking = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    data class App(val component: String, val label: String, val mode: String) {
        fun json() = JSONObject().put("component", component).put("label", label).put("mode", mode)
        companion object {
            fun parse(j: JSONObject) = App(j.getString("component"), j.getString("label"), j.getString("mode"))
        }
    }

    // Retain the old app selection when upgrading, but expose only one target.
    fun selected(c: Context): App? = runCatching {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(TARGET, null)?.let { App.parse(JSONObject(it)) }
            ?: JSONArray(prefs.getString("apps", "[]")).let { array ->
                if (array.length() == 0) null else App.parse(array.getJSONObject(0))
            }
    }.getOrNull()

    fun select(c: Context, app: App) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TARGET, app.json().toString()).apply()
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

    private fun pending(c: Context): List<Pending> = runCatching {
        val a = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING, "[]"))
        (0 until a.length()).mapNotNull { runCatching { Pending.parse(a.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    @Synchronized private fun addPending(c: Context, item: Pending) {
        val next = pending(c).filterNot { it.id == item.id }.takeLast(49) + item
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PENDING, JSONArray(next.map { it.json() }).toString()).apply()
    }

    @Synchronized private fun removePending(c: Context, id: Int) {
        val next = pending(c).filterNot { it.id == id }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PENDING, JSONArray(next.map { it.json() }).toString()).apply()
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

    private fun fromQueue(c: Context, queued: DownloadObjects.DownloadQueueWrapper, link: ExtractorLink): Pending {
        val item = queued.downloadItem
        val ep = item?.episode
        val resume = queued.resumePackage?.item?.ep
        val header = c.getKey<DownloadObjects.DownloadHeaderCached>(
            DOWNLOAD_HEADER_CACHE, queued.parentId.toString()
        )
        val filename = runCatching {
            Uri.decode(Uri.parse(link.url).lastPathSegment.orEmpty()).substringBefore('?').take(240)
        }.getOrDefault("")
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

    /** Direct handoff: no action dialog and no downloader-side status monitoring. */
    fun handoff(activity: Activity, queued: DownloadObjects.DownloadQueueWrapper, link: ExtractorLink) {
        val app = selected(activity) ?: return
        if (Uri.parse(link.url).scheme !in listOf("http", "https")) {
            toast(activity, "External download requires an HTTP(S) link")
            return
        }
        val component = ComponentName.unflattenFromString(app.component) ?: return
        val intent = if (app.mode == "view") {
            Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, link.url)
                putExtra(Intent.EXTRA_SUBJECT, link.name)
                putExtra(Intent.EXTRA_TITLE, link.name)
            }
        }.apply { this.component = component }
        try {
            activity.startActivity(intent)
            addPending(activity.applicationContext, fromQueue(activity, queued, link))
        } catch (e: Exception) {
            toast(activity, "Cannot open " + app.label + ": " + e.message)
        }
    }

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
        if (VideoDownloadManager.getDownloadFileInfo(c, p.id) == null) return
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

    /** Only inspect CloudStream's configured folder on app resume; never copy files or poll continuously. */
    fun checkFolder(c: Context) {
        if (!ElevateFeaturePrefs.enabled(c, ElevateFeature.EXTERNAL_DOWNLOADS) ||
            !checking.compareAndSet(false, true)) return
        Thread {
            try {
                val requests = pending(c)
                if (requests.isEmpty()) return@Thread
                val base = basePath(c)
                val dir = directory(c, base) ?: return@Thread
                val now = System.currentTimeMillis()
                val files = dir.listFiles().orEmpty().filter { file ->
                    isVideo(file.name().orEmpty()) &&
                        (file.lastModified() ?: 0L) >= (requests.minOf { it.created } - 1000L) &&
                        (file.lastModified() ?: now) < now - 15000L &&
                        (file.length() ?: 0L) > 1024L
                }
                for (p in requests) {
                    val fresh = files.filter { (it.lastModified() ?: 0L) >= p.created - 1000L }
                    val exact = fresh.filter { p.expectedFile.isNotBlank() &&
                        it.name().equals(p.expectedFile, ignoreCase = true) }
                    // When the downloader renames the file, auto-associate only if unambiguous.
                    val file = exact.singleOrNull() ?: if (requests.size == 1) fresh.singleOrNull() else null
                    if (file == null) continue
                    val size = file.length() ?: continue
                    Thread.sleep(1200)
                    if (file.length() == size &&
                        file.lastModified()?.let { it < System.currentTimeMillis() - 15000L } == true) {
                        runCatching { register(c, p, file.name() ?: return@runCatching, size, base) }
                            .onSuccess { toast(c, "Added to CloudStream Downloads: " + p.title) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ElevateExternal", "Cannot inspect CloudStream download folder", e)
            } finally {
                checking.set(false)
            }
        }.start()
    }
}

internal class ExternalDownloaderLifecycle(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    fun start() = app.registerActivityLifecycleCallbacks(this)
    fun stop() = app.unregisterActivityLifecycleCallbacks(this)
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) ExternalDownloads.checkFolder(app)
    }
    override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityPaused(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) = Unit
}
