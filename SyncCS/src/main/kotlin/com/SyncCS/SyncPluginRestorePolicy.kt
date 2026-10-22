package com.SyncCS

internal object SyncPluginRestorePolicy {
    fun canRestoreNow(activityPresent: Boolean, pluginsLoaded: Boolean): Boolean =
        activityPresent && pluginsLoaded
}
