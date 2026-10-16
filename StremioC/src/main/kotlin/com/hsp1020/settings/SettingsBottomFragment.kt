// hsp1020/settings/SettingsBottomFragment.kt
package com.hsp1020

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.TouchDelegate
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import kotlinx.coroutines.CancellationException
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
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
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.showToast
import org.json.JSONArray
import org.json.JSONObject
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.withContext
import java.util.Collections

data class LinkItem(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val link: String,
    val type: String,
    val isSyncEnabled: Boolean = true,
    val isHistorySyncEnabled: Boolean = true,
    val isStreamSubtitleSyncEnabled: Boolean = false,
    val enabled: Boolean = true
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
                        isStreamSubtitleSyncEnabled = obj.optBoolean("isStreamSubtitleSyncEnabled", false),
                        enabled = obj.optBoolean("enabled", true)
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
                put("enabled", item.enabled)
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
    private val settingsDialog = object : AppCompatDialog(hostContext) {
        override fun cancel() {
            if (backHandler?.invoke() != true) super.cancel()
        }
    }
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


    private enum class SettingsPage { HUB, SOURCES, METADATA, BACKUP }
    private var backHandler: (() -> Boolean)? = null
    private var refreshSummaries: (() -> Unit)? = null
    private var closing = false
    private val childDialogs = linkedSetOf<AlertDialog>()
    private val dialogOwners = mutableMapOf<Dialog, Dialog>()
    private val dialogCleanup = mutableMapOf<Dialog, MutableList<() -> Unit>>()
    private val focusPlans = mutableMapOf<Dialog, () -> Unit>()
    private val legacyLinkIds = mutableMapOf<String, Long>()

    private fun catalogLinks(): MutableList<LinkItem> {
        val links = SettingsRepository.loadLinks(sharedPref)
        val json = runCatching { JSONArray(sharedPref.getString("stremio_saved_links", "[]")) }.getOrNull()
            ?: return links
        if (json.length() != links.size) return links
        val occurrences = mutableMapOf<String, Int>()
        for (i in links.indices) {
            val obj = json.optJSONObject(i) ?: continue
            if (runCatching { obj.getLong("id") }.isSuccess) continue
            val signature = obj.toString()
            val occurrence = occurrences[signature] ?: 0
            occurrences[signature] = occurrence + 1
            val id = legacyLinkIds.getOrPut("$signature\n$occurrence") {
                var candidate = links[i].id
                while (links.any { it.id == candidate } || candidate in legacyLinkIds.values) candidate++
                candidate
            }
            links[i] = links[i].copy(id = id)
        }
        return links
    }

    private fun topDialog(): Dialog = childDialogs.lastOrNull { it.isShowing } ?: settingsDialog
    private fun alive(owner: Dialog) = !closing && owner.isShowing && settingsDialog.isShowing &&
        (hostContext !is Activity || (!hostContext.isFinishing && !hostContext.isDestroyed))
    private fun onClose(owner: Dialog, cleanup: () -> Unit) {
        dialogCleanup.getOrPut(owner) { mutableListOf() }.add(cleanup)
    }
    private fun cleanupDialog(owner: Dialog) {
        dialogCleanup.remove(owner)?.toList()?.forEach { it() }
        focusPlans.remove(owner)
    }
    private fun View.optional(name: String): View? =
        getCachedResId(name, "id").takeIf { it != 0 }?.let { findViewById<View>(it) }

    private fun hasViews(root: View, groups: Map<Class<*>, List<String>>) =
        groups.all { (type, ids) -> ids.all { type.isInstance(root.optional(it)) } }

    private fun usable(view: View?) = view != null && view.isAttachedToWindow &&
        view.isShown && view.isEnabled && view.isFocusable

    private fun hideKeyboard(view: View) {
        (hostContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }
    private fun imeVisible(view: View): Boolean {
        if (!view.isAttachedToWindow) return false
        ViewCompat.getRootWindowInsets(view)?.let { insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (android.os.Build.VERSION.SDK_INT >= 30 || visible) return visible
        }
        val frame = Rect()
        view.getWindowVisibleDisplayFrame(frame)
        return frame.height() > 0 && hostContext.resources.displayMetrics.heightPixels - frame.bottom > dp(120)
    }

    private fun EditText.activateTvInput(key: Int, event: KeyEvent): Boolean {
        if (key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER &&
            key != KeyEvent.KEYCODE_NUMPAD_ENTER || imeVisible(this)) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !performClick()) {
            (hostContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
        return true
    }

    private fun prepareInput(field: EditText) {
        field.backgroundTintList = null
        field.background = getCardBg()
        field.setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
        field.setHintTextColor(hostContext.colorFromAttribute(HostR.attr.grayTextColor))
        field.textSize = 14f
        field.minHeight = 0
        field.isSingleLine = true
        field.makeTvCompatible()
        if (isLayout(TV)) {
            field.showSoftInputOnFocus = false
            field.setOnKeyListener { _, key, event -> field.activateTvInput(key, event) }
        }
        val slot = field.parent as? FrameLayout ?: return
        val tagKey = getCachedResId("outline", "drawable")
        if (slot.getTag(tagKey) == true) return
        slot.setTag(tagKey, true)
        fun update() {
            val visual = maxOf(dp(35), kotlin.math.ceil(
                (field.paint.fontMetrics.bottom - field.paint.fontMetrics.top).toDouble()
            ).toInt() + field.compoundPaddingTop + field.compoundPaddingBottom + dp(4))
            if (field.layoutParams.height != visual) field.layoutParams = field.layoutParams.apply { height = visual }
            slot.minimumHeight = maxOf(dp(48), visual)
            if (slot.layoutParams.height in 1 until slot.minimumHeight)
                slot.layoutParams = slot.layoutParams.apply { height = slot.minimumHeight }
            slot.touchDelegate = TouchDelegate(Rect(0, 0, slot.width, slot.height), field)
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        if (slot.isAttachedToWindow) {
            slot.addOnLayoutChangeListener(listener)
            slot.post { if (slot.isAttachedToWindow) update() }
        }
        slot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                slot.addOnLayoutChangeListener(listener)
                slot.post { if (slot.isAttachedToWindow) update() }
            }
            override fun onViewDetachedFromWindow(v: View) {
                slot.removeOnLayoutChangeListener(listener)
                slot.touchDelegate = null
            }
        })
    }

    private fun prepareControls(view: View) {
        if (view is EditText) prepareInput(view)
        else if (view is CompoundButton) {
            view.textSize = 14f
            view.minimumHeight = dp(48)
            view.makeTvCompatible()
            val row = view.parent as? LinearLayout
            if (view is Switch && row != null && row.orientation == LinearLayout.HORIZONTAL &&
                row.childCount == 2 && row.getChildAt(0) is TextView) {
                val switchOnly = listOf(
                    "switchCineStream", "switchStreamPlay", "switchVixSrc", "switchMdbList"
                ).any { view.id == getCachedResId(it, "id") }
                if (switchOnly) {
                    row.setOnClickListener(null)
                    row.isClickable = false
                } else {
                    row.setOnClickListener { if (view.isEnabled) view.performClick() }
                }
                row.isFocusable = false
                row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (view.contentDescription.isNullOrBlank() && view.text.isNullOrBlank())
                    view.contentDescription = (row.getChildAt(0) as TextView).text
                for (i in 0 until row.childCount) if (row.getChildAt(i) !== view)
                    row.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        } else if (view.isFocusable && view.isClickable) view.makeTvCompatible()
        if (view is ViewGroup) for (i in 0 until view.childCount) prepareControls(view.getChildAt(i))
    }

    private fun horizontalFocus(vararg controls: View) {
        if (!isLayout(TV)) return
        controls.forEachIndexed { index, control ->
            control.setOnKeyListener { _, key, event ->
                if (control is EditText && imeVisible(control)) false
                else if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    val step = if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    val target = generateSequence(index + step) { it + step }
                        .takeWhile { it in controls.indices }.map { controls[it] }.firstOrNull { usable(it) }
                    if (target == null) false else {
                        if (event.action == KeyEvent.ACTION_DOWN) target.requestFocus()
                        true
                    }
                } else (control as? EditText)?.activateTvInput(key, event) ?: false
            }
        }
    }

    private fun restoreRow(rv: RecyclerView, position: Int, target: (View) -> View = { it.optional("btnEditAddon") ?: it }) {
        if (position !in 0 until (rv.adapter?.itemCount ?: 0) || !rv.isAttachedToWindow ||
            !rv.isShown || topDialog().window?.decorView !== rv.rootView) return
        fun focus() {
            if (!rv.isAttachedToWindow || !rv.isShown || topDialog().window?.decorView !== rv.rootView) return
            rv.findViewHolderForAdapterPosition(position)?.itemView?.let { target(it).requestFocus() }
        }
        rv.findViewHolderForAdapterPosition(position)?.itemView?.let {
            target(it).requestFocus()
        } ?: run {
            rv.doOnNextLayout { focus() }
            rv.scrollToPosition(position)
        }
    }

    private fun fitDialog(dialog: Dialog, root: LinearLayout, body: View?, capDp: Int) {
        var metrics = hostContext.resources.displayMetrics
        var displayWidth = metrics.widthPixels
        var displayHeight = metrics.heightPixels
        val base = Rect()
        val hostDecor = (hostContext as? Activity)?.window?.decorView
        hostDecor?.getWindowVisibleDisplayFrame(base)
        var baseHeight = if (base.height() > 0 && hostDecor?.let { imeVisible(it) } != true)
            base.height() else metrics.heightPixels
        var heightCap = (baseHeight * 0.88f).toInt().coerceAtLeast(1)
        val initialPadding = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        val title = root.getChildAt(0)?.takeIf { it !== body }
        val footer = root.getChildAt(root.childCount - 1)?.takeIf { it !== body && it is LinearLayout }
        var keyboardShown = false
        var windowInsets: WindowInsetsCompat? = null
        var scrollBeforeIme: Pair<Int, Int>? = null
        var bodyHeightBeforeIme: Int? = null
        var restoreScroll: Pair<Int, Int>? = null
        var scrollAnchor: View? = null
        data class Bounds(val width: Int, val height: Int, val top: Int, val bodyHeight: Int)
        var placed: Bounds? = null
        var target: Bounds? = null
        var motion: ValueAnimator? = null
        var imeAnimation: WindowInsetsAnimationCompat? = null
        var settlingIme = false
        fun place(bounds: Bounds) {
            body?.let {
                if (it.layoutParams.height != bounds.bodyHeight)
                    it.layoutParams = it.layoutParams.apply { height = bounds.bodyHeight }
            }
            dialog.window?.let { window ->
                val params = window.attributes
                val gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                if (params.width != bounds.width || params.height != bounds.height ||
                    params.gravity != gravity || params.y != bounds.top) {
                    params.width = bounds.width; params.height = bounds.height
                    params.gravity = gravity; params.y = bounds.top
                    window.attributes = params
                }
            }
            placed = bounds
        }
        fun frame(from: Bounds, to: Bounds, fraction: Float) {
            val f = fraction.coerceIn(0f, 1f)
            fun lerp(a: Int, b: Int) = Math.round(a + (b - a) * f)
            val chromeHeight = (to.height - to.bodyHeight).coerceAtLeast(0)
            val windowHeight = maxOf(chromeHeight, lerp(from.height, to.height))
            place(Bounds(to.width, windowHeight, lerp(from.top, to.top),
                (windowHeight - chromeHeight).coerceAtLeast(0)))
        }
        fun finishMotion() {
            if (keyboardShown) return
            bodyHeightBeforeIme = null
            val scroll = restoreScroll ?: return
            restoreScroll = null
            val anchor = scrollAnchor
            scrollAnchor = null
            root.doOnLayout {
                if (alive(dialog) && topDialog() === dialog && !keyboardShown && anchor?.isShown != false)
                    (body as? NestedScrollView)?.scrollTo(scroll.first, scroll.second)
            }
        }
        fun cancelMotion() {
            motion?.let { it.removeAllListeners(); it.removeAllUpdateListeners(); it.cancel() }
            motion = null
        }
        fun move(next: Bounds, animate: Boolean) {
            if (target == next && (motion != null || imeAnimation != null || placed == next)) return
            target = next
            if (placed?.width != next.width) { imeAnimation = null }
            if (imeAnimation != null) return // The keyboard's own progress drives this transition.
            cancelMotion()
            val from = placed
            if (!animate || from == null || from.width != next.width || from == next) {
                place(next); finishMotion(); return
            }
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220L
                interpolator = AnimationUtils.loadInterpolator(hostContext, android.R.interpolator.fast_out_slow_in)
                addUpdateListener {
                    if (alive(dialog) && topDialog() === dialog) frame(from, next, it.animatedValue as Float)
                    else cancelMotion()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (motion !== animation) return
                        motion = null
                        if (alive(dialog) && topDialog() === dialog) { place(next); finishMotion() }
                    }
                })
            }
            motion = animator
            animator.start()
        }
        val revealInput = Runnable {
            if (alive(dialog) && topDialog() === dialog && keyboardShown) {
                (root.findFocus() as? EditText)?.let {
                    it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true)
                }
            }
        }
        var sizing = false
        fun resize(initial: Boolean = false, settle: Boolean = false) {
            if (sizing || closing || (!initial && (!dialog.isShowing || topDialog() !== dialog))) return
            sizing = true
            try {
                metrics = hostContext.resources.displayMetrics
                val frame = Rect()
                if (root.isAttachedToWindow) root.getWindowVisibleDisplayFrame(frame) else frame.set(base)
                val availableWidth = frame.width().takeIf { it > 0 } ?: metrics.widthPixels
                val width = minOf(dp(capDp), (availableWidth * 0.9f).toInt()).coerceAtLeast(1)
                val bodyWidth = (width - root.paddingLeft - root.paddingRight).coerceAtLeast(1)
                val insets = windowInsets ?: ViewCompat.getRootWindowInsets(root)
                // Only the active window owns IME state; frozen parent windows can retain stale insets.
                val keyboard = if (imeAnimation != null && insets != null)
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom >
                        insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                else if (android.os.Build.VERSION.SDK_INT >= 30 && insets != null)
                    insets.isVisible(WindowInsetsCompat.Type.ime()) else imeVisible(root)
                if (displayWidth != metrics.widthPixels || displayHeight != metrics.heightPixels) {
                    cancelMotion()
                    imeAnimation = null
                    displayWidth = metrics.widthPixels; displayHeight = metrics.heightPixels
                    baseHeight = if (!keyboard && frame.height() > 0) frame.height() else
                        (metrics.heightPixels - frame.top -
                            (insets?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0)).coerceAtLeast(1)
                    heightCap = (baseHeight * 0.88f).toInt().coerceAtLeast(1)
                }
                // A floating window can report only its overlap; the host supplies full-screen height,
                // never keyboard visibility. Stale host insets therefore cannot prevent restoration.
                val imeBottom = if (imeAnimation != null && insets != null)
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                else maxOf(insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0,
                    if (keyboard) hostDecor?.let { ViewCompat.getRootWindowInsets(it) }
                        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 else 0)
                val wasKeyboard = keyboardShown
                if (keyboard && !wasKeyboard) {
                    if (restoreScroll == null) scrollAnchor = root.findFocus()
                    scrollBeforeIme = restoreScroll ?: (body as? NestedScrollView)?.let { it.scrollX to it.scrollY }
                    restoreScroll = null
                    if (bodyHeightBeforeIme == null) bodyHeightBeforeIme = body?.layoutParams?.height
                } else if (!keyboard && wasKeyboard) {
                    restoreScroll = scrollBeforeIme
                    scrollBeforeIme = null
                }
                keyboardShown = keyboard
                // The floating window's visible frame depends on its own position.
                val visibleHeight = if (imeBottom > 0)
                    minOf(baseHeight, (metrics.heightPixels - imeBottom - base.top).coerceAtLeast(1))
                else baseHeight
                val cap = if (keyboard) minOf(heightCap, visibleHeight) else heightCap
                var showTitle = true
                var showFooter = true
                var paddingTop = initialPadding[1]
                var paddingBottom = initialPadding[3]
                fun adaptPairs(node: View) {
                    if (node !is ViewGroup) return
                    if (node is LinearLayout && node.childCount == 2 &&
                        node.getChildAt(0).isClickable && node.getChildAt(1).isClickable &&
                        node.getChildAt(0) !is EditText) {
                        val need = maxOf(dp((264 * metrics.scaledDensity / metrics.density).toInt()),
                            (0 until 2).sumOf { i ->
                                val button = node.getChildAt(i)
                                if (button is TextView) kotlin.math.ceil(button.paint.measureText(button.text.toString()).toDouble()).toInt() + dp(20)
                                else dp(128)
                            } + dp(8))
                        val narrow = bodyWidth < need
                        val orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                        if (node.orientation != orientation) {
                            node.orientation = orientation
                            for (j in 0 until 2) node.getChildAt(j).layoutParams = LinearLayout.LayoutParams(
                                if (narrow) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, if (narrow) 0f else 1f
                            ).apply { if (j == 1) setMargins(if (narrow) 0 else dp(8), if (narrow) dp(4) else 0, 0, 0) }
                        }
                    }
                    for (i in 0 until node.childCount) adaptPairs(node.getChildAt(i))
                }
                adaptPairs(root)
                fun chrome(): Int {
                    var total = paddingTop + paddingBottom
                    for (i in 0 until root.childCount) {
                        val child = root.getChildAt(i)
                        if (child === body || (child === title && !showTitle) ||
                            (child === footer && !showFooter) ||
                            (child !== title && child !== footer && child.visibility == View.GONE)) continue
                        child.measure(View.MeasureSpec.makeMeasureSpec(bodyWidth, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
                        total += child.measuredHeight + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
                    }
                    return total
                }
                var chromeHeight = chrome()
                if (keyboard && body != null && cap < chromeHeight + dp(48)) {
                    showTitle = false; paddingTop = dp(4); paddingBottom = dp(4)
                    chromeHeight = chrome()
                    if (cap < chromeHeight + dp(48)) { showFooter = false; chromeHeight = chrome() }
                }
                title?.visibility = if (showTitle) View.VISIBLE else View.GONE
                footer?.visibility = if (showFooter) View.VISIBLE else View.GONE
                if (root.paddingTop != paddingTop || root.paddingBottom != paddingBottom)
                    root.setPadding(initialPadding[0], paddingTop, initialPadding[2], paddingBottom)
                body?.measure(View.MeasureSpec.makeMeasureSpec(bodyWidth, View.MeasureSpec.EXACTLY),
                    if (body is RecyclerView) View.MeasureSpec.makeMeasureSpec(
                        (bodyHeightBeforeIme ?: body.layoutParams.height).coerceAtLeast(dp(48)), View.MeasureSpec.EXACTLY)
                    else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val natural = body?.measuredHeight ?: 0
                val height = (chromeHeight + natural).coerceIn(1, cap.coerceAtLeast(1))
                val y = (((if (keyboard) visibleHeight else baseHeight) - height) / 2).coerceAtLeast(0)
                val imeDriven = android.os.Build.VERSION.SDK_INT >= 30 &&
                    (keyboard || wasKeyboard || imeAnimation != null)
                move(Bounds(width, height, y, (height - chromeHeight).coerceAtLeast(0)),
                    !initial && !imeDriven && !settlingIme &&
                        (keyboard || wasKeyboard || motion != null || settle))
                root.removeCallbacks(revealInput)
                if (keyboard && imeAnimation == null) root.post(revealInput)
            } finally { sizing = false }
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener { if (imeAnimation == null) resize() }
        val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
            if (imeAnimation == null) resize()
        }
        val reflow = Runnable { if (imeAnimation == null) resize() }
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            windowInsets = insets
            root.removeCallbacks(reflow)
            root.post(reflow)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(root, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0 ||
                    !alive(dialog) || topDialog() !== dialog) return
                cancelMotion()
                imeAnimation = animation
            }
            override fun onStart(animation: WindowInsetsAnimationCompat,
                bounds: WindowInsetsAnimationCompat.BoundsCompat): WindowInsetsAnimationCompat.BoundsCompat = bounds

            override fun onProgress(insets: WindowInsetsCompat,
                runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                val animation = imeAnimation
                if (animation != null && animation in runningAnimations &&
                    alive(dialog) && topDialog() === dialog) {
                    windowInsets = insets
                    // Follow each actual IME inset instead of animating toward a stale target.
                    resize()
                    target?.let { place(it) }
                }
                return insets
            }
            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                if (imeAnimation !== animation) return
                imeAnimation = null
                if (alive(dialog) && topDialog() === dialog) {
                    settlingIme = true
                    try { resize() } finally { settlingIme = false }
                    finishMotion()
                }
            }
        })
        onClose(dialog) {
            cancelMotion()
            imeAnimation = null
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
            }
            ViewCompat.setOnApplyWindowInsetsListener(root, null)
            ViewCompat.setWindowInsetsAnimationCallback(root, null)
            root.removeCallbacks(reflow)
            root.removeCallbacks(revealInput)
        }
        resize(initial = true)
    }

    private fun styleAddonIcon(button: ImageButton, name: String) {
        button.setImageDrawable(getDrawable(name))
        button.imageTintList = ColorStateList.valueOf(hostContext.colorFromAttribute(HostR.attr.textColor))
        button.makeTvCompatible()
    }
    private fun inputSlot(field: EditText) = FrameLayout(hostContext).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        addView(field, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(35), Gravity.CENTER_VERTICAL))
    }
    private fun bindAddonActions(owner: Dialog, url: EditText, gear: ImageButton, trash: ImageButton?,
        name: EditText? = null, canDelete: () -> Boolean = { false }, onDelete: () -> Unit = {}) {
        styleAddonIcon(gear, "settings_icon")
        trash?.let { styleAddonIcon(it, "delete_icon") }
        fun update() {
            listOfNotNull(gear to url.text.isNotBlank(), trash?.let { it to canDelete() }).forEach { (button, enabled) ->
                button.isEnabled = enabled
                button.isFocusable = enabled
                button.alpha = if (enabled) 1f else 0.5f
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update() }
            override fun afterTextChanged(s: Editable?) { if (s === url.text) url.error = null }
        }
        url.addTextChangedListener(watcher)
        name?.addTextChangedListener(watcher)
        gear.setOnClickListener {
            showAddonSettings(owner, { url.text.toString() }) {
                url.error = "Enter a valid addon URL to open settings."
                url.requestFocus()
            }
        }
        trash?.setOnClickListener { if (canDelete()) onDelete() }
        update()
        horizontalFocus(*listOfNotNull<View>(url, gear, trash).toTypedArray())
        val cleanup: () -> Unit = { url.removeTextChangedListener(watcher); name?.removeTextChangedListener(watcher) }
        onClose(owner, cleanup)
    }

    private fun popupContent(title: String) = LinearLayout(hostContext).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = getDarkThemeBg()
        addView(TextView(hostContext).apply {
            text = title; textSize = 18f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(12))
        })
    }
    private fun popupMessage(message: String) = TextView(hostContext).apply {
        text = message; textSize = 14f
        setTextColor(hostContext.colorFromAttribute(HostR.attr.grayTextColor))
    }
    private fun popupButtons(vararg buttons: View) = LinearLayout(hostContext).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, 0)
        buttons.forEachIndexed { i, button -> addView(button, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { if (i > 0) marginStart = dp(8) }) }
    }
    private fun simpleButton(label: String) = dialogButton(hostContext, label,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    private fun showDeleteConfirmation(owner: Dialog, label: String, message: String, confirm: () -> Unit) {
        if (!alive(owner) || topDialog() !== owner) return
        val content = popupContent("Delete addon?")
        val safeLabel = label.takeUnless { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: "Catalog addon"
        val body = popupMessage("$safeLabel\n\n$message")
        content.addView(body)
        val cancel = simpleButton("Cancel")
        val delete = simpleButton("Delete").apply { setTextColor(Color.rgb(229, 9, 20)) }
        content.addView(popupButtons(cancel, delete))
        val dialog = AlertDialog.Builder(hostContext).setView(content).create()
        setupCustomDialog(dialog, content, owner = owner, initialFocus = { cancel })
        cancel.setOnClickListener { dialog.dismiss() }
        delete.setOnClickListener {
            if (!alive(owner) || !dialog.isShowing || !delete.isEnabled) return@setOnClickListener
            delete.isEnabled = false
            try { confirm(); dialog.dismiss() }
            catch (_: Exception) { body.text = "Unable to remove the addon. Try again."; delete.isEnabled = true }
        }
        dialog.show()
    }
    private fun deleteCatalogById(id: Long): Boolean {
        val links = catalogLinks()
        if (!links.removeAll { it.id == id }) {
            showToast("This addon is no longer available.")
            return false
        }
        SettingsRepository.saveLinks(sharedPref, links)
        return true
    }

    private fun addonManifestUri(raw: String): Uri {
        val uri = raw.trim().toUri()
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
        return stremioManifestUrl(uri.buildUpon().fragment(null).build().toString()).toUri()
    }
    private fun addonConfigureUri(manifest: Uri): Uri {
        val path = manifest.encodedPath ?: error("Missing manifest path")
        require(path.endsWith("/manifest.json"))
        return manifest.buildUpon().encodedPath(path.removeSuffix("/manifest.json") + "/configure")
            .fragment(null).build()
    }

    private fun showAddonSettings(owner: Dialog, url: () -> String, onUrlError: () -> Unit = {}) {
        if (!alive(owner) || topDialog() !== owner) return
        val configure = runCatching { addonConfigureUri(addonManifestUri(url())) }.getOrElse {
            onUrlError()
            showToast("Enter a valid addon URL to open settings.")
            return
        }
        try {
            hostContext.startActivity(Intent(Intent.ACTION_VIEW, configure).apply {
                if (hostContext !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: ActivityNotFoundException) {
            showToast("No browser is available to open addon settings.")
        } catch (_: SecurityException) {
            showToast("Unable to open addon settings in a browser.")
        }
    }

    private fun setupCustomDialog(
        dialog: AlertDialog, contentView: View, bodyView: View? = null, capDp: Int = 480,
        owner: Dialog = topDialog(), initialFocus: (() -> View?)? = null
    ) {
        val parents = generateSequence(owner) { dialogOwners[it] }.toList()
        val origin = owner.currentFocus
        contentView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val root = contentView as? LinearLayout
        var body = bodyView
        if (root != null) {
            root.setPadding(dp(20), dp(20), dp(20), dp(20))
            if (body == null && root.childCount > 1) {
                body = (0 until root.childCount).map { root.getChildAt(it) }
                    .firstOrNull { it is RecyclerView || it is NestedScrollView }
                if (body == null) {
                    val end = if (root.getChildAt(root.childCount - 1).isClickable ||
                        root.getChildAt(root.childCount - 1) is LinearLayout) root.childCount - 1 else root.childCount
                    if (end > 1) {
                        val messages = LinearLayout(hostContext).apply { orientation = LinearLayout.VERTICAL }
                        repeat(end - 1) {
                            val message = root.getChildAt(1)
                            root.removeViewAt(1)
                            message.setPadding(0, 0, 0, 0)
                            messages.addView(message)
                        }
                        body = NestedScrollView(hostContext).apply { addView(messages) }
                        root.addView(body, 1, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                }
            }
            (root.getChildAt(0) as? TextView)?.let {
                if (((it.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0) == 0 &&
                    root.childCount > 1) it.setPadding(0, 0, 0, dp(12))
            }
        }
        normalizeTypeface(contentView)
        prepareControls(contentView)
        dialogOwners[dialog] = owner
        childDialogs.add(dialog)
        dialog.setCanceledOnTouchOutside(false)
        val ownerWindow = owner.window
        val ownerInputMode = ownerWindow?.attributes?.softInputMode
        if (ownerInputMode != null) {
            ownerWindow.setSoftInputMode((ownerInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            onClose(dialog) {
                if (!closing && owner.isShowing) ownerWindow.setSoftInputMode(ownerInputMode)
            }
        }
        dialog.create()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(if (android.os.Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            attributes = attributes.apply {
                gravity = Gravity.CENTER
                x = 0; y = 0
                windowAnimations = 0
            }
        }
        // Size before show(): the first frame must not use the host's default dialog bounds.
        if (root != null) fitDialog(dialog, root, body, capDp)
        dialog.setOnShowListener {
            if (isLayout(TV) || initialFocus != null) {
                val focus = initialFocus?.invoke() ?: body?.getFocusables(View.FOCUS_FORWARD)?.firstOrNull { usable(it) }
                    ?: contentView.getFocusables(View.FOCUS_FORWARD).firstOrNull { usable(it) }
                focus?.requestFocus()
            }
        }
        dialog.setOnDismissListener {
            childDialogs.remove(dialog)
            val children = childDialogs.filter { dialogOwners[it] === dialog }
            dialogOwners.remove(dialog)
            cleanupDialog(dialog)
            children.forEach { it.dismiss() }
            if (!closing && children.isEmpty()) {
                val parent = parents.firstOrNull { it.isShowing }
                if (parent != null) parent.window?.decorView?.post {
                    if (alive(parent) && topDialog() === parent) {
                        focusPlans.remove(parent)?.invoke()
                            ?: if (usable(origin)) origin?.requestFocus() else parent.window?.decorView
                                ?.getFocusables(View.FOCUS_FORWARD)?.firstOrNull { usable(it) }?.requestFocus()
                        refreshSummaries?.invoke()
                    }
                }
            }
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

    private fun View.makeTvCompatible() {
        val key = getCachedResId("outline", "drawable")
        if (getTag(key) == true) return
        val tv = isLayout(TV)
        if (!tv && (this is EditText || this is CompoundButton)) return
        val navigationRow = id in listOf("rowCatalogs", "rowSources", "rowMetadata",
            "rowBackup", "rowStreamSubtitleAddons").map { getCachedResId(it, "id") }
        val attrs = hostContext.obtainStyledAttributes(intArrayOf(
            if (tv) HostR.attr.focusBackground else android.R.attr.selectableItemBackground
        ))
        val effect = attrs.getDrawable(0)
        attrs.recycle()
        if (effect != null) {
            when {
                navigationRow -> {
                    // Keep a single host ripple or TV outline for every navigation row.
                    if (android.os.Build.VERSION.SDK_INT >= 23) foreground = null
                    background = effect
                }
                tv && this !is EditText && this !is CompoundButton && background is RippleDrawable ->
                    background = effect
                background == null && this !is EditText && this !is CompoundButton ->
                    background = effect
                (background !is RippleDrawable || this is CompoundButton) &&
                    android.os.Build.VERSION.SDK_INT >= 23 -> foreground = effect
            }
        }
        setTag(key, true)
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

    // Mirror the TextView actions in settingsFooter (font, minimum height and ripple).
    private fun dialogButton(ctx: Context, label: String, params: LinearLayout.LayoutParams) = TextView(ctx).apply {
        text = label
        textSize = 14f
        typeface = android.graphics.Typeface.create(hostTypeface, android.graphics.Typeface.BOLD)
        setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
        gravity = Gravity.CENTER
        minHeight = dp(48)
        isFocusable = true
        isClickable = true
        layoutParams = params
        makeTvCompatible()
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
        val hostWindow = (hostContext as? Activity)?.window
        val hostInputMode = hostWindow?.attributes?.softInputMode
        if (hostInputMode != null) {
            hostWindow.setSoftInputMode((hostInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            onClose(settingsDialog) { hostWindow.setSoftInputMode(hostInputMode) }
        }
        var finished = false
        val finish: () -> Unit = {
            if (!finished) {
            finished = true
            closing = true
            childDialogs.toList().forEach { it.dismiss() }
            cleanupDialog(settingsDialog)
            dialogOwners.clear()
            focusPlans.clear()
            legacyLinkIds.clear()
            backHandler = null
            refreshSummaries = null
            hostOwner?.lifecycle?.removeObserver(closeOnDestroy)
            searchJob?.cancel()
            uiScope.cancel()
            onDismiss()
            }
        }
        settingsDialog.setOnDismissListener { finish() }
        if (hostOwner?.lifecycle?.currentState == Lifecycle.State.DESTROYED) { finish(); return }
        val ctx = hostContext
        val appContext = ctx.applicationContext
        val view = try { getLayout("bottom_sheet_layout", LayoutInflater.from(ctx), null) }
        catch (_: Exception) {
            showToast("Settings resources are unavailable. Reload the plugin and try again.")
            finish(); return
        }
        if (view !is LinearLayout || !hasViews(view, mapOf(
            LinearLayout::class.java to listOf("settingsRoot", "settingsHeader", "settingsBody",
                "containerStreamSubtitleUrls", "layoutHistorySyncUrl", "layoutMdbList",
                "pageHub", "pageSources", "pageMetadata",
                "pageBackup", "settingsFooter", "saveAction", "rowCatalogs",
                "rowLanguage", "rowSources", "rowMetadata", "rowBackup",
                "rowStreamSubtitleAddons"),
            NestedScrollView::class.java to listOf("settingsViewport"),
            TextView::class.java to listOf("settingsToggle", "tvExport", "tvImport", "settingsBack",
                "btnAddStreamSubtitleUrl"),
            ImageView::class.java to listOf("saveIcon", "addlinks", "showlinks",
                "btnSelectLanguage", "toggleStreamSubtitleUrls"),
            ImageButton::class.java to listOf("btnConfigureHistoryAddon", "btnDeleteHistoryAddon"),
            EditText::class.java to listOf("etTmdbApiKey", "etMdbListApiKey", "etHistorySyncUrl"),
            Switch::class.java to listOf("switchMdbList", "switchCineStream", "switchStreamPlay", "switchVixSrc")
        ))) {
            showToast("Settings resources are unavailable. Reload the plugin and try again.")
            finish(); return
        }
        view.background = getDarkThemeBg()
        val settingsBody = view.findView<View>("settingsBody")
        val settingsToggle = view.findView<TextView>("settingsToggle")
        val hub = view.findView<LinearLayout>("pageHub")
        val viewport = view.findView<NestedScrollView>("settingsViewport")
        settingsBody.visibility = View.VISIBLE
        settingsToggle.text = "Stremio Settings"
        val addlinks: ImageView = try { view.findView("addlinks") } catch (e: Throwable) { throw Exception("addlinks ImageView not found", e) }
        val showlinks: ImageView = try { view.findView("showlinks") } catch (e: Throwable) { throw Exception("showlinks ImageView not found", e) }
        val saveIcon: ImageView = try { view.findView("saveIcon") } catch (e: Throwable) { throw Exception("saveIcon ImageView not found", e) }

        addlinks.setImageDrawable(getDrawable("settings_icon"))
        showlinks.setImageDrawable(getDrawable("settings_icon"))
        saveIcon.setImageDrawable(getDrawable("save_icon"))
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

                    val cancelBtn = dialogButton(ctx, "Cancel", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(8), 0) })
                    val restoreBtn = dialogButton(ctx, "Restore", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, 0, 0) })

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
                        msgBuilder.append("\n\nRestoring will replace the included settings.\nContinue?")

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

        val sourceDraft = mutableListOf<LinkItem>()
        val extraUrls = runCatching {
            JSONArray(sharedPref.getString(SettingsRepository.PREF_KEY_STREAM_SUBTITLE_URLS, "[]"))
        }.getOrNull()
        if (extraUrls != null) for (i in 0 until extraUrls.length()) {
            val entry = extraUrls.opt(i)
            val name = (entry as? JSONObject)?.optString("name").orEmpty()
            val url = if (entry is String) entry else (entry as? JSONObject)?.optString("url").orEmpty()
            if (url.isNotBlank()) sourceDraft.add(LinkItem(id = i.toLong(), name = name, link = url,
                type = "StremioC", enabled = (entry as? JSONObject)?.optBoolean("enabled", true) ?: true))
        }
        view.findView<LinearLayout>("containerStreamSubtitleUrls").visibility = View.GONE
        view.findView<ImageView>("toggleStreamSubtitleUrls").apply {
            visibility = View.GONE; isFocusable = false; isClickable = false
        }
        val sourceEntry = view.optional("rowStreamSubtitleAddons") ?: simpleButton("Streams / Subtitles Addon ›").also {
            (view.findView<LinearLayout>("containerStreamSubtitleUrls").parent as ViewGroup).addView(it)
        }
        view.optional("btnAddStreamSubtitleUrl")?.visibility = View.GONE
        sourceEntry.setOnClickListener { showAddonListDialog(sourceDraft, onDraftChanged = { refreshSummaries?.invoke() }) }

        val layoutHistorySyncUrl: View = try { view.findView("layoutHistorySyncUrl") } catch (e: Throwable) { throw Exception("layoutHistorySyncUrl View not found", e) }
        val etHistorySyncUrl: EditText = try { view.findView("etHistorySyncUrl") } catch (e: Throwable) { throw Exception("etHistorySyncUrl EditText not found", e) }

        layoutHistorySyncUrl.visibility = View.VISIBLE
        etHistorySyncUrl.setText(sharedPref.getString(SettingsRepository.PREF_KEY_HISTORY_SYNC_URL, ""))
        val historyGear = view.optional("btnConfigureHistoryAddon") as? ImageButton
        view.optional("btnDeleteHistoryAddon")?.apply {
            visibility = View.GONE; isFocusable = false; isClickable = false
        }
        if (historyGear != null) bindAddonActions(settingsDialog, etHistorySyncUrl, historyGear, trash = null)

        setupToggle(view, "switchCineStream", SettingsRepository.PREF_KEY_CINESTREAM_ENABLED, isCineStreamInstalled(), "CineStream")
        setupToggle(view, "switchStreamPlay", SettingsRepository.PREF_KEY_STREAMPLAY_ENABLED, isStreamPlayInstalled(), "StreamPlay")
        setupToggle(view, "switchVixSrc", SettingsRepository.PREF_KEY_VIXSRC_ENABLED, true, "VixSrc")

        val btnSelectLanguage: ImageView = try { view.findView("btnSelectLanguage") } catch (e: Throwable) { throw Exception("btnSelectLanguage ImageView not found", e) }
        btnSelectLanguage.setImageDrawable(getDrawable("settings_icon"))
        btnSelectLanguage.imageTintList = actionIconTint
        btnSelectLanguage.makeTvCompatible()

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
            if (!isChecked && layoutMdbList.hasFocus()) switchMdbList.requestFocus()
            layoutMdbList.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        addlinks.setOnClickListener {
            showLinkEditorDialog(false, null, -1, null)
        }

        showlinks.setOnClickListener { showSavedLinksDialog() }

        var currentPage = SettingsPage.HUB
        val pageScroll = IntArray(SettingsPage.entries.size)
        val pageFocus = arrayOfNulls<View>(SettingsPage.entries.size)
        val pages = listOf("pageHub", "pageSources", "pageMetadata", "pageBackup").map { view.optional(it) }
        val titles = listOf("Stremio Settings", "Sources & Integrations", "Metadata & Sync", "Backup")
        val back = view.optional("settingsBack") as? TextView
        fun updateSummaries() {
            val catalogCount = catalogLinks().count { it.link.isNotBlank() }
            (view.optional("summaryCatalogs") as? TextView)?.text =
                if (catalogCount == 0) "No URL entries" else if (catalogCount == 1) "1 URL entry" else "$catalogCount URL entries"
            val count = sourceDraft.count { it.link.isNotBlank() }
            val integrations = listOf("switchCineStream", "switchStreamPlay", "switchVixSrc")
                .count { view.findView<Switch>(it).isChecked }
            (view.optional("summarySources") as? TextView)?.text =
                "${if (count == 0) "No URL entries" else if (count == 1) "1 URL entry" else "$count URL entries"} · $integrations integrations on"
            val code = sharedPref.getString(SettingsRepository.PREF_KEY_TMDB_LANG_CODE, "en-US") ?: "en-US"
            val names = LANGUAGES.filter { it.second == code }
            (view.optional("summaryLanguage") as? TextView)?.text = names.singleOrNull()?.first ?: code
            (view.optional("summaryMetadata") as? TextView)?.text =
                "$code · ${if (etTmdbApiKey.text.isNotBlank()) "API key set" else "API key required"} · MDB Ratings: ${if (switchMdbList.isChecked) "On" else "Off"}"
            listOf("rowCatalogs", "rowSources", "rowMetadata", "rowLanguage", "rowBackup", "rowStreamSubtitleAddons").forEach { id ->
                (view.optional(id) as? ViewGroup)?.let { row ->
                    val texts = mutableListOf<String>()
                    fun labels(node: View) {
                        if (node is TextView && node.text.toString() != "›") {
                            texts.add(node.text.toString())
                            node.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }
                        if (node is ViewGroup) for (i in 0 until node.childCount) labels(node.getChildAt(i))
                    }
                    labels(row)
                    row.contentDescription = texts.joinToString(". ")
                }
            }
        }
        fun showPage(next: SettingsPage, errorField: EditText? = null) {
            if (hub == null) { errorField?.requestFocus(); return }
            if (next != currentPage) {
                back?.apply {
                    isPressed = false
                    jumpDrawablesToCurrentState()
                }
                pageScroll[currentPage.ordinal] = viewport?.scrollY ?: 0
                pages[currentPage.ordinal]?.findFocus()?.let { pageFocus[currentPage.ordinal] = it }
            }
            currentPage = next
            pages.forEachIndexed { i, page -> page?.visibility = if (i == next.ordinal) View.VISIBLE else View.GONE }
            settingsToggle.text = titles[next.ordinal]
            back?.text = "Close"
            view.optional("saveAction")?.visibility = if (next == SettingsPage.BACKUP) View.GONE else View.VISIBLE
            updateSummaries()
            viewport?.post {
                if (!settingsDialog.isShowing || currentPage != next) return@post
                viewport.scrollTo(0, pageScroll[next.ordinal])
                val focus = errorField ?: pageFocus[next.ordinal]?.takeIf { usable(it) }
                    ?: pages[next.ordinal]?.getFocusables(View.FOCUS_FORWARD)?.firstOrNull { usable(it) }
                if (errorField != null || isLayout(TV)) focus?.requestFocus()
                errorField?.requestRectangleOnScreen(Rect(0, 0, errorField.width, errorField.height))
            }
        }
        refreshSummaries = { updateSummaries() }
        if (hub != null) {
            view.findView<View>("rowCatalogs").setOnClickListener { showlinks.performClick() }
            listOf("rowSources" to SettingsPage.SOURCES, "rowMetadata" to SettingsPage.METADATA,
                "rowBackup" to SettingsPage.BACKUP).forEach { (id, page) ->
                view.findView<View>(id).setOnClickListener { showPage(page) }
            }
            view.findView<View>("rowLanguage").setOnClickListener { btnSelectLanguage.performClick() }
            view.findView<View>("saveAction").setOnClickListener { saveIcon.performClick() }
            back?.setOnClickListener {
                hideKeyboard(view)
                if (currentPage == SettingsPage.HUB) dismiss() else showPage(SettingsPage.HUB)
            }
            backHandler = {
                if (imeVisible(view)) { hideKeyboard(view); true }
                else if (currentPage != SettingsPage.HUB) { showPage(SettingsPage.HUB); true } else false
            }
        }
        saveIcon.setOnClickListener {
            etTmdbApiKey.error = null
            val context = hostContext
            val tmdbApiKeyVal = etTmdbApiKey.text.toString().trim()
            if (tmdbApiKeyVal.isEmpty()) {
                showPage(SettingsPage.METADATA, etTmdbApiKey)
                etTmdbApiKey.error = "TMDB API key is a required field"
                showToast("TMDB API key is a required field")
                return@setOnClickListener
            }

            val switchCineStream = view.findView<Switch>("switchCineStream")
            val switchStreamPlay = view.findView<Switch>("switchStreamPlay")
            val switchVixSrc = view.findView<Switch>("switchVixSrc")

            val extraUrlList = sourceDraft.filter { it.link.isNotBlank() }
                .distinctBy { stremioManifestUrl(it.link.trim()) }
            val invalid = extraUrlList.firstOrNull {
                runCatching { val uri = it.link.trim().toUri()
                    uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()
                }.getOrDefault(true)
            }
            if (invalid != null) {
                showPage(SettingsPage.SOURCES)
                showToast("Invalid stream/subtitle addon URL")
                showAddonListDialog(sourceDraft, { refreshSummaries?.invoke() }, invalid.id)
                return@setOnClickListener
            }
            val editor = sharedPref.edit()
            editor.putString(SettingsRepository.PREF_KEY_TMDB_API_KEY, tmdbApiKeyVal)
            editor.putString(SettingsRepository.PREF_KEY_MDBLIST_API_KEY, etMdbListApiKey.text.toString().trim())
            editor.putString(SettingsRepository.PREF_KEY_STREAM_SUBTITLE_URLS, JSONArray().apply {
                extraUrlList.forEach { addon ->
                    put(JSONObject().put("name", addon.name.trim()).put("url", addon.link.trim())
                        .put("enabled", addon.enabled))
                }
            }.toString())
            editor.putString(SettingsRepository.PREF_KEY_HISTORY_SYNC_URL, etHistorySyncUrl.text.toString().trim())
            editor.remove("custom_poster_url").remove("custom_poster_enabled")
            editor.putBoolean(SettingsRepository.PREF_KEY_MDBLIST_ENABLED, switchMdbList.isChecked)
            
            editor.putBoolean(SettingsRepository.PREF_KEY_CINESTREAM_ENABLED, switchCineStream.isChecked)
            editor.putBoolean(SettingsRepository.PREF_KEY_STREAMPLAY_ENABLED, switchStreamPlay.isChecked)
            editor.putBoolean(SettingsRepository.PREF_KEY_VIXSRC_ENABLED, switchVixSrc.isChecked)
            editor.commit()
            StremioC.invalidateSyncSettingsCache()

            recordBackupSnapshot(sharedPref, "stremio_c_restore_complete", "stremio_c_last_applied_backup_hash", "native_backup_stremioc")

            val streamPlayPrefs = context.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
            recordBackupSnapshot(streamPlayPrefs, "streamplay_restore_complete", "streamplay_last_applied_backup_hash", "native_backup_streamplay")

            val cancelBtn = dialogButton(context, "No", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(8), 0) })
            val restartBtn = dialogButton(context, "Yes", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, 0, 0) })

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
                    text = "Changes have been saved. Restart the app to apply them?"
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

        prepareControls(view)
        if (hub != null) listOf(addlinks, showlinks, saveIcon, btnSelectLanguage).forEach {
            it.isFocusable = false; it.isClickable = false; it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        settingsDialog.setContentView(view)
        hostOwner?.lifecycle?.addObserver(closeOnDestroy)
        settingsDialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setSoftInputMode(if (android.os.Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        showPage(SettingsPage.HUB)
        fitDialog(settingsDialog, view, viewport, 800)
        settingsDialog.show()
        if (isLayout(TV)) (view.optional("rowCatalogs") ?: showlinks).requestFocus()
    }


    private fun showSavedLinksDialog() = showAddonListDialog(onDraftChanged = { refreshSummaries?.invoke() })

    private fun showAddonListDialog(sourceDraft: MutableList<LinkItem>? = null,
        onDraftChanged: () -> Unit = {}, invalidId: Long? = null) {
        if (!alive(settingsDialog) || topDialog() !== settingsDialog) return
        val ctx = hostContext
        val inflater = LayoutInflater.from(ctx)
        val content = runCatching { getLayout("dialog_list_links", inflater, null) as? LinearLayout }.getOrNull()
            ?: run { showToast("Settings resources are unavailable. Reload the plugin and try again."); return }
        val sampleRow = runCatching { getLayout("item_saved_link", inflater, null) }.getOrNull()
            ?: run { showToast("Settings resources are unavailable. Reload the plugin and try again."); return }
        if (!hasViews(content, mapOf(RecyclerView::class.java to listOf("rvLinks"),
                TextView::class.java to listOf("tvNoLinks"))) ||
            !hasViews(sampleRow, mapOf(TextView::class.java to listOf("tvName", "tvLink", "tvType", "tvSyncBadge"),
                ImageButton::class.java to listOf("btnDelete", "btnConfigureAddon", "btnEditAddon", "btnCatalogs"),
                Switch::class.java to listOf("switchAddonEnabled")))) {
            showToast("Settings resources are unavailable. Reload the plugin and try again.")
            return
        }
        val sources = sourceDraft != null
        (content.getChildAt(0) as? TextView)?.text = if (sources) "Streams / Subtitles Addon" else "Catalogs"
        content.background = getDarkThemeBg()
        val rv = content.findView<RecyclerView>("rvLinks")
        rv.layoutManager = LinearLayoutManager(ctx)
        val empty = content.findView<TextView>("tvNoLinks").apply {
            text = if (sources) "No stream / subtitle addons" else "No saved catalogs"
        }
        val close = simpleButton("Close")
        val add = simpleButton("Add")
        content.addView(popupButtons(close, add))
        val dialog = AlertDialog.Builder(ctx).setView(content).create()
        lateinit var adapter: LinksAdapter
        fun records() = sourceDraft?.toMutableList() ?: catalogLinks()
        fun resizeList() {
            val count = adapter.itemCount
            empty.visibility = if (count == 0) View.VISIBLE else View.GONE
            rv.visibility = if (count == 0) View.GONE else View.VISIBLE
            val width = rv.width.takeIf { it > 0 } ?: (minOf(dp(600),
                (ctx.resources.displayMetrics.widthPixels * 0.9f).toInt()) - dp(40)).coerceAtLeast(1)
            val sample = getLayout("item_saved_link", inflater, rv)
            val margins = sample.layoutParams as? ViewGroup.MarginLayoutParams
            sample.measure(View.MeasureSpec.makeMeasureSpec((width -
                (margins?.leftMargin ?: 0) - (margins?.rightMargin ?: 0)).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val rowHeight = sample.measuredHeight + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
            val cap = minOf(dp(450), (ctx.resources.displayMetrics.heightPixels * 0.62f).toInt())
            rv.layoutParams = rv.layoutParams.apply { height = minOf(cap.toLong(), rowHeight.toLong() * count).toInt() }
        }
        fun refreshList(focusId: Long?, fallback: Int, controlId: String? = null) {
            if (rv.isComputingLayout) {
                rv.post { if (alive(dialog)) refreshList(focusId, fallback, controlId) }
                return
            }
            adapter.replaceAll(records())
            resizeList()
            onDraftChanged()
            focusPlans[dialog] = {
                if (adapter.itemCount == 0) add.requestFocus()
                else restoreRow(rv, adapter.positionOf(focusId).takeIf { it >= 0 }
                    ?: fallback.coerceIn(0, adapter.itemCount - 1)) {
                    controlId?.let { id -> it.optional(id) } ?: it.optional("btnEditAddon") ?: it
                }
            }
            if (topDialog() === dialog) rv.post {
                if (alive(dialog) && topDialog() === dialog) focusPlans.remove(dialog)?.invoke()
            }
        }
        fun edit(item: LinkItem?, position: Int, urlError: String? = null) {
            if (!alive(dialog) || topDialog() !== dialog) return
            showLinkEditorDialog(item != null, item, position, rv, sourceDraft, urlError) { changed ->
                refreshList(changed, position.coerceAtLeast(0))
            }
        }
        adapter = LinksAdapter(records(),
            showCatalogs = !sources,
            onDelete = { item ->
                val position = adapter.positionOf(item.id).coerceAtLeast(0)
                showDeleteConfirmation(dialog, item.name,
                    if (sources) "Remove this stream / subtitle addon?"
                    else "Delete this saved catalog addon? This change is saved immediately.") {
                    if (sourceDraft != null) sourceDraft.removeAll { it.id == item.id }
                    else if (deleteCatalogById(item.id)) showToast("Deleted")
                    refreshList(null, position)
                }
            },
            onEdit = { item, position -> edit(item, position) },
            onCatalogs = { item -> if (alive(dialog) && topDialog() === dialog) showCatalogConfigDialog(item) },
            onConfigure = { item -> showAddonSettings(dialog, { adapter.linkFor(item.id) ?: "" }) },
            onToggle = { item, enabled ->
                if (alive(dialog) && topDialog() === dialog) {
                    val values = records()
                    val index = values.indexOfFirst { it.id == item.id }
                    if (index >= 0) {
                        values[index] = values[index].copy(enabled = enabled)
                        if (sourceDraft != null) { sourceDraft.clear(); sourceDraft.addAll(values) }
                        else SettingsRepository.saveLinks(sharedPref, values)
                        adapter.updateItem(values[index], notify = false)
                        onDraftChanged()
                    }
                }
            }
        )
        rv.adapter = adapter
        resizeList()
        var measuredWidth = 0
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (rv.width > 0 && measuredWidth != rv.width) { measuredWidth = rv.width; resizeList() }
        }
        rv.addOnLayoutChangeListener(listener)
        onClose(dialog) { rv.removeOnLayoutChangeListener(listener) }
        setupCustomDialog(dialog, content, content.optional("catalogListBody") ?: rv, 600,
            initialFocus = { if (adapter.itemCount == 0) add else rv })
        add.setOnClickListener { edit(null, 0) }
        close.setOnClickListener { dialog.dismiss() }
        dialog.show()
        if (invalidId != null) {
            val index = adapter.positionOf(invalidId)
            records().getOrNull(index)?.let { edit(it, index, "Invalid stream/subtitle addon URL") }
        } else if (isLayout(TV) && adapter.itemCount > 0) restoreRow(rv, 0)
    }

    private fun showLinkEditorDialog(isEdit: Boolean, itemToEdit: LinkItem?, position: Int, rv: RecyclerView?,
        sourceDraft: MutableList<LinkItem>? = null, urlError: String? = null,
        onLinksChanged: ((Long?) -> Unit)? = null) {
        val owner = topDialog()
        if (!alive(owner)) return
        val sources = sourceDraft != null
        val ctx = hostContext
        val dialogView = runCatching { getLayout("addlinks", LayoutInflater.from(ctx), null) as? LinearLayout }.getOrNull()
            ?: run { showToast("Settings resources are unavailable. Reload the plugin and try again."); return }
        if (!hasViews(dialogView, mapOf(
            EditText::class.java to listOf("etName", "etLink"),
            Switch::class.java to listOf("switchStreamSubtitleSync", "switchLinkSync", "switchHistorySync"),
            NestedScrollView::class.java to listOf("catalogEditorScroll")
        ))) {
            showToast("Settings resources are unavailable. Reload the plugin and try again.")
            return
        }
        dialogView.background = getDarkThemeBg()
        (dialogView.getChildAt(0) as? TextView)?.text = when {
            sources && isEdit -> "Edit Streams / Subtitles Addon"
            sources -> "Add Streams / Subtitles Addon"
            isEdit -> "Edit Catalog"
            else -> "Add Catalog"
        }
        val name = dialogView.findView<EditText>("etName")
        val url = dialogView.findView<EditText>("etLink")
        name.setText(itemToEdit?.name.orEmpty())
        url.setText(itemToEdit?.link.orEmpty())
        val streamSync = dialogView.findView<Switch>("switchStreamSubtitleSync")
        val metaSync = dialogView.findView<Switch>("switchLinkSync")
        val historySync = dialogView.findView<Switch>("switchHistorySync")
        streamSync.isChecked = itemToEdit?.isStreamSubtitleSyncEnabled ?: false
        metaSync.isChecked = itemToEdit?.isSyncEnabled ?: true
        historySync.isChecked = itemToEdit?.isHistorySyncEnabled ?: true
        dialogView.optional("catalogSyncSection")?.visibility = if (sources) View.GONE else View.VISIBLE
        val close = simpleButton("Close")
        val save = simpleButton("Save")
        dialogView.addView(popupButtons(close, save))
        val dlg = AlertDialog.Builder(ctx).setView(dialogView).create()
        setupCustomDialog(dlg, dialogView, dialogView.optional("catalogEditorScroll"), 600, owner,
            initialFocus = { if (urlError != null) url else name })
        save.setOnClickListener {
            if (!alive(dlg) || !save.isEnabled) return@setOnClickListener
            val raw = url.text.toString().trim()
            val valid = runCatching {
                val uri = raw.toUri()
                uri.scheme?.lowercase() in setOf("http", "https") && (!sources || !uri.host.isNullOrBlank())
            }.getOrDefault(false)
            if (!valid) {
                url.error = "Enter a valid URL (http/https)"
                url.requestFocus()
                showToast("Enter a valid URL (http/https)")
                return@setOnClickListener
            }
            val changed = (itemToEdit ?: LinkItem(
                id = maxOf(System.currentTimeMillis(), (sourceDraft?.maxOfOrNull { it.id } ?: 0L) + 1L),
                name = "", link = "", type = "StremioC"
            )).copy(name = name.text.toString().trim().ifBlank { if (sources) "" else raw },
                link = raw, type = "StremioC", isSyncEnabled = metaSync.isChecked,
                isHistorySyncEnabled = historySync.isChecked, isStreamSubtitleSyncEnabled = streamSync.isChecked)
            val values = sourceDraft ?: catalogLinks()
            val index = values.indexOfFirst { it.id == changed.id }
            if (isEdit && index < 0) {
                showToast("This addon is no longer available.")
                return@setOnClickListener
            }
            save.isEnabled = false
            if (isEdit) values[index] = changed else values.add(0, changed)
            if (sourceDraft == null) SettingsRepository.saveLinks(sharedPref, values)
            if (onLinksChanged != null) onLinksChanged(changed.id)
            else (rv?.adapter as? LinksAdapter)?.updateItem(changed)
            showToast(if (isEdit) "Addon updated" else "Addon added")
            dlg.dismiss()
        }
        close.setOnClickListener { dlg.dismiss() }
        dlg.show()
        if (urlError != null) url.error = urlError
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
        container.addView(inputSlot(searchBar))

        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(dp(380), (context.resources.displayMetrics.heightPixels * 0.50f).toInt()))
        }
        val languageBody = FrameLayout(context)
        val noMatches = popupMessage("No matching languages").apply { gravity = Gravity.CENTER; visibility = View.GONE }
        languageBody.addView(rv); languageBody.addView(noMatches,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        container.addView(languageBody)

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
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    isClickable = true
                    isFocusable = true
                    minimumHeight = dp(48)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                // 가로 전체를 차지하여 체크 아이콘을 우측 끝으로 밀어냅니다.
                val tvName = TextView(context).apply {
                    textSize = 14f
                    maxLines = 2
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
                rowLayout.background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_selected), getCardBg())
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
                rowLayout.makeTvCompatible()
                normalizeTypeface(rowLayout)
                return VH(rowLayout, tvName, tvCheck)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                val (langName, code) = filteredList[position]
                val isSelected = code == selectedCode
                holder.tvName.text = langName
                
                updateSelection(holder, isSelected)
                holder.view.contentDescription = langName
                holder.tvName.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                holder.tvCheck.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                holder.view.setOnClickListener {
                    onClick(code, langName)
                }
            }
            private fun updateSelection(holder: VH, selected: Boolean) {
                holder.tvName.setTextColor(hostContext.colorFromAttribute(
                    if (selected) HostR.attr.colorPrimary else HostR.attr.textColor
                ))
                holder.view.isSelected = selected
                holder.tvCheck.visibility = if (selected) View.VISIBLE else View.GONE
            }

            override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
                if (payloads.isEmpty()) super.onBindViewHolder(holder, position, payloads)
                else updateSelection(holder, filteredList[position].second == selectedCode)
            }

            fun select(code: String) {
                if (selectedCode == code) return
                val previous = filteredList.indexOfFirst { it.second == selectedCode }
                val next = filteredList.indexOfFirst { it.second == code }
                selectedCode = code
                if (previous >= 0) notifyItemChanged(previous, "selection")
                if (next >= 0 && next != previous) notifyItemChanged(next, "selection")
            }
            fun indexOf(code: String) = filteredList.indexOfFirst { it.second == code }

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
            adapter.select(code)
            val selectedIndex = adapter.indexOf(code)
            if (isLayout(TV)) {
                rv.doOnNextLayout {
                    if (!closing && rv.isShown && searchBar.text.isEmpty()) restoreRow(rv, selectedIndex)
                }
            }
        }
        rv.adapter = adapter

        val searchWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = uiScope.launch {
                    delay(150) 
                    val query = s.toString().trim().lowercase()
                    if (!rv.isAttachedToWindow) return@launch
                    adapter.filter(query)
                    noMatches.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                    rv.visibility = if (adapter.itemCount == 0) View.GONE else View.VISIBLE
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        searchBar.addTextChangedListener(searchWatcher)

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val applyBtn = dialogButton(context, "Apply", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, 0, 0) })
        val cancelBtn = dialogButton(context, "Cancel", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(8), 0) })
        footer.addView(cancelBtn)
        footer.addView(applyBtn)
        container.addView(footer)

        val langDialog = AlertDialog.Builder(context).setView(container).create()
        val selectedIndex = LANGUAGES.indexOfFirst { it.second == selectedCode }
        setupCustomDialog(langDialog, container, languageBody, 600, initialFocus = {
            if (selectedIndex >= 0) rv else searchBar
        })
        onClose(langDialog) { searchBar.removeTextChangedListener(searchWatcher); searchJob?.cancel(); searchJob = null }

        // Apply 버튼 클릭 시에만 SharedPreferences에 값을 저장하고 토스트 메시지를 표시한 후 창을 닫습니다.
        applyBtn.setOnClickListener {
            val code = selectedCode
            val langName = selectedLangName

            val editor = sharedPref.edit()
            editor.putString(SettingsRepository.PREF_KEY_TMDB_LANG_CODE, code)
            if (editor.commit() && currentSavedCode != code) {
                StremioC.invalidateSyncSettingsCache()
            }

            Toast.makeText(context, "Language set to $code ($langName)", Toast.LENGTH_SHORT).show()
            langDialog.dismiss()
        }
        cancelBtn.setOnClickListener { langDialog.dismiss() }
        langDialog.show()
        if (isLayout(TV) && selectedIndex >= 0) restoreRow(rv, selectedIndex)
    }

    private fun showCatalogConfigDialog(item: LinkItem) {
        val context = hostContext
        val owner = topDialog()
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

        var delivered = false
        var request: Job? = null
        onClose(progressDialog) { if (!delivered) request?.cancel() }
        request = uiScope.launch {
            val currentUrl = stremioManifestUrl(item.link)

            val manifestResponse = withContext(Dispatchers.IO) {
                try {
                    com.lagradost.cloudstream3.app.get(currentUrl, timeout = 10L)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { null }
            }
            val manifestText = manifestResponse?.text
            if (!isActive || !alive(owner) || !progressDialog.isShowing) return@launch
            delivered = true
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
                val key = stremioCatalogKey(item.link)
                val cachePolicy = manifestResponse?.headers?.entries
                    ?.firstOrNull { it.key.equals("Cache-Control", ignoreCase = true) }
                    ?.value.orEmpty()
                val directives = cachePolicy.lowercase(java.util.Locale.ROOT)
                    .split(',').map { it.trim() }
                val noStore = "no-store" in directives
                val maxAge = directives.firstOrNull { it.startsWith("max-age=") }
                    ?.substringAfter('=')?.trim('"', ' ')?.toLongOrNull()
                val ttl = when {
                    noStore || "no-cache" in directives -> 0L
                    maxAge != null -> maxAge.coerceIn(0L, 72L * 3600L) * 1000L
                    else -> 3_600_000L
                }
                val version = runCatching { JSONObject(manifestText).optString("version") }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                manifestPrefs.edit().apply {
                    if (noStore) {
                        putBoolean("manifest_no_store_" + key, true)
                        remove(key)
                        remove("manifest_saved_at_" + key)
                        remove("manifest_ttl_ms_" + key)
                    } else {
                        putBoolean("manifest_no_store_" + key, false)
                        putString(key, manifestText)
                        putLong("manifest_saved_at_" + key, System.currentTimeMillis())
                        putLong("manifest_ttl_ms_" + key, ttl)
                    }
                    if (version != null) putString("manifest_version_" + key, version)
                }.apply()

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
        var activeDialog: AlertDialog? = null
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
            setPadding(dp(20), dp(20), dp(20), dp(20))
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
        titleView.maxLines = 2
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        dialogContainer.addView(titleView)
        dialogContainer.addView(popupMessage("Order controls the catalog display sequence.").apply { textSize = 12f })

        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(dp(450), (context.resources.displayMetrics.heightPixels * 0.62f).toInt(), dp(56) * masterTabs.size))
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

            inner class VH(val view: LinearLayout, val checkbox: CheckBox, val etOrder: EditText, val dragHandle: ImageView) : RecyclerView.ViewHolder(view) {
                var displayedOrder = 0
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = getCardBg()
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, dp(4), 0, dp(4))
                    }
                }
                val dragHandle = ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_menu_sort_by_size)
                    isFocusable = false
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
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
                layout.addView(inputSlot(etOrder).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(48)).apply { marginEnd = dp(8) }
                })
                layout.addView(checkbox)
                checkbox.maxLines = 2
                normalizeTypeface(layout)
                prepareControls(layout)
                horizontalFocus(etOrder, checkbox)
                return VH(layout, checkbox, etOrder, dragHandle)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                val tab = visibleTabs[position]
                
                holder.checkbox.setOnCheckedChangeListener(null)
                holder.checkbox.isChecked = tab.isChecked
                holder.checkbox.text = tab.displayName
                val itemColor = hostContext.colorFromAttribute(if (tab.isChecked) HostR.attr.colorPrimary else HostR.attr.textColor)
                holder.checkbox.setTextColor(hostContext.colorFromAttribute(HostR.attr.textColor))
                holder.etOrder.setTextColor(itemColor)
                holder.checkbox.buttonTintList = ColorStateList.valueOf(itemColor)
                holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                    (rv.findFocus() as? EditText)?.clearFocus()
                    tab.isChecked = isChecked 
                    
                    val checkedTabs = masterTabs.filter { it.isChecked }
                    val uncheckedTabs = masterTabs.filter { !it.isChecked }
                    masterTabs.clear()
                    masterTabs.addAll(checkedTabs)
                    masterTabs.addAll(uncheckedTabs)
                    updateVisibleTabs()
                    rv.doOnNextLayout {
                        if (activeDialog?.isShowing == true && rv.hasFocus()) restoreRow(rv,
                            visibleTabs.indexOfFirst { it.key == tab.key }) { (it as ViewGroup).getChildAt(2) }
                    }
                }

                if (holder.etOrder.hasFocus() && holder.etOrder.text.toString().toIntOrNull() != holder.displayedOrder)
                    holder.etOrder.clearFocus()
                holder.etOrder.setOnFocusChangeListener(null)
                holder.displayedOrder = position + 1
                holder.etOrder.setText(holder.displayedOrder.toString())
                
                holder.etOrder.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        rv.postDelayed({
                            if (activeDialog?.isShowing == true && !closing) {
                                val index = visibleTabs.indexOfFirst { it.key == tab.key }
                                if (index >= 0) rv.smoothScrollToPosition(index)
                            }
                        }, 150)
                    } else {
                        val inputVal = holder.etOrder.text.toString().toIntOrNull()
                        if (inputVal != null && inputVal != holder.displayedOrder) {
                            shiftItemInRecyclerView(tab.key, inputVal)
                        } else if (inputVal == null) {
                            val current = visibleTabs.indexOfFirst { it.key == tab.key }
                            if (current >= 0) holder.etOrder.setText((current + 1).toString())
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
            
            fun shiftItemInRecyclerView(tabKey: String, newOrder: Int) {
                rv.post {
                    if (activeDialog?.isShowing != true || closing) return@post
                    val oldPos = visibleTabs.indexOfFirst { it.key == tabKey }
                    if (oldPos !in visibleTabs.indices) return@post
                    val focused = rv.findFocus()
                    val focusedIndex = focused?.let { rv.findContainingViewHolder(it)?.adapterPosition }
                    val focusedKey = focusedIndex?.let { visibleTabs.getOrNull(it)?.key }
                    val orderFocused = focused is EditText
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
                        notifyItemRangeChanged(minOf(oldPos, newPos), kotlin.math.abs(newPos - oldPos) + 1)
                    } else {
                        notifyItemChanged(oldPos)
                    }
                    if (focusedKey != null) rv.doOnNextLayout {
                        if (activeDialog?.isShowing == true && rv.hasFocus()) restoreRow(rv,
                            visibleTabs.indexOfFirst { it.key == focusedKey }) { row ->
                                val parent = row as ViewGroup
                                if (orderFocused) (parent.getChildAt(1) as FrameLayout).getChildAt(0) else parent.getChildAt(2)
                            }
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
            setPadding(0, dp(12), 0, 0)
        }
        val cancelBtn = dialogButton(context, "Cancel", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(8), 0) })
        val saveBtn = dialogButton(context, "Save", LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, 0, 0) })
        footer.addView(cancelBtn)
        footer.addView(saveBtn)
        dialogContainer.addView(footer)

        val dialog = AlertDialog.Builder(context).setView(dialogContainer).create()
        activeDialog = dialog
        setupCustomDialog(dialog, dialogContainer, rv, 800, initialFocus = { rv })

        saveBtn.isFocusableInTouchMode = true
        saveBtn.setOnClickListener {
            if (!saveBtn.isEnabled) return@setOnClickListener
            rv.findFocus()?.clearFocus()
            saveBtn.requestFocus()
            hideKeyboard(saveBtn)
            saveBtn.isEnabled = false
            rv.post {
            if (!dialog.isShowing || closing) return@post
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
        }

        cancelBtn.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        if (isLayout(TV)) restoreRow(rv, 0) { (it as ViewGroup).getChildAt(2) }
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
                switchView.text = ""
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
        private val showCatalogs: Boolean,
        private val onDelete: (LinkItem) -> Unit,
        private val onEdit: (LinkItem, Int) -> Unit,
        private val onCatalogs: (LinkItem) -> Unit,
        private val onConfigure: (LinkItem) -> Unit,
        private val onToggle: (LinkItem, Boolean) -> Unit
    ) : RecyclerView.Adapter<LinksAdapter.VH>() {
        inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val body: View = view.optional("addonRowBody") ?: view
            val tvName: TextView = view.findView("tvName")
            val tvLink: TextView = view.findView("tvLink")
            val tvType: TextView = view.findView("tvType")
            val tvSyncBadge: TextView = view.findView("tvSyncBadge")
            val edit: ImageButton = view.findView("btnEditAddon")
            val catalogs: ImageButton = view.findView("btnCatalogs")
            val gear: ImageButton = view.findView("btnConfigureAddon")
            val trash: ImageButton = view.findView("btnDelete")
            val enabled: Switch = view.findView("switchAddonEnabled")
            fun showEnabledState(checked: Boolean, label: String) {
                body.contentDescription = "$label. ${if (checked) "Enabled" else "Disabled"}."
                body.alpha = if (checked) 1f else 0.6f
            }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = getLayout("item_saved_link", LayoutInflater.from(parent.context), parent)
            view.background = null
            prepareControls(view)
            return VH(view).also {
                it.body.background = null
                it.body.isClickable = false
                it.body.isLongClickable = false
                it.body.isFocusable = false
                listOf(it.tvName, it.tvLink, it.tvType, it.tvSyncBadge).forEach { label ->
                    label.setTextIsSelectable(false)
                    label.linksClickable = false
                    label.movementMethod = null
                    label.isClickable = false
                    label.isLongClickable = false
                    label.isFocusable = false
                }
                styleAddonIcon(it.edit, "addon_edit_icon")
                if (showCatalogs) styleAddonIcon(it.catalogs, "addon_catalog_icon")
                else { it.catalogs.visibility = View.GONE; it.catalogs.isFocusable = false }
                styleAddonIcon(it.gear, "settings_icon")
                styleAddonIcon(it.trash, "delete_icon")
                val actions = it.edit.parent as LinearLayout
                fun alignActions() {
                    val gravity = if (isLayout(TV) ||
                        view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                        Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER
                    actions.gravity = gravity
                }
                alignActions()
                actions.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignActions() }
            }
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name.ifBlank { "Addon" }
            holder.tvLink.text = item.link
            holder.tvType.text = item.type
            listOf(holder.tvName, holder.tvLink, holder.tvType, holder.tvSyncBadge).forEach {
                it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            holder.tvSyncBadge.visibility = View.GONE
            val label = item.name.takeUnless { it == item.link }?.ifBlank { "Addon" } ?: "Addon"
            holder.showEnabledState(item.enabled, label)
            holder.edit.contentDescription = "Edit addon $label"
            if (showCatalogs) holder.catalogs.contentDescription = "Configure catalogs for $label"
            holder.gear.contentDescription = "Addon settings for $label"
            holder.trash.contentDescription = "Delete addon $label"
            holder.enabled.contentDescription = "Enable addon $label"
            holder.enabled.setOnCheckedChangeListener(null)
            holder.enabled.isChecked = item.enabled
            holder.enabled.setOnCheckedChangeListener { _, checked ->
                items.getOrNull(holder.adapterPosition)?.takeIf { it.enabled != checked }?.let { changed ->
                    onToggle(changed, checked)
                    items.getOrNull(holder.adapterPosition)?.takeIf { it.id == changed.id }?.let {
                        holder.showEnabledState(it.enabled, label)
                    }
                }
            }
            holder.gear.isEnabled = item.link.isNotBlank()
            holder.gear.isFocusable = holder.gear.isEnabled
            holder.gear.alpha = if (holder.gear.isEnabled) 1f else 0.5f
            holder.edit.setOnClickListener {
                val index = holder.adapterPosition
                items.getOrNull(index)?.let { onEdit(it, index) }
            }
            if (showCatalogs)
                holder.catalogs.setOnClickListener { items.getOrNull(holder.adapterPosition)?.let(onCatalogs) }
            holder.gear.setOnClickListener { items.getOrNull(holder.adapterPosition)?.let(onConfigure) }
            holder.trash.setOnClickListener { items.getOrNull(holder.adapterPosition)?.let(onDelete) }
            if (isLayout(TV)) {
                val peerIds = if (showCatalogs)
                    listOf("btnEditAddon", "btnCatalogs", "btnConfigureAddon", "btnDelete", "switchAddonEnabled")
                else listOf("btnEditAddon", "btnConfigureAddon", "btnDelete", "switchAddonEnabled")
                val peers = peerIds.map { holder.itemView.findView<View>(it) }
                peers.forEachIndexed { column, control ->
                    control.setOnKeyListener { _, key, event ->
                        if (holder.adapterPosition == RecyclerView.NO_POSITION) return@setOnKeyListener false
                        val direction = when (key) {
                            KeyEvent.KEYCODE_DPAD_UP -> -1
                            KeyEvent.KEYCODE_DPAD_DOWN -> 1
                            else -> 0
                        }
                        if (direction != 0) {
                            val target = holder.adapterPosition + direction
                            val recycler = holder.itemView.parent as? RecyclerView
                            if (recycler == null || target !in items.indices) false else {
                                if (event.action == KeyEvent.ACTION_DOWN) restoreRow(recycler, target) { row ->
                                    row.optional(peerIds[column])
                                        ?.takeIf { it.isEnabled } ?: row.findView<View>("btnEditAddon")
                                }
                                true
                            }
                        } else if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            val step = if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                            val target = generateSequence(column + step) { it + step }.takeWhile { it in peers.indices }
                                .map { peers[it] }.firstOrNull { usable(it) }
                            if (target == null) false else {
                                if (event.action == KeyEvent.ACTION_DOWN) target.requestFocus()
                                true
                            }
                        } else false
                    }
                }
            }
        }
        override fun getItemCount() = items.size
        fun positionOf(id: Long?) = items.indexOfFirst { it.id == id }
        fun linkFor(id: Long) = items.firstOrNull { it.id == id }?.link
        @SuppressLint("NotifyDataSetChanged")
        fun replaceAll(links: List<LinkItem>) {
            items.clear(); items.addAll(links)
            notifyDataSetChanged()
        }
        fun updateItem(newItem: LinkItem, notify: Boolean = true) {
            val index = positionOf(newItem.id)
            if (index >= 0) {
                items[index] = newItem
                if (notify) notifyItemChanged(index)
            }
        }
    }
}
