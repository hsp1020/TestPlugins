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
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity.showToast
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

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

        // 수동 입력용 텍스트 필드 바인딩
        val etTmdbKey: EditText = try { view.findView("etTmdbKey") } catch (e: Throwable) {
            throw Exception("etTmdbKey EditText not found in bottom_sheet_layout", e)
        }
        val etTraktClientId: EditText = try { view.findView("etTraktClientId") } catch (e: Throwable) {
            throw Exception("etTraktClientId EditText not found in bottom_sheet_layout", e)
        }
        val etTraktToken: EditText = try { view.findView("etTraktToken") } catch (e: Throwable) {
            throw Exception("etTraktToken EditText not found in bottom_sheet_layout", e)
        }
        val etSimklClientId: EditText = try { view.findView("etSimklClientId") } catch (e: Throwable) {
            throw Exception("etSimklClientId EditText not found in bottom_sheet_layout", e)
        }
        val etSimklToken: EditText = try { view.findView("etSimklToken") } catch (e: Throwable) {
            throw Exception("etSimklToken EditText not found in bottom_sheet_layout", e)
        }

        addlinks.setImageDrawable(getDrawable("settings_icon"))
        showlinks.setImageDrawable(getDrawable("settings_icon"))
        saveIcon.setImageDrawable(getDrawable("save_icon"))

        addlinks.makeTvCompatible()
        showlinks.makeTvCompatible()
        saveIcon.makeTvCompatible()

        etTmdbKey.setText(sharedPref.getString("tmdb_api_key", ""))
        etTraktClientId.setText(sharedPref.getString("trakt_client_id", ""))
        etTraktToken.setText(sharedPref.getString("trakt_access_token", ""))
        etSimklClientId.setText(sharedPref.getString("simkl_client_id", ""))
        etSimklToken.setText(sharedPref.getString("simkl_access_token", ""))

        setupToggle(view, "switchCineStream", SettingsRepository.PREF_KEY_CINESTREAM_ENABLED, isCineStreamInstalled(), "CineStream")
        setupToggle(view, "switchStreamPlay", SettingsRepository.PREF_KEY_STREAMPLAY_ENABLED, isStreamPlayInstalled(), "StreamPlay")

        // 🌟 [UI 추가] 웹뷰 간편 로그인 전용 버튼 레이아웃 동적 생성 및 주입
        val rootGroup = view as? ViewGroup
        if (rootGroup != null) {
            val context = view.context
            
            val loginContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 20, 30, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            
            // Trakt 간편 로그인 버튼
            val btnTraktLogin = Button(context).apply {
                text = "Trakt Login"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#ED2224")) // Trakt Red
                    cornerRadius = 20f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 10, 0, 10)
                }
                setOnClickListener {
                    val dialog = OAuthWebViewDialog(sharedPref, "trakt") { success ->
                        if (success) {
                            etTraktToken.setText(sharedPref.getString("trakt_access_token", ""))
                            etTraktClientId.setText(sharedPref.getString("trakt_client_id", ""))
                        }
                    }
                    dialog.show(childFragmentManager, "trakt_login")
                }
            }
            
            // Simkl 간편 로그인 버튼
            val btnSimklLogin = Button(context).apply {
                text = "Simkl Login"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E47E24")) // Simkl Orange
                    cornerRadius = 20f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 10, 0, 10)
                }
                setOnClickListener {
                    val dialog = OAuthWebViewDialog(sharedPref, "simkl") { success ->
                        if (success) {
                            etSimklToken.setText(sharedPref.getString("simkl_access_token", ""))
                            etSimklClientId.setText(sharedPref.getString("simkl_client_id", ""))
                        }
                    }
                    dialog.show(childFragmentManager, "simkl_login")
                }
            }
            
            loginContainer.addView(btnTraktLogin)
            loginContainer.addView(btnSimklLogin)
            rootGroup.addView(loginContainer)
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
                        val uri = android.net.Uri.parse(link)
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
                        showEditDialog(itemToEdit, position, rv)
                    }
                )

                rv.adapter = adapter
            }
            dlg.show()
        }

        // Action: Save & Restart
        saveIcon.setOnClickListener {
            val context = this.context ?: return@setOnClickListener

            sharedPref.edit {
                putString("tmdb_api_key", etTmdbKey.text.toString().trim())
                putString("trakt_client_id", etTraktClientId.text.toString().trim())
                putString("trakt_access_token", etTraktToken.text.toString().trim())
                putString("simkl_client_id", etSimklClientId.text.toString().trim())
                putString("simkl_access_token", etSimklToken.text.toString().trim())
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
        } catch (e: Throwable) {}
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

    // 🌟 [새 기능] Trakt, Simkl 자동 백그라운드 웹뷰 로그인 처리 클래스 추가
    class OAuthWebViewDialog(
        private val sharedPref: SharedPreferences,
        private val type: String, // "trakt" or "simkl"
        private val onFinish: (Boolean) -> Unit
    ) : DialogFragment() {

        private lateinit var webView: WebView
        
        private val TRAKT_CLIENT_ID = "6d8668915ed1953f5023ea090e206facc6261813243f567dea15a9a678783b6d"
        private val TRAKT_CLIENT_SECRET = "86a36b2cfb73a9424b528089289a27d2a3dde5b62be6d9bd250476438160613e"
        
        private val SIMKL_CLIENT_ID = "8423f7ac58b77798ff3137b389aa8c5d5cdaaa118e52ab260b9c772428ebef42"
        private val SIMKL_CLIENT_SECRET = "a1ac111d79b4ea67ffa82d685595ecc7d9dc937d85644b3b6f47a180143b924c"

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val ctx = requireContext()
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.parseColor("#121212"))
            }

            val toolbar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(30, 30, 30, 30)
                setBackgroundColor(Color.parseColor("#1E1E1E"))
                gravity = Gravity.CENTER_VERTICAL
            }

            val titleView = TextView(ctx).apply {
                text = if (type == "trakt") "Trakt Login" else "Simkl Login"
                textSize = 18f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val closeBtn = Button(ctx).apply {
                text = "Close"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#333333"))
                    cornerRadius = 15f
                }
                setPadding(30, 15, 30, 15)
                setOnClickListener { dismiss() }
            }

            toolbar.addView(titleView)
            toolbar.addView(closeBtn)
            root.addView(toolbar)

            val webContainer = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }

            webView = WebView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: ""
                        
                        if (url.startsWith("https://google.com") && url.contains("code=")) {
                            val code = url.substringAfter("code=").substringBefore("&")
                            performTokenExchange(code)
                            return true
                        }
                        return false
                    }
                }
            }

            val authUrl = if (type == "trakt") {
                "https://trakt.tv/oauth/authorize?response_type=code&client_id=$TRAKT_CLIENT_ID&redirect_uri=https://google.com"
            } else {
                "https://simkl.com/oauth/authorize?response_type=code&client_id=$SIMKL_CLIENT_ID&redirect_uri=https://google.com"
            }

            webView.loadUrl(authUrl)
            webContainer.addView(webView)
            root.addView(webContainer)

            return root
        }

        private fun performTokenExchange(code: String) {
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val url = if (type == "trakt") "https://api.trakt.tv/oauth/token" else "https://api.simkl.com/oauth/token"
                    val clientId = if (type == "trakt") TRAKT_CLIENT_ID else SIMKL_CLIENT_ID
                    val clientSecret = if (type == "trakt") TRAKT_CLIENT_SECRET else SIMKL_CLIENT_SECRET

                    val payload = JSONObject().apply {
                        put("code", code)
                        put("client_id", clientId)
                        put("client_secret", clientSecret)
                        put("redirect_uri", "https://google.com")
                        put("grant_type", "authorization_code")
                    }

                    val response = StremioC.customSession.post(
                        url,
                        headers = mapOf("Content-Type" to "application/json"),
                        requestBody = payload.toString().toRequestBody("application/json".toMediaTypeOrNull())
                    )

                    if (response.isSuccessful) {
                        val json = JSONObject(response.text)
                        val accessToken = json.optString("access_token")

                        if (!accessToken.isNullOrBlank()) {
                            sharedPref.edit {
                                if (type == "trakt") {
                                    putString("trakt_client_id", clientId)
                                    putString("trakt_access_token", accessToken)
                                } else {
                                    putString("simkl_client_id", clientId)
                                    putString("simkl_access_token", accessToken)
                                }
                            }
                            
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(requireContext(), "${if(type == "trakt") "Trakt" else "Simkl"} Login Successful!", Toast.LENGTH_SHORT).show()
                                onFinish(true)
                                dismiss()
                            }
                            return@launch
                        }
                    }
                    
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Authentication failed. Please try again.", Toast.LENGTH_SHORT).show()
                        onFinish(false)
                        dismiss()
                    }
                } catch (e: Exception) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                        onFinish(false)
                        dismiss()
                    }
                }
            }
        }
    }
}
