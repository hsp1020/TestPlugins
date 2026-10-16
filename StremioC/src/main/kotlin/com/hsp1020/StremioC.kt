package com.hsp1020

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
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

private fun String.urlEncode(): String = try { URLEncoder.encode(this, "UTF-8").replace("+", "%20") } catch (e: Exception) { this }


private fun String.cleanBaseUrl(): String {
    return this.substringBefore("?").replace("/manifest.json", "").trimEnd('/')
}

private inline fun <T> lruSet(maxSize: Int): MutableSet<T> {
    return Collections.synchronizedSet(Collections.newSetFromMap(object : java.util.LinkedHashMap<T, Boolean>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<T, Boolean>): Boolean {
            return size > maxSize
        }
    }))
}

private inline fun <K, V> lruCacheMap(maxSize: Int): MutableMap<K, V> {
    return Collections.synchronizedMap(object : java.util.LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
            return size > maxSize
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

internal fun stremioMdbAuditKey(id: String, isTmdb: Boolean, mediaType: String): String =
    "${mdbCredentialFingerprint()}:$isTmdb:$mediaType:$id"

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

// Temporary end-to-end cache audit: all keys are SHA-256 fingerprints, never
// full URLs, API keys or JSON payloads. Remains visible in ordinary Logcat.
internal object StremioCCacheAudit {
    private const val TAG = "StremioCCacheAudit"
    fun id(key: String): String = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrDefault("unknown")

    fun log(group: String, action: String, key: String, info: String = "") {
        Log.i(TAG, "group=$group event=$action key=${id(key)} $info")
    }
}

internal fun <T> stremioCacheMemoryRead(
    group: String, key: String, cached: Pair<T, Long>?, ttlMs: Long
): Pair<T, Long>? {
    if (cached == null) {
        StremioCCacheAudit.log(group, "RAM_MISS", key)
        return null
    }
    val age = System.currentTimeMillis() - cached.second
    val valid = age >= 0L && age < ttlMs
    StremioCCacheAudit.log(group, if (valid) "RAM_HIT" else "RAM_EXPIRED",
        key, "ageMs=$age ttlMs=$ttlMs")
    return cached.takeIf { valid }
}

private object MetadataDiskCache {
    private const val TTL_MS = 24L * 60 * 60 * 1000
    private const val SEASON_TTL_MS = 12L * 60 * 60 * 1000
    private const val MAX_BYTES = 50L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024
    private const val MAX_RAW_BYTES = 2 * 1024 * 1024
    private val entryLimits = mapOf("detail" to 200, "season" to 300, "find" to 5000,
        "anime" to 5000, "mdb" to 1000, "meta" to 200)
    private val lock = Any()
    private val diskHits = AtomicLong()
    private val diskMisses = AtomicLong()

    data class Usage(val items: Int, val bytes: Long, val tempBytes: Long, val hits: Long, val misses: Long)

    suspend fun usage(): Usage = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val all = directory()?.listFiles()?.filter { it.isFile }.orEmpty()
            val files = all.filter { it.name.endsWith(".gz") }
            Usage(files.size, files.sumOf { it.length() },
                all.filter { it.name.endsWith(".tmp") }.sumOf { it.length() },
                diskHits.get(), diskMisses.get())
        }
    }

    private fun directory(): File? = CloudStreamApp.context?.filesDir
        ?.let { File(it, "stremioc-metadata-v1").apply { mkdirs() }.takeIf { dir -> dir.isDirectory } }

    private fun target(dir: File, kind: String, key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest("${kind}:${key}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(dir, "${kind}_${hash}.gz")
    }

    suspend fun read(kind: String, key: String): Pair<String, Long>? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val file = directory()?.let { target(it, kind, key) }
            if (file == null) {
                StremioCCacheAudit.log(kind, "DISK_UNAVAILABLE", key)
                return@synchronized null
            }
            if (!file.isFile) {
                diskMisses.incrementAndGet()
                StremioCCacheAudit.log(kind, "DISK_MISS", key)
                return@synchronized null
            }
            try {
                val body = GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val stamp = body.substringBefore('\n').toLongOrNull()
                val now = System.currentTimeMillis()
                val firstNewline = body.indexOf('\n')
                val secondNewline = if (kind == "meta" && firstNewline >= 0)
                    body.indexOf('\n', firstNewline + 1) else -1
                val ttl = when (kind) {
                    "season" -> SEASON_TTL_MS
                    "meta" -> if (secondNewline > firstNewline)
                        body.substring(firstNewline + 1, secondNewline).toLongOrNull() ?: 0L else 0L
                    else -> TTL_MS
                }
                if (stamp == null || stamp > now || ttl <= 0L ||
                    now - stamp >= ttl || firstNewline < 0 || (kind == "meta" && secondNewline < 0)) {
                    file.delete()
                    diskMisses.incrementAndGet()
                    StremioCCacheAudit.log(kind, "DISK_EXPIRED_OR_INVALID", key,
                        "ageMs=${stamp?.let { now - it } ?: -1} ttlMs=$ttl bytes=${file.length()}")
                    null
                } else {
                    diskHits.incrementAndGet()
                    StremioCCacheAudit.log(kind, "DISK_HIT", key,
                        "ageMs=${now - stamp} ttlMs=$ttl jsonChars=${body.length} compressedBytes=${file.length()}")
                    file.setLastModified(now) // Disk LRU; TTL stays in the file header.
                    body.substring(if (kind == "meta") secondNewline + 1 else firstNewline + 1) to stamp
                }
            } catch (error: Exception) {
                file.delete()
                diskMisses.incrementAndGet()
                StremioCCacheAudit.log(kind, "DISK_READ_ERROR", key,
                    "error=${error.javaClass.simpleName}")
                null
            }
        }
    }

    suspend fun write(kind: String, key: String, json: String, ttlMs: Long = TTL_MS) {
        if (kind == "meta" && ttlMs <= 0L) {
            StremioCCacheAudit.log(kind, "DISK_SKIP", key, "reason=nonpositive_ttl")
            return
        }
        val rawBytes = json.toByteArray(Charsets.UTF_8).size
        if (json.contains("data:image/", ignoreCase = true) ||
            rawBytes > MAX_RAW_BYTES) {
            StremioCCacheAudit.log(kind, "DISK_SKIP", key, "reason=image_or_oversize rawBytes=$rawBytes")
            return
        }
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val dir = directory() ?: run {
                    StremioCCacheAudit.log(kind, "DISK_WRITE_UNAVAILABLE", key)
                    return@synchronized
                }
                // Reserve the maximum entry size before writing a temporary file.
                // Old abandoned temporaries are removed under the same lock.
                dir.listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") }
                    ?.forEach { it.delete() }
                trim(dir, MAX_BYTES - MAX_ENTRY_BYTES)
                val file = target(dir, kind, key)
                val temp = File(dir, file.name + ".tmp")
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
                        StremioCCacheAudit.log(kind, "DISK_SKIP", key,
                            "reason=compressed_oversize bytes=${temp.length()}")
                        temp.delete()
                        return@synchronized
                    }
                    if (!temp.renameTo(file)) {
                        StremioCCacheAudit.log(kind, "DISK_WRITE_ERROR", key, "reason=rename")
                        temp.delete()
                    } else {
                        StremioCCacheAudit.log(kind, "DISK_WRITE_OK", key,
                            "rawBytes=$rawBytes compressedBytes=${file.length()} ttlMs=$ttlMs")
                        trim(dir) // Enforce the 50 MiB budget on every successful write.
                        StremioCCacheAudit.log(kind, "DISK_BUDGET", key,
                            "totalBytes=${dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: -1}")
                    }
                } catch (error: Exception) {
                    StremioCCacheAudit.log(kind, "DISK_WRITE_ERROR", key,
                        "error=${error.javaClass.simpleName}")
                    temp.delete()
                }
            }
        }
    }

    private fun trim(dir: File, limitBytes: Long = MAX_BYTES) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".gz") } ?: return
        for ((kind, limit) in entryLimits) {
            files.filter { it.name.startsWith("${kind}_") }
                .sortedByDescending { it.lastModified() }.drop(limit).forEach {
                    val bytes = it.length()
                    if (it.delete()) StremioCCacheAudit.log(kind, "DISK_EVICT",
                        it.name, "reason=entry_limit bytes=$bytes")
                }
        }
        val remaining = files.filter { it.exists() }.sortedBy { it.lastModified() }
        var total = remaining.sumOf { it.length() }
        for (file in remaining) {
            if (total <= limitBytes) break
            val length = file.length()
            if (file.delete()) {
                total -= length
                StremioCCacheAudit.log("gzip", "DISK_EVICT", file.name,
                    "reason=byte_limit bytes=$length remainingBytes=$total")
            }
        }
    }
}

private object DetailLoadPerf {
    private val sequence = AtomicLong()
    val current = ThreadLocal<Session?>()
    private const val TAG = "StremioCLoadPerf"

    class Session {
        val id = sequence.incrementAndGet()
        private val origin = SystemClock.elapsedRealtimeNanos()
        fun event(stage: String, detail: String = "") {
            val totalMs = (SystemClock.elapsedRealtimeNanos() - origin) / 1_000_000L
            Log.i(TAG, "run=$id totalMs=$totalMs stage=$stage $detail")
        }
    }

    fun event(stage: String, detail: String = "") = current.get()?.event(stage, detail)

    suspend fun <T> step(stage: String, block: suspend () -> T): T {
        val trace = current.get() ?: return block()
        val start = SystemClock.elapsedRealtimeNanos()
        trace.event("BEGIN", "op=$stage")
        try {
            val result = block()
            trace.event("END", "op=$stage tookMs=${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L}")
            return result
        } catch (error: Throwable) {
            trace.event(if (error is CancellationException) "CANCEL" else "FAIL",
                "op=$stage tookMs=${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L} error=${error.javaClass.simpleName}")
            throw error
        }
    }

    fun <T> stepNow(stage: String, block: () -> T): T {
        val trace = current.get() ?: return block()
        val start = SystemClock.elapsedRealtimeNanos()
        trace.event("BEGIN", "op=$stage")
        try {
            val result = block()
            trace.event("END", "op=$stage tookMs=${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L}")
            return result
        } catch (error: Throwable) {
            trace.event("FAIL", "op=$stage tookMs=${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L} error=${error.javaClass.simpleName}")
            throw error
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

// Query configured-language and English logos in the existing TMDB detail call.
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

// The host adds sync metadata trailers after provider.load() and extracts them for
// its inline preview. Discard only those additions; StremioC's own picker is separate.
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
    
    // The shared provider alone appears in CloudStream's library opener when a
    // metadata/history addon has been configured; catalog providers never appear.
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

        // CloudStream persists the complete, account-scoped Simkl library, including external IDs.
        val cached = DetailLoadPerf.stepNow("library.simkl.cache.read") {
            getKey<JsonNode>(SimklApi.SIMKL_CACHED_LIST, auth.user.id.toString())
        }
        DetailLoadPerf.event("LIBRARY_CACHE", "service=simkl cachePresent=${cached != null} movies=${cached?.get("movies")?.size() ?: 0} shows=${cached?.get("shows")?.size() ?: 0} anime=${cached?.get("anime")?.size() ?: 0}")
        for ((section, type) in listOf("movies" to "movie", "shows" to "series", "anime" to "series")) {
            val item = cached?.get(section)?.firstOrNull {
                val content = if (section == "movies") it.get("movie") else it.get("show")
                content?.get("ids")?.get("simkl")?.asText() == simklId
            } ?: continue
            DetailLoadPerf.event("LIBRARY_CACHE_MATCH", "service=simkl section=$section")
            val ids = (if (section == "movies") item.get("movie") else item.get("show"))?.get("ids")
            if (section != "anime") {
                simklMetaUrl(base, type, ids)?.let { return it }
                continue
            }

            // CloudStream's cached Simkl anime model drops anime_type. Do not assume
            // an anime is a series: confirm its type with one Simkl summary request.
            val animeType = item.get("anime_type").textOrNull()
                ?: item.get("show")?.get("anime_type").textOrNull()
            val detail = if (animeType == null) runSuspendCatching {
                DetailLoadPerf.step("library.simkl.animeType.http") {
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
            DetailLoadPerf.event("LIBRARY_TYPE", "service=simkl from=anime type=$resolvedType")
            return simklMetaUrl(base, resolvedType, ids)
                ?: simklMetaUrl(base, resolvedType, detail?.get("ids"))
        }

        // Cache miss (or no external ID): first identify the media type, then fetch full IDs.
        DetailLoadPerf.event("LIBRARY_CACHE_MISS", "service=simkl startingFallback=true")
        val matches = runSuspendCatching {
            DetailLoadPerf.step("library.simkl.searchId.http") {
                parseJson<JsonNode>(customSession.get(
                    "https://api.simkl.com/search/id",
                    params = params + ("simkl" to simklId),
                    headers = headers,
                    timeout = 15L
                ).text)
            }
        }.onFailure { DetailLoadPerf.event("LIBRARY_FALLBACK_ERROR", "service=simkl op=search error=${it.javaClass.simpleName}") }.getOrNull()
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
            DetailLoadPerf.step("library.simkl.$endpoint.detail.http") {
                parseJson<JsonNode>(customSession.get(
                    "https://api.simkl.com/$endpoint/$simklId",
                    params = params,
                    headers = headers,
                    timeout = 15L
                ).text)
            }
        }.onFailure { DetailLoadPerf.event("LIBRARY_FALLBACK_ERROR", "service=simkl op=detail error=${it.javaClass.simpleName}") }.getOrNull() ?: return null
        if (detail.get("ids")?.get("simkl")?.asText() != simklId) return null
        val type = if (endpoint == "anime") {
            when (detail.get("anime_type").textOrNull()?.lowercase()) {
                "movie" -> "movie"
                "tv", "ova", "ona", "special", "music video" -> "series"
                else -> return null
            }
        } else if (endpoint == "movies") "movie" else "series"
        DetailLoadPerf.event("LIBRARY_TYPE", "service=simkl from=fallback type=$type")
        return simklMetaUrl(base, type, detail.get("ids"))
    }

    private fun animeLibraryType(format: String?): String? = when (format?.trim()?.lowercase()) {
        "movie" -> "movie"
        "tv", "tv_short", "ona", "ova", "special", "music" -> "series"
        else -> null
    }

    private suspend fun animeLibraryMetaType(name: SyncIdName, itemId: String): String? {
        val auth = AccountManager.syncApis.firstOrNull { it.syncIdName == name }?.authData()
        val cachedType = DetailLoadPerf.stepNow("library.anime.cache.read") { when (name) {
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
            // CloudStream's Kitsu library cache does not request the anime subtype.
            else -> null
        } }
        DetailLoadPerf.event("LIBRARY_CACHE", "service=$name format=$cachedType hit=${animeLibraryType(cachedType) != null}")
        animeLibraryType(cachedType)?.let { return it }

        // A library URL carries no movie/series type. Resolve it only when the
        // existing MAL/AniList cache cannot answer (or for Kitsu).
        val remoteType = runSuspendCatching {
            DetailLoadPerf.step("library.anime.typeRemote.$name") { when (name) {
                SyncIdName.MyAnimeList -> {
                    val token = auth?.token?.accessToken ?: return@step null
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
        }.onFailure { DetailLoadPerf.event("LIBRARY_TYPE_ERROR", "service=$name error=${it.javaClass.simpleName}") }.getOrNull()
        DetailLoadPerf.event("LIBRARY_TYPE", "service=$name remoteType=$remoteType resolved=${animeLibraryType(remoteType)}")
        return animeLibraryType(remoteType)
    }

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        val resolved = getLoadUrlInternal(name, id)
        // The sync provider's lookup URL is not necessarily the URL kept in
        // ResultFragment arguments. MAL and AniList can differ from the host's
        // canonical URL even for the same anime ID; Kitsu often does not.
        // Register the canonical forms as well, without another HTTP request.
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

    // 주소가 있는 실제 애드온만 홈 화면 탭 목록에 표시되도록 설정
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

    // StremioC class 내부에 추가
    // ExtractorLink import는 기존 StremioC.kt에 이미 존재함.
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
    // mainPage is a synchronous property. A matching persisted manifest is parsed by exactly
    // one caller; concurrent/repeated callers reuse the prepared object instead of parsing twice.
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
        
        private val IMDB_ID_REGEX = "tt[0-9]+".toRegex()
        private val JSON_ID_EXTRACT_REGEX = "\"id\":\"([^\"]+)\"".toRegex()
        
        private const val MANIFEST_CACHE_TTL_MS = 3_600_000L
        
        private val settingsCache = ConcurrentHashMap<String, Pair<Boolean, Long>>()
        
        @Volatile private var cachedCustomHosts: Set<String>? = null
        @Volatile private var cachedCustomHostsTime: Long = 0
        private const val SETTINGS_CACHE_TTL = 60_000L

        // SharedPreferences 반복 파싱 부하 개선을 위한 3초 생명주기 캐시 바인딩
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
            StremioCCacheAudit.log("settings", "CACHE_INVALIDATE", "sync_settings")
            // Metadata keys already include the exact addon URL, locale and
            // manifest version. Unrelated settings must not flush other addons.
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
                StremioCCacheAudit.log("settings", "RAM_HIT", "sync_settings",
                    "ageMs=${now - cachedSyncSettingsTime} ttlMs=$SYNC_SETTINGS_TTL")
                return cached
            }
            StremioCCacheAudit.log("settings", if (cached == null) "RAM_MISS" else "RAM_EXPIRED",
                "sync_settings", "ageMs=${now - cachedSyncSettingsTime}")
            synchronized(StremioC::class.java) {
                val doubleCached = cachedSyncSettings
                if (doubleCached != null && (now - cachedSyncSettingsTime) < SYNC_SETTINGS_TTL) {
                    StremioCCacheAudit.log("settings", "RAM_REUSE_AFTER_LOCK", "sync_settings")
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
                        e.printStackTrace()
                    }
                }
                val extraUrls = runCatching {
                    val arr = org.json.JSONArray(prefs.getString("stream_subtitle_addon_urls", "[]"))
                    (0 until arr.length()).mapNotNull { i ->
                        when (val entry = arr.opt(i)) {
                            is org.json.JSONObject -> if (entry.optBoolean("enabled", true))
                                StreamSubtitleAddon(entry.optString("name").trim(), entry.optString("url").trim()) else null
                            is String -> StreamSubtitleAddon("", entry.trim()) // Legacy URL-only settings
                            else -> null
                        }?.takeIf { it.url.isNotBlank() }
                    }
                }.getOrDefault(emptyList()).distinctBy { stremioManifestUrl(it.url) }
                val result = SyncSettings(syncUrl, map, historyMap, extraMap, extraUrls,
                    catalogEnabled.filterValues { !it }.keys.toSet(), catalogEnabledIds)
                cachedSyncSettings = result
                cachedSyncSettingsTime = System.currentTimeMillis()
                StremioCCacheAudit.log("settings", "RAM_WRITE_OK", "sync_settings",
                    "links=${map.size} extraAddons=${extraUrls.size} disabled=${result.disabledCatalogUrls.size}")
                return result
            }
        }

        val networkSemaphore = Semaphore(35)
        // CloudStream launches all homepage rows concurrently and waits for all of them.
        // Bound foreground traffic directly; catalog prefetch has intentionally been removed.
        const val HOME_FOREGROUND_CONCURRENCY = 10
        const val HOME_FOREGROUND_PER_HOST_CONCURRENCY = 10
        private val homeForegroundSemaphore = Semaphore(HOME_FOREGROUND_CONCURRENCY)

        private class CatalogHttpException(val statusCode: Int) :
            IllegalStateException("Catalog HTTP $statusCode")
        
        @Volatile private var cachedCineStreamProvider: MainAPI? = null
        @Volatile private var cachedStreamPlayProvider: MainAPI? = null

        
        @Volatile private var cachedClassLoaderRef: WeakReference<ClassLoader>? = null

        internal val fallbackLogoTitles = lruCacheMap<String, Boolean>(200)
        // getLoadUrl() and load() may run on different StremioC instances/threads.
        // Bounded mapping only; never change CloudStream's original detail URL.
        private val libraryOriginalUrlsByMeta = lruCacheMap<String, MutableSet<String>>(200)
        internal val expectedLogoByUrl = lruCacheMap<String, Boolean>(200)
        internal val trailerChoicesByUrl = lruCacheMap<String, List<TrailerChoice>>(200)
        // A /meta response is shared by detail and episode loading. Keep a bounded
        // byte cache and join concurrent fetches for the identical exact URL.
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
        private val addonMetaMemoryHits = AtomicLong()
        private val addonMetaMemoryMisses = AtomicLong()
        private val addonMetaNetworkFetches = AtomicLong()

        // Optional on-demand diagnostics: no permanent high-volume logging.
        internal suspend fun metadataCacheDiagnostics(): String {
            val disk = MetadataDiskCache.usage()
            val ram = synchronized(addonMetaCache) {
                addonMetaCache.size to addonMetaCacheBytes
            }
            return "diskItems=${disk.items} diskBytes=${disk.bytes} tempBytes=${disk.tempBytes} " +
                "diskHits=${disk.hits} diskMisses=${disk.misses} " +
                "metaRamItems=${ram.first} metaRamBytes=${ram.second} " +
                "metaRamHits=${addonMetaMemoryHits.get()} " +
                "metaRamMisses=${addonMetaMemoryMisses.get()} " +
                "metaHttpFetches=${addonMetaNetworkFetches.get()}"
        }
        private val metadataTextFlights = mutableMapOf<String, Deferred<String>>()
        private val animeHttpFlights = mutableMapOf<String, Deferred<String>>()

        // Share only metadata GETs with identical URLs and request headers.
        // Stream/subtitle endpoints are deliberately excluded.
        private suspend fun fetchSharedMetadataText(url: String): String {
            val task = synchronized(metadataTextFlights) {
                metadataTextFlights[url]?.also {
                    StremioCCacheAudit.log("tmdb_http", "INFLIGHT_JOIN", url)
                } ?: addonMetaScope.async(start = CoroutineStart.LAZY) {
                    val start = SystemClock.elapsedRealtime()
                    StremioCCacheAudit.log("tmdb_http", "HTTP_START", url)
                    try {
                        val text = customSession.get(url).text
                        StremioCCacheAudit.log("tmdb_http", "HTTP_DONE", url,
                            "durationMs=${SystemClock.elapsedRealtime() - start} chars=${text.length}")
                        text
                    } catch (error: Throwable) {
                        StremioCCacheAudit.log("tmdb_http", "HTTP_ERROR", url,
                            "durationMs=${SystemClock.elapsedRealtime() - start} error=${error.javaClass.simpleName}")
                        throw error
                    } finally {
                        synchronized(metadataTextFlights) { metadataTextFlights.remove(url) }
                    }
                }.also {
                    StremioCCacheAudit.log("tmdb_http", "INFLIGHT_NEW", url)
                    metadataTextFlights[url] = it
                    it.start()
                }
            }
            return task.await()
        }

        private suspend fun fetchSharedAnimeText(url: String): String {
            val task = synchronized(animeHttpFlights) {
                animeHttpFlights[url]?.also {
                    StremioCCacheAudit.log("anime_http", "INFLIGHT_JOIN", url)
                } ?: addonMetaScope.async(start = CoroutineStart.LAZY) {
                    val start = SystemClock.elapsedRealtime()
                    StremioCCacheAudit.log("anime_http", "HTTP_START", url)
                    try {
                        val text = aioAnimeSession.get(url, headers = mapOf(
                            "Cache-Control" to "no-cache, no-store",
                            "Pragma" to "no-cache"
                        )).text
                        StremioCCacheAudit.log("anime_http", "HTTP_DONE", url,
                            "durationMs=${SystemClock.elapsedRealtime() - start} chars=${text.length}")
                        text
                    } catch (error: Throwable) {
                        StremioCCacheAudit.log("anime_http", "HTTP_ERROR", url,
                            "error=${error.javaClass.simpleName}")
                        throw error
                    } finally {
                        synchronized(animeHttpFlights) { animeHttpFlights.remove(url) }
                    }
                }.also {
                    StremioCCacheAudit.log("anime_http", "INFLIGHT_NEW", url)
                    animeHttpFlights[url] = it
                    it.start()
                }
            }
            return task.await()
        }

        private fun cacheAddonMetaInRam(url: String, text: String, ttlMs: Long) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (ttlMs <= 0L || bytes.size > ADDON_META_MAX_RAW) {
                StremioCCacheAudit.log("meta", "RAM_SKIP", url,
                    "bytes=${bytes.size} ttlMs=$ttlMs")
                return
            }
            synchronized(addonMetaCache) {
                addonMetaCache.remove(url)?.let { addonMetaCacheBytes -= it.bytes.size.toLong() }
                addonMetaCache[url] = AddonMetaEntry(bytes, System.currentTimeMillis(), ttlMs)
                addonMetaCacheBytes += bytes.size.toLong()
                val cursor = addonMetaCache.entries.iterator()
                while ((addonMetaCache.size > ADDON_META_RAM_ENTRIES ||
                        addonMetaCacheBytes > ADDON_META_RAM_LIMIT) && cursor.hasNext()) {
                    val removed = cursor.next()
                    addonMetaCacheBytes -= removed.value.bytes.size.toLong()
                    StremioCCacheAudit.log("meta", "RAM_EVICT", removed.key,
                        "bytes=${removed.value.bytes.size}")
                    cursor.remove()
                }
                StremioCCacheAudit.log("meta", "RAM_WRITE_OK", url,
                    "bytes=${bytes.size} entries=${addonMetaCache.size} totalBytes=$addonMetaCacheBytes ttlMs=$ttlMs")
            }
        }

        private fun cachedAddonMetaInRam(url: String): String? {
            val bytes = synchronized(addonMetaCache) {
                val held = addonMetaCache[url]
                if (held != null && System.currentTimeMillis() - held.at < held.ttlMs) {
                    StremioCCacheAudit.log("meta", "RAM_HIT", url,
                        "ageMs=${System.currentTimeMillis() - held.at} ttlMs=${held.ttlMs} bytes=${held.bytes.size}")
                    held.bytes
                } else {
                    if (held != null) StremioCCacheAudit.log("meta", "RAM_EXPIRED", url,
                        "ageMs=${System.currentTimeMillis() - held.at} ttlMs=${held.ttlMs}")
                    else StremioCCacheAudit.log("meta", "RAM_MISS", url)
                    addonMetaCache.remove(url)?.let { addonMetaCacheBytes -= it.bytes.size.toLong() }
                    null
                }
            }
            if (bytes != null) addonMetaMemoryHits.incrementAndGet()
            else addonMetaMemoryMisses.incrementAndGet()
            return bytes?.let { String(it, Charsets.UTF_8) }
        }

        // Do not cache embedded stream links (often signed or short-lived) or inline images.
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

        // Validate the metadata against the requested URL before allowing persistence.
        // An addon may legitimately remap anime IDs: display those results, but
        // never keep a mismatched or incomplete response across page loads.
        private fun isCacheableAddonMeta(url: String, root: JsonNode): Boolean {
            if (root.get("success")?.asBoolean() == false) return false
            val meta = root.get("meta") ?: root
            if (!meta.isObject) return false
            val entry = runCatching { parseJson<CatalogEntry>(meta.toString()) }.getOrNull()
                ?: return false
            val returnedId = entry.id?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val returnedType = canonicalDetailType(entry.type) ?: return false
            val encodedId = url.substringBefore('?').substringAfterLast('/')
                .removeSuffix(".json")
            val expectedId = runCatching {
                URLDecoder.decode(encodedId, "UTF-8")
            }.getOrDefault(encodedId)
            val expectedType = url.substringBefore("/meta/")
                .let { url.substringAfter("/meta/").substringBefore('/') }
            return returnedId == expectedId &&
                returnedType == canonicalDetailType(expectedType)
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
            // Only this language + addon configuration + addon version shares /meta.
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
            StremioCCacheAudit.log("meta", "READ_BEGIN", storageKey, "force=$forceRefresh")
            val previous = if (forceRefresh) {
                cachedAddonMetaInRam(storageKey)
                    ?: MetadataDiskCache.read("meta", storageKey)?.first
            } else null
            if (!forceRefresh) {
                cachedAddonMetaInRam(storageKey)?.let {
                    StremioCCacheAudit.log("meta", "REUSE_RAM", storageKey, "chars=${it.length}")
                    return it
                }
                MetadataDiskCache.read("meta", storageKey)?.let {
                    StremioCCacheAudit.log("meta", "REUSE_DISK", storageKey,
                        "chars=${it.first.length}")
                    return it.first
                }
            }

            val flightKey = (if (forceRefresh) "force|" else "normal|") + storageKey
            val flight = synchronized(addonMetaFlights) {
                addonMetaFlights[flightKey]?.also {
                    StremioCCacheAudit.log("meta", "INFLIGHT_JOIN", storageKey, "force=$forceRefresh")
                } ?: addonMetaScope.async(start = CoroutineStart.LAZY) {
                    val started = SystemClock.elapsedRealtime()
                    StremioCCacheAudit.log("meta", "HTTP_START", storageKey, "force=$forceRefresh")
                    try {
                        addonMetaNetworkFetches.incrementAndGet()
                        val response = customSession.get(url, headers = if (forceRefresh)
                            mapOf("Cache-Control" to "no-cache") else emptyMap())
                        if (response.code !in 200..299) {
                            throw IllegalStateException("Stremio meta HTTP " + response.code)
                        }
                        val body = response.text
                        StremioCCacheAudit.log("meta", "HTTP_DONE", storageKey,
                            "code=${response.code} durationMs=${SystemClock.elapsedRealtime() - started} chars=${body.length}")
                        val root = parseJson<JsonNode>(body)
                        fun header(name: String): String? = response.headers
                            .firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
                        val policy = header("Cache-Control")
                        val vary = header("Vary")
                        val ttlMs = addonMetaTtlMs(
                            url, policy, header("Age"), header("Expires"), header("Date"), root
                        )
                        // Only Accept-Encoding varies the wire representation, not the
                        // parsed JSON. Other Vary fields require request-header-aware keys.
                        val safeVary = vary.orEmpty().split(',').all {
                            it.trim().isEmpty() || it.trim().equals("Accept-Encoding", true)
                        }
                        val valid = isCacheableAddonMeta(url, root)
                        val transient = containsTransientMetaLinks(root)
                        val image = body.contains("data:image/", ignoreCase = true)
                        StremioCCacheAudit.log("meta", "CACHE_DECISION", storageKey,
                            "valid=$valid ttlMs=$ttlMs safeVary=$safeVary transient=$transient inlineImage=$image")
                        if (valid && ttlMs > 0L && safeVary && !transient && !image) {
                            cacheAddonMetaInRam(storageKey, body, ttlMs)
                            // Never delay the first detail paint on GZIP or disk trimming.
                            addonMetaScope.launch {
                                MetadataDiskCache.write("meta", storageKey, body, ttlMs)
                            }
                        }
                        body
                    } catch (error: Throwable) {
                        StremioCCacheAudit.log("meta", "HTTP_OR_PARSE_ERROR", storageKey,
                            "durationMs=${SystemClock.elapsedRealtime() - started} error=${error.javaClass.simpleName}")
                        throw error
                    } finally {
                        synchronized(addonMetaFlights) { addonMetaFlights.remove(flightKey) }
                    }
                }.also { deferred ->
                    StremioCCacheAudit.log("meta", "INFLIGHT_NEW", storageKey, "force=$forceRefresh")
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
                    StremioCCacheAudit.log("meta", "REFRESH_STALE_FALLBACK", storageKey,
                        "error=${error.javaClass.simpleName} chars=${previous.length}")
                    previous
                } else throw error
            }
        }
        internal val genreCatalogsByUrl = lruCacheMap<String, Map<String, CatalogShortcutTarget>>(200)
        private val tmdbDetailsCache = lruCacheMap<String, Pair<TmdbDetailResponse, Long>>(200)
        private val tmdbSeasonCache = lruCacheMap<String, Pair<TmdbSeasonDetail, Long>>(300)
        private val tmdbFindCache = lruCacheMap<String, Pair<Pair<String, String>?, Long>>(5000)
        private val animeMappingCache = lruCacheMap<String, Pair<AnimeSeasonMapping, Long>>(5000)
        private val mdbRatingsCache = lruCacheMap<String, Pair<List<String>, Long>>(1000)
        private val mdbFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mdbFetchSemaphore = Semaphore(5)
        private val mdbInflight = mutableMapOf<MdbTarget, Deferred<SpeculativeMdbResult?>>()
        private val detailLoadHints = lruCacheMap<String, DetailUrlHint>(500)
        private const val METADATA_CACHE_TTL = 24 * 60 * 60 * 1000L
        private const val SEASON_CACHE_TTL = 12 * 60 * 60 * 1000L

        // Rebuild supported poster-service URLs using IDs already in TMDB recommendations.
        // Never guess arbitrary custom patterns or reuse a per-item image signature.
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
            // AIO Metadata signs the original external URL. Do not modify that
            // signature: supported upstream services can be used directly instead.
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
                // The key-based AIO Metadata proxy can independently resolve TMDB IDs.
                // An URL+sig proxy is instead unwrapped above and only used if known.
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

        // Same service URL resolver as the existing TMDB recommendation posters.
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
        // Committed home-page URLs and the raw skip after each response. Used only
        // to seed a browser snapshot; browser paging stays independent.
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

        // Home and Genre may request the same exact page at the same time.
        // Share transport without sharing either caller's pagination state.
        private suspend fun fetchHomeJson(url: String): String {
            val task = synchronized(homeJsonFlights) {
                homeJsonFlights[url]?.also {
                    StremioCCacheAudit.log("catalog", "INFLIGHT_JOIN", url)
                } ?: addonMetaScope.async(start = CoroutineStart.LAZY) {
                    val started = SystemClock.elapsedRealtime()
                    StremioCCacheAudit.log("catalog", "HTTP_START", url)
                    try {
                        val body = fetchHomeJsonUnshared(url)
                        StremioCCacheAudit.log("catalog", "HTTP_DONE", url,
                            "chars=${body.length} durationMs=${SystemClock.elapsedRealtime() - started}")
                        body
                    } catch (error: Throwable) {
                        StremioCCacheAudit.log("catalog", "HTTP_ERROR", url,
                            "error=${error.javaClass.simpleName}")
                        throw error
                    } finally {
                        synchronized(homeJsonFlights) { homeJsonFlights.remove(url) }
                    }
                }.also {
                    StremioCCacheAudit.log("catalog", "INFLIGHT_NEW", url)
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
                        // Other provider classes only expose a no-argument constructor.
                    }
                }
                return clazz.newInstance() as? MainAPI
            }
            val loader = cachedClassLoaderRef?.get()
            if (loader != null) {
                try {
                    val clazz = loader.loadClass(className)
                    return instantiate(clazz)
                } catch (e: Exception) { e.printStackTrace() }
            }
            try {
                val pmClass = Class.forName("com.lagradost.cloudstream3.plugins.PluginManager")
                val pmInstance = pmClass.getField("INSTANCE").get(null)

                for (field in pmClass.declaredFields) {
                    field.isAccessible = true
                    val obj = runSuspendCatching { field.get(pmInstance) }.onFailure { it.printStackTrace() }.getOrNull()
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
                                    // Several plugins may share this publisher; keep searching.
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
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
            e.printStackTrace()
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
        StremioCCacheAudit.log("mdb", "FETCH_ENTER", cacheKey,
            "source=${if (target.isTmdb) "tmdb" else "imdb"} media=${target.mediaType}")
        val cached = stremioCacheMemoryRead("mdb", cacheKey, mdbRatingsCache[cacheKey], METADATA_CACHE_TTL) ?: MetadataDiskCache.read("mdb", cacheKey)?.let { (json, stamp) ->
            runCatching { parseJson<List<String>>(json) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { ratings ->
                    Pair(ratings, stamp).also {
                        mdbRatingsCache[cacheKey] = it
                        StremioCCacheAudit.log("mdb", "RAM_RESTORE_DISK", cacheKey,
                            "ratings=${ratings.size}")
                    }
                }
        }
        DetailLoadPerf.event("MDB_CACHE", "hit=${cached != null}")
        if (cached != null) {
            StremioCCacheAudit.log("mdb", "REUSE_CACHE", cacheKey,
                "ratings=${cached.first.size} ageMs=${System.currentTimeMillis() - cached.second}")
            return SpeculativeMdbResult(target, cached.first, true)
        }
        StremioCCacheAudit.log("mdb", "HTTP_PENDING", cacheKey)

        val attempt = runSuspendCatching {
            DetailLoadPerf.step("mdb.httpAndParse") {
                mdbFetchSemaphore.withPermit {
                    val started = SystemClock.elapsedRealtime()
                    StremioCCacheAudit.log("mdb", "HTTP_START", cacheKey)
                    val values = fetchMdbListRatings(target.id, target.isTmdb, target.mediaType)
                    StremioCCacheAudit.log("mdb", "HTTP_DONE", cacheKey,
                        "durationMs=${SystemClock.elapsedRealtime() - started} ratings=${values.size}")
                    values
                }
            }
        }.onFailure {
            DetailLoadPerf.event("MDB_ERROR", "error=${it.javaClass.simpleName}")
            StremioCCacheAudit.log("mdb", "HTTP_ERROR", cacheKey,
                "error=${it.javaClass.simpleName}")
        }
        val ratings = attempt.getOrDefault(emptyList())
        if (ratings.isNotEmpty()) {
            mdbRatingsCache[cacheKey] = ratings to System.currentTimeMillis()
            StremioCCacheAudit.log("mdb", "RAM_WRITE_OK", cacheKey, "ratings=${ratings.size}")
            MetadataDiskCache.write("mdb", cacheKey, ratings.toJson())
        }
        DetailLoadPerf.event("MDB_RESULT", "success=${attempt.isSuccess} ratings=${ratings.size} type=${target.mediaType}")
        StremioCCacheAudit.log("mdb", "FETCH_RESULT", cacheKey,
            "success=${attempt.isSuccess} ratings=${ratings.size} stored=${ratings.isNotEmpty()}")
        return SpeculativeMdbResult(target, ratings, attempt.isSuccess)
    }

    // Requests are detached so a slow rating endpoint cannot hold the detail coroutineScope open.
    private fun CoroutineScope.launchMdbAttempt(target: MdbTarget?): Deferred<SpeculativeMdbResult?>? =
        target?.let { requested ->
            synchronized(mdbInflight) {
                val auditKey = "${requested.credentialFingerprint}:${requested.isTmdb}:${requested.mediaType}:${requested.id}"
                mdbInflight[requested]?.also {
                    StremioCCacheAudit.log("mdb", "INFLIGHT_JOIN", auditKey)
                } ?: mdbFetchScope.async(start = CoroutineStart.LAZY) {
                    StremioCCacheAudit.log("mdb", "JOB_START", auditKey)
                    DetailLoadPerf.step("task.mdbPrefetch") { fetchMdbAttempt(requested) }
                }.also { job ->
                    StremioCCacheAudit.log("mdb", "INFLIGHT_NEW", auditKey)
                    mdbInflight[requested] = job
                    job.invokeOnCompletion { error ->
                        StremioCCacheAudit.log("mdb", "JOB_COMPLETE", auditKey,
                            "cancelled=${job.isCancelled} failure=${error?.javaClass?.simpleName ?: "none"}")
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
        val cached = stremioCacheMemoryRead("find", imdbId,
            tmdbFindCache[imdbId], METADATA_CACHE_TTL) ?: return false
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

    // The legacy anime-kitsu endpoint is removed. Reuse the single anime mapping
    // service for Kitsu/MAL identity resolution during detail loading, without caching.
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
        val cachedFind = stremioCacheMemoryRead("find", imdbId, tmdbFindCache[imdbId], METADATA_CACHE_TTL) ?: MetadataDiskCache.read("find", imdbId)?.let { (encoded, stamp) ->
            val parts = encoded.split('|')
            if (parts.size == 2 && parts[0].toIntOrNull() != null && parts[1] in listOf("tv", "movie")) {
                Pair(Pair(parts[0], parts[1]), stamp).also {
                    tmdbFindCache[imdbId] = it
                    StremioCCacheAudit.log("find", "RAM_RESTORE_DISK", imdbId)
                }
            } else null
        }
        DetailLoadPerf.event("TMDB_FIND_CACHE", "hit=${cachedFind != null}")
        val result: Pair<String, String>? = if (cachedFind != null) {
            cachedFind.first
        } else {
            val fetchedFind = runSuspendCatching {
                val text = DetailLoadPerf.step("tmdb.find.http") {
                    fetchSharedMetadataText(buildTmdbUrl("/find/$imdbId", "external_source" to "imdb_id"))
                }
                val findRes = DetailLoadPerf.stepNow("tmdb.find.parse") {
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
                StremioCCacheAudit.log("find", "RAM_WRITE_OK", imdbId, "type=${fetchedFind.second}")
                MetadataDiskCache.write("find", imdbId, "${fetchedFind.first}|${fetchedFind.second}")
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
        val cached = stremioCacheMemoryRead("detail", detailUrl, tmdbDetailsCache[detailUrl], METADATA_CACHE_TTL) ?: MetadataDiskCache.read("detail", detailUrl)?.let { (json, stamp) ->
            runCatching { parseJson<TmdbDetailResponse>(json) }.getOrNull()?.let { value ->
                Pair(value, stamp).also {
                    tmdbDetailsCache[detailUrl] = it
                    StremioCCacheAudit.log("detail", "RAM_RESTORE_DISK", detailUrl)
                }
            }
        }
        DetailLoadPerf.event("TMDB_DETAIL_CACHE", "hit=${cached != null} media=$mediaType")
        val result: TmdbDetailResponse? = if (cached != null) {
            cached.first
        } else {
            runSuspendCatching {
                val text = DetailLoadPerf.step("tmdb.detail.http") { fetchSharedMetadataText(detailUrl) }
                val fetched = DetailLoadPerf.stepNow("tmdb.detail.parse") {
                    if (text.isNotBlank()) parseJson<TmdbDetailResponse>(text) else null
                }
                if (fetched != null) {
                    tmdbDetailsCache[detailUrl] = Pair(fetched, System.currentTimeMillis())
                    StremioCCacheAudit.log("detail", "RAM_WRITE_OK", detailUrl)
                    MetadataDiskCache.write("detail", detailUrl, text)
                }
                fetched
            }.onFailure { it.printStackTrace() }.getOrNull()
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
        return runSuspendCatching { IMDB_ID_REGEX.find(url)?.value }.onFailure { it.printStackTrace() }.getOrNull()
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
            StremioCCacheAudit.log("manifest", "REUSE_RAM", currentUrl,
                "ageMs=${now - lastCacheTime} ttlMs=$manifestCacheTtlMs catalogs=${cachedManifest?.catalogs?.size ?: 0}")
            return cachedManifest
        }
        StremioCCacheAudit.log("manifest", if (cachedManifest == null) "RAM_MISS" else "RAM_EXPIRED_OR_CHANGED",
            currentUrl, "ageMs=${now - lastCacheTime} ttlMs=$manifestCacheTtlMs")

        val manifestResponse = runSuspendCatching {
            StremioCCacheAudit.log("manifest", "HTTP_START", currentUrl)
            customSession.get(currentUrl, timeout = 15L)
        }.onFailure {
            StremioCCacheAudit.log("manifest", "HTTP_ERROR", currentUrl,
                "error=${it.javaClass.simpleName}")
            it.printStackTrace()
        }.getOrNull()
        if (manifestResponse != null) StremioCCacheAudit.log("manifest", "HTTP_DONE", currentUrl,
            "code=${manifestResponse.code}")
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
                    StremioCCacheAudit.log("manifest", "RAM_WRITE_OK", currentUrl,
                        "catalogs=${parsed.catalogs.size} ttlMs=${cachePolicy.ttlMs} noStore=${cachePolicy.noStore} versionChanged=${oldVersion != null && oldVersion != newVersion}")
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
                    StremioCCacheAudit.log("manifest", if (cachePolicy.noStore) "STORE_SKIPPED_NO_STORE" else "PREFS_WRITE_OK",
                        currentUrl, "chars=${resText.length} ttlMs=${cachePolicy.ttlMs}")
                    return parsed
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (manifestCacheTtlMs > 0L && lastManifestUrl == currentUrl &&
            now - lastCacheTime <= 72L * 60 * 60 * 1000) {
            StremioCCacheAudit.log("manifest", "REUSE_STALE_RAM_ON_ERROR", currentUrl,
                "ageMs=${now - lastCacheTime}")
            return cachedManifest
        }
        val saved = CloudStreamApp.context
            ?.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
            ?: return null
        val key = stremioCatalogKey(mainUrl)
        val raw = stremioReadManifest(saved, mainUrl) ?: return null
        StremioCCacheAudit.log("manifest", "READ_SAVED_FALLBACK", currentUrl,
            "chars=${raw.length}")
        val savedAt = saved.getLong("manifest_saved_at_" + key, 0L)
        // Legacy stored manifests had no timestamp; preserve them during migration.
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
                StremioCCacheAudit.log("manifest", "REUSE_SAVED_FALLBACK", currentUrl,
                    "catalogs=${it.catalogs.size} ageMs=${now - effectiveAt}")
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
            } catch (e: Exception) { e.printStackTrace() }
        }

        val rank = orderedKeys.withIndex().associate { it.value to it.index }
        val sortedTabs = flatTabs.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
        val enabledTabs = buildList<Pair<String, String>> {
            sortedTabs.forEach { tab ->
                val tabKey = "tab_enabled_${uniqueKey}_${tab.key}"
                val isEnabled = if (prefs.contains(tabKey)) prefs.getBoolean(tabKey, true)
                    else prefs.getBoolean("tab_enabled_${legacyKey}_${tab.key}", true)

                if (isEnabled) add(tab.urlPath to tab.displayName)
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
                    it == "catalog_order_$addonKey" || it.startsWith("tab_enabled_${addonKey}_")
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
        val cursorAuditKey = "$mainUrl|${target.type}|${target.catalogId}"
        if (visible.isEmpty()) {
            StremioCCacheAudit.log("catalog_cursor", "SNAPSHOT_MISS", cursorAuditKey, "reason=empty")
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
            StremioCCacheAudit.log("catalog_cursor", "SNAPSHOT_MISS", cursorAuditKey,
                "reason=invalid_cursor visible=${visible.size}")
            return null
        }
        StremioCCacheAudit.log("catalog_cursor", "SNAPSHOT_HIT", cursorAuditKey,
            "visible=${visible.size} cursor=$last")
        return last
    }

    // Adopt an unfiltered browser page only when Home is still at the same raw skip.
    // Ignore concurrent native Home requests; never reset their generation or paging.
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
                StremioCCacheAudit.log("catalog_cursor", "ADOPT_REJECT", stateKey,
                    "currentSkip=$currentSkip requested=$offset active=$active")
                return@withLock null
            }

            val sent = catalogSentIds.getOrPut(stateKey) { lruSet(500) }
            val accepted = page.filter { sent.add(it.extractPureId()) }
            val nextSkip = offset + rawCount
            catalogSkipState[stateKey] = nextSkip
            val cursors = catalogHomeCursors.getOrPut(stateKey) { ConcurrentHashMap() }
            accepted.forEach { cursors[it.url] = nextSkip }
            StremioCCacheAudit.log("catalog_cursor", "ADOPT_OK", stateKey,
                "requested=$offset raw=$rawCount accepted=${accepted.size} nextSkip=$nextSkip")
            accepted
        }
    }

    private fun getCatalogLogIdentity(request: MainPageRequest): Pair<String, String> {
        val parts = request.data.substringBefore('?').split('/').filter { it.isNotBlank() }
        val catalogIndex = parts.indexOf("catalog")
        val type = parts.getOrNull(catalogIndex + 1) ?: "unknown"
        val id = parts.getOrNull(catalogIndex + 2) ?: "unknown"
        return type to id
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!isAddonEnabled()) return newHomePageResponse(
            listOf(HomePageList(request.name, emptyList<SearchResponse>())), hasNext = false)
        return try {
            executeMainPage(page, request)
        } catch (e: CancellationException) {
            // Navigation/provider switches must still cancel promptly.
            throw e
        } catch (t: Throwable) {
            val status = (t as? CatalogHttpException)?.statusCode?.toString() ?: "none"
            val (catalogType, catalogId) = getCatalogLogIdentity(request)
            com.lagradost.api.Log.w(
                "StremioC",
                "home row isolated provider=$name tab=${request.name} page=$page status=$status errorClass=${t.javaClass.simpleName} catalogType=$catalogType catalogId=$catalogId"
            )
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
            // Save raw response offsets, never filtered item counts.
            val nextSkip = currentSkip + resultPair.second
            filteredItems.forEach { cursors[it.url] = nextSkip }

            newHomePageResponse(
                listOf(HomePageList(request.name, filteredItems)),
                hasNext = request.data.contains("###") && resultPair.second > 0
            )
        }
    }

    // Both callers share transport/parsing, never pagination state. Browser requests do not
    // reset catalogSkipState/catalogSentIds or join the homepage's stateful PageFlight.
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
        // Keep the original addon available for Home/metadata without duplicating search results.
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
                    try { catalog.search(query, this@StremioC) } catch (e: CancellationException) { throw e } catch (e: Exception) { e.printStackTrace(); emptyList() }
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
        // Explicit refresh requests bypass only StremioC's own /meta cache.
        // The marker is stripped before any addon URL, hint or artwork lookup.
        val requested = url.toHttpUrlOrNull()
        val forceRefresh = requested?.queryParameter("__stremioc_refresh") == "1"
        val effectiveUrl = if (forceRefresh) requested!!.newBuilder()
            .removeAllQueryParameters("__stremioc_refresh").build().toString() else url
        StremioCCacheAudit.log("detail_load", "PROVIDER_LOAD_ENTER", effectiveUrl,
            "force=$forceRefresh")
        val trace = DetailLoadPerf.current.get() ?: DetailLoadPerf.Session()
        return withContext(DetailLoadPerf.current.asContextElement(trace)) {
            val source = when {
                url.contains("simkl.com/") -> "simkl"
                url.contains("myanimelist.net/") -> "mal"
                url.contains("anilist.co/") -> "anilist"
                url.contains("kitsu.app/") -> "kitsu"
                url.contains("/meta/") -> "addon-meta"
                else -> "other"
            }
            DetailLoadPerf.event("LOAD_ENTER", "source=$source provider=${if (mainUrl.isBlank()) "library" else "addon"}")
            try {
                val result = DetailLoadPerf.step("load.coroutineScope") { loadInternal(effectiveUrl, forceRefresh) }
                DetailLoadPerf.event("LOAD_RETURN", "type=${result.type} poster=${!result.posterUrl.isNullOrBlank()} backdrop=${!result.backgroundPosterUrl.isNullOrBlank()}")
                StremioCCacheAudit.log("detail_load", "PROVIDER_LOAD_RETURN", effectiveUrl,
                    "type=${result.type} plotChars=${result.plot?.length ?: 0}")
                addonMetaScope.launch {
                    StremioCCacheAudit.log("summary", "USAGE_AFTER_LOAD", effectiveUrl,
                        metadataCacheDiagnostics())
                }
                if (Log.isLoggable("StremioCCache", Log.DEBUG)) {
                    Log.d("StremioCCache", metadataCacheDiagnostics())
                }
                result
            } catch (error: Throwable) {
                DetailLoadPerf.event("LOAD_THROW", "error=${error.javaClass.simpleName}")
                throw error
            }
        }
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
                DetailLoadPerf.event("LIBRARY_SOURCE", "service=$libraryService")
                val metaUrl = DetailLoadPerf.step("library.resolveUrl.$libraryService") {
                    getLoadUrl(libraryService, url)
                } ?: throw IllegalStateException("Unable to resolve the library item with the configured addon.")
                val hint = parseDetailUrlHint(metaUrl)
                DetailLoadPerf.event("LIBRARY_MAPPED", "type=${hint?.type} id=${hint?.id}")
                return@coroutineScope DetailLoadPerf.step("library.delegateLoad") {
                    StremioC(base, name).load(metaUrl).also { response ->
                        // Library details may retain their original URL, not the delegated meta URL.
                        // Copy artwork/title visibility state to that original URL as well.
                        for (source in listOf(metaUrl, response.url)) {
                            fallbackLogoTitles[source]?.let { fallbackLogoTitles[url] = it }
                            expectedLogoByUrl[source]?.let { expectedLogoByUrl[url] = it }
                            trailerChoicesByUrl[source]?.let { trailerChoicesByUrl[url] = it }
                            genreCatalogsByUrl[source]?.let { genreCatalogsByUrl[url] = it }
                        }
                    }
                }
            }
        }
        // Catalogs opened from a metadata Discover link do not require addon registration.
        if (mainUrl.isBlank() && (url.startsWith("https://") || url.startsWith("http://"))) {
            val origin = url.substringBefore("/meta/", "").takeIf { it.isNotBlank() }
            if (origin != null) {
                val delegatedUrl = if (forceRefresh) url.toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("__stremioc_refresh", "1")?.build()?.toString() ?: url else url
                return@coroutineScope StremioC(origin, name).load(delegatedUrl)
            }
        }
        suspend fun fetchSecondaryMeta(metaUrl: String, expectedId: String): CatalogEntry? =
            DetailLoadPerf.step("meta.secondary.total") {
                runSuspendCatching {
                    val text = DetailLoadPerf.step("meta.secondary.http") { fetchAddonMetaText(metaUrl, forceRefresh) }
                    val response = DetailLoadPerf.stepNow("meta.secondary.parse") {
                        if (text.isNotBlank()) parseJson<CatalogResponse>(text) else null
                    }
                    DetailLoadPerf.event("META_SECONDARY_RESULT", "bytes=${text.length} meta=${response?.meta != null} items=${response?.metas?.size ?: 0}")
                    response?.meta ?: response?.metas?.firstOrNull { it.id == expectedId }
                        ?: response?.metas?.firstOrNull()
                }.onFailure { DetailLoadPerf.event("META_SECONDARY_ERROR", "error=${it.javaClass.simpleName}"); it.printStackTrace() }.getOrNull()
            }

        val parsedUrlHint = parseDetailUrlHint(url)
        val requestedAnimeId = parsedUrlHint?.id?.takeIf { isLibraryAnimeId(it) }
        val rememberedHint = detailLoadHints[url]?.takeIf { cached ->
            parsedUrlHint != null &&
                cached.id == parsedUrlHint.id &&
                canonicalDetailType(cached.type) == canonicalDetailType(parsedUrlHint.type)
        }
        val urlHint = rememberedHint ?: parsedUrlHint
        // Start a distinct metadata addon's request alongside the primary meta HTTP
        // request. Validate both the ID/type and resolved URL before using its result.
        val hintedMetaUrl = urlHint?.let { getUnifiedMetaUrl(it.type, it.id) }
            ?.takeUnless { it == url }
        val hintedMetaDeferred = hintedMetaUrl?.let { metaUrl ->
            async(Dispatchers.IO) { DetailLoadPerf.step("task.secondaryPrefetch") { fetchSecondaryMeta(metaUrl, urlHint!!.id) } }
        }
        val hintedImdbId = (urlHint?.id ?: if (url.startsWith("{"))
            runCatching { parseJson<CatalogEntry>(url)?.id }.getOrNull() else null)
            ?.takeIf { IMDB_ID_REGEX.matches(it) }
        val speculativeImdbAnimeDeferred = hintedImdbId?.let { id ->
            async(Dispatchers.IO) {
                DetailLoadPerf.step("task.speculativeImdbAnime") { fetchAnimeSeasonMapping(LoadData(id = id, imdbId = id, isAnime = true)) }
            }
        }
        val hintMediaType = detailMediaType(urlHint?.type)
        val mdbEnabled = isIntegrationEnabled("mdblist_enabled", default = false)
        val directMdbHint = directMdbTarget(urlHint?.id, hintMediaType)

        val hintedAnimeId = urlHint?.id?.takeIf { isLibraryAnimeId(it) }
        val speculativeAnimeDeferred: Deferred<AnimeSeasonMapping?>? = hintedAnimeId?.let { id ->
            async(Dispatchers.IO) { DetailLoadPerf.step("task.speculativeAnime") { resolveAnimeIdForLoad(id) } }
        }

        val speculativeMdbDeferred: Deferred<SpeculativeMdbResult?>? = when {
            !mdbEnabled || urlHint == null -> null
            directMdbHint != null -> launchMdbAttempt(directMdbHint)
            speculativeAnimeDeferred != null -> mdbFetchScope.async {
                val resolved = DetailLoadPerf.step("await.speculativeAnimeForMdb") { speculativeAnimeDeferred.await() } ?: return@async null
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
                    val detail = DetailLoadPerf.step("task.speculativeTmdbDirect") { fetchTmdbDetailForLoad(hintedTmdbId, hintMediaType) }
                    SpeculativeTmdbChain(urlHint?.id?.takeIf { it.startsWith("tt") }, hintedTmdbId, hintMediaType, false, detail)
                }
            }
            urlHint?.id?.startsWith("tt") == true -> async(Dispatchers.IO) {
                val hintId = urlHint?.id ?: return@async null
                val find = DetailLoadPerf.step("task.speculativeTmdbFind") { fetchTmdbFindForLoad(hintId) } ?: return@async null
                val detail = DetailLoadPerf.step("task.speculativeTmdbFromFind") { fetchTmdbDetailForLoad(find.first, find.second) }
                SpeculativeTmdbChain(hintId, find.first, find.second, true, detail)
            }
            urlHint?.id?.startsWith("tmdb:") == true -> {
                val tmdbId = urlHint.id.removePrefix("tmdb:")
                async(Dispatchers.IO) {
                    val detail = DetailLoadPerf.step("task.speculativeTmdbPrefixed") { fetchTmdbDetailForLoad(tmdbId, hintMediaType) }
                    SpeculativeTmdbChain(null, tmdbId, hintMediaType, false, detail)
                }
            }
            speculativeAnimeDeferred != null -> async(Dispatchers.IO) {
                val resolved = DetailLoadPerf.step("await.speculativeAnimeForTmdb") { speculativeAnimeDeferred.await() } ?: return@async null
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
            DetailLoadPerf.stepNow("meta.embedded.parse") { parseJson<CatalogEntry>(url) }
        } else {
            val responseText = DetailLoadPerf.step("meta.primary.http") { fetchAddonMetaText(url, forceRefresh) }
            try {
                DetailLoadPerf.stepNow("meta.primary.parse") {
                    val rootNode = parseJson<JsonNode>(responseText)
                    val metaNode = if (rootNode.has("meta")) rootNode.get("meta") else rootNode
                    val parsedEntry = parseJson<CatalogEntry>(metaNode.toString())
                        ?: throw IllegalStateException("Failed to bind JSON to CatalogEntry")
                    if (rootNode.has("meta") && metaNode.isObject) {
                        initialMetaFromResponse = parsedEntry
                    }
                    DetailLoadPerf.event("META_PRIMARY_RESULT", "bytes=${responseText.length} type=${parsedEntry.type} id=${parsedEntry.id} videos=${parsedEntry.videos?.size ?: 0}")
                    parsedEntry
                }
            } catch (e: Exception) {
                e.printStackTrace()
                throw IllegalStateException("Metadata not found or parsing failed: $url", e)
            }
        }

        DetailLoadPerf.event("PREFETCH_STATE", "hasHint=${urlHint != null} secondary=${hintedMetaDeferred != null} imdbAnime=${speculativeImdbAnimeDeferred != null} anime=${speculativeAnimeDeferred != null} mdb=${speculativeMdbDeferred != null} tmdb=${speculativeTmdbDeferred != null}")
        val hintMatchesAuthoritative = urlHint != null &&
            urlHint.id == res.id &&
            canonicalDetailType(urlHint.type) == canonicalDetailType(res.type)

        if (!hintMatchesAuthoritative && urlHint != null) {
            // MDB work is detached and keyed by its original ID; it can still populate the cache.
            if (requestedAnimeId == null) speculativeTmdbDeferred?.cancel()
            // A rewritten IMDb meta ID does not invalidate the original anime ID lookup.
            if (hintedAnimeId != requestedAnimeId) speculativeAnimeDeferred?.cancel()
            // This lookup only depends on the IMDb ID, not movie/series type.
            if (hintedImdbId != res.id) speculativeImdbAnimeDeferred?.cancel()
        }

        val normalizedId = try { normalizeId(res.id ?: "") } catch (e: Exception) { e.printStackTrace(); res.id ?: "" }
        val encodedId = normalizedId.urlEncode()

        val authoritativeMetaUrl = getUnifiedMetaUrl(res.type ?: "movie", res.id ?: "")
        val reuseHintedMeta = hintMatchesAuthoritative && hintedMetaUrl == authoritativeMetaUrl
        if (!reuseHintedMeta) hintedMetaDeferred?.cancel()
        val addonDeferred: Deferred<CatalogEntry?>? = when {
            // The primary response already contains this exact metadata URL;
            // don't issue another GET (or create a deferred for the same object).
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
            val candidate = DetailLoadPerf.step("await.originalAnimeMapping") {
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
                DetailLoadPerf.step("await.speculativeTmdb") { speculativeTmdbDeferred.await() }?.let { spec ->
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
                DetailLoadPerf.step("task.normalTmdbDetail") {
                    fetchTmdbDetailForLoad(targetTmdbId, targetTmdbMediaType)
                }
            }
        } else {
            null
        }

        val preFetchedTmdbDetail = speculativeTmdbDetail ?: DetailLoadPerf.step("await.normalTmdbDetail") { normalTmdbDeferred?.await() }
        DetailLoadPerf.event("TMDB_PREFETCH_RESULT", "spec=${speculativeTmdbDetail != null} hasDetail=${preFetchedTmdbDetail != null} media=$targetTmdbMediaType")
        // Movie collections are independent of the second metadata addon's response.
        // Fetch them as soon as an authoritative movie detail exposes a collection ID.
        DetailLoadPerf.event("LOAD_ENRICH_PLAN", "tmdb=${targetTmdbId != null} imdb=${!resolvedImdbId.isNullOrBlank() || !preFetchedTmdbDetail?.external_ids?.imdb_id.isNullOrBlank()} mdb=$mdbEnabled")
        val collectionDeferred: Deferred<List<TmdbMedia>>? = if (targetTmdbMediaType == "movie") {
            preFetchedTmdbDetail?.belongs_to_collection?.id?.let { collectionId ->
                async(Dispatchers.IO) {
                    runSuspendCatching {
                        val text = DetailLoadPerf.step("tmdb.collection.prefetch.http") {
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

        val loadRes = DetailLoadPerf.step("toLoadResponse.total") { res.toLoadResponse(
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
        // The detail fragment keeps the originally requested URL (e.g. kitsu:...),
        // while the returned metadata may use the mapped IMDb URL. Share the same
        // logo visibility decisions under both exact keys without another request.
        if (url != loadRes.url) {
            fallbackLogoTitles[loadRes.url]?.let { fallbackLogoTitles[url] = it }
            expectedLogoByUrl[loadRes.url]?.let { expectedLogoByUrl[url] = it }
            trailerChoicesByUrl[loadRes.url]?.let { trailerChoicesByUrl[url] = it }
            genreCatalogsByUrl[loadRes.url]?.let { genreCatalogsByUrl[url] = it }
            ActorInformation.alias(this@StremioC, url, loadRes.url)
        }
        // MAL/AniList normally enter via getLoadUrl() -> load(metaUrl), not
        // load(libraryUrl). Mirror the decision to the fragment's original URL.
        val libraryOrigins = synchronized(libraryOriginalUrlsByMeta) {
            libraryOriginalUrlsByMeta[url]?.toList().orEmpty()
        }
        for (originalUrl in libraryOrigins) {
            fallbackLogoTitles[url]?.let { fallbackLogoTitles[originalUrl] = it }
            expectedLogoByUrl[url]?.let { expectedLogoByUrl[originalUrl] = it }
            trailerChoicesByUrl[url]?.let { trailerChoicesByUrl[originalUrl] = it }
            genreCatalogsByUrl[url]?.let { genreCatalogsByUrl[originalUrl] = it }
        }
        collectionDeferred?.cancel() // No remaining prefetch when the final media type differs.

        var actualMdbTarget = resolvedMdbTarget(res, resolvedTmdbId, resolvedImdbId)
        if (actualMdbTarget == null) {
            val resolvedImdb = resolvedIdHolder[0]?.takeIf { it.startsWith("tt") }
            val resolvedTmdb = resolvedIdHolder[1]?.takeIf { it.isNotBlank() }
            actualMdbTarget = resolvedImdb?.let { MdbTarget(it, false, authoritativeMediaType) }
                ?: resolvedTmdb?.let { MdbTarget(it, true, authoritativeMediaType) }
        }

        val finalMdbTarget = actualMdbTarget
        if (finalMdbTarget != null && mdbEnabled) {
            // Always await the authoritative ID, not a speculative request for a different ID.
            // The detached request keeps populating the cache after the display budget expires.
            val authoritativeMdb = if (postMetaMdbTarget == finalMdbTarget) {
                postMetaMdbDeferred ?: launchMdbAttempt(finalMdbTarget)
            } else launchMdbAttempt(finalMdbTarget)
            val mdbRatings = withTimeoutOrNull(4000L) {
                DetailLoadPerf.step("await.mdbForFinalTarget") { authoritativeMdb?.await() }
                    ?.takeIf { it.matches(finalMdbTarget) }?.ratings
            }
            val mdbAuditKey = "${finalMdbTarget.credentialFingerprint}:${finalMdbTarget.isTmdb}:${finalMdbTarget.mediaType}:${finalMdbTarget.id}"
            StremioCCacheAudit.log("mdb", if (mdbRatings == null) "DISPLAY_TIMEOUT_OR_NO_RESULT" else "DISPLAY_RESULT",
                mdbAuditKey, "ratings=${mdbRatings?.size ?: 0} finished=${authoritativeMdb?.isCompleted}")
            mdbRatings?.takeIf { it.isNotEmpty() }?.let { ratings ->
                val ratingStr = "\u2063" + ratings.joinToString("  ")
                val currentPlot = loadRes.plot
                loadRes.plot = if (!currentPlot.isNullOrBlank()) "${ratingStr}<br>${currentPlot}" else ratingStr
                loadRes.score = Score.from10(0)
            }
        }

        DetailLoadPerf.event("LOAD_RESULT_READY", "type=${loadRes.type} mdbEnabled=$mdbEnabled")
        return@coroutineScope loadRes
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (!isAddonEnabled()) return false
        val loadData = try { parseJson<LoadData>(data) } catch (e: Exception) { e.printStackTrace(); null } ?: return false
        
        val legacyPrimaryId = if (loadData.id?.startsWith("tmdb:") == true && !loadData.imdbId.isNullOrBlank()) {
            loadData.imdbId
        } else {
            loadData.id ?: ""
        }

        val primaryId = loadData.originalAnimeId ?: legacyPrimaryId
        val globalId = if (!loadData.imdbId.isNullOrBlank()) loadData.imdbId else legacyPrimaryId
        val targetType = loadData.type ?: if ((loadData.season ?: 0) > 0 || (loadData.episode ?: 0) > 0) "series" else "movie"
        
        val actualIsMovie = targetType == "movie" || (loadData.season == null && loadData.episode == null)

        // Keep the original Stremio type for addon/VixSrc requests. CineStream and
        // StreamPlay only understand movie/series, so normalize non-standard types
        // only when this item already has a valid IMDb identity.
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
        // Only deduplicate subtitles supplied directly by Stremio addons.
        // CloudStream extractors/integrations must keep using the original callback.
        val seenStremioSubtitles = Collections.synchronizedSet(mutableSetOf<String>())
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

        val copyId = loadData.imdbId ?: loadData.id ?: ""
        if (!copyId.isNullOrBlank()) {
            wrappedCallback.invoke(
                newExtractorLink(
                    "Copy to clipboard",
                    "📋 Video ID: $copyId",
                    copyId,
                    ExtractorLinkType.VIDEO
                )
                {
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        val currentMainUrl = loadData.addonUrl?.takeIf { it.isNotBlank() } ?: mainUrl
        val cleanBaseUrl = currentMainUrl.fixSourceUrl().cleanBaseUrl()
        val querySuffix = currentMainUrl.substringAfter("?", "").takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""

        val syncSettings = getSyncSettings()
        val (syncUrl, _, historySyncMap) = syncSettings
        val currentCleanUrl = currentMainUrl.cleanBaseUrl().lowercase()
        val isHistorySyncEnabled = historySyncMap[currentCleanUrl] ?: false
        val extraUrls = if (syncSettings.streamSubtitleMap[currentCleanUrl] == true) {
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
                t.printStackTrace()
                try {
                    val prefs = CloudStreamApp.context?.let { androidx.preference.PreferenceManager.getDefaultSharedPreferences(it) }
                    val v = prefs?.getString("stremio_addons", "") ?: ""
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v.substring(1, v.length - 1) else v
                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
            }
            
            val hosts = raw.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { URI(it.split("|").getOrElse(1) { "" }).host }.onFailure { it.printStackTrace() }.getOrNull() }
                .toSet() 
                
            cachedCustomHosts = hosts
            cachedCustomHostsTime = now
            hosts
        }
        val builtInHosts = setOf("opensubtitles.stremio.homes", "subsense.nepiraw.com", "opensubtitles-v3.strem.io")

        // Keep the original URL-based label handling for subtitles returned by
        // CineStream, StreamPlay and VixSrc. These providers can also return
        // CloudStream subtitles, so do not deduplicate them here.
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
                    subtitleCallback.invoke(SubtitleFile(newLang, subtitleUrl))
                } else {
                    subtitleCallback.invoke(sub)
                }
            } catch (e: Exception) {
                subtitleCallback.invoke(sub)
            }
        }

        val stremioSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (sub.url.isNotBlank() && seenStremioSubtitles.add(sub.url)) {
                // These subtitles originated in StremioC, not external integrations.
                // Do not re-append the legacy (Stremio) marker.
                subtitleCallback.invoke(sub)
            }
        }

        // Avoid issuing the exact same subtitle resource request twice when the
        // active addon and history-sync addon resolve to an identical URL.
        val requestedSubtitleUrls = Collections.synchronizedSet(mutableSetOf<String>())

        suspend fun fetchStremioSubtitles(
            addonUrl: String,
            id: String?,
            season: Int?,
            episode: Int?,
            requestType: String = targetType,
            preferredName: String? = null
        ) {
            if (addonUrl.isBlank() || id.isNullOrBlank()) return

            val subId = buildStremioId(
                requestType,
                id,
                season,
                episode
            ) ?: id
            if (subId.isBlank()) return

            val cleanSubtitleBase = addonUrl.fixSourceUrl().cleanBaseUrl()
            if (cleanSubtitleBase.isBlank()) return

            val subtitleSuffix = addonUrl.substringAfter("?", "")
                .takeIf { it.isNotEmpty() }
                ?.let { "?$it" }
                ?: ""
            val subtitleRequestUrl =
                "$cleanSubtitleBase/subtitles/$requestType/${subId.urlEncode()}.json$subtitleSuffix"

            if (!requestedSubtitleUrls.add(subtitleRequestUrl)) return

            runSuspendCatching {
                val text = customSession.get(subtitleRequestUrl, timeout = 15L).text
                val res = if (text.isNotBlank()) parseJson<SubtitlesResponse>(text) else null
                val addonSource = preferredName?.takeIf { it.isNotBlank() }
                    ?: getActualStremioAddonName(addonUrl)
                    ?: name.takeIf { addonUrl == currentMainUrl && !it.equals("StremioC", true) }
                res?.subtitles?.forEach { sub ->
                    sub.toCloudStreamSubtitle(addonSource, preferredName)?.let(stremioSubtitleCallback)
                }
            }.onFailure { it.printStackTrace() }
        }

        coroutineScope {
        val mappingDeferred = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            if (!actualIsMovie) fetchAnimeSeasonMapping(loadData) else null
        }
        // Use one IMDb ID and season for direct streams, extra streams and subtitles.
        // One mapping response is shared within this loadLinks call; it is never cached.
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
            mappedAnimeStremioId()?.let { return "series" to it }
            if (isLibraryAnimeId(id)) {
                return if (actualIsMovie) integrationImdbId?.let { "movie" to it } else null
            }
            if (id.isBlank()) return null
            return type to (buildStremioId(type, id, season, episode) ?: id)
        }
        runAllAsync(
            {
                // Embedded streams add sources without suppressing regular /stream requests.
                loadData.embeddedStreams?.let { json ->
                    runSuspendCatching {
                        parseJson<List<Stream>>(json).forEach { stream ->
                            val named = if (stream.name == null && stream.title == null && stream.description == null)
                                stream.copy(title = loadData.embeddedStreamTitle) else stream
                            named.runCallback(this@StremioC, name,
                                stremioSubtitleCallback, subtitleCallback, wrappedCallback)
                        }
                    }.onFailure { it.printStackTrace() }
                }
            },
            {
                val request = streamRequest(targetType, primaryId, loadData.season, loadData.episode)
                if (cleanBaseUrl.isNotBlank() && request != null) {
                    runSuspendCatching {
                        val streamRequestUrl = "$cleanBaseUrl/stream/${request.first}/${request.second.urlEncode()}.json$querySuffix"
                        val text = customSession.get(streamRequestUrl, timeout = 25L).text
                        val res = if (text.isNotBlank()) parseJson<StreamsResponse>(text) else null
                        res?.streams?.forEach { stream ->
                            stream.runCallback(
                                this@StremioC,
                                name,
                                stremioSubtitleCallback,
                                subtitleCallback,
                                wrappedCallback
                            )
                        }
                    }.onFailure { it.printStackTrace() }
                }
            },
            {
                // Fetch subtitle resources exposed by the currently selected
                // Stremio addon itself (e.g. AIOStreams aggregated subtitles).
                val mappedId = mappedAnimeStremioId()
                if (mappedId != null) {
                    fetchStremioSubtitles(currentMainUrl, mappedId, null, null, "series")
                } else if (!isLibraryAnimeId(primaryId) || actualIsMovie) {
                    fetchStremioSubtitles(currentMainUrl, primaryId, loadData.season, loadData.episode)
                }
            },
            {
                if (isHistorySyncEnabled && syncUrl.isNotBlank()) {
                    fetchStremioSubtitles(
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
                                    if (streamRequest != null) runSuspendCatching {
                                        val requestUrl = "$base/stream/${streamRequest.first}/${streamRequest.second.urlEncode()}.json$suffix"
                                        val text = customSession.get(requestUrl, timeout = 25L).text
                                        val streams = if (text.isNotBlank()) parseJson<StreamsResponse>(text) else null
                                        streams?.streams?.forEach { stream ->
                                            stream.runCallback(this@StremioC, source,
                                                stremioSubtitleCallback, subtitleCallback, wrappedCallback,
                                                preferredName = addon.name)
                                        }
                                    }.onFailure { it.printStackTrace() }
                                }, {
                                    val mappedId = mappedAnimeStremioId()
                                    if (mappedId != null) {
                                        fetchStremioSubtitles(addonUrl, mappedId, null, null, "series", addon.name)
                                    } else if (!isLibraryAnimeId(reqId) || actualIsMovie) {
                                        fetchStremioSubtitles(addonUrl, reqId, season, episode, extraType, addon.name)
                                    }
                                })
                            }
                        }
                    } }.awaitAll()
                }
            },
            { 
                invokeCineStream(loadData, integrationTargetType, globalId, globalSeason, globalEpisode, isCasting, integrationSubtitleCallback, wrappedCallback, mappingDeferred) 
            },
            {
                invokeTmdbStremioIntegrationFallback(
                    loadData,
                    targetType,
                    globalId,
                    globalSeason,
                    globalEpisode,
                    stremioSubtitleCallback,
                    subtitleCallback,
                    wrappedCallback
                )
            },
            { 
                invokeStreamPlay(loadData, integrationTargetType, globalId, globalSeason, globalEpisode, isCasting, integrationSubtitleCallback, wrappedCallback, mappingDeferred) 
            },
            {
                if (isIntegrationEnabled("vixsrc_integration_enabled")) {
                    VixSrcExtractor.invokeVixSrc(loadData, targetType, globalSeason, globalEpisode, integrationSubtitleCallback, wrappedCallback)
                }
            }
        )
        mappingDeferred.cancel() // LAZY deferred must not keep the scope open when no integration uses it.
        }
        return true
    }
        
    private data class AnimeSeasonMapping(
        val kitsuId: String?, val malId: Int?, val aniListId: Int?, val tmdbId: Int?,
        val tmdbSeason: Int?, val tmdbEpisode: Int?, val title: String?,
        val imdbId: String?, val tvdbSeason: Int?, val imdbSeason: Int?,
        val traktSeason: Int?
    )

    private suspend fun fetchAnimeSeasonMapping(loadData: LoadData): AnimeSeasonMapping? {
        val mapStarted = SystemClock.elapsedRealtimeNanos()
        DetailLoadPerf.event("ANIME_MAP_ENTER", "source=${loadData.originalAnimeId?.substringBefore(':') ?: if (loadData.imdbId != null) "imdb" else "other"} season=${loadData.season ?: loadData.imdbSeason} episode=${loadData.episode ?: loadData.imdbEpisode}")
        try {
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
        val cachedAnime = stremioCacheMemoryRead("anime", query, animeMappingCache[query], METADATA_CACHE_TTL) ?: MetadataDiskCache.read("anime", query)?.let { (json, stamp) ->
            runCatching { parseJson<AnimeSeasonMapping>(json) }.getOrNull()?.let { value ->
                Pair(value, stamp).also {
                    animeMappingCache[query] = it
                    StremioCCacheAudit.log("anime", "RAM_RESTORE_DISK", query)
                }
            }
        }
        DetailLoadPerf.event("ANIME_MAP_CACHE", "hit=${cachedAnime != null}")
        if (cachedAnime != null) return cachedAnime.first

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
            val mapping = runSuspendCatching {
            val response = DetailLoadPerf.step("anime.host.$host.http") {
                fetchSharedAnimeText("https://$host/api/v1/anime?$query")
            }
            val root = DetailLoadPerf.stepNow("anime.host.$host.parse") { parseJson<JsonNode>(response) }
            if (root.get("success")?.asBoolean() != true) return@runSuspendCatching null
            if (root.get("data")?.isNull == true) {
                noMapping = true
                return@runSuspendCatching null
            }
            val data = root.get("data") ?: return@runSuspendCatching null
            val ids = data.get("mappings") ?: return@runSuspendCatching null
            val resolvedKitsu = ids.get("kitsuId").intOrNull()?.takeIf { it > 0 }?.toString()
            val resolvedImdb = ids.get("imdbId").textOrNull()
            if (byKitsu && resolvedKitsu != kitsuId) return@runSuspendCatching null
            if ((byMal || imdbId == null && kitsuId == null && malLookupId != null && !byAniList) &&
                ids.get("malId").intOrNull() != malLookupId) return@runSuspendCatching null
            if (byAniList && ids.get("anilistId").intOrNull() != aniListLookupId)
                return@runSuspendCatching null
            if (imdbId != null && resolvedImdb != imdbId) return@runSuspendCatching null
            if (resolvedKitsu == null && ids.get("malId").intOrNull() == null &&
                ids.get("anilistId").intOrNull() == null) return@runSuspendCatching null
            // AIOStreams may return its first record if season matching fails.
            // Do not silently accept a different season for an IMDb episode.
            if (!byKitsu && !byMal && !byAniList && season != null && season > 1) {
                val mappedSeason = data.get("imdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
                    ?: data.get("tvdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
                    ?: data.get("trakt")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
                    ?: data.get("tmdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
                if (mappedSeason != season) return@runSuspendCatching null
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
                else -> null // No proven offset between these coordinate systems.
            }
            AnimeSeasonMapping(
                kitsuId = resolvedKitsu,
                malId = ids.get("malId").intOrNull()?.takeIf { it > 0 },
                aniListId = ids.get("anilistId").intOrNull()?.takeIf { it > 0 },
                tmdbId = ids.get("themoviedbId").intOrNull()?.takeIf { it > 0 },
                tmdbSeason = tmdbSeason,
                tmdbEpisode = tmdbEpisode,
                title = data.get("title").textOrNull(),
                imdbId = resolvedImdb?.takeIf { IMDB_ID_REGEX.matches(it) },
                tvdbSeason = data.get("tvdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 },
                imdbSeason = data.get("imdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 },
                traktSeason = data.get("trakt")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
            )
            }.onFailure { DetailLoadPerf.event("ANIME_HOST_ERROR", "host=$host error=${it.javaClass.simpleName}") }.getOrNull()
            DetailLoadPerf.event("ANIME_HOST_RESULT", "host=$host dataNull=$noMapping mapped=${mapping != null}")
            if (noMapping) return null
            if (mapping != null) {
                animeMappingCache[query] = mapping to System.currentTimeMillis()
                StremioCCacheAudit.log("anime", "RAM_WRITE_OK", query)
                MetadataDiskCache.write("anime", query, mapping.toJson())
                return mapping
            }
        }
        return null
        } finally {
            DetailLoadPerf.event("ANIME_MAP_EXIT", "tookMs=${(SystemClock.elapsedRealtimeNanos() - mapStarted) / 1_000_000L}")
        }
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
        // The unmodified StreamPlay accepts one season/episode pair. Its
        // anime sources need the original pair. For its general-only route,
        // TMDB takes the original pair and IMDb-only content uses the mapping.
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
        val subtitleOnly: Boolean
    )

    // Read actual addon identity; never infer it from an individual subtitle download URL.
    // StremioC may already have the manifest cached from normal addon loading.
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
                        IntegrationStremioAddon(parts[0].trim().ifBlank { url }, url, subtitleOnly)
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
                            IntegrationStremioAddon(name, url, subtitleOnly)
                        )
                    }
                }
            }.onFailure { it.printStackTrace() }
        }

        return addons.values.filter { isCatalogAddonEnabled(it.url) }
    }

    private suspend fun invokeTmdbStremioIntegrationFallback(
        loadData: LoadData,
        targetType: String,
        globalId: String?,
        globalSeason: Int?,
        globalEpisode: Int?,
        stremioSubtitleCallback: (SubtitleFile) -> Unit,
        extractorSubtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (targetType != "movie" && targetType != "series") return

        val media = integrationData(loadData, targetType, globalId, globalSeason, globalEpisode)
        if (!media.imdbId.isNullOrBlank()) return

        val tmdbId = media.tmdbId ?: return
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
                            res?.subtitles?.forEach { sub ->
                                sub.toCloudStreamSubtitle(addon.name)?.let(stremioSubtitleCallback)
                            }
                        } else {
                            val requestUrl = "$cleanBaseUrl/stream/$targetType/${encodedId}.json$querySuffix"
                            val text = customSession.get(requestUrl, timeout = 25L).text
                            val res = if (text.isNotBlank()) parseJson<StreamsResponse>(text) else null

                            res?.streams?.forEach { stream ->
                                stream.runCallback(
                                    this@StremioC,
                                    addon.name,
                                    stremioSubtitleCallback,
                                    extractorSubtitleCallback,
                                    callback
                                )
                            }
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        error.printStackTrace()
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
        if (!isIntegrationEnabled("cinestream_integration_enabled")) return
        if (targetType != "movie" && targetType != "series") return

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
                    cineStreamProvider.loadLinks(
                        cinePayload(
                            loadData, targetType, media, animeRoute,
                            japaneseTitle, standardImdbRoute
                        ).toJson(),
                        isCasting,
                        subtitleCallback,
                        trackedCallback
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (media.isAnime) {
                // The original CineStream dispatches disjoint anime and
                // standard implementations. Both entry points are required
                // to reach standard-only providers for anime.
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
            e.printStackTrace()
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
        if (!isIntegrationEnabled("streamplay_integration_enabled")) return
        if (targetType != "movie" && targetType != "series") return

        try {
            val media = integrationData(loadData, targetType, globalId, globalSeason, globalEpisode,
                mappingDeferred?.await())
            val streamPlayProvider = getStreamPlayProvider()

            if (!media.isAnime) {
                if (streamPlayProvider != null) {
                    withTimeoutOrNull(55000L) {
                        streamPlayProvider.loadLinks(
                            streamPlayPayload(loadData, targetType, media).toJson(),
                            isCasting, subtitleCallback, callback
                        )
                    }
                }
                return
            }

            supervisorScope {
                // This is a separate original StreamPlay API with its own anime
                // invokers. Call it even if the main API found a link.
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
                                animeProvider.loadLinks(
                                    animePayload.toJson(), isCasting, subtitleCallback, callback
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                if (streamPlayProvider != null) {
                    val anime = async(Dispatchers.IO) {
                        try {
                            if (media.hasSeasonMapping || (loadData.originalAnimeId == null && (media.season ?: 1) <= 1)) withTimeoutOrNull(35000L) {
                                streamPlayProvider.loadLinks(
                                    streamPlayPayload(loadData, targetType, media).toJson(),
                                    isCasting, subtitleCallback, callback
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    val general = if (media.hasSeasonMapping && media.tmdbSeason != null && media.tmdbEpisode != null && media.tmdbId != null ||
                        !media.hasSeasonMapping && loadData.originalAnimeId == null && (media.season ?: 1) <= 1 &&
                        (media.imdbId != null || media.tmdbId != null)) {
                        async(Dispatchers.IO) {
                            try {
                                withTimeoutOrNull(35000L) {
                                    streamPlayProvider.loadLinks(
                                        streamPlayPayload(
                                            loadData, targetType, media, generalAnimeRoute = true
                                        ).toJson(),
                                        isCasting, subtitleCallback, callback
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    } else null
                    val imdbGeneral = if (media.imdbId != null &&
                        media.imdbSeason != null && media.imdbEpisode != null &&
                        (media.hasSeasonMapping || (loadData.originalAnimeId == null && (media.season ?: 1) > 1))) {
                        async(Dispatchers.IO) {
                            runSuspendCatching {
                                withTimeoutOrNull(35000L) {
                                    streamPlayProvider.loadLinks(
                                        streamPlayPayload(loadData, targetType, media,
                                            generalAnimeRoute = true, imdbOnlyRoute = true).toJson(),
                                        isCasting, subtitleCallback, callback
                                    )
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
            e.printStackTrace()
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
        @JsonProperty("originalAnimeId") @JsonAlias("originalKitsuId") val originalAnimeId: String? = null, // Original kitsu:, mal:, or anilist: ID
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
            } catch (e: Throwable) { e.printStackTrace(); emptyList() }
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
            recommendationPoster: String? = null
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
            val cached = stremioCacheMemoryRead("detail", cacheKey, tmdbDetailsCache[cacheKey], METADATA_CACHE_TTL) ?: MetadataDiskCache.read("detail", cacheKey)?.let { (json, stamp) ->
                runCatching { parseJson<TmdbDetailResponse>(json) }.getOrNull()?.let { value ->
                    Pair(value, stamp).also {
                    tmdbDetailsCache[cacheKey] = it
                    StremioCCacheAudit.log("detail", "RAM_RESTORE_DISK", cacheKey)
                }
                }
            }
            DetailLoadPerf.event("TMDB_ENRICH_CACHE", "prefetch=${preFetchedDetail != null} cacheHit=${cached != null} media=$tmdbMediaType")
            val detailRes = preFetchedDetail ?: if (cached != null) {
                cached.first
            } else {
                val text = DetailLoadPerf.step("tmdb.enrich.detail.http") { fetchSharedMetadataText(detailUrl) }
                val fetched = DetailLoadPerf.stepNow("tmdb.enrich.detail.parse") {
                    if (text.isNotBlank()) parseJson<TmdbDetailResponse>(text) else null
                }
                if (fetched != null) {
                    tmdbDetailsCache[cacheKey] = Pair(fetched, System.currentTimeMillis())
                    StremioCCacheAudit.log("detail", "RAM_WRITE_OK", cacheKey)
                    MetadataDiskCache.write("detail", cacheKey, text)
                }
                fetched
            }
            
            if (detailRes == null) {
                return FetchedTmdbData(null, null, null, emptyList(), null, null, null, null, null, null, emptyMap(), emptyList(), null, null, null)
            }

            val episodeTmdbMeta = ConcurrentHashMap<String, TmdbEpisode>()

            val context = CloudStreamApp.context
            val prefs = context?.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
            val langCode = prefs?.getString("tmdb_language_code", "en-US") ?: "en-US"
            val metadataHost = provider.metadataPosterAddonBaseUrl()?.toHttpUrlOrNull()?.host
            
            coroutineScope {
                val shouldFocusEpisodes = (!isMovie && targetVideos.isNullOrEmpty()) || (stremioType != "movie" && stremioType != "series" && !isMovie)
                
                DetailLoadPerf.event("TMDB_EPISODE_PLAN", "required=$shouldFocusEpisodes knownVideos=${targetVideos?.size ?: 0} totalSeasons=${detailRes.number_of_seasons ?: 0}")
                val episodeDeferred = if (shouldFocusEpisodes) {
                    var requiredSeasons = targetVideos?.mapNotNull { it.seasonNumber }?.filter { it > 0 }?.distinct() ?: emptyList()
                    if (requiredSeasons.isEmpty()) {
                        val totalSeasons = detailRes.number_of_seasons ?: 1
                        requiredSeasons = if (totalSeasons > 0) (1..totalSeasons).toList() else listOf(1)
                    }
                    
                    DetailLoadPerf.event("TMDB_EPISODE_SCHEDULE", "count=${requiredSeasons.size} seasons=${requiredSeasons.joinToString(",")}")
                    requiredSeasons.map { seasonNum ->
                        async(Dispatchers.IO) {
                            DetailLoadPerf.step("tmdb.season.$seasonNum.total") {
                            DetailLoadPerf.step("tmdb.season.$seasonNum.semaphore") {
                            StremioC.networkSemaphore.withPermit {
                                val completed = withTimeoutOrNull(10000L) {
                                    runSuspendCatching {
                                        val seasonUrl = provider.buildTmdbUrl("/tv/$tmdbIdStr/season/$seasonNum")
                                        val seasonCacheKey = seasonUrl
                                        val cachedSeason = stremioCacheMemoryRead("season", seasonCacheKey, tmdbSeasonCache[seasonCacheKey], SEASON_CACHE_TTL) ?: MetadataDiskCache.read("season", seasonCacheKey)?.let { (json, stamp) ->
                                            runCatching { parseJson<TmdbSeasonDetail>(json) }.getOrNull()?.let { value ->
                                                Pair(value, stamp).also {
                                                    tmdbSeasonCache[seasonCacheKey] = it
                                                    StremioCCacheAudit.log("season", "RAM_RESTORE_DISK", seasonCacheKey)
                                                }
                                            }
                                        }
                                        DetailLoadPerf.event("TMDB_SEASON_CACHE", "season=$seasonNum hit=${cachedSeason != null}")
                                        val seasonRes = if (cachedSeason != null) {
                                            cachedSeason.first
                                        } else {
                                            val fetchedSeasonText = DetailLoadPerf.step("tmdb.season.$seasonNum.http") {
                                                fetchSharedMetadataText(seasonUrl)
                                            }
                                            val fetchedSeason = DetailLoadPerf.stepNow("tmdb.season.$seasonNum.parse") {
                                                if (fetchedSeasonText.isNotBlank()) parseJson<TmdbSeasonDetail>(fetchedSeasonText) else null
                                            }
                                            if (fetchedSeason != null) {
                                                tmdbSeasonCache[seasonCacheKey] = Pair(fetchedSeason, System.currentTimeMillis())
                                                StremioCCacheAudit.log("season", "RAM_WRITE_OK", seasonCacheKey,
                                                    "season=$seasonNum")
                                                MetadataDiskCache.write("season", seasonCacheKey, fetchedSeasonText)
                                            }
                                            fetchedSeason
                                        }
                                        seasonRes?.episodes?.forEach { ep -> if (ep.episodeNumber != null) episodeTmdbMeta["${seasonNum}_${ep.episodeNumber}"] = ep }
                                    }.onFailure {
                                        DetailLoadPerf.event("TMDB_SEASON_ERROR", "season=$seasonNum error=${it.javaClass.simpleName}")
                                        it.printStackTrace()
                                    }
                                    true
                                }
                                if (completed == null) DetailLoadPerf.event("TMDB_SEASON_TIMEOUT", "season=$seasonNum timeoutMs=10000")
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
                            val colText = DetailLoadPerf.step("tmdb.collection.http") {
                                fetchSharedMetadataText(colUrl)
                            }
                            DetailLoadPerf.stepNow("tmdb.collection.parse") {
                                if (colText.isNotBlank()) parseJson<TmdbCollectionDetail>(colText).parts ?: emptyList() else emptyList()
                            }
                        }.getOrDefault(emptyList())
                    } else emptyList()
                    parts
                }

                val collectionParts = DetailLoadPerf.step("await.tmdbCollection") { collectionDeferred.await() }
                DetailLoadPerf.step("await.tmdbEpisodesAll") { episodeDeferred?.awaitAll() }
                DetailLoadPerf.event("TMDB_SUBREQUESTS_DONE", "collectionCount=${collectionParts.size} episodeCount=${episodeTmdbMeta.size}")

                val tmdbRecs: List<TmdbMedia> = detailRes.recommendations?.results ?: emptyList()
                val finalCombinedMedia = (collectionParts + tmdbRecs)
                    .distinctBy { it.id }
                    .filter { it.id != null && it.id != tmdbIdStr.toIntOrNull() }

                fetchedRecommendations = DetailLoadPerf.stepNow("tmdb.recommendations.build") { finalCombinedMedia.take(50).map { rec ->
                    val recTitle = rec.title ?: rec.name ?: ""
                    val isRecMovie = rec.mediaType == "movie" || rec.title != null
                    val recTypeStr = if (isRecMovie) "movie" else "series"
                    val recType = if (isRecMovie) TvType.Movie else TvType.TvSeries
                    
                    val tmdbPoster = if (!rec.poster_path.isNullOrBlank())
                        "https://image.tmdb.org/t/p/w500${rec.poster_path}" else TRANSPARENT_PIXEL
                    val recPoster = rec.id?.let {
                        StremioC.recommendationPosterUrl(recommendationPoster, it, isRecMovie,
                            tmdbPoster, metadataHost)
                    } ?: tmdbPoster
                    val thinData = LoadData(id = "tmdb:${rec.id}", type = recTypeStr, addonUrl = provider.mainUrl)
                    provider.newMovieSearchResponse(recTitle, thinData.toJson(), recType) {
                        this.posterUrl = recPoster
                    }
                } }
                DetailLoadPerf.event("TMDB_RECS_RESULT", "total=${fetchedRecommendations?.size ?: 0} posterOverride=${recommendationPoster != null}")
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

            // TMDB preference: configured language, then English. Addon logo is the final fallback.
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
                    // TMDB serves a PNG rasterization at the same file basename as an SVG.
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
            year: Int? = null, // ★ year 매개변수 추가
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
                        year, // ★ toEpisode 메서드로 year 매개변수 위임 전달
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
            val syncedEntry = DetailLoadPerf.step("await.secondaryAddonMeta") { addonDeferred?.await() }
            val detailedEntry = syncedEntry ?: this@CatalogEntry
            DetailLoadPerf.event("DETAIL_META_CHOICE", "secondaryUsed=${syncedEntry != null} requestedId=${this@CatalogEntry.id} type=${this@CatalogEntry.type}")
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

            DetailLoadPerf.event("RESPONSE_PLAN", "entryType=${this@CatalogEntry.type} imdb=${finalImdbId != null} tmdb=${tmdbIdStr != null} secondary=${syncedEntry != null} videos=${this@CatalogEntry.videos?.size ?: 0}")
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

            // IMDb mapping has no season/episode in the detail-page request.
            // Multi-season series cannot share a single anime tracking identity.
            val videoSeasons = finalVideos.orEmpty().mapNotNull {
                it.seasonNumber?.takeIf { number -> number > 0 }
            }.distinct()
            val isMovieForSync = finalType == "movie" || isSingleMovie(finalType ?: "", finalVideos)
            // A single listed IMDb season 3 is not a single-season series.
            val canSyncAnimeTrackers = isMovieForSync ||
                ((finalType == "series" || finalType == "tv") && videoSeasons.singleOrNull() == 1)
            val useImdbSyncMapping = (originalAnimeId == null ||
                (originalAnimeId.startsWith("anilist:") &&
                    fetchedAniListId == null && fetchedMalId == null && fetchedKitsuId == null)) &&
                finalImdbId?.let { IMDB_ID_REGEX.matches(it) } == true && canSyncAnimeTrackers
            DetailLoadPerf.event("SYNC_MAPPING_PLAN", "enabled=$useImdbSyncMapping movie=$isMovieForSync seasons=${videoSeasons.joinToString(",")}")
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
                val findResult = DetailLoadPerf.step("await.tmdbFindInResponse") { findApiDeferred.await() }
                if (findResult != null) { tmdbIdStr = findResult.first; foundMediaType = findResult.second }
            }

            var tmdbData: FetchedTmdbData? = null
            if (tmdbIdStr != null) {
                try {
                    val refinedMediaType = foundMediaType ?: if (finalType == "series" || finalType == "tv") "tv" else if (isSingleMovie(finalType, finalVideos) || finalType == "movie" || finalVideos.isNullOrEmpty()) "movie" else "tv"
                    val safePrefetchedDetail = preFetchedDetail?.takeIf {
                        preFetchedDetailTmdbId == tmdbIdStr && preFetchedDetailMediaType == refinedMediaType
                    }
                    tmdbData = DetailLoadPerf.step("response.tmdbEnrichment") {
                        fetchTmdbDetails(provider, refinedMediaType, tmdbIdStr!!,
                            refinedMediaType == "movie", finalImdbId, finalType, finalVideos,
                            safePrefetchedDetail, if (safePrefetchedDetail != null) preFetchedCollection else null,
                            recommendationPoster)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
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
            val imdbSyncMapping = DetailLoadPerf.step("await.detailSyncMapping") { detailSyncMapping?.await() }
            DetailLoadPerf.event("ANIME_SYNC_RESULT", "requested=${detailSyncMapping != null} matched=${imdbSyncMapping != null} eligible=$canSyncAnimeTrackers")
            val isAnimeMedia = isAnimeFromMetadata || imdbSyncMapping != null
            DetailLoadPerf.event("RESPONSE_ASSET_PLAN", "poster=${!finalPoster.isNullOrBlank()} backdrop=${!finalBackground.isNullOrBlank()} tmdbActors=${tmdbData?.fetchedActors?.size ?: 0} recs=${tmdbData?.fetchedRecommendations?.size ?: 0} trailers=${finalTrailers.size}")

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
            // The addon does not expose the logo's language: keep the text title alongside it.
            val finalLogoFallback = if (tmdbData?.fetchedLogo.isNullOrBlank())
                finalLogo != null else tmdbData?.fetchedLogoFallback == true

            fun LoadResponse.attachMappedIds() {
                val validImdb = cleanImdbId?.takeIf { it.startsWith("tt") }
                validImdb?.let { addImdbId(it) }
                if (validImdb == null) cleanTmdbId?.takeIf { it.isNotBlank() }?.let { addTMDbId(it) }
                // Only filter tracker sync IDs; playback and subtitle IDs remain available.
                if (this@CatalogEntry.id?.let { IMDB_ID_REGEX.matches(it) } != true || canSyncAnimeTrackers) {
                    finalAniId?.let { addAniListId(it) }
                    finalMalId?.let { addMalId(it) }
                    finalKitsuId?.let { syncData["kitsu"] = it.toString() }
                }
                apiName = provider.getTargetApiName()
                // Preserve all metadata/sync fields; block only host inline-preview extraction.
                trailers = NoNativeTrailerPreview()
            }

            if (finalVideos.isNullOrEmpty() || isSingleMovie(finalType, finalVideos)) {
                val metaUrl = provider.getUnifiedMetaUrl(finalType ?: "movie", this@CatalogEntry.id ?: "")
                StremioC.fallbackLogoTitles[metaUrl] = finalLogoFallback
                StremioC.expectedLogoByUrl[metaUrl] = !finalLogo.isNullOrBlank()
                StremioC.trailerChoicesByUrl[metaUrl] = finalTrailers
                StremioC.genreCatalogsByUrl[metaUrl] = genreTargets
                val resolvedMovieId = if (finalType == "other" && finalVideos?.size == 1) {
                    finalVideos[0].id ?: movieId
                } else {
                    movieId
                }

                val response = DetailLoadPerf.step("response.movie.build") { provider.newMovieLoadResponse(
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
                    this.actors = tmdbData?.fetchedActors
                    this.tags = finalGenre
                    this.recommendations = tmdbData?.fetchedRecommendations 
                    this.duration = tmdbData?.fetchedRuntime; this.contentRating = tmdbData?.fetchedAgeRating
                    finalLogo?.let { this.logoUrl = it }


                } }
                DetailLoadPerf.stepNow("response.movie.actorRegister") {
                    ActorInformation.register(
                        provider, metaUrl, tmdbData?.fetchedActors, tmdbData?.fetchedPeople.orEmpty(),
                        recommendationPoster, provider.metadataPosterAddonBaseUrl()?.toHttpUrlOrNull()?.host
                    )
                }
                return@coroutineScope DetailLoadPerf.stepNow("response.movie.attachSync") { response.apply { attachMappedIds() } }

            } else {
                val finalEpisodes = DetailLoadPerf.step("response.series.processEpisodes") { processEpisodes(
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
                    tmdbData?.fetchedYear, // ★ 수집된 출시 연도(year) 인자 전달 추가
                    originalAnimeId
                ) }
                val metaUrl = provider.getUnifiedMetaUrl(finalType ?: "series", this@CatalogEntry.id ?: "")
                StremioC.fallbackLogoTitles[metaUrl] = finalLogoFallback
                StremioC.expectedLogoByUrl[metaUrl] = !finalLogo.isNullOrBlank()
                StremioC.trailerChoicesByUrl[metaUrl] = finalTrailers
                StremioC.genreCatalogsByUrl[metaUrl] = genreTargets
                val response = DetailLoadPerf.step("response.series.build") { provider.newTvSeriesLoadResponse(
                    finalName, metaUrl, finalSeriesType, finalEpisodes
                ) {
                    posterUrl = finalPoster; backgroundPosterUrl = finalBackground; score = Score.from10(finalRatingValue); year = tmdbData?.fetchedYear
                    this.showStatus = tmdbData?.fetchedStatus 
                    this.plot = finalDescription
                    this.actors = tmdbData?.fetchedActors
                    this.tags = finalGenre
                    this.recommendations = tmdbData?.fetchedRecommendations
                    this.contentRating = tmdbData?.fetchedAgeRating
                    finalLogo?.let { this.logoUrl = it }
                    

                } }
                DetailLoadPerf.stepNow("response.series.actorRegister") {
                    ActorInformation.register(
                        provider, metaUrl, tmdbData?.fetchedActors, tmdbData?.fetchedPeople.orEmpty(),
                        recommendationPoster, provider.metadataPosterAddonBaseUrl()?.toHttpUrlOrNull()?.host
                    )
                }
                return@coroutineScope DetailLoadPerf.stepNow("response.series.attachSync") { response.apply { attachMappedIds() } }
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
            year: Int? = null, // ★ year 매개변수 추가
            originalAnimeId: String? = null
        ): Episode { // ★ 누락되었던 닫는 괄호, 반환 타입, 시작 중괄호 추가
            val tmdbEp = tmdbMetaMap["${displaySeason}_${displayEpisode}"] ?: tmdbMetaMap["${seasonNumber ?: 0}_${episode ?: number ?: 0}"]
            val finalAirDate = tmdbEp?.airDate?.takeIf { it.isNotBlank() } ?: this@Video.firstAired ?: this@Video.released
            val airedYearInt = finalAirDate?.split("-")?.first()?.toIntOrNull()
            
            return provider.newEpisode(LoadData(
                type = type, 
                id = id, 
                season = displaySeason, 
                episode = displayEpisode, 
                imdbId = imdbId, 
                year = year, // ★ null 대신 전달받은 정수형 year 바인딩
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

    // Use CloudStream's language catalog; no hardcoded language names or subtitle sources.
    private val subtitleLanguageNames by lazy {
        SubtitleHelper.languages.map { it.languageName.lowercase(java.util.Locale.ROOT) }.toSet()
    }

    private fun cleanSubtitleDisplayLabel(label: String, language: String): String? {
        var cleaned = label.trim()
        // Drop only a complete, repeated language prefix followed by a display separator.
        val repeatedPrefix = Regex("^${Regex.escape(language)}\\s*(?:[·•|:]|\\s[-—–]\\s)\\s*", RegexOption.IGNORE_CASE)
        while (repeatedPrefix.containsMatchIn(cleaned)) {
            cleaned = repeatedPrefix.replaceFirst(cleaned, "").trim()
        }
        // Parenthesized *full language names*, not release names or filename fragments.
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

    private suspend fun Subtitle.toCloudStreamSubtitle(addonName: String?, preferredName: String? = null): SubtitleFile? {
        val subtitleUrl = url ?: return null
        val rawLang = lang ?: lang_code ?: "Unknown"
        val language = SubtitleHelper.fromTagToEnglishLanguageName(rawLang) ?: rawLang
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
            return newSubtitleFile(language, subtitleUrl)
        }
        val displayName = buildString {
            append(language)
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
        @JsonProperty("sources") val sources: List<String> = emptyList(),
        @JsonProperty("subtitles") val subtitles: List<Subtitle> = emptyList()
    ) {
        suspend fun runCallback(
            provider: StremioC,
            sourceName: String,
            stremioSubtitleCallback: (SubtitleFile) -> Unit,
            extractorSubtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            preferredName: String? = null
        ) {
            val displaySource = sourceName.takeIf { it.isNotBlank() } ?: name ?: ""

            if (url != null) {
                val lowerUrl = url.lowercase()
                val linkType = when {
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
                callback.invoke(newExtractorLink(displaySource, "[$displaySource] $displayTitle", url, linkType) {
                    this.quality = getQuality(listOf(description, title, name))
                    this.headers = behaviorHints?.proxyHeaders?.request ?: behaviorHints?.headers ?: mapOf()
                })
            }

            if (ytId != null) loadExtractor("https://m.youtube.com/watch?v=$ytId", extractorSubtitleCallback, callback)
            if (externalUrl != null) loadExtractor(externalUrl, extractorSubtitleCallback, callback)
            if (infoHash != null) {
                val displayTitle = description ?: title ?: name ?: ""
                callback.invoke(newExtractorLink(displaySource, "[$displaySource] $displayTitle", "magnet:?xt=urn:btih:${infoHash}") {
                    this.quality = Qualities.Unknown.value
                })
            }

            val hasPlayableTarget = url != null || ytId != null || externalUrl != null || infoHash != null
            if (hasPlayableTarget) {
                subtitles.forEach { sub ->
                    provider.run { sub.toCloudStreamSubtitle(sourceName, preferredName) }?.let(stremioSubtitleCallback)
                }
            }
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
        e.printStackTrace()
        emptyList()
    }
}

private data class FlatTab(val key: String, val displayName: String, val urlPath: String)


// Shared by the provider and SettingsBottomFragment. Keep these functions together.
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
                // The supplied settings UI wrote legacy keys exclusively. On first
                // migration these are authoritative; subsequent saves use v2 only.
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
        StremioCCacheAudit.log("manifest", "PREFS_BLOCKED_NO_STORE", source)
        return null
    }
    val raw = prefs.getString(key, null) ?: stremioOldCatalogKeys(source)
        .firstNotNullOfOrNull { prefs.getString(it, null) }
    if (raw == null) {
        StremioCCacheAudit.log("manifest", "PREFS_MISS", source,
            "requireFresh=$requireFresh")
        return null
    }
    val now = System.currentTimeMillis()
    val stampKey = "manifest_saved_at_$key"
    val savedAt = prefs.getLong(stampKey, 0L)
    // One-time migration for manifests stored before timestamp tracking.
    if (savedAt == 0L) {
        prefs.edit().putString(key, raw).putLong(stampKey, now).apply()
        StremioCCacheAudit.log("manifest", "PREFS_MIGRATE_AND_REUSE", source,
            "chars=${raw.length} requireFresh=$requireFresh")
        return raw
    }
    val maxAge = if (requireFresh) prefs.getLong(
        "manifest_ttl_ms_" + key, 3_600_000L
    ).coerceIn(0L, 72L * 60 * 60 * 1000) else 72L * 60 * 60 * 1000
    val valid = savedAt <= now && now - savedAt <= maxAge
    StremioCCacheAudit.log("manifest", if (valid) "PREFS_HIT" else "PREFS_EXPIRED",
        source, "ageMs=${now - savedAt} ttlMs=$maxAge requireFresh=$requireFresh chars=${raw.length}")
    return raw.takeIf { valid }
}
