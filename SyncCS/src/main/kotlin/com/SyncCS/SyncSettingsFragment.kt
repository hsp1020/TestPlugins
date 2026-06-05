package com.SyncCS

import android.annotation.SuppressLint
import android.content.Context
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
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.mapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

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

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }
        val scrollView = ScrollView(ctx)
        scrollView.addView(layout)

        val tokenInput = EditText(ctx).apply {
            hint = "GitHub PAT Token (ghp_...)"
            var tokenVal: String? = null
            val context = AcraApplication.context
            if (context != null) {
                try {
                    val raw = with(DataStore) { context.getSharedPrefs().getString("sync_token", null) }
                    if (raw != null) {
                        tokenVal = mapper.readValue(raw, String::class.java)
                    }
                } catch (unused: Exception) {
                }
            }
            setText(tokenVal)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        layout.addView(tokenInput)

        val projectNumInput = EditText(ctx).apply {
            hint = "GitHub Project Number (ex: 1)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            var projectNumVal: String? = null
            val context2 = AcraApplication.context
            if (context2 != null) {
                try {
                    val raw = with(DataStore) { context2.getSharedPrefs().getString("sync_project_num", null) }
                    if (raw != null) {
                        projectNumVal = mapper.readValue(raw, String::class.java)
                    }
                } catch (unused: Exception) {
                }
            }
            setText(projectNumVal)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        layout.addView(projectNumInput)

        val backupSwitch = Switch(ctx).apply {
            text = "Backup data to Cloud"
            var backupVal: String? = null
            val context3 = AcraApplication.context
            if (context3 != null) {
                try {
                    val raw = with(DataStore) { context3.getSharedPrefs().getString("backup_device", null) }
                    if (raw != null) {
                        backupVal = mapper.readValue(raw, String::class.java)
                    }
                } catch (unused: Exception) {
                }
            }
            isChecked = (backupVal == "true")
            setPadding(0, 30, 0, 30)
        }
        layout.addView(backupSwitch)

        val restoreSwitch = Switch(ctx).apply {
            text = "Restore data from Cloud"
            var restoreVal: String? = null
            val context4 = AcraApplication.context
            if (context4 != null) {
                try {
                    val raw = with(DataStore) { context4.getSharedPrefs().getString("restore_device", null) }
                    if (raw != null) {
                        restoreVal = mapper.readValue(raw, String::class.java)
                    }
                } catch (unused: Exception) {
                }
            }
            isChecked = (restoreVal == "true")
            setPadding(0, 0, 0, 30)
        }
        layout.addView(restoreSwitch)

        val pullBtn = Button(ctx).apply {
            text = "⬇️ PULL: Download from Cloud"
            setOnClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    CommonActivity.showToast("Downloading data from GitHub...", null)
                    val result = withContext(Dispatchers.IO) {
                        (plugin as? SyncPlugin)?.restoreDevice(true)
                    }
                    CommonActivity.showToast("Pull executed", null)
                }
            }
        }
        layout.addView(pullBtn)

        val pushBtn = Button(ctx).apply {
            text = "⬆️ PUSH: Upload to Cloud"
            setOnClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    CommonActivity.showToast("Uploading data to GitHub...", null)
                    val result = withContext(Dispatchers.IO) {
                        (plugin as? SyncPlugin)?.backupDevice(true)
                    }
                    CommonActivity.showToast("Push executed", null)
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
            if (ApiUtils.INSTANCE.isLoggedIn()) {
                val devices = ApiUtils.INSTANCE.fetchDevices()
                withContext(Dispatchers.Main) {
                    if (devices.isNullOrEmpty()) {
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
                                        var token: String? = null
                                        var projectId: String? = null
                                        try {
                                            val rawToken = with(DataStore) { ctx.getSharedPrefs().getString("sync_token", null) }
                                            if (rawToken != null) {
                                                token = mapper.readValue(rawToken, String::class.java)
                                            }
                                            val rawProjectId = with(DataStore) { ctx.getSharedPrefs().getString("sync_project_id", null) }
                                            if (rawProjectId != null) {
                                                projectId = mapper.readValue(rawProjectId, String::class.java)
                                            }
                                        } catch (e: Exception) {
                                        }
                                        if (token.isNullOrEmpty() || projectId.isNullOrEmpty()) return@launch

                                        val query = """
                                            mutation {
                                                deleteProjectV2Item(input: {projectId: "$projectId", itemId: "${device.itemId}"}) {
                                                    deletedItemId
                                                }
                                            }
                                        """.trimIndent()
                                        ApiUtils.INSTANCE.apiCall(query)
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
            .setTitle("Config")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val token = tokenInput.text.toString().trim()
                val projectNum = projectNumInput.text.toString().trim()

                if (token.isEmpty() || projectNum.isEmpty()) {
                    CommonActivity.showToast("Please fill in all information", null)
                    return@setPositiveButton
                }

                loadingDialog.show()

                AcraApplication.Companion.setKey("sync_token", token)
                AcraApplication.Companion.setKey("sync_project_num", projectNum)
                AcraApplication.Companion.setKey("backup_device", backupSwitch.isChecked.toString())
                AcraApplication.Companion.setKey("restore_device", restoreSwitch.isChecked.toString())

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val res = ApiUtils.INSTANCE.syncProjectDetails(ctx)
                        withContext(Dispatchers.Main) {
                            loadingDialog.dismiss()
                            if (res != null && res.first) {
                                dismiss()
                            }
                            CommonActivity.showToast(res?.second ?: "Sync failed", null)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            loadingDialog.dismiss()
                            CommonActivity.showToast("Error syncing: $e", null)
                        }
                    }
                }
            }
            .setNegativeButton("Reset") { _, _ ->
                AcraApplication.Companion.setKey("sync_token", "")
                AcraApplication.Companion.setKey("sync_project_num", "")
                CommonActivity.showToast("Credentials removed", null)
                dismiss()
            }
            .show()
    }
}
