package com.hsp1020

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.core.content.edit
import androidx.core.widget.addTextChangedListener

class LanguageSelectFragment(
    private val plugin: StremioXPlugin,
    private val sharedPref: SharedPreferences,
    private val onDismissCallback: (() -> Unit)? = null
) : DialogFragment() {

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val displayMetrics = resources.displayMetrics
            val maxDialogWidth = (500 * displayMetrics.density).toInt()
            val width = if (displayMetrics.widthPixels > 0 && displayMetrics.widthPixels > maxDialogWidth) {
                maxDialogWidth
            } else {
                (displayMetrics.widthPixels * 0.9f).toInt()
            }
            // 태블릿 및 대화면 대응 다이얼로그 높이 최대치 세팅
            val height = (displayMetrics.heightPixels * 0.75f).toInt()
            setLayout(width, height)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun getDarkThemeBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#050505"))
            cornerRadius = 40f
        }
    }

    private fun getButtonBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#151515"))
            cornerRadius = 20f
        }
    }

    private val languages = listOf(
        "South Africa (Afrikaans)" to "af-ZA",
        "United Arab Emirates (Arabic)" to "ar-AE",
        "Saudi Arabia (Arabic)" to "ar-SA",
        "Azerbaijan (Azerbaijani)" to "az-AZ",
        "Bulgaria (Bulgarian)" to "bg-BG",
        "India (Bengali)" to "bn-IN",
        "Spain (Catalan)" to "ca-ES",
        "Czech Republic (Czech)" to "cs-CZ",
        "United Kingdom (Welsh)" to "cy-GB",
        "Denmark (Danish)" to "da-DK",
        "Germany (German)" to "de-DE",
        "Greece (Greek)" to "el-GR",
        "United States (English)" to "en-US",
        "United Kingdom (English)" to "en-GB",
        "Spain (Spanish)" to "es-ES",
        "Latin America (Spanish)" to "es-419",
        "Estonia (Estonian)" to "et-EE",
        "Spain (Basque)" to "eu-ES",
        "Iran (Persian)" to "fa-IR",
        "Finland (Finnish)" to "fi-FI",
        "Philippines (Filipino)" to "fil-PH",
        "France (French)" to "fr-FR",
        "Spain (Galician)" to "gl-ES",
        "India (Gujarati)" to "gu-IN",
        "Israel (Hebrew)" to "he-IL",
        "India (Hindi)" to "hi-IN",
        "Croatia (Croatian)" to "hr-HR",
        "Hungary (Hungarian)" to "hu-HU",
        "Indonesia (Indonesian)" to "id-ID",
        "Iceland (Icelandic)" to "is-IS",
        "Italy (Italian)" to "it-IT",
        "Japan (Japanese)" to "ja-JP",
        "India (Kannada)" to "kn-IN",
        "South Korea (Korean)" to "ko-KR",
        "Lithuania (Lithuanian)" to "lt-LT",
        "Latvia (Latvian)" to "lv-LV",
        "India (Malayalam)" to "ml-IN",
        "Malaysia (Malay)" to "ms-MY",
        "Norway (Norwegian)" to "no-NO",
        "Netherlands (Dutch)" to "nl-NL",
        "Poland (Polish)" to "pl-PL",
        "Brazil (Portuguese)" to "pt-BR",
        "Portugal (Portuguese)" to "pt-PT",
        "Romania (Romanian)" to "ro-RO",
        "Russia (Russian)" to "ru-RU",
        "Slovakia (Slovak)" to "sk-SK",
        "Slovenia (Slovenian)" to "sl-SI",
        "Serbia (Serbian)" to "sr-RS",
        "Sweden (Swedish)" to "sv-SE",
        "India (Tamil)" to "ta-IN",
        "India (Telugu)" to "te-IN",
        "Thailand (Thai)" to "th-TH",
        "Turkey (Turkish)" to "tr-TR",
        "Ukraine (Ukrainian)" to "uk-UA",
        "Vietnam (Vietnamese)" to "vi-VN",
        "China (Chinese Simplified)" to "zh-CN",
        "Taiwan (Chinese Traditional)" to "zh-TW"
    ).sortedBy { it.first.lowercase() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        
        val mainLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = getDarkThemeBg()
        }

        val titleLabel = TextView(ctx).apply {
            text = "Select TMDb Language"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(10, 0, 10, 20)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        mainLayout.addView(titleLabel)

        val searchBar = EditText(ctx).apply {
            hint = "Search language..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(25, 20, 25, 20)
            background = getButtonBg()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 25)
            }
        }
        mainLayout.addView(searchBar)

        val scrollView = androidx.core.widget.NestedScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        
        val listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(listContainer)
        mainLayout.addView(scrollView)

        val savedCode = sharedPref.getString("tmdb_language_code", "ko-KR") ?: "ko-KR"
        val rowViews = mutableListOf<Pair<String, View>>()

        languages.forEach { (name, code) ->
            val rowLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(15, 20, 15, 20)
                isClickable = true
                focusable = View.FOCUSABLE
                
                // 클릭 리플 선택 효과 연동
                val outValue = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
            }

            val tvName = TextView(ctx).apply {
                text = "$name ($code)"
                textSize = 15f
                setTextColor(if (code == savedCode) Color.parseColor("#2196F3") else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            rowLayout.addView(tvName)

            if (code == savedCode) {
                val tvCheck = TextView(ctx).apply {
                    text = "✓"
                    textSize = 16f
                    setTextColor(Color.parseColor("#2196F3"))
                    setPadding(15, 0, 15, 0)
                }
                rowLayout.addView(tvCheck)
            }

            rowLayout.setOnClickListener {
                sharedPref.edit { putString("tmdb_language_code", code) }
                Toast.makeText(ctx, "Language set to $code", Toast.LENGTH_SHORT).show()
                dismiss()
            }

            listContainer.addView(rowLayout)
            rowViews.add(name to rowLayout)
        }

        searchBar.addTextChangedListener { text ->
            val query = text.toString().trim().lowercase()
            rowViews.forEach { (name, view) ->
                view.visibility = if (query.isEmpty() || name.lowercase().contains(query)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
        }

        return mainLayout
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }
}
