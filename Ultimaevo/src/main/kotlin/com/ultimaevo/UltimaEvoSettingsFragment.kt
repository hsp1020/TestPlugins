// v1.7
package com.ultimaevo

import android.os.Bundle
import android.preference.EditTextPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.preference.SwitchPreference
import android.text.InputType
import com.lagradost.cloudstream3.APIHolder

class UltimaEvoSettingsFragment : PreferenceFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = activity ?: return
        val screen = preferenceManager.createPreferenceScreen(context)
        val allProviders = APIHolder.allProviders.filter { it.hasMainPage && it.name != "Ultima Evo" }

        for (provider in allProviders) {
            val category = PreferenceCategory(context).apply {
                title = provider.name
            }
            screen.addPreference(category)

            val providerSwitchKey = "ext_toggle_${provider.name}"
            val providerSwitch = SwitchPreference(context).apply {
                key = providerSwitchKey
                title = "${provider.name} 활성화"
                setDefaultValue(false)
            }
            category.addPreference(providerSwitch)

            provider.mainPage.forEach { catalog ->
                val catalogSwitchKey = "cat_toggle_${provider.name}_${catalog.name}"
                val catalogSwitch = SwitchPreference(context).apply {
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
                    summary = text ?: catalog.name
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, newValue ->
                        pref.summary = newValue.toString()
                        true
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
                    editText.inputType = InputType.TYPE_CLASS_NUMBER
                    summary = text ?: "99"
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, newValue ->
                        pref.summary = newValue.toString()
                        true
                    }
                }
                category.addPreference(orderInput)
            }
        }
        preferenceScreen = screen
    }
}
