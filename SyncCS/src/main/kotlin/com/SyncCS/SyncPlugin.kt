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

    private var lastResumeWatching: List<Any>? = null
    private var counterBackup = 0
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
        val deviceId = Util.getDeviceId()
        SharedPrefsHelper.getPrefs().edit().putString("device_id", deviceId).apply()
        Log.d("SyncCS", "Plugin Loaded. This Device ID: $deviceId")

        pluginScope.launch { restoreDevice() }

        handler.post(runnable)

        val backupTrigger = { _: Boolean -> triggerBackupDebounced() }
        MainActivity.bookmarksUpdatedEvent.plusAssign(backupTrigger)
        MainActivity.afterPluginsLoadedEvent.plusAssign(backupTrigger)
        MainActivity.mainPluginsLoadedEvent.plusAssign(backupTrigger)
        MainActivity.reloadHomeEvent.plusAssign(backupTrigger)
        MainActivity.reloadAccountEvent.plusAssign(backupTrigger)

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
        val pref = SharedPrefsHelper.getPrefs()
        val backupEnabled = pref.getString("backup_device", "false") == "true"
        val syncPluginsEnabled = pref.getString("sync_plugins_enabled", "false") == "true"

        if (!backupEnabled) {
            return@withContext Pair(false, "Backup is disabled in settings. Enable 'Backup data to Cloud' first.")
        }
        if (!ApiUtils.isLoggedIn()) {
            return@withContext Pair(false, "Not logged in or missing project details. Save credentials first.")
        }

        val backupFile = BackupUtils.getFullBackup(syncPluginsEnabled)
            ?: return@withContext Pair(false, "No valid data found to backup.")
        
        val json = mapper.writeValueAsString(backupFile)
        if (json.isEmpty()) {
            return@withContext Pair(false, "Backup payload is empty.")
        }

        val cleaned = Util.cleanedSyncData(json)
        val originalLength = cleaned.length

        // 깃허브 업로드용 초고압축 수행 및 글자 수 수치화
        val compressed = Util.compressJsonToString(cleaned)
        val compressedLength = compressed.length
        Log.d("SyncCS", "Original Size: $originalLength chars, Compressed Size: $compressedLength chars")
        
        val result = ApiUtils.syncThisDevice(compressed)
        if (result.first) {
            pref.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply()
            Log.d("SyncCS", "Backup Complete. Timestamp updated.")
            return@withContext Pair(true, "Backup Succeeded! (Original: $originalLength -> Compressed: $compressedLength chars)")
        } else {
            // 실패 시 원본 크기와 실제 압축된 페이로드 크기 정보를 에러 메시지와 함께 반환합니다.
            return@withContext Pair(false, "${result.second} (Original: $originalLength -> Compressed: $compressedLength chars)")
        }
    }

    suspend fun restoreDevice(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val context = AcraApplication.context ?: CloudStreamApp.context ?: return@withContext Pair(false, "Context is null")
        val pref = SharedPrefsHelper.getPrefs()
        val restoreEnabled = pref.getString("restore_device", "false") == "true"
        val syncPluginsEnabled = pref.getString("sync_plugins_enabled", "false") == "true"

        if (!restoreEnabled) {
            return@withContext Pair(false, "Restore is disabled in settings. Enable 'Restore data from Cloud' first.")
        }
        if (!ApiUtils.isLoggedIn()) {
            return@withContext Pair(false, "Not logged in or missing project details. Save credentials first.")
        }

        Log.d("SyncCS", "--- Initiating Restore Process ---")
        try {
            val devices = ApiUtils.fetchDevices()
            val thisDeviceId = Util.getDeviceId()
            var restoredAny = false

            for (device in devices) {
                if (device.deviceId == thisDeviceId) continue

                val syncedData = device.syncedData ?: continue
                if (syncedData.isNotEmpty()) {
                    val decompressed = Util.restoreText(Util.decompressJsonFromString(syncedData))
                    val backupFile = mapper.readValue(decompressed, BackupFile::class.java)

                    if (backupFile != null) {
                        withContext(Dispatchers.Main) {
                            BackupUtils.restore(backupFile, true, true, syncPluginsEnabled)
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
