// v1.19
package com.ultimaevo

import android.os.Bundle
import android.preference.PreferenceManager
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
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
        }
        mainLayout.addView(title)

        val instanceCount = prefs.getInt("evo_instance_count", 3)

        for (i in 1..instanceCount) {
            val insCategory = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 30, 0, 30)
            }
            mainLayout.addView(insCategory)

            val insNameInput = EditText(ctx).apply {
                hint = "통합 카탈로그 $i 이름"
                setText(prefs.getString("evo_name_$i", "Ultima Evo $i"))
                tag = "evo_name_$i"
            }
            insCategory.addView(insNameInput)

            val searchToggle = Switch(ctx).apply {
                text = "이 카탈로그에서 검색 기능 활성화"
                isChecked = prefs.getBoolean("evo_search_enabled_$i", true)
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean("evo_search_enabled_$i", isChecked).apply()
                }
            }
            insCategory.addView(searchToggle)

            val expandBtn = Button(ctx).apply { text = "내부 카탈로그 구성 편집 ▼" }
            insCategory.addView(expandBtn)

            val providerContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 0, 0, 0)
                visibility = View.GONE
            }
            insCategory.addView(providerContainer)

            expandBtn.setOnClickListener {
                providerContainer.visibility = if (providerContainer.visibility == View.GONE) View.VISIBLE else View.GONE
            }

            val allProviders = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }

            for (provider in allProviders) {
                val provKey = "ext_toggle_${i}_${provider.name}"
                val provSwitch = Switch(ctx).apply {
                    text = "${provider.name} 포함"
                    isChecked = prefs.getBoolean(provKey, false)
                }
                providerContainer.addView(provSwitch)

                val catContainer = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(40, 0, 0, 20)
                    visibility = if (provSwitch.isChecked) View.VISIBLE else View.GONE
                }
                providerContainer.addView(catContainer)

                provSwitch.setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean(provKey, isChecked).apply()
                    catContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
                }

                val syncBtn = Button(ctx).apply { text = "🔄 ${provider.name} 카탈로그 동기화" }
                catContainer.addView(syncBtn)
                syncBtn.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        val reqName = provider.mainPage.firstOrNull()?.name ?: "Home"
                        val reqData = provider.mainPage.firstOrNull()?.data ?: ""
                        val request = MainPageRequest(reqName, reqData, false)
                        val response = provider.getMainPage(1, request)
                        if (response != null && response.items.isNotEmpty()) {
                            val names = response.items.map { it.name }
                            prefs.edit().putString("dynamic_catalogs_${provider.name}", names.joinToString("|||")).apply()
                        }
                    }
                }

                val staticCats = provider.mainPage.map { it.name to it.data }
                val cachedDynStr = prefs.getString("dynamic_catalogs_${provider.name}", "") ?: ""
                val dynamicCats = if (cachedDynStr.isNotBlank()) cachedDynStr.split("|||").map { it to it } else emptyList()
                val allCats = (staticCats + dynamicCats).distinctBy { it.first }

                for (cat in allCats) {
                    val cName = cat.first
                    val toggleKey = "cat_toggle_${i}_${provider.name}_$cName"
                    val renameKey = "cat_rename_${i}_${provider.name}_$cName"
                    val orderKey = "cat_order_${i}_${provider.name}_$cName"

                    val cSwitch = Switch(ctx).apply {
                        text = "└ $cName 표시"
                        isChecked = prefs.getBoolean(toggleKey, false)
                    }
                    catContainer.addView(cSwitch)

                    val editLayout = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(40, 0, 0, 10)
                        visibility = if (cSwitch.isChecked) View.VISIBLE else View.GONE
                    }
                    catContainer.addView(editLayout)

                    cSwitch.setOnCheckedChangeListener { _, isChecked ->
                        prefs.edit().putBoolean(toggleKey, isChecked).apply()
                        editLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
                    }

                    val rInput = EditText(ctx).apply {
                        hint = "표시될 이름"
                        setText(prefs.getString(renameKey, cName))
                        tag = renameKey
                    }
                    editLayout.addView(rInput)

                    val oInput = EditText(ctx).apply {
                        hint = "정렬 순서"
                        inputType = InputType.TYPE_CLASS_NUMBER
                        setText(prefs.getString(orderKey, "99"))
                        tag = "ORDER_INPUT|${i}|${provider.name}|${cName}" 
                    }
                    editLayout.addView(oInput)
                }
            }
        }

        val saveBtn = Button(ctx).apply {
            text = "모든 설정 저장 및 닫기 (앱 재시작 권장)"
            setOnClickListener {
                applyShiftingAndSave(mainLayout, prefs)
                dismiss()
            }
        }
        mainLayout.addView(saveBtn)

        scrollView.addView(mainLayout)
        return scrollView
    }

    private fun applyShiftingAndSave(root: ViewGroup, prefs: android.content.SharedPreferences) {
        val editor = prefs.edit()
        val allOrderInputs = mutableListOf<Triple<String, Int, EditText>>()

        fun collect(viewGroup: ViewGroup) {
            for (i in 0 until viewGroup.childCount) {
                val v = viewGroup.getChildAt(i)
                if (v is EditText) {
                    val tagStr = v.tag as? String ?: ""
                    if (tagStr.startsWith("ORDER_INPUT")) {
                        val currentVal = v.text.toString().toIntOrNull() ?: 99
                        allOrderInputs.add(Triple(tagStr, currentVal, v))
                    } else if (tagStr.isNotEmpty()) {
                        editor.putString(tagStr, v.text.toString())
                    }
                } else if (v is ViewGroup) collect(v)
            }
        }
        collect(root)

        for (insId in 1..10) {
            val currentInsOrders = allOrderInputs.filter { it.first.split("|")[1] == insId.toString() }
            if (currentInsOrders.isEmpty()) continue

            val sortedList = currentInsOrders.sortedBy { it.second }
            var lastIdx = -1

            for (item in sortedList) {
                var targetIdx = item.second
                if (targetIdx <= lastIdx) {
                    targetIdx = lastIdx + 1
                }
                val tagParts = item.first.split("|")
                val key = "cat_order_${tagParts[1]}_${tagParts[2]}_${tagParts[3]}"
                editor.putString(key, targetIdx.toString())
                lastIdx = targetIdx
            }
        }
        editor.apply()
    }
}
