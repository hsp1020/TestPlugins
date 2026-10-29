package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ListView
import androidx.annotation.OptIn
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.SubtitleView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.ui.player.CustomDecoder
import com.lagradost.cloudstream3.ui.player.CustomDecoder.Companion.fixSubtitleAlignment
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.SubtitleCue
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOffsetItemAdapter
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.applyStyle
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Avoid a video reload when a newly listed external subtitle is selected.
 * Keep the actual CS3IPlayer (preview, compressor and existing hooks depend on its class).
 * Only the native subtitle selection status is temporarily virtualized, never the media source.
 */
@OptIn(UnstableApi::class)
internal class LateSubtitleNoReloadHook(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private companion object {
        const val TAG = "Elevate-LateSubtitle"
        const val MAX_BYTES = 4 * 1024 * 1024
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: FragmentActivity? = null
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var media: Player? = null
    private var dialog: Dialog? = null
    private var initialSource = -1
    private var virtual = emptySet<SubtitleData>()
    private var selectedVirtual: SubtitleData? = null
    private var job: Job? = null
    private var generation = 0
    private var overlay: LateSubtitleOverlay? = null
    private var offsetDialog: Dialog? = null
    private var removeObserver: (() -> Unit)? = null
    private var focusTree: ViewTreeObserver? = null
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        fragment?.view?.post { inspect() }
    }
    private val dialogField = runCatching {
        FullScreenPlayer::class.java.getDeclaredField("selectSourceDialog").apply { isAccessible = true }
    }.getOrNull()
    private val offsetField = runCatching {
        FullScreenPlayer::class.java.getDeclaredField("selectSubtitlesDialog").apply { isAccessible = true }
    }.getOrNull()
    private val helperField = runCatching {
        CS3IPlayer::class.java.getDeclaredField("subtitleHelper").apply { isAccessible = true }
    }.getOrNull()
    private val activeField = runCatching {
        PlayerSubtitleHelper::class.java.getDeclaredField("activeSubtitles").apply { isAccessible = true }
    }.getOrNull()
    private val requestedField = runCatching {
        CS3IPlayer::class.java.getDeclaredField("currentSubtitles").apply { isAccessible = true }
    }.getOrNull()
    private val selectedTracksField = runCatching {
        CS3IPlayer::class.java.getDeclaredField("playerSelectedSubtitleTracks").apply { isAccessible = true }
    }.getOrNull()

    private fun native(): CS3IPlayer? = fragment?.player as? CS3IPlayer
    private fun requested(): SubtitleData? =
        runCatching { requestedField?.get(native()) as? SubtitleData }.getOrNull()
    private fun helper(): PlayerSubtitleHelper? =
        runCatching { helperField?.get(native()) as? PlayerSubtitleHelper }.getOrNull()
    private fun active(helper: PlayerSubtitleHelper): Set<SubtitleData>? = runCatching {
        @Suppress("UNCHECKED_CAST")
        activeField?.get(helper) as? Set<SubtitleData>
    }.getOrNull()
    private fun actualTrack(sub: SubtitleData): Boolean =
        media?.currentTracks?.groups?.any { group ->
            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { i ->
                group.getTrackFormat(i).id?.replace(Regex("""^\d+:"""), "") == sub.getId()
            }
        } == true
    private fun eligible(sub: SubtitleData): Boolean {
        if (sub.origin != SubtitleOrigin.URL) return false
        val url = sub.getFixedUrl()
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return false
        val path = url.substringBefore('?').lowercase()
        return !(path.endsWith(".sup") || path.endsWith(".idx") ||
            path.endsWith(".sub") || path.endsWith(".zip") || path.endsWith(".rar"))
    }

    private fun reconcile(keep: SubtitleData? = null, includeCandidates: Boolean = false) {
        val f = fragment ?: return
        val h = helper() ?: return
        val current = active(h) ?: return
        val candidates = if (includeCandidates) model?.state?.subtitles.orEmpty()
            .filter { eligible(it) && !actualTrack(it) }.toSet() else emptySet()
        val needed = candidates + listOfNotNull(keep).filter { eligible(it) && !actualTrack(it) }
        val updated = (current - virtual) + needed
        virtual = needed.filterNot { it in current - virtual }.toSet()
        if (updated != current && f.playerView?.player === media) {
            h.setActiveSubtitles(updated)
            Log.i(TAG, "virtualTracks=" + virtual.size + " actualTracks=" + (updated.size - virtual.size))
        }
    }

    private fun updateSelectedTrackCache(sub: SubtitleData?) {
        val player = native() ?: return
        // CloudStream's getCurrentPreferredSubtitle() reads this cache, not currentSubtitles.
        // The synthetic ID is confined to an external cue overlay selection.
        runCatching {
            selectedTracksField?.set(
                player, if (sub == null) emptyList<Pair<String, Boolean>>()
                else listOf(sub.getId() to true)
            )
        }
    }

    private fun bindMedia() {
        val next = fragment?.playerView?.player
        if (media === next) return
        media?.removeListener(playerListener)
        clearSelection()
        virtual = emptySet() // native loadOnlinePlayer rebuilds its own active subtitle set
        media = next
        next?.addListener(playerListener)
    }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player !== media) return
            if (events.contains(Player.EVENT_TRACKS_CHANGED)) {
                selectedVirtual?.let(::updateSelectedTrackCache)
            }
            overlay?.render()
        }
    }

    private fun clearSelection() {
        generation++
        job?.cancel()
        job = null
        overlay?.detach()
        overlay = null
        selectedVirtual = null
        offsetDialog = null
    }

    private fun inspect() {
        val f = fragment ?: return
        if (f.player !is CS3IPlayer) return
        bindMedia()
        if (media == null) return
        selectedVirtual?.let { selected ->
            val fresh = model?.state?.subtitles?.firstOrNull {
                it.origin == SubtitleOrigin.URL && it.url == selected.url
            }
            if (fresh != null && fresh != selected && requested()?.url == selected.url) {
                runCatching { requestedField?.set(native(), fresh) }
                selectedVirtual = fresh
                updateSelectedTrackCache(fresh)
                reconcile(keep = fresh, includeCandidates = dialog != null)
            }
        }
        val shown = runCatching { dialogField?.get(f) as? Dialog }.getOrNull()
            ?.takeIf { it.isShowing }
        if (shown != null && shown !== dialog) {
            dialog = shown
            initialSource = shown.findViewById<ListView>(R.id.sort_providers)?.checkedItemPosition ?: -1
            shown.window?.decorView?.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = Unit
                    override fun onViewDetachedFromWindow(view: View) {
                        view.removeOnAttachStateChangeListener(this)
                        f.view?.post { if (dialog === shown) finishDialog(shown) }
                    }
                }
            )
        }
        if (shown != null) {
            reconcile(keep = selectedVirtual, includeCandidates = true)
        } else if (dialog != null) {
            finishDialog(dialog ?: return)
        }
        val offset = runCatching { offsetField?.get(f) as? Dialog }.getOrNull()
            ?.takeIf { it.isShowing }
        if (offset != null && offset !== offsetDialog) {
            offsetDialog = offset
            overlay?.showSyncCues(offset, f)
        } else if (offset == null) {
            offsetDialog = null
        }
    }

    private fun finishDialog(closing: Dialog) {
        if (closing !== dialog) return
        dialog = null
        val changedSource = closing.findViewById<ListView>(R.id.sort_providers)
            ?.checkedItemPosition?.let { initialSource >= 0 && it != initialSource } ?: false
        initialSource = -1
        val sub = if (!changedSource) requested() else null
        val late = sub?.takeIf { it in virtual && !actualTrack(it) }
        reconcile(keep = late)
        if (late == null) {
            clearSelection()
            // Restore native track cache if our overlay previously owned it.
            updateSelectedTrackCache(sub)
            return
        }
        if (selectedVirtual?.url == late.url && overlay != null) return
        clearSelection()
        selectedVirtual = late
        updateSelectedTrackCache(late)
        val f = fragment ?: return
        val exo = media ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        Log.i(TAG, "selected late external subtitle; preserving ExoPlayer, id=" + late.getId().hashCode())
        val currentGeneration = generation
        val instance = model?.state?.instance
        job = scope.launch {
            try {
                val parsed = load(late)
                if (currentGeneration != generation || fragment !== f ||
                    f.playerView?.player !== exo || model?.state?.instance != instance ||
                    requested()?.url != late.url) return@launch
                overlay = LateSubtitleOverlay(f, exo, parsed).also { it.attach() }
                Log.i(TAG, "overlay attached; cueCount=" + parsed.cues.size +
                    " player=" + System.identityHashCode(exo))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (currentGeneration == generation && fragment === f &&
                    f.playerView?.player === exo && requested()?.url == late.url) {
                    Log.w(TAG, "Late subtitle parsing failed; using native reload", error)
                    reconcile()
                    selectedVirtual = null
                    f.context?.let { native()?.reloadPlayer(it) }
                }
            }
        }
    }

    private suspend fun load(sub: SubtitleData): LateParsed = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(sub.getFixedUrl()).apply {
            app.defaultHeaders.forEach { (key, value) -> header(key, value) }
            sub.headers.forEach { (key, value) -> header(key, value) }
        }.build()
        val bytes = suspendCancellableCoroutine<ByteArray> { continuation ->
            val call = app.baseClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val content = response.use {
                            check(it.isSuccessful) { "HTTP " + it.code }
                            val body = it.body ?: error("Empty subtitle body")
                            check(body.contentLength() < 0 || body.contentLength() <= MAX_BYTES)
                            body.byteStream().use { input ->
                                val out = ByteArrayOutputStream()
                                val chunk = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("Cancelled")
                                    val count = input.read(chunk)
                                    if (count < 0) break
                                    check(out.size() + count <= MAX_BYTES) { "Subtitle exceeds size limit" }
                                    out.write(chunk, 0, count)
                                }
                                out.toByteArray()
                            }
                        }
                        if (continuation.isActive) continuation.resume(content)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
        check(bytes.isNotEmpty()) { "Empty subtitle" }
        val preview = bytes.copyOfRange(0, minOf(256, bytes.size)).toString(Charsets.UTF_8)
        check(!preview.contains("<html", true)) { "Unexpected HTML subtitle" }
        val text = preview.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        val mime = when {
            text.startsWith("[Script Info]", true) || text.startsWith("[V4+ Styles]", true) ->
                MimeTypes.TEXT_SSA
            text.startsWith("WEBVTT", true) -> MimeTypes.TEXT_VTT
            text.startsWith("<?xml", true) && text.contains("<tt") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        withContext(Dispatchers.Default) {
            val decoder = CustomDecoder(Format.Builder().setSampleMimeType(mime).build())
            val cues = ArrayList<LateCue>()
            decoder.parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { output ->
                val time = decoder.currentSubtitleCues.lastOrNull()
                if (time != null && time.durationMs > 0 && output.cues.any {
                        !it.text.isNullOrBlank() || it.bitmap != null
                    }) {
                    cues.add(LateCue(time.startTimeMs, time.endTimeMs, output.cues.toList()))
                }
            }
            ensureActive()
            check(cues.isNotEmpty()) { "Unsupported or empty subtitle format" }
            LateParsed(cues.sortedBy { it.start }, mime == MimeTypes.TEXT_SSA)
        }
    }

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, state: Bundle?
        ) = watch(f)
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) = watch(f)
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) = detach(f)
    }
    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }
    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        detach()
        host = null
        scope.cancel()
    }
    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        detach()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { f ->
                if (f.view != null) watch(f)
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }
    private fun watch(candidate: Fragment) {
        if (candidate !is GeneratorPlayer || !candidate.isAdded || candidate.view == null) return
        if (fragment === candidate) { inspect(); return }
        detach()
        fragment = candidate
        model = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
        val vm = model ?: return
        val observer = Observer<VideoLive<Set<SubtitleData>>> { event ->
            if (event?.instance == vm.state.instance) candidate.view?.post { inspect() }
        }
        vm.currentSubtitles.observe(candidate.viewLifecycleOwner, observer)
        removeObserver = { vm.currentSubtitles.removeObserver(observer) }
        candidate.activity?.window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.let {
            focusTree = it
            it.addOnWindowFocusChangeListener(focusListener)
        }
        candidate.view?.post { inspect() }
    }
    private fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree = null
        removeObserver?.invoke()
        removeObserver = null
        media?.removeListener(playerListener)
        clearSelection()
        reconcile()
        media = null
        virtual = emptySet()
        dialog = null
        fragment = null
        model = null
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) {
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
            detach()
            host = null
        }
    }
    override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityPaused(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
}

@OptIn(UnstableApi::class)
private data class LateCue(val start: Long, val end: Long, val cues: List<Cue>)
@OptIn(UnstableApi::class)
private class LateParsed(val cues: List<LateCue>, val preserveAss: Boolean) {
    private val boundaries = cues.flatMap { listOf(it.start, it.end) }.distinct().sorted()
    private val prefixEnds = LongArray(cues.size).apply {
        var maximum = Long.MIN_VALUE
        for (i in indices) {
            maximum = maxOf(maximum, cues[i].end)
            this[i] = maximum
        }
    }
    fun activeAt(t: Long): List<Int> {
        var lo = 0
        var hi = prefixEnds.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (prefixEnds[mid] <= t) lo = mid + 1 else hi = mid
        }
        val found = ArrayList<Int>(2)
        for (i in lo until cues.size) {
            if (cues[i].start > t) break
            if (t < cues[i].end) found.add(i)
        }
        return found
    }
    fun nextAfter(t: Long): Long? {
        var lo = 0
        var hi = boundaries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (boundaries[mid] <= t) lo = mid + 1 else hi = mid
        }
        return boundaries.getOrNull(lo)
    }
}

@OptIn(UnstableApi::class)
private class LateSubtitleOverlay(
    private val fragment: GeneratorPlayer, private val player: Player,
    private val parsed: LateParsed
) {
    private val main = Handler(Looper.getMainLooper())
    private var native: SubtitleView? = null
    private var original: View? = null
    private var originalAlpha = 1f
    private var view: SubtitleView? = null
    private var rendered: List<Int>? = null
    private val tick = Runnable { render() }
    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { applyAppearance() }
    private val styleListener: (SaveCaptionStyle) -> Unit = {
        view?.post { if (view != null) { applyAppearance(); rendered = null; render() } }
    }
    private var host: FragmentActivity? = null
    fun attach() {
        val parent = fragment.subView ?: error("No native subtitle view")
        val output = parent.getChildAt(0) ?: error("No native subtitle output")
        native = parent
        original = output
        originalAlpha = output.alpha
        val overlay = SubtitleView(parent.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag = "ElevateLateSubtitleOverlay"
        }
        val refreshedOverlayActive = (0 until parent.childCount).any {
            val child = parent.getChildAt(it)
            child is SubtitleView && child.tag != "ElevateLateSubtitleOverlay"
        }
        parent.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        view = overlay
        if (refreshedOverlayActive) overlay.alpha = 0f
        output.alpha = 0f
        host = fragment.activity as? FragmentActivity
        host?.addOnPictureInPictureModeChangedListener(pipListener)
        SubtitlesFragment.applyStyleEvent += styleListener
        applyAppearance()
        render()
    }
    private fun applyAppearance() {
        val current = view ?: return
        SubtitlesFragment.setSubtitleViewStyle(
            current, SubtitlesFragment.getCurrentSavedStyle(), true
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
            host?.isInPictureInPictureMode == true) {
            current.translationY = 0f
            current.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.5f)
            current.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        }
    }
    fun render() {
        main.removeCallbacks(tick)
        val output = view ?: return
        if (fragment.playerView?.player !== player) { detach(); return }
        val time = player.currentPosition + fragment.player.getSubtitleOffset()
        val ids = parsed.activeAt(time)
        if (ids != rendered) {
            val style = SubtitlesFragment.getCurrentSavedStyle()
            val cues = ids.flatMap { parsed.cues[it].cues }
            val displayed = if (!parsed.preserveAss && cues.size > 1 &&
                cues.all { it.bitmap == null && !it.text.isNullOrBlank() }) {
                val text = android.text.SpannableStringBuilder()
                cues.forEachIndexed { index, cue ->
                    if (index > 0) text.append('\n')
                    text.append(cue.text)
                }
                listOf(cues.first().buildUpon().setText(text)
                    .fixSubtitleAlignment().applyStyle(style).build())
            } else cues.map { cue ->
                cue.buildUpon().fixSubtitleAlignment().applyStyle(style).apply {
                    if (parsed.preserveAss) {
                        setLine(cue.line, cue.lineType)
                        setLineAnchor(cue.lineAnchor)
                        setPosition(cue.position)
                        setPositionAnchor(cue.positionAnchor)
                        setTextAlignment(cue.textAlignment)
                    }
                }.build()
            }
            output.setCues(displayed)
            rendered = ids
        }
        if (!player.isPlaying) return
        val next = parsed.nextAfter(time) ?: return
        val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
        main.postDelayed(tick, ((next - time) / speed).toLong().coerceAtLeast(1L))
    }
    fun showSyncCues(dialog: Dialog, f: GeneratorPlayer) {
        val list = dialog.findViewById<RecyclerView>(R.id.subtitle_offset_recyclerview) ?: return
        val adapter = list.adapter as? SubtitleOffsetItemAdapter ?: return
        val cues = parsed.cues.mapNotNull { item ->
            val lines = item.cues.mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }
            if (lines.isEmpty()) null
            else SubtitleCue(item.start, (item.end - item.start).coerceAtLeast(1), lines)
        }
        if (cues.isEmpty()) return
        adapter.submitList(cues)
        list.visibility = View.VISIBLE
        dialog.findViewById<View>(R.id.no_subtitles_loaded_notice)?.visibility = View.GONE
        val t = (f.player.getPosition() ?: 0) + f.player.getSubtitleOffset()
        list.post { if (dialog.isShowing && list.adapter === adapter) {
            list.scrollToPosition(cues.indexOfLast { it.startTimeMs <= t }.coerceAtLeast(0))
        } }
    }
    fun detach() {
        main.removeCallbacks(tick)
        host?.removeOnPictureInPictureModeChangedListener(pipListener)
        if (view != null) SubtitlesFragment.applyStyleEvent -= styleListener
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        original?.alpha = originalAlpha
        view = null
        native = null
        original = null
        host = null
        rendered = null
    }
}
