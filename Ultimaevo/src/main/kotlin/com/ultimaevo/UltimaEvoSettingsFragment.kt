// v1.6
package com.ultimaevo

import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.*

class UltimaEvoSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        val allProviders = APIHolder.allProviders.filter { it.hasMainPage && it.name != "Ultima Evo" }

        for (provider in allProviders) {
            val category = PreferenceCategory(context).apply {
                title = provider.name
            }
            screen.addPreference(category)

            val providerSwitchKey = "ext_toggle_${provider.name}"
            val providerSwitch = SwitchPreferenceCompat(context).apply {
                key = providerSwitchKey
                title = "${provider.name} 활성화"
                setDefaultValue(false)
            }
            category.addPreference(providerSwitch)

            provider.mainPage.forEach { catalog ->
                val catalogSwitchKey = "cat_toggle_${provider.name}_${catalog.name}"
                val catalogSwitch = SwitchPreferenceCompat(context).apply {
                    key = catalogSwitchKey
                    title = " └ ${catalog.name} 표시"
                    setDefaultValue(false)
                    dependency = providerSwitchKey
                }
                category.addPreference(catalogSwitch)

                val nameInputKey = "cat_rename_${provider.name}_${catalog.name}"
                val nameInput = EditTextPreference(context).apply {
                    key = nameInputKey
                    title = "    └ 새 이름 설정"
                    dialogTitle = "${catalog.name} 이름 변경"
                    setDefaultValue(catalog.name)
                    dependency = catalogSwitchKey
                    summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                        preference.text
                    }
                }
                category.addPreference(nameInput)

                val orderInputKey = "cat_order_${provider.name}_${catalog.name}"
                val orderInput = EditTextPreference(context).apply {
                    key = orderInputKey
                    title = "    └ 정렬 순서"
                    dialogTitle = "정렬 순서 입력 (숫자)"
                    setDefaultValue("99")
                    dependency = catalogSwitchKey
                    setOnBindEditTextListener { editText ->
                        editText.inputType = InputType.TYPE_CLASS_NUMBER
                    }
                    summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                        preference.text
                    }
                }
                category.addPreference(orderInput)
            }
        }
        preferenceScreen = screen
    }
}
