package com.hsp1020

import android.annotation.SuppressLint
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
import android.widget.RadioButton
import android.widget.RadioGroup
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
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.showToast

class SettingsBottomFragment(
    plugin: StremioXPlugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {
    private val PREF_KEY_LINKS = "stremio_saved_links"
    val PREF_KEY_CINESTREAM_ENABLED = "cinestream_integration_enabled" // CineStream 설정 키

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

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

    //[핵심 수정] CineStream 확장프로그램 설치 여부 확인 함수 (Ghost Provider 감지 적용)
    private fun isCineStreamInstalled(): Boolean {
        // APIHolder 뿐만 아니라 카탈로그가 꺼져있을 때의 유령 객체(Ghost)까지 감지합니다.
        return StremioC.getCineStreamProvider() != null
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = getLayout("bottom_sheet_layout", inflater, container)

        listOf("addlinks", "showlinks", "saveIcon").forEach { name ->
            val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
            if (id == 0) Log.w("SettingsBottomFragment", "View id '$name' not found")
            else Log.d("SettingsBottomFragment", "View id '$name' -> $id")
        }

        // safe find helpers (throws clear exceptions if missing)
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

        // ---------- [기능 추가 1] CineStream Toggle ----------
        try {
            val switchCineStream: CompoundButton = view.findView("switchCineStream")
            // 여기서 Ghost Provider까지 완벽하게 스캔합니다.
            val isInstalled = isCineStreamInstalled()

            switchCineStream.isEnabled = isInstalled
            if (!isInstalled) {
                switchCineStream.isChecked = false
                switchCineStream.text = "CineStream (Not Installed)"
                sharedPref.edit { putBoolean(PREF_KEY_CINESTREAM_ENABLED, false) } // 안 깔려있으면 강제 OFF
            } else {
                switchCineStream.text = "CineStream Integration"
                switchCineStream.isChecked = sharedPref.getBoolean(PREF_KEY_CINESTREAM_ENABLED, true)
            }

            switchCineStream.setOnCheckedChangeListener { _, isChecked ->
                sharedPref.edit { putBoolean(PREF_KEY_CINESTREAM_ENABLED, isChecked) }
                if (isChecked) showToast("CineStream Integration Enabled")
                else showToast("CineStream Integration Disabled")
            }
        } catch (e: Throwable) {
            Log.w("SettingsBottomFragment", "switchCineStream view not found in XML. Skipping toggle setup.")
        }

        // ---------- ADD dialog ----------
        addlinks.setOnClickListener {
            val dialogView = getLayout("addlinks", inflater, container)
            val etName: EditText
            val etLink: EditText
            val rg: RadioGroup
            try {
                etName = dialogView.findView("etName")
                etLink = dialogView.findView("etLink")
                rg = dialogView.findView("radioGroup")
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "Dialog fields not found", Toast.LENGTH_SHORT).show()
                Log.e("SettingsBottomFragment", "Missing dialog views $t")
                return@setOnClickListener
            }

            val rbXId = res.getIdentifier("rbX", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
            try { dialogView.findView<RadioButton>("rbX").isChecked = true } catch (_: Throwable) {}

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
                    val type = if (rg.checkedRadioButtonId == rbXId) "StremioX" else "StremioC"

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
                        val list = loadLinks().toMutableList()
                        list.add(0, item)
                        saveLinks(list)
                        Toast.makeText(requireContext(), "Link saved", Toast.LENGTH_SHORT).show()
                        dlg.dismiss()
                    } catch (e: Throwable) {
                        Log.e("SettingsBottomFragment", "Failed to save link $e")
                        showToast("Failed to save link")
                    }
                }
            }

            dlg.show()
        }

        // ---------- SHOW list dialog ----------
        showlinks.setOnClickListener {
            val dialogView = getLayout("dialog_list_links", inflater, container)
            val dlg = AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .create()

            val rv: RecyclerView = dialogView.findView("rvLinks")
            val tvNoLinks: TextView = dialogView.findView("tvNoLinks")
            val list = loadLinks().toMutableList()
            Log.d("SettingsBottomFragment", "Loaded ${list.size} saved links")

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
                        val updatedList = loadLinks().toMutableList()
                        val removed = updatedList.removeAll { it.id == itemToDelete.id }

                        if (removed) {
                            saveLinks(updatedList)
                            (rv.adapter as? LinksAdapter)?.remove(itemToDelete)
                            showToast("Deleted")
                            if (updatedList.isEmpty()) {
                                tvNoLinks.visibility = View.VISIBLE
                                rv.visibility = View.GONE
                            }
                        }
                    },
                    onEdit = { itemToEdit, position ->
                        //[기능 추가 2] 링크 수정(Edit) 다이얼로그 띄우기
                        showEditDialog(itemToEdit, position, rv)
                    }
                )

                rv.adapter = adapter
            }
            dlg.show()
        }


        // ---------- SAVE & RESTART ----------
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

    // [기능 추가 2] 링크 수정(Edit) 다이얼로그 띄우기
    private fun showEditDialog(itemToEdit: LinkItem, position: Int, rv: RecyclerView) {
        val dialogView = getLayout("addlinks", LayoutInflater.from(requireContext()), null)
        val etName: EditText
        val etLink: EditText
        val rg: RadioGroup
        
        try {
            etName = dialogView.findView("etName")
            etLink = dialogView.findView("etLink")
            rg = dialogView.findView("radioGroup")
        } catch (t: Throwable) {
            Toast.makeText(requireContext(), "Dialog fields not found", Toast.LENGTH_SHORT).show()
            return
        }

        val rbXId = res.getIdentifier("rbX", "id", BuildConfig.LIBRARY_PACKAGE_NAME)

        // 기본값 세팅
        etName.setText(itemToEdit.name)
        etLink.setText(itemToEdit.link)
        if (itemToEdit.type == "StremioX") {
            try { dialogView.findView<RadioButton>("rbX").isChecked = true } catch (_: Throwable) {}
        } else {
            try { 
                val rbCId = res.getIdentifier("rbC", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
                dialogView.findViewById<RadioButton>(rbCId)?.isChecked = true 
            } catch (_: Throwable) {}
        }

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
                val type = if (rg.checkedRadioButtonId == rbXId) "StremioX" else "StremioC"

                if (link.isEmpty() || !(link.startsWith("http://") || link.startsWith("https://"))) {
                    showToast("Enter a valid URL (http/https)")
                    return@setOnClickListener
                }

                val updatedList = loadLinks().toMutableList()
                val indexInDb = updatedList.indexOfFirst { it.id == itemToEdit.id }
                
                if (indexInDb != -1) {
                    val newItem = itemToEdit.copy(name = name.ifBlank { link }, link = link, type = type)
                    updatedList[indexInDb] = newItem
                    saveLinks(updatedList)

                    (rv.adapter as? LinksAdapter)?.updateItem(newItem, position)
                    showToast("Link Updated Successfully")
                }
                dlg.dismiss()
            }
        }
        dlg.show()
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

    data class LinkItem(
        val id: Long = System.currentTimeMillis(),
        val name: String,
        val link: String,
        val type: String
    )

    private fun loadLinks(): MutableList<LinkItem> {
        val json = sharedPref.getString(PREF_KEY_LINKS, null) ?: return mutableListOf()
        val list = mutableListOf<LinkItem>()

        return try {
            val arr = org.json.JSONArray(json)

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)

                list.add(
                    LinkItem(
                        id   = obj.optLong("id", System.currentTimeMillis()),
                        name = obj.optString("name", ""),
                        link = obj.optString("link", ""),
                        type = obj.optString("type", "StremioX")
                    )
                )
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveLinks(list: List<LinkItem>) {
        val arr = org.json.JSONArray()

        for (item in list) {
            val obj = org.json.JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("link", item.link)
                put("type", item.type)
            }
            arr.put(obj)
        }
        sharedPref.edit { putString(PREF_KEY_LINKS, arr.toString()) }
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
            val layoutId = res.getIdentifier("item_saved_link", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
            val v = layoutInflater.inflate(res.getLayout(layoutId), parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]

            holder.tvName.text = item.name
            holder.tvLink.text = item.link
            holder.tvType.text = item.type

            holder.btnDelete.setOnClickListener {
                onDelete(item)
            }

            // 아이템을 클릭하면 수정 창을 엽니다.
            holder.itemView.setOnClickListener {
                onEdit(item, position)
            }
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
