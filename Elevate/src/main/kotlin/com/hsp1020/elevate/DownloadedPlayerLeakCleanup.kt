package com.hsp1020.elevate

import android.app.Activity
import android.content.BroadcastReceiver
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper

/**
 * Release only the CloudStream callbacks owned by a DownloadedPlayerActivity
 * when it is being destroyed. Do not alter active PiP or other activities.
 * The core's PlayerView.release() does not unregister its PiP receiver.
 */
internal object DownloadedPlayerLeakCleanup {
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
                if (fragment is AbstractPlayerFragment) {
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
