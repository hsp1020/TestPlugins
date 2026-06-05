package com.SyncCS

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 10, 0, 10) }
            setOnClickListener { showConfigDialog() }
        }
        layout.addView(configBtn)

        val guideBtn = Button(ctx).apply {
            text = "Read Guide"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 10, 0, 10) }
            setOnClickListener { showGuideDialog() }
        }
        layout.addView(guideBtn)

        return layout
    }

    private fun showGuideDialog() {
        val ctx = context ?: return
        val textView = TextView(ctx).apply {
            text = "1. GitHub에서 'project' 권한이 체크된 PAT 토큰을 발급하세요.\n\n" +
                   "2. GitHub 프로필에서 Project V2를 생성하고 프로젝트 번호를 확인하세요.\n\n" +
                   "3. Configuration 메뉴에 토큰과 번호를 입력한 뒤 'Save'를 누르세요.\n\n" +
                   "4. 동기화 후 앱을 완전히 종료(스와이프)하고 다시 켜야 데이터가 화면에 표시됩니다."
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
        val pref = SharedPrefsHelper.getPrefs()

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }
        val scrollView = ScrollView(ctx)
        scrollView.addView(layout)

        val tokenInput = EditText(ctx).apply {
            hint = "GitHub PAT Token (ghp_...)"
            setText(pref.getString("sync_token", ""))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        layout.addView(tokenInput)

        val projectNumInput = EditText(ctx).apply {
            hint = "GitHub Project Number (ex: 1)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(pref.getString("sync_project_num", ""))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
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

        val pullBtn = Button(ctx).apply {
            text = "⬇️ PULL: Download from Cloud"
            setOnClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    CommonActivity.showToast("Downloading data from GitHub...", null)
                    val success = withContext(Dispatchers.IO) {
                        (plugin as? SyncPlugin)?.restoreDevice()
                    }
                    if (success == true) {
                        CommonActivity.showToast("Success! Please restart the app to see changes.", null)
                    } else {
                        CommonActivity.showToast("Failed to pull data.", null)
                    }
                }
            }
        }
        layout.addView(pullBtn)

        val pushBtn = Button(ctx).apply {
            text = "⬆️ PUSH: Upload to Cloud"
            setOnClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    CommonActivity.showToast("Uploading data to GitHub...", null)
                    withContext(Dispatchers.IO) {
                        (plugin as? SyncPlugin)?.forceBackupNow()
                    }
                    CommonActivity.showToast("Upload completed!", null)
                }
            }
        }
        layout.addView(pushBtn)

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

        CoroutineScope(Dispatchers.IO).launch {
            if (ApiUtils.isLoggedIn()) {
                val devices = ApiUtils.fetchDevices()
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
                        val result = ApiUtils.syncProjectDetails()
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
