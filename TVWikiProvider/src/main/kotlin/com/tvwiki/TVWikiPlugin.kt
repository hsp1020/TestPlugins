package com.tvwiki

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

@CloudstreamPlugin
class TVWikiPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(TVWiki())
        registerExtractorAPI(BunnyPoorCdn())
    }

    override fun openSettings(context: Context) {
        val prefs = context.getSharedPreferences("TVWiki_Domain_Cache", Context.MODE_PRIVATE)
        val current = prefs.getString("user_domain", null) 
            ?: prefs.getString("current_domain", "https://tvwiki7.net")

        val builder = AlertDialog.Builder(context)
        builder.setTitle("TVWiki 도메인 설정")
        builder.setMessage("수동으로 도메인 주소를 지정하거나 확인합니다.")

        val input = EditText(context)
        input.setText(current)
        builder.setView(input)

        builder.setPositiveButton("저장") { _, _ ->
            var newDomain = input.text.toString().trim().trimEnd('/')
            if (newDomain.isNotEmpty()) {
                if (!newDomain.startsWith("http")) {
                    newDomain = "https://$newDomain"
                }
                prefs.edit().putString("user_domain", newDomain).apply()
                TVWiki.isDomainChecked = false
            }
        }
        builder.setNegativeButton("취소", null)
        builder.show()
    }
}
