package com.anhdaden

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DataStoreHelper
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

    private fun backupDevice(unused: Boolean) {
        try {
            this.counterBackup = 0
            CoroutineScope(Dispatchers.IO).launch {
                val context = AcraApplication.Companion.getContext()
                var backupDeviceStr: String? = null
                if (context != null) {
                    val dataStore = com.lagradost.cloudstream3.utils.DataStore.INSTANCE
                    try {
                        val raw = dataStore.getSharedPrefs(context).getString("backup_device", null)
                        if (raw != null) {
                            backupDeviceStr = dataStore.getMapper().readValue(raw, String::class.java)
                        }
                    } catch (unused2: Exception) {
                    }
                }
                if (backupDeviceStr == "true") {
                    val context2 = AcraApplication.Companion.getContext()
                    val resumeWatching = com.lagradost.cloudstream3.ui.home.HomeViewModel.Companion.getResumeWatching()
                    val backupFile = BackupUtils.getBackup(context2, resumeWatching)
                    val json = if (backupFile != null) {
                        com.lagradost.cloudstream3.utils.AppUtils.INSTANCE.toJson(backupFile) ?: ""
                    } else ""
                    ApiUtils.INSTANCE.syncThisDevice(json)
                }
            }
        } catch (unused: Exception) {
        }
    }

    private fun restoreDevice(unused: Boolean) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                val context = AcraApplication.Companion.getContext()
                var restoreDeviceStr: String? = null
                if (context != null) {
                    val dataStore = com.lagradost.cloudstream3.utils.DataStore.INSTANCE
                    try {
                        val raw = dataStore.getSharedPrefs(context).getString("restore_device", null)
                        if (raw != null) {
                            restoreDeviceStr = dataStore.getMapper().readValue(raw, String::class.java)
                        }
                    } catch (unused2: Exception) {
                    }
                }
                if (restoreDeviceStr == "true" && ApiUtils.INSTANCE.isLoggedIn()) {
                    val devices = ApiUtils.INSTANCE.fetchDevices()
                    val context2 = AcraApplication.Companion.getContext()
                    val thisDeviceId = if (context2 != null) {
                        UtilKt.getDeviceId(context2.packageName, context2)
                    } else ""
                    if (devices != null) {
                        for (device in devices) {
                            if (device.deviceId != thisDeviceId) {
                                val syncedData = device.syncedData
                                if (!syncedData.isNullOrEmpty()) {
                                    val json = UtilKt.restoreText(UtilKt.decompressJsonFromString(syncedData))
                                    val mapper = com.lagradost.cloudstream3.utils.DataStore.INSTANCE.getMapper()
                                    val backupFile = mapper.readValue(json, BackupFile::class.java)
                                    if (backupFile != null && context2 != null) {
                                        withContext(Dispatchers.Main) {
                                            BackupUtils.restore(context2, backupFile, true, true)
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
        setOpenSettings { ctx ->
            SyncSettingsFragment(this).show(appCompatActivity.supportFragmentManager, "Sync")
            kotlin.Unit
        }
    }
}
