// v1.46
package com.ultimaevo

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UltimaEvoSettingsDialog : BottomSheetDialogFragment() {

    private var lastClickTime: Long = 0

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
                    key.startsWith("cat_order_${next}_") || key.startsWith("ext_toggle_${next}_")) {
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
                key.startsWith("cat_order_${latestCount}_") || key.startsWith("ext_toggle_${latestCount}_")) {
                editor.remove(key)
            }
        }
        
        val newCount = if (latestCount > 0) latestCount - 1 else 0
        editor.putString("evo_instance_count", newCount.toString())
        editor.commit()
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
                
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val providers = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
                    val total = providers.size
                    var current = 0

                    for (provider in providers) {
                        current++
                        withContext(Dispatchers.Main) {
                            this@apply.text = "Syncing... $current / $total"
                        }
                        try {
                            val reqName = provider.mainPage.firstOrNull()?.name ?: "Home"
                            val reqData = provider.mainPage.firstOrNull()?.data ?: ""
                            val response = provider.getMainPage(1, MainPageRequest(reqName, reqData, false))
                            if (response != null && response.items.isNotEmpty()) {
                                val names = response.items.map { it.name }
                                prefs.edit().putString("dynamic_catalogs_${provider.name}", names.joinToString("|||")).apply() 
                            }
                        } catch (e: Exception) { }
                    }
                    withContext(Dispatchers.Main) {
                        this@apply.text = "✅ Sync Complete"
                        this@apply.isEnabled = true
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
                editor.commit()
                
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
                    AlertDialog.Builder(requireContext())
                        .setTitle("Delete Instances")
                        .setMessage("Reducing instances from $oldCount to $newCount will delete the last ${oldCount - newCount} catalog(s).\nAre you sure?")
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
                                        key.startsWith("cat_order_${i}_") || key.startsWith("ext_toggle_${i}_")) {
                                        editor.remove(key)
                                    }
                                }
                            }
                            editor.commit()
                            val act = activity
                            dismiss()
                            UltimaEvoSettingsDialog().show(act!!.supportFragmentManager, "UltimaEvoSettingsDialog")
                        }
                        .setNegativeButton("No") { _, _ ->
                            instanceCountInput.setText(oldCount.toString())
                        }
                        .show()
                } else if (newCount > oldCount) {
                    val editor = prefs.edit()
                    saveCurrentNamesToPrefs(editor, mainLayout)
                    editor.putString("evo_instance_count", newCount.toString())
                    editor.commit()
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
                    prefs.edit().putBoolean("evo_visibility_enabled_$i", isChecked).commit() 
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
                    prefs.edit().putBoolean("evo_search_enabled_$i", isChecked).commit() 
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
                    
                    AlertDialog.Builder(requireContext())
                        .setTitle("Delete Catalog")
                        .setMessage("Are you sure you want to delete this catalog?")
                        .setPositiveButton("Yes") { _, _ ->
                            val latestCount = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1
                            shiftAndDeleteInstance(prefs, i, latestCount)
                            
                            val act = activity
                            dismiss()
                            UltimaEvoSettingsDialog().show(act!!.supportFragmentManager, "UltimaEvoSettingsDialog")
                        }
                        .setNegativeButton("No", null)
                        .show()
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
                    AlertDialog.Builder(requireContext())
                        .setTitle("Delete Instances")
                        .setMessage("Reducing instances from $oldCount to $newCount will delete the last ${oldCount - newCount} catalog(s).\nAre you sure?")
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
                                        key.startsWith("cat_order_${i}_") || key.startsWith("ext_toggle_${i}_")) {
                                        editor.remove(key)
                                    }
                                }
                            }
                            editor.commit()
                            dismiss()
                        }
                        .setNegativeButton("No") { _, _ ->
                            instanceCountInput.setText(oldCount.toString())
                        }
                        .show()
                    return@setOnClickListener
                }

                val editor = prefs.edit()
                editor.putString("evo_instance_count", newCount.toString())
                saveCurrentNamesToPrefs(editor, mainLayout)
                editor.commit()
                dismiss()
            }
        }
        mainLayout.addView(saveBtn)

        scrollView.addView(mainLayout)
        return scrollView
    }

    private fun showEditCatalogsDialog(instanceId: Int, ctx: Context) {
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
                if (listLayout.getChildAt(i) != rowView) {
                    listLayout.removeView(rowView)
                    listLayout.addView(rowView, i)
                }
            }

            focusedView?.requestFocus()
            if (selStart in 0..(focusedView?.text?.length ?: 0) && selEnd in 0..(focusedView?.text?.length ?: 0)) {
                focusedView?.setSelection(selStart, selEnd)
            }
        }

        fun shiftItem(row: CatalogRow, newOrder: Int) {
            val clampedOrder = newOrder.coerceIn(1, maxOrder)
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
                isChecked = prefs.getBoolean("cat_toggle_${instanceId}_${pName}_$cName", false)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 5, 0)
                }
            }
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

            cb.setOnCheckedChangeListener { _, isChecked -> etRename.isEnabled = isChecked }

            val catalogRow = CatalogRow(pName, cName, "", cb, etOrder, etRename, row)
            activeRows.add(catalogRow)

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
                    val inputVal = etOrder.text.toString().toIntOrNull() ?: (activeRows.indexOf(catalogRow) + 1)
                    shiftItem(catalogRow, inputVal)
                }
            }

            up.setOnClickListener {
                etOrder.clearFocus()
                val cur = activeRows.indexOf(catalogRow) + 1
                if (cur > 1) {
                    shiftItem(catalogRow, cur - 1)
                }
            }
            down.setOnClickListener {
                etOrder.clearFocus()
                val cur = activeRows.indexOf(catalogRow) + 1
                if (cur < maxOrder) {
                    shiftItem(catalogRow, cur + 1)
                }
            }
        }

        for (i in activeRows.indices) {
            activeRows[i].orderEdit.setText((i + 1).toString())
        }
        triggerSort()

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
        footer.addView(applyBtn); footer.addView(closeBtn)
        mainContainer.addView(footer)

        val builder = AlertDialog.Builder(ctx)
        val dialog = builder.setView(mainContainer).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        applyBtn.setOnClickListener {
            if (SystemClock.elapsedRealtime() - lastClickTime < 1000) return@setOnClickListener
            lastClickTime = SystemClock.elapsedRealtime()

            val editor = prefs.edit()
            val activeCatalogs = mutableListOf<String>()

            println("[UltimaEvo-Debug] [Popup] 설정 저장 시도. 체크된 항목들을 구워냅니다.")
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
                    
                    println("[UltimaEvo-Debug] [Popup] 활성화 카탈로그 데이터 수집 -> Provider: ${row.providerName}, Catalog: ${row.catalogName}, OrigData: '$origData'")
                    val safeConfigStr = "${row.providerName}|~|${row.catalogName}|~|${customName}|~|${finalOrder}|~|${origData}"
                    activeCatalogs.add(safeConfigStr)
                }
            }
            
            val joinedStr = activeCatalogs.joinToString("|||")
            println("[UltimaEvo-Debug] [Popup] 디스크에 기록될 최종 문자열: $joinedStr")
            editor.putString("evo_active_cats_$instanceId", joinedStr)
            editor.commit()
            dialog.dismiss()
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
