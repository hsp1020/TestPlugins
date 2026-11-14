package com.hsp1020.elevate

import android.app.Activity
import android.content.BroadcastReceiver
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.media3.common.Player
import com.lagradost.cloudstream3.ui.player.live.LiveHelper
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Release only the CloudStream callbacks owned by a DownloadedPlayerActivity
 * when it is being destroyed. Do not alter active PiP or other activities.
 * The core's PlayerView.release() does not unregister its PiP receiver.
 */
internal object DownloadedPlayerLeakCleanup {

    /**
     * Retain only weak Player references. The core's LiveHelper retains released
     * players via the value-side Player.Listener of its WeakHashMap; unregister
     * the exact players observed in this downloaded activity before they vanish
     * from CS3IPlayer.exoPlayer.
     */
    private val liveTrackers = WeakHashMap<Activity, LiveTracker>()

    private class LiveTracker(private val activityId: Int) {
        private class FragmentPlayers {
            private val players = ArrayList<WeakReference<Player>>()
            private var observer = WeakReference<ViewTreeObserver>(null)
            private var preDraw: ViewTreeObserver.OnPreDrawListener? = null

            fun remember(player: Player?, activityId: Int) {
                if (player == null || players.any { it.get() === player }) return
                players.add(WeakReference(player))
                System.getProperty("hsp1020.java.alloc.run")?.let { run ->
                    Log.i("ElevateLiveLeak", "run=$run phase=LIVE_PLAYER_SEEN activityId=$activityId " +
                        "playerId=${System.identityHashCode(player)} tracked=${players.size}")
                }
            }

            fun observe(fragment: AbstractPlayerFragment<*>, activityId: Int) {
                remember(fragment.playerView?.player, activityId)
                val tree = fragment.view?.viewTreeObserver ?: return
                if (observer.get() === tree && preDraw != null) return
                detach()
                val weakFragment = WeakReference(fragment)
                val listener = ViewTreeObserver.OnPreDrawListener {
                    remember(weakFragment.get()?.playerView?.player, activityId)
                    true
                }
                tree.addOnPreDrawListener(listener)
                observer = WeakReference(tree)
                preDraw = listener
            }

            fun detach() {
                val tree = observer.get()
                val listener = preDraw
                if (tree?.isAlive == true && listener != null) {
                    tree.removeOnPreDrawListener(listener)
                }
                observer = WeakReference(null)
                preDraw = null
            }

            fun release(phase: String, activityId: Int) {
                detach()
                val run = System.getProperty("hsp1020.java.alloc.run")
                for (reference in players) {
                    val player = reference.get() ?: continue
                    val playerId = System.identityHashCode(player)
                    try {
                        val registeredBefore = LiveHelper.getLiveManager(player) != null
                        if (registeredBefore) LiveHelper.unregisterPlayer(player)
                        val registeredAfter = LiveHelper.getLiveManager(player) != null
                        if (run != null) Log.i("ElevateLiveLeak",
                            "run=$run phase=LIVE_UNREGISTER reason=$phase " +
                                "activityId=$activityId playerId=$playerId " +
                                "registeredBefore=$registeredBefore registeredAfter=$registeredAfter")
                    } catch (error: Exception) {
                        Log.w("ElevateLiveLeak",
                            "phase=LIVE_UNREGISTER_FAILED reason=$phase " +
                                "activityId=$activityId playerId=$playerId", error)
                    }
                }
                players.clear()
            }
        }

        private val fragments = WeakHashMap<Fragment, FragmentPlayers>()
        private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(
                fm: FragmentManager, fragment: Fragment, view: android.view.View, savedInstanceState: android.os.Bundle?
            ) {
                watch(fragment)
            }

            override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
                watch(fragment)
            }

            override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
                fragments[fragment]?.detach()
            }

            override fun onFragmentDestroyed(fm: FragmentManager, fragment: Fragment) {
                val tracked = fragments.remove(fragment) ?: return
                (fragment as? AbstractPlayerFragment<*>)?.let {
                    tracked.remember(it.playerView?.player, activityId)
                }
                tracked.release("FRAGMENT_PRE_DESTROY", activityId)
            }
        }

        private fun watch(fragment: Fragment) {
            if (fragment !is AbstractPlayerFragment<*>) return
            fragments.getOrPut(fragment) { FragmentPlayers() }.observe(fragment, activityId)
        }

        fun start(activity: FragmentActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
            fun scan(manager: FragmentManager) {
                for (fragment in manager.fragments) {
                    if (fragment.view != null) watch(fragment)
                    if (fragment.isAdded) scan(fragment.childFragmentManager)
                }
            }
            scan(activity.supportFragmentManager)
        }

        fun close(activity: FragmentActivity) {
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(callbacks)
            fragments.values.forEach { it.release("ACTIVITY_PRE_DESTROY", activityId) }
            fragments.clear()
        }
    }

    fun trackLivePlayers(activity: Activity) {
        if (activity.javaClass.name !=
            "com.lagradost.cloudstream3.ui.player.DownloadedPlayerActivity") return
        val host = activity as? FragmentActivity ?: return
        if (liveTrackers.containsKey(activity)) return
        val tracker = LiveTracker(System.identityHashCode(activity))
        liveTrackers[activity] = tracker
        tracker.start(host)
        System.getProperty("hsp1020.java.alloc.run")?.let { run ->
            Log.i("ElevateLiveLeak", "run=$run phase=LIVE_TRACK_START " +
                "activityId=${System.identityHashCode(activity)}")
        }
    }

    private fun field(instance: Any, name: String): java.lang.reflect.Field? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        return null
    }

    fun onDestroy(activity: Activity) {
        if (activity.javaClass.name != "com.lagradost.cloudstream3.ui.player.DownloadedPlayerActivity") return
        (activity as? FragmentActivity)?.let { host ->
            liveTrackers.remove(activity)?.close(host)
        }
        val run = System.getProperty("hsp1020.java.alloc.run")
        var callbacks = 0
        var receivers = 0
        var absent = 0
        var errors = 0

        // The helper stores a callback value that strongly captures the
        // Activity, defeating the WeakHashMap's weak key.
        try {
            with(BackPressedCallbackHelper) {
                (activity as ComponentActivity).detachBackPressedCallback("DownloadedPlayerActivity")
            }
            callbacks++
        } catch (error: Exception) {
            errors++
            if (run != null) Log.w("ElevateReceiverLeak", "phase=BACK_CALLBACK_RELEASE_FAILED", error)
        }

        fun visit(manager: FragmentManager) {
            for (fragment in manager.fragments) {
                if (fragment is AbstractPlayerFragment<*>) {
                    try {
                        val host = field(fragment, "playerHostView")?.get(fragment)
                        if (host != null) {
                            val receiverField = field(host, "pipReceiver")
                            val receiver = receiverField?.get(host) as? BroadcastReceiver
                            if (receiver != null) {
                                try {
                                    activity.unregisterReceiver(receiver)
                                    receivers++
                                } catch (_: IllegalArgumentException) {
                                    absent++
                                }
                                receiverField.set(host, null)
                            }
                        }
                    } catch (error: Exception) {
                        errors++
                        if (run != null) Log.w("ElevateReceiverLeak",
                            "phase=PIP_RECEIVER_RELEASE_FAILED fragment=${fragment.javaClass.simpleName}", error)
                    }
                }
                if (fragment.isAdded) visit(fragment.childFragmentManager)
            }
        }
        try {
            val host = activity as? androidx.fragment.app.FragmentActivity
            host?.let { visit(it.supportFragmentManager) }
        } catch (error: Exception) {
            errors++
            if (run != null) Log.w("ElevateReceiverLeak", "phase=PIP_RECEIVER_SCAN_FAILED", error)
        }
        if (run != null) Log.i("ElevateReceiverLeak",
            "run=$run phase=DOWNLOADED_ACTIVITY_CLEANUP activityId=${System.identityHashCode(activity)} " +
                "callbacks=$callbacks pipReceivers=$receivers alreadyUnregistered=$absent errors=$errors")
    }
}
