package com.SyncCS

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalOriginalBackupTest {
    @Test fun originalBackupsAreListedSeparatelyAndIncompleteStagingIsHidden() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "synccs-recovery")
        root.deleteRecursively()
        try {
            assertTrue(root.mkdirs())
            val oldFile = File(root, "pre-github-link-device-1000.enc")
            oldFile.writeText("legacy encrypted payload")
            val newDir = File(root, "pre-github-link-device-2000")
            assertTrue(newDir.mkdirs())
            File(newDir, "snapshot.enc").writeText("encrypted manifest")
            val staging = File(root, "pre-github-link-device-3000.staging")
            assertTrue(staging.mkdirs())
            File(staging, "snapshot.enc").writeText("not committed")

            val choices = LocalOriginalBackup.list(context)
            assertEquals(2, choices.size)
            assertTrue(choices.any { !it.complete && it.file == oldFile })
            assertTrue(choices.any {
                it.complete && it.file == File(newDir, "snapshot.enc")
            })
            assertFalse(choices.any { it.file.parentFile == staging })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun localRestoreStorageDoesNotExposeAnyGitHubMutation() {
        val old = LocalOriginalBackup.Content(
            emptyMap(), "", "", null, null, 2)
        val source = LocalOriginalBackup.storage(old)
        assertThrows(IllegalStateException::class.java) {
            source.upload(mapOf("x/profiles" to "unsafe"), nullCipher(), emptyMap())
        }
        assertThrows(IllegalStateException::class.java) { source.flush(nullCipher()) }
        assertThrows(Exception::class.java) { source.downloadFile("a".repeat(64)) }
        assertNull(source.getEntry("x/profiles"))
    }

    private fun nullCipher(): SyncCipher = SyncCipher.derive(
        "offline-only-recovery-passphrase", "https://api.github.com", "fake-test-user")
}
