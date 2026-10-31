package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
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
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.DownloadedPlayerActivity
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer

/** Redirect new playback without rebuilding CloudStream's VideoGenerator. */
internal class PipLibraryNavigation(private val app: Application) : Application.ActivityLifecycleCallbacks {
    companion object {
        private var current: PipLibraryNavigation? = null

        fun currentPlayerHost(): FragmentActivity? =
            current?.playerActivity ?: (CommonActivity.activity as? MainActivity)

        fun playerHost(activity: Activity): FragmentActivity? = when {
            current?.playerActivity === activity -> activity as? FragmentActivity
            activity is MainActivity && current?.playerActivity == null -> activity
            else -> null
        }

        fun isPlayerActivity(activity: Activity): Boolean = current?.playerActivity === activity
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

    private fun Activity.inPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    private val destinations = NavController.OnDestinationChangedListener {
            nav: NavController, destination: NavDestination, args: Bundle? ->
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
        try {
            val pip = playerActivity?.takeIf {
                it.inPip() && !it.isFinishing && !it.isDestroyed
            }
            if (!nav.popBackStack()) return@OnDestinationChangedListener
            if (pip != null) {
                // Do not bring the PiP activity to the foreground.
                nextPipVideo?.getString("uuid")?.let(::forgetPlaceholder)
                nextPipVideo = Bundle(bundle)
                schedulePipSwitch(pip)
            } else {
                pending = Bundle(bundle)
                val uuid = bundle.getString("uuid")
                // A non-network stub creates the native player destination.
                origin.startActivity(Intent(origin, DownloadedPlayerActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, "https://127.0.0.1/__elevate_pip/$uuid")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        } catch (error: Exception) {
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
        }
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
        override fun handleOnBackPressed() { playerActivity?.let(::closePlayer) }
    }

    private fun closePlayer(activity: DownloadedPlayerActivity) {
        if (closing || playerActivity !== activity) return
        closing = true
        nextPipVideo?.getString("uuid")?.let(::forgetPlaceholder)
        nextPipVideo = null
        findPlayer(activity.supportFragmentManager)?.let { player ->
            runCatching { player.releasePlayer() }
                .onFailure { Log.e("ElevatePiP", "Player release failed", it) }
        }
        forgetPlaceholder(activeUuid)
        activeUuid = null
        activity.finishAndRemoveTask()
    }

    private val playerDestination = NavController.OnDestinationChangedListener {
            _: NavController, destination: NavDestination, _: Bundle? ->
        if (switching) return@OnDestinationChangedListener
        if (destination.id == R.id.navigation_player) wasPlaying = true
        else if (wasPlaying) playerActivity?.let(::closePlayer)
    }

    private val playerFragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentPreAttached(fm: FragmentManager, f: Fragment, context: Context) {
            if (f !is GeneratorPlayer) return
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
    }

    private val pipChanged = Consumer<PictureInPictureModeChangedInfo> { change ->
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
        enabled = false
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
        if (!enabled || mainActivity === activity) return
        controller?.removeOnDestinationChangedListener(destinations)
        mainActivity = activity
        controller = (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            as? NavHostFragment)?.navController?.also {
            // Adding a listener immediately dispatches the current destination.
            redirecting = true
            try { it.addOnDestinationChangedListener(destinations) }
            finally { redirecting = false }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is DownloadedPlayerActivity || pending == null) return
        playerActivity?.takeIf { it !== activity }?.let {
            it.supportFragmentManager.unregisterFragmentLifecycleCallbacks(playerFragments)
            it.removeOnPictureInPictureModeChangedListener(pipChanged)
        }
        playerActivity = activity
        closing = false
        wasPlaying = false
        wasInPip = false
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(playerFragments, true)
        // Native FullScreenPlayer back callbacks register later and retain priority.
        activity.onBackPressedDispatcher.addCallback(activity, exitBack)
        activity.addOnPictureInPictureModeChangedListener(pipChanged)
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            as? NavHostFragment)?.navController
            ?.addOnDestinationChangedListener(playerDestination)
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity) watchMain(activity)
        if (activity === playerActivity && nextPipVideo != null) {
            schedulePipSwitch(activity as DownloadedPlayerActivity)
        }
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) watchMain(activity)
        if (activity === playerActivity) {
            if (!activity.inPip()) wasInPip = false
            if (nextPipVideo != null) schedulePipSwitch(activity as DownloadedPlayerActivity)
        }
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (activity === mainActivity) {
            controller?.removeOnDestinationChangedListener(destinations)
            controller = null
            mainActivity = null
        }
        if (activity === playerActivity) {
            (activity as DownloadedPlayerActivity).apply {
                (supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                    as? NavHostFragment)?.navController
                    ?.removeOnDestinationChangedListener(playerDestination)
                supportFragmentManager.unregisterFragmentLifecycleCallbacks(playerFragments)
                removeOnPictureInPictureModeChangedListener(pipChanged)
            }
            exitBack.remove()
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
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) {
        // Closing PiP stops the Activity. Expanding it resumes instead.
        if (activity === playerActivity && wasInPip &&
            !activity.inPip() && !activity.isChangingConfigurations) {
            closePlayer(activity as DownloadedPlayerActivity)
        }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
