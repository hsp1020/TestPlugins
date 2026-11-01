package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.safefile.SafeFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** A native episode-menu action, not a second download engine. */
internal class ExternalDownloadAction : VideoClickAction() {
    override val name = txt("External Download")
    override val oneSource = true
    override fun shouldShow(context: Context?, video: ResultEpisode?) =
        context != null && video != null &&
            ElevateFeaturePrefs.enabled(context, ElevateFeature.EXTERNAL_DOWNLOADS)

    override suspend fun runAction(
        context: Context?, video: ResultEpisode, result: LinkLoadingResult, index: Int?
    ) {
        val link = index?.let { result.links.getOrNull(it) } ?: return
        val activity = context as? androidx.fragment.app.FragmentActivity ?: return
        uiThread {
            ExternalDownloads.request = video to link
            ExternalDownloadDialog.show(activity.supportFragmentManager)
        }
    }
}

internal object ExternalDownloads {
    private const val PREFS = "ElevateExternalDownloads"
    private const val APPS = "apps"
    private const val PENDING = "pending"
    private const val FOLDER = "folder"
    var request: Pair<ResultEpisode, ExtractorLink>? = null
    private val checking = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    data class App(val component: String, val label: String, val mode: String) {
        fun json() = JSONObject().put("component", component).put("label", label).put("mode", mode)
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
            fun from(j: JSONObject) = Pending(
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

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun array(c: Context, key: String): JSONArray =
        runCatching { JSONArray(prefs(c).getString(key, "[]")) }.getOrDefault(JSONArray())

    @Synchronized fun apps(c: Context): List<App> = array(c, APPS).let { a ->
        (0 until a.length()).mapNotNull { i ->
            runCatching { a.getJSONObject(i).let { App(it.getString("component"), it.getString("label"), it.getString("mode")) } }.getOrNull()
        }
    }
    @Synchronized fun addApp(c: Context, app: App) {
        val current = apps(c).filterNot { it.component == app.component && it.mode == app.mode } + app
        prefs(c).edit().putString(APPS, JSONArray(current.map { it.json() }).toString()).apply()
    }
    @Synchronized fun removeApp(c: Context, app: App) {
        prefs(c).edit().putString(APPS, JSONArray(apps(c).filterNot { it == app }.map { it.json() }).toString()).apply()
    }
    @Synchronized fun pending(c: Context): List<Pending> = array(c, PENDING).let { a ->
        (0 until a.length()).mapNotNull { runCatching { Pending.from(a.getJSONObject(it)) }.getOrNull() }
    }
    @Synchronized fun addPending(c: Context, p: Pending) {
        val items = pending(c).filterNot { it.id == p.id }.takeLast(49) + p
        prefs(c).edit().putString(PENDING, JSONArray(items.map { it.json() }).toString()).apply()
    }
    @Synchronized fun removePending(c: Context, id: Int) {
        prefs(c).edit().putString(PENDING, JSONArray(pending(c).filterNot { it.id == id }.map { it.json() }).toString()).apply()
    }

    fun folder(c: Context): String? = prefs(c).getString(FOLDER, null)
    fun setFolder(c: Context, uri: Uri, flags: Int) {
        val grant = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(grant and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Folder read permission required" }
        c.contentResolver.takePersistableUriPermission(uri, grant)
        prefs(c).edit().putString(FOLDER, uri.toString()).apply()
    }

    fun fromRequest(c: Context, video: ResultEpisode, link: ExtractorLink): Pending {
        val header = c.getKey<DownloadObjects.DownloadHeaderCached>(
            DOWNLOAD_HEADER_CACHE, video.parentId.toString()
        )
        val filename = runCatching { Uri.decode(Uri.parse(link.url).lastPathSegment ?: "") }.getOrDefault("")
        return Pending(
            video.id, video.parentId, header?.name ?: video.headerName,
            video.name, video.episode, video.season, video.apiName,
            header?.url ?: "", (header?.type ?: video.tvType).name,
            header?.poster, video.poster, video.description,
            filename.substringBefore('?').take(240), System.currentTimeMillis()
        )
    }

    fun intent(link: ExtractorLink, mode: String): Intent {
        return if (mode == "view") {
            Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, link.url)
                putExtra(Intent.EXTRA_SUBJECT, link.name)
                putExtra(Intent.EXTRA_TITLE, link.name)
            }
        }
    }

    fun toast(c: Context, message: String) {
        main.post { Toast.makeText(c.applicationContext, message, Toast.LENGTH_LONG).show() }
    }

    private fun directory(c: Context, path: String?): SafeFile? = when {
        path.isNullOrBlank() -> com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getDefaultDir(c)
        path.startsWith("content://") -> SafeFile.fromUri(c, Uri.parse(path))
        else -> SafeFile.fromFilePath(c, path)
    }

    @Synchronized private fun register(c: Context, p: Pending, name: String, size: Long, basePath: String?, relative: String) {
        require(ElevateFeaturePrefs.enabled(c, ElevateFeature.EXTERNAL_DOWNLOADS)) { "External Downloads is disabled" }
        require(size > 1024L) { "The selected file is empty or incomplete" }
        require(c.getKey<DownloadObjects.DownloadedFileInfo>(
            VideoDownloadManager.KEY_DOWNLOAD_INFO, p.id.toString()
        )?.let { VideoDownloadManager.getDownloadFileInfo(c, p.id) } == null) {
            "This episode already has a registered download"
        }
        val type = TvType.valueOf(p.type)
        c.setKey(
            VideoDownloadManager.KEY_DOWNLOAD_INFO, p.id.toString(),
            DownloadObjects.DownloadedFileInfo(size, relative, name, basePath = basePath)
        )
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
                p.api, p.resultUrl, type, p.title, p.poster, System.currentTimeMillis(), p.parentId
            )
        )
        check(VideoDownloadManager.getDownloadFileInfo(c, p.id) != null) {
            "CloudStream could not read the registered file"
        }
        removePending(c, p.id)
    }

    private fun matchesDocument(c: Context, first: Uri?, second: Uri): Boolean {
        if (first == second) return true
        return runCatching {
            first != null && DocumentsContract.getDocumentId(first) ==
                DocumentsContract.getDocumentId(second) && first.authority == second.authority
        }.getOrDefault(false)
    }

    /** Use a granted shared folder without copying; otherwise copy once into the configured CloudStream directory. */
    fun importFile(c: Context, p: Pending, uri: Uri) {
        Thread {
            var copy: SafeFile? = null
            try {
                val source = SafeFile.fromUri(c, uri) ?: error("Cannot open selected file")
                val name = source.name()?.takeIf { isVideo(it) } ?: error("Choose a video file")
                val size = source.length()?.takeIf { it > 1024L } ?: error("Invalid video file")
                val shared = folder(c)
                val sharedFile = shared?.let { directory(c, it)?.findFile(name) }
                if (sharedFile != null && matchesDocument(c, sharedFile.uri(), uri)) {
                    register(c, p, name, size, shared, "")
                } else {
                    val setting = PreferenceManager.getDefaultSharedPreferences(c)
                        .getString(c.getString(R.string.download_path_key), null)
                    val parent = directory(c, setting)?.gotoDirectory("Elevate", true)
                        ?: error("CloudStream download directory unavailable")
                    val stem = name.substringBeforeLast('.')
                    val ext = name.substringAfterLast('.')
                    val unique = generateSequence(0) { it + 1 }.map { i ->
                        if (i == 0) name else "${stem} (${i}).${ext}"
                    }.first { parent.findFile(it) == null }
                    copy = parent.createFileOrThrow(unique)
                    c.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Cannot read selected file" }
                        copy!!.openOutputStreamOrThrow().use { output -> input.copyTo(output, 64 * 1024) }
                    }
                    check(copy!!.lengthOrThrow() == size) { "File copy was incomplete" }
                    register(c, p, unique, size, setting, "Elevate")
                    copy = null
                }
                toast(c, "Registered in CloudStream Downloads: ${p.title}")
            } catch (e: Exception) {
                copy?.delete()
                toast(c, "External download import failed: ${e.message}")
            }
        }.start()
    }

    private fun isVideo(name: String) =
        name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v")

    /** One bounded check on resume or on demand, never a download-state polling loop. */
    fun checkFolder(c: Context) {
        if (!ElevateFeaturePrefs.enabled(c, ElevateFeature.EXTERNAL_DOWNLOADS) ||
            folder(c) == null || pending(c).isEmpty() || !checking.compareAndSet(false, true)) return
        Thread {
            try {
                val basePath = folder(c) ?: return@Thread
                val dir = directory(c, basePath) ?: return@Thread
                val files = dir.listFiles().orEmpty().filter { isVideo(it.name().orEmpty()) }
                val now = System.currentTimeMillis()
                for (p in pending(c)) {
                    if (p.expectedFile.isBlank() || !isVideo(p.expectedFile)) continue
                    val matching = files.filter { it.name().equals(p.expectedFile, true) &&
                        (it.lastModified() ?: 0L) >= p.created - 1000L &&
                        (it.lastModified() ?: now) < now - 15000L &&
                        (it.length() ?: 0L) > 1024L }
                    if (matching.size != 1) continue
                    val file = matching.single()
                    val size = file.length() ?: continue
                    Thread.sleep(1200)
                    if (file.length() == size && file.lastModified()?.let { it < System.currentTimeMillis() - 15000L } == true) {
                        runCatching {
                            register(c, p, file.name() ?: return@runCatching, size, basePath, "")
                        }.onSuccess { toast(c, "External download detected: ${p.title}") }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ElevateExternal", "Could not scan download folder", e)
            } finally { checking.set(false) }
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

/** Registers user-selected intent targets, including browsers, without hard-coded package names. */
class ExternalDownloadDialog : DialogFragment() {
    companion object {
        private const val TAG = "ElevateExternalDownload"
        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) ExternalDownloadDialog().show(manager, TAG)
        }
        fun manage(manager: FragmentManager) {
            ExternalDownloads.request = null
            show(manager)
        }
    }

    private var pickingMode: String = "send"
    private var importingId: Int = 0
    private val appPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = result.data?.component
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            val name = runCatching {
                requireContext().packageManager.getActivityInfo(target, 0)
                    .loadLabel(requireContext().packageManager).toString()
            }.getOrDefault(target.packageName)
            ExternalDownloads.addApp(requireContext(), ExternalDownloads.App(target.flattenToString(), name, pickingMode))
            ExternalDownloads.toast(requireContext(), "Added downloader: ${name}")
        }
        reopen()
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }?.let { uri ->
            runCatching { ExternalDownloads.setFolder(requireContext(), uri, result.data?.flags ?: 0) }
                .onSuccess { ExternalDownloads.toast(requireContext(), "Download folder connected") }
                .onFailure { ExternalDownloads.toast(requireContext(), "Folder access failed: ${it.message}") }
        }
        reopen()
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val p = ExternalDownloads.pending(requireContext()).firstOrNull { it.id == importingId }
        if (result.resultCode == Activity.RESULT_OK && p != null) {
            result.data?.data?.let { ExternalDownloads.importFile(requireContext().applicationContext, p, it) }
        }
        reopen()
    }

    private fun reopen() {
        if (!isAdded) return
        val fm = parentFragmentManager
        dismissAllowingStateLoss()
        fm.executePendingTransactions()
        show(fm)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val request = ExternalDownloads.request
        val apps = ExternalDownloads.apps(ctx)
        val pending = ExternalDownloads.pending(ctx)
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        if (request != null) {
            for (app in apps) entries += app.label + " · " + app.mode to {
                launchTarget(app)
            }
            entries += "Choose download app (Share)" to { launchTarget(null, "send") }
            entries += "Choose browser (Open URL)" to { launchTarget(null, "view") }
        }
        entries += "Add app accepting shared links" to { chooseApp("send") }
        entries += "Add browser / URL app" to { chooseApp("view") }
        if (request == null) {
            entries += "Set download folder (for automatic recognition)" to {
                folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                })
            }
            entries += "Check download folder now" to {
                ExternalDownloads.checkFolder(ctx.applicationContext)
                ExternalDownloads.toast(ctx, "Checking connected download folder")
                dismiss()
            }
            for (p in pending) entries += "Import file: ${p.title} S${p.season ?: 0}E${p.episode}" to {
                importingId = p.id
                filePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                })
            }
            for (p in pending) entries += "Forget pending request: ${p.title} S${p.season ?: 0}E${p.episode}" to {
                ExternalDownloads.removePending(ctx, p.id)
                reopen()
            }
            for (app in apps) entries += "Remove app: ${app.label} · ${app.mode}" to {
                AlertDialog.Builder(ctx).setMessage("Remove ${app.label}?")
                    .setPositiveButton("Remove") { _, _ ->
                        ExternalDownloads.removeApp(ctx, app); reopen()
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (request != null) "External Download" else "External Downloaders")
            .setItems(entries.map { it.first }.toTypedArray(), null)
            .setNegativeButton("Close") { _, _ -> ExternalDownloads.request = null }
            .create()
        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, index, _ ->
                entries.getOrNull(index)?.second?.invoke()
            }
        }
        return dialog
    }

    private fun chooseApp(mode: String) {
        pickingMode = mode
        val sample = if (mode == "view") {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/video.mp4"))
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "https://example.com/video.mp4")
            }
        }
        appPicker.launch(Intent(Intent.ACTION_PICK_ACTIVITY).putExtra(Intent.EXTRA_INTENT, sample))
    }

    private fun launchTarget(app: ExternalDownloads.App?, mode: String = app?.mode ?: "send") {
        val (video, link) = ExternalDownloads.request ?: return
        val uri = Uri.parse(link.url)
        if (uri.scheme !in listOf("http", "https")) {
            ExternalDownloads.toast(requireContext(), "This source is not a direct HTTP(S) link")
            return
        }
        val intent = ExternalDownloads.intent(link, mode)
        if (app == null) {
            val chooser = Intent.createChooser(intent, "Download with")
            try {
                startActivity(chooser)
                ExternalDownloads.addPending(requireContext(), ExternalDownloads.fromRequest(requireContext(), video, link))
                ExternalDownloads.request = null
                dismiss()
            } catch (e: Exception) {
                ExternalDownloads.toast(requireContext(), "No compatible app: ${e.message}")
            }
        } else {
            val component = ComponentName.unflattenFromString(app.component) ?: return
            intent.component = component
            try {
                startActivity(intent)
                ExternalDownloads.addPending(requireContext(), ExternalDownloads.fromRequest(requireContext(), video, link))
                ExternalDownloads.request = null
                dismiss()
            } catch (e: Exception) {
                ExternalDownloads.toast(requireContext(), "Cannot open ${app.label}: ${e.message}")
            }
        }
    }
}
