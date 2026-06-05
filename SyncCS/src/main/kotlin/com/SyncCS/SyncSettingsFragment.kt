package com.SyncCS

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
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
    private val res: Resources

    init {
        val resources = plugin.resources ?: throw Exception("Unable to read resources")
        this.res = resources
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> findView(view: View, str: String): T {
        return view.findViewById(this.res.getIdentifier(str, "id", "com.SyncCS")) as T
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = this.res.getIdentifier(name, "layout", "com.SyncCS")
        val parser = this.res.getLayout(id)
        return inflater.inflate(parser as XmlPullParser, container, false)
    }

    private fun getDrawable(name: String): Drawable? {
        val id = this.res.getIdentifier(name, "drawable", "com.SyncCS")
        return ResourcesCompat.getDrawable(this.res, id, null)
    }

    private fun getString(name: String): String {
        val id = this.res.getIdentifier(name, "string", "com.SyncCS")
        return this.res.getString(id)
    }

    @Suppress("DEPRECATION")
    private fun makeTvCompatible(view: View) {
        val id = this.res.getIdentifier("outline", "drawable", "com.SyncCS")
        view.background = this.res.getDrawable(id, null)
    }

    @RequiresApi(23)
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layout = getLayout("settings", inflater, container)
        findView<TextView>(layout, "config_text").text = "Config"
        findView<TextView>(layout, "group_text").text = "Guide"
        val buttonConfig = findView<ImageView>(layout, "button_config")
        buttonConfig.setImageDrawable(getDrawable("edit_icon"))
        makeTvCompatible(buttonConfig)
        
        buttonConfig.setOnClickListener {
            val layoutConfig = getLayout("config", inflater, container)
            val tokenInput = findView<EditText>(layoutConfig, "token")
            
            val context = AcraApplication.context
            var tokenVal: String? = null
            if (context != null) {
                try {
                    val raw = with(DataStore) { context.getSharedPrefs().getString("sync_token", null) }
                    if (raw != null) {
                        tokenVal = mapper.readValue(raw, String::class.java)
                    }
                } catch (unused: Exception) {
                }
            }
            tokenInput.setText(tokenVal)
            
            val projectNumInput = findView<EditText>(layoutConfig, "project_num")
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
            projectNumInput.setText(projectNumVal)
            
            val backupSwitch = findView<Switch>(layoutConfig, "backup_device")
            backupSwitch.text = "Backup data to the cloud"
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
            backupSwitch.isChecked = (backupVal == "true")
            
            val restoreSwitch = findView<Switch>(layoutConfig, "restore_device")
            restoreSwitch.text = "Recover data from cloud"
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
            restoreSwitch.isChecked = (restoreVal == "true")
            
            val layoutLoading = getLayout("loading", inflater, container)
            val ctx = context ?: throw Exception("Unable to build alert dialog")
            val loadingDialog = AlertDialog.Builder(ctx).setView(layoutLoading).setCancelable(false).create()
            
            AlertDialog.Builder(ctx)
                .setTitle("Config")
                .setView(layoutConfig)
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
        
        val buttonGroup = findView<ImageView>(layout, "button_group")
        buttonGroup.setImageDrawable(getDrawable("view_icon"))
        makeTvCompatible(buttonGroup)
        
        buttonGroup.setOnClickListener {
            val layoutGuide = getLayout("guide", inflater, container)
            val ctx = context ?: throw Exception("Unable to build alert dialog")
            AlertDialog.Builder(ctx).setTitle("Guide").setView(layoutGuide).show()
        }
        
        return layout
    }
}
