package com.SyncCS

import android.content.Context
import android.os.SystemClock

/**
 * Remove old preference-listener markers only after a committed, verified sync.
 * Newer edits and values that still differ from the confirmed baseline survive.
 */
internal object SyncDirtyMarkerCleaner {
    // Report detailed reasons once per distinct marker/status until its state changes.
    private val diagnosed = LinkedHashSet<String>()
    data class Stats(val removed: Int, val pending: Int, val newer: Int, val elapsedMs: Long)

    fun matchesVerifiedBaseline(actual: String?, expectedHash: String?): Boolean =
        if (expectedHash == null || expectedHash == "<deleted>") actual == null
        else actual != null && SyncBackend.digest(actual) == expectedHash

    fun prune(
        context: Context, baseline: Map<String, String>,
        profiles: ProfileSync.State, startedAt: Long, pluginsPending: Boolean
    ): Stats {
        val begin = SystemClock.elapsedRealtime()
        val prefs = SyncConfiguration(context).preferences
        val markers = prefs.all.filterKeys { it.startsWith("modified/") }
        if (markers.isEmpty()) return Stats(0, 0, 0, 0)
        val native = markers.keys.mapNotNull {
            ProfileIdentity.nativePath(it.removePrefix("modified/"), profiles)
        }
        val now = ProfileIdentity.toRemote(SyncData.snapshotSelected(context, native), profiles)
        val editor = prefs.edit()
        var removed = 0
        var newer = 0
        var differing = 0
        var removedHeaderMarkers = 0
        var withoutBaseline = 0
        for ((tag, timestamp) in markers) {
            val key = tag.removePrefix("modified/")
            if ((timestamp as? Number)?.toLong()?.let { it > startedAt } != false) {
                newer++
                continue
            }
            val settled = when (key) {
                // The desired plugin manifest is already committed; binary
                // installation is tracked separately by pending_plugins.
                "x/plugins" -> true
                "x/profiles" -> true
                else -> {
                    val native = ProfileIdentity.nativePath(key, profiles)
                    if (native == null) {
                        // Retired UUIDs cannot be mapped onto current CloudStream
                        // profiles. Dropping the old event marker preserves data.
                        true
                    } else if (!SyncData.isTracked(native)) true
                    else matchesVerifiedBaseline(now[key], baseline[key])
                }
            }
            if (settled) {
                editor.remove(tag)
                removed++
                synchronized(diagnosed) { diagnosed.removeAll { it.startsWith("$key:") } }
                if (key.startsWith("d/download_header_cache/")) removedHeaderMarkers++
            } else {
                differing++
                if (!baseline.containsKey(key)) withoutBaseline++
                val mapped = ProfileIdentity.nativePath(key, profiles)
                val reason = if (baseline[key] == null) "not-in-verified-baseline" else
                    "value-differs-from-baseline"
                val code = "$key:$reason:${now.containsKey(key)}"
                val first = synchronized(diagnosed) {
                    if (diagnosed.size >= 128) diagnosed.clear()
                    diagnosed.add(code)
                }
                if (first) SyncTrace.info("dirty-marker-held",
                    "keyHash=${SyncBackend.digest(key).take(12)} " +
                        "reason=$reason category=${if (key.startsWith("d/download_header_cache/"))
                            "header-cache" else if (key.startsWith("u/")) "profile"
                            else if (key.startsWith("p/")) "addon-setting" else "other"} " +
                        "localPresent=${now.containsKey(key)} " +
                        "baselinePresent=${baseline.containsKey(key)} " +
                        "nativeMapped=${mapped != null} " +
                        "tracked=${mapped?.let(SyncData::isTracked)}")
            }
        }
        if (removed != 0) check(editor.commit()) { "Could not prune settled dirty markers" }
        val duration = SystemClock.elapsedRealtime() - begin
        SyncTrace.info("dirty-marker-cleanup", "before=${markers.size} removed=$removed " +
            "remaining=${markers.size - removed} newer=$newer " +
                "unsettled=$differing baselineMissing=$withoutBaseline " +
                "headerMarkersRemoved=$removedHeaderMarkers " +
                "pluginsPending=$pluginsPending elapsedMs=$duration")
        return Stats(removed, markers.size - removed, newer, duration)
    }
}
