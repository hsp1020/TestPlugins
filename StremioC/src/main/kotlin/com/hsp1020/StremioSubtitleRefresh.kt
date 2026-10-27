package com.hsp1020

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.TypedValue
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

/** Stremio provenance is intentionally kept outside CloudStream's SubtitleFile/SubtitleData. */
@OptIn(UnstableApi::class)
internal object StremioSubtitleRefreshRegistry {
    data class TimedCues(val startTimeMs: Long, val endTimeMs: Long, val cues: List<Cue>)
    data class Prepared(val cues: List<TimedCues>, val timeline: SubtitleTimeline)

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
    suspend fun refresh(entry: Entry): Prepared = withContext(Dispatchers.IO) {
        check(find(entry.originalUrl)?.let {
            it.addonUrl == entry.addonUrl && it.id == entry.id && it.listUrl == entry.listUrl
        } == true) { "Subtitle provenance changed" }
        var url = entry.latestUrl
        if (entry.listUrl != null) {
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
            url = selectRefreshedSubtitleUrl(entry.id, entry.lang, entry.originalUrl, entry.latestUrl, candidates)
                ?: error("Subtitle missing or ambiguous")
        }
        val bytes = readBytes(url, entry.headers, MAX_SUB_BYTES)
        check(bytes.isNotEmpty()) { "Empty subtitle" }
        val preview = bytes.copyOfRange(0, minOf(bytes.size, 256)).toString(Charsets.UTF_8)
        check(!preview.contains("<html", true)) { "Unexpected HTML" }
        check(!preview.trimStart().startsWith("[Script Info]", true) &&
            !preview.trimStart().startsWith("Title:", true)) { "Unsupported ASS/SSA subtitle" }
        val type = if (preview.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                .startsWith("WEBVTT", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
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
            Prepared(cues, timeline)
        }
        check(prepared.cues.isNotEmpty()) { "No valid SRT/VTT cues" }
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
    private var originalAlpha = 1f
    private var overlay: SubtitleView? = null
    private var host: FragmentActivity? = null
    private var targetUrl: String? = null
    private var cues: List<StremioSubtitleRefreshRegistry.TimedCues> = emptyList()
    private var timeline: SubtitleTimeline? = null
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
        val container = native.parent as? FrameLayout ?: return
        if (candidate.player.getCurrentPreferredSubtitle()?.url != subtitleUrl) return
        if (fragment === candidate && mediaPlayer === player &&
            original === native && targetUrl == subtitleUrl && overlay != null) {
            if (cues !== parsed) {
                cues = parsed
                timeline = prepared.timeline
                rendered = null
                synchronize()
            }
            return
        }
        detach()
        fragment = candidate
        mediaPlayer = player
        original = native
        originalAlpha = native.alpha
        targetUrl = subtitleUrl
        cues = parsed
        timeline = prepared.timeline
        val view = SubtitleView(native.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        overlay = view
        originalAlpha = native.alpha
        native.alpha = 0f
        host = candidate.activity as? FragmentActivity
        host?.addOnPictureInPictureModeChangedListener(pipListener)
        SubtitlesFragment.applyStyleEvent += styleListener
        applyAppearance()
        synchronize()
    }

    private fun applyAppearance() {
        val view = overlay ?: return
        val native = original ?: return
        val saved = SubtitlesFragment.getCurrentSavedStyle()
        SubtitlesFragment.setSubtitleViewStyle(view, saved, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && host?.isInPictureInPictureMode == true) {
            view.setPadding(view.paddingLeft, 0, view.paddingRight, view.paddingBottom)
            view.translationY = 0f
            view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.5f)
            view.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        } else {
            view.translationY = -TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, saved.elevation.toFloat(), view.resources.displayMetrics
            )
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
            view.setCues(indexes.flatMap { index ->
                cues[index].cues.map { cue ->
                    cue.buildUpon().fixSubtitleAlignment().applyStyle(style).build()
                }
            })
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
        original?.alpha = originalAlpha
        val view = overlay
        (view?.parent as? FrameLayout)?.removeView(view)
        overlay = null
        original = null
        fragment = null
        mediaPlayer = null
        host = null
        targetUrl = null
        cues = emptyList()
        timeline = null
        rendered = null
        lastOffset = Long.MIN_VALUE
    }
}
