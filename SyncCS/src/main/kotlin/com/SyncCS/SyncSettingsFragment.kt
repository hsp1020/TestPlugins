package com.SyncCS

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
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

class SyncSettingsFragment(
    private val plugin: Plugin,
    private val pluginContext: Context
) : BottomSheetDialogFragment() {

    private val res = pluginContext.resources
    private val resPackageName = pluginContext.packageName

    private fun <T : View> findView(view: View, name: String): T {
        val id = res.getIdentifier(name, "id", resPackageName)
        if (id == 0) error("View ID not found: $name")
        return view.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", resPackageName)
        if (id == 0) error("Layout XML not found: $name")
        val pluginInflater = inflater.cloneInContext(pluginContext)
        return pluginInflater.inflate(id, container, false)
    }

    private fun getDrawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", resPackageName)
        return if (id != 0) ResourcesCompat.getDrawable(res, id, null) else null
    }

    private fun makeTvCompatible(view: View) {
        val id = res.getIdentifier("outline", "drawable", resPackageName)
        if (id != 0) {
            view.background = ResourcesCompat.getDrawable(res, id, null)
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val layout = getLayout("settings", inflater, container)
        
        findView<TextView>(layout, "config_text").text = "Config"
        findView<TextView>(layout, "group_text").text = "Devices"

        val buttonConfig = findView<ImageView>(layout, "button_config")
        buttonConfig.setImageDrawable(getDrawable("edit_icon"))
        makeTvCompatible(buttonConfig)
        
        buttonConfig.setOnClickListener {
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
                    
                    loadingDialog.show()
                    
                    AcraApplication.setKey("sync_token", token)
                    AcraApplication.setKey("sync_project_num", projectNum)
                    AcraApplication.setKey("device_name", deviceName)
                    AcraApplication.setKey("backup_device", backupSwitch.isChecked.toString())
                    AcraApplication.setKey("restore_device", restoreSwitch.isChecked.toString())

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val (success, msg) = ApiUtils.syncProjectDetails(context)
                            if (!success) {
                                withContext(Dispatchers.Main) {
                                    loadingDialog.dismiss()
                                    CommonActivity.showToast(msg, null)
                                    AcraApplication.setKey("sync_token", "")
                                }
                                return@launch
                            }

                            val devices = ApiUtils.fetchDevices()
                            val currentDeviceId = UtilKt.getDeviceId(pluginContext.packageName, requireContext())
                            val isDuplicate = devices.any { it.name == deviceName && it.deviceId != currentDeviceId }
                            
                            if (isDuplicate) {
                                withContext(Dispatchers.Main) {
                                    loadingDialog.dismiss()
                                    CommonActivity.showToast("Error: Device name '$deviceName' already exists!", null)
                                }
                                return@launch
                            }

                            // ★★★ 에러 발생 원인 수정: getBackup() 인자를 1개로 변경하여 컴파일 에러 해결 ★★★
                            val backupFile = BackupUtils.getBackup(requireContext())
                            val backupJson = mapper.writeValueAsString(backupFile)
                            val (syncSuccess, syncMsg) = ApiUtils.syncThisDevice(backupJson, requireContext())

                            withContext(Dispatchers.Main) {
                                loadingDialog.dismiss()
                                if (syncSuccess) {
                                    CommonActivity.showToast("Successfully synced to GitHub!", null)
                                    dismiss()
                                } else {
                                    CommonActivity.showToast("Sync Failed: $syncMsg", null)
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                loadingDialog.dismiss()
                                CommonActivity.showToast("Error syncing: ${e.message}", null)
                            }
                        }
                    }
                }
                .setNegativeButton("Reset") { _, _ ->
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
            val loadingDialog = AlertDialog.Builder(requireContext())
                .setView(getLayout("loading", inflater, container))
                .setCancelable(false)
                .create()
            loadingDialog.show()

            CoroutineScope(Dispatchers.IO).launch {
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
        val listLayout = getLayout("device_list", inflater, container)
        val containerLayout = findView<LinearLayout>(listLayout, "device_list_container")

        if (devices.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = "No devices synced yet."
                setPadding(16, 16, 16, 16)
            }
            containerLayout.addView(emptyText)
        } else {
            for (device in devices) {
                val itemLayout = getLayout("device_item", inflater, containerLayout)
                val nameText = findView<TextView>(itemLayout, "device_name_text")
                val deleteBtn = findView<Button>(itemLayout, "btn_delete_device")

                nameText.text = "${device.name}\n(${device.deviceId})"
                
                deleteBtn.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        val projectId = AcraApplication.getKey<String>("sync_project_id") ?: return@launch
                        val success = ApiUtils.deleteDevice(projectId, device.itemId)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                containerLayout.removeView(itemLayout)
                                CommonActivity.showToast("Device deleted", null)
                            } else {
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
