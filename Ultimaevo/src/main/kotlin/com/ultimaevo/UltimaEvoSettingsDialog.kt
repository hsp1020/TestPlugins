// v1.55 (Export/Import: 다이렉트 클립보드 스캔 및 100% 동기식 처리로 UI 렌더링 렉/크래시 원천 차단)
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
import android.text.InputType
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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

class UltimaEvoSettingsDialog : BottomSheetDialogFragment() {

    private var lastClickTime: Long = 0
    
    // 🌟 중복 다중 팝업 생성을 원천 차단하기 위한 활성 다이얼로그 전역 참조 변수
    private var activeAlertDialog: AlertDialog? = null

    private fun getDarkThemeBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#000000"))
            cornerRadius = 40f
        }
    }

    private fun getCardBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#0C0C0C"))
            setStroke(2, Color.parseColor("#1A1A1A"))
            cornerRadius = 30f
        }
    }

    private fun getButtonBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#151515"))
            cornerRadius = 20f
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun saveCurrentNamesToPrefs(editor: SharedPreferences.Editor, rootLayout: ViewGroup) {
        fun findAndSave(v: ViewGroup) {
            for (idx in 0 until v.childCount) {
                val child = v.getChildAt(idx)
                if (child is EditText && child.tag?.toString()?.startsWith("evo_name_") == true) {
                    editor.putString(child.tag.toString(), child.text.toString())
                } else if (child is ViewGroup) findAndSave(child)
            }
        }
        findAndSave(rootLayout)
    }

    private fun shiftAndDeleteInstance(prefs: SharedPreferences, startIndex: Int, latestCount: Int) {
        val editor = prefs.edit()
        for (j in startIndex until latestCount) {
            val curr = j
            val next = j + 1
            editor.putString("evo_name_$curr", prefs.getString("evo_name_$next", "Ultima Evo $next"))
            editor.putBoolean("evo_visibility_enabled_$curr", prefs.getBoolean("evo_visibility_enabled_$next", true))
            editor.putBoolean("evo_search_enabled_$curr", prefs.getBoolean("evo_search_enabled_$next", true))
            editor.putString("evo_active_cats_$curr", prefs.getString("evo_active_cats_$next", ""))
            
            prefs.all.keys.forEach { key ->
                if (key.startsWith("cat_toggle_${next}_") || key.startsWith("cat_rename_${next}_") || 
                    key.startsWith("cat_order_${next}_") || key.startsWith("ext_toggle_${next}_") ||
                    key.startsWith("auto_add_${next}_")) {
                    val newKey = key.replaceFirst("_${next}_", "_${curr}_")
                    when (val value = prefs.all[key]) {
                        is Boolean -> editor.putBoolean(newKey, value)
                        is String -> editor.putString(newKey, value)
                        is Int -> editor.putInt(newKey, value)
                    }
                }
            }
        }
        
        editor.remove("evo_name_$latestCount")
        editor.remove("evo_visibility_enabled_$latestCount")
        editor.remove("evo_search_enabled_$latestCount")
        editor.remove("evo_active_cats_$latestCount")
        prefs.all.keys.forEach { key ->
            if (key.startsWith("cat_toggle_${latestCount}_") || key.startsWith("cat_rename_${latestCount}_") || 
                key.startsWith("cat_order_${latestCount}_") || key.startsWith("ext_toggle_${latestCount}_") ||
                key.startsWith("auto_add_${latestCount}_")) {
                editor.remove(key)
            }
        }
        
        val newCount = if (latestCount > 0) latestCount - 1 else 0
        editor.putString("evo_instance_count", newCount.toString())
        editor.apply()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)

        val scrollView = ScrollView(ctx).apply { background = getDarkThemeBg() }
        val mainLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(ctx).apply {
            text = "Ultima Evo Settings"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 30)
            gravity = Gravity.CENTER
        }
        mainLayout.addView(title)

        val backupRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 30)
        }

        // ==========================================
        // [다이렉트 클립보드 Export 최적화 완료]
        // ==========================================
        val exportBtn = Button(ctx).apply {
            text = "Export Settings"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,15,0) }
            setOnClickListener {
                if (SystemClock.elapsedRealtime() - lastClickTime < 1000) return@setOnClickListener
                lastClickTime = SystemClock.elapsedRealtime()

                if (activeAlertDialog?.isShowing == true) return@setOnClickListener

                try {
                    val json = JSONObject()
                    prefs.all.forEach { (key, value) -> json.put(key, value) }
                    
                    // 1. 메모리상에서 Base64로 즉시 인코딩 (0.01초 소요)
                    val backupStr = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

                    // 2. 무거운 화면(EditText)에 그리지 않고 즉시 기기 클립보드에 삽입
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Ultima Backup", backupStr)
                    clipboard.setPrimaryClip(clip)

                    // 3. 완료 팝업 호출
                    val dialog = AlertDialog.Builder(ctx)
                        .setTitle("Export Successful")
                        .setMessage("Backup data has been successfully copied to your clipboard.\n\nPlease paste it somewhere safe (e.g., Notes, Messenger).")
                        .setPositiveButton("OK", null)
                        .create()
                    
                    activeAlertDialog = dialog
                    dialog.show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Failed to export settings", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ==========================================
        // [다이렉트 클립보드 Import 사전 검증 및 최적화 완료]
        // ==========================================
        val importBtn = Button(ctx).apply {
            text = "Import Settings"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15,0,0,0) }
            setOnClickListener {
                if (SystemClock.elapsedRealtime() - lastClickTime < 1000) return@setOnClickListener
                lastClickTime = SystemClock.elapsedRealtime()

                if (activeAlertDialog?.isShowing == true) return@setOnClickListener

                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                
                // 1. 클립보드에서 텍스트 직접 읽어오기
                val clipData = clipboard.primaryClip
                val clipboardText = if (clipData != null && clipData.itemCount > 0) {
                    clipData.getItemAt(0).text?.toString()?.trim() ?: ""
                } else ""

                if (clipboardText.isEmpty()) {
                    Toast.makeText(ctx, "Clipboard is empty. Copy the backup data first.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                try {
                    // 2. 덮어쓰기 전, 백업 코드가 유효한지 사전 파싱 테스트 (철벽 방어)
                    val decodedBytes = Base64.decode(clipboardText, Base64.NO_WRAP)
                    val jsonString = String(decodedBytes, Charsets.UTF_8)
                    val parsedJson = JSONObject(jsonString)
                    
                    if (parsedJson.length() == 0) throw Exception("Empty JSON Data")

                    // 3. 정상 데이터임이 확인되면 복원 팝업 호출
                    val dialog = AlertDialog.Builder(ctx)
                        .setTitle("Backup Data Detected")
                        .setMessage("Valid backup data found in clipboard!\n\nWarning: Restoring will permanently overwrite all current settings.\nAre you sure you want to proceed?")
                        .setPositiveButton("Restore") { _, _ ->
                            val editor = prefs.edit()
                            editor.clear() 
                            
                            val keys = parsedJson.keys()
                            val importedProviders = mutableSetOf<String>()
                            
                            while (keys.hasNext()) {
                                val key = keys.next()
                                when (val value = parsedJson.get(key)) {
                                    is Boolean -> editor.putBoolean(key, value)
                                    is String -> {
                                        editor.putString(key, value)
                                        // Ultima Evo 특화 기능: 임포트된 프로바이더 목록 수집
                                        if (key.startsWith("evo_active_cats_")) {
                                            val chunkSeparator = if (value.contains("%%%")) "%%%" else "|||"
                                            val fieldSeparator = if (value.contains("%%%")) ";;;" else "|~|"
                                            value.split(chunkSeparator).forEach { chunk ->
                                                val parts = chunk.split(fieldSeparator, limit = 5)
                                                if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                                                    importedProviders.add(parts[0])
                                                }
                                            }
                                        }
                                    }
                                    is Int -> editor.putInt(key, value)
                                }
                            }
                            editor.commit() // 확실한 동기식 저장 적용
                            
                            // 4. 누락된 플러그인 유무 확인 (UltimaEvo 특화 기능)
                            val currentlyInstalled = APIHolder.allProviders.map { it.name }.toSet()
                            val missingProviders = importedProviders.subtract(currentlyInstalled)
                            
                            var successMessage = "Import successful. Please restart the app."
                            if (missingProviders.isNotEmpty()) {
                                val missingList = missingProviders.joinToString("\n") { "• $it" }
                                successMessage = "Import successful, but the following plugins are missing:\n\n$missingList\n\nTheir catalogs will be hidden until installed.\nPlease restart the app."
                            }
                            
                            val resultDialog = AlertDialog.Builder(ctx)
                                .setTitle("Import Result")
                                .setMessage(successMessage)
                                .setPositiveButton("OK") { _, _ -> dismiss() }
                                .create()
                            
                            activeAlertDialog = resultDialog
                            resultDialog.show()
                        }
                        .setNegativeButton("Cancel", null)
                        .create()
                    
                    activeAlertDialog = dialog
                    dialog.show()

                } catch (e: Exception) {
                    // 클립보드에 카톡 대화 등 이상한 텍스트가 있을 경우 철벽 에러 팝업
                    val errDialog = AlertDialog.Builder(ctx)
                        .setTitle("Invalid Backup Data")
                        .setMessage("No valid backup data found in your clipboard.\n\nPlease copy the exact backup text and try again.")
                        .setPositiveButton("OK", null)
                        .create()
                    activeAlertDialog = errDialog
                    errDialog.show()
                }
            }
        }
        
        backupRow.addView(exportBtn)
        backupRow.addView(importBtn)
        mainLayout.addView(backupRow)

        val syncAllBtn = Button(ctx).apply {
            text = "Sync Provider Data"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 30)
            }
            setOnClickListener {
                if (SystemClock.elapsedRealtime() - lastClickTime < 1000) return@setOnClickListener
                lastClickTime = SystemClock.elapsedRealtime()

                this.isEnabled = false
                
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        UltimaEvoSyncManager.performSync(ctx, forceSyncAll = true) { current, total ->
                            withContext(Dispatchers.Main) {
                                this@apply.text = "Syncing... $current / $total"
                            }
                        }
                        withContext(Dispatchers.Main) {
                            this@apply.text = "✅ Sync Complete"
                            this@apply.isEnabled = true
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            this@apply.text = "❌ Sync Failed"
                            this@apply.isEnabled = true
                        }
                    }
                }
            }
        }
        mainLayout.addView(syncAllBtn)

        val countLayout = LinearLayout(ctx).apply { 
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 20)
        }
        countLayout.addView(TextView(ctx).apply { text = "Instances:"; textSize = 15f; setTextColor(Color.LTGRAY); setPadding(0,0,20,0) })
        
        val instanceCountInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            val initCount = prefs.getString("evo_instance_count", "1")
            setText(initCount)
            setTextColor(Color.WHITE)
            background = getButtonBg()
            setPadding(30, 20, 30, 20)
            layoutParams = LinearLayout.LayoutParams(150, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        }
        countLayout.addView(instanceCountInput)

        val addInstanceBtn = Button(ctx).apply {
            text = "+"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(120, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(20, 0, 0, 0)
            }
            setOnClickListener {
                if (SystemClock.elapsedRealtime() - lastClickTime < 500) return@setOnClickListener
                lastClickTime = SystemClock.elapsedRealtime()
                
                val current = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1
                val newCount = current + 1
                
                val editor = prefs.edit()
                saveCurrentNamesToPrefs(editor, mainLayout)
                editor.putString("evo_instance_count", newCount.toString())
                editor.apply()
                
                val act = activity
                dismiss()
                UltimaEvoSettingsDialog().show(act!!.supportFragmentManager, "UltimaEvoSettingsDialog")
            }
        }
        countLayout.addView(addInstanceBtn)
        mainLayout.addView(countLayout)

        instanceCountInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                instanceCountInput.clearFocus()
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(instanceCountInput.windowToken, 0)
                true
            } else false
        }

        instanceCountInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val oldCount = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1
                val newCount = instanceCountInput.text.toString().toIntOrNull() ?: oldCount

                if (newCount < oldCount) {
                    if (activeAlertDialog?.isShowing == true) return@setOnFocusChangeListener

                    val dialog = AlertDialog.Builder(requireContext())
                        .setTitle("Delete Instances")
                        .setMessage("Reducing instances from $oldCount to $newCount will delete the last ${oldCount - newCount} catalog(s).\nAre you sure?\n\nPlease restart the app for the changes to take effect.")
                        .setPositiveButton("Yes") { _, _ ->
                            val editor = prefs.edit()
                            saveCurrentNamesToPrefs(editor, mainLayout)
                            editor.putString("evo_instance_count", newCount.toString())
                            for (i in (newCount + 1)..oldCount) {
                                editor.remove("evo_name_$i")
                                editor.remove("evo_visibility_enabled_$i")
                                editor.remove("evo_search_enabled_$i")
                                editor.remove("evo_active_cats_$i")
                                prefs.all.keys.forEach { key ->
                                    if (key.startsWith("cat_toggle_${i}_") || key.startsWith("cat_rename_${i}_") || 
                                        key.startsWith("cat_order_${i}_") || key.startsWith("ext_toggle_${i}_") ||
                                        key.startsWith("auto_add_${i}_")) {
                                        editor.remove(key)
                                    }
                                }
                            }
                            editor.apply()
                            val act = activity
                            dismiss()
                            UltimaEvoSettingsDialog().show(act!!.supportFragmentManager, "UltimaEvoSettingsDialog")
                        }
                        .setNegativeButton("No") { _, _ ->
                            instanceCountInput.setText(oldCount.toString())
                        }
                        .create()
                    
                    activeAlertDialog = dialog
                    dialog.show()
                } else if (newCount > oldCount) {
                    val editor = prefs.edit()
                    saveCurrentNamesToPrefs(editor, mainLayout)
                    editor.putString("evo_instance_count", newCount.toString())
                    editor.apply()
                    val act = activity
                    dismiss()
                    UltimaEvoSettingsDialog().show(act!!.supportFragmentManager, "UltimaEvoSettingsDialog")
                }
            }
        }

        val currentCount = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1

        for (i in 1..currentCount) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 30, 30, 30)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 15, 0, 15)
                }
                background = getCardBg()
            }
            
            val nameRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 20)
            }
            val nameLabel = TextView(ctx).apply {
                text = "Catalog Name:"
                setTextColor(Color.parseColor("#CCCCCC"))
                textSize = 14f
                setPadding(0, 0, 20, 0)
            }
            val savedName = prefs.getString("evo_name_$i", "Ultima Evo $i")
            val nameInput = EditText(ctx).apply {
                hint = "Ultima Evo $i"
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(20, 20, 20, 20)
                background = getButtonBg()
                setText(savedName)
                tag = "evo_name_$i"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setSingleLine(true) 
            }
            nameRow.addView(nameLabel)
            nameRow.addView(nameInput)
            card.addView(nameRow)

            val toggleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10, 0, 10)
            }
            val visibilitySwitch = Switch(ctx).apply {
                text = "Show Catalog"
                setTextColor(Color.WHITE)
                textSize = 14f
                isChecked = prefs.getBoolean("evo_visibility_enabled_$i", true)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnCheckedChangeListener { _, isChecked -> 
                    prefs.edit().putBoolean("evo_visibility_enabled_$i", isChecked).apply()
                }
            }
            val searchSwitch = Switch(ctx).apply {
                text = "Enable Search"
                setTextColor(Color.WHITE)
                textSize = 14f
                isChecked = prefs.getBoolean("evo_search_enabled_$i", true)
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(50, 0, 0, 0) 
                layoutParams = params
                setOnCheckedChangeListener { _, isChecked -> 
                    prefs.edit().putBoolean("evo_search_enabled_$i", isChecked).apply()
                }
            }
            toggleRow.addView(visibilitySwitch)
            toggleRow.addView(searchSwitch)
            card.addView(toggleRow)

            val actionBtnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            
            val configBtn = Button(ctx).apply {
                text = "Configure Providers"
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = getButtonBg()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, 15, 0)
                }
                setOnClickListener {
                    if (SystemClock.elapsedRealtime() - lastClickTime < 1000) return@setOnClickListener
                    lastClickTime = SystemClock.elapsedRealtime()
                    showEditCatalogsDialog(i, ctx) 
                }
            }

            val deleteBtn = Button(ctx).apply {
                text = "Delete"
                setTextColor(Color.parseColor("#FF5555"))
                isAllCaps = false
                background = getButtonBg()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    if (SystemClock.elapsedRealtime() - lastClickTime < 1000) return@setOnClickListener
                    lastClickTime = SystemClock.elapsedRealtime()

                    if (activeAlertDialog?.isShowing == true) return@setOnClickListener
                    
                    val dialog = AlertDialog.Builder(requireContext())
                        .setTitle("Delete Catalog")
                        .setMessage("Are you sure you want to delete this catalog?\n\nPlease restart the app for the changes to take effect.")
                        .setPositiveButton("Yes") { _, _ ->
                            val latestCount = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1
                            shiftAndDeleteInstance(prefs, i, latestCount)
                            
                            val act = activity
                            dismiss()
                            UltimaEvoSettingsDialog().show(act!!.supportFragmentManager, "UltimaEvoSettingsDialog")
                        }
                        .setNegativeButton("No", null)
                        .create()
                    
                    activeAlertDialog = dialog
                    dialog.show()
                }
            }

            actionBtnRow.addView(configBtn)
            actionBtnRow.addView(deleteBtn)
            card.addView(actionBtnRow)
            
            mainLayout.addView(card)
        }

        val saveBtn = Button(ctx).apply {
            text = "Save & Close"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 30, 0, 0)
            }
            setOnClickListener {
                if (SystemClock.elapsedRealtime() - lastClickTime < 1000) return@setOnClickListener
                lastClickTime = SystemClock.elapsedRealtime()

                val oldCount = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1
                val newCount = instanceCountInput.text.toString().toIntOrNull() ?: oldCount

                if (newCount < oldCount) {
                    if (activeAlertDialog?.isShowing == true) return@setOnClickListener

                    val dialog = AlertDialog.Builder(requireContext())
                        .setTitle("Delete Instances")
                        .setMessage("Reducing instances from $oldCount to $newCount will delete the last ${oldCount - newCount} catalog(s).\nAre you sure?\n\nPlease restart the app for the changes to take effect.")
                        .setPositiveButton("Yes") { _, _ ->
                            val editor = prefs.edit()
                            saveCurrentNamesToPrefs(editor, mainLayout)
                            editor.putString("evo_instance_count", newCount.toString())
                            for (i in (newCount + 1)..oldCount) {
                                editor.remove("evo_name_$i")
                                editor.remove("evo_visibility_enabled_$i")
                                editor.remove("evo_search_enabled_$i")
                                editor.remove("evo_active_cats_$i")
                                prefs.all.keys.forEach { key ->
                                    if (key.startsWith("cat_toggle_${i}_") || key.startsWith("cat_rename_${i}_") || 
                                        key.startsWith("cat_order_${i}_") || key.startsWith("ext_toggle_${i}_") ||
                                        key.startsWith("auto_add_${i}_")) {
                                        editor.remove(key)
                                    }
                                }
                            }
                            editor.apply()
                            dismiss()
                        }
                        .setNegativeButton("No") { _, _ ->
                            instanceCountInput.setText(oldCount.toString())
                        }
                        .create()
                    
                    activeAlertDialog = dialog
                    dialog.show()
                    return@setOnClickListener
                }

                if (activeAlertDialog?.isShowing == true) return@setOnClickListener

                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle("Restart Required")
                    .setMessage("Please restart the app for the changes to take effect.")
                    .setPositiveButton("OK") { _, _ ->
                        val editor = prefs.edit()
                        editor.putString("evo_instance_count", newCount.toString())
                        saveCurrentNamesToPrefs(editor, mainLayout)
                        editor.apply()
                        dismiss()
                    }
                    .create()
                
                activeAlertDialog = dialog
                dialog.show()
            }
        }
        mainLayout.addView(saveBtn)

        scrollView.addView(mainLayout)
        return scrollView
    }

    private fun showEditCatalogsDialog(instanceId: Int, ctx: Context) {
        if (activeAlertDialog?.isShowing == true) return

        val prefs = ctx.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)

        val mainContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(25, 40, 25, 30)
            background = getDarkThemeBg()
        }

        val headerTitle = TextView(ctx).apply {
            text = "Configure Providers"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 30)
            gravity = Gravity.CENTER
        }
        mainContainer.addView(headerTitle)

        val scroll = ScrollView(ctx)
        val listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        class CatalogRow(
            val providerName: String, val catalogName: String, val originalData: String,
            val checkBox: CheckBox, val orderEdit: EditText, val renameEdit: EditText, val view: View
        )
        val activeRows = mutableListOf<CatalogRow>()

        val allProviders = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
        
        val providerSettingsCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(25, 25, 25, 25)
            background = getCardBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 20)
            }
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val providerSettingsTitle = TextView(ctx).apply {
            text = "Provider Auto-Add Settings"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val toggleArrow = TextView(ctx).apply {
            text = "▼"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(15, 10, 15, 10)
        }

        headerRow.addView(providerSettingsTitle)
        headerRow.addView(toggleArrow)
        providerSettingsCard.addView(headerRow)

        val providersContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 15, 0, 0)
        }
        providerSettingsCard.addView(providersContainer)

        headerRow.setOnClickListener {
            if (providersContainer.visibility == View.VISIBLE) {
                providersContainer.visibility = View.GONE
                toggleArrow.text = "▼"
            } else {
                providersContainer.visibility = View.VISIBLE
                toggleArrow.text = "▲"
            }
        }

        val tempAutoAddSettings = mutableMapOf<String, Boolean>()
        val providerCheckBoxMap = mutableMapOf<String, MutableList<CheckBox>>()

        for (provider in allProviders) {
            val pName = provider.name
            tempAutoAddSettings[pName] = prefs.getBoolean("auto_add_${instanceId}_$pName", false)
            providerCheckBoxMap[pName] = mutableListOf()

            val providerRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val providerLabel = TextView(ctx).apply {
                text = pName
                setTextColor(Color.LTGRAY)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val providerSwitch = Switch(ctx).apply {
                text = "Auto-add"
                textSize = 12f
                isChecked = tempAutoAddSettings[pName] ?: false
                setOnCheckedChangeListener { _, isChecked ->
                    tempAutoAddSettings[pName] = isChecked
                }
            }
            providerRow.addView(providerLabel)
            providerRow.addView(providerSwitch)
            
            providersContainer.addView(providerRow)
        }
        listLayout.addView(providerSettingsCard)

        val allCatalogs = mutableListOf<Pair<String, String>>()

        for (provider in allProviders) {
            val staticCats = provider.mainPage.map { it.name }
            val dynamicCats = prefs.getString("dynamic_catalogs_${provider.name}", "")?.split("|||") ?: emptyList()
            val combined = (staticCats + dynamicCats).filter { it.isNotBlank() }.distinct()
            combined.forEach { allCatalogs.add(provider.name to it) }
        }

        val maxOrder = allCatalogs.size

        fun triggerSort() {
            val focusedView = listLayout.findFocus() as? EditText
            val selStart = focusedView?.selectionStart ?: 0
            val selEnd = focusedView?.selectionEnd ?: 0

            for (i in activeRows.indices) {
                val rowView = activeRows[i].view
                val expectedIndex = i + 1
                if (listLayout.getChildAt(expectedIndex) != rowView) {
                    listLayout.removeView(rowView)
                    listLayout.addView(rowView, expectedIndex)
                }
            }

            focusedView?.requestFocus()
            if (selStart in 0..(focusedView?.text?.length ?: 0) && selEnd in 0..(focusedView?.text?.length ?: 0)) {
                focusedView?.setSelection(selStart, selEnd)
            }
        }

        fun reorderRowsByCheckState() {
            val checked = activeRows.filter { it.checkBox.isChecked }
            val unchecked = activeRows.filter { !it.checkBox.isChecked }
            activeRows.clear()
            activeRows.addAll(checked)
            activeRows.addAll(unchecked)
            for (i in activeRows.indices) {
                activeRows[i].orderEdit.setText((i + 1).toString())
            }
            triggerSort()
        }

        fun shiftItem(row: CatalogRow, newOrder: Int) {
            val checkedCount = activeRows.count { it.checkBox.isChecked }
            val groupMin = if (row.checkBox.isChecked) 1 else checkedCount + 1
            val groupMax = if (row.checkBox.isChecked) checkedCount else maxOrder
            val clampedOrder = newOrder.coerceIn(groupMin, groupMax)

            val oldIndex = activeRows.indexOf(row)
            val newIndex = clampedOrder - 1

            if (oldIndex == -1) return

            if (oldIndex != newIndex) {
                activeRows.removeAt(oldIndex)
                activeRows.add(newIndex, row)
            }

            for (i in activeRows.indices) {
                activeRows[i].orderEdit.setText((i + 1).toString())
            }

            triggerSort()
        }

        val preSortedCatalogs = allCatalogs.map {
            val order = prefs.getString("cat_order_${instanceId}_${it.first}_${it.second}", "99")?.toIntOrNull() ?: 99
            Triple(it.first, it.second, order)
        }.sortedBy { it.third }

        for (cat in preSortedCatalogs) {
            val pName = cat.first
            val cName = cat.second

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10, 8, 10, 8)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 5, 0, 5)
                }
                background = getCardBg()
            }

            val cb = CheckBox(ctx).apply {
                val isAutoAddEnabled = tempAutoAddSettings[pName] ?: false
                isChecked = prefs.getBoolean("cat_toggle_${instanceId}_${pName}_$cName", isAutoAddEnabled)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 5, 0)
                }
            }
            providerCheckBoxMap[pName]?.add(cb)
            row.addView(cb)

            val etOrder = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                imeOptions = EditorInfo.IME_ACTION_DONE
                layoutParams = LinearLayout.LayoutParams(70, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 5, 0)
                }
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(0, 10, 0, 10)
                background = getButtonBg()
            }
            row.addView(etOrder)

            val etSource = TextView(ctx).apply {
                text = pName
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 11f
                setPadding(15, 8, 15, 8)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#222222"))
                    cornerRadius = 15f
                }
                val sourceParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                sourceParams.setMargins(5, 0, 10, 0)
                layoutParams = sourceParams
            }
            row.addView(etSource)

            val etRename = EditText(ctx).apply {
                hint = cName
                setText(prefs.getString("cat_rename_${instanceId}_${pName}_$cName", cName))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isEnabled = cb.isChecked
                setTextColor(Color.WHITE)
                setHintTextColor(Color.DKGRAY)
                textSize = 13f
                setPadding(15, 10, 15, 10)
                background = getButtonBg()
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            row.addView(etRename)

            val up = TextView(ctx).apply {
                text = "▲"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(10, 5, 10, 5)
            }
            val down = TextView(ctx).apply {
                text = "▼"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(10, 5, 10, 5)
            }
            row.addView(up)
            row.addView(down)

            val catalogRow = CatalogRow(pName, cName, "", cb, etOrder, etRename, row)
            activeRows.add(catalogRow)

            cb.setOnCheckedChangeListener { _, isChecked ->
                etRename.isEnabled = isChecked
                reorderRowsByCheckState()
            }

            etOrder.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                    etOrder.clearFocus()
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(etOrder.windowToken, 0)
                    true
                } else false
            }

            etOrder.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val inputVal = etOrder.text.toString().toIntOrNull()
                        ?: (activeRows.indexOf(catalogRow) + 1)
                    shiftItem(catalogRow, inputVal)
                }
            }

            up.setOnClickListener {
                etOrder.clearFocus()
                val cur = activeRows.indexOf(catalogRow) + 1
                val checkedCount = activeRows.count { it.checkBox.isChecked }
                val groupMin = if (catalogRow.checkBox.isChecked) 1 else checkedCount + 1
                if (cur > groupMin) {
                    shiftItem(catalogRow, cur - 1)
                }
            }
            down.setOnClickListener {
                etOrder.clearFocus()
                val cur = activeRows.indexOf(catalogRow) + 1
                val checkedCount = activeRows.count { it.checkBox.isChecked }
                val groupMax = if (catalogRow.checkBox.isChecked) checkedCount else maxOrder
                if (cur < groupMax) {
                    shiftItem(catalogRow, cur + 1)
                }
            }
        }

        reorderRowsByCheckState()

        scroll.addView(listLayout)
        mainContainer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 30, 0, 0)
        }
        val applyBtn = Button(ctx).apply {
            text = "Save"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,15,0) }
        }
        val closeBtn = Button(ctx).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15,0,0,0) }
        }
        footer.addView(applyBtn)
        footer.addView(closeBtn)
        mainContainer.addView(footer)

        val builder = AlertDialog.Builder(ctx)
        val dialog = builder.setView(mainContainer).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        applyBtn.setOnClickListener {
            if (SystemClock.elapsedRealtime() - lastClickTime < 1000) return@setOnClickListener
            lastClickTime = SystemClock.elapsedRealtime()

            val editor = prefs.edit()

            val keysToRemove = prefs.all.keys.filter {
                it.startsWith("cat_toggle_${instanceId}_") ||
                it.startsWith("cat_rename_${instanceId}_") ||
                it.startsWith("cat_order_${instanceId}_") ||
                it.startsWith("ext_toggle_${instanceId}_")
            }
            for (k in keysToRemove) editor.remove(k)

            val activeCatalogs = mutableListOf<String>()

            for ((index, row) in activeRows.withIndex()) {
                val finalOrder = index + 1
                val tKey = "cat_toggle_${instanceId}_${row.providerName}_${row.catalogName}"
                val rKey = "cat_rename_${instanceId}_${row.providerName}_${row.catalogName}"
                val oKey = "cat_order_${instanceId}_${row.providerName}_${row.catalogName}"

                editor.putBoolean(tKey, row.checkBox.isChecked)
                editor.putString(rKey, row.renameEdit.text.toString())
                editor.putString(oKey, finalOrder.toString())

                val pKey = "ext_toggle_${instanceId}_${row.providerName}"
                if (row.checkBox.isChecked) {
                    editor.putBoolean(pKey, true)

                    val customName = row.renameEdit.text.toString().ifBlank { row.catalogName }
                    val origProvider = APIHolder.allProviders.find { it.name == row.providerName }
                    val origData = origProvider?.mainPage?.find { it.name == row.catalogName }?.data ?: ""

                    val safeConfigStr = "${row.providerName};;;${row.catalogName};;;${customName};;;${finalOrder};;;${origData}"
                    activeCatalogs.add(safeConfigStr)
                }
            }

            tempAutoAddSettings.forEach { (pName, isChecked) ->
                editor.putBoolean("auto_add_${instanceId}_$pName", isChecked)
            }

            editor.putString("evo_active_cats_$instanceId", activeCatalogs.joinToString("%%%"))
            editor.apply()

            APIHolder.allProviders.forEach { p ->
                if (p is UltimaEvoProvider) {
                    p.invalidateCache()
                }
            }
            dialog.dismiss()
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        
        activeAlertDialog = dialog
        dialog.show()
    }
}
