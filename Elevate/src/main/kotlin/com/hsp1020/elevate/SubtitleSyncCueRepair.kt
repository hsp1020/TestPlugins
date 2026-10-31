package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.app.Dialog
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
            "onCues" -> {
                if (offsetDialog?.isShowing == true && repairedDialog !== offsetDialog) inspectDialog()
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
        PipLibraryNavigation.currentPlayerHost()?.let(::use)
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

        private fun invalidCue(cue: SubtitleCue) =
            cue.durationMs <= 0L && cue.text.all { it.isBlank() }

        private fun validCues(cues: List<SubtitleCue>): List<SubtitleCue> =
            if (cues.any(::invalidCue)) cues.filterNot(::invalidCue) else cues

        /** Same active-renderer fallback for the native and integrated sync lists. */
        internal fun cuesForPlayer(f: GeneratorPlayer): List<SubtitleCue> {
            val selected = f.player.getCurrentPreferredSubtitle() ?: return emptyList()
            val active = f.playerView?.player as? ExoPlayer ?: return emptyList()
            val cues = runCatching { f.player.getSubtitleCues().toList() }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: rendererCues(active, selected.getId())
            return validCues(cues)
        }

        private fun rendererCues(exo: ExoPlayer, selectedId: String): List<SubtitleCue> {
            val field = decoderField ?: return emptyList()
            return runCatching {
                for (index in 0 until exo.rendererCount) {
                    val renderer = exo.getRenderer(index) as? TextRenderer ?: continue
                    val format = formatField?.get(renderer) as? Format
                    if (format?.id?.replace(trackIdPrefix, "")?.let { it != selectedId } == true) continue
                    val factory = field.get(renderer) as? CustomSubtitleDecoderFactory ?: continue
                    val cues = factory.getSubtitleCues()?.toList().orEmpty()
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
            offsetDialog = null
            repairedDialog = null
            return
        }
        offsetDialog = dialog
        if (repairedDialog === dialog) return
        val list = dialog.findViewById<RecyclerView>(R.id.subtitle_offset_recyclerview) ?: return
        val adapter = list.adapter as? SubtitleOffsetItemAdapter ?: return
        val notice = dialog.findViewById<View>(R.id.no_subtitles_loaded_notice) ?: return
        // Preserve native cues unless there are invalid empty entries blocking active-row selection.
        val existing = adapter.immutableCurrentList
        if (existing.isNotEmpty()) {
            repairedDialog = dialog
            val cleaned = validCues(existing)
            if (cleaned !== existing) {
                adapter.submitList(cleaned) {
                    list.post {
                        if (dialog.isShowing && fragment === f && list.adapter === adapter) {
                            val time = (f.player.getPosition() ?: 0L) + f.player.getSubtitleOffset()
                            list.scrollToPosition(adapter.getLatestActiveItem(time))
                        }
                    }
                }
            }
            return
        }
        if (notice.visibility != View.VISIBLE) {
            repairedDialog = dialog
            return
        }
        val selected = f.player.getCurrentPreferredSubtitle() ?: return
        val active = f.playerView?.player as? ExoPlayer ?: return
        val cues = cuesForPlayer(f)
        if (cues.isEmpty() || fragment !== f || f.playerView?.player !== active ||
            f.player.getCurrentPreferredSubtitle()?.url != selected.url) return

        repairedDialog = dialog
        adapter.submitList(cues)
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
    }

    override fun onActivityResumed(activity: Activity) {
        PipLibraryNavigation.playerHost(activity)?.let(::use)
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
