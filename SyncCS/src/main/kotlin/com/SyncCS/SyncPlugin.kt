package com.anhdaden

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.DataStoreHelper.ResumeWatchingResult
import kotlinx.coroutines.*

@CloudstreamPlugin
class SyncPlugin : Plugin() {

    private val handler = Handler(Looper.getMainLooper())
    private val pluginScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastResumeWatching: List<ResumeWatchingResult>? = null
    private var counterBackup = 0
    private var backupDebounceJob: Job? = null

    // 지수 백오프 기반 주파수 조절 변수 (Rate Limit 대응)
    private var currentPollingInterval = 5000L
    private val minPollingInterval = 5000L
    private val maxPollingInterval = 60000L

    private val runnable = object : Runnable {
        override fun run() {
            pluginScope.launch {
                try {
                    val list = HomeViewModel.Companion.getResumeWatching()
                    val listTake = list?.take(10)
                    if (listTake != lastResumeWatching) {
                        triggerBackupDebounced()
                        lastResumeWatching = listTake
                    }

                    if (counterBackup >= 15) {
                        val success = restoreDevice()
                        if (success) {
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
        val deviceId = Util.getDeviceId(context.packageName, context)
        AcraApplication.Companion.setKey("device_id", deviceId)

        pluginScope.launch { restoreDevice() }

        handler.post(runnable)

        val backupTrigger = { _: Boolean -> triggerBackupDebounced() }
        MainActivity.Companion.getBookmarksUpdatedEvent().plusAssign(backupTrigger)
        MainActivity.Companion.getAfterPluginsLoadedEvent().plusAssign(backupTrigger)
        MainActivity.Companion.getMainPluginsLoadedEvent().plusAssign(backupTrigger)
        MainActivity.Companion.getReloadHomeEvent().plusAssign(backupTrigger)
        MainActivity.Companion.getReloadAccountEvent().plusAssign(backupTrigger)

        val activity = context as? AppCompatActivity ?: return
        setOpenSettings {
            SyncSettingsFragment(this).show(activity.supportFragmentManager, "Sync")
            kotlin.Unit
        }
    }

    // 15초의 디바운스를 주어 실시간 시청 도중 불필요한 백업 주기 발생을 방지
    fun triggerBackupDebounced() {
        backupDebounceJob?.cancel()
        backupDebounceJob = pluginScope.launch {
            delay(15000L)
            backupDevice()
        }
    }

    private suspend fun backupDevice() = withContext(Dispatchers.IO) {
        val context = AcraApplication.Companion.getContext() ?: return@withContext
        val pref = DataStore.INSTANCE.getSharedPrefs(context)
        val backupEnabled = pref.getString("backup_device", "false") == "true"
        val syncPluginsEnabled = pref.getString("sync_plugins_enabled", "false") == "true"

        if (backupEnabled && ApiUtils.isLoggedIn()) {
            val lastSyncTime = pref.getLong("last_sync_timestamp", 0L)
            val backupFile = BackupUtils.getDeltaBackup(context, lastSyncTime, syncPluginsEnabled) ?: return@withContext
            val json = AppUtils.INSTANCE.toJson(backupFile)

            if (json.isNotEmpty()) {
                val compressed = Util.compressJsonToString(Util.cleanedSyncData(json))
                val result = ApiUtils.syncThisDevice(compressed, context)
                if (result.first) {
                    pref.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply()
                }
            }
        }
    }

    suspend fun restoreDevice(): Boolean = withContext(Dispatchers.IO) {
        val context = AcraApplication.Companion.getContext() ?: return@withContext false
        val pref = DataStore.INSTANCE.getSharedPrefs(context)
        val restoreEnabled = pref.getString("restore_device", "false") == "true"
        val syncPluginsEnabled = pref.getString("sync_plugins_enabled", "false") == "true"

        if (restoreEnabled && ApiUtils.isLoggedIn()) {
            try {
                val devices = ApiUtils.fetchDevices(context)
                val thisDeviceId = Util.getDeviceId(context.packageName, context)

                for (device in devices) {
                    if (device.deviceId == thisDeviceId) continue

                    val syncedData = device.syncedData ?: continue
                    if (syncedData.isNotEmpty()) {
                        val decompressed = Util.restoreText(Util.decompressJsonFromString(syncedData))
                        val backupFile = AppUtils.INSTANCE.toObjectOrNull<BackupFile>(decompressed)

                        if (backupFile != null) {
                            withContext(Dispatchers.Main) {
                                BackupUtils.restore(context, backupFile, true, true, syncPluginsEnabled)
                            }
                        }
                    }
                }
                return@withContext true
            } catch (e: Exception) {
                return@withContext false
            }
        }
        return@withContext true
    }

    override fun unload() {
        handler.removeCallbacks(runnable)
        pluginScope.cancel()
        super.unload()
    }
}
