package com.SyncCS

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * Fresh-install-only local baseline. The removed base/remote_versions JSON
 * preferences are deliberately not imported. GitHub's authenticated full
 * snapshot is the authority when this database has no committed state.
 */
internal object SyncBaselineStore {
    private const val NAME = "synccs-baseline-sqlite-v1.db"

    data class State(val accountId: String, val revision: String, val generation: Int)
    data class Snapshot(
        val state: State,
        val hashes: Map<String, String>,
        val versions: Map<String, Long>
    )
    data class Changes(
        val hashUpserts: Int, val hashDeletes: Int,
        val versionUpserts: Int, val versionDeletes: Int
    )

    private class Database(context: Context) :
        SQLiteOpenHelper(context.applicationContext, NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE hashes (entry_key TEXT PRIMARY KEY NOT NULL, hash_value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE versions (entry_key TEXT PRIMARY KEY NOT NULL, version_value INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE sync_state (slot INTEGER PRIMARY KEY NOT NULL, account_id TEXT NOT NULL, revision TEXT NOT NULL, generation INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("SyncCS baseline schema has no migration: a new database name is required.")
        }
    }

    private fun state(db: SQLiteDatabase): State? =
        db.rawQuery("SELECT account_id, revision, generation FROM sync_state WHERE slot=1", null)
            .use { cursor ->
                if (!cursor.moveToFirst()) null
                else State(cursor.getString(0), cursor.getString(1), cursor.getInt(2))
            }

    fun state(context: Context, accountId: String): State? =
        Database(context).use { helper ->
            state(helper.readableDatabase)?.takeIf { it.accountId == accountId }
        }

    /** A partial pull is safe only if the baseline and durable Git cursor agree. */
    fun isCurrent(context: Context, config: SyncConfiguration): Boolean {
        val accountId = config.preferences.getString("github_account_id", null) ?: return false
        val stored = state(context, accountId) ?: return false
        return stored.revision == config.preferences.getString("github_base_revision", null) &&
            stored.generation == config.preferences.getInt("github_generation", 0)
    }

    fun snapshot(context: Context, accountId: String): Snapshot? =
        Database(context).use { helper ->
            val db = helper.readableDatabase
            val committed = state(db)?.takeIf { it.accountId == accountId }
                ?: return@use null
            val hashes = LinkedHashMap<String, String>()
            db.rawQuery("SELECT entry_key, hash_value FROM hashes", null).use { cursor ->
                while (cursor.moveToNext()) hashes[cursor.getString(0)] = cursor.getString(1)
            }
            val versions = LinkedHashMap<String, Long>()
            db.rawQuery("SELECT entry_key, version_value FROM versions", null).use { cursor ->
                while (cursor.moveToNext()) versions[cursor.getString(0)] = cursor.getLong(1)
            }
            Snapshot(committed, hashes, versions)
        }

    /**
     * Only changed rows are written. Hashes, versions and their Git revision
     * become visible together in one SQLite transaction.
     */
    fun commit(
        context: Context, accountId: String, previous: Snapshot?,
        hashes: Map<String, String>, versions: Map<String, Long>,
        revision: String, generation: Int
    ): Changes = Database(context).use { helper ->
        val db = helper.writableDatabase
        var hashUpserts = 0
        var hashDeletes = 0
        var versionUpserts = 0
        var versionDeletes = 0
        db.beginTransaction()
        try {
            val before = state(db)
            check(before == previous?.state || (previous == null &&
                before != null && before.accountId != accountId)) {
                "SyncCS baseline changed during transaction; refusing stale write."
            }
            if (before != null && before.accountId != accountId) {
                db.delete("hashes", null, null)
                db.delete("versions", null, null)
            }
            val oldHashes = previous?.hashes.orEmpty()
            val oldVersions = previous?.versions.orEmpty()
            db.compileStatement("INSERT OR REPLACE INTO hashes(entry_key, hash_value) VALUES(?, ?)").use { insert ->
                for ((key, value) in hashes) {
                    if (oldHashes[key] == value) continue
                    insert.clearBindings()
                    insert.bindString(1, key)
                    insert.bindString(2, value)
                    insert.executeInsert()
                    hashUpserts++
                }
            }
            db.compileStatement("DELETE FROM hashes WHERE entry_key=?").use { delete ->
                for (key in oldHashes.keys) {
                    if (hashes.containsKey(key)) continue
                    delete.clearBindings()
                    delete.bindString(1, key)
                    delete.executeUpdateDelete()
                    hashDeletes++
                }
            }
            db.compileStatement("INSERT OR REPLACE INTO versions(entry_key, version_value) VALUES(?, ?)").use { insert ->
                for ((key, value) in versions) {
                    if (oldVersions[key] == value) continue
                    insert.clearBindings()
                    insert.bindString(1, key)
                    insert.bindLong(2, value)
                    insert.executeInsert()
                    versionUpserts++
                }
            }
            db.compileStatement("DELETE FROM versions WHERE entry_key=?").use { delete ->
                for (key in oldVersions.keys) {
                    if (versions.containsKey(key)) continue
                    delete.clearBindings()
                    delete.bindString(1, key)
                    delete.executeUpdateDelete()
                    versionDeletes++
                }
            }
            db.compileStatement("INSERT OR REPLACE INTO sync_state(slot, account_id, revision, generation) VALUES(1, ?, ?, ?)").use { insert ->
                insert.bindString(1, accountId)
                insert.bindString(2, revision)
                insert.bindLong(3, generation.toLong())
                insert.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Changes(hashUpserts, hashDeletes, versionUpserts, versionDeletes)
    }

    fun reset(context: Context) {
        Database(context).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.delete("hashes", null, null)
                db.delete("versions", null, null)
                db.delete("sync_state", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Copy tables directly into the existing undo journal without JSON or
     * retaining 30k key/value objects in Java. The transaction lease prevents
     * concurrent SyncCS writers during capture and recovery.
     */
    fun backupTo(context: Context, file: File) {
        require(!file.exists()) { "Existing SyncCS baseline checkpoint" }
        Database(context).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("ATTACH DATABASE ? AS undo_baseline", arrayOf(file.absolutePath))
            try {
                db.beginTransaction()
                try {
                    db.execSQL("CREATE TABLE undo_baseline.hashes AS SELECT entry_key, hash_value FROM main.hashes")
                    db.execSQL("CREATE TABLE undo_baseline.versions AS SELECT entry_key, version_value FROM main.versions")
                    db.execSQL("CREATE TABLE undo_baseline.sync_state AS SELECT slot, account_id, revision, generation FROM main.sync_state")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.execSQL("DETACH DATABASE undo_baseline")
            }
        }
    }

    fun restoreFrom(context: Context, file: File) {
        check(file.isFile) { "Missing local SQLite baseline recovery checkpoint" }
        Database(context).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("ATTACH DATABASE ? AS undo_baseline", arrayOf(file.absolutePath))
            try {
                db.beginTransaction()
                try {
                    db.execSQL("DELETE FROM main.hashes")
                    db.execSQL("DELETE FROM main.versions")
                    db.execSQL("DELETE FROM main.sync_state")
                    db.execSQL("INSERT INTO main.hashes(entry_key, hash_value) SELECT entry_key, hash_value FROM undo_baseline.hashes")
                    db.execSQL("INSERT INTO main.versions(entry_key, version_value) SELECT entry_key, version_value FROM undo_baseline.versions")
                    db.execSQL("INSERT INTO main.sync_state(slot, account_id, revision, generation) SELECT slot, account_id, revision, generation FROM undo_baseline.sync_state")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.execSQL("DETACH DATABASE undo_baseline")
            }
        }
    }
}
