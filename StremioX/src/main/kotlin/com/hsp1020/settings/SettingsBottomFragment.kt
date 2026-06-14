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
    
    // [v2.2.0] TMDb 다국어 및 추천목록 포스터 설정 키 정의
    const val PREF_KEY_TMDB_LANG_CODE = "tmdb_language_code"
    const val PREF_KEY_CUSTOM_POSTER_URL = "custom_poster_url"

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
    private val plugin: StremioXPlugin, // 플러그인 전송 인자 바인딩
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    private data class ConfigFlatTab(val key: String, val displayName: String, val isGenre: Boolean, val catalogKey: String)

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

        // [v2.2.0] 다국어 선택 및 커스텀 포스터 주소 바인딩 연동 (참고 코드 수치 규격 적용) [3]
        val btnSelectLanguage: Button = try { view.findView("btnSelectLanguage") } catch (e: Throwable) {
            throw Exception("btnSelectLanguage Button not found in bottom_sheet_layout", e)
        }
        val etCustomPosterUrl: EditText = try { view.findView("etCustomPosterUrl") } catch (e: Throwable) {
            throw Exception("etCustomPosterUrl EditText not found in bottom_sheet_layout", e)
        }

        val savedLangCode = sharedPref.getString(SettingsRepository.PREF_KEY_TMDB_LANG_CODE, "ko-KR") ?: "ko-KR"
        btnSelectLanguage.text = savedLangCode
        etCustomPosterUrl.setText(sharedPref.getString(SettingsRepository.PREF_KEY_CUSTOM_POSTER_URL, ""))

        btnSelectLanguage.setOnClickListener {
            // 참고 전송 양식대로 수선한 LanguageSelectFragment를 다이어로그형태로 팝업
            val fragment = LanguageSelectFragment(plugin, sharedPref) {
                val updatedLangCode = sharedPref.getString(SettingsRepository.PREF_KEY_TMDB_LANG_CODE, "ko-KR") ?: "ko-KR"
                btnSelectLanguage.text = updatedLangCode
            }
            fragment.show(parentFragmentManager, "LanguageSelect")
        }

        etCustomPosterUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                sharedPref.edit { putString(SettingsRepository.PREF_KEY_CUSTOM_POSTER_URL, etCustomPosterUrl.text.toString().trim()) }
            }
        }

        // Action: Add Link
        addlinks.setOnClickListener {
            val dialogView = getLayout("addlinks", inflater, container)
            val etName: EditText
            val etLink: EditText
            
            try {
                etName = dialogView.findView("etName")
                etLink = dialogView.findView("etLink")
            } catch (t: Throwable) {
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

            // 가로입력단 저장소 누수 방어 수치 수집 저장
            sharedPref.edit {
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

            // AIOMetadata 표준 extra[genre].options 및 Cinemeta genres 이중 수집 대응
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
