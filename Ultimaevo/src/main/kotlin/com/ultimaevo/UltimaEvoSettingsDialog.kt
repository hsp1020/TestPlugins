// v1.36
package com.ultimaevo

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UltimaEvoSettingsDialog : BottomSheetDialogFragment() {

    private fun getDarkThemeBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#121212"))
            setStroke(3, Color.WHITE)
            cornerRadius = 16f
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val pluginCtx = UltimaEvoPlugin.pluginContext ?: requireContext()
        val prefs = pluginCtx.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)

        val scrollView = ScrollView(pluginCtx).apply { background = getDarkThemeBg() }
        val mainLayout = LinearLayout(pluginCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        val title = TextView(pluginCtx).apply {
            text = "Ultima Evo 마스터 관리자"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
            gravity = Gravity.CENTER
        }
        mainLayout.addView(title)

        val syncAllBtn = Button(pluginCtx).apply {
            text = "🔄 모든 카탈로그 데이터 최초 동기화"
            setTextColor(Color.WHITE)
            setPadding(10, 0, 10, 0)
            setOnClickListener {
                this.text = "통신 중..."
                this.isEnabled = false
                CoroutineScope(Dispatchers.IO).launch {
                    val providers = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
                    for (provider in providers) {
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
                        this@apply.text = "✅ 동기화 완료"
                        this@apply.isEnabled = true
                    }
                }
            }
        }
        mainLayout.addView(syncAllBtn)

        val countLayout = LinearLayout(pluginCtx).apply { 
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 15, 0, 15)
        }
        countLayout.addView(TextView(pluginCtx).apply { text = "생성할 개수: "; textSize = 14f; setTextColor(Color.WHITE) })
        val instanceCountInput = EditText(pluginCtx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            val initCount = prefs.getString("evo_instance_count", "1")
            setText(initCount)
            setTextColor(Color.WHITE)
            setPadding(10, 10, 10, 10)
            layoutParams = LinearLayout.LayoutParams(120, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        countLayout.addView(instanceCountInput)
        mainLayout.addView(countLayout)

        val currentCount = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1

        for (i in 1..currentCount) {
            val card = LinearLayout(pluginCtx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 10, 0, 10)
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E1E"))
                    setStroke(2, Color.DKGRAY)
                    cornerRadius = 12f
                }
            }
            
            val savedName = prefs.getString("evo_name_$i", "Ultima Evo $i")
            val nameInput = EditText(pluginCtx).apply {
                hint = "통합 카탈로그 $i 이름"
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(10, 10, 10, 10)
                setText(savedName)
                tag = "evo_name_$i"
            }
            card.addView(nameInput)

            val searchSwitch = Switch(pluginCtx).apply {
                text = "이 카탈로그 검색 허용"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 10, 0, 10)
                isChecked = prefs.getBoolean("evo_search_enabled_$i", true)
                setOnCheckedChangeListener { _, isChecked -> 
                    prefs.edit().putBoolean("evo_search_enabled_$i", isChecked).commit() 
                }
            }
            card.addView(searchSwitch)

            val configBtn = Button(pluginCtx).apply {
                text = "내부 카탈로그 구성/정렬 편집 ⚙️"
                setTextColor(Color.WHITE)
                setPadding(10, 0, 10, 0)
                setOnClickListener { 
                    showEditCatalogsDialog(i, pluginCtx) 
                }
            }
            card.addView(configBtn)
            
            mainLayout.addView(card)
        }

        val saveBtn = Button(pluginCtx).apply {
            text = "메인 설정 저장 후 닫기"
            setTextColor(Color.WHITE)
            setPadding(10, 10, 10, 10)
            setOnClickListener {
                val editor = prefs.edit()
                editor.putString("evo_instance_count", instanceCountInput.text.toString())
                
                fun findNames(v: ViewGroup) {
                    for (idx in 0 until v.childCount) {
                        val child = v.getChildAt(idx)
                        if (child is EditText && child.tag?.toString()?.startsWith("evo_name_") == true) {
                            editor.putString(child.tag.toString(), child.text.toString())
                        } else if (child is ViewGroup) findNames(child)
                    }
                }
                findNames(mainLayout)
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
            setPadding(15, 15, 15, 15)
            background = getDarkThemeBg()
        }
        
        val headerTitle = TextView(ctx).apply {
            text = "카탈로그 $instanceId 구성 편집"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 10)
            gravity = Gravity.CENTER
        }
        mainContainer.addView(headerTitle)

        // 안내 텍스트 수정
        val warningText = TextView(ctx).apply {
            text = "※ 숫자를 입력하거나 화살표를 누르면 즉시 번호가 밀려나며 재정렬됩니다."
            textSize = 12f
            setTextColor(Color.YELLOW)
            setPadding(0, 0, 0, 20)
            gravity = Gravity.CENTER
        }
        mainContainer.addView(warningText)

        val scroll = ScrollView(ctx)
        val listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        
        class CatalogRow(
            val providerName: String, val catalogName: String, val originalData: String,
            val checkBox: CheckBox, val orderEdit: EditText, val renameEdit: EditText, val view: View
        )
        // [핵심] 현재 UI의 순서를 물리적으로 보관하는 스택 리스트
        val activeRows = mutableListOf<CatalogRow>()

        val allProviders = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
        val allCatalogs = mutableListOf<Pair<String, String>>() 

        for (provider in allProviders) {
            val staticCats = provider.mainPage.map { it.name }
            val dynamicCats = prefs.getString("dynamic_catalogs_${provider.name}", "")?.split("|||") ?: emptyList()
            val combined = (staticCats + dynamicCats).filter { it.isNotBlank() }.distinct()
            combined.forEach { allCatalogs.add(provider.name to it) }
        }
        
        // 최대 순서값은 항상 총 카탈로그의 개수로 고정됩니다.
        val maxOrder = allCatalogs.size

        // [핵심] 포커스 유지 및 UI 점핑 방지 정렬 로직
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

        // [핵심] 실시간 스택 밀어내기 (Insert & Shift) 알고리즘
        fun shiftItem(row: CatalogRow, newOrder: Int) {
            val clampedOrder = newOrder.coerceIn(1, maxOrder)
            val oldIndex = activeRows.indexOf(row)
            val newIndex = clampedOrder - 1

            if (oldIndex == -1) return
            
            // 번호가 실제로 변경되었을 때만 이동
            if (oldIndex != newIndex) {
                activeRows.removeAt(oldIndex)
                activeRows.add(newIndex, row) // 기존 항목들은 자동으로 뒤로 밀려남
            }

            // 배열 순서대로 텍스트 박스에 1부터 N까지 강제 재할당
            for (i in activeRows.indices) {
                activeRows[i].orderEdit.setText((i + 1).toString())
            }

            triggerSort()
        }

        val preSortedCatalogs = allCatalogs.map {
            val order = prefs.getString("cat_order_${instanceId}_${it.first}_${it.second}", "99")?.toIntOrNull() ?: 99
            Triple(it.first, it.second, order)
        }.sortedBy { it.third }

        // UI 생성
        for (cat in preSortedCatalogs) {
            val pName = cat.first
            val cName = cat.second

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 4, 0, 4)
                }
            }

            val cb = CheckBox(ctx).apply {
                isChecked = prefs.getBoolean("cat_toggle_${instanceId}_${pName}_$cName", false)
                setPadding(0, 0, 0, 0)
            }
            row.addView(cb)

            val etOrder = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                // 엔터키 완료 옵션 지정
                imeOptions = EditorInfo.IME_ACTION_DONE
                layoutParams = LinearLayout.LayoutParams(80, LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(5, 5, 5, 5)
            }
            row.addView(etOrder)

            val etSource = EditText(ctx).apply {
                setText(pName)
                isFocusable = false
                isClickable = false
                isCursorVisible = false
                setTextColor(Color.LTGRAY)
                textSize = 11f
                setPadding(10, 5, 10, 5)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2A2A2A"))
                    setStroke(1, Color.DKGRAY)
                    cornerRadius = 8f
                }
            }
            val sourceParams = etSource.layoutParams as LinearLayout.LayoutParams
            sourceParams.setMargins(10, 0, 10, 0)
            row.addView(etSource)

            val etRename = EditText(ctx).apply {
                hint = cName
                setText(prefs.getString("cat_rename_${instanceId}_${pName}_$cName", cName))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isEnabled = cb.isChecked
                setTextColor(Color.WHITE)
                setHintTextColor(Color.DKGRAY)
                textSize = 13f
                setPadding(5, 5, 5, 5)
            }
            row.addView(etRename)

            val up = TextView(ctx).apply { 
                text = "▲"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(15, 5, 15, 5) 
            }
            val down = TextView(ctx).apply { 
                text = "▼"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(15, 5, 15, 5) 
            }
            
            row.addView(up)
            row.addView(down)

            cb.setOnCheckedChangeListener { _, isChecked -> etRename.isEnabled = isChecked }

            val catalogRow = CatalogRow(pName, cName, "", cb, etOrder, etRename, row)
            activeRows.add(catalogRow)

            // 리스너 연결: 키보드 엔터 버튼 누를 때
            etOrder.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                    etOrder.clearFocus()
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(etOrder.windowToken, 0)
                    true
                } else false
            }

            // 리스너 연결: 텍스트 박스 바깥을 터치하여 포커스를 잃었을 때 (실시간 입력 반영)
            etOrder.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val inputVal = etOrder.text.toString().toIntOrNull() ?: (activeRows.indexOf(catalogRow) + 1)
                    shiftItem(catalogRow, inputVal)
                }
            }

            // 위 화살표 누르면 순위 -1 (위로)
            up.setOnClickListener {
                etOrder.clearFocus()
                val cur = activeRows.indexOf(catalogRow) + 1
                if (cur > 1) {
                    shiftItem(catalogRow, cur - 1)
                }
            }
            // 아래 화살표 누르면 순위 +1 (아래로)
            down.setOnClickListener {
                etOrder.clearFocus()
                val cur = activeRows.indexOf(catalogRow) + 1
                if (cur < maxOrder) {
                    shiftItem(catalogRow, cur + 1)
                }
            }
        }

        // [초기화] UI가 처음 뜰 때 1부터 N까지 빈틈없이 번호를 강제 배정하고 화면에 그립니다.
        for (i in activeRows.indices) {
            activeRows[i].orderEdit.setText((i + 1).toString())
        }
        triggerSort()

        scroll.addView(listLayout)
        mainContainer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(ctx).apply { 
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }
        val applyBtn = Button(ctx).apply { text = "저장 후 닫기"; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val closeBtn = Button(ctx).apply { text = "취소"; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        footer.addView(applyBtn); footer.addView(closeBtn)
        mainContainer.addView(footer)

        val builder = AlertDialog.Builder(ctx)
        val dialog = builder.setView(mainContainer).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        applyBtn.setOnClickListener {
            val editor = prefs.edit()

            // 이미 activeRows는 1부터 N까지 완벽하게 정렬된 상태이므로 단순히 배열 순서대로 저장합니다.
            for ((index, row) in activeRows.withIndex()) {
                val finalOrder = index + 1
                val tKey = "cat_toggle_${instanceId}_${row.providerName}_${row.catalogName}"
                val rKey = "cat_rename_${instanceId}_${row.providerName}_${row.catalogName}"
                val oKey = "cat_order_${instanceId}_${row.providerName}_${row.catalogName}"
                
                editor.putBoolean(tKey, row.checkBox.isChecked)
                editor.putString(rKey, row.renameEdit.text.toString())
                editor.putString(oKey, finalOrder.toString()) // 교정된 순서 저장
                
                val pKey = "ext_toggle_${instanceId}_${row.providerName}"
                if (row.checkBox.isChecked) {
                    editor.putBoolean(pKey, true)
                }
            }
            
            editor.commit()
            dialog.dismiss()
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
