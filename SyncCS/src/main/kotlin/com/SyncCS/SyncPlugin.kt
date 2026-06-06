package com.SyncCS

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.mapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class SyncPlugin : Plugin() {
    private var counterBackup = 0
    private var lastResumeWatching: List<DataStoreHelper.ResumeWatchingResult>? = null
    private val handler = Handler(Looper.getMainLooper())

    private val runnable = object : Runnable {
        override fun run() {
            try {
                CoroutineScope(Dispatchers.IO).launch {
                    val list = com.lagradost.cloudstream3.ui.home.HomeViewModel.Companion.getResumeWatching()
                    val listTake = list?.take(10)
                    if (listTake != lastResumeWatching) {
                        backupDevice(true)
                        lastResumeWatching = listTake
                    }
                    if (counterBackup >= 15) {
                        restoreDevice(false)
                    }
                    counterBackup++
                }
                handler.postDelayed(this, 5000)
            } catch (unused: Exception) {
            }
        }
    }

    fun backupDevice(unused: Boolean) {
        try {
            this.counterBackup = 0
            CoroutineScope(Dispatchers.IO).launch {
                val context = AcraApplication.context
                var backupDeviceStr: String? = null
                if (context != null) {
                    try {
                        val raw = with(DataStore) { context.getSharedPrefs().getString("backup_device", null) }
                        if (raw != null) {
                            backupDeviceStr = mapper.readValue(raw, String::class.java)
                        }
                    } catch (unused2: Exception) {
                    }
                }
                if (backupDeviceStr == "true") {
                    val context2 = AcraApplication.context
                    val resumeWatching = com.lagradost.cloudstream3.ui.home.HomeViewModel.Companion.getResumeWatching()
                    val backupFile = BackupUtils.getBackup(context2, resumeWatching)
                    val json = if (backupFile != null) {
                        mapper.writeValueAsString(backupFile) ?: ""
                    } else ""
                    
                    if (json.isNotEmpty()) {
                        val cleaned = UtilKt.cleanedSyncData(json)
                        val compressed = UtilKt.compressJsonToString(cleaned)
                        ApiUtils.INSTANCE.syncThisDevice(compressed)
                    }
                }
            }
        } catch (unused: Exception) {
        }
    }

    fun restoreDevice(unused: Boolean) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                val context = AcraApplication.context
                var restoreDeviceStr: String? = null
                if (context != null) {
                    try {
                        val raw = with(DataStore) { context.getSharedPrefs().getString("restore_device", null) }
                        if (raw != null) {
                            restoreDeviceStr = mapper.readValue(raw, String::class.java)
                        }
                    } catch (unused2: Exception) {
                    }
                }
                if (restoreDeviceStr == "true" && ApiUtils.INSTANCE.isLoggedIn()) {
                    val devices = ApiUtils.INSTANCE.fetchDevices()
                    val context2 = AcraApplication.context
                    val thisDeviceId = if (context2 != null) {
                        UtilKt.getDeviceId(context2.packageName, context2)
                    } else ""
                    if (devices != null) {
                        for (device in devices) {
                            if (device.deviceId != thisDeviceId) {
                                val syncedData = device.syncedData
                                if (!syncedData.isNullOrEmpty()) {
                                    val decompressed = UtilKt.decompressJsonFromString(syncedData)
                                    if (decompressed.isNotEmpty()) {
                                        val json = UtilKt.restoreText(decompressed)
                                        if (json.isNotEmpty()) {
                                            try {
                                                val backupFile = mapper.readValue(json, BackupFile::class.java)
                                                if (backupFile != null && context2 != null) {
                                                    withContext(Dispatchers.Main) {
                                                        BackupUtils.restore(context2, backupFile, true, true)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e("SyncCS", "Error mapping device JSON: ${e.message}")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (unused: Exception) {
        }
    }

    override fun load(context: Context) {
        AcraApplication.Companion.setKey("device_id", UtilKt.getDeviceId(context.packageName, context))
        restoreDevice(true)
        handler.post(runnable)
        MainActivity.Companion.bookmarksUpdatedEvent.plusAssign { backupDevice(it) }
        MainActivity.Companion.afterPluginsLoadedEvent.plusAssign { backupDevice(it) }
        MainActivity.Companion.mainPluginsLoadedEvent.plusAssign { backupDevice(it) }
        MainActivity.Companion.reloadHomeEvent.plusAssign { backupDevice(it) }
        MainActivity.Companion.reloadAccountEvent.plusAssign { backupDevice(it) }

        val appCompatActivity = context as AppCompatActivity
        openSettings = { ctx ->
            SyncSettingsFragment(this).show(appCompatActivity.supportFragmentManager, "Sync")
        }
    }
}
