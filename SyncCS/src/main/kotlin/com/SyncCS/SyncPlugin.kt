package com.SyncCS

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        return inflater.inflate(res.getLayout(id), container, false)
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
        findView<TextView>(layout, "group_text").text = "Guide"

        val buttonConfig = findView<ImageView>(layout, "button_config")
        buttonConfig.setImageDrawable(getDrawable("edit_icon"))
        makeTvCompatible(buttonConfig)
        
        buttonConfig.setOnClickListener {
            val configLayout = getLayout("config", inflater, container) as ViewGroup
            val tokenEdit = findView<EditText>(configLayout, "token")
            val projectNumEdit = findView<EditText>(configLayout, "project_num")
            val backupSwitch = findView<Switch>(configLayout, "backup_device")
            val restoreSwitch = findView<Switch>(configLayout, "restore_device")

            // [요구사항 반영] 기기명 입력칸 동적 생성 및 삽입
            val deviceNameEdit = EditText(requireContext()).apply {
                hint = "Device Name (e.g. My Phone)"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16 }
            }
            // project_num 바로 아래(인덱스 2)에 삽입
            configLayout.addView(deviceNameEdit, 2)

            val ctx = AcraApplication.context
            if (ctx != null) {
                val prefs = with(DataStore) { ctx.getSharedPrefs() }
                tokenEdit.setText(prefs.getString("sync_token", null)?.let { mapper.readValue<String>(it) })
                projectNumEdit.setText(prefs.getString("sync_project_num", null)?.let { mapper.readValue<String>(it) })
                
                // 저장된 기기명이 없으면 기본 모델명 사용
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
                    
                    // 값 임시 저장
                    AcraApplication.setKey("sync_token", token)
                    AcraApplication.setKey("sync_project_num", projectNum)
                    AcraApplication.setKey("device_name", deviceName)
                    AcraApplication.setKey("backup_device", backupSwitch.isChecked.toString())
                    AcraApplication.setKey("restore_device", restoreSwitch.isChecked.toString())

                    // [요구사항 반영] 실제 GitHub 서버에 찔러서 검증
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val (success, msg) = ApiUtils.syncProjectDetails(context)
                            withContext(Dispatchers.Main) {
                                loadingDialog.dismiss()
                                CommonActivity.showToast(msg, null)
                                if (success) {
                                    dismiss() // 성공 시에만 창 닫기
                                } else {
                                    // 실패 시 토큰 초기화
                                    AcraApplication.setKey("sync_token", "")
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
            val guideLayout = getLayout("guide", inflater, container)
            AlertDialog.Builder(requireContext())
                .setTitle("Guide")
                .setView(guideLayout)
                .show()
        }

        return layout
    }
}
