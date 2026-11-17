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
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.TrailerData
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
import com.lagradost.cloudstream3.SeasonData
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

private fun stremioMetadataDiagnosticBypass(): Boolean =
    CloudStreamApp.context?.getSharedPreferences("ElevateFeatures", Context.MODE_PRIVATE)
        ?.getBoolean("METADATA_CACHE_BYPASS", false) == true

internal fun <T> stremioCacheMemoryRead(
    cached: Pair<T, Long>?, ttlMs: Long
): Pair<T, Long>? {
    if (stremioMetadataDiagnosticBypass()) return null
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
        "anime" to 5000, "missing_anime" to 1000, "meta" to 200)
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

    suspend fun invalidate(kind: String, key: String) = withContext(Dispatchers.IO) {
        val dir = directory() ?: return@withContext
        synchronized(lock) { target(dir, kind, key).delete() }
    }

    suspend fun read(kind: String, key: String): Pair<String, Long>? =
        readWithTtl(kind, key)?.let { it.json to it.timestamp }

    suspend fun readWithTtl(kind: String, key: String): Entry? = withContext(Dispatchers.IO) {
        if (stremioMetadataDiagnosticBypass()) return@withContext null
        // Writers publish complete files using atomic rename. Reading a completed
        // file does not need the writer's global eviction/quota monitor.
        val file = directory()?.let { target(it, kind, key) } ?: return@withContext null
        if (!file.isFile || file.length() > MAX_ENTRY_BYTES) return@withContext null
        try {
            val body = GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val chunk = CharArray(8192)
                while (true) {
                    val size = reader.read(chunk)
                    if (size < 0) break
                    if (text.length + size > MAX_RAW_BYTES + 64) return@use null
                    text.append(chunk, 0, size)
                }
                text.toString()
            } ?: return@withContext null
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
                val json = body.substring(if (kind == "meta") secondNewline + 1
                    else firstNewline + 1)
                if (json.toByteArray(Charsets.UTF_8).size > MAX_RAW_BYTES) null
                else Entry(json, stamp, ttl)
            }
        } catch (_: Exception) {
            // An eviction racing this read is a cache miss, never a playback error.
            null

        }
    }

    suspend fun write(kind: String, key: String, json: String, ttlMs: Long = TTL_MS) {
        if (stremioMetadataDiagnosticBypass()) return
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
                            if (trimNeeded) trim(files)
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

    private fun trim(files: List<File>, limitBytes: Long = MAX_BYTES) {
        val byKind = files.groupBy { file ->
            entryLimits.keys.firstOrNull { kind -> file.name.startsWith("${kind}_") }
        }
        for ((kind, limit) in entryLimits) {
            byKind[kind].orEmpty().sortedByDescending { it.lastModified() }.drop(limit).forEach {
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

private fun tmdbLogoLanguages(): String {
    val preferred = StremioTmdb.language().substringBefore('-').lowercase()
    return listOf(preferred, "en").distinct().joinToString(",")
}

private class NoNativeTrailerPreview : AbstractMutableList<TrailerData>() {
    override val size: Int get() = 0
    override fun get(index: Int): TrailerData = throw IndexOutOfBoundsException()
    override fun add(index: Int, element: TrailerData) = Unit
    override fun set(index: Int, element: TrailerData): TrailerData = throw IndexOutOfBoundsException()
    override fun removeAt(index: Int): TrailerData = throw IndexOutOfBoundsException()
}

open class StremioC(
    override var mainUrl: String,
    override var name: String,
    internal val addonId: Long? = null,
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

    fun getUnifiedMetaUrl(type: String, id: String): String =
        "${mainUrl.cleanBaseUrl()}/meta/$type/${id.urlEncode()}.json"

    fun getTargetApiName(): String {
        val (_, syncMap, _) = getSyncSettings()
        val currentCleanUrl = mainUrl.cleanBaseUrl().lowercase()
        val isSyncEnabled = syncMap[currentCleanUrl] ?: false

        return if (isSyncEnabled) "StremioC" else name
    }

    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val TRANSPARENT_PIXEL = "https://upload.wikimedia.org/wikipedia/commons/c/ca/1x1.png"
        private var noActorImage = TRANSPARENT_PIXEL

        internal fun initializeActorImage(context: Context, pluginResources: android.content.res.Resources?) {
            val res = pluginResources ?: return
            val id = res.getIdentifier("no_image", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
            if (id == 0) return
            runCatching {
                val file = File(context.cacheDir, "stremioc_no_image.jpg")
                res.openRawResource(id).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                noActorImage = android.net.Uri.fromFile(file).toString()
            }
        }


        // Recommendations do not depend on the user's watchlist, but reuse the logged-in
        // Simkl identity and CloudStream's own Simkl client credentials.
        private data class SimklSuggestion(
            val type: String, val id: String, val tmdbId: Int?, val name: String,
            val poster: String?, val rank: Int
        )
        // Null means a failed request, distinct from a successful empty recommendation list.
        private data class SimklSuggestionCache(val entries: List<SimklSuggestion>?, val at: Long)
        private val simklSuggestionCache = lruCacheMap<String, SimklSuggestionCache>(160)
        private val simklSuggestionFlights = mutableMapOf<String, Deferred<List<SimklSuggestion>>>()
        private val simklSuggestionLock = Any()
        // A separate supervisor prevents the parent detail load from joining background requests.
        private val simklSuggestionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val simklSuggestionSemaphore = Semaphore(3)
        private const val SIMKL_RECOMMENDATION_WAIT_MS = 150L
        internal data class RecommendationUpdate(
            val url: String, val cards: List<SearchResponse>
        )
        internal val recommendationUpdates = androidx.lifecycle.MutableLiveData<RecommendationUpdate>()
        private val recentRecommendations = lruCacheMap<String, List<SearchResponse>>(100)
        internal fun latestRecommendationFor(url: String): RecommendationUpdate? =
            recentRecommendations[url]?.let { RecommendationUpdate(url, it) }
        private fun publishRecommendations(url: String, cards: List<SearchResponse>) {
            recentRecommendations[url] = cards
            recommendationUpdates.postValue(RecommendationUpdate(url, cards))
        }
        private data class RelationArtwork(val poster: String?, val mal: String?, val kitsu: String?)
        private val relationArtCache = lruCacheMap<String, RelationArtwork>(200)
        private val relationArtSemaphore = Semaphore(3)
        private fun cachedRelationArt(url: String): RelationArtwork? = relationArtCache[url]
        private suspend fun loadRelationArt(url: String) = relationArtSemaphore.withPermit {
            if (relationArtCache.containsKey(url)) return@withPermit
            val artwork = withTimeoutOrNull(5_000L) {
                runSuspendCatching {
                    val json = parseJson<JsonNode>(fetchAddonMetaText(url))
                    val meta = json.get("meta") ?: json
                    fun id(key: String) = meta.get(key)?.asText()?.trim()?.takeIf { it.isNotBlank() }
                    RelationArtwork(
                        id("poster")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                        id("_malId") ?: id("mal_id"), id("_kitsuId")
                    )
                }.getOrNull()
            }
            if (artwork != null) relationArtCache[url] = artwork
        }
        private const val SIMKL_RECOMMENDATION_TTL_MS = 24L * 60 * 60 * 1000
        private const val SIMKL_RECOMMENDATION_EMPTY_TTL_MS = 15L * 60 * 1000
        private const val SIMKL_RECOMMENDATION_ERROR_TTL_MS = 30_000L
        private const val SIMKL_TITLE_INDEX_TTL_MS = 2L * 60 * 1000
        private data class SimklTitleIndex(
            val userId: String, val at: Long, val byExternalId: Map<String, Pair<String, String>>
        )
        @Volatile private var simklTitleIndex: SimklTitleIndex? = null
        private val simklTitleIndexLock = Any()

        private fun simklSuggestionsFromDetail(text: String): List<SimklSuggestion> {
            val detail = parseJson<JsonNode>(text)
            val rows = buildList<JsonNode> {
                detail.get("users_recommendations")?.takeIf { it.isArray }?.take(30)?.let { addAll(it) }
                detail.get("similar")?.takeIf { it.isArray }?.take(30)?.let { addAll(it) }
            }
            return rows.mapIndexedNotNull { index, row ->
                val ids = row.get("ids") ?: return@mapIndexedNotNull null
                val tmdb = ids.get("tmdb")?.asInt(0)?.takeIf { it > 0 }
                val mal = ids.get("mal")?.asInt(0)?.takeIf { it > 0 }
                val kitsu = ids.get("kitsu")?.asInt(0)?.takeIf { it > 0 }
                val imdb = ids.get("imdb")?.asText()?.takeIf { IMDB_ID_REGEX.matches(it) }
                val originalType = row.get("type")?.asText()?.lowercase()
                val isAnime = originalType == "anime"
                val movie = originalType == "movie" || originalType == "movies" ||
                    (isAnime && row.get("anime_type")?.asText()?.equals("movie", true) == true)
                val type = if (movie) "movie" else "series"
                val id = if (isAnime && mal != null) "mal:$mal"
                    else tmdb?.let { "tmdb:$it" }
                        ?: imdb ?: mal?.let { "mal:$it" }
                        ?: kitsu?.let { "kitsu:$it" } ?: return@mapIndexedNotNull null
                val title = listOf("en_title", "title", "name")
                    .firstNotNullOfOrNull { row.get(it).textOrNull()?.trim()?.takeIf(String::isNotBlank) }
                    ?: return@mapIndexedNotNull null
                val art = row.get("poster")?.asText()?.trim()
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_/-]{1,160}")) }
                    ?.let { "https://wsrv.nl/?url=https://simkl.in/posters/" + it + "_m.webp" }
                SimklSuggestion(type, id, tmdb, title, art, index)
            }.distinctBy { it.type to (it.tmdbId?.let { id -> "tmdb:$id" } ?: it.id) }
        }

        private fun cachedSimklTitle(
            userId: String, type: String, tmdbId: String?, imdbId: String?, malId: Int?
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
                ?: tmdbId?.let { entries["tmdb:$type:$it"] }
            
            return hit
        }

        private suspend fun loadSimklSuggestions(
            userId: String, type: String, tmdbId: String?, imdbId: String?, malId: Int?,
            headers: Map<String, String>, clientId: String, key: String
        ): List<SimklSuggestion>? {
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
                tmdbId?.toIntOrNull()?.takeIf { it > 0 }?.let { lookups += "tmdb" to it.toString() }
                val paramsBase = mapOf(
                    "client_id" to clientId,
                    "app-name" to "stremioc",
                    "app-version" to "1.0"
                )
                val requestHeaders = headers + ("User-Agent" to "StremioC/1.0")
                var lookupFailed = false
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
                        lookupFailed = true
                        continue
                    }

                    if (response.code !in 200..299) {
                        lookupFailed = true
                        // Invalid credentials and rate limits will affect every ID;
                        // avoid consuming extra requests in these cases.
                        if (response.code in listOf(401, 403, 412, 429)) break
                        continue
                    }
                    val parsed = try {
                        parseJson<JsonNode>(response.text)
                    } catch (e: Exception) {
                        lookupFailed = true
                        continue
                    }

                    if (!parsed.isArray) {
                        lookupFailed = true
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
                    return@withPermit if (lookupFailed) null else emptyList()
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
                return@withPermit null
            }

            if (response.code !in 200..299 || response.text.isBlank()) {
                return@withPermit null
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
            type: String, tmdbId: String?, imdbId: String?, malId: Int?
        ): Deferred<List<SimklSuggestion>>? {
            if (tmdbId?.toIntOrNull()?.let { it > 0 } != true &&
                imdbId?.let { IMDB_ID_REGEX.matches(it) } != true &&
                malId?.let { it > 0 } != true) return null
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
            val key = listOf(type, tmdbId.orEmpty(), imdbId.orEmpty(), malId?.toString().orEmpty()).joinToString("|")
            val cached = simklSuggestionCache[key]
            val now = System.currentTimeMillis()
            if (cached != null && now >= cached.at &&
                now - cached.at < when {
                    cached.entries == null -> SIMKL_RECOMMENDATION_ERROR_TTL_MS
                    cached.entries.isEmpty() -> SIMKL_RECOMMENDATION_EMPTY_TTL_MS
                    else -> SIMKL_RECOMMENDATION_TTL_MS
                }) {
                return CompletableDeferred(cached.entries.orEmpty())
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
                        null
                    }
                    // Failed requests use a brief passive cooldown, not the 15-minute empty-result TTL.
                    simklSuggestionCache[key] = SimklSuggestionCache(result, System.currentTimeMillis())
                    result.orEmpty()
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

        private data class StreamSubtitleAddon(val name: String, val url: String)
        private data class SyncSettings(
            val syncUrl: String,
            val syncMap: Map<String, Boolean>,
            val progressSyncMap: Map<String, Boolean>,
            val streamsAddonMap: Map<String, Boolean>,
            val subtitlesAddonMap: Map<String, Boolean>,
            val streamSubtitleUrls: List<StreamSubtitleAddon>,
            val disabledCatalogUrls: Set<String> = emptySet(),
            val catalogEnabledIds: Map<Long, Boolean> = emptyMap(),
            val catalogAddons: List<StreamSubtitleAddon> = emptyList()
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

        /** Expose only the catalog's metadata setting; Elevate owns its swap source and requests. */
    private fun isMetadataSwapAllowed(catalogUrl: String?): Boolean {
        val catalog = catalogUrl?.takeIf { it.isNotBlank() }?.cleanBaseUrl()?.lowercase()
        if (catalog == null) {
            return false
        }
        val settings = getSyncSettings()
        val stored = settings.syncMap[catalog]
        return stored == true
    }

        /** Encode the catalog's progress permission into returned metadata. */
    private fun isWatchProgressAllowed(catalogUrl: String?): Boolean {
        val catalog = catalogUrl?.takeIf { it.isNotBlank() }?.cleanBaseUrl()?.lowercase() ?: return false
        val settings = getSyncSettings()
        settings.progressSyncMap[catalog]?.let { return it }
        // For a unified metadata URL without a clear origin, respect every
        // enabled catalog's explicit OFF; never guess a source.
        if (settings.syncUrl.isBlank() ||
            catalog != settings.syncUrl.cleanBaseUrl().lowercase()) return false
        val candidates = settings.progressSyncMap.filterKeys { settings.syncMap[it] == true }
        return candidates.isNotEmpty() && candidates.values.all { it }
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
                val context = CloudStreamApp.context ?: return SyncSettings("", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())
                val prefs = context.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
                val syncUrl = prefs.getString("history_sync_url", "").orEmpty()
                val json = prefs.getString("stremio_saved_links", null)
                val map = mutableMapOf<String, Boolean>()
                val progressMap = mutableMapOf<String, Boolean>()
                val streamsMap = mutableMapOf<String, Boolean>()
                val subtitlesMap = mutableMapOf<String, Boolean>()
                val catalogEnabled = mutableMapOf<String, Boolean>()
                val catalogEnabledIds = mutableMapOf<Long, Boolean>()
                val catalogAddons = mutableListOf<StreamSubtitleAddon>()
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
                            if (rawLink.isNotBlank()) catalogAddons.add(
                                StreamSubtitleAddon(obj.optString("name").trim(), rawLink)
                            )
                            val link = obj.optString("link", "").cleanBaseUrl().lowercase()
                            if (link.isNotBlank()) {
                                map[link] = obj.optBoolean("isSyncEnabled", true)
                                progressMap[link] = obj.optBoolean("isHistorySyncEnabled", map[link] ?: true)
                                val previous = obj.optBoolean("isStreamSubtitleSyncEnabled", false)
                                streamsMap[link] = obj.optBoolean("isStreamsAddonEnabled", previous)
                                subtitlesMap[link] = obj.optBoolean("isSubtitlesAddonEnabled", previous)
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
                val result = SyncSettings(syncUrl, map, progressMap, streamsMap, subtitlesMap, extraUrls,
                    catalogEnabled.filterValues { !it }.keys.toSet(), catalogEnabledIds, catalogAddons)
                cachedSyncSettings = result
                cachedSyncSettingsTime = System.currentTimeMillis()
                return result
            }
        }

        val networkSemaphore = Semaphore(10)

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

        const val HOME_FOREGROUND_CONCURRENCY = 16
        const val HOME_FOREGROUND_PER_HOST_CONCURRENCY = 16
        private val homeForegroundSemaphore = Semaphore(HOME_FOREGROUND_CONCURRENCY)
        private const val HOME_RESULTS_REUSE_MS = 60_000L
        private data class CachedHomeResult(val result: Pair<List<SearchResponse>, Int>, val savedAt: Long)
        private val recentHomeResults = object : LinkedHashMap<String, CachedHomeResult>(24, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedHomeResult>?): Boolean =
                size > 24
        }

        private class CatalogHttpException(val statusCode: Int) :
            IllegalStateException("Catalog HTTP $statusCode")

        @Volatile private var cachedCineStreamProvider: MainAPI? = null
        @Volatile private var cachedStreamPlayProvider: MainAPI? = null

        @Volatile private var cachedClassLoaderRef: WeakReference<ClassLoader>? = null


        private val libraryOriginalUrlsByMeta = lruCacheMap<String, MutableSet<String>>(200)
        internal val animeAudioByUrl = lruCacheMap<String, Boolean>(200)
        // Distinguish an episode-based result awaiting native season selection
        // from a movie, which has no native seasonSelections event.
        internal val episodeBasedByUrl = lruCacheMap<String, Boolean>(200)
        // IMDb mapping identity and the last native season used for season-zero specials.
        internal val seasonalAnimeByUrl = lruCacheMap<String, Pair<String?, Int?>>(200)

        private val subtitleAnimeManifestCache = lruCacheMap<String, Pair<String?, Long>>(80)
        private val subtitleAnimeManifestLocks = ConcurrentHashMap<String, Mutex>()

        internal val seasonEpisodeCountsByUrl = lruCacheMap<String, Map<Int, Int>>(200)
        internal val subtitleOrderByUrl = Collections.synchronizedMap(lruCacheMap<String, Int>(1200))
        internal val subtitleDetailByUrl = Collections.synchronizedMap(lruCacheMap<String, String>(2400))
        internal val subtitleSourceByUrl = Collections.synchronizedMap(lruCacheMap<String, String>(2400))
        // Download-only metadata; preserve normal source labels and bound memory usage.
        internal val streamFilenameByLink = Collections.synchronizedMap(lruCacheMap<String, String>(256))

        // CloudStream decodes subtitle names once on registration and once in the picker.
        // Keep translation labels opaque to language auto-selection until the picker.
        internal fun translationGroupId(label: String): String = buildString {
            var i = 0
            while (i < label.length) {
                val codePoint = Character.codePointAt(label, i)
                append("&amp;#").append(codePoint).append(';')
                i += Character.charCount(codePoint)
            }
        }

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
                metadataTextFlights[url]
                    ?: addonMetaScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val response = customSession.get(url,
                            headers = if (stremioMetadataDiagnosticBypass())
                                mapOf("Cache-Control" to "no-cache, no-store", "Pragma" to "no-cache")
                            else emptyMap())
                        val text = response.text
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
            if (stremioMetadataDiagnosticBypass()) return
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

        private fun invalidateAddonMetaInRam(key: String) {
            synchronized(addonMetaCache) {
                addonMetaCache.remove(key)?.let { addonMetaCacheBytes -= it.bytes.size.toLong() }
            }
        }

        private fun cachedAddonMetaInRam(url: String): String? {
            if (stremioMetadataDiagnosticBypass()) return null
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
            val language = StremioTmdb.language()
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
            // The catalog's own Metadata Sync switch controls its original meta cache.
            val diagnosticBypass = stremioMetadataDiagnosticBypass()
            val skipCache = diagnosticBypass || isMetadataSwapAllowed(url.substringBefore("/meta/"))
            if (skipCache && !diagnosticBypass) {
                invalidateAddonMetaInRam(storageKey)
                MetadataDiskCache.invalidate("meta", storageKey)
            }
            val previous = if (forceRefresh && !skipCache) {
                cachedAddonMetaInRam(storageKey)
                    ?: MetadataDiskCache.read("meta", storageKey)?.first
            } else null
            if (!forceRefresh && !skipCache) {
                cachedAddonMetaInRam(storageKey)?.let {
                    return it
                }
                MetadataDiskCache.readWithTtl("meta", storageKey)?.let {
                    cacheAddonMetaInRam(storageKey, it.json, it.ttlMs, it.timestamp)
                    return it.json
                }
            }
            val flightKey = (if (skipCache) "uncached|" else if (forceRefresh) "force|" else "normal|") + storageKey
            val flight = synchronized(addonMetaFlights) {
                addonMetaFlights[flightKey]
                    ?: addonMetaScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val response = customSession.get(url, headers = if (diagnosticBypass)
                            mapOf("Cache-Control" to "no-cache, no-store", "Pragma" to "no-cache")
                        else if (forceRefresh) mapOf("Cache-Control" to "no-cache") else emptyMap())
                        if (response.code !in 200..299) {
                            throw IllegalStateException("Stremio meta HTTP " + response.code)
                        }
                        val body = response.text
                        if (skipCache || isMetadataSwapAllowed(url.substringBefore("/meta/"))) {
                            // The caller still parses the metadata once for the LoadResponse.
                            // Avoid a second JSON tree and validation allocation just for caching.
                            return@async body
                        }
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
                        val cacheable = !isMetadataSwapAllowed(url.substringBefore("/meta/")) &&
                            valid && ttlMs > 0L && safeVary && !transient && !image
                        if (cacheable) {
                            cacheAddonMetaInRam(storageKey, body, ttlMs)
                            addonMetaScope.launch {
                                if (!isMetadataSwapAllowed(url.substringBefore("/meta/")))
                                    MetadataDiskCache.write("meta", storageKey, body, ttlMs)
                            }
                        }
                        body
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
        private val tmdbOriginLogoCache = lruCacheMap<String, Pair<TmdbImages, Long>>(100)
        private val tmdbCreditsCache = lruCacheMap<String, Pair<TmdbCreditsResponse, Long>>(100)
        private val tmdbSeasonCache = lruCacheMap<String, Pair<TmdbSeasonDetail, Long>>(300)
        private val tmdbFindCache = lruCacheMap<String, Pair<Pair<String, String>?, Long>>(5000)
        private val animeMappingCache = lruCacheMap<String, Pair<AnimeSeasonMapping, Long>>(5000)
        private val kitsuEpisodeCache = lruCacheMap<String, Pair<Map<Int, Int>, Long>>(100)
        private val kitsuImdbEpisodeCache = lruCacheMap<String, Pair<Map<Pair<Int, Int>, Int>, Long>>(40)
        private val animeNoMappingCache = lruCacheMap<String, Long>(1000)
        private val detailLoadHints = lruCacheMap<String, DetailUrlHint>(2000)
        private const val METADATA_CACHE_TTL = 24 * 60 * 60 * 1000L

        private const val SEASON_CACHE_TTL = 12 * 60 * 60 * 1000L

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
        private val homeJsonFlights = mutableMapOf<String, Deferred<HomeCatalogResponse>>()

        private suspend fun fetchHomeJson(url: String): HomeCatalogResponse {
            val task = synchronized(homeJsonFlights) {
                homeJsonFlights[url]
                    ?: addonMetaScope.async(start = CoroutineStart.LAZY) {
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

        private suspend fun fetchHomeJsonUnshared(url: String): HomeCatalogResponse {
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
                                val body = res.body ?: throw IllegalStateException("Empty catalog response body")
                                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                                if (charset == Charsets.UTF_8) {
                                    com.lagradost.cloudstream3.mapper.readValue(
                                        body.byteStream(), HomeCatalogResponse::class.java)
                                } else {
                                    parseJson<HomeCatalogResponse>(body.string())
                                }
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
            val cached = settingsCache[prefKey]
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

            settingsCache[prefKey] = Pair(isEnabled, now)
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

    /** Only Stremio movies and series have corresponding TMDB detail endpoints. */
    private fun detailMediaType(type: String?): String? = when (type?.lowercase()) {
        "movie" -> "movie"
        "series" -> "tv"
        else -> null
    }

    private fun isKitsuOrMalId(id: String?): Boolean =
        id?.startsWith("kitsu:") == true || id?.startsWith("mal:") == true

    private fun isLibraryAnimeId(id: String?): Boolean =
        isKitsuOrMalId(id) || id?.startsWith("anilist:") == true

    private fun isTmdbEligibleId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        if (IMDB_ID_REGEX.matches(id)) return true
        if (id.substringBefore(':') !in setOf("mal", "kitsu", "anilist", "tmdb")) return false
        return id.substringAfter(':', "").substringBefore(':').toIntOrNull()?.let { it > 0 } == true
    }

    private fun cachedFindMatches(
        imdbId: String?,
        tmdbId: String?,
        mediaType: String
    ): Boolean {
        if (imdbId.isNullOrBlank() || tmdbId.isNullOrBlank()) return false
        val cached = stremioCacheMemoryRead(tmdbFindCache["${imdbId}|${mediaType}"], METADATA_CACHE_TTL)
            ?: stremioCacheMemoryRead(tmdbFindCache[imdbId], METADATA_CACHE_TTL)
            ?: return false
        return cached.first?.first == tmdbId && cached.first?.second == mediaType
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
        imdbId: String, mediaType: String?
    ): Pair<String, String>? {
        if (mediaType != null && mediaType != "movie" && mediaType != "tv") return null
        val cacheKey = "${imdbId}|${mediaType ?: "any"}"
        val fromRam = stremioCacheMemoryRead(tmdbFindCache[cacheKey], METADATA_CACHE_TTL)
        val cachedFind = fromRam ?: MetadataDiskCache.read("find", cacheKey)?.let { (encoded, stamp) ->
            val parts = encoded.split('|')
            if (parts.size == 2 && parts[0].toIntOrNull() != null && parts[1] in listOf("tv", "movie")) {
                Pair(Pair(parts[0], parts[1]), stamp).also {
                    tmdbFindCache[cacheKey] = it
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
                when (mediaType) {
                    "movie" -> findRes?.movie_results?.firstOrNull()?.id
                        ?.let { it.toString() to "movie" }
                    "tv" -> findRes?.tv_results?.firstOrNull()?.id
                        ?.let { it.toString() to "tv" }
                    else -> findRes?.movie_results?.firstOrNull()?.id
                        ?.let { it.toString() to "movie" }
                        ?: findRes?.tv_results?.firstOrNull()?.id
                            ?.let { it.toString() to "tv" }
                }
            }.getOrNull()
            if (fetchedFind != null) {
                tmdbFindCache[cacheKey] = Pair(fetchedFind, System.currentTimeMillis())
                MetadataDiskCache.write("find", cacheKey, "${fetchedFind.first}|${fetchedFind.second}")
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
            "release_dates,images,videos,external_ids,recommendations,translations"
        } else {
            "content_ratings,images,videos,external_ids,recommendations,translations"
        }
        val videoLanguages = if (mediaType == "tv") arrayOf(
            "include_video_language" to tmdbTrailerLanguages().joinToString(",")
        ) else emptyArray()
        val detailUrl = buildTmdbUrl("/$mediaType/$tmdbId",
            "append_to_response" to detailAppend, "include_image_language" to tmdbLogoLanguages(),
            *videoLanguages)
        val fromRam = stremioCacheMemoryRead(tmdbDetailsCache[detailUrl], METADATA_CACHE_TTL)
        val cached = fromRam ?: MetadataDiskCache.read("detail", detailUrl)?.let { (json, stamp) ->
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
        val tmdbKey = StremioTmdb.apiKey
        if (tmdbKey.isBlank()) return ""
        builder.addQueryParameter("api_key", tmdbKey)
        builder.addQueryParameter("language", StremioTmdb.language())
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
                // The playback ID can already contain the provider's original IMDb S/E.
                id?.split(':')?.let { parts ->
                    parts.size == 3 && IMDB_ID_REGEX.matches(parts[0]) &&
                        parts[1].toIntOrNull()?.let { it >= 0 } == true &&
                        parts[2].toIntOrNull()?.let { it > 0 } == true
                } == true -> id
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

    private suspend fun fetchTmdbCredits(mediaType: String, tmdbId: String): AioCredits? {
        val url = buildTmdbUrl("/$mediaType/$tmdbId/credits")
        if (url.isBlank()) return null
        val cached = stremioCacheMemoryRead(tmdbCreditsCache[url], METADATA_CACHE_TTL)
        val credits = cached?.first ?: withTimeoutOrNull(12_000L) {
            runSuspendCatching {
                val text = fetchSharedMetadataText(url)
                if (text.isBlank()) null else parseJson<TmdbCreditsResponse>(text).also {
                    tmdbCreditsCache[url] = Pair(it, System.currentTimeMillis())
                }
            }.getOrNull()
        } ?: return null

        fun person(row: TmdbCreditPerson) = AioPerson(
            name = row.name, character = row.character,
            photo = getActorUrl(row.profilePath), tmdbId = row.id
        )
        return AioCredits(
            cast = credits.cast.orEmpty().map(::person),
            directors = credits.crew.orEmpty()
                .filter { it.job.equals("Director", ignoreCase = true) }.map(::person),
            writers = credits.crew.orEmpty()
                .filter { it.job in setOf("Writer", "Screenplay", "Story", "Teleplay") }
                .map(::person)
        )
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
            if (!catalog.supportsHome() ||
                (catalog.requiresExtra("skip") && !catalog.supportsExtra("skip"))) return@forEach
            val catalogId = catalog.id ?: ""
            val catalogType = catalog.type ?: "movie"
            val catalogName = catalog.name ?: catalogId
            val displayType = catalogType.replaceFirstChar { it.uppercase() }
            val extras = listOfNotNull(
                catalog.homeGenre()?.let { "genre=${it.urlEncodeLocal()}" },
                if (catalog.supportsExtra("skip")) "skip=###" else null
            ).joinToString("&")

            flatTabs.add(FlatTab(
                key = "${catalogId}_${catalogType}_default",
                displayName = "$catalogName - $displayType",
                urlPath = "/catalog/${catalogType.urlEncodeLocal()}/${catalogId.urlEncodeLocal()}" +
                    (if (extras.isNotEmpty()) "/$extras" else "") + ".json"
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
            executeMainPage(page, request).also {
            }
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

    private suspend fun fetchCatalogItems(
        path: String, fallbackType: String = "movie", origin: String = "home"
    ): Pair<List<SearchResponse>, Int> {
        val cacheKey = "$mainUrl|$fallbackType|$path"
        synchronized(recentHomeResults) {
            recentHomeResults[cacheKey]?.takeIf {
                System.currentTimeMillis() - it.savedAt in 0L..HOME_RESULTS_REUSE_MS
            }?.result
        }?.let {
            return it
        }
        return homeForegroundSemaphore.withPermit {
            val metas = fetchHomeJson(buildUrl(path)).metas
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
            val result = items to metas.size
            if (items.isNotEmpty() && items.size <= 100) {
                synchronized(recentHomeResults) {
                    recentHomeResults[cacheKey] = CachedHomeResult(result, System.currentTimeMillis())
                }
            }
            result
        }
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
        else fetchCatalogItems(catalog.browsePath(genre, offset), requireNotNull(catalog.type), "browser")

    override suspend fun search(query: String): List<SearchResponse>? {
        if (disableSearch) return emptyList()
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
        val startedAt = SystemClock.elapsedRealtime()
        android.util.Log.i("MetaLoadTiming", "phase=LOAD_START key=${effectiveUrl.hashCode()} bypass=${stremioMetadataDiagnosticBypass()}")
        var outcome = "OK"
        return try {
            loadInternal(effectiveUrl, forceRefresh).also { result ->
            }
        } catch (error: Throwable) {
            outcome = error.javaClass.simpleName
            throw error
        } finally {
            android.util.Log.i("MetaLoadTiming",
                "phase=LOAD_END key=${effectiveUrl.hashCode()} elapsedMs=${SystemClock.elapsedRealtime() - startedAt} outcome=$outcome")
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
                    ?: throw IllegalStateException("Configure a metadata addon before opening a library item.")
                val metaUrl = run {
                    getLoadUrl(libraryService, url)
                } ?: throw IllegalStateException("Unable to resolve the library item with the configured addon.")
                return@coroutineScope run {
                    StremioC(base, name).load(metaUrl).also { response ->

                        for (source in listOf(metaUrl, response.url)) {
                            animeAudioByUrl[source]?.let { animeAudioByUrl[url] = it }
                            episodeBasedByUrl[source]?.let { episodeBasedByUrl[url] = it }
                            seasonalAnimeByUrl[source]?.let { seasonalAnimeByUrl[url] = it }
            seasonEpisodeCountsByUrl[source]?.let { seasonEpisodeCountsByUrl[url] = it }
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
        val hintedAnimeId = urlHint?.id?.takeIf { isLibraryAnimeId(it) }
        val speculativeAnimeDeferred: Deferred<AnimeSeasonMapping?>? = hintedAnimeId?.let { id ->
            async(Dispatchers.IO) { run { resolveAnimeIdForLoad(id) } }
        }

        val speculativeTmdbDeferred: Deferred<SpeculativeTmdbChain?>? = when {
            hintMediaType == null -> null
            !urlHint?.tmdbId.isNullOrBlank() -> {
                val hintedTmdbId = urlHint?.tmdbId ?: ""
                async(Dispatchers.IO) {
                    val mediaType = hintMediaType ?: return@async null
                    val detail = run { fetchTmdbDetailForLoad(hintedTmdbId, mediaType) }
                    SpeculativeTmdbChain(urlHint?.id?.takeIf { it.startsWith("tt") }, hintedTmdbId, mediaType, false, detail)
                }
            }

            urlHint?.id?.startsWith("tmdb:") == true -> {
                val tmdbId = urlHint.id.removePrefix("tmdb:")
                async(Dispatchers.IO) {
                    val mediaType = hintMediaType ?: return@async null
                    val detail = run { fetchTmdbDetailForLoad(tmdbId, mediaType) }
                    SpeculativeTmdbChain(null, tmdbId, mediaType, false, detail)
                }
            }
            speculativeAnimeDeferred != null -> async(Dispatchers.IO) {
                val mediaType = hintMediaType ?: return@async null
                val resolved = run { speculativeAnimeDeferred.await() } ?: return@async null
                val tmdbId = resolved.tmdbId?.toString()
                if (!tmdbId.isNullOrBlank()) {
                    val detail = fetchTmdbDetailForLoad(tmdbId, mediaType)
                    SpeculativeTmdbChain(resolved.imdbId, tmdbId, mediaType, false, detail)
                } else if (!resolved.imdbId.isNullOrBlank()) {
                    val find = fetchTmdbFindForLoad(resolved.imdbId, mediaType) ?: return@async null
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
        var resolvedTmdbMediaType: String? = null

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
                resolvedTmdbMediaType = resolved.tmdbMediaType
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
        if (authoritativeMediaType == null) speculativeTmdbDeferred?.cancel()

        var acceptedTmdbSpec: SpeculativeTmdbChain? = null

        if (authoritativeMediaType != null &&
            (hintMatchesAuthoritative || (originalMappingId != null && !resolvedTmdbId.isNullOrBlank())) &&
            speculativeTmdbDeferred != null) {
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
            ?.takeIf { it.second == authoritativeMediaType }
        val validatedFindImdbId = acceptedTmdbSpec?.takeIf {
            it.viaFind && it.mediaType == authoritativeMediaType
        }?.sourceImdbId
        val targetTmdbId = if (authoritativeMediaType != null)
            directTmdbFromRes ?: validatedFindResult?.first else null
        val targetTmdbMediaType = authoritativeMediaType
        val speculativeTmdbDetail = acceptedTmdbSpec?.takeIf {
            targetTmdbId != null && targetTmdbMediaType != null &&
                it.matchesDirect(targetTmdbId, targetTmdbMediaType)
        }?.detail

        val normalTmdbDeferred: Deferred<TmdbDetailResponse?>? = if (
            targetTmdbId != null && targetTmdbMediaType != null && speculativeTmdbDetail == null
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
            resolvedTmdbMediaType = resolvedTmdbMediaType,
            resolvedIdHolder = resolvedIdHolder,
            requestedAnimeId = requestedAnimeId,
            prefetchedAnimeMapping = speculativeImdbAnimeDeferred?.takeIf { hintedImdbId == res.id },
            prefetchedAnimeImdbId = hintedImdbId
        ) }

        if (url != loadRes.url) {
            animeAudioByUrl[loadRes.url]?.let { animeAudioByUrl[url] = it }
            seasonEpisodeCountsByUrl[loadRes.url]?.let { seasonEpisodeCountsByUrl[url] = it }
            genreCatalogsByUrl[loadRes.url]?.let { genreCatalogsByUrl[url] = it }
        }

        val libraryOrigins = synchronized(libraryOriginalUrlsByMeta) {
            libraryOriginalUrlsByMeta[url]?.toList().orEmpty()
        }
        for (originalUrl in libraryOrigins) {
            animeAudioByUrl[url]?.let { animeAudioByUrl[originalUrl] = it }
            seasonEpisodeCountsByUrl[url]?.let { seasonEpisodeCountsByUrl[originalUrl] = it }
            genreCatalogsByUrl[url]?.let { genreCatalogsByUrl[originalUrl] = it }
        }
        collectionDeferred?.cancel()
        return@coroutineScope loadRes
    }

    private fun traceSource(traceId: String, phase: String, details: String) {
        android.util.Log.i("StremioSourceTrace", "trace=$traceId phase=$phase $details")
    }

    private fun tracePayload(payload: Map<String, Any?>): String =
        listOf("tvtype", "type", "id", "imdbId", "imdb_id", "tmdbId",
            "season", "episode", "imdbSeason", "imdbEpisode", "isKitsu",
            "isAnime", "anilistId", "aniId", "malId", "kitsuId",
            "year", "airedYear", "firstAired", "airedDate", "date", "lastSeason",
            "title", "orgTitle", "jpTitle")
            .joinToString(" ") { key ->
                "$key=${if (payload.containsKey(key)) payload[key] else "<absent>"}"
            }

    private fun isOriginalImdbEpisode(loadData: LoadData): Boolean =
        loadData.originalAnimeId == null &&
            loadData.id?.substringBefore(':')?.let { IMDB_ID_REGEX.matches(it) } == true &&
            loadData.season != null && loadData.episode != null

    private fun originalImdbSeason(loadData: LoadData): Int? =
        loadData.imdbSeason?.takeIf { it == 0 } ?: loadData.season

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val traceId = SystemClock.elapsedRealtimeNanos().toString(36)
        if (!isAddonEnabled()) {
            traceSource(traceId, "EXIT", "reason=ADDON_DISABLED")
            return false
        }
        val loadData = try { parseJson<LoadData>(data) } catch (e: Exception) {
            traceSource(traceId, "EXIT", "reason=INVALID_LOAD_DATA error=${e.javaClass.simpleName}")
            null
        } ?: run {
            return false
        }
        traceSource(traceId, "ENTRY",
            "source=CloudStream type=${loadData.type} id=${loadData.id} imdbId=${loadData.imdbId}" +
            " tmdbId=${loadData.tmdbId} S=${loadData.season} E=${loadData.episode}" +
            " imdbS=${loadData.imdbSeason} imdbE=${loadData.imdbEpisode}" +
            " originalAnimeId=${loadData.originalAnimeId} mal=${loadData.malId}" +
            " anilist=${loadData.anilistId} kitsu=${loadData.kitsuId}" +
            " aired=${loadData.firstAired} airedYear=${loadData.airedYear}" +
            " isAnime=${loadData.isAnime} embedded=${!loadData.embeddedStreams.isNullOrBlank()}")

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

        val originalImdbEpisode = isOriginalImdbEpisode(loadData)
        val globalSeason = if (actualIsMovie) null else if (originalImdbEpisode) originalImdbSeason(loadData)
            else loadData.imdbSeason?.takeIf { it >= 0 } ?: loadData.season?.takeIf { it >= 0 } ?: 1
        val globalEpisode = if (actualIsMovie) null else if (originalImdbEpisode) loadData.episode
            else loadData.imdbEpisode?.takeIf { it > 0 } ?: loadData.episode?.takeIf { it > 0 } ?: 1
        traceSource(traceId, "NORMALIZE",
            "primaryId=$primaryId legacyPrimaryId=$legacyPrimaryId globalId=$globalId" +
            " targetType=$targetType integrationType=$integrationTargetType" +
            " movie=$actualIsMovie orig=S${loadData.season}:E${loadData.episode}" +
            " imdb=S${loadData.imdbSeason}:E${loadData.imdbEpisode}" +
            " global=S$globalSeason:E$globalEpisode" +
            " globalSource=${if (loadData.imdbSeason != null || loadData.imdbEpisode != null) "IMDB_FIELDS" else "DISPLAY_OR_DEFAULT"}")

        val seenLinks = Collections.synchronizedSet(mutableSetOf<Triple<String, String, Map<String, String>>>())

        val seenStremioSubtitles = Collections.synchronizedSet(mutableSetOf<String>())
        fun emitUniqueSubtitle(sub: SubtitleFile) {
            if (sub.url.isBlank() || sub.lang.trim().let {
                    it.isBlank() || it.equals("und", true)
                }) return
            if (seenStremioSubtitles.add(sub.url)) subtitleCallback(sub)
        }
        val stremioSourceNames = Collections.synchronizedSet(mutableSetOf<String>()).apply {
            name.trim().takeIf { it.isNotEmpty() }?.let { add(it.lowercase()) }
            getIntegrationStremioAddons().forEach { addon ->
                addon.name.trim().takeIf { it.isNotEmpty() }?.let { add(it.lowercase()) }
            }
        }
        val uniqueLinks = java.util.concurrent.atomic.AtomicInteger(0)
        val duplicateLinks = java.util.concurrent.atomic.AtomicInteger(0)
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
                val count = uniqueLinks.incrementAndGet()
                traceSource(traceId, "LINK_ACCEPT",
                    "source=${link.source} quality=${link.quality} ordinal=$count")
                callback(link)
            } else {
                val count = duplicateLinks.incrementAndGet()
                traceSource(traceId, "LINK_DUPLICATE",
                    "source=${link.source} ordinal=$count")
            }
        }

        val currentMainUrl = loadData.addonUrl?.takeIf { it.isNotBlank() } ?: mainUrl
        val cleanBaseUrl = currentMainUrl.fixSourceUrl().cleanBaseUrl()
        val querySuffix = currentMainUrl.substringAfter("?", "").takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""

        val syncSettings = getSyncSettings()
        val syncUrl = syncSettings.syncUrl
        val currentCleanUrl = currentMainUrl.cleanBaseUrl().lowercase()

        val savedStreamsAddon = syncSettings.streamsAddonMap[currentCleanUrl]
        val savedSubtitlesAddon = syncSettings.subtitlesAddonMap[currentCleanUrl]
        val libraryHistoryStreamSync = savedStreamsAddon == null && savedSubtitlesAddon == null && mainUrl.isBlank() &&
            syncUrl.isNotBlank() && currentCleanUrl == syncUrl.cleanBaseUrl().lowercase() &&
            stremioManifestUrl(currentMainUrl) !in syncSettings.disabledCatalogUrls
        val streamsAddonEnabled = savedStreamsAddon ?: libraryHistoryStreamSync
        val subtitlesAddonEnabled = savedSubtitlesAddon ?: libraryHistoryStreamSync
        val extraUrls = if (streamsAddonEnabled || subtitlesAddonEnabled) {
            syncSettings.streamSubtitleUrls.filter { addon ->
                val parsed = addon.url.toHttpUrlOrNull()
                parsed != null && (parsed.scheme == "http" || parsed.scheme == "https")
            }.filterNot { stremioManifestUrl(it.url) == stremioManifestUrl(currentMainUrl) }
        } else emptyList()
        val stremioAddons = (syncSettings.catalogAddons + syncSettings.streamSubtitleUrls)
            .distinctBy { stremioManifestUrl(it.url) }
        val integrationAddons = getIntegrationStremioAddons()
        val cineAddons = integrationAddons.filter { it.fromCineStream }
        val streamPlayAddons = integrationAddons.filterNot { it.fromCineStream }
        fun rankSubtitle(sub: SubtitleFile, group: Int, addonUrl: String? = null) {
            if (sub.url.isBlank()) return
            // Retain details from direct integration callbacks as well as Stremio API responses.
            val originalDetail = sub.lang.substringAfter(" · ", "").trim()
            if (originalDetail.isNotEmpty()) synchronized(subtitleDetailByUrl) {
                if (subtitleDetailByUrl[sub.url] == null) subtitleDetailByUrl[sub.url] = originalDetail
            }
            val source = subtitleSourceByUrl[sub.url] ?: sub.lang.substringAfterLast(" — ", "").trim()
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
                group == 0 && index >= 0 -> 1_000 + index
                group == 1 && index >= 0 -> 10_000 + index
                group == 2 && index >= 0 -> 20_000 + index
                else -> 30_000
            }
            synchronized(subtitleOrderByUrl) {
                val previous = subtitleOrderByUrl[sub.url]
                if (previous == null || priority < previous) subtitleOrderByUrl[sub.url] = priority
            }
        }

        fun stremioSubtitleFrom(addonUrl: String, group: Int = 0): (SubtitleFile) -> Unit = { sub ->
            rankSubtitle(sub, group, addonUrl)
            emitUniqueSubtitle(sub)
        }
        fun integratedSubtitleFrom(group: Int): (SubtitleFile) -> Unit = { sub ->
            rankSubtitle(sub, group)
            emitUniqueSubtitle(sub)
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
            if (!requestedSubtitleUrls.add(subtitleRequestUrl)) {
                traceSource(traceId, "STREMIO_SUBTITLE_SKIP",
                    "type=$requestType id=$subId reason=DUPLICATE")
                return
            }
            traceSource(traceId, "STREMIO_SUBTITLE_REQUEST",
                "source=${preferredName ?: addonUrl.toHttpUrlOrNull()?.host}" +
                " type=$requestType id=$subId input=S$season:E$episode group=$group")
            runSuspendCatching {
                val response = customSession.get(subtitleRequestUrl, timeout = if (animeIdRequest) 50L else 15L)
                val text = response.text
                val res = if (text.isNotBlank()) parseJson<SubtitlesResponse>(text) else null
                traceSource(traceId, "STREMIO_SUBTITLE_RESPONSE",
                    "type=$requestType id=$subId count=${res?.subtitles?.size ?: 0}")
                val addonSource = preferredName?.takeIf { it.isNotBlank() }
                    ?: getActualStremioAddonName(addonUrl)
                    ?: name.takeIf { addonUrl == currentMainUrl && !it.equals("StremioC", true) }
                res?.subtitles?.let { entries -> entries.forEachIndexed { position, sub ->
                    sub.toCloudStreamSubtitle(addonSource, preferredName, sub.translationSourceLanguage(entries))?.let { file ->
                        stremioSubtitleFrom(addonUrl, group)(file)
                    }
                } }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                traceSource(traceId, "STREMIO_SUBTITLE_ERROR",
                    "type=$requestType id=$subId error=${error.javaClass.simpleName}")
            }

        }

        coroutineScope {
        val animeRouteDeferred = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            if (!actualIsMovie) resolveAnimeEpisodeRoute(loadData, traceId) else null
        }
        val mappingDeferred = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val route = animeRouteDeferred.await()
            val mapped = route?.mapping
            traceSource(traceId, "AIO_MAPPING",
                "via=${route?.via} found=${mapped != null}" +
                " imdb=${mapped?.imdbId} kitsu=${route?.kitsuId ?: mapped?.kitsuId}" +
                " mal=${mapped?.malId} al=${mapped?.aniListId} animeE=${route?.episode}" +
                " absolute=${route?.absolute}" +
                " tmdb=${mapped?.tmdbId}:${mapped?.tmdbSeason}:${mapped?.tmdbEpisode}" +
                " tvdb=${mapped?.tvdbSeason} ranges=${mapped?.tvdbRanges?.size ?: 0}")
            mapped
        }

        suspend fun fetchAniListSubtitles(
            addonUrl: String, preferredName: String? = null, group: Int = 0
        ) {
            if (actualIsMovie || !(loadData.isAnime || loadData.originalAnimeId != null ||
                    loadData.anilistId != null || loadData.malId != null || loadData.kitsuId != null ||
                    isLibraryAnimeId(loadData.id) || (loadData.isCartoon && loadData.isAsian))) return

            val route = animeRouteDeferred.await()
            val aniListId = route?.mapping?.aniListId
                ?: (loadData.originalAnimeId ?: loadData.id)?.takeIf { it.startsWith("anilist:") }
                    ?.substringAfter(':')?.substringBefore(':')?.toIntOrNull()
            val animeEpisode = route?.episode
            traceSource(traceId, "ANILIST_SUBTITLE_ID",
                "addon=${preferredName ?: addonUrl.toHttpUrlOrNull()?.host}" +
                " via=${route?.via} al=$aniListId E=$animeEpisode absolute=${route?.absolute}" +
                " original=S${loadData.season}:E${loadData.episode}" +
                " status=${if (aniListId == null || animeEpisode == null) "SKIP_UNVERIFIED" else "READY"}")
            if (aniListId == null || animeEpisode == null) return
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
            if (originalImdbEpisode) return null
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
            traceSource(traceId, "STREMIO_ROUTE",
                "inputType=$type inputId=$id input=S$season:E$episode" +
                " originalFirst=$originalFirst mappedId=$mappedId" +
                " outputType=${result?.first} outputId=${result?.second}" +
                " originalAnimeId=${loadData.originalAnimeId}")
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
            addonUrl: String? = null,
            emitLinks: Boolean = true,
            emitSubtitles: Boolean = true
        ): Int = coroutineScope {
            val extractorFlights = ExtractorFlightGroup()
            val jobs = streams.orEmpty().map { stream -> async(Dispatchers.IO) {
                val readyLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                withStreamCallback(category) {
                    stream.runCallback(this@StremioC, source, subtitleHandler, extractorSubtitleHandler,
                        { readyLinks.add(it) }, preferredName, addonUrl, emitLinks, emitSubtitles,
                        extractorFlights)
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

        // CineStream's user HTTPS/DEBRID loader does not forward stream.subtitles.
        // Mirror its exact payload route, but emit only subtitles, never duplicate links.
        val cineSubtitleRequests = Collections.synchronizedSet(mutableSetOf<String>())
        val cineSubtitleLimiter = Semaphore(4)
        suspend fun fetchCineInlineSubtitles(payload: Map<String, Any?>): Unit = coroutineScope {
            val imdb = (payload["imdb_id"] as? String)?.takeIf { IMDB_ID_REGEX.matches(it) }
                ?: return@coroutineScope
            val animeRoute = payload["isKitsu"] == true
            val season = payload[if (animeRoute) "imdbSeason" else "season"] as? Int
            val episode = payload[if (animeRoute) "imdbEpisode" else "episode"] as? Int
            if (!actualIsMovie && (season == null || episode == null)) return@coroutineScope
            val type = if (actualIsMovie) "movie" else "series"
            val id = if (actualIsMovie) imdb else "$imdb:$season:$episode"
            traceSource(traceId, "CINE_INLINE_ROUTE",
                "animeRoute=$animeRoute type=$type id=$id" +
                " payload=${tracePayload(payload)}")
            integrationAddons.filter { it.fromCineStream && it.cineInlineSubtitles }.map { addon ->
                async(Dispatchers.IO) {
                    cineSubtitleLimiter.withPermit {
                        val base = addon.url.fixSourceUrl().cleanBaseUrl()
                        if (base.isBlank()) return@withPermit
                        val suffix = addon.url.substringAfter("?", "").takeIf { it.isNotEmpty() }
                            ?.let { "?$it" } ?: ""
                        val requestUrl = "$base/stream/$type/${id.urlEncode()}.json$suffix"
                        if (!cineSubtitleRequests.add(requestUrl)) {
                            traceSource(traceId, "CINE_INLINE_SKIP",
                                "source=${addon.name} id=$id reason=DUPLICATE")
                            return@withPermit
                        }
                        traceSource(traceId, "CINE_INLINE_REQUEST",
                            "source=${addon.name} type=$type id=$id")
                        runSuspendCatching {
                            val text = customSession.get(requestUrl, timeout = 30L).text
                            val response = if (text.isNotBlank()) parseJson<StreamsResponse>(text) else null
                            traceSource(traceId, "CINE_INLINE_RESPONSE",
                                "source=${addon.name} id=$id streams=${response?.streams?.size ?: 0}" +
                                " subtitles=${response?.streams?.sumOf { it.subtitles.size } ?: 0}")
                            response?.streams.orEmpty().forEach { stream ->
                                if (stream.url == null && stream.infoHash == null &&
                                    stream.externalUrl == null && stream.ytId == null) return@forEach
                                stream.subtitles.forEachIndexed { position, sub ->
                                    sub.toCloudStreamSubtitle(addon.name, addon.name,
                                        sub.translationSourceLanguage(stream.subtitles))?.let { file ->
                                        stremioSubtitleFrom(addon.url, 1)(file)
                                    }
                                }
                            }
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            traceSource(traceId, "CINE_INLINE_ERROR",
                                "source=${addon.name} id=$id error=${error.javaClass.simpleName}")
                        }
                    }
                }
            }.awaitAll()
            Unit
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
                        val count = emitOrderedAddonStreams(namedStreams, name,
                            stremioSubtitleFrom(currentMainUrl), extractorSubtitleFrom(currentMainUrl),
                            "embedded", addonUrl = currentMainUrl)
                        traceSource(traceId, "STREMIO_EMBEDDED",
                            "source=$name streams=${namedStreams.size} emitted=$count" +
                            " input=S${loadData.season}:E${loadData.episode}")
                    }
                }
            },
            {
                val request = streamRequest(targetType, primaryId, loadData.season, loadData.episode)
                if (cleanBaseUrl.isNotBlank() && request != null) {
                    suspend fun fetchMainStreams(route: Pair<String, String>): Int =
                        runSuspendCatching {
                            val startedAt = SystemClock.elapsedRealtime()
                            traceSource(traceId, "STREMIO_MAIN_REQUEST",
                                "source=$name type=${route.first} id=${route.second}" +
                                " original=S${loadData.season}:E${loadData.episode}")
                            val streamRequestUrl = "$cleanBaseUrl/stream/${route.first}/${route.second.urlEncode()}.json$querySuffix"
                            val response = customSession.get(streamRequestUrl, timeout = 30L)
                            val text = response.text
                            val res = parseTracedStreams(text, traceId, name,
                                route.first, route.second, response.code,
                                response.headers.firstOrNull { it.first.equals("Content-Type", true) }?.second)
                            val emitted = emitOrderedAddonStreams(res?.streams, name,
                                stremioSubtitleFrom(currentMainUrl), extractorSubtitleFrom(currentMainUrl),
                                "main", addonUrl = currentMainUrl)
                            traceSource(traceId, "STREMIO_MAIN_RESPONSE",
                                "source=$name type=${route.first} id=${route.second}" +
                                " streams=${res?.streams?.size ?: 0} emitted=$emitted" +
                                " elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                            emitted
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            traceSource(traceId, "STREMIO_MAIN_ERROR",
                                "source=$name type=${route.first} id=${route.second}" +
                                " error=${error.javaClass.simpleName}")
                        }.getOrDefault(0)

                    val originalLinks = fetchMainStreams(request)
                    if (targetType != "movie" && targetType != "series" && originalLinks == 0) {
                        val fallbackRequest = if (actualIsMovie) {
                            integrationImdbId?.let { "movie" to it }
                        } else {
                            mappedAnimeStremioId()?.let { "series" to it }
                        }
                        if (fallbackRequest != null && fallbackRequest != request) {
                            traceSource(traceId, "STREMIO_MAIN_FALLBACK",
                                "from=${request.first}:${request.second}" +
                                " to=${fallbackRequest.first}:${fallbackRequest.second}")
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
                                val request = streamRequest(extraType, reqId, season, episode)
                                val source = addon.name.takeIf { it.isNotBlank() }
                                    ?: getActualStremioAddonName(addonUrl)
                                    ?: base.toHttpUrlOrNull()?.host.orEmpty()
                                if (source.isNotBlank()) stremioSourceNames.add(source.lowercase())
                                runAllAsync({
                                    if (request != null && base.isNotBlank()) {
                                        suspend fun fetchExtraStreams(route: Pair<String, String>): Int = runSuspendCatching {
                                            val startedAt = SystemClock.elapsedRealtime()
                                            traceSource(traceId, "STREMIO_EXTRA_REQUEST",
                                                "source=$source type=${route.first} id=${route.second}" +
                                                " original=S${loadData.season}:E${loadData.episode}")
                                            val requestUrl = "$base/stream/${route.first}/${route.second.urlEncode()}.json$suffix"
                                            val response = customSession.get(requestUrl, timeout = 30L)
                                            val text = response.text
                                            val streams = parseTracedStreams(text, traceId, source,
                                                route.first, route.second, response.code,
                                                response.headers.firstOrNull { it.first.equals("Content-Type", true) }?.second)
                                            val emitted = emitOrderedAddonStreams(streams?.streams, source,
                                                stremioSubtitleFrom(addonUrl), extractorSubtitleFrom(addonUrl),
                                                "extra", addon.name, addonUrl, streamsAddonEnabled, subtitlesAddonEnabled)
                                            val resultCount = if (streamsAddonEnabled) emitted
                                                else streams?.streams?.count { it.subtitles.isNotEmpty() } ?: 0
                                            traceSource(traceId, "STREMIO_EXTRA_RESPONSE",
                                                "source=$source type=${route.first} id=${route.second}" +
                                                " streams=${streams?.streams?.size ?: 0} emitted=$resultCount" +
                                                " elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                                            resultCount
                                        }.onFailure { error ->
                                            if (error is CancellationException) throw error
                                            traceSource(traceId, "STREMIO_EXTRA_ERROR",
                                                "source=$source type=${route.first} id=${route.second}" +
                                                " error=${error.javaClass.simpleName}")
                                        }.getOrDefault(0)
                                        val originalLinks = fetchExtraStreams(request)
                                        if (extraType != "movie" && extraType != "series" && originalLinks == 0) {
                                            val fallback = if (actualIsMovie) {
                                                integrationImdbId?.let { "movie" to it }
                                            } else {
                                                mappedAnimeStremioId()?.let { "series" to it }
                                            }
                                            if (fallback != null && fallback != request) {
                                                traceSource(traceId, "STREMIO_EXTRA_FALLBACK",
                                                    "source=$source from=${request.first}:${request.second}" +
                                                    " to=${fallback.first}:${fallback.second}")
                                                fetchExtraStreams(fallback)
                                            }
                                        }
                                    }
                                }, {
                                    if (subtitlesAddonEnabled) {
                                        val mappedId = mappedAnimeStremioId()
                                        if (mappedId != null) {
                                            fetchAnimeAwareSubtitles(addonUrl, mappedId, null, null, "series", addon.name)
                                        } else if (!isLibraryAnimeId(reqId) || actualIsMovie) {
                                            fetchAnimeAwareSubtitles(addonUrl, reqId, season, episode, extraType, addon.name)
                                        }
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
                invokeCineStream(loadData, integrationTargetType, globalId, globalSeason, globalEpisode, isCasting, integratedSubtitleFrom(1), wrappedCallback, mappingDeferred, animeRouteDeferred, ::fetchCineInlineSubtitles, traceId)
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
                    wrappedCallback,
                    traceId
                )
            },
            {
                invokeStreamPlay(loadData, integrationTargetType, globalId, globalSeason, globalEpisode, isCasting, integratedSubtitleFrom(2), wrappedCallback, mappingDeferred, animeRouteDeferred, traceId)
            },
            {
                if (isIntegrationEnabled("vixsrc_integration_enabled")) {
                    VixSrcExtractor.invokeVixSrc(loadData, targetType, globalSeason, globalEpisode, integratedSubtitleFrom(3), wrappedCallback)
                }
            }
        )
        mappingDeferred.cancel()
        animeRouteDeferred.cancel()
        }
        traceSource(traceId, "EXIT", "reason=COMPLETE uniqueLinks=$uniqueLinks duplicateLinks=$duplicateLinks")
        return true
    }

    private data class TmdbEpisodeRange(val season: Int, val start: Int, val end: Int?)


    private data class TvdbEpisodeRange(val season: Int, val start: Int, val end: Int?, val offset: Int)
    private data class AnimeEpisodeRoute(
        val mapping: AnimeSeasonMapping?, val kitsuId: String?,
        val episode: Int?, val absolute: Int?, val via: String
    )
    private data class AnimeSeasonMapping(
        val kitsuId: String?, val malId: Int?, val aniListId: Int?, val tmdbId: Int?,
        val simklId: Int? = null, val tvdbId: Int? = null,
        val tmdbSeason: Int?, val tmdbEpisode: Int?, val title: String?,
        val imdbId: String?, val tvdbSeason: Int?, val imdbSeason: Int?,
        val traktSeason: Int?, val tmdbFromEpisode: Int? = null,
        val tmdbMediaType: String? = null,
        val tmdbRanges: List<TmdbEpisodeRange> = emptyList(),
        val tvdbFromEpisode: Int? = null,
        val tvdbRanges: List<TvdbEpisodeRange> = emptyList(),
        val tvdbAbsoluteOrder: Boolean? = null,
        val alternateImdbId: String? = null,
        val alternateImdbFromEpisode: Int? = null
    )

    private suspend fun fetchKitsuAbsoluteEpisodes(rawId: String?): Map<Int, Int> {
        val id = rawId?.removePrefix("kitsu:")?.substringBefore(':')
            ?.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: return emptyMap()
        stremioCacheMemoryRead(kitsuEpisodeCache[id], METADATA_CACHE_TTL)
            ?.first?.let { return it }
        val episodes = runSuspendCatching {
            val text = fetchSharedAnimeText("https://anime-kitsu.strem.fun/meta/series/kitsu:" +
                id + ".json")
            val videos = parseJson<JsonNode>(text).get("meta")?.get("videos")
            if (videos == null || !videos.isArray) emptyMap() else
                videos.mapNotNull { video ->
                    val local = video.get("episode").intOrNull()?.takeIf { it > 0 }
                    val absolute = video.get("absoluteEpisode").intOrNull()?.takeIf { it > 0 }
                    if (local != null && absolute != null) local to absolute else null
                }.toMap()
        }.getOrNull() ?: emptyMap()
        kitsuEpisodeCache[id] = episodes to System.currentTimeMillis()
        return episodes
    }

    /** IMDb-form Kitsu metadata preserves the addon episode and its absolute number. */
    private suspend fun fetchKitsuImdbEpisodes(imdbId: String?): Map<Pair<Int, Int>, Int> {
        val imdb = imdbId?.takeIf { IMDB_ID_REGEX.matches(it) } ?: return emptyMap()
        stremioCacheMemoryRead(kitsuImdbEpisodeCache[imdb], METADATA_CACHE_TTL)
            ?.first?.let { return it }
        val episodes = runSuspendCatching {
            val text = fetchSharedAnimeText("https://anime-kitsu.strem.fun/meta/series/$imdb.json")
            val videos = parseJson<JsonNode>(text).get("meta")?.get("videos")
            if (videos == null || !videos.isArray) emptyMap() else
                videos.mapNotNull { row ->
                    val season = row.get("season").intOrNull()?.takeIf { it >= 0 }
                    val episode = (row.get("episode").intOrNull()
                        ?: row.get("number").intOrNull())?.takeIf { it > 0 }
                    val absolute = row.get("absoluteEpisode").intOrNull()?.takeIf { it > 0 }
                    if (season != null && episode != null && absolute != null)
                        (season to episode) to absolute else null
                }.toMap()
        }.getOrNull() ?: emptyMap()
        if (!stremioMetadataDiagnosticBypass())
            kitsuImdbEpisodeCache[imdb] = episodes to System.currentTimeMillis()
        return episodes
    }

    private suspend fun fetchAnimeSeasonMapping(
        loadData: LoadData, traceId: String? = null, tvdbLookupId: Int? = null
    ): AnimeSeasonMapping? {
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
            tvdbLookupId != null -> "idType=thetvdbId&idValue=$tvdbLookupId" +
                (if (season != null) "&season=$season" else "") +
                (if (episode != null) "&episode=$episode" else "")
            byMal -> "idType=malId&idValue=$id"
            byAniList -> "idType=anilistId&idValue=$id"
            byKitsu || (id == kitsuId && imdbId == null) -> "idType=kitsuId&idValue=$id"
            imdbId == null && malLookupId != null -> "idType=malId&idValue=$id"
            imdbId == null && aniListLookupId != null -> "idType=anilistId&idValue=$id"
            else -> "idType=imdbId&idValue=$id" +
                (if (season != null) "&season=$season" else "") +
                (if (episode != null) "&episode=$episode" else "")
        }
        if (traceId != null) traceSource(traceId, "AIO_QUERY",
            "query=$query requested=S$season:E$episode" +
            " byKitsu=$byKitsu byMal=$byMal byAniList=$byAniList" +
            " originalAnimeId=${loadData.originalAnimeId}" +
            " inputImdb=$imdbId inputKitsu=$kitsuId inputMal=$malLookupId inputAl=$aniListLookupId" +
            " lookupTvdb=$tvdbLookupId")
        val memoryAnime = stremioCacheMemoryRead(animeMappingCache[query], METADATA_CACHE_TTL)
        val cachedAnime = memoryAnime ?: MetadataDiskCache.read("anime", query)?.let { (json, stamp) ->
            runCatching { parseJson<AnimeSeasonMapping>(json) }.getOrNull()?.let { value ->
                Pair(value, stamp).also {
                    animeMappingCache[query] = it
                }
            }
        }
        if (cachedAnime != null) {
            if (traceId != null) traceSource(traceId, "AIO_CACHE_HIT",
                "query=$query cache=${if (memoryAnime != null) "RAM" else "DISK"}" +
                " imdb=${cachedAnime.first.imdbId} kitsu=${cachedAnime.first.kitsuId}" +
                " mal=${cachedAnime.first.malId} al=${cachedAnime.first.aniListId}" +
                " tmdb=${cachedAnime.first.tmdbId}")
            return cachedAnime.first
        }
        if (traceId != null) traceSource(traceId, "AIO_CACHE_MISS", "query=$query")
        val noMappingStamp = if (stremioMetadataDiagnosticBypass()) null else animeNoMappingCache[query]
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
            var acceptedResponse = false
            val requestStarted = SystemClock.elapsedRealtime()
            if (traceId != null) traceSource(traceId, "AIO_HOST_REQUEST",
                "host=$host season=$season episode=$episode")
            val outcome = runSuspendCatching {
            val response = run {
                fetchSharedAnimeText("https://$host/api/v1/anime?$query")
            }
            val root = run { parseJson<JsonNode>(response) }
            if (root.get("success")?.asBoolean() != true || root.get("detail").textOrNull() != "OK") {
                return@runSuspendCatching null
            }
            if (root.get("data")?.isNull == true) {
                return@runSuspendCatching null
            }
            val data = root.get("data") ?: run {
                return@runSuspendCatching null
            }
            val ids = data.get("mappings")?.takeIf {
                it.isObject && it.size() > 0
            } ?: run {
                return@runSuspendCatching null
            }
            acceptedResponse = true
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
                if (traceId != null) traceSource(traceId, "AIO_ID_REJECT",
                    "query=$query reason=IMDB_ID_MISMATCH expected=$imdbId got=$resolvedImdb")
                return@runSuspendCatching null
            }
            if (tvdbLookupId != null &&
                (ids.get("thetvdbId").intOrNull() != tvdbLookupId ||
                    data.get("tvdb")?.get("seasonNumber").intOrNull() != season)) {
                if (traceId != null) traceSource(traceId, "AIO_ID_REJECT",
                    "query=$query reason=TVDB_SEASON_OR_ID_MISMATCH" +
                    " expectedId=$tvdbLookupId gotId=${ids.get("thetvdbId").intOrNull()}" +
                    " expectedSeason=$season gotSeason=${data.get("tvdb")?.get("seasonNumber").intOrNull()}")
                return@runSuspendCatching null
            }
            if (resolvedKitsu == null && ids.get("malId").intOrNull() == null &&
                ids.get("anilistId").intOrNull() == null) {
                return@runSuspendCatching null
            }
            if (traceId != null) traceSource(traceId, "AIO_ID_VERIFY",
                "query=$query host=$host imdb=$resolvedImdb" +
                " alternativeImdb=${data.get("imdb")?.get("id").textOrNull()}" +
                " kitsu=$resolvedKitsu mal=${ids.get("malId").intOrNull()}" +
                " al=${ids.get("anilistId").intOrNull()}" +
                " tmdb=${ids.get("themoviedbId").intOrNull()} accepted=true")

            val tvdbRanges = data.get("episodeMappings")?.takeIf { it.isArray }
                ?.mapNotNull { row ->
                    val tvdbSeason = row.get("tvdbSeason").intOrNull()?.takeIf { it > 0 }
                    val start = row.get("start").intOrNull()?.takeIf { it > 0 }
                    val end = row.get("end").intOrNull()
                    val offset = row.get("offset").intOrNull()
                    if (tvdbSeason != null && start != null && offset != null &&
                        (end == null || end >= start))
                        TvdbEpisodeRange(tvdbSeason, start, end, offset) else null
                }.orEmpty()
            val tmdbRanges = data.get("episodeMappings")?.takeIf { it.isArray }
                ?.mapNotNull { row ->
                    val target = row.get("tmdbSeason").intOrNull()?.takeIf { it > 0 }
                    val start = row.get("start").intOrNull()?.takeIf { it > 0 }
                    val end = row.get("end").intOrNull()
                    if (target != null && start != null && (end == null || end >= start))
                        TmdbEpisodeRange(target, start, end) else null
                }.orEmpty()
            val mappedSeason = if (tvdbLookupId != null) {
                data.get("tvdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
            } else {
                data.get("imdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
                    ?: data.get("tvdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
                    ?: data.get("trakt")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
                    ?: data.get("tmdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 }
            }
            val seasonMismatch = !byKitsu && !byMal && !byAniList &&
                season != null && season > 1 && mappedSeason != season
            if (seasonMismatch) android.util.Log.i("MetaEpisodeMap",
                "phase=AIO_SEASON_UNRESOLVED season=$season mappedSeason=$mappedSeason ranges=${tmdbRanges.size} host=$host")
            val tmdb = data.get("tmdb")
            val first = tmdb?.get("fromEpisode").intOrNull()
                ?.takeIf { it > 0 && !seasonMismatch }
            val tmdbSeason = tmdb?.get("seasonNumber").intOrNull()
                ?.takeIf { it > 0 && !seasonMismatch }
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
                tmdbMediaType = when (data.get("type").textOrNull()?.uppercase()) {
                    "TV" -> "tv"
                    "MOVIE" -> "movie"
                    else -> null
                },
                simklId = ids.get("simklId").intOrNull()?.takeIf { it > 0 },
                tvdbId = ids.get("thetvdbId").intOrNull()?.takeIf { it > 0 },
                tmdbSeason = tmdbSeason,
                tmdbEpisode = tmdbEpisode,
                title = data.get("title").textOrNull(),
                imdbId = resolvedImdb?.takeIf { IMDB_ID_REGEX.matches(it) },
                tvdbSeason = data.get("tvdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 },
                imdbSeason = data.get("imdb")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 },
                traktSeason = data.get("trakt")?.get("seasonNumber").intOrNull()?.takeIf { it > 0 },
                tmdbFromEpisode = first,
                tmdbRanges = tmdbRanges,
                tvdbFromEpisode = data.get("tvdb")?.get("fromEpisode").intOrNull()
                    ?.takeIf { it > 0 },
                tvdbRanges = tvdbRanges,
                tvdbAbsoluteOrder = data.get("tvdb")?.get("absoluteOrder")
                    ?.takeUnless { it.isNull }?.asBoolean(),
                alternateImdbId = data.get("imdb")?.get("id").textOrNull()
                    ?.takeIf { IMDB_ID_REGEX.matches(it) },
                alternateImdbFromEpisode = data.get("imdb")?.get("fromEpisode").intOrNull()
                    ?.takeIf { it > 0 }
            )
            }
            val mapping = outcome.getOrNull()
            if (mapping == null) android.util.Log.i("MetaEpisodeMap",
                "phase=${if (acceptedResponse) "AIO_ACCEPT_UNUSABLE" else "AIO_RETRY"} season=$season host=$host reason=${if (acceptedResponse) "ID_OR_MAPPING_UNUSABLE" else if (outcome.isFailure) "HTTP_OR_PARSE_ERROR" else "INVALID_RESPONSE"} elapsedMs=${SystemClock.elapsedRealtime() - requestStarted}")
            if (traceId != null) traceSource(traceId, "AIO_HOST_RESULT",
                "host=$host accepted=$acceptedResponse mapped=${mapping != null}" +
                " season=$season sourceTmdbS=${mapping?.tmdbSeason}" +
                " ranges=${mapping?.tmdbRanges?.size ?: 0}" +
                " elapsedMs=${SystemClock.elapsedRealtime() - requestStarted}" +
                " error=${outcome.exceptionOrNull()?.javaClass?.simpleName}")
            if (acceptedResponse && mapping == null) return null
            if (mapping != null) {
                android.util.Log.i("MetaEpisodeMap", "phase=AIO_ACCEPT season=$season host=$host ranges=${mapping.tmdbRanges.size} kitsu=${mapping.kitsuId} elapsedMs=${SystemClock.elapsedRealtime() - requestStarted}")
                animeMappingCache[query] = mapping to System.currentTimeMillis()
                MetadataDiskCache.write("anime", query, mapping.toJson())
                return mapping
            }
        }
        return null
    }


    /** Resolve ambiguous IMDb seasons only after the regular TVDB fallback fails. */
    private suspend fun fetchKitsuFranchiseMeta(kitsuId: String): JsonNode? =
        runSuspendCatching {
            parseJson<JsonNode>(fetchSharedAnimeText(
                "https://anime-kitsu.strem.fun/meta/series/kitsu:$kitsuId.json"
            )).get("meta")
        }.getOrNull()?.takeIf {
            it.get("kitsu_id").textOrNull() == kitsuId ||
                it.get("id").textOrNull() == "kitsu:$kitsuId"
        }

    private suspend fun resolveKitsuFranchiseFallback(
        seedKitsuId: String?, tvdbId: Int?, seedTvdbSeason: Int?,
        season: Int, episode: Int, traceId: String
    ): AnimeEpisodeRoute? {
        val seed = seedKitsuId?.toIntOrNull()?.takeIf { it > 0 }?.toString() ?: return null
        val expectedTvdb = tvdbId?.takeIf { it > 0 } ?: return null
        val preferred = when {
            seedTvdbSeason != null && season > seedTvdbSeason -> "Sequel"
            seedTvdbSeason != null && season < seedTvdbSeason -> "Prequel"
            else -> null
        }
        val visited = mutableSetOf(seed)
        var frontier = listOf(seed)
        traceSource(traceId, "KITSU_FRANCHISE_START",
            "seed=$seed tvdb=$expectedTvdb target=S$season:E$episode preferred=$preferred")

        // Traverse both relation directions; use priority only for lookup order.
        // At each depth, never select a candidate before evaluating its siblings.
        for (depth in 0..3) {
            val links = mutableListOf<Pair<String, String>>()
            val matches = mutableListOf<AnimeEpisodeRoute>()
            for (id in frontier) {
                val meta = fetchKitsuFranchiseMeta(id) ?: continue
                if (depth > 0) {
                    val lookup = "kitsu:$id"
                    val mapping = fetchAnimeSeasonMapping(
                        LoadData(id = lookup, originalAnimeId = lookup, kitsuId = id, isAnime = true),
                        traceId
                    )
                    val local = mapping?.takeIf {
                        it.kitsuId == id && it.tvdbId == expectedTvdb &&
                            it.tvdbSeason == season && it.tvdbAbsoluteOrder == false
                    }?.let { info ->
                        val first = info.tvdbFromEpisode ?: 1
                        (episode - first + 1).takeIf { episode >= first && it > 0 }
                    }
                    val video = local?.let { number ->
                        meta.get("videos")?.takeIf { it.isArray }?.filter { item ->
                            (item.get("episode").intOrNull() ?: item.get("number").intOrNull()) == number &&
                                (item.get("season").intOrNull() ?: 1) > 0 &&
                                item.get("id").textOrNull() == "kitsu:$id:$number"
                        }?.singleOrNull()
                    }
                    traceSource(traceId, "KITSU_FRANCHISE_CHECK",
                        "depth=$depth kitsu=$id tvdb=${mapping?.tvdbId}:" +
                        "S${mapping?.tvdbSeason} localE=$local episodeMatch=${video != null}")
                    if (video != null) matches.add(AnimeEpisodeRoute(
                        mapping, id, local, video.get("absoluteEpisode").intOrNull()?.takeIf { it > 0 },
                        "KITSU_FRANCHISE"
                    ))
                }
                if (depth == 3) continue
                meta.get("links")?.takeIf { it.isArray }?.forEach { link ->
                    if (link.get("category").textOrNull()?.equals("Franchise", true) != true)
                        return@forEach
                    val relation = when {
                        link.get("name").textOrNull()?.startsWith("Sequel:", true) == true -> "Sequel"
                        link.get("name").textOrNull()?.startsWith("Prequel:", true) == true -> "Prequel"
                        else -> return@forEach
                    }
                    val nextId = link.get("url").textOrNull()
                        ?.takeIf { it.startsWith("stremio:///detail/series/kitsu:") }
                        ?.removePrefix("stremio:///detail/series/kitsu:")
                        ?.toIntOrNull()?.takeIf { it > 0 }?.toString() ?: return@forEach
                    links.add(nextId to relation)
                }
            }
            if (matches.size > 1) {
                traceSource(traceId, "KITSU_FRANCHISE_RESULT",
                    "status=AMBIGUOUS depth=$depth candidates=${matches.size}")
                return null
            }
            if (matches.size == 1) {
                val found = matches.single()
                traceSource(traceId, "KITSU_FRANCHISE_RESULT",
                    "status=MATCH depth=$depth kitsu=${found.kitsuId} localE=${found.episode}")
                return found
            }
            if (depth == 3) break
            val next = mutableListOf<String>()
            val ordered = if (preferred == null) links else
                links.sortedBy { if (it.second == preferred) 0 else 1 }
            for ((id, _) in ordered) {
                if (id in visited) continue
                if (visited.size > 12) {
                    traceSource(traceId, "KITSU_FRANCHISE_RESULT",
                        "status=LIMIT depth=$depth visited=${visited.size}")
                    return null
                }
                visited.add(id)
                next.add(id)
            }
            if (next.isEmpty()) break
            frontier = next
        }
        traceSource(traceId, "KITSU_FRANCHISE_RESULT",
            "status=MISS visited=${visited.size}")
        return null
    }

    private suspend fun resolveAnimeEpisodeRoute(loadData: LoadData, traceId: String): AnimeEpisodeRoute? {
        if (!(loadData.isAnime || loadData.originalAnimeId != null || loadData.anilistId != null ||
                loadData.malId != null || loadData.kitsuId != null || isLibraryAnimeId(loadData.id) ||
                (loadData.isCartoon && loadData.isAsian)) || loadData.episode == null) return null
        val origin = loadData.originalAnimeId ?: loadData.id.orEmpty()
        val source = origin.substringBefore(':').takeIf { it in setOf("kitsu", "mal", "anilist") }
        val sourceId = source?.let { origin.substringAfter(':').substringBefore(':').toIntOrNull() }
            ?.takeIf { it > 0 }
        val originalImdb = isOriginalImdbEpisode(loadData)
        val season = (if (originalImdb) originalImdbSeason(loadData) else
            loadData.imdbSeason ?: loadData.season)?.takeIf { it >= 0 }
        val episode = (if (originalImdb) loadData.episode else
            loadData.imdbEpisode ?: loadData.episode)?.takeIf { it > 0 }
        val imdb = listOf(loadData.id, loadData.imdbId)
            .firstNotNullOfOrNull { it?.substringBefore(':')?.takeIf { key -> IMDB_ID_REGEX.matches(key) } }
        traceSource(traceId, "ANIME_ID_INPUT",
            "origin=$origin imdb=$imdb app=S${loadData.season}:E${loadData.episode}" +
            " imdbInput=S${loadData.imdbSeason}:E${loadData.imdbEpisode}" +
            " lookup=S$season:E$episode source=$source:$sourceId")
        // The input MAL/Kitsu/AniList identity already uses its title-local episode.
        if (source != null && sourceId != null) {
            val id = "$source:$sourceId"
            val mapped = fetchAnimeSeasonMapping(loadData.copy(
                id = id, originalAnimeId = id, imdbId = null, isAnime = true), traceId)
            val valid = when (source) {
                "kitsu" -> mapped?.kitsuId == sourceId.toString()
                "mal" -> mapped?.malId == sourceId
                else -> mapped?.aniListId == sourceId
            }
            val route = AnimeEpisodeRoute(if (valid) mapped else null,
                if (source == "kitsu") sourceId.toString() else if (valid) mapped?.kitsuId else null,
                loadData.episode.takeIf { it > 0 }, null, "ORIGINAL_$source")
            traceSource(traceId, "ANIME_ID_RESULT",
                "via=${route.via} valid=$valid kitsu=${route.kitsuId}" +
                " mal=${route.mapping?.malId} al=${route.mapping?.aniListId}" +
                " localE=${route.episode} absolute=null")
            return route
        }
        var found: Triple<String, Int, Int?>? = null
        if (imdb != null && season != null && episode != null) {
            val url = "https://anime-kitsu.strem.fun/meta/series/$imdb.json"
            traceSource(traceId, "KITSU_IMDB_LOOKUP", "url=$url input=S$season:E$episode")
            val fetched = runSuspendCatching {
                val videos = parseJson<JsonNode>(fetchSharedAnimeText(url)).get("meta")?.get("videos")
                if (videos == null || !videos.isArray) emptyList<Triple<String, Int, Int?>>()
                else videos.mapNotNull { row ->
                    val s = row.get("season").intOrNull()
                    val e = row.get("episode").intOrNull() ?: row.get("number").intOrNull()
                    if (s != season || e != episode) null else {
                        val id = row.get("kitsu_id")?.asText()?.takeIf {
                            it.isNotBlank() && it.all(Char::isDigit)
                        }
                        val n = row.get("kitsuEpisode").intOrNull()?.takeIf { it > 0 }
                        if (id != null && n != null)
                            Triple(id, n, row.get("absoluteEpisode").intOrNull()?.takeIf { it > 0 })
                        else null
                    }
                }.distinct()
            }
            val matches = fetched.getOrNull().orEmpty()
            found = matches.singleOrNull()
            traceSource(traceId, "KITSU_IMDB_RESULT",
                "imdb=$imdb input=S$season:E$episode count=${matches.size}" +
                " kitsu=${found?.first} kitsuE=${found?.second} absolute=${found?.third}" +
                " error=${fetched.exceptionOrNull()?.javaClass?.simpleName}" +
                " status=${if (matches.size > 1) "AMBIGUOUS" else if (matches.isEmpty()) "MISS" else "EXACT"}")
        }
        if (found != null) {
            val kitsu = found.first
            val local = found.second
            val lookup = "kitsu:$kitsu"
            val mapped = fetchAnimeSeasonMapping(LoadData(id = lookup, originalAnimeId = lookup,
                kitsuId = kitsu, season = 1, episode = local, isAnime = true), traceId)
            val valid = mapped?.kitsuId == kitsu
            if (season == 0 && !valid) {
                traceSource(traceId, "ANIME_ID_RESULT",
                    "via=SPECIAL_UNVERIFIED reason=KITSU_AIO_MISMATCH" +
                    " imdb=$imdb S0:E$episode kitsu=$kitsu localE=$local" +
                    " resolvedKitsu=${mapped?.kitsuId}")
                return AnimeEpisodeRoute(null, null, null, null, "SPECIAL_UNVERIFIED")
            }
            val route = AnimeEpisodeRoute(if (valid) mapped else null, kitsu, local,
                found.third, "KITSU_IMDB")
            traceSource(traceId, "ANIME_ID_RESULT",
                "via=KITSU_IMDB valid=$valid sourceImdb=$imdb mappedImdb=${mapped?.imdbId}" +
                " kitsu=$kitsu mal=${route.mapping?.malId} al=${route.mapping?.aniListId}" +
                " localE=$local absolute=${route.absolute}" +
                " tmdb=${route.mapping?.tmdbId}:${route.mapping?.tmdbSeason}:${route.mapping?.tmdbEpisode}")
            return route
        }
        if (originalImdb && season == 0) {
            traceSource(traceId, "ANIME_ID_RESULT",
                "via=SPECIAL_UNVERIFIED reason=NO_EXACT_KITSU imdb=$imdb S0:E$episode")
            return AnimeEpisodeRoute(null, null, null, null, "SPECIAL_UNVERIFIED")
        }
        val mapped = fetchAnimeSeasonMapping(loadData, traceId)
        val converted = if (season != null && episode != null) mapped?.tvdbRanges
            ?.mapNotNull { range ->
                val absolute = episode.toLong() - range.offset.toLong()
                if (range.season == season && absolute >= range.start.toLong() &&
                    (range.end == null || absolute <= range.end.toLong()) &&
                    absolute in 1L..Int.MAX_VALUE.toLong())
                    range to absolute.toInt() else null
            }?.singleOrNull() else null
        val local = converted?.let { (_, absolute) ->
            val first = mapped?.tvdbFromEpisode
            if (first != null) (episode!! - first + 1).takeIf { it > 0 } else absolute
        }
        // The IMDb lookup can select the wrong cour, or return no indexed season.
        // Resolve that ambiguity by asking AIO for the same TVDB S/E, never by
        // translating IMDb S17 to an assumed AIO season number.
        if (local == null && imdb != null && season != null && episode != null) {
            val fallbackMapping = if (mapped?.tvdbId != null) mapped else fetchAnimeSeasonMapping(
                loadData.copy(season = null, episode = null, imdbSeason = null, imdbEpisode = null),
                traceId
            )
            val tvdbId = mapped?.tvdbId ?: fallbackMapping?.tvdbId
            traceSource(traceId, "AIO_TVDB_FALLBACK",
                "imdb=$imdb input=S$season:E$episode tvdbId=$tvdbId" +
                " reason=${if (tvdbId == null) "NO_TVDB_ID" else "NO_VERIFIED_IMDB_EPISODE"}")
            if (tvdbId != null) {
                val tvdb = fetchAnimeSeasonMapping(
                    loadData.copy(imdbSeason = season, imdbEpisode = episode),
                    traceId, tvdbLookupId = tvdbId
                )
                // Local episode numbering is safe only with explicit non-absolute TVDB order.
                val tvdbEpisode = if (tvdb?.tvdbSeason == season &&
                    tvdb.tvdbId == tvdbId && tvdb.tvdbAbsoluteOrder == false) {
                    (episode - (tvdb.tvdbFromEpisode ?: 1) + 1).takeIf { it > 0 }
                } else null
                traceSource(traceId, "AIO_TVDB_VERIFY",
                    "requested=$tvdbId:S$season:E$episode" +
                    " result=${tvdb?.tvdbId}:S${tvdb?.tvdbSeason}" +
                    " absoluteOrder=${tvdb?.tvdbAbsoluteOrder}" +
                    " fromEpisode=${tvdb?.tvdbFromEpisode}" +
                    " kitsu=${tvdb?.kitsuId} mal=${tvdb?.malId} al=${tvdb?.aniListId}" +
                    " localE=$tvdbEpisode" +
                    " status=${if (tvdbEpisode != null && tvdb?.kitsuId != null) "MATCH" else "UNVERIFIED"}")
                val kitsuId = tvdb?.kitsuId
                if (tvdbEpisode != null && kitsuId != null) {
                    val lookup = "kitsu:$kitsuId"
                    val fromKitsu = fetchAnimeSeasonMapping(LoadData(
                        id = lookup, originalAnimeId = lookup, kitsuId = kitsuId,
                        season = 1, episode = tvdbEpisode, isAnime = true
                    ), traceId)
                    val matches = fromKitsu?.kitsuId == kitsuId &&
                        fromKitsu.tvdbId == tvdbId &&
                        fromKitsu.tvdbSeason == season &&
                        (fromKitsu.aniListId == null || tvdb.aniListId == null ||
                            fromKitsu.aniListId == tvdb.aniListId) &&
                        (fromKitsu.malId == null || tvdb.malId == null ||
                            fromKitsu.malId == tvdb.malId)
                    val selected = if (matches) fromKitsu!! else tvdb
                    traceSource(traceId, "AIO_KITSU_RECHECK",
                        "kitsu=$kitsuId sourceTvdb=$tvdbId:S$season:E$episode" +
                        " matched=$matches alternateImdb=${fromKitsu?.alternateImdbId}" +
                        " altSeason=${fromKitsu?.imdbSeason}" +
                        " altFromEpisode=${fromKitsu?.alternateImdbFromEpisode}" +
                        " selected=${if (matches) "KITSU" else "TVDB"}")
                    val route = AnimeEpisodeRoute(selected, kitsuId, tvdbEpisode, null, "AIO_TVDB_LOOKUP")
                    traceSource(traceId, "ANIME_ID_RESULT",
                        "via=${route.via} originalImdb=$imdb:S$season:E$episode" +
                        " kitsu=$kitsuId mal=${selected.malId} al=${selected.aniListId}" +
                        " localE=$tvdbEpisode altImdb=${selected.alternateImdbId}" +
                        " altSeason=${selected.imdbSeason}")
                    return route
                }
            }
            val franchise = withTimeoutOrNull(15000L) {
                resolveKitsuFranchiseFallback(
                    mapped?.kitsuId ?: fallbackMapping?.kitsuId, tvdbId,
                    mapped?.tvdbSeason ?: fallbackMapping?.tvdbSeason, season, episode, traceId
                )
            }
            if (franchise != null) {
                traceSource(traceId, "ANIME_ID_RESULT",
                    "via=KITSU_FRANCHISE originalImdb=$imdb:S$season:E$episode" +
                    " kitsu=${franchise.kitsuId} localE=${franchise.episode}")
                return franchise
            }
        }
        val verified = local != null
        val route = AnimeEpisodeRoute(if (verified) mapped else null,
            if (verified) mapped?.kitsuId else null,
            local, converted?.second, if (verified) "AIO_TVDB" else "UNVERIFIED")
        traceSource(traceId, "ANIME_ID_RESULT",
            "via=${route.via} imdb=$imdb S$season E$episode range=${converted?.first}" +
            " kitsu=${route.kitsuId} mal=${if (local != null) mapped?.malId else null}" +
            " al=${if (local != null) mapped?.aniListId else null}" +
            " localE=${route.episode} absolute=${route.absolute}" +
            " status=${if (local == null) "SKIP_UNVERIFIED" else "VERIFIED"}")
        return route
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
        val hasSeasonMapping: Boolean,
        val originalImdbEpisode: Boolean,
        val animeEpisode: Int? = null,
        val animeVia: String? = null
    )

    private fun integrationData(
        loadData: LoadData,
        targetType: String,
        globalId: String?,
        globalSeason: Int?,
        globalEpisode: Int?,
        mapping: AnimeSeasonMapping? = null,
        route: AnimeEpisodeRoute? = null
    ): IntegrationData {
        val originalImdbEpisode = isOriginalImdbEpisode(loadData)
        val candidates = if (originalImdbEpisode)
            listOf(loadData.id, loadData.imdbId, globalId, mapping?.imdbId)
        else listOf(mapping?.imdbId, loadData.imdbId, globalId, loadData.id)
        val imdbId = candidates
            .firstNotNullOfOrNull { candidate ->
                candidate?.substringBefore(':')?.takeIf { IMDB_ID_REGEX.matches(it) }
            }
        val tmdbId = mapping?.tmdbId ?: loadData.tmdbId?.takeIf { it > 0 }
            ?: loadData.id?.takeIf { it.startsWith("tmdb:") }
                ?.removePrefix("tmdb:")?.substringBefore(':')?.toIntOrNull()?.takeIf { it > 0 }
        val kitsuId = (if (route != null) route.kitsuId?.takeIf { route.episode != null }
            else mapping?.kitsuId ?: loadData.kitsuId
                ?: loadData.originalAnimeId?.takeIf { it.startsWith("kitsu:") }
                ?: loadData.id?.takeIf { it.startsWith("kitsu:") }?.removePrefix("kitsu:"))
            ?.removePrefix("kitsu:")?.substringBefore(':')
            ?.takeIf { it.isNotBlank() && it.all { digit -> digit.isDigit() } }
        val isAnime = loadData.isAnime ||
            isLibraryAnimeId(loadData.id) || isLibraryAnimeId(loadData.originalAnimeId) ||
            kitsuId != null || loadData.anilistId != null || loadData.malId != null ||
            (loadData.isCartoon && loadData.isAsian)
        val isMovie = targetType == "movie" ||
            (loadData.season == null && loadData.episode == null)
        val mappedImdbSeason = mapping?.takeUnless { originalImdbEpisode }
            ?.takeIf { it.imdbId != null && (loadData.episode ?: 0) > 0 }
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
            season = if (isMovie) null else if (route?.episode != null) 1
                else if (originalImdbEpisode) originalImdbSeason(loadData)
                else if (mapping != null) 1 else loadData.season?.takeIf { it >= 0 } ?: globalSeason,
            episode = if (isMovie) null else route?.episode
                ?: loadData.episode?.takeIf { it > 0 } ?: globalEpisode,
            imdbSeason = if (isMovie) null else if (originalImdbEpisode) originalImdbSeason(loadData)
                else mappedImdbSeason
                    ?: if (loadData.originalAnimeId != null) null else loadData.imdbSeason?.takeIf { it >= 0 } ?: globalSeason,
            imdbEpisode = if (isMovie) null else if (originalImdbEpisode) loadData.episode
                else if (mappedImdbSeason != null) loadData.episode
                else if (loadData.originalAnimeId != null) null else loadData.imdbEpisode?.takeIf { it > 0 } ?: globalEpisode,
            year = loadData.year,
            airedYear = loadData.airedYear,
            malId = if (route != null) {
                if (route.episode != null) route.mapping?.malId
                    ?: (loadData.originalAnimeId ?: loadData.id)?.takeIf { it.startsWith("mal:") }
                        ?.substringAfter(':')?.substringBefore(':')?.toIntOrNull()
                else null
            } else if (mapping != null) mapping.malId
                else if (loadData.originalAnimeId != null || (loadData.season ?: 1) > 1) null else loadData.malId,
            aniListId = if (route != null) {
                if (route.episode != null) route.mapping?.aniListId
                    ?: (loadData.originalAnimeId ?: loadData.id)?.takeIf { it.startsWith("anilist:") }
                        ?.substringAfter(':')?.substringBefore(':')?.toIntOrNull()
                else null
            } else if (mapping != null) mapping.aniListId
                else if (loadData.originalAnimeId != null || (loadData.season ?: 1) > 1) null else loadData.anilistId,
            tmdbSeason = mapping?.tmdbSeason,
            tmdbEpisode = mapping?.tmdbEpisode,
            hasSeasonMapping = mapping != null,
            originalImdbEpisode = originalImdbEpisode,
            animeEpisode = route?.episode,
            animeVia = route?.via
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
        val tmdbRoute = !animeRoute && !standardImdbRoute &&
            media.tmdbId != null && media.tmdbSeason != null && media.tmdbEpisode != null
        val requestImdbId = if (tmdbRoute) null else media.imdbId
        val requestTmdbId = if (standardImdbRoute) null else media.tmdbId
        val originalYear = if (media.isAnime && (media.hasSeasonMapping || (media.season ?: 0) > 1))
            media.airedYear ?: media.year else media.year
        return mapOf(
            "title" to title,
            "id" to (requestImdbId ?: ""),
            "tmdbId" to requestTmdbId,
            "tvtype" to targetType,
            "year" to originalYear?.toString(),
            "season" to (if (tmdbRoute) media.tmdbSeason
                else if (standardImdbRoute) media.imdbSeason else media.season),
            "episode" to (if (tmdbRoute) media.tmdbEpisode
                else if (standardImdbRoute) media.imdbEpisode else media.episode),
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

        val tmdbRoute = generalAnimeRoute && !imdbOnlyRoute &&
            media.tmdbId != null && media.tmdbSeason != null && media.tmdbEpisode != null
        val season = if (imdbOnlyRoute) media.imdbSeason
            else if (tmdbRoute) media.tmdbSeason else media.season
        val episode = if (imdbOnlyRoute) media.imdbEpisode
            else if (tmdbRoute) media.tmdbEpisode else media.episode
        // StreamPlay's one episode field is shared by IMDb and anime providers.
        val requestImdbId = if (imdbOnlyRoute) media.imdbId
            else if (generalAnimeRoute || media.animeEpisode != null) null else media.imdbId
        val year = if (media.isAnime && (media.hasSeasonMapping || (media.season ?: 0) > 1))
            media.airedYear ?: media.year else media.year
        return mapOf(
            "id" to if (imdbOnlyRoute || (!generalAnimeRoute && media.animeEpisode != null))
                null else media.tmdbId,
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
        val fromCineStream: Boolean,
        val cineInlineSubtitles: Boolean = false
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
                        IntegrationStremioAddon(parts[0].trim().ifBlank { url }, url, subtitleOnly, true,
                            parts[2].trim().uppercase() in setOf("HTTPS", "DEBRID"))
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
        callback: (ExtractorLink) -> Unit,
        traceId: String
    ) {
        if (targetType != "movie" && targetType != "series") {
            traceSource(traceId, "STREMIO_TMDB_FALLBACK_SKIP", "reason=TYPE type=$targetType")
            return
        }

        val media = integrationData(loadData, targetType, globalId, globalSeason, globalEpisode)
        if (!media.imdbId.isNullOrBlank()) {
            traceSource(traceId, "STREMIO_TMDB_FALLBACK_SKIP",
                "reason=IMDB_PRESENT id=${media.imdbId} input=S${loadData.season}:E${loadData.episode}")
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
        traceSource(traceId, "STREMIO_TMDB_FALLBACK_ROUTE",
            "type=$targetType id=$stremioId source=S${loadData.season}:E${loadData.episode}")
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

                        val startedAt = SystemClock.elapsedRealtime()
                        traceSource(traceId, "STREMIO_TMDB_ADDON_REQUEST",
                            "source=${addon.name} category=${if (addon.subtitleOnly) "subtitle" else "stream"}" +
                            " type=$targetType id=$stremioId")
                        if (addon.subtitleOnly) {
                            val requestUrl = "$cleanBaseUrl/subtitles/$targetType/${encodedId}.json$querySuffix"
                            val text = customSession.get(requestUrl, timeout = 15L).text
                            val res = if (text.isNotBlank()) parseJson<SubtitlesResponse>(text) else null
                            res?.subtitles?.forEachIndexed { position, sub ->
                                sub.toCloudStreamSubtitle(addon.name)?.let { subFile ->
                                    stremioSubtitleCallback(subFile, addon.url, if (addon.fromCineStream) 1 else 2)
                                }
                            }
                            traceSource(traceId, "STREMIO_TMDB_ADDON_RESPONSE",
                                "source=${addon.name} type=$targetType id=$stremioId" +
                                " subtitles=${res?.subtitles?.size ?: 0}" +
                                " elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                        } else {
                            val requestUrl = "$cleanBaseUrl/stream/$targetType/${encodedId}.json$querySuffix"
                            val response = customSession.get(requestUrl, timeout = 30L)
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
                            traceSource(traceId, "STREMIO_TMDB_ADDON_RESPONSE",
                                "source=${addon.name} type=$targetType id=$stremioId" +
                                " streams=${res?.streams?.size ?: 0}" +
                                " elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        traceSource(traceId, "STREMIO_TMDB_ADDON_ERROR",
                            "source=${addon.name} type=$targetType id=$stremioId" +
                            " error=${error.javaClass.simpleName}")
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
        mappingDeferred: Deferred<AnimeSeasonMapping?>?,
        animeRouteDeferred: Deferred<AnimeEpisodeRoute?>,
        inlineSubtitleLoader: suspend (Map<String, Any?>) -> Unit,
        traceId: String
    ) {
        if (!isIntegrationEnabled("cinestream_integration_enabled")) {
            traceSource(traceId, "CINE_SKIP", "reason=DISABLED")
            return
        }
        if (targetType != "movie" && targetType != "series") {
            traceSource(traceId, "CINE_SKIP", "reason=TYPE type=$targetType")
            return
        }

        try {
            val cineStreamProvider = getCineStreamProvider() ?: run {
                traceSource(traceId, "CINE_SKIP", "reason=PROVIDER_MISSING")
                return
            }
            val media = integrationData(loadData, targetType, globalId, globalSeason, globalEpisode,
                mappingDeferred?.await(), animeRouteDeferred.await())
            val originalSpecial = media.originalImdbEpisode && media.imdbSeason == 0
            traceSource(traceId, "CINE_MEDIA",
                "input=S${loadData.season}:E${loadData.episode}" +
                " imdbInput=S${loadData.imdbSeason}:E${loadData.imdbEpisode}" +
                " global=S$globalSeason:E$globalEpisode" +
                " mapped=${media.hasSeasonMapping}" +
                " resolved=S${media.season}:E${media.episode}" +
                " imdb=S${media.imdbSeason}:E${media.imdbEpisode}" +
                " tmdb=S${media.tmdbSeason}:E${media.tmdbEpisode}" +
                " imdbId=${media.imdbId} tmdbId=${media.tmdbId}" +
                " mal=${media.malId} anilist=${media.aniListId} kitsu=${media.kitsuId}" +
                " animeE=${media.animeEpisode} via=${media.animeVia}")
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
                    val payload = cinePayload(loadData, targetType, media, animeRoute,
                        japaneseTitle, standardImdbRoute)
                    val route = if (animeRoute) "ANIME" else if (standardImdbRoute) "IMDB" else "STANDARD"
                    val startedAt = SystemClock.elapsedRealtime()
                    traceSource(traceId, "CINE_REQUEST",
                        "route=$route japaneseTitle=$japaneseTitle ${tracePayload(payload)}")
                    supervisorScope {
                        launch { inlineSubtitleLoader(payload) }
                        withIntegratedSource("cine") { cineStreamProvider.loadLinks(
                            payload.toJson(), isCasting, subtitleCallback, trackedCallback
                        ) }
                    }
                    traceSource(traceId, "CINE_DONE",
                        "route=$route japaneseTitle=$japaneseTitle" +
                        " totalLinks=${linksFound.get()} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                } catch (e: CancellationException) {
                    traceSource(traceId, "CINE_CANCEL",
                        "animeRoute=$animeRoute standardImdbRoute=$standardImdbRoute")
                    throw e
                } catch (e: Exception) {
                    traceSource(traceId, "CINE_ERROR",
                        "animeRoute=$animeRoute standardImdbRoute=$standardImdbRoute" +
                        " error=${e.javaClass.simpleName}")
                }
            }

            if (media.isAnime) {

                withTimeoutOrNull(60000L) {
                    supervisorScope {
                        val anime = if (media.hasSeasonMapping || (!originalSpecial && loadData.originalAnimeId == null && (media.season ?: 1) <= 1))
                            async(Dispatchers.IO) { callCine(animeRoute = true) } else null
                        val standard = if (media.hasSeasonMapping && media.tmdbId != null &&
                            media.tmdbSeason != null && media.tmdbEpisode != null ||
                            !media.hasSeasonMapping && !originalSpecial && loadData.originalAnimeId == null && (media.season ?: 1) <= 1)
                            async(Dispatchers.IO) { callCine(animeRoute = false) } else null
                        val mappedImdb = if (
                            media.imdbId != null && media.imdbSeason != null && media.imdbEpisode != null &&
                            (media.hasSeasonMapping || originalSpecial ||
                                (loadData.originalAnimeId == null && (media.season ?: 1) > 1) ||
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
                withTimeoutOrNull(60000L) { callCine(animeRoute = false) }
            }
        } catch (e: CancellationException) {
            traceSource(traceId, "CINE_CANCEL", "stage=INTEGRATION")
            throw e
        } catch (e: Exception) {
            traceSource(traceId, "CINE_ERROR",
                "stage=INTEGRATION error=${e.javaClass.simpleName}")
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
        mappingDeferred: Deferred<AnimeSeasonMapping?>?,
        animeRouteDeferred: Deferred<AnimeEpisodeRoute?>,
        traceId: String
    ) {
        if (!isIntegrationEnabled("streamplay_integration_enabled")) {
            traceSource(traceId, "STREAMPLAY_SKIP", "reason=DISABLED")
            return
        }
        if (targetType != "movie" && targetType != "series") {
            traceSource(traceId, "STREAMPLAY_SKIP", "reason=TYPE type=$targetType")
            return
        }

        try {
            val media = integrationData(loadData, targetType, globalId, globalSeason, globalEpisode,
                mappingDeferred?.await(), animeRouteDeferred.await())
            val originalSpecial = media.originalImdbEpisode && media.imdbSeason == 0
            val streamPlayProvider = getStreamPlayProvider()
            traceSource(traceId, "STREAMPLAY_PROVIDER",
                "general=${streamPlayProvider != null} isAnime=${media.isAnime}" +
                " hasMapping=${media.hasSeasonMapping} source=S${loadData.season}:E${loadData.episode}")
            traceSource(traceId, "STREAMPLAY_MEDIA",
                "input=S${loadData.season}:E${loadData.episode}" +
                " imdbInput=S${loadData.imdbSeason}:E${loadData.imdbEpisode}" +
                " global=S$globalSeason:E$globalEpisode mapped=${media.hasSeasonMapping}" +
                " resolved=S${media.season}:E${media.episode}" +
                " imdb=S${media.imdbSeason}:E${media.imdbEpisode}" +
                " tmdb=S${media.tmdbSeason}:E${media.tmdbEpisode}" +
                " imdbId=${media.imdbId} tmdbId=${media.tmdbId}" +
                " mal=${media.malId} anilist=${media.aniListId} kitsu=${media.kitsuId}" +
                " animeE=${media.animeEpisode} via=${media.animeVia}")
            suspend fun callStreamPlay(route: String, payload: Map<String, Any?>) {
                val provider = streamPlayProvider ?: return
                val startedAt = SystemClock.elapsedRealtime()
                traceSource(traceId, "STREAMPLAY_REQUEST",
                    "route=$route ${tracePayload(payload)}")
                try {
                    withIntegratedSource("streamplay") {
                        provider.loadLinks(payload.toJson(), isCasting, subtitleCallback, callback)
                    }
                    traceSource(traceId, "STREAMPLAY_DONE",
                        "route=$route elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                } catch (e: CancellationException) {
                    traceSource(traceId, "STREAMPLAY_CANCEL", "route=$route")
                    throw e
                } catch (e: Exception) {
                    traceSource(traceId, "STREAMPLAY_ERROR",
                        "route=$route error=${e.javaClass.simpleName}")
                    throw e
                }
            }
            if (!media.isAnime) {
                if (streamPlayProvider != null) {
                    withTimeoutOrNull(60000L) {
                        callStreamPlay("STANDARD", streamPlayPayload(loadData, targetType, media))
                    }
                }
                return
            }

            supervisorScope {

                val dedicatedAnime = getStreamPlayAnimeProvider()?.takeIf {
                    media.hasSeasonMapping || (!originalSpecial && loadData.originalAnimeId == null && (media.season ?: 1) <= 1)
                }?.let { animeProvider ->
                    async(Dispatchers.IO) {
                        try {
                            val animePayload = mapOf(
                                "imdbId" to media.imdbId,
                                "tmdbId" to media.tmdbId,
                                "type" to targetType,
                                "season" to (if (media.animeEpisode != null) 1 else media.season),
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
                            withTimeoutOrNull(60000L) {
                                val startedAt = SystemClock.elapsedRealtime()
                                traceSource(traceId, "STREAMPLAY_ANIME_REQUEST",
                                    "route=DEDICATED ${tracePayload(animePayload)}")
                                withIntegratedSource("anime") { animeProvider.loadLinks(animePayload.toJson(), isCasting, subtitleCallback, callback
                                ) }
                                traceSource(traceId, "STREAMPLAY_ANIME_DONE",
                                    "route=DEDICATED elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                            }
                        } catch (e: CancellationException) {
                            traceSource(traceId, "STREAMPLAY_ANIME_CANCEL", "route=DEDICATED")
                            throw e
                        } catch (e: Exception) {
                            traceSource(traceId, "STREAMPLAY_ANIME_ERROR",
                                "route=DEDICATED error=${e.javaClass.simpleName}")
                        }
                    }
                }
                if (streamPlayProvider != null) {
                    val anime = async(Dispatchers.IO) {
                        try {
                            if (media.animeEpisode != null ||
                                (!originalSpecial && loadData.originalAnimeId == null && (media.season ?: 1) <= 1)) withTimeoutOrNull(60000L) {
                                traceSource(traceId, "STREAMPLAY_AUTO_ID",
                                    "expectedAniList=${media.aniListId} expectedMal=${media.malId}" +
                                    " animeE=${media.animeEpisode ?: media.episode}" +
                                    " note=STREAMPLAY2_RE_RESOLVES_IDS")
                                callStreamPlay("ANIME", streamPlayPayload(loadData, targetType, media))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                        }
                    }
                    val general = if (media.hasSeasonMapping && media.tmdbSeason != null && media.tmdbEpisode != null && media.tmdbId != null ||
                        !media.hasSeasonMapping && !originalSpecial && loadData.originalAnimeId == null && (media.season ?: 1) <= 1 &&
                        (media.imdbId != null || media.tmdbId != null)) {
                        async(Dispatchers.IO) {
                            try {
                                withTimeoutOrNull(60000L) {
                                    callStreamPlay("TMDB", streamPlayPayload(
                                        loadData, targetType, media, generalAnimeRoute = true))
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                            }
                        }
                    } else null
                    val imdbGeneral = if (media.imdbId != null &&
                        media.imdbSeason != null && media.imdbEpisode != null &&
                        (media.hasSeasonMapping || originalSpecial ||
                            (loadData.originalAnimeId == null && (media.season ?: 1) > 1))) {
                        async(Dispatchers.IO) {
                            runSuspendCatching {
                                withTimeoutOrNull(60000L) {
                                    callStreamPlay("IMDB", streamPlayPayload(loadData, targetType, media,
                                        generalAnimeRoute = true, imdbOnlyRoute = true))
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
            traceSource(traceId, "STREAMPLAY_CANCEL", "stage=INTEGRATION")
            throw e
        } catch (e: Exception) {
            traceSource(traceId, "STREAMPLAY_ERROR",
                "stage=INTEGRATION error=${e.javaClass.simpleName}")
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
        @JsonProperty("watchProgressAllowed") val watchProgressAllowed: Boolean? = null,
        @JsonProperty("watchProgressPreferKitsu") val watchProgressPreferKitsu: Boolean? = null,
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

        fun homeGenre(): String? = if (requiresExtra("genre")) {
            val options = browseGenres()
            options.firstOrNull { it.equals("None", ignoreCase = true) }
                ?: options.firstOrNull { it.equals("All", ignoreCase = true) }
        } else null

        fun supportsHome(): Boolean {
            val required = extra.orEmpty().filter { it.isRequired == true }.mapNotNull { it.name } +
                extraRequired.orEmpty()
            return !id.isNullOrBlank() && required.all { it == "genre" || it == "skip" } &&
                (!requiresExtra("genre") || homeGenre() != null)
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
    private data class RelationSuggestion(val type: String, val id: String, val name: String, val url: String, val kind: String)

    // Preserve scoring inputs across the 150ms initial wait and asynchronous refresh.
    private data class RecommendationCandidate(
        val type: String, val id: String, val tmdbId: Int?, var name: String,
        var poster: String?, var fallbackPoster: String? = null,
        var collectionDate: String? = null, var collectionIndex: Int? = null,
        var tmdbRank: Int? = null, var simklRank: Int? = null,
        var sameOrigin: Boolean = false
    ) {
        fun score(): Double =
            (if (simklRank != null) 2.0 else 0.0) +
            (if (tmdbRank != null) 1.7 else 0.0) +
            (if (sameOrigin) 0.5 else 0.0) +
            (simklRank?.let { (30 - it.coerceAtMost(30)) / 300.0 } ?: 0.0) +
            (tmdbRank?.let { (30 - it.coerceAtMost(30)) / 300.0 } ?: 0.0)
    }

    private data class FetchedTmdbData(
        val fetchedTitle: String?, val fetchedOverview: String?, val fetchedLogo: String?,
        val fetchedTrailers: List<TrailerChoice>, val fetchedRecommendations: List<RecommendationCandidate>?,
        val fetchedRuntime: Int?, val fetchedAgeRating: String?,
        val fetchedYear: Int?, val fetchedStatus: ShowStatus?, val episodeTmdbMeta: Map<String, TmdbEpisode>,
        val fetchedOriginCountry: List<String>?,
        val fetchedOriginalTitle: String?,
        val fetchedEnglishTitle: String?,
        val fetchedVoteAverage: Double? = null,
        val fetchedLogoFallback: Boolean = false,
        val fetchedGenreIds: List<Int> = emptyList(),
        val fetchedBackdrop: String? = null,
        val fetchedPoster: String? = null,
        val fetchedGenres: List<String> = emptyList()
    )

    private data class ProviderArtInfo(
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("originalLanguage") val originalLanguage: String? = null
    )

    private data class AioPerson(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("photo") val photo: String? = null,
        @JsonProperty("tmdbId") val tmdbId: Int? = null
    )

    private data class AioCredits(
        @JsonProperty("cast") val cast: List<AioPerson>? = null,
        @JsonProperty("directors") val directors: List<AioPerson>? = null,
        @JsonProperty("writers") val writers: List<AioPerson>? = null,
        @JsonProperty("certificationLocal") val certificationLocal: String? = null,
        @JsonProperty("certifications") val certifications: Map<String, String>? = null
    )

    private data class AioActorRow(val data: ActorData, val id: Int?, val crew: Boolean)

    private data class CatalogEntry(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("id") val id: String? = null, 
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("moviedb_id") val moviedb_id: Int? = null,
        @JsonProperty("_tmdbId") val tmdbIdExt: String? = null, 
        @JsonProperty("_malId") val malIdExt: String? = null,
        @JsonProperty("_kitsuId") val kitsuIdExt: String? = null,
        @JsonProperty("poster") val poster: String? = null, 
        @JsonProperty("background") val background: String? = null,
        @JsonProperty("backdrop") val backdrop: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("logoUrl") val logoUrl: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("overview") val overview: String? = null, 
        @JsonProperty("runtime") val runtime: String? = null,
        @JsonProperty("imdbRating") val imdbRating: String? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("contentRating") val contentRating: String? = null,
        @JsonProperty("certification") val certification: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("original_language") val original_language: String? = null,
        @JsonProperty("_providerArt") val providerArt: ProviderArtInfo? = null,
        @JsonProperty("videos") val videos: List<Video>? = null, 
        @JsonProperty("genre") val genre: List<String>? = null, 
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("cast") val cast: List<String>? = null, 
        @JsonProperty("app_extras") val appExtras: AioCredits? = null,
        @JsonProperty("trailers") val trailersSources: List<Trailer>? = null,
        @JsonProperty("trailerStreams") val trailerStreams: List<TrailerStream>? = null, 
        @JsonProperty("links") val links: List<Link>? = null
    ) {

        private fun aioActorRows(credits: AioCredits?): List<AioActorRow> {
            if (credits == null) return emptyList()
            val crew = linkedMapOf<String, MutableList<Pair<String, AioPerson>>>()
            fun collect(job: String, people: List<AioPerson>?) {
                people.orEmpty().forEach { person ->
                    val name = person.name?.takeIf { it.isNotBlank() } ?: return@forEach
                    val key = person.tmdbId?.takeIf { it > 0 }?.let { "id:$it" }
                        ?: "name:${name.trim().lowercase()}"
                    crew.getOrPut(key) { mutableListOf() }.add(job to person)
                }
            }
            collect("Director", credits.directors)
            collect("Writer", credits.writers)
            val crewRows = crew.values.mapNotNull { jobs ->
                val person = jobs.first().second
                val name = person.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val roles = jobs.map { it.first }.distinct()
                    .sortedBy { if (it == "Director") 0 else 1 }.joinToString(", ")
                val photo = jobs.firstNotNullOfOrNull { it.second.photo?.takeIf(String::isNotBlank) }
                    ?: noActorImage
                AioActorRow(ActorData(Actor(name, photo), roleString = roles),
                    person.tmdbId?.takeIf { it > 0 }, true)
            }.sortedBy { row ->
                when (row.data.roleString) {
                    "Director, Writer" -> 1
                    "Director" -> 2
                    else -> 3
                }
            }
            val castRows = credits.cast.orEmpty().mapNotNull { person ->
                val name = person.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AioActorRow(ActorData(Actor(name,
                    person.photo?.takeIf(String::isNotBlank) ?: noActorImage),
                    roleString = person.character),
                    person.tmdbId?.takeIf { it > 0 }, false)
            }
            return crewRows + castRows
        }

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

            val baseName = name?.takeIf { it.isNotBlank() }
                ?: title?.takeIf { it.isNotBlank() } ?: (id ?: "")

            val finalName = baseName

            return provider.newMovieSearchResponse(finalName, metaUrl, TvType.Others) { 
                posterUrl = poster 
            }
        }

        private fun relatedTitles(provider: StremioC, detail: CatalogEntry): List<RelationSuggestion> =
            (detail.links.orEmpty() + links.orEmpty()).mapNotNull { link ->
                if (!link.category.equals("prequel", true) && !link.category.equals("sequel", true))
                    return@mapNotNull null
                val target = runCatching { android.net.Uri.parse(link.url ?: "") }.getOrNull()
                    ?: return@mapNotNull null
                val parts = target.pathSegments
                if (target.scheme != "stremio" || !target.host.isNullOrEmpty() || parts.size != 3 ||
                    parts[0] != "detail" || parts[1] !in listOf("movie", "series"))
                    return@mapNotNull null
                val id = parts[2]
                if (id.substringBefore(':').lowercase() !in setOf("kitsu", "mal") ||
                    id.substringAfter(':', "").toIntOrNull()?.let { it > 0 } != true ||
                    id == this.id || id == detail.id) return@mapNotNull null
                RelationSuggestion(parts[1], id, link.name?.takeIf { it.isNotBlank() } ?: id,
                    provider.getUnifiedMetaUrl(parts[1], id),
                    if (link.category.equals("prequel", true)) "prequel" else "sequel")
            }.distinctBy { it.type to it.id }.take(12).sortedBy { relation ->
                if ((detail.links.orEmpty() + links.orEmpty()).any {
                    it.category.equals("prequel", true) && it.url?.endsWith(relation.id) == true
                }) 0 else 1
            }

        private fun mergeRecommendations(
            provider: StremioC, relations: List<RelationSuggestion>,
            original: List<RecommendationCandidate>?, simklRows: List<SimklSuggestion>,
            currentTmdbId: String?
        ): List<SearchResponse> {

            // One ranking path for initial and asynchronous results: merge before scoring.
            val candidates = linkedMapOf<String, RecommendationCandidate>()
            original.orEmpty().forEach { rec ->
                val key = rec.type + ":" + (rec.tmdbId?.let { "tmdb:$it" } ?: rec.id)
                candidates[key] = rec.copy()
            }
            simklRows.forEach { rec ->
                if (currentTmdbId != null && rec.tmdbId?.toString() == currentTmdbId)
                    return@forEach
                val key = rec.type + ":" + (rec.tmdbId?.let { "tmdb:$it" } ?: rec.id)
                val card = candidates.getOrPut(key) {
                    RecommendationCandidate(rec.type, rec.id, rec.tmdbId, rec.name, rec.poster)
                }
                card.simklRank = rec.rank
                card.fallbackPoster = rec.poster
            }
            val ranked = candidates.values.sortedWith(
                compareByDescending<RecommendationCandidate> { it.collectionIndex != null }
                    .thenBy { it.collectionDate ?: "" }
                    .thenByDescending { it.score() }
                    .thenBy { it.collectionIndex ?: Int.MAX_VALUE }
                    .thenBy { it.simklRank ?: Int.MAX_VALUE }
                    .thenBy { it.tmdbRank ?: Int.MAX_VALUE }
            )
            val cards = ArrayList<SearchResponse>(50)
            val seen = HashSet<String>()
            val aliases = HashSet<String>()
            for (relation in relations) {
                val art = StremioC.cachedRelationArt(relation.url)
                art?.mal?.let { aliases.add("mal:$it") }
                art?.kitsu?.let { aliases.add("kitsu:$it") }
                if (!seen.add(relation.type + ":" + relation.id)) {
                    continue
                }
                cards += provider.newMovieSearchResponse(relation.name, relation.url,
                    if (relation.type == "movie") TvType.AnimeMovie else TvType.Anime
                ) { posterUrl = art?.poster ?: TRANSPARENT_PIXEL }
            }
            fun add(card: SearchResponse, id: String, type: String) {
                if (id in aliases) {
                    return
                }
                if (!seen.add(type + ":" + id)) {
                    return
                }
                if (cards.size < 50) {
                    cards += card
                }
            }
            ranked.take(50).forEach { rec ->
                val canonical = rec.tmdbId?.let { "tmdb:$it" } ?: rec.id
                if (rec.id in aliases || canonical in aliases) {
                    return@forEach
                }
                val data = LoadData(id = rec.id, type = rec.type, addonUrl = provider.mainUrl)
                val card = provider.newMovieSearchResponse(rec.name, data.toJson(),
                    if (rec.type == "movie") TvType.Movie else TvType.TvSeries
                ) { posterUrl = rec.poster ?: rec.fallbackPoster ?: TRANSPARENT_PIXEL }
                add(card, canonical, rec.type)
                seen.add(rec.type + ":" + rec.id)
            }
            return cards.take(50)
        }

        private fun scheduleRecommendationRefresh(
            provider: StremioC, url: String, relations: List<RelationSuggestion>,
            original: List<RecommendationCandidate>?, simklJob: Deferred<List<SimklSuggestion>>?,
            currentTmdbId: String?
        ) {
            if (simklJob != null) StremioC.simklSuggestionScope.launch {
                val rows = try { simklJob.await() } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                val cards = mergeRecommendations(provider, relations, original, rows, currentTmdbId)
                StremioC.publishRecommendations(url, cards)
            }
            if (relations.any { StremioC.cachedRelationArt(it.url) == null }) {
                StremioC.simklSuggestionScope.launch {
                    relations.map { relation -> async { StremioC.loadRelationArt(relation.url) } }.awaitAll()
                    val ready = simklJob?.isCompleted == true
                    val rows = if (ready) {
                        try { simklJob?.await().orEmpty() } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                    } else emptyList()
                    val cards = mergeRecommendations(provider, relations, original, rows, currentTmdbId)
                    StremioC.publishRecommendations(url, cards)
                }
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
            recommendationMalId: Int? = null,
            earlySimklJob: Deferred<List<SimklSuggestion>>? = null,
            addonLogoPreferred: Boolean = false,
            animeEpisodeSeasons: Map<Int, AnimeSeasonMapping> = emptyMap(),
            requestKitsuSeason: Boolean = false
        ): FetchedTmdbData {
            
            var fetchedRecommendations: List<RecommendationCandidate>? = null

            val detailAppend = if (isMovie) "release_dates,images,videos,external_ids,recommendations,translations" else "content_ratings,images,videos,external_ids,recommendations,translations"
            val videoLanguages = if (tmdbMediaType == "tv") arrayOf(
                "include_video_language" to tmdbTrailerLanguages().joinToString(",")
            ) else emptyArray()
            val detailUrl = provider.buildTmdbUrl("/$tmdbMediaType/$tmdbIdStr",
                "append_to_response" to detailAppend, "include_image_language" to tmdbLogoLanguages(),
                *videoLanguages)
            val cacheKey = detailUrl
            val detailLookupStarted = SystemClock.elapsedRealtime()
            val fromRam = stremioCacheMemoryRead(tmdbDetailsCache[cacheKey], METADATA_CACHE_TTL)
            val cached = fromRam ?: MetadataDiskCache.read("detail", cacheKey)?.let { (json, stamp) ->
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

            android.util.Log.i("MetaLoadTiming",
                "phase=DETAIL id=$tmdbIdStr cache=${if (preFetchedDetail != null) "PREFETCH" else if (fromRam != null) "RAM" else if (cached != null) "DISK" else "NETWORK"} elapsedMs=${SystemClock.elapsedRealtime() - detailLookupStarted} success=${detailRes != null}")
            if (detailRes == null) {
                
                return FetchedTmdbData(null, null, null, emptyList(), null, null, null, null, null, emptyMap(), emptyList(), null, null, null)
            }

            val simklJob = earlySimklJob ?: StremioC.requestSimklSuggestions(
                if (tmdbMediaType == "movie") "movie" else "series", tmdbIdStr,
                finalImdbId?.takeIf { StremioC.IMDB_ID_REGEX.matches(it) }
                    ?: detailRes.external_ids?.imdb_id,
                recommendationMalId
            )

            val episodeTmdbMeta = ConcurrentHashMap<String, TmdbEpisode>()

            val langCode = StremioTmdb.language()
            val seasonBatchStarted = SystemClock.elapsedRealtime()
            val activeSeasons = java.util.concurrent.atomic.AtomicInteger(0)

            coroutineScope {
                val shouldFocusEpisodes = tmdbMediaType == "tv"
                val episodeDeferred = if (shouldFocusEpisodes) {
                    var requiredSeasons = targetVideos?.mapNotNull { it.seasonNumber }?.filter { it > 0 }?.distinct() ?: emptyList()
                    if (requiredSeasons.isEmpty()) {
                        val totalSeasons = detailRes.number_of_seasons ?: 1
                        requiredSeasons = if (totalSeasons > 0) (1..totalSeasons).toList() else listOf(1)
                    }
                    if (requestKitsuSeason) requiredSeasons = (requiredSeasons + 1).distinct()
                    if (animeEpisodeSeasons.isNotEmpty()) {
                        val count = detailRes.number_of_seasons ?: 0
                        requiredSeasons = requiredSeasons.flatMap { sourceSeason ->
                            val mapped = animeEpisodeSeasons[sourceSeason]
                            val preferred = mapped?.tmdbSeason ?: sourceSeason
                            if (count > 0 && preferred > count) listOf(1)
                            else if (mapped != null && mapped.tmdbFromEpisode == null &&
                                preferred != 1) listOf(preferred, 1)
                            else listOf(preferred)
                        }.distinct()
                        val absolutes = targetVideos.orEmpty().mapNotNull { it.absoluteEpisode }
                        requiredSeasons = (requiredSeasons +
                            animeEpisodeSeasons.values.flatMap { mapping ->
                                mapping.tmdbRanges.filter { range ->
                                    absolutes.any { it >= range.start && (range.end == null || it <= range.end) }
                                }.map { it.season }
                            }).distinct().filter { it > 0 && (count <= 0 || it <= count) }
                    }
                    val tmdbSeasonCount = detailRes.number_of_seasons ?: 0
                    if (stremioType != "movie" && stremioType != "series" &&
                        tmdbSeasonCount > 0 && requiredSeasons.any { it > tmdbSeasonCount } &&
                        targetVideos.orEmpty().any {
                            !it.firstAired.isNullOrBlank() || !it.released.isNullOrBlank()
                        }) {
                        requiredSeasons = (requiredSeasons.filter { it in 1..tmdbSeasonCount } +
                            (1..tmdbSeasonCount)).distinct()
                    }
                    if (targetVideos.orEmpty().any { it.seasonNumber == 0 })
                        requiredSeasons = (requiredSeasons + 0).distinct()
                    android.util.Log.i("MetaLoadTiming",
                        "phase=SEASONS_START id=$tmdbIdStr count=${requiredSeasons.size}")
                    requiredSeasons.map { seasonNum ->
                        val queuedAt = SystemClock.elapsedRealtime()
                        async(Dispatchers.IO) {
                            var queueMs = -1L
                            var httpMs = -1L
                            var cacheSource = "MISS"
                            var status = "EMPTY"
                            var concurrency = 0
                            try {
                                StremioC.networkSemaphore.withPermit {
                                    queueMs = SystemClock.elapsedRealtime() - queuedAt
                                    concurrency = activeSeasons.incrementAndGet()
                                    try {
                                        val completed = withTimeoutOrNull(10000L) {
                                            runSuspendCatching {
                                                val seasonUrl = provider.buildTmdbUrl("/tv/$tmdbIdStr/season/$seasonNum")
                                                val seasonCacheKey = seasonUrl
                                                val ram = stremioCacheMemoryRead(tmdbSeasonCache[seasonCacheKey], SEASON_CACHE_TTL)
                                                val cachedSeason = ram ?: MetadataDiskCache.read("season", seasonCacheKey)?.let { (json, stamp) ->
                                                    runCatching { parseJson<TmdbSeasonDetail>(json) }.getOrNull()?.let { value ->
                                                        Pair(value, stamp).also {
                                                            tmdbSeasonCache[seasonCacheKey] = it
                                                        }
                                                    }
                                                }
                                                cacheSource = if (ram != null) "RAM" else if (cachedSeason != null) "DISK" else "MISS"
                                                val seasonRes = if (cachedSeason != null) {
                                                    cachedSeason.first
                                                } else {
                                                    val httpStarted = SystemClock.elapsedRealtime()
                                                    val fetchedSeasonText = try {
                                                        fetchSharedMetadataText(seasonUrl)
                                                    } finally {
                                                        httpMs = SystemClock.elapsedRealtime() - httpStarted
                                                    }
                                                    val fetchedSeason = run {
                                                        if (fetchedSeasonText.isNotBlank()) parseJson<TmdbSeasonDetail>(fetchedSeasonText) else null
                                                    }
                                                    if (fetchedSeason != null) {
                                                        tmdbSeasonCache[seasonCacheKey] = Pair(fetchedSeason, System.currentTimeMillis())
                                                        MetadataDiskCache.write("season", seasonCacheKey, fetchedSeasonText)
                                                    }
                                                    fetchedSeason
                                                }
                                                status = if (seasonRes != null) "OK" else "EMPTY"
                                                seasonRes?.episodes?.forEach { ep -> if (ep.episodeNumber != null) episodeTmdbMeta["${seasonNum}_${ep.episodeNumber}"] = ep }
                                            }
                                            true
                                        }
                                        if (completed == null) status = "TIMEOUT"
                                    } finally {
                                        activeSeasons.decrementAndGet()
                                    }
                                }
                            } finally {
                                android.util.Log.i("MetaLoadTiming",
                                    "phase=SEASON id=$tmdbIdStr season=$seasonNum cache=$cacheSource queueMs=$queueMs httpMs=$httpMs totalMs=${SystemClock.elapsedRealtime() - queuedAt} concurrent=$concurrency status=$status")
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
                if (shouldFocusEpisodes) android.util.Log.i("MetaLoadTiming",
                    "phase=SEASONS_END id=$tmdbIdStr count=${episodeDeferred?.size ?: 0} episodes=${episodeTmdbMeta.size} elapsedMs=${SystemClock.elapsedRealtime() - seasonBatchStarted}")

                // No per-recommendation Simkl or TMDB detail calls: use lightweight rows
                // from the two existing responses and keep collection ordering native.
                val candidates = linkedMapOf<String, RecommendationCandidate>()
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
                        RecommendationCandidate(type, "tmdb:$id", id, title, poster)
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
                fetchedRecommendations = candidates.values.toList()
                
            }

            val countryCode = langCode.substringAfter("-", "KR").uppercase()

            val fetchedAgeRating = if (isMovie) {
                detailRes.release_dates?.results?.firstOrNull { it.iso_3166_1 == countryCode }?.release_dates?.firstOrNull { !it.certification.isNullOrEmpty() }?.certification
                    ?: detailRes.release_dates?.results?.firstOrNull { it.iso_3166_1 == "US" }?.release_dates?.firstOrNull { !it.certification.isNullOrEmpty() }?.certification
            } else {
                detailRes.content_ratings?.results?.firstOrNull { it.iso_3166_1 == countryCode }?.rating
                    ?: detailRes.content_ratings?.results?.firstOrNull { it.iso_3166_1 == "US" }?.rating
            }

            val fetchedTrailers = collectStremioTrailers(
                detailRes.videos?.results.orEmpty()
                    .filter { it.site.equals("YouTube", true) }
                    .map { StremioTrailerCandidate(it.key, it.name, it.type, it.language, it.region, it.publishedAt) }
            )

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
            val preferredLogo = detailRes.images?.logos.orEmpty()
                .filter { logo ->
                    val language = logo.iso_639_1?.lowercase()
                    !logo.file_path.isNullOrBlank() &&
                        (language == preferred || (!addonLogoPreferred && language == "en"))
                }
                .maxWithOrNull(compareBy<TmdbLogo> {
                    when (it.iso_639_1?.lowercase()) { preferred -> 3; "en" -> 2; else -> 1 }
                }.thenBy { it.vote_average ?: 0.0 })
            // If the addon logo wins, TMDB is consulted only to decide whether
            // the app-language logo exists; no English/original fallback is needed.
            val originalLanguage = detailRes.original_language?.lowercase()
                ?.takeIf { it.matches(Regex("[a-z]{2,3}")) && it != preferred && it != "en" }
            val originalLogo = if (!addonLogoPreferred &&
                preferredLogo == null && originalLanguage != null) {
                val url = provider.buildTmdbUrl("/$tmdbMediaType/$tmdbIdStr/images",
                    "include_image_language" to originalLanguage)
                runSuspendCatching {
                    val cached = stremioCacheMemoryRead(tmdbOriginLogoCache[url], METADATA_CACHE_TTL)
                        ?: MetadataDiskCache.read("origin-logo", url)?.let { (json, stamp) ->
                            runCatching { parseJson<TmdbImages>(json) }.getOrNull()?.let { it to stamp }
                        }
                    val images = cached?.first ?: fetchSharedMetadataText(url)
                        .takeIf { it.isNotBlank() }?.let { text ->
                            parseJson<TmdbImages>(text).also { parsed ->
                                tmdbOriginLogoCache[url] = parsed to System.currentTimeMillis()
                                MetadataDiskCache.write("origin-logo", url, text)
                            }
                        }
                    images?.logos.orEmpty().filter { logo ->
                        logo.iso_639_1.equals(originalLanguage, true) &&
                            !logo.file_path.isNullOrBlank()
                    }.maxByOrNull { it.vote_average ?: 0.0 }
                }.getOrNull()
            } else null
            val selectedLogo = preferredLogo ?: originalLogo

            return FetchedTmdbData(
                if (isMovie) detailRes.title ?: detailRes.original_title else detailRes.name ?: detailRes.original_name,
                detailRes.overview, selectedLogo?.file_path?.let { path ->

                    val rasterPath = if (path.endsWith(".svg", ignoreCase = true))
                        path.dropLast(4) + ".png" else path
                    "https://image.tmdb.org/t/p/w500$rasterPath"
                },
                fetchedTrailers, fetchedRecommendations, if (isMovie) detailRes.runtime else null, fetchedAgeRating,
                (detailRes.releaseDate ?: detailRes.firstAirDate)?.split("-")?.first()?.toIntOrNull(),
                if (detailRes.status == "Returning Series") ShowStatus.Ongoing else ShowStatus.Completed, episodeTmdbMeta, detailRes.origin_country,
                detailRes.original_title ?: detailRes.original_name,
                extractedEnglishTitle,
                detailRes.vote_average,
                selectedLogo != null && selectedLogo.iso_639_1?.lowercase() != preferred,
                detailRes.genres.orEmpty().mapNotNull { it.id },
                detailRes.backdropPath?.takeIf(String::isNotBlank)
                    ?.let { "https://image.tmdb.org/t/p/w1280$it" },
                detailRes.posterPath?.takeIf(String::isNotBlank)
                    ?.let { "https://image.tmdb.org/t/p/w780$it" },
                detailRes.genres.orEmpty().mapNotNull { it.name?.takeIf(String::isNotBlank) }
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
            originalAnimeId: String? = null,
            animeEpisodeSeasons: Map<Int, AnimeSeasonMapping> = emptyMap(),
            kitsuEpisodes: Map<Int, Map<Int, Int>> = emptyMap()
        ): List<Episode> {
            val videos = targetVideos ?: emptyList()

            val maxRegularSeason = videos.mapNotNull { it.seasonNumber }.filter { it > 0 }.maxOrNull() ?: 0
            val targetSeasonForZero = maxRegularSeason + 1

            val videosByDisplaySeason = videos.groupBy {
                val s = it.seasonNumber ?: 0
                if (s == 0) targetSeasonForZero else s
            }

            val processedEpisodes = mutableListOf<Episode>()
            val parser = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply {
                isLenient = false
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            fun day(value: String?): Long? = value?.take(10)?.let { stamp ->
                runCatching { parser.parse(stamp)?.time?.div(86_400_000L) }.getOrNull()
            }
            val tmdbEpisodeDays = episodeTmdbMeta.mapValues { (_, ep) -> day(ep.airDate) }
            val tmdbKeysByEpisode = episodeTmdbMeta.keys.groupBy { it.substringAfterLast('_') }
            val byAirDate = if (isAnime) tmdbEpisodeDays.entries.mapNotNull { (key, date) ->
                date?.let { it to key }
            }.groupBy({ it.first }, { it.second }) else emptyMap()
            val usedTmdb = mutableSetOf<String>()
            val absoluteByEpisode = mutableMapOf<Pair<Int, Int>, Int>()
            var total = 0
            var contiguous = true
            var nextSeason = 1
            for (s in videosByDisplaySeason.keys.filter { it > 0 }.sorted()) {
                val numbered = videosByDisplaySeason[s].orEmpty()
                    .mapNotNull { (it.episode ?: it.number)?.takeIf { n -> n > 0 } }
                    .distinct().sorted()
                if (!contiguous || s != nextSeason ||
                    numbered.isEmpty() || numbered != (1..numbered.size).toList()) {
                    contiguous = false
                    continue
                }
                for (n in numbered) absoluteByEpisode[s to n] = total + n
                total += numbered.size
                nextSeason++
            }

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

                    val isSpecial = video.seasonNumber == 0
                    val mapping = if (isSpecial) null else animeEpisodeSeasons[displaySeason]
                    val tmdbSeason = mapping?.tmdbSeason
                    val directEpisode = mapping?.tmdbFromEpisode
                        ?.let { it + displayEpisode - 1 } ?: displayEpisode
                    val originalDay = day(video.firstAired ?: video.released)
                    val absolute = video.absoluteEpisode?.takeIf { it > 0 }
                        ?: absoluteByEpisode[displaySeason to displayEpisode]
                    val range = absolute?.let { n -> mapping?.tmdbRanges
                        ?.singleOrNull { n >= it.start && (it.end == null || n <= it.end) } }
                    val aioCandidate = if (isSpecial) "0_$displayEpisode" else
                        range?.let { "${it.season}_$absolute" }
                            ?: tmdbSeason?.let { "${it}_$directEpisode" }
                    val aioEpisode = aioCandidate?.let { episodeTmdbMeta[it] }
                    val aioDateDiff = originalDay?.let { a ->
                        day(aioEpisode?.airDate)?.let { kotlin.math.abs(a - it) }
                    }
                    val rejectedAio = aioDateDiff != null && aioDateDiff >= 30L
                    val directKey = aioCandidate?.takeIf {
                        aioEpisode != null && !rejectedAio && it !in usedTmdb
                    }
                    val kitsuAbsolute = kitsuEpisodes[if (isSpecial) 0 else displaySeason]
                        ?.get(displayEpisode)
                        ?: video.absoluteEpisode?.takeIf { it > 0 }
                    val kitsuMatches = if (directKey == null) kitsuAbsolute?.let { n ->
                        tmdbKeysByEpisode[n.toString()].orEmpty().filter { key ->
                            val targetDay = tmdbEpisodeDays[key]
                            key.endsWith("_$n") &&
                                key.startsWith("0_") == isSpecial &&
                                key != (if (rejectedAio) aioCandidate else null) &&
                                (originalDay == null || targetDay == null ||
                                    kotlin.math.abs(originalDay - targetDay) < 30L)
                        }
                    }.orEmpty() else emptyList()
                    val absoluteKey = kitsuMatches.singleOrNull()?.takeUnless { it in usedTmdb }
                    val dateMatches = if (isAnime && directKey == null && absoluteKey == null &&
                        originalDay != null) (-1L..1L)
                            .flatMap { byAirDate[originalDay + it].orEmpty() }
                            .filter { it.startsWith("0_") == isSpecial }.distinct()
                        else emptyList()
                    val airDateKey = dateMatches.singleOrNull()?.takeUnless { it in usedTmdb }
                    val mappedKey = directKey ?: absoluteKey ?: airDateKey
                    if (mappedKey != null) usedTmdb.add(mappedKey)
                    if (displaySeason >= 21) android.util.Log.i("MetaEpisodeMap",
                        "phase=STREMIOC_REQUEST app=S$displaySeason:E$displayEpisode" +
                            " special=$isSpecial absolute=$absolute aio=$aioCandidate aioDateDiff=$aioDateDiff" +
                            " aioRejected30d=$rejectedAio kitsuAbsolute=$kitsuAbsolute" +
                            " kitsuCandidates=${kitsuMatches.size} dateCandidates=${dateMatches.size}" +
                            " finalTmdb=$mappedKey method=${if (directKey != null) "AIO" else if (absoluteKey != null) "KITSU" else if (airDateKey != null) "AIRDATE" else "FAIL"}" +
                            " reason=${if (mappedKey != null) "MATCH" else if (rejectedAio) "AIRDATE_30D" else if (aioEpisode == null) "AIO_EPISODE_MISSING" else "AMBIGUOUS_OR_DUPLICATE"}")
                    val ep = video.toEpisode(
                        provider, type, epImdbId, episodeTmdbMeta, 
                        displaySeason, displayEpisode, showTitle, tmdbId, 
                        isAnime, isBollywood, isAsian, isCartoon, 
                        imdbSeason, imdbEpisode, originalTitle, englishTitle, 
                        aniListId, malId, kitsuId,
                        provider.mainUrl,
                        year, 
                        originalAnimeId,
                        mappedKey, isAnime || mapping != null || kitsuEpisodes.containsKey(displaySeason)
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
            resolvedTmdbMediaType: String? = null,
            resolvedIdHolder: Array<String?>? = null,
            requestedAnimeId: String? = null,
            prefetchedAnimeMapping: Deferred<AnimeSeasonMapping?>? = null,
            prefetchedAnimeImdbId: String? = null
        ): LoadResponse = coroutineScope {
            val syncedEntry = run { addonDeferred?.await() }
            val detailedEntry = syncedEntry ?: this@CatalogEntry
            val fullMetadataSync = StremioC.isMetadataSwapAllowed(provider.mainUrl)
            val nonstandardType =
                provider.detailMediaType(detailedEntry.type ?: this@CatalogEntry.type) == null
            val originalAnimeId = requestedAnimeId
                ?: this@CatalogEntry.id?.takeIf { provider.isLibraryAnimeId(it) }
                ?: if (nonstandardType) {
                    detailedEntry.id?.takeIf { provider.isLibraryAnimeId(it) }
                        ?: detailedEntry.kitsuIdExt?.toIntOrNull()?.takeIf { it > 0 }?.let { "kitsu:$it" }
                        ?: detailedEntry.malIdExt?.toIntOrNull()?.takeIf { it > 0 }?.let { "mal:$it" }
                } else null
            val extraAnimeMapping = if (nonstandardType && originalAnimeId != null &&
                originalAnimeId != requestedAnimeId && originalAnimeId != this@CatalogEntry.id) {
                provider.resolveAnimeIdForLoad(originalAnimeId)
            } else null

            var finalImdbId = if (this@CatalogEntry.id?.startsWith("tt") == true) this@CatalogEntry.id 
                              else imdbId?.takeIf { it.startsWith("tt") || provider.isLibraryAnimeId(it) }

            if (finalImdbId == null && detailedEntry.links?.isNotEmpty() == true) {
                finalImdbId = detailedEntry.links.firstOrNull { it.category == "imdb" }?.url?.substringAfterLast("/")?.takeIf { it.startsWith("tt") }
            }
            if (finalImdbId == null) {
                finalImdbId = provider.extractImdbId(detailedEntry.logo) ?: provider.extractImdbId(detailedEntry.poster) ?: provider.extractImdbId(detailedEntry.background)
            }
            if (nonstandardType && finalImdbId == null) finalImdbId = extraAnimeMapping?.imdbId
            val eligibleNonstandardId = nonstandardType && (
                sequenceOf(this@CatalogEntry.id, detailedEntry.id, originalAnimeId, finalImdbId)
                    .any { provider.isTmdbEligibleId(it) } ||
                sequenceOf(this@CatalogEntry.tmdbIdExt, detailedEntry.tmdbIdExt,
                    this@CatalogEntry.malIdExt, detailedEntry.malIdExt,
                    this@CatalogEntry.kitsuIdExt, detailedEntry.kitsuIdExt)
                    .any { it?.toIntOrNull()?.let { n -> n > 0 } == true } ||
                (this@CatalogEntry.moviedb_id ?: 0) > 0 ||
                (detailedEntry.moviedb_id ?: 0) > 0
            )

            var tmdbIdStr: String? = resolvedTmdbId ?: extraAnimeMapping?.tmdbId?.toString()
                ?: if (this@CatalogEntry.id?.startsWith("tmdb:") == true)
                    this@CatalogEntry.id.removePrefix("tmdb:")
                else this@CatalogEntry.moviedb_id?.toString()
                    ?: this@CatalogEntry.tmdbIdExt ?: detailedEntry.tmdbIdExt
                    ?: detailedEntry.id?.takeIf { nonstandardType && it.startsWith("tmdb:") }
                        ?.removePrefix("tmdb:")
            var tmdbType = provider.detailMediaType(detailedEntry.type ?: this@CatalogEntry.type)
            if (nonstandardType) {
                if (!eligibleNonstandardId) tmdbIdStr = null
                tmdbType = if (eligibleNonstandardId) when {
                    tmdbIdStr != null && tmdbIdStr == resolvedTmdbId -> resolvedTmdbMediaType
                    tmdbIdStr != null && tmdbIdStr == extraAnimeMapping?.tmdbId?.toString() ->
                        extraAnimeMapping?.tmdbMediaType
                    else -> null
                } else null
            }
            if (tmdbType != null && tmdbIdStr == null && finalImdbId?.startsWith("tt") == true &&
                preFetchedFind?.second == tmdbType && preFetchedFindImdbId == finalImdbId) {
                tmdbIdStr = preFetchedFind.first
            }
            val lookupImdbId = finalImdbId?.takeIf { StremioC.IMDB_ID_REGEX.matches(it) }
            val lookupMediaType = tmdbType
            val needsTmdbFind = if (nonstandardType) tmdbType == null else tmdbIdStr == null
            val findApiDeferred: Deferred<Pair<String, String>?>? = if (
                needsTmdbFind && lookupImdbId != null &&
                    (!nonstandardType || eligibleNonstandardId)
            ) {
                async(Dispatchers.IO) {
                    provider.fetchTmdbFindForLoad(lookupImdbId, lookupMediaType)
                }
            } else null
            val recommendationMalId = fetchedMalId ?: detailedEntry.malIdExt?.toIntOrNull()
                ?: originalAnimeId?.takeIf { it.startsWith("mal:") }
                    ?.substringAfter(':')?.toIntOrNull()
            val simklJob = if (!nonstandardType) StremioC.requestSimklSuggestions(
                if (tmdbType == "movie") "movie" else "series",
                tmdbIdStr, finalImdbId?.takeIf { StremioC.IMDB_ID_REGEX.matches(it) },
                recommendationMalId
            ) else null
            val relations = relatedTitles(provider, detailedEntry)

            var finalVideos = detailedEntry.videos ?: this@CatalogEntry.videos
            val finalTrailersSources = detailedEntry.trailersSources ?: this@CatalogEntry.trailersSources
            val finalTrailerStreams = detailedEntry.trailerStreams ?: this@CatalogEntry.trailerStreams
            val originalName = sequenceOf(detailedEntry.name, detailedEntry.title,
                this@CatalogEntry.name, this@CatalogEntry.title)
                .firstOrNull { !it.isNullOrBlank() }
            var finalGenre = listOfNotNull(detailedEntry.genre, detailedEntry.genres,
                this@CatalogEntry.genre, this@CatalogEntry.genres).firstOrNull { it.isNotEmpty() }
            // Skip unsupported SVG URLs, including an SVG wrapped by an art proxy.
            fun isSvgLogo(url: String): Boolean {
                val parsed = url.toHttpUrlOrNull() ?: return url.substringBefore('?')
                    .endsWith(".svg", ignoreCase = true)
                return parsed.encodedPath.endsWith(".svg", ignoreCase = true) ||
                    parsed.queryParameter("url")?.toHttpUrlOrNull()?.encodedPath
                        ?.endsWith(".svg", ignoreCase = true) == true
            }
            // Prefer the active addon's meta.logo regardless of which addon supplies it.
            val addonLogo = detailedEntry.logo?.takeIf { it.isNotBlank() && !isSvgLogo(it) }
            val fallbackAddonLogo = sequenceOf(detailedEntry.logoUrl, detailedEntry.providerArt?.logo,
                this@CatalogEntry.logo, this@CatalogEntry.logoUrl, this@CatalogEntry.providerArt?.logo)
                .firstOrNull { !it.isNullOrBlank() && !isSvgLogo(it) }
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
            var finalPoster = sequenceOf(detailedEntry.poster, this@CatalogEntry.poster)
                .firstOrNull { !it.isNullOrBlank() }
            val addonBackground = sequenceOf(detailedEntry.background, detailedEntry.backdrop,
                this@CatalogEntry.background, this@CatalogEntry.backdrop)
                .firstOrNull { !it.isNullOrBlank() }
                        val finalType = detailedEntry.type ?: this@CatalogEntry.type
            val nativeActors = aioActorRows(detailedEntry.appExtras)
                .ifEmpty { aioActorRows(this@CatalogEntry.appExtras) }

            val videoSeasons = finalVideos.orEmpty().mapNotNull {
                it.seasonNumber?.takeIf { number -> number > 0 }
            }.distinct()
            val isMovieForSync = finalType == "movie" || isSingleMovie(finalType ?: "", finalVideos)

            val canSyncAnimeTrackers = isMovieForSync ||
                ((finalType == "series" || finalType == "tv" || eligibleNonstandardId) &&
                    videoSeasons.singleOrNull() != null)
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
                if (findResult != null && (nonstandardType || findResult.second == tmdbType)) {
                    if (nonstandardType) {
                        // Keep the IMDb find result's ID and type together.
                        tmdbIdStr = findResult.first
                        tmdbType = findResult.second
                    } else if (tmdbIdStr == null) tmdbIdStr = findResult.first
                }
            }
            var probedTmdbDetail: TmdbDetailResponse? = null
            if (eligibleNonstandardId && tmdbType == null &&
                tmdbIdStr?.toIntOrNull()?.let { it > 0 } == true) {
                // Direct TMDB ID with no type: check actual endpoints, never video counts.
                for (candidate in listOf("movie", "tv")) {
                    val detail = provider.fetchTmdbDetailForLoad(tmdbIdStr!!, candidate)
                    if (detail != null) {
                        tmdbType = candidate
                        probedTmdbDetail = detail
                        break
                    }
                }
            }
            val resolvedSimklJob = simklJob ?: if (eligibleNonstandardId &&
                (tmdbType == "movie" || tmdbType == "tv")) StremioC.requestSimklSuggestions(
                if (tmdbType == "movie") "movie" else "series",
                tmdbIdStr, finalImdbId?.takeIf { StremioC.IMDB_ID_REGEX.matches(it) },
                recommendationMalId
            ) else null

            val castTmdbId = tmdbIdStr?.takeIf { it.toIntOrNull()?.let { id -> id > 0 } == true }
            val castTmdbType = tmdbType?.takeIf { it == "movie" || it == "tv" }
            val creditsDeferred = if (nativeActors.none { !it.crew } &&
                castTmdbId != null && castTmdbType != null &&
                (!nonstandardType || eligibleNonstandardId)) {
                async(Dispatchers.IO) {
                    aioActorRows(provider.fetchTmdbCredits(castTmdbType, castTmdbId))
                }
            } else null

            val animeEpisodeSeasons = if (tmdbType == "tv" && videoSeasons.isNotEmpty() &&
                (originalAnimeId != null || fetchedKitsuId != null ||
                    detailedEntry.kitsuIdExt != null || this@CatalogEntry.kitsuIdExt != null ||
                    fetchedMalId != null || fetchedAniListId != null ||
                    (nonstandardType && useImdbSyncMapping))) {
                var sharedRanges: AnimeSeasonMapping? = null
                videoSeasons.mapNotNull { season ->
                    val directId = originalAnimeId?.takeIf { videoSeasons.size == 1 }
                    val reusable = sharedRanges?.takeIf { previous ->
                        val episodes = finalVideos.orEmpty().filter {
                            it.seasonNumber == season && (it.episode ?: it.number ?: 0) > 0
                        }
                        episodes.isNotEmpty() && episodes.all { ep ->
                            val absolute = ep.absoluteEpisode
                            absolute != null && previous.tmdbRanges.any { range ->
                                absolute >= range.start &&
                                    (range.end == null || absolute <= range.end)
                            }
                        }
                    }
                    val mapping = (reusable ?: provider.fetchAnimeSeasonMapping(LoadData(
                        id = directId ?: finalImdbId,
                        imdbId = finalImdbId?.takeIf { directId == null },
                        originalAnimeId = directId,
                        kitsuId = fetchedKitsuId ?: detailedEntry.kitsuIdExt
                            ?: this@CatalogEntry.kitsuIdExt,
                        malId = fetchedMalId, anilistId = fetchedAniListId,
                        isAnime = true, season = season
                    )))?.takeIf { it.tmdbId == null ||
                        it.tmdbId.toString() == tmdbIdStr }
                    if (sharedRanges == null && mapping?.tmdbRanges?.isNotEmpty() == true)
                        sharedRanges = mapping
                    if (mapping != null) season to mapping else null
                }.toMap()
            } else emptyMap()
            var tmdbData: FetchedTmdbData? = null
            if (tmdbType != null && tmdbIdStr != null &&
                (!nonstandardType || eligibleNonstandardId)) {
                try {
                    val refinedMediaType = tmdbType
                    val safePrefetchedDetail = probedTmdbDetail ?: preFetchedDetail?.takeIf {
                        preFetchedDetailTmdbId == tmdbIdStr && preFetchedDetailMediaType == refinedMediaType
                    }
                    tmdbData = run {
                        fetchTmdbDetails(provider, refinedMediaType, tmdbIdStr!!,
                            refinedMediaType == "movie", finalImdbId, finalType, finalVideos,
                            safePrefetchedDetail, if (safePrefetchedDetail != null) preFetchedCollection else null,
                            recommendationMalId, resolvedSimklJob,
                            addonLogoPreferred = !eligibleNonstandardId &&
                                (addonLogo != null || fallbackAddonLogo != null),
                            animeEpisodeSeasons = animeEpisodeSeasons,
                            requestKitsuSeason = animeEpisodeSeasons.isEmpty() &&
                                (fetchedKitsuId != null || detailedEntry.kitsuIdExt != null ||
                                    this@CatalogEntry.kitsuIdExt != null ||
                                    originalAnimeId?.startsWith("kitsu:") == true))
                    }
                } catch (e: Exception) {
                }
            }

            if (finalPoster == null) finalPoster = tmdbData?.fetchedPoster
            if (finalGenre.isNullOrEmpty()) finalGenre = tmdbData?.fetchedGenres
            val finalBackground = addonBackground ?: tmdbData?.fetchedBackdrop
            val finalName = originalName?.takeIf { it.isNotBlank() }
                ?: tmdbData?.fetchedTitle?.takeIf { it.isNotBlank() } ?: "Unknown Content"
            val finalAddonDescription = sequenceOf(detailedEntry.description, detailedEntry.overview,
                this@CatalogEntry.description, this@CatalogEntry.overview)
                .firstOrNull { !it.isNullOrBlank() }
            var finalDescription = if (fullMetadataSync)
                finalAddonDescription ?: tmdbData?.fetchedOverview?.takeIf { it.isNotBlank() }
            else if (eligibleNonstandardId)
                tmdbData?.fetchedOverview?.takeIf { it.isNotBlank() } ?: finalAddonDescription
            else finalAddonDescription ?: tmdbData?.fetchedOverview?.takeIf { it.isNotBlank() }


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
            val finalTrailers = collectStremioTrailers(
                emptyList(), if (fullMetadataSync)
                    fallbackTrailers.ifEmpty { tmdbData?.fetchedTrailers.orEmpty() }
                else tmdbData?.fetchedTrailers.orEmpty().ifEmpty { fallbackTrailers }
            )

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
            val tmdbRecommendations = tmdbData?.fetchedRecommendations
            val readySimklRows = if (resolvedSimklJob?.isCompleted == true) {
                try { resolvedSimklJob.await() } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { emptyList() }
            } else emptyList()
            val recommendationsForPage = mergeRecommendations(
                provider, relations, tmdbRecommendations, readySimklRows, tmdbIdStr
            )

            resolvedIdHolder?.let {
                it[0] = cleanImdbId
                it[1] = cleanTmdbId
            }

            val finalYear = sequenceOf(detailedEntry.year, this@CatalogEntry.year)
                .mapNotNull { it?.trim()?.toIntOrNull()?.takeIf { year -> year in 1800..3000 } }
                .firstOrNull() ?: tmdbData?.fetchedYear
            val countryCode = StremioTmdb.language().substringAfter("-", "KR").uppercase()
            val finalAgeRating = sequenceOf(detailedEntry.appExtras, this@CatalogEntry.appExtras)
                .mapNotNull { it?.certificationLocal?.trim()?.takeIf(String::isNotBlank) }
                .firstOrNull()
                ?: sequenceOf(detailedEntry.appExtras, this@CatalogEntry.appExtras)
                    .mapNotNull { it?.certifications?.get(countryCode)?.trim()?.takeIf(String::isNotBlank) }
                    .firstOrNull()
                ?: sequenceOf(detailedEntry.appExtras, this@CatalogEntry.appExtras)
                    .mapNotNull { it?.certifications?.get("US")?.trim()?.takeIf(String::isNotBlank) }
                    .firstOrNull()
                ?: sequenceOf(detailedEntry.contentRating, detailedEntry.certification,
                    this@CatalogEntry.contentRating, this@CatalogEntry.certification)
                    .firstOrNull { !it.isNullOrBlank() }
                ?: tmdbData?.fetchedAgeRating
            val finalShowStatus = sequenceOf(detailedEntry.status, this@CatalogEntry.status)
                .mapNotNull { status -> when (status?.trim()?.lowercase()) {
                    "ended", "canceled", "cancelled" -> ShowStatus.Completed
                    "returning series", "in production" -> ShowStatus.Ongoing
                    else -> null
                } }.firstOrNull() ?: tmdbData?.fetchedStatus
            val finalRatingValue = sequenceOf(detailedEntry.imdbRating, this@CatalogEntry.imdbRating,
                detailedEntry.rating, this@CatalogEntry.rating)
                .mapNotNull { it?.trim()?.toDoubleOrNull()?.takeIf { n -> n.isFinite() && n > 0 && n <= 10 } }
                .firstOrNull()?.toString()
                ?: tmdbData?.fetchedVoteAverage?.takeIf { it.isFinite() && it > 0 && it <= 10 }?.toString()

            val hasAnimeTrackerIds = finalMalId != null || finalAniId != null ||
                this@CatalogEntry.kitsuIdExt != null || finalKitsuId != null
            val finalMovieType = if (isAnimeMedia && hasAnimeTrackerIds) TvType.AnimeMovie else TvType.Movie
            val finalSeriesType = if (isAnimeMedia && hasAnimeTrackerIds) TvType.Anime else TvType.TvSeries
            val finalLogo = if (fullMetadataSync)
                addonLogo ?: fallbackAddonLogo ?: tmdbData?.fetchedLogo?.takeIf { it.isNotBlank() }
            else if (eligibleNonstandardId)
                tmdbData?.fetchedLogo?.takeIf { it.isNotBlank() } ?: addonLogo ?: fallbackAddonLogo
            else addonLogo ?: fallbackAddonLogo
                ?: tmdbData?.fetchedLogo?.takeIf { it.isNotBlank() }

            // With an addon logo, only the app-language TMDB logo decides whether
            // to show the title alongside it; without one, use normal TMDB fallback.
            val finalLogoFallback = stremioLogoNeedsTitle(
                finalLogo, tmdbData?.fetchedLogo, tmdbData?.fetchedLogoFallback == true
            )
            val fallbackActors = creditsDeferred?.await().orEmpty()
            val finalActors = if (nativeActors.isEmpty()) fallbackActors
                else if (nativeActors.none { !it.crew })
                    nativeActors + fallbackActors.filter { !it.crew }
                else nativeActors

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
                if (finalActors.isNotEmpty()) {
                    actors = finalActors.map { it.data }
                    syncData["tmdb_people"] = org.json.JSONArray().apply {
                        finalActors.forEach { row ->
                            put(org.json.JSONObject()
                                .put("name", row.data.actor.name)
                                .put("id", row.id ?: org.json.JSONObject.NULL)
                                .put("crew", row.crew))
                        }
                    }.toString()
                }

                sequenceOf(detailedEntry.original_language, detailedEntry.providerArt?.originalLanguage,
                    this@CatalogEntry.original_language, this@CatalogEntry.providerArt?.originalLanguage)
                    .mapNotNull { it?.trim()?.lowercase()?.takeIf { code ->
                        code.matches(Regex("[a-z]{2,3}")) && code != "und"
                    } }.firstOrNull()?.let { syncData["original_language"] = it }
                val metadataSwapAllowed = StremioC.isMetadataSwapAllowed(provider.mainUrl)
                syncData["metadata_swap_allowed"] = metadataSwapAllowed.toString()
                syncData["watch_progress_allowed"] = StremioC.isWatchProgressAllowed(provider.mainUrl).toString()
                syncData["watch_progress_prefer_kitsu"] = (isAnimeMedia && finalKitsuId != null).toString()
                syncData["trailer_choices"] = serializeStremioTrailers(finalTrailers)
                syncData["logo_expected"] = (!finalLogo.isNullOrBlank()).toString()
                syncData["logo_foreign_language"] = finalLogoFallback.toString()
                trailers = NoNativeTrailerPreview()
            }

            if (finalVideos.isNullOrEmpty() || isSingleMovie(finalType, finalVideos)) {
                val metaUrl = provider.getUnifiedMetaUrl(finalType ?: "movie", this@CatalogEntry.id ?: "")
                StremioC.animeAudioByUrl[metaUrl] = isAnimeMedia
                StremioC.episodeBasedByUrl[metaUrl] = false
                StremioC.seasonalAnimeByUrl.remove(metaUrl)
                StremioC.seasonEpisodeCountsByUrl[metaUrl] = finalVideos.orEmpty()
                    .mapNotNull { video -> video.seasonNumber?.takeIf { it > 0 }?.let { it to video } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, videos) -> videos.size }
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
                        year = finalYear, 
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
                        watchProgressAllowed = StremioC.isWatchProgressAllowed(provider.mainUrl),
                        watchProgressPreferKitsu = isAnimeMedia && finalKitsuId != null,
                        originalAnimeId = originalAnimeId,
                        embeddedStreams = finalVideos?.singleOrNull()?.embeddedStreamsJson(),
                        embeddedStreamTitle = finalVideos?.singleOrNull()?.let { it.title ?: it.name }
                    )
                ) {
                    posterUrl = finalPoster; backgroundPosterUrl = finalBackground; score = Score.from10(finalRatingValue); year = finalYear
                    this.plot = finalDescription
                    this.tags = finalGenre
                    this.recommendations = recommendationsForPage
                    this.duration = stremioRuntimeMinutes(detailedEntry.runtime ?: this@CatalogEntry.runtime)
                        ?: finalVideos?.singleOrNull()?.runtime?.let(::stremioRuntimeMinutes)
                        ?: tmdbData?.fetchedRuntime
                    this.contentRating = finalAgeRating
                    finalLogo?.let { this.logoUrl = it }

                } }
                scheduleRecommendationRefresh(provider, metaUrl, relations, tmdbRecommendations, resolvedSimklJob, tmdbIdStr)
                return@coroutineScope run { response.apply { attachMappedIds() } }

            } else {
                val representativeRunTime = stremioRuntimeMinutes(detailedEntry.runtime ?: this@CatalogEntry.runtime)
                val dateParser = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply {
                    isLenient = false
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                val needsKitsu = finalVideos.orEmpty().any { video ->
                    val season = video.seasonNumber ?: return@any false
                    val mapping = animeEpisodeSeasons[season]
                    val absolute = video.absoluteEpisode
                    val range = absolute?.let { n -> mapping?.tmdbRanges
                        ?.singleOrNull { n >= it.start && (it.end == null || n <= it.end) } }
                    val key = range?.let { "${it.season}_$absolute" }
                        ?: mapping?.tmdbSeason?.let { target ->
                            val number = (video.episode ?: video.number ?: 1) +
                                (mapping.tmdbFromEpisode ?: 1) - 1
                            "${target}_$number"
                        }
                    val matched = key?.let { tmdbData?.episodeTmdbMeta?.get(it) }
                    if (matched == null) true else {
                        val sourceDate = (video.firstAired ?: video.released)?.take(10)
                        val targetDate = matched.airDate?.take(10)
                        if (sourceDate == null || targetDate == null) false else
                            runCatching {
                                val left = dateParser.parse(sourceDate)?.time
                                val right = dateParser.parse(targetDate)?.time
                                left != null && right != null &&
                                    kotlin.math.abs(left - right) >= 30L * 86_400_000L
                            }.getOrDefault(false)
                    }
                }
                val kitsuImdb = if (needsKitsu) provider.fetchKitsuImdbEpisodes(finalImdbId)
                    else emptyMap()
                val kitsuEpisodes = (videoSeasons +
                    (if (finalVideos.orEmpty().any { it.seasonNumber == 0 }) listOf(0)
                    else emptyList())).distinct().mapNotNull { season ->
                    val matched = kitsuImdb.filterKeys { it.first == season }
                        .mapKeys { it.key.second }
                    if (matched.isNotEmpty()) season to matched
                    else if (season > 0 && needsKitsu && videoSeasons.size == 1) {
                        val fallbackId = animeEpisodeSeasons[season]?.kitsuId
                            ?: finalKitsuId?.toString() ?: detailedEntry.kitsuIdExt
                            ?: this@CatalogEntry.kitsuIdExt
                        fallbackId?.let { season to provider.fetchKitsuAbsoluteEpisodes(it) }
                    } else null
                }.toMap()
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
                    finalYear, 
                    originalAnimeId,
                    animeEpisodeSeasons, kitsuEpisodes
                ) }.onEach { episode ->
                    if (episode.runTime == null) episode.runTime = representativeRunTime
                }
                val metaUrl = provider.getUnifiedMetaUrl(finalType ?: "series", this@CatalogEntry.id ?: "")
                StremioC.animeAudioByUrl[metaUrl] = isAnimeMedia
                StremioC.episodeBasedByUrl[metaUrl] = true
                val specialsSeason = finalVideos.orEmpty().takeIf { videos ->
                    videos.any { it.seasonNumber == 0 }
                }?.let { videos ->
                    (videos.mapNotNull { it.seasonNumber?.takeIf { n -> n > 0 } }.maxOrNull() ?: 0) + 1
                }
                if (eligibleNonstandardId && isAnimeMedia &&
                    finalVideos.orEmpty().any { it.seasonNumber != null }) {
                    StremioC.seasonalAnimeByUrl[metaUrl] =
                        cleanImdbId?.takeIf { IMDB_ID_REGEX.matches(it) } to specialsSeason
                } else StremioC.seasonalAnimeByUrl.remove(metaUrl)
                StremioC.seasonEpisodeCountsByUrl[metaUrl] = finalVideos.orEmpty()
                    .mapNotNull { video -> video.seasonNumber?.takeIf { it > 0 }?.let { it to video } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, videos) -> videos.size }
                StremioC.genreCatalogsByUrl[metaUrl] = genreTargets
                val response = run { provider.newTvSeriesLoadResponse(
                    finalName, metaUrl, finalSeriesType, finalEpisodes
                ) {
                    posterUrl = finalPoster; backgroundPosterUrl = finalBackground; score = Score.from10(finalRatingValue); year = finalYear
                    this.showStatus = finalShowStatus
                    if (specialsSeason != null) {
                        val seasonLabel = CloudStreamApp.context
                            ?.getString(com.lagradost.cloudstream3.R.string.season)
                            ?.let { "$it S" } ?: "S"
                        this.seasonNames = listOf(SeasonData(specialsSeason, seasonLabel))
                    }
                    this.plot = finalDescription
                    this.tags = finalGenre
                    this.recommendations = recommendationsForPage
                    this.contentRating = finalAgeRating
                    finalLogo?.let { this.logoUrl = it }

                } }
                scheduleRecommendationRefresh(provider, metaUrl, relations, tmdbRecommendations, resolvedSimklJob, tmdbIdStr)
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
        @JsonProperty("rating") val rating: JsonNode? = null,
        @JsonProperty("tvdb_id") val tvdb_id: Int? = null,
        @JsonProperty("imdbSeason") val imdbSeason: Int? = null,
        @JsonProperty("imdbEpisode") val imdbEpisode: Int? = null,
        @JsonProperty("imdb_id") val imdb_id: String? = null,
        @JsonProperty("streams") val streams: JsonNode? = null,
        @JsonProperty("absoluteEpisode") val absoluteEpisode: Int? = null
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
            originalAnimeId: String? = null,
            tmdbEpisodeKey: String? = null,
            mappedTmdbOnly: Boolean = false
        ): Episode { 
            val unknownEpisode = type != "movie" && type != "series" && (episode ?: number ?: 0) <= 0
            val tmdbEp = when {
                unknownEpisode -> null
                mappedTmdbOnly -> tmdbEpisodeKey?.let { tmdbMetaMap[it] }
                else -> tmdbMetaMap["${displaySeason}_${displayEpisode}"]
                    ?: tmdbMetaMap["${seasonNumber ?: 0}_${episode ?: number ?: 0}"]
            }
            val traceTmdbKey = if (displaySeason < 21 || unknownEpisode) null else if (mappedTmdbOnly)
                tmdbEpisodeKey else listOf(
                    "${displaySeason}_${displayEpisode}",
                    "${seasonNumber ?: 0}_${episode ?: number ?: 0}"
                ).firstOrNull { tmdbMetaMap.containsKey(it) }
            if (displaySeason >= 21) android.util.Log.i("MetaEpisodeMap",
                "phase=STREMIOC_MAP app=S$displaySeason:E$displayEpisode tmdbKey=$traceTmdbKey mappedOnly=$mappedTmdbOnly found=${tmdbEp != null}" +
                    " sourceThumb=${!thumbnail.isNullOrBlank()} sourcePlot=${!(overview ?: description).isNullOrBlank()}" +
                    " tmdbThumb=${!tmdbEp?.stillPath.isNullOrBlank()} tmdbPlot=${!tmdbEp?.overview.isNullOrBlank()}" +
                    " tmdbRating=${tmdbEp?.voteAverage} tmdbAirDate=${tmdbEp?.airDate}")
            val finalAirDate = if (unknownEpisode) null else (
                tmdbEp?.airDate?.takeIf { it.isNotBlank() } ?: this@Video.firstAired ?: this@Video.released
            )
            val airedYearInt = finalAirDate?.split("-")?.first()?.toIntOrNull()

            if (displaySeason >= 21) android.util.Log.i("StremioSourceTrace",
                "phase=EPISODE_CREATE id=$id video=S${seasonNumber}:E${episode ?: number}" +
                    " display=S$displaySeason:E$displayEpisode" +
                    " imdb=S$imdbSeason:E$imdbEpisode originalAnimeId=$originalAnimeId" +
                    " tmdbKey=$tmdbEpisodeKey mappedTmdbOnly=$mappedTmdbOnly" +
                    " aired=$finalAirDate isAnime=$isAnime")
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
                watchProgressAllowed = StremioC.isWatchProgressAllowed(addonUrl),
                watchProgressPreferKitsu = isAnime && !kitsuId.isNullOrBlank(),
                originalAnimeId = originalAnimeId,
                embeddedStreams = embeddedStreamsJson(),
                embeddedStreamTitle = title ?: name
            )) {
                val rawTitle = this@Video.name ?: this@Video.title ?: "Episode $displayEpisode"
                val cleanOriginalTitle = if (rawTitle.contains("📄")) rawTitle.substringAfter("📄").replace("\n", "").trim() else rawTitle.replace("\n", "").trim()

                this.name = if (tmdbEp?.name != null) tmdbEp.name else cleanOriginalTitle
                val tmdbThumb = tmdbEp?.stillPath?.takeIf(String::isNotBlank)
                    ?.let { "https://image.tmdb.org/t/p/w500$it" }
                val originalThumb = thumbnail?.takeIf(String::isNotBlank)
                this.posterUrl = if (mappedTmdbOnly && tmdbEpisodeKey != null)
                    tmdbThumb ?: originalThumb ?: TRANSPARENT_PIXEL
                else originalThumb ?: tmdbThumb ?: TRANSPARENT_PIXEL

                var finalEpDesc = (tmdbEp?.overview ?: this@Video.overview ?: this@Video.description)?.takeIf { it != "null" }
                if (type != "movie" && type != "series" && !unknownEpisode) {
                    finalEpDesc = if (finalEpDesc.isNullOrBlank()) cleanOriginalTitle else "$finalEpDesc<br>$cleanOriginalTitle"
                }

                this.description = if (unknownEpisode) null else finalEpDesc
                this.season = displaySeason
                this.episode = displayEpisode
                this.runTime = stremioRuntimeMinutes(this@Video.runtime)
                    ?: tmdbEp?.runtime?.takeIf { it > 0 }
                val episodeRating = this@Video.rating?.asText()?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0 && it <= 10 }
                    ?: tmdbEp?.voteAverage?.takeIf { it.isFinite() && it > 0 && it <= 10 }
                episodeRating?.let { this.score = Score.from10(it) }

                finalAirDate?.takeIf { it.isNotBlank() }?.let { this.addDate(it) }
                if (displaySeason >= 21) android.util.Log.i("MetaEpisodeMap",
                    "phase=STREMIOC_FINAL app=S$displaySeason:E$displayEpisode tmdbKey=$traceTmdbKey" +
                        " finalThumb=${!this.posterUrl.isNullOrBlank()} finalPlot=${!this.description.isNullOrBlank()}" +
                        " finalRating=$episodeRating finalAirDate=$finalAirDate")
            }
        }
    }

    /** Diagnose unsuccessful stream parsing without recording URLs or response values. */
    private fun parseTracedStreams(
        body: String, traceId: String, source: String, type: String, id: String,
        status: Int, contentType: String?
    ): StreamsResponse? {
        if (body.isBlank()) return null
        return try {
            parseJson<StreamsResponse>(body)
        } catch (error: Exception) {
            val root = runCatching { parseJson<JsonNode>(body) }.getOrNull()
            val streams = root?.get("streams")
            val first = streams?.takeIf { it.isArray && it.size() > 0 }?.get(0)
            fun keys(node: JsonNode?): String = node?.takeIf { it.isObject }?.fieldNames()
                ?.asSequence()?.take(12)?.joinToString(",") { it.take(32) } ?: "-"
            val mapping = error as? com.fasterxml.jackson.databind.JsonMappingException
            val path = mapping?.path?.joinToString(".") {
                it.fieldName ?: "[${it.index}]"
            }?.take(160) ?: "-"
            val location = (error as? com.fasterxml.jackson.core.JsonProcessingException)?.location
            traceSource(traceId, "STREMIO_STREAM_PARSE_DIAG",
                "source=$source type=$type id=$id status=$status" +
                " mime=${contentType?.substringBefore(';')?.take(60)} chars=${body.length}" +
                " leadingCode=${body.firstOrNull { !it.isWhitespace() }?.code}" +
                " root=${root?.nodeType ?: "INVALID_JSON"} rootKeys=${keys(root)}" +
                " streams=${streams?.nodeType ?: "MISSING"} count=${if (streams?.isArray == true) streams.size() else -1}" +
                " first=${first?.nodeType ?: "NONE"} firstKeys=${keys(first)}" +
                " error=${error.javaClass.simpleName} cause=${error.cause?.javaClass?.simpleName}" +
                " path=$path line=${location?.lineNr} col=${location?.columnNr}")
            throw error
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
        val rawLang = lang?.trim()?.takeUnless { it.isBlank() || it.equals("Unknown", true) }
            ?: lang_code?.trim()?.takeUnless { it.isBlank() || it.equals("Unknown", true) }
            ?: return null
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
        val detail = buildString {
            if (mainLabel != null && !mainLabel.equals(language, true)) append(mainLabel)
            if (sourceLabel != null) {
                if (isNotEmpty()) append(" — ")
                append(sourceLabel)
            }
        }
        if (detail.isNotBlank()) synchronized(subtitleDetailByUrl) {
            subtitleDetailByUrl[subtitleUrl] = detail
        }
        if (sourceLabel != null) synchronized(subtitleSourceByUrl) {
            subtitleSourceByUrl[subtitleUrl] = sourceLabel
        }
        // Keep translated choices distinct from actual target-language subtitles.
        val groupKey = if (sourceLanguage != null) translationGroupId(displayLanguage) else language
        return newSubtitleFile(groupKey, subtitleUrl)
    }
    private data class ProxyHeaders(@JsonProperty("request") val request: Map<String, String>?)   
    private data class BehaviorHints(@JsonProperty("proxyHeaders") val proxyHeaders: ProxyHeaders?, @JsonProperty("headers") val headers: Map<String, String>?, @JsonProperty("filename") val filename: String? = null)


    // Share only concurrently running extractor work within one addon's response.
    // Each stream still receives its own callbacks in the original order.

    // Share only an in-flight "no matching extractor" result within one response.
    // Successful extractor calls and their mutable links/callbacks remain independent.
    private class ExtractorFlightGroup {
        private val flights = HashMap<Pair<String, String>, CompletableDeferred<Boolean>>()

        suspend fun load(
            kind: String, url: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            linkCallback: (ExtractorLink) -> Unit
        ) {
            val key = kind to url
            var leader = false
            val flight = synchronized(flights) {
                flights[key] ?: CompletableDeferred<Boolean>().also {
                    flights[key] = it
                    leader = true
                }
            }
            if (!leader) {
                if (flight.await()) return
                loadExtractor(url, subtitleCallback, linkCallback)
                return
            }
            var callbacks = 0
            try {
                val found = loadExtractor(url,
                    { subtitle -> callbacks++; subtitleCallback(subtitle) },
                    { link -> callbacks++; linkCallback(link) })
                flight.complete(!found && callbacks == 0)
            } catch (error: Throwable) {
                flight.complete(false)
                throw error
            } finally {
                synchronized(flights) {
                    if (flights[key] === flight) flights.remove(key)
                }
            }
        }
    }

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
            addonUrl: String? = null,
            emitLinks: Boolean = true,
            emitSubtitles: Boolean = true,
            extractorFlights: ExtractorFlightGroup? = null
        ) {
            val displaySource = sourceName.takeIf { it.isNotBlank() } ?: name ?: ""
            fun rememberFilename(url: String, displayName: String) {
                val key = url + "\u0000" + displayName
                val filename = behaviorHints?.filename?.trim()?.takeIf { it.isNotEmpty() }
                if (filename == null) streamFilenameByLink.remove(key)
                else streamFilenameByLink[key] = filename
            }
            suspend fun torrentLink(raw: String, type: ExtractorLinkType): String {
                val withTrackers = StremioTorrentFiles.addTrackers(raw, sources)
                val index = fileIdx?.takeIf { it >= 0 }
                    ?: StremioTorrentFiles.explicitIndex(withTrackers)
                    ?: StremioTorrentFiles.largestIndex(withTrackers, type, infoHash)
                return if (index == null) withTrackers
                else StremioTorrentFiles.withIndex(withTrackers, type, index)
            }

            if (emitLinks && url != null) {
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
                val linkName = "[$displaySource] $displayTitle"
                rememberFilename(playbackUrl, linkName)
                callback.invoke(newExtractorLink(displaySource, linkName, playbackUrl, linkType) {
                    this.quality = getQuality(listOf(description, title, name))
                    this.headers = behaviorHints?.proxyHeaders?.request ?: behaviorHints?.headers ?: mapOf()
                })
            }

            if ((emitLinks || emitSubtitles) && ytId != null) {
                val target = "https://m.youtube.com/watch?v=$ytId"
                val subs = if (emitSubtitles) extractorSubtitleCallback else { _: SubtitleFile -> }
                val links = if (emitLinks) callback else { _: ExtractorLink -> }
                if (extractorFlights != null) extractorFlights.load("youtube", target, subs, links)
                else loadExtractor(target, subs, links)
            }
            if ((emitLinks || emitSubtitles) && externalUrl != null) {
                val subs = if (emitSubtitles) extractorSubtitleCallback else { _: SubtitleFile -> }
                val links = if (emitLinks) callback else { _: ExtractorLink -> }
                if (extractorFlights != null) extractorFlights.load("external", externalUrl, subs, links)
                else loadExtractor(externalUrl, subs, links)
            }
            if (emitLinks && infoHash != null) {
                val displayTitle = description ?: title ?: name ?: ""
                val magnet = torrentLink("magnet:?xt=urn:btih:" + infoHash, ExtractorLinkType.MAGNET)
                val linkName = "[$displaySource] $displayTitle"
                rememberFilename(magnet, linkName)
                callback.invoke(newExtractorLink(displaySource, linkName, magnet, ExtractorLinkType.MAGNET) {
                    this.quality = Qualities.Unknown.value
                })
            }

            val hasPlayableTarget = url != null || ytId != null || externalUrl != null || infoHash != null
            if (emitSubtitles && hasPlayableTarget) {
                subtitles.forEachIndexed { position, sub ->
                    provider.run {
                        sub.toCloudStreamSubtitle(sourceName, preferredName, sub.translationSourceLanguage(subtitles))
                    }?.let { file ->
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

private data class TmdbGenre(@JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null)

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
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("origin_country") val origin_country: List<String>? = null, 
    @JsonProperty("original_language") val original_language: String? = null, 
    @JsonProperty("belongs_to_collection") val belongs_to_collection: TmdbCollection? = null, 
    @JsonProperty("recommendations") val recommendations: TmdbRecommendations? = null,
    @JsonProperty("runtime") val runtime: Int? = null, 
    @JsonProperty("release_dates") val release_dates: TmdbReleaseDates? = null, 
    @JsonProperty("content_ratings") val content_ratings: TmdbContentRatings? = null, 
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
private data class TmdbSeasonDetail(@JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null)
private data class TmdbEpisode(@JsonProperty("name") val name: String?, @JsonProperty("overview") val overview: String?, @JsonProperty("episode_number") val episodeNumber: Int?, @JsonProperty("air_date") val airDate: String?, @JsonProperty("vote_average") val voteAverage: Double?, @JsonProperty("runtime") val runtime: Int?, @JsonProperty("still_path") val stillPath: String? = null)

private data class TmdbCreditsResponse(
    @JsonProperty("cast") val cast: List<TmdbCreditPerson>? = null,
    @JsonProperty("crew") val crew: List<TmdbCreditPerson>? = null
)
private data class TmdbCreditPerson(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("job") val job: String? = null
)

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
