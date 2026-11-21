package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Activity lifecycle evidence; optional memory checkpoints during a manual profile only. */
internal class ElevatePlaybackTrace(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private var running = false
    private val hlsStartupTrace = ElevateHlsStartupTrace()
    fun start() {
        if (running) return
        running = true
        application.registerActivityLifecycleCallbacks(this)
        ElevateActivityResultOwner.bootstrap()
        hlsStartupTrace.start()
    }

    fun stop() {
        if (!running) return
        running = false
        application.unregisterActivityLifecycleCallbacks(this)
        ElevateActivityResultOwner.reset()
        hlsStartupTrace.stop()

    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit

    override fun onActivityPostCreated(activity: Activity, state: Bundle?) {
        ElevateActivityResultOwner.track(activity, "POST_CREATE")
        DownloadedPlayerLeakCleanup.trackLivePlayers(activity)
        hlsStartupTrace.track(activity)
    }

    override fun onActivityPreDestroyed(activity: Activity) {
        ElevateActivityResultOwner.onDestroy(activity)
        ReceiverRegistrationTrace.snapshot(activity, "PRE_DESTROY")
        DownloadedPlayerLeakCleanup.onDestroy(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        ElevateActivityResultOwner.onDestroy(activity)
        // Fallback on older Android versions; callback removal is idempotent.
        DownloadedPlayerLeakCleanup.onDestroy(activity)
        hlsStartupTrace.untrack(activity)
        ReceiverRegistrationTrace.snapshot(activity, "DESTROYED")
        ElevateJavaAllocationProfile.checkpoint(application, "ACTIVITY_DESTROY")
    }

    override fun onActivityStarted(activity: Activity) {
        // Also covers Android versions without onActivityPostCreated().
        ElevateActivityResultOwner.track(activity, "STARTED")
        DownloadedPlayerLeakCleanup.trackLivePlayers(activity)
        hlsStartupTrace.track(activity)
    }
    override fun onActivityResumed(activity: Activity) {
        ReceiverRegistrationTrace.snapshot(activity, "RESUMED")
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) {
        ElevateJavaAllocationProfile.checkpoint(application, "ACTIVITY_STOP")
    }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
