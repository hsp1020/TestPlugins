package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.navigation.NavController
import androidx.navigation.NavDestination
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
    private var mainActivity: MainActivity? = null
    private var controller: NavController? = null
    private var playerActivity: DownloadedPlayerActivity? = null
    private var pending: Bundle? = null
    private var redirecting = false
    private var restoreNative = false

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
            if (!nav.popBackStack()) return@OnDestinationChangedListener
            pending = Bundle(bundle)
            val uuid = bundle.getString("uuid")
            // Inert link only creates the native destination; onFragmentPreAttached
            // replaces its arguments before the media source is initialized.
            origin.startActivity(Intent(origin, DownloadedPlayerActivity::class.java).apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, "https://127.0.0.1/__elevate_pip/$uuid")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (error: Exception) {
            pending = null
            Log.e("ElevatePiP", "Player routing failed", error)
            origin.window.decorView.post {
                if (!origin.isFinishing && !origin.isDestroyed) {
                    restoreNative = true
                    nav.navigate(R.id.global_to_navigation_player, bundle)
                }
            }
        } finally {
            redirecting = false
        }
    }

    private val playerFragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentPreAttached(fm: FragmentManager, f: Fragment, context: Context) {
            if (f !is GeneratorPlayer) return
            val original = pending ?: return
            val activity = playerActivity ?: return
            val placeholder = f.arguments?.getString("uuid")
            try {
                f.arguments = Bundle(original)
                pending = null
                if (placeholder != original.getString("uuid")) forgetPlaceholder(placeholder)
            } catch (error: Exception) {
                pending = null
                Log.e("ElevatePiP", "Generator handoff failed", error)
                activity.finish()
            }
        }
    }

    private val pipChanged = Consumer<PictureInPictureModeChangedInfo> { change ->
        val activity = playerActivity ?: return@Consumer
        if (!change.isInPictureInPictureMode) return@Consumer
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

    private fun watchMain(activity: MainActivity) {
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
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(playerFragments, true)
        activity.addOnPictureInPictureModeChangedListener(pipChanged)
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity) watchMain(activity)
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) watchMain(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (activity === mainActivity) {
            controller?.removeOnDestinationChangedListener(destinations)
            controller = null
            mainActivity = null
        }
        if (activity === playerActivity) {
            (activity as DownloadedPlayerActivity).apply {
                supportFragmentManager.unregisterFragmentLifecycleCallbacks(playerFragments)
                removeOnPictureInPictureModeChangedListener(pipChanged)
            }
            playerActivity = null
            pending = null
            if (!enabled) unregister()
        }
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
