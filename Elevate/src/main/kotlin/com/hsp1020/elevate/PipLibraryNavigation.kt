package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.os.Bundle
import android.os.Build
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.DownloadedPlayerActivity
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer

/** Redirect new playback without rebuilding CloudStream's VideoGenerator. */
internal class PipLibraryNavigation(private val app: Application) : Application.ActivityLifecycleCallbacks {
    companion object {
        private const val PLAYER_RESTORE_KEY = "elevate_pip_player_restored"
        private const val PLAYER_UUID_KEY = "elevate_pip_player_uuid"
        private var current: PipLibraryNavigation? = null

        fun currentPlayerHost(): FragmentActivity? =
            current?.playerActivity ?: (CommonActivity.activity as? FragmentActivity)
                ?.takeIf { it is MainActivity || it is DownloadedPlayerActivity }

        fun playerHost(activity: Activity): FragmentActivity? = when {
            current?.playerActivity === activity -> activity as? FragmentActivity
            current?.playerActivity == null && activity is MainActivity -> activity
            current?.playerActivity == null && activity is DownloadedPlayerActivity -> activity
            else -> null
        }

        fun isPlayerActivity(activity: Activity): Boolean =
            activity is DownloadedPlayerActivity &&
                (current?.playerActivity == null || current?.playerActivity === activity)

        fun switchLiveInPip(card: SearchResponse): Boolean =
            current?.switchLive(card) == true
    }

    private var enabled = false
    private var registered = false
    private var mainActivity: FragmentActivity? = null
    private var controller: NavController? = null
    private var playerActivity: DownloadedPlayerActivity? = null
    private var pending: Bundle? = null
    private var redirecting = false
    private var restoreNative = false
    private var nextPipVideo: Bundle? = null
    private var switchPosted = false
    private var switching = false
    private var wasPlaying = false
    private var wasInPip = false
    private var closing = false
    private var activeUuid: String? = null
    private var liveSwitchSequence = 0
    private var liveSwitchJob: Job? = null
    private var playerNavigation: NavController? = null
    private var exitBackBound = false

    // Temporary diagnostics share the YouTube tag and monotonic clock.
    private fun trace(event: String, activity: Activity? = playerActivity ?: mainActivity) {
        Log.i("PopupTrace", "EL ms=${android.os.SystemClock.uptimeMillis()} $event" +
            " act=${activity?.javaClass?.simpleName}#${System.identityHashCode(activity)}" +
            " main#${System.identityHashCode(mainActivity)} player#${System.identityHashCode(playerActivity)}" +
            " mainNav=${controller?.currentDestination?.id} playerNav=${playerNavigation?.currentDestination?.id}" +
            " enabled=$enabled pending=${pending != null} redirecting=$redirecting restoreNative=$restoreNative" +
            " closing=$closing exitBound=$exitBackBound wasPlaying=$wasPlaying" +
            " wasPip=$wasInPip inPip=${(activity?.let { it.inPip() })}" +
            " finish=${activity?.isFinishing} config=${activity?.isChangingConfigurations}" +
            " destroyed=${activity?.isDestroyed} orientation=${activity?.resources?.configuration?.orientation}")
    }

    private fun Activity.inPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    /** The native nav layout reads system orientation, which may remain portrait
     * after Shorts PiP although MainActivity is still landscape. */
    private fun restoreLandscapeNavigation() {
        val activity = mainActivity ?: return
        if (playerActivity?.inPip() != true ||
            activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return
        activity.window.decorView.post {
            if (mainActivity !== activity || playerActivity?.inPip() != true ||
                activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return@post
            val bottom = activity.findViewById<View>(R.id.nav_view) ?: return@post
            val rail = activity.findViewById<View>(R.id.nav_rail_view) ?: return@post
            // Do not expose navigation if the current destination hides both.
            if (bottom.visibility == View.VISIBLE && rail.visibility != View.VISIBLE) {
                bottom.visibility = View.GONE
                rail.visibility = View.VISIBLE
            }
        }
    }

    // Shared through the MainActivity decor tag; YouTube and Elevate have
    // independent plugin class loaders, but both use the native Bundle type.
    private val youtubePipBridge: (Bundle) -> Boolean = { args ->
        trace("PIP_BRIDGE_ATTEMPT")
        val pip = playerActivity?.takeIf {
            enabled && it.inPip() && !it.isFinishing && !it.isDestroyed
        }
        if (pip == null || args.getString("uuid").isNullOrBlank() ||
            !args.containsKey("index")) {
            trace("PIP_BRIDGE_REJECT")
            false
        } else {
            trace("PIP_BRIDGE_ACCEPT")
            nextPipVideo?.getString("uuid")?.let(::forgetPlaceholder)
            nextPipVideo = Bundle(args)
            schedulePipSwitch(pip)
            true
        }
    }

    private fun detachYoutubeBridge(activity: FragmentActivity?) {
        val decor = activity?.window?.decorView ?: return
        if (decor.getTag(R.id.global_to_navigation_player) === youtubePipBridge) {
            decor.setTag(R.id.global_to_navigation_player, null)
        }
    }

    private val destinations = NavController.OnDestinationChangedListener {
            nav: NavController, destination: NavDestination, args: Bundle? ->
        trace("MAIN_NAV destination=${destination.id}")
        if (destination.id != R.id.navigation_player) restoreLandscapeNavigation()
        if (restoreNative) {
            restoreNative = false
            return@OnDestinationChangedListener
        }
        if (!enabled || redirecting || pending != null ||
            destination.id != R.id.navigation_player) return@OnDestinationChangedListener
        val origin = mainActivity ?: return@OnDestinationChangedListener
        val bundle = args?.takeIf {
            !it.getString("uuid").isNullOrEmpty() && it.containsKey("index")
        } ?: return@OnDestinationChangedListener
        redirecting = true
        trace("REDIRECT_BEGIN")
        try {
            val pip = playerActivity?.takeIf {
                it.inPip() && !it.isFinishing && !it.isDestroyed
            }
            val popped = nav.popBackStack()
            trace("REDIRECT_POP success=$popped destination=${nav.currentDestination?.id}")
            if (!popped) return@OnDestinationChangedListener
            if (pip != null) {
                // Do not bring the PiP activity to the foreground.
                nextPipVideo?.getString("uuid")?.let(::forgetPlaceholder)
                nextPipVideo = Bundle(bundle)
                schedulePipSwitch(pip)
            } else {
                pending = Bundle(bundle)
                val uuid = bundle.getString("uuid")
                trace("EXTERNAL_LAUNCH_BEGIN", origin)
                // A non-network stub creates the native player destination.
                origin.startActivity(Intent(origin, DownloadedPlayerActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, "https://127.0.0.1/__elevate_pip/$uuid")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                trace("EXTERNAL_LAUNCH_DISPATCHED", origin)
            }
        } catch (error: Exception) {
            trace("REDIRECT_ERROR type=${error.javaClass.simpleName}")
            pending = null
            Log.e("ElevatePiP", "Player routing failed", error)
            if (playerActivity?.inPip() != true) {
                origin.window.decorView.post {
                    if (!origin.isFinishing && !origin.isDestroyed) {
                        restoreNative = true
                        nav.navigate(R.id.global_to_navigation_player, bundle)
                    }
                }
            }
        } finally {
            redirecting = false
            trace("REDIRECT_END")
        }
    }

    /**
     * Load a live card without navigating through ResultFragment while a PiP
     * player is active. The existing player destination hook performs the switch.
     */
    private fun switchLive(card: SearchResponse): Boolean {
        if (!enabled) return false
        val pip = playerActivity?.takeIf {
            it.inPip() && !it.isFinishing && !it.isDestroyed
        } ?: return false
        val origin = mainActivity ?: return false
        val nav = controller ?: return false
        val api = getApiFromNameNull(card.apiName) ?: return false
        val selection = ++liveSwitchSequence
        liveSwitchJob?.cancel()
        liveSwitchJob = origin.lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { api.load(card.url) }
                    ?: return@launch
                if (selection != liveSwitchSequence || playerActivity !== pip ||
                    !pip.inPip() || controller !== nav || !enabled) return@launch
                val parentId = page.uniqueUrl.replace(api.mainUrl, "")
                    .replace("/", "").hashCode()
                val episodes = when (page) {
                    is TvSeriesLoadResponse -> page.episodes.sortedBy {
                        (it.season?.times(10_000) ?: 0) + (it.episode ?: 0)
                    }.mapIndexed { index, episode ->
                        val number = episode.episode ?: index + 1
                        buildResultEpisode(
                            headerName = page.name,
                            name = episode.name,
                            poster = episode.posterUrl,
                            episode = number,
                            seasonIndex = episode.season,
                            season = episode.season,
                            data = episode.data,
                            apiName = page.apiName,
                            id = parentId + (episode.season?.times(100_000) ?: 0) + number + 1,
                            index = index,
                            rating = episode.score,
                            description = episode.description,
                            isFiller = null,
                            tvType = page.type,
                            parentId = parentId,
                            airDate = episode.date,
                            runTime = episode.runTime,
                        )
                    }
                    is MovieLoadResponse, is LiveStreamLoadResponse -> {
                        val data = when (page) {
                            is MovieLoadResponse -> page.dataUrl
                            is LiveStreamLoadResponse -> page.dataUrl
                            else -> ""
                        }
                        listOf(buildResultEpisode(
                            headerName = page.name,
                            name = page.name,
                            poster = null,
                            episode = 0,
                            seasonIndex = null,
                            season = null,
                            data = data,
                            apiName = page.apiName,
                            id = parentId,
                            index = 0,
                            rating = null,
                            description = null,
                            isFiller = null,
                            tvType = page.type,
                            parentId = parentId,
                        ))
                    }
                    else -> emptyList()
                }
                if (episodes.isEmpty()) return@launch
                val args = GeneratorPlayer.newInstance(
                    RepoLinkGenerator(episodes, page = page), 0, HashMap(page.syncData)
                )
                try {
                    nav.navigate(R.id.global_to_navigation_player, args)
                } catch (error: Exception) {
                    forgetPlaceholder(args.getString("uuid"))
                    throw error
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.e("ElevatePiP", "Live PiP switch failed", error)
            }
        }
        return true
    }

    // Event-based; multiple selections before the next UI turn become one load.
    private fun schedulePipSwitch(activity: DownloadedPlayerActivity) {
        if (switchPosted || switching) return
        switchPosted = true
        activity.window.decorView.post {
            switchPosted = false
            if (playerActivity !== activity || activity.isFinishing || activity.isDestroyed) return@post
            val manager = activity.supportFragmentManager
            if (manager.isStateSaved) return@post // Retry on Activity start/resume.
            val nav = (manager.findFragmentById(R.id.nav_host_fragment)
                as? NavHostFragment)?.navController ?: return@post
            if (nav.currentDestination?.id != R.id.navigation_player) return@post
            val next = nextPipVideo ?: return@post
            val old = findPlayer(manager) ?: return@post
            val previous = old.arguments?.getString("uuid")
            switching = true
            try {
                old.releasePlayer() // Stops the decoder before any replacement starts.
                nextPipVideo = null
                nav.navigate(
                    R.id.global_to_navigation_player, next,
                    NavOptions.Builder()
                        .setPopUpTo(R.id.navigation_player, true, false)
                        .setEnterAnim(0).setExitAnim(0)
                        .setPopEnterAnim(0).setPopExitAnim(0).build()
                )
                if (previous != next.getString("uuid")) forgetPlaceholder(previous)
            } catch (error: Exception) {
                switching = false
                nextPipVideo = next
                Log.e("ElevatePiP", "PiP video switch failed", error)
            }
        }
    }

    private fun findPlayer(manager: FragmentManager): GeneratorPlayer? {
        for (fragment in manager.fragments) {
            if (fragment is GeneratorPlayer && fragment.isAdded && !fragment.isRemoving) return fragment
            if (fragment.isAdded) {
                findPlayer(fragment.childFragmentManager)?.let { return it }
            }
        }
        return null
    }

    private val exitBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            trace("PLAYER_BACK_CALLBACK")
            playerActivity?.let(::closePlayer)
        }
    }

    private fun closePlayer(activity: DownloadedPlayerActivity) {
        trace("CLOSE_PLAYER_BEGIN", activity)
        if (closing || playerActivity !== activity) {
            trace("CLOSE_PLAYER_SKIPPED", activity)
            return
        }
        closing = true
        nextPipVideo?.getString("uuid")?.let(::forgetPlaceholder)
        nextPipVideo = null
        findPlayer(activity.supportFragmentManager)?.let { player ->
            runCatching { player.releasePlayer() }
                .onFailure { Log.e("ElevatePiP", "Player release failed", it) }
        }
        forgetPlaceholder(activeUuid)
        activeUuid = null
        trace("FINISH_AND_REMOVE_TASK_BEGIN", activity)
        activity.finishAndRemoveTask()
        trace("FINISH_AND_REMOVE_TASK_DISPATCHED", activity)
    }

    private val playerDestination = NavController.OnDestinationChangedListener {
            _: NavController, destination: NavDestination, _: Bundle? ->
        trace("EXTERNAL_NAV destination=${destination.id}")
        if (switching) return@OnDestinationChangedListener
        if (destination.id == R.id.navigation_player) wasPlaying = true
        else if (wasPlaying) playerActivity?.let(::closePlayer)
    }

    private val playerFragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentPreAttached(fm: FragmentManager, f: Fragment, context: Context) {
            if (f !is GeneratorPlayer) return
            trace("PLAYER_FRAGMENT_PRE_ATTACH fragment#${System.identityHashCode(f)}")
            val activity = playerActivity ?: return
            val latest = pending ?: if (switching) nextPipVideo else null
            val oldUuid = f.arguments?.getString("uuid")
            try {
                if (latest != null) {
                    f.arguments = Bundle(latest)
                    if (oldUuid != latest.getString("uuid")) forgetPlaceholder(oldUuid)
                }
                activeUuid = f.arguments?.getString("uuid")
                pending = null
                nextPipVideo = null
                switching = false
            } catch (error: Exception) {
                pending = null
                switching = false
                Log.e("ElevatePiP", "Generator handoff failed", error)
                activity.finish()
            }
        }

        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, view: View, savedInstanceState: Bundle?) {
            if (f is GeneratorPlayer) trace("PLAYER_FRAGMENT_VIEW_CREATED fragment#${System.identityHashCode(f)}")
        }

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (f is GeneratorPlayer) trace("PLAYER_FRAGMENT_RESUMED fragment#${System.identityHashCode(f)}")
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            if (f is GeneratorPlayer) trace("PLAYER_FRAGMENT_PAUSED fragment#${System.identityHashCode(f)}")
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f is GeneratorPlayer) trace("PLAYER_FRAGMENT_VIEW_DESTROYED fragment#${System.identityHashCode(f)}")
        }

        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            if (f is GeneratorPlayer) trace("PLAYER_FRAGMENT_DESTROYED fragment#${System.identityHashCode(f)}")
        }
    }

    private val pipChanged = Consumer<PictureInPictureModeChangedInfo> { change ->
        trace("PIP_CHANGED inPip=${change.isInPictureInPictureMode}")
        val activity = playerActivity ?: return@Consumer
        if (!change.isInPictureInPictureMode) return@Consumer
        wasInPip = true
        activity.window.decorView.post {
            if (playerActivity !== activity || !activity.isInPictureInPictureMode) return@post
            runCatching {
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                })
            }.onFailure { Log.e("ElevatePiP", "Cannot show library", it) }
        }
    }

    private fun forgetPlaceholder(uuid: String?) {
        if (uuid.isNullOrBlank()) return
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val cache = GeneratorPlayer::class.java.getDeclaredField("generators")
                .apply { isAccessible = true }.get(null) as? MutableMap<String, *>
            cache?.remove(uuid)
        }
    }

    fun start() {
        trace("START")
        if (enabled) return
        enabled = true
        current = this
        if (!registered) {
            app.registerActivityLifecycleCallbacks(this)
            registered = true
        }
        (CommonActivity.activity as? MainActivity)?.let(::watchMain)
    }

    fun stop() {
        trace("STOP")
        detachYoutubeBridge(mainActivity)
        enabled = false
        liveSwitchSequence++
        liveSwitchJob?.cancel()
        liveSwitchJob = null
        controller?.removeOnDestinationChangedListener(destinations)
        controller = null
        mainActivity = null
        if (playerActivity == null) unregister()
    }

    private fun unregister() {
        if (registered) app.unregisterActivityLifecycleCallbacks(this)
        registered = false
        if (current === this) current = null
    }

    private fun watchMain(activity: FragmentActivity) {
        trace("WATCH_MAIN_BEGIN", activity)
        if (!enabled) return
        if (mainActivity === activity) {
            activity.window.decorView.setTag(R.id.global_to_navigation_player, youtubePipBridge)
            return
        }
        detachYoutubeBridge(mainActivity)
        controller?.removeOnDestinationChangedListener(destinations)
        mainActivity = activity
        activity.window.decorView.setTag(R.id.global_to_navigation_player, youtubePipBridge)
        controller = (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            as? NavHostFragment)?.navController?.also {
            // Adding a listener immediately dispatches the current destination.
            redirecting = true
            try { it.addOnDestinationChangedListener(destinations) }
            finally { redirecting = false }
        }
        trace("WATCH_MAIN_END", activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is MainActivity || activity is DownloadedPlayerActivity)
            trace("ACTIVITY_CREATED saved=${savedInstanceState != null}", activity)
        if (activity !is DownloadedPlayerActivity ||
            (pending == null && savedInstanceState?.getBoolean(PLAYER_RESTORE_KEY) != true)) return
        playerActivity?.takeIf { it !== activity }?.let {
            it.supportFragmentManager.unregisterFragmentLifecycleCallbacks(playerFragments)
            it.removeOnPictureInPictureModeChangedListener(pipChanged)
            playerNavigation?.removeOnDestinationChangedListener(playerDestination)
            playerNavigation = null
            exitBack.remove()
            exitBackBound = false
        }
        playerActivity = activity
        activeUuid = savedInstanceState?.getString(PLAYER_UUID_KEY) ?: activeUuid
        closing = false
        wasPlaying = false
        wasInPip = false
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(playerFragments, true)
        // Keep the pre-attach observer early, but bind Back/Nav only after
        // DownloadedPlayerActivity.onCreate has registered its own back callback
        // and inflated the NavHost.
        activity.addOnPictureInPictureModeChangedListener(pipChanged)
        trace("EXTERNAL_ACTIVITY_BOUND", activity)
    }

    private fun bindPlayerExit(activity: DownloadedPlayerActivity) {
        trace("BIND_EXIT_BEGIN", activity)
        if (!exitBackBound) {
            // Registered after the core Activity back callback, and before
            // FullScreenPlayer's later resume callback.
            activity.onBackPressedDispatcher.addCallback(activity, exitBack)
            exitBackBound = true
        }
        if (playerNavigation == null) {
            playerNavigation = (activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)
                ?.navController?.also { it.addOnDestinationChangedListener(playerDestination) }
        }
        trace("BIND_EXIT_END", activity)
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity || activity is DownloadedPlayerActivity)
            trace("ACTIVITY_STARTED_BEGIN", activity)
        if (activity is MainActivity) watchMain(activity)
        if (activity === playerActivity) {
            bindPlayerExit(activity as DownloadedPlayerActivity)
            if (nextPipVideo != null) schedulePipSwitch(activity)
        }
        if (activity is MainActivity || activity is DownloadedPlayerActivity)
            trace("ACTIVITY_STARTED_END", activity)
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity || activity is DownloadedPlayerActivity)
            trace("ACTIVITY_RESUMED_BEGIN", activity)
        if (activity is MainActivity) {
            watchMain(activity)
            restoreLandscapeNavigation()
        }
        if (activity === playerActivity) {
            bindPlayerExit(activity as DownloadedPlayerActivity)
            if (!activity.inPip()) wasInPip = false
            if (nextPipVideo != null) schedulePipSwitch(activity)
        }
        if (activity is MainActivity || activity is DownloadedPlayerActivity)
            trace("ACTIVITY_RESUMED_END", activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (activity is MainActivity || activity is DownloadedPlayerActivity)
            trace("ACTIVITY_DESTROYED_BEGIN", activity)
        if (activity === mainActivity) {
            detachYoutubeBridge(mainActivity)
            liveSwitchSequence++
            liveSwitchJob?.cancel()
            liveSwitchJob = null
            controller?.removeOnDestinationChangedListener(destinations)
            controller = null
            mainActivity = null
        }
        if (activity === playerActivity) {
            (activity as DownloadedPlayerActivity).apply {
                playerNavigation?.removeOnDestinationChangedListener(playerDestination)
                supportFragmentManager.unregisterFragmentLifecycleCallbacks(playerFragments)
                removeOnPictureInPictureModeChangedListener(pipChanged)
            }
            playerNavigation = null
            exitBack.remove()
            exitBackBound = false
            nextPipVideo?.getString("uuid")?.let(::forgetPlaceholder)
            nextPipVideo = null
            if (!activity.isChangingConfigurations) forgetPlaceholder(activeUuid)
            activeUuid = null
            playerActivity = null
            pending = null
            switching = false
            switchPosted = false
            wasPlaying = false
            wasInPip = false
            closing = false
            if (!enabled) unregister()
        }
        if (activity is MainActivity || activity is DownloadedPlayerActivity)
            trace("ACTIVITY_DESTROYED_END", activity)
    }
    override fun onActivityPaused(activity: Activity) {
        if (activity is MainActivity || activity is DownloadedPlayerActivity)
            trace("ACTIVITY_PAUSED", activity)
    }
    override fun onActivityStopped(activity: Activity) {
        if (activity is MainActivity || activity is DownloadedPlayerActivity)
            trace("ACTIVITY_STOPPED_BEGIN", activity)
        // Android can still report inPip=true while dismissing PiP.
        // A stopped PiP Activity is no longer presenting the video;
        // release it rather than leaving a paused task/session behind.
        if (activity === playerActivity && !activity.isChangingConfigurations &&
            (activity.isFinishing || wasInPip)) {
            closePlayer(activity as DownloadedPlayerActivity)
        }
        if (activity is MainActivity || activity is DownloadedPlayerActivity)
            trace("ACTIVITY_STOPPED_END", activity)
    }

    override fun onActivityPreDestroyed(activity: Activity) {
        if (activity is MainActivity || activity is DownloadedPlayerActivity)
            trace("ACTIVITY_PRE_DESTROYED", activity)
        if (activity === playerActivity && !closing && !activity.isChangingConfigurations) {
            // Android's PiP X can destroy the Activity without dispatching a
            // back press. Do not leave the native decoder waiting for onStop.
            findPlayer(activity.supportFragmentManager)?.let { player ->
                runCatching { player.releasePlayer() }
                    .onFailure { Log.e("ElevatePiP", "PiP close release failed", it) }
            }
        }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        if (activity is MainActivity || activity is DownloadedPlayerActivity)
            trace("ACTIVITY_SAVE_INSTANCE", activity)
        if (activity === playerActivity) {
            outState.putBoolean(PLAYER_RESTORE_KEY, true)
            outState.putString(PLAYER_UUID_KEY, activeUuid)
        }
    }
}
