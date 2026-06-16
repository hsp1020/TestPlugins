// 파일명: hsp1020/settings/SettingsBottomFragment.kt
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
import android.app.AlertDialog // ◀️ androidx.appcompat.app.AlertDialog 완전 폐기 및 네이티브 플랫폼 적용
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity.showToast
import org.json.JSONArray
import org.json.JSONObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
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
    private val plugin: StremioXPlugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")
    
    private val resourceCache = mutableMapOf<String, Int>()
    private fun getCachedResId(name: String, type: String): Int {
        return resourceCache.getOrPut("$type/$name") {
            res.getIdentifier(name, type, BuildConfig.LIBRARY_PACKAGE_NAME)
        }
    }

    private data class ConfigFlatTab(val key: String, val displayName: String, val isGenre: Boolean, val catalogKey: String)

    // 🔥 [UltimaEvo 스타일 디자인 통합 헬퍼]
    private fun getDarkThemeBg() = GradientDrawable().apply { setColor(Color.parseColor("#000000")); cornerRadius = 40f }
    private fun getCardBg() = GradientDrawable().apply { setColor(Color.parseColor("#0C0C0C")); setStroke(2, Color.parseColor("#1A1A1A")); cornerRadius = 30f }
    private fun getButtonBg() = GradientDrawable().apply { setColor(Color.parseColor("#151515")); cornerRadius = 20f }

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

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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
            val ctx = requireContext()
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val backupStr = withContext(Dispatchers.IO) {
                        val json = JSONObject()
                        sharedPref.all.forEach { (key, value) -> json.put(key, value) }
                        val jsonString = json.toString()
                        val bos = java.io.ByteArrayOutputStream()
                        java.util.zip.GZIPOutputStream(bos).use { it.write(jsonString.toByteArray(Charsets.UTF_8)) }
                        Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                    }

                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("StremioX Backup", backupStr)
                    clipboard.setPrimaryClip(clip)

                    AlertDialog.Builder(ctx)
                        .setTitle("Export Successful")
                        .setMessage("Compressed backup data has been successfully copied to your clipboard.\n\nPlease paste it somewhere safe (e.g., Notes, Messenger).")
                        .setPositiveButton("OK", null)
                        .show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(ctx, "Failed to export settings", Toast.LENGTH_SHORT).show()
                }
            }
        }

        tvImport.setOnClickListener {
            val ctx = requireContext()
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

                    AlertDialog.Builder(ctx)
                        .setTitle("Backup Data Detected")
                        .setMessage("Valid backup data found in clipboard!\n\nAre you sure you want to proceed? (Existing unrelated settings will be kept safely)")
                        .setPositiveButton("Restore") { _, _ ->
                            val editor = sharedPref.edit()
                            
                            val keys = parsedJson.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                when (val value = parsedJson.get(key)) {
                                    is Boolean -> editor.putBoolean(key, value)
                                    is String -> editor.putString(key, value)
                                    is Int -> editor.putInt(key, value)
                                }
                            }
                            editor.apply()

                            AlertDialog.Builder(ctx)
                                .setTitle("Import Result")
                                .setMessage("Restore completed successfully!\nPlease restart the app.")
                                .setPositiveButton("OK") { _, _ -> dismiss() }
                                .show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()

                } catch (e: Exception) {
                    e.printStackTrace()
                    AlertDialog.Builder(ctx)
                        .setTitle("Invalid Backup Data")
                        .setMessage("No valid backup data found in your clipboard.\n\nPlease copy the exact backup text and try again.")
                        .setPositiveButton("OK", null)
                        .show()
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
            sharedPref.edit { putBoolean(SettingsRepository.PREF_KEY_HISTORY_SYNC_ENABLED, isChecked) }
            layoutHistorySyncUrl.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        etHistorySyncUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                sharedPref.edit { putString(SettingsRepository.PREF_KEY_HISTORY_SYNC_URL, etHistorySyncUrl.text.toString().trim()) }
            }
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
            sharedPref.edit { putBoolean(SettingsRepository.PREF_KEY_CUSTOM_POSTER_ENABLED, isChecked) }
            layoutCustomPosterUrl.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnSelectLanguage.setOnClickListener {
            showLanguageSelectDialog()
        }

        etCustomPosterUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                sharedPref.edit { putString(SettingsRepository.PREF_KEY_CUSTOM_POSTER_URL, etCustomPosterUrl.text.toString().trim()) }
            }
        }

        val etTmdbApiKey: EditText = try { view.findView("etTmdbApiKey") } catch (e: Throwable) { throw Exception("etTmdbApiKey EditText not found", e) }
        val switchMdbList: Switch = try { view.findView("switchMdbList") } catch (e: Throwable) { throw Exception("switchMdbList Switch not found", e) }
        val layoutMdbList: View = try { view.findView("layoutMdbList") } catch (e: Throwable) { throw Exception("layoutMdbList View not found", e) }
        val etMdbListApiKey: EditText = try { view.findView("etMdbListApiKey") } catch (e: Throwable) { throw Exception("etMdbListApiKey EditText not found", e) }

        etTmdbApiKey.setText(sharedPref.getString(SettingsRepository.PREF_KEY_TMDB_API_KEY, ""))
        etTmdbApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                sharedPref.edit { putString(SettingsRepository.PREF_KEY_TMDB_API_KEY, etTmdbApiKey.text.toString().trim()) }
            }
        }

        val isMdbListEnabled = sharedPref.getBoolean(SettingsRepository.PREF_KEY_MDBLIST_ENABLED, false)
        switchMdbList.isChecked = isMdbListEnabled
        layoutMdbList.visibility = if (isMdbListEnabled) View.VISIBLE else View.GONE
        etMdbListApiKey.setText(sharedPref.getString(SettingsRepository.PREF_KEY_MDBLIST_API_KEY, ""))

        switchMdbList.setOnCheckedChangeListener { _, isChecked ->
            sharedPref.edit { putBoolean(SettingsRepository.PREF_KEY_MDBLIST_ENABLED, isChecked) }
            layoutMdbList.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        etMdbListApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                sharedPref.edit { putString(SettingsRepository.PREF_KEY_MDBLIST_API_KEY, etMdbListApiKey.text.toString().trim()) }
            }
        }

        addlinks.setOnClickListener {
            showLinkEditorDialog(false, null, -1, null)
        }

        showlinks.setOnClickListener {
            val ctx = requireContext()
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 40, 40, 40)
                background = getDarkThemeBg()
            }
            
            container.addView(TextView(ctx).apply {
                text = "Saved Links"
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 30)
                gravity = android.view.Gravity.CENTER
            })

            val rv = RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(120)
            }
            
            val tvNoLinks = TextView(ctx).apply {
                text = "No saved links"
                setTextColor(Color.GRAY)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400)
            }

            val list = SettingsRepository.loadLinks(sharedPref)
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
                            showToast("Deleted")
                            if (updatedList.isEmpty()) {
                                tvNoLinks.visibility = View.VISIBLE
                                rv.visibility = View.GONE
                            }
                        }
                    },
                    onEdit = { itemToEdit, position ->
                        val choices = arrayOf("Edit Link Info", "Configure Catalogs & Genres")
                        AlertDialog.Builder(ctx)
                            .setTitle(itemToEdit.name)
                            .setItems(choices) { _, which ->
                                if (which == 0) showLinkEditorDialog(true, itemToEdit, position, rv)
                                else showCatalogConfigDialog(itemToEdit)
                            }.show()
                    }
                )
                rv.adapter = adapter
            }
            
            container.addView(tvNoLinks)
            container.addView(rv)

            val footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 30, 0, 0) }
            val closeBtn = Button(ctx).apply {
                text = "Close"
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = getButtonBg()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            footer.addView(closeBtn)
            container.addView(footer)

            val dlg = AlertDialog.Builder(ctx).setView(container).create()
            dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
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

            sharedPref.edit {
                putString(SettingsRepository.PREF_KEY_TMDB_API_KEY, tmdbApiKeyVal)
                putString(SettingsRepository.PREF_KEY_MDBLIST_API_KEY, etMdbListApiKey.text.toString().trim())
                putString(SettingsRepository.PREF_KEY_HISTORY_SYNC_URL, etHistorySyncUrl.text.toString().trim())
                putString(SettingsRepository.PREF_KEY_CUSTOM_POSTER_URL, etCustomPosterUrl.text.toString().trim())
            }

            AlertDialog.Builder(context)
                .setTitle("Save & Reload")
                .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                .setPositiveButton("Yes") { _, _ -> dismiss(); restartApp() }
                .setNegativeButton("No", null)
                .show()
        }

        return view
    }

    private fun showLinkEditorDialog(isEdit: Boolean, itemToEdit: LinkItem?, position: Int, rv: RecyclerView?) {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = getDarkThemeBg()
        }
        
        container.addView(TextView(ctx).apply {
            text = if(isEdit) "Edit Link" else "Add Link"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 30)
            gravity = android.view.Gravity.CENTER
        })

        val etName = EditText(ctx).apply {
            hint = "Name (Optional)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setPadding(30, 30, 30, 30)
            background = getButtonBg()
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
            if (isEdit) setText(itemToEdit?.name)
        }
        container.addView(etName)

        val etLink = EditText(ctx).apply {
            hint = "URL (http/https)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setPadding(30, 30, 30, 30)
            background = getButtonBg()
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 30) }
            if (isEdit) setText(itemToEdit?.link)
        }
        container.addView(etLink)

        val footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val saveBtn = Button(ctx).apply {
            text = if(isEdit) "Update" else "Save"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,15,0) }
        }
        val cancelBtn = Button(ctx).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15,0,0,0) }
        }
        footer.addView(saveBtn); footer.addView(cancelBtn)
        container.addView(footer)

        val dlg = AlertDialog.Builder(ctx).setView(container).create()
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        
        dlg.setOnShowListener {
            dlg.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        saveBtn.setOnClickListener {
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
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 30)
            gravity = android.view.Gravity.CENTER
        })

        val searchBar = EditText(context).apply {
            hint = "Search language..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(30, 30, 30, 30)
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 20)
            }
        }
        container.addView(searchBar)

        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(120)
        }
        container.addView(rv)

        class LangAdapter : RecyclerView.Adapter<LangAdapter.VH>() {
            var filteredList = languages

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
                    setTextColor(Color.parseColor("#2196F3"))
                    setPadding(10, 0, 10, 0)
                }
                rowLayout.addView(tvName); rowLayout.addView(tvCheck)
                return VH(rowLayout, tvName, tvCheck)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                val (langName, code) = filteredList[position]
                val isSelected = code == selectedCode
                holder.tvName.text = langName
                holder.tvName.setTextColor(if (isSelected) Color.parseColor("#2196F3") else Color.WHITE)
                holder.tvCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                
                holder.view.setOnClickListener {
                    selectedCode = code
                    selectedLangName = langName
                    notifyDataSetChanged()
                }
            }
            override fun getItemCount() = filteredList.size
        }

        val adapter = LangAdapter()
        rv.adapter = adapter

        searchBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim().lowercase()
                adapter.filteredList = if (query.isEmpty()) languages else languages.filter { it.first.lowercase().contains(query) }
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 30, 0, 0) }
        val applyBtn = Button(context).apply {
            text = "Apply"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,15,0) }
        }
        val cancelBtn = Button(context).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(15,0,0,0) }
        }
        footer.addView(applyBtn); footer.addView(cancelBtn)
        container.addView(footer)

        val langDialog = AlertDialog.Builder(context).setView(container).create()
        langDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        langDialog.setOnShowListener {
            langDialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            langDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        applyBtn.setOnClickListener {
            sharedPref.edit { putString(SettingsRepository.PREF_KEY_TMDB_LANG_CODE, selectedCode) }
            Toast.makeText(context, "Language set to $selectedCode ($selectedLangName)", Toast.LENGTH_SHORT).show()
            langDialog.dismiss()
        }
        cancelBtn.setOnClickListener { langDialog.dismiss() }
        
        langDialog.show()
    }

    private fun showCatalogConfigDialog(item: LinkItem) {
        val context = requireContext()
        val progressDialog = AlertDialog.Builder(context).setMessage("Loading Stremio Addon Manifest...").setCancelable(false).create()
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
            val manifestPrefs = context.getSharedPreferences("StremioX_Manifest", Context.MODE_PRIVATE)
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

                        flatTabs.add(ConfigFlatTab("${id}_${type}_default", "$name - $displayType", false, "${id}_${type}"))

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
                            flatTabs.add(ConfigFlatTab("${id}_${type}_genre_$genre", "$name : $capitalizedGenre  - $displayType", true, "${id}_${type}"))
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
        val checkedInit = sortedTabs.filter { prefs.getBoolean("tab_enabled_${uniqueId}_${it.key}", true) }
        val uncheckedInit = sortedTabs.filter { !prefs.getBoolean("tab_enabled_${uniqueId}_${it.key}", true) }
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
                    if (tab.isGenre) prefs.getBoolean("genres_enabled_${uniqueId}_${tab.catalogKey}", true) else true
                }.toMutableList()
                notifyDataSetChanged()
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
                val enabledKey = "tab_enabled_${uniqueId}_${tab.key}"
                
                holder.checkbox.setOnCheckedChangeListener(null)
                holder.checkbox.isChecked = prefs.getBoolean(enabledKey, true)
                holder.checkbox.text = tab.displayName
                holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean(enabledKey, isChecked).apply()
                    val checkedTabs = masterTabs.filter { prefs.getBoolean("tab_enabled_${uniqueId}_${it.key}", true) }
                    val uncheckedTabs = masterTabs.filter { !prefs.getBoolean("tab_enabled_${uniqueId}_${it.key}", true) }
                    masterTabs.clear()
                    masterTabs.addAll(checkedTabs)
                    masterTabs.addAll(uncheckedTabs)
                    updateVisibleTabs()
                }

                if (!tab.isGenre && masterTabs.any { it.isGenre && it.catalogKey == tab.catalogKey }) {
                    holder.genreSwitch.visibility = View.VISIBLE
                    val genresEnabledKey = "genres_enabled_${uniqueId}_${tab.catalogKey}"
                    holder.genreSwitch.setOnCheckedChangeListener(null)
                    holder.genreSwitch.isChecked = prefs.getBoolean(genresEnabledKey, true)
                    holder.genreSwitch.setOnCheckedChangeListener { _, isChecked ->
                        prefs.edit().putBoolean(genresEnabledKey, isChecked).apply()
                        updateVisibleTabs()
                    }
                } else {
                    holder.genreSwitch.visibility = View.GONE
                }
                
                holder.etOrder.setOnFocusChangeListener(null)
                holder.etOrder.setText((position + 1).toString())
                
                holder.etOrder.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        rv.postDelayed({
                            val curPos = holder.adapterPosition
                            if (curPos != RecyclerView.NO_POSITION) {
                                rv.smoothScrollToPosition(curPos)
                            }
                        }, 150)
                    } else {
                        val inputVal = holder.etOrder.text.toString().toIntOrNull()
                        if (inputVal != null) {
                            shiftItemInRecyclerView(position, inputVal)
                        } else {
                            holder.etOrder.setText((position + 1).toString())
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
                    
                    val isEnabled = prefs.getBoolean("tab_enabled_${uniqueId}_${item.key}", true)
                    val checkedCount = visibleTabs.count { prefs.getBoolean("tab_enabled_${uniqueId}_${it.key}", true) }
                    
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
                
                val movedTab = adapter.visibleTabs[fromPos]
                Collections.swap(adapter.visibleTabs, fromPos, toPos)
                
                adapter.notifyItemMoved(fromPos, toPos)
                adapter.notifyItemChanged(fromPos)
                adapter.notifyItemChanged(toPos)

                val masterFrom = masterTabs.indexOf(movedTab)
                val targetTab = adapter.visibleTabs[fromPos]
                val masterTo = masterTabs.indexOf(targetTab)
                
                if (masterFrom != -1 && masterTo != -1) {
                    masterTabs.removeAt(masterFrom)
                    masterTabs.add(masterTo, movedTab)
                }
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(rv)

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }
        val saveBtn = Button(context).apply {
            text = "Save"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 15, 0)
            }
        }
        val cancelBtn = Button(context).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(15, 0, 0, 0)
            }
        }
        footer.addView(saveBtn)
        footer.addView(cancelBtn)
        dialogContainer.addView(footer)

        val dialog = AlertDialog.Builder(context).setView(dialogContainer).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.setOnShowListener {
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        saveBtn.setOnClickListener {
            val keysToSave = JSONArray()
            masterTabs.forEach { keysToSave.put(it.key) }
            prefs.edit().putString(orderKey, keysToSave.toString()).apply()
            Toast.makeText(context, "Catalog settings saved. Reload to apply.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
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
                SettingsRepository.setIntegrationEnabled(sharedPref, prefKey, isChecked)
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
