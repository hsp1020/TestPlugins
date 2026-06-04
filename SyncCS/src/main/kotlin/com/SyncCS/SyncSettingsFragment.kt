package com.SyncCS

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

class SyncSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")

    private fun <T : View> findView(view: View, name: String): T {
        val id = res.getIdentifier(name, "id", "com.SyncCS")
        return view.findViewById(id) as T
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", "com.SyncCS")
        val parser = res.getLayout(id)
        return inflater.inflate(parser as XmlPullParser, container, false)
    }

    private fun getDrawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", "com.SyncCS")
        return ResourcesCompat.getDrawable(res, id, null)
    }

    private fun makeTvCompatible(view: View) {
        val id = res.getIdentifier("outline", "drawable", "com.SyncCS")
        view.background = ResourcesCompat.getDrawable(res, id, null)
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val layout = getLayout("settings", inflater, container)

        findView<TextView>(layout, "config_text").text = "Config"
        findView<TextView>(layout, "group_text").text = "Guide"

        val configBtn = findView<ImageView>(layout, "button_config")
        configBtn.setImageDrawable(getDrawable("edit_icon"))
        makeTvCompatible(configBtn)
        configBtn.setOnClickListener {
            showConfigDialog(inflater, container)
        }

        val guideBtn = findView<ImageView>(layout, "button_group")
        guideBtn.setImageDrawable(getDrawable("view_icon"))
        makeTvCompatible(guideBtn)
        guideBtn.setOnClickListener {
            val guideLayout = getLayout("guide", inflater, container)
            context?.let { ctx ->
                AlertDialog.Builder(ctx)
                    .setTitle("Guide")
                    .setView(guideLayout)
                    .show()
            }
        }

        return layout
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showConfigDialog(inflater: LayoutInflater, container: ViewGroup?) {
        val ctx = context ?: return
        val configLayout = getLayout("config", inflater, container) as ViewGroup

        val tokenInput = findView<EditText>(configLayout, "token")
        val projectNumInput = findView<EditText>(configLayout, "project_num")
        val backupSwitch = findView<Switch>(configLayout, "backup_device")
        val restoreSwitch = findView<Switch>(configLayout, "restore_device")

        val pref = DataStore.getSharedPrefs(ctx)

        val syncPluginsSwitch = Switch(ctx).apply {
            text = "Automated Plugin Sync"
            isChecked = pref.getString("sync_plugins_enabled", "false") == "true"
        }
        configLayout.addView(syncPluginsSwitch)

        val manualSyncBtn = Button(ctx).apply {
            text = "Force Sync Now"
            setOnClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    CommonActivity.showToast("Manual sync started...", null)
                    withContext(Dispatchers.IO) {
                        val pluginInstance = plugin as? SyncPlugin
                        pluginInstance?.triggerBackupDebounced()
                        pluginInstance?.restoreDevice()
                    }
                    CommonActivity.showToast("Sync completed!", null)
                }
            }
        }
        configLayout.addView(manualSyncBtn)

        val deviceManagerContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 20, 10, 10)
        }
        val deviceTitle = TextView(ctx).apply {
            text = "Registered Devices"
            textSize = 16f
            setPadding(0, 0, 0, 10)
        }
        deviceManagerContainer.addView(deviceTitle)

        CoroutineScope(Dispatchers.IO).launch {
            if (ApiUtils.isLoggedIn()) {
                val devices = ApiUtils.fetchDevices(ctx)
                withContext(Dispatchers.Main) {
                    for (device in devices) {
                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, 5, 0, 5)
                        }
                        val nameText = TextView(ctx).apply {
                            text = device.name
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        val deleteBtn = Button(ctx).apply {
                            text = "Unlink"
                            setOnClickListener {
                                CoroutineScope(Dispatchers.IO).launch {
                                    val token = pref.getString("sync_token", null)?.replace("\"", "") ?: return@launch
                                    val projectId = pref.getString("sync_project_id", null)?.replace("\"", "") ?: return@launch
                                    val query = """
                                        mutation {
                                            deleteProjectV2Item(input: {projectId: "$projectId", itemId: "${device.itemId}"}) {
                                                deletedItemId
                                            }
                                        }
                                    """.trimIndent()
                                    ApiUtils.apiCall(query, token)
                                    withContext(Dispatchers.Main) {
                                        row.visibility = View.GONE
                                        CommonActivity.showToast("Device removed", null)
                                    }
                                }
                            }
                        }
                        row.addView(nameText)
                        row.addView(deleteBtn)
                        deviceManagerContainer.addView(row)
                    }
                }
            }
        }
        configLayout.addView(deviceManagerContainer)

        tokenInput.setText(pref.getString("sync_token", ""))
        projectNumInput.setText(pref.getString("sync_project_num", ""))
        backupSwitch.isChecked = pref.getString("backup_device", "false") == "true"
        restoreSwitch.isChecked = pref.getString("restore_device", "false") == "true"

        val loadingLayout = getLayout("loading", inflater, container)
        val loadingDialog = AlertDialog.Builder(ctx)
            .setView(loadingLayout)
            .setCancelable(false)
            .create()

        AlertDialog.Builder(ctx)
            .setTitle("Configuration")
            .setView(configLayout)
            .setPositiveButton("Save") { _, _ ->
                val token = tokenInput.text.toString().trim()
                val projectNum = projectNumInput.text.toString().trim()

                if (token.isEmpty() || projectNum.isEmpty()) {
                    CommonActivity.showToast("Please fill in all information", null)
                    return@setPositiveButton
                }

                loadingDialog.show()

                AcraApplication.setKey("sync_token", token)
                AcraApplication.setKey("sync_project_num", projectNum)
                AcraApplication.setKey("backup_device", backupSwitch.isChecked.toString())
                AcraApplication.setKey("restore_device", restoreSwitch.isChecked.toString())
                AcraApplication.setKey("sync_plugins_enabled", syncPluginsSwitch.isChecked.toString())

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val result = ApiUtils.syncProjectDetails(ctx)
                        CoroutineScope(Dispatchers.Main).launch {
                            loadingDialog.dismiss()
                            CommonActivity.showToast(result.second, null)
                            if (result.first) {
                                dismiss()
                            }
                        }
                    } catch (e: Exception) {
                        CoroutineScope(Dispatchers.Main).launch {
                            loadingDialog.dismiss()
                            CommonActivity.showToast("Error syncing: ${e.message}", null)
                        }
                    }
                }
            }
            .setNegativeButton("Reset") { _, _ ->
                AcraApplication.setKey("sync_token", "")
                AcraApplication.setKey("sync_project_num", "")
                AcraApplication.setKey("sync_project_id", "")
                AcraApplication.setKey("sync_item_id", "")
                AcraApplication.setKey("last_sync_timestamp", "0")
                CommonActivity.showToast("Credentials removed", null)
                dismiss()
            }
            .show()
    }
}
