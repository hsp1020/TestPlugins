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

    private data class Review(val keys: Set<String>, val expires: Long, val revision: String)

    private fun parse(raw: String?): Review {
        if (raw.isNullOrBlank()) return Review(emptySet(), 0L, "")
        val data = JSONObject(raw)
        return Review(keys(data.optString("keys")), data.optLong("expires", 0L),
            data.optString("revision"))
    }

    private fun record(paths: Set<String>, now: Long, revision: String): String = JSONObject()
        .put("keys", encode(paths))
        .put("expires", now + LIFETIME_MS)
        .put("revision", revision).toString()

    fun approved(config: SyncConfiguration, candidates: Set<String>,
                 remoteRevision: String): Set<String> {
        if (candidates.isEmpty() || remoteRevision.isBlank()) return emptySet()
        val saved = parse(config.preferences.getString(APPROVAL, null))
        // Approvals are bound to the exact reviewed keys AND the exact Git HEAD.
        // A different proposal or concurrent edit can never reuse a prior approval.
        return if (System.currentTimeMillis() <= saved.expires &&
            candidates == saved.keys && remoteRevision == saved.revision) {
            saved.keys
        } else emptySet()
    }

    fun requireApproval(config: SyncConfiguration, candidates: Set<String>,
                        remoteRevision: String) {
        if (candidates.isEmpty()) return
        check(remoteRevision.matches(Regex("[0-9a-f]{40}"))) {
            "Cannot review a deletion without a verified GitHub revision."
        }
        check(candidates.size <= 25) {
            "Unsafe deletion request blocked: " + candidates.size + " changes."
        }
        if (approved(config, candidates, remoteRevision) == candidates) return
        check(config.preferences.edit().putString(PROPOSAL,
            record(candidates, System.currentTimeMillis(), remoteRevision)).commit()) {
            "Could not store deletion review request."
        }
        error("Deletion confirmation required for " + candidates.size +
            " items. Open SyncCS > Review deletions; nothing was deleted remotely.")
    }

    /** Defer unapproved deletions without blocking unrelated non-deleting updates. */
    fun reviewOrDefer(config: SyncConfiguration, candidates: Set<String>,
                      revision: String): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        check(revision.matches(Regex("[0-9a-f]{40}")))
        check(candidates.size <= 25) { "Unsafe deletion request blocked: ${candidates.size}" }
        val accepted = approved(config, candidates, revision)
        if (accepted == candidates) return accepted
        check(config.preferences.edit().putString(PROPOSAL,
            record(candidates, System.currentTimeMillis(), revision)).commit())
        return emptySet()
    }

    fun rebasePending(config: SyncConfiguration, pending: Set<String>, revision: String) {
        if (pending.isEmpty()) {
            consumed(config)
            return
        }
        check(revision.matches(Regex("[0-9a-f]{40}")))
        check(config.preferences.edit().remove(APPROVAL)
            .putString(PROPOSAL, record(pending, System.currentTimeMillis(), revision)).commit())
    }

    fun pending(config: SyncConfiguration): List<String> {
        val proposal = parse(config.preferences.getString(PROPOSAL, null))
        return if (System.currentTimeMillis() <= proposal.expires &&
            proposal.revision.matches(Regex("[0-9a-f]{40}")))
            proposal.keys.sorted() else emptyList()
    }

    fun authorizePending(config: SyncConfiguration): Int {
        val request = parse(config.preferences.getString(PROPOSAL, null))
        val paths = pending(config).toSet()
        require(paths.isNotEmpty() && paths.size <= 25 && paths == request.keys) {
            "No valid deletion request."
        }
        check(config.preferences.edit()
            .putString(APPROVAL, record(paths, System.currentTimeMillis(), request.revision))
            .remove(PROPOSAL).commit())
        return paths.size
    }

    fun consumed(config: SyncConfiguration) {
        config.preferences.edit().remove(APPROVAL).remove(PROPOSAL).apply()
    }
}
