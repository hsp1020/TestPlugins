package com.hsp1020

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.text.SpannableStringBuilder
import android.view.View
import android.widget.FrameLayout
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.media3.extractor.text.SubtitleParser
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.player.CustomDecoder
import com.lagradost.cloudstream3.ui.player.CustomDecoder.Companion.fixSubtitleAlignment
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.applyStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import okhttp3.CacheControl
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.security.MessageDigest

/** Stremio provenance is intentionally kept outside CloudStream's SubtitleFile/SubtitleData. */
@OptIn(UnstableApi::class)
internal object StremioSubtitleRefreshRegistry {
    data class TimedCues(val startTimeMs: Long, val endTimeMs: Long, val cues: List<Cue>)
    data class Prepared(
        val cues: List<TimedCues>, val timeline: SubtitleTimeline,
        val preserveAssLayout: Boolean = false, val fingerprint: ByteArray = byteArrayOf()
    )

    data class Entry(
        val originalUrl: String,
        val addonUrl: String,
        val id: String?,
        val lang: String?,
        val listUrl: String?,
        val position: Int,
        val headers: Map<String, String>,
        val latestUrl: String = originalUrl,
        val ambiguous: Boolean = false
    )

    private val entries = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 1200
    }

    @Synchronized
    fun remember(
        url: String, addonUrl: String, id: String?, lang: String?,
        listUrl: String?, position: Int, headers: Map<String, String> = emptyMap()
    ) {
        if (url.isBlank()) return
        // The native player de-duplicates by URL. Prefer exact list provenance
        // if an inline stream entry for the same addon was recorded first.
        val previous = entries[url]
        if (previous != null && (previous.addonUrl != addonUrl ||
                (previous.id != null && id != null && previous.id != id))) {
            // CloudStream collapses equal URLs. Never guess which addon owns that row.
            entries[url] = previous.copy(ambiguous = true)
            return
        }
        if (previous?.ambiguous == true) return
        if (previous == null || (previous.addonUrl == addonUrl &&
            listUrl != null &&
            (previous.id == null || previous.id == id))) {
            entries[url] = Entry(
                url, addonUrl, id?.takeIf { it.isNotBlank() }, lang, listUrl, position, headers
            )
        }
    }

    @Synchronized
    fun find(url: String): Entry? = entries[url]?.takeUnless { it.ambiguous }

    @Synchronized
    private fun update(entry: Entry, url: String) {
        val stored = entries[entry.originalUrl]
        if (stored != null && stored.addonUrl == entry.addonUrl && stored.id == entry.id) {
            entries[entry.originalUrl] = stored.copy(latestUrl = url)
        }
    }

    private val noCacheClient by lazy { app.baseClient.newBuilder().cache(null).build() }
    private val cacheControl = CacheControl.Builder().noCache().noStore().build()
    private const val MAX_SUB_BYTES = 4 * 1024 * 1024
    private const val MAX_LIST_BYTES = 512 * 1024

    private suspend fun readBytes(url: String, headers: Map<String, String>, max: Int): ByteArray {
        require(url.startsWith("https://", true) || url.startsWith("http://", true))
        val request = Request.Builder().url(url).cacheControl(cacheControl).apply {
            app.defaultHeaders.forEach { (name, value) -> header(name, value) }
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = noCacheClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            check(response.isSuccessful) { "HTTP " + response.code }
                            val body = response.body ?: error("Empty subtitle response")
                            require(body.contentLength() <= max || body.contentLength() < 0)
                            body.byteStream().use { stream ->
                                val out = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("Cancelled")
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    check(out.size() + count <= max) { "Subtitle response exceeds limit" }
                                    out.write(buffer, 0, count)
                                }
                                out.toByteArray()
                            }
                        }
                        continuation.resume(bytes)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    /**
     * Refresh only the originating addon request and selected subtitle body.
     * A changed URL is matched by id; if id is absent we only accept the
     * original URL; ambiguous or missing identities fail without replacing cues.
     */
    suspend fun refresh(
        entry: Entry, previous: Prepared? = null, checkList: Boolean = true
    ): Prepared = withContext(Dispatchers.IO) {
        val current = find(entry.originalUrl) ?: error("Subtitle provenance changed")
        check(current.addonUrl == entry.addonUrl && current.id == entry.id &&
            current.listUrl == entry.listUrl) { "Subtitle provenance changed" }
        var url = current.latestUrl
        if (checkList && entry.listUrl != null) {
            // Translation addons can temporarily fail to list tracks. Only
            // the exact known URL is safe as fallback, not language/position.
            val updated = try {
                val response = JSONObject(readBytes(entry.listUrl, emptyMap(), MAX_LIST_BYTES).toString(Charsets.UTF_8))
                val subtitles = response.optJSONArray("subtitles") ?: error("Missing subtitles")
                val candidates = (0 until subtitles.length()).mapNotNull { index ->
                    subtitles.optJSONObject(index)?.let { candidate ->
                        SubtitleRefreshCandidate(
                            candidate.optString("id").takeIf { it.isNotBlank() },
                            candidate.optString("lang", candidate.optString("lang_code")).takeIf { it.isNotBlank() },
                            candidate.optString("url")
                        )
                    }
                }
                selectRefreshedSubtitleUrl(entry.id, entry.lang, entry.originalUrl, current.latestUrl, candidates)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (updated != null) url = updated
        }
        val bytes = readBytes(url, entry.headers, MAX_SUB_BYTES)
        check(bytes.isNotEmpty()) { "Empty subtitle" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes)
        if (previous != null && previous.fingerprint.contentEquals(fingerprint)) {
            update(entry, url)
            return@withContext previous
        }
        val preview = bytes.copyOfRange(0, minOf(bytes.size, 256)).toString(Charsets.UTF_8)
        check(!preview.contains("<html", true)) { "Unexpected HTML" }
        val trimmed = preview.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val isAss = trimmed.startsWith("[Script Info]", true) ||
            trimmed.startsWith("Title:", true) || trimmed.startsWith("[V4+ Styles]", true)
        val type = when {
            isAss -> MimeTypes.TEXT_SSA
            trimmed.startsWith("WEBVTT", true) -> MimeTypes.TEXT_VTT
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val prepared = withContext(Dispatchers.Default) {
            val decoder = CustomDecoder(Format.Builder().setSampleMimeType(type).build())
            val parsed = ArrayList<TimedCues>()
            decoder.parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { output ->
                // CustomDecoder records unshifted timing before invoking this callback.
                // Keep Media3's styled cues, without changing its global subtitle offset.
                val timing = decoder.currentSubtitleCues.lastOrNull()
                if (timing != null && timing.durationMs > 0 &&
                    output.cues.any { !it.text.isNullOrBlank() }) {
                    parsed.add(TimedCues(timing.startTimeMs, timing.endTimeMs, output.cues.toList()))
                }
            }
            ensureActive()
            val cues = parsed.sortedBy { it.startTimeMs }
            val timeline = SubtitleTimeline(
                cues.map { it.startTimeMs }.toLongArray(), cues.map { it.endTimeMs }.toLongArray()
            )
            Prepared(cues, timeline, isAss, fingerprint)
        }
        check(prepared.cues.isNotEmpty()) { "No valid subtitle cues" }
        ensureActive()
        update(entry, url)
        prepared
    }
}

/** Only active after a manually refreshed Stremio subtitle has been selected. */
@OptIn(UnstableApi::class)
internal class StremioRefreshedSubtitleOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var fragment: GeneratorPlayer? = null
    private var mediaPlayer: Player? = null
    private var original: SubtitleView? = null
    private var originalOutput: View? = null
    private var originalOutputAlpha = 1f
    private var shadowedElevate: View? = null
    private var shadowedElevateAlpha = 1f
    private var overlay: SubtitleView? = null
    private var host: FragmentActivity? = null
    private var targetUrl: String? = null
    private var cues: List<StremioSubtitleRefreshRegistry.TimedCues> = emptyList()
    private var timeline: SubtitleTimeline? = null
    private var preserveAssLayout = false
    private var rendered: List<Int>? = null
    private var lastOffset = Long.MIN_VALUE
    private val tick = Runnable { synchronize() }
    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { applyAppearance() }
    private val styleListener: (com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle) -> Unit = {
        overlay?.post { if (overlay != null) { applyAppearance(); rendered = null; synchronize() } }
    }

    fun onPlayerEvent() {
        if (overlay != null) synchronize()
    }

    fun bind(candidate: GeneratorPlayer) {
        if (fragment == null) return
        val selected = candidate.player.getCurrentPreferredSubtitle()?.url
        if (fragment !== candidate || candidate.playerView?.player !== mediaPlayer ||
            targetUrl != selected || candidate.subView !== original) {
            detach()
        } else if (candidate.player.getSubtitleOffset() != lastOffset) {
            // Native offset apply/reset emits a seek event, including while paused.
            rendered = null
            synchronize()
        }
    }

    fun attach(candidate: GeneratorPlayer, subtitleUrl: String,
               prepared: StremioSubtitleRefreshRegistry.Prepared) {
        val parsed = prepared.cues
        val player = candidate.playerView?.player ?: return
        val native = candidate.subView ?: return
        val nativeOutput = native.getChildAt(0) ?: return
        if (candidate.player.getCurrentPreferredSubtitle()?.url != subtitleUrl) return
        if (fragment === candidate && mediaPlayer === player &&
            original === native && targetUrl == subtitleUrl && overlay != null) {
            if (cues !== parsed) {
                cues = parsed
                timeline = prepared.timeline
                preserveAssLayout = prepared.preserveAssLayout
                rendered = null
                synchronize()
            }
            return
        }
        detach()
        fragment = candidate
        mediaPlayer = player
        original = native
        originalOutput = nativeOutput
        originalOutputAlpha = nativeOutput.alpha
        shadowedElevate = (0 until native.childCount).map { native.getChildAt(it) }
            .firstOrNull { it.tag == "ElevateLateSubtitleOverlay" }
        shadowedElevateAlpha = shadowedElevate?.alpha ?: 1f
        shadowedElevate?.alpha = 0f
        targetUrl = subtitleUrl
        cues = parsed
        timeline = prepared.timeline
        preserveAssLayout = prepared.preserveAssLayout
        val view = SubtitleView(native.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Inherit CloudStream's control-avoidance translation from the native subtitle view.
        native.addView(view, FrameLayout.LayoutParams(-1, -1))
        overlay = view
        nativeOutput.alpha = 0f
        host = candidate.activity as? FragmentActivity
        host?.addOnPictureInPictureModeChangedListener(pipListener)
        SubtitlesFragment.applyStyleEvent += styleListener
        applyAppearance()
        synchronize()
    }

    private fun applyAppearance() {
        val view = overlay ?: return
        val saved = SubtitlesFragment.getCurrentSavedStyle()
        SubtitlesFragment.setSubtitleViewStyle(view, saved, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && host?.isInPictureInPictureMode == true) {
            view.setPadding(view.paddingLeft, 0, view.paddingRight, view.paddingBottom)
            view.translationY = 0f
            view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.5f)
            view.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        } else {
            // Parent subView already applies subtitle elevation and control avoidance.
            view.translationY = 0f
        }
    }

    private fun synchronize() {
        main.removeCallbacks(tick)
        val candidate = fragment ?: return
        val player = mediaPlayer ?: return
        val view = overlay ?: return
        if (candidate.playerView?.player !== player ||
            candidate.player.getCurrentPreferredSubtitle()?.url != targetUrl) {
            detach()
            return
        }
        val offset = candidate.player.getSubtitleOffset()
        lastOffset = offset
        val time = player.currentPosition + offset
        val indexes = timeline?.activeAt(time) ?: emptyList()
        if (indexes != rendered) {
            val style = SubtitlesFragment.getCurrentSavedStyle()
            val active = indexes.flatMap { index -> cues[index].cues }
            val visible = if (!preserveAssLayout && active.size > 1 &&
                active.all { !it.text.isNullOrBlank() && it.bitmap == null }) {
                // SRT has no ASS layout coordinates: stack overlapping dialogue in one cue.
                val text = SpannableStringBuilder()
                active.forEachIndexed { index, cue ->
                    if (index > 0) text.append('\n')
                    text.append(cue.text)
                }
                listOf(active.first().buildUpon().setText(text)
                    .fixSubtitleAlignment().applyStyle(style).build())
            } else {
                active.map { cue ->
                    cue.buildUpon().fixSubtitleAlignment().applyStyle(style).apply {
                        if (preserveAssLayout) {
                            setLine(cue.line, cue.lineType)
                            setLineAnchor(cue.lineAnchor)
                            setPosition(cue.position)
                            setPositionAnchor(cue.positionAnchor)
                            setTextAlignment(cue.textAlignment)
                        }
                    }.build()
                }
            }
            view.setCues(visible)
            rendered = indexes
        }
        if (!player.isPlaying) return
        val next = timeline?.nextAfter(time) ?: return
        val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
        val wait = ((next - time) / speed).toLong().coerceAtLeast(1L)
        main.postDelayed(tick, wait)
    }

    fun detach() {
        main.removeCallbacks(tick)
        host?.removeOnPictureInPictureModeChangedListener(pipListener)
        if (overlay != null) SubtitlesFragment.applyStyleEvent -= styleListener
        val view = overlay
        (view?.parent as? FrameLayout)?.removeView(view)
        originalOutput?.alpha = originalOutputAlpha
        shadowedElevate?.alpha = shadowedElevateAlpha
        shadowedElevate = null
        originalOutput = null
        overlay = null
        original = null
        fragment = null
        mediaPlayer = null
        host = null
        targetUrl = null
        cues = emptyList()
        timeline = null
        preserveAssLayout = false
        rendered = null
        lastOffset = Long.MIN_VALUE
    }
}

internal data class SubtitleRefreshCandidate(val id: String?, val language: String?, val url: String)

/** Candidates must come from the originating addon request, never a merged subtitle list. */
internal fun selectRefreshedSubtitleUrl(
    id: String?, language: String?, originalUrl: String, latestUrl: String,
    candidates: List<SubtitleRefreshCandidate>
): String? {
    val matching = candidates.filter {
        it.url.isNotBlank() && (id == null || it.id == id) &&
            (language == null || language.equals(it.language, true))
    }
    val exactUrls = matching.filter { it.url == latestUrl || it.url == originalUrl }
        .map { it.url }.distinct()
    if (exactUrls.size == 1) return exactUrls.single()
    if (exactUrls.size > 1) return null
    // A language or array position alone must never identify a changed URL.
    return if (id != null) matching.singleOrNull()?.url else null
}

/** Immutable index built off the UI thread. Intervals are start-inclusive, end-exclusive. */
internal class SubtitleTimeline(private val starts: LongArray, private val ends: LongArray) {
    val boundaries = (starts.asSequence() + ends.asSequence()).distinct().sorted().toList().toLongArray()
    private val prefixEnds = LongArray(ends.size)

    init {
        require(starts.size == ends.size)
        var end = Long.MIN_VALUE
        for (index in starts.indices) {
            require(ends[index] > starts[index])
            require(index == 0 || starts[index - 1] <= starts[index])
            end = maxOf(end, ends[index])
            prefixEnds[index] = end
        }
    }

    fun activeAt(time: Long): List<Int> {
        var low = 0
        var high = prefixEnds.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (prefixEnds[middle] <= time) low = middle + 1 else high = middle
        }
        val active = ArrayList<Int>(2)
        for (index in low until starts.size) {
            if (starts[index] > time) break
            if (time < ends[index]) active.add(index)
        }
        return active
    }

    fun nextAfter(time: Long): Long? {
        var low = 0
        var high = boundaries.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (boundaries[middle] <= time) low = middle + 1 else high = middle
        }
        return boundaries.getOrNull(low)
    }
}
