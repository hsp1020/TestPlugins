// hsp1020/settings/SettingsBottomFragment.kt
package com.hsp1020

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.InputType
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialog
import com.lagradost.cloudstream3.R as HostR
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.cancel
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.showToast
import org.json.JSONArray
import org.json.JSONObject
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.withContext
import java.util.Collections
import com.lagradost.cloudstream3.MainAPI

data class LinkItem(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val link: String,
    val type: String,
    val isSyncEnabled: Boolean = true,
    val isHistorySyncEnabled: Boolean = true,
    val isStreamSubtitleSyncEnabled: Boolean = false
)

object SettingsRepository {
    private const val PREF_KEY_LINKS = "stremio_saved_links"
    const val PREF_KEY_CINESTREAM_ENABLED = "cinestream_integration_enabled"
    const val PREF_KEY_STREAMPLAY_ENABLED = "streamplay_integration_enabled"
    const val PREF_KEY_VIXSRC_ENABLED = "vixsrc_integration_enabled"
    const val PREF_KEY_HISTORY_SYNC_URL = "history_sync_url"
    const val PREF_KEY_STREAM_SUBTITLE_URLS = "stream_subtitle_addon_urls"
    const val PREF_KEY_STREAM_SUBTITLE_EXPANDED = "stream_subtitle_urls_expanded"
    
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
                        type = obj.optString("type", "StremioC"),
                        isSyncEnabled = obj.optBoolean("isSyncEnabled", true),
                        isHistorySyncEnabled = obj.optBoolean("isHistorySyncEnabled", obj.optBoolean("isSyncEnabled", true)),
                        isStreamSubtitleSyncEnabled = obj.optBoolean("isStreamSubtitleSyncEnabled", false)
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
                put("isSyncEnabled", item.isSyncEnabled)
                put("isHistorySyncEnabled", item.isHistorySyncEnabled)
                put("isStreamSubtitleSyncEnabled", item.isStreamSubtitleSyncEnabled)
            }
            arr.put(obj)
        }
        val editor = sharedPref.edit()
        editor.putString(PREF_KEY_LINKS, arr.toString())
        editor.commit()
        StremioC.invalidateSyncSettingsCache()
    }

    fun isIntegrationEnabled(sharedPref: SharedPreferences, key: String, default: Boolean = true): Boolean {
        return sharedPref.getBoolean(key, default)
    }

    fun setIntegrationEnabled(sharedPref: SharedPreferences, key: String, state: Boolean) {
        val editor = sharedPref.edit()
        editor.putBoolean(key, state)
        editor.commit()
    }
}

class SettingsBottomFragment(
    private val plugin: StremioCPlugin,
    private val sharedPref: SharedPreferences,
    private val hostContext: Context
) {
    // The sheet is not restored by FragmentManager during Activity recreation.
    private val settingsDialog = AppCompatDialog(hostContext)
    private val uiScope = MainScope()
    private val hostOwner = hostContext as? LifecycleOwner
    private val closeOnDestroy = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) dismiss()
    }
    fun dismiss() = settingsDialog.dismiss()

    companion object {
        val LANGUAGES = listOf(
            "South Africa (Afrikaans)" to "af-ZA", "United Arab Emirates (Arabic)" to "ar-AE", "Saudi Arabia (Arabic)" to "ar-SA",
            "Azerbaijan (Azerbaijani)" to "az-AZ", "Bulgaria (Bulgarian)" to "bg-BG", "India (Bengali)" to "bn-IN",
            "Spain (Catalan)" to "ca-ES", "Czech Republic (Czech)" to "cs-CZ", "United Kingdom (Welsh)" to "cy-GB",
            "Denmark (Danish)" to "da-DK", "Germany (German)" to "de-DE", "Greece (Greek)" to "el-GR",
            "United States (English)" to "en-US", "United Kingdom (English)" to "en-GB", "Spain (Spanish)" to "es-ES",
            "Latin America (Spanish)" to "es-419", "Estonia (Estonian)" to "et-EE", "Spain (Basque)" to "eu-ES",
            "Iran (Persian)" to "fa-IR", "Finland (Finnish)" to "fi-FI", "Philippines (Filipino)" to "fil-PH",
            "France (French)" to "fr-FR", "Spain (Galician)" to "gl-ES", "India (Gujarati)" to "gu-IN",
            "Israel (Hebrew)" to "he-IL", "India (Hindi)" to "hi-IN", "Croatia (Croatian)" to "hr-HR",
            "Hungary (Hungarian)" to "hu-HU", "Indonesia (Indonesian)" to "id-ID", "Iceland (Icelandic)" to "is-IS",
            "Italy (Italian)" to "it-IT", "Japan (Japanese)" to "ja-JP", "India (Kannada)" to "kn-IN",
            "South Korea (Korean)" to "ko-KR", "Lublin (Polish)" to "pl-PL", "Lithuania (Lithuanian)" to "lt-LT",
            "Latvia (Latvian)" to "lv-LV", "India (Malayalam)" to "ml-IN", "Malaysia (Malay)" to "ms-MY",
            "Norway (Norwegian)" to "no-NO", "Netherlands (Dutch)" to "nl-NL", "Poland (Polish)" to "pl-PL",
            "Brazil (Portuguese)" to "pt-BR", "Portugal (Portuguese)" to "pt-PT", "Romania (Romanian)" to "ro-RO",
            "Russia (Russian)" to "ru-RU", "Slovakia (Slovak)" to "sk-SK", "Slovenia (Slovenian)" to "sl-SI",
            "Serbia (Serbian)" to "sr-RS", "Sweden (Swedish)" to "sv-SE", "India (Tamil)" to "ta-IN",
            "India (Telugu)" to "te-IN", "Thailand (Thai)" to "th-TH", "Turkey (Turkish)" to "tr-TR",
            "Ukraine (Ukrainian)" to "uk-UA", "Vietnam (Vietnamese)" to "vi-VN", "China (Chinese Simplified)" to "zh-CN",
            "Taiwan (Chinese Traditional)" to "zh-TW"
        ).sortedBy { it.first.lowercase() }
    }

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
        var isChecked: Boolean = true
    )

    private var searchJob: Job? = null 

    private fun dp(value: Int) = (value * hostContext.resources.displayMetrics.density + 0.5f).toInt()
    // Inherit the host font while retaining each existing field's emphasis.
    private val hostTypeface by lazy { TextView(hostContext).typeface }
    private fun normalizeTypeface(view: View) {
        if (view is TextView) {
            val weight = if (view.typeface?.isBold == true) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            view.typeface = android.graphics.Typeface.create(hostTypeface, weight)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) normalizeTypeface(view.getChildAt(i))
    }
    private fun getDarkThemeBg() = hostContext.getDrawable(HostR.drawable.dialog__window_background)!!.mutate()
    private fun getCardBg() = hostContext.getDrawable(HostR.drawable.bg_color_both)!!.mutate()

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
        try {
            val loader = getCineStreamClassLoader()
            if (loader != null) {
                val settingsClass = Class.forName("com.megix.settings.Settings", true, loader)
                settingsClass.declaredFields.forEach { field ->
                    if (field.type == String::class.java) {
                        field.isAccessible = true
                        val keyName = field.get(null) as? String
                        if (!keyName.isNullOrBlank()) keys.add(keyName)
                    }
                }
            }
        } catch (e: Exception) { }

        try {
            CloudStreamApp.getKey<String>("seen_providers")?.split(",")?.forEach {
                if (it.isNotBlank()) keys.add(it.trim())
            }
            CloudStreamApp.getKey<String>("provider_order")?.split(",")?.forEach {
                if (it.isNotBlank()) keys.add(it.trim())
            }
        } catch (e: Exception) { }

        try {
            CloudStreamApp.getKey<String>("stremio_addons")?.lines()?.forEach { line ->
                val name = line.split("|").firstOrNull()?.trim()
                if (!name.isNullOrBlank()) {
                    val formatted = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    keys.add("stremio_$formatted")
                }
            }
        } catch (e: Exception) { }

        val hardcoded = listOf(
            "ScrapeConcurrency", "DownloadEnable", "ProviderCineStream", "ProviderSimkl", 
            "ProviderTmdb", "showbox_ui_token", "wyzie_subs_api_key", "gramcinema_bearer_token", 
            "stremio_addons", "new_provider_default_on", "seen_providers", "provider_order"
        )
        keys.addAll(hardcoded)
        return keys
    }

    private fun recordBackupSnapshot(prefs: SharedPreferences, completeKey: String, hashKey: String, backupKey: String) {
        val json = prefsToJson(prefs).toString()
        val hash = json.hashCode().toString()
        val editor = prefs.edit()
        editor.putBoolean(completeKey, true)
        editor.putString(hashKey, hash)
        editor.commit()
        try {
            CloudStreamApp.setKey(backupKey, json)
        } catch (t: Throwable) { t.printStackTrace() }
    }

    private fun setupCustomDialog(dialog: AlertDialog, contentView: View) {
        normalizeTypeface(contentView)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

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

    // Plugin XML cannot reference the host app's custom attrs at AAPT2 link time.
    private fun themeLayoutText(view: View) {
        if (view is TextView && view.tag != "stremioc_keep_color") {
            val attr = if (view.tag == "stremioc_secondary") HostR.attr.grayTextColor else HostR.attr.textColor
            view.setTextColor(hostContext.colorFromAttribute(attr))
            if (view is EditText) view.setHintTextColor(hostContext.colorFromAttribute(HostR.attr.grayTextColor))
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) themeLayoutText(view.getChildAt(i))
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = getCachedResId(name, "layout")
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false).also {
            normalizeTypeface(it)
            themeLayoutText(it)
        }
    }

    private fun isCineStreamInstalled(): Boolean {
        return getSafeAllProviders().any { it.name.equals("CineStream", true) } ||
               StremioC.loadProviderDynamically("megix", "com.megix.CineStreamProvider") != null
    }

    private fun isStreamPlayInstalled(): Boolean {
        return getSafeAllProviders().any { it.name.equals("StreamPlay", true) } ||
               StremioC.loadProviderDynamically("phisher", "com.phisher98.StreamPlay") != null
    }

    private fun dialogButton(ctx: Context, label: String, params: LinearLayout.LayoutParams) = TextView(ctx).apply {
        text = label
        textSize = 14f
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
        gravity = android.view.Gravity.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        minHeight = dp(40)
        isClickable = true
        isFocusable = true
        val ripple = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
        layoutParams = params
    }

    private fun showSavedPopup(ctx: Context, title: String, message: String, onConfirm: () -> Unit = {}) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = getDarkThemeBg()
            addView(TextView(ctx).apply {
                text = title
                textSize = 18f
                setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 20)
                gravity = android.view.Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = message
                setTextColor(hostContext.colorFromAttribute(HostR.attr.grayTextColor))
                textSize = 14f
                setPadding(0, 0, 0, 30)
            })
        }
        val okBtn = dialogButton(ctx, "OK", LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
    fun show(onDismiss: () -> Unit = {}) {
        settingsDialog.setCancelable(true)
        settingsDialog.setCanceledOnTouchOutside(false)
        settingsDialog.setOnDismissListener {
            hostOwner?.lifecycle?.removeObserver(closeOnDestroy)
            searchJob?.cancel()
            uiScope.cancel()
            onDismiss()
        }
        val ctx = hostContext
        val appContext = ctx.applicationContext
        val view = getLayout("bottom_sheet_layout", LayoutInflater.from(ctx), null)
        view.background = getDarkThemeBg()
        val settingsBody = view.findView<View>("settingsBody")
        val settingsToggle = view.findView<TextView>("settingsToggle")
        val settingsActions = listOf("tvExport", "tvImport", "saveIcon").map { view.findView<View>(it) }
        val header = view.findView<LinearLayout>("settingsHeader")
        // The host can retain an older plugin layout: rebuild this row from the existing controls.
        (settingsToggle.parent as? ViewGroup)?.removeView(settingsToggle)
        settingsActions.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        header.removeAllViews()
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = android.view.Gravity.CENTER_VERTICAL
        settingsToggle.apply {
            setPadding(0, 0, 0, 0)
            setSingleLine(true)
        }
        header.addView(settingsToggle, LinearLayout.LayoutParams(0, dp(48), 1f))
        settingsActions.forEachIndexed { index, action ->
            if (index > 0) header.addView(View(ctx), LinearLayout.LayoutParams(dp(16), dp(48)))
            if (action is TextView) action.setPadding(0, 0, 0, 0)
            header.addView(action, LinearLayout.LayoutParams(
                if (index == 2) dp(36) else ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)
            ))
        }
        settingsBody.visibility = View.VISIBLE
        settingsActions.forEach { it.visibility = View.VISIBLE }
        settingsToggle.text = "Stremio Settings"
        // Plugin drawable IDs cannot be resolved safely by the host XML inflater.
        // Apply theme backgrounds after inflation using the plugin-independent drawable.
        listOf("etTmdbApiKey", "etMdbListApiKey", "etCustomPosterUrl", "etHistorySyncUrl").forEach { id ->
            view.findView<EditText>(id).background = getCardBg()
        }

        val addlinks: ImageView = try { view.findView("addlinks") } catch (e: Throwable) { throw Exception("addlinks ImageView not found", e) }
        val showlinks: ImageView = try { view.findView("showlinks") } catch (e: Throwable) { throw Exception("showlinks ImageView not found", e) }
        val saveIcon: ImageView = try { view.findView("saveIcon") } catch (e: Throwable) { throw Exception("saveIcon ImageView not found", e) }

        addlinks.setImageDrawable(getDrawable("settings_icon"))
        showlinks.setImageDrawable(getDrawable("settings_icon"))
        saveIcon.setImageDrawable(getDrawable("save_icon"))
        val iconTint = ColorStateList.valueOf(hostContext.colorFromAttribute(HostR.attr.colorPrimary))
        val actionIconTint = ColorStateList.valueOf(hostContext.colorFromAttribute(HostR.attr.textColor))
        listOf(addlinks, showlinks, saveIcon).forEach { it.imageTintList = actionIconTint }

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

            uiScope.launch {
                try {
                    val backupStr = withContext(Dispatchers.IO) {
                        val json = JSONObject()
                        
                        json.put("stremio_c_data", prefsToJson(sharedPref))
                        json.put("plugin_name", "StremioC_Bundle")
                        
                        json.put("stream_play_enabled", isStreamPlayExportActive)
                        if (isStreamPlayExportActive) {
                            val streamPlayPrefs = appContext.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
                            json.put("stream_play_data", prefsToJson(streamPlayPrefs))
                        }

                        json.put("cine_stream_enabled", isCineStreamExportActive)
                        if (isCineStreamExportActive) {
                            val cineStreamJson = JSONObject()
                            try {
                                val dynamicKeys = getCineStreamDynamicKeys()
                                
                                dynamicKeys.forEach { cKey ->
                                    var found = false
                                    try { 
                                        val strVal = CloudStreamApp.getKey<String>(cKey)
                                        if (strVal != null) {
                                            cineStreamJson.put(cKey, strVal)
                                            found = true
                                        }
                                    } catch (t: Throwable) {
                                    }
                                    
                                    if (!found) {
                                        try {
                                            val boolVal = CloudStreamApp.getKey<Boolean>(cKey)
                                            if (boolVal != null) {
                                                cineStreamJson.put(cKey, boolVal)
                                                found = true
                                            }
                                        } catch (t: Throwable) {
                                        }
                                    }
                                    
                                    if (!found) {
                                        try {
                                            val intVal = CloudStreamApp.getKey<Int>(cKey)
                                            if (intVal != null) {
                                                cineStreamJson.put(cKey, intVal)
                                            }
                                        } catch (t: Throwable) {
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            json.put("cine_stream_data", cineStreamJson)
                        }                        
                                              
                        val jsonString = json.toString()
                        val bos = java.io.ByteArrayOutputStream()
                        java.util.zip.GZIPOutputStream(bos).use { it.write(jsonString.toByteArray(Charsets.UTF_8)) }
                        Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                    }

                    if (!isActive) return@launch

                    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("StremioC Backup Bundle", backupStr)
                    clipboard.setPrimaryClip(clip)

                    val container = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(40, 40, 40, 40)
                        background = getDarkThemeBg()
                        addView(TextView(ctx).apply {
                            text = "Export Successful"
                            textSize = 18f
                            setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setPadding(0, 0, 0, 20)
                            gravity = android.view.Gravity.CENTER
                        })
                        val descBuilder = StringBuilder("StremioC settings copied successfully.")
                        if (isStreamPlayExportActive) descBuilder.append("\n+ StreamPlay settings included.")
                        if (isCineStreamExportActive) descBuilder.append("\n+ CineStream settings included.")
                        descBuilder.append("\n\nPlease paste it somewhere safe.")

                        addView(TextView(ctx).apply {
                            text = descBuilder.toString()
                            setTextColor(hostContext.colorFromAttribute(HostR.attr.grayTextColor))
                            textSize = 14f
                            setPadding(0, 0, 0, 30)
                        })
                    }
                    val okBtn = dialogButton(ctx, "OK", LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    container.addView(okBtn)

                    val dlg = AlertDialog.Builder(ctx).setView(container).create()
                    setupCustomDialog(dlg, container)
                    okBtn.setOnClickListener { dlg.dismiss() } 
                    dlg.show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(appContext, "Failed to export settings", Toast.LENGTH_SHORT).show()
                }
            }
        }

        tvImport.setOnClickListener {
            val ctx = hostContext
            val appContext = ctx.applicationContext
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            val clipboardText = if (clipData != null && clipData.itemCount > 0) {
                clipData.getItemAt(0).text?.toString()?.trim() ?: ""
            } else ""

            if (clipboardText.isEmpty()) {
                Toast.makeText(appContext, "Clipboard is empty. Copy the backup data first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            uiScope.launch {
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
                    if (pluginName != "StremioC_Bundle" && pluginName != "StremioC") {
                        throw Exception("Plugin mismatch")
                    }
                    
                    if (!isActive) return@launch

                    val cancelBtn = dialogButton(ctx, "Cancel", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) })
                    val restoreBtn = dialogButton(ctx, "Restore", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) })

                    val container = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(40, 40, 40, 40)
                        background = getDarkThemeBg()
                        addView(TextView(ctx).apply {
                            text = "Backup Data Detected"
                            textSize = 18f
                            setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
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
                            if (isCSAvailable) msgBuilder.append("\n• CineStream: Ready")
                        }
                        msgBuilder.append("\n\nAre you sure you want to proceed?")

                        addView(TextView(ctx).apply {
                            text = msgBuilder.toString()
                            setTextColor(hostContext.colorFromAttribute(HostR.attr.grayTextColor))
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
                        dlg.dismiss() 
                        
                        val loadingContainer = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(40, 40, 40, 40)
                            background = getDarkThemeBg()
                            addView(TextView(ctx).apply {
                                text = "Restoring Data"
                                textSize = 18f
                                setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                setPadding(0, 0, 0, 20)
                                gravity = android.view.Gravity.CENTER
                            })
                            addView(TextView(ctx).apply {
                                text = "Please wait while settings are being applied...\nApp will restart automatically."
                                setTextColor(hostContext.colorFromAttribute(HostR.attr.grayTextColor))
                                textSize = 14f
                                gravity = android.view.Gravity.CENTER
                                setPadding(0, 0, 0, 10)
                            })
                        }
                        val loadingDlg = AlertDialog.Builder(ctx).setView(loadingContainer).setCancelable(false).create()
                        setupCustomDialog(loadingDlg, loadingContainer)
                        loadingDlg.show()

                        uiScope.launch {
                            withContext(Dispatchers.IO) {
                                val editor = sharedPref.edit()
                                
                                if (pluginName == "StremioC_Bundle") {
                                    val stremioCData = parsedJson.optJSONObject("stremio_c_data")
                                    if (stremioCData != null) {
                                        editor.clear()
                                        jsonToPrefs(stremioCData, editor)
                                    }
                                    editor.commit()

                                    recordBackupSnapshot(sharedPref, "stremio_c_restore_complete", "stremio_c_last_applied_backup_hash", "native_backup_stremioc")

                                    val isStreamPlayInBackup = parsedJson.optBoolean("stream_play_enabled", false)
                                    val isStreamPlayEnabledInConfig = stremioCData?.optBoolean(SettingsRepository.PREF_KEY_STREAMPLAY_ENABLED, false) ?: false
                                    if (parsedJson.has("stream_play_data") && (isStreamPlayInBackup || isStreamPlayEnabledInConfig)) {
                                        val streamPlayData = parsedJson.optJSONObject("stream_play_data")
                                        if (streamPlayData != null) {
                                            val streamPlayPrefs = appContext.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
                                            val editorSP = streamPlayPrefs.edit()
                                            editorSP.clear() 
                                            jsonToPrefs(streamPlayData, editorSP)
                                            editorSP.commit()

                                            recordBackupSnapshot(streamPlayPrefs, "streamplay_restore_complete", "streamplay_last_applied_backup_hash", "native_backup_streamplay")
                                        }
                                    }

                                    val isCineStreamInBackup = parsedJson.optBoolean("cine_stream_enabled", false)
                                    val isCineStreamEnabledInConfig = stremioCData?.optBoolean(SettingsRepository.PREF_KEY_CINESTREAM_ENABLED, false) ?: false
                                    if (parsedJson.has("cine_stream_data") && (isCineStreamInBackup || isCineStreamEnabledInConfig)) {
                                        val cineStreamData = parsedJson.optJSONObject("cine_stream_data")
                                        if (cineStreamData != null) {
                                            val dynamicKeys = getCineStreamDynamicKeys()
                                            
                                            // 1. CloudStreamApp 전역 데이터베이스 복원
                                            dynamicKeys.forEach { cKey ->
                                                if (cineStreamData.has(cKey)) {
                                                    val cValue = cineStreamData.get(cKey)
                                                    try {
                                                        when (cValue) {
                                                            is Boolean -> CloudStreamApp.setKey(cKey, cValue)
                                                            is String -> CloudStreamApp.setKey(cKey, cValue)
                                                            is Int -> CloudStreamApp.setKey(cKey, cValue)
                                                        }
                                                    } catch (t: Throwable) {
                                                    }
                                                } else {
                                                    try { CloudStreamApp.setKey(cKey, null as String?) } catch (_: Throwable) {}
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    editor.clear()
                                    jsonToPrefs(parsedJson, editor)
                                    editor.commit()

                                    recordBackupSnapshot(sharedPref, "stremio_c_restore_complete", "stremio_c_last_applied_backup_hash", "native_backup_stremioc")
                                }
                            }
                            
                            delay(1500)
                            if (isActive && settingsDialog.isShowing) {
                                loadingDlg.dismiss()
                                dismiss()
                                restartApp(appContext)
                            }
                        }
                    }
                    dlg.show()

                } catch (e: Exception) {
                    e.printStackTrace()
                    showSavedPopup(ctx, "Invalid Backup Data",
                        "No valid backup data found in your clipboard.\n\nPlease copy the exact backup text and try again.")
                }
            }
        }

        val extraUrlContainer = view.findView<LinearLayout>("containerStreamSubtitleUrls")
        val extraUrlToggle = view.findView<ImageView>("toggleStreamSubtitleUrls")
        val urlControlSize = (40 * ctx.resources.displayMetrics.density + 0.5f).toInt()
        val urlFieldPadding = dp(12)
        fun addExtraUrlField(initialName: String = "", initialUrl: String = "") {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            fun newField(hintText: String, value: String, isUrl: Boolean) = EditText(ctx).apply {
                hint = hintText
                inputType = InputType.TYPE_CLASS_TEXT or
                    (if (isUrl) InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
                setText(value)
                textSize = 14f
                setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
                setHintTextColor(hostContext.colorFromAttribute(HostR.attr.grayTextColor))
                setPadding(urlFieldPadding, 0, urlFieldPadding, 0)
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = getCardBg()
                isSingleLine = true
            }
            val nameField = newField("Name", initialName, false)
            val urlField = newField("Addon URL", initialUrl, true)
            row.addView(nameField, LinearLayout.LayoutParams(0, dp(35), 0.85f))
            row.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#444444")) },
                LinearLayout.LayoutParams((ctx.resources.displayMetrics.density + 0.5f).toInt(), urlFieldPadding * 2))
            row.addView(urlField, LinearLayout.LayoutParams(0, dp(35), 2f))
            row.addView(TextView(ctx).apply {
                text = "−"
                textSize = 20f
                setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
                gravity = android.view.Gravity.CENTER
                contentDescription = "Remove addon URL"
                isClickable = true
                isFocusable = true
                val ripple = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener {
                    if (extraUrlContainer.childCount > 1) extraUrlContainer.removeView(row)
                    else { nameField.setText(""); urlField.setText("") }
                }
            }, LinearLayout.LayoutParams(urlControlSize, urlControlSize))
            row.addView(TextView(ctx).apply {
                text = "+"
                textSize = 20f
                setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
                gravity = android.view.Gravity.CENTER
                contentDescription = "Add addon URL"
                isClickable = true
                isFocusable = true
                val ripple = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener { addExtraUrlField() }
            }, LinearLayout.LayoutParams(urlControlSize, urlControlSize))
            normalizeTypeface(row)
            extraUrlContainer.addView(row)
        }
        val extraUrls = runCatching {
            JSONArray(sharedPref.getString(SettingsRepository.PREF_KEY_STREAM_SUBTITLE_URLS, "[]"))
        }.getOrNull()
        if (extraUrls == null || extraUrls.length() == 0) addExtraUrlField()
        else (0 until extraUrls.length()).forEach { i ->
            when (val entry = extraUrls.opt(i)) {
                is JSONObject -> addExtraUrlField(entry.optString("name"), entry.optString("url"))
                is String -> addExtraUrlField(initialUrl = entry) // Legacy URL-only settings
                else -> addExtraUrlField()
            }
        }
        fun setExtraUrlExpanded(expanded: Boolean) {
            extraUrlContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            extraUrlToggle.setImageDrawable(getDrawable(if (expanded) "addon_collapse_icon" else "addon_expand_icon"))
            extraUrlToggle.imageTintList = iconTint
            extraUrlToggle.contentDescription = if (expanded) "Collapse addon URLs" else "Expand addon URLs"
            sharedPref.edit().putBoolean(SettingsRepository.PREF_KEY_STREAM_SUBTITLE_EXPANDED, expanded).apply()
        }
        setExtraUrlExpanded(sharedPref.getBoolean(SettingsRepository.PREF_KEY_STREAM_SUBTITLE_EXPANDED, true))
        extraUrlToggle.setOnClickListener { setExtraUrlExpanded(extraUrlContainer.visibility != View.VISIBLE) }

        val layoutHistorySyncUrl: View = try { view.findView("layoutHistorySyncUrl") } catch (e: Throwable) { throw Exception("layoutHistorySyncUrl View not found", e) }
        val etHistorySyncUrl: EditText = try { view.findView("etHistorySyncUrl") } catch (e: Throwable) { throw Exception("etHistorySyncUrl EditText not found", e) }

        layoutHistorySyncUrl.visibility = View.VISIBLE
        etHistorySyncUrl.setText(sharedPref.getString(SettingsRepository.PREF_KEY_HISTORY_SYNC_URL, ""))

        setupToggle(view, "switchCineStream", SettingsRepository.PREF_KEY_CINESTREAM_ENABLED, isCineStreamInstalled(), "CineStream")
        setupToggle(view, "switchStreamPlay", SettingsRepository.PREF_KEY_STREAMPLAY_ENABLED, isStreamPlayInstalled(), "StreamPlay")
        setupToggle(view, "switchVixSrc", SettingsRepository.PREF_KEY_VIXSRC_ENABLED, true, "VixSrc")

        val btnSelectLanguage: ImageView = try { view.findView("btnSelectLanguage") } catch (e: Throwable) { throw Exception("btnSelectLanguage ImageView not found", e) }
        val switchCustomPoster: Switch = try { view.findView("switchCustomPoster") } catch (e: Throwable) { throw Exception("switchCustomPoster Switch not found", e) }
        val layoutCustomPosterUrl: View = try { view.findView("layoutCustomPosterUrl") } catch (e: Throwable) { throw Exception("layoutCustomPosterUrl View not found", e) }
        val etCustomPosterUrl: EditText = try { view.findView("etCustomPosterUrl") } catch (e: Throwable) { throw Exception("etCustomPosterUrl EditText not found", e) }

        btnSelectLanguage.setImageDrawable(getDrawable("settings_icon"))
        btnSelectLanguage.imageTintList = actionIconTint
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
            dialogView.setPadding(dp(20), dp(20), dp(20), dp(20))
            
            val rv: RecyclerView = dialogView.findView("rvLinks")
            rv.layoutManager = LinearLayoutManager(ctx)
            val tvNoLinks: TextView = dialogView.findView("tvNoLinks")
            // Measure the real row (including margins) so short lists have no empty viewport.
            val rowHeight by lazy {
                val row = inflater.inflate(res.getLayout(getCachedResId("item_saved_link", "layout")), rv, false)
                row.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        (ctx.resources.displayMetrics.widthPixels - dp(80)).coerceAtLeast(1),
                        View.MeasureSpec.EXACTLY
                    ),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val margins = row.layoutParams as? ViewGroup.MarginLayoutParams
                (row.measuredHeight + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)).coerceAtLeast(1)
            }
            fun resizeList(count: Int) {
                val cap = minOf(dp(450), (ctx.resources.displayMetrics.heightPixels * 0.62f).toInt()).coerceAtLeast(1)
                rv.layoutParams = rv.layoutParams.apply {
                    height = if (count == 0) 0 else minOf(cap.toLong(), rowHeight.toLong() * count).toInt()
                }
            }

            val list = SettingsRepository.loadLinks(sharedPref)
            resizeList(list.size)
            if (list.isEmpty()) {
                tvNoLinks.visibility = View.VISIBLE
                rv.visibility = View.GONE
            } else {
                tvNoLinks.visibility = View.GONE
                rv.visibility = View.VISIBLE

                val adapter = LinksAdapter(
                    items = list,
                    onDelete = { itemToDelete ->
                        val updatedList = SettingsRepository.loadLinks(sharedPref)
                        val removed = updatedList.removeAll { it.id == itemToDelete.id }

                        if (removed) {
                            SettingsRepository.saveLinks(sharedPref, updatedList)
                            (rv.adapter as? LinksAdapter)?.remove(itemToDelete)
                            resizeList(updatedList.size)
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
                            setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
                            setPadding(0, 0, 0, 30)
                            gravity = android.view.Gravity.CENTER
                        })

                        val optEdit = dialogButton(ctx, "Edit Link Info", LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 15) })
                        
                        val optCatalog = dialogButton(ctx, "Configure Catalogs", LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        
                        choiceContainer.addView(optEdit)
                        choiceContainer.addView(optCatalog)

                        val choiceDlg = AlertDialog.Builder(ctx).setView(choiceContainer).create()
                        setupCustomDialog(choiceDlg, choiceContainer)
                        
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
            val closeBtn = dialogButton(ctx, "Close", LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            footer.addView(closeBtn)
            dialogView.addView(footer)

            val dlg = AlertDialog.Builder(ctx).setView(dialogView).create()
            setupCustomDialog(dlg, dialogView)
            closeBtn.setOnClickListener { dlg.dismiss() }
            dlg.show()
        }

        saveIcon.setOnClickListener {
            val context = hostContext
            val tmdbApiKeyVal = etTmdbApiKey.text.toString().trim()
            if (tmdbApiKeyVal.isEmpty()) {
                showToast("TMDB API key is a required field")
                return@setOnClickListener
            }

            val switchCineStream = view.findView<Switch>("switchCineStream")
            val switchStreamPlay = view.findView<Switch>("switchStreamPlay")
            val switchVixSrc = view.findView<Switch>("switchVixSrc")

            val extraUrlList = (0 until extraUrlContainer.childCount).mapNotNull { i ->
                val row = extraUrlContainer.getChildAt(i) as LinearLayout
                val addonName = (row.getChildAt(0) as EditText).text.toString().trim()
                val addonUrl = (row.getChildAt(2) as EditText).text.toString().trim()
                addonUrl.takeIf { it.isNotBlank() }?.let { addonName to it }
            }.distinctBy { stremioManifestUrl(it.second) }
            if (extraUrlList.any { (_, url) ->
                    runCatching { val uri = url.toUri();
                        (uri.scheme?.lowercase() !in setOf("http", "https")) || uri.host.isNullOrBlank()
                    }.getOrDefault(true)
                }) {
                showToast("Invalid stream/subtitle addon URL")
                return@setOnClickListener
            }
            val editor = sharedPref.edit()
            editor.putString(SettingsRepository.PREF_KEY_TMDB_API_KEY, tmdbApiKeyVal)
            editor.putString(SettingsRepository.PREF_KEY_MDBLIST_API_KEY, etMdbListApiKey.text.toString().trim())
            editor.putString(SettingsRepository.PREF_KEY_STREAM_SUBTITLE_URLS, JSONArray().apply {
                extraUrlList.forEach { (addonName, addonUrl) ->
                    put(JSONObject().put("name", addonName).put("url", addonUrl))
                }
            }.toString())
            editor.putString(SettingsRepository.PREF_KEY_HISTORY_SYNC_URL, etHistorySyncUrl.text.toString().trim())
            editor.putString(SettingsRepository.PREF_KEY_CUSTOM_POSTER_URL, etCustomPosterUrl.text.toString().trim())
            
            editor.putBoolean(SettingsRepository.PREF_KEY_CUSTOM_POSTER_ENABLED, switchCustomPoster.isChecked)
            editor.putBoolean(SettingsRepository.PREF_KEY_MDBLIST_ENABLED, switchMdbList.isChecked)
            
            editor.putBoolean(SettingsRepository.PREF_KEY_CINESTREAM_ENABLED, switchCineStream.isChecked)
            editor.putBoolean(SettingsRepository.PREF_KEY_STREAMPLAY_ENABLED, switchStreamPlay.isChecked)
            editor.putBoolean(SettingsRepository.PREF_KEY_VIXSRC_ENABLED, switchVixSrc.isChecked)
            editor.commit()
            StremioC.invalidateSyncSettingsCache()

            recordBackupSnapshot(sharedPref, "stremio_c_restore_complete", "stremio_c_last_applied_backup_hash", "native_backup_stremioc")

            val streamPlayPrefs = context.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
            recordBackupSnapshot(streamPlayPrefs, "streamplay_restore_complete", "streamplay_last_applied_backup_hash", "native_backup_streamplay")

            val cancelBtn = dialogButton(context, "No", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) })
            val restartBtn = dialogButton(context, "Yes", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) })

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 40, 40, 40)
                background = getDarkThemeBg()
                addView(TextView(context).apply {
                    text = "Save & Reload"
                    textSize = 18f
                    setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, 20)
                    gravity = android.view.Gravity.CENTER
                })
                addView(TextView(context).apply {
                    text = "Changes have been saved. Do you want to restart the app to apply them?"
                    setTextColor(hostContext.colorFromAttribute(HostR.attr.grayTextColor))
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

            val dlg = AlertDialog.Builder(context).setView(container).create()
            setupCustomDialog(dlg, container)

            cancelBtn.setOnClickListener { dlg.dismiss() }
            restartBtn.setOnClickListener { dismiss(); restartApp(context.applicationContext) }
            dlg.show()
        }

        settingsDialog.setContentView(view)
        hostOwner?.lifecycle?.addObserver(closeOnDestroy)
        val metrics = hostContext.resources.displayMetrics
        val width = minOf((1000 * metrics.density).toInt(), (metrics.widthPixels * 0.9f).toInt()).coerceAtLeast(1)
        val maxHeight = (metrics.heightPixels * 0.88f).toInt().coerceAtLeast(1)
        val body = (view as? ViewGroup)?.getChildAt(0)
        // Measure BEFORE showing the dialog, so the initial frame is centered.
        body?.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val initialHeight = (body?.measuredHeight ?: maxHeight).coerceIn(1, maxHeight)
        settingsDialog.show()
        settingsDialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(android.view.Gravity.CENTER)
            setLayout(width, initialHeight)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        // When the URL section expands, resize on the layout pass rather than a posted
        // frame later (the latter caused a visible center jump).
        body?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val wanted = body.height.coerceIn(1, maxHeight)
            if (settingsDialog.isShowing && settingsDialog.window?.attributes?.height != wanted) {
                settingsDialog.window?.setLayout(width, wanted)
            }
        }
    }

    private fun showLinkEditorDialog(isEdit: Boolean, itemToEdit: LinkItem?, position: Int, rv: RecyclerView?) {
        val ctx = hostContext
        val inflater = LayoutInflater.from(ctx)
        val dialogView = getLayout("addlinks", inflater, null) as LinearLayout
        
        dialogView.background = getDarkThemeBg()
        dialogView.setPadding(dp(20), dp(20), dp(20), dp(20))
        
        val titleTv = dialogView.getChildAt(0) as? TextView
        if (titleTv != null) {
            titleTv.text = if (isEdit) "Edit Catalog" else "Add Catalogs"
            titleTv.setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
            titleTv.textSize = 18f
            titleTv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            titleTv.gravity = android.view.Gravity.START
        }
        
        val etName = try { dialogView.findView<EditText>("etName") } catch (t: Throwable) { null }
        val etLink = try { dialogView.findView<EditText>("etLink") } catch (t: Throwable) { null }
        val switchStreamSubtitleSync = try { dialogView.findView<Switch>("switchStreamSubtitleSync") } catch (_: Throwable) { null }
        val switchLinkSync = try { dialogView.findView<Switch>("switchLinkSync") } catch (t: Throwable) { null }
        val switchHistorySync = try { dialogView.findView<Switch>("switchHistorySync") } catch (t: Throwable) { null }
        
        etName?.background = getCardBg()
        etName?.setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
        etName?.setHintTextColor(hostContext.colorFromAttribute(HostR.attr.grayTextColor))
        etName?.setPadding(dp(12), 0, dp(12), 0) 
        if (isEdit) etName?.setText(itemToEdit?.name)
        
        etLink?.background = getCardBg()
        etLink?.setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
        etLink?.setHintTextColor(hostContext.colorFromAttribute(HostR.attr.grayTextColor))
        etLink?.setPadding(dp(12), 0, dp(12), 0) 
        if (isEdit) etLink?.setText(itemToEdit?.link)

        switchStreamSubtitleSync?.isChecked = itemToEdit?.isStreamSubtitleSyncEnabled ?: false
        switchLinkSync?.isChecked = itemToEdit?.isSyncEnabled ?: true
        switchHistorySync?.isChecked = itemToEdit?.isHistorySyncEnabled ?: true

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 30, 0, 0)
        }
        val saveBtn = dialogButton(ctx, if (isEdit) "Update" else "Save", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) })
        val cancelBtn = dialogButton(ctx, "Cancel", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) })
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
            val isExtraSync = switchStreamSubtitleSync?.isChecked ?: false
            val isSync = switchLinkSync?.isChecked ?: true
            val isHistorySync = switchHistorySync?.isChecked ?: true

            if (link.isEmpty()) { showToast("Please enter a link"); return@setOnClickListener }
            val valid = try { val uri = link.toUri(); val scheme = uri.scheme?.lowercase(); scheme == "http" || scheme == "https" } catch (_: Exception) { false }
            if (!valid) { showToast("Enter a valid URL (http/https)"); return@setOnClickListener }

            if (isEdit && itemToEdit != null) {
                val updatedList = SettingsRepository.loadLinks(sharedPref)
                val indexInDb = updatedList.indexOfFirst { it.id == itemToEdit.id }
                if (indexInDb != -1) {
                    val newItem = itemToEdit.copy(name = name.ifBlank { link }, link = link, type = type, isSyncEnabled = isSync, isHistorySyncEnabled = isHistorySync, isStreamSubtitleSyncEnabled = isExtraSync)
                    updatedList[indexInDb] = newItem
                    SettingsRepository.saveLinks(sharedPref, updatedList)
                    (rv?.adapter as? LinksAdapter)?.updateItem(newItem, position)
                    showToast("Link Updated Successfully")
                }
            } else {
                val item = LinkItem(name = name.ifBlank { link }, link = link, type = type, isSyncEnabled = isSync, isHistorySyncEnabled = isHistorySync, isStreamSubtitleSyncEnabled = isExtraSync)
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
        val context = hostContext
        val currentSavedCode = sharedPref.getString(SettingsRepository.PREF_KEY_TMDB_LANG_CODE, "en-US") ?: "en-US"
        
        var selectedCode = currentSavedCode
        var selectedLangName = LANGUAGES.find { it.second == currentSavedCode }?.first ?: "United States (English)"

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = getDarkThemeBg()
        }

        container.addView(TextView(context).apply {
            text = "TMDB Language"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
            setPadding(0, 0, 0, dp(20))
            gravity = android.view.Gravity.START
        })

        val searchBar = EditText(context).apply {
            hint = "Search language..."
            setHintTextColor(hostContext.colorFromAttribute(HostR.attr.grayTextColor))
            setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
            textSize = 14f
            setPadding(dp(12), 0, dp(12), 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
            isSingleLine = true
            background = getCardBg()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(35)).apply {
                setMargins(0, 0, 0, 20)
            }
        }
        container.addView(searchBar)

        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(dp(380), (context.resources.displayMetrics.heightPixels * 0.50f).toInt()))
        }
        container.addView(rv)

        // 언어 목록 어댑터
        class LangAdapter(
            private val originalList: List<Pair<String, String>>,
            var selectedCode: String, // 선택 상상 추적을 위해 var로 변경
            private val onClick: (String, String) -> Unit
        ) : RecyclerView.Adapter<LangAdapter.VH>() {

            private var filteredList = originalList.toMutableList()

            inner class VH(val view: LinearLayout, val tvName: TextView, val tvCheck: ImageView) : RecyclerView.ViewHolder(view)

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val rowLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    isClickable = true
                    focusable = View.FOCUSABLE
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                // 가로 전체를 차지하여 체크 아이콘을 우측 끝으로 밀어냅니다.
                val tvName = TextView(context).apply {
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvCheck = ImageView(context).apply {
                    setImageDrawable(getDrawable("addon_check_icon"))
                    imageTintList = ColorStateList.valueOf(hostContext.colorFromAttribute(HostR.attr.colorPrimary))
                    contentDescription = "Selected language"
                    setPadding(dp(10), 0, dp(10), 0)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(24))
                }
                rowLayout.addView(tvName)
                rowLayout.addView(tvCheck)
                normalizeTypeface(rowLayout)
                return VH(rowLayout, tvName, tvCheck)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                val (langName, code) = filteredList[position]
                val isSelected = code == selectedCode
                holder.tvName.text = langName
                
                holder.tvName.setTextColor(hostContext.colorFromAttribute(if (isSelected) HostR.attr.colorPrimary else HostR.attr.textColor))
                holder.view.background = if (isSelected) getCardBg() else ColorDrawable(Color.TRANSPARENT) 
                holder.tvCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                
                holder.view.setOnClickListener {
                    onClick(code, langName)
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

        // 어댑터 생성 및 연결 (항목 클릭 시 UI만 즉각 업데이트)
        lateinit var adapter: LangAdapter
        adapter = LangAdapter(LANGUAGES, selectedCode) { code, langName ->
            selectedCode = code
            selectedLangName = langName
            adapter.selectedCode = code
            adapter.notifyDataSetChanged() // 화면 갱신
        }
        rv.adapter = adapter

        searchBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = uiScope.launch {
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
        val applyBtn = dialogButton(context, "Apply", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) })
        val cancelBtn = dialogButton(context, "Cancel", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) })
        footer.addView(cancelBtn)
        footer.addView(applyBtn)
        container.addView(footer)

        val langDialog = AlertDialog.Builder(context).setView(container).create()
        setupCustomDialog(langDialog, container)

        // Apply 버튼 클릭 시에만 SharedPreferences에 값을 저장하고 토스트 메시지를 표시한 후 창을 닫습니다.
        applyBtn.setOnClickListener {
            val code = selectedCode
            val langName = selectedLangName

            val editor = sharedPref.edit()
            editor.putString(SettingsRepository.PREF_KEY_TMDB_LANG_CODE, code)
            editor.commit()

            Toast.makeText(context, "Language set to $code ($langName)", Toast.LENGTH_SHORT).show()
            langDialog.dismiss()
        }
        cancelBtn.setOnClickListener { langDialog.dismiss() }
        langDialog.show()
    }

    private fun showCatalogConfigDialog(item: LinkItem) {
        val context = hostContext
        migrateStremioCatalogSettings(sharedPref, item.link)
        val uniqueId = stremioCatalogKey(item.link) 
        
        val progressContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = getDarkThemeBg()
            addView(TextView(context).apply {
                text = "Loading Stremio Addon Manifest..."
                setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
                textSize = 16f
                gravity = android.view.Gravity.CENTER
            })
        }
        val progressDialog = AlertDialog.Builder(context).setView(progressContainer).setCancelable(false).create()
        setupCustomDialog(progressDialog, progressContainer)
        progressDialog.show()

        uiScope.launch {
            val currentUrl = stremioManifestUrl(item.link)

            val manifestText = withContext(Dispatchers.IO) {
                try { 
                    // 실제 manifest URL로 요청
                    com.lagradost.cloudstream3.app.get(currentUrl, timeout = 10L).text 
                } catch (e: Exception) { 
                    e.printStackTrace(); 
                    null 
                }
            }
            progressDialog.dismiss()

            if (manifestText.isNullOrBlank()) {
                // 토스트 대신 기존 테마 맞춤형 팝업(showSavedPopup) 표시
                showSavedPopup(context, "Error", "Failed to load manifest. Check link.")
                return@launch
            }

            val manifestPrefs = context.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)

            val flatTabs = mutableListOf<ConfigFlatTab>()
            try {
                val manifest = parseJson<StremioC.Manifest>(manifestText)
                val catalogs = manifest.catalogs
                manifestPrefs.edit().putString(stremioCatalogKey(item.link), manifestText).apply()

                if (catalogs != null) {
                    for (cat in catalogs) {
                        val id = cat.id ?: ""
                        val type = cat.type ?: "movie"
                        val name = cat.name ?: id
                        val displayType = type.replaceFirstChar { it.uppercase() }
                        
                        flatTabs.add(ConfigFlatTab(
                            key = "${id}_${type}_default", 
                            displayName = "$name - $displayType", 
                            isChecked = sharedPref.getBoolean("tab_enabled_${uniqueId}_${id}_${type}_default", true)
                        ))
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            if (flatTabs.isEmpty()) { 
                // 3. 토스트 대신 기존 테마 맞춤형 팝업(showSavedPopup) 표시
                showSavedPopup(context, "Error", "No catalogs found in manifest.")
                return@launch 
            }
            displayCatalogListDialog(item.name, flatTabs, item.link)
        }
    }

    private fun displayCatalogListDialog(addonName: String, flatTabs: List<ConfigFlatTab>, linkUrl: String) {
        val context = hostContext
        val prefs = sharedPref
        migrateStremioCatalogSettings(prefs, linkUrl)
        val uniqueId = stremioCatalogKey(linkUrl)
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
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
            setPadding(0, 0, 0, 30)
            gravity = android.view.Gravity.START
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
                visibleTabs = masterTabs.toMutableList()
                
                if (rv.isComputingLayout) {
                    rv.post { notifyDataSetChanged() }
                } else {
                    notifyDataSetChanged()
                }
            }

            inner class VH(val view: LinearLayout, val checkbox: CheckBox, val etOrder: EditText, val dragHandle: ImageView) : RecyclerView.ViewHolder(view)

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
                    setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
                    textSize = 14f
                    setPadding(dp(8), 0, dp(8), 0)
                    background = getCardBg()
                    gravity = android.view.Gravity.CENTER
                    isSingleLine = true
                    layoutParams = LinearLayout.LayoutParams(90, dp(35)).apply {
                        setMargins(0, 0, 15, 0)
                    }
                    setOnClickListener {
                        requestFocus()
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                
                val checkbox = CheckBox(context).apply {
                    textSize = 14f
                    setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                layout.addView(dragHandle)
                layout.addView(etOrder)
                layout.addView(checkbox)
                normalizeTypeface(layout)
                return VH(layout, checkbox, etOrder, dragHandle)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                val tab = visibleTabs[position]
                
                holder.checkbox.setOnCheckedChangeListener(null)
                holder.checkbox.isChecked = tab.isChecked
                holder.checkbox.text = tab.displayName
                val itemColor = hostContext.colorFromAttribute(if (tab.isChecked) HostR.attr.colorPrimary else HostR.attr.textColor)
                holder.checkbox.setTextColor(itemColor)
                holder.checkbox.buttonTintList = ColorStateList.valueOf(itemColor)
                holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                    tab.isChecked = isChecked 
                    
                    val checkedTabs = masterTabs.filter { it.isChecked }
                    val uncheckedTabs = masterTabs.filter { !it.isChecked }
                    masterTabs.clear()
                    masterTabs.addAll(checkedTabs)
                    masterTabs.addAll(uncheckedTabs)
                    updateVisibleTabs()
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
        val cancelBtn = dialogButton(context, "Cancel", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 15, 0) })
        val saveBtn = dialogButton(context, "Save", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15, 0, 0, 0) })
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
            }
            // Preserve dormant genre/removed-catalog order keys for compatibility; never clear their settings.
            val currentKeys = masterTabs.map { it.key }.toSet()
            orderedKeys.filterNot { it in currentKeys }.forEach { keysToSave.put(it) }
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

    private fun restartApp(appContext: Context) {
        val packageManager = appContext.packageManager
        val intent = packageManager.getLaunchIntentForPackage(appContext.packageName)
        val componentName = intent?.component

        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            appContext.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }

    inner class LinksAdapter(
        private val items: MutableList<LinkItem>,
        private val onDelete: (LinkItem) -> Unit,
        private val onEdit: (LinkItem, Int) -> Unit
    ) : RecyclerView.Adapter<LinksAdapter.VH>() {

        inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findView("tvName")
            val tvLink: TextView = view.findView("tvLink")
            val tvType: TextView = view.findView("tvType")
            val tvSyncBadge: TextView = view.findView("tvSyncBadge")
            val btnDelete: ImageButton = view.findView("btnDelete")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layoutId = getCachedResId("item_saved_link", "layout")
            val v = LayoutInflater.from(parent.context).inflate(res.getLayout(layoutId), parent, false)
            v.background = getCardBg()
            normalizeTypeface(v)
            themeLayoutText(v)
            return VH(v).also {
                it.btnDelete.setImageDrawable(getDrawable("delete_icon"))
                it.btnDelete.imageTintList = ColorStateList.valueOf(Color.rgb(229, 9, 20))
            }
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvLink.text = item.link
            holder.tvType.text = item.type
            holder.tvSyncBadge.visibility = if (item.isSyncEnabled || item.isHistorySyncEnabled || item.isStreamSubtitleSyncEnabled) View.VISIBLE else View.GONE
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
