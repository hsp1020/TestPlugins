package com.SyncCS

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DataStore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

    private val res = plugin.resources ?: error("Resources not found")
    private val resPackageName = "com.SyncCS"

    private fun <T : View> findView(view: View, name: String): T {
        val id = res.getIdentifier(name, "id", resPackageName)
        if (id == 0) error("View ID not found: $name")
        return view.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", resPackageName)
        if (id == 0) error("Layout XML not found: $name")
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun getDrawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", resPackageName)
        return if (id != 0) ResourcesCompat.getDrawable(res, id, null) else null
    }

    private fun makeTvCompatible(view: View) {
        val id = res.getIdentifier("outline", "drawable", resPackageName)
        if (id != 0) {
            view.background = res.getDrawable(id, null)
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Log.d("SYNC", "[SyncSettingsFragment] onCreateView() called.")
        val layout = getLayout("settings", inflater, container)
        
        findView<TextView>(layout, "config_text").text = "Config"
        findView<TextView>(layout, "group_text").text = "Devices"

        val buttonConfig = findView<ImageView>(layout, "button_config")
        buttonConfig.setImageDrawable(getDrawable("edit_icon"))
        makeTvCompatible(buttonConfig)
        
        buttonConfig.setOnClickListener {
            Log.d("SYNC", "[SyncSettingsFragment] Config button clicked.")
            val configLayout = getLayout("config", inflater, container) as ViewGroup
            val tokenEdit = findView<EditText>(configLayout, "token")
            val projectNumEdit = findView<EditText>(configLayout, "project_num")
            val deviceNameEdit = findView<EditText>(configLayout, "device_name")
            val backupSwitch = findView<Switch>(configLayout, "backup_device")
            val restoreSwitch = findView<Switch>(configLayout, "restore_device")

            val ctx = AcraApplication.context
            if (ctx != null) {
                val prefs = with(DataStore) { ctx.getSharedPrefs() }
                tokenEdit.setText(prefs.getString("sync_token", null)?.let { mapper.readValue<String>(it) })
                projectNumEdit.setText(prefs.getString("sync_project_num", null)?.let { mapper.readValue<String>(it) })
                
                val savedDeviceName = prefs.getString("device_name", null)?.let { mapper.readValue<String>(it) } ?: Build.MODEL
                deviceNameEdit.setText(savedDeviceName)
                
                backupSwitch.isChecked = prefs.getString("backup_device", null)?.let { mapper.readValue<String>(it) } == "true"
                restoreSwitch.isChecked = prefs.getString("restore_device", null)?.let { mapper.readValue<String>(it) } == "true"
            }

            backupSwitch.text = "Backup data to the cloud"
            restoreSwitch.text = "Recover data from cloud"

            val loadingLayout = getLayout("loading", inflater, container)
            val loadingDialog = AlertDialog.Builder(requireContext())
                .setView(loadingLayout)
                .setCancelable(false)
                .create()

            AlertDialog.Builder(requireContext())
                .setTitle("Config")
                .setView(configLayout)
                .setPositiveButton("Save") { _, _ ->
                    val token = tokenEdit.text.toString().trim()
                    val projectNum = projectNumEdit.text.toString().trim()
                    val deviceName = deviceNameEdit.text.toString().trim().ifEmpty { Build.MODEL }
                    
                    if (token.isEmpty() || projectNum.isEmpty()) {
                        CommonActivity.showToast("Please fill in all information", null)
                        return@setPositiveButton
                    }
                    
                    Log.d("SYNC", "[SyncSettingsFragment] Save clicked. Token/ProjectNum provided.")
                    loadingDialog.show()

                    // ★ [진단 코드 추가] 데이터를 수집 및 압축하기 전, 현재 로컬 저장소 상태를 분석하여 로그캣에 출력합니다.
                    try {
                        BackupUtils.analyzeBackupData(requireContext())
                    } catch (e: Exception) {
                        Log.e("SYNC", "진단 분석 실행 중 오류 발생: ${e.message}", e)
                    }
                    
                    AcraApplication.setKey("sync_token", token)
                    AcraApplication.setKey("sync_project_num", projectNum)
                    AcraApplication.setKey("device_name", deviceName)
                    AcraApplication.setKey("backup_device", backupSwitch.isChecked.toString())
                    AcraApplication.setKey("restore_device", restoreSwitch.isChecked.toString())

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            Log.d("SYNC", "[SyncSettingsFragment] Verifying project details...")
                            val (success, msg) = ApiUtils.syncProjectDetails(context)
                            if (!success) {
                                Log.d("SYNC", "[SyncSettingsFragment] Project verification failed: $msg")
                                withContext(Dispatchers.Main) {
                                    loadingDialog.dismiss()
                                    CommonActivity.showToast(msg, null)
                                    AcraApplication.setKey("sync_token", "")
                                }
                                return@launch
                            }

                            val devices = ApiUtils.fetchDevices()
                            
                            val currentDeviceId = try {
                                val prefs = with(DataStore) { requireContext().getSharedPrefs() }
                                prefs.getString("device_id", null)?.let { mapper.readValue<String>(it) }
                            } catch (e: Exception) {
                                null
                            } ?: UtilKt.getDeviceId(requireContext().packageName, requireContext())

                            val isDuplicate = devices.any { it.name == deviceName && it.deviceId != currentDeviceId }
                            
                            if (isDuplicate) {
                                Log.d("SYNC", "[SyncSettingsFragment] Duplicate device name found: $deviceName")
                                withContext(Dispatchers.Main) {
                                    loadingDialog.dismiss()
                                    CommonActivity.showToast("Error: Device name '$deviceName' already exists!", null)
                                }
                                return@launch
                            }

                            Log.d("SYNC", "[SyncSettingsFragment] Initiating immediate sync...")
                            val backupFile = BackupUtils.getBackup(requireContext())
                            val backupJson = mapper.writeValueAsString(backupFile)
                            val (syncSuccess, syncMsg) = ApiUtils.syncThisDevice(backupJson, requireContext())

                            withContext(Dispatchers.Main) {
                                loadingDialog.dismiss()
                                if (syncSuccess) {
                                    Log.d("SYNC", "[SyncSettingsFragment] Immediate sync successful.")
                                    CommonActivity.showToast("Successfully synced to GitHub!", null)
                                    dismiss()
                                } else {
                                    Log.d("SYNC", "[SyncSettingsFragment] Immediate sync failed: $syncMsg")
                                    CommonActivity.showToast("Sync Failed: $syncMsg", null)
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("SYNC", "[SyncSettingsFragment] Exception during save: ${e.message}")
                            withContext(Dispatchers.Main) {
                                loadingDialog.dismiss()
                                CommonActivity.showToast("Error syncing: ${e.message}", null)
                            }
                        }
                    }
                }
                .setNegativeButton("Reset") { _, _ ->
                    Log.d("SYNC", "[SyncSettingsFragment] Reset clicked. Clearing credentials.")
                    AcraApplication.setKey("sync_token", "")
                    AcraApplication.setKey("sync_project_num", "")
                    CommonActivity.showToast("Credentials removed", null)
                    dismiss()
                }
                .show()
        }

        val buttonGroup = findView<ImageView>(layout, "button_group")
        buttonGroup.setImageDrawable(getDrawable("view_icon"))
        makeTvCompatible(buttonGroup)
        
        buttonGroup.setOnClickListener {
            Log.d("SYNC", "[SyncSettingsFragment] Devices button clicked.")
            val loadingDialog = AlertDialog.Builder(requireContext())
                .setView(getLayout("loading", inflater, container))
                .setCancelable(false)
                .create()
            loadingDialog.show()

            CoroutineScope(Dispatchers.IO).launch {
                Log.d("SYNC", "[SyncSettingsFragment] Fetching devices for list dialog...")
                val devices = ApiUtils.fetchDevices()
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    showDeviceListDialog(devices, inflater, container)
                }
            }
        }

        return layout
    }

    private fun showDeviceListDialog(devices: List<SyncDevice>, inflater: LayoutInflater, container: ViewGroup?) {
        Log.d("SYNC", "[SyncSettingsFragment] Showing device list dialog. Count: ${devices.size}")
        val listLayout = getLayout("device_list", inflater, container)
        val containerLayout = findView<LinearLayout>(listLayout, "device_list_container")

        val ctx = requireContext()
        val currentDeviceId = try {
            val prefs = with(DataStore) { ctx.getSharedPrefs() }
            prefs.getString("device_id", null)?.let { mapper.readValue<String>(it) }
        } catch (e: Exception) {
            null
        } ?: UtilKt.getDeviceId(ctx.packageName, ctx)

        if (devices.isEmpty()) {
            val emptyText = TextView(ctx).apply {
                text = "No devices synced yet."
                setPadding(16, 16, 16, 16)
            }
            containerLayout.addView(emptyText)
        } else {
            for (device in devices) {
                val itemLayout = getLayout("device_item", inflater, containerLayout)
                val nameText = findView<TextView>(itemLayout, "device_name_text")
                val deleteBtn = findView<Button>(itemLayout, "btn_delete_device")

                val isCurrentDevice = device.deviceId == currentDeviceId
                if (isCurrentDevice) {
                    nameText.text = "${device.name} (Current)\n(${device.deviceId})"
                    nameText.setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    nameText.text = "${device.name}\n(${device.deviceId})"
                }
                
                deleteBtn.setOnClickListener {
                    Log.d("SYNC", "[SyncSettingsFragment] Delete clicked for device: ${device.name} (${device.deviceId})")
                    CoroutineScope(Dispatchers.IO).launch {
                        val projectId = AcraApplication.getKey<String>("sync_project_id") ?: return@launch
                        val success = ApiUtils.deleteDevice(projectId, device.itemId)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Log.d("SYNC", "[SyncSettingsFragment] Device deleted successfully.")
                                containerLayout.removeView(itemLayout)
                                CommonActivity.showToast("Device deleted", null)
                            } else {
                                Log.d("SYNC", "[SyncSettingsFragment] Failed to delete device.")
                                CommonActivity.showToast("Failed to delete", null)
                            }
                        }
                    }
                }
                containerLayout.addView(itemLayout)
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Synced Devices")
            .setView(listLayout)
            .setPositiveButton("Close", null)
            .show()
    }
}
