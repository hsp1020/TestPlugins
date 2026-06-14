// 파일명: hsp1020/settings/SettingsBottomFragment.kt
package com.hsp1020

import android.annotation.SuppressLint
import android.content.Context // 🔥 누락되었던 컨텍스트 임포트 복구 완료
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.CheckBox  // 🔥 추가 완료
import android.widget.Switch    // 🔥 추가 완료
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
    plugin: StremioXPlugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    // 독자정렬(Flat-Tab) 구성을 위한 DTO 선언
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

        // UI Initialization: Toggles
        setupToggle(view, "switchCineStream", SettingsRepository.PREF_KEY_CINESTREAM_ENABLED, isCineStreamInstalled(), "CineStream")
        setupToggle(view, "switchStreamPlay", SettingsRepository.PREF_KEY_STREAMPLAY_ENABLED, isStreamPlayInstalled(), "StreamPlay")
        setupToggle(view, "switchVixSrc", SettingsRepository.PREF_KEY_VIXSRC_ENABLED, true, "VixSrc")

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
        val etName: EditText
        val etLink: EditText
        
        try {
            etName = dialogView.findView("etName")
            etLink = dialogView.findView("etLink")
        } catch (t: Throwable) {
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

    // ==========================================
    // [v2.0.0] 다이내믹 카탈로그 및 장르 제어 UI 빌더
    // ==========================================
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

            // [v2.0.3] AIOMetadata 표준 extra[genre].options 및 Cinemeta genres 이중 수집 대응
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
                        // 방식 1: Cinemeta 식 top-level "genres"
                        cat.get("genres")?.let { gArr ->
                            if (gArr.isArray) {
                                for (j in 0 until gArr.size()) {
                                    genres.add(gArr.get(j).asText())
                                }
                            }
                        }
                        // 방식 2: AIOMetadata / Stremio 표준 "extra[name=genre].options"
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
                            // [v2.1.1] 첫 글자 대문자 보정 유틸 적용
                            val capitalizedGenre = genre.capitalizeFirstLocal()
                            flatTabs.add(ConfigFlatTab("${id}_${type}_genre_$genre", "$name : $capitalizedGenre  - $displayType", true, "${id}_${type}"))
                        }
                    }
                }
            } catch (e: Exception) {
                // 파싱 예외 처리
            }

            if (flatTabs.isEmpty()) {
                Toast.makeText(context, "No catalogs found in manifest.", Toast.LENGTH_LONG).show()
                return@launch
            }

            displayCatalogListDialog(item.name, flatTabs)
        }
    }

    private fun displayCatalogListDialog(addonName: String, flatTabs: List<ConfigFlatTab>) {
        val context = requireContext()
        val prefs = sharedPref

        val orderKey = "catalog_order_$addonName"
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

        // 마스터 탭 리스트 구성
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

        // Shifting Helper: 순서 텍스트박스 수치 입력 시 또는 버튼 클릭 시 순서 이동 연동 [2]
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

            // 키보드 포커스와 텍스트 선택 영역 저장을 위한 사전 포커스 탐색
            val focusedView = container.findFocus() as? EditText
            val selStart = focusedView?.selectionStart ?: 0
            val selEnd = focusedView?.selectionEnd ?: 0

            // 변경된 순서 목록을 바탕으로 visibleTabs 정규화
            val visibleTabs = masterTabs.filter { tab ->
                if (tab.isGenre) {
                    val genresEnabledKey = "genres_enabled_${addonName}_${tab.catalogKey}"
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

            // 뷰를 통째로 생성하지 않고, LinearLayout 상의 인덱스 순서만 스왑하여 포커스 상실 방지 [2]
            for (i in activeRows.indices) {
                val rowView = activeRows[i].view
                val expectedIndex = i
                if (container.getChildAt(expectedIndex) != rowView) {
                    container.removeView(rowView)
                    container.addView(rowView, expectedIndex)
                }
                activeRows[i].orderEdit.setText((i + 1).toString())
            }

            // 화살표 활성화 상태 업데이트
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

            // 부모의 장르 온오프 토글 스위치 상태를 감안하여 visible 목록 추려냄 [1]
            val visibleTabs = masterTabs.filter { tab ->
                if (tab.isGenre) {
                    val genresEnabledKey = "genres_enabled_${addonName}_${tab.catalogKey}"
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

                // Up Button
                val upButton = android.widget.ImageButton(context).apply {
                    setImageResource(android.R.drawable.arrow_up_float)
                    background = null
                    this.isEnabled = index > 0
                    alpha = if (this.isEnabled) 1.0f else 0.3f
                }

                // Down Button
                val downButton = android.widget.ImageButton(context).apply {
                    setImageResource(android.R.drawable.arrow_down_float)
                    background = null
                    this.isEnabled = index < visibleTabs.size - 1
                    alpha = if (this.isEnabled) 1.0f else 0.3f
                }

                itemLayout.addView(upButton)
                itemLayout.addView(downButton)

                // [v2.1.1] 수치 직접 대입 순서 텍스트박스 에디터 연동 [2]
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

                // 활성화 토글 체크박스
                // 🔥 [키 매핑 동기화]: StremioC 코어의 로드 규격인 tab_enabled 키 형식과 1대1 일치시킵니다.
                val enabledKey = "tab_enabled_${addonName}_${tab.key}"
                val isCatEnabled = prefs.getBoolean(enabledKey, true)

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

                // [v2.1.1] 장르 온오프 토글스위치 마스터 제어 연동 및 노출 상태 제어 [1]
                if (!tab.isGenre) {
                    val catalogHasGenres = masterTabs.any { it.isGenre && it.catalogKey == tab.catalogKey }
                    if (catalogHasGenres) {
                        val genresEnabledKey = "genres_enabled_${addonName}_${tab.catalogKey}"
                        val isGenresEnabled = prefs.getBoolean(genresEnabledKey, true)

                        val genreToggle = android.widget.Switch(context).apply {
                            isChecked = isGenresEnabled
                            text = "Genre"
                            textSize = 12f
                            setOnCheckedChangeListener { _, checked ->
                                prefs.edit().putBoolean(genresEnabledKey, checked).apply()
                                rebuildLayout?.invoke() // ON/OFF 변경 시에만 리스트 가시성 전체 갱신 [1]
                            }
                        }
                        itemLayout.addView(genreToggle)
                    }
                }

                val row = TabRow(tab, checkbox, etOrder, itemLayout)
                activeRows.add(row)

                // 화살표 연동 리스너
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

                // 에디터 처리 리스너
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
