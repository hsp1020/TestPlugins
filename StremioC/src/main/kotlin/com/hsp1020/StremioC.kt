package com.hsp1020

import android.content.Context
import android.os.SystemClock
import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ui.player.Torrent
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Protocol
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.lang.ref.WeakReference
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

inline fun <T> runSuspendCatching(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c 
    } catch (t: Throwable) { 
        Result.failure(t)
    }
}

internal data class SeasonTrackerIds(
    val mal: Int?, val aniList: Int?, val kitsu: Int?,
    val simkl: Int? = null, val tvdb: Int? = null,
    val tvdbSeason: Int? = null, val tmdbSeason: Int? = null
)


private fun String.urlEncode(): String = try { URLEncoder.encode(this, "UTF-8").replace("+", "%20") } catch (e: Exception) { this }

private val stremioRuntimePattern = Regex("""(?i)^(?:(\d+)\s*h(?:ours?|rs?)?\s*)?(?:(\d+)\s*m(?:in(?:utes?)?)?\s*)?$""")

private fun stremioRuntimeMinutes(runtime: String?): Int? {
    val value = runtime?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
    val parts = stremioRuntimePattern.matchEntire(value) ?: return null
    if (parts.groupValues[1].isEmpty() && parts.groupValues[2].isEmpty()) return null
    val hours = parts.groupValues[1].ifEmpty { "0" }.toIntOrNull() ?: return null
    val minutes = parts.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
    val total = hours.toLong() * 60L + minutes
    return total.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
}

private fun String.cleanBaseUrl(): String {
    return this.substringBefore("?").replace("/manifest.json", "").trimEnd('/')
}

private inline fun <T> lruSet(maxSize: Int): MutableSet<T> {
    return Collections.synchronizedSet(Collections.newSetFromMap(object : java.util.LinkedHashMap<T, Boolean>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<T, Boolean>): Boolean {
            val evicted = size > maxSize
            return evicted
        }
    }))
}

private inline fun <K, V> lruCacheMap(maxSize: Int): MutableMap<K, V> {
    return Collections.synchronizedMap(object : java.util.LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
            val evicted = size > maxSize
            return evicted
        }
    })
}

private fun canonicalDetailType(type: String?): String? = when (type?.lowercase()) {
    "movie" -> "movie"
    "series", "tv" -> "series"
    null -> null
    else -> type.lowercase()
}

private fun mdbCredentialFingerprint(): String {
    val key = CloudStreamApp.context
        ?.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
        ?.getString("mdblist_api_key", "").orEmpty()
    return key.toUniqueKey()
}

internal data class StremioManifestCachePolicy(val ttlMs: Long, val noStore: Boolean)

internal fun stremioManifestCachePolicy(
    headers: Iterable<Pair<String, String>>
): StremioManifestCachePolicy {
    fun header(name: String): String? = headers
        .firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    val directives = header("Cache-Control").orEmpty().lowercase(java.util.Locale.ROOT)
        .split(',').map { it.trim() }
    val noStore = "no-store" in directives
    if (noStore || "no-cache" in directives) return StremioManifestCachePolicy(0L, noStore)
    val age = header("Age")?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    val maxAge = directives.firstOrNull { it.startsWith("max-age=") }
        ?.substringAfter('=')?.trim('"', ' ')?.toLongOrNull()
    val ttl = if (maxAge != null) {
        (maxAge - age).coerceIn(0L, 72L * 3600L) * 1000L
    } else {
        fun parseDate(value: String?): Long? = runCatching {
            java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US
            ).parse(value ?: "")?.time
        }.getOrNull()
        val expires = parseDate(header("Expires"))
        if (expires != null) {
            val serverDate = parseDate(header("Date")) ?: System.currentTimeMillis()
            (expires - serverDate - age * 1000L).coerceIn(0L, 72L * 3600L * 1000L)
        } else 3_600_000L
    }
    return StremioManifestCachePolicy(ttl, false)
}

internal object StremioCMdbUiBridge {
    private val urls = Collections.synchronizedMap(object : java.util.LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 2000
    })
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var listener: ((String) -> Unit)? = null

    fun bind(cacheKey: String, candidates: Iterable<String?>) {
        candidates.forEach { url ->
            if (!url.isNullOrBlank()) urls[url] = cacheKey
        }
    }

    fun keyFor(url: String?): String? = url?.let { urls[it] }
    fun listen(callback: ((String) -> Unit)?) { listener = callback }

    fun publish(cacheKey: String) {
        main.post { listener?.invoke(cacheKey) }
    }
}

internal fun <T> stremioCacheMemoryRead(
    cached: Pair<T, Long>?, ttlMs: Long
): Pair<T, Long>? {
    if (cached == null) {
        return null
    }
    val age = System.currentTimeMillis() - cached.second
    val valid = age >= 0L && age < ttlMs
    return cached.takeIf { valid }
}

private object MetadataDiskCache {
    private const val TTL_MS = 24L * 60 * 60 * 1000
    private const val SEASON_TTL_MS = 12L * 60 * 60 * 1000
    private const val MAX_BYTES = 50L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024
    private const val MAX_RAW_BYTES = 2 * 1024 * 1024
    private const val ANIME_EMPTY_TTL_MS = 15L * 60 * 1000
    private val entryLimits = mapOf("detail" to 700, "season" to 300, "find" to 5000,
        "anime" to 5000, "missing_anime" to 1000, "mdb" to 1000, "meta" to 200)
    private val lock = Any()
    private var quotaChecked = false
    private var cleanedOldTemps = false
    private var writesSinceQuotaCheck = 0
    private var lastQuotaCheckAtMs = 0L
    private const val QUOTA_CHECK_WRITES = 16
    private const val QUOTA_CHECK_MS = 30_000L

    data class Entry(val json: String, val timestamp: Long, val ttlMs: Long)

    private fun directory(): File? = CloudStreamApp.context?.filesDir
        ?.let { File(it, "stremioc-metadata-v1").apply { mkdirs() }.takeIf { dir -> dir.isDirectory } }

    private fun target(dir: File, kind: String, key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest("${kind}:${key}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(dir, "${kind}_${hash}.gz")
    }

    suspend fun read(kind: String, key: String): Pair<String, Long>? =
        readWithTtl(kind, key)?.let { it.json to it.timestamp }

    suspend fun readWithTtl(kind: String, key: String): Entry? = withContext(Dispatchers.IO) {
        // Writers publish complete files using atomic rename. Reading a completed
        // file does not need the writer's global eviction/quota monitor.
        val file = directory()?.let { target(it, kind, key) } ?: return@withContext null
        if (!file.isFile) return@withContext null
        try {
            val body = GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
            val stamp = body.substringBefore('\n').toLongOrNull()
            val now = System.currentTimeMillis()
            val firstNewline = body.indexOf('\n')
            val secondNewline = if (kind == "meta" && firstNewline >= 0)
                body.indexOf('\n', firstNewline + 1) else -1
            val ttl = when (kind) {
                "season" -> SEASON_TTL_MS
                "missing_anime" -> ANIME_EMPTY_TTL_MS
                "meta" -> if (secondNewline > firstNewline)
                    body.substring(firstNewline + 1, secondNewline).toLongOrNull() ?: 0L else 0L
                else -> TTL_MS
            }
            if (stamp == null || stamp > now || ttl <= 0L ||
                now - stamp >= ttl || firstNewline < 0 ||
                (kind == "meta" && secondNewline < 0)) {
                // Never delete here. A concurrent writer might have replaced this
                // filename while the old inode was being read.
                null
            } else {
                file.setLastModified(now)
                Entry(body.substring(if (kind == "meta") secondNewline + 1
                    else firstNewline + 1), stamp, ttl)
            }
        } catch (_: Exception) {
            // An eviction racing this read is a cache miss, never a playback error.
            null

        }
    }

    suspend fun write(kind: String, key: String, json: String, ttlMs: Long = TTL_MS) {
        if (kind == "meta" && ttlMs <= 0L) {
            return
        }
        val rawBytes = json.toByteArray(Charsets.UTF_8).size
        if (json.contains("data:image/", ignoreCase = true) || rawBytes > MAX_RAW_BYTES) {
            return
        }
        withContext(Dispatchers.IO) {
            val dir = directory() ?: run {
                return@withContext
            }

            val temp = try { File.createTempFile("stremioc_${kind}_", ".tmp", dir) } catch (error: Exception) {
                return@withContext
            }
            try {
                GZIPOutputStream(temp.outputStream()).bufferedWriter(Charsets.UTF_8).use {
                    it.write(System.currentTimeMillis().toString())
                    it.newLine()
                    if (kind == "meta") {
                        it.write(ttlMs.toString())
                        it.newLine()
                    }
                    it.write(json)
                }
                if (temp.length() > MAX_ENTRY_BYTES) {
                    return@withContext
                }
                synchronized(lock) {
                    val acquired = android.os.SystemClock.elapsedRealtime()
                    if (!cleanedOldTemps) {
                        cleanedOldTemps = true
                        val cutoff = System.currentTimeMillis() - 10L * 60 * 1000
                        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") &&
                            it != temp && it.lastModified() < cutoff }?.forEach { it.delete() }
                    }
                    val file = target(dir, kind, key)
                    if (temp.renameTo(file)) {
                        writesSinceQuotaCheck++
                        val due = !quotaChecked ||
                            writesSinceQuotaCheck >= QUOTA_CHECK_WRITES ||
                            acquired - lastQuotaCheckAtMs >= QUOTA_CHECK_MS
                        if (due) {
                            val files = dir.listFiles()?.filter {
                                it.isFile && it.name.endsWith(".gz")
                            }.orEmpty()
                            val totalBytes = files.sumOf { it.length() }
                            val kindCount = files.count { it.name.startsWith("${kind}_") }
                            val trimNeeded = totalBytes > MAX_BYTES ||
                                kindCount > (entryLimits[kind] ?: Int.MAX_VALUE)
                            if (trimNeeded) trim(dir)
                            quotaChecked = true
                            writesSinceQuotaCheck = 0
                            lastQuotaCheckAtMs = android.os.SystemClock.elapsedRealtime()

                        }
                    }

                }
            } catch (error: Exception) {
            } finally {
                temp.delete()
            }
        }
    }

    private fun trim(dir: File, limitBytes: Long = MAX_BYTES) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".gz") } ?: return
        for ((kind, limit) in entryLimits) {
            files.filter { it.name.startsWith("${kind}_") }
                .sortedByDescending { it.lastModified() }.drop(limit).forEach {
                    it.delete()
                }
        }
        val remaining = files.filter { it.exists() }.sortedBy { it.lastModified() }
        var total = remaining.sumOf { it.length() }
        for (file in remaining) {
            if (total <= limitBytes) break
            val length = file.length()
            if (file.delete()) {
                total -= length
            }
        }
    }
}

private fun JsonNode?.textOrNull(): String? {
    if (this == null || this.isNull || this.isMissingNode) return null
    return try { this.asText().takeIf { it.isNotBlank() && it != "null" } } catch (e: Exception) { null }
}

private fun JsonNode?.intOrNull(): Int? {
    if (this == null || this.isNull || this.isMissingNode) return null
    return try { this.asInt() } catch (e: Exception) { null }
}

private fun tmdbTrailerLanguages(): List<String> {
    val configured = CloudStreamApp.context
        ?.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
        ?.getString("tmdb_language_code", "en-US")
        ?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: "en-US"
    return listOf(configured, configured.substringBefore('-'), "en")
        .distinctBy { it.lowercase() }
}

private fun tmdbLogoLanguages(): String {
    val preferred = CloudStreamApp.context
        ?.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
        ?.getString("tmdb_language_code", "en-US")
        ?.substringBefore('-')?.trim()?.lowercase()
        ?.takeIf { it.isNotEmpty() } ?: "en"
    return listOf(preferred, "en").distinct().joinToString(",")
}

private class NoNativeTrailerPreview : AbstractMutableList<TrailerData>() {
    override val size: Int get() = 0
    override fun get(index: Int): TrailerData = throw IndexOutOfBoundsException()
    override fun add(index: Int, element: TrailerData) = Unit
    override fun set(index: Int, element: TrailerData): TrailerData = throw IndexOutOfBoundsException()
    override fun removeAt(index: Int): TrailerData = throw IndexOutOfBoundsException()
}

internal data class TrailerChoice(
    val youtubeId: String,
    val title: String,
    val type: String,
    val language: String? = null
)

private fun parseTrailerYoutubeId(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    fun valid(id: String) = id.length == 11 && id.all {
        (it.isLetterOrDigit() && it.code < 128) || it == '_' || it == '-'
    }
    if (valid(value)) return value
    return runCatching {
        val uri = android.net.Uri.parse(value)
        val host = uri.host?.lowercase() ?: return@runCatching null
        val segments = uri.pathSegments
        val candidate = when {
            host == "youtu.be" || host.endsWith(".youtu.be") -> segments.firstOrNull()
            host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ->
                uri.getQueryParameter("v") ?: segments.takeIf {
                    it.firstOrNull() in listOf("embed", "shorts", "live", "v")
                }?.getOrNull(1)
            else -> null
        }
        candidate?.takeIf(::valid)
    }.getOrNull()
}

open class StremioC(
    override var mainUrl: String,
    override var name: String,
    private val addonId: Long? = null,
    private val searchCatalogId: String? = null,
    private val searchCatalogType: String? = null,
    private val showHome: Boolean = true,
    private val disableSearch: Boolean = false
) : MainAPI() {
    override val supportedTypes get() = if (mainUrl.isNotBlank() && isAddonEnabled()) {
        setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    } else {
        emptySet()
    }

    override val supportedSyncNames: Set<SyncIdName>
        get() = if (mainUrl.isBlank() && libraryMetadataBase() != null) {
            setOf(SyncIdName.MyAnimeList, SyncIdName.Kitsu, SyncIdName.Anilist, SyncIdName.Simkl)
        } else emptySet()

    private fun libraryMetadataBase(): String? = CloudStreamApp.context
        ?.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
        ?.getString("history_sync_url", null)
        ?.trim()?.takeIf { it.isNotBlank() }?.cleanBaseUrl()

    private fun libraryItemId(url: String, host: String, vararg sections: String): String? =
        runCatching {
            val uri = URI(if (url.startsWith("https://") || url.startsWith("http://")) url else "https://$url")
            val parts = uri.path.split('/').filter { it.isNotBlank() }
            if (uri.host?.lowercase()?.removePrefix("www.") != host || parts.firstOrNull() !in sections) {
                null
            } else {
                parts.getOrNull(1)?.takeIf { id ->
                    id.all(Char::isDigit) && id.toLongOrNull()?.let { it > 0L } == true
                }
            }
        }.getOrNull()

    private fun simklMetaUrl(base: String, type: String, ids: JsonNode?): String? {
        val imdbId = ids?.get("imdb").textOrNull()?.takeIf { IMDB_ID_REGEX.matches(it) }
        val tmdbId = ids?.get("tmdb").textOrNull()?.takeIf {
            it.all(Char::isDigit) && it.toLongOrNull()?.let { n -> n > 0L } == true
        }
        val id = imdbId ?: tmdbId?.let { "tmdb:$it" } ?: return null
        return "$base/meta/$type/${id.urlEncode()}.json"
    }

    private suspend fun simklLibraryMetaUrl(url: String, base: String): String? {
        val simklId = libraryItemId(url, "simkl.com", "tv", "movies", "anime") ?: return null
        val auth = AccountManager.syncApis.firstOrNull { it.idPrefix == "simkl" }
            ?.authData() ?: return null

        val headers = SimklApi.getHeaders(auth.token) + ("User-Agent" to "StremioC/1.0")
        val params = mapOf(
            "client_id" to (headers["simkl-api-key"] ?: return null),
            "app-name" to "stremioc",
            "app-version" to "1.0"
        )

        val cached = run {
            getKey<JsonNode>(SimklApi.SIMKL_CACHED_LIST, auth.user.id.toString())
        }
        for ((section, type) in listOf("movies" to "movie", "shows" to "series", "anime" to "series")) {
            val item = cached?.get(section)?.firstOrNull {
                val content = if (section == "movies") it.get("movie") else it.get("show")
                content?.get("ids")?.get("simkl")?.asText() == simklId
            } ?: continue
            val ids = (if (section == "movies") item.get("movie") else item.get("show"))?.get("ids")
            if (section != "anime") {
                simklMetaUrl(base, type, ids)?.let { return it }
                continue
            }

            val animeType = item.get("anime_type").textOrNull()
                ?: item.get("show")?.get("anime_type").textOrNull()
            val detail = if (animeType == null) runSuspendCatching {
                run {
                    parseJson<JsonNode>(customSession.get(
                        "https://api.simkl.com/anime/$simklId",
                        params = params,
                        headers = headers,
                        timeout = 15L
                    ).text)
                }
            }.getOrNull()?.takeIf {
                it.get("ids")?.get("simkl")?.asText() == simklId
            } else null
            val resolvedType = when ((animeType ?: detail?.get("anime_type").textOrNull())?.lowercase()) {
                "movie" -> "movie"
                "tv", "ova", "ona", "special", "music video" -> "series"
                else -> return null
            }
            return simklMetaUrl(base, resolvedType, ids)
                ?: simklMetaUrl(base, resolvedType, detail?.get("ids"))
        }

        val matches = runSuspendCatching {
            run {
                parseJson<JsonNode>(customSession.get(
                    "https://api.simkl.com/search/id",
                    params = params + ("simkl" to simklId),
                    headers = headers,
                    timeout = 15L
                ).text)
            }
        }.getOrNull()
        val match = matches?.takeIf { it.isArray }?.firstOrNull {
            it.get("ids")?.get("simkl")?.asText() == simklId
        } ?: return null
        val endpoint = when (match.get("type").textOrNull()?.lowercase()) {
            "movie" -> "movies"
            "tv" -> "tv"
            "anime" -> "anime"
            else -> return null
        }
        val detail = runSuspendCatching {
            run {
                parseJson<JsonNode>(customSession.get(
                    "https://api.simkl.com/$endpoint/$simklId",
                    params = params,
                    headers = headers,
                    timeout = 15L
                ).text)
            }
        }.getOrNull() ?: return null
        if (detail.get("ids")?.get("simkl")?.asText() != simklId) return null
        val type = if (endpoint == "anime") {
            when (detail.get("anime_type").textOrNull()?.lowercase()) {
                "movie" -> "movie"
                "tv", "ova", "ona", "special", "music video" -> "series"
                else -> return null
            }
        } else if (endpoint == "movies") "movie" else "series"
        return simklMetaUrl(base, type, detail.get("ids"))
    }

    private fun animeLibraryType(format: String?): String? = when (format?.trim()?.lowercase()) {
        "movie" -> "movie"
        "tv", "tv_short", "ona", "ova", "special", "music" -> "series"
        else -> null
    }

    private suspend fun animeLibraryMetaType(name: SyncIdName, itemId: String): String? {
        val auth = AccountManager.syncApis.firstOrNull { it.syncIdName == name }?.authData()
        val cachedType = run { when (name) {
            SyncIdName.MyAnimeList -> auth?.let {
                getKey<JsonNode>("mal_cached_list", it.user.id.toString())
            }?.firstOrNull { it.get("node")?.get("id")?.asText() == itemId }
                ?.get("node")?.get("media_type").textOrNull()
            SyncIdName.Anilist -> auth?.let {
                getKey<JsonNode>("anilist_cached_list", it.user.id.toString())
            }?.firstNotNullOfOrNull { group ->
                group.get("entries")?.firstOrNull {
                    it.get("media")?.get("id")?.asText() == itemId
                }?.get("media")?.get("format").textOrNull()
            }

            else -> null
        } }
        animeLibraryType(cachedType)?.let { return it }

        val remoteType = runSuspendCatching {
            run { when (name) {
                SyncIdName.MyAnimeList -> {
                    val token = auth?.token?.accessToken ?: return@run null
                    val item = parseJson<JsonNode>(customSession.get(
                        "https://api.myanimelist.net/v2/anime/$itemId",
                        params = mapOf("fields" to "media_type"),
                        headers = mapOf("Authorization" to "Bearer $token"),
                        timeout = 10L
                    ).text)
                    item.takeIf { it.get("id")?.asText() == itemId }
                        ?.get("media_type").textOrNull()
                }
                SyncIdName.Anilist -> {
                    val query = "query { Media(id: $itemId, type: ANIME) { id format } }"
                    val data = parseJson<JsonNode>(customSession.post(
                        "https://graphql.anilist.co/",
                        json = mapOf("query" to query),
                        timeout = 10L
                    ).text).get("data")?.get("Media")
                    data?.takeIf { it.get("id")?.asText() == itemId }
                        ?.get("format").textOrNull()
                }
                SyncIdName.Kitsu -> {
                    val data = parseJson<JsonNode>(customSession.get(
                        "https://kitsu.app/api/edge/anime/$itemId",
                        params = mapOf("fields[anime]" to "subtype"),
                        timeout = 10L
                    ).text).get("data")
                    data?.takeIf { it.get("id")?.asText() == itemId }
                        ?.get("attributes")?.get("subtype").textOrNull()
                }
                else -> null
            } }
        }.getOrNull()
        return animeLibraryType(remoteType)
    }

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        val resolved = getLoadUrlInternal(name, id)

        if (mainUrl.isBlank() && resolved != null) {
            val canonical = when (name) {
                SyncIdName.MyAnimeList -> libraryItemId(id, "myanimelist.net", "anime")
                    ?.let { "https://myanimelist.net/anime/$it/" }
                SyncIdName.Anilist -> libraryItemId(id, "anilist.co", "anime")
                    ?.let { "https://anilist.co/anime/$it/" }
                SyncIdName.Kitsu -> libraryItemId(id, "kitsu.app", "anime")
                    ?.let { "https://kitsu.app/anime/$it/" }
                else -> null
            }
            if (canonical != null) {
                synchronized(libraryOriginalUrlsByMeta) {
                    libraryOriginalUrlsByMeta.getOrPut(resolved) { LinkedHashSet() }.apply {
                        add(id)
                        add(canonical)
                        add(canonical.dropLast(1))
                    }
                }
            }
        }
        return resolved
    }

    private suspend fun getLoadUrlInternal(name: SyncIdName, id: String): String? {
        if (mainUrl.isNotBlank()) return null
        val base = libraryMetadataBase() ?: return null
        if (name == SyncIdName.Simkl) return simklLibraryMetaUrl(id, base)
        val (directId, itemId) = when (name) {
            SyncIdName.MyAnimeList -> libraryItemId(id, "myanimelist.net", "anime")?.let { "mal:$it" to it }
            SyncIdName.Kitsu -> libraryItemId(id, "kitsu.app", "anime")?.let { "kitsu:$it" to it }
            SyncIdName.Anilist -> libraryItemId(id, "anilist.co", "anime")?.let { "anilist:$it" to it }
            else -> null
        } ?: return null
        val type = animeLibraryMetaType(name, itemId) ?: return null
        return "$base/meta/$type/${directId.urlEncode()}.json"
    }

    override val hasMainPage get() = showHome && mainUrl.isNotBlank() && isAddonEnabled()

    private fun isAddonEnabled() = isCatalogAddonEnabled(mainUrl, addonId)

    internal fun primeManifest(manifest: Manifest, ttlMs: Long = MANIFEST_CACHE_TTL_MS) {
        cachedManifest = manifest
        lastManifestUrl = stremioManifestUrl(mainUrl)
        lastCacheTime = System.currentTimeMillis()
        manifestCacheTtlMs = ttlMs
    }

    private var cachedManifest: Manifest? = null
    private var lastManifestUrl: String = ""
    private var lastCacheTime: Long = 0
    private var manifestCacheTtlMs: Long = MANIFEST_CACHE_TTL_MS

    override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor? =
    VixSrcExtractor.getVideoInterceptor(extractorLink)

    private data class PreparedMainPageManifest(
        val manifestUrl: String,
        val rawSignature: String,
        val manifest: Manifest,
    )
    private data class PreparedMainPageData(
        val manifestUrl: String,
        val rawSignature: String,
        val data: List<MainPageData>,
    )
    @Volatile private var preparedMainPageManifest: PreparedMainPageManifest? = null
    @Volatile private var preparedMainPageData: PreparedMainPageData? = null

    private val mainPageManifestParseLock = Any()

    private val pageMutex = Mutex()
    private class PageFlight(
        val scope: CoroutineScope,
        val deferred: Deferred<HomePageResponse>,
        var waiters: Int = 0
    )
    private val activePageRequests = mutableMapOf<String, PageFlight>()

    private val manifestMutex = Mutex()

    private fun getCineStreamProvider(): MainAPI? {
        if (getKey<Boolean>("ProviderCineStream") == false) return null
        if (cachedCineStreamProvider != null) return cachedCineStreamProvider

        synchronized(StremioC::class.java) {
            if (cachedCineStreamProvider != null) return cachedCineStreamProvider

            val provider = getSafeAllProviders().find { it.name.equals("CineStream", true) }
                ?: loadProviderDynamically("megix", "com.megix.CineStreamProvider")

            if (provider != null) cachedCineStreamProvider = provider
            return provider
        }
    }

    private fun isStreamPlayApiEnabled(apiName: String): Boolean {
        val context = CloudStreamApp.context ?: return true
        val enabled = context.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
            .getStringSet("enabled_plugins_saved", null)
        return enabled == null || apiName in enabled
    }

    private fun getStreamPlayProvider(): MainAPI? {
        if (!isStreamPlayApiEnabled("StreamPlay")) return null
        if (cachedStreamPlayProvider != null) return cachedStreamPlayProvider

        synchronized(StremioC::class.java) {
            if (cachedStreamPlayProvider != null) return cachedStreamPlayProvider

            val provider = getSafeAllProviders().find { it.name.equals("StreamPlay", true) }
                ?: loadProviderDynamically(
                    "phisher", "com.phisher98.StreamPlay", "StreamPlay"
                )

            if (provider != null) cachedStreamPlayProvider = provider
            return provider
        }
    }

    private fun getStreamPlayAnimeProvider(): MainAPI? {
        if (!isStreamPlayApiEnabled("StreamPlay-Anime")) return null
        getSafeAllProviders().find { it.name.equals("StreamPlay-Anime", true) }?.let { return it }
        return loadProviderDynamically("phisher", "com.phisher98.StreamPlayAnime")
    }

    fun getUnifiedMetaUrl(type: String, id: String): String {
        val (syncUrl, syncMap, _) = getSyncSettings()
        val currentCleanUrl = mainUrl.cleanBaseUrl().lowercase()
        val isSyncEnabled = syncMap[currentCleanUrl] ?: false

        return if (isSyncEnabled && syncUrl.isNotBlank()) {
            val cleanBase = syncUrl.cleanBaseUrl()
            "$cleanBase/meta/$type/${id.urlEncode()}.json"
        } else {
            val cleanBase = mainUrl.cleanBaseUrl()
            "$cleanBase/meta/$type/${id.urlEncode()}.json"
        }
    }

    private fun metadataPosterAddonBaseUrl(): String? {
        val (syncUrl, syncMap, _) = getSyncSettings()
        return syncUrl.takeIf { it.isNotBlank() && syncMap[mainUrl.cleanBaseUrl().lowercase()] == true }?.cleanBaseUrl()
    }

    fun getTargetApiName(): String {
        val (_, syncMap, _) = getSyncSettings()
        val currentCleanUrl = mainUrl.cleanBaseUrl().lowercase()
        val isSyncEnabled = syncMap[currentCleanUrl] ?: false

        return if (isSyncEnabled) "StremioC" else name
    }

    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val TRANSPARENT_PIXEL = "https://upload.wikimedia.org/wikipedia/commons/c/ca/1x1.png"


        // Recommendations do not depend on the user's watchlist, but reuse the logged-in
        // Simkl identity and CloudStream's own Simkl client credentials.
        private data class SimklSuggestion(
            val type: String, val id: String, val tmdbId: Int?, val name: String,
            val poster: String?, val rank: Int
        )
        private data class SimklSuggestionCache(val entries: List<SimklSuggestion>, val at: Long)
        private val simklSuggestionCache = lruCacheMap<String, SimklSuggestionCache>(160)
        private val simklSuggestionFlights = mutableMapOf<String, Deferred<List<SimklSuggestion>>>()
        private val simklSuggestionLock = Any()
        // A separate supervisor prevents the parent detail load from joining background requests.
        private val simklSuggestionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val simklSuggestionSemaphore = Semaphore(3)
        private const val SIMKL_RECOMMENDATION_WAIT_MS = 1000L
        private const val SIMKL_RECOMMENDATION_TTL_MS = 24L * 60 * 60 * 1000
        private const val SIMKL_RECOMMENDATION_EMPTY_TTL_MS = 15L * 60 * 1000
        private const val SIMKL_TITLE_INDEX_TTL_MS = 2L * 60 * 1000
        private data class SimklTitleIndex(
            val userId: String, val at: Long, val byExternalId: Map<String, Pair<String, String>>
        )
        @Volatile private var simklTitleIndex: SimklTitleIndex? = null
        private val simklTitleIndexLock = Any()

        private fun simklSuggestionsFromDetail(text: String): List<SimklSuggestion> {
            val detail = parseJson<JsonNode>(text)
            val userSuggestions = detail.get("users_recommendations")
            val rows = if (userSuggestions != null && userSuggestions.isArray && userSuggestions.size() > 0)
                userSuggestions else detail.get("similar")
            if (rows == null || !rows.isArray) return emptyList()
            return rows.take(30).mapIndexedNotNull { index, row ->
                val ids = row.get("ids") ?: return@mapIndexedNotNull null
                val tmdb = ids.get("tmdb")?.asInt(0)?.takeIf { it > 0 }
                val mal = ids.get("mal")?.asInt(0)?.takeIf { it > 0 }
                val imdb = ids.get("imdb")?.asText()?.takeIf { IMDB_ID_REGEX.matches(it) }
                val originalType = row.get("type")?.asText()?.lowercase()
                val isAnime = originalType == "anime"
                val movie = originalType == "movie" || originalType == "movies" ||
                    (isAnime && row.get("anime_type")?.asText()?.equals("movie", true) == true)
                val type = if (movie) "movie" else "series"
                val id = if (isAnime && mal != null) "mal:$mal"
                    else tmdb?.let { "tmdb:$it" }
                        ?: imdb ?: mal?.let { "mal:$it" } ?: return@mapIndexedNotNull null
                val title = listOf("en_title", "title", "name")
                    .firstNotNullOfOrNull { row.get(it)?.asText()?.trim()?.takeIf(String::isNotBlank) }
                    ?: return@mapIndexedNotNull null
                val art = row.get("poster")?.asText()?.trim()
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_/-]{1,160}")) }
                    ?.let { "https://wsrv.nl/?url=https://simkl.in/posters/" + it + "_m.webp" }
                SimklSuggestion(type, id, tmdb, title, art, index)
            }.distinctBy { it.type to (it.tmdbId?.let { id -> "tmdb:$id" } ?: it.id) }
        }

        private fun cachedSimklTitle(
            userId: String, type: String, tmdbId: String, imdbId: String?, malId: Int?
        ): Pair<String, String>? {
                val now = System.currentTimeMillis()
            var index = simklTitleIndex?.takeIf {
                it.userId == userId && now >= it.at && now - it.at < SIMKL_TITLE_INDEX_TTL_MS
            }
            if (index == null) {
                index = synchronized(simklTitleIndexLock) {
                    val recent = simklTitleIndex?.takeIf {
                        it.userId == userId && now >= it.at && now - it.at < SIMKL_TITLE_INDEX_TTL_MS
                    }
                    recent ?: run {
                        val list = getKey<JsonNode>(SimklApi.SIMKL_CACHED_LIST, userId)
                        val ids = HashMap<String, Pair<String, String>>()
                        for ((section, segment) in listOf("anime" to "anime", "shows" to "tv", "movies" to "movies")) {
                            val rows = list?.get(section)?.takeIf { it.isArray } ?: continue
                            for (record in rows) {
                                val item = record.get(if (section == "movies") "movie" else "show") ?: record
                                val itemIds = item.get("ids") ?: continue
                                val simklId = itemIds.get("simkl")?.asText()
                                    ?.takeIf { it.toLongOrNull()?.let { n -> n > 0L } == true } ?: continue
                                val target = segment to simklId
                                itemIds.get("imdb")?.asText()?.takeIf { IMDB_ID_REGEX.matches(it) }
                                    ?.let { ids.putIfAbsent("imdb:$it", target) }
                                itemIds.get("tmdb")?.asText()?.toIntOrNull()?.takeIf { it > 0 }
                                    ?.let {
                                        val media = if (section == "movies") "movie" else "series"
                                        ids.putIfAbsent("tmdb:$media:$it", target)
                                    }
                                if (section == "anime") {
                                    itemIds.get("mal")?.asInt(0)?.takeIf { it > 0 }
                                        ?.let { ids.putIfAbsent("mal:$it", target) }
                                }
                            }
                        }
                        SimklTitleIndex(userId, System.currentTimeMillis(), ids).also {
                            simklTitleIndex = it
                        }
                    }
                }
            }
            val entries = index!!.byExternalId
            val hit = malId?.let { entries["mal:$it"] }
                ?: imdbId?.let { entries["imdb:$it"] }
                ?: entries["tmdb:$type:$tmdbId"]
            
            return hit
        }

        private suspend fun loadSimklSuggestions(
            userId: String, type: String, tmdbId: String, imdbId: String?, malId: Int?,
            headers: Map<String, String>, clientId: String, key: String
        ): List<SimklSuggestion> {
            return simklSuggestionSemaphore.withPermit {
            
            val disk = MetadataDiskCache.read("simkl_recs", key)
            
            if (disk != null && System.currentTimeMillis() >= disk.second &&
                System.currentTimeMillis() - disk.second < SIMKL_RECOMMENDATION_TTL_MS) {
                val cached = runCatching { simklSuggestionsFromDetail(disk.first) }.getOrNull()
                if (!cached.isNullOrEmpty()) {
                    
                    return@withPermit cached
                }
            }
            val found = cachedSimklTitle(userId, type, tmdbId, imdbId, malId)
            val target = found ?: run {
                // Search-by-ID returns a thin result: ids.simkl is reliable, but
                // ids.imdb / ids.tmdb may be missing even for an exact ID match.
                val lookups = mutableListOf<Pair<String, String>>()
                malId?.takeIf { it > 0 }?.let { lookups += "mal" to it.toString() }
                imdbId?.takeIf { IMDB_ID_REGEX.matches(it) }?.let { lookups += "imdb" to it }
                lookups += "tmdb" to tmdbId
                val paramsBase = mapOf(
                    "client_id" to clientId,
                    "app-name" to "stremioc",
                    "app-version" to "1.0"
                )
                val requestHeaders = headers + ("User-Agent" to "StremioC/1.0")
                var resolved: Pair<String, String>? = null
                for ((lookupKey, lookupValue) in lookups.distinct()) {
                    val params = paramsBase + (lookupKey to lookupValue) +
                        (if (lookupKey == "tmdb") mapOf("type" to
                            (if (type == "movie") "movie" else "show")) else emptyMap())
                    val response = try {
                        customSession.get(
                            "https://api.simkl.com/search/id",
                            params = params, headers = requestHeaders, timeout = 4L
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        
                        continue
                    }
                    
                    if (response.code !in 200..299) {
                        
                        // Invalid credentials and rate limits will affect every ID;
                        // avoid consuming extra requests in these cases.
                        if (response.code in listOf(401, 403, 412, 429)) break
                        continue
                    }
                    val parsed = try {
                        parseJson<JsonNode>(response.text)
                    } catch (e: Exception) {
                        
                        continue
                    }
                    
                    if (!parsed.isArray) {
                        
                        continue
                    }
                    val rows = parsed.toList()
                    data class Candidate(
                        val segment: String, val simklId: String,
                        val typeScore: Int, val idMatches: Boolean
                    )
                    val candidates = rows.mapNotNull { row ->
                        val ids = row.get("ids")
                        val simklId = ids?.get("simkl")?.asText()?.trim()
                            ?.takeIf { it.toLongOrNull()?.let { n -> n > 0L } == true }
                            ?: return@mapNotNull null
                        val rawType = row.get("type")?.asText()?.lowercase()
                        val animeType = row.get("anime_type")?.asText()?.lowercase()
                        val segment = when (rawType) {
                            "anime" -> "anime"
                            "movie", "movies" -> "movies"
                            "tv", "show", "shows" -> "tv"
                            else -> return@mapNotNull null
                        }
                        val score = when {
                            type == "movie" && segment == "movies" -> 3
                            type == "movie" && segment == "anime" && animeType == "movie" -> 2
                            type != "movie" && segment == "tv" -> 3
                            type != "movie" && segment == "anime" && animeType != "movie" -> 2
                            else -> 0
                        }
                        if (score == 0) return@mapNotNull null
                        Candidate(
                            segment, simklId,
                            if (lookupKey == "mal" && segment == "anime") 4 else score,
                            ids?.get(lookupKey)?.asText() == lookupValue
                        )
                    }
                    val selected = candidates.sortedWith(
                        compareByDescending<Candidate> { it.typeScore }
                            .thenByDescending { it.idMatches }
                    ).firstOrNull()
                    if (selected != null) {
                        resolved = selected.segment to selected.simklId
                        break
                    }
                    
                }
                if (resolved == null) {
                    
                    return@withPermit emptyList()
                }
                
                resolved
            }
            val response = try {
                customSession.get(
                    "https://api.simkl.com/" + target.first + "/" + target.second,
                    params = mapOf("client_id" to clientId, "extended" to "full",
                        "app-name" to "stremioc", "app-version" to "1.0"),
                    headers = headers + ("User-Agent" to "StremioC/1.0"), timeout = 5L
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                
                return@withPermit emptyList()
            }
            
            if (response.code !in 200..299 || response.text.isBlank()) {
                
                return@withPermit emptyList()
            }
            val result = simklSuggestionsFromDetail(response.text)
            
            
            // Persistence must never delay the recommendation returned to the detail page.
            // The same supervisor keeps this write alive beyond the 1-second UI wait.
            if (result.isNotEmpty()) {
                val persistBody = response.text
                simklSuggestionScope.launch {
                    try {
                        MetadataDiskCache.write("simkl_recs", key, persistBody)
                        
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        
                    }
                }
            }
            result
            }
        }

        private fun requestSimklSuggestions(
            type: String, tmdbId: String, imdbId: String?, malId: Int?
        ): Deferred<List<SimklSuggestion>>? {
            if (tmdbId.toIntOrNull()?.let { it > 0 } != true) {
                
                return null
            }
            val auth = AccountManager.syncApis.firstOrNull { it.idPrefix == "simkl" }
                ?.authData()
            if (auth == null) {
                
                return null
            }
            val headers = SimklApi.getHeaders(auth.token)
            val clientId = headers["simkl-api-key"]?.takeIf(String::isNotBlank)
            if (clientId == null) {
                
                return null
            }
            val key = listOf(type, tmdbId, imdbId.orEmpty(), malId?.toString().orEmpty()).joinToString("|")
            val cached = simklSuggestionCache[key]
            val now = System.currentTimeMillis()
            if (cached != null && now >= cached.at &&
                now - cached.at < if (cached.entries.isEmpty()) SIMKL_RECOMMENDATION_EMPTY_TTL_MS else SIMKL_RECOMMENDATION_TTL_MS) {
                
                return CompletableDeferred(cached.entries)
            }
            val userId = auth.user.id.toString()
            val task = synchronized(simklSuggestionLock) {
                simklSuggestionFlights[key] ?: simklSuggestionScope.async(start = CoroutineStart.LAZY) {
                    val result = try {
                        withTimeoutOrNull(6500L) {
                            loadSimklSuggestions(userId, type, tmdbId, imdbId, malId, headers, clientId, key)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        
                        emptyList()
                    }
                    val rows = result.orEmpty()
                    
                    simklSuggestionCache[key] = SimklSuggestionCache(rows, System.currentTimeMillis())
                    rows
                }.also {
                    simklSuggestionFlights[key] = it
                }
            }
            
            task.invokeOnCompletion {
                synchronized(simklSuggestionLock) {
                    if (simklSuggestionFlights[key] === task) simklSuggestionFlights.remove(key)
                }
            }
            task.start()
            return task
        }

        private val IMDB_ID_REGEX = "tt[0-9]+".toRegex()
        private val JSON_ID_EXTRACT_REGEX = "\"id\":\"([^\"]+)\"".toRegex()

        private const val MANIFEST_CACHE_TTL_MS = 3_600_000L

        private val settingsCache = ConcurrentHashMap<String, Pair<Boolean, Long>>()

        @Volatile private var cachedCustomHosts: Set<String>? = null
        @Volatile private var cachedCustomHostsTime: Long = 0
        private const val SETTINGS_CACHE_TTL = 60_000L

        private data class StreamSubtitleAddon(val name: String, val url: String)
        private data class SyncSettings(
            val syncUrl: String,
            val syncMap: Map<String, Boolean>,
            val historySyncMap: Map<String, Boolean>,
            val streamSubtitleMap: Map<String, Boolean>,
            val streamSubtitleUrls: List<StreamSubtitleAddon>,
            val disabledCatalogUrls: Set<String> = emptySet(),
            val catalogEnabledIds: Map<Long, Boolean> = emptyMap()
        )
        @Volatile private var cachedSyncSettings: SyncSettings? = null
        @Volatile private var cachedSyncSettingsTime: Long = 0
        private const val SYNC_SETTINGS_TTL = 3000L

        fun invalidateSyncSettingsCache() = synchronized(StremioC::class.java) {
            cachedSyncSettings = null
            cachedSyncSettingsTime = 0L

        }

        internal fun isCatalogAddonEnabled(url: String, id: Long? = null): Boolean {
            val settings = getSyncSettings()
            return id?.let { settings.catalogEnabledIds[it] }
                ?: (url.isBlank() || stremioManifestUrl(url) !in settings.disabledCatalogUrls)
        }

        private fun getSyncSettings(): SyncSettings {
            val now = System.currentTimeMillis()
            val cached = cachedSyncSettings
            if (cached != null && (now - cachedSyncSettingsTime) < SYNC_SETTINGS_TTL) {
                return cached
            }
            synchronized(StremioC::class.java) {
                val doubleCached = cachedSyncSettings
                if (doubleCached != null && (now - cachedSyncSettingsTime) < SYNC_SETTINGS_TTL) {
                    return doubleCached
                }
                val context = CloudStreamApp.context ?: return SyncSettings("", emptyMap(), emptyMap(), emptyMap(), emptyList())
                val prefs = context.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
                val syncUrl = prefs.getString("history_sync_url", "") ?: ""
                val json = prefs.getString("stremio_saved_links", null)
                val map = mutableMapOf<String, Boolean>()
                val historyMap = mutableMapOf<String, Boolean>()
                val extraMap = mutableMapOf<String, Boolean>()
                val catalogEnabled = mutableMapOf<String, Boolean>()
                val catalogEnabledIds = mutableMapOf<Long, Boolean>()
                if (json != null) {
                    try {
                        val arr = org.json.JSONArray(json)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val rawLink = obj.optString("link", "")
                            val enabled = obj.optBoolean("enabled", true)
                            if (obj.has("id")) catalogEnabledIds[obj.optLong("id")] = enabled
                            if (rawLink.isNotBlank()) {
                                val key = stremioManifestUrl(rawLink)
                                catalogEnabled[key] = catalogEnabled[key] == true || enabled
                            }
                            if (!enabled) continue
                            val link = obj.optString("link", "").cleanBaseUrl().lowercase()
                            if (link.isNotBlank()) {
                                map[link] = obj.optBoolean("isSyncEnabled", true)
                                historyMap[link] = obj.optBoolean("isHistorySyncEnabled", map[link] ?: true)
                                extraMap[link] = obj.optBoolean("isStreamSubtitleSyncEnabled", false)
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
                val extraUrls = runCatching {
                    val arr = org.json.JSONArray(prefs.getString("stream_subtitle_addon_urls", "[]"))
                    (0 until arr.length()).mapNotNull { i ->
                        when (val entry = arr.opt(i)) {
                            is org.json.JSONObject -> if (entry.optBoolean("enabled", true))
                                StreamSubtitleAddon(entry.optString("name").trim(), entry.optString("url").trim()) else null
                            is String -> StreamSubtitleAddon("", entry.trim()) 
                            else -> null
                        }?.takeIf { it.url.isNotBlank() }
                    }
                }.onFailure { error ->
                }.getOrDefault(emptyList()).distinctBy { stremioManifestUrl(it.url) }
                val result = SyncSettings(syncUrl, map, historyMap, extraMap, extraUrls,
                    catalogEnabled.filterValues { !it }.keys.toSet(), catalogEnabledIds)
                cachedSyncSettings = result
                cachedSyncSettingsTime = System.currentTimeMillis()
                return result
            }
        }

        val networkSemaphore = Semaphore(35)

        // Bound only the expensive extractor callback / integrated provider
        // invocations. Metadata and link discovery remain concurrent so the
        // first playable result is not delayed by unrelated network lookups.
        private val streamCallbackLimiter = Semaphore(8)
        private val integratedSourceLimiter = Semaphore(4)

        private suspend fun <T> withStreamCallback(
            @Suppress("UNUSED_PARAMETER") category: String,
            work: suspend () -> T
        ): T = streamCallbackLimiter.withPermit { work() }

        private suspend fun <T> withIntegratedSource(
            @Suppress("UNUSED_PARAMETER") label: String,
            work: suspend () -> T
        ): T = integratedSourceLimiter.withPermit { work() }

        const val HOME_FOREGROUND_CONCURRENCY = 10
        const val HOME_FOREGROUND_PER_HOST_CONCURRENCY = 10
        private val homeForegroundSemaphore = Semaphore(HOME_FOREGROUND_CONCURRENCY)

        private class CatalogHttpException(val statusCode: Int) :
            IllegalStateException("Catalog HTTP $statusCode")

        @Volatile private var cachedCineStreamProvider: MainAPI? = null
        @Volatile private var cachedStreamPlayProvider: MainAPI? = null

        @Volatile private var cachedClassLoaderRef: WeakReference<ClassLoader>? = null

        internal val fallbackLogoTitles = lruCacheMap<String, Boolean>(200)

        private val libraryOriginalUrlsByMeta = lruCacheMap<String, MutableSet<String>>(200)
        internal val expectedLogoByUrl = lruCacheMap<String, Boolean>(200)
        internal val animeAudioByUrl = lruCacheMap<String, Boolean>(200)
        // Distinguish an episode-based result awaiting native season selection
        // from a movie, which has no native seasonSelections event.
        internal val episodeBasedByUrl = lruCacheMap<String, Boolean>(200)
        internal val animeFallbackActorsByUrl = lruCacheMap<String, List<ActorData>>(100)

        // Successful AniList cast survives result-view recreation. No negative
        // entries: an unavailable AniList response must remain retryable.
        private val animeCastCache = lruCacheMap<Int, Pair<List<ActorData>, Long>>(24)
        private val animeCastFlights = mutableMapOf<Int, Deferred<List<ActorData>?>>()
        private val animeCastScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val subtitleAnimeManifestCache = lruCacheMap<String, Pair<String?, Long>>(80)
        private val subtitleAnimeManifestLocks = ConcurrentHashMap<String, Mutex>()

        internal fun cachedAnimeCast(id: Int): List<ActorData>? {
            if (id <= 0) return null
            synchronized(animeCastCache) {
                val cached = animeCastCache[id] ?: return null
                val age = System.currentTimeMillis() - cached.second
                if (age < 0 || age >= 24L * 60 * 60 * 1000) {
                    animeCastCache.remove(id)
                    return null
                }
                return cached.first
            }
        }

        internal fun rememberAnimeCast(id: Int, actors: List<ActorData>?) {
            if (id <= 0 || actors.isNullOrEmpty()) return
            synchronized(animeCastCache) {
                animeCastCache[id] = actors.toList() to System.currentTimeMillis()
            }
        }

        // Only direct StremioC cast lookups use this flight. The normal
        // SyncViewModel metadata lookup is never duplicated by this method.
        internal suspend fun loadAnimeCast(id: Int): List<ActorData>? {
            cachedAnimeCast(id)?.let { return it }
            val flight = synchronized(animeCastFlights) {
                animeCastFlights[id] ?: animeCastScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val actors = com.lagradost.cloudstream3.syncproviders.SyncRepo(
                            AccountManager.aniListApi
                        ).load(id.toString()).getOrNull()?.actors
                        rememberAnimeCast(id, actors)
                        actors
                    } finally {
                        synchronized(animeCastFlights) { animeCastFlights.remove(id) }
                    }
                }.also { animeCastFlights[id] = it; it.start() }
            }
            return flight.await()
        }

        internal val seasonEpisodeCountsByUrl = lruCacheMap<String, Map<Int, Int>>(200)
        internal val subtitleOrderByUrl = Collections.synchronizedMap(lruCacheMap<String, Int>(1200))
        internal val trailerChoicesByUrl = lruCacheMap<String, List<TrailerChoice>>(200)

        private data class AddonMetaEntry(val bytes: ByteArray, val at: Long, val ttlMs: Long)
        private val addonMetaCache = LinkedHashMap<String, AddonMetaEntry>(48, 0.75f, true)
        private var addonMetaCacheBytes = 0L
        private const val ADDON_META_RAM_LIMIT = 12L * 1024 * 1024
        private const val ADDON_META_RAM_ENTRIES = 48
        private const val ADDON_META_MAX_RAW = 2 * 1024 * 1024
        private const val ADDON_META_MOVIE_TTL_MS = 3L * 24 * 60 * 60 * 1000
        private const val ADDON_META_SERIES_TTL_MS = 12L * 60 * 60 * 1000
        private const val ADDON_META_MAX_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private val addonMetaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val addonMetaFlights = mutableMapOf<String, Deferred<String>>()
        private val metadataTextFlights = mutableMapOf<String, Deferred<String>>()
        private val animeHttpFlights = mutableMapOf<String, Deferred<String>>()

        private suspend fun fetchSharedMetadataText(url: String): String {
            val task = synchronized(metadataTextFlights) {
                metadataTextFlights[url] ?: addonMetaScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val text = customSession.get(url).text
                        text
                    } catch (error: Throwable) {
                        throw error
                    } finally {
                        synchronized(metadataTextFlights) { metadataTextFlights.remove(url) }
                    }
                }.also {
                    metadataTextFlights[url] = it
                    it.start()
                }
            }
            return task.await()
        }

        private suspend fun fetchSharedAnimeText(url: String): String {
            val task = synchronized(animeHttpFlights) {
                animeHttpFlights[url] ?: addonMetaScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val text = aioAnimeSession.get(url, headers = mapOf(
                            "Cache-Control" to "no-cache, no-store",
                            "Pragma" to "no-cache"
                        )).text
                        text
                    } catch (error: Throwable) {
                        throw error
                    } finally {
                        synchronized(animeHttpFlights) { animeHttpFlights.remove(url) }
                    }
                }.also {
                    animeHttpFlights[url] = it
                    it.start()
                }
            }
            return task.await()
        }

        private fun cacheAddonMetaInRam(url: String, text: String, ttlMs: Long, stamp: Long = System.currentTimeMillis()) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (ttlMs <= 0L || bytes.size > ADDON_META_MAX_RAW) {
                return
            }
            synchronized(addonMetaCache) {
                addonMetaCache.remove(url)?.let { addonMetaCacheBytes -= it.bytes.size.toLong() }
                addonMetaCache[url] = AddonMetaEntry(bytes, stamp, ttlMs)
                addonMetaCacheBytes += bytes.size.toLong()
                val cursor = addonMetaCache.entries.iterator()
                while ((addonMetaCache.size > ADDON_META_RAM_ENTRIES ||
                        addonMetaCacheBytes > ADDON_META_RAM_LIMIT) && cursor.hasNext()) {
                    val removed = cursor.next()
                    addonMetaCacheBytes -= removed.value.bytes.size.toLong()
                    cursor.remove()
                }
            }
        }

        private fun cachedAddonMetaInRam(url: String): String? {
            val bytes = synchronized(addonMetaCache) {
                val held = addonMetaCache[url]
                if (held != null && System.currentTimeMillis() - held.at < held.ttlMs) {
                    held.bytes
                } else {
                    addonMetaCache.remove(url)?.let { addonMetaCacheBytes -= it.bytes.size.toLong() }
                    null
                }
            }
            return bytes?.let { String(it, Charsets.UTF_8) }
        }

        private fun containsTransientMetaLinks(root: JsonNode): Boolean {
            val meta = root.get("meta") ?: root
            if (meta.get("streams")?.size()?.let { it > 0 } == true ||
                meta.get("url")?.asText()?.isNotBlank() == true) return true
            val videos = meta.get("videos") ?: return false
            return videos.any { video ->
                listOf("streams", "sources", "stream").any { key ->
                    video.get(key)?.let { it.isObject || it.isArray && it.size() > 0 } == true
                } || video.get("url")?.asText()?.isNotBlank() == true
            }
        }

        private fun addonMetaTtlMs(
            url: String, cacheControl: String?, age: String?, expires: String?,
            date: String?, root: JsonNode
        ): Long {
            val policy = cacheControl.orEmpty().lowercase(java.util.Locale.ROOT)
            val directives = policy.split(',').map { it.trim() }
            if (directives.any {
                it == "no-store" || it == "no-cache" || it == "private" ||
                    it == "max-age=0" || it == "s-maxage=0"
            }) return 0L
            val headerSeconds = directives.firstOrNull { it.startsWith("max-age=") }
                ?.substringAfter('=')?.trim('"', ' ')?.toLongOrNull()
            val ageSeconds = age?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            if (headerSeconds != null) {
                return (headerSeconds - ageSeconds)
                    .coerceIn(0L, ADDON_META_MAX_TTL_MS / 1000L) * 1000L
            }
            if (!expires.isNullOrBlank()) {
                fun parseHttpDate(value: String?): Long? = runCatching {
                    java.text.SimpleDateFormat(
                        "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US
                    ).parse(value ?: "")?.time
                }.getOrNull()
                val expiryTime = parseHttpDate(expires)
                if (expiryTime != null) {
                    val referenceTime = parseHttpDate(date) ?: System.currentTimeMillis()
                    return (expiryTime - referenceTime - ageSeconds * 1000L)
                        .coerceIn(0L, ADDON_META_MAX_TTL_MS)
                }
            }
            val addonSeconds = root.get("cacheMaxAge")?.takeIf { it.isNumber }?.asLong()
            if (addonSeconds != null) {
                return (addonSeconds - ageSeconds)
                    .coerceIn(0L, ADDON_META_MAX_TTL_MS / 1000L) * 1000L
            }
            return if (url.contains("/meta/movie/", ignoreCase = true))
                ADDON_META_MOVIE_TTL_MS else ADDON_META_SERIES_TTL_MS
        }

        private fun addonMetaRejectReason(url: String, root: JsonNode): String? {
            if (root.get("success")?.asBoolean() == false) return "server_success_false"
            val meta = root.get("meta") ?: root
            if (!meta.isObject) return "meta_not_object"
            val entry = runCatching { parseJson<CatalogEntry>(meta.toString()) }.getOrNull()
                ?: return "meta_parse_failed"
            val returnedId = entry.id?.trim()?.takeIf { it.isNotEmpty() }
                ?: return "missing_response_id"
            val returnedType = canonicalDetailType(entry.type)
                ?: return "missing_response_type"
            val encodedId = url.substringBefore('?').substringAfterLast('/')
                .removeSuffix(".json")
            val expectedId = runCatching {
                URLDecoder.decode(encodedId, "UTF-8")
            }.getOrDefault(encodedId)
            val expectedType = canonicalDetailType(
                url.substringAfter("/meta/").substringBefore('/')
            )
            if (returnedType != expectedType) {
                return "type_mismatch expected=$expectedType actual=$returnedType"
            }

            val expectedAnime = expectedId.startsWith("kitsu:") ||
                expectedId.startsWith("mal:") || expectedId.startsWith("anilist:")
            val mappedExternal = (returnedId.startsWith("tt") &&
                IMDB_ID_REGEX.matches(returnedId)) ||
                (returnedId.startsWith("tmdb:") &&
                    returnedId.removePrefix("tmdb:").toIntOrNull()?.let { it > 0 } == true)
            if (returnedId != expectedId && !(expectedAnime && mappedExternal &&
                    !entry.name.isNullOrBlank())) {
                return "id_mismatch"
            }
            return null
        }

        private fun isAddonMetaUrl(url: String): Boolean {
            val path = url.substringBefore('?')
            return (path.startsWith("https://") || path.startsWith("http://")) &&
                path.contains("/meta/") && path.endsWith(".json", ignoreCase = true)
        }

        private fun addonMetaStorageKey(url: String): String {
            val context = CloudStreamApp.context
            val prefs = context?.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
            val language = prefs?.getString("tmdb_language_code", "en-US").orEmpty()
            val addonBase = url.substringBefore("/meta/")
            val version = context
                ?.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
                ?.getString("manifest_version_" + stremioCatalogKey(addonBase), "").orEmpty()

            return "$language|$version|$url"
        }

        private fun invalidateAddonVersionCache(manifestUrl: String) {
            val prefix = manifestUrl.substringBefore("/manifest.json") + "/meta/"
            synchronized(addonMetaCache) {
                val entries = addonMetaCache.entries.iterator()
                while (entries.hasNext()) {
                    val entry = entries.next()
                    if (entry.key.contains("|$prefix")) {
                        addonMetaCacheBytes -= entry.value.bytes.size.toLong()
                        entries.remove()
                    }
                }
            }
        }

        private suspend fun fetchAddonMetaText(url: String, forceRefresh: Boolean = false): String {
            if (!isAddonMetaUrl(url)) {
                return customSession.get(url, headers = if (forceRefresh)
                    mapOf("Cache-Control" to "no-cache") else emptyMap()).text
            }
            val storageKey = addonMetaStorageKey(url)
            val previous = if (forceRefresh) {
                cachedAddonMetaInRam(storageKey)
                    ?: MetadataDiskCache.read("meta", storageKey)?.first
            } else null
            if (!forceRefresh) {
                cachedAddonMetaInRam(storageKey)?.let {
                    return it
                }
                MetadataDiskCache.readWithTtl("meta", storageKey)?.let {
                    cacheAddonMetaInRam(storageKey, it.json, it.ttlMs, it.timestamp)
                    return it.json
                }
            }

            val flightKey = (if (forceRefresh) "force|" else "normal|") + storageKey
            val flight = synchronized(addonMetaFlights) {
                addonMetaFlights[flightKey] ?: addonMetaScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val response = customSession.get(url, headers = if (forceRefresh)
                            mapOf("Cache-Control" to "no-cache") else emptyMap())
                        if (response.code !in 200..299) {
                            throw IllegalStateException("Stremio meta HTTP " + response.code)
                        }
                        val body = response.text
                        val root = parseJson<JsonNode>(body)
                        fun header(name: String): String? = response.headers
                            .firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
                        val policy = header("Cache-Control")
                        val vary = header("Vary")
                        val ttlMs = addonMetaTtlMs(
                            url, policy, header("Age"), header("Expires"), header("Date"), root
                        )

                        val safeVary = vary.orEmpty().split(',').all {
                            it.trim().isEmpty() || it.trim().equals("Accept-Encoding", true)
                        }
                        val rejectReason = addonMetaRejectReason(url, root)
                        val valid = rejectReason == null
                        val transient = containsTransientMetaLinks(root)
                        val image = body.contains("data:image/", ignoreCase = true)
                        if (valid && ttlMs > 0L && safeVary && !transient && !image) {
                            cacheAddonMetaInRam(storageKey, body, ttlMs)

                            addonMetaScope.launch {
                                MetadataDiskCache.write("meta", storageKey, body, ttlMs)
                            }
                        }
                        body
                    } catch (error: Throwable) {
                        throw error
                    } finally {
                        synchronized(addonMetaFlights) { addonMetaFlights.remove(flightKey) }
                    }
                }.also { deferred ->
                    addonMetaFlights[flightKey] = deferred
                    deferred.start()
                }
            }
            return try {
                flight.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (forceRefresh && previous != null) {
                    previous
                } else throw error
            }
        }
        internal val genreCatalogsByUrl = lruCacheMap<String, Map<String, CatalogShortcutTarget>>(200)
        private val tmdbDetailsCache = lruCacheMap<String, Pair<TmdbDetailResponse, Long>>(200)
        private val tmdbSeasonCache = lruCacheMap<String, Pair<TmdbSeasonDetail, Long>>(300)
        private val tmdbFindCache = lruCacheMap<String, Pair<Pair<String, String>?, Long>>(5000)
        private val animeMappingCache = lruCacheMap<String, Pair<AnimeSeasonMapping, Long>>(5000)
        private val animeNoMappingCache = lruCacheMap<String, Long>(1000)
        private val mdbRatingsCache = lruCacheMap<String, Pair<List<String>, Long>>(1000)
        private val mdbFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mdbFetchSemaphore = Semaphore(5)
        private val mdbInflight = mutableMapOf<MdbTarget, Deferred<SpeculativeMdbResult?>>()
        private val detailLoadHints = lruCacheMap<String, DetailUrlHint>(2000)
        private const val METADATA_CACHE_TTL = 24 * 60 * 60 * 1000L

        internal fun mdbRatingsForUi(cacheKey: String): List<String>? {
            val cached = mdbRatingsCache[cacheKey] ?: return null
            val age = System.currentTimeMillis() - cached.second
            return cached.first.takeIf { age >= 0L && age < METADATA_CACHE_TTL && it.isNotEmpty() }
        }
        private const val SEASON_CACHE_TTL = 12 * 60 * 60 * 1000L

        private fun recommendationPosterUrl(
            source: String?, tmdbId: Int, movie: Boolean, fallback: String, metadataHost: String?
        ): String? {
            if (tmdbId <= 0) return null
            val outer = source?.toHttpUrlOrNull() ?: return null
            val outerParts = outer.pathSegments
            val metaProxy = metadataHost != null && outer.host == metadataHost &&
                outerParts.size == 5 && outerParts[0] == "poster-cache" &&
                outerParts[1] == "proxy" && outerParts[2] == "poster" &&
                outerParts[3] in listOf("movie", "series")

            val nested = if (metaProxy) outer.queryParameter("url") else null
            val url = if (nested != null) nested.toHttpUrlOrNull() ?: return null else outer
            val parts = url.pathSegments
            val host = url.host.lowercase()
            val kind = if (movie) "movie" else "series"
            val oldId = parts.lastOrNull().orEmpty()
            val knownId = Regex("tt[0-9]+|tmdb:(?:(?:movie|series|tv):)?[0-9]+|(?:tvdb|kitsu|mal|anilist|anidb):[0-9]+")
            val rpdbId = Regex("tt[0-9]+|(?:movie|series|tv)-[0-9]+")
            val builder = url.newBuilder()

            when {

                metaProxy && nested == null && outer.queryParameter("sig") == null &&
                    !outer.queryParameter("key").isNullOrBlank() && oldId.matches(knownId) -> {
                    builder.setPathSegment(3, kind).setPathSegment(4, "tmdb:$tmdbId")
                    builder.setQueryParameter("fallback",
                        fallback.takeUnless { it == TRANSPARENT_PIXEL }.orEmpty())
                    return builder.build().toString()
                }
                (host in setOf("extendedratings.com", "www.extendedratings.com", "xrdb.elfhosted.com") ||
                    parts.size == 2 && url.queryParameter("config") != null &&
                        url.queryParameter("v") != null) &&
                    parts.size == 2 && parts[0] == "poster" && oldId.matches(knownId) &&
                    url.queryParameter("sig") == null -> {
                    builder.setPathSegment(1, "tmdb:$kind:$tmdbId")
                }
                host in setOf("api.ratingposterdb.com", "api.top-posters.com",
                    "openposterdb.com", "api.openposterdb.com", "api.aioratings.com") &&
                    parts.size == 4 && parts[0].isNotBlank() &&
                    parts[1] in listOf("imdb", "tmdb", "tvdb") &&
                    (host == "api.top-posters.com" && parts[2] == "poster" ||
                        host == "api.aioratings.com" &&
                            parts[2].matches(Regex("[a-zA-Z0-9_-]{1,64}")) &&
                            !parts[2].startsWith("backdrop") && !parts[2].startsWith("logo") &&
                            !parts[2].startsWith("episode") ||
                        host !in setOf("api.top-posters.com", "api.aioratings.com") &&
                            (parts[2].startsWith("poster-") || parts[2].startsWith("textless-"))) &&
                    oldId.endsWith(".jpg") && oldId.removeSuffix(".jpg").matches(rpdbId) &&
                    url.queryParameter("img") == null && url.queryParameter("sig") == null &&
                    url.queryParameter("url") == null -> {
                    val prefix = when {
                        movie -> "movie"
                        host == "api.aioratings.com" && !oldId.startsWith("series-") -> "tv"
                        else -> "series"
                    }
                    builder.setPathSegment(1, "tmdb")
                        .setPathSegment(3, "$prefix-$tmdbId.jpg")
                    if (url.queryParameter("fallback_url") != null) {
                        if (fallback == TRANSPARENT_PIXEL)
                            builder.removeAllQueryParameters("fallback_url")
                        else builder.setQueryParameter("fallback_url", fallback)
                    } else if (host == "api.top-posters.com" && fallback != TRANSPARENT_PIXEL) {
                        builder.setQueryParameter("fallback_url", fallback)
                    }
                }
                host in setOf("easyratingsdb.com", "www.easyratingsdb.com") &&
                    parts.size in 3..4 && parts[0].startsWith("Tk-") &&
                    parts[1] == "poster" && oldId.endsWith(".jpg") &&
                    oldId.removeSuffix(".jpg").matches(knownId) &&
                    (parts.size == 3 || parts[2] in listOf("movie", "series")) -> {
                    if (parts.size == 4) {
                        builder.setPathSegment(2, kind).setPathSegment(3, "tmdb:$tmdbId.jpg")
                    } else {
                        builder.setPathSegment(2, kind).addPathSegment("tmdb:$tmdbId.jpg")
                    }
                }
                else -> return null
            }
            return builder.build().toString()
        }

        internal fun actorPosterUrl(
            source: String?, tmdbId: Int, movie: Boolean,
            posterPath: String?, metadataHost: String?
        ): String? {
            val fallback = posterPath?.takeIf { it.startsWith("/") }
                ?.let { "https://image.tmdb.org/t/p/w500$it" } ?: TRANSPARENT_PIXEL
            return recommendationPosterUrl(source, tmdbId, movie, fallback, metadataHost)
                ?: fallback.takeUnless { it == TRANSPARENT_PIXEL }
        }

        val catalogSentIds = ConcurrentHashMap<String, MutableSet<String>>()
        val catalogSkipState = ConcurrentHashMap<String, Int>()

        private val catalogHomeCursors = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
        private val catalogGenerations = ConcurrentHashMap<String, Long>()

        private val aioAnimeSession by lazy {
            val client = app.baseClient.newBuilder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(1_500, TimeUnit.MILLISECONDS)
                .readTimeout(2_500, TimeUnit.MILLISECONDS)
                .cache(null)
                .build()
            Requests(client).apply {
                responseParser = app.responseParser
                defaultHeaders = app.defaultHeaders
            }
        }

        val customSession by lazy {
            val newClient = app.baseClient.newBuilder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .dispatcher(Dispatcher().apply {
                    maxRequests = 70
                    maxRequestsPerHost = 40
                })
                .connectionPool(ConnectionPool(45, 15, TimeUnit.SECONDS))
                .build()

            Requests(newClient).apply {
                this.responseParser = app.responseParser
                this.defaultHeaders = app.defaultHeaders
            }
        }

        private val homeForegroundClient by lazy {
            app.baseClient.newBuilder()
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(35, TimeUnit.SECONDS)
                .cache(null)
                .dispatcher(Dispatcher().apply {
                    maxRequests = HOME_FOREGROUND_CONCURRENCY
                    maxRequestsPerHost = HOME_FOREGROUND_PER_HOST_CONCURRENCY
                })
                .connectionPool(ConnectionPool(HOME_FOREGROUND_CONCURRENCY, 60, TimeUnit.SECONDS))
                .build()
        }
        private val homeHostBackoff = ConcurrentHashMap<String, Long>()
        private val homeJsonFlights = mutableMapOf<String, Deferred<String>>()

        private suspend fun fetchHomeJson(url: String): String {
            val task = synchronized(homeJsonFlights) {
                homeJsonFlights[url] ?: addonMetaScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val body = fetchHomeJsonUnshared(url)
                        body
                    } catch (error: Throwable) {
                        throw error
                    } finally {
                        synchronized(homeJsonFlights) { homeJsonFlights.remove(url) }
                    }
                }.also {
                    homeJsonFlights[url] = it
                    it.start()
                }
            }
            return task.await()
        }

        private suspend fun fetchHomeJsonUnshared(url: String): String {
            currentCoroutineContext().ensureActive()
            val host = url.toHttpUrlOrNull()?.host ?: throw IllegalArgumentException("Invalid catalog URL")
            val now = System.currentTimeMillis()
            val until = homeHostBackoff[host] ?: 0L
            if (until > now) throw IllegalStateException("Catalog server rate limited; retry later")

            val request = okhttp3.Request.Builder()
                .url(url)
                .apply { app.defaultHeaders.forEach { (name, value) -> header(name, value) } }
                .build()
            return suspendCancellableCoroutine { continuation ->
                val call = homeForegroundClient.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        val outcome = runCatching {
                            response.use { res ->
                                if (res.code == 429) {
                                    val raw = res.header("Retry-After")
                                    val delayMs = raw?.toLongOrNull()?.coerceIn(0L, 86400L)?.times(1000L)
                                        ?: runCatching {
                                            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                                                .parse(raw ?: "")?.time?.minus(System.currentTimeMillis())
                                        }.getOrNull()?.coerceIn(0L, 86400000L) ?: 10000L
                                    synchronized(homeHostBackoff) {
                                        homeHostBackoff[host] = maxOf(
                                            homeHostBackoff[host] ?: 0L,
                                            System.currentTimeMillis() + delayMs
                                        )
                                    }
                                    throw CatalogHttpException(429)
                                }
                                if (!res.isSuccessful) throw CatalogHttpException(res.code)
                                res.body?.string() ?: throw IllegalStateException("Empty catalog response body")
                            }
                        }
                        if (continuation.isActive) continuation.resumeWith(outcome)
                    }
                })
            }
        }

        fun loadProviderDynamically(
            pluginKeyword: String,
            className: String,
            preferencesFile: String? = null
        ): MainAPI? {
            fun instantiate(clazz: Class<*>): MainAPI? {
                val context = CloudStreamApp.context
                if (preferencesFile != null && context != null) {
                    val prefs = context.getSharedPreferences(preferencesFile, Context.MODE_PRIVATE)
                    try {
                        return clazz.getConstructor(SharedPreferences::class.java)
                            .newInstance(prefs) as? MainAPI
                    } catch (_: NoSuchMethodException) {

                    }
                }
                return clazz.newInstance() as? MainAPI
            }
            val loader = cachedClassLoaderRef?.get()
            if (loader != null) {
                try {
                    val clazz = loader.loadClass(className)
                    return instantiate(clazz)
                } catch (e: Exception) {}
            }
            try {
                val pmClass = Class.forName("com.lagradost.cloudstream3.plugins.PluginManager")
                val pmInstance = pmClass.getField("INSTANCE").get(null)

                for (field in pmClass.declaredFields) {
                    field.isAccessible = true
                    val obj = runSuspendCatching { field.get(pmInstance) }.getOrNull()
                    val items = when (obj) {
                        is Map<*, *> -> obj.values
                        is java.util.Collection<*> -> obj
                        is Array<*> -> obj.toList()
                        else -> null
                    }

                    if (items != null) {
                        for (plugin in items) {
                            val pName = plugin?.javaClass?.name ?: ""
                            if (pName.contains(pluginKeyword, true)) {
                                try {
                                    val candidateLoader = plugin!!.javaClass.classLoader
                                    val provider = instantiate(candidateLoader.loadClass(className))
                                    if (provider != null) {
                                        cachedClassLoaderRef = WeakReference(candidateLoader)
                                        return provider
                                    }
                                } catch (_: ClassNotFoundException) {

                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            return null
        }

        fun isIntegrationEnabled(prefKey: String, default: Boolean = true): Boolean {
            val now = System.currentTimeMillis()
            val cached = if (prefKey == "mdblist_enabled") null else settingsCache[prefKey]
            if (cached != null && (now - cached.second) < 60000L) {
                return cached.first
            }
            val context = CloudStreamApp.context ?: return default
            val possibleNames = listOf("StremioC", "StremioC_preferences", "stremio_preferences", "com.hsp1020_preferences", context.packageName + "_preferences")

            var isEnabled = default
            for (name in possibleNames) {
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                if (prefs.contains(prefKey)) {
                    isEnabled = prefs.getBoolean(prefKey, true)
                    break
                }
            }

            if (prefKey != "mdblist_enabled") settingsCache[prefKey] = Pair(isEnabled, now)
            return isEnabled
        }

    }

    private fun SearchResponse.extractPureId(): String {
        return try {
            JSON_ID_EXTRACT_REGEX.find(this.url)?.groupValues?.get(1) ?: this.url
        } catch (e: Exception) {
            this.url
        }
    }

    private data class DetailUrlHint(
        val type: String,
        val id: String,
        val tmdbId: String? = null
    )

    private data class SpeculativeTmdbChain(
        val sourceImdbId: String?,
        val tmdbId: String,
        val mediaType: String,
        val viaFind: Boolean,
        val detail: TmdbDetailResponse?
    ) {
        fun matchesDirect(id: String, type: String): Boolean = tmdbId == id && mediaType == type
        fun matchesFind(imdbId: String?): Boolean = viaFind && !imdbId.isNullOrBlank() && sourceImdbId == imdbId
        fun asFindResult(): Pair<String, String>? = if (viaFind) tmdbId to mediaType else null
    }

    private data class MdbTarget(
        val id: String,
        val isTmdb: Boolean,
        val mediaType: String,
        val credentialFingerprint: String = mdbCredentialFingerprint()
    )

    private data class SpeculativeMdbResult(
        val target: MdbTarget,
        val ratings: List<String>,
        val requestSucceeded: Boolean
    ) {
        fun matches(other: MdbTarget): Boolean = target == other && requestSucceeded
    }

    private fun detailMediaType(type: String?): String =
        if (canonicalDetailType(type) == "movie") "movie" else "tv"

    private fun isKitsuOrMalId(id: String?): Boolean =
        id?.startsWith("kitsu:") == true || id?.startsWith("mal:") == true

    private fun isLibraryAnimeId(id: String?): Boolean =
        isKitsuOrMalId(id) || id?.startsWith("anilist:") == true

    private fun directMdbTarget(id: String?, mediaType: String = "tv"): MdbTarget? = when {
        id?.startsWith("tt") == true -> MdbTarget(id, false, mediaType)
        id?.startsWith("tmdb:") == true -> MdbTarget(id.removePrefix("tmdb:"), true, mediaType)
        else -> null
    }

    private fun resolvedMdbTarget(
        entry: CatalogEntry,
        resolvedTmdbId: String?,
        resolvedImdbId: String?
    ): MdbTarget? {
        val id = resolvedTmdbId ?: resolvedImdbId ?: when {
            entry.id?.startsWith("tt") == true -> entry.id
            entry.id?.startsWith("tmdb:") == true -> entry.id.removePrefix("tmdb:")
            entry.moviedb_id != null -> entry.moviedb_id.toString()
            !entry.tmdbIdExt.isNullOrBlank() -> entry.tmdbIdExt
            else -> null
        } ?: return null
        return MdbTarget(id, id == resolvedTmdbId || !id.startsWith("tt"), detailMediaType(entry.type))
    }

    private suspend fun fetchMdbAttempt(target: MdbTarget): SpeculativeMdbResult {
        val cacheKey = "${target.credentialFingerprint}:${target.isTmdb}:${target.mediaType}:${target.id}"
        val cached = stremioCacheMemoryRead(mdbRatingsCache[cacheKey], METADATA_CACHE_TTL) ?: MetadataDiskCache.read("mdb", cacheKey)?.let { (json, stamp) ->
            runCatching { parseJson<List<String>>(json) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { ratings ->
                    Pair(ratings, stamp).also {
                        mdbRatingsCache[cacheKey] = it
                    }
                }
        }
        if (cached != null) {

            StremioCMdbUiBridge.publish(cacheKey)
            return SpeculativeMdbResult(target, cached.first, true)
        }
        val attempt = runSuspendCatching {
            run {
                mdbFetchSemaphore.withPermit {
                    fetchMdbListRatings(target.id, target.isTmdb, target.mediaType)
                }
            }
        }
        val ratings = attempt.getOrDefault(emptyList())
        if (ratings.isNotEmpty()) {
            mdbRatingsCache[cacheKey] = ratings to System.currentTimeMillis()

            StremioCMdbUiBridge.publish(cacheKey)
            MetadataDiskCache.write("mdb", cacheKey, ratings.toJson())
        }
        return SpeculativeMdbResult(target, ratings, attempt.isSuccess)
    }

    private fun CoroutineScope.launchMdbAttempt(target: MdbTarget?): Deferred<SpeculativeMdbResult?>? =
        target?.let { requested ->
            synchronized(mdbInflight) {
                mdbInflight[requested] ?: mdbFetchScope.async(start = CoroutineStart.LAZY) {
                    fetchMdbAttempt(requested)
                }.also { job ->
                    mdbInflight[requested] = job
                    job.invokeOnCompletion {
                        synchronized(mdbInflight) {
                            if (mdbInflight[requested] === job) mdbInflight.remove(requested)
                        }
                    }
                    job.start()
                }
            }
        }

    private fun cachedFindMatches(
        imdbId: String?,
        tmdbId: String?,
        mediaType: String
    ): Boolean {
        if (imdbId.isNullOrBlank() || tmdbId.isNullOrBlank()) return false
        val cached = stremioCacheMemoryRead(tmdbFindCache[imdbId], METADATA_CACHE_TTL) ?: return false
        return cached.first?.first == tmdbId && cached.first?.second == mediaType
    }

    private fun hasUsableSpeculativeMdbTarget(
        hint: DetailUrlHint?,
        resolvedTmdbId: String?,
        resolvedImdbId: String?
    ): Boolean = when {
        directMdbTarget(hint?.id) != null -> true
        isLibraryAnimeId(hint?.id) -> !resolvedTmdbId.isNullOrBlank() || !resolvedImdbId.isNullOrBlank()
        else -> false
    }

    private fun parseDetailUrlHint(url: String): DetailUrlHint? {
        if (url.startsWith("{")) return null
        return runCatching {
            val rawPath = URI(url).rawPath ?: return@runCatching null
            val segments = rawPath.split('/').filter { it.isNotBlank() }
            val metaIndex = segments.indexOfLast { it.equals("meta", ignoreCase = true) }
            if (metaIndex < 0 || metaIndex + 2 >= segments.size) return@runCatching null

            val rawType = segments[metaIndex + 1]
            val rawId = segments[metaIndex + 2].removeSuffix(".json")
            val decodedType = URLDecoder.decode(rawType, "UTF-8")
            val decodedId = URLDecoder.decode(rawId, "UTF-8")
            if (decodedType.isBlank() || decodedId.isBlank()) null else DetailUrlHint(decodedType, decodedId)
        }.getOrNull()
    }

    private suspend fun resolveAnimeIdForLoad(id: String): AnimeSeasonMapping? {
        val kind = id.substringBefore(':')
        val value = id.substringAfter(':', "").substringBefore(':').toIntOrNull()
            ?.takeIf { it > 0 } ?: return null
        if (kind != "kitsu" && kind != "mal" && kind != "anilist") return null
        return fetchAnimeSeasonMapping(LoadData(
            id = "$kind:$value",
            originalAnimeId = "$kind:$value",
            isAnime = true
        ))
    }

    private suspend fun fetchTmdbFindForLoad(
        imdbId: String
    ): Pair<String, String>? {
        val cachedFind = stremioCacheMemoryRead(tmdbFindCache[imdbId], METADATA_CACHE_TTL) ?: MetadataDiskCache.read("find", imdbId)?.let { (encoded, stamp) ->
            val parts = encoded.split('|')
            if (parts.size == 2 && parts[0].toIntOrNull() != null && parts[1] in listOf("tv", "movie")) {
                Pair(Pair(parts[0], parts[1]), stamp).also {
                    tmdbFindCache[imdbId] = it
                }
            } else null
        }
        val result: Pair<String, String>? = if (cachedFind != null) {
            cachedFind.first
        } else {
            val fetchedFind = runSuspendCatching {
                val text = run {
                    fetchSharedMetadataText(buildTmdbUrl("/find/$imdbId", "external_source" to "imdb_id"))
                }
                val findRes = run {
                    if (text.isNotBlank()) parseJson<TmdbFindResponse>(text) else null
                }
                val movieMatch = findRes?.movie_results?.firstOrNull()
                if (movieMatch?.id != null) {
                    Pair(movieMatch.id.toString(), "movie")
                } else {
                    findRes?.tv_results?.firstOrNull()?.id?.let { Pair(it.toString(), "tv") }
                }
            }.getOrNull()
            if (fetchedFind != null) {
                tmdbFindCache[imdbId] = Pair(fetchedFind, System.currentTimeMillis())
                MetadataDiskCache.write("find", imdbId, "${fetchedFind.first}|${fetchedFind.second}")
            } else {
            }
            fetchedFind
        }
        return result
    }

    private suspend fun fetchTmdbDetailForLoad(
        tmdbId: String,
        mediaType: String
    ): TmdbDetailResponse? {
        val detailAppend = if (mediaType == "movie") {
            "release_dates,credits,images,videos,external_ids,recommendations,translations"
        } else {
            "content_ratings,credits,images,videos,external_ids,recommendations,translations"
        }
        val videoLanguages = if (mediaType == "tv") arrayOf(
            "include_video_language" to tmdbTrailerLanguages().joinToString(",")
        ) else emptyArray()
        val detailUrl = buildTmdbUrl("/$mediaType/$tmdbId",
            "append_to_response" to detailAppend, "include_image_language" to tmdbLogoLanguages(),
            *videoLanguages)
        val cached = stremioCacheMemoryRead(tmdbDetailsCache[detailUrl], METADATA_CACHE_TTL) ?: MetadataDiskCache.read("detail", detailUrl)?.let { (json, stamp) ->
            runCatching { parseJson<TmdbDetailResponse>(json) }.getOrNull()?.let { value ->
                Pair(value, stamp).also {
                    tmdbDetailsCache[detailUrl] = it
                }
            }
        }
        val result: TmdbDetailResponse? = if (cached != null) {
            cached.first
        } else {
            runSuspendCatching {
                val text = run { fetchSharedMetadataText(detailUrl) }
                val fetched = run {
                    if (text.isNotBlank()) parseJson<TmdbDetailResponse>(text) else null
                }
                if (fetched != null) {
                    tmdbDetailsCache[detailUrl] = Pair(fetched, System.currentTimeMillis())
                    MetadataDiskCache.write("detail", detailUrl, text)
                } else {
                }
                fetched
            }.getOrNull()
        }
        return result
    }

    private fun rememberDetailLoadHint(url: String, type: String, id: String, tmdbId: String?) {
        if (url.isBlank() || id.isBlank()) return
        val normalizedTmdbId = tmdbId?.removePrefix("tmdb:")?.takeIf { it.isNotBlank() }
        detailLoadHints[url] = DetailUrlHint(type = type, id = id, tmdbId = normalizedTmdbId)
    }

    private fun buildTmdbUrl(endpoint: String, vararg params: Pair<String, String>): String {
        val builder = "$tmdbAPI$endpoint".toHttpUrlOrNull()?.newBuilder() ?: return ""
        val context = CloudStreamApp.context
        val prefs = context?.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
        val tmdbKey = prefs?.getString("tmdb_api_key", "") ?: ""
        builder.addQueryParameter("api_key", tmdbKey)
        val langCode = prefs?.getString("tmdb_language_code", "en-US") ?: "en-US"
        builder.addQueryParameter("language", langCode)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun extractImdbId(url: String?): String? {
        if (url.isNullOrBlank() || !url.contains("tt")) return null
        return runSuspendCatching { IMDB_ID_REGEX.find(url)?.value }.getOrNull()
    }

    private fun buildStremioId(type: String?, id: String?, season: Int?, episode: Int?): String? {
        if (type == "series" && season != null && episode != null) {
            return when {
                isLibraryAnimeId(id) -> {
                    val parts = id!!.split(":")
                    val baseId = if (parts.size >= 2) "${parts[0]}:${parts[1]}" else id
                    "$baseId:$episode"
                }
                id?.endsWith(":$season:$episode") == true -> id
                else -> "$id:$season:$episode"
            }
        }
        return id
    }

    private fun baseUrl(): String {
        return mainUrl.fixSourceUrl().cleanBaseUrl()
    }

    private fun querySuffix(): String {
        return mainUrl.substringAfter("?", "")
            .takeIf { it.isNotEmpty() }
            ?.let { "?$it" }
            ?: ""
    }

    private fun buildUrl(path: String): String {
        return "${baseUrl()}$path${querySuffix()}"
    }

    private fun getActorUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w300$link" else link
    }

    private suspend fun getManifest(): Manifest? = manifestMutex.withLock {
        if (!isAddonEnabled()) return@withLock null
        fetchManifestLocked()
    }

    private suspend fun fetchManifestLocked(): Manifest? {
        val currentUrl = stremioManifestUrl(mainUrl)
        val now = System.currentTimeMillis()
        val isExpired = (now - lastCacheTime) > manifestCacheTtlMs

        if (cachedManifest != null && lastManifestUrl == currentUrl && !isExpired && !cachedManifest?.catalogs.isNullOrEmpty()) {
            return cachedManifest
        }
        val manifestResponse = runSuspendCatching {
            customSession.get(currentUrl, timeout = 15L)
        }.getOrNull()
        val resText = manifestResponse?.text

        if (!resText.isNullOrBlank()) {
            try {
                val parsed = parseJson<Manifest>(resText)
                if (parsed?.catalogs?.isNotEmpty() == true) {
                    val cachePolicy = stremioManifestCachePolicy(
                        manifestResponse?.headers?.toList().orEmpty())
                    manifestCacheTtlMs = cachePolicy.ttlMs
                    val context = CloudStreamApp.context
                    val stored = context?.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
                    val manifestKey = currentUrl.toUniqueKey()
                    val newVersion = runCatching {
                        parseJson<JsonNode>(resText).get("version")?.asText()?.trim()
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                    val oldVersion = stored?.getString("manifest_version_" + manifestKey, null)
                    if (oldVersion != null && newVersion != null && oldVersion != newVersion) {
                        invalidateAddonVersionCache(currentUrl)
                    }
                    cachedManifest = parsed
                    preparedMainPageManifest = PreparedMainPageManifest(currentUrl, resText.toUniqueKey(), parsed)
                    lastManifestUrl = currentUrl
                    lastCacheTime = now
                    stored?.edit()?.apply {
                        if (cachePolicy.noStore) {
                            putBoolean("manifest_no_store_" + manifestKey, true)
                            remove(manifestKey)
                            remove("manifest_saved_at_" + manifestKey)
                            remove("manifest_ttl_ms_" + manifestKey)
                        } else {
                            putBoolean("manifest_no_store_" + manifestKey, false)
                            putString(manifestKey, resText)
                            putLong("manifest_saved_at_" + manifestKey, now)
                            putLong("manifest_ttl_ms_" + manifestKey, manifestCacheTtlMs)
                        }
                        if (newVersion != null) putString("manifest_version_" + manifestKey, newVersion)
                    }?.apply()
                    return parsed
                }
            } catch (e: Exception) {
            }
        }

        if (manifestCacheTtlMs > 0L && lastManifestUrl == currentUrl &&
            now - lastCacheTime <= 72L * 60 * 60 * 1000) {
            return cachedManifest
        }
        val saved = CloudStreamApp.context
            ?.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
            ?: return null
        val key = stremioCatalogKey(mainUrl)
        val raw = stremioReadManifest(saved, mainUrl) ?: return null
        val savedAt = saved.getLong("manifest_saved_at_" + key, 0L)

        val effectiveAt = if (savedAt > 0L) savedAt else now.also {
            saved.edit().putLong("manifest_saved_at_" + key, it).apply()
        }
        if (effectiveAt > now || now - effectiveAt > 72L * 60 * 60 * 1000) return null
        return runCatching { parseJson<Manifest>(raw) }.getOrNull()
            ?.takeIf { it.catalogs.isNotEmpty() }
            ?.also {
                cachedManifest = it
                lastManifestUrl = currentUrl
                lastCacheTime = effectiveAt
            }
    }

    private fun parseMainPageManifestSingleFlight(
        raw: String,
        currentUrl: String,
        signature: String,
    ): Manifest? {
        preparedMainPageManifest?.let { existing ->
            if (existing.manifestUrl == currentUrl && existing.rawSignature == signature) {
                return existing.manifest
            }
        }

        return synchronized(mainPageManifestParseLock) {
            val joined = preparedMainPageManifest
            if (joined != null && joined.manifestUrl == currentUrl && joined.rawSignature == signature) {
                joined.manifest
            } else {
                val parsed: Manifest? = runCatching { parseJson<Manifest>(raw) }.getOrNull()
                if (parsed != null && parsed.catalogs.isNotEmpty()) {
                    preparedMainPageManifest = PreparedMainPageManifest(currentUrl, signature, parsed)
                    preparedMainPageData = null
                    parsed
                } else {
                    null
                }
            }
        }
    }

    private fun buildMainPageData(): List<MainPageData> {
        val context = CloudStreamApp.context ?: return emptyList()
        val prefs = context.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
        val manifestPrefs = context.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)

        val currentUrl = stremioManifestUrl(mainUrl)
        val uniqueKey = stremioCatalogKey(mainUrl)
        migrateStremioCatalogSettings(prefs, mainUrl)
        val diskCache = stremioReadManifest(manifestPrefs, mainUrl)
        val rawCatalogs = if (!diskCache.isNullOrBlank()) {
            val parsed = parseMainPageManifestSingleFlight(
                diskCache,
                currentUrl,
                diskCache.toUniqueKey()
            )
            parsed?.catalogs ?: cachedManifest?.catalogs.orEmpty()
        } else {
            cachedManifest?.catalogs.orEmpty()
        }

        if (rawCatalogs.isEmpty()) return emptyList()

        val flatTabs = mutableListOf<FlatTab>()
        rawCatalogs.forEach { catalog ->
            if (!catalog.supportsHome() || catalog.requiresExtra("genre") ||
                (catalog.requiresExtra("skip") && !catalog.supportsExtra("skip"))) return@forEach
            val catalogId = catalog.id ?: ""
            val catalogType = catalog.type ?: "movie"
            val catalogName = catalog.name ?: catalogId
            val displayType = catalogType.replaceFirstChar { it.uppercase() }

            flatTabs.add(FlatTab(
                key = "${catalogId}_${catalogType}_default",
                displayName = "$catalogName - $displayType",
                urlPath = "/catalog/${catalogType.urlEncodeLocal()}/${catalogId.urlEncodeLocal()}" +
                    (if (catalog.supportsExtra("skip")) "/skip=###" else "") + ".json"
            ))
        }

        val orderKey = "catalog_order_$uniqueKey"
        val legacyKey = currentUrl.toLegacyUniqueKey()
        val orderJson = prefs.getString(orderKey, null)
            ?: prefs.getString("catalog_order_$legacyKey", null)
        val orderedKeys = mutableListOf<String>()
        if (orderJson != null) {
            try {
                val arr = org.json.JSONArray(orderJson)
                for (i in 0 until arr.length()) orderedKeys.add(arr.getString(i))
            } catch (e: Exception) {}
        }

        val rank = orderedKeys.withIndex().associate { it.value to it.index }
        val sortedTabs = flatTabs.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
        val enabledTabs = buildList<Pair<String, String>> {
            sortedTabs.forEach { tab ->
                val tabKey = "tab_enabled_${uniqueKey}_${tab.key}"
                val isEnabled = if (prefs.contains(tabKey)) prefs.getBoolean(tabKey, true)
                    else prefs.getBoolean("tab_enabled_${legacyKey}_${tab.key}", true)

                if (isEnabled && prefs.getBoolean("tab_home_${uniqueKey}_${tab.key}", true))
                    add(tab.urlPath to tab.displayName)
            }
        }
        return mainPageOf(*enabledTabs.toTypedArray())
    }

    override val mainPage: List<MainPageData>
        get() {
            if (!showHome || !isAddonEnabled()) return emptyList()
            val context = CloudStreamApp.context ?: return emptyList()
            val manifestPrefs = context.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
            val currentUrl = stremioManifestUrl(mainUrl)
            val raw = stremioReadManifest(manifestPrefs, mainUrl)
            if (!raw.isNullOrBlank()) {
                val prefs = context.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
                val addonKey = stremioCatalogKey(mainUrl)
                val homeSettings = prefs.all.filterKeys {
                    it == "catalog_order_$addonKey" || it.startsWith("tab_enabled_${addonKey}_") ||
                        it.startsWith("tab_home_${addonKey}_")
                }.toSortedMap()
                val signature = (raw + homeSettings.toString()).toUniqueKey()
                preparedMainPageData?.let { prepared ->
                    if (prepared.manifestUrl == currentUrl && prepared.rawSignature == signature) {
                        return prepared.data
                    }
                }
                val built = buildMainPageData()
                if (built.isNotEmpty()) {
                    preparedMainPageData = PreparedMainPageData(currentUrl, signature, built)
                }
                return built
            }
            return buildMainPageData()
        }

    private fun getCatalogStateKey(request: MainPageRequest): String {
        return "$mainUrl|${request.data}"
    }

    internal fun homeCursorFor(target: CatalogShortcutTarget, visible: List<SearchResponse>): Int? {
        if (visible.isEmpty()) {
            return null
        }
        val path = "/catalog/${target.type.urlEncodeLocal()}/${target.catalogId.urlEncodeLocal()}"
        val tab = mainPage.firstOrNull {
            it.data.startsWith("$path/") || it.data.startsWith("$path.json")
        }
        if (tab == null) {
            return null
        }
        val stateKey = "$mainUrl|${tab.data}"
        val offsets = catalogHomeCursors[stateKey]
        if (offsets == null) {
            return null
        }
        var last = -1
        for (item in visible) {
            val next = offsets[item.url]
            if (next == null) {
                return null
            }
            if (next < last) {
                return null
            }
            last = next
        }
        if (last <= 0) {
            return null
        }
        return last
    }

    internal suspend fun adoptBrowserPage(
        target: CatalogShortcutTarget, offset: Int,
        page: List<SearchResponse>, rawCount: Int
    ): List<SearchResponse>? {
        if (rawCount < 0) {
            return null
        }
        val path = "/catalog/" + target.type.urlEncodeLocal() + "/" +
            target.catalogId.urlEncodeLocal()
        val tab = mainPage.firstOrNull {
            it.data.startsWith("$path/") || it.data.startsWith("$path.json")
        }
        if (tab == null) {
            return null
        }
        val stateKey = mainUrl + "|" + tab.data
        return pageMutex.withLock {
            val currentSkip = catalogSkipState[stateKey]
            val active = activePageRequests.keys.count { it.startsWith("$stateKey|page=") }
            if (currentSkip != offset || active != 0) {
                return@withLock null
            }

            val sent = catalogSentIds.getOrPut(stateKey) { lruSet(500) }
            val accepted = page.filter { sent.add(it.extractPureId()) }
            val nextSkip = offset + rawCount
            catalogSkipState[stateKey] = nextSkip
            val cursors = catalogHomeCursors.getOrPut(stateKey) { ConcurrentHashMap() }
            accepted.forEach { cursors[it.url] = nextSkip }
            accepted
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!isAddonEnabled()) return newHomePageResponse(
            listOf(HomePageList(request.name, emptyList<SearchResponse>())), hasNext = false)
        return try {
            executeMainPage(page, request)
        } catch (e: CancellationException) {

            throw e
        } catch (t: Throwable) {
            newHomePageResponse(listOf(HomePageList(request.name, emptyList<SearchResponse>())), hasNext = false)
        }
    }

    private suspend fun executeMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val stateKey = getCatalogStateKey(request)
        val key = "$stateKey|page=$page"
        var owner = false

        val flight = pageMutex.withLock {
            val existing = activePageRequests[key]
            if (existing != null) {
                existing.waiters++
                existing
            } else {
                owner = true
                val generation = if (page <= 1) {
                    catalogSentIds.remove(stateKey)
                    catalogSkipState.remove(stateKey)
                    catalogHomeCursors.remove(stateKey)
                    ((catalogGenerations[stateKey] ?: 0L) + 1L).also { catalogGenerations[stateKey] = it }
                } else catalogGenerations[stateKey] ?: 0L
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    fetchTabCatalogData(request, page, generation)
                }
                PageFlight(scope, deferred, 1).also { activePageRequests[key] = it }
            }
        }

        try {
            val response = flight.deferred.await()
            return if (!owner && page > 1) newHomePageResponse(emptyList(), hasNext = true) else response
        } finally {
            withContext(NonCancellable) {
                pageMutex.withLock {
                    flight.waiters--
                    if (flight.waiters == 0) {
                        if (activePageRequests[key] === flight) activePageRequests.remove(key)
                        flight.scope.cancel()
                    }
                }
            }
        }
    }

    private suspend fun fetchTabCatalogData(
        request: MainPageRequest,
        page: Int,
        generation: Long
    ): HomePageResponse {
        val stateKey = getCatalogStateKey(request)

        if ((catalogGenerations[stateKey] ?: 0L) != generation) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        if (page > 1 && !catalogSkipState.containsKey(stateKey)) {
            fetchTabCatalogData(request, 1, generation)
        }

        if ((catalogGenerations[stateKey] ?: 0L) != generation) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        val currentSkip = catalogSkipState[stateKey] ?: 0
        val targetPath = request.data.replace("###", currentSkip.toString())
        val resultPair = fetchCatalogItems(targetPath)

        if ((catalogGenerations[stateKey] ?: 0L) != generation) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        return pageMutex.withLock {
            if ((catalogGenerations[stateKey] ?: 0L) != generation) {
                return@withLock newHomePageResponse(emptyList(), hasNext = false)
            }

            catalogSkipState[stateKey] = currentSkip + resultPair.second

            var seenForThisCatalog = catalogSentIds[stateKey]
            if (seenForThisCatalog == null) {
                synchronized(catalogSentIds) {
                    seenForThisCatalog = catalogSentIds.getOrPut(stateKey) { lruSet(500) }
                }
            }

            val filteredItems = resultPair.first.filter { item ->
                seenForThisCatalog!!.add(item.extractPureId())
            }
            val cursors = catalogHomeCursors.getOrPut(stateKey) { ConcurrentHashMap() }

            val nextSkip = currentSkip + resultPair.second
            filteredItems.forEach { cursors[it.url] = nextSkip }

            newHomePageResponse(
                listOf(HomePageList(request.name, filteredItems)),
                hasNext = request.data.contains("###") && resultPair.second > 0
            )
        }
    }

    private suspend fun fetchCatalogItems(path: String, fallbackType: String = "movie"): Pair<List<SearchResponse>, Int> {
        val json = homeForegroundSemaphore.withPermit { fetchHomeJson(buildUrl(path)) }
        val metas = parseJson<HomeCatalogResponse>(json).metas
            ?: throw IllegalStateException("Catalog response is missing metas")
        val items = metas.mapNotNull { meta ->
            val type = meta.type ?: fallbackType
            val id = meta.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = meta.name?.takeIf { it.isNotBlank() }
                ?: "[${type.replaceFirstChar { it.uppercase() }}] $id"
            val metaUrl = getUnifiedMetaUrl(type, id)
            rememberDetailLoadHint(metaUrl, type, id, meta.moviedb_id?.toString() ?: meta.tmdbIdExt)
            newMovieSearchResponse(title, metaUrl, TvType.Others) { posterUrl = meta.poster }
        }
        return items to metas.size
    }

    internal suspend fun browseCatalogs(): List<Catalog> {
        if (!isAddonEnabled()) return emptyList()
        val prefs = CloudStreamApp.context?.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
        val raw = prefs?.let { stremioReadManifest(it, mainUrl) }
        val manifest = if (!raw.isNullOrBlank()) {
            parseMainPageManifestSingleFlight(raw, stremioManifestUrl(mainUrl), raw.toUniqueKey())
        } else getManifest()
        return manifest?.catalogs?.filter { !it.id.isNullOrBlank() && !it.type.isNullOrBlank() }
            ?: throw IllegalStateException("No cached or available manifest")
    }

    internal suspend fun browsePage(catalog: Catalog, genre: String?, offset: Int): Pair<List<SearchResponse>, Int> =
        if (!isAddonEnabled()) emptyList<SearchResponse>() to 0
        else fetchCatalogItems(catalog.browsePath(genre, offset), requireNotNull(catalog.type))

    override suspend fun search(query: String): List<SearchResponse>? {

        if (disableSearch) return null
        if (!isAddonEnabled()) return emptyList()
        mainUrl = mainUrl.fixSourceUrl()
        val manifest = getManifest()

        val context = CloudStreamApp.context
        val prefs = context?.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
        val currentUrl = stremioManifestUrl(mainUrl)
        val uniqueKey = stremioCatalogKey(mainUrl)
        val legacyKey = currentUrl.toLegacyUniqueKey()
        if (prefs != null) migrateStremioCatalogSettings(prefs, mainUrl)

        val supportedCatalogs = manifest?.catalogs?.filter { catalog ->
            if (!catalog.supportsSearch()) return@filter false
            if (searchCatalogId != null &&
                (catalog.id != searchCatalogId || catalog.type != searchCatalogType)) return@filter false
            if (prefs == null) return@filter true

            val catalogId = catalog.id ?: return@filter false
            val catalogType = catalog.type ?: "movie"
            val tabKey = "tab_enabled_${uniqueKey}_${catalogId}_${catalogType}_default"
            if (prefs.contains(tabKey)) {
                prefs.getBoolean(tabKey, true)
            } else {
                prefs.getBoolean("tab_enabled_${legacyKey}_${catalogId}_${catalogType}_default", true)
            }
        } ?: emptyList()

        val searchResults = coroutineScope {
            supportedCatalogs.map { catalog ->
                async(Dispatchers.IO) {
                    try { catalog.search(query, this@StremioC) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
                }
            }.awaitAll().flatten()
        }

        val seenIds = mutableSetOf<String>()
        return searchResults.filter { item ->
            val extractedId = item.extractPureId()
            if (seenIds.contains(extractedId)) false else { seenIds.add(extractedId); true }
        }
    }

    override suspend fun load(url: String): LoadResponse {

        val requested = url.toHttpUrlOrNull()
        val forceRefresh = requested?.queryParameter("__stremioc_refresh") == "1"
        val effectiveUrl = if (forceRefresh) requested!!.newBuilder()
            .removeAllQueryParameters("__stremioc_refresh").build().toString() else url
        return loadInternal(effectiveUrl, forceRefresh)
    }

    private suspend fun loadInternal(url: String, forceRefresh: Boolean = false): LoadResponse = coroutineScope {
        if (!isAddonEnabled()) throw IllegalStateException("This addon is disabled.")
        if (mainUrl.isBlank()) {
            val libraryService = when {
                libraryItemId(url, "myanimelist.net", "anime") != null -> SyncIdName.MyAnimeList
                libraryItemId(url, "kitsu.app", "anime") != null -> SyncIdName.Kitsu
                libraryItemId(url, "anilist.co", "anime") != null -> SyncIdName.Anilist
                libraryItemId(url, "simkl.com", "tv", "movies", "anime") != null -> SyncIdName.Simkl
                else -> null
            }
            if (libraryService != null) {
                val base = libraryMetadataBase()
                    ?: throw IllegalStateException("Configure a metadata/history addon before opening a library item.")
                val metaUrl = run {
                    getLoadUrl(libraryService, url)
                } ?: throw IllegalStateException("Unable to resolve the library item with the configured addon.")
                return@coroutineScope run {
                    StremioC(base, name).load(metaUrl).also { response ->
                        StremioCMdbUiBridge.keyFor(metaUrl)?.let { key ->
                            StremioCMdbUiBridge.bind(key, listOf(url))
                        }

                        for (source in listOf(metaUrl, response.url)) {
                            fallbackLogoTitles[source]?.let { fallbackLogoTitles[url] = it }
                            expectedLogoByUrl[source]?.let { expectedLogoByUrl[url] = it }
                            animeAudioByUrl[source]?.let { animeAudioByUrl[url] = it }
                            episodeBasedByUrl[source]?.let { episodeBasedByUrl[url] = it }
                            seasonEpisodeCountsByUrl[source]?.let { seasonEpisodeCountsByUrl[url] = it }
                            trailerChoicesByUrl[source]?.let { trailerChoicesByUrl[url] = it }
                            genreCatalogsByUrl[source]?.let { genreCatalogsByUrl[url] = it }
                        }
                    }
                }
            }
        }

        if (mainUrl.isBlank() && (url.startsWith("https://") || url.startsWith("http://"))) {
            val origin = url.substringBefore("/meta/", "").takeIf { it.isNotBlank() }
            if (origin != null) {
                val delegatedUrl = if (forceRefresh) url.toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("__stremioc_refresh", "1")?.build()?.toString() ?: url else url
                return@coroutineScope StremioC(origin, name).load(delegatedUrl)
            }
        }
        suspend fun fetchSecondaryMeta(metaUrl: String, expectedId: String): CatalogEntry? =
            run {
                runSuspendCatching {
                    val text = run { fetchAddonMetaText(metaUrl, forceRefresh) }
                    val response = run {
                        if (text.isNotBlank()) parseJson<CatalogResponse>(text) else null
                    }
                    response?.meta ?: response?.metas?.firstOrNull { it.id == expectedId }
                        ?: response?.metas?.firstOrNull()
                }.getOrNull()
            }

        val parsedUrlHint = parseDetailUrlHint(url)
        val requestedAnimeId = parsedUrlHint?.id?.takeIf { isLibraryAnimeId(it) }
        val rememberedHint = detailLoadHints[url]?.takeIf { cached ->
            parsedUrlHint != null &&
                cached.id == parsedUrlHint.id &&
                canonicalDetailType(cached.type) == canonicalDetailType(parsedUrlHint.type)
        }
        val urlHint = rememberedHint ?: parsedUrlHint

        val hintedMetaUrl = urlHint?.let { getUnifiedMetaUrl(it.type, it.id) }
            ?.takeUnless { it == url }
        val hintedMetaDeferred = hintedMetaUrl?.let { metaUrl ->
            async(Dispatchers.IO) { run { fetchSecondaryMeta(metaUrl, urlHint!!.id) } }
        }
        val hintedImdbId = (urlHint?.id ?: if (url.startsWith("{"))
            runCatching { parseJson<CatalogEntry>(url)?.id }.getOrNull() else null)
            ?.takeIf { IMDB_ID_REGEX.matches(it) }
        val speculativeImdbAnimeDeferred = hintedImdbId?.let { id ->
            async(Dispatchers.IO) {
                run { fetchAnimeSeasonMapping(LoadData(id = id, imdbId = id, isAnime = true)) }
            }
        }
        val hintMediaType = detailMediaType(urlHint?.type)
        val mdbEnabled = isIntegrationEnabled("mdblist_enabled", default = false)
        val directMdbHint = directMdbTarget(urlHint?.id, hintMediaType)

        val hintedAnimeId = urlHint?.id?.takeIf { isLibraryAnimeId(it) }
        val speculativeAnimeDeferred: Deferred<AnimeSeasonMapping?>? = hintedAnimeId?.let { id ->
            async(Dispatchers.IO) { run { resolveAnimeIdForLoad(id) } }
        }

        val speculativeMdbDeferred: Deferred<SpeculativeMdbResult?>? = when {
            !mdbEnabled || urlHint == null -> null
            directMdbHint != null -> launchMdbAttempt(directMdbHint)
            speculativeAnimeDeferred != null -> mdbFetchScope.async {
                val resolved = run { speculativeAnimeDeferred.await() } ?: return@async null
                val target = resolved.tmdbId?.toString()?.let { MdbTarget(it, true, hintMediaType) }
                    ?: resolved.imdbId?.let { MdbTarget(it, false, hintMediaType) }
                    ?: return@async null
                launchMdbAttempt(target)?.await()
            }
            else -> null
        }

        val speculativeTmdbDeferred: Deferred<SpeculativeTmdbChain?>? = when {
            !urlHint?.tmdbId.isNullOrBlank() -> {
                val hintedTmdbId = urlHint?.tmdbId ?: ""
                async(Dispatchers.IO) {
                    val detail = run { fetchTmdbDetailForLoad(hintedTmdbId, hintMediaType) }
                    SpeculativeTmdbChain(urlHint?.id?.takeIf { it.startsWith("tt") }, hintedTmdbId, hintMediaType, false, detail)
                }
            }

            urlHint?.id?.startsWith("tmdb:") == true -> {
                val tmdbId = urlHint.id.removePrefix("tmdb:")
                async(Dispatchers.IO) {
                    val detail = run { fetchTmdbDetailForLoad(tmdbId, hintMediaType) }
                    SpeculativeTmdbChain(null, tmdbId, hintMediaType, false, detail)
                }
            }
            speculativeAnimeDeferred != null -> async(Dispatchers.IO) {
                val resolved = run { speculativeAnimeDeferred.await() } ?: return@async null
                val tmdbId = resolved.tmdbId?.toString()
                if (!tmdbId.isNullOrBlank()) {
                    val detail = fetchTmdbDetailForLoad(tmdbId, hintMediaType)
                    SpeculativeTmdbChain(resolved.imdbId, tmdbId, hintMediaType, false, detail)
                } else if (!resolved.imdbId.isNullOrBlank()) {
                    val find = fetchTmdbFindForLoad(resolved.imdbId) ?: return@async null
                    val detail = fetchTmdbDetailForLoad(find.first, find.second)
                    SpeculativeTmdbChain(resolved.imdbId, find.first, find.second, true, detail)
                } else {
                    null
                }
            }
            else -> null
        }

        var initialMetaFromResponse: CatalogEntry? = null
        val res: CatalogEntry = if (url.startsWith("{")) {
            run { parseJson<CatalogEntry>(url) }
        } else {
            val responseText = run { fetchAddonMetaText(url, forceRefresh) }
            try {
                run {
                    val rootNode = parseJson<JsonNode>(responseText)
                    val metaNode = if (rootNode.has("meta")) rootNode.get("meta") else rootNode
                    val parsedEntry = parseJson<CatalogEntry>(metaNode.toString())
                        ?: throw IllegalStateException("Failed to bind JSON to CatalogEntry")
                    if (rootNode.has("meta") && metaNode.isObject) {
                        initialMetaFromResponse = parsedEntry
                    }
                    parsedEntry
                }
            } catch (e: Exception) {
                throw IllegalStateException("Metadata not found or parsing failed: $url", e)
            }
        }
        val hintMatchesAuthoritative = urlHint != null &&
            urlHint.id == res.id &&
            canonicalDetailType(urlHint.type) == canonicalDetailType(res.type)

        if (!hintMatchesAuthoritative && urlHint != null) {

            if (requestedAnimeId == null) speculativeTmdbDeferred?.cancel()

            if (hintedAnimeId != requestedAnimeId) speculativeAnimeDeferred?.cancel()

            if (hintedImdbId != res.id) speculativeImdbAnimeDeferred?.cancel()
        }

        val normalizedId = try { normalizeId(res.id ?: "") } catch (e: Exception) { res.id ?: "" }
        val encodedId = normalizedId.urlEncode()

        val authoritativeMetaUrl = getUnifiedMetaUrl(res.type ?: "movie", res.id ?: "")
        val reuseHintedMeta = hintMatchesAuthoritative && hintedMetaUrl == authoritativeMetaUrl
        if (!reuseHintedMeta) hintedMetaDeferred?.cancel()
        val addonDeferred: Deferred<CatalogEntry?>? = when {

            authoritativeMetaUrl == url && (initialMetaFromResponse != null || !res.id.isNullOrBlank()) -> null
            reuseHintedMeta && hintedMetaDeferred != null -> hintedMetaDeferred
            else -> async(Dispatchers.IO) {
                fetchSecondaryMeta(authoritativeMetaUrl, res.id ?: "")
            }
        }

        val originalMappingId = requestedAnimeId ?: res.id?.takeIf { isLibraryAnimeId(it) }
        var fetchedAniListId: Int? = null
        var fetchedMalId: Int? = null
        var fetchedKitsuId: String? = null
        var resolvedImdbId: String? = null
        var resolvedTmdbId: String? = null

        if (originalMappingId != null) {
            val candidate = run {
                if (hintedAnimeId == originalMappingId && speculativeAnimeDeferred != null) {
                    speculativeAnimeDeferred.await()
                } else {
                    resolveAnimeIdForLoad(originalMappingId)
                }
            }
            val resolved = candidate?.takeIf { mapping ->
                when {
                    res.id == originalMappingId -> true
                    res.id?.let { IMDB_ID_REGEX.matches(it) } == true -> mapping.imdbId == res.id
                    res.id?.startsWith("tmdb:") == true -> mapping.tmdbId?.toString() == res.id?.removePrefix("tmdb:")
                    else -> false
                }
            }
            if (resolved != null) {
                resolvedImdbId = resolved.imdbId
                resolvedTmdbId = resolved.tmdbId?.toString()
                fetchedAniListId = resolved.aniListId
                fetchedMalId = resolved.malId
                fetchedKitsuId = resolved.kitsuId
            }
        }

        val tmdbIdFromRes = res.moviedb_id?.toString() ?: res.tmdbIdExt
        val directTmdbFromRes = when {
            !resolvedTmdbId.isNullOrBlank() -> resolvedTmdbId
            res.id?.startsWith("tmdb:") == true -> res.id.removePrefix("tmdb:")
            !tmdbIdFromRes.isNullOrBlank() -> tmdbIdFromRes
            else -> null
        }
        val authoritativeMediaType = detailMediaType(res.type)

        var acceptedTmdbSpec: SpeculativeTmdbChain? = null

        if ((hintMatchesAuthoritative || (originalMappingId != null && !resolvedTmdbId.isNullOrBlank())) && speculativeTmdbDeferred != null) {
            val hintedImdb = resolvedImdbId ?: urlHint?.id?.takeIf { it.startsWith("tt") }
            val authoritativeHint = !urlHint?.tmdbId.isNullOrBlank() &&
                directTmdbFromRes != null && urlHint?.tmdbId == directTmdbFromRes &&
                hintMediaType == authoritativeMediaType
            val reuseFind = urlHint?.tmdbId.isNullOrBlank() &&
                cachedFindMatches(hintedImdb, directTmdbFromRes, authoritativeMediaType)

            if (directTmdbFromRes == null || authoritativeHint || reuseFind || speculativeTmdbDeferred.isCompleted) {
                run { speculativeTmdbDeferred.await() }?.let { spec ->
                    val valid = directTmdbFromRes?.let { spec.matchesDirect(it, authoritativeMediaType) }
                        ?: spec.matchesFind(hintedImdb)
                    if (valid) acceptedTmdbSpec = spec
                }
            } else {
                speculativeTmdbDeferred.cancel()
            }
        }

        val validatedFindResult = acceptedTmdbSpec?.asFindResult()
        val validatedFindImdbId = acceptedTmdbSpec?.takeIf { it.viaFind }?.sourceImdbId
        val targetTmdbId = directTmdbFromRes ?: validatedFindResult?.first
        val targetTmdbMediaType = validatedFindResult?.second ?: authoritativeMediaType
        val speculativeTmdbDetail = acceptedTmdbSpec?.takeIf {
            targetTmdbId != null && it.matchesDirect(targetTmdbId, targetTmdbMediaType)
        }?.detail

        val postMetaMdbTarget = resolvedMdbTarget(res, resolvedTmdbId, resolvedImdbId)
        val speculativeMdbHasUsableTarget = speculativeMdbDeferred != null && hintMatchesAuthoritative &&
            hasUsableSpeculativeMdbTarget(urlHint, resolvedTmdbId, resolvedImdbId)
        val postMetaMdbDeferred = if (mdbEnabled && !speculativeMdbHasUsableTarget) {
            launchMdbAttempt(postMetaMdbTarget)
        } else null

        val normalTmdbDeferred: Deferred<TmdbDetailResponse?>? = if (
            targetTmdbId != null && speculativeTmdbDetail == null
        ) {
            async(Dispatchers.IO) {
                run {
                    fetchTmdbDetailForLoad(targetTmdbId, targetTmdbMediaType)
                }
            }
        } else {
            null
        }

        val preFetchedTmdbDetail = speculativeTmdbDetail ?: run { normalTmdbDeferred?.await() }

        val collectionDeferred: Deferred<List<TmdbMedia>>? = if (targetTmdbMediaType == "movie") {
            preFetchedTmdbDetail?.belongs_to_collection?.id?.let { collectionId ->
                async(Dispatchers.IO) {
                    runSuspendCatching {
                        val text = run {
                            fetchSharedMetadataText(buildTmdbUrl("/collection/$collectionId"))
                        }
                        if (text.isNotBlank()) parseJson<TmdbCollectionDetail>(text).parts
                            ?: emptyList() else emptyList()
                    }.getOrDefault(emptyList())
                }
            }
        } else null

        var finalProcessedId = res.id
        val imdbId = resolvedImdbId ?: preFetchedTmdbDetail?.external_ids?.imdb_id
        if (!imdbId.isNullOrBlank() && (imdbId.startsWith("tt") || isLibraryAnimeId(imdbId))) {
            finalProcessedId = imdbId
        }

        val resolvedIdHolder = arrayOf<String?>(null, null)

        val loadRes = run { res.toLoadResponse(
            provider = this@StremioC,
            imdbId = finalProcessedId,
            addonDeferred = addonDeferred,
            preFetchedDetail = preFetchedTmdbDetail,
            preFetchedDetailTmdbId = targetTmdbId,
            preFetchedDetailMediaType = targetTmdbMediaType,
            preFetchedCollection = collectionDeferred,
            preFetchedFind = validatedFindResult,
            preFetchedFindImdbId = validatedFindImdbId,
            fetchedAniListId = fetchedAniListId,
            fetchedMalId = fetchedMalId,
            fetchedKitsuId = fetchedKitsuId,
            resolvedTmdbId = resolvedTmdbId,
            resolvedIdHolder = resolvedIdHolder,
            requestedAnimeId = requestedAnimeId,
            prefetchedAnimeMapping = speculativeImdbAnimeDeferred?.takeIf { hintedImdbId == res.id },
            prefetchedAnimeImdbId = hintedImdbId
        ) }

        if (url != loadRes.url) {
            fallbackLogoTitles[loadRes.url]?.let { fallbackLogoTitles[url] = it }
            expectedLogoByUrl[loadRes.url]?.let { expectedLogoByUrl[url] = it }
            animeAudioByUrl[loadRes.url]?.let { animeAudioByUrl[url] = it }
                            seasonEpisodeCountsByUrl[loadRes.url]?.let { seasonEpisodeCountsByUrl[url] = it }
            trailerChoicesByUrl[loadRes.url]?.let { trailerChoicesByUrl[url] = it }
            genreCatalogsByUrl[loadRes.url]?.let { genreCatalogsByUrl[url] = it }
            ActorInformation.alias(this@StremioC, url, loadRes.url)
        }

        val libraryOrigins = synchronized(libraryOriginalUrlsByMeta) {
            libraryOriginalUrlsByMeta[url]?.toList().orEmpty()
        }
        for (originalUrl in libraryOrigins) {
            fallbackLogoTitles[url]?.let { fallbackLogoTitles[originalUrl] = it }
            expectedLogoByUrl[url]?.let { expectedLogoByUrl[originalUrl] = it }
            animeAudioByUrl[url]?.let { animeAudioByUrl[originalUrl] = it }
                            seasonEpisodeCountsByUrl[url]?.let { seasonEpisodeCountsByUrl[originalUrl] = it }
            trailerChoicesByUrl[url]?.let { trailerChoicesByUrl[originalUrl] = it }
            genreCatalogsByUrl[url]?.let { genreCatalogsByUrl[originalUrl] = it }
        }
        collectionDeferred?.cancel() 

        var actualMdbTarget = resolvedMdbTarget(res, resolvedTmdbId, resolvedImdbId)
        if (actualMdbTarget == null) {
            val resolvedImdb = resolvedIdHolder[0]?.takeIf { it.startsWith("tt") }
            val resolvedTmdb = resolvedIdHolder[1]?.takeIf { it.isNotBlank() }
            actualMdbTarget = resolvedImdb?.let { MdbTarget(it, false, authoritativeMediaType) }
                ?: resolvedTmdb?.let { MdbTarget(it, true, authoritativeMediaType) }
        }

        val finalMdbTarget = actualMdbTarget
        if (finalMdbTarget != null && mdbEnabled) {

            val mdbAuditKey = "${finalMdbTarget.credentialFingerprint}:${finalMdbTarget.isTmdb}:${finalMdbTarget.mediaType}:${finalMdbTarget.id}"
            StremioCMdbUiBridge.bind(mdbAuditKey, listOf(url, loadRes.url) + libraryOrigins)
            val mdbRatings = mdbRatingsForUi(mdbAuditKey)
            if (mdbRatings == null) {
                if (postMetaMdbTarget != finalMdbTarget || postMetaMdbDeferred == null) {
                    launchMdbAttempt(finalMdbTarget)
                }
            } else {
            }
            mdbRatings?.takeIf { it.isNotEmpty() }?.let { ratings ->
                val ratingStr = "\u2063" + ratings.joinToString("  ")
                val currentPlot = loadRes.plot
                loadRes.plot = if (!currentPlot.isNullOrBlank()) "${ratingStr}<br>${currentPlot}" else ratingStr
                loadRes.score = Score.from10(0)
            }
        }
        return@coroutineScope loadRes
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (!isAddonEnabled()) {
            return false
        }
        val loadData = try { parseJson<LoadData>(data) } catch (e: Exception) {
            null
        } ?: run {
            return false
        }

        val legacyPrimaryId = if (loadData.id?.startsWith("tmdb:") == true && !loadData.imdbId.isNullOrBlank()) {
            loadData.imdbId
        } else {
            loadData.id ?: ""
        }

        val primaryId = loadData.originalAnimeId ?: legacyPrimaryId
        val globalId = if (!loadData.imdbId.isNullOrBlank()) loadData.imdbId else legacyPrimaryId
        val targetType = loadData.type ?: if ((loadData.season ?: 0) > 0 || (loadData.episode ?: 0) > 0) "series" else "movie"

        val actualIsMovie = targetType == "movie" || (loadData.season == null && loadData.episode == null)

        val integrationImdbId = listOf(loadData.imdbId, globalId, loadData.id)
            .firstNotNullOfOrNull { candidate ->
                candidate?.substringBefore(':')?.takeIf { IMDB_ID_REGEX.matches(it) }
            }
        val integrationTargetType = if (targetType == "movie" || targetType == "series") {
            targetType
        } else if (integrationImdbId != null) {
            if (actualIsMovie) "movie" else "series"
        } else {
            targetType
        }

        val globalSeason = if (actualIsMovie) null else loadData.imdbSeason?.takeIf { it > 0 } ?: loadData.season?.takeIf { it > 0 } ?: 1
        val globalEpisode = if (actualIsMovie) null else loadData.imdbEpisode?.takeIf { it > 0 } ?: loadData.episode?.takeIf { it > 0 } ?: 1

        val seenLinks = Collections.synchronizedSet(mutableSetOf<Triple<String, String, Map<String, String>>>())

        val seenStremioSubtitles = Collections.synchronizedSet(mutableSetOf<String>())
        fun emitUniqueSubtitle(sub: SubtitleFile) {
            if (sub.url.isBlank()) return
            if (seenStremioSubtitles.add(sub.url)) subtitleCallback(sub)
        }
        val stremioSourceNames = Collections.synchronizedSet(mutableSetOf<String>()).apply {
            name.trim().takeIf { it.isNotEmpty() }?.let { add(it.lowercase()) }
            getIntegrationStremioAddons().forEach { addon ->
                addon.name.trim().takeIf { it.isNotEmpty() }?.let { add(it.lowercase()) }
            }
        }
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            val sourceKey = link.source
                .removeSuffix("🧲")
                .removeSuffix(" Magnet")
                .trim()
                .lowercase()
            if (sourceKey in stremioSourceNames) {
                link.quality = Qualities.Unknown.value
            }
            if (seenLinks.add(Triple(link.url, link.referer, link.headers.toMap()))) {
                callback(link)
            }
        }

        val currentMainUrl = loadData.addonUrl?.takeIf { it.isNotBlank() } ?: mainUrl
        val cleanBaseUrl = currentMainUrl.fixSourceUrl().cleanBaseUrl()
        val querySuffix = currentMainUrl.substringAfter("?", "").takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""

        val syncSettings = getSyncSettings()
        val (syncUrl, _, historySyncMap) = syncSettings
        val currentCleanUrl = currentMainUrl.cleanBaseUrl().lowercase()
        val isHistorySyncEnabled = historySyncMap[currentCleanUrl] ?: false

        val savedStreamSync = syncSettings.streamSubtitleMap[currentCleanUrl]
        val libraryHistoryStreamSync = savedStreamSync == null && mainUrl.isBlank() &&
            syncUrl.isNotBlank() && currentCleanUrl == syncUrl.cleanBaseUrl().lowercase() &&
            stremioManifestUrl(currentMainUrl) !in syncSettings.disabledCatalogUrls
        val streamSyncEnabled = savedStreamSync ?: libraryHistoryStreamSync
        val extraUrls = if (streamSyncEnabled) {
            syncSettings.streamSubtitleUrls.filter { addon ->
                val parsed = addon.url.toHttpUrlOrNull()
                parsed != null && (parsed.scheme == "http" || parsed.scheme == "https")
            }.filterNot { stremioManifestUrl(it.url) == stremioManifestUrl(currentMainUrl) }
        } else emptyList()
        val customHosts = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (cachedCustomHosts != null && now - cachedCustomHostsTime < SETTINGS_CACHE_TTL) {
                return@withContext cachedCustomHosts!!
            }
            val context = CloudStreamApp.context

            val raw = try { 
                val rebuildPrefs = context?.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
                var rawValue = rebuildPrefs?.getString("stremio_addons", "") ?: ""

                if (rawValue.isBlank()) {
                    val rebuildPrefsBackup = context?.getSharedPreferences("rebuild_key", Context.MODE_PRIVATE)
                    rawValue = rebuildPrefsBackup?.getString("stremio_addons", "") ?: ""
                }

                if (rawValue.startsWith("\"") && rawValue.endsWith("\"") && rawValue.length >= 2) {
                    rawValue.substring(1, rawValue.length - 1)
                } else {
                    rawValue
                }
            } catch (t: Throwable) {
                try {
                    val prefs = CloudStreamApp.context?.let { androidx.preference.PreferenceManager.getDefaultSharedPreferences(it) }
                    val v = prefs?.getString("stremio_addons", "") ?: ""
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v.substring(1, v.length - 1) else v
                } catch (e: Exception) {
                    ""
                }
            }

            val hosts = raw.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { URI(it.split("|").getOrElse(1) { "" }).host }.getOrNull() }
                .toSet() 

            cachedCustomHosts = hosts
            cachedCustomHostsTime = now
            hosts
        }
        val builtInHosts = setOf("opensubtitles.stremio.homes", "subsense.nepiraw.com", "opensubtitles-v3.strem.io")

        val stremioAddons = syncSettings.streamSubtitleUrls
        val integrationAddons = getIntegrationStremioAddons()
        val cineAddons = integrationAddons.filter { it.fromCineStream }
        val streamPlayAddons = integrationAddons.filterNot { it.fromCineStream }
        fun rankSubtitle(sub: SubtitleFile, group: Int, addonUrl: String? = null) {
            if (sub.url.isBlank()) return
            val code = SubtitleHelper.fromLanguageToTagIETF(sub.lang.trim())
            val plain = code != null &&
                SubtitleHelper.fromTagToEnglishLanguageName(code)?.equals(sub.lang.trim(), true) == true
            val source = sub.lang.substringAfterLast(" — ", "").trim()
            val addons = when (group) {
                0 -> stremioAddons.map { it.name to it.url }
                1 -> cineAddons.map { it.name to it.url }
                2 -> streamPlayAddons.map { it.name to it.url }
                else -> emptyList()
            }
            val index = addons.indexOfFirst { (addonName, url) ->
                (addonUrl != null && stremioManifestUrl(url) == stremioManifestUrl(addonUrl)) ||
                    (addonUrl == null && addonName.isNotBlank() && addonName.equals(source, true))
            }
            val priority = when {
                plain -> 0
                group == 0 -> 1_000 + if (index >= 0) index else addons.size
                group == 1 -> 10_000 + if (index >= 0) index else addons.size
                group == 2 -> 20_000 + if (index >= 0) index else addons.size
                else -> 30_000
            }
            synchronized(subtitleOrderByUrl) {
                val previous = subtitleOrderByUrl[sub.url]
                if (previous == null || priority < previous) subtitleOrderByUrl[sub.url] = priority
            }
        }

        val integrationSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            val subtitleUrl = sub.url
            try {
                val start = subtitleUrl.indexOf("://")
                val host = if (start != -1) {
                    val hostStart = start + 3
                    val end = subtitleUrl.indexOf('/', hostStart)
                    val hostPort = if (end == -1) subtitleUrl.substring(hostStart) else subtitleUrl.substring(hostStart, end)
                    val portIdx = hostPort.indexOf(':')
                    if (portIdx == -1) hostPort else hostPort.substring(0, portIdx)
                } else ""

                val isCustom = customHosts.contains(host)
                val isBuiltIn = builtInHosts.contains(host)
                val isContainsStrem = host.contains("strem", ignoreCase = true)

                val isStremio = isCustom || isBuiltIn || isContainsStrem

                if (isStremio && !sub.lang.contains("(Stremio)", ignoreCase = true)) {
                    val newLang = "${sub.lang} (Stremio)"
                    emitUniqueSubtitle(SubtitleFile(newLang, subtitleUrl))
                } else {
                    emitUniqueSubtitle(sub)
                }
            } catch (e: Exception) {
                emitUniqueSubtitle(sub)
            }
        }

        val stremioSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            emitUniqueSubtitle(sub)
        }

        fun stremioSubtitleFrom(addonUrl: String, group: Int = 0): (SubtitleFile) -> Unit = { sub ->
            rankSubtitle(sub, group, addonUrl)
            stremioSubtitleCallback(sub)
        }
        fun integratedSubtitleFrom(group: Int): (SubtitleFile) -> Unit = { sub ->
            rankSubtitle(sub, group)
            integrationSubtitleCallback(sub)
        }
        fun extractorSubtitleFrom(addonUrl: String, group: Int = 0): (SubtitleFile) -> Unit = { sub ->
            rankSubtitle(sub, group, addonUrl)
            emitUniqueSubtitle(sub)
        }

        val requestedSubtitleUrls = Collections.synchronizedSet(mutableSetOf<String>())

        suspend fun animeSubtitleRequestType(addonUrl: String): String? {
            val manifestUrl = stremioManifestUrl(addonUrl)
            fun cached(): Pair<String?, Long>? = synchronized(subtitleAnimeManifestCache) {
                stremioCacheMemoryRead(subtitleAnimeManifestCache[manifestUrl], 3_600_000L)
            }
            cached()?.let { return it.first }
            val mutex = subtitleAnimeManifestLocks.getOrPut(manifestUrl) { Mutex() }
            mutex.lock()
            try {
                cached()?.let { return it.first }
                val prefs = CloudStreamApp.context
                    ?.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
                val raw = prefs?.let { stremioReadManifest(it, addonUrl, requireFresh = true) }
                    ?: runSuspendCatching {
                        val response = customSession.get(manifestUrl, timeout = 5L)
                        val text = response.text
                        val policy = stremioManifestCachePolicy(response.headers.toList())
                        if (text.isNotBlank() && !policy.noStore && policy.ttlMs > 0L) {
                            prefs?.edit()
                                ?.putString(stremioCatalogKey(addonUrl), text)
                                ?.putLong("manifest_saved_at_" + stremioCatalogKey(addonUrl), System.currentTimeMillis())
                                ?.putLong("manifest_ttl_ms_" + stremioCatalogKey(addonUrl), policy.ttlMs)
                                ?.apply()
                        }
                        text
                    }.getOrNull()
                val manifest = raw?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                val types = manifest?.optJSONArray("types")
                val resources = manifest?.optJSONArray("resources")
                val supportsAnime = types != null && (0 until types.length()).any {
                    types.optString(it).equals("anime", true)
                }
                val supportsSubtitles = resources != null && (0 until resources.length()).any {
                    val entry = resources.opt(it)
                    entry == "subtitles" || (entry as? org.json.JSONObject)?.optString("name") == "subtitles"
                }
                val prefixes = manifest?.optJSONArray("idPrefixes")
                val supportsAniList = prefixes == null || (0 until prefixes.length()).any {
                    "anilist:".startsWith(prefixes.optString(it), true)
                }
                val type = if (supportsAnime && supportsSubtitles && supportsAniList) {
                    if (types != null && (0 until types.length()).any { types.optString(it) == "series" })
                        "series" else "anime"
                } else null
                synchronized(subtitleAnimeManifestCache) {
                    subtitleAnimeManifestCache[manifestUrl] = type to System.currentTimeMillis()
                }
                return type
            } finally {
                mutex.unlock()
            }
        }

        suspend fun fetchStremioSubtitles(
            addonUrl: String,
            id: String?,
            season: Int?,
            episode: Int?,
            requestType: String = targetType,
            preferredName: String? = null,
            group: Int = 0
        ) {
            if (addonUrl.isBlank() || id.isNullOrBlank()) {
                return
            }

            val subId = buildStremioId(
                requestType,
                id,
                season,
                episode
            ) ?: id
            if (subId.isBlank()) {
                return
            }

            val cleanSubtitleBase = addonUrl.fixSourceUrl().cleanBaseUrl()
            if (cleanSubtitleBase.isBlank()) {
                return
            }

            val subtitleSuffix = addonUrl.substringAfter("?", "")
                .takeIf { it.isNotEmpty() }
                ?.let { "?$it" }
                ?: ""
            val subtitleRequestUrl =
                "$cleanSubtitleBase/subtitles/$requestType/${subId.urlEncode()}.json$subtitleSuffix"

            val animeIdRequest = subId.startsWith("anilist:", ignoreCase = true)
            if (!requestedSubtitleUrls.add(subtitleRequestUrl)) return
            runSuspendCatching {
                val response = customSession.get(subtitleRequestUrl, timeout = if (animeIdRequest) 50L else 15L)
                val text = response.text
                val res = if (text.isNotBlank()) parseJson<SubtitlesResponse>(text) else null
                val addonSource = preferredName?.takeIf { it.isNotBlank() }
                    ?: getActualStremioAddonName(addonUrl)
                    ?: name.takeIf { addonUrl == currentMainUrl && !it.equals("StremioC", true) }
                res?.subtitles?.let { entries -> entries.forEachIndexed { position, sub ->
                    sub.toCloudStreamSubtitle(addonSource, preferredName, sub.translationSourceLanguage(entries))?.let { file ->
                        StremioSubtitleRefreshRegistry.remember(
                            file.url, addonUrl, sub.id, sub.lang ?: sub.lang_code,
                            subtitleRequestUrl, position, file.headers ?: emptyMap()
                        )
                        stremioSubtitleFrom(addonUrl, group)(file)
                    }
                } }
            }

        }

        coroutineScope {
        val mappingDeferred = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val mapped = if (!actualIsMovie) fetchAnimeSeasonMapping(loadData) else null
            mapped
        }

        suspend fun fetchAniListSubtitles(
            addonUrl: String, preferredName: String? = null, group: Int = 0
        ) {
            if (actualIsMovie || !(loadData.isAnime || loadData.originalAnimeId != null ||
                    loadData.anilistId != null || loadData.malId != null || loadData.kitsuId != null ||
                    isLibraryAnimeId(loadData.id) || (loadData.isCartoon && loadData.isAsian))) return

            val mapped = mappingDeferred.await()
            val aniListId = mapped?.aniListId?.takeIf { it > 0 }
                ?: loadData.originalAnimeId?.takeIf { it.startsWith("anilist:") }
                    ?.substringAfter(':')?.substringBefore(':')?.toIntOrNull()?.takeIf { it > 0 }
                ?: loadData.anilistId?.takeIf { it > 0 }
                ?: return
            val animeEpisode = loadData.imdbEpisode?.takeIf { it > 0 }
                ?: loadData.episode?.takeIf { it > 0 }?.let { episode ->
                    val from = mapped?.tmdbFromEpisode
                    val mappedSeason = mapped?.imdbSeason
                    if (from != null && mapped?.tmdbSeason == loadData.season &&
                        mappedSeason != null && mappedSeason != loadData.season &&
                        episode >= from) episode - from + 1 else episode
                } ?: return
            val animeType = animeSubtitleRequestType(addonUrl) ?: return
            fetchStremioSubtitles(
                addonUrl, "anilist:$aniListId:$animeEpisode", null, null, animeType, preferredName, group
            )
        }

        suspend fun fetchAnimeAwareSubtitles(
            addonUrl: String, id: String?, season: Int?, episode: Int?,
            type: String = targetType, preferredName: String? = null
        ) = coroutineScope {
            launch { fetchStremioSubtitles(addonUrl, id, season, episode, type, preferredName) }
            launch { fetchAniListSubtitles(addonUrl, preferredName) }
        }

        suspend fun mappedAnimeStremioId(): String? {
            if (actualIsMovie || !(loadData.isAnime || loadData.originalAnimeId != null ||
                    loadData.kitsuId != null || isLibraryAnimeId(loadData.id) ||
                    loadData.malId != null || loadData.anilistId != null ||
                    (loadData.isCartoon && loadData.isAsian))) return null
            val mapped = mappingDeferred.await() ?: return null
            val imdb = mapped.imdbId ?: return null
            val season = mapped.imdbSeason ?: mapped.tvdbSeason ?: mapped.traktSeason ?: mapped.tmdbSeason ?: return null
            val episode = loadData.episode?.takeIf { it > 0 } ?: return null
            return "$imdb:$season:$episode"
        }
        suspend fun streamRequest(type: String, id: String, season: Int?, episode: Int?): Pair<String, String>? {
            val originalFirst = type != "movie" && type != "series" && id.isNotBlank()
            val mappedId = if (originalFirst) null else mappedAnimeStremioId()
            val result = when {
                originalFirst -> type to id
                mappedId != null -> "series" to mappedId
                isLibraryAnimeId(id) -> if (actualIsMovie) integrationImdbId?.let { "movie" to it } else null
                id.isBlank() -> null
                else -> type to (buildStremioId(type, id, season, episode) ?: id)
            }
            return result
        }
        // Keep stream callbacks in each addon's response order, regardless of preparation time.
        suspend fun emitOrderedAddonStreams(
            streams: List<Stream>?,
            source: String,
            subtitleHandler: (SubtitleFile) -> Unit,
            extractorSubtitleHandler: (SubtitleFile) -> Unit,
            category: String,
            preferredName: String? = null,
            addonUrl: String? = null
        ): Int = coroutineScope {
            val jobs = streams.orEmpty().map { stream -> async(Dispatchers.IO) {
                val readyLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                withStreamCallback(category) {
                    stream.runCallback(this@StremioC, source, subtitleHandler, extractorSubtitleHandler,
                        { readyLinks.add(it) }, preferredName, addonUrl)
                }
                readyLinks
            } }
            var emitted = 0
            jobs.forEach { job ->
                job.await().forEach { link ->
                    emitted++
                    wrappedCallback(link)
                }
            }
            emitted
        }

        runAllAsync(
            {

                loadData.embeddedStreams?.let { json ->
                    runSuspendCatching {
                        val streams = parseJson<List<Stream>>(json)
                        val namedStreams = streams.map { stream ->
                            if (stream.name == null && stream.title == null && stream.description == null)
                                stream.copy(title = loadData.embeddedStreamTitle) else stream
                        }
                        emitOrderedAddonStreams(namedStreams, name,
                            stremioSubtitleFrom(currentMainUrl), extractorSubtitleFrom(currentMainUrl),
                            "embedded", addonUrl = currentMainUrl)
                    }
                }
            },
            {
                val request = streamRequest(targetType, primaryId, loadData.season, loadData.episode)
                if (cleanBaseUrl.isNotBlank() && request != null) {
                    suspend fun fetchMainStreams(route: Pair<String, String>): Int =
                        runSuspendCatching {
                            val streamRequestUrl = "$cleanBaseUrl/stream/${route.first}/${route.second.urlEncode()}.json$querySuffix"
                            val response = customSession.get(streamRequestUrl, timeout = 25L)
                            val text = response.text
                            val res = if (text.isNotBlank()) parseJson<StreamsResponse>(text) else null
                            emitOrderedAddonStreams(res?.streams, name,
                                stremioSubtitleFrom(currentMainUrl), extractorSubtitleFrom(currentMainUrl),
                                "main", addonUrl = currentMainUrl)
                        }
                            .getOrDefault(0)

                    val originalLinks = fetchMainStreams(request)
                    if (targetType != "movie" && targetType != "series" && originalLinks == 0) {
                        val fallbackRequest = if (actualIsMovie) {
                            integrationImdbId?.let { "movie" to it }
                        } else {
                            mappedAnimeStremioId()?.let { "series" to it }
                        }
                        if (fallbackRequest != null && fallbackRequest != request) {
                            fetchMainStreams(fallbackRequest)
                        }
                    }
                }
            },
            {

                val mappedId = mappedAnimeStremioId()
                if (mappedId != null) {
                    fetchAnimeAwareSubtitles(currentMainUrl, mappedId, null, null, "series")
                } else if (!isLibraryAnimeId(primaryId) || actualIsMovie) {
                    fetchAnimeAwareSubtitles(currentMainUrl, primaryId, loadData.season, loadData.episode)
                }
            },
            {
                if (isHistorySyncEnabled && syncUrl.isNotBlank()) {
                    fetchAnimeAwareSubtitles(
                        syncUrl,
                        globalId ?: primaryId,
                        globalSeason,
                        globalEpisode,
                        integrationTargetType
                    )
                }
            },
            {
                if (extraUrls.isNotEmpty()) coroutineScope {
                    val limiter = Semaphore(4)
                    val reqId = loadData.originalAnimeId ?: integrationImdbId ?: globalId.takeIf { it.isNotBlank() }
                    extraUrls.map { addon -> async(Dispatchers.IO) {
                        limiter.withPermit {
                            if (!reqId.isNullOrBlank()) {
                                val addonUrl = addon.url
                                val actualUrl = addonUrl.fixSourceUrl()
                                val base = actualUrl.cleanBaseUrl()
                                val suffix = actualUrl.substringAfter("?", "").takeIf { it.isNotEmpty() }
                                    ?.let { "?$it" } ?: ""
                                val season = if (loadData.originalAnimeId != null || integrationImdbId == null) loadData.season else globalSeason
                                val episode = if (loadData.originalAnimeId != null || integrationImdbId == null) loadData.episode else globalEpisode
                                val extraType = if (loadData.originalAnimeId != null) targetType else integrationTargetType
                                val streamRequest = streamRequest(extraType, reqId, season, episode)
                                val source = addon.name.takeIf { it.isNotBlank() }
                                    ?: getActualStremioAddonName(addonUrl)
                                    ?: base.toHttpUrlOrNull()?.host.orEmpty()
                                if (source.isNotBlank()) stremioSourceNames.add(source.lowercase())
                                runAllAsync({
                                    if (streamRequest != null && base.isNotBlank()) {
                                        runSuspendCatching {
                                            val requestUrl = "$base/stream/${streamRequest.first}/${streamRequest.second.urlEncode()}.json$suffix"
                                            val response = customSession.get(requestUrl, timeout = 25L)
                                            val text = response.text
                                            val streams = if (text.isNotBlank()) parseJson<StreamsResponse>(text) else null
                                            emitOrderedAddonStreams(streams?.streams, source,
                                                stremioSubtitleFrom(addonUrl), extractorSubtitleFrom(addonUrl),
                                                "extra", addon.name, addonUrl)
                                        }

                                    }
                                }, {
                                    val mappedId = mappedAnimeStremioId()
                                    if (mappedId != null) {
                                        fetchAnimeAwareSubtitles(addonUrl, mappedId, null, null, "series", addon.name)
                                    } else if (!isLibraryAnimeId(reqId) || actualIsMovie) {
                                        fetchAnimeAwareSubtitles(addonUrl, reqId, season, episode, extraType, addon.name)
                                    }
                                })
                            }
                        }
                    } }.awaitAll()
                }
            },
            {
                if (!actualIsMovie) coroutineScope {
                    val limiter = Semaphore(4)
                    integrationAddons.filter { it.subtitleOnly }.map { addon -> async(Dispatchers.IO) {
                        limiter.withPermit {
                            fetchAniListSubtitles(
                                addon.url, addon.name, if (addon.fromCineStream) 1 else 2
                            )
                        }
                    } }.awaitAll()
                }
            },
            {
                invokeCineStream(loadData, integrationTargetType, globalId, globalSeason, globalEpisode, isCasting, integratedSubtitleFrom(1), wrappedCallback, mappingDeferred)
            },
            {
                invokeTmdbStremioIntegrationFallback(
                    loadData,
                    targetType,
                    globalId,
                    globalSeason,
                    globalEpisode,
                    { sub, url, group -> stremioSubtitleFrom(url, group)(sub) },
                    { sub, url, group -> extractorSubtitleFrom(url, group)(sub) },
                    wrappedCallback
                )
            },
            { 
                invokeStreamPlay(loadData, integrationTargetType, globalId, globalSeason, globalEpisode, isCasting, integratedSubtitleFrom(2), wrappedCallback, mappingDeferred)
            },
            {
                if (isIntegrationEnabled("vixsrc_integration_enabled")) {
                    VixSrcExtractor.invokeVixSrc(loadData, targetType, globalSeason, globalEpisode, integratedSubtitleFrom(3), wrappedCallback)
                }
            }
        )
        mappingDeferred.cancel()
        }
        return true
    }

    private data class AnimeSeasonMapping(
        val kitsuId: String?, val malId: Int?, val aniListId: Int?, val tmdbId: Int?,
        val simklId: Int? = null, val tvdbId: Int? = null,
        val tmdbSeason: Int?, val tmdbEpisode: Int?, val title: String?,
        val imdbId: String?, val tvdbSeason: Int?, val imdbSeason: Int?,
        val traktSeason: Int?, val tmdbFromEpisode: Int? = null
    )

    internal suspend fun seasonTrackerIds(imdbId: String, season: Int): SeasonTrackerIds? {
        if (!IMDB_ID_REGEX.matches(imdbId) || season <= 0) return null
        val mapped = fetchAnimeSeasonMapping(LoadData(
            id = imdbId, imdbId = imdbId, imdbSeason = season, isAnime = true
        )) ?: return null
        if (mapped.imdbId != imdbId) {
            return null
        }

        if (season > 1 && mapped.imdbSeason != season &&
            mapped.tvdbSeason != season && mapped.traktSeason != season &&
            mapped.tmdbSeason != season) {
            return null
        }
        return SeasonTrackerIds(mapped.malId, mapped.aniListId,
            mapped.kitsuId?.toIntOrNull(), mapped.simklId, mapped.tvdbId,
            mapped.tvdbSeason, mapped.tmdbSeason)
            .takeIf { it.mal != null || it.aniList != null || it.kitsu != null ||
                it.simkl != null }
    }

    internal suspend fun playbackTrackerIds(loadData: LoadData): SeasonTrackerIds? {
        val mapped = fetchAnimeSeasonMapping(loadData) ?: return null
        return SeasonTrackerIds(mapped.malId, mapped.aniListId,
            mapped.kitsuId?.toIntOrNull(), mapped.simklId, mapped.tvdbId,
            mapped.tvdbSeason, mapped.tmdbSeason)
    }

    private suspend fun fetchAnimeSeasonMapping(loadData: LoadData): AnimeSeasonMapping? {
        val aniListLookupId = loadData.originalAnimeId?.takeIf { it.startsWith("anilist:") }
            ?.removePrefix("anilist:")?.substringBefore(':')?.toIntOrNull()?.takeIf { it > 0 }
            ?: loadData.anilistId?.takeIf { it > 0 }
            ?: loadData.id?.takeIf { it.startsWith("anilist:") }
                ?.removePrefix("anilist:")?.substringBefore(':')?.toIntOrNull()?.takeIf { it > 0 }
        val kitsuId = (loadData.originalAnimeId?.takeIf { it.startsWith("kitsu:") } ?: loadData.kitsuId
            ?: loadData.id?.takeIf { it.startsWith("kitsu:") })
            ?.removePrefix("kitsu:")?.substringBefore(':')
            ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        val imdbId = (loadData.imdbId ?: loadData.id)
            ?.substringBefore(':')?.takeIf { IMDB_ID_REGEX.matches(it) }
        if (!loadData.isAnime && kitsuId == null && loadData.malId == null &&
            loadData.anilistId == null && aniListLookupId == null && !(loadData.isCartoon && loadData.isAsian)) return null
        val byKitsu = loadData.originalAnimeId?.startsWith("kitsu:") == true && kitsuId != null
        val malLookupId = loadData.originalAnimeId?.takeIf { it.startsWith("mal:") }
            ?.removePrefix("mal:")?.substringBefore(':')?.toIntOrNull()?.takeIf { it > 0 }
            ?: loadData.malId?.takeIf { it > 0 }
            ?: loadData.id?.takeIf { it.startsWith("mal:") }
                ?.removePrefix("mal:")?.substringBefore(':')?.toIntOrNull()?.takeIf { it > 0 }
        val byMal = loadData.originalAnimeId?.startsWith("mal:") == true && malLookupId != null
        val byAniList = loadData.originalAnimeId?.startsWith("anilist:") == true && aniListLookupId != null && imdbId == null
        val id = when {
            byKitsu -> kitsuId
            byMal -> malLookupId.toString()
            byAniList -> aniListLookupId.toString()
            else -> imdbId ?: kitsuId ?: malLookupId?.toString() ?: aniListLookupId?.toString() ?: return null
        }
        val season = loadData.imdbSeason?.takeIf { it > 0 } ?: loadData.season?.takeIf { it > 0 }
        val episode = loadData.imdbEpisode?.takeIf { it > 0 } ?: loadData.episode?.takeIf { it > 0 }
        val query = when {
            byMal -> "idType=malId&idValue=$id"
            byAniList -> "idType=anilistId&idValue=$id"
            byKitsu || (id == kitsuId && imdbId == null) -> "idType=kitsuId&idValue=$id"
            imdbId == null && malLookupId != null -> "idType=malId&idValue=$id"
            imdbId == null && aniListLookupId != null -> "idType=anilistId&idValue=$id"
            else -> "idType=imdbId&idValue=$id" +
                (if (season != null) "&season=$season" else "") +
                (if (episode != null) "&episode=$episode" else "")
        }
        val memoryAnime = stremioCacheMemoryRead(animeMappingCache[query], METADATA_CACHE_TTL)
        val cachedAnime = memoryAnime ?: MetadataDiskCache.read("anime", query)?.let { (json, stamp) ->
            runCatching { parseJson<AnimeSeasonMapping>(json) }.getOrNull()?.let { value ->
                Pair(value, stamp).also {
                    animeMappingCache[query] = it
                }
            }
        }
        if (cachedAnime != null) {
            return cachedAnime.first
        }
        val noMappingStamp = animeNoMappingCache[query]
        if (noMappingStamp != null) {
            val age = System.currentTimeMillis() - noMappingStamp
            if (age >= 0L && age < 15L * 60 * 1000) {
                return null
            }
            animeNoMappingCache.remove(query)
        }
        MetadataDiskCache.read("missing_anime", query)?.takeIf { it.first == "null" }?.let {
            animeNoMappingCache[query] = it.second
            return null
        }
        val hosts = listOf(
            "aiostreams.elfhosted.com",
            "aiostreams.fortheweak.cloud",
            "aiostreamsfortheweebsstable.midnightignite.me",
            "aio.atbphosting.com",
            "aiostreams.stremio.ru",
            "aiostreams.viren070.me"
        )
        for (host in hosts) {
            var noMapping = false
            val outcome = runSuspendCatching {
            val response = run {
                fetchSharedAnimeText("https://$host/api/v1/anime?$query")
            }
            val root = run { parseJson<JsonNode>(response) }
            if (root.get("success")?.asBoolean() != true) {
                return@runSuspendCatching null
            }
            if (root.get("data")?.isNull == true) {
                noMapping = true
                return@runSuspendCatching null
            }
            val data = root.get("data") ?: run {
                return@runSuspendCatching null
            }
            val ids = data.get("mappings") ?: run {
                return@runSuspendCatching null
            }
            val resolvedKitsu = ids.get("kitsuId").intOrNull()?.takeIf { it > 0 }?.toString()
            val resolvedImdb = ids.get("imdbId").textOrNull()
            if (byKitsu && resolvedKitsu != kitsuId) {
                return@runSuspendCatching null
            }
            if ((byMal || imdbId == null && kitsuId == null && malLookupId != null && !byAniList) &&
                ids.get("malId").intOrNull() != malLookupId) {
                return@runSuspendCatching null
            }
            if (byAniList && ids.get("anilistId").intOrNull() != aniListLookupId) {
                return@runSuspendCatching null
            }
            if (imdbId != null && resolvedImdb != imdbId) {
                return@runSuspendCatching null
            }
            if (resolvedKitsu == null && ids.get("malId").intOrNull() == null &&
                ids.get("anilistId").intOrNull() == null) {
                return@runSuspendCatching null
            }

            if (!byKitsu && !byMal && !byAniList && season != null && season > 1) {
                val mappedSeason = data.get("imdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
                    ?: data.get("tvdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
                    ?: data.get("trakt")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
                    ?: data.get("tmdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
                if (mappedSeason != season) {
                    return@runSuspendCatching null
                }
            }
            val tmdb = data.get("tmdb")
            val first = tmdb?.get("fromEpisode").intOrNull()?.takeIf { it > 0 }
            val tmdbSeason = tmdb?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
            val localEpisode = loadData.episode?.takeIf { it > 0 }
            val tmdbEpisode = when {
                tmdbSeason == null || localEpisode == null -> null
                first != null -> if (!byKitsu && !byMal && !byAniList && season == tmdbSeason && localEpisode >= first)
                    localEpisode else first + localEpisode - 1
                !byKitsu && !byMal && !byAniList && season == tmdbSeason -> localEpisode
                else -> null 
            }
            AnimeSeasonMapping(
                kitsuId = resolvedKitsu,
                malId = ids.get("malId").intOrNull()?.takeIf { it > 0 },
                aniListId = ids.get("anilistId").intOrNull()?.takeIf { it > 0 },
                tmdbId = ids.get("themoviedbId").intOrNull()?.takeIf { it > 0 },
                simklId = ids.get("simklId").intOrNull()?.takeIf { it > 0 },
                tvdbId = ids.get("thetvdbId").intOrNull()?.takeIf { it > 0 },
                tmdbSeason = tmdbSeason,
                tmdbEpisode = tmdbEpisode,
                title = data.get("title").textOrNull(),
                imdbId = resolvedImdb?.takeIf { IMDB_ID_REGEX.matches(it) },
                tvdbSeason = data.get("tvdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 },
                imdbSeason = data.get("imdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 },
                traktSeason = data.get("trakt")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 },
                tmdbFromEpisode = first
            )
            }
            val mapping = outcome.getOrNull()
            if (noMapping) {

                animeNoMappingCache[query] = System.currentTimeMillis()
                addonMetaScope.launch {
                    MetadataDiskCache.write("missing_anime", query, "null", 15L * 60 * 1000)
                }
                return null
            }
            if (mapping != null) {
                animeMappingCache[query] = mapping to System.currentTimeMillis()
                MetadataDiskCache.write("anime", query, mapping.toJson())
                return mapping
            }
        }
        return null
    }

    private data class IntegrationData(
        val imdbId: String?,
        val tmdbId: Int?,
        val kitsuId: String?,
        val isAnime: Boolean,
        val isMovie: Boolean,
        val title: String,
        val season: Int?,
        val episode: Int?,
        val imdbSeason: Int?,
        val imdbEpisode: Int?,
        val year: Int?,
        val airedYear: Int?,
        val malId: Int?, val aniListId: Int?,
        val tmdbSeason: Int?, val tmdbEpisode: Int?,
        val hasSeasonMapping: Boolean
    )

    private fun integrationData(
        loadData: LoadData,
        targetType: String,
        globalId: String?,
        globalSeason: Int?,
        globalEpisode: Int?,
        mapping: AnimeSeasonMapping? = null
    ): IntegrationData {
        val imdbId = listOf(mapping?.imdbId, loadData.imdbId, globalId, loadData.id)
            .firstNotNullOfOrNull { candidate ->
                candidate?.substringBefore(':')?.takeIf { IMDB_ID_REGEX.matches(it) }
            }
        val tmdbId = mapping?.tmdbId ?: loadData.tmdbId?.takeIf { it > 0 }
            ?: loadData.id?.takeIf { it.startsWith("tmdb:") }
                ?.removePrefix("tmdb:")?.substringBefore(':')?.toIntOrNull()?.takeIf { it > 0 }
        val kitsuId = (mapping?.kitsuId ?: loadData.kitsuId
            ?: loadData.originalAnimeId?.takeIf { it.startsWith("kitsu:") }
            ?: loadData.id?.takeIf { it.startsWith("kitsu:") }
                ?.removePrefix("kitsu:"))
            ?.removePrefix("kitsu:")?.substringBefore(':')
            ?.takeIf { it.isNotBlank() && it.all { digit -> digit.isDigit() } }
        val isAnime = loadData.isAnime ||
            isLibraryAnimeId(loadData.id) || isLibraryAnimeId(loadData.originalAnimeId) ||
            kitsuId != null || loadData.anilistId != null || loadData.malId != null ||
            (loadData.isCartoon && loadData.isAsian)
        val isMovie = targetType == "movie" ||
            (loadData.season == null && loadData.episode == null)
        val mappedImdbSeason = mapping?.takeIf { it.imdbId != null && (loadData.episode ?: 0) > 0 }
            ?.let { it.imdbSeason ?: it.tvdbSeason ?: it.traktSeason ?: it.tmdbSeason }
        return IntegrationData(
            imdbId = imdbId,
            tmdbId = tmdbId,
            kitsuId = kitsuId,
            isAnime = isAnime,
            isMovie = isMovie,
            title = mapping?.title?.takeIf { it.isNotBlank() }
                ?: loadData.englishTitle?.takeIf { it.isNotBlank() }
                ?: loadData.title?.takeIf { it.isNotBlank() }
                ?: loadData.originalTitle?.takeIf { it.isNotBlank() }
                ?: loadData.id ?: "Unknown",
            season = if (isMovie) null else if (mapping != null) 1 else loadData.season?.takeIf { it > 0 } ?: globalSeason,
            episode = if (isMovie) null else loadData.episode?.takeIf { it > 0 } ?: globalEpisode,
            imdbSeason = if (isMovie) null else mappedImdbSeason
                ?: if (loadData.originalAnimeId != null) null else loadData.imdbSeason?.takeIf { it > 0 } ?: globalSeason,
            imdbEpisode = if (isMovie) null else if (mappedImdbSeason != null) loadData.episode
                else if (loadData.originalAnimeId != null) null else loadData.imdbEpisode?.takeIf { it > 0 } ?: globalEpisode,
            year = loadData.year,
            airedYear = loadData.airedYear,
            malId = if (mapping != null) mapping.malId else if (loadData.originalAnimeId != null || (loadData.season ?: 1) > 1) null else loadData.malId,
            aniListId = if (mapping != null) mapping.aniListId else if (loadData.originalAnimeId != null || (loadData.season ?: 1) > 1) null else loadData.anilistId,
            tmdbSeason = mapping?.tmdbSeason,
            tmdbEpisode = mapping?.tmdbEpisode,
            hasSeasonMapping = mapping != null
        )
    }

    private fun cinePayload(
        loadData: LoadData,
        targetType: String,
        media: IntegrationData,
        animeRoute: Boolean,
        japaneseTitle: Boolean = false,
        standardImdbRoute: Boolean = false
    ): Map<String, Any?> {
        val title = if (japaneseTitle) loadData.originalTitle?.takeIf { it.isNotBlank() }
            ?: media.title else media.title
        val splitIds = media.isAnime && !animeRoute &&
            media.imdbId != null && media.tmdbId != null &&
            (media.imdbSeason != media.season || media.imdbEpisode != media.episode)
        val useImdbEpisode = media.isAnime && !animeRoute && media.imdbId != null &&
            (!splitIds || standardImdbRoute)
        val requestImdbId = if (media.hasSeasonMapping && !animeRoute && !standardImdbRoute) null
            else if (splitIds && !standardImdbRoute) null else media.imdbId
        val requestTmdbId = if (!animeRoute && standardImdbRoute) null else media.tmdbId
        val originalYear = if (media.isAnime && (media.hasSeasonMapping || (media.season ?: 0) > 1))
            media.airedYear ?: media.year else media.year
        return mapOf(
            "title" to title,
            "id" to (requestImdbId ?: ""),
            "tmdbId" to requestTmdbId,
            "tvtype" to targetType,
            "year" to originalYear?.toString(),
            "season" to (if (media.hasSeasonMapping && !animeRoute && !standardImdbRoute)
                media.tmdbSeason else if (useImdbEpisode) media.imdbSeason else media.season),
            "episode" to (if (media.hasSeasonMapping && !animeRoute && !standardImdbRoute)
                media.tmdbEpisode else if (useImdbEpisode) media.imdbEpisode else media.episode),
            "firstAired" to loadData.firstAired,
            "isAnime" to media.isAnime,
            "isBollywood" to loadData.isBollywood,
            "isAsian" to loadData.isAsian,
            "isCartoon" to loadData.isCartoon,
            "imdb_id" to requestImdbId,
            "imdbSeason" to media.imdbSeason,
            "imdbEpisode" to media.imdbEpisode,
            "isKitsu" to animeRoute,
            "anilistId" to media.aniListId,
            "malId" to media.malId,
            "kitsuId" to media.kitsuId
        )
    }

    private fun streamPlayPayload(
        loadData: LoadData,
        targetType: String,
        media: IntegrationData,
        generalAnimeRoute: Boolean = false,
        imdbOnlyRoute: Boolean = false
    ): Map<String, Any?> {

        val mapped = generalAnimeRoute && media.tmdbId == null
        val season = if (media.hasSeasonMapping && generalAnimeRoute) {
            if (imdbOnlyRoute) media.imdbSeason else media.tmdbSeason
        } else if (mapped) media.imdbSeason else media.season
        val episode = if (media.hasSeasonMapping && generalAnimeRoute) {
            if (imdbOnlyRoute) media.imdbEpisode else media.tmdbEpisode
        } else if (mapped) media.imdbEpisode else media.episode
        val differentMapping = media.isAnime &&
            (media.imdbSeason != media.season || media.imdbEpisode != media.episode)
        val requestImdbId = if (media.hasSeasonMapping) {
            if (generalAnimeRoute && imdbOnlyRoute) media.imdbId else null
        } else if (differentMapping && !mapped) null else media.imdbId
        val year = if (media.isAnime && (media.hasSeasonMapping || (media.season ?: 0) > 1))
            media.airedYear ?: media.year else media.year
        return mapOf(
            "id" to if (imdbOnlyRoute) null else media.tmdbId,
            "imdbId" to requestImdbId,
            "type" to targetType,
            "season" to season,
            "episode" to episode,
            "aniId" to media.aniListId?.toString(),
            "title" to media.title,
            "year" to year,
            "orgTitle" to loadData.originalTitle,
            "isAnime" to (media.isAnime && !generalAnimeRoute),
            "airedYear" to media.airedYear,
            "lastSeason" to (media.season?.takeIf { it > 1 } ?: 1),
            "jpTitle" to loadData.originalTitle,
            "date" to loadData.firstAired,
            "airedDate" to loadData.firstAired,
            "isAsian" to loadData.isAsian,
            "isBollywood" to loadData.isBollywood,
            "isCartoon" to loadData.isCartoon,
            "isMovie" to media.isMovie
        )
    }

    private data class IntegrationStremioAddon(
        val name: String,
        val url: String,
        val subtitleOnly: Boolean,
        val fromCineStream: Boolean
    )

    private fun getActualStremioAddonName(addonUrl: String): String? {
        val context = CloudStreamApp.context ?: return null
        val manifestPrefs = context.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
        val manifestJson = manifestPrefs.getString(stremioCatalogKey(addonUrl), null)
        val manifestName = manifestJson?.let { raw ->
            runCatching { parseJson<JsonNode>(raw).get("name").textOrNull()?.trim() }.getOrNull()
        }
        if (!manifestName.isNullOrBlank() && !manifestName.equals("StremioC", true)) return manifestName

        val savedJson = context.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
            .getString("stremio_saved_links", null) ?: return null
        val saved = runCatching { org.json.JSONArray(savedJson) }.getOrNull() ?: return null
        val target = stremioManifestUrl(addonUrl)
        for (i in 0 until saved.length()) {
            val entry = saved.optJSONObject(i) ?: continue
            val link = entry.optString("link").takeIf { it.isNotBlank() } ?: continue
            if (stremioManifestUrl(link) != target) continue
            val declaredName = entry.optString("name").trim()
            if (declaredName.isNotBlank() && !declaredName.equals("StremioC", true)) return declaredName
        }
        return null
    }

    private fun getIntegrationStremioAddons(): List<IntegrationStremioAddon> {
        val context = CloudStreamApp.context ?: return emptyList()
        val addons = linkedMapOf<Pair<String, Boolean>, IntegrationStremioAddon>()

        if (isIntegrationEnabled("cinestream_integration_enabled") && getCineStreamProvider() != null) {
            getKey<String>("stremio_addons")
                ?.lineSequence()
                ?.filter { it.isNotBlank() }
                ?.map { it.split("|") }
                ?.filter { it.size >= 3 }
                ?.filter { parts ->
                    val key = "stremio_${parts[0].trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}"
                    getKey<Boolean>(key) != false
                }
                ?.forEach { parts ->
                    val url = parts[1].trim().takeIf(String::isNotBlank) ?: return@forEach
                    val subtitleOnly = parts[2].equals("SUBTITLE", ignoreCase = true)
                    addons.putIfAbsent(
                        url to subtitleOnly,
                        IntegrationStremioAddon(parts[0].trim().ifBlank { url }, url, subtitleOnly, true)
                    )
                }
        }

        if (isIntegrationEnabled("streamplay_integration_enabled") && getStreamPlayProvider() != null) {
            val raw = context.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
                .getString("streamplay_stremio_addon_saved_links", null)

            runCatching {
                val root = raw?.let { parseJson<JsonNode>(it) }
                if (root != null && root.isArray) {
                    root.forEach { item ->
                        val url = item.get("link").textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                        val subtitleOnly = item.get("type").textOrNull()?.equals("SUBTITLE", ignoreCase = true) == true
                        val name = item.get("name").textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: url
                        addons.putIfAbsent(
                            url to subtitleOnly,
                            IntegrationStremioAddon(name, url, subtitleOnly, false)
                        )
                    }
                }
            }
        }

        return addons.values.filter { isCatalogAddonEnabled(it.url) }
    }

    private suspend fun invokeTmdbStremioIntegrationFallback(
        loadData: LoadData,
        targetType: String,
        globalId: String?,
        globalSeason: Int?,
        globalEpisode: Int?,
        stremioSubtitleCallback: (SubtitleFile, String, Int) -> Unit,
        extractorSubtitleCallback: (SubtitleFile, String, Int) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (targetType != "movie" && targetType != "series") {
            return
        }

        val media = integrationData(loadData, targetType, globalId, globalSeason, globalEpisode)
        if (!media.imdbId.isNullOrBlank()) {
            return
        }

        val tmdbId = media.tmdbId ?: run {
            return
        }
        val stremioId = if (targetType == "series") {
            val season = media.season ?: return
            val episode = media.episode ?: return
            "tmdb:$tmdbId:$season:$episode"
        } else {
            "tmdb:$tmdbId"
        }
        val encodedId = stremioId.urlEncode()
        val addons = getIntegrationStremioAddons()
        if (addons.isEmpty()) return

        supervisorScope {
            addons.map { addon ->
                async(Dispatchers.IO) {
                    runSuspendCatching {
                        val cleanBaseUrl = addon.url.fixSourceUrl().cleanBaseUrl()
                        if (cleanBaseUrl.isBlank()) return@runSuspendCatching

                        val querySuffix = addon.url.substringAfter("?", "")
                            .takeIf { it.isNotEmpty() }
                            ?.let { "?$it" }
                            ?: ""

                        if (addon.subtitleOnly) {
                            val requestUrl = "$cleanBaseUrl/subtitles/$targetType/${encodedId}.json$querySuffix"
                            val text = customSession.get(requestUrl, timeout = 15L).text
                            val res = if (text.isNotBlank()) parseJson<SubtitlesResponse>(text) else null
                            res?.subtitles?.forEachIndexed { position, sub ->
                                sub.toCloudStreamSubtitle(addon.name)?.let { subFile ->
                                    StremioSubtitleRefreshRegistry.remember(
                                        subFile.url, addon.url, sub.id, sub.lang ?: sub.lang_code,
                                        requestUrl, position, subFile.headers ?: emptyMap()
                                    )
                                    stremioSubtitleCallback(subFile, addon.url, if (addon.fromCineStream) 1 else 2)
                                }
                            }
                        } else {
                            val requestUrl = "$cleanBaseUrl/stream/$targetType/${encodedId}.json$querySuffix"
                            val response = customSession.get(requestUrl, timeout = 25L)
                            val text = response.text
                            val res = if (text.isNotBlank()) parseJson<StreamsResponse>(text) else null
                            res?.streams?.forEach { stream ->
                                stream.runCallback(
                                    this@StremioC,
                                    addon.name,
                                    { subFile ->
                                        stremioSubtitleCallback(subFile, addon.url, if (addon.fromCineStream) 1 else 2)
                                    },
                                    { subFile ->
                                        extractorSubtitleCallback(subFile, addon.url, if (addon.fromCineStream) 1 else 2)
                                    },
                                    callback,
                                    addonUrl = addon.url
                                )
                            }
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun invokeCineStream(
        loadData: LoadData,
        targetType: String,
        globalId: String?,
        globalSeason: Int?,
        globalEpisode: Int?,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        mappingDeferred: Deferred<AnimeSeasonMapping?>?
    ) {
        if (!isIntegrationEnabled("cinestream_integration_enabled")) {
            return
        }
        if (targetType != "movie" && targetType != "series") {
            return
        }

        try {
            val cineStreamProvider = getCineStreamProvider() ?: return
            val media = integrationData(loadData, targetType, globalId, globalSeason, globalEpisode,
                mappingDeferred?.await())
            val linksFound = java.util.concurrent.atomic.AtomicInteger(0)
            val trackedCallback: (ExtractorLink) -> Unit = { link ->
                linksFound.incrementAndGet()
                callback(link)
            }
            suspend fun callCine(
                animeRoute: Boolean,
                japaneseTitle: Boolean = false,
                standardImdbRoute: Boolean = false
            ) {
                try {
                    withIntegratedSource("cine") { cineStreamProvider.loadLinks(
                        cinePayload(loadData, targetType, media, animeRoute,
                            japaneseTitle, standardImdbRoute).toJson(),
                        isCasting, subtitleCallback, trackedCallback
                    ) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                }
            }

            if (media.isAnime) {

                withTimeoutOrNull(55000L) {
                    supervisorScope {
                        val anime = if (media.hasSeasonMapping || (loadData.originalAnimeId == null && (media.season ?: 1) <= 1))
                            async(Dispatchers.IO) { callCine(animeRoute = true) } else null
                        val standard = if (media.hasSeasonMapping && media.tmdbId != null &&
                            media.tmdbSeason != null && media.tmdbEpisode != null ||
                            !media.hasSeasonMapping && loadData.originalAnimeId == null && (media.season ?: 1) <= 1)
                            async(Dispatchers.IO) { callCine(animeRoute = false) } else null
                        val mappedImdb = if (
                            media.imdbId != null && media.imdbSeason != null && media.imdbEpisode != null &&
                            (media.hasSeasonMapping || (loadData.originalAnimeId == null && (media.season ?: 1) > 1) ||
                                (media.tmdbId != null && (media.imdbSeason != media.season || media.imdbEpisode != media.episode)))
                        ) async(Dispatchers.IO) {
                            callCine(animeRoute = false, standardImdbRoute = true)
                        } else null
                        listOfNotNull(anime, standard, mappedImdb).awaitAll()
                    }
                }
                if (linksFound.get() == 0 && media.hasSeasonMapping &&
                    !loadData.originalTitle.isNullOrBlank() &&
                    !loadData.originalTitle.equals(media.title, ignoreCase = true)
                ) {
                    withTimeoutOrNull(15000L) {
                        callCine(animeRoute = true, japaneseTitle = true)
                    }
                }
            } else {
                withTimeoutOrNull(55000L) { callCine(animeRoute = false) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    private suspend fun invokeStreamPlay(
        loadData: LoadData,
        targetType: String,
        globalId: String?,
        globalSeason: Int?,
        globalEpisode: Int?,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        mappingDeferred: Deferred<AnimeSeasonMapping?>?
    ) {
        if (!isIntegrationEnabled("streamplay_integration_enabled")) {
            return
        }
        if (targetType != "movie" && targetType != "series") {
            return
        }

        try {
            val media = integrationData(loadData, targetType, globalId, globalSeason, globalEpisode,
                mappingDeferred?.await())
            val streamPlayProvider = getStreamPlayProvider()

            if (!media.isAnime) {
                if (streamPlayProvider != null) {
                    withTimeoutOrNull(55000L) {
                        withIntegratedSource("streamplay") { streamPlayProvider.loadLinks(
                            streamPlayPayload(loadData, targetType, media).toJson(),
                            isCasting, subtitleCallback, callback
                        ) }
                    }
                }
                return
            }

            supervisorScope {

                val dedicatedAnime = getStreamPlayAnimeProvider()?.takeIf {
                    media.hasSeasonMapping || (loadData.originalAnimeId == null && (media.season ?: 1) <= 1)
                }?.let { animeProvider ->
                    async(Dispatchers.IO) {
                        try {
                            val animePayload = mapOf(
                                "imdbId" to media.imdbId,
                                "tmdbId" to media.tmdbId,
                                "type" to targetType,
                                "season" to media.season,
                                "episode" to media.episode,
                                "aniId" to media.aniListId,
                                "malId" to media.malId,
                                "title" to media.title,
                                "year" to (media.airedYear ?: media.year),
                                "orgTitle" to loadData.originalTitle,
                                "isAnime" to true,
                                "airedYear" to media.airedYear,
                                "lastSeason" to (media.season?.takeIf { it > 1 } ?: 1),
                                "jpTitle" to loadData.originalTitle,
                                "date" to loadData.firstAired,
                                "airedDate" to loadData.firstAired,
                                "isAsian" to loadData.isAsian,
                                "isBollywood" to loadData.isBollywood,
                                "isCartoon" to loadData.isCartoon
                            )
                            withTimeoutOrNull(35000L) {
                                withIntegratedSource("anime") { animeProvider.loadLinks(
                                    animePayload.toJson(), isCasting, subtitleCallback, callback
                                ) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                        }
                    }
                }
                if (streamPlayProvider != null) {
                    val anime = async(Dispatchers.IO) {
                        try {
                            if (media.hasSeasonMapping || (loadData.originalAnimeId == null && (media.season ?: 1) <= 1)) withTimeoutOrNull(35000L) {
                                withIntegratedSource("streamplay") { streamPlayProvider.loadLinks(
                                    streamPlayPayload(loadData, targetType, media).toJson(),
                                    isCasting, subtitleCallback, callback
                                ) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                        }
                    }
                    val general = if (media.hasSeasonMapping && media.tmdbSeason != null && media.tmdbEpisode != null && media.tmdbId != null ||
                        !media.hasSeasonMapping && loadData.originalAnimeId == null && (media.season ?: 1) <= 1 &&
                        (media.imdbId != null || media.tmdbId != null)) {
                        async(Dispatchers.IO) {
                            try {
                                withTimeoutOrNull(35000L) {
                                    withIntegratedSource("streamplay") { streamPlayProvider.loadLinks(
                                        streamPlayPayload(
                                            loadData, targetType, media, generalAnimeRoute = true
                                        ).toJson(),
                                        isCasting, subtitleCallback, callback
                                    ) }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                            }
                        }
                    } else null
                    val imdbGeneral = if (media.imdbId != null &&
                        media.imdbSeason != null && media.imdbEpisode != null &&
                        (media.hasSeasonMapping || (loadData.originalAnimeId == null && (media.season ?: 1) > 1))) {
                        async(Dispatchers.IO) {
                            runSuspendCatching {
                                withTimeoutOrNull(35000L) {
                                    withIntegratedSource("streamplay") { streamPlayProvider.loadLinks(
                                        streamPlayPayload(loadData, targetType, media,
                                            generalAnimeRoute = true, imdbOnlyRoute = true).toJson(),
                                        isCasting, subtitleCallback, callback
                                    ) }
                                }
                            }
                        }
                    } else null
                    anime.await()
                    general?.await()
                    imdbGeneral?.await()
                }
                dedicatedAnime?.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    data class LoadData(
        @JsonProperty("type") val type: String? = null, 
        @JsonProperty("id") val id: String? = null, 
        @JsonProperty("season") val season: Int? = null, 
        @JsonProperty("episode") val episode: Int? = null, 
        @JsonProperty("imdbId") val imdbId: String? = null, 
        @JsonProperty("year") val year: Int? = null, 
        @JsonProperty("title") val title: String? = null, 
        @JsonProperty("tmdbId") val tmdbId: Int? = null, 
        @JsonProperty("isAnime") val isAnime: Boolean = false,
        @JsonProperty("isBollywood") val isBollywood: Boolean = false,
        @JsonProperty("isAsian") val isAsian: Boolean = false,
        @JsonProperty("isCartoon") val isCartoon: Boolean = false,
        @JsonProperty("firstAired") val firstAired: String? = null,
        @JsonProperty("airedYear") val airedYear: Int? = null, 
        @JsonProperty("originalTitle") val originalTitle: String? = null, 
        @JsonProperty("englishTitle") val englishTitle: String? = null, 
        @JsonProperty("imdbSeason") val imdbSeason: Int? = null,
        @JsonProperty("imdbEpisode") val imdbEpisode: Int? = null,
        @JsonProperty("anilistId") val anilistId: Int? = null,
        @JsonProperty("malId") val malId: Int? = null,
        @JsonProperty("kitsuId") val kitsuId: String? = null,
        @JsonProperty("addonUrl") val addonUrl: String? = null,
        @JsonProperty("originalAnimeId") @JsonAlias("originalKitsuId") val originalAnimeId: String? = null, 
        @JsonProperty("embeddedStreams") val embeddedStreams: String? = null,
        @JsonProperty("embeddedStreamTitle") val embeddedStreamTitle: String? = null
    )

    data class Manifest(val catalogs: List<Catalog>)

    data class Extra(
        @JsonProperty("name") val name: String?, 
        @JsonProperty("isRequired") val isRequired: Boolean? = false,
        @JsonProperty("options") val options: List<String>? = null
    )

    data class Catalog(
        @JsonProperty("name") var name: String? = null, 
        @JsonProperty("id") val id: String? = null, 
        @JsonProperty("type") val type: String? = null, 
        @JsonProperty("types") val parsedTypes: List<String>? = null, 
        @JsonProperty("extra") val extra: List<Extra>? = null, 
        @JsonProperty("extraSupported") val extraSupported: List<String>? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("extraRequired") val extraRequired: List<String>? = null
    ) {
        fun supportsExtra(name: String): Boolean = if (extra != null) {
            extra.any { it.name == name }
        } else extraSupported?.contains(name) == true || extraRequired?.contains(name) == true ||
            (name == "genre" && !genres.isNullOrEmpty())

        fun browseGenres(): List<String> = if (!supportsExtra("genre")) emptyList() else
            (extra?.find { it.name == "genre" }?.options?.takeIf { it.isNotEmpty() }
                ?: genres.orEmpty()).filter { it.isNotBlank() }.distinct()

        fun browseBlockReason(): String? {
            val required = extra.orEmpty().filter { it.isRequired == true }.mapNotNull { it.name } + extraRequired.orEmpty()
            val unsupported = required.filter { it != "genre" && it != "skip" }
            return when {
                "search" in unsupported -> "This catalog requires a search. Use the regular search screen."
                unsupported.isNotEmpty() -> "Required filters are not supported: ${unsupported.joinToString()}"
                requiresExtra("genre") && browseGenres().isEmpty() -> "This catalog requires a genre but provides no genre options."
                required.any { !supportsExtra(it) } -> "Inconsistent required filters in the manifest."
                else -> null
            }
        }

        internal fun browsePath(genre: String?, offset: Int): String {
            require(!id.isNullOrBlank() && !type.isNullOrBlank()) { "Invalid catalog" }
            require(offset >= 0 && (offset == 0 || supportsExtra("skip"))) { "Pagination is not supported" }
            require(browseBlockReason() == null) { browseBlockReason().orEmpty() }
            require(!requiresExtra("genre") || genre != null) { "Choose a genre" }
            require(genre == null || (supportsExtra("genre") && genre in browseGenres())) { "Invalid genre" }
            val extras = buildList {
                genre?.let { add("genre=${it.urlEncodeLocal()}") }
                if (supportsExtra("skip") && (offset > 0 || requiresExtra("skip"))) add("skip=$offset")
            }.joinToString("&")
            return "/catalog/${type.urlEncodeLocal()}/${id.urlEncodeLocal()}" +
                (if (extras.isEmpty()) "" else "/$extras") + ".json"
        }

        fun requiresExtra(name: String): Boolean =
            extra?.any { it.name == name && it.isRequired == true } == true ||
                extraRequired?.contains(name) == true

        fun supportsHome(): Boolean {
            val required = extra.orEmpty().filter { it.isRequired == true }.mapNotNull { it.name } +
                extraRequired.orEmpty()
            return !id.isNullOrBlank() && required.all { it == "genre" || it == "skip" } &&
                (!requiresExtra("genre") || getGenresList().isNotEmpty())
        }

        val activeTypes: List<String> get() = if (!parsedTypes.isNullOrEmpty()) parsedTypes else listOfNotNull(type)

        fun getGenresList(): List<String> {
            val list = mutableListOf<String>()
            genres?.let { list.addAll(it) }
            if (list.isEmpty()) {
                extra?.find { it.name == "genre" }?.options?.let { list.addAll(it) }
            }
            return list
        }

        fun supportsSearch(): Boolean = extra?.any { it.name == "search" } == true || extraSupported?.contains("search") == true

        private suspend fun fetchMetas(provider: StremioC, path: String): List<CatalogEntry> {
            return try {
                val text = StremioC.customSession.get(provider.buildUrl(path), timeout = 120L).text
                if (text.isNotBlank()) parseJson<CatalogResponse>(text).metas ?: emptyList() else emptyList()
            } catch (e: Throwable) { emptyList() }
        }

        suspend fun search(query: String, provider: StremioC): List<SearchResponse> {
            val allMetasWithType = coroutineScope {
                activeTypes.map { type ->
                    async(Dispatchers.IO) {
                        val path = "/catalog/${type}/${id}/search=${query.urlEncode()}.json"
                        fetchMetas(provider, path).map { it to type }
                    }
                }.awaitAll().flatten()
            }
            return allMetasWithType.distinctBy { it.first.id }.map { (meta, type) ->
                meta.toSearchResponse(provider, typeName = type)
            }
        }

    }

    private data class HomeCatalogResponse(
        @JsonProperty("metas") val metas: List<HomeCatalogEntry>? = null
    )

    private data class HomeCatalogEntry(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("moviedb_id") val moviedb_id: Int? = null,
        @JsonProperty("_tmdbId") val tmdbIdExt: String? = null
    )

    private data class CatalogResponse(
        @JsonProperty("metas") val metas: List<CatalogEntry>?, 
        @JsonProperty("meta") val meta: CatalogEntry?
    )

    private data class Trailer(
        @JsonProperty("source") val source: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("iso_639_1") val isoLanguage: String? = null
    )

    private data class TrailerStream(
        @JsonProperty("ytId") val ytId: String?,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("iso_639_1") val isoLanguage: String? = null
    )

    private data class Link(
        @JsonProperty("name") val name: String? = null, 
        @JsonProperty("category") val category: String? = null, 
        @JsonProperty("url") val url: String? = null
    )

    private data class FetchedTmdbData(
        val fetchedTitle: String?, val fetchedOverview: String?, val fetchedLogo: String?,
        val fetchedTrailers: List<TrailerChoice>, val fetchedRecommendations: List<SearchResponse>?,
        val fetchedRuntime: Int?, val fetchedAgeRating: String?, val fetchedActors: List<ActorData>?,
        val fetchedYear: Int?, val fetchedStatus: ShowStatus?, val episodeTmdbMeta: Map<String, TmdbEpisode>,
        val fetchedOriginCountry: List<String>?,
        val fetchedOriginalTitle: String?,
        val fetchedEnglishTitle: String?,
        val fetchedVoteAverage: Double? = null,
        val fetchedLogoFallback: Boolean = false,
        val fetchedGenreIds: List<Int> = emptyList(),
        val fetchedPeople: List<ActorPersonRef> = emptyList()
    )

    private data class ProviderArtInfo(
        @JsonProperty("logo") val logo: String? = null
    )

    private data class CatalogEntry(
        @JsonProperty("name") val name: String? = null, 
        @JsonProperty("id") val id: String? = null, 
        @JsonProperty("moviedb_id") val moviedb_id: Int? = null,
        @JsonProperty("_tmdbId") val tmdbIdExt: String? = null, 
        @JsonProperty("_malId") val malIdExt: String? = null,
        @JsonProperty("_kitsuId") val kitsuIdExt: String? = null,
        @JsonProperty("poster") val poster: String? = null, 
        @JsonProperty("background") val background: String? = null, 
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("description") val description: String? = null, 
        @JsonProperty("runtime") val runtime: String? = null,
        @JsonProperty("imdbRating") val imdbRating: String? = null, 
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("original_language") val original_language: String? = null,
        @JsonProperty("_providerArt") val providerArt: ProviderArtInfo? = null,
        @JsonProperty("videos") val videos: List<Video>? = null, 
        @JsonProperty("genre") val genre: List<String>? = null, 
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("cast") val cast: List<String>? = null, 
        @JsonProperty("trailers") val trailersSources: List<Trailer>? = null,
        @JsonProperty("trailerStreams") val trailerStreams: List<TrailerStream>? = null, 
        @JsonProperty("links") val links: List<Link>? = null
    ) {

        fun toSearchResponse(
            provider: StremioC,
            typeName: String? = null
        ): SearchResponse {
            val targetType = this.type ?: typeName ?: "movie"
            val metaUrl = provider.getUnifiedMetaUrl(targetType, this.id ?: "")
            provider.rememberDetailLoadHint(
                url = metaUrl,
                type = targetType,
                id = this.id ?: "",
                tmdbId = this.moviedb_id?.toString() ?: this.tmdbIdExt
            )

            val baseName = if (!name.isNullOrBlank()) {
                name
            } else {
                id ?: ""
            }

            val finalName = baseName

            return provider.newMovieSearchResponse(finalName, metaUrl, TvType.Others) { 
                posterUrl = poster 
            }
        }

        private fun isSingleMovie(finalType: String?, finalVideos: List<Video>?): Boolean {
            return (finalType == "movie" && finalVideos?.size == 1 && finalVideos[0].seasonNumber == 1 && (finalVideos[0].episode == 1 || finalVideos[0].number == 1)) ||
                   (finalVideos?.size == 1 && (finalVideos[0].seasonNumber ?: 0) == 0 && (finalVideos[0].episode ?: finalVideos[0].number ?: 0) == 0)
        }

        private suspend fun fetchTmdbDetails(
            provider: StremioC, tmdbMediaType: String, tmdbIdStr: String, isMovie: Boolean, finalImdbId: String?,
            stremioType: String?, targetVideos: List<Video>?, preFetchedDetail: TmdbDetailResponse? = null,
            preFetchedCollection: Deferred<List<TmdbMedia>>? = null,
            recommendationPoster: String? = null,
            recommendationMalId: Int? = null
        ): FetchedTmdbData {
            
            var fetchedRecommendations: List<SearchResponse>? = null

            val detailAppend = if (isMovie) "release_dates,credits,images,videos,external_ids,recommendations,translations" else "content_ratings,credits,images,videos,external_ids,recommendations,translations"
            val videoLanguages = if (tmdbMediaType == "tv") arrayOf(
                "include_video_language" to tmdbTrailerLanguages().joinToString(",")
            ) else emptyArray()
            val detailUrl = provider.buildTmdbUrl("/$tmdbMediaType/$tmdbIdStr",
                "append_to_response" to detailAppend, "include_image_language" to tmdbLogoLanguages(),
                *videoLanguages)
            val cacheKey = detailUrl
            val cached = stremioCacheMemoryRead(tmdbDetailsCache[cacheKey], METADATA_CACHE_TTL) ?: MetadataDiskCache.read("detail", cacheKey)?.let { (json, stamp) ->
                runCatching { parseJson<TmdbDetailResponse>(json) }.getOrNull()?.let { value ->
                    Pair(value, stamp).also {
                    tmdbDetailsCache[cacheKey] = it
                }
                }
            }
            val detailRes = preFetchedDetail ?: if (cached != null) {
                cached.first
            } else {
                val text = run { fetchSharedMetadataText(detailUrl) }
                val fetched = run {
                    if (text.isNotBlank()) parseJson<TmdbDetailResponse>(text) else null
                }
                if (fetched != null) {
                    tmdbDetailsCache[cacheKey] = Pair(fetched, System.currentTimeMillis())
                    MetadataDiskCache.write("detail", cacheKey, text)
                } else {
                }
                fetched
            }

            if (detailRes == null) {
                
                return FetchedTmdbData(null, null, null, emptyList(), null, null, null, null, null, null, emptyMap(), emptyList(), null, null, null)
            }

            val simklJob = StremioC.requestSimklSuggestions(
                tmdbMediaType, tmdbIdStr,
                finalImdbId?.takeIf { StremioC.IMDB_ID_REGEX.matches(it) }
                    ?: detailRes.external_ids?.imdb_id,
                recommendationMalId
            )

            val episodeTmdbMeta = ConcurrentHashMap<String, TmdbEpisode>()

            val context = CloudStreamApp.context
            val prefs = context?.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
            val langCode = prefs?.getString("tmdb_language_code", "en-US") ?: "en-US"
            val metadataHost = provider.metadataPosterAddonBaseUrl()?.toHttpUrlOrNull()?.host

            coroutineScope {
                val shouldFocusEpisodes = (!isMovie && targetVideos.isNullOrEmpty()) || (stremioType != "movie" && stremioType != "series" && !isMovie)
                val episodeDeferred = if (shouldFocusEpisodes) {
                    var requiredSeasons = targetVideos?.mapNotNull { it.seasonNumber }?.filter { it > 0 }?.distinct() ?: emptyList()
                    if (requiredSeasons.isEmpty()) {
                        val totalSeasons = detailRes.number_of_seasons ?: 1
                        requiredSeasons = if (totalSeasons > 0) (1..totalSeasons).toList() else listOf(1)
                    }
                    requiredSeasons.map { seasonNum ->
                        async(Dispatchers.IO) {
                            run {
                            run {
                            StremioC.networkSemaphore.withPermit {
                                val completed = withTimeoutOrNull(10000L) {
                                    runSuspendCatching {
                                        val seasonUrl = provider.buildTmdbUrl("/tv/$tmdbIdStr/season/$seasonNum")
                                        val seasonCacheKey = seasonUrl
                                        val cachedSeason = stremioCacheMemoryRead(tmdbSeasonCache[seasonCacheKey], SEASON_CACHE_TTL) ?: MetadataDiskCache.read("season", seasonCacheKey)?.let { (json, stamp) ->
                                            runCatching { parseJson<TmdbSeasonDetail>(json) }.getOrNull()?.let { value ->
                                                Pair(value, stamp).also {
                                                    tmdbSeasonCache[seasonCacheKey] = it
                                                }
                                            }
                                        }
                                        val seasonRes = if (cachedSeason != null) {
                                            cachedSeason.first
                                        } else {
                                            val fetchedSeasonText = run {
                                                fetchSharedMetadataText(seasonUrl)
                                            }
                                            val fetchedSeason = run {
                                                if (fetchedSeasonText.isNotBlank()) parseJson<TmdbSeasonDetail>(fetchedSeasonText) else null
                                            }
                                            if (fetchedSeason != null) {
                                                tmdbSeasonCache[seasonCacheKey] = Pair(fetchedSeason, System.currentTimeMillis())
                                                MetadataDiskCache.write("season", seasonCacheKey, fetchedSeasonText)
                                            } else {
                                            }
                                            fetchedSeason
                                        }
                                        seasonRes?.episodes?.forEach { ep -> if (ep.episodeNumber != null) episodeTmdbMeta["${seasonNum}_${ep.episodeNumber}"] = ep }
                                    }
                                    true
                                }
                            }
                            }
                            }
                        }
                    }
                } else null

                val collectionDeferred = preFetchedCollection ?: async(Dispatchers.IO) {
                    val collectionId = detailRes.belongs_to_collection?.id
                    val active = isMovie && collectionId != null
                    val parts: List<TmdbMedia> = if (active) {
                        runSuspendCatching {
                            val colUrl = provider.buildTmdbUrl("/collection/$collectionId")
                            val colText = run {
                                fetchSharedMetadataText(colUrl)
                            }
                            run {
                                if (colText.isNotBlank()) parseJson<TmdbCollectionDetail>(colText).parts ?: emptyList() else emptyList()
                            }
                        }.getOrDefault(emptyList())
                    } else emptyList()
                    parts
                }

                val collectionParts = run { collectionDeferred.await() }
                run { episodeDeferred?.awaitAll() }

                // No per-recommendation Simkl or TMDB detail calls: use lightweight rows
                // from the two existing responses and keep collection ordering native.
                data class SuggestedCard(
                    val type: String, val id: String, val tmdbId: Int?, var name: String,
                    var poster: String?, var fallbackPoster: String? = null,
                    var collectionDate: String? = null, var collectionIndex: Int? = null,
                    var tmdbRank: Int? = null, var simklRank: Int? = null,
                    var sameOrigin: Boolean = false
                )
                val candidates = linkedMapOf<String, SuggestedCard>()
                fun addTmdb(media: TmdbMedia, collectionIndex: Int?, rank: Int?) {
                    val id = media.id?.takeIf { it > 0 && it.toString() != tmdbIdStr } ?: return
                    val movie = media.mediaType == "movie" ||
                        (media.mediaType != "tv" && (media.title != null || tmdbMediaType == "movie"))
                    val type = if (movie) "movie" else "series"
                    val key = "$type:tmdb:$id"
                    val title = media.title ?: media.name ?: media.originalTitle ?: return
                    val poster = media.poster_path?.takeIf(String::isNotBlank)
                        ?.let { "https://image.tmdb.org/t/p/w500$it" }
                    val card = candidates.getOrPut(key) {
                        SuggestedCard(type, "tmdb:$id", id, title, poster)
                    }
                    if (card.poster == null) card.poster = poster
                    if (collectionIndex != null) {
                        card.collectionIndex = collectionIndex
                        card.collectionDate = media.releaseDate ?: media.firstAirDate ?: "9999-12-31"
                    }
                    if (rank != null) card.tmdbRank = rank
                    if ((!media.original_language.isNullOrBlank() &&
                            media.original_language == detailRes.original_language) ||
                        media.origin_country.orEmpty().any { it in detailRes.origin_country.orEmpty() })
                        card.sameOrigin = true
                }
                collectionParts.forEachIndexed { idx, media -> addTmdb(media, idx, null) }
                detailRes.recommendations?.results.orEmpty().forEachIndexed { idx, media ->
                    addTmdb(media, null, idx)
                }
                val awaitedSimklRows = withTimeoutOrNull(StremioC.SIMKL_RECOMMENDATION_WAIT_MS) {
                    try {
                        simklJob?.await() ?: emptyList()
                    } catch (e: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw e
                        emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val simklRows = awaitedSimklRows.orEmpty()
                
                simklRows.forEach { rec ->
                    if (rec.tmdbId?.toString() == tmdbIdStr) return@forEach
                    val key = rec.type + ":" + (rec.tmdbId?.let { "tmdb:$it" } ?: rec.id)
                    val card = candidates.getOrPut(key) {
                        SuggestedCard(rec.type, rec.id, rec.tmdbId, rec.name, rec.poster)
                    }
                    card.simklRank = rec.rank
                    card.fallbackPoster = rec.poster
                }
                val ordered = candidates.values.sortedWith(
                    compareByDescending<SuggestedCard> { it.collectionIndex != null }
                        .thenBy { it.collectionDate ?: "" }
                        .thenByDescending {
                            (if (it.simklRank != null) 2.0 else 0.0) +
                            (if (it.tmdbRank != null) 1.7 else 0.0) +
                            (if (it.sameOrigin) 0.5 else 0.0) +
                            (it.simklRank?.let { n -> (30 - n.coerceAtMost(30)) / 300.0 } ?: 0.0) +
                            (it.tmdbRank?.let { n -> (30 - n.coerceAtMost(30)) / 300.0 } ?: 0.0)
                        }
                        .thenBy { it.collectionIndex ?: Int.MAX_VALUE }
                        .thenBy { it.tmdbRank ?: it.simklRank ?: Int.MAX_VALUE }
                ).take(50)
                fetchedRecommendations = ordered.map { rec ->
                    val fallback = rec.poster ?: rec.fallbackPoster ?: TRANSPARENT_PIXEL
                    val poster = rec.tmdbId?.let {
                        StremioC.recommendationPosterUrl(
                            recommendationPoster, it, rec.type == "movie", fallback, metadataHost
                        )
                    } ?: fallback
                    val thinData = LoadData(id = rec.id, type = rec.type, addonUrl = provider.mainUrl)
                    provider.newMovieSearchResponse(
                        rec.name, thinData.toJson(),
                        if (rec.type == "movie") TvType.Movie else TvType.TvSeries
                    ) { this.posterUrl = poster }
                }
                
            }

            val countryCode = langCode.substringAfter("-", "KR").uppercase()

            val fetchedAgeRating = if (isMovie) {
                detailRes.release_dates?.results?.firstOrNull { it.iso_3166_1 == countryCode }?.release_dates?.firstOrNull { !it.certification.isNullOrEmpty() }?.certification
                    ?: detailRes.release_dates?.results?.firstOrNull { it.iso_3166_1 == "US" }?.release_dates?.firstOrNull { !it.certification.isNullOrEmpty() }?.certification
            } else {
                detailRes.content_ratings?.results?.firstOrNull { it.iso_3166_1 == countryCode }?.rating
                    ?: detailRes.content_ratings?.results?.firstOrNull { it.iso_3166_1 == "US" }?.rating
            }

            val crewPairs = detailRes.credits?.crew?.filter { it.job == "Director" || it.job == "Writer" }
                ?.groupBy { it.id?.takeIf { id -> id > 0 }?.let { id -> "id:$id" }
                    ?: "name:" + (it.name ?: it.originalName).orEmpty() }
                ?.values?.mapNotNull { roles ->
                    val head = roles.first()
                    val name = head.name ?: head.originalName ?: return@mapNotNull null
                    val jobs = roles.mapNotNull { it.job }.distinct()
                        .sortedBy { if (it == "Director") 1 else 2 }.joinToString(", ")
                    val img = roles.firstNotNullOfOrNull { it.profilePath }
                        ?.let { provider.getActorUrl(it) } ?: TRANSPARENT_PIXEL
                    ActorData(Actor(name, img), roleString = jobs) to
                        ActorPersonRef(head.id, name, img, true)
                }?.sortedBy { (item, _) ->
                    if (item.roleString?.contains("Director, Writer") == true) 1
                    else if (item.roleString?.contains("Director") == true) 2 else 3
                } ?: emptyList()
            val castPairs = detailRes.credits?.cast?.mapNotNull { cast ->
                val actorName = cast.name ?: cast.originalName ?: return@mapNotNull null
                val img = cast.profilePath?.let { provider.getActorUrl(it) } ?: TRANSPARENT_PIXEL
                ActorData(Actor(actorName, img), roleString = cast.character) to
                    ActorPersonRef(cast.id, actorName, img, false)
            } ?: emptyList()
            val actorPairs = crewPairs + castPairs

            val trailerLanguages = tmdbTrailerLanguages()
            val rankedTrailers = detailRes.videos?.results.orEmpty()
                .filter { (it.type == "Trailer" || it.type == "Teaser") && it.site.equals("YouTube", true) }
                .mapNotNull { item ->
                    val rank = trailerLanguages.indexOfFirst { locale ->
                        item.language.equals(locale.substringBefore('-'), true) &&
                            (!locale.contains('-') || item.region.equals(locale.substringAfter('-'), true))
                    }
                    if (rank < 0 || parseTrailerYoutubeId(item.key) == null) null else rank to item
                }
            val bestLanguage = rankedTrailers.minOfOrNull { it.first }
            val fetchedTrailers = rankedTrailers.asSequence()
                .filter { it.first == bestLanguage }
                .map { it.second }
                .sortedWith(compareBy({ if (it.type == "Trailer") 0 else 1 }, { it.publishedAt ?: "9999-12-31" }))
                .mapNotNull { item ->
                    parseTrailerYoutubeId(item.key)?.let { id ->
                        TrailerChoice(id, item.name?.takeIf { it.isNotBlank() } ?: (item.type ?: "Trailer"),
                            item.type ?: "Trailer", item.language)
                    }
                }.distinctBy { it.youtubeId }.take(30).toList()

            val translationData = detailRes.translations?.translations?.firstOrNull { 
                it.iso_3166_1 == "US" || it.iso_639_1 == "en" 
            }?.data
            val enTranslation = (translationData?.title ?: translationData?.name)?.takeIf { it.isNotBlank() }

            val extractedEnglishTitle = if (detailRes.original_language == "en") {
                detailRes.original_title ?: detailRes.original_name
            } else {
                enTranslation ?: detailRes.original_title ?: detailRes.original_name
            }

            val preferred = langCode.substringBefore('-').lowercase()
            val selectedLogo = detailRes.images?.logos.orEmpty()
                .filter { logo ->
                    val language = logo.iso_639_1?.lowercase()
                    !logo.file_path.isNullOrBlank() && (language == preferred || language == "en")
                }
                .maxWithOrNull(compareBy<TmdbLogo> {
                    when (it.iso_639_1?.lowercase()) { preferred -> 3; "en" -> 2; else -> 1 }
                }.thenBy { it.vote_average ?: 0.0 })

            return FetchedTmdbData(
                if (isMovie) detailRes.title ?: detailRes.original_title else detailRes.name ?: detailRes.original_name,
                detailRes.overview, selectedLogo?.file_path?.let { path ->

                    val rasterPath = if (path.endsWith(".svg", ignoreCase = true))
                        path.dropLast(4) + ".png" else path
                    "https://image.tmdb.org/t/p/w500$rasterPath"
                },
                fetchedTrailers, fetchedRecommendations, if (isMovie) detailRes.runtime else null, fetchedAgeRating, actorPairs.map { it.first },
                (detailRes.releaseDate ?: detailRes.firstAirDate)?.split("-")?.first()?.toIntOrNull(),
                if (detailRes.status == "Returning Series") ShowStatus.Ongoing else ShowStatus.Completed, episodeTmdbMeta, detailRes.origin_country,
                detailRes.original_title ?: detailRes.original_name,
                extractedEnglishTitle,
                detailRes.vote_average,
                selectedLogo != null && selectedLogo.iso_639_1?.lowercase() != preferred,
                detailRes.genres.orEmpty().mapNotNull { it.id },
                actorPairs.map { it.second }
            )
        }

        private fun processEpisodes(
            provider: StremioC, 
            finalImdbId: String?, 
            episodeTmdbMeta: Map<String, TmdbEpisode>, 
            targetVideos: List<Video>?, 
            type: String?, 
            showTitle: String?, 
            tmdbId: Int?, 
            isAnime: Boolean, 
            isBollywood: Boolean, 
            isAsian: Boolean, 
            isCartoon: Boolean, 
            originalTitle: String?, 
            englishTitle: String?, 
            aniListId: Int?, 
            malId: Int?,
            kitsuId: String?,
            year: Int? = null, 
            originalAnimeId: String? = null
        ): List<Episode> {
            val videos = targetVideos ?: emptyList()

            val maxRegularSeason = videos.mapNotNull { it.seasonNumber }.filter { it > 0 }.maxOrNull() ?: 0
            val targetSeasonForZero = maxRegularSeason + 1

            val videosByDisplaySeason = videos.groupBy { 
                val s = it.seasonNumber ?: 0
                if (s == 0) targetSeasonForZero else s
            }

            val processedEpisodes = mutableListOf<Episode>()

            for ((displaySeason, seasonVideos) in videosByDisplaySeason) {
                val normalEpisodes = seasonVideos.filter { (it.episode ?: it.number ?: 0) > 0 }
                    .sortedBy { it.episode ?: it.number ?: 0 }

                val zeroEpisodes = seasonVideos.filter { (it.episode ?: it.number ?: 0) <= 0 }
                    .sortedBy { it.title ?: it.name ?: "" }

                val uniqueEpisodeChecker = mutableSetOf<Int>()
                val finalSeasonVideos = mutableListOf<Pair<Video, Int>>()

                var maxEpisodeNum = 0
                for (video in normalEpisodes) {
                    val epNum = video.episode ?: video.number ?: 1
                    maxEpisodeNum = maxOf(maxEpisodeNum, epNum)
                    uniqueEpisodeChecker.add(epNum)
                    finalSeasonVideos.add(video to epNum)
                }

                var nextZeroEpNum = maxEpisodeNum + 1
                for (video in zeroEpisodes) {
                    while (uniqueEpisodeChecker.contains(nextZeroEpNum)) {
                        nextZeroEpNum++
                    }
                    uniqueEpisodeChecker.add(nextZeroEpNum)
                    finalSeasonVideos.add(video to nextZeroEpNum)
                    maxEpisodeNum = maxOf(maxEpisodeNum, nextZeroEpNum)
                }

                for ((video, displayEpisode) in finalSeasonVideos) {
                    val imdbSeason = if (originalAnimeId != null) video.imdbSeason
                        else video.imdbSeason ?: video.seasonNumber
                    val imdbEpisode = if (originalAnimeId != null) video.imdbEpisode
                        else video.imdbEpisode ?: video.episode ?: video.number

                    val rawEpImdbId = video.imdb_id ?: finalImdbId
                    val epImdbId = rawEpImdbId?.let { idStr ->
                        val parts = idStr.split(":")
                        if (provider.isLibraryAnimeId(idStr) || idStr.startsWith("tmdb:")) {
                            if (parts.size >= 2) "${parts[0]}:${parts[1]}" else idStr
                        } else {
                            parts[0]
                        }
                    }

                    val ep = video.toEpisode(
                        provider, type, epImdbId, episodeTmdbMeta, 
                        displaySeason, displayEpisode, showTitle, tmdbId, 
                        isAnime, isBollywood, isAsian, isCartoon, 
                        imdbSeason, imdbEpisode, originalTitle, englishTitle, 
                        aniListId, malId, kitsuId,
                        provider.mainUrl,
                        year, 
                        originalAnimeId
                    )
                    processedEpisodes.add(ep)
                }
            }

            return processedEpisodes.sortedWith(compareBy({ it.season }, { it.episode }))
        }

        suspend fun toLoadResponse(
            provider: StremioC, 
            imdbId: String?, 
            addonDeferred: Deferred<CatalogEntry?>?, 
            preFetchedDetail: TmdbDetailResponse? = null,
            preFetchedDetailTmdbId: String? = null,
            preFetchedDetailMediaType: String? = null,
            preFetchedCollection: Deferred<List<TmdbMedia>>? = null,
            preFetchedFind: Pair<String, String>? = null,
            preFetchedFindImdbId: String? = null,
            fetchedAniListId: Int? = null, 
            fetchedMalId: Int? = null,
            fetchedKitsuId: String? = null,
            resolvedTmdbId: String? = null,
            resolvedIdHolder: Array<String?>? = null,
            requestedAnimeId: String? = null,
            prefetchedAnimeMapping: Deferred<AnimeSeasonMapping?>? = null,
            prefetchedAnimeImdbId: String? = null
        ): LoadResponse = coroutineScope {
            val syncedEntry = run { addonDeferred?.await() }
            val detailedEntry = syncedEntry ?: this@CatalogEntry
            val recommendationPoster = if (provider.metadataPosterAddonBaseUrl() != null &&
                (addonDeferred == null || syncedEntry != null)) detailedEntry.poster else null
            val originalAnimeId = requestedAnimeId
                ?: this@CatalogEntry.id?.takeIf { provider.isLibraryAnimeId(it) }

            var finalImdbId = if (this@CatalogEntry.id?.startsWith("tt") == true) this@CatalogEntry.id 
                              else imdbId?.takeIf { it.startsWith("tt") || provider.isLibraryAnimeId(it) }

            if (finalImdbId == null && detailedEntry.links?.isNotEmpty() == true) {
                finalImdbId = detailedEntry.links.firstOrNull { it.category == "imdb" }?.url?.substringAfterLast("/")?.takeIf { it.startsWith("tt") }
            }
            if (finalImdbId == null) {
                finalImdbId = provider.extractImdbId(detailedEntry.logo) ?: provider.extractImdbId(detailedEntry.poster) ?: provider.extractImdbId(detailedEntry.background)
            }

            var tmdbIdStr: String? = resolvedTmdbId ?: if (this@CatalogEntry.id?.startsWith("tmdb:") == true) this@CatalogEntry.id.removePrefix("tmdb:") else this@CatalogEntry.moviedb_id?.toString() ?: this@CatalogEntry.tmdbIdExt ?: detailedEntry.tmdbIdExt

            var foundMediaType: String? = null
            if (tmdbIdStr == null && finalImdbId?.startsWith("tt") == true && preFetchedFind != null && preFetchedFindImdbId == finalImdbId) {
                tmdbIdStr = preFetchedFind.first
                foundMediaType = preFetchedFind.second
            }
            val findApiDeferred: Deferred<Pair<String, String>?>? = if (tmdbIdStr == null && finalImdbId?.startsWith("tt") == true) {
                async(Dispatchers.IO) {
                    provider.fetchTmdbFindForLoad(finalImdbId)
                }
            } else null

            var finalVideos = detailedEntry.videos ?: this@CatalogEntry.videos
            val finalTrailersSources = detailedEntry.trailersSources ?: this@CatalogEntry.trailersSources
            val finalTrailerStreams = detailedEntry.trailerStreams ?: this@CatalogEntry.trailerStreams
            val originalName = detailedEntry.name ?: this@CatalogEntry.name
            val finalGenre = detailedEntry.genre ?: detailedEntry.genres ?: this@CatalogEntry.genre ?: this@CatalogEntry.genres
            val genreTargets = (detailedEntry.links.orEmpty() + this@CatalogEntry.links.orEmpty())
                .mapNotNull { link ->
                    val name = link.name?.takeIf { it in finalGenre.orEmpty() }
                        ?: return@mapNotNull null
                    if (!link.category.equals("Genres", ignoreCase = true)) return@mapNotNull null
                    val target = runCatching {
                        val uri = android.net.Uri.parse(link.url)
                        val parts = uri.pathSegments
                        if (uri.scheme != "stremio" || parts.size != 4 ||
                            parts[0] != "discover" || uri.getQueryParameter("genre") != name) null
                        else {
                            val manifest = android.net.Uri.parse(parts[1])
                            if (manifest.scheme !in listOf("http", "https") ||
                                manifest.host.isNullOrBlank() ||
                                manifest.authority.orEmpty().contains("@") ||
                                !manifest.path.orEmpty().endsWith("/manifest.json") ||
                                parts[2].isBlank() || parts[3].isBlank()) null
                            else CatalogShortcutTarget(parts[1], parts[2], parts[3], name)
                        }
                    }.getOrNull()
                    target?.let { name to it }
                }.toMap()
            val finalPoster = detailedEntry.poster ?: this@CatalogEntry.poster
            val finalBackground = detailedEntry.background ?: this@CatalogEntry.background
            val finalImdbRating = detailedEntry.imdbRating ?: this@CatalogEntry.imdbRating
            val finalType = detailedEntry.type ?: this@CatalogEntry.type

            val videoSeasons = finalVideos.orEmpty().mapNotNull {
                it.seasonNumber?.takeIf { number -> number > 0 }
            }.distinct()
            val isMovieForSync = finalType == "movie" || isSingleMovie(finalType ?: "", finalVideos)

            val canSyncAnimeTrackers = isMovieForSync ||
                ((finalType == "series" || finalType == "tv") && videoSeasons.singleOrNull() == 1)
            val useImdbSyncMapping = (originalAnimeId == null ||
                (originalAnimeId.startsWith("anilist:") &&
                    fetchedAniListId == null && fetchedMalId == null && fetchedKitsuId == null)) &&
                finalImdbId?.let { IMDB_ID_REGEX.matches(it) } == true && canSyncAnimeTrackers
            val detailSyncMapping = if (useImdbSyncMapping) {
                if (prefetchedAnimeImdbId != finalImdbId) prefetchedAnimeMapping?.cancel()
                prefetchedAnimeMapping?.takeIf { prefetchedAnimeImdbId == finalImdbId }
                    ?: async(Dispatchers.IO) {
                        provider.fetchAnimeSeasonMapping(LoadData(
                            id = finalImdbId, imdbId = finalImdbId, isAnime = true
                        ))
                    }
            } else {
                prefetchedAnimeMapping?.cancel()
                null
            }

            if (findApiDeferred != null) {
                val findResult = run { findApiDeferred.await() }
                if (findResult != null) { tmdbIdStr = findResult.first; foundMediaType = findResult.second }
            }

            var tmdbData: FetchedTmdbData? = null
            if (tmdbIdStr != null) {
                try {
                    val refinedMediaType = foundMediaType ?: if (finalType == "series" || finalType == "tv") "tv" else if (isSingleMovie(finalType, finalVideos) || finalType == "movie" || finalVideos.isNullOrEmpty()) "movie" else "tv"
                    val safePrefetchedDetail = preFetchedDetail?.takeIf {
                        preFetchedDetailTmdbId == tmdbIdStr && preFetchedDetailMediaType == refinedMediaType
                    }
                    tmdbData = run {
                        fetchTmdbDetails(provider, refinedMediaType, tmdbIdStr!!,
                            refinedMediaType == "movie", finalImdbId, finalType, finalVideos,
                            safePrefetchedDetail, if (safePrefetchedDetail != null) preFetchedCollection else null,
                            recommendationPoster,
                            fetchedMalId ?: originalAnimeId?.takeIf { it.startsWith("mal:") }
                                ?.substringAfter(':')?.toIntOrNull())
                    }
                } catch (e: Exception) {
                }
            }

            var finalName = originalName ?: this@CatalogEntry.name ?: "Unknown Content"
            var finalDescription = detailedEntry.description ?: this@CatalogEntry.description

            if (tmdbData != null) {
                if (!tmdbData.fetchedTitle.isNullOrBlank()) finalName = tmdbData.fetchedTitle
                finalDescription = tmdbData.fetchedOverview?.takeIf { it.isNotBlank() } ?: finalDescription
            }

            if (finalType != "movie" && finalType != "series") {
                finalDescription = if (finalDescription.isNullOrBlank()) originalName else "$finalDescription<br>$originalName"
            }

            val movieId = originalAnimeId ?: this@CatalogEntry.kitsuIdExt?.let { "kitsu:$it" }
                ?: this@CatalogEntry.id ?: ""
            if (tmdbData != null && finalVideos.isNullOrEmpty() && tmdbData.episodeTmdbMeta.isNotEmpty()) {
                val generatedVideos = tmdbData.episodeTmdbMeta.mapNotNull { (key, tmdbEp) ->
                    val parts = key.split("_")
                    if (parts.size == 2) {
                        val s = parts[0].toIntOrNull() ?: return@mapNotNull null
                        val e = parts[1].toIntOrNull() ?: return@mapNotNull null
                        Video(
                            id = movieId, 
                            title = tmdbEp.name,
                            seasonNumber = s,
                            episode = e,
                            overview = tmdbEp.overview,
                            released = tmdbEp.airDate
                        )
                    } else null
                }
                if (generatedVideos.isNotEmpty()) {
                    finalVideos = generatedVideos
                }
            }

            val fallbackTrailers = finalTrailerStreams.orEmpty().mapNotNull { item ->
                parseTrailerYoutubeId(item.ytId)?.let { id ->
                    TrailerChoice(id, item.title?.takeIf { it.isNotBlank() } ?: "Trailer", "Trailer",
                        item.language ?: item.lang ?: item.isoLanguage)
                }
            }.ifEmpty {
                finalTrailersSources.orEmpty().mapNotNull { item ->
                    parseTrailerYoutubeId(item.source)?.let { id ->
                        TrailerChoice(id, item.type ?: "Trailer", item.type ?: "Trailer",
                            item.language ?: item.lang ?: item.isoLanguage)
                    }
                }
            }
            val finalTrailers = tmdbData?.fetchedTrailers.orEmpty().ifEmpty { fallbackTrailers }
                .distinctBy { it.youtubeId }.take(30)

            val countries = tmdbData?.fetchedOriginCountry ?: emptyList()
            val isBollywood = countries.any { it.contains("IN", true) || it.contains("India", true) }
            val isAsian = countries.any { it.contains("KR", true) || it.contains("JP", true) || it.contains("CN", true) || it.contains("TH", true) || it.contains("TW", true) }
            val isCartoon = tmdbData?.fetchedGenreIds?.contains(16) == true ||
                finalGenre?.any { it.contains("Animation", true) } == true
            val isAnimeFromMetadata = originalAnimeId != null || fetchedMalId != null || provider.isLibraryAnimeId(finalImdbId) || finalGenre?.contains("Anime") == true || (countries.any { it.contains("JP", true) } && isCartoon)
            val imdbSyncMapping = run { detailSyncMapping?.await() }
            val isAnimeMedia = isAnimeFromMetadata || imdbSyncMapping != null
            val originalTrackerId = originalAnimeId?.substringAfter(':')?.substringBefore(':')
                ?.toIntOrNull()?.takeIf { it > 0 }
            val finalMalId = imdbSyncMapping?.malId ?: fetchedMalId
                ?: originalTrackerId?.takeIf { originalAnimeId?.startsWith("mal:") == true }
            val finalAniId = imdbSyncMapping?.aniListId ?: fetchedAniListId
                ?: originalTrackerId?.takeIf { originalAnimeId?.startsWith("anilist:") == true }
            val finalKitsuId = imdbSyncMapping?.kitsuId?.toIntOrNull()?.takeIf { it > 0 }
                ?: fetchedKitsuId?.toIntOrNull()?.takeIf { it > 0 }
                ?: originalTrackerId?.takeIf { originalAnimeId?.startsWith("kitsu:") == true }
            val cleanImdbId = finalImdbId?.let { idStr ->
                val parts = idStr.split(":")
                if (provider.isLibraryAnimeId(idStr) || idStr.startsWith("tmdb:")) {
                    if (parts.size >= 2) "${parts[0]}:${parts[1]}" else idStr
                } else {
                    parts[0]
                }
            }
            val cleanTmdbId = tmdbIdStr?.substringBefore(":")

            resolvedIdHolder?.let {
                it[0] = cleanImdbId
                it[1] = cleanTmdbId
            }

            val finalRatingValue = finalImdbRating?.takeIf { it.isNotBlank() && it != "null" } 
                                   ?: tmdbData?.fetchedVoteAverage?.toString()

            val hasAnimeTrackerIds = finalMalId != null || finalAniId != null ||
                this@CatalogEntry.kitsuIdExt != null || finalKitsuId != null
            val finalMovieType = if (isAnimeMedia && hasAnimeTrackerIds) TvType.AnimeMovie else TvType.Movie
            val finalSeriesType = if (isAnimeMedia && hasAnimeTrackerIds) TvType.Anime else TvType.TvSeries
            val addonLogo = sequenceOf(detailedEntry.logo, detailedEntry.providerArt?.logo,
                this@CatalogEntry.logo, this@CatalogEntry.providerArt?.logo)
                .firstOrNull { !it.isNullOrBlank() }
            val finalLogo = tmdbData?.fetchedLogo?.takeIf { it.isNotBlank() } ?: addonLogo

            val finalLogoFallback = if (tmdbData?.fetchedLogo.isNullOrBlank())
                finalLogo != null else tmdbData?.fetchedLogoFallback == true

            fun LoadResponse.attachMappedIds() {
                val validImdb = cleanImdbId?.takeIf { it.startsWith("tt") }
                validImdb?.let { addImdbId(it) }
                if (validImdb == null) cleanTmdbId?.takeIf { it.isNotBlank() }?.let { addTMDbId(it) }

                if (this@CatalogEntry.id?.let { IMDB_ID_REGEX.matches(it) } != true || canSyncAnimeTrackers) {
                    finalAniId?.let { addAniListId(it) }
                    finalMalId?.let { addMalId(it) }
                    finalKitsuId?.let { syncData["kitsu"] = it.toString() }
                }
                apiName = provider.getTargetApiName()

                trailers = NoNativeTrailerPreview()
            }

            if (finalVideos.isNullOrEmpty() || isSingleMovie(finalType, finalVideos)) {
                val metaUrl = provider.getUnifiedMetaUrl(finalType ?: "movie", this@CatalogEntry.id ?: "")
                StremioC.fallbackLogoTitles[metaUrl] = finalLogoFallback
                StremioC.expectedLogoByUrl[metaUrl] = !finalLogo.isNullOrBlank()
                StremioC.animeAudioByUrl[metaUrl] = isAnimeMedia
                StremioC.episodeBasedByUrl[metaUrl] = false
                if (isAnimeMedia && !tmdbData?.fetchedActors.isNullOrEmpty()) {
                    StremioC.animeFallbackActorsByUrl[metaUrl] = tmdbData!!.fetchedActors!!
                } else StremioC.animeFallbackActorsByUrl.remove(metaUrl)
                StremioC.seasonEpisodeCountsByUrl[metaUrl] = finalVideos.orEmpty()
                    .mapNotNull { video -> video.seasonNumber?.takeIf { it > 0 }?.let { it to video } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, videos) -> videos.size }
                StremioC.trailerChoicesByUrl[metaUrl] = finalTrailers
                StremioC.genreCatalogsByUrl[metaUrl] = genreTargets
                val resolvedMovieId = if (finalType == "other" && finalVideos?.size == 1) {
                    finalVideos[0].id ?: movieId
                } else {
                    movieId
                }

                val response = run { provider.newMovieLoadResponse(
                    finalName, metaUrl, finalMovieType, 
                    LoadData(
                        type = finalType, 
                        id = resolvedMovieId, 
                        imdbId = cleanImdbId, 
                        year = tmdbData?.fetchedYear, 
                        title = finalName, 
                        tmdbId = cleanTmdbId?.toIntOrNull(), 
                        isAnime = isAnimeMedia,
                        isBollywood = isBollywood,
                        isAsian = isAsian,
                        isCartoon = isCartoon,
                        anilistId = finalAniId,
                        malId = finalMalId,
                        kitsuId = finalKitsuId?.toString() ?: this@CatalogEntry.kitsuIdExt,
                        originalTitle = tmdbData?.fetchedOriginalTitle,
                        englishTitle = tmdbData?.fetchedEnglishTitle,
                        addonUrl = provider.mainUrl,
                        originalAnimeId = originalAnimeId,
                        embeddedStreams = finalVideos?.singleOrNull()?.embeddedStreamsJson(),
                        embeddedStreamTitle = finalVideos?.singleOrNull()?.let { it.title ?: it.name }
                    )
                ) {
                    posterUrl = finalPoster; backgroundPosterUrl = finalBackground; score = Score.from10(finalRatingValue); year = tmdbData?.fetchedYear
                    this.plot = finalDescription
                    this.actors = if (isAnimeMedia) null else tmdbData?.fetchedActors
                    this.tags = finalGenre
                    this.recommendations = tmdbData?.fetchedRecommendations 
                    this.duration = stremioRuntimeMinutes(this@CatalogEntry.runtime)
                        ?: finalVideos?.singleOrNull()?.runtime?.let(::stremioRuntimeMinutes)
                        ?: tmdbData?.fetchedRuntime
                    this.contentRating = tmdbData?.fetchedAgeRating
                    finalLogo?.let { this.logoUrl = it }

                } }
                if (!isAnimeMedia) {
                    ActorInformation.register(
                        provider, metaUrl, tmdbData?.fetchedActors, tmdbData?.fetchedPeople.orEmpty(),
                        recommendationPoster, provider.metadataPosterAddonBaseUrl()?.toHttpUrlOrNull()?.host
                    )
                }
                return@coroutineScope run { response.apply { attachMappedIds() } }

            } else {
                val representativeRunTime = stremioRuntimeMinutes(this@CatalogEntry.runtime)
                val finalEpisodes = run { processEpisodes(
                    provider, 
                    cleanImdbId, 
                    tmdbData?.episodeTmdbMeta ?: emptyMap(), 
                    finalVideos, 
                    finalType, 
                    finalName, 
                    cleanTmdbId?.toIntOrNull(), 
                    isAnimeMedia, 
                    isBollywood, 
                    isAsian, 
                    isCartoon, 
                    tmdbData?.fetchedOriginalTitle, 
                    tmdbData?.fetchedEnglishTitle, 
                    finalAniId, 
                    finalMalId, 
                    finalKitsuId?.toString() ?: this@CatalogEntry.kitsuIdExt,
                    tmdbData?.fetchedYear, 
                    originalAnimeId
                ) }.onEach { episode ->
                    if (episode.runTime == null) episode.runTime = representativeRunTime
                }
                val metaUrl = provider.getUnifiedMetaUrl(finalType ?: "series", this@CatalogEntry.id ?: "")
                StremioC.fallbackLogoTitles[metaUrl] = finalLogoFallback
                StremioC.expectedLogoByUrl[metaUrl] = !finalLogo.isNullOrBlank()
                StremioC.animeAudioByUrl[metaUrl] = isAnimeMedia
                StremioC.episodeBasedByUrl[metaUrl] = true
                if (isAnimeMedia && !tmdbData?.fetchedActors.isNullOrEmpty()) {
                    StremioC.animeFallbackActorsByUrl[metaUrl] = tmdbData!!.fetchedActors!!
                } else StremioC.animeFallbackActorsByUrl.remove(metaUrl)
                StremioC.seasonEpisodeCountsByUrl[metaUrl] = finalVideos.orEmpty()
                    .mapNotNull { video -> video.seasonNumber?.takeIf { it > 0 }?.let { it to video } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, videos) -> videos.size }
                StremioC.trailerChoicesByUrl[metaUrl] = finalTrailers
                StremioC.genreCatalogsByUrl[metaUrl] = genreTargets
                val response = run { provider.newTvSeriesLoadResponse(
                    finalName, metaUrl, finalSeriesType, finalEpisodes
                ) {
                    posterUrl = finalPoster; backgroundPosterUrl = finalBackground; score = Score.from10(finalRatingValue); year = tmdbData?.fetchedYear
                    this.showStatus = tmdbData?.fetchedStatus 
                    this.plot = finalDescription
                    this.actors = if (isAnimeMedia) null else tmdbData?.fetchedActors
                    this.tags = finalGenre
                    this.recommendations = tmdbData?.fetchedRecommendations
                    this.contentRating = tmdbData?.fetchedAgeRating
                    finalLogo?.let { this.logoUrl = it }

                } }
                if (!isAnimeMedia) {
                    ActorInformation.register(
                        provider, metaUrl, tmdbData?.fetchedActors, tmdbData?.fetchedPeople.orEmpty(),
                        recommendationPoster, provider.metadataPosterAddonBaseUrl()?.toHttpUrlOrNull()?.host
                    )
                }
                return@coroutineScope run { response.apply { attachMappedIds() } }
            }           
        }
    }

    private data class Video(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season") val seasonNumber: Int? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("firstAired") val firstAired: String? = null,
        @JsonProperty("released") val released: String? = null,
        @JsonProperty("runtime") val runtime: String? = null,
        @JsonProperty("tvdb_id") val tvdb_id: Int? = null,
        @JsonProperty("imdbSeason") val imdbSeason: Int? = null,
        @JsonProperty("imdbEpisode") val imdbEpisode: Int? = null,
        @JsonProperty("imdb_id") val imdb_id: String? = null,
        @JsonProperty("streams") val streams: JsonNode? = null
    ) {
        fun embeddedStreamsJson(): String? = streams?.takeIf { it.isArray && it.size() > 0 }
            ?.let { runCatching { it.toString() }.getOrNull() }

        fun toEpisode(
            provider: StremioC, 
            type: String?, 
            imdbId: String?, 
            tmdbMetaMap: Map<String, TmdbEpisode>, 
            displaySeason: Int, 
            displayEpisode: Int, 
            showTitle: String?, 
            tmdbId: Int?, 
            isAnime: Boolean, 
            isBollywood: Boolean, 
            isAsian: Boolean, 
            isCartoon: Boolean, 
            imdbSeason: Int?, 
            imdbEpisode: Int?, 
            originalTitle: String?, 
            englishTitle: String?, 
            aniListId: Int?, 
            malId: Int?, 
            kitsuId: String?, 
            addonUrl: String? = null,
            year: Int? = null, 
            originalAnimeId: String? = null
        ): Episode { 
            val tmdbEp = tmdbMetaMap["${displaySeason}_${displayEpisode}"] ?: tmdbMetaMap["${seasonNumber ?: 0}_${episode ?: number ?: 0}"]
            val finalAirDate = tmdbEp?.airDate?.takeIf { it.isNotBlank() } ?: this@Video.firstAired ?: this@Video.released
            val airedYearInt = finalAirDate?.split("-")?.first()?.toIntOrNull()

            return provider.newEpisode(LoadData(
                type = type, 
                id = id, 
                season = displaySeason, 
                episode = displayEpisode, 
                imdbId = imdbId, 
                year = year, 
                title = showTitle, 
                tmdbId = tmdbId, 
                isAnime = isAnime,
                isBollywood = isBollywood,
                isAsian = isAsian,
                isCartoon = isCartoon,
                firstAired = finalAirDate, 
                airedYear = airedYearInt, 
                imdbSeason = imdbSeason, 
                imdbEpisode = imdbEpisode,
                originalTitle = originalTitle,
                englishTitle = englishTitle,
                anilistId = aniListId,
                malId = malId,
                kitsuId = kitsuId,
                addonUrl = addonUrl,
                originalAnimeId = originalAnimeId,
                embeddedStreams = embeddedStreamsJson(),
                embeddedStreamTitle = title ?: name
            )) {
                val rawTitle = this@Video.name ?: this@Video.title ?: "Episode $displayEpisode"
                val cleanOriginalTitle = if (rawTitle.contains("📄")) rawTitle.substringAfter("📄").replace("\n", "").trim() else rawTitle.replace("\n", "").trim()

                this.name = if (tmdbEp?.name != null) tmdbEp.name else cleanOriginalTitle
                this.posterUrl = thumbnail ?: TRANSPARENT_PIXEL

                var finalEpDesc = (tmdbEp?.overview ?: this@Video.overview ?: this@Video.description)?.takeIf { it != "null" }
                if (type != "movie" && type != "series") {
                    finalEpDesc = if (finalEpDesc.isNullOrBlank()) cleanOriginalTitle else "$finalEpDesc<br>$cleanOriginalTitle"
                }

                this.description = finalEpDesc
                this.season = displaySeason
                this.episode = displayEpisode
                this.runTime = stremioRuntimeMinutes(this@Video.runtime)
                    ?: tmdbEp?.runtime?.takeIf { it > 0 }

                finalAirDate?.takeIf { it.isNotBlank() }?.let { this.addDate(it) }
            }
        }
    }

    private data class StreamsResponse(@JsonProperty("streams") val streams: List<Stream>)    
    private data class SubtitlesResponse(@JsonProperty("subtitles") val subtitles: List<Subtitle>?)
    private data class Subtitle(
        @JsonProperty("url") val url: String?,
        @JsonProperty("lang") val lang: String?,
        @JsonProperty("lang_code") val lang_code: String?,
        @JsonProperty("id") val id: String?,
        @JsonProperty("label") val label: JsonNode? = null,
        @JsonProperty("name") val name: JsonNode? = null
    )

    private val subtitleLanguageNames by lazy {
        SubtitleHelper.languages.map { it.languageName.lowercase(java.util.Locale.ROOT) }.toSet()
    }

    private fun cleanSubtitleDisplayLabel(label: String, language: String): String? {
        var cleaned = label.trim()

        val repeatedPrefix = Regex("^${Regex.escape(language)}\\s*(?:[·•|:]|\\s[-—–]\\s)\\s*", RegexOption.IGNORE_CASE)
        while (repeatedPrefix.containsMatchIn(cleaned)) {
            cleaned = repeatedPrefix.replaceFirst(cleaned, "").trim()
        }

        val beforeParentheses = cleaned
        cleaned = Regex("\\s*\\(([^()]+)\\)").replace(cleaned) { match ->
            if (match.groupValues[1].trim().lowercase(java.util.Locale.ROOT) in subtitleLanguageNames) ""
            else match.value
        }.trim()
        if (cleaned != beforeParentheses) {
            cleaned = cleaned.trimStart('·', '•', '|', ' ').trim()
            cleaned = Regex("\\s+[·•|]\\s*[·•|]\\s+").replace(cleaned, " · ")
        }
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun Subtitle.translationSourceLanguage(entries: List<Subtitle>): String? {
        val derivedId = id ?: return null
        val separator = derivedId.lastIndexOf("_to_")
        if (separator <= 0) return null
        val target = SubtitleHelper.fromCodeToLangTagIETF(derivedId.substring(separator + 4))
            ?: return null
        val reported = lang_code ?: lang
        val reportedTag = SubtitleHelper.fromCodeToLangTagIETF(reported)
            ?: SubtitleHelper.fromLanguageToTagIETF(reported, true)
        if (reportedTag != target) return null

        // Match a source ID within this response, without assuming an addon or language name.
        val sourcePrefix = derivedId.substring(0, separator)
        val source = entries.singleOrNull { candidate ->
            val sourceId = candidate.id ?: return@singleOrNull false
            val start = sourcePrefix.length - sourceId.length
            start >= 0 && sourcePrefix.endsWith(sourceId) &&
                (start == 0 || sourcePrefix[start - 1] == '_')
        } ?: return null
        val sourceCode = source.lang_code ?: source.lang
        val sourceTag = SubtitleHelper.fromCodeToLangTagIETF(sourceCode)
            ?: SubtitleHelper.fromLanguageToTagIETF(sourceCode, true)
            ?: return null
        if (sourceTag == target) return null
        return SubtitleHelper.languages.firstOrNull { it.IETF_tag == sourceTag }
            ?.ISO_639_3?.takeIf { it.isNotBlank() }
            ?.uppercase(java.util.Locale.ROOT)
    }

    private suspend fun Subtitle.toCloudStreamSubtitle(
        addonName: String?, preferredName: String? = null, sourceLanguage: String? = null
    ): SubtitleFile? {
        val subtitleUrl = url ?: return null
        val rawLang = lang ?: lang_code ?: "Unknown"
        val language = SubtitleHelper.fromTagToEnglishLanguageName(rawLang) ?: rawLang
        val displayLanguage = if (sourceLanguage != null) "$language ($sourceLanguage)" else language
        val mainLabel = label.textOrNull()?.let { cleanSubtitleDisplayLabel(it, language) }
        val responseSource = name.textOrNull()?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("StremioC", true) && !it.startsWith("http", true) &&
                !it.equals(mainLabel, true) && !it.equals(language, true)
        }
        val sourceLabel = preferredName?.trim()?.takeIf { it.isNotBlank() }
            ?: (responseSource ?: addonName?.trim())?.takeIf {
                it.isNotBlank() && !it.equals("StremioC", true) && !it.startsWith("http", true) &&
                    !it.equals(mainLabel, true) && !it.equals(language, true)
            }
        if (mainLabel == null && sourceLabel == null) {
            return newSubtitleFile(displayLanguage, subtitleUrl)
        }
        val displayName = buildString {
            append(displayLanguage)
            if (mainLabel != null && !mainLabel.equals(language, true)) { append(" · "); append(mainLabel) }
            if (sourceLabel != null) { append(" — "); append(sourceLabel) }
        }
        return newSubtitleFile(displayName, subtitleUrl)
    }
    private data class ProxyHeaders(@JsonProperty("request") val request: Map<String, String>?)   
    private data class BehaviorHints(@JsonProperty("proxyHeaders") val proxyHeaders: ProxyHeaders?, @JsonProperty("headers") val headers: Map<String, String>?)

    private data class Stream(
        @JsonProperty("name") val name: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("ytId") val ytId: String?,
        @JsonProperty("externalUrl") val externalUrl: String?,
        @JsonProperty("behaviorHints") val behaviorHints: BehaviorHints?,
        @JsonProperty("infoHash") val infoHash: String?,
        @JsonProperty("fileIdx") val fileIdx: Int? = null,
        @JsonProperty("sources") val sources: List<String> = emptyList(),
        @JsonProperty("subtitles") val subtitles: List<Subtitle> = emptyList()
    ) {
        suspend fun runCallback(
            provider: StremioC,
            sourceName: String,
            stremioSubtitleCallback: (SubtitleFile) -> Unit,
            extractorSubtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            preferredName: String? = null,
            addonUrl: String? = null
        ) {
            val displaySource = sourceName.takeIf { it.isNotBlank() } ?: name ?: ""
            suspend fun torrentLink(raw: String, type: ExtractorLinkType): String {
                val withTrackers = StremioTorrentFiles.addTrackers(raw, sources)
                val index = fileIdx?.takeIf { it >= 0 }
                    ?: StremioTorrentFiles.explicitIndex(withTrackers)
                    ?: StremioTorrentFiles.largestIndex(withTrackers, type, infoHash)
                return if (index == null) withTrackers
                else StremioTorrentFiles.withIndex(withTrackers, type, index)
            }

            if (url != null) {
                val lowerUrl = url.lowercase()
                val linkType = when {
                    lowerUrl.startsWith("magnet:") -> ExtractorLinkType.MAGNET
                    lowerUrl.contains(".m3u8") || 
                    lowerUrl.contains("m3u8") || 
                    lowerUrl.contains("playlist") || 
                    name?.contains("m3u8", true) == true || 
                    title?.contains("m3u8", true) == true -> ExtractorLinkType.M3U8

                    lowerUrl.contains(".mpd") -> ExtractorLinkType.DASH
                    lowerUrl.contains(".torrent") -> ExtractorLinkType.TORRENT
                    else -> ExtractorLinkType.VIDEO 
                }

                val displayTitle = description ?: title ?: name ?: ""
                val playbackUrl = if (linkType == ExtractorLinkType.MAGNET ||
                    linkType == ExtractorLinkType.TORRENT) torrentLink(url, linkType) else url
                callback.invoke(newExtractorLink(displaySource, "[$displaySource] $displayTitle", playbackUrl, linkType) {
                    this.quality = getQuality(listOf(description, title, name))
                    this.headers = behaviorHints?.proxyHeaders?.request ?: behaviorHints?.headers ?: mapOf()
                })
            }

            if (ytId != null) loadExtractor("https://m.youtube.com/watch?v=$ytId", extractorSubtitleCallback, callback)
            if (externalUrl != null) loadExtractor(externalUrl, extractorSubtitleCallback, callback)
            if (infoHash != null) {
                val displayTitle = description ?: title ?: name ?: ""
                val magnet = torrentLink("magnet:?xt=urn:btih:" + infoHash, ExtractorLinkType.MAGNET)
                callback.invoke(newExtractorLink(displaySource, "[$displaySource] $displayTitle", magnet, ExtractorLinkType.MAGNET) {
                    this.quality = Qualities.Unknown.value
                })
            }

            val hasPlayableTarget = url != null || ytId != null || externalUrl != null || infoHash != null
            if (hasPlayableTarget) {
                subtitles.forEachIndexed { position, sub ->
                    provider.run {
                        sub.toCloudStreamSubtitle(sourceName, preferredName, sub.translationSourceLanguage(subtitles))
                    }?.let { file ->
                        if (addonUrl != null) {
                            StremioSubtitleRefreshRegistry.remember(
                                file.url, addonUrl, sub.id, sub.lang ?: sub.lang_code,
                                null, position, file.headers ?: emptyMap()
                            )
                        }
                        stremioSubtitleCallback(file)
                    }
                }
            }
        }
    }
}

private object StremioTorrentFiles {
    private const val LOOKUP_TIMEOUT_MS = 12_000L
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    private val slots = Semaphore(10)
    private val firstLookupLock = Mutex()
    @Volatile private var torrentServerInitialized = false
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Int?>>()
    private val cache = lruCacheMap<String, Pair<Int, Long>>(256)
    private val hashPattern = Regex("""(?i)urn:btih:([0-9a-z]+)""")
    private val indexPattern = Regex("""(?i)[?&]index=(\d+)""")
    private val indexParameter = Regex("""(?i)[?&]index=[^&#]*""")
    private val videoExtensions = setOf(
        "mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "m2ts",
        "mpg", "mpeg", "wmv", "flv", "ogv", "3gp", "vob"
    )

    fun explicitIndex(url: String): Int? =
        indexPattern.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 }

    fun addTrackers(url: String, sources: List<String>): String {
        if (!url.startsWith("magnet:", ignoreCase = true)) return url
        val known = Regex("""(?i)[?&]tr=([^&#]*)""").findAll(url)
            .mapNotNull {
                runCatching { URLDecoder.decode(it.groupValues[1], "UTF-8") }.getOrNull()
            }.toMutableSet()
        val added = StringBuilder(url)
        sources.forEach { source ->
            if (!source.startsWith("tracker:", ignoreCase = true)) return@forEach
            val tracker = source.substringAfter(':').trim()
            if (tracker.startsWith("udp://", true) ||
                tracker.startsWith("http://", true) ||
                tracker.startsWith("https://", true)) {
                if (known.add(tracker)) {
                    added.append("&tr=").append(tracker.urlEncode())
                }
            }
        }
        return added.toString()
    }

    fun withIndex(url: String, type: ExtractorLinkType, index: Int): String {
        val withoutIndex = indexParameter.replace(url, "")
        if (type == ExtractorLinkType.TORRENT &&
            (url.startsWith("https://", true) || url.startsWith("http://", true))) {
            return withoutIndex.substringBefore('#') + "#index=" + index
        }
        val base = withoutIndex.substringBefore('#')
        val fragment = withoutIndex.substringAfter('#', "")
        val separator = if (base.contains('?')) "&" else "?"
        return base + separator + "index=" + index +
            (if (fragment.isEmpty()) "" else "#" + fragment)
    }

    suspend fun largestIndex(
        url: String,
        type: ExtractorLinkType,
        infoHash: String?
    ): Int? {
        if (Torrent.hasAcceptedTorrentForThisSession == false) return null
        val key = infoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: hashPattern.find(url)?.groupValues?.getOrNull(1)?.lowercase()
            ?: url.substringBefore('#')
        synchronized(cache) {
            cache[key]?.let { (index, savedAt) ->
                val age = System.currentTimeMillis() - savedAt
                if (age >= 0L && age < CACHE_TTL_MS) return index
            }
        }
        val task = CompletableDeferred<Int?>()
        val existing = pending.putIfAbsent(key, task)
        if (existing != null) return existing.await()
        try {
            val result = slots.withPermit {
                withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                    val link = newExtractorLink("Torrent", "Torrent", url, type)
                    val status = if (torrentServerInitialized) {
                        Torrent.transformLink(link).second
                    } else {
                        firstLookupLock.withLock {
                            val fetched = Torrent.transformLink(link).second
                            torrentServerInitialized = true
                            fetched
                        }
                    }
                    val files = status.fileStats.orEmpty().filter {
                        it.id != null && (it.length ?: 0L) > 0L
                    }
                    val videos = files.filter { file ->
                        file.path?.substringAfterLast('.', "")?.lowercase() in videoExtensions
                    }
                    (videos.ifEmpty { files }).maxByOrNull { it.length ?: 0L }?.id
                }
            }
            if (result != null) {
                synchronized(cache) { cache[key] = result to System.currentTimeMillis() }
            }
            task.complete(result)
            return result
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            task.complete(null)
            return null
        } finally {
            if (!task.isCompleted) task.complete(null)
            pending.remove(key, task)
        }
    }
}

private data class TmdbFindResponse(
    @JsonProperty("movie_results") val movie_results: List<TmdbFindResult>? = null,
    @JsonProperty("tv_results") val tv_results: List<TmdbFindResult>? = null
)

private data class TmdbFindResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val media_type: String? = null
)

private data class TmdbCollection(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val poster_path: String? = null,
    @JsonProperty("backdrop_path") val backdrop_path: String? = null
)

private data class TmdbCollectionDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("parts") val parts: List<TmdbMedia>? = null
)

private data class TmdbTranslations(@JsonProperty("translations") val translations: List<TmdbTranslationItem>?)
private data class TmdbTranslationItem(@JsonProperty("iso_3166_1") val iso_3166_1: String?, @JsonProperty("iso_639_1") val iso_639_1: String?, @JsonProperty("data") val data: TmdbTranslationData?)
private data class TmdbTranslationData(@JsonProperty("title") val title: String?, @JsonProperty("name") val name: String?, @JsonProperty("overview") val overview: String?)

private data class TmdbGenre(@JsonProperty("id") val id: Int? = null)

private data class TmdbDetailResponse(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
    @JsonProperty("number_of_seasons") val number_of_seasons: Int? = null,
    @JsonProperty("title") val title: String? = null, 
    @JsonProperty("name") val name: String? = null, 
    @JsonProperty("original_title") val original_title: String? = null, 
    @JsonProperty("original_name") val original_name: String? = null, 
    @JsonProperty("overview") val overview: String? = null, 
    @JsonProperty("release_date") val releaseDate: String? = null, 
    @JsonProperty("first_air_date") val firstAirDate: String? = null, 
    @JsonProperty("status") val status: String? = null, 
    @JsonProperty("poster_path") val posterPath: String? = null, 
    @JsonProperty("origin_country") val origin_country: List<String>? = null, 
    @JsonProperty("original_language") val original_language: String? = null, 
    @JsonProperty("belongs_to_collection") val belongs_to_collection: TmdbCollection? = null, 
    @JsonProperty("recommendations") val recommendations: TmdbRecommendations? = null,
    @JsonProperty("runtime") val runtime: Int? = null, 
    @JsonProperty("release_dates") val release_dates: TmdbReleaseDates? = null, 
    @JsonProperty("content_ratings") val content_ratings: TmdbContentRatings? = null, 
    @JsonProperty("credits") val credits: TmdbCredits? = null, 
    @JsonProperty("images") val images: TmdbImages? = null, 
    @JsonProperty("videos") val videos: ResultsTrailer? = null, 
    @JsonProperty("external_ids") val external_ids: ExternalIds? = null, 
    @JsonProperty("translations") val translations: TmdbTranslations? = null,
    @JsonProperty("vote_average") val vote_average: Double? = null
)

private data class TmdbImages(@JsonProperty("logos") val logos: List<TmdbLogo>? = null)
private data class TmdbLogo(
    @JsonProperty("file_path") val file_path: String? = null,
    @JsonProperty("iso_639_1") val iso_639_1: String? = null,
    @JsonProperty("vote_average") val vote_average: Double? = null
)
private data class TmdbRecommendations(@JsonProperty("results") val results: List<TmdbMedia>? = null)
private data class TmdbMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("poster_path") val poster_path: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("origin_country") val origin_country: List<String>? = null,
    @JsonProperty("original_language") val original_language: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null
)

private data class TmdbReleaseDates(@JsonProperty("results") val results: List<TmdbReleaseDateResult>?)
private data class TmdbReleaseDateResult(@JsonProperty("iso_3166_1") val iso_3166_1: String?, @JsonProperty("release_dates") val release_dates: List<TmdbReleaseDate>?)
private data class TmdbReleaseDate(@JsonProperty("certification") val certification: String?)
private data class TmdbContentRatings(@JsonProperty("results") val results: List<TmdbContentRatingResult>?)
private data class TmdbContentRatingResult(@JsonProperty("iso_3166_1") val iso_3166_1: String?, @JsonProperty("rating") val rating: String?)
private data class TmdbCredits(@JsonProperty("cast") val cast: List<TmdbCast>? = null, @JsonProperty("crew") val crew: List<TmdbCrew>? = null)
private data class TmdbCast(@JsonProperty("name") val name: String?, @JsonProperty("original_name") val originalName: String?, @JsonProperty("character") val character: String?, @JsonProperty("profile_path") val profilePath: String?, @JsonProperty("id") val id: Int? = null)
private data class TmdbCrew(@JsonProperty("name") val name: String?, @JsonProperty("original_name") val originalName: String?, @JsonProperty("job") val job: String?, @JsonProperty("profile_path") val profilePath: String?, @JsonProperty("id") val id: Int? = null)
private data class TmdbSeasonDetail(@JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null)
private data class TmdbEpisode(@JsonProperty("name") val name: String?, @JsonProperty("overview") val overview: String?, @JsonProperty("episode_number") val episodeNumber: Int?, @JsonProperty("air_date") val airDate: String?, @JsonProperty("vote_average") val voteAverage: Double?, @JsonProperty("runtime") val runtime: Int?)

data class Trailers(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("published_at") val publishedAt: String? = null,
    @JsonProperty("site") val site: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("iso_639_1") val language: String? = null,
    @JsonProperty("iso_3166_1") val region: String? = null
)
data class ResultsTrailer(@JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf())
data class ExternalIds(@JsonProperty("imdb_id") val imdb_id: String? = null, @JsonProperty("tvdb_id") val tvdb_id: String? = null)

private fun String.toUniqueKey(): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-256")
        .digest(this.toByteArray(Charsets.UTF_8))
    return "v2_" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun String.toLegacyUniqueKey(): String {
    return this.lowercase().filter { it.isLetter() }
}

internal fun String.urlEncodeLocal(): String = try {
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
} catch (e: Exception) {
    this
}

fun getSafeAllProviders(): List<MainAPI> {
    return try {
        val method = APIHolder::class.java.methods.find { it.name == "getAllProviders" }
        val obj = method?.invoke(APIHolder)
        when (obj) {
            is Iterable<*> -> obj.filterIsInstance<MainAPI>()
            is Array<*> -> obj.filterIsInstance<MainAPI>()
            else -> emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }
}

private data class FlatTab(val key: String, val displayName: String, val urlPath: String)

internal fun stremioManifestUrl(source: String): String {
    val base = source.substringBefore("?").trimEnd('/').removeSuffix("/manifest.json")
    val query = source.substringAfter("?", "").takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""
    return "$base/manifest.json$query"
}

internal fun stremioCatalogKey(source: String): String = stremioManifestUrl(source).toUniqueKey()

private fun stremioOldCatalogKeys(source: String): List<String> = listOf(
    source.toLegacyUniqueKey(),
    stremioManifestUrl(source).substringBefore("?").toLegacyUniqueKey(),
    stremioManifestUrl(source).toLegacyUniqueKey(),
    stremioManifestUrl(source).substringBefore("?").toUniqueKey()
).distinct()

internal fun migrateStremioCatalogSettings(prefs: SharedPreferences, source: String) {
    val key = stremioCatalogKey(source)
    val marker = "catalog_keys_migrated_$key"
    synchronized(StremioC::class.java) {
        if (prefs.getBoolean(marker, false)) return
        val all = prefs.all
        val editor = prefs.edit()
        val written = mutableSetOf<String>()
        for (old in stremioOldCatalogKeys(source)) {
            if (old == key) continue
            for ((name, value) in all) {
                val target = when {
                    name == "catalog_order_$old" -> "catalog_order_$key"
                    name.startsWith("tab_enabled_${old}_") -> "tab_enabled_${key}_" + name.removePrefix("tab_enabled_${old}_")
                    name.startsWith("genres_enabled_${old}_") -> "genres_enabled_${key}_" + name.removePrefix("genres_enabled_${old}_")
                    else -> continue
                }

                if (!written.add(target)) continue
                when (value) {
                    is Boolean -> editor.putBoolean(target, value)
                    is String -> editor.putString(target, value)
                }
            }
        }
        editor.putBoolean(marker, true).apply()
    }
}

internal fun stremioReadManifest(
    prefs: SharedPreferences, source: String, requireFresh: Boolean = false
): String? {
    val key = stremioCatalogKey(source)
    if (prefs.getBoolean("manifest_no_store_" + key, false)) {
        return null
    }
    val raw = prefs.getString(key, null) ?: stremioOldCatalogKeys(source)
        .firstNotNullOfOrNull { prefs.getString(it, null) }
    if (raw == null) {
        return null
    }
    val now = System.currentTimeMillis()
    val stampKey = "manifest_saved_at_$key"
    val savedAt = prefs.getLong(stampKey, 0L)

    if (savedAt == 0L) {
        prefs.edit().putString(key, raw).putLong(stampKey, now).apply()
        return raw
    }
    val maxAge = if (requireFresh) prefs.getLong(
        "manifest_ttl_ms_" + key, 3_600_000L
    ).coerceIn(0L, 72L * 60 * 60 * 1000) else 72L * 60 * 60 * 1000
    val valid = savedAt <= now && now - savedAt <= maxAge
    return raw.takeIf { valid }
}
