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
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
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
import java.util.concurrent.atomic.AtomicInteger
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
    private val scanSequence = AtomicInteger()
    private val csFileMarker = Regex("""\[CS-(-?\d+)\]""")
    private fun trace(event: String, message: String) {
        android.util.Log.i("ElevateExternal", "$event $message")
    }
    private fun pathForLog(path: String?): String =
        path?.substringBefore('?')?.substringBefore('#') ?: "@default"
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

    fun selected(c: Context): App? = runCatching {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TARGET, null)?.let { App.parse(JSONObject(it)) }
            ?.takeIf { ComponentName.unflattenFromString(it.component)?.packageName in supported }
    }.getOrNull()

    fun select(c: Context, app: App) {
        trace("APP_SELECTED", "app=${app.label} component=${app.component}")
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TARGET, app.json().toString()).apply()
    }

    fun clearSelection(c: Context) {
        trace("APP_CLEARED", "selectionRemoved=true")
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(TARGET).apply()
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
            trace("PENDING_LOAD", "stored=${array.length()}")
            for (i in 0 until array.length()) {
                val item = runCatching { Pending.parse(array.getJSONObject(i)) }
                    .onFailure { android.util.Log.w("ElevateExternal", "PENDING_PARSE_FAIL index=$i", it) }
                    .getOrNull() ?: continue
                if (item.created <= 0L || System.currentTimeMillis() - item.created > 30L * 24 * 60 * 60 * 1000) {
                    trace("PENDING_EXPIRED", "index=$i id=${item.id} created=${item.created}")
                    continue
                }
                result[item.id] = item
                trace("PENDING_RESTORED", "index=$i id=${item.id} parent=${item.parentId} expected=${item.expectedFile} created=${item.created}")
            }
        }.onFailure { android.util.Log.w("ElevateExternal", "PENDING_LOAD_FAIL", it) }
        // The old recovery archive is no longer used; discard it without touching Pending.
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { prefs ->
            if (prefs.contains("known_downloads")) {
                prefs.edit().remove("known_downloads").apply()
                trace("LEGACY_ARCHIVE_CLEARED", "pending=${result.size}")
            }
        }
        trace("PENDING_READY", "count=${result.size}")
        pendingCache = result
        return result
    }

    @Synchronized private fun pending(c: Context): List<Pending> =
        pendingMap(c).values.toList()

    /** Keep current handoffs durable before launching the external downloader. */
    @Synchronized private fun savePending(c: Context): Boolean {
        val persisted = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PENDING, JSONArray(pendingMap(c).values.map { it.json() }).toString())
            .commit()
        trace("PENDING_SAVED", "success=$persisted pending=${pendingMap(c).size}")
        return persisted
    }

    @Synchronized private fun addPendingBatch(c: Context, items: List<Pending>) {
        if (items.isEmpty()) return
        val map = pendingMap(c)
        val oldPending = LinkedHashMap(map)
        trace("PENDING_BATCH", "added=${items.size} before=${map.size}")
        for (item in items) {
            val replaced = map.put(item.id, item)
            trace("PENDING_ADD", "id=${item.id} parent=${item.parentId} season=${item.season} episode=${item.episode} expected=${item.expectedFile} replaced=${replaced != null} created=${item.created}")
        }
        if (!savePending(c)) {
            map.clear(); map.putAll(oldPending)
            throw IllegalStateException("Unable to persist external download metadata")
        }
        unstableRetries = 0
        watch(c)
        scheduleCheck(c, 2400L)
    }

    @Synchronized private fun removePending(c: Context, id: Int) {
        val map = pendingMap(c)
        val removed = map.remove(id)
        trace("PENDING_REMOVE", "id=$id existed=${removed != null} remaining=${map.size}")
        if (removed == null) return
        if (!savePending(c)) {
            map[id] = removed
            throw IllegalStateException("Unable to persist external download registration")
        }
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

    /** Sanitize unsafe filesystem characters while keeping legitimate release-name brackets. */
    private fun safePart(raw: String, maxBytes: Int): String {
        var part = raw.replace(Regex("""[\u0000-\u001F\\/:*?"<>|]"""), " ")
            .replace(Regex("""\s+"""), " ").trim().ifBlank { "Source" }
        while (part.toByteArray(Charsets.UTF_8).size > maxBytes && part.isNotEmpty()) {
            val last = part.lastIndex
            part = part.dropLast(if (last > 0 && Character.isLowSurrogate(part[last]) &&
                Character.isHighSurrogate(part[last - 1])) 2 else 1)
        }
        return part.trim()
    }

    private fun episodeStem(id: Int, title: String, season: Int?, episode: Int?): String {
        val suffix = if (season != null && episode != null) {
            " S%02dE%02d".format(java.util.Locale.US, season, episode)
        } else ""
        return "${safePart(title + suffix, 80)} [CS-$id]"
    }

    private val videoExts = setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v")
    private val filenameInLine = Regex(
        """\.(?:mkv|mp4|webm|avi|mov|ts|m4v)(?=$|[\s)\]])""",
        RegexOption.IGNORE_CASE
    )
    private val genericUrlName = Regex(
        """(?i)(?:index|playlist|master|manifest|stream|video|download|playback|file)(?:[-_.]?\d+)?"""
    )

    /** Look up exact selected link metadata without modifying its visible name. */
    private fun stremioFilename(link: ExtractorLink): String? = runCatching {
        val method = APIHolder::class.java.methods.firstOrNull { it.name == "getAllProviders" }
        val provider = (method?.invoke(APIHolder) as? Iterable<*>)?.firstOrNull {
            it?.javaClass?.name == "com.hsp1020.StremioC"
        } ?: return@runCatching null
        val field = provider.javaClass.getDeclaredField("streamFilenameByLink")
            .apply { isAccessible = true }
        val cache = field.get(null) as? Map<*, *>
        cache?.get(link.url + "\u0000" + link.name) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Only a clear video filename, not the full multiline source description. */
    private fun filenameInDescription(name: String): String? =
        name.lineSequence().mapNotNull { line ->
            val text = line.trim()
            if ("://" in text) return@mapNotNull null
            val extension = filenameInLine.findAll(text).lastOrNull() ?: return@mapNotNull null
            val filename = text.substring(0, extension.range.last + 1)
                .replace(Regex("""^[^\p{L}\p{N}\[\](_.-]+"""), "").trim()
            filename.takeIf { it.length >= 6 && it.any(Char::isLetter) }
        }.firstOrNull()

    /** Reject CDN tokens and generic endpoints rather than presenting them as file titles. */
    private fun filenameInUrl(url: String): String? = runCatching {
        val uri = Uri.parse(url)
        if (uri.scheme !in listOf("http", "https")) return@runCatching null
        val filename = uri.lastPathSegment?.trim().orEmpty()
        val ext = filename.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val stem = filename.substringBeforeLast('.', filename)
        filename.takeIf {
            ext in videoExts && stem.length >= 6 && stem.any(Char::isLetter) &&
                !genericUrlName.matches(stem) &&
                !Regex("""(?i)[a-f0-9_-]{16,}""").matches(stem)
        }
    }.getOrNull()

    private fun fallbackSourceName(link: ExtractorLink): String {
        val quality = Qualities.getStringByInt(link.quality).trim()
            .takeUnless { link.quality == Qualities.Unknown.value || it.equals("Unknown", true) }
            ?: Regex("""(?i)(?<!\d)(?:2160|1440|1080|720|576|480|360|240)\s*p\b""")
                .find(link.name)?.value?.replace(" ", "")?.lowercase(java.util.Locale.ROOT)
        return listOfNotNull(link.source.trim().takeIf { it.isNotBlank() } ?: "Source",
            quality?.takeIf { it.isNotBlank() }).joinToString(" ")
    }

    private fun fileName(queued: DownloadObjects.DownloadQueueWrapper, link: ExtractorLink): String {
        val item = queued.downloadItem
        val ep = item?.episode
        val resume = queued.resumePackage?.item?.ep
        val title = item?.resultName ?: resume?.mainName ?: ep?.headerName ?: "Video"
        val prefix = episodeStem(queued.id, title, ep?.season ?: resume?.season,
            ep?.episode ?: resume?.episode)
        // Standard Stremio metadata -> displayed original file -> media URL -> source + quality.
        val original = stremioFilename(link) ?: filenameInDescription(link.name)
            ?: filenameInUrl(link.url) ?: fallbackSourceName(link)
        val leaf = original.substringAfterLast('/').substringAfterLast('\\').trim()
        val extension = leaf.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            .takeIf { it in videoExts }
        val name = if (extension != null) leaf.substringBeforeLast('.') else leaf
        // HLS output is MP4 even if an addon declares another container.
        val outputExt = if (link.isM3u8) "mp4" else extension ?: "mp4"
        // Reserve room for a subtitle language/index suffix within Android's 255-byte limit.
        val available = (210 - prefix.toByteArray(Charsets.UTF_8).size - 1).coerceAtLeast(1)
        return "$prefix ${safePart(name, available)}.$outputExt"
    }

    /** Strip the video source filename while retaining the episode's stable CS identity. */
    private fun subtitleStemFromVideo(id: Int, videoName: String): String? {
        val marker = "[CS-$id"
        if (marker !in videoName) return null
        val title = videoName.substringBefore(marker).trimEnd()
        return title.takeIf { it.isNotEmpty() }?.let { "$it [CS-$id]" }
    }

    private data class Transfer(
        val queued: DownloadObjects.DownloadQueueWrapper?,
        val url: String,
        val filename: String,
        val headers: Map<String, String>
    )

    private fun subtitleTransfer(stem: String, subtitle: SubtitleData, label: String): Transfer {
        val url = subtitle.getFixedUrl()
        val extension = if (url.contains(".srt", ignoreCase = true)) "srt" else "vtt"
        val available = (255 - stem.toByteArray(Charsets.UTF_8).size -
            extension.toByteArray(Charsets.UTF_8).size - 2).coerceAtLeast(1)
        val suffix = safePart(label, available)
        return Transfer(null, url, "$stem $suffix.$extension", subtitle.headers)
    }

    internal data class SubtitleChoice(
        val subtitle: SubtitleData, val title: String, val originalIndex: Int
    )

    private val numericEntity = Regex("&#(?:x([0-9a-fA-F]+)|([0-9]+));")

    internal fun displayName(raw: String): String {
        val input = raw.replace("&amp;#", "&#", ignoreCase = true)
        if (!input.contains("&#")) return raw
        return numericEntity.replace(input) { match ->
            val hex = match.groupValues[1]
            val cp = (if (hex.isNotEmpty()) hex.toIntOrNull(16)
                else match.groupValues[2].toIntOrNull())
            if (cp == null || !Character.isValidCodePoint(cp) || cp in 0xD800..0xDFFF)
                match.value else String(Character.toChars(cp))
        }
    }

    /** Use exactly the same display title for manual and automatic subtitle downloads. */
    internal fun subtitleChoices(context: Context, subs: List<SubtitleData>): List<SubtitleChoice> {
        val ranks = SubtitleLanguageNormalizer.stremioRanks()
        val details = SubtitleLanguageNormalizer.stremioDetailCache()
        val localize = ElevateFeaturePrefs.enabled(context, ElevateFeature.SUBTITLE_LANGUAGE_NAMES)
        val localized = subs.map { sub ->
            if (localize) SubtitleLanguageNormalizer.localized(sub, ranks) else sub
        }
        return subs.indices.filter { SubtitleLanguageNormalizer.isSelectable(subs[it]) }
            .groupBy { displayName(localized[it].originalName) }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER).flatMap { (language, indices) ->
                indices.sortedWith(compareBy<Int> {
                    (ranks?.get(subs[it].url) as? Number)?.toInt() ?: Int.MAX_VALUE
                }.thenBy { it }).mapIndexed { position, index ->
                    val suffix = displayName(localized[index].nameSuffix)
                    val detail = details?.get(subs[index].url)?.takeIf { it.isNotBlank() }
                        ?: suffix.takeIf { it.isNotBlank() && it.toIntOrNull() == null }
                    val title = language + " " + (position + 1) +
                        (detail?.let { "($it)" } ?: "")
                    SubtitleChoice(subs[index], title, index)
                }
            }
    }

    private fun matchingSubtitles(context: Context, subs: List<SubtitleData>): List<SubtitleChoice> {
        val languages = SubtitlesFragment.getDownloadSubsLanguageTagIETF()
        val ranks = SubtitleLanguageNormalizer.stremioRanks()
        return subtitleChoices(context, subs).filter { choice ->
            val sub = choice.subtitle
            !sub.originalName.startsWith("Make ", true) &&
                !sub.originalName.startsWith("Translate ", true) &&
                languages.any { language ->
                    sub.getIETF_tag()?.equals(language, true) == true ||
                        sub.originalName.contains(
                            SubtitleHelper.fromTagToEnglishLanguageName(language) ?: language, true
                        )
                }
        }.sortedWith(compareBy<SubtitleChoice> {
            (ranks?.get(it.subtitle.url) as? Number)?.toInt() ?: Int.MAX_VALUE
        }.thenBy { it.originalIndex }).take(5)
    }

    /** Manual subtitle downloads use the same external app as video downloads. */
    fun handoffManualSubtitle(
        context: Context, episode: ResultEpisode, title: String,
        subtitle: SubtitleData, shownTitle: String
    ): Boolean {
        val app = selected(context) ?: return false
        // Reuse the downloaded video's actual title prefix, not its source filename.
        val existing = pending(context).firstOrNull { it.id == episode.id }?.expectedFile
            ?: context.getKey<DownloadObjects.DownloadedFileInfo>(
                VideoDownloadManager.KEY_DOWNLOAD_INFO, episode.id.toString()
            )?.takeIf {
                it.relativePath.isEmpty() && it.basePath == basePath(context) &&
                    (it.displayName.contains("[CS-" + episode.id + "]") ||
                        it.displayName.contains("[CS-" + episode.id + "--"))
            }?.displayName
        val stem = existing?.let { subtitleStemFromVideo(episode.id, it) }
            ?: episodeStem(episode.id, title, episode.season, episode.episode)
        return dispatch(context, app, listOf(subtitleTransfer(stem, subtitle, shownTitle)))
    }

    private fun headersFor(link: ExtractorLink): Map<String, String> {
        val result = LinkedHashMap<String, String>(link.headers.size + 1)
        for ((key, value) in link.headers) result[key.lowercase(java.util.Locale.ROOT)] = value
        if (link.referer.isNotBlank() && "referer" !in result) result["referer"] = link.referer
        return result
    }

    /** Match ADM's 128-character filename limit while preserving short extensions. */
    private fun admSavedName(name: String): String {
        if (name.length <= 128) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot >= 0 && name.length - dot in 2..8) name.substring(dot) else ""
        return name.take(128 - ext.length) + ext
    }

    /**
     * Preserve the existing 1DM/ADM batch intent formats. Subtitles are sent
     * alongside video, grouped by HTTP headers; only videos enter pending recognition.
     */
    private fun dispatch(context: Context, app: App, transfers: List<Transfer>): Boolean {
        val pkg = ComponentName.unflattenFromString(app.component)?.packageName ?: return false
        trace("HANDOFF_BEGIN", "app=${app.label} pkg=$pkg transfers=${transfers.size} videos=${transfers.count { it.queued != null }} folder=${pathForLog(basePath(context))}")
        for (transfer in transfers) {
            trace("HANDOFF_TRANSFER", "id=${transfer.queued?.id} file=${transfer.filename} scheme=${Uri.parse(transfer.url).scheme} headerKeys=${transfer.headers.keys}")
        }
        val valid = transfers.filter { transfer ->
            val scheme = Uri.parse(transfer.url).scheme
            if (scheme !in listOf("http", "https")) {
                trace("HANDOFF_REJECT", "file=${transfer.filename} scheme=$scheme")
                toast(context, "External download requires an HTTP(S) link")
                false
            } else true
        }
        if (valid.isEmpty()) {
            trace("HANDOFF_EMPTY", "reason=noValidTransfers")
            return false
        }
        var launched = false
        for ((headers, group) in valid.groupBy { it.headers }) {
            trace("HANDOFF_GROUP", "pkg=$pkg count=${group.size} headerKeys=${headers.keys}")
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
            val videos = group.mapNotNull { transfer ->
                transfer.queued?.let {
                    fromQueue(context, it,
                        if (pkg == "com.dv.adm") admSavedName(transfer.filename)
                        else transfer.filename)
                }
            }
            val previous = pending(context).associateBy { it.id }
            var saved = false
            try {
                // Never start an external Activity before identity + expected filename are durable.
                if (videos.isNotEmpty()) {
                    addPendingBatch(context.applicationContext, videos)
                    saved = true
                }
                trace("HANDOFF_PENDINGS", "count=${videos.size} saved=$saved ids=${videos.map { it.id }}")
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                launched = true
                trace("HANDOFF_LAUNCHED", "pkg=$pkg count=${group.size} context=${context.javaClass.simpleName}")
            } catch (e: Exception) {
                if (saved) {
                    runCatching {
                        for (video in videos) {
                            val old = previous[video.id]
                            if (old != null) addPendingBatch(context.applicationContext, listOf(old))
                            else removePending(context.applicationContext, video.id)
                        }
                    }.onFailure { android.util.Log.w("ElevateExternal", "HANDOFF_ROLLBACK_FAILED", it) }
                }
                android.util.Log.w("ElevateExternal", "HANDOFF_FAILED app=${app.label}", e)
                toast(context, "Cannot open " + app.label + ": " + e.message)
            }
        }
        trace("HANDOFF_END", "launched=$launched")
        return launched
    }

    fun handoffBatch(
        context: Context,
        requests: List<Pair<DownloadObjects.DownloadQueueWrapper, ExtractorLink>>,
        subtitles: Map<Int, List<SubtitleData>> = emptyMap()
    ) {
        val app = selected(context) ?: return
        trace("BATCH_PREPARE", "app=${app.label} requests=${requests.size} ids=${requests.map { it.first.id }}")
        val transfers = requests.flatMap { (queued, link) ->
            if (Uri.parse(link.url).scheme !in listOf("http", "https")) {
                toast(context, "External download requires an HTTP(S) link")
                return@flatMap emptyList()
            }
            val filename = fileName(queued, link)
            trace("BATCH_VIDEO", "id=${queued.id} parent=${queued.parentId} filename=$filename urlScheme=${Uri.parse(link.url).scheme} isM3u8=${link.isM3u8}")
            val video = Transfer(queued, link.url, filename, headersFor(link))
            val stem = subtitleStemFromVideo(queued.id, filename)
                ?: filename.substringBeforeLast('.')
            val subs = matchingSubtitles(context, subtitles[queued.id].orEmpty())
            trace("BATCH_SUBTITLES", "id=${queued.id} available=${subtitles[queued.id].orEmpty().size} selected=${subs.size}")
            listOf(video) + subs.map { choice ->
                subtitleTransfer(stem, choice.subtitle, choice.title)
            }
        }
        dispatch(context, app, transfers)
    }

    fun handoff(activity: Activity, queued: DownloadObjects.DownloadQueueWrapper, link: ExtractorLink) =
        handoffBatch(activity, listOf(queued to link))

    @Synchronized private fun register(
        c: Context, p: Pending, name: String, size: Long, base: String?, relativePath: String
    ): Boolean {
        trace("REGISTER_BEGIN", "id=${p.id} parent=${p.parentId} name=$name bytes=$size base=${pathForLog(base)} relative=$relativePath")
        if (!ElevateFeaturePrefs.enabled(c, ElevateFeature.EXTERNAL_DOWNLOADS)) return false
        val epKey = getFolderName(DOWNLOAD_EPISODE_CACHE, p.parentId.toString())
        val existingEpisode = c.getKey<DownloadObjects.DownloadEpisodeCached>(epKey, p.id.toString())
        val existingHeader = c.getKey<DownloadObjects.DownloadHeaderCached>(
            DOWNLOAD_HEADER_CACHE, p.parentId.toString())
        val existingFile = c.getKey<DownloadObjects.DownloadedFileInfo>(
            VideoDownloadManager.KEY_DOWNLOAD_INFO, p.id.toString())
        val fileInfo = VideoDownloadManager.getDownloadFileInfo(c, p.id)
        if (fileInfo != null && existingFile != null &&
            (existingFile.displayName != name || existingFile.relativePath != relativePath)) {
            trace("REGISTER_CONFLICT", "id=${p.id} existing=${existingFile.displayName} existingRelative=${existingFile.relativePath}")
            return false
        }
        if (existingEpisode != null && (existingEpisode.id != p.id || existingEpisode.parentId != p.parentId) ||
            existingHeader != null && existingHeader.id != p.parentId) {
            trace("REGISTER_CONFLICT", "id=${p.id} reason=metadataIdMismatch")
            return false
        }
        // Header and episode metadata must be durable before the file becomes visible in Library.
        if (existingEpisode == null) {
            c.setKey(
                epKey, p.id.toString(),
                DownloadObjects.DownloadEpisodeCached(
                    p.episodeName, p.childPoster, p.episode, p.season, p.parentId,
                    description = p.description, cacheTime = System.currentTimeMillis(), id = p.id
                )
            )
        }
        if (existingHeader == null) {
            val type = runCatching { TvType.valueOf(p.type) }.getOrNull()
            if (type == null || p.title.isBlank() || p.api.isBlank()) {
                trace("REGISTER_METADATA_MISSING", "id=${p.id} type=${p.type} hasTitle=${p.title.isNotBlank()} hasApi=${p.api.isNotBlank()}")
                return false
            }
            c.setKey(
                DOWNLOAD_HEADER_CACHE, p.parentId.toString(),
                DownloadObjects.DownloadHeaderCached(
                    p.api, p.resultUrl, type, p.title, p.poster,
                    System.currentTimeMillis(), p.parentId
                )
            )
        }
        val verifyEpisode = c.getKey<DownloadObjects.DownloadEpisodeCached>(epKey, p.id.toString())
        val verifyHeader = c.getKey<DownloadObjects.DownloadHeaderCached>(
            DOWNLOAD_HEADER_CACHE, p.parentId.toString())
        if (verifyEpisode?.id != p.id || verifyEpisode?.parentId != p.parentId ||
            verifyHeader?.id != p.parentId) {
            trace("REGISTER_VERIFY_FAIL", "id=${p.id} reason=episodeOrHeader missingEpisode=${verifyEpisode == null} missingHeader=${verifyHeader == null}")
            return false
        }
        if (fileInfo == null) {
            c.setKey(
                VideoDownloadManager.KEY_DOWNLOAD_INFO, p.id.toString(),
                DownloadObjects.DownloadedFileInfo(size, relativePath, name, basePath = base)
            )
        }
        val verified = VideoDownloadManager.getDownloadFileInfo(c, p.id)
        if (verified == null || verified.fileLength <= 1024L) {
            trace("REGISTER_VERIFY_FAIL", "id=${p.id} reason=downloadInfo fileLength=${verified?.fileLength}")
            return false
        }
        trace("REGISTER_VERIFY_OK", "id=${p.id} fileLength=${verified.fileLength} parent=${p.parentId} relative=$relativePath")
        removePending(c, p.id)
        trace("REGISTER_SUCCESS", "id=${p.id} parent=${p.parentId} name=$name")
        return fileInfo == null || existingEpisode == null || existingHeader == null
    }

    private fun isVideo(name: String) =
        name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v")

    private data class LocatedFile(val file: SafeFile, val name: String, val relativePath: String)
    private data class Candidate(val pending: Pending, val found: LocatedFile, val size: Long)

    /** Stay inside the selected download tree: no global media search or unbounded recursion. */
    private fun scanFiles(dir: SafeFile, scan: Int): List<LocatedFile> {
        val found = ArrayList<LocatedFile>()
        val queue = java.util.ArrayDeque<Triple<SafeFile, String, Int>>()
        queue.add(Triple(dir, "", 0))
        var inspected = 0
        while (queue.isNotEmpty() && inspected < 1500) {
            val (folder, relative, depth) = queue.removeFirst()
            val children = runCatching { folder.listFiles() }
                .onFailure { android.util.Log.w("ElevateExternal", "SCAN_LIST_FAILED scan=$scan relative=$relative", it) }
                .getOrNull()
            for (child in children.orEmpty()) {
                if (++inspected > 1500) break
                val name = child.name() ?: continue
                val isDirectory = runCatching { child.isDirectory() == true }.getOrDefault(false)
                if (isDirectory) {
                    if (depth < 3 && !name.startsWith(".")) {
                        queue.add(Triple(child, if (relative.isEmpty()) name else "$relative/$name", depth + 1))
                    }
                } else {
                    found.add(LocatedFile(child, name, relative))
                }
            }
        }
        if (inspected >= 1500) trace("SCAN_LIMIT", "scan=$scan maxEntries=1500")
        trace("SCAN_LIST", "scan=$scan inspected=$inspected files=${found.size}")
        return found
    }

    /** One observer per pending directory; no periodic polling and no extra worker thread. */
    @Synchronized private fun watch(c: Context) {
        val path = basePath(c)
        val needed = pendingMap(c).isNotEmpty()
        val key = path ?: "@default"
        trace("WATCH_CHECK", "needed=$needed folder=${pathForLog(path)} existing=${pathForLog(watchPath)} observer=${fileObserver != null} contentObserver=${contentObserver != null}")
        if (!needed) {
            trace("WATCH_STOP_EMPTY", "pending=0")
            stopWatching(c)
            return
        }
        if (watchPath == key) {
            trace("WATCH_REUSED", "folder=${pathForLog(path)}")
            return
        }
        stopWatching(c)
        watchPath = key
        @Suppress("DEPRECATION")
        val physical = if (path.isNullOrBlank()) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        } else path.takeUnless { it.startsWith("content://") }
        trace("WATCH_PATH", "configured=${pathForLog(path)} physical=${pathForLog(physical)}")
        if (physical != null) {
            runCatching {
                object : FileObserver(physical, FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE) {
                    override fun onEvent(event: Int, name: String?) {
                        val accepted = name != null && isVideo(name)
                        trace("FILE_EVENT", "mask=$event name=$name accepted=$accepted")
                        if (accepted) scheduleCheck(c, 2400L)
                    }
                }.also { it.startWatching() }
            }.onSuccess {
                fileObserver = it
                trace("WATCH_FILE_READY", "path=${pathForLog(physical)}")
            }.onFailure { android.util.Log.w("ElevateExternal", "WATCH_FILE_FAILED path=${pathForLog(physical)}", it) }
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
                    trace("CONTENT_EVENT", "self=$selfChange uri=${pathForLog(uri.toString())}")
                    scheduleCheck(c, 2400L)
                }
            }
            runCatching { c.contentResolver.registerContentObserver(uri, true, observer) }
                .onSuccess {
                    contentObserver = observer
                    trace("WATCH_CONTENT_READY", "uri=${pathForLog(uri.toString())}")
                }.onFailure { android.util.Log.w("ElevateExternal", "WATCH_CONTENT_FAILED", it) }
        } else trace("WATCH_CONTENT_SKIPPED", "reason=noContentUri")
    }

    @Synchronized private fun stopWatching(c: Context) {
        trace("WATCH_STOP", "file=${fileObserver != null} content=${contentObserver != null} folder=${pathForLog(watchPath)}")
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
            trace("SCAN_SCHEDULE_SKIPPED", "reason=noPending delayMs=$delayMs")
            return
        }
        trace("SCAN_SCHEDULE", "delayMs=$delayMs pending=${pendingMap(c).size} replaced=${scheduled != null}")
        scheduled?.let(main::removeCallbacks)
        val task = Runnable {
            synchronized(this) { scheduled = null }
            trace("SCAN_TIMER_FIRED", "delayMs=$delayMs")
            checkFolder(c)
        }
        scheduled = task
        main.postDelayed(task, delayMs)
    }

    /** Manually retry recognition for downloads with an active Pending entry. */
    fun syncNow(c: Context) {
        trace("SYNC_BUTTON", "clicked=true pending=${pending(c).size} folder=${pathForLog(basePath(c))}")
        checkFolder(c, manual = true)
    }

    /** Reuse native registration for pending files, including renamed or nested downloads. */
    fun checkFolder(c: Context, manual: Boolean = false) {
        val scan = scanSequence.incrementAndGet()
        trace("SCAN_REQUEST", "scan=$scan manual=$manual checking=${checking.get()} feature=${ElevateFeaturePrefs.enabled(c, ElevateFeature.EXTERNAL_DOWNLOADS)}")
        if (!ElevateFeaturePrefs.enabled(c, ElevateFeature.EXTERNAL_DOWNLOADS)) {
            if (manual) toast(c, "Enable External Downloads first.")
            return
        }
        if (!checking.compareAndSet(false, true)) {
            if (manual) toast(c, "Download scan is already running.")
            return
        }
        scanner.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            var unstableFile = false
            var registered = 0
            var scanError: String? = null
            var exactMatches = 0
            var renamed = 0
            var withoutPending = 0
            var ambiguous = 0
            try {
                val requests = pending(c)
                trace("SCAN_BEGIN", "scan=$scan manual=$manual pending=${requests.size} retry=$unstableRetries thread=${Thread.currentThread().name}")
                if (requests.isEmpty()) {
                    trace("SCAN_EMPTY", "scan=$scan reason=noPending")
                    if (manual) scanError = "No pending external downloads."
                    return@launch
                }
                val base = basePath(c)
                val dir = directory(c, base)
                trace("SCAN_DIRECTORY", "scan=$scan configured=${pathForLog(base)} kind=${if (base.isNullOrBlank()) "default" else if (base.startsWith("content://")) "SAF" else "filesystem"} opened=${dir != null}")
                if (dir == null) {
                    if (manual) scanError = "CloudStream download folder unavailable."
                    return@launch
                }
                val files = scanFiles(dir, scan)
                val byName = HashMap<String, Pending>()
                val byId = requests.associateBy { it.id }
                for (p in requests) {
                    if (p.expectedFile.isNotBlank()) byName[p.expectedFile.lowercase(java.util.Locale.ROOT)] = p
                }
                val markerGroups = files.filter { isVideo(it.name) }
                    .mapNotNull { found ->
                        csFileMarker.find(found.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?.let { it to found }
                    }.groupBy({ it.first }, { it.second })
                val candidates = ArrayList<Candidate>()
                val now = System.currentTimeMillis()
                for ((index, found) in files.withIndex()) {
                    val name = found.name
                    val markerId = csFileMarker.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val exact = byName[name.lowercase(java.util.Locale.ROOT)]
                    val pending = when {
                        exact != null && (markerId == null || markerId == exact.id) -> {
                            exactMatches++
                            exact
                        }
                        !isVideo(name) -> {
                            continue
                        }
                        markerId == null -> {
                            continue
                        }
                        markerGroups[markerId]?.size != 1 -> {
                            ambiguous++
                            trace("SCAN_AMBIGUOUS", "scan=$scan id=$markerId name=$name reason=multipleVideosSameId count=${markerGroups[markerId]?.size}")
                            continue
                        }
                        else -> {
                            val current = byId[markerId]
                            if (current == null) {
                                withoutPending++
                                continue
                            }
                            renamed++
                            trace("SCAN_PENDING_RENAMED", "scan=$scan id=$markerId parent=${current.parentId} actual=$name expected=${current.expectedFile}")
                            current
                        }
                    }
                    if (!isVideo(name)) {
                        continue
                    }
                    val size = found.file.length()
                    if (size == null || size <= 1024L) {
                        unstableFile = true
                        continue
                    }
                    val modified = found.file.lastModified()
                    if (modified != null && (modified < pending.created - 2000L || modified > now - 1500L)) {
                        if (modified >= pending.created - 2000L) unstableFile = true
                        continue
                    }
                    candidates.add(Candidate(pending, found, size))
                }
                trace("SCAN_MATCH_SUMMARY", "scan=$scan pending=${requests.size} files=${files.size} exact=$exactMatches renamed=$renamed withoutPending=$withoutPending ambiguous=$ambiguous candidates=${candidates.size}")
                if (candidates.isEmpty()) return@launch
                delay(750L)
                val stableAt = System.currentTimeMillis()
                for ((p, found, size) in candidates) {
                    val currentSize = found.file.length()
                    val modified = found.file.lastModified()
                    if (currentSize != size || size <= 1024L ||
                        modified != null && modified > stableAt - 1500L) {
                        unstableFile = true
                        continue
                    }
                    if (found.file.name() != found.name) {
                        continue
                    }
                    val result = runCatching {
                        register(c, p, found.name, size, base, found.relativePath)
                    }.onFailure {
                        android.util.Log.w("ElevateExternal", "SCAN_REGISTER_EXCEPTION scan=$scan id=${p.id}", it)
                    }.getOrDefault(false)
                    trace("SCAN_REGISTER_RESULT", "scan=$scan id=${p.id} result=$result")
                    if (result) registered++
                }
            } catch (e: Exception) {
                android.util.Log.w("ElevateExternal", "SCAN_EXCEPTION scan=$scan", e)
                if (manual) scanError = "Cannot scan CloudStream download folder."
            } finally {
                checking.set(false)
                trace("SCAN_END", "scan=$scan manual=$manual exact=$exactMatches renamed=$renamed noPending=$withoutPending ambiguous=$ambiguous registered=$registered unstable=$unstableFile durationMs=${android.os.SystemClock.elapsedRealtime() - started}")
                if (unstableFile && unstableRetries < 2) {
                    unstableRetries++
                    trace("SCAN_RETRY", "scan=$scan retry=$unstableRetries delayMs=6000")
                    scheduleCheck(c, 6_000L)
                } else if (!unstableFile) unstableRetries = 0
                if (manual) {
                    val result = scanError ?: "Synced $registered; ${pending(c).size} pending; $withoutPending no Pending; $ambiguous ambiguous; $renamed renamed."
                    toast(c, result)
                }
            }
        }
    }

    fun startRecognition(c: Context) {
        trace("RECOGNITION_START", "pending=${pending(c).size} configured=${pathForLog(basePath(c))}")
        unstableRetries = 0
        watch(c)
        scheduleCheck(c, 2400L)
    }

    @Synchronized fun stopRecognition(c: Context) {
        trace("RECOGNITION_STOP", "pending=${pending(c).size}")
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
        android.util.Log.i("ElevateExternal", "LIFECYCLE_REGISTER")
        app.registerActivityLifecycleCallbacks(this)
        ExternalDownloads.startRecognition(app)
    }
    fun stop() {
        ExternalDownloads.stopRecognition(app)
        app.unregisterActivityLifecycleCallbacks(this)
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) {
            android.util.Log.i("ElevateExternal", "LIFECYCLE_RESUMED activity=MainActivity restartRecognition=true")
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
