// v1.17
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
            text = "Ultima Evo 고급 설정"
            textSize = 22f
            setPadding(0, 0, 0, 40)
        }
        mainLayout.addView(title)

        val allProviders = APIHolder.allProviders.filter { it.hasMainPage && it.name != "Ultima Evo" }

        for (provider in allProviders) {
            val provSwitchKey = "ext_toggle_${provider.name}"
            val provSwitch = Switch(ctx).apply {
                text = "${provider.name} 활성화"
                textSize = 18f
                setPadding(0, 20, 0, 10)
                isChecked = prefs.getBoolean(provSwitchKey, false)
            }
            mainLayout.addView(provSwitch)

            val catalogContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 0, 0, 20)
                visibility = if (provSwitch.isChecked) View.VISIBLE else View.GONE
            }
            mainLayout.addView(catalogContainer)

            provSwitch.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(provSwitchKey, isChecked).apply()
                catalogContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            }

            val staticCats = provider.mainPage.map { it.name to it.data }
            val cachedDynStr = prefs.getString("dynamic_catalogs_${provider.name}", "") ?: ""
            val dynamicCats = if (cachedDynStr.isNotBlank()) cachedDynStr.split("|||").map { it to it } else emptyList()
            val allCatalogs = (staticCats + dynamicCats).distinctBy { it.first }

            // [수정됨] 조건문 삭제: 무조건 동기화 버튼을 최상단에 배치합니다.
            val fetchBtn = Button(ctx).apply {
                text = "🔄 ${provider.name} 카탈로그 강제 동기화"
                setOnClickListener {
                    this.text = "통신 중... 잠시만 기다려주세요"
                    this.isEnabled = false
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // 안전한 통신을 위해 해당 Provider의 첫 번째 데이터를 추출하여 보냅니다.
                            val reqName = provider.mainPage.firstOrNull()?.name ?: "Home"
                            val reqData = provider.mainPage.firstOrNull()?.data ?: ""
                            val request = MainPageRequest(reqName, reqData, false)
                            
                            val response = provider.getMainPage(1, request)
                            if (response != null && response.items.isNotEmpty()) {
                                val names = response.items.map { it.name }
                                prefs.edit().putString("dynamic_catalogs_${provider.name}", names.joinToString("|||")).apply()
                                withContext(Dispatchers.Main) {
                                    this@apply.text = "✅ 완료! (설정 창을 닫았다 다시 열어주세요)"
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    this@apply.text = "❌ 통신 실패 (파싱된 데이터 없음)"
                                    this@apply.isEnabled = true
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                this@apply.text = "❌ 네트워크 에러 발생"
                                this@apply.isEnabled = true
                            }
                        }
                    }
                }
            }
            catalogContainer.addView(fetchBtn)

            // 기존에 저장된(또는 방금 불러온) 카탈로그 목록들을 스위치로 생성
            for (cat in allCatalogs) {
                val catalogName = cat.first
                
                val catSwitchKey = "cat_toggle_${provider.name}_${catalogName}"
                val nameInputKey = "cat_rename_${provider.name}_${catalogName}"
                val orderInputKey = "cat_order_${provider.name}_${catalogName}"

                val catSwitch = Switch(ctx).apply {
                    text = "└ $catalogName"
                    setPadding(0, 20, 0, 10)
                    isChecked = prefs.getBoolean(catSwitchKey, false)
                }
                catalogContainer.addView(catSwitch)

                val detailsContainer = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(50, 0, 0, 20)
                    visibility = if (catSwitch.isChecked) View.VISIBLE else View.GONE
                }
                catalogContainer.addView(detailsContainer)

                catSwitch.setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean(catSwitchKey, isChecked).apply()
                    detailsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
                }

                val nameInput = EditText(ctx).apply {
                    hint = "새 이름 (기본: $catalogName)"
                    setText(prefs.getString(nameInputKey, catalogName))
                    tag = nameInputKey
                }
                detailsContainer.addView(nameInput)

                val orderInput = EditText(ctx).apply {
                    hint = "정렬 순서 (기본: 99)"
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(prefs.getString(orderInputKey, "99"))
                    tag = orderInputKey
                }
                detailsContainer.addView(orderInput)
            }
        }

        val saveBtn = Button(ctx).apply {
            text = "저장 및 닫기"
            setOnClickListener {
                fun saveEditTexts(viewGroup: ViewGroup) {
                    for (i in 0 until viewGroup.childCount) {
                        val child = viewGroup.getChildAt(i)
                        if (child is EditText) {
                            val key = child.tag as? String
                            if (key != null) {
                                prefs.edit().putString(key, child.text.toString()).apply()
                            }
                        } else if (child is ViewGroup) {
                            saveEditTexts(child)
                        }
                    }
                }
                saveEditTexts(mainLayout)
                dismiss()
            }
        }
        mainLayout.addView(saveBtn)

        scrollView.addView(mainLayout)
        return scrollView
    }
}
