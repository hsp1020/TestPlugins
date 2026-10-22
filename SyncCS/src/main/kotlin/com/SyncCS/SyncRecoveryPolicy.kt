package com.SyncCS

/** Decisions for recovering an interrupted SyncCS transaction. */
internal object SyncRecoveryPolicy {
    fun wasChangedAfter(value: Any?, checkpointMs: Long): Boolean =
        (value as? Number)?.toLong()?.let { it > checkpointMs } == true

    fun keepNewPlugins(binariesMatchCheckpoint: Boolean,
                       pluginChangedAfterCheckpoint: Boolean): Boolean =
        !binariesMatchCheckpoint && pluginChangedAfterCheckpoint

    fun requiresForegroundBinaryRestore(binariesMatchCheckpoint: Boolean,
                                        pluginChangedAfterCheckpoint: Boolean): Boolean =
        !binariesMatchCheckpoint && !pluginChangedAfterCheckpoint
}
