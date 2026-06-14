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

    private data class ConfigCatalog(val id: String, val type: String, val name: String, val genres: List<String>)

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

            val catalogs = try {
                val node = StremioC.jsonMapper.readTree(manifestText)
                val catsNode = node.get("catalogs")
                val list = mutableListOf<ConfigCatalog>()
                if (catsNode != null && catsNode.isArray) {
                    for (i in 0 until catsNode.size()) {
                        val cat = catsNode.get(i)
                        val id = cat.get("id")?.asText() ?: ""
                        val type = cat.get("type")?.asText() ?: "movie"
                        val name = cat.get("name")?.asText() ?: id
                        val genres = mutableListOf<String>()
                        cat.get("genres")?.let { gArr ->
                            if (gArr.isArray) {
                                for (j in 0 until gArr.size()) {
                                    genres.add(gArr.get(j).asText())
                                }
                            }
                        }
                        list.add(ConfigCatalog(id, type, name, genres))
                    }
                }
                list
            } catch (e: Exception) {
                null
            }

            if (catalogs.isNullOrEmpty()) {
                Toast.makeText(context, "No catalogs found in manifest.", Toast.LENGTH_LONG).show()
                return@launch
            }

            displayCatalogListDialog(item.name, catalogs)
        }
    }

    private fun displayCatalogListDialog(addonName: String, catalogs: List<ConfigCatalog>) {
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

        val mutableCatalogs = catalogs.sortedBy { cat ->
            val key = "${cat.id}_${cat.type}"
            val idx = orderedKeys.indexOf(key)
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

        var rebuild: (() -> Unit)? = null

        val dialog = AlertDialog.Builder(context)
            .setTitle("$addonName - Catalog Settings")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val keysToSave = JSONArray()
                mutableCatalogs.forEach {
                    keysToSave.put("${it.id}_${it.type}")
                }
                prefs.edit().putString(orderKey, keysToSave.toString()).apply()
                Toast.makeText(context, "Catalog settings saved. Reload to apply.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()

        rebuild = {
            container.removeAllViews()

            mutableCatalogs.forEachIndexed { index, catalog ->
                val catalogKey = "${catalog.id}_${catalog.type}"
                val enabledKey = "catalog_enabled_${addonName}_$catalogKey"
                val isCatEnabled = prefs.getBoolean(enabledKey, true)

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
                    setOnClickListener {
                        Collections.swap(mutableCatalogs, index, index - 1)
                        rebuild?.invoke()
                    }
                }

                val downButton = android.widget.ImageButton(context).apply {
                    setImageResource(android.R.drawable.arrow_down_float)
                    background = null
                    this.isEnabled = index < mutableCatalogs.size - 1
                    alpha = if (this.isEnabled) 1.0f else 0.3f
                    setOnClickListener {
                        Collections.swap(mutableCatalogs, index, index + 1)
                        rebuild?.invoke()
                    }
                }

                itemLayout.addView(upButton)
                itemLayout.addView(downButton)

                val checkbox = android.widget.CheckBox(context).apply {
                    isChecked = isCatEnabled
                    text = "${catalog.name} (${catalog.type})"
                    textSize = 15f
                    setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean(enabledKey, checked).apply()
                    }
                }
                val checkParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                itemLayout.addView(checkbox, checkParams)

                if (catalog.genres.isNotEmpty()) {
                    val genreButton = android.widget.Button(context).apply {
                        text = "Genres"
                        setOnClickListener {
                            showGenreConfigDialog(addonName, catalogKey, catalog.genres)
                        }
                    }
                    itemLayout.addView(genreButton)
                }

                container.addView(itemLayout)
            }
        }

        rebuild.invoke()
        dialog.show()
    }

    private fun showGenreConfigDialog(addonName: String, catalogKey: String, genres: List<String>) {
        val context = requireContext()
        val prefs = sharedPref

        val genreArray = genres.toTypedArray()
        val checkedItems = BooleanArray(genres.size) { i ->
            val genre = genres[i]
            prefs.getBoolean("genre_enabled_${addonName}_${catalogKey}_$genre", true)
        }

        AlertDialog.Builder(context)
            .setTitle("Select Genres to Show")
            .setMultiChoiceItems(genreArray, checkedItems) { _, which, isChecked ->
                val genre = genres[which]
                prefs.edit().putBoolean("genre_enabled_${addonName}_${catalogKey}_$genre", isChecked).apply()
            }
            .setPositiveButton("Done", null)
            .show()
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
