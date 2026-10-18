// hsp1020/ultimaevo/UltimaEvoSettingsDialog.kt
package com.ultimaevo

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.DialogFragment
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.text.Charsets
import com.lagradost.cloudstream3.MainAPI

class UltimaEvoSettingsDialog : DialogFragment() {

    private var lastClickTime: Long = 0
    private var activeAlertDialog: AlertDialog? = null

    data class InstanceData(var originalId: Int, var name: String, var visibility: Boolean, var search: Boolean)
    private var maxIdGen = 1
    private val instanceMemoryList = mutableListOf<InstanceData>()

    private fun getDarkThemeBg() = GradientDrawable().apply { setColor(Color.parseColor("#000000")); cornerRadius = 25f }
    private fun getCardBg() = GradientDrawable().apply { setColor(Color.parseColor("#0C0C0C")); setStroke(2, Color.parseColor("#1A1A1A")); cornerRadius = 18f }
    private fun getButtonBg() = GradientDrawable().apply { setColor(Color.parseColor("#151515")); cornerRadius = 12f }

    private fun prefsToJson(prefs: SharedPreferences): JSONObject {
        val json = JSONObject()
        prefs.all.forEach { (key, value) ->
            if (value != null && !key.startsWith("dynamic_catalogs_")) { // [Issue 1 해결] 단순 캐시용 카탈로그 목록은 필터링 제외하여 용량 극소화
                if (value is Set<*>) {
                    val arr = JSONArray()
                    value.forEach { item -> if (item != null) arr.put(item) }
                    json.put(key, arr)
                } else {
                    json.put(key, value)
                }
            }
        }
        return json
    }

    private fun jsonToPrefs(json: JSONObject, editor: SharedPreferences.Editor) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = json.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is JSONArray -> {
                    val set = mutableSetOf<String>()
                    for (i in 0 until value.length()) {
                        val item = value.optString(i)
                        if (item != null) set.add(item)
                    }
                    editor.putStringSet(key, set)
                }
            }
        }
    }

    private fun setupCustomDialog(dialog: AlertDialog, contentView: View) {
        activeAlertDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component

        if (componentName != null) {
            val restartIntent = android.content.Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val metrics = resources.displayMetrics
            val width = minOf((1000 * metrics.density).toInt(), (metrics.widthPixels * 0.9f).toInt()).coerceAtLeast(1)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDestroyView() {
        activeAlertDialog?.dismiss()
        activeAlertDialog = null
        super.onDestroyView()
    }

    private fun saveMemoryToPrefs(prefs: SharedPreferences) {
        val editor = prefs.edit()
        val currentInstanceCount = instanceMemoryList.size
        editor.putString("evo_instance_count", currentInstanceCount.toString())
        for (i in 1..currentInstanceCount) {
            val data = instanceMemoryList[i - 1]
            editor.putString("evo_name_$i", data.name)
            editor.putBoolean("evo_visibility_enabled_$i", data.visibility)
            editor.putBoolean("evo_search_enabled_$i", data.search)
        }
        editor.apply()
    }

    private fun showSavedPopup(ctx: Context, title: String, message: String, onConfirm: () -> Unit = {}) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = getDarkThemeBg()
            addView(TextView(ctx).apply {
                text = title
                textSize = 18f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 20)
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = message
                setTextColor(Color.LTGRAY)
                textSize = 14f
                setPadding(0, 0, 0, 30)
            })
        }
        val okBtn = Button(ctx).apply {
            text = "OK"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        container.addView(okBtn)

        val dlg = AlertDialog.Builder(ctx).setView(container).create()
        setupCustomDialog(dlg, container)
        okBtn.setOnClickListener {
            dlg.dismiss()
            onConfirm()
        }
        dlg.show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        isCancelable = true 
        dialog?.setCanceledOnTouchOutside(false) 
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
    
        maxIdGen = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1
        instanceMemoryList.clear()
        for (i in 1..maxIdGen) {
            instanceMemoryList.add(InstanceData(
                originalId = i,
                name = prefs.getString("evo_name_$i", "Ultima Evo $i") ?: "Ultima Evo $i",
                visibility = prefs.getBoolean("evo_visibility_enabled_$i", true),
                search = prefs.getBoolean("evo_search_enabled_$i", true)
            ))
        }

        val scrollView = object : androidx.core.widget.NestedScrollView(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = (resources.displayMetrics.heightPixels * 0.88f).toInt().coerceAtLeast(1)
                val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
                    maxHeight else minOf(maxHeight, View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
            }
        }.apply { 
            background = getDarkThemeBg()
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(120)
        }
        
        val mainLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }

        mainLayout.addView(TextView(ctx).apply {
            text = "Ultima Evo Settings"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 30)
            gravity = Gravity.CENTER
        })

        val backupRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 30) }

        val exportBtn = Button(ctx).apply {
            text = "Export Settings"; setTextColor(Color.WHITE); isAllCaps = false; background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,15,0) }
            setOnClickListener {
                if (SystemClock.elapsedRealtime() - lastClickTime < 1000 || activeAlertDialog?.isShowing == true) return@setOnClickListener
                lastClickTime = SystemClock.elapsedRealtime()
                
                saveMemoryToPrefs(prefs)

                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val backupStr = withContext(Dispatchers.IO) {
                            val json = prefsToJson(prefs)
                            json.put("plugin_name", "UltimaEvo")
                            
                            val bos = ByteArrayOutputStream()
                            GZIPOutputStream(bos).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                        }
                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Ultima Backup", backupStr))
                        
                        val container = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(40, 40, 40, 40)
                            background = getDarkThemeBg()
                        }
                        container.addView(TextView(ctx).apply {
                            text = "Export Successful"
                            textSize = 18f
                            setTextColor(Color.WHITE)
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setPadding(0, 0, 0, 20)
                            gravity = Gravity.CENTER
                        })
                        container.addView(TextView(ctx).apply {
                            text = "Compressed backup data has been successfully copied to your clipboard.\n\nPlease paste it somewhere safe (e.g., Notes, Messenger)."
                            setTextColor(Color.LTGRAY)
                            textSize = 14f
                            setPadding(0, 0, 0, 30)
                        })
                        val okBtn = Button(ctx).apply {
                            text = "OK"
                            setTextColor(Color.WHITE)
                            isAllCaps = false
                            background = getButtonBg()
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        }
                        container.addView(okBtn)

                        val dlg = AlertDialog.Builder(ctx).setView(container).create()
                        setupCustomDialog(dlg, container)
                        okBtn.setOnClickListener { dlg.dismiss() }
                        dlg.show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(ctx, "Export failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val importBtn = Button(ctx).apply {
            text = "Import Settings"; setTextColor(Color.WHITE); isAllCaps = false; background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15,0,0,0) }
            setOnClickListener {
                if (SystemClock.elapsedRealtime() - lastClickTime < 1000 || activeAlertDialog?.isShowing == true) return@setOnClickListener
                lastClickTime = SystemClock.elapsedRealtime()

                val clipboardText = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                if (clipboardText.isEmpty()) { 
                    Toast.makeText(ctx, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener 
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val parsedJson = withContext(Dispatchers.IO) {
                            val decodedBytes = Base64.decode(clipboardText, Base64.NO_WRAP)
                            val jsonString = try {
                                val bais = ByteArrayInputStream(decodedBytes)
                                GZIPInputStream(bais).bufferedReader(Charsets.UTF_8).readText()
                            } catch (e: Exception) { String(decodedBytes, Charsets.UTF_8) }
                            JSONObject(jsonString)
                        }

                        if (parsedJson.optString("plugin_name") != "UltimaEvo") {
                            throw Exception("Plugin mismatch")
                        }

                        if (parsedJson.length() == 0) throw Exception("Empty Data")

                        val container = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(40, 40, 40, 40)
                            background = getDarkThemeBg()
                        }
                        container.addView(TextView(ctx).apply {
                            text = "Backup Data Detected"
                            textSize = 18f
                            setTextColor(Color.WHITE)
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setPadding(0, 0, 0, 20)
                            gravity = Gravity.CENTER
                        })
                        container.addView(TextView(ctx).apply {
                            text = "Valid compressed backup data found in clipboard!\n\nWarning: Restoring will overwrite settings.\nAre you sure you want to proceed?"
                            setTextColor(Color.LTGRAY)
                            textSize = 14f
                            setPadding(0, 0, 0, 30)
                        })
                        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                        val cancelBtn = Button(ctx).apply {
                            text = "Cancel"
                            setTextColor(Color.WHITE)
                            isAllCaps = false
                            background = getButtonBg()
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) }
                        }
                        val restoreBtn = Button(ctx).apply {
                            text = "Restore"
                            setTextColor(Color.WHITE)
                            isAllCaps = false
                            background = getButtonBg()
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) }
                        }
                        btnRow.addView(cancelBtn)
                        btnRow.addView(restoreBtn)
                        container.addView(btnRow)

                        val dlg = AlertDialog.Builder(ctx).setView(container).create()
                        setupCustomDialog(dlg, container)

                        cancelBtn.setOnClickListener { dlg.dismiss() }
                        
                        restoreBtn.setOnClickListener {
                            dlg.dismiss() 
                            
                            val loadingContainer = LinearLayout(ctx).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(40, 40, 40, 40)
                                background = getDarkThemeBg()
                                addView(TextView(ctx).apply {
                                    text = "Restoring Data"
                                    textSize = 18f
                                    setTextColor(Color.WHITE)
                                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    setPadding(0, 0, 0, 20)
                                    gravity = Gravity.CENTER
                                })
                                addView(TextView(ctx).apply {
                                    text = "Please wait while settings are being applied...\nApp will restart automatically."
                                    setTextColor(Color.LTGRAY)
                                    textSize = 14f
                                    gravity = Gravity.CENTER
                                    setPadding(0, 0, 0, 10)
                                })
                            }
                            val loadingDlg = AlertDialog.Builder(ctx).setView(loadingContainer).setCancelable(false).create()
                            setupCustomDialog(loadingDlg, loadingContainer)
                            loadingDlg.show()

                            viewLifecycleOwner.lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    val editor = prefs.edit()
                                    editor.clear() 
                                    
                                    jsonToPrefs(parsedJson, editor)
                                    editor.commit()

                                    val finalUltimaEvoJson = prefsToJson(prefs).toString()
                                    val finalUltimaEvoHash = finalUltimaEvoJson.hashCode().toString()
                                    
                                    val mirrorEditor = prefs.edit()
                                    mirrorEditor.putBoolean("ultima_evo_restore_complete", true)
                                    mirrorEditor.putString("ultima_evo_last_applied_backup_hash", finalUltimaEvoHash)
                                    mirrorEditor.putLong("evo_last_sync_time", 0L) // 0L로 리셋하여 복원 재구동 시 우회 처리 수행
                                    mirrorEditor.commit()

                                    try {
                                        CloudStreamApp.setKey("native_backup_ultimaevo", finalUltimaEvoJson)
                                    } catch (t: Throwable) { t.printStackTrace() }
                                }
                                
                                delay(1500)
                                if (isActive && isAdded) {
                                    loadingDlg.dismiss()
                                    dismiss()
                                    restartApp()
                                }
                            }
                        }
                        dlg.show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        val errContainer = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(40, 40, 40, 40)
                            background = getDarkThemeBg()
                        }
                        errContainer.addView(TextView(ctx).apply {
                            text = "Invalid Backup Data"
                            textSize = 18f
                            setTextColor(Color.WHITE)
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setPadding(0, 0, 0, 20)
                            gravity = Gravity.CENTER
                        })
                        errContainer.addView(TextView(ctx).apply {
                            text = "No valid backup data found in your clipboard.\n\nPlease copy the exact backup text and try again."
                            setTextColor(Color.LTGRAY)
                            textSize = 14f
                            setPadding(0, 0, 0, 30)
                        })
                        val okBtn = Button(ctx).apply {
                            text = "OK"
                            setTextColor(Color.WHITE)
                            isAllCaps = false
                            background = getButtonBg()
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        }
                        errContainer.addView(okBtn)

                        val errDlg = AlertDialog.Builder(ctx).setView(errContainer).create()
                        setupCustomDialog(errDlg, errContainer)
                        okBtn.setOnClickListener { errDlg.dismiss() }
                        errDlg.show()
                    }
                }
            }
        }
        backupRow.addView(exportBtn); backupRow.addView(importBtn); mainLayout.addView(backupRow)

        val syncAllBtn = Button(ctx).apply {
            text = "Sync Provider Data"; setTextColor(Color.WHITE); isAllCaps = false; background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 30) }
            setOnClickListener {
                if (SystemClock.elapsedRealtime() - lastClickTime < 1000) return@setOnClickListener
                lastClickTime = SystemClock.elapsedRealtime()
                this.isEnabled = false
                
                saveMemoryToPrefs(prefs) 
                
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        UltimaEvoSyncManager.performSync(ctx, forceSyncAll = true) { current, total ->
                            withContext(Dispatchers.Main) { this@apply.text = "Syncing... $current / $total" }
                        }
                        withContext(Dispatchers.Main) { this@apply.text = "✅ Sync Complete"; this@apply.isEnabled = true }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) { this@apply.text = "❌ Sync Failed"; this@apply.isEnabled = true }
                    }
                }
            }
        }
        mainLayout.addView(syncAllBtn)

        val rvInstances = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(120)
        }

        val countLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 20) }
        countLayout.addView(TextView(ctx).apply { text = "Instances:"; textSize = 15f; setTextColor(Color.LTGRAY); setPadding(0,0,20,0) })
        
        val instanceCountInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; imeOptions = EditorInfo.IME_ACTION_DONE; setText(instanceMemoryList.size.toString())
            setTextColor(Color.WHITE); background = getButtonBg(); setPadding(30, 20, 30, 20)
            layoutParams = LinearLayout.LayoutParams(150, LinearLayout.LayoutParams.WRAP_CONTENT); gravity = Gravity.CENTER
            
            setOnClickListener {
                requestFocus()
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        
        instanceCountInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val newCount = instanceCountInput.text.toString().toIntOrNull() ?: instanceMemoryList.size
                if (newCount < instanceMemoryList.size) {
                    while(instanceMemoryList.size > newCount) {
                        instanceMemoryList.removeLast()
                    }
                    rvInstances.adapter?.notifyDataSetChanged()
                } else if (newCount > instanceMemoryList.size) {
                    while(instanceMemoryList.size < newCount) {
                        maxIdGen++
                        instanceMemoryList.add(InstanceData(maxIdGen, "Ultima Evo ${instanceMemoryList.size + 1}", true, true))
                    }
                    rvInstances.adapter?.notifyDataSetChanged()
                }
            }
        }
        countLayout.addView(instanceCountInput)

        val addInstanceBtn = Button(ctx).apply {
            text = "+"; textSize = 20f; setTextColor(Color.WHITE); background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(120, LinearLayout.LayoutParams.MATCH_PARENT).apply { setMargins(20, 0, 0, 0) }
            setOnClickListener {
                maxIdGen++
                instanceMemoryList.add(InstanceData(maxIdGen, "Ultima Evo ${instanceMemoryList.size + 1}", true, true))
                rvInstances.adapter?.notifyItemInserted(instanceMemoryList.size - 1)
                instanceCountInput.setText(instanceMemoryList.size.toString())
            }
        }
        countLayout.addView(addInstanceBtn)
        mainLayout.addView(countLayout)

        class InstanceAdapter : RecyclerView.Adapter<InstanceAdapter.VH>() {
            inner class VH(val view: LinearLayout, val nameInput: EditText, val visSwitch: Switch, val searchSwitch: Switch, val configBtn: Button, val delBtn: Button) : RecyclerView.ViewHolder(view) {
                var targetData: InstanceData? = null
                init {
                    nameInput.addTextChangedListener(object: TextWatcher {
                        override fun afterTextChanged(s: Editable?) { targetData?.name = s.toString() }
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    })
                }
            }
            
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(30, 30, 30, 30); background = getCardBg()
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 15, 0, 15) }
                }
                val nameRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 20) }
                val nameLabel = TextView(ctx).apply { text = "Catalog Name:"; setTextColor(Color.parseColor("#CCCCCC")); textSize = 14f; setPadding(0, 0, 20, 0) }
                val nameInput = EditText(ctx).apply {
                    setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); textSize = 14f; setPadding(20, 20, 20, 20); background = getButtonBg()
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setSingleLine(true)
                    
                    setOnClickListener {
                        requestFocus()
                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                nameRow.addView(nameLabel); nameRow.addView(nameInput); card.addView(nameRow)

                val toggleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 10) }
                val visSwitch = Switch(ctx).apply { text = "Show Catalog"; setTextColor(Color.WHITE); textSize = 14f; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
                val searchSwitch = Switch(ctx).apply { text = "Enable Search"; setTextColor(Color.WHITE); textSize = 14f; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(50,0,0,0) } }
                toggleRow.addView(visSwitch); toggleRow.addView(searchSwitch); card.addView(toggleRow)

                val actionRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val configBtn = Button(ctx).apply { text = "Configure Providers"; setTextColor(Color.WHITE); isAllCaps = false; background = getButtonBg(); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,15,0) } }
                val delBtn = Button(ctx).apply { text = "Delete"; setTextColor(Color.parseColor("#FF5555")); isAllCaps = false; background = getButtonBg(); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15,0,0,0) } }
                actionRow.addView(configBtn); actionRow.addView(delBtn); card.addView(actionRow)

                return VH(card, nameInput, visSwitch, searchSwitch, configBtn, delBtn)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                val data = instanceMemoryList[position]
                
                holder.targetData = null 
                holder.nameInput.setText(data.name)
                holder.targetData = data 
                
                holder.visSwitch.setOnCheckedChangeListener(null)
                holder.visSwitch.isChecked = data.visibility
                holder.visSwitch.setOnCheckedChangeListener { _, isChecked -> data.visibility = isChecked }
                
                holder.searchSwitch.setOnCheckedChangeListener(null)
                holder.searchSwitch.isChecked = data.search
                holder.searchSwitch.setOnCheckedChangeListener { _, isChecked -> data.search = isChecked }

                holder.configBtn.setOnClickListener { showEditCatalogsDialog(data.originalId, ctx) }
                
                holder.delBtn.setOnClickListener {
                    val pos = holder.adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        instanceMemoryList.removeAt(pos)
                        notifyItemRemoved(pos)
                        instanceCountInput.setText(instanceMemoryList.size.toString())
                    }
                }
            }
            override fun getItemCount() = instanceMemoryList.size
        }
        rvInstances.adapter = InstanceAdapter()
        mainLayout.addView(rvInstances)

        val saveBtn = Button(ctx).apply {
            text = "Save & Close"; setTextColor(Color.WHITE); isAllCaps = false; background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 30, 0, 0) }
            setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val editor = prefs.edit()
                    val newCount = instanceMemoryList.size
                    val oldCount = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1
                    
                    editor.putString("evo_instance_count", newCount.toString())
                    
                    for (i in 0 until newCount) {
                        val targetId = i + 1
                        val item = instanceMemoryList[i]
                        
                        editor.putString("evo_name_$targetId", item.name)
                        editor.putBoolean("evo_visibility_enabled_$targetId", item.visibility)
                        editor.putBoolean("evo_search_enabled_$targetId", item.search)
                        
                        if (item.originalId != targetId) {
                            val srcId = item.originalId
                            editor.putString("evo_active_cats_json_$targetId", prefs.getString("evo_active_cats_json_$srcId", ""))
                            
                            prefs.all.keys.forEach { key ->
                                if (key.startsWith("cat_toggle_${srcId}_") || 
                                    key.startsWith("cat_rename_${srcId}_") || 
                                    key.startsWith("cat_order_${srcId}_") || 
                                    key.startsWith("auto_add_${srcId}_") ||
                                    key.startsWith("ext_toggle_${srcId}_") ||
                                    key.startsWith("provider_enabled_${srcId}_")) {
                                    
                                    val newKey = key.replaceFirst("_${srcId}_", "_${targetId}_")
                                    when (val value = prefs.all[key]) {
                                        is Boolean -> editor.putBoolean(newKey, value)
                                        is String -> editor.putString(newKey, value)
                                        is Int -> editor.putInt(newKey, value)
                                    }
                                }
                            }
                        }
                    }
                    
                    val maxIdToCheck = maxOf(oldCount, maxIdGen)
                    for (i in (newCount + 1)..maxIdToCheck) {
                        editor.remove("evo_name_$i")
                        editor.remove("evo_visibility_enabled_$i")
                        editor.remove("evo_search_enabled_$i")
                        editor.remove("evo_active_cats_json_$i")
                        
                        prefs.all.keys.forEach { key ->
                            if (key.startsWith("cat_toggle_${i}_") || 
                                key.startsWith("cat_rename_${i}_") || 
                                key.startsWith("cat_order_${i}_") || 
                                key.startsWith("auto_add_${i}_") ||
                                key.startsWith("ext_toggle_${i}_") ||
                                key.startsWith("provider_enabled_${i}_")) {
                                editor.remove(key)
                            }
                        }
                    }

                    editor.commit()

                    val ultimaEvoJson = prefsToJson(prefs).toString()
                    val ultimaEvoHash = ultimaEvoJson.hashCode().toString()
                    
                    val mirrorEditor = prefs.edit()
                    mirrorEditor.putBoolean("ultima_evo_restore_complete", true)
                    mirrorEditor.putString("ultima_evo_last_applied_backup_hash", ultimaEvoHash)
                    mirrorEditor.putLong("evo_last_sync_time", 0L)
                    mirrorEditor.commit()

                    try {
                        CloudStreamApp.setKey("native_backup_ultimaevo", ultimaEvoJson)
                    } catch (t: Throwable) { t.printStackTrace() }
                    
                    withContext(Dispatchers.Main) {
                        getSafeAllProviders().forEach { p -> if (p is UltimaEvoProvider) p.invalidateCache() }
                    
                        val cancelBtn = Button(ctx).apply {
                            text = "No"
                            setTextColor(Color.WHITE)
                            isAllCaps = false
                            background = getButtonBg()
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) }
                        }
                        val restartBtn = Button(ctx).apply {
                            text = "Yes"
                            setTextColor(Color.WHITE)
                            isAllCaps = false
                            background = getButtonBg()
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) }
                        }

                        val container = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(40, 40, 40, 40)
                            background = getDarkThemeBg()
                            addView(TextView(ctx).apply {
                                text = "Save & Reload"
                                textSize = 18f
                                setTextColor(Color.WHITE)
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                setPadding(0, 0, 0, 20)
                                gravity = Gravity.CENTER
                            })
                            addView(TextView(ctx).apply {
                                text = "Changes have been saved. Do you want to restart the app to apply them?"
                                setTextColor(Color.LTGRAY)
                                textSize = 14f
                                setPadding(0, 0, 0, 30)
                            })
                            val btnRow = LinearLayout(ctx).apply {
                                orientation = LinearLayout.HORIZONTAL
                                addView(cancelBtn)
                                addView(restartBtn)
                            }
                            addView(btnRow)
                        }

                        val dlg = AlertDialog.Builder(ctx).setView(container).create()
                        setupCustomDialog(dlg, container)

                        cancelBtn.setOnClickListener { 
                            dlg.dismiss()
                            dismiss() 
                        }
                        
                        restartBtn.setOnClickListener { 
                            dlg.dismiss()
                            dismiss() 
                            restartApp() 
                        }
                        
                        dlg.show()
                    }
                }
            }
        }
        mainLayout.addView(saveBtn)

        scrollView.addView(mainLayout)
        return scrollView
    }

    private fun showEditCatalogsDialog(instanceId: Int, ctx: Context) {
        val prefs = ctx.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        val mainContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(25, 40, 25, 30); background = getDarkThemeBg() }
        
        mainContainer.addView(TextView(ctx).apply { text = "Configure Providers"; textSize = 20f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, 0, 0, 30); gravity = Gravity.CENTER })

        val allProviders = getSafeAllProviders().filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
        
        val providerSettingsCard = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(25, 25, 25, 25); background = getCardBg(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) } }
        val headerRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        
        headerRow.addView(TextView(ctx).apply { text = "Provider Settings"; setTextColor(Color.WHITE); textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        val toggleArrow = TextView(ctx).apply { text = "▼"; setTextColor(Color.WHITE); textSize = 14f; setPadding(15, 10, 15, 10) }
        headerRow.addView(toggleArrow); providerSettingsCard.addView(headerRow)

        val rvProviders = RecyclerView(ctx).apply { 
            layoutManager = LinearLayoutManager(ctx)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400)
            visibility = View.GONE 
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(120)
        }

        val tempActiveSettings = mutableMapOf<String, Boolean>()
        val tempAutoAddSettings = mutableMapOf<String, Boolean>()

        data class ProviderData(val pName: String, var isActive: Boolean, var isAutoAdd: Boolean)
        val providerList = mutableListOf<ProviderData>()

        for (provider in allProviders) {
            val pName = provider.name
            val isActive = prefs.getBoolean("provider_enabled_${instanceId}_$pName", true)
            val isAutoAdd = prefs.getBoolean("auto_add_${instanceId}_$pName", false)
            
            providerList.add(ProviderData(pName, isActive, isAutoAdd))
            tempActiveSettings[pName] = isActive
            tempAutoAddSettings[pName] = isAutoAdd
        }

        fun sortProviderList() {
            val active = providerList.filter { it.isActive }.sortedBy { it.pName.lowercase() }
            val inactive = providerList.filter { !it.isActive }.sortedBy { it.pName.lowercase() }
            providerList.clear()
            providerList.addAll(active)
            providerList.addAll(inactive)
        }
        sortProviderList()

        data class CatalogData(val providerName: String, val catalogName: String, var isChecked: Boolean, var customName: String, var orderIndex: Int)
        val allCatalogList = mutableListOf<CatalogData>()
        val visibleCatalogList = mutableListOf<CatalogData>()

        for (provider in allProviders) {
            val staticCats = provider.mainPage.map { it.name }
            val dynamicCats = prefs.getString("dynamic_catalogs_${provider.name}", "")?.split("|||") ?: emptyList()
            val combined = (staticCats + dynamicCats).filter { it.isNotBlank() }.distinct()
            
            combined.forEach { cName ->
                val isAutoAdd = prefs.getBoolean("auto_add_${instanceId}_${provider.name}", false)
                val isChecked = prefs.getBoolean("cat_toggle_${instanceId}_${provider.name}_$cName", isAutoAdd)
                val customName = prefs.getString("cat_rename_${instanceId}_${provider.name}_$cName", cName) ?: cName
                val orderIndex = prefs.getString("cat_order_${instanceId}_${provider.name}_$cName", "999")?.toIntOrNull() ?: 999
                allCatalogList.add(CatalogData(provider.name, cName, isChecked, customName, orderIndex))
            }
        }

        allCatalogList.sortBy { it.orderIndex }

        val rvCatalogs = RecyclerView(ctx).apply { 
            layoutManager = LinearLayoutManager(ctx)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) 
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(120)
        }

        class CatalogAdapter : RecyclerView.Adapter<CatalogAdapter.CVH>() {
            var visibleTabs = mutableListOf<CatalogData>()

            inner class CVH(val view: LinearLayout, val dragHandle: ImageView, val cb: CheckBox, val etOrder: EditText, val tvSource: TextView, val etRename: EditText) : RecyclerView.ViewHolder(view) {
                var targetData: CatalogData? = null
                init {
                    etRename.addTextChangedListener(object: TextWatcher {
                        override fun afterTextChanged(s: Editable?) { targetData?.customName = s.toString() }
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    })
                }
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CVH {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(10, 8, 10, 8); background = getCardBg(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 5, 0, 5) } }
                val dragHandle = ImageView(ctx).apply { setImageResource(android.R.drawable.ic_menu_sort_by_size); setPadding(10, 0, 20, 0) }
                val cb = CheckBox(ctx).apply { setPadding(0, 0, 5, 0) }
                
                val etOrder = EditText(ctx).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setPadding(10, 10, 10, 10)
                    background = getButtonBg()
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(80, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 15, 0)
                    }
                    
                    setOnClickListener {
                        requestFocus()
                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                
                val tvSource = TextView(ctx).apply { setTextColor(Color.parseColor("#AAAAAA")); textSize = 11f; setPadding(15, 8, 15, 8); background = GradientDrawable().apply { setColor(Color.parseColor("#222222")); cornerRadius = 15f }; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(5, 0, 10, 0) } }
                val etRename = EditText(ctx).apply { 
                    setTextColor(Color.WHITE); setHintTextColor(Color.DKGRAY); textSize = 13f; setPadding(15, 10, 15, 10); background = getButtonBg(); setSingleLine(true); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) 
                    
                    setOnClickListener {
                        requestFocus()
                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                row.addView(dragHandle); row.addView(cb); row.addView(etOrder); row.addView(tvSource); row.addView(etRename)
                return CVH(row, dragHandle, cb, etOrder, tvSource, etRename)
            }
            override fun onBindViewHolder(holder: CVH, position: Int) {
                val data = visibleTabs[position]
                holder.tvSource.text = data.providerName
                
                holder.targetData = null 
                holder.etRename.setText(data.customName)
                holder.etRename.isEnabled = data.isChecked
                holder.targetData = data 
                
                holder.cb.setOnCheckedChangeListener(null)
                holder.cb.isChecked = data.isChecked
                
                holder.cb.setOnCheckedChangeListener { _, isChecked ->
                    if (data.isChecked == isChecked) return@setOnCheckedChangeListener
                    data.isChecked = isChecked
                    holder.etRename.isEnabled = isChecked
                    
                    val checkedItems = visibleTabs.filter { it.isChecked }
                    val uncheckedItems = visibleTabs.filter { !it.isChecked }
                    visibleTabs.clear()
                    visibleTabs.addAll(checkedItems)
                    visibleTabs.addAll(uncheckedItems)

                    val masterIndex = allCatalogList.indexOf(data)
                    if (masterIndex != -1) {
                        allCatalogList.removeAt(masterIndex)
                        if (isChecked) {
                            allCatalogList.add(checkedItems.size - 1, data)
                        } else {
                            allCatalogList.add(checkedItems.size + uncheckedItems.indexOf(data), data)
                        }
                    }

                    for (i in allCatalogList.indices) {
                        allCatalogList[i].orderIndex = i + 1
                    }
                    
                    if (rvCatalogs.isComputingLayout) {
                        rvCatalogs.post { notifyDataSetChanged() }
                    } else {
                        notifyDataSetChanged()
                    }
                }
                
                holder.etOrder.setOnFocusChangeListener(null)
                holder.etOrder.setText((position + 1).toString())
                
                holder.etOrder.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val inputVal = holder.etOrder.text.toString().toIntOrNull()
                        if (inputVal != null) {
                            shiftItemInRecyclerView(position, inputVal)
                        } else {
                            holder.etOrder.setText((position + 1).toString())
                        }
                    }
                }
                
                holder.etOrder.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                        holder.etOrder.clearFocus()
                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(holder.etOrder.windowToken, 0)
                        true
                    } else false
                }
            }
            override fun getItemCount() = visibleTabs.size
            
            fun shiftItemInRecyclerView(oldPos: Int, newOrder: Int) {
                if (oldPos !in visibleTabs.indices) return
                val item = visibleTabs[oldPos]
                val checkedCount = visibleTabs.count { it.isChecked }
                
                val groupMin = if (item.isChecked) 1 else checkedCount + 1
                val groupMax = if (item.isChecked) checkedCount else visibleTabs.size
                
                val clampedOrder = newOrder.coerceIn(groupMin, groupMax)
                val newPos = clampedOrder - 1
                
                if (oldPos != newPos) {
                    visibleTabs.removeAt(oldPos)
                    visibleTabs.add(newPos, item)
                    
                    val allFrom = allCatalogList.indexOf(item)
                    if (allFrom != -1) {
                        allCatalogList.removeAt(allFrom)
                        val refTab = if (newPos + 1 in visibleTabs.indices) visibleTabs[newPos + 1] else if (newPos - 1 in visibleTabs.indices) visibleTabs[newPos - 1] else null
                        var allTo = if (refTab != null) allCatalogList.indexOf(refTab) else -1
                        if (allTo != -1) {
                            if (refTab == visibleTabs.getOrNull(newPos - 1)) {
                                allTo += 1
                            }
                            allCatalogList.add(allTo.coerceIn(0, allCatalogList.size), item)
                        } else {
                            allCatalogList.add(item)
                        }
                    }

                    for (i in allCatalogList.indices) {
                        allCatalogList[i].orderIndex = i + 1
                    }
                    
                    rvCatalogs.post {
                        notifyItemMoved(oldPos, newPos)
                        notifyItemRangeChanged(minOf(oldPos, newPos), maxOf(oldPos, newPos) + 1)
                    }
                } else {
                    rvCatalogs.post {
                        notifyItemChanged(oldPos)
                    }
                }
            }
        }
        val catalogAdapter = CatalogAdapter()
        rvCatalogs.adapter = catalogAdapter

        fun updateVisibleCatalogs() {
            visibleCatalogList.clear()
            val filtered = allCatalogList.filter { item ->
                tempActiveSettings[item.providerName] ?: true
            }
            val checked = filtered.filter { it.isChecked }.sortedBy { it.orderIndex }
            val unchecked = filtered.filter { !it.isChecked }.sortedBy { it.orderIndex }
            visibleCatalogList.addAll(checked)
            visibleCatalogList.addAll(unchecked)
            catalogAdapter.visibleTabs = visibleCatalogList
            
            if (rvCatalogs.isComputingLayout) {
                rvCatalogs.post { catalogAdapter.notifyDataSetChanged() }
            } else {
                catalogAdapter.notifyDataSetChanged()
            }
        }

        class ProviderAdapter : RecyclerView.Adapter<ProviderAdapter.PVH>() {
            inner class PVH(val view: LinearLayout, val tvName: TextView, val swActive: Switch, val swAutoAdd: Switch) : RecyclerView.ViewHolder(view)
            
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PVH {
                val row = LinearLayout(ctx).apply { 
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 15, 0, 15) 
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val tv = TextView(ctx).apply { 
                    setTextColor(Color.LTGRAY)
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                
                val swContainer = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, 
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val swActive = Switch(ctx).apply {
                    text = "Enable"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                val swAutoAdd = Switch(ctx).apply {
                    text = "Auto-Add"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(40, 0, 0, 0)
                    }
                }
                
                swContainer.addView(swActive)
                swContainer.addView(swAutoAdd)
                
                row.addView(tv)
                row.addView(swContainer)
                return PVH(row, tv, swActive, swAutoAdd)
            }
            
            override fun onBindViewHolder(holder: PVH, position: Int) {
                val data = providerList[position]
                holder.tvName.text = data.pName
                
                holder.swActive.setOnCheckedChangeListener(null)
                holder.swActive.isChecked = data.isActive
                
                holder.swAutoAdd.setOnCheckedChangeListener(null)
                holder.swAutoAdd.isChecked = data.isAutoAdd
                holder.swAutoAdd.isEnabled = data.isActive 
                
                holder.swActive.setOnCheckedChangeListener { _, isChecked -> 
                    if (data.isActive == isChecked) return@setOnCheckedChangeListener
                    data.isActive = isChecked
                    tempActiveSettings[data.pName] = isChecked
                    holder.swAutoAdd.isEnabled = isChecked
                    
                    sortProviderList()
                    
                    if (rvProviders.isComputingLayout) {
                        rvProviders.post { notifyDataSetChanged() }
                    } else {
                        notifyDataSetChanged()
                    }
                    
                    updateVisibleCatalogs() 
                }
                
                holder.swAutoAdd.setOnCheckedChangeListener { _, isChecked -> 
                    data.isAutoAdd = isChecked
                    tempAutoAddSettings[data.pName] = isChecked 
                }
            }
            override fun getItemCount() = providerList.size
        }
        rvProviders.adapter = ProviderAdapter()
        providerSettingsCard.addView(rvProviders)

        headerRow.setOnClickListener {
            if (rvProviders.visibility == View.VISIBLE) { rvProviders.visibility = View.GONE; toggleArrow.text = "▼" } 
            else { rvProviders.visibility = View.VISIBLE; toggleArrow.text = "▲" }
        }
        mainContainer.addView(providerSettingsCard)

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                
                Collections.swap(visibleCatalogList, from, to)
                catalogAdapter.notifyItemMoved(from, to)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                for (i in visibleCatalogList.indices) {
                    visibleCatalogList[i].orderIndex = i + 1
                }
                
                val inactiveCatalogs = allCatalogList.filter { it !in visibleCatalogList }
                allCatalogList.clear()
                allCatalogList.addAll(visibleCatalogList)
                allCatalogList.addAll(inactiveCatalogs)
                
                for (i in allCatalogList.indices) {
                    allCatalogList[i].orderIndex = i + 1
                }
                
                if (rvCatalogs.isComputingLayout) {
                    rvCatalogs.post { catalogAdapter.notifyDataSetChanged() }
                } else {
                    catalogAdapter.notifyDataSetChanged()
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })

        touchHelper.attachToRecyclerView(rvCatalogs)
        mainContainer.addView(rvCatalogs)

        updateVisibleCatalogs()

        val footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 30, 0, 0) }
        val cancelBtn = Button(ctx).apply { text = "Cancel"; setTextColor(Color.WHITE); isAllCaps = false; background = getButtonBg(); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,15,0) } }
        val applyBtn = Button(ctx).apply { text = "Save"; setTextColor(Color.WHITE); isAllCaps = false; background = getButtonBg(); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15,0,0,0) } }
        footer.addView(cancelBtn); footer.addView(applyBtn); mainContainer.addView(footer)

        val dialog = AlertDialog.Builder(ctx).setView(mainContainer).create()
        setupCustomDialog(dialog, mainContainer)

        applyBtn.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val editor = prefs.edit()
                
                tempActiveSettings.forEach { (pName, isChecked) -> editor.putBoolean("provider_enabled_${instanceId}_$pName", isChecked) }
                tempAutoAddSettings.forEach { (pName, isChecked) -> editor.putBoolean("auto_add_${instanceId}_$pName", isChecked) }
                
                val activeCatsConfig = mutableListOf<EvoCatalogConfig>()
                for ((index, item) in allCatalogList.withIndex()) {
                    val finalOrder = index + 1 
                    editor.putBoolean("cat_toggle_${instanceId}_${item.providerName}_${item.catalogName}", item.isChecked)
                    editor.putString("cat_rename_${instanceId}_${item.providerName}_${item.catalogName}", item.customName)
                    editor.putString("cat_order_${instanceId}_${item.providerName}_${item.catalogName}", finalOrder.toString())
                    
                    val isProviderActive = tempActiveSettings[item.providerName] ?: true
                    if (isProviderActive && item.isChecked) {
                        val origData = getSafeAllProviders().find { it.name == item.providerName }?.mainPage?.find { it.name == item.catalogName }?.data ?: ""
                        activeCatsConfig.add(EvoCatalogConfig(item.providerName, item.catalogName, item.customName, origData, finalOrder))
                    }
                }
                
                val jsonStr = activeCatsConfig.toJson()
                editor.putString("evo_active_cats_json_$instanceId", jsonStr)
                editor.commit()

                val ultimaEvoJson = prefsToJson(prefs).toString()
                val ultimaEvoHash = ultimaEvoJson.hashCode().toString()
                
                val mirrorEditor = prefs.edit()
                mirrorEditor.putBoolean("ultima_evo_restore_complete", true)
                mirrorEditor.putString("ultima_evo_last_applied_backup_hash", ultimaEvoHash)
                mirrorEditor.putLong("evo_last_sync_time", 0L)
                mirrorEditor.commit()

                try {
                    CloudStreamApp.setKey("native_backup_ultimaevo", ultimaEvoJson)
                } catch (t: Throwable) { t.printStackTrace() }

                withContext(Dispatchers.Main) { 
                    getSafeAllProviders().forEach { p -> if (p is UltimaEvoProvider) p.invalidateCache() }
                    
                    dialog.dismiss() 
                    showSavedPopup(ctx, "Catalog Settings", "Catalog settings saved. Reload to apply.")
                }
            }
        }
        
        cancelBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
