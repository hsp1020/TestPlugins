// 파일명: hsp1020/ultimaevo/UltimaEvoSettingsDialog.kt
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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.APIHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class UltimaEvoSettingsDialog : BottomSheetDialogFragment() {

    private var lastClickTime: Long = 0
    private var activeAlertDialog: AlertDialog? = null

    data class InstanceData(var originalId: Int, var name: String, var visibility: Boolean, var search: Boolean)
    private var maxIdGen = 1
    private val instanceMemoryList = mutableListOf<InstanceData>()

    private fun getDarkThemeBg() = GradientDrawable().apply { setColor(Color.parseColor("#000000")); cornerRadius = 40f }
    private fun getCardBg() = GradientDrawable().apply { setColor(Color.parseColor("#0C0C0C")); setStroke(2, Color.parseColor("#1A1A1A")); cornerRadius = 30f }
    private fun getButtonBg() = GradientDrawable().apply { setColor(Color.parseColor("#151515")); cornerRadius = 20f }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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

        val scrollView = androidx.core.widget.NestedScrollView(ctx).apply { 
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
                            val json = JSONObject()
                            prefs.all.forEach { (key, value) -> json.put(key, value) }
                            val bos = ByteArrayOutputStream()
                            GZIPOutputStream(bos).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                        }
                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Ultima Backup", backupStr))
                        
                        AlertDialog.Builder(ctx)
                            .setTitle("Export Successful")
                            .setMessage("Compressed backup data has been successfully copied to your clipboard.\n\nPlease paste it somewhere safe (e.g., Notes, Messenger).")
                            .setPositiveButton("OK", null)
                            .show()
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

                        if (parsedJson.length() == 0) throw Exception("Empty Data")

                        AlertDialog.Builder(ctx)
                            .setTitle("Backup Data Detected")
                            .setMessage("Valid compressed backup data found in clipboard!\n\nWarning: Restoring will overwrite settings.\nAre you sure you want to proceed?")
                            .setPositiveButton("Restore") { _, _ ->
                                val editor = prefs.edit()
                                val keys = parsedJson.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    when (val value = parsedJson.get(key)) {
                                        is Boolean -> editor.putBoolean(key, value)
                                        is String -> editor.putString(key, value)
                                        is Int -> editor.putInt(key, value)
                                    }
                                }
                                editor.apply()
                                
                                AlertDialog.Builder(ctx)
                                    .setTitle("Import Result")
                                    .setMessage("Restore completed successfully!\nPlease restart the app.")
                                    .setPositiveButton("OK") { _, _ -> dismiss() }
                                    .show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        AlertDialog.Builder(ctx)
                            .setTitle("Invalid Backup Data")
                            .setMessage("No valid backup data found in your clipboard.\n\nPlease copy the exact backup text and try again.")
                            .setPositiveButton("OK", null)
                            .show()
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
                var textWatcher: TextWatcher? = null 
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
                val delBtn = Button(ctx).apply { text = "Delete"; setTextColor(Color.parseColor("#FF5555")); isAllCaps = false; background = getButtonBg(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
                actionRow.addView(configBtn); actionRow.addView(delBtn); card.addView(actionRow)

                return VH(card, nameInput, visSwitch, searchSwitch, configBtn, delBtn)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                val data = instanceMemoryList[position]
                
                if (holder.textWatcher != null) {
                    holder.nameInput.removeTextChangedListener(holder.textWatcher)
                }
                
                holder.nameInput.setText(data.name)
                
                holder.textWatcher = object: TextWatcher {
                    override fun afterTextChanged(s: Editable?) { data.name = s.toString() }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                }
                holder.nameInput.addTextChangedListener(holder.textWatcher)
                
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
                                key.startsWith("ext_toggle_${srcId}_")) {
                                
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
                            key.startsWith("ext_toggle_${i}_")) {
                            editor.remove(key)
                        }
                    }
                }
                
                editor.apply()
                APIHolder.allProviders.forEach { p -> if (p is UltimaEvoProvider) p.invalidateCache() }
                dismiss()
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

        val allProviders = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
        
        val providerSettingsCard = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(25, 25, 25, 25); background = getCardBg(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) } }
        val headerRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(TextView(ctx).apply { text = "Provider Auto-Add Settings"; setTextColor(Color.WHITE); textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        val toggleArrow = TextView(ctx).apply { text = "▼"; setTextColor(Color.WHITE); textSize = 14f; setPadding(15, 10, 15, 10) }
        headerRow.addView(toggleArrow); providerSettingsCard.addView(headerRow)

        val rvProviders = RecyclerView(ctx).apply { 
            layoutManager = LinearLayoutManager(ctx)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400)
            visibility = View.GONE 
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(120)
        }
        
        val tempAutoAddSettings = mutableMapOf<String, Boolean>()
        class ProviderAdapter : RecyclerView.Adapter<ProviderAdapter.PVH>() {
            inner class PVH(val view: LinearLayout, val tvName: TextView, val switch: Switch) : RecyclerView.ViewHolder(view)
            
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PVH {
                val row = LinearLayout(ctx).apply { 
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 15, 0, 15) 
                }
                
                val tv = TextView(ctx).apply { 
                    setTextColor(Color.LTGRAY)
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) 
                }
                
                // 🔥 [수정된 부분] 기존에 있던 `text = "Auto-add"` 속성을 완전히 제거했습니다.
                // 이제 옆에 군더더기 글자 없이 깔끔한 토글 스위치 형태만 렌더링됩니다.
                val sw = Switch(ctx)
                
                row.addView(tv)
                row.addView(sw)
                return PVH(row, tv, sw)
            }
            
            override fun onBindViewHolder(holder: PVH, position: Int) {
                val pName = allProviders[position].name
                // 왼쪽에 프로바이더 이름만 단독으로 예쁘게 출력됩니다.
                holder.tvName.text = pName
                
                holder.switch.setOnCheckedChangeListener(null)
                holder.switch.isChecked = prefs.getBoolean("auto_add_${instanceId}_$pName", false)
                holder.switch.setOnCheckedChangeListener { _, isChecked -> 
                    tempAutoAddSettings[pName] = isChecked 
                }
            }
            
            override fun getItemCount() = allProviders.size
        }
        rvProviders.adapter = ProviderAdapter()
        providerSettingsCard.addView(rvProviders)

        headerRow.setOnClickListener {
            if (rvProviders.visibility == View.VISIBLE) { rvProviders.visibility = View.GONE; toggleArrow.text = "▼" } 
            else { rvProviders.visibility = View.VISIBLE; toggleArrow.text = "▲" }
        }
        mainContainer.addView(providerSettingsCard)

        data class CatalogData(val providerName: String, val catalogName: String, var isChecked: Boolean, var customName: String, var orderIndex: Int)
        val catalogList = mutableListOf<CatalogData>()

        for (provider in allProviders) {
            val staticCats = provider.mainPage.map { it.name }
            val dynamicCats = prefs.getString("dynamic_catalogs_${provider.name}", "")?.split("|||") ?: emptyList()
            val combined = (staticCats + dynamicCats).filter { it.isNotBlank() }.distinct()
            
            combined.forEach { cName ->
                val isAutoAdd = prefs.getBoolean("auto_add_${instanceId}_${provider.name}", false)
                val isChecked = prefs.getBoolean("cat_toggle_${instanceId}_${provider.name}_$cName", isAutoAdd)
                val customName = prefs.getString("cat_rename_${instanceId}_${provider.name}_$cName", cName) ?: cName
                val orderIndex = prefs.getString("cat_order_${instanceId}_${provider.name}_$cName", "999")?.toIntOrNull() ?: 999
                catalogList.add(CatalogData(provider.name, cName, isChecked, customName, orderIndex))
            }
        }

        val initChecked = catalogList.filter { it.isChecked }.sortedBy { it.orderIndex }
        val initUnchecked = catalogList.filter { !it.isChecked }.sortedBy { it.orderIndex }
        catalogList.clear()
        catalogList.addAll(initChecked)
        catalogList.addAll(initUnchecked)

        val rvCatalogs = RecyclerView(ctx).apply { 
            layoutManager = LinearLayoutManager(ctx)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) 
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(120)
        }
        
        class CatalogAdapter : RecyclerView.Adapter<CatalogAdapter.CVH>() {
            inner class CVH(val view: LinearLayout, val dragHandle: ImageView, val cb: CheckBox, val tvSource: TextView, val etRename: EditText) : RecyclerView.ViewHolder(view) {
                var textWatcher: TextWatcher? = null 
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CVH {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(10, 8, 10, 8); background = getCardBg(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 5, 0, 5) } }
                val dragHandle = ImageView(ctx).apply { setImageResource(android.R.drawable.ic_menu_sort_by_size); setPadding(10, 0, 20, 0) }
                val cb = CheckBox(ctx).apply { setPadding(0, 0, 5, 0) }
                val tvSource = TextView(ctx).apply { setTextColor(Color.parseColor("#AAAAAA")); textSize = 11f; setPadding(15, 8, 15, 8); background = GradientDrawable().apply { setColor(Color.parseColor("#222222")); cornerRadius = 15f }; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(5, 0, 10, 0) } }
                val etRename = EditText(ctx).apply { 
                    setTextColor(Color.WHITE); setHintTextColor(Color.DKGRAY); textSize = 13f; setPadding(15, 10, 15, 10); background = getButtonBg(); setSingleLine(true); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) 
                    
                    setOnClickListener {
                        requestFocus()
                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                row.addView(dragHandle); row.addView(cb); row.addView(tvSource); row.addView(etRename)
                return CVH(row, dragHandle, cb, tvSource, etRename)
            }
            override fun onBindViewHolder(holder: CVH, position: Int) {
                val data = catalogList[position]
                holder.tvSource.text = data.providerName
                
                if (holder.textWatcher != null) {
                    holder.etRename.removeTextChangedListener(holder.textWatcher)
                }

                holder.etRename.setText(data.customName)
                holder.etRename.isEnabled = data.isChecked
                
                holder.textWatcher = object: TextWatcher {
                    override fun afterTextChanged(s: Editable?) { data.customName = s.toString() }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                }
                holder.etRename.addTextChangedListener(holder.textWatcher)

                holder.cb.setOnCheckedChangeListener(null)
                holder.cb.isChecked = data.isChecked
                
                holder.cb.setOnCheckedChangeListener { _, isChecked ->
                    if (data.isChecked == isChecked) return@setOnCheckedChangeListener
                    data.isChecked = isChecked
                    holder.etRename.isEnabled = isChecked
                    
                    val checkedItems = catalogList.filter { it.isChecked }
                    val uncheckedItems = catalogList.filter { !it.isChecked }
                    catalogList.clear()
                    catalogList.addAll(checkedItems)
                    catalogList.addAll(uncheckedItems)
                    notifyDataSetChanged() 
                }
            }
            override fun getItemCount() = catalogList.size
        }
        val catalogAdapter = CatalogAdapter()
        rvCatalogs.adapter = catalogAdapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                Collections.swap(catalogList, from, to)
                catalogAdapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(rvCatalogs)
        mainContainer.addView(rvCatalogs)

        val footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 30, 0, 0) }
        val applyBtn = Button(ctx).apply { text = "Save"; setTextColor(Color.WHITE); isAllCaps = false; background = getButtonBg(); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,15,0) } }
        val closeBtn = Button(ctx).apply { text = "Cancel"; setTextColor(Color.WHITE); isAllCaps = false; background = getButtonBg(); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15,0,0,0) } }
        footer.addView(applyBtn); footer.addView(closeBtn); mainContainer.addView(footer)

        val dialog = AlertDialog.Builder(ctx).setView(mainContainer).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // 🔥 [키보드 버그 최종 픽스] AlertDialog가 화면에 뜰 때 키보드 차단 플래그를 영구 해제
        dialog.setOnShowListener {
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        applyBtn.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val editor = prefs.edit()
                
                tempAutoAddSettings.forEach { (pName, isChecked) -> editor.putBoolean("auto_add_${instanceId}_$pName", isChecked) }
                
                val activeCatsForGson = mutableListOf<EvoCatalogConfig>()
                for ((index, item) in catalogList.withIndex()) {
                    val finalOrder = index + 1 
                    editor.putBoolean("cat_toggle_${instanceId}_${item.providerName}_${item.catalogName}", item.isChecked)
                    editor.putString("cat_rename_${instanceId}_${item.providerName}_${item.catalogName}", item.customName)
                    editor.putString("cat_order_${instanceId}_${item.providerName}_${item.catalogName}", finalOrder.toString())
                    
                    if (item.isChecked) {
                        val origData = APIHolder.allProviders.find { it.name == item.providerName }?.mainPage?.find { it.name == item.catalogName }?.data ?: ""
                        activeCatsForGson.add(EvoCatalogConfig(item.providerName, item.catalogName, item.customName, origData, finalOrder))
                    }
                }
                
                val jsonStr = UltimaEvoPlugin.gson.toJson(activeCatsForGson)
                editor.putString("evo_active_cats_json_$instanceId", jsonStr)
                editor.commit()

                APIHolder.allProviders.forEach { p -> if (p is UltimaEvoProvider) p.invalidateCache() }
                
                withContext(Dispatchers.Main) { dialog.dismiss() }
            }
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
