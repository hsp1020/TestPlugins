package com.ultimaevo

import android.app.AlertDialog
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UltimaEvoSettingsDialog : BottomSheetDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        val scrollView = ScrollView(ctx)
        val mainLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val title = TextView(ctx).apply {
            text = "Ultima Evo 마스터 관리자"
            textSize = 24f
            setPadding(0, 0, 0, 40)
            gravity = Gravity.CENTER
        }
        mainLayout.addView(title)

        // 1. 통합 카탈로그 개수 설정
        val countLayout = LinearLayout(ctx).apply { 
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        countLayout.addView(TextView(ctx).apply { text = "생성할 개수: "; textSize = 16f })
        val instanceCountInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getString("evo_instance_count", "1"))
            layoutParams = LinearLayout.LayoutParams(150, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        countLayout.addView(instanceCountInput)
        mainLayout.addView(countLayout)

        val currentCount = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1

        // 2. 각 인스턴스별 설정 카드
        for (i in 1..currentCount) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 30, 30, 30)
                background = ctx.getDrawable(android.R.drawable.dialog_holo_light_frame) // 간단한 카드 느낌
            }
            
            val nameInput = EditText(ctx).apply {
                hint = "통합 카탈로그 $i 이름"
                setText(prefs.getString("evo_name_$i", "Ultima Evo $i"))
                tag = "evo_name_$i"
            }
            card.addView(nameInput)

            val searchSwitch = Switch(ctx).apply {
                text = "검색 기능 활성화"
                isChecked = prefs.getBoolean("evo_search_enabled_$i", true)
                setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("evo_search_enabled_$i", isChecked).apply() }
            }
            card.addView(searchSwitch)

            val configBtn = Button(ctx).apply {
                text = "내부 카탈로그 구성/정렬 편집 ⚙️"
                setOnClickListener { showEditCatalogsDialog(i) }
            }
            card.addView(configBtn)
            
            mainLayout.addView(card)
        }

        val saveBtn = Button(ctx).apply {
            text = "메인 설정 저장 후 닫기"
            setOnClickListener {
                val editor = prefs.edit()
                editor.putString("evo_instance_count", instanceCountInput.text.toString())
                // 이름 저장 로직
                fun findNames(v: ViewGroup) {
                    for (idx in 0 until v.childCount) {
                        val child = v.getChildAt(idx)
                        if (child is EditText && child.tag?.toString()?.startsWith("evo_name_") == true) {
                            editor.putString(child.tag.toString(), child.text.toString())
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

    private fun showEditCatalogsDialog(instanceId: Int) {
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val builder = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) // 전체화면 팝업
        
        val mainContainer = LinearLayout(ctx).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        
        val headerTitle = TextView(ctx).apply {
            text = "통합 카탈로그 $instanceId 구성 (CineStream Style)"
            textSize = 20f
            setPadding(0, 20, 0, 40)
            gravity = Gravity.CENTER
        }
        mainContainer.addView(headerTitle)

        val scroll = ScrollView(ctx)
        val listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        
        val allProviders = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }

        class CatalogRow(
            val providerName: String, val catalogName: String,
            val checkBox: CheckBox, val orderEdit: EditText, val renameEdit: EditText
        )
        val rows = mutableListOf<CatalogRow>()

        for (provider in allProviders) {
            // 공급자 헤더 + 동기화 버튼
            val pHead = LinearLayout(ctx).apply { 
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 20, 0, 10)
            }
            pHead.addView(TextView(ctx).apply { text = provider.name; textSize = 16f; setTextColor(android.graphics.Color.YELLOW) })
            val sync = Button(ctx).apply { text = "Sync"; layoutParams = LinearLayout.LayoutParams(200, 100) }
            sync.setOnClickListener { /* 동기화 로직 생략(기존과 동일) */ }
            pHead.addView(sync)
            listLayout.addView(pHead)

            val dynamicCats = prefs.getString("dynamic_catalogs_${provider.name}", "")?.split("|||") ?: emptyList()
            val allCats = (provider.mainPage.map { it.name } + dynamicCats).filter { it.isNotBlank() }.distinct()

            for (catName in allCats) {
                // CineStream 스타일 집약 행
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(10, 10, 10, 10)
                }

                // 1. 체크박스
                val cb = CheckBox(ctx).apply {
                    isChecked = prefs.getBoolean("cat_toggle_${instanceId}_${provider.name}_$catName", false)
                }
                row.addView(cb)

                // 2. 순서 숫자 입력창 (CineStream 스타일)
                val etOrder = EditText(ctx).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(prefs.getString("cat_order_${instanceId}_${provider.name}_$catName", "99"))
                    layoutParams = LinearLayout.LayoutParams(100, LinearLayout.LayoutParams.WRAP_CONTENT)
                    gravity = Gravity.CENTER
                }
                row.addView(etOrder)

                // 3. 이름 수정창
                val etRename = EditText(ctx).apply {
                    setText(prefs.getString("cat_rename_${instanceId}_${provider.name}_$catName", catName))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    isEnabled = cb.isChecked
                }
                row.addView(etRename)

                // 4. 화살표 버튼 (집약 배치)
                val arrows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                val up = TextView(ctx).apply { text = "▲"; textSize = 20f; setPadding(10, 0, 10, 0) }
                val down = TextView(ctx).apply { text = "▼"; textSize = 20f; setPadding(10, 0, 10, 0) }
                
                up.setOnClickListener {
                    val cur = etOrder.text.toString().toIntOrNull() ?: 99
                    if (cur > 1) etOrder.setText((cur - 1).toString())
                }
                down.setOnClickListener {
                    val cur = etOrder.text.toString().toIntOrNull() ?: 99
                    etOrder.setText((cur + 1).toString())
                }
                
                arrows.addView(up); arrows.addView(down)
                row.addView(arrows)

                cb.setOnCheckedChangeListener { _, isChecked -> etRename.isEnabled = isChecked }
                
                listLayout.addView(row)
                rows.add(CatalogRow(provider.name, catName, cb, etOrder, etRename))
            }
        }

        scroll.addView(listLayout)
        mainContainer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val applyBtn = Button(ctx).apply { text = "적용"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val closeBtn = Button(ctx).apply { text = "취소"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        footer.addView(applyBtn); footer.addView(closeBtn)
        mainContainer.addView(footer)

        val dialog = builder.setView(mainContainer).create()

        applyBtn.setOnClickListener {
            val editor = prefs.edit()
            val allOrders = mutableListOf<Triple<CatalogRow, Int, Int>>()

            for (row in rows) {
                val tKey = "cat_toggle_${instanceId}_${row.providerName}_${row.catalogName}"
                val rKey = "cat_rename_${instanceId}_${row.providerName}_${row.catalogName}"
                
                editor.putBoolean(tKey, row.checkBox.isChecked)
                editor.putString(rKey, row.renameEdit.text.toString())

                val currentVal = row.orderEdit.text.toString().toIntOrNull() ?: 99
                val originalVal = prefs.getString("cat_order_${instanceId}_${row.providerName}_${row.catalogName}", "99")?.toIntOrNull() ?: 99
                allOrders.add(Triple(row, currentVal, originalVal))
            }

            // 자동 밀어내기 정렬 알고리즘 (Shifting)
            val sorted = allOrders.sortedWith(compareBy({ it.second }, { if (it.second == it.third) 1 else 0 }))
            var last = -1
            for (item in sorted) {
                var target = item.second
                if (target <= last) target = last + 1
                editor.putString("cat_order_${instanceId}_${item.first.providerName}_${item.first.catalogName}", target.toString())
                last = target
            }
            editor.apply()
            dialog.dismiss()
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
