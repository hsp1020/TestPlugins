package com.SyncCS

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*

@CloudstreamPlugin
class SyncPlugin : Plugin() {

    private val handler = Handler(Looper.getMainLooper())
    private val pluginScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mapper = jacksonObjectMapper()

    var lastResumeWatching: List<Any>? = null
    var counterBackup = 0
    private var backupDebounceJob: Job? = null

    private var currentPollingInterval = 5000L
    private val minPollingInterval = 5000L
    private val maxPollingInterval = 60000L

    private val runnable = object : Runnable {
        override fun run() {
            pluginScope.launch {
                try {
                    val list = HomeViewModel.getResumeWatching() as? List<Any>
                    val listTake = list?.take(10)
                    if (listTake != lastResumeWatching) {
                        Log.d("SyncCS", "Watch history change detected. Triggering Debounce Backup...")
                        triggerBackupDebounced()
                        lastResumeWatching = listTake
                    }

                    if (counterBackup >= 15) {
                        val success = restoreDevice()
                        if (success.first) {
                            currentPollingInterval = minPollingInterval
                        } else {
                            currentPollingInterval = (currentPollingInterval * 2).coerceAtMost(maxPollingInterval)
                        }
                        counterBackup = 0
                    } else {
                        counterBackup++
                    }
                } catch (e: Exception) {
                    currentPollingInterval = (currentPollingInterval * 2).coerceAtMost(maxPollingInterval)
                }
            }
            handler.postDelayed(this, currentPollingInterval)
        }
    }

    override fun load(context: Context) {
        val deviceId = Util.getDeviceId(context)
        SharedPrefsHelper.setKey("device_id", deviceId, context)
        Log.d("SyncCS", "Plugin Loaded. This Device ID: $deviceId")

        pluginScope.launch { restoreDevice() }

        handler.post(runnable)

        val backupTrigger = { _: Boolean -> triggerBackupDebounced() }
        MainActivity.Companion.bookmarksUpdatedEvent.plusAssign(backupTrigger)
        MainActivity.Companion.afterPluginsLoadedEvent.plusAssign(backupTrigger)
        MainActivity.Companion.mainPluginsLoadedEvent.plusAssign(backupTrigger)
        MainActivity.Companion.reloadHomeEvent.plusAssign(backupTrigger)
        MainActivity.Companion.reloadAccountEvent.plusAssign(backupTrigger)

        openSettings = { ctx ->
            val currentActivity = ctx as? AppCompatActivity 
                ?: AcraApplication.context as? AppCompatActivity 
                ?: CloudStreamApp.context as? AppCompatActivity

            if (currentActivity != null && !currentActivity.isDestroyed && !currentActivity.isFinishing) {
                SyncSettingsFragment(this@SyncPlugin).show(currentActivity.supportFragmentManager, "Sync")
            } else {
                Log.e("SyncCS", "Failed to open settings: Activity context is invalid.")
            }
        }
    }

    fun triggerBackupDebounced() {
        backupDebounceJob?.cancel()
        backupDebounceJob = pluginScope.launch {
            delay(15000L)
            backupDevice()
        }
    }

    suspend fun forceBackupNow(): Pair<Boolean, String> {
        backupDebounceJob?.cancel()
        Log.d("SyncCS", "Manual Push (Backup) Triggered!")
        return backupDevice()
    }

    private suspend fun backupDevice(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val context = AcraApplication.context ?: CloudStreamApp.context ?: return@withContext Pair(false, "Context is null")
        val backupEnabled = SharedPrefsHelper.getKey("backup_device", String::class.java, "false", context) == "true"
        val syncPluginsEnabled = SharedPrefsHelper.getKey("sync_plugins_enabled", String::class.java, "false", context) == "true"

        if (!backupEnabled) {
            return@withContext Pair(false, "Backup is disabled in settings. Enable 'Backup data to Cloud' first.")
        }
        if (!ApiUtils.isLoggedIn(context)) {
            return@withContext Pair(false, "Not logged in or missing project details. Save credentials first.")
        }

        // Properly get and limit resumeWatching list (max 50 to prevent overflow)
        val rawResumeWatching = try {
            HomeViewModel.getResumeWatching()
        } catch (e: Exception) {
            null
        }
        val resumeWatching = rawResumeWatching?.take(50)

        val backupFile = BackupUtils.getFullBackup(context, resumeWatching, syncPluginsEnabled)
            ?: return@withContext Pair(false, "No valid data found to backup.")
        
        val json = mapper.writeValueAsString(backupFile)
        if (json.isEmpty()) {
            return@withContext Pair(false, "Backup payload is empty.")
        }

        val cleaned = Util.cleanedSyncData(json)
        val originalLength = cleaned.length

        // Correctly match the original Base64 encoding structure (Flag 10)
        val compressed = Util.compressJsonToString(cleaned)
        val compressedLength = compressed.length
        Log.d("SyncCS", "Original Size: $originalLength chars, Compressed Size: $compressedLength chars")
        
        val result = ApiUtils.syncThisDevice(compressed, context)
        if (result.first) {
            SharedPrefsHelper.setKey("last_sync_timestamp", System.currentTimeMillis(), context)
            Log.d("SyncCS", "Backup Complete. Timestamp updated.")
            return@withContext Pair(true, "Backup Succeeded! (Original: $originalLength -> Compressed: $compressedLength chars)")
        } else {
            return@withContext Pair(false, "${result.second} (Original: $originalLength -> Compressed: $compressedLength chars)")
        }
    }

    suspend fun restoreDevice(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val context = AcraApplication.context ?: CloudStreamApp.context ?: return@withContext Pair(false, "Context is null")
        val restoreEnabled = SharedPrefsHelper.getKey("restore_device", String::class.java, "false", context) == "true"
        val syncPluginsEnabled = SharedPrefsHelper.getKey("sync_plugins_enabled", String::class.java, "false", context) == "true"

        if (!restoreEnabled) {
            return@withContext Pair(false, "Restore is disabled in settings. Enable 'Restore data from Cloud' first.")
        }
        if (!ApiUtils.isLoggedIn(context)) {
            return@withContext Pair(false, "Not logged in or missing project details. Save credentials first.")
        }

        Log.d("SyncCS", "--- Initiating Restore Process ---")
        try {
            val devices = ApiUtils.fetchDevices(context)
            val thisDeviceId = Util.getDeviceId(context)
            var restoredAny = false

            for (device in devices) {
                if (device.deviceId == thisDeviceId) continue

                val syncedData = device.syncedData ?: continue
                if (syncedData.isNotEmpty()) {
                    val decompressed = Util.restoreText(Util.decompressJsonFromString(syncedData))
                    val backupFile = mapper.readValue(decompressed, BackupFile::class.java)

                    if (backupFile != null) {
                        withContext(Dispatchers.Main) {
                            BackupUtils.restore(context, backupFile, true, true, syncPluginsEnabled)
                        }
                        restoredAny = true
                    }
                }
            }
            if (restoredAny) {
                return@withContext Pair(true, "Restore completed. Please restart the app.")
            } else {
                return@withContext Pair(false, "No other device backups found on GitHub Project.")
            }
        } catch (e: Exception) {
            Log.e("SyncCS", "Restore Process Failed", e)
            return@withContext Pair(false, "Restore failed: ${e.message}")
        }
    }
}
