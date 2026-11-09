
package com.hsp1020

import coil3.SingletonImageLoader
import okhttp3.OkHttpClient
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel

import androidx.appcompat.content.res.AppCompatResources
import android.util.TypedValue
import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.R
import org.json.JSONArray
import org.json.JSONObject
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.SubtitleHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.SyncWatchType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import kotlin.math.round
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.os.Build
import android.content.Context
import android.icu.util.ULocale
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.ui.settings.getCurrentLocale
import android.content.res.Resources
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
import android.widget.ImageView
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import android.widget.FrameLayout
import android.widget.Checkable
import androidx.media3.common.Player
import com.google.android.material.chip.ChipGroup
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.AppCompatSpinner
import androidx.recyclerview.widget.RecyclerView
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.result.ResultData
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.SyncViewModel
import com.lagradost.cloudstream3.ui.result.ActorAdaptor
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import kotlin.math.roundToInt
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.HeaderViewListAdapter
import android.widget.ListView
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.database.DataSetObserver
import android.view.MotionEvent
import java.util.WeakHashMap
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.SubtitleCue
import com.lagradost.cloudstream3.ui.player.SubtitleOffsetItemAdapter
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.utils.AppContextUtils.sortSubs
/** Reuse CloudStream's font family while preserving the view's original weight and size. */
internal fun TextView.applyStremioCNativeFont(
    bold: Boolean = typeface?.isBold == true,
    header: Boolean = false
) {
    val originalSize = textSize
    val original = typeface
    if (header) setTextAppearance(R.style.WatchHeaderText)
    val family = ResourcesCompat.getFont(context, R.font.google_sans)
    if (family != null) {
        typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(family, if (bold) 700 else original?.weight ?: 400, original?.isItalic == true)
        } else {
            val style = original?.style ?: Typeface.NORMAL
            Typeface.create(family, if (bold) style or Typeface.BOLD else style)
        }
    }
    if (header) setTextSize(TypedValue.COMPLEX_UNIT_PX, originalSize)
}

internal fun themedPressedForeground(view: View): Drawable? {
    val out = TypedValue()
    return if (view.context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, out, true
        ) && out.resourceId != 0)
        AppCompatResources.getDrawable(view.context, out.resourceId)?.mutate()
    else null
}

internal fun tvOutlinedForeground(view: View, pressed: Drawable? = themedPressedForeground(view)): Drawable? {
    if (!isLayout(TV)) return pressed
    val outline = AppCompatResources.getDrawable(view.context, R.drawable.outline_drawable)?.mutate()
    return when {
        pressed != null && outline != null ->
            android.graphics.drawable.LayerDrawable(arrayOf(pressed, outline))
        else -> outline ?: pressed
    }
}

internal fun applyEpisodeChipFeedback(chip: Chip) {
    chip.rippleColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
    chip.foreground = tvOutlinedForeground(chip)
    chip.stateListAnimator = null
    chip.tag = chip.context.getString(R.string.tv_no_focus_tag)
}

private val QUALITY_REGEX = Regex("(\\d{3,4}[pP])")
private val TT_REGEX = Regex("^tt\\d+$")

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
    private val homeEntries = CatalogHomeEntryHook(openCatalog, ::bindHomePreview) { _ -> }
    private var homeRoot: View? = null
    private var removeHomeProviderObserver: (() -> Unit)? = null
    private fun syncHomeHooks() {
        val activity = host
        val selected = activity?.let { ViewModelProvider(it)[HomeViewModel::class.java].apiName.value }
        if (selected != null && APIHolder.allProviders.any {
                it is StremioC && it.hasMainPage && it.name == selected
            }) {
            homeRoot?.let { homeEntries.attach(it) }
        } else {
            homeEntries.detach()
        }
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
    private fun bindHomePreview(view: TextView?) {
        val next = view.takeIf { isLayout(TV or EMULATOR) }
        if (homePreviewText !== next) styleTvHero(null)
        styleTvHero(next)
        homePreviewText = next
    }

    private val fragmentCallback = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
        ) {
            runCatching {
                if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment") {
                    homeRoot = v
                    syncHomeHooks()
                }
                attach(f, v)
            }
        }

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment")
                syncHomeHooks()

        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment") {
                homeRoot = null
                homeEntries.detach()
            }
            watchers.remove(f)?.detach()
        }
    }

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        removeHomeProviderObserver?.invoke()
        removeHomeProviderObserver = null
        homeRoot = null
        homeEntries.detach()
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        watchers.values.toList().forEach { it.detach() }
        watchers.clear()
        host = null
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        removeHomeProviderObserver?.invoke()
        removeHomeProviderObserver = null
        homeRoot = null
        homeEntries.detach()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        watchers.values.toList().forEach { it.detach() }
        watchers.clear()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallback, true)
        val homeModel = ViewModelProvider(activity)[HomeViewModel::class.java]
        val homeObserver = Observer<String?> { syncHomeHooks() }
        homeModel.apiName.observe(activity, homeObserver)
        removeHomeProviderObserver = { homeModel.apiName.removeObserver(homeObserver) }
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                f.view?.let {
                    if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment") {
                        homeRoot = it
                        syncHomeHooks()
                    }
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
        watchers[f] = Watcher(view, root, f.arguments?.getString("url"), f).also {
            it.start()
        }
    }

    private inner class Watcher(private val view: TextView, private val root: View, private val detailUrl: String?, private val fragment: Fragment) : TextWatcher {
        private val genreGroup = root.findViewById<ChipGroup>(R.id.result_tag)
        private val boundGenreChips = mutableMapOf<View, CatalogShortcutTarget>()

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
                    (chip as? Chip)?.let(::applyEpisodeChipFeedback)
                    chip.setOnClickListener {
                        openCatalog(target, null)
                    }
                    chip.isFocusable = true
                    boundGenreChips[chip] = target
                }
            }
        }

        private val detailLayout = ViewTreeObserver.OnGlobalLayoutListener {
            if (fragment.isAdded) bindGenreClicks()
        }
        fun start() {
            val model = ViewModelProvider(fragment)[ResultViewModel2::class.java]
            fun applyRecommendation(update: StremioC.Companion.RecommendationUpdate) {
                val page = (model.page.value as? Resource.Success<ResultData>)?.value
                if (page?.url != update.url) {
                    return
                }
                @Suppress("UNCHECKED_CAST")
                val target = model.recommendations as? androidx.lifecycle.MutableLiveData<List<SearchResponse>>
                if (target == null) {
                    return
                }
                target.value = update.cards
            }
            model.page.observe(fragment.viewLifecycleOwner) { value ->
                val page = (value as? Resource.Success<ResultData>)?.value ?: return@observe
                StremioC.latestRecommendationFor(page.url)?.let(::applyRecommendation)
            }
            StremioC.recommendationUpdates.observe(fragment.viewLifecycleOwner) { update ->
                update?.let(::applyRecommendation)
            }
            view.addTextChangedListener(this)
            if (genreGroup != null)
                root.viewTreeObserver.addOnGlobalLayoutListener(detailLayout)
            detailLayout.onGlobalLayout()
        }
        fun detach() {
            view.removeTextChangedListener(this)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(detailLayout)
            boundGenreChips.keys.forEach { chip ->
                chip.setOnClickListener(null)
                chip.setOnTouchListener(null)
                chip.isClickable = false
                chip.isFocusable = false
            }
            boundGenreChips.clear()
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = Unit

    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) runCatching { use(activity) }
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) {
        removeHomeProviderObserver?.invoke()
        removeHomeProviderObserver = null
        homeRoot = null
        homeEntries.detach()
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

/** Apply the existing image concurrency policy without replacing the native Coil loader. */
internal object NativeCoilDispatcher {
    @Synchronized
    fun configure(context: Context) {
        try {
            val loader = SingletonImageLoader.get(context.applicationContext)
            val clients = ArrayList<OkHttpClient>()
            for ((factory, _) in loader.components.fetcherFactories) {
                if (!factory.javaClass.name.contains("NetworkFetcher")) continue
                try {
                    val lazyField = factory.javaClass.getDeclaredField("networkClientLazy")
                        .apply { isAccessible = true }
                    val networkClient = (lazyField.get(factory) as Lazy<*>).value ?: continue
                    val clientField = networkClient.javaClass.getDeclaredField("callFactory")
                        .apply { isAccessible = true }
                    val client = clientField.get(networkClient) as? OkHttpClient ?: continue
                    if (clients.any { it === client }) continue
                    clients.add(client)
                    client.dispatcher.maxRequests = 64
                    client.dispatcher.maxRequestsPerHost = 32
                } catch (_: Throwable) {
                    // Fail open when a different Coil fetcher implementation is installed.
                }
            }
        } catch (_: Throwable) {
            // Never block plugin startup if the native image loader is unavailable.
        }
    }
}


/** StremioC-only metadata assembly; the presentation layer reads standard LoadResponse metadata. */
internal data class TrailerChoice(
    val youtubeId: String,
    val title: String,
    val type: String,
    val language: String? = null
)
internal data class StremioTrailerCandidate(
    val key: String?,
    val title: String?,
    val type: String?,
    val language: String?,
    val region: String?,
    val publishedAt: String?
)
internal fun parseTrailerYoutubeId(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    fun valid(id: String) = id.length == 11 && id.all {
        (it.isLetterOrDigit() && it.code < 128) || it == '_' || it == '-'
    }
    if (valid(value)) return value
    return runCatching {
        val uri = android.net.Uri.parse(value)
        val host = uri.host?.lowercase() ?: return@runCatching null
        val segments = uri.pathSegments
        val candidate = when {
            host == "youtu.be" || host.endsWith(".youtu.be") -> segments.firstOrNull()
            host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ->
                uri.getQueryParameter("v") ?: segments.takeIf {
                    it.firstOrNull() in listOf("embed", "shorts", "live", "v")
                }?.getOrNull(1)
            else -> null
        }
        candidate?.takeIf(::valid)
    }.getOrNull()
}
internal object StremioTmdb {
    val apiKey: String by lazy {
        runCatching {
            Class.forName(
                "com.lagradost.cloudstream3.ui.search.SearchSuggestionApi",
                false, CloudStreamApp::class.java.classLoader
            ).getDeclaredField("TMDB_API_KEY").apply { isAccessible = true }
                .get(null) as? String
        }.getOrNull().orEmpty()
    }
    private val isoLanguages = Locale.getISOLanguages().toSet()
    fun language(): String {
        val context = CloudStreamApp.context ?: return "en-US"
        val saved = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context.getString(R.string.locale_key), null)
        val locale = Locale.forLanguageTag(
            (saved?.takeIf { it.isNotBlank() } ?: getCurrentLocale(context)).replace('_', '-')
        )
        val language = locale.language.takeIf { it in isoLanguages } ?: return "en-US"
        val region = locale.country.ifBlank {
            // CloudStream distinguishes pt (Portugal) from pt-BR (Brazil).
            if (language == "pt") "PT"
            else ULocale.addLikelySubtags(ULocale.forLocale(locale)).country
        }
        return if (region.length == 2) "${language}-${region}" else language
    }
}

internal fun tmdbTrailerLanguages(): List<String> {
    val configured = StremioTmdb.language()
    return listOf(configured, configured.substringBefore('-'), "en")
        .distinctBy { it.lowercase() }
}
internal fun collectStremioTrailers(
    tmdb: List<StremioTrailerCandidate>,
    fallback: List<TrailerChoice> = emptyList()
): List<TrailerChoice> {
    val languages = tmdbTrailerLanguages()
    val ranked = tmdb.mapNotNull { item ->
        if (item.type != "Trailer" && item.type != "Teaser") return@mapNotNull null
        val id = parseTrailerYoutubeId(item.key) ?: return@mapNotNull null
        val rank = languages.indexOfFirst { locale ->
            item.language.equals(locale.substringBefore('-'), true) &&
                (!locale.contains('-') || item.region.equals(locale.substringAfter('-'), true))
        }
        if (rank < 0) null else Triple(rank, item, id)
    }
    val best = ranked.minOfOrNull { it.first }
    val selected = ranked.asSequence().filter { it.first == best }
        .sortedWith(compareBy({ if (it.second.type == "Trailer") 0 else 1 },
            { it.second.publishedAt ?: "9999-12-31" }))
        .map { (_, item, id) ->
            TrailerChoice(id, item.title?.takeIf { it.isNotBlank() } ?: (item.type ?: "Trailer"),
                item.type ?: "Trailer", item.language)
        }.toList()
    return selected.ifEmpty { fallback }.distinctBy { it.youtubeId }.take(30)
}
internal fun serializeStremioTrailers(items: List<TrailerChoice>): String =
    JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("url", "https://www.youtube.com/watch?v=${item.youtubeId}")
                put("title", item.title)
                put("type", item.type)
                item.language?.let { put("language", it) }
            })
        }
    }.toString()

/** TMDB's language selection is StremioC-specific; Elevate only consumes the result flag. */
internal fun stremioLogoNeedsTitle(
    logo: String?, tmdbLogo: String?, tmdbFallback: Boolean
): Boolean = !logo.isNullOrBlank() && (tmdbLogo.isNullOrBlank() || tmdbFallback)

