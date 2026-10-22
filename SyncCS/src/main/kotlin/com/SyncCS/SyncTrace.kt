package com.SyncCS

import android.util.Log
import android.os.Debug
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Diagnostic-only trace. Never log preference values, access tokens or raw identifiers. */
internal object SyncTrace {
    private const val TAG = "SyncCS-Trace"
    private val next = AtomicLong()
    @Volatile private var active = 0L

    fun start(): Long {
        val id = next.incrementAndGet()
        active = id
        info("begin")
        return id
    }

    fun finish(id: Long) {
        info("end")
        if (active == id) active = 0L
    }

    /** Lightweight heap samples for correlating player stalls with sync work. */
    fun memory(stage: String) {
        val runtime = Runtime.getRuntime()
        val javaMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val nativeMiB = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
        info("memory", "point=$stage javaMiB=$javaMiB nativeMiB=$nativeMiB " +
            "maxMiB=${runtime.maxMemory() / (1024L * 1024L)}")
    }

    fun info(stage: String, detail: String = "") {
        Log.i(TAG, "run=$active stage=$stage $detail")
    }

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).take(6)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun category(key: String): String {
        if (key == "x/plugins" || key.startsWith("x/plugins/")) return "plugins"
        if (key == "x/profiles" || key.startsWith("x/profiles/")) return "profiles"
        if (key.startsWith("p/")) return "addon-settings"
        if (key.startsWith("s/")) return "app-settings"
        val suffix = when {
            key.startsWith("u/") -> key.removePrefix("u/").substringAfter('/', "")
            key.startsWith("d/") -> {
                val raw = key.removePrefix("d/")
                if (raw.substringBefore('/').all(Char::isDigit)) raw.substringAfter('/', "") else raw
            }
            else -> key
        }
        return when {
            suffix.startsWith("video_pos_dur/") ||
                suffix.startsWith("video_watch_state/") ||
                suffix.startsWith("result_resume_watching_2/") ||
                suffix.startsWith("result_episode/") ||
                suffix.startsWith("result_season/") ||
                suffix.startsWith("result_dub/") -> "playback"
            suffix.startsWith("result_watch_state/") ||
                suffix.startsWith("result_watch_state_data/") ||
                suffix.startsWith("result_favorites_state_data/") ||
                suffix.startsWith("result_subscribed_state_data/") -> "library"
            suffix.startsWith("search_history/") -> "search"
            suffix.startsWith("auth_tokens/") || suffix.startsWith("auth_ids/") -> "accounts"
            else -> "preferences"
        }
    }

    fun keys(stage: String, values: Collection<String>) {
        val counts = values.groupingBy(::category).eachCount().toSortedMap()
        info(stage, "count=${values.size} groups=$counts")
        // Stable hashed identities let logs from A and B be compared without raw IDs.
        values.asSequence()
            .filter { category(it) in setOf("playback", "library", "plugins", "profiles", "search") }
            .take(36).forEach { key ->
                info("entry", "phase=$stage kind=${category(key)} id=${fingerprint(key)}")
            }
    }

    fun profiles(stage: String, raw: String?) {
        runCatching {
            val state = ProfileSync.decode(raw)
            val ids = state.items.toSortedMap().map { (slot, row) ->
                "$slot:${fingerprint(ProfileIdentity.uuid(row))}"
            }
            info(stage, "profiles=${ids.joinToString(",")} tombstones=${state.deleted.size}")
        }.onFailure { info(stage, "profile-manifest-unavailable") }
    }

    fun plugins(stage: String, raw: String?) {
        runCatching {
            val state = PluginSync.decode(raw)
            info(stage, "installed=${state.items.size} tombstones=${state.deleted.size} " +
                "ids=${state.items.keys.take(24).map(::fingerprint)}")
        }.onFailure { info(stage, "plugin-manifest-unavailable") }
    }

    fun pluginDelta(stage: String, previous: String?, target: String?) {
        runCatching {
            val a = PluginSync.decode(previous).items
            val b = PluginSync.decode(target).items
            val installed = b.keys - a.keys
            val removed = a.keys - b.keys
            val changed = (a.keys intersect b.keys).filter { a[it].toString() != b[it].toString() }
            info(stage, "install=${installed.size} remove=${removed.size} update=${changed.size} " +
                "installIds=${installed.take(16).map(::fingerprint)} " +
                "removeIds=${removed.take(16).map(::fingerprint)}")
        }.onFailure { info(stage, "plugin-delta-unavailable") }
    }

    private fun equivalent(left: String?, right: String?): Boolean {
        if (left == right) return true
        if (left == null || right == null) return false
        return runCatching {
            val a = org.json.JSONObject(left)
            val b = org.json.JSONObject(right)
            val type = a.getString("type")
            if (type != b.getString("type")) return@runCatching false
            if (type == "set") {
                val x = a.getJSONArray("value")
                val y = b.getJSONArray("value")
                (0 until x.length()).map(x::getString).toSet() ==
                    (0 until y.length()).map(y::getString).toSet()
            } else a.get("value").toString() == b.get("value").toString()
        }.getOrDefault(false)
    }

    fun actions(stage: String, changes: Map<String, String?>) {
        info("actions", "phase=$stage changes=${changes.size} deletes=${changes.values.count { it == null }}")
        changes.entries.asSequence().take(100).forEach { (key, value) ->
            info("transfer", "phase=$stage action=${if (value == null) "DELETE" else "UPSERT"} " +
                "kind=${category(key)} id=${fingerprint(key)} bytes=${value?.length ?: 0}")
        }
    }

    fun applied(stage: String, expected: Map<String, String?>,
                actual: Map<String, String>): Boolean {
        val mismatched = expected.filter { (key, value) ->
            !equivalent(value, actual[key])
        }.keys
        info(stage, "checked=${expected.size} matched=${expected.size - mismatched.size} " +
            "mismatch=${mismatched.size}")
        keys("$stage-mismatch", mismatched)
        mismatched.take(25).forEach { key ->
            val expectedVal = expected[key]
            val actualVal = actual[key]
            info("apply-mismatch-detail", "kind=${category(key)} id=${fingerprint(key)} " +
                "expected=${expectedVal?.length ?: -1} actual=${actualVal?.length ?: -1} " +
                "expectedHash=${expectedVal?.let(::fingerprint) ?: "deleted"} " +
                "actualHash=${actualVal?.let(::fingerprint) ?: "deleted"}")
        }
        return mismatched.isEmpty()
    }
}
