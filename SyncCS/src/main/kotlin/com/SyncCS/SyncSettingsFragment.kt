package com.SyncCS

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()

        // XML 없이 코드로 직접 "메인 설정 화면" 그리기
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val title = TextView(ctx).apply {
            text = "Cloudstream Sync Plugin"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 40)
        }
        layout.addView(title)

        val configBtn = Button(ctx).apply {
            text = "Configuration (Setup Sync)"
            setOnClickListener { showConfigDialog() }
        }
        layout.addView(configBtn)

        val guideBtn = Button(ctx).apply {
            text = "Read Guide"
            setOnClickListener { showGuideDialog() }
        }
        layout.addView(guideBtn)

        return layout
    }

    private fun showGuideDialog() {
        val ctx = context ?: return
        val textView = TextView(ctx).apply {
            text = "1. Go to GitHub and create a Personal Access Token (PAT) with 'project' permissions.\n\n" +
                   "2. Create a Project V2 on GitHub and obtain its project number.\n\n" +
                   "3. Enter the PAT and Project Number in the Configuration menu.\n\n" +
                   "4. Enable Backup/Restore and plugin sync as needed."
            setPadding(60, 60, 60, 60)
            textSize = 15f
        }
        AlertDialog.Builder(ctx)
            .setTitle("How to setup")
            .setView(textView)
            .setPositiveButton("Close", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun showConfigDialog() {
        val ctx = context ?: return
        val pref = SharedPrefsHelper.getPrefs(ctx)

        // XML 없이 코드로 직접 "Config 팝업창" 그리기
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }
        val scrollView = ScrollView(ctx)
        scrollView.addView(layout)

        val tokenInput = EditText(ctx).apply {
            hint = "GitHub PAT Token (ghp_...)"
            setText(pref.getString("sync_token", ""))
        }
        layout.addView(tokenInput)

        val projectNumInput = EditText(ctx).apply {
            hint = "GitHub Project Number (ex: 1)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(pref.getString("sync_project_num", ""))
        }
        layout.addView(projectNumInput)

        val backupSwitch = Switch(ctx).apply {
            text = "Backup data to Cloud"
            isChecked = pref.getString("backup_device", "false") == "true"
            setPadding(0, 30, 0, 30)
        }
        layout.addView(backupSwitch)

        val restoreSwitch = Switch(ctx).apply {
            text = "Restore data from Cloud"
            isChecked = pref.getString("restore_device", "false") == "true"
            setPadding(0, 0, 0, 30)
        }
        layout.addView(restoreSwitch)

        val syncPluginsSwitch = Switch(ctx).apply {
            text = "Automated Plugin Sync"
            isChecked = pref.getString("sync_plugins_enabled", "false") == "true"
            setPadding(0, 0, 0, 30)
        }
        layout.addView(syncPluginsSwitch)

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
        layout.addView(manualSyncBtn)

        val deviceTitle = TextView(ctx).apply {
            text = "Registered Devices (Device Manager)"
            textSize = 16f
            setPadding(0, 40, 0, 10)
        }
        layout.addView(deviceTitle)

        val deviceManagerContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(deviceManagerContainer)

        // 기기 관리자(Device Manager) 목록 불러오기
        CoroutineScope(Dispatchers.IO).launch {
            if (ApiUtils.isLoggedIn()) {
                val devices = ApiUtils.fetchDevices(ctx)
                withContext(Dispatchers.Main) {
                    if (devices.isEmpty()) {
                        deviceManagerContainer.addView(TextView(ctx).apply { text = "No devices found." })
                    } else {
                        for (device in devices) {
                            val row = LinearLayout(ctx).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(0, 10, 0, 10)
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
            } else {
                withContext(Dispatchers.Main) {
                    deviceManagerContainer.addView(TextView(ctx).apply { text = "Please save token and project number first." })
                }
            }
        }

        // 로딩 프로그레스바 다이얼로그
        val loadingView = ProgressBar(ctx).apply { setPadding(50, 50, 50, 50) }
        val loadingDialog = AlertDialog.Builder(ctx)
            .setView(loadingView)
            .setCancelable(false)
            .create()

        AlertDialog.Builder(ctx)
            .setTitle("Configuration")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val token = tokenInput.text.toString().trim()
                val projectNum = projectNumInput.text.toString().trim()

                if (token.isEmpty() || projectNum.isEmpty()) {
                    CommonActivity.showToast("Please fill in all information", null)
                    return@setPositiveButton
                }

                loadingDialog.show()

                pref.edit()
                    .putString("sync_token", token)
                    .putString("sync_project_num", projectNum)
                    .putString("backup_device", backupSwitch.isChecked.toString())
                    .putString("restore_device", restoreSwitch.isChecked.toString())
                    .putString("sync_plugins_enabled", syncPluginsSwitch.isChecked.toString())
                    .apply()

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val result = ApiUtils.syncProjectDetails(ctx)
                        withContext(Dispatchers.Main) {
                            loadingDialog.dismiss()
                            CommonActivity.showToast(result.second, null)
                            if (result.first) {
                                dismiss()
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
                pref.edit()
                    .putString("sync_token", "")
                    .putString("sync_project_num", "")
                    .putString("sync_project_id", "")
                    .putString("sync_item_id", "")
                    .putLong("last_sync_timestamp", 0L)
                    .apply()

                CommonActivity.showToast("Credentials removed", null)
                dismiss()
            }
            .show()
    }
}
