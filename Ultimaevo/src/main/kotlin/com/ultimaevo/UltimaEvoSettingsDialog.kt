// v1.32
package com.ultimaevo

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
        println("[UltimaEvo-Debug] [Dialog] onViewCreated 호출됨")
        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        println("[UltimaEvo-Debug] [Dialog] onCreateView 호출됨")
        val ctx = UltimaEvoPlugin.pluginContext ?: requireContext()
        println("[UltimaEvo-Debug] [Dialog] 사용될 Context (pluginContext가 null인지 확인): $ctx")
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        val scrollView = ScrollView(ctx).apply { background = getDarkThemeBg() }
        val mainLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        val title = TextView(ctx).apply {
            text = "Ultima Evo 마스터 관리자"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
            gravity = Gravity.CENTER
        }
        mainLayout.addView(title)

        val syncAllBtn = Button(ctx).apply {
            text = "🔄 모든 카탈로그 데이터 최초 동기화"
            setTextColor(Color.WHITE)
            setPadding(10, 0, 10, 0)
            setOnClickListener {
                println("[UltimaEvo-Debug] [Dialog] 전체 동기화 버튼 클릭됨")
                this.text = "통신 중..."
                this.isEnabled = false
                CoroutineScope(Dispatchers.IO).launch {
                    val providers = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
                    println("[UltimaEvo-Debug] [Dialog] 동기화할 타겟 수: ${providers.size}")
                    for (provider in providers) {
                        try {
                            val reqName = provider.mainPage.firstOrNull()?.name ?: "Home"
                            val reqData = provider.mainPage.firstOrNull()?.data ?: ""
                            val response = provider.getMainPage(1, MainPageRequest(reqName, reqData, false))
                            if (response != null && response.items.isNotEmpty()) {
                                val names = response.items.map { it.name }
                                println("[UltimaEvo-Debug] [Dialog] 동기화 파싱 성공 -> ${provider.name}: $names")
                                prefs.edit().putString("dynamic_catalogs_${provider.name}", names.joinToString("|||")).apply()
                            }
                        } catch (e: Exception) { 
                            println("[UltimaEvo-Debug] [Dialog] 동기화 예외 -> ${provider.name}: ${e.message}")
                        }
                    }
                    withContext(Dispatchers.Main) {
                        println("[UltimaEvo-Debug] [Dialog] 동기화 프로세스 전체 종료")
                        this@apply.text = "✅ 동기화 완료"
                        this@apply.isEnabled = true
                    }
                }
            }
        }
        mainLayout.addView(syncAllBtn)

        val countLayout = LinearLayout(ctx).apply { 
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 15, 0, 15)
        }
        countLayout.addView(TextView(ctx).apply { text = "생성할 개수: "; textSize = 14f; setTextColor(Color.WHITE) })
        val instanceCountInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            val initCount = prefs.getString("evo_instance_count", "1")
            setText(initCount)
            println("[UltimaEvo-Debug] [Dialog] 화면에 찍힌 초기 생성 개수: $initCount")
            setTextColor(Color.WHITE)
            setPadding(10, 10, 10, 10)
            layoutParams = LinearLayout.LayoutParams(120, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        countLayout.addView(instanceCountInput)
        mainLayout.addView(countLayout)

        val currentCount = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1

        for (i in 1..currentCount) {
            val card = LinearLayout(ctx).apply {
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
            val nameInput = EditText(ctx).apply {
                hint = "통합 카탈로그 $i 이름"
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(10, 10, 10, 10)
                setText(savedName)
                tag = "evo_name_$i"
            }
            card.addView(nameInput)

            val searchSwitch = Switch(ctx).apply {
                text = "이 카탈로그 검색 허용"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 10, 0, 10)
                isChecked = prefs.getBoolean("evo_search_enabled_$i", true)
                setOnCheckedChangeListener { _, isChecked -> 
                    println("[UltimaEvo-Debug] [Dialog] 검색 스위치 변경 $i -> $isChecked")
                    prefs.edit().putBoolean("evo_search_enabled_$i", isChecked).apply() 
                }
            }
            card.addView(searchSwitch)

            val configBtn = Button(ctx).apply {
                text = "내부 카탈로그 구성/정렬 편집 ⚙️"
                setTextColor(Color.WHITE)
                setPadding(10, 0, 10, 0)
                setOnClickListener { 
                    println("[UltimaEvo-Debug] [Dialog] 편집 팝업버튼 클릭됨 인스턴스 $i")
                    showEditCatalogsDialog(i, ctx) 
                }
            }
            card.addView(configBtn)
            
            mainLayout.addView(card)
        }

        val saveBtn = Button(ctx).apply {
            text = "메인 설정 저장 후 닫기"
            setTextColor(Color.WHITE)
            setPadding(10, 10, 10, 10)
            setOnClickListener {
                println("[UltimaEvo-Debug] [Dialog] 메인 저장 버튼 클릭")
                val editor = prefs.edit()
                editor.putString("evo_instance_count", instanceCountInput.text.toString())
                println("[UltimaEvo-Debug] [Dialog] 저장됨: evo_instance_count -> ${instanceCountInput.text}")
                
                fun findNames(v: ViewGroup) {
                    for (idx in 0 until v.childCount) {
                        val child = v.getChildAt(idx)
                        if (child is EditText && child.tag?.toString()?.startsWith("evo_name_") == true) {
                            editor.putString(child.tag.toString(), child.text.toString())
                            println("[UltimaEvo-Debug] [Dialog] 저장됨: ${child.tag} -> ${child.text}")
                        } else if (child is ViewGroup) findNames(child)
                    }
                }
                findNames(mainLayout)
                editor.apply()
                dismiss()
            }
        }
        mainLayout.addView(saveBtn)

        scrollView.addView(mainLayout)
        return scrollView
    }

    private fun showEditCatalogsDialog(instanceId: Int, ctx: android.content.Context) {
        println("[UltimaEvo-Debug] [Popup] 팝업 생성 시작: 인스턴스 $instanceId")
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        
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

        val warningText = TextView(ctx).apply {
            text = "※ 맨 아래 '적용' 버튼을 누르면 정렬 번호가 자동으로 재정리됩니다."
            textSize = 12f
            setTextColor(Color.YELLOW)
            setPadding(0, 0, 0, 20)
            gravity = Gravity.CENTER
        }
        mainContainer.addView(warningText)

        val scroll = ScrollView(ctx)
        val listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        
        class CatalogRow(
            val providerName: String, val catalogName: String,
            val checkBox: CheckBox, val orderEdit: EditText, val renameEdit: EditText, val view: View
        )
        val rows = mutableListOf<CatalogRow>()

        val allProviders = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
        val allCatalogs = mutableListOf<Pair<String, String>>() 

        for (provider in allProviders) {
            val staticCats = provider.mainPage.map { it.name }
            val dynamicCats = prefs.getString("dynamic_catalogs_${provider.name}", "")?.split("|||") ?: emptyList()
            val combined = (staticCats + dynamicCats).filter { it.isNotBlank() }.distinct()
            combined.forEach { allCatalogs.add(provider.name to it) }
        }
        println("[UltimaEvo-Debug] [Popup] 발견된 전체 세부 카탈로그 수: ${allCatalogs.size}")

        fun triggerSort() {
            val focusedView = listLayout.findFocus() as? EditText
            val selStart = focusedView?.selectionStart ?: 0
            val selEnd = focusedView?.selectionEnd ?: 0

            val sorted = rows.sortedWith(compareBy({ it.orderEdit.text.toString().toIntOrNull() ?: 99 }, { it.catalogName }))
            
            for (i in sorted.indices) {
                val rowView = sorted[i].view
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
                setText(prefs.getString("cat_order_${instanceId}_${pName}_$cName", "99"))
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

            val catalogRow = CatalogRow(pName, cName, cb, etOrder, etRename, row)
            rows.add(catalogRow)

            up.setOnClickListener {
                val cur = etOrder.text.toString().toIntOrNull() ?: 99
                if (cur > 1) {
                    etOrder.setText((cur - 1).toString())
                    triggerSort()
                }
            }
            down.setOnClickListener {
                val cur = etOrder.text.toString().toIntOrNull() ?: 99
                etOrder.setText((cur + 1).toString())
                triggerSort()
            }
            
            etOrder.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) triggerSort()
            }
            
            listLayout.addView(row)
        }

        scroll.addView(listLayout)
        mainContainer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(ctx).apply { 
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }
        val applyBtn = Button(ctx).apply { text = "적용"; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val closeBtn = Button(ctx).apply { text = "취소"; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        footer.addView(applyBtn); footer.addView(closeBtn)
        mainContainer.addView(footer)

        val builder = AlertDialog.Builder(ctx)
        val dialog = builder.setView(mainContainer).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        applyBtn.setOnClickListener {
            println("[UltimaEvo-Debug] [Popup] 팝업창 최종 저장(적용) 버튼 클릭됨")
            val editor = prefs.edit()
            val allOrders = mutableListOf<Triple<CatalogRow, Int, Int>>()

            for (row in rows) {
                val tKey = "cat_toggle_${instanceId}_${row.providerName}_${row.catalogName}"
                val rKey = "cat_rename_${instanceId}_${row.providerName}_${row.catalogName}"
                
                editor.putBoolean(tKey, row.checkBox.isChecked)
                editor.putString(rKey, row.renameEdit.text.toString())
                
                // 해당 카탈로그가 포함된 Provider 자체도 활성화
                val pKey = "ext_toggle_${instanceId}_${row.providerName}"
                if (row.checkBox.isChecked) {
                    editor.putBoolean(pKey, true)
                }

                val currentVal = row.orderEdit.text.toString().toIntOrNull() ?: 99
                val originalVal = prefs.getString("cat_order_${instanceId}_${row.providerName}_${row.catalogName}", "99")?.toIntOrNull() ?: 99
                allOrders.add(Triple(row, currentVal, originalVal))
            }

            val sorted = allOrders.sortedWith(compareBy({ it.second }, { if (it.second == it.third) 1 else 0 }))
            var last = -1
            for (item in sorted) {
                var target = item.second
                if (target <= last) target = last + 1
                val key = "cat_order_${instanceId}_${item.first.providerName}_${item.first.catalogName}"
                editor.putString(key, target.toString())
                println("[UltimaEvo-Debug] [Popup] 저장된 정렬값: $key -> $target")
                last = target
            }
            editor.apply()
            println("[UltimaEvo-Debug] [Popup] 팝업창 데이터 저장 완료 후 닫힘")
            dialog.dismiss()
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
        println("[UltimaEvo-Debug] [Popup] 팝업창 show() 완료")
    }
}
