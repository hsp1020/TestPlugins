// hsp1020/settings/SettingsBottomFragment.kt
package com.hsp1020

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.InputType
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.CheckBox
import android.widget.Switch
import android.widget.LinearLayout
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Base64
import android.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.showToast
import org.json.JSONArray
import org.json.JSONObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

data class LinkItem(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val link: String,
    val type: String
)

object SettingsRepository {
    private const val PREF_KEY_LINKS = "stremio_saved_links"
    const val PREF_KEY_CINESTREAM_ENABLED = "cinestream_integration_enabled"
    const val PREF_KEY_STREAMPLAY_ENABLED = "streamplay_integration_enabled"
    const val PREF_KEY_VIXSRC_ENABLED = "vixsrc_integration_enabled"
    const val PREF_KEY_HISTORY_SYNC_ENABLED = "history_sync_enabled"
    const val PREF_KEY_HISTORY_SYNC_URL = "history_sync_url"
    
    const val PREF_KEY_TMDB_LANG_CODE = "tmdb_language_code"
    const val PREF_KEY_CUSTOM_POSTER_ENABLED = "custom_poster_enabled"
    const val PREF_KEY_CUSTOM_POSTER_URL = "custom_poster_url"

    const val PREF_KEY_TMDB_API_KEY = "tmdb_api_key"
    const val PREF_KEY_MDBLIST_ENABLED = "mdblist_enabled"
    const val PREF_KEY_MDBLIST_API_KEY = "mdblist_api_key"

    fun loadLinks(sharedPref: SharedPreferences): MutableList<LinkItem> {
        val json = sharedPref.getString(PREF_KEY_LINKS, null) ?: return mutableListOf()
        val list = mutableListOf<LinkItem>()
        return try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    LinkItem(
                        id = obj.optLong("id", System.currentTimeMillis()),
                        name = obj.optString("name", ""),
                        link = obj.optString("link", ""),
                        type = obj.optString("type", "StremioC")
                    )
                )
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    fun saveLinks(sharedPref: SharedPreferences, list: List<LinkItem>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("link", item.link)
                put("type", item.type)
            }
            arr.put(obj)
        }
        sharedPref.edit { putString(PREF_KEY_LINKS, arr.toString()) }
    }

    fun isIntegrationEnabled(sharedPref: SharedPreferences, key: String, default: Boolean = true): Boolean {
        return sharedPref.getBoolean(key, default)
    }

    fun setIntegrationEnabled(sharedPref: SharedPreferences, key: String, state: Boolean) {
        sharedPref.edit { putBoolean(key, state) }
    }
}

class SettingsBottomFragment(
    private val plugin: StremioCPlugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")
    
    private val resourceCache = mutableMapOf<String, Int>()
    private fun getCachedResId(name: String, type: String): Int {
        return resourceCache.getOrPut("$type/$name") {
            res.getIdentifier(name, type, BuildConfig.LIBRARY_PACKAGE_NAME)
        }
    }

    private data class ConfigFlatTab(
        val key: String, 
        val displayName: String, 
        val isGenre: Boolean, 
        val catalogKey: String,
        var isChecked: Boolean = true,
        var isGenresEnabled: Boolean = true
    )

    private var activeSubDialog: AlertDialog? = null 
    private var searchJob: Job? = null 

    private fun getDarkThemeBg() = GradientDrawable().apply { setColor(Color.parseColor("#000000")); cornerRadius = 25f }
    private fun getCardBg() = GradientDrawable().apply { setColor(Color.parseColor("#0C0C0C")); setStroke(2, Color.parseColor("#1A1A1A")); cornerRadius = 18f }
    private fun getButtonBg() = GradientDrawable().apply { setColor(Color.parseColor("#151515")); cornerRadius = 12f }

    private fun getCineStreamClassLoader(): ClassLoader? {
        try {
            val pmClass = Class.forName("com.lagradost.cloudstream3.plugins.PluginManager")
            val pmInstance = pmClass.getField("INSTANCE").get(null)
            for (field in pmClass.declaredFields) {
                field.isAccessible = true
                val obj = runCatching { field.get(pmInstance) }.getOrNull()
                val items = when (obj) {
                    is Map<*, *> -> obj.values
                    is java.util.Collection<*> -> obj
                    is Array<*> -> obj.toList()
                    else -> null
                }
                if (items != null) {
                    for (plugin in items) {
                        val pName = plugin?.javaClass?.name ?: ""
                        if (pName.contains("megix", true) || pName.contains("CineStream", true)) {
                            return plugin!!.javaClass.classLoader
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    private fun getCineStreamDynamicKeys(): Set<String> {
        val keys = mutableSetOf<String>()
        println("[CineData] Dynamic Scanner Started")
        
        try {
            val loader = getCineStreamClassLoader()
            if (loader != null) {
                val settingsClass = Class.forName("com.megix.settings.Settings", true, loader)
                settingsClass.declaredFields.forEach { field ->
                    if (field.type == String::class.java) {
                        field.isAccessible = true
                        val keyName = field.get(null) as? String
                        if (!keyName.isNullOrBlank()) {
                            keys.add(keyName)
                        }
                    }
                }
            }
        } catch (e: Exception) { 
            println("[CineData] Reflection Error: ${e.message}")
        }

        try {
            val seenProviders = CloudStreamApp.getKey<String>("seen_providers")
            seenProviders?.split(",")?.forEach {
                if (it.isNotBlank()) keys.add(it.trim())
            }
            val providerOrder = CloudStreamApp.getKey<String>("provider_order")
            providerOrder?.split(",")?.forEach {
                if (it.isNotBlank()) keys.add(it.trim())
            }
        } catch (e: Exception) { 
            println("[CineData] State Scanner Error: ${e.message}")
        }

        try {
            val addons = CloudStreamApp.getKey<String>("stremio_addons")
            addons?.lines()?.forEach { line ->
                val name = line.split("|").firstOrNull()?.trim()
                if (!name.isNullOrBlank()) {
                    val formatted = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    keys.add("stremio_$formatted")
                }
            }
        } catch (e: Exception) { 
            println("[CineData] Addon Scanner Error: ${e.message}")
        }

        val hardcoded = listOf(
            "ScrapeConcurrency", "DownloadEnable", "ProviderCineStream", "ProviderSimkl", 
            "ProviderTmdb", "showbox_ui_token", "wyzie_subs_api_key", "gramcinema_bearer_token", 
            "stremio_addons", "new_provider_default_on", "seen_providers", "provider_order"
        )
        keys.addAll(hardcoded)
        
        return keys
    }

    private fun prefsToJson(prefs: SharedPreferences, filterBlock: ((String) -> Boolean)? = null): JSONObject {
        val json = JSONObject()
        prefs.all.forEach { (key, value) ->
            if (value != null && (filterBlock == null || filterBlock(key))) {
                if (value is Set<*>) {
                    val arr = JSONArray()
                    value.forEach { item -> if (item != null) arr.put(item) }
                    json.put(key, arr)
                } else {
                    json.put(key, value)
                }
            }
        }
        return json
    }

    private fun jsonToPrefs(json: JSONObject, editor: SharedPreferences.Editor) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = json.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is JSONArray -> {
                    val set = mutableSetOf<String>()
                    for (i in 0 until value.length()) {
                        val item = value.optString(i)
                        if (item != null) set.add(item)
                    }
                    editor.putStringSet(key, set)
                }
            }
        }
    }

    private fun setupCustomDialog(dialog: AlertDialog, contentView: View) {
        activeSubDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        activeSubDialog?.dismiss()
        activeSubDialog = null
        super.onDestroyView()
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
        "Lublin (Polish)" to "pl-PL",
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

    private fun getDrawable(name: String): Drawable {
        val id = getCachedResId(name, "drawable")
        return res.getDrawable(id, null) ?: throw Exception("Drawable $name not found")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = getCachedResId(name, "id")
        if (id == 0) throw Exception("View ID $name not found.")
        return this.findViewById(id)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun View.makeTvCompatible() {
        val outlineId = getCachedResId("outline", "drawable")
        this.background = res.getDrawable(outlineId, null)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = getCachedResId(name, "layout")
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun isCineStreamInstalled(): Boolean {
        return APIHolder.allProviders.any { it.name.equals("CineStream", true) } ||
               StremioC.loadProviderDynamically("megix", "com.megix.CineStreamProvider") != null
    }

    private fun isStreamPlayInstalled(): Boolean {
        return APIHolder.allProviders.any { it.name.equals("StreamPlay", true) } ||
               StremioC.loadProviderDynamically("phisher", "com.phisher98.StreamPlay") != null
    }

    private fun showSavedPopup(ctx: Context, title: String, message: String, onConfirm: () -> Unit = {}) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = getDarkThemeBg()
            addView(TextView(ctx).apply {
                text = title
                textSize = 18f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 20)
                gravity = android.view.Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = message
                setTextColor(Color.LTGRAY)
                textSize = 14f
                setPadding(0, 0, 0, 30)
            })
        }
        val okBtn = Button(ctx).apply {
            text = "OK"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        container.addView(okBtn)

        val dlg = AlertDialog.Builder(ctx).setView(container).create()
        setupCustomDialog(dlg, container)
        okBtn.setOnClickListener {
            dlg.dismiss()
            onConfirm()
        }
        dlg.show()
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        isCancelable = true
        dialog?.setCanceledOnTouchOutside(false)
        
        val ctx = requireContext()
        val view = getLayout("bottom_sheet_layout", inflater, container)

        val addlinks: ImageView = try { view.findView("addlinks") } catch (e: Throwable) { throw Exception("addlinks ImageView not found", e) }
        val showlinks: ImageView = try { view.findView("showlinks") } catch (e: Throwable) { throw Exception("showlinks ImageView not found", e) }
        val saveIcon: ImageView = try { view.findView("saveIcon") } catch (e: Throwable) { throw Exception("saveIcon ImageView not found", e) }

        addlinks.setImageDrawable(getDrawable("settings_icon"))
        showlinks.setImageDrawable(getDrawable("settings_icon"))
        saveIcon.setImageDrawable(getDrawable("save_icon"))

        addlinks.makeTvCompatible()
        showlinks.makeTvCompatible()
        saveIcon.makeTvCompatible()

        val tvExport: TextView = try { view.findView("tvExport") } catch (e: Throwable) { throw Exception("tvExport TextView not found", e) }
        val tvImport: TextView = try { view.findView("tvImport") } catch (e: Throwable) { throw Exception("tvImport TextView not found", e) }

        tvExport.makeTvCompatible()
        tvImport.makeTvCompatible()

        tvExport.setOnClickListener {
            val isStreamPlayExportActive = try { view.findView<Switch>("switchStreamPlay").isChecked } catch (_: Throwable) { false }
            val isCineStreamExportActive = try { view.findView<Switch>("switchCineStream").isChecked } catch (_: Throwable) { false }

            println("[CineData] EXPORT TRIGGERED. CineStream Active: $isCineStreamExportActive")

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val backupStr = withContext(Dispatchers.IO) {
                        val json = JSONObject()
                        
                        // 1. StremioC 데이터 인코딩
                        json.put("stremio_c_data", prefsToJson(sharedPref))
                        json.put("plugin_name", "StremioC_Bundle")
                        
                        // 2. StreamPlay 데이터 인코딩
                        json.put("stream_play_enabled", isStreamPlayExportActive)
                        if (isStreamPlayExportActive) {
                            val streamPlayPrefs = ctx.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
                            json.put("stream_play_data", prefsToJson(streamPlayPrefs))
                        }

                        // 3. CineStream 데이터 인코딩 (타입 오염 방지를 위한 정밀 추출)
                        json.put("cine_stream_enabled", isCineStreamExportActive)
                        if (isCineStreamExportActive) {
                            val cineStreamJson = JSONObject()
                            val dynamicKeys = getCineStreamDynamicKeys()
                            
                            dynamicKeys.forEach { cKey ->
                                try {
                                    // 🚨 타입 보존 로직: DataStore에서 각 키의 명확한 타입으로 핀포인트 추출
                                    when {
                                        cKey == "ScrapeConcurrency" -> {
                                            val intVal = CloudStreamApp.getKey<Int>(cKey)
                                            if (intVal != null) cineStreamJson.put(cKey, intVal)
                                        }
                                        cKey in listOf("showbox_ui_token", "wyzie_subs_api_key", "gramcinema_bearer_token", "stremio_addons", "seen_providers", "provider_order") -> {
                                            val strVal = CloudStreamApp.getKey<String>(cKey)
                                            if (strVal != null) cineStreamJson.put(cKey, strVal)
                                        }
                                        else -> {
                                            // 나머지 모든 동적 키(p_*, stremio_*, Provider*, DownloadEnable 등)는 Boolean 임
                                            val boolVal = CloudStreamApp.getKey<Boolean>(cKey)
                                            if (boolVal != null) cineStreamJson.put(cKey, boolVal)
                                        }
                                    }
                                } catch (e: Throwable) {
                                    println("[CineData] EXPORT - Error reading key: $cKey => ${e.message}")
                                }
                            }
                            println("[CineData] EXPORT - Final CineStream JSON: ${cineStreamJson.toString()}")
                            json.put("cine_stream_data", cineStreamJson)
                        }

                        val jsonString = json.toString()
                        val bos = java.io.ByteArrayOutputStream()
                        java.util.zip.GZIPOutputStream(bos).use { it.write(jsonString.toByteArray(Charsets.UTF_8)) }
                        Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                    }

                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("StremioC Backup Bundle", backupStr)
                    clipboard.setPrimaryClip(clip)

                    val container = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(40, 40, 40, 40)
                        background = getDarkThemeBg()
                        addView(TextView(ctx).apply {
                            text = "Export Successful"
                            textSize = 18f
                            setTextColor(Color.WHITE)
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setPadding(0, 0, 0, 20)
                            gravity = android.view.Gravity.CENTER
                        })
                        val descBuilder = StringBuilder("StremioC settings copied successfully.")
                        if (isStreamPlayExportActive) descBuilder.append("\n+ StreamPlay settings included.")
                        if (isCineStreamExportActive) descBuilder.append("\n+ CineStream settings included (via Smart Scanner).")
                        descBuilder.append("\n\nPlease paste it somewhere safe (e.g., Notes).")

                        addView(TextView(ctx).apply {
                            text = descBuilder.toString()
                            setTextColor(Color.LTGRAY)
                            textSize = 14f
                            setPadding(0, 0, 0, 30)
                        })
                    }
                    val okBtn = Button(ctx).apply {
                        text = "OK"
                        setTextColor(Color.WHITE)
                        isAllCaps = false
                        background = getButtonBg()
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    container.addView(okBtn)

                    val dlg = AlertDialog.Builder(ctx).setView(container).create()
                    setupCustomDialog(dlg, container)
                    okBtn.setOnClickListener { dlg.dismiss() } 
                    dlg.show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(ctx, "Failed to export settings", Toast.LENGTH_SHORT).show()
                }
            }
        }

        tvImport.setOnClickListener {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            val clipboardText = if (clipData != null && clipData.itemCount > 0) {
                clipData.getItemAt(0).text?.toString()?.trim() ?: ""
            } else ""

            if (clipboardText.isEmpty()) {
                Toast.makeText(ctx, "Clipboard is empty. Copy the backup data first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val parsedJson = withContext(Dispatchers.IO) {
                        val decodedBytes = Base64.decode(clipboardText, Base64.NO_WRAP)
                        val jsonString = try {
                            val bais = java.io.ByteArrayInputStream(decodedBytes)
                            java.util.zip.GZIPInputStream(bais).bufferedReader(Charsets.UTF_8).readText()
                        } catch (e: Exception) {
                            String(decodedBytes, Charsets.UTF_8)
                        }
                        JSONObject(jsonString)
                    }

                    if (parsedJson.length() == 0) throw Exception("Empty JSON Data")

                    val pluginName = parsedJson.optString("plugin_name")
                    println("[CineData] IMPORT TRIGGERED. Parsed Plugin Name: $pluginName")
                    if (pluginName != "StremioC_Bundle" && pluginName != "StremioC") {
                        throw Exception("Plugin mismatch")
                    }
                    
                    val cancelBtn = Button(ctx).apply {
                        text = "Cancel"
                        setTextColor(Color.WHITE)
                        isAllCaps = false
                        background = getButtonBg()
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) }
                    }
                    val restoreBtn = Button(ctx).apply {
                        text = "Restore"
                        setTextColor(Color.WHITE)
                        isAllCaps = false
                        background = getButtonBg()
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) }
                    }

                    val container = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(40, 40, 40, 40)
                        background = getDarkThemeBg()
                        addView(TextView(ctx).apply {
                            text = "Backup Data Detected"
                            textSize = 18f
                            setTextColor(Color.WHITE)
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setPadding(0, 0, 0, 20)
                            gravity = android.view.Gravity.CENTER
                        })
                        
                        val msgBuilder = StringBuilder("Valid backup data found in clipboard!\n")
                        if (pluginName == "StremioC_Bundle") {
                            val isSPAvailable = parsedJson.optBoolean("stream_play_enabled", false)
                            val isCSAvailable = parsedJson.optBoolean("cine_stream_enabled", false)
                            msgBuilder.append("\nDetected packages:")
                            msgBuilder.append("\n• StremioC: Ready")
                            if (isSPAvailable) msgBuilder.append("\n• StreamPlay: Ready")
                            if (isCSAvailable) msgBuilder.append("\n• CineStream: Ready (Will restore directly via DataStore)")
                        }
                        msgBuilder.append("\n\nAre you sure you want to proceed?")

                        addView(TextView(ctx).apply {
                            text = msgBuilder.toString()
                            setTextColor(Color.LTGRAY)
                            textSize = 14f
                            setPadding(0, 0, 0, 30)
                        })
                        val btnRow = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            addView(cancelBtn)
                            addView(restoreBtn)
                        }
                        addView(btnRow)
                    }

                    val dlg = AlertDialog.Builder(ctx).setView(container).create()
                    setupCustomDialog(dlg, container)

                    cancelBtn.setOnClickListener { dlg.dismiss() }
                    restoreBtn.setOnClickListener {
                        val editor = sharedPref.edit()
                        
                        if (pluginName == "StremioC_Bundle") {
                            // 1. StremioC 설정 복원
                            val stremioCData = parsedJson.optJSONObject("stremio_c_data")
                            if (stremioCData != null) {
                                jsonToPrefs(stremioCData, editor)
                            }

                            // 2. StreamPlay 조건부 복구 (XML 기록)
                            val isStreamPlayInBackup = parsedJson.optBoolean("stream_play_enabled", false)
                            val isStreamPlayEnabledInConfig = stremioCData?.optBoolean(SettingsRepository.PREF_KEY_STREAMPLAY_ENABLED, false) ?: false
                            if (parsedJson.has("stream_play_data") && (isStreamPlayInBackup || isStreamPlayEnabledInConfig)) {
                                val streamPlayData = parsedJson.optJSONObject("stream_play_data")
                                if (streamPlayData != null) {
                                    val streamPlayPrefs = ctx.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
                                    val editorSP = streamPlayPrefs.edit()
                                    jsonToPrefs(streamPlayData, editorSP)
                                    editorSP.commit()
                                }
                            }

                            // 3. CineStream 조건부 복구 (DataStore 강제 주입)
                            val isCineStreamInBackup = parsedJson.optBoolean("cine_stream_enabled", false)
                            val isCineStreamEnabledInConfig = stremioCData?.optBoolean(SettingsRepository.PREF_KEY_CINESTREAM_ENABLED, false) ?: false
                            val hasCineStreamData = parsedJson.has("cine_stream_data")
                            
                            println("[CineData] IMPORT - Backup has cine_stream_data: $hasCineStreamData, isCineStreamInBackup: $isCineStreamInBackup, isCineStreamEnabledInConfig: $isCineStreamEnabledInConfig")

                            if (hasCineStreamData && (isCineStreamInBackup || isCineStreamEnabledInConfig)) {
                                val cineStreamData = parsedJson.optJSONObject("cine_stream_data")
                                println("[CineData] IMPORT - Restoring CineStream Data: ${cineStreamData?.toString()}")
                                if (cineStreamData != null) {
                                    val csKeys = cineStreamData.keys()
                                    while (csKeys.hasNext()) {
                                        val cKey = csKeys.next()
                                        val cValue = cineStreamData.get(cKey)
                                        println("[CineData] IMPORT - Restoring Key: $cKey => Value: $cValue (Type: ${cValue?.javaClass?.simpleName})")
                                        try {
                                            when (cValue) {
                                                is Boolean -> CloudStreamApp.setKey(cKey, cValue)
                                                is String -> CloudStreamApp.setKey(cKey, cValue)
                                                is Int -> CloudStreamApp.setKey(cKey, cValue)
                                            }
                                            println("[CineData] IMPORT - Successfully Restored Key: $cKey")
                                        } catch (e: Throwable) {
                                            println("[CineData] IMPORT - Error Restoring Key: $cKey => ${e.message}")
                                        }
                                    }
                                }
                            }
                        } else {
                            jsonToPrefs(parsedJson, editor)
                        }
                        
                        editor.commit()
                        dlg.dismiss()
                        
                        // 🚨 DataStore 비동기 쓰기가 디스크에 반영될 수 있도록 앱 종료 전 1.5초 대기
                        viewLifecycleOwner.lifecycleScope.launch {
                            Toast.makeText(ctx, "Restoring data... Please wait.", Toast.LENGTH_SHORT).show()
                            delay(2000)
                            dismiss()
                            restartApp()
                        }
                    }
                    dlg.show()

                } catch (e: Exception) {
                    e.printStackTrace()
                    val okBtn = Button(ctx).apply {
                        text = "OK"
                        setTextColor(Color.WHITE)
                        isAllCaps = false
                        background = getButtonBg()
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }

                    val errContainer = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(40, 40, 40, 40)
                        background = getDarkThemeBg()
                        addView(TextView(ctx).apply {
                            text = "Invalid Backup Data"
                            textSize = 18f
                            setTextColor(Color.WHITE)
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setPadding(0, 0, 0, 20)
                            gravity = android.view.Gravity.CENTER
                        })
                        addView(TextView(ctx).apply {
                            text = "No valid backup data found in your clipboard.\n\nPlease copy the exact backup text and try again."
                            setTextColor(Color.LTGRAY)
                            textSize = 14f
                            setPadding(0, 0, 0, 30)
                        })
                        addView(okBtn)
                    }
                    val errDlg = AlertDialog.Builder(ctx).setView(errContainer).create()
                    setupCustomDialog(errDlg, errContainer)
                    okBtn.setOnClickListener { errDlg.dismiss() }
                    errDlg.show()
                }
            }
        }

        val switchHistorySync: Switch = try { view.findView("switchHistorySync") } catch (e: Throwable) { throw Exception("switchHistorySync Switch not found", e) }
        val layoutHistorySyncUrl: View = try { view.findView("layoutHistorySyncUrl") } catch (e: Throwable) { throw Exception("layoutHistorySyncUrl View not found", e) }
        val etHistorySyncUrl: EditText = try { view.findView("etHistorySyncUrl") } catch (e: Throwable) { throw Exception("etHistorySyncUrl EditText not found", e) }

        val isSyncEnabled = sharedPref.getBoolean(SettingsRepository.PREF_KEY_HISTORY_SYNC_ENABLED, false)
        switchHistorySync.isChecked = isSyncEnabled
        layoutHistorySyncUrl.visibility = if (isSyncEnabled) View.VISIBLE else View.GONE
        etHistorySyncUrl.setText(sharedPref.getString(SettingsRepository.PREF_KEY_HISTORY_SYNC_URL, ""))

        switchHistorySync.setOnCheckedChangeListener { _, isChecked ->
            layoutHistorySyncUrl.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        setupToggle(view, "switchCineStream", SettingsRepository.PREF_KEY_CINESTREAM_ENABLED, isCineStreamInstalled(), "CineStream")
        setupToggle(view, "switchStreamPlay", SettingsRepository.PREF_KEY_STREAMPLAY_ENABLED, isStreamPlayInstalled(), "StreamPlay")
        setupToggle(view, "switchVixSrc", SettingsRepository.PREF_KEY_VIXSRC_ENABLED, true, "VixSrc")

        val btnSelectLanguage: ImageView = try { view.findView("btnSelectLanguage") } catch (e: Throwable) { throw Exception("btnSelectLanguage ImageView not found", e) }
        val switchCustomPoster: Switch = try { view.findView("switchCustomPoster") } catch (e: Throwable) { throw Exception("switchCustomPoster Switch not found", e) }
        val layoutCustomPosterUrl: View = try { view.findView("layoutCustomPosterUrl") } catch (e: Throwable) { throw Exception("layoutCustomPosterUrl View not found", e) }
        val etCustomPosterUrl: EditText = try { view.findView("etCustomPosterUrl") } catch (e: Throwable) { throw Exception("etCustomPosterUrl EditText not found", e) }

        btnSelectLanguage.setImageDrawable(getDrawable("settings_icon"))
        btnSelectLanguage.makeTvCompatible()

        val isPosterEnabled = sharedPref.getBoolean(SettingsRepository.PREF_KEY_CUSTOM_POSTER_ENABLED, false)
        switchCustomPoster.isChecked = isPosterEnabled
        layoutCustomPosterUrl.visibility = if (isPosterEnabled) View.VISIBLE else View.GONE
        etCustomPosterUrl.setText(sharedPref.getString(SettingsRepository.PREF_KEY_CUSTOM_POSTER_URL, ""))

        switchCustomPoster.setOnCheckedChangeListener { _, isChecked ->
            layoutCustomPosterUrl.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnSelectLanguage.setOnClickListener {
            showLanguageSelectDialog()
        }

        val etTmdbApiKey: EditText = try { view.findView("etTmdbApiKey") } catch (e: Throwable) { throw Exception("etTmdbApiKey EditText not found", e) }
        val switchMdbList: Switch = try { view.findView("switchMdbList") } catch (e: Throwable) { throw Exception("switchMdbList Switch not found", e) }
        val layoutMdbList: View = try { view.findView("layoutMdbList") } catch (e: Throwable) { throw Exception("layoutMdbList View not found", e) }
        val etMdbListApiKey: EditText = try { view.findView("etMdbListApiKey") } catch (e: Throwable) { throw Exception("etMdbListApiKey EditText not found", e) }

        etTmdbApiKey.setText(sharedPref.getString(SettingsRepository.PREF_KEY_TMDB_API_KEY, ""))

        val isMdbListEnabled = sharedPref.getBoolean(SettingsRepository.PREF_KEY_MDBLIST_ENABLED, false)
        switchMdbList.isChecked = isMdbListEnabled
        layoutMdbList.visibility = if (isMdbListEnabled) View.VISIBLE else View.GONE
        etMdbListApiKey.setText(sharedPref.getString(SettingsRepository.PREF_KEY_MDBLIST_API_KEY, ""))

        switchMdbList.setOnCheckedChangeListener { _, isChecked ->
            layoutMdbList.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        addlinks.setOnClickListener {
            showLinkEditorDialog(false, null, -1, null)
        }

        showlinks.setOnClickListener {
            val inflater = LayoutInflater.from(ctx)
            val dialogView = getLayout("dialog_list_links", inflater, null) as LinearLayout
            
            dialogView.background = getDarkThemeBg()
            dialogView.setPadding(40, 40, 40, 40)
            
            val rv: RecyclerView = dialogView.findView("rvLinks")
            val tvNoLinks: TextView = dialogView.findView("tvNoLinks")
            
            val list = SettingsRepository.loadLinks(sharedPref)
            if (list.isEmpty()) {
                tvNoLinks.visibility = View.VISIBLE
                rv.visibility = View.GONE
            } else {
                tvNoLinks.visibility = View.GONE
                rv.visibility = View.VISIBLE
                rv.layoutManager = LinearLayoutManager(ctx)

                val adapter = LinksAdapter(
                    items = list,
                    onDelete = { itemToDelete ->
                        val updatedList = SettingsRepository.loadLinks(sharedPref)
                        val removed = updatedList.removeAll { it.id == itemToDelete.id }

                        if (removed) {
                            SettingsRepository.saveLinks(sharedPref, updatedList)
                            (rv.adapter as? LinksAdapter)?.remove(itemToDelete)
                            showToast("Deleted")
                            if (updatedList.isEmpty()) {
                                tvNoLinks.visibility = View.VISIBLE
                                rv.visibility = View.GONE
                            }
                        }
                    },
                    onEdit = { itemToEdit, position ->
                        val choiceContainer = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(40, 40, 40, 40)
                            background = getDarkThemeBg()
                        }
                        
                        choiceContainer.addView(TextView(ctx).apply {
                            text = itemToEdit.name
                            textSize = 18f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                            setPadding(0, 0, 0, 30)
                            gravity = android.view.Gravity.CENTER
                        })

                        val optEdit = Button(ctx).apply {
                            text = "Edit Link Info"
                            setTextColor(Color.WHITE)
                            isAllCaps = false
                            background = getButtonBg()
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 15) }
                        }
                        
                        val optCatalog = Button(ctx).apply {
                            text = "Configure Catalogs & Genres"
                            setTextColor(Color.WHITE)
                            isAllCaps = false
                            background = getButtonBg()
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        }
                        
                        choiceContainer.addView(optEdit)
                        choiceContainer.addView(optCatalog)

                        val choiceDlg = AlertDialog.Builder(ctx).setView(choiceContainer).create()
                        choiceDlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                        
                        optEdit.setOnClickListener {
                            choiceDlg.dismiss()
                            showLinkEditorDialog(true, itemToEdit, position, rv)
                        }
                        optCatalog.setOnClickListener {
                            choiceDlg.dismiss()
                            showCatalogConfigDialog(itemToEdit)
                        }
                        choiceDlg.show()
                    }
                )
                rv.adapter = adapter
            }

            val footer = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 20, 0, 0)
            }
            val closeBtn = Button(ctx).apply {
                text = "Close"
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = getButtonBg()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            footer.addView(closeBtn)
            dialogView.addView(footer)

            val dlg = AlertDialog.Builder(ctx).setView(dialogView).create()
            setupCustomDialog(dlg, dialogView)
            closeBtn.setOnClickListener { dlg.dismiss() }
            dlg.show()
        }

        saveIcon.setOnClickListener {
            val context = this.context ?: return@setOnClickListener
            val tmdbApiKeyVal = etTmdbApiKey.text.toString().trim()
            if (tmdbApiKeyVal.isEmpty()) {
                showToast("TMDB API key is a required field")
                return@setOnClickListener
            }

            val switchCineStream = view.findView<Switch>("switchCineStream")
            val switchStreamPlay = view.findView<Switch>("switchStreamPlay")
            val switchVixSrc = view.findView<Switch>("switchVixSrc")

            sharedPref.edit(commit = true) {
                putString(SettingsRepository.PREF_KEY_TMDB_API_KEY, tmdbApiKeyVal)
                putString(SettingsRepository.PREF_KEY_MDBLIST_API_KEY, etMdbListApiKey.text.toString().trim())
                putString(SettingsRepository.PREF_KEY_HISTORY_SYNC_URL, etHistorySyncUrl.text.toString().trim())
                putString(SettingsRepository.PREF_KEY_CUSTOM_POSTER_URL, etCustomPosterUrl.text.toString().trim())
                
                putBoolean(SettingsRepository.PREF_KEY_HISTORY_SYNC_ENABLED, switchHistorySync.isChecked)
                putBoolean(SettingsRepository.PREF_KEY_CUSTOM_POSTER_ENABLED, switchCustomPoster.isChecked)
                putBoolean(SettingsRepository.PREF_KEY_MDBLIST_ENABLED, switchMdbList.isChecked)
                
                putBoolean(SettingsRepository.PREF_KEY_CINESTREAM_ENABLED, switchCineStream.isChecked)
                putBoolean(SettingsRepository.PREF_KEY_STREAMPLAY_ENABLED, switchStreamPlay.isChecked)
                putBoolean(SettingsRepository.PREF_KEY_VIXSRC_ENABLED, switchVixSrc.isChecked)
            }

            val cancelBtn = Button(context).apply {
                text = "No"
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = getButtonBg()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) }
            }
            val restartBtn = Button(context).apply {
                text = "Yes"
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = getButtonBg()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) }
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 40, 40, 40)
                background = getDarkThemeBg()
                addView(TextView(context).apply {
                    text = "Save & Reload"
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, 20)
                    gravity = android.view.Gravity.CENTER
                })
                addView(TextView(context).apply {
                    text = "Changes have been saved. Do you want to restart the app to apply them?"
                    setTextColor(Color.LTGRAY)
                    textSize = 14f
                    setPadding(0, 0, 0, 30)
                })
                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(cancelBtn)
                    addView(restartBtn)
                }
                addView(btnRow)
            }

            val dlg = AlertDialog.Builder(ctx).setView(container).create()
            setupCustomDialog(dlg, container)

            cancelBtn.setOnClickListener { dlg.dismiss() }
            restartBtn.setOnClickListener { dismiss(); restartApp() }
            dlg.show()
        }

        return view
    }

    private fun showLinkEditorDialog(isEdit: Boolean, itemToEdit: LinkItem?, position: Int, rv: RecyclerView?) {
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)
        val dialogView = getLayout("addlinks", inflater, null) as LinearLayout
        
        dialogView.background = getDarkThemeBg()
        dialogView.setPadding(40, 40, 40, 40)
        
        val titleTv = dialogView.getChildAt(0) as? TextView
        if (titleTv != null) {
            titleTv.text = if (isEdit) "Edit Link" else "Add Link"
            titleTv.setTextColor(Color.WHITE)
            titleTv.textSize = 20f
            titleTv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            titleTv.gravity = android.view.Gravity.CENTER
        }
        
        val etName = try { dialogView.findView<EditText>("etName") } catch (t: Throwable) { null }
        val etLink = try { dialogView.findView<EditText>("etLink") } catch (t: Throwable) { null }
        
        etName?.background = getButtonBg()
        etName?.setTextColor(Color.WHITE)
        etName?.setHintTextColor(Color.GRAY)
        etName?.setPadding(25, 0, 25, 0) 
        if (isEdit) etName?.setText(itemToEdit?.name)
        
        etLink?.background = getButtonBg()
        etLink?.setTextColor(Color.WHITE)
        etLink?.setHintTextColor(Color.GRAY)
        etLink?.setPadding(25, 0, 25, 0) 
        if (isEdit) etLink?.setText(itemToEdit?.link)

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 30, 0, 0)
        }
        val saveBtn = Button(ctx).apply {
            text = if (isEdit) "Update" else "Save"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) }
        }
        val cancelBtn = Button(ctx).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) }
        }
        footer.addView(cancelBtn)
        footer.addView(saveBtn)
        dialogView.addView(footer)

        val dlg = AlertDialog.Builder(ctx).setView(dialogView).create()
        setupCustomDialog(dlg, dialogView)

        saveBtn.setOnClickListener {
            if (etName == null || etLink == null) return@setOnClickListener
            val name = etName.text.toString().trim()
            val link = etLink.text.toString().trim()
            val type = "StremioC"

            if (link.isEmpty()) { showToast("Please enter a link"); return@setOnClickListener }
            val valid = try { val uri = link.toUri(); val scheme = uri.scheme?.lowercase(); scheme == "http" || scheme == "https" } catch (_: Exception) { false }
            if (!valid) { showToast("Enter a valid URL (http/https)"); return@setOnClickListener }

            if (isEdit && itemToEdit != null) {
                val updatedList = SettingsRepository.loadLinks(sharedPref)
                val indexInDb = updatedList.indexOfFirst { it.id == itemToEdit.id }
                if (indexInDb != -1) {
                    val newItem = itemToEdit.copy(name = name.ifBlank { link }, link = link, type = type)
                    updatedList[indexInDb] = newItem
                    SettingsRepository.saveLinks(sharedPref, updatedList)
                    (rv?.adapter as? LinksAdapter)?.updateItem(newItem, position)
                    showToast("Link Updated Successfully")
                }
            } else {
                val item = LinkItem(name = name.ifBlank { link }, link = link, type = type)
                try {
                    val list = SettingsRepository.loadLinks(sharedPref)
                    list.add(0, item)
                    SettingsRepository.saveLinks(sharedPref, list)
                    Toast.makeText(ctx, "Link saved", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) { e.printStackTrace(); showToast("Failed to save link") }
            }
            dlg.dismiss()
        }
        cancelBtn.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    private fun showLanguageSelectDialog() {
        val context = requireContext()
        val currentSavedCode = sharedPref.getString(SettingsRepository.PREF_KEY_TMDB_LANG_CODE, "en-US") ?: "en-US"
        
        var selectedCode = currentSavedCode
        var selectedLangName = languages.find { it.second == currentSavedCode }?.first ?: "South Korea (Korean)"

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = getDarkThemeBg()
        }

        container.addView(TextView(context).apply {
            text = "Select TMDB Language"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 20)
            gravity = android.view.Gravity.CENTER
        })

        val searchBar = EditText(context).apply {
            hint = "Search language..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(25, 20, 25, 20)
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 20)
            }
        }
        container.addView(searchBar)

        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600)
        }
        container.addView(rv)

        class LangAdapter(
            private val originalList: List<Pair<String, String>>,
            private val selectedCode: String,
            private val onClick: (String) -> Unit
        ) : RecyclerView.Adapter<LangAdapter.VH>() {

            private var filteredList = originalList.toMutableList()

            inner class VH(val view: LinearLayout, val tvName: TextView, val tvCheck: TextView) : RecyclerView.ViewHolder(view)

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val rowLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(10, 20, 10, 20)
                    isClickable = true
                    focusable = View.FOCUSABLE
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val tvName = TextView(context).apply {
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvCheck = TextView(context).apply {
                    text = "✓"
                    textSize = 16f
                    setTextColor(Color.parseColor("#FF5555")) 
                    setPadding(10, 0, 10, 0)
                }
                rowLayout.addView(tvName)
                rowLayout.addView(tvCheck)
                return VH(rowLayout, tvName, tvCheck)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                val (langName, code) = filteredList[position]
                val isSelected = code == selectedCode
                holder.tvName.text = langName
                holder.tvName.setTextColor(if (isSelected) Color.parseColor("#FF5555") else Color.WHITE) 
                holder.tvCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                
                holder.view.setOnClickListener {
                    onClick(code)
                }
            }
            override fun getItemCount() = filteredList.size

            @SuppressLint("NotifyDataSetChanged")
            fun filter(query: String) {
                filteredList = if (query.isBlank()) {
                    originalList.toMutableList()
                } else {
                    originalList.filter { it.first.contains(query, ignoreCase = true) }.toMutableList()
                }
                notifyDataSetChanged()
            }
        }

        val adapter = LangAdapter(languages, selectedCode) { code ->
            sharedPref.edit { putString(SettingsRepository.PREF_KEY_TMDB_LANG_CODE, code) }
            val langName = languages.find { it.second == code }?.first ?: code
            Toast.makeText(context, "Language set to $code ($langName)", Toast.LENGTH_SHORT).show()
            activeSubDialog?.dismiss()
        }
        rv.adapter = adapter

        searchBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(150) 
                    val query = s.toString().trim().lowercase()
                    adapter.filter(query)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 30, 0, 0)
        }
        val applyBtn = Button(context).apply {
            text = "Apply"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) }
        }
        val cancelBtn = Button(context).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) }
        }
        footer.addView(cancelBtn)
        footer.addView(applyBtn)
        container.addView(footer)

        val langDialog = AlertDialog.Builder(context).setView(container).create()
        setupCustomDialog(langDialog, container)

        applyBtn.setOnClickListener {
            val code = selectedCode
            val langName = selectedLangName
            sharedPref.edit { putString(SettingsRepository.PREF_KEY_TMDB_LANG_CODE, code) }
            Toast.makeText(context, "Language set to $code ($langName)", Toast.LENGTH_SHORT).show()
            langDialog.dismiss()
        }
        cancelBtn.setOnClickListener { langDialog.dismiss() }
        langDialog.show()
    }

    private fun showCatalogConfigDialog(item: LinkItem) {
        val context = requireContext()
        val uniqueId = item.link.toUniqueKeyLocal() 
        
        val progressContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = getDarkThemeBg()
            addView(TextView(context).apply {
                text = "Loading Stremio Addon Manifest..."
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = android.view.Gravity.CENTER
            })
        }
        val progressDialog = AlertDialog.Builder(context).setView(progressContainer).setCancelable(false).create()
        setupCustomDialog(progressDialog, progressContainer)
        progressDialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val manifestText = withContext(Dispatchers.IO) {
                try { com.lagradost.cloudstream3.app.get(item.link, timeout = 30L).text } catch (e: Exception) { e.printStackTrace(); null }
            }
            progressDialog.dismiss()

            if (manifestText.isNullOrBlank()) {
                Toast.makeText(context, "Failed to load manifest. Check link.", Toast.LENGTH_LONG).show()
                return@launch
            }

            val cleanUrl = item.link.substringBefore("?").trimEnd('/')
            val currentUrl = if (cleanUrl.endsWith("manifest.json")) cleanUrl else "$cleanUrl/manifest.json"
            val manifestPrefs = context.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
            manifestPrefs.edit().putString(currentUrl.toUniqueKeyLocal(), manifestText).apply()

            val flatTabs = mutableListOf<ConfigFlatTab>()
            try {
                val node = JsonParser.parseString(manifestText).asJsonObject
                val catsNode = node.get("catalogs")?.takeIf { it.isJsonArray }?.asJsonArray
                
                if (catsNode != null) {
                    for (i in 0 until catsNode.size()) {
                        val cat = catsNode.get(i).asJsonObject
                        val id = cat.get("id")?.asString ?: ""
                        val type = cat.get("type")?.asString ?: "movie"
                        val name = cat.get("name")?.asString ?: id
                        val displayType = type.replaceFirstChar { it.uppercase() }
                        
                        val requiresGenre = cat.get("extra")?.takeIf { it.isJsonArray }?.asJsonArray?.let { extraArr ->
                            var found = false
                            for (j in 0 until extraArr.size()) {
                                val extraObj = extraArr.get(j).asJsonObject
                                if (extraObj.get("name")?.asString == "genre" && extraObj.get("isRequired")?.asBoolean == true) found = true
                            }
                            found
                        } ?: false

                        flatTabs.add(ConfigFlatTab(
                            key = "${id}_${type}_default", 
                            displayName = "$name - $displayType", 
                            isGenre = false, 
                            catalogKey = "${id}_${type}",
                            isChecked = sharedPref.getBoolean("tab_enabled_${uniqueId}_${id}_${type}_default", true),
                            isGenresEnabled = sharedPref.getBoolean("genres_enabled_${uniqueId}_${id}_${type}", true)
                        ))

                        val genres = mutableListOf<String>()
                        cat.get("genres")?.takeIf { it.isJsonArray }?.asJsonArray?.let { gArr ->
                            for (j in 0 until gArr.size()) genres.add(gArr.get(j).asString)
                        }
                        if (genres.isEmpty()) {
                            cat.get("extra")?.takeIf { it.isJsonArray }?.asJsonArray?.let { extraArr ->
                                for (j in 0 until extraArr.size()) {
                                    val extraObj = extraArr.get(j).asJsonObject
                                    if (extraObj.get("name")?.asString == "genre") {
                                        extraObj.get("options")?.takeIf { it.isJsonArray }?.asJsonArray?.let { optsArr ->
                                            for (k in 0 until optsArr.size()) genres.add(optsArr.get(k).asString)
                                        }
                                    }
                                }
                            }
                        }

                        genres.forEach { genre ->
                            val capitalizedGenre = genre.capitalizeFirstLocal()
                            flatTabs.add(ConfigFlatTab(
                                key = "${id}_${type}_genre_$genre", 
                                displayName = "$name : $capitalizedGenre  - $displayType", 
                                isGenre = true, 
                                catalogKey = "${id}_${type}",
                                isChecked = sharedPref.getBoolean("tab_enabled_${uniqueId}_${id}_${type}_genre_$genre", true)
                            ))
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            if (flatTabs.isEmpty()) { Toast.makeText(context, "No catalogs found in manifest.", Toast.LENGTH_LONG).show(); return@launch }
            displayCatalogListDialog(item.name, flatTabs, item.link)
        }
    }

    private fun displayCatalogListDialog(addonName: String, flatTabs: List<ConfigFlatTab>, linkUrl: String) {
        val context = requireContext()
        val prefs = sharedPref
        val uniqueId = linkUrl.toUniqueKeyLocal()
        val orderKey = "catalog_order_$uniqueId"
        
        val orderJson = prefs.getString(orderKey, null)
        val orderedKeys = mutableListOf<String>()
        if (orderJson != null) {
            try {
                val arr = JSONArray(orderJson)
                for (i in 0 until arr.length()) orderedKeys.add(arr.getString(i))
            } catch (e: Exception) { e.printStackTrace() }
        }

        val sortedTabs = flatTabs.sortedBy { tab ->
            val idx = orderedKeys.indexOf(tab.key)
            if (idx != -1) idx else Int.MAX_VALUE
        }
        val checkedInit = sortedTabs.filter { it.isChecked }
        val uncheckedInit = sortedTabs.filter { !it.isChecked }
        val masterTabs = (checkedInit + uncheckedInit).toMutableList()

        val dialogContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(25, 40, 25, 30)
            background = getDarkThemeBg()
        }

        val titleView = TextView(context).apply {
            text = "$addonName - Catalog Settings"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 30)
            gravity = android.view.Gravity.CENTER
        }
        dialogContainer.addView(titleView)

        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(0, 0, 0, 20)
            }
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(120)
        }
        dialogContainer.addView(rv)

        class CatalogAdapter : RecyclerView.Adapter<CatalogAdapter.VH>() {
            var visibleTabs = mutableListOf<ConfigFlatTab>()
            
            fun updateVisibleTabs() {
                visibleTabs = masterTabs.filter { tab ->
                    if (tab.isGenre) {
                        val parentTab = masterTabs.find { !it.isGenre && it.catalogKey == tab.catalogKey }
                        parentTab?.isGenresEnabled ?: true
                    } else true
                }.toMutableList()
                
                if (rv.isComputingLayout) {
                    rv.post { notifyDataSetChanged() }
                } else {
                    notifyDataSetChanged()
                }
            }

            inner class VH(val view: LinearLayout, val checkbox: CheckBox, val etOrder: EditText, val genreSwitch: Switch, val dragHandle: ImageView) : RecyclerView.ViewHolder(view)

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(10, 8, 10, 8)
                    background = getCardBg()
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 5, 0, 5)
                    }
                }
                val dragHandle = ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_menu_sort_by_size)
                    setPadding(10, 0, 20, 0)
                }
                
                val etOrder = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setPadding(10, 10, 10, 10)
                    background = getButtonBg()
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(90, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 15, 0)
                    }
                    setOnClickListener {
                        requestFocus()
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                
                val checkbox = CheckBox(context).apply {
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val genreSwitch = Switch(context).apply {
                    text = "Genre"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setPadding(20, 0, 20, 0)
                }
                layout.addView(dragHandle)
                layout.addView(etOrder)
                layout.addView(checkbox)
                layout.addView(genreSwitch)
                return VH(layout, checkbox, etOrder, genreSwitch, dragHandle)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                val tab = visibleTabs[position]
                
                holder.checkbox.setOnCheckedChangeListener(null)
                holder.checkbox.isChecked = tab.isChecked 
                holder.checkbox.text = tab.displayName
                holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                    tab.isChecked = isChecked 
                    
                    val checkedTabs = masterTabs.filter { it.isChecked }
                    val uncheckedTabs = masterTabs.filter { !it.isChecked }
                    masterTabs.clear()
                    masterTabs.addAll(checkedTabs)
                    masterTabs.addAll(uncheckedTabs)
                    updateVisibleTabs()
                }

                if (!tab.isGenre && masterTabs.any { it.isGenre && it.catalogKey == tab.catalogKey }) {
                    holder.genreSwitch.visibility = View.VISIBLE
                    holder.genreSwitch.setOnCheckedChangeListener(null)
                    holder.genreSwitch.isChecked = tab.isGenresEnabled 
                    holder.genreSwitch.setOnCheckedChangeListener { _, isChecked ->
                        tab.isGenresEnabled = isChecked 
                        updateVisibleTabs()
                    }
                } else {
                    holder.genreSwitch.visibility = View.GONE
                }
                
                holder.etOrder.setOnFocusChangeListener(null)
                holder.etOrder.setText((position + 1).toString())
                
                holder.etOrder.setOnFocusChangeListener { _, hasFocus ->
                    val curPos = holder.adapterPosition 
                    if (curPos == RecyclerView.NO_POSITION) return@setOnFocusChangeListener

                    if (hasFocus) {
                        rv.postDelayed({
                            rv.smoothScrollToPosition(curPos)
                        }, 150)
                    } else {
                        val inputVal = holder.etOrder.text.toString().toIntOrNull()
                        if (inputVal != null) {
                            shiftItemInRecyclerView(curPos, inputVal) 
                        } else {
                            holder.etOrder.setText((curPos + 1).toString()) 
                        }
                    }
                }
                
                holder.etOrder.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                        holder.etOrder.clearFocus()
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(holder.etOrder.windowToken, 0)
                        true
                    } else false
                }
            }


            override fun getItemCount() = visibleTabs.size
            
            fun shiftItemInRecyclerView(oldPos: Int, newOrder: Int) {
                rv.post {
                    if (oldPos !in visibleTabs.indices) return@post
                    val item = visibleTabs[oldPos]
                    
                    val isEnabled = item.isChecked 
                    val checkedCount = visibleTabs.count { it.isChecked } 
                    
                    val groupMin = if (isEnabled) 1 else checkedCount + 1
                    val groupMax = if (isEnabled) checkedCount else visibleTabs.size
                    
                    val clampedOrder = newOrder.coerceIn(groupMin, groupMax)
                    val newPos = clampedOrder - 1
                    
                    if (oldPos != newPos) {
                        visibleTabs.removeAt(oldPos)
                        visibleTabs.add(newPos, item)
                        
                        val masterFrom = masterTabs.indexOf(item)
                        if (masterFrom != -1) {
                            masterTabs.removeAt(masterFrom)
                            
                            val referenceTab = if (newPos + 1 in visibleTabs.indices) visibleTabs[newPos + 1] else if (newPos - 1 in visibleTabs.indices) visibleTabs[newPos - 1] else null
                            var masterTo = if (referenceTab != null) masterTabs.indexOf(referenceTab) else -1
                            
                            if (masterTo != -1) {
                                if (referenceTab == visibleTabs.getOrNull(newPos - 1)) {
                                    masterTo += 1
                                }
                                masterTabs.add(masterTo.coerceIn(0, masterTabs.size), item)
                            } else {
                                masterTabs.add(item)
                            }
                        }
                        
                        notifyItemMoved(oldPos, newPos)
                        notifyItemRangeChanged(minOf(oldPos, newPos), maxOf(oldPos, newPos) + 1)
                    } else {
                        notifyItemChanged(oldPos)
                    }
                }
            }
        }

        val adapter = CatalogAdapter()
        adapter.updateVisibleTabs()
        rv.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
                
                val movedTab = adapter.visibleTabs[fromPos]
                Collections.swap(adapter.visibleTabs, fromPos, toPos)
                
                val masterFrom = masterTabs.indexOf(movedTab)
                val targetTab = adapter.visibleTabs[fromPos]
                val masterTo = masterTabs.indexOf(targetTab)
                
                if (masterFrom != -1 && masterTo != -1) {
                    masterTabs.removeAt(masterFrom)
                    masterTabs.add(masterTo, movedTab)
                }

                adapter.notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.notifyDataSetChanged()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        
        touchHelper.attachToRecyclerView(rv)

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 30, 0, 0)
        }
        val cancelBtn = Button(context).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 15, 0)
            }
        }
        val saveBtn = Button(context).apply {
            text = "Save"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(15, 0, 0, 0)
            }
        }
        footer.addView(cancelBtn)
        footer.addView(saveBtn)
        dialogContainer.addView(footer)

        val dialog = AlertDialog.Builder(context).setView(dialogContainer).create()
        setupCustomDialog(dialog, dialogContainer)

        saveBtn.setOnClickListener {
            val editor = prefs.edit()
            val keysToSave = JSONArray()
            
            masterTabs.forEach { 
                keysToSave.put(it.key) 
                editor.putBoolean("tab_enabled_${uniqueId}_${it.key}", it.isChecked)
                if (!it.isGenre) {
                    editor.putBoolean("genres_enabled_${uniqueId}_${it.catalogKey}", it.isGenresEnabled)
                }
            }
            editor.putString(orderKey, keysToSave.toString())
            editor.commit() 
            
            dialog.dismiss()
            showSavedPopup(context, "Catalog Settings", "Catalog settings saved. Reload to apply.")
        }

        cancelBtn.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun String.capitalizeFirstLocal(): String = if (this.isNotEmpty()) this.substring(0, 1).uppercase() + this.substring(1) else this
    private fun String.toUniqueKeyLocal(): String = this.lowercase().filter { it.isLetter() }

    private fun setupToggle(view: View, viewId: String, prefKey: String, isInstalled: Boolean, displayName: String) {
        try {
            val switchView: CompoundButton = view.findView(viewId)
            switchView.isEnabled = isInstalled
            if (!isInstalled) {
                switchView.isChecked = false
                switchView.text = "Not Installed"
                SettingsRepository.setIntegrationEnabled(sharedPref, prefKey, false)
            } else {
                switchView.text = "Integration"
                switchView.isChecked = SettingsRepository.isIntegrationEnabled(sharedPref, prefKey)
            }
            switchView.setOnCheckedChangeListener { _, isChecked ->
                showToast("$displayName Integration " + if (isChecked) "Enabled" else "Disabled")
            }
        } catch (e: Throwable) { e.printStackTrace() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {}

    private fun restartApp() {
        val context = requireContext().applicationContext
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component

        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }

    inner class LinksAdapter(
        private val items: MutableList<LinkItem>,
        private val onDelete: (LinkItem) -> Unit,
        private val onEdit: (LinkItem, Int) -> Unit
    ) : RecyclerView.Adapter<LinksAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findView("tvName")
            val tvLink: TextView = v.findView("tvLink")
            val tvType: TextView = v.findView("tvType")
            val btnDelete: ImageButton = v.findView("btnDelete")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layoutId = getCachedResId("item_saved_link", "layout")
            val v = layoutInflater.inflate(res.getLayout(layoutId), parent, false)
            v.background = getCardBg() 
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvLink.text = item.link
            holder.tvType.text = item.type
            holder.btnDelete.setOnClickListener { onDelete(item) }
            holder.itemView.setOnClickListener { onEdit(item, position) }
        }

        override fun getItemCount(): Int = items.size

        fun remove(item: LinkItem) {
            val idx = items.indexOfFirst { it.id == item.id }
            if (idx >= 0) {
                items.removeAt(idx)
                notifyItemRemoved(idx)
            }
        }

        fun updateItem(newItem: LinkItem, position: Int) {
            if (position in items.indices) {
                items[position] = newItem
                notifyItemChanged(position)
            }
        }
    }
}
