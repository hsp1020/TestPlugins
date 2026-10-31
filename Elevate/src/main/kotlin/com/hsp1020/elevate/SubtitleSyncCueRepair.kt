package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.util.Log
import android.widget.ProgressBar
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.text.TextRenderer
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.CustomSubtitleDecoderFactory
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.SubtitleCue
import com.lagradost.cloudstream3.ui.player.SubtitleOffsetItemAdapter

/** Recover the native sync list from the active renderer if CloudStream loses its shared decoder reference. */
@OptIn(UnstableApi::class)
internal class SubtitleSyncCueRepair(private val application: Application) : Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private var fragment: GeneratorPlayer? = null
    private var player: Player? = null
    private var focusTree: ViewTreeObserver? = null
    private var offsetDialog: Dialog? = null
    private var repairedDialog: Dialog? = null
    private var loggedDialog: Dialog? = null
    private var loggedAdapter: SubtitleOffsetItemAdapter? = null
    private var loggedList: RecyclerView? = null
    private var scrollLogger: RecyclerView.OnScrollListener? = null
    private var layoutLogger: View.OnLayoutChangeListener? = null
    private val adapterLogger = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = logNative("adapter-changed")
        override fun onItemRangeInserted(start: Int, count: Int) = logNative("insert:$start:$count")
        override fun onItemRangeRemoved(start: Int, count: Int) = logNative("remove:$start:$count")
        override fun onItemRangeChanged(start: Int, count: Int) = logNative("change:$start:$count")
        override fun onItemRangeMoved(from: Int, to: Int, count: Int) = logNative("move:$from:$to:$count")
    }
    private fun logNative(reason: String) {
        val f = fragment ?: return
        val list = loggedList ?: return
        val adapter = loggedAdapter ?: return
        SubtitleSyncTrace.snapshot("native-$reason", f, adapter.immutableCurrentList, adapter, list)
        list.post { if (list === loggedList) SubtitleSyncTrace.visible("native-$reason", f, adapter, list) }
    }
    private fun clearNativeLogger() {
        loggedAdapter?.unregisterAdapterDataObserver(adapterLogger)
        scrollLogger?.let { listener -> loggedList?.removeOnScrollListener(listener) }
        layoutLogger?.let { listener -> loggedList?.removeOnLayoutChangeListener(listener) }
        if (loggedDialog != null) Log.d("ElevateSyncDiag", "native dialog-detach")
        layoutLogger = null
        loggedAdapter = null
        loggedList = null
        loggedDialog = null
        scrollLogger = null
    }
    private fun logNativeDialog(dialog: Dialog, list: RecyclerView, adapter: SubtitleOffsetItemAdapter) {
        if (loggedDialog !== dialog || loggedAdapter !== adapter) {
            clearNativeLogger()
            loggedDialog = dialog
            loggedAdapter = adapter
            loggedList = list
            adapter.registerAdapterDataObserver(adapterLogger)
            val scroll = object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    SubtitleSyncTrace.visible("native-scroll:$dx:$dy", fragment ?: return,
                        adapter, recyclerView)
                }
            }
            scrollLogger = scroll
            list.addOnScrollListener(scroll)
            val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                fragment?.let { f -> SubtitleSyncTrace.visible("native-layout", f, adapter, list) }
            }
            layoutLogger = layout
            list.addOnLayoutChangeListener(layout)
            Log.d("ElevateSyncDiag", "native dialog-open player=${player?.let(System::identityHashCode)} " +
                "dialog=${System.identityHashCode(dialog)} rows=${adapter.itemCount}")
        }
        if (loggedDialog === dialog) {
            val f = fragment
            if (f != null) {
                val coreCues = runCatching { f.player.getSubtitleCues().toList() }.getOrDefault(emptyList())
                SubtitleSyncTrace.snapshot("native-core", f, coreCues, null, list)
            }
        }
        logNative("inspect")
    }

    private val dialogField = runCatching {
        FullScreenPlayer::class.java.getDeclaredField("selectSubtitlesDialog")
            .apply { isAccessible = true }
    }.getOrNull()


    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        fragment?.view?.post { inspectDialog() }
    }
    private val cueListener = java.lang.reflect.Proxy.newProxyInstance(
        Player.Listener::class.java.classLoader,
        arrayOf(Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onCues", "onTracksChanged", "onIsPlayingChanged", "onPositionDiscontinuity" -> {
                if (offsetDialog?.isShowing == true) {
                    fragment?.view?.post {
                        if (offsetDialog?.isShowing == true) {
                            logNative("player-${method.name}")
                            inspectDialog()
                        }
                    }
                }
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ElevateSubtitleSyncCueRepairListener"
            else -> null
        }
    } as Player.Listener
    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
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
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        detach()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { candidate ->
                if (candidate.view != null) watch(candidate)
                if (candidate.isAdded) scan(candidate.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun watch(candidate: Fragment) {
        if (candidate !is GeneratorPlayer || !candidate.isAdded || candidate.view == null) return
        if (fragment !== candidate) {
            detach()
            fragment = candidate
            candidate.activity?.window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.let {
                focusTree = it
                it.addOnWindowFocusChangeListener(focusListener)
            }
        }
        bindPlayer()
        candidate.view?.post { inspectDialog() }
    }

    private fun bindPlayer() {
        val next = fragment?.playerView?.player
        if (next === player) return
        player?.removeListener(cueListener)
        player = next
        repairedDialog = null
        next?.addListener(cueListener)
    }

    companion object {
        private val decoderField = runCatching {
            TextRenderer::class.java.getDeclaredField("subtitleDecoderFactory")
                .apply { isAccessible = true }
        }.getOrNull()
        private val formatField = runCatching {
            TextRenderer::class.java.getDeclaredField("streamFormat")
                .apply { isAccessible = true }
        }.getOrNull()
        private val trackIdPrefix = Regex("^\\d+:")

        /** Same active-renderer fallback for the native and integrated sync lists. */
        internal fun cuesForPlayer(f: GeneratorPlayer): List<SubtitleCue> {
            val selected = f.player.getCurrentPreferredSubtitle() ?: return emptyList()
            val active = f.playerView?.player as? ExoPlayer ?: return emptyList()
            val native = runCatching { f.player.getSubtitleCues().toList() }.getOrNull()
            val cues = native?.takeIf { it.isNotEmpty() } ?: rendererCues(active, selected.getId())
            Log.d("ElevateSyncDiag", "cue-provider nativeRows=${native?.size} " +
                "returnedRows=${cues.size} fallback=${native.isNullOrEmpty()} " +
                "selectedHash=${selected.url.hashCode()} player=${System.identityHashCode(active)}")
            return cues
        }

        private fun rendererCues(exo: ExoPlayer, selectedId: String): List<SubtitleCue> {
            val field = decoderField ?: return emptyList()
            return runCatching {
                for (index in 0 until exo.rendererCount) {
                    val renderer = exo.getRenderer(index) as? TextRenderer ?: continue
                    val format = formatField?.get(renderer) as? Format
                    val excluded = format?.id?.replace(trackIdPrefix, "")?.let { it != selectedId } == true
                    Log.d("ElevateSyncDiag", "renderer-probe index=$index formatIdHash=${format?.id?.hashCode()} " +
                        "mime=${format?.sampleMimeType} excluded=$excluded")
                    if (excluded) continue
                    val factory = field.get(renderer) as? CustomSubtitleDecoderFactory ?: continue
                    val cues = factory.getSubtitleCues()?.toList().orEmpty()
                    Log.d("ElevateSyncDiag", "renderer-result index=$index cues=${cues.size}")
                    if (cues.isNotEmpty()) return@runCatching cues
                }
                emptyList()
            }.getOrDefault(emptyList())
        }
    }

    private fun inspectDialog() {
        val f = fragment ?: return
        bindPlayer()
        val dialog = runCatching { dialogField?.get(f) as? Dialog }.getOrNull()
            ?.takeIf { it.isShowing }
        if (dialog == null) {
            clearNativeLogger()
            offsetDialog = null
            repairedDialog = null
            return
        }
        offsetDialog = dialog
        val list = dialog.findViewById<RecyclerView>(R.id.subtitle_offset_recyclerview) ?: return
        val adapter = list.adapter as? SubtitleOffsetItemAdapter ?: return
        logNativeDialog(dialog, list, adapter)
        if (repairedDialog === dialog) return
        val notice = dialog.findViewById<View>(R.id.no_subtitles_loaded_notice) ?: return
        // Do not override native cues or an already populated StremioC refresh overlay.
        if (adapter.itemCount != 0 || notice.visibility != View.VISIBLE) {
            repairedDialog = dialog
            return
        }
        val selected = f.player.getCurrentPreferredSubtitle() ?: return
        val active = f.playerView?.player as? ExoPlayer ?: return
        val cues = cuesForPlayer(f)
        if (cues.isEmpty() || fragment !== f || f.playerView?.player !== active ||
            f.player.getCurrentPreferredSubtitle()?.url != selected.url) return

        repairedDialog = dialog
        Log.d("ElevateSyncDiag", "native fallback-submit count=${cues.size}")
        SubtitleSyncTrace.snapshot("native-repair-before-submit", f, cues, null, list)
        adapter.submitList(cues) {
            SubtitleSyncTrace.snapshot("native-repair-commit", f, cues, adapter, list)
            SubtitleSyncTrace.visible("native-repair-commit", f, adapter, list)
        }
        list.visibility = View.VISIBLE
        notice.visibility = View.GONE
        val position = (f.player.getPosition() ?: 0L) + f.player.getSubtitleOffset()
        val index = cues.indexOfLast { it.startTimeMs <= position }.coerceAtLeast(0)
        list.post {
            if (dialog.isShowing && fragment === f && list.adapter === adapter) {
                list.scrollToPosition(index)
            }
        }
    }

    private fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        player?.removeListener(cueListener)
        player = null
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree = null
        fragment = null
        offsetDialog = null
        repairedDialog = null
        clearNativeLogger()
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
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

// Diagnostic-only snapshots shared by both native and integrated sync windows.
// Never reorder cues or modify adapter state here.
internal object SubtitleSyncTrace {
    private const val TAG = "ElevateSyncDiag"

    fun snapshot(stage: String, f: GeneratorPlayer, cues: List<SubtitleCue>,
                 adapter: SubtitleOffsetItemAdapter?, list: RecyclerView?) {
        val pos = f.player.getPosition() ?: -1L
        val offset = f.player.getSubtitleOffset()
        val time = pos + offset
        val selected = f.player.getCurrentPreferredSubtitle()
        val media = f.playerView?.player
        val actual = adapter?.immutableCurrentList ?: cues
        var inversions = 0
        var badDuration = 0
        var overlap = 0
        val inversionSamples = ArrayList<Int>()
        val durationSamples = ArrayList<Int>()
        val matching = ArrayList<Int>()
        var activeCount = 0
        var minStart = Long.MAX_VALUE
        var maxEnd = Long.MIN_VALUE
        for (i in actual.indices) {
            val cue = actual[i]
            if (cue.startTimeMs < minStart) minStart = cue.startTimeMs
            if (cue.endTimeMs > maxEnd) maxEnd = cue.endTimeMs
            if (cue.durationMs <= 0L) {
                badDuration++
                if (durationSamples.size < 8) durationSamples.add(i)
            }
            if (i > 0) {
                if (actual[i - 1].startTimeMs > cue.startTimeMs) {
                    inversions++
                    if (inversionSamples.size < 8) inversionSamples.add(i)
                }
                if (actual[i - 1].endTimeMs > cue.startTimeMs) overlap++
            }
            if (time >= cue.startTimeMs && time < cue.endTimeMs) {
                activeCount++
                if (matching.size < 8) matching.add(i)
            }
        }
        val chosenIndex = if (actual.isNotEmpty()) adapter?.getLatestActiveItem(time)
            ?: actual.indexOfLast { it.startTimeMs <= time }.coerceAtLeast(0) else -1
        val chosen = actual.getOrNull(chosenIndex)
        val progressExpected = chosen != null && time >= chosen.startTimeMs && time < chosen.endTimeMs
        val fraction = if (chosen != null && progressExpected && chosen.durationMs > 0L)
            ((time - chosen.startTimeMs) * 1000f / chosen.durationMs).toInt() else -1
        Log.d(TAG, "$stage sourceCount=${cues.size} adapterCount=${actual.size} " +
            "player=${media?.let(System::identityHashCode)} playing=${media?.isPlaying} " +
            "state=${media?.playbackState} pos=$pos offset=$offset time=$time " +
            "selectedHash=${selected?.url?.hashCode()} selectedOrigin=${selected?.origin} " +
            "listShown=${list?.isShown} listSize=${list?.width}x${list?.height} " +
            "inversions=$inversions inversionSamples=$inversionSamples " +
            "badDuration=$badDuration badSamples=$durationSamples overlap=$overlap " +
            "range=${if (actual.isEmpty()) "empty" else "$minStart..$maxEnd"} " +
            "activeWindowCount=$activeCount activeIndices=$matching " +
            "latestIndex=$chosenIndex latestStart=${chosen?.startTimeMs} " +
            "latestEnd=${chosen?.endTimeMs} latestDuration=${chosen?.durationMs} " +
            "shouldShowProgress=$progressExpected predictedProgress=$fraction")
        val sampleIndices = listOf(0, 1, 2, chosenIndex - 1, chosenIndex,
            chosenIndex + 1, actual.lastIndex - 1, actual.lastIndex) +
            inversionSamples + durationSamples + matching
        val samples = sampleIndices.filter { it in actual.indices }.distinct().take(20)
        Log.d(TAG, "$stage samples=" + samples.joinToString(";") { i ->
            val cue = actual[i]
            "$i:${cue.startTimeMs}-${cue.endTimeMs}/${cue.durationMs}/chars=${cue.text.sumOf { it.length }}"
        })
    }

    fun visible(stage: String, f: GeneratorPlayer,
                adapter: SubtitleOffsetItemAdapter, list: RecyclerView) {
        val time = (f.player.getPosition() ?: -1L) + f.player.getSubtitleOffset()
        val cues = adapter.immutableCurrentList
        val latest = if (cues.isNotEmpty()) adapter.getLatestActiveItem(time) else -1
        val rows = ArrayList<String>()
        var visibleProgress = 0
        var expectedVisible = 0
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            val index = list.getChildAdapterPosition(row)
            val cue = cues.getOrNull(index)
            val progress = row.findViewById<ProgressBar>(R.id.subtitle_progress)
            val shouldShow = cue != null && time >= cue.startTimeMs && time < cue.endTimeMs
            if (shouldShow) expectedVisible++
            if (progress?.visibility == View.VISIBLE) visibleProgress++
            if (i < 8 || shouldShow || index == latest) {
                rows.add("$index:expected=$shouldShow/start=${cue?.startTimeMs}" +
                    "/end=${cue?.endTimeMs}/barVis=${progress?.visibility}" +
                    "/barShown=${progress?.isShown}/barValue=${progress?.progress}" +
                    "/barMax=${progress?.max}/size=${progress?.width}x${progress?.height}" +
                    "/rowShown=${row.isShown}")
            }
        }
        Log.d(TAG, "$stage view time=$time listShown=${list.isShown} " +
            "listAttached=${list.isAttachedToWindow} listSize=${list.width}x${list.height} " +
            "adapterCount=${adapter.itemCount} children=${list.childCount} " +
            "first=${if (list.childCount > 0) list.getChildAdapterPosition(list.getChildAt(0)) else -1} " +
            "latest=$latest expectedVisible=$expectedVisible barsVisible=$visibleProgress " +
            "rows=${rows.joinToString("|")}")
    }
}
