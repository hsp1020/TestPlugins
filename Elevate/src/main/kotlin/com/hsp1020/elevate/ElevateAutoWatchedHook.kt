package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.isEpisodeBased
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.PlayerView
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper

/** Reuses native position events; never adds timers, polling or Media3 listeners. */
internal class ElevateAutoWatchedHook(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private val owners = LinkedHashSet<FragmentActivity>()
    private val sessions = LinkedHashMap<GeneratorPlayer, Session>()
    private val hostField = runCatching {
        AbstractPlayerFragment::class.java.getDeclaredField("playerHostView").apply {
            isAccessible = true
        }
    }.getOrNull()

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: android.view.View, savedInstanceState: Bundle?
        ) = bind(fragment)

        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) = bind(fragment)

        override fun onFragmentStopped(fm: FragmentManager, fragment: Fragment) {
            (fragment as? GeneratorPlayer)?.let { sessions[it]?.finish() }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            (fragment as? GeneratorPlayer)?.let { sessions.remove(it)?.detach() }
        }
    }

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? FragmentActivity)?.let(::use)
    }

    private fun use(activity: FragmentActivity) {
        if (activity !is MainActivity &&
            activity !is com.lagradost.cloudstream3.ui.player.DownloadedPlayerActivity) return
        if (!owners.add(activity)) return
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                if (fragment.view != null) bind(fragment)
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun bind(fragment: Fragment) {
        if (fragment !is GeneratorPlayer || sessions.containsKey(fragment)) return
        val host = runCatching { hostField?.get(fragment) as? PlayerView }.getOrNull() ?: return
        val delegate = host.callbacks ?: return
        sessions[fragment] = Session(fragment, host, delegate).also { host.callbacks = it }
    }

    private inner class Session(
        private val fragment: GeneratorPlayer,
        private val host: PlayerView,
        private val delegate: PlayerView.Callbacks
    ) : PlayerView.Callbacks by delegate {
        private val model = ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
        private var instance = -1
        private var id: Int? = null
        private var parentId: Int? = null
        private var next: Any? = null
        private var reached = false
        private var percentage = 0L
        private var replayReset = false

        override fun playerPositionChanged(position: Long, duration: Long) {
            // Keep CloudStream's progress updates, sync and preloading intact.
            delegate.playerPositionChanged(position, duration)
            if (duration <= 0L || position < 0L) return
            val state = model.state
            val generator = state.generatorState ?: return
            val episode = generator.meta as? ResultEpisode
            val file = generator.meta as? ExtractorUri
            val currentId = episode?.id ?: file?.id ?: return
            val currentParent = episode?.parentId ?: file?.parentId ?: return
            if (episode?.tvType?.isEpisodeBased() != true &&
                file?.tvType?.isEpisodeBased() != true) return
            if (instance != state.instance || id != currentId) {
                instance = state.instance
                id = currentId
                parentId = currentParent
                next = generator.nextMeta
                reached = false
                replayReset = false
                percentage = 0L
            }

            // Native clears a watched rewatch at its first position update.
            // Fix the immutable metadata snapshot once, preventing repeated clears.
            if (!replayReset && episode?.videoWatchState == VideoWatchState.Watched) {
                replayReset = true
                model.modifyState {
                    copy(generatorState = generatorState?.copy(
                        meta = episode.copy(videoWatchState = VideoWatchState.None)
                    ))
                }
            }

            percentage = position * 100L / duration
            if (!reached && percentage >= 80L) {
                reached = true
                if (DataStoreHelper.getVideoWatchState(currentId) != VideoWatchState.Watched) {
                    DataStoreHelper.setVideoWatchState(currentId, VideoWatchState.Watched)
                }
                if (percentage < 90L) saveNext()
            }
        }

        override fun playerStatusChanged() {
            delegate.playerStatusChanged()
            if (!fragment.player.getIsPlaying()) finish()
        }

        private fun saveNext() {
            val parent = parentId ?: return
            when (val target = next) {
                is ResultEpisode -> DataStoreHelper.setLastWatched(
                    parent, target.id, target.episode, target.season
                )
                is ExtractorUri -> DataStoreHelper.setLastWatched(
                    parent, target.id, target.episode, target.season, isFromDownload = true
                )
                else -> DataStoreHelper.removeLastWatched(parent)
            }
        }

        // Native's 90% resume logic may overwrite the 80% switch while playing.
        // Restore the final resume target on pause/exit, not every position event.
        fun finish() {
            if (reached && percentage in 80L..89L &&
                model.state.instance == instance && model.state.generatorState?.id == id) {
                saveNext()
            }
        }

        fun detach() {
            finish()
            if (host.callbacks === this) host.callbacks = delegate
        }
    }

    fun stop() {
        owners.forEach { activity ->
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragments)
        }
        owners.clear()
        sessions.values.toList().forEach { it.detach() }
        sessions.clear()
        application.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        (activity as? FragmentActivity)?.let(::use)
    }

    override fun onActivityPaused(activity: Activity) {
        sessions.forEach { (fragment, session) ->
            if (fragment.activity === activity) session.finish()
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is FragmentActivity && owners.remove(activity)) {
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragments)
            sessions.filterKeys { it.activity === activity }.keys.toList().forEach { fragment ->
                sessions.remove(fragment)?.detach()
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
