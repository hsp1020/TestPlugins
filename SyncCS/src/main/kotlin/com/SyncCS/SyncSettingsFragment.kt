package com.SyncCS

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
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

class SyncSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()

        val mainLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Config Row Layout
        val configRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val configText = TextView(ctx).apply {
            text = "Config"
            textSize = 18f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val configButton = Button(ctx).apply {
            text = "Setup"
            setOnClickListener { showConfigDialog() }
        }
        configRow.addView(configText)
        configRow.addView(configButton)
        mainLayout.addView(configRow)

        // Guide Row Layout
        val guideRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val guideText = TextView(ctx).apply {
            text = "Guide"
            textSize = 18f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val guideButton = Button(ctx).apply {
            text = "View"
            setOnClickListener { showGuideDialog() }
        }
        guideRow.addView(guideText)
        guideRow.addView(guideButton)
        mainLayout.addView(guideRow)

        return mainLayout
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
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Guide")
            .setView(textView)
            .setPositiveButton("Close", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun showConfigDialog() {
        val ctx = context ?: return

        val scrollContainer = ScrollView(ctx)
        val configLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }
        scrollContainer.addView(configLayout)

        // Token Input
        val tokenInput = EditText(ctx).apply {
            hint = "GitHub PAT Token"
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
            ).apply { setMargins(0, 10, 0, 20) }
        }
        configLayout.addView(tokenInput)

        // Project Num Input
        val projectNumInput = EditText(ctx).apply {
            hint = "GitHub Project Number"
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
            ).apply { setMargins(0, 10, 0, 20) }
        }
        configLayout.addView(projectNumInput)

        // Backup Switch
        val backupSwitch = Switch(ctx).apply {
            text = "Backup data to the cloud"
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
            setPadding(0, 20, 0, 20)
        }
        configLayout.addView(backupSwitch)

        // Restore Switch
        val restoreSwitch = Switch(ctx).apply {
            text = "Recover data from cloud"
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
            setPadding(0, 20, 0, 20)
        }
        configLayout.addView(restoreSwitch)

        val loadingView = ProgressBar(ctx).apply { setPadding(50, 50, 50, 50) }
        val loadingDialog = AlertDialog.Builder(ctx).setView(loadingView).setCancelable(false).create()

        AlertDialog.Builder(ctx)
            .setTitle("Config")
            .setView(scrollContainer)
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
