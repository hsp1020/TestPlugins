package com.SyncCS

import org.json.JSONArray
import org.json.JSONObject

/**
 * A preference-change callback does not identify a human-initiated deletion.
 * An actual removal is first recorded as a proposal, and must be confirmed in
 * SyncCS settings before it can be published. No automatic removal permissions.
 */
internal object SyncDeletionGate {
    private const val PROPOSAL = "github_delete_proposal"
    private const val APPROVAL = "github_delete_approval"
    private const val LIFETIME_MS = 10L * 60L * 1000L

    private fun encode(paths: Set<String>): String {
        val list = JSONArray()
        paths.sorted().forEach(list::put)
        return list.toString()
    }

    private fun keys(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val items = JSONArray(raw)
        return (0 until items.length()).map { items.getString(it) }.toSet()
    }

    private fun parse(raw: String?): Pair<Set<String>, Long> {
        if (raw.isNullOrBlank()) return emptySet<String>() to 0L
        val data = JSONObject(raw)
        return keys(data.optString("keys")) to data.optLong("expires", 0L)
    }

    private fun record(paths: Set<String>, now: Long): String = JSONObject()
        .put("keys", encode(paths))
        .put("expires", now + LIFETIME_MS).toString()

    fun approved(config: SyncConfiguration, candidates: Set<String>): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val (saved, expires) = parse(config.preferences.getString(APPROVAL, null))
        return if (System.currentTimeMillis() <= expires && candidates.all { it in saved }) {
            saved
        } else emptySet()
    }

    fun requireApproval(config: SyncConfiguration, candidates: Set<String>) {
        if (candidates.isEmpty()) return
        check(candidates.size <= 25) {
            "Unsafe deletion request blocked: " + candidates.size + " changes."
        }
        if (approved(config, candidates).containsAll(candidates)) return
        check(config.preferences.edit().putString(PROPOSAL,
            record(candidates, System.currentTimeMillis())).commit()) {
            "Could not store deletion review request."
        }
        error("Deletion confirmation required for " + candidates.size +
            " items. Open SyncCS > Review deletions; nothing was deleted remotely.")
    }

    fun pending(config: SyncConfiguration): List<String> {
        val (paths, expires) = parse(config.preferences.getString(PROPOSAL, null))
        return if (System.currentTimeMillis() <= expires) paths.sorted() else emptyList()
    }

    fun authorizePending(config: SyncConfiguration): Int {
        val paths = pending(config).toSet()
        require(paths.isNotEmpty() && paths.size <= 25) { "No valid deletion request." }
        check(config.preferences.edit()
            .putString(APPROVAL, record(paths, System.currentTimeMillis()))
            .remove(PROPOSAL).commit())
        return paths.size
    }

    fun consumed(config: SyncConfiguration) {
        config.preferences.edit().remove(APPROVAL).remove(PROPOSAL).apply()
    }
}
