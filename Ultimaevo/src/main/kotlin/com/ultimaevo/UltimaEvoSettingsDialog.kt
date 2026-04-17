// v1.11
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
            text = "Ultima Evo 설정"
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

            for (catalog in provider.mainPage) {
                val catSwitchKey = "cat_toggle_${provider.name}_${catalog.name}"
                val nameInputKey = "cat_rename_${provider.name}_${catalog.name}"
                val orderInputKey = "cat_order_${provider.name}_${catalog.name}"

                val catSwitch = Switch(ctx).apply {
                    text = "└ ${catalog.name}"
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
                    hint = "새 이름 (기본: ${catalog.name})"
                    setText(prefs.getString(nameInputKey, catalog.name))
                    tag = nameInputKey
                }
                detailsContainer.addView(nameInput)

                val orderInput = EditText(ctx).apply {
                    hint = "순서 (기본: 99)"
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
