package com.SyncCS

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
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
    private val pluginContext: Context // Plugin에서 넘겨받은 리소스 컨텍스트
) : BottomSheetDialogFragment() {

    // plugin.resources 대신 pluginContext.resources를 사용합니다.
    private val res = pluginContext.resources

    private fun <T : View> findView(view: View, name: String): T {
        val id = res.getIdentifier(name, "id", plugin.javaClass.packageName)
        return view.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", plugin.javaClass.packageName)
        // 핵심: Inflater에 플러그인 컨텍스트를 입혀서 XML을 파싱하도록 합니다.
        val pluginInflater = inflater.cloneInContext(pluginContext)
        return pluginInflater.inflate(id, container, false)
    }

    private fun getDrawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", plugin.javaClass.packageName)
        return if (id != 0) ResourcesCompat.getDrawable(res, id, null) else null
    }

    private fun makeTvCompatible(view: View) {
        val id = res.getIdentifier("outline", "drawable", plugin.javaClass.packageName)
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
            val configLayout = getLayout("config", inflater, container)
            val tokenEdit = findView<EditText>(configLayout, "token")
            val projectNumEdit = findView<EditText>(configLayout, "project_num")
            val backupSwitch = findView<Switch>(configLayout, "backup_device")
            val restoreSwitch = findView<Switch>(configLayout, "restore_device")

            val ctx = AcraApplication.context
            if (ctx != null) {
                val prefs = with(DataStore) { ctx.getSharedPrefs() }
                tokenEdit.setText(prefs.getString("sync_token", null)?.let { mapper.readValue(it, String::class.java) })
                projectNumEdit.setText(prefs.getString("sync_project_num", null)?.let { mapper.readValue(it, String::class.java) })
                backupSwitch.isChecked = prefs.getString("backup_device", null)?.let { mapper.readValue(it, String::class.java) } == "true"
                restoreSwitch.isChecked = prefs.getString("restore_device", null)?.let { mapper.readValue(it, String::class.java) } == "true"
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
                    
                    if (token.isEmpty() || projectNum.isEmpty()) {
                        CommonActivity.showToast("Please fill in all information", null)
                        return@setPositiveButton
                    }
                    
                    loadingDialog.show()
                    AcraApplication.setKey("sync_token", token)
                    AcraApplication.setKey("sync_project_num", projectNum)
                    AcraApplication.setKey("backup_device", backupSwitch.isChecked.toString())
                    AcraApplication.setKey("restore_device", restoreSwitch.isChecked.toString())

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val (success, msg) = ApiUtils.syncProjectDetails(context)
                            withContext(Dispatchers.Main) {
                                loadingDialog.dismiss()
                                if (success) dismiss()
                                CommonActivity.showToast(msg, null)
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
