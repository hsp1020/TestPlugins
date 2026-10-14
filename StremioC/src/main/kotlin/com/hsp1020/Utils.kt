// 파일명: hsp1020/Utils.kt
package com.hsp1020

import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.R
import org.json.JSONArray
import org.json.JSONObject
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlin.math.round
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.res.Resources
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.widget.ImageView
import com.google.android.material.chip.Chip
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.google.android.material.chip.ChipGroup
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationBarView
import androidx.navigation.NavOptions
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.result.ResultData
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import kotlin.math.roundToInt
internal const val STREMIOC_DETAIL_RIPPLE_COLOR = 0x55FFFFFF
private val QUALITY_REGEX = Regex("(\\d{3,4}[pP])")
private val TT_REGEX = Regex("^tt\\d+$")

private data class RatingMeta(val name: String, val icon: String, val sortOrder: Int)
private val RATING_SOURCES = mapOf(
    "myanimelist" to RatingMeta("MAL", " Ⓜ️", 1),
    "mal" to RatingMeta("MAL", " Ⓜ️", 1),
    "imdb" to RatingMeta("IMDb", "⭐", 2),
    "letterboxd" to RatingMeta("LB", "🟢", 3),
    "lb" to RatingMeta("LB", "🟢", 3),
    "audiencescore" to RatingMeta("Popcorn", "🍿", 5),
    "tomatoesaudience" to RatingMeta("Popcorn", "🍿", 5),
    "popcorn" to RatingMeta("Popcorn", "🍿", 5),
    "audience" to RatingMeta("Popcorn", "🍿", 5),
    "trakt" to RatingMeta("Trakt", "Ⓣ", 6),
    "tmdb" to RatingMeta("TMDB", "🎬", 7),
    "metacritic" to RatingMeta("MC", "🎯", 8),
    "mc" to RatingMeta("MC", "🎯", 8),
    "rottentomatoes" to RatingMeta("RT", "🍅", 9),
    "tomatoes" to RatingMeta("RT", "🍅", 9),
    "rt" to RatingMeta("RT", "🍅", 9),
    "rogerebert" to RatingMeta("RE", "👍", 10),
    "re" to RatingMeta("RE", "👍", 10)
)

suspend fun fetchMdbListRatings(id: String?, isTmdb: Boolean = false, mediaType: String = "movie"): List<String> {
    if (id.isNullOrBlank()) return emptyList()

    val context = com.lagradost.cloudstream3.CloudStreamApp.context ?: return emptyList()
    val prefs = context.getSharedPreferences("StremioC", android.content.Context.MODE_PRIVATE)
    
    val mdbListEnabled = prefs.getBoolean("mdblist_enabled", false)
    val mdbListApiKey = prefs.getString("mdblist_api_key", "") ?: ""
    
    if (!mdbListEnabled || mdbListApiKey.isBlank()) return emptyList()
    
    return try {
        val provider = if (isTmdb) "tmdb" else "imdb"
        val type = if (mediaType == "movie") "movie" else "show"
        val modernUrl = "https://api.mdblist.com/$provider/$type/$id/?apikey=$mdbListApiKey&append_to_response=keyword"
        val json = try {
            JSONObject(app.get(modernUrl, timeout = 15L).text).also {
                if (it.optJSONArray("ratings") == null) error("MDBList missing ratings")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val idParam = if (isTmdb) "tm" else "i"
            JSONObject(app.get("https://mdblist.com/api/?apikey=$mdbListApiKey&$idParam=$id", timeout = 15L).text)
        }
        val ratingsArray = json.optJSONArray("ratings") ?: return emptyList()
        val keywords = json.optJSONArray("keywords")
        val certifications = (0 until (keywords?.length() ?: 0)).mapNotNull { i ->
            val value = keywords?.opt(i)
            (if (value is JSONObject) value.optString("name") else value as? String)
                ?.substringAfterLast('.')?.lowercase()
        }.toSet()

        val results = mutableListOf<Pair<RatingMeta, Double>>()
        
        for (i in 0 until ratingsArray.length()) {
            val ratingObj = ratingsArray.optJSONObject(i) ?: continue
            val sourceKey = ratingObj.optString("source", "").lowercase()
            val value = if (ratingObj.has("value")) ratingObj.optDouble("value") else ratingObj.optDouble("score")
            
            if (value.isNaN() || value <= 0.0) continue
            
            val scaledValue = when (sourceKey) {
                "rogerebert", "re" -> value * 2.5
                "letterboxd", "lb" -> ratingObj.optDouble("score").takeUnless { it.isNaN() }
                    ?.div(10.0) ?: if (value > 5.0) value else value * 2.0
                "tmdb", "trakt", "metacritic", "mc", "tomatoes", "rottentomatoes", "rt", "tomatoesaudience", "audiencescore", "popcorn", "audience" -> value / 10.0
                else -> value
            }
            
            val roundedValue = round(scaledValue * 10) / 10.0
            if (roundedValue <= 0.0) continue
            
            if (sourceKey == "metacriticuser") continue
            val meta = RATING_SOURCES[sourceKey] ?: RatingMeta(sourceKey.uppercase(), "🏷️", 99)
            val icon = when (meta.name) {
                "RT" -> when {
                    "certified-fresh" in certifications && scaledValue >= 7.0 -> "🍅\u2060\u2060"
                    scaledValue < 6.0 -> "🍅\u2060"
                    else -> "🍅"
                }
                "Popcorn" -> when {
                    "certified-hot" in certifications && scaledValue >= 8.0 -> "🍿\u2060\u2060"
                    scaledValue < 6.0 -> "🍿\u2060"
                    else -> "🍿"
                }
                else -> meta.icon
            }
            results.add(meta.copy(icon = icon) to roundedValue)
        }
        
        results.sortBy { it.first.sortOrder }
        results.map { (meta, score) ->
            "${meta.icon} ${String.format(Locale.US, "%.1f", score)}"
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

fun String.fixSourceUrl(): String = this.replace("/manifest.json", "").replace("stremio://", "https://")

fun getQuality(qualities: List<String?>): Int {
    val qualityStr = qualities.firstNotNullOfOrNull { it?.let { str -> QUALITY_REGEX.find(str)?.groupValues?.getOrNull(1) } }
    return getQualityFromName(qualityStr)
}

fun normalizeId(id: String?): String {
    val clean = id?.trim() ?: ""
    return when {
        TT_REGEX.matches(clean) -> clean
        clean.isNotEmpty() && clean.all { it.isDigit() } -> "tmdb:$clean"
        else -> clean
    }
}

internal fun prefsToJson(prefs: SharedPreferences, filterBlock: ((String) -> Boolean)? = null): JSONObject {
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

internal fun jsonToPrefs(json: JSONObject, editor: SharedPreferences.Editor) {
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

/** Only replaces known MDBList glyphs on rows marked by StremioC.kt. */
internal class RatingPlotImageHook(
    context: Context,
    private val resources: Resources,
    private val openCatalog: (CatalogShortcutTarget, CatalogHomeSnapshot?) -> Unit
) : Application.ActivityLifecycleCallbacks {
    private val app = context.applicationContext as Application
    private val prefs = app.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var host: FragmentActivity? = null
    private val watchers = mutableMapOf<Fragment, Watcher>()
    private val homeEntries = CatalogHomeEntryHook(openCatalog, ::bindHomePreview, ::bindPhoneHero)
    private var phoneHero: PhoneHeroHook? = null
    private fun bindPhoneHero(parent: RecyclerView?) {
        if (parent == null || !isLayout(PHONE)) {
            phoneHero?.detach()
            phoneHero = null
            return
        }
        val activity = host ?: return
        val hero = phoneHero ?: PhoneHeroHook(activity, ::decoratedRatings).also { phoneHero = it }
        hero.attach(parent)
    }
    private var homePreviewText: TextView? = null
    private data class HeroTextStyle(
        val color: ColorStateList, val radius: Float, val dx: Float, val dy: Float, val shadow: Int
    )
    private val tvHeroStyles = mutableMapOf<TextView, HeroTextStyle>()
    private fun styleTvHero(preview: TextView?) {
        if (preview == null) {
            tvHeroStyles.forEach { (text, original) ->
                text.setTextColor(original.color)
                text.setShadowLayer(original.radius, original.dx, original.dy, original.shadow)
            }
            tvHeroStyles.clear()
            return
        }
        val panel = preview.parent as? ViewGroup ?: return
        tvHeroStyles.keys.removeAll { it.parent == null }
        fun apply(text: TextView?, color: Int) {
            if (text == null) return
            if (text.currentTextColor == color && text.shadowRadius == 4f &&
                text.shadowDx == 0f && text.shadowDy == 2f &&
                text.shadowColor == 0xB0000000.toInt()) return
            tvHeroStyles.putIfAbsent(text, HeroTextStyle(
                text.textColors, text.shadowRadius, text.shadowDx, text.shadowDy, text.shadowColor
            ))
            if (text.currentTextColor != color) text.setTextColor(color)
            text.setShadowLayer(4f, 0f, 2f, 0xB0000000.toInt())
        }
        val context = preview.context
        val white = ContextCompat.getColor(context, R.color.white)
        val normal = ContextCompat.getColor(context, R.color.textColor)
        val muted = ContextCompat.getColor(context, R.color.gray_200)
        apply(panel.findViewById(R.id.home_preview_text), normal)
        apply(panel.findViewById(R.id.home_preview_score), white)
        apply(panel.findViewById(R.id.home_preview_year), muted)
        apply(panel.findViewById(R.id.home_preview_duration), muted)
        apply(panel.findViewById(R.id.home_preview_description), normal)
        apply(panel.findViewById(R.id.home_preview_cast), ContextCompat.getColor(context, R.color.gray_400))
        panel.findViewById<ViewGroup>(R.id.home_preview_tags)?.let { tags ->
            for (i in 0 until tags.childCount) apply(tags.getChildAt(i) as? TextView, normal)
        }
    }
    private var homePreviewChanging = false
    private val homePreviewWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (!homePreviewChanging) renderHomePreview()
        }
    }
    private fun bindHomePreview(view: TextView?) {
        val next = view.takeIf { isLayout(TV or EMULATOR) }
        if (homePreviewText !== next) styleTvHero(null)
        styleTvHero(next)
        if (homePreviewText === next) return
        homePreviewText?.let { previous ->
            previous.removeTextChangedListener(homePreviewWatcher)
            (previous.text as? Spanned)?.let { old ->
                val spans = old.getSpans(0, old.length, LogoSpan::class.java)
                if (spans.isNotEmpty()) previous.setText(SpannableStringBuilder(old).apply {
                    spans.forEach { removeSpan(it) }
                }, TextView.BufferType.SPANNABLE)
            }
        }
        homePreviewText = next
        next?.addTextChangedListener(homePreviewWatcher)
        renderHomePreview()
    }
    private fun renderHomePreview() {
        val view = homePreviewText ?: return
        val original = view.text ?: return
        val result = if (prefs.getBoolean("mdblist_enabled", false)) {
            decoratedRatings(original, view.textSize)
        } else {
            val spans = (original as? Spanned)?.getSpans(0, original.length, LogoSpan::class.java)
            if (spans.isNullOrEmpty()) null else SpannableStringBuilder(original).apply {
                spans.forEach { removeSpan(it) }
            }
        } ?: return
        homePreviewChanging = true
        try { view.setText(result, TextView.BufferType.SPANNABLE) }
        finally { homePreviewChanging = false }
    }
    private var previewPageObserver: Observer<Resource<ResultData>?>? = null
    private var previewPageModel: ResultViewModel2? = null

    // Keep the native Activity-scoped SearchViewModel's results visible when its Fragment is recreated.
    private var lastSearchQuery: String? = null
    private var stremioSearchSession = false
    private var freshSearchPending = false
    private var homeMenuOwner: Fragment? = null
    private var homeMenuItems = emptyList<android.view.MenuItem>()

    private fun releaseHomeMenu(owner: Fragment? = null) {
        if (owner != null && homeMenuOwner !== owner) return
        homeMenuItems.forEach { it.setOnMenuItemClickListener(null) }
        homeMenuItems = emptyList()
        homeMenuOwner = null
    }

    private fun setupSearchNavigation(f: Fragment, root: View) {
        val marked = f.arguments?.getBoolean("stremioc_detail_search") == true
        if (!marked && !stremioSearchSession) return
        stremioSearchSession = true
        val fresh = marked && freshSearchPending
        freshSearchPending = false
        val activity = f.activity ?: return
        fun id(name: String) = root.resources.getIdentifier(name, "id", root.context.packageName)
        val searchView = id("main_search").takeIf { it != 0 }?.let { root.findViewById<SearchView>(it) }
        if (!fresh && searchView?.query.isNullOrEmpty() &&
            f.arguments?.getString("search_query").isNullOrBlank() &&
            MainActivity.nextSearchQuery.isNullOrBlank()) {
            lastSearchQuery?.let { searchView?.setQuery(it, false) }
        }

        val homeId = id("navigation_home")
        val searchId = id("navigation_search")
        val hostId = id("nav_host_fragment")
        if (homeId == 0 || searchId == 0 || hostId == 0) return
        val navHost = activity.supportFragmentManager.findFragmentById(hostId)
            as? androidx.navigation.fragment.NavHostFragment ?: return
        releaseHomeMenu()
        homeMenuOwner = f
        homeMenuItems = listOf("nav_view", "nav_rail_view").mapNotNull { name ->
            val barId = id(name)
            val bar = if (barId == 0) null else activity.findViewById<NavigationBarView>(barId)
            bar?.menu?.findItem(homeId)?.apply {
                setOnMenuItemClickListener {
                    if (!f.isAdded || f.view !== root ||
                        navHost.navController.currentDestination?.id != searchId) {
                        false
                    } else {
                        runCatching {
                            val nav = navHost.navController
                            if (!nav.popBackStack(homeId, false, false)) {
                                nav.navigate(homeId, null, NavOptions.Builder()
                                    .setLaunchSingleTop(true)
                                    .setRestoreState(false)
                                    .build())
                            }
                            true
                        }.getOrElse { e ->
                            android.util.Log.e("StremioCNavMenu", "HOME navigation failed", e)
                            false
                        }
                    }
                }
            }
        }
    }
    private val ids = mutableMapOf<String, Int>()
    private val icons = linkedMapOf(
        "Ⓜ️" to "rating_mal", "⭐" to "rating_imdb",
        "🟢" to "rating_letterboxd", "Ⓣ" to "rating_trakt",
        "🎬" to "rating_tmdb", "🎯" to "rating_metacritic",
        "👍" to "rating_roger_ebert",
        "🍅\u2060\u2060" to "rating_rotten_tomatoes_certified",
        "🍅\u2060" to "rating_rotten_tomatoes_rotten",
        "🍅" to "rating_rotten_tomatoes",
        "🍿\u2060\u2060" to "rating_audience_verified_hot",
        "🍿\u2060" to "rating_audience_stale",
        "🍿" to "rating_audience_score"
    )
    private val number = Regex("^\\s+\\d+(?:\\.\\d+)?(?:\\s|$)")
    private class LogoSpan(d: android.graphics.drawable.Drawable) : ImageSpan(d, ImageSpan.ALIGN_BOTTOM)

    private fun decoratedRatings(original: CharSequence, textSize: Float): SpannableStringBuilder? {
        if (!prefs.getBoolean("mdblist_enabled", false) ||
            original is Spanned && original.getSpans(0, original.length, LogoSpan::class.java).isNotEmpty() ||
            !original.startsWith('\u2063')) return null
        val end = original.indexOf('\n').let { if (it < 0) original.length else it }
        val line = original.subSequence(0, end).toString()
        val result = SpannableStringBuilder(original)
        val height = (textSize * 0.92f).roundToInt().coerceAtLeast(12)
        var applied = false
        for ((glyph, file) in icons) {
            var from = 1
            while (true) {
                val at = line.indexOf(glyph, from)
                if (at < 0) break
                if (number.containsMatchIn(line.substring(at + glyph.length))) {
                    val id = ids.getOrPut(file) {
                        resources.getIdentifier(file, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
                    }
                    if (id != 0) {
                        val drawable = runCatching { resources.getDrawable(id, null).mutate() }.getOrNull()
                        if (drawable != null) {
                            val width = (height.toFloat() * drawable.intrinsicWidth.coerceAtLeast(1) /
                                drawable.intrinsicHeight.coerceAtLeast(1)).roundToInt().coerceIn(height / 2, height * 3)
                            drawable.setBounds(0, 0, width, height)
                            result.setSpan(LogoSpan(drawable), at, at + glyph.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            applied = true
                        }
                    }
                }
                from = at + glyph.length
            }
        }
        return if (applied) result else null
    }

    private fun installPreviewHook(activity: FragmentActivity, data: ResultData) {
        val source = data.apiName.asString(activity)
        if (source != "StremioC" && APIHolder.allProviders.none {
                it is StremioC && it.name.equals(source, ignoreCase = true)
            }) return
        // The native preview is an independent Dialog window, not part of Activity.findViewById.
        val dialog = runCatching {
            activity.javaClass.getDeclaredField("bottomPreviewPopup").apply {
                isAccessible = true
            }.get(activity) as? Dialog
        }.getOrNull()?.takeIf { it.isShowing } ?: return
        val id = activity.resources.getIdentifier("resultview_preview_description", "id", activity.packageName)
        val description = if (id == 0) null else dialog.findViewById<TextView>(id)
        description ?: return
        decoratedRatings(description.text ?: return, description.textSize)?.let {
            description.setText(it, TextView.BufferType.SPANNABLE)
        }
        if (isLayout(PHONE)) description.setOnClickListener {
            // The native secondary plot dialog is created from plain HTML and would lose our
            // ImageSpans. Recreate only that click action with the identical data/title/style.
            val raw = data.plotText.asString(activity).html()
            AlertDialog.Builder(activity, com.lagradost.cloudstream3.R.style.AlertDialogCustom)
                .setMessage(decoratedRatings(raw, description.textSize) ?: raw)
                .setTitle(data.plotHeaderText.asString(activity))
                .show()
        }
    }

    private fun observePreview(activity: FragmentActivity) {
        val model = ViewModelProvider(activity)[ResultViewModel2::class.java]
        val observer = Observer<Resource<ResultData>?> { value ->
            if (value is Resource.Success) main.post {
                if (host === activity) runCatching { installPreviewHook(activity, value.value) }
            }
        }
        model.page.observe(activity, observer)
        previewPageModel = model
        previewPageObserver = observer
    }

    private fun detachPreview() {
        previewPageObserver?.let { observer -> previewPageModel?.page?.removeObserver(observer) }
        previewPageObserver = null
        previewPageModel = null
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "mdblist_enabled") main.post {
            watchers.values.toList().forEach { runCatching { it.render() } }
            runCatching { renderHomePreview() }
        }
    }
    // A search-back transition can expose an intermediate NavHost size for several frames.
    // Keep the last drawn search frame until the restored detail fills the host.
    private var detailRevealEnd: (() -> Unit)? = null

    private fun holdDetailReveal(search: Fragment) {
        if (search.arguments?.getBoolean("stremioc_detail_search") != true) return
        val activity = search.activity ?: return
        fun find(name: String): View? {
            val id = activity.resources.getIdentifier(name, "id", activity.packageName)
            return if (id == 0) null else activity.findViewById<View>(id)
        }
        // Do not affect navigation between ordinary search/home/library screens.
        if (listOf("nav_view", "nav_rail_view").any { find(it)?.visibility == View.VISIBLE }) return
        val content = find("nav_host_fragment") ?: return
        val bounds = content.parent as? android.view.ViewGroup ?: return
        val root = activity.window.decorView
        val observer = root.viewTreeObserver
        if (!observer.isAlive) return
        detailRevealEnd?.invoke()
        val started = android.os.SystemClock.uptimeMillis()
        var stable = 0
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        val release = {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            detailRevealEnd = null
        }
        listener = ViewTreeObserver.OnPreDrawListener {
            val elapsed = android.os.SystemClock.uptimeMillis() - started
            val full = content.visibility == View.VISIBLE &&
                content.left <= bounds.paddingLeft &&
                content.top <= bounds.paddingTop &&
                content.right >= bounds.width - bounds.paddingRight &&
                content.bottom >= bounds.height - bounds.paddingBottom &&
                !content.isLayoutRequested
            stable = if (full) stable + 1 else 0
            if (stable >= 2 || elapsed >= 600L) {
                release()
                true
            } else false
        }
        detailRevealEnd = release
        observer.addOnPreDrawListener(listener)
        main.postDelayed({ if (detailRevealEnd === release) release() }, 650L)
    }

    private val fragmentCallback = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
        ) {
            runCatching {
                if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment")
                    homeEntries.attach(v)
                if (f.javaClass.name == "com.lagradost.cloudstream3.ui.search.SearchFragment")
                    setupSearchNavigation(f, v)
                if (f.javaClass.name == "com.lagradost.cloudstream3.ui.search.SearchFragment" &&
                    f.arguments?.getBoolean("stremioc_detail_search") == true && isLayout(PHONE)) {
                    val id = v.resources.getIdentifier("main_search", "id", v.context.packageName)
                    if (id != 0) v.findViewById<SearchView>(id)?.let { search ->
                        search.post {
                            if (f.isAdded && f.view === v) {
                                search.clearFocus()
                                com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard(search)
                            }
                        }
                    }
                } else attach(f, v)
            }
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name == "com.lagradost.cloudstream3.ui.search.SearchFragment") {
                if (stremioSearchSession) {
                    val id = f.view?.resources?.getIdentifier("main_search", "id", f.requireContext().packageName) ?: 0
                    val query = if (id == 0) null else f.view?.findViewById<SearchView>(id)?.query?.toString()
                    if (query != null) lastSearchQuery = query.takeIf { it.isNotBlank() }
                }
                runCatching { holdDetailReveal(f) }
                    .onFailure { android.util.Log.e("StremioCNavBack", "detail reveal error", it) }
            }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment")
                homeEntries.detach()
            releaseHomeMenu(f)
            watchers.remove(f)?.detach()
        }
    }

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    private fun resetSearchState() {
        releaseHomeMenu()
        stremioSearchSession = false
        freshSearchPending = false
        lastSearchQuery = null
    }

    fun stop() {
        homeEntries.detach()
        detachPreview()
        detailRevealEnd?.invoke()
        resetSearchState()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        watchers.values.toList().forEach { it.detach() }
        watchers.clear()
        host = null
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        homeEntries.detach()
        detachPreview()
        detailRevealEnd?.invoke()
        resetSearchState()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        watchers.values.toList().forEach { it.detach() }
        watchers.clear()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallback, true)
        observePreview(activity)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                f.view?.let {
                    if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment")
                        homeEntries.attach(it)
                    attach(f, it)
                }
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun attach(f: Fragment, root: View) {
        if (watchers.containsKey(f)) return
        if (f.javaClass.name !in setOf(
                "com.lagradost.cloudstream3.ui.result.ResultTrailerPlayer",
                "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
                "com.lagradost.cloudstream3.ui.result.ResultFragmentTv"
            )) return
        val apiName = f.arguments?.getString("apiName") ?: return
        if (apiName != "StremioC" && !APIHolder.allProviders.any {
                it is StremioC && it.name.equals(apiName, ignoreCase = true)
            }) return
        val resId = root.resources.getIdentifier("result_description", "id", root.context.packageName)
        if (resId == 0) return
        val view = root.findViewById<TextView>(resId) ?: return
        watchers[f] = Watcher(view, root, f.arguments?.getString("url"), f).also { it.start() }
    }

    private inner class Watcher(private val view: TextView, private val root: View, private val detailUrl: String?, private val fragment: Fragment) : TextWatcher {
        private val backdrop = DetailBackdropLayout(root, detailUrl)
        private val trailers = TrailerChooser(fragment, root, detailUrl)
        private var changing = false
        private val searchButton = listOf("result_search_Button", "result_search").firstNotNullOfOrNull {
            val id = root.resources.getIdentifier(it, "id", root.context.packageName)
            if (id == 0) null else root.findViewById<View>(id)
        }
        private val searchTitle = root.resources.getIdentifier("result_title", "id", root.context.packageName)
            .takeIf { it != 0 }?.let { root.findViewById<TextView>(it) }
        private val castItems = root.resources.getIdentifier("result_cast_items", "id", root.context.packageName)
            .takeIf { it != 0 }?.let { root.findViewById<RecyclerView>(it) }
        private val actorNameId = root.resources.getIdentifier("actor_name", "id", root.context.packageName)
        private val genreGroup = root.findViewById<ChipGroup>(R.id.result_tag)
        private val boundGenreChips = mutableMapOf<View, CatalogShortcutTarget>()
        private val actorPhotoForegrounds = mutableMapOf<View, Pair<ImageView, Drawable?>>()

        private fun bindActorPhotoRipple(card: View) {
            if (actorPhotoForegrounds.containsKey(card)) return
            val photo = card.findViewById<ImageView>(R.id.actor_image) ?: return
            val original = photo.foreground
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.WHITE)
            }
            photo.foreground = RippleDrawable(
                ColorStateList.valueOf(STREMIOC_DETAIL_RIPPLE_COLOR), original, mask
            )
            card.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        photo.foreground?.setHotspot(photo.width / 2f, photo.height / 2f)
                        photo.isPressed = true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> photo.isPressed = false
                }
                false // Preserve existing actor-name search click and long-press behavior.
            }
            actorPhotoForegrounds[card] = photo to original
        }

        private fun bindGenreClicks() {
            val group = genreGroup ?: return
            val targets = detailUrl?.let { StremioC.genreCatalogsByUrl[it] }.orEmpty()
            val children = (0 until group.childCount).map(group::getChildAt)
            boundGenreChips.keys.retainAll(children)
            children.forEach { chip ->
                val target = targets[(chip as? TextView)?.text?.toString()]
                if (boundGenreChips[chip] == target) return@forEach
                if (target == null) {
                    if (boundGenreChips.remove(chip) != null) {
                        chip.setOnClickListener(null)
                        chip.setOnTouchListener(null)
                        chip.isClickable = false
                        chip.isFocusable = false
                    }
                } else {
                    (chip as? Chip)?.rippleColor = ColorStateList.valueOf(STREMIOC_DETAIL_RIPPLE_COLOR)
                    chip.setOnTouchListener { view, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            view.background?.setHotspot(event.x, event.y)
                            view.isPressed = true
                        }
                        false
                    }
                    chip.setOnClickListener { openCatalog(target, null) }
                    chip.isFocusable = true
                    boundGenreChips[chip] = target
                }
            }
        }

        private fun openSearch(query: String) {
            if (query.isBlank()) return
            val id = root.resources.getIdentifier("navigation_search", "id", root.context.packageName)
            val enter = root.resources.getIdentifier("enter_anim", "anim", root.context.packageName)
            val exit = root.resources.getIdentifier("exit_anim", "anim", root.context.packageName)
            if (id == 0) return
            val activity = fragment.activity ?: return
            stremioSearchSession = true
            freshSearchPending = true
            lastSearchQuery = query
            activity.navigate(id, Bundle().apply {
                putString("search_query", query)
                putBoolean("stremioc_detail_search", true)
            }, NavOptions.Builder()
                .setEnterAnim(enter).setExitAnim(exit)
                .setPopEnterAnim(enter).setPopExitAnim(exit)
                .build())
        }

        private val searchClick = View.OnClickListener {
            openSearch(searchTitle?.text?.toString()?.trim().orEmpty()
                .ifEmpty { fragment.arguments?.getString("name")?.trim().orEmpty() })
        }
        private val castClick = View.OnClickListener { card ->
            if (actorNameId != 0) {
                openSearch(card.findViewById<TextView>(actorNameId)?.text?.toString()?.trim().orEmpty())
            }
        }
        private val detailPreDraw = ViewTreeObserver.OnPreDrawListener {
            if (fragment.isAdded) {
                if (searchButton?.isShown == true) searchButton.setOnClickListener(searchClick)
                castItems?.takeIf { it.isShown }?.let { list ->
                    for (i in 0 until list.childCount) {
                        val card = list.getChildAt(i)
                        card.setOnClickListener(castClick)
                        bindActorPhotoRipple(card)
                    }
                }
                bindGenreClicks()
            }
            true
        }
        fun start() {
            view.addTextChangedListener(this)
            searchButton?.setOnClickListener(searchClick)
            if (searchButton != null || castItems != null || genreGroup != null)
                root.viewTreeObserver.addOnPreDrawListener(detailPreDraw)
            runCatching { backdrop.start() }; runCatching { trailers.start() }; runCatching { render() }
        }
        fun detach() {
            view.removeTextChangedListener(this)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(detailPreDraw)
            searchButton?.setOnClickListener(null)
            boundGenreChips.keys.forEach { chip ->
                chip.setOnClickListener(null)
                chip.setOnTouchListener(null)
                chip.isClickable = false
                chip.isFocusable = false
            }
            boundGenreChips.clear()
            actorPhotoForegrounds.forEach { (card, pair) ->
                card.setOnTouchListener(null)
                pair.first.isPressed = false
                pair.first.foreground = pair.second
            }
            actorPhotoForegrounds.clear()
            runCatching { trailers.stop() }; runCatching { backdrop.stop() }; runCatching { restore() }
        }
        private fun restore() {
            val old = view.text as? Spanned ?: return
            val spans = old.getSpans(0, old.length, LogoSpan::class.java)
            if (spans.isEmpty()) return
            val plain = SpannableStringBuilder(old)
            spans.forEach(plain::removeSpan)
            changing = true
            try { view.setText(plain, TextView.BufferType.SPANNABLE) }
            finally { changing = false }
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { if (!changing) { runCatching { trailers.refresh() }; runCatching { render() } } }

        fun render() {
            if (!prefs.getBoolean("mdblist_enabled", false)) { restore(); return }
            val result = decoratedRatings(view.text ?: return, view.textSize) ?: return
            changing = true
            try { view.setText(result, TextView.BufferType.SPANNABLE) }
            finally { changing = false }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) runCatching { use(activity) }
    }
    override fun onActivityDestroyed(activity: Activity) { if (host === activity) {
        detachPreview()
        detailRevealEnd?.invoke()
        resetSearchState()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        watchers.values.toList().forEach { it.detach() }
        watchers.clear(); host = null
    } }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
