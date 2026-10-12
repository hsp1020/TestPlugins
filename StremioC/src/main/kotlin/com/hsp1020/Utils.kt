// 파일명: hsp1020/Utils.kt
package com.hsp1020

import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import org.json.JSONArray
import org.json.JSONObject
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlin.math.round
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationBarView
import androidx.navigation.NavOptions
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlin.math.roundToInt
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
    private val resources: Resources
) : Application.ActivityLifecycleCallbacks {
    private val app = context.applicationContext as Application
    private val prefs = app.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var host: FragmentActivity? = null
    private val watchers = mutableMapOf<Fragment, Watcher>()

    // Retain the last result without re-querying when the host recreates SearchFragment.
    private var lastSearchQuery: String? = null
    private var stremioSearchSession = false
    private var homeMenuOwner: Fragment? = null
    private var homeMenuItems = emptyList<android.view.MenuItem>()

    private fun releaseHomeMenu(owner: Fragment? = null) {
        if (owner != null && homeMenuOwner !== owner) return
        homeMenuItems.forEach { it.setOnMenuItemClickListener(null) }
        homeMenuItems = emptyList()
        homeMenuOwner = null
    }

    private fun setupSearchNavigation(f: Fragment, root: View) {
        if (!stremioSearchSession) return
        val activity = f.activity ?: return
        fun id(name: String) = root.resources.getIdentifier(name, "id", root.context.packageName)
        if (f.arguments?.getString("search_query").isNullOrBlank() &&
            MainActivity.nextSearchQuery.isNullOrBlank()) {
            root.findViewById<SearchView>(id("main_search"))?.let { search ->
                if (search.query.isNullOrBlank()) lastSearchQuery?.let { search.setQuery(it, false) }
            }
        }
        val homeId = id("navigation_home")
        val searchId = id("navigation_search")
        if (homeId == 0 || searchId == 0) return
        val navHost = activity.supportFragmentManager.findFragmentById(id("nav_host_fragment"))
            as? androidx.navigation.fragment.NavHostFragment ?: return
        releaseHomeMenu()
        homeMenuOwner = f
        homeMenuItems = listOf("nav_view", "nav_rail_view").mapNotNull { name ->
            activity.findViewById<NavigationBarView>(id(name))?.menu?.findItem(homeId)?.apply {
                setOnMenuItemClickListener {
                    if (!f.isAdded || f.view !== root ||
                        navHost.navController.currentDestination?.id != searchId) return@setOnMenuItemClickListener false
                    runCatching {
                        val nav = navHost.navController
                        if (!nav.popBackStack(homeId, false, false))
                            nav.navigate(homeId, null, NavOptions.Builder()
                                .setLaunchSingleTop(true).setRestoreState(false).build())
                        true
                    }.getOrElse { error ->
                        android.util.Log.e("StremioCNavMenu", "HOME navigation failed", error)
                        false
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

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "mdblist_enabled") main.post {
            watchers.values.toList().forEach { runCatching { it.render() } }
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
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (stremioSearchSession &&
                f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment")
                resetSearchState()
        }

        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
        ) {
            runCatching {
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
        lastSearchQuery = null
    }

    fun stop() {
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
        detailRevealEnd?.invoke()
        resetSearchState()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        watchers.values.toList().forEach { it.detach() }
        watchers.clear()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallback, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                f.view?.let { attach(f, it) }
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

    private inner class Watcher(private val view: TextView, private val root: View, detailUrl: String?, private val fragment: Fragment) : TextWatcher {
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

        private fun openSearch(query: String) {
            if (query.isBlank()) return
            val id = root.resources.getIdentifier("navigation_search", "id", root.context.packageName)
            val enter = root.resources.getIdentifier("enter_anim", "anim", root.context.packageName)
            val exit = root.resources.getIdentifier("exit_anim", "anim", root.context.packageName)
            if (id == 0) return
            val activity = fragment.activity ?: return
            stremioSearchSession = true
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
                    for (i in 0 until list.childCount) list.getChildAt(i).setOnClickListener(castClick)
                }
            }
            true
        }
        fun start() {
            view.addTextChangedListener(this)
            searchButton?.setOnClickListener(searchClick)
            if (searchButton != null || castItems != null)
                root.viewTreeObserver.addOnPreDrawListener(detailPreDraw)
            runCatching { backdrop.start() }; runCatching { trailers.start() }; runCatching { render() }
        }
        fun detach() {
            view.removeTextChangedListener(this)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(detailPreDraw)
            searchButton?.setOnClickListener(null)
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
            val original = view.text ?: return
            if (original is Spanned && original.getSpans(0, original.length, LogoSpan::class.java).isNotEmpty()) return
            // Invisible separator U+2063 is inserted ONLY for actual MDBList rows.
            if (!original.startsWith('\u2063')) return
            val end = original.indexOf('\n').let { if (it < 0) original.length else it }
            val line = original.subSequence(0, end).toString()
            val replacements = mutableListOf<Triple<Int, Int, String>>()
            for ((glyph, file) in icons) {
                var from = 1
                while (true) {
                    val at = line.indexOf(glyph, from)
                    if (at < 0) break
                    if (number.containsMatchIn(line.substring(at + glyph.length))) {
                        replacements.add(Triple(at, at + glyph.length, file))
                    }
                    from = at + glyph.length
                }
            }
            if (replacements.isEmpty()) return
            val result = SpannableStringBuilder(original)
            var applied = 0
            val height = (view.textSize * 0.92f).roundToInt().coerceAtLeast(12)
            for ((start, endIndex, file) in replacements) {
                val id = ids.getOrPut(file) {
                    resources.getIdentifier(file, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
                }
                if (id == 0) continue
                val image = runCatching { resources.getDrawable(id, null).mutate() }.getOrNull() ?: continue
                val width = (height.toFloat() * image.intrinsicWidth.coerceAtLeast(1) /
                    image.intrinsicHeight.coerceAtLeast(1)).roundToInt().coerceIn(height / 2, height * 3)
                image.setBounds(0, 0, width, height)
                result.setSpan(LogoSpan(image), start, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                applied++
            }
            if (applied == 0) return
            changing = true
            try { view.setText(result, TextView.BufferType.SPANNABLE) }
            finally { changing = false }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) runCatching { use(activity) }
    }
    override fun onActivityDestroyed(activity: Activity) { if (host === activity) {
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
