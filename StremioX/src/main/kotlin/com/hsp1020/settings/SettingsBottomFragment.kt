// 파일명: hsp1020/settings/SettingsBottomFragment.kt
package com.hsp1020

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.CheckBox
import android.widget.Switch
import android.widget.FrameLayout
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Base64
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity.showToast
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// ==========================================
// Data & Repository Layer
// ==========================================
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
    
    // TMDB 다국어 및 추천목록 포스터 설정 키 정의
    const val PREF_KEY_TMDB_LANG_CODE = "tmdb_language_code"
    const val PREF_KEY_CUSTOM_POSTER_ENABLED = "custom_poster_enabled"
    const val PREF_KEY_CUSTOM_POSTER_URL = "custom_poster_url"

    // TMDB API 및 MdbList 정밀 연동 제어 키 정의
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
            Log.e("SettingsRepository", "Failed to parse links: ${e.message}")
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

// ==========================================
// UI Layer
// ==========================================
class SettingsBottomFragment(
    private val plugin: StremioXPlugin, // 플러그인 인자 전적 프로퍼티 바인딩
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    private data class ConfigFlatTab(val key: String, val displayName: String, val isGenre: Boolean, val catalogKey: String)

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
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getDrawable(id, null) ?: throw Exception("Drawable $name not found")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) throw Exception("View ID $name not found.")
        return this.findViewById(id)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        this.background = res.getDrawable(outlineId, null)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
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

        val addlinks: ImageView = try { view.findView("addlinks") } catch (e: Throwable) {
            throw Exception("addlinks ImageView not found in bottom_sheet_layout", e)
        }
        val showlinks: ImageView = try { view.findView("showlinks") } catch (e: Throwable) {
            throw Exception("showlinks ImageView not found in bottom_sheet_layout", e)
        }
        val saveIcon: ImageView = try { view.findView("saveIcon") } catch (e: Throwable) {
            throw Exception("saveIcon ImageView not found in bottom_sheet_layout", e)
        }

        addlinks.setImageDrawable(getDrawable("settings_icon"))
        showlinks.setImageDrawable(getDrawable("settings_icon"))
        saveIcon.setImageDrawable(getDrawable("save_icon"))

        addlinks.makeTvCompatible()
        showlinks.makeTvCompatible()
        saveIcon.makeTvCompatible()

        val tvExport: TextView = try { view.findView("tvExport") } catch (e: Throwable) {
            throw Exception("tvExport TextView not found in bottom_sheet_layout", e)
        }
        val tvImport: TextView = try { view.findView("tvImport") } catch (e: Throwable) {
            throw Exception("tvImport TextView not found in bottom_sheet_layout", e)
        }

        tvExport.makeTvCompatible()
        tvImport.makeTvCompatible()

        // 백업(Export) 64비트 문자열 처리 로직 적용
        // [최적화 완료]: 백업(Export) 비동기 처리 및 자동 클립보드 복사 (앱 크래시 원천 차단)
        // [v2.4.1 최적화]: 클립보드 다이렉트 스캔 및 100% 사전 유효성 검증(Pre-validation) 도입
        // [v2.4.2 완벽 수정본]: 코루틴 및 불필요한 압축 제거. 렉의 원인이었던 EditText 렌더링만 걷어내고 다이렉트 클립보드 동기식 입출력 적용
        tvExport.setOnClickListener {
            val ctx = requireContext()
            try {
                val json = JSONObject()
                sharedPref.all.forEach { (key, value) -> json.put(key, value) }
                
                // 1. 설정 데이터를 Base64 문자열로 즉시 인코딩 (메모리상 연산이라 0.01초 만에 완료됨)
                val backupStr = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

                // 2. 무거운 화면(EditText)에 띄우지 않고 곧바로 기기 클립보드에 삽입
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("StremioX Backup", backupStr)
                clipboard.setPrimaryClip(clip)

                // 3. 완료 팝업 호출
                AlertDialog.Builder(ctx)
                    .setTitle("Export Successful")
                    .setMessage("Backup data has been successfully copied to your clipboard.\n\nPlease paste it somewhere safe (e.g., Notes, Messenger).")
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Failed to export settings", Toast.LENGTH_SHORT).show()
            }
        }

        // [v2.4.2 완벽 수정본]: 클립보드 스캔 및 즉시 복원 (사전 검증 포함)
        tvImport.setOnClickListener {
            val ctx = requireContext()
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            
            // 1. 클립보드에서 텍스트 읽어오기
            val clipData = clipboard.primaryClip
            val clipboardText = if (clipData != null && clipData.itemCount > 0) {
                clipData.getItemAt(0).text?.toString()?.trim() ?: ""
            } else ""

            if (clipboardText.isEmpty()) {
                Toast.makeText(ctx, "Clipboard is empty. Copy the backup data first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            try {
                // 2. 덮어쓰기 전, 백업 코드가 유효한지 사전 파싱 테스트
                val decodedBytes = Base64.decode(clipboardText, Base64.NO_WRAP)
                val jsonString = String(decodedBytes, Charsets.UTF_8)
                val parsedJson = JSONObject(jsonString)
                
                if (parsedJson.length() == 0) throw Exception("Empty JSON Data")

                // 3. 정상 데이터임이 확인되면 복원 팝업 호출
                AlertDialog.Builder(ctx)
                    .setTitle("Backup Data Detected")
                    .setMessage("Valid backup data found in clipboard!\n\nWarning: Restoring will permanently overwrite all current settings.\nAre you sure you want to proceed?")
                    .setPositiveButton("Restore") { _, _ ->
                        val editor = sharedPref.edit()
                        editor.clear() // 새 데이터로 덮어쓰기 위해 기존 설정 초기화
                        
                        val keys = parsedJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            when (val value = parsedJson.get(key)) {
                                is Boolean -> editor.putBoolean(key, value)
                                is String -> editor.putString(key, value)
                                is Int -> editor.putInt(key, value)
                            }
                        }
                        editor.apply() // 안전한 동기화

                        AlertDialog.Builder(ctx)
                            .setTitle("Import Result")
                            .setMessage("Restore completed successfully!\nPlease restart the app.")
                            .setPositiveButton("OK") { _, _ -> dismiss() }
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

            } catch (e: Exception) {
                // 클립보드에 이상한 텍스트나 오류 데이터가 있을 경우 철벽 방어
                AlertDialog.Builder(ctx)
                    .setTitle("Invalid Backup Data")
                    .setMessage("No valid backup data found in your clipboard.\n\nPlease copy the exact backup text and try again.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }   

        // 기록 연동 동적 뷰 처리 연동
        val switchHistorySync: Switch = try { view.findView("switchHistorySync") } catch (e: Throwable) {
            throw Exception("switchHistorySync Switch not found in bottom_sheet_layout", e)
        }
        val layoutHistorySyncUrl: View = try { view.findView("layoutHistorySyncUrl") } catch (e: Throwable) {
            throw Exception("layoutHistorySyncUrl View not found in bottom_sheet_layout", e)
        }
        val etHistorySyncUrl: EditText = try { view.findView("etHistorySyncUrl") } catch (e: Throwable) {
            throw Exception("etHistorySyncUrl EditText not found in bottom_sheet_layout", e)
        }

        // 기록 연동 초기 상태 정의
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

        // UI Initialization: Toggles
        setupToggle(view, "switchCineStream", SettingsRepository.PREF_KEY_CINESTREAM_ENABLED, isCineStreamInstalled(), "CineStream")
        setupToggle(view, "switchStreamPlay", SettingsRepository.PREF_KEY_STREAMPLAY_ENABLED, isStreamPlayInstalled(), "StreamPlay")
        setupToggle(view, "switchVixSrc", SettingsRepository.PREF_KEY_VIXSRC_ENABLED, true, "VixSrc")

        // 다국어 선택 및 커스텀 포스터 주소 바인딩 연동
        val btnSelectLanguage: ImageView = try { view.findView("btnSelectLanguage") } catch (e: Throwable) {
            throw Exception("btnSelectLanguage ImageView not found in bottom_sheet_layout", e)
        }
        val switchCustomPoster: Switch = try { view.findView("switchCustomPoster") } catch (e: Throwable) {
            throw Exception("switchCustomPoster Switch not found in bottom_sheet_layout", e)
        }
        val layoutCustomPosterUrl: View = try { view.findView("layoutCustomPosterUrl") } catch (e: Throwable) {
            throw Exception("layoutCustomPosterUrl View not found in bottom_sheet_layout", e)
        }
        val etCustomPosterUrl: EditText = try { view.findView("etCustomPosterUrl") } catch (e: Throwable) {
            throw Exception("etCustomPosterUrl EditText not found in bottom_sheet_layout", e)
        }

        btnSelectLanguage.setImageDrawable(getDrawable("settings_icon"))
        btnSelectLanguage.makeTvCompatible()

        // 커스텀 포스터 옵션 초기 상태 설정
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

        // TMDB API Key 및 MdbList 수집 제어판 위젯 신규 연동
        val etTmdbApiKey: EditText = try { view.findView("etTmdbApiKey") } catch (e: Throwable) {
            throw Exception("etTmdbApiKey EditText not found in bottom_sheet_layout", e)
        }
        val switchMdbList: Switch = try { view.findView("switchMdbList") } catch (e: Throwable) {
            throw Exception("switchMdbList Switch not found in bottom_sheet_layout", e)
        }
        val layoutMdbList: View = try { view.findView("layoutMdbList") } catch (e: Throwable) {
            throw Exception("layoutMdbList View not found in bottom_sheet_layout", e)
        }
        val etMdbListApiKey: EditText = try { view.findView("etMdbListApiKey") } catch (e: Throwable) {
            throw Exception("etMdbListApiKey EditText not found in bottom_sheet_layout", e)
        }

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

        // Action: Add Link
        addlinks.setOnClickListener {
            val dialogView = getLayout("addlinks", inflater, container)
            val etName = try { dialogView.findView<EditText>("etName") } catch (t: Throwable) { null }
            val etLink = try { dialogView.findView<EditText>("etLink") } catch (t: Throwable) { null }
            
            if (etName == null || etLink == null) {
                Toast.makeText(requireContext(), "Dialog fields not found", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val dlg = AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create()

            dlg.setOnShowListener {
                val btnSave = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                btnSave.setOnClickListener {
                    val name = etName.text.toString().trim()
                    val link = etLink.text.toString().trim()
                    val type = "StremioC"

                    if (link.isEmpty()) {
                        showToast("Please enter a link")
                        return@setOnClickListener
                    }

                    val valid = try {
                        val uri = link.toUri()
                        val scheme = uri.scheme?.lowercase()
                        scheme == "http" || scheme == "https"
                    } catch (_: Exception) {
                        false
                    }

                    if (!valid) {
                        showToast("Enter a valid URL (http/https)")
                        return@setOnClickListener
                    }

                    val item = LinkItem(name = name.ifBlank { link }, link = link, type = type)
                    try {
                        val list = SettingsRepository.loadLinks(sharedPref)
                        list.add(0, item)
                        SettingsRepository.saveLinks(sharedPref, list)
                        Toast.makeText(requireContext(), "Link saved", Toast.LENGTH_SHORT).show()
                        dlg.dismiss()
                    } catch (e: Throwable) {
                        showToast("Failed to save link")
                    }
                }
            }
            dlg.show()
        }

        // Action: Show Links
        showlinks.setOnClickListener {
            val dialogView = getLayout("dialog_list_links", inflater, container)
            val dlg = AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .create()

            val rv: RecyclerView = dialogView.findView("rvLinks")
            val tvNoLinks: TextView = dialogView.findView("tvNoLinks")
            val list = SettingsRepository.loadLinks(sharedPref)

            if (list.isEmpty()) {
                tvNoLinks.visibility = View.VISIBLE
                rv.visibility = View.GONE
            } else {
                tvNoLinks.visibility = View.GONE
                rv.visibility = View.VISIBLE
                rv.layoutManager = LinearLayoutManager(requireContext())

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
                        AlertDialog.Builder(requireContext())
                            .setTitle(itemToEdit.name)
                            .setItems(choices) { _, which ->
                                if (which == 0) {
                                    showEditDialog(itemToEdit, position, rv)
                                } else {
                                    showCatalogConfigDialog(itemToEdit)
                                }
                            }
                            .show()
                    }
                )

                rv.adapter = adapter
            }
            dlg.show()
        }

        // Action: Save & Restart
        saveIcon.setOnClickListener {
            val context = this.context ?: return@setOnClickListener

            // 저장 클릭 시 TMDB API Key 필수값 체크 분기 적용
            val tmdbApiKeyVal = etTmdbApiKey.text.toString().trim()
            if (tmdbApiKeyVal.isEmpty()) {
                showToast("TMDB API key is a required field")
                return@setOnClickListener
            }

            // 가로입력단 저장소 수집 저장
            sharedPref.edit {
                putString(SettingsRepository.PREF_KEY_TMDB_API_KEY, tmdbApiKeyVal)
                putString(SettingsRepository.PREF_KEY_MDBLIST_API_KEY, etMdbListApiKey.text.toString().trim())
                putString(SettingsRepository.PREF_KEY_HISTORY_SYNC_URL, etHistorySyncUrl.text.toString().trim())
                putString(SettingsRepository.PREF_KEY_CUSTOM_POSTER_URL, etCustomPosterUrl.text.toString().trim())
            }

            AlertDialog.Builder(context)
                .setTitle("Save & Reload")
                .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                .setPositiveButton("Yes") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No", null)
                .show()
        }

        return view
    }

    private fun showLanguageSelectDialog() {
        val context = requireContext()
        val prefs = sharedPref

        val scrollView = androidx.core.widget.NestedScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
        }

        val searchBar = EditText(context).apply {
            hint = "Search language..."
            setHintTextColor(android.graphics.Color.GRAY)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setPadding(20, 20, 20, 20)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#151515"))
                cornerRadius = 10f
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 20)
            }
        }
        container.addView(searchBar)

        val currentSavedCode = prefs.getString(SettingsRepository.PREF_KEY_TMDB_LANG_CODE, "ko-KR") ?: "ko-KR"
        val rowViews = mutableListOf<Pair<String, View>>()

        val langDialog = AlertDialog.Builder(context).create()

        languages.forEach { (langName, code) ->
            val rowLayout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(10, 15, 10, 15)
                isClickable = true
                focusable = View.FOCUSABLE
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                }
            }

            val tvName = TextView(context).apply {
                text = langName
                textSize = 15f
                setTextColor(if (code == currentSavedCode) android.graphics.Color.parseColor("#2196F3") else android.graphics.Color.WHITE)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            rowLayout.addView(tvName)

            if (code == currentSavedCode) {
                val tvCheck = TextView(context).apply {
                    text = "✓"
                    textSize = 16f
                    setTextColor(android.graphics.Color.parseColor("#2196F3"))
                    setPadding(10, 0, 10, 0)
                }
                rowLayout.addView(tvCheck)
            }

            rowLayout.setOnClickListener {
                prefs.edit { putString(SettingsRepository.PREF_KEY_TMDB_LANG_CODE, code) }
                Toast.makeText(context, "Language set to $code ($langName)", Toast.LENGTH_SHORT).show()
                langDialog.dismiss()
            }

            container.addView(rowLayout)
            rowViews.add(langName to rowLayout)
        }

        scrollView.addView(container)

        langDialog.apply {
            setTitle("Select TMDB Language")
            setView(scrollView)
            setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel") { d, _ -> d.dismiss() }
        }

        searchBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim().lowercase()
                rowViews.forEach { (name, view) ->
                    view.visibility = if (query.isEmpty() || name.lowercase().contains(query)) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        langDialog.show()
    }

    private fun showCatalogConfigDialog(item: LinkItem) {
        val context = requireContext()
        val progressDialog = AlertDialog.Builder(context)
            .setMessage("Loading Stremio Addon Manifest...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        CoroutineScope(Dispatchers.Main).launch {
            val manifestText = withContext(Dispatchers.IO) {
                try {
                    com.lagradost.cloudstream3.app.get(item.link, timeout = 30L).text
                } catch (e: Exception) {
                    null
                }
            }
            progressDialog.dismiss()

            if (manifestText.isNullOrBlank()) {
                Toast.makeText(context, "Failed to load manifest. Check link.", Toast.LENGTH_LONG).show()
                return@launch
            }

            val flatTabs = mutableListOf<ConfigFlatTab>()
            try {
                val node = StremioC.jsonMapper.readTree(manifestText)
                val catsNode = node.get("catalogs")
                if (catsNode != null && catsNode.isArray) {
                    for (i in 0 until catsNode.size()) {
                        val cat = catsNode.get(i)
                        val id = cat.get("id")?.asText() ?: ""
                        val type = cat.get("type")?.asText() ?: "movie"
                        val name = cat.get("name")?.asText() ?: id
                        val displayType = type.replaceFirstChar { it.uppercase() }
                        
                        val requiresGenre = cat.get("extra")?.let { extraArr ->
                            var found = false
                            if (extraArr.isArray) {
                                for (j in 0 until extraArr.size()) {
                                    val extraObj = extraArr.get(j)
                                    if (extraObj.get("name")?.asText() == "genre" && extraObj.get("isRequired")?.asBoolean() == true) {
                                        found = true
                                    }
                                }
                            }
                            found
                        } ?: false

                        if (!requiresGenre) {
                            flatTabs.add(ConfigFlatTab("${id}_${type}_default", "$name - $displayType", false, "${id}_${type}"))
                        }

                        val genres = mutableListOf<String>()
                        cat.get("genres")?.let { gArr ->
                            if (gArr.isArray) {
                                for (j in 0 until gArr.size()) {
                                    genres.add(gArr.get(j).asText())
                                }
                            }
                        }
                        if (genres.isEmpty()) {
                            cat.get("extra")?.let { extraArr ->
                                if (extraArr.isArray) {
                                    for (j in 0 until extraArr.size()) {
                                        val extraObj = extraArr.get(j)
                                        if (extraObj.get("name")?.asText() == "genre") {
                                            extraObj.get("options")?.let { optsArr ->
                                                if (optsArr.isArray) {
                                                    for (k in 0 until optsArr.size()) {
                                                        genres.add(optsArr.get(k).asText())
                                                    }
                                                }
                                            }
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
            } catch (e: Exception) {
                // 파싱 예외
            }

            if (flatTabs.isEmpty()) {
                Toast.makeText(context, "No catalogs found in manifest.", Toast.LENGTH_LONG).show()
                return@launch
            }

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
                for (i in 0 until arr.length()) {
                    orderedKeys.add(arr.getString(i))
                }
            } catch (e: Exception) {}
        }

        val masterTabs = flatTabs.sortedBy { tab ->
            val idx = orderedKeys.indexOf(tab.key)
            if (idx != -1) idx else Int.MAX_VALUE
        }.toMutableList()

        val scrollView = androidx.core.widget.NestedScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
        }
        scrollView.addView(container)

        class TabRow(
            val tab: ConfigFlatTab,
            val checkBox: CheckBox,
            val orderEdit: EditText,
            val view: View
        )

        val activeRows = mutableListOf<TabRow>()
        var rebuildLayout: (() -> Unit)? = null

        fun shiftItem(row: TabRow, newOrder: Int) {
            val clampedOrder = newOrder.coerceIn(1, activeRows.size)
            val oldIndex = activeRows.indexOf(row)
            val newIndex = clampedOrder - 1

            if (oldIndex == -1) return

            if (oldIndex != newIndex) {
                val targetTab = row.tab
                val masterOldIndex = masterTabs.indexOf(targetTab)
                
                if (masterOldIndex != -1) {
                    masterTabs.removeAt(masterOldIndex)
                    val targetNeighborTab = activeRows[newIndex].tab
                    val masterNewIndex = masterTabs.indexOf(targetNeighborTab)
                    if (masterNewIndex != -1) {
                        masterTabs.add(masterNewIndex, targetTab)
                    } else {
                        masterTabs.add(targetTab)
                    }
                }
            }

            val focusedView = container.findFocus() as? EditText
            val selStart = focusedView?.selectionStart ?: 0
            val selEnd = focusedView?.selectionEnd ?: 0

            val visibleTabs = masterTabs.filter { tab ->
                if (tab.isGenre) {
                    val genresEnabledKey = "genres_enabled_${uniqueId}_${tab.catalogKey}"
                    prefs.getBoolean(genresEnabledKey, true)
                } else {
                    true
                }
            }

            val reorderedRows = mutableListOf<TabRow>()
            visibleTabs.forEach { tab ->
                activeRows.find { it.tab.key == tab.key }?.let { reorderedRows.add(it) }
            }
            activeRows.clear()
            activeRows.addAll(reorderedRows)

            for (i in activeRows.indices) {
                val rowView = activeRows[i].view
                val expectedIndex = i
                if (container.getChildAt(expectedIndex) != rowView) {
                    container.removeView(rowView)
                    container.addView(rowView, expectedIndex)
                }
                activeRows[i].orderEdit.setText((i + 1).toString())
            }

            for (i in activeRows.indices) {
                val itemLayout = activeRows[i].view as android.widget.LinearLayout
                val upBtn = itemLayout.getChildAt(0) as android.widget.ImageButton
                val downBtn = itemLayout.getChildAt(1) as android.widget.ImageButton
                upBtn.isEnabled = i > 0
                upBtn.alpha = if (upBtn.isEnabled) 1.0f else 0.3f
                downBtn.isEnabled = i < activeRows.size - 1
                downBtn.alpha = if (downBtn.isEnabled) 1.0f else 0.3f
            }

            focusedView?.requestFocus()
            if (focusedView != null && selStart in 0..focusedView.text.length && selEnd in 0..focusedView.text.length) {
                focusedView.setSelection(selStart, selEnd)
            }
        }

        rebuildLayout = {
            container.removeAllViews()
            activeRows.clear()

            val visibleTabs = masterTabs.filter { tab ->
                if (tab.isGenre) {
                    val genresEnabledKey = "genres_enabled_${uniqueId}_${tab.catalogKey}"
                    prefs.getBoolean(genresEnabledKey, true)
                } else {
                    true
                }
            }

            visibleTabs.forEachIndexed { index, tab ->
                val itemLayout = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 10, 0, 10)
                }

                val upButton = android.widget.ImageButton(context).apply {
                    setImageResource(android.R.drawable.arrow_up_float)
                    background = null
                    this.isEnabled = index > 0
                    alpha = if (this.isEnabled) 1.0f else 0.3f
                }

                val downButton = android.widget.ImageButton(context).apply {
                    setImageResource(android.R.drawable.arrow_down_float)
                    background = null
                    this.isEnabled = index < visibleTabs.size - 1
                    alpha = if (this.isEnabled) 1.0f else 0.3f
                }

                itemLayout.addView(upButton)
                itemLayout.addView(downButton)

                val etOrder = EditText(context).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    layoutParams = android.widget.LinearLayout.LayoutParams(90, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 10, 0)
                    }
                    gravity = android.view.Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 13f
                    setPadding(10, 10, 10, 10)
                    setText((index + 1).toString())
                    
                    val gd = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#151515"))
                        cornerRadius = 10f
                    }
                    background = gd
                }
                itemLayout.addView(etOrder)

                val enabledKey = "tab_enabled_${uniqueId}_${tab.key}"
                val isEnabled = prefs.getBoolean(enabledKey, true)

                val checkbox = android.widget.CheckBox(context).apply {
                    isChecked = isEnabled
                    text = tab.displayName
                    textSize = 15f
                    setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean(enabledKey, checked).apply()
                    }
                }
                val checkParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                itemLayout.addView(checkbox, checkParams)

                if (!tab.isGenre) {
                    val catalogHasGenres = masterTabs.any { it.isGenre && it.catalogKey == tab.catalogKey }
                    if (catalogHasGenres) {
                        val genresEnabledKey = "genres_enabled_${uniqueId}_${tab.catalogKey}"
                        val isGenresEnabled = prefs.getBoolean(genresEnabledKey, true)

                        val genreToggle = android.widget.Switch(context).apply {
                            isChecked = isGenresEnabled
                            text = "Genre"
                            textSize = 12f
                            setOnCheckedChangeListener { _, checked ->
                                prefs.edit().putBoolean(genresEnabledKey, checked).apply()
                                rebuildLayout?.invoke()
                            }
                        }
                        itemLayout.addView(genreToggle)
                    }
                }

                val row = TabRow(tab, checkbox, etOrder, itemLayout)
                activeRows.add(row)

                upButton.setOnClickListener {
                    etOrder.clearFocus()
                    val cur = activeRows.indexOf(row) + 1
                    if (cur > 1) {
                        shiftItem(row, cur - 1)
                    }
                }
                downButton.setOnClickListener {
                    etOrder.clearFocus()
                    val cur = activeRows.indexOf(row) + 1
                    if (cur < activeRows.size) {
                        shiftItem(row, cur + 1)
                    }
                }

                etOrder.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                        etOrder.clearFocus()
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(etOrder.windowToken, 0)
                        true
                    } else false
                }

                etOrder.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val inputVal = etOrder.text.toString().toIntOrNull() ?: (activeRows.indexOf(row) + 1)
                        shiftItem(row, inputVal)
                    }
                }

                container.addView(itemLayout)
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("$addonName - Catalog Settings")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val keysToSave = JSONArray()
                masterTabs.forEach {
                    keysToSave.put(it.key)
                }
                prefs.edit().putString(orderKey, keysToSave.toString()).apply()
                Toast.makeText(context, "Catalog settings saved. Reload to apply.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()

        rebuildLayout.invoke()
        dialog.show()
    }

    private fun String.capitalizeFirstLocal(): String = if (this.isNotEmpty()) {
        this.substring(0, 1).uppercase() + this.substring(1)
    } else {
        this
    }

    private fun String.toUniqueKeyLocal(): String {
        return this.lowercase().filter { it.isLetter() }
    }

    private fun setupToggle(view: View, viewId: String, prefKey: String, isInstalled: Boolean, displayName: String) {
        try {
            val switchView: CompoundButton = view.findView(viewId)
            switchView.isEnabled = isInstalled
            if (!isInstalled) {
                switchView.isChecked = false
                switchView.text = "$displayName (Not Installed)"
                SettingsRepository.setIntegrationEnabled(sharedPref, prefKey, false)
            } else {
                switchView.text = "$displayName Integration"
                switchView.isChecked = SettingsRepository.isIntegrationEnabled(sharedPref, prefKey)
            }

            switchView.setOnCheckedChangeListener { _, isChecked ->
                SettingsRepository.setIntegrationEnabled(sharedPref, prefKey, isChecked)
                val statusText = if (isChecked) "Enabled" else "Disabled"
                showToast("$displayName Integration $statusText")
            }
        } catch (e: Throwable) {
            // 방어 코드
        }
    }

    private fun showEditDialog(itemToEdit: LinkItem, position: Int, rv: RecyclerView) {
        val dialogView = getLayout("addlinks", LayoutInflater.from(requireContext()), null)
        val etName = try { dialogView.findView<EditText>("etName") } catch (t: Throwable) { null }
        val etLink = try { dialogView.findView<EditText>("etLink") } catch (t: Throwable) { null }
        
        if (etName == null || etLink == null) {
            Toast.makeText(requireContext(), "Dialog fields not found", Toast.LENGTH_SHORT).show()
            return
        }

        etName.setText(itemToEdit.name)
        etLink.setText(itemToEdit.link)

        val dlg = AlertDialog.Builder(requireContext())
            .setTitle("Edit Link")
            .setView(dialogView)
            .setPositiveButton("Update", null)
            .setNegativeButton("Cancel", null)
            .create()

        dlg.setOnShowListener {
            val btnUpdate = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btnUpdate.setOnClickListener {
                val name = etName.text.toString().trim()
                val link = etLink.text.toString().trim()
                val type = "StremioC"

                if (link.isEmpty() || !(link.startsWith("http://") || link.startsWith("https://"))) {
                    showToast("Enter a valid URL (http/https)")
                    return@setOnClickListener
                }

                val updatedList = SettingsRepository.loadLinks(sharedPref)
                val indexInDb = updatedList.indexOfFirst { it.id == itemToEdit.id }
                
                if (indexInDb != -1) {
                    val newItem = itemToEdit.copy(name = name.ifBlank { link }, link = link, type = type)
                    updatedList[indexInDb] = newItem
                    SettingsRepository.saveLinks(sharedPref, updatedList)

                    (rv.adapter as? LinksAdapter)?.updateItem(newItem, position)
                    showToast("Link Updated Successfully")
                }
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {}

    // 앱 재시작 함수 복원 완료
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

    // ==========================================
    // RecyclerView Adapter
    // ==========================================
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
            val layoutId = res.getIdentifier("item_saved_link", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
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
