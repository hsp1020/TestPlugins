package com.hsp1020.elevate

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.ui.player.DownloadedPlayerActivity
import java.lang.ref.WeakReference

/**
 * CommonActivity.init() registers an Activity-owned result launcher but stores
 * it in MainActivity's process-wide companion field. Restore the last living
 * launcher only when the departing Activity still owns that field.
 */
internal object ElevateActivityResultOwner {
    private const val TAG = "ElevateResultOwner"

    private class Owner(activity: Activity, val launcher: ActivityResultLauncher<Intent>) {
        val activity = WeakReference(activity)
    }

    private val owners = ArrayList<Owner>()

    /** Elevate can load after MainActivity.onCreate() has already run. */
    fun bootstrap() {
        val main = CommonActivity.activity as? MainActivity ?: return
        track(main, "BOOTSTRAP")
    }

    /** Called after onCreate(), once CommonActivity.init() registered the launcher. */
    fun track(activity: Activity, source: String) {
        if (activity !is MainActivity && activity !is DownloadedPlayerActivity) return
        if (owners.any { it.activity.get() === activity }) return
        val launcher = MainActivity.activityResultLauncher ?: run {
            Log.w(TAG, "phase=REGISTER_MISSING source=$source ownerId=${System.identityHashCode(activity)}")
            return
        }
        owners.removeAll { it.activity.get()?.isDestroyed != false }
        owners.add(Owner(activity, launcher))
        Log.i(TAG, "phase=REGISTER source=$source type=${activity.javaClass.simpleName} " +
            "ownerId=${System.identityHashCode(activity)} launcherId=${System.identityHashCode(launcher)} " +
            "liveOwners=${owners.size}")
    }

    /** Both pre-destroy and destroy invoke this; removing the entry makes it idempotent. */
    fun onDestroy(activity: Activity) {
        if (activity !is MainActivity && activity !is DownloadedPlayerActivity) return
        val departing = owners.firstOrNull { it.activity.get() === activity } ?: return
        owners.removeAll {
            val owner = it.activity.get()
            owner == null || owner === activity || owner.isDestroyed
        }
        val currentMatches = MainActivity.activityResultLauncher === departing.launcher
        val replacement = if (currentMatches) {
            owners.lastOrNull {
                val owner = it.activity.get()
                owner != null && !owner.isDestroyed && !owner.isFinishing
            }
        } else null
        if (currentMatches) MainActivity.activityResultLauncher = replacement?.launcher
        Log.i(TAG, "phase=RELEASE type=${activity.javaClass.simpleName} " +
            "ownerId=${System.identityHashCode(activity)} launcherId=${System.identityHashCode(departing.launcher)} " +
            "currentMatches=$currentMatches " +
            "replacementType=${replacement?.activity?.get()?.javaClass?.simpleName ?: "none"} " +
            "replacementId=${replacement?.activity?.get()?.let { System.identityHashCode(it) } ?: 0} " +
            "liveOwners=${owners.size}")
    }

    fun reset() {
        owners.clear()
    }
}
