package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Process
import android.util.Log

/** Activity creation and destruction evidence; no player polling or heap sampling. */
internal class ElevatePlaybackTrace(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private companion object { const val TAG = "ElevateMedia3Trace" }
    private var running = false
    private val previousDestroyed = HashMap<String, String>()

    private fun activityInfo(activity: Activity): String =
        "pid=${Process.myPid()} type=${activity.javaClass.simpleName} " +
            "activity=${System.identityHashCode(activity)} taskId=${activity.taskId} " +
            "taskRoot=${activity.isTaskRoot} finishing=${activity.isFinishing} " +
            "changingConfig=${activity.isChangingConfigurations} pip=${activity.isInPictureInPictureMode}"

    private fun destroyReason(activity: Activity): String = when {
        activity.isChangingConfigurations -> "CONFIGURATION_CHANGE"
        activity.isFinishing -> "FINISHING"
        else -> "SYSTEM_OR_EXTERNAL_UNKNOWN"
    }

    fun start() {
        if (running) return
        running = true
        application.registerActivityLifecycleCallbacks(this)
    }

    fun stop() {
        if (!running) return
        running = false
        application.unregisterActivityLifecycleCallbacks(this)
        previousDestroyed.clear()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        val previous = previousDestroyed.remove(activity.javaClass.name) ?: "none"
        val evidence = when {
            previous != "none" -> "AFTER_PREVIOUS_DESTROY"
            state != null -> "RESTORED_SAVED_STATE"
            else -> "NEW_INSTANCE_CAUSE_UNKNOWN"
        }
        Log.i(TAG, "ACTIVITY_CREATE ${activityInfo(activity)} evidence=$evidence " +
            "previous=$previous savedState=${state != null} intentAction=${activity.intent?.action} " +
            "intentFlags=0x${Integer.toHexString(activity.intent?.flags ?: 0)}")
    }

    override fun onActivityDestroyed(activity: Activity) {
        val reason = destroyReason(activity)
        Log.i(TAG, "ACTIVITY_DESTROY ${activityInfo(activity)} reason=$reason")
        previousDestroyed[activity.javaClass.name] =
            "activity=${System.identityHashCode(activity)},taskId=${activity.taskId},reason=$reason"
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
