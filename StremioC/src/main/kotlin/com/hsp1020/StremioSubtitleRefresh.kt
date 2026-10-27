package com.hsp1020

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Tracks
import androidx.media3.common.MediaItem
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.player.CustomDecoder
import com.lagradost.cloudstream3.ui.player.CustomDecoder.Companion.fixSubtitleAlignment
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.SubtitleCue
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.applyStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap

/** Stremio provenance is intentionally kept outside CloudStream's SubtitleFile/SubtitleData. */
@OptIn(UnstableApi::class)
internal object StremioSubtitleRefreshRegistry {
    data class Prepared(val cues: List<SubtitleCue>, val boundaries: LongArray)

    data class Entry(
        val originalUrl: String,
        val addonUrl: String,
        val id: String?,
        val lang: String?,
        val listUrl: String?,
        val position: Int,
        val headers: Map<String, String>,
        val latestUrl: String = originalUrl
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
        if (previous == null || (previous.addonUrl == addonUrl &&
            previous.listUrl == null && listUrl != null &&
            (previous.id == null || previous.id == id))) {
            entries[url] = Entry(
                url, addonUrl, id?.takeIf { it.isNotBlank() }, lang, listUrl, position, headers
            )
        }
    }

    @Synchronized
    fun find(url: String): Entry? = entries[url]

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

    private fun readBytes(url: String, headers: Map<String, String>, max: Int): ByteArray {
        require(url.startsWith("https://", true) || url.startsWith("http://", true))
        val request = Request.Builder().url(url).cacheControl(cacheControl).apply {
            app.defaultHeaders.forEach { (name, value) -> header(name, value) }
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        noCacheClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP " + response.code }
            val body = response.body ?: error("Empty subtitle response")
            require(body.contentLength() <= max || body.contentLength() < 0)
            body.byteStream().use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(out.size() + count <= max) { "Subtitle response exceeds limit" }
                    out.write(buffer, 0, count)
                }
                return out.toByteArray()
            }
        }
    }

    /**
     * Refresh only the originating addon request and selected subtitle body.
     * A changed URL is matched by id; if id is absent we only accept the
     * original URL or the same position AND language, never an unrelated row.
     */
    suspend fun refresh(entry: Entry): Prepared = withContext(Dispatchers.IO) {
        var url = entry.latestUrl
        if (entry.listUrl != null) {
            val response = JSONObject(readBytes(entry.listUrl, emptyMap(), MAX_LIST_BYTES).toString(Charsets.UTF_8))
            val subtitles = response.optJSONArray("subtitles") ?: error("Missing subtitles")
            var selected: JSONObject? = null
            if (entry.id != null) {
                for (index in 0 until subtitles.length()) {
                    val candidate = subtitles.optJSONObject(index) ?: continue
                    if (candidate.optString("id") == entry.id &&
                        (entry.lang == null || entry.lang.equals(
                            candidate.optString("lang", candidate.optString("lang_code")), true))) {
                        selected = candidate
                        break
                    }
                }
            } else {
                for (index in 0 until subtitles.length()) {
                    val candidate = subtitles.optJSONObject(index) ?: continue
                    if (candidate.optString("url") == entry.latestUrl ||
                        candidate.optString("url") == entry.originalUrl) {
                        selected = candidate
                        break
                    }
                }
                // No ID or matching URL: only accept a unique language candidate.
                // Reusing an index would silently swap to another same-language subtitle.
                if (selected == null && entry.lang != null) {
                    val matches = (0 until subtitles.length()).mapNotNull { index ->
                        subtitles.optJSONObject(index)?.takeIf { candidate ->
                            entry.lang.equals(candidate.optString("lang", candidate.optString("lang_code")), true)
                        }
                    }
                    selected = matches.singleOrNull()
                }
            }
            url = selected?.optString("url")?.takeIf { it.isNotBlank() }
                ?: error("Subtitle no longer available")
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
            decoder.parseToLegacySubtitle(bytes, 0, bytes.size)
            val cues = decoder.currentSubtitleCues.filter {
                it.durationMs > 0 && it.text.any { text -> text.isNotBlank() }
            }.sortedBy { it.startTimeMs }
            val boundaries = cues.asSequence().flatMap {
                sequenceOf(it.startTimeMs, it.endTimeMs)
            }.distinct().sorted().toList().toLongArray()
            Prepared(cues, boundaries)
        }
        check(prepared.cues.isNotEmpty()) { "No valid SRT/VTT cues" }
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
    private var cues: List<SubtitleCue> = emptyList()
    private var boundaries: LongArray = longArrayOf()
    private var rendered: List<Int> = emptyList()
    private var lastOffset = Long.MIN_VALUE
    private val tick = Runnable { synchronize() }
    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { applyAppearance() }
    private val styleListener: (com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle) -> Unit = {
        overlay?.post { if (overlay != null) { applyAppearance(); rendered = emptyList(); synchronize() } }
    }

    private val playerListener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
        ) = synchronize()
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = synchronize()
        override fun onIsPlayingChanged(isPlaying: Boolean) = synchronize()
        override fun onPlaybackStateChanged(playbackState: Int) = synchronize()
        override fun onTracksChanged(tracks: Tracks) = synchronize()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = detach()
    }

    fun bind(candidate: GeneratorPlayer) {
        if (fragment == null) return
        val selected = candidate.player.getCurrentPreferredSubtitle()?.url
        if (fragment !== candidate || candidate.playerView?.player !== mediaPlayer ||
            targetUrl != selected || candidate.subView !== original) {
            detach()
        } else if (candidate.player.getSubtitleOffset() != lastOffset) {
            // Reuse the existing native-dialog controller tick for a change check.
            // No new recurring timer is introduced for offset settings.
            rendered = emptyList()
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
                boundaries = prepared.boundaries
                rendered = emptyList()
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
        boundaries = prepared.boundaries
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
        player.addListener(playerListener)
        applyAppearance()
        synchronize()
    }

    private fun applyAppearance() {
        val view = overlay ?: return
        val native = original ?: return
        val saved = SubtitlesFragment.getCurrentSavedStyle()
        SubtitlesFragment.setSubtitleViewStyle(view, saved, true)
        if (host?.isInPictureInPictureMode == true) {
            view.setPadding(view.paddingLeft, 0, view.paddingRight, view.paddingBottom)
            view.translationY = 0f
            view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.5f)
            view.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        } else {
            view.translationY = native.translationY
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
        val indexes = ArrayList<Int>(2)
        for (index in cues.indices) {
            val cue = cues[index]
            if (cue.startTimeMs <= time && time < cue.endTimeMs) indexes.add(index)
        }
        if (indexes != rendered) {
            val style = SubtitlesFragment.getCurrentSavedStyle()
            view.setCues(indexes.map { index ->
                Cue.Builder().setText(cues[index].text.joinToString("\n"))
                    .fixSubtitleAlignment().applyStyle(style).build()
            })
            rendered = indexes
        }
        if (!player.isPlaying) return
        val next = boundaries.binarySearch(time + 1).let { if (it < 0) -it - 1 else it }
        if (next >= boundaries.size) return
        val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
        val wait = ((boundaries[next] - time) / speed).toLong().coerceAtLeast(1L)
        main.postDelayed(tick, wait)
    }

    fun detach() {
        main.removeCallbacks(tick)
        mediaPlayer?.removeListener(playerListener)
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
        boundaries = longArrayOf()
        rendered = emptyList()
        lastOffset = Long.MIN_VALUE
    }
}
