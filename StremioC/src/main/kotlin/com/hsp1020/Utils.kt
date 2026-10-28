
package com.hsp1020

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
import android.os.SystemClock
import android.view.animation.LinearInterpolator
import android.os.Build
import android.content.Context
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
import android.widget.FrameLayout
import android.widget.Checkable
import androidx.media3.common.Player
import com.google.android.material.chip.ChipGroup
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.AppCompatSpinner
import androidx.recyclerview.widget.RecyclerView
import androidx.navigation.NavOptions
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
internal fun themedPressedForeground(view: View): Drawable? {
    val out = TypedValue()
    return if (view.context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, out, true
        ) && out.resourceId != 0)
        AppCompatResources.getDrawable(view.context, out.resourceId)?.mutate()
    else null
}

internal fun applyEpisodeChipFeedback(chip: Chip) {
    chip.rippleColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
    chip.foreground = themedPressedForeground(chip)
    chip.stateListAnimator = null
    chip.tag = chip.context.getString(R.string.tv_no_focus_tag)
}

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
    if (!mdbListEnabled || mdbListApiKey.isBlank()) {
        return emptyList()
    }
    return try {
        val provider = if (isTmdb) "tmdb" else "imdb"
        val type = if (mediaType == "movie") "movie" else "show"
        val modernUrl = "https://api.mdblist.com/$provider/$type/$id/?apikey=$mdbListApiKey&append_to_response=keyword"
        val json = try {
            val response = app.get(modernUrl, timeout = 15L)
            JSONObject(response.text).also {
                if (it.optJSONArray("ratings") == null) error("MDBList missing ratings")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            val idParam = if (isTmdb) "tm" else "i"
            val response = app.get("https://mdblist.com/api/?apikey=$mdbListApiKey&$idParam=$id", timeout = 15L)
            JSONObject(response.text)
        }
        val ratingsArray = json.optJSONArray("ratings") ?: run {
            return emptyList()
        }
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

internal class StremioSubtitleController(private val pluginResources: Resources) {
    private companion object {
        const val AUTO_REFRESH_INTERVAL_MS = 10_000L
        const val AUTO_REFRESH_DURATION_MS = 300_000L
        const val AUTO_REFRESH_MAX_REQUESTS = 30
    }
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var focusTree: ViewTreeObserver? = null
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        fragment?.view?.post { inspectDialogs() }
    }
    private var lastInstance = -1
    private var sortedDialog: Dialog? = null
    private var pendingDialog: Dialog? = null
    private var isStremio = false
    private val refreshedOverlay = StremioRefreshedSubtitleOverlay()
    private val refreshedCues = linkedMapOf<String, StremioSubtitleRefreshRegistry.Prepared>()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private var autoRefreshUrl: String? = null
    private var autoSpinView: ImageButton? = null
    private var autoSpinAnimator: ObjectAnimator? = null
    private var observedPlayer: Player? = null
    private var selectedUrl: String? = null
    private var iconSelectionUrl: String? = null
    private data class DeferredRefresh(
        val entry: StremioSubtitleRefreshRegistry.Entry, val automatic: Boolean,
        val instance: Int, val videoId: Int?
    )
    private var queuedRefresh: DeferredRefresh? = null
    private var appliedRefresh: DeferredRefresh? = null
    private var refreshedOffsetDialog: Dialog? = null
    private var removeStateObserver: (() -> Unit)? = null
    private val playerListener = java.lang.reflect.Proxy.newProxyInstance(
        Player.Listener::class.java.classLoader,
        arrayOf(Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onEvents" -> {
                updateRefreshedSubtitle()
                null
            }
            "onMediaItemTransition" -> {
                val deferred = appliedRefresh?.takeIf {
                    it.instance == model?.state?.instance &&
                        it.videoId == model?.state?.generatorState?.id
                }
                clearRefreshState()
                appliedRefresh = deferred
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "StremioSubtitlePlayerListener"
            else -> null
        }
    } as Player.Listener

    private fun clearRefreshState() {
        refreshGeneration++
        refreshJob?.cancel()
        refreshJob = null
        endAutoRefresh()
        refreshedCues.clear()
        refreshedOverlay.detach()
        refreshedOffsetDialog = null
        iconSelectionUrl = null
        queuedRefresh = null
        appliedRefresh = null
    }

    private fun bindPlayer() {
        val current = fragment?.playerView?.player
        if (current === observedPlayer) return
        observedPlayer?.removeListener(playerListener)
        val deferred = appliedRefresh?.takeIf {
            it.instance == model?.state?.instance &&
                it.videoId == model?.state?.generatorState?.id
        }
        clearRefreshState()
        appliedRefresh = deferred
        observedPlayer = current
        selectedUrl = fragment?.player?.getCurrentPreferredSubtitle()?.url
        current?.addListener(playerListener)
        runAppliedRefresh()
    }

    private fun runAppliedRefresh() {
        val deferred = appliedRefresh ?: return
        val target = fragment ?: return
        if (model?.state?.instance != deferred.instance ||
            model?.state?.generatorState?.id != deferred.videoId) {
            appliedRefresh = null
            return
        }
        if (target.playerView?.player == null ||
            target.player.getCurrentPreferredSubtitle()?.url != deferred.entry.originalUrl) return
        appliedRefresh = null
        requestSubtitleRefresh(deferred.entry, null, deferred.automatic)
    }

    private fun refreshPickerIcons(url: String?) {
        if (iconSelectionUrl == url) return
        iconSelectionUrl = url
        val dialog = sortedDialog?.takeIf { it.isShowing } ?: return
        (dialog.findViewById<ListView>(R.id.sort_subtitles)?.adapter as? BaseAdapter)
            ?.notifyDataSetChanged()
        (dialog.findViewById<ListView>(R.id.sort_subtitles_options)?.adapter as? BaseAdapter)
            ?.notifyDataSetChanged()
    }

    private fun updateRefreshedSubtitle() {
        val target = fragment ?: return
        if (model?.state?.instance != lastInstance) {
            clearRefreshState()
            return
        }
        val currentUrl = target.player.getCurrentPreferredSubtitle()?.url
        refreshPickerIcons(currentUrl)
        if (selectedUrl != currentUrl) {
            refreshGeneration++
            refreshJob?.cancel()
            refreshJob = null
            endAutoRefresh()
            refreshedOverlay.detach()
            selectedUrl = currentUrl
        }
        refreshedOverlay.bind(target)
        currentUrl?.let { url ->
            refreshedCues[url]?.let { refreshedOverlay.attach(target, url, it) }
        }
        refreshedOverlay.onPlayerEvent()
        runAppliedRefresh()
    }
    private val nativeSelectedSubtitleField = runCatching {
        GeneratorPlayer::class.java.getDeclaredField("currentSelectedSubtitles")
            .apply { isAccessible = true }
    }.getOrNull()
    private val dialogField = runCatching {
        FullScreenPlayer::class.java.getDeclaredField("selectSourceDialog")
            .apply { isAccessible = true }
    }.getOrNull()
    private val offsetDialogField = runCatching {
        FullScreenPlayer::class.java.getDeclaredField("selectSubtitlesDialog")
            .apply { isAccessible = true }
    }.getOrNull()

    // Reuse the native offset adapter so its seek/offset and progress behavior stays intact.
    private fun showRefreshedCuesInOffsetDialog(dialog: Dialog) {
        val target = fragment ?: return
        val url = target.player.getCurrentPreferredSubtitle()?.url ?: return
        val prepared = refreshedCues[url] ?: return
        val list = dialog.findViewById<RecyclerView>(R.id.subtitle_offset_recyclerview) ?: return
        val adapter = list.adapter as? SubtitleOffsetItemAdapter ?: return
        val items = prepared.cues.mapNotNull { segment ->
            val lines = segment.cues.mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }
            if (lines.isEmpty()) null else SubtitleCue(
                segment.startTimeMs, (segment.endTimeMs - segment.startTimeMs).coerceAtLeast(1L), lines
            )
        }
        if (items.isEmpty()) return
        adapter.submitList(items)
        list.visibility = View.VISIBLE
        dialog.findViewById<View>(R.id.no_subtitles_loaded_notice)?.visibility = View.GONE
        val position = (target.player.getPosition() ?: 0L) + target.player.getSubtitleOffset()
        val index = items.indexOfLast { it.startTimeMs <= position }.coerceAtLeast(0)
        list.post { if (dialog.isShowing && list.adapter === adapter) list.scrollToPosition(index) }
    }

    private fun checkStremio(): Boolean {
        val response = model?.state?.generatorState?.response ?: return false
        return response.apiName.equals("StremioC", true) ||
            APIHolder.allProviders.any { it is StremioC && it.name.equals(response.apiName, true) }
    }

    // Run only when a dialog changes window focus or player state changes.
    private fun inspectDialogs() {
        val f = fragment ?: return
        val state = model?.state ?: return
        if (lastInstance != state.instance) {
            lastInstance = state.instance
            sortedDialog = null
            clearRefreshState()
            isStremio = checkStremio()
        }
        if (!isStremio) return
        bindPlayer()
        val popup = runCatching { dialogField?.get(f) as? Dialog }.getOrNull()
        if (popup != null && popup.isShowing &&
            popup !== sortedDialog && popup !== pendingDialog) {
            pendingDialog = popup
            f.view?.post {
                pendingDialog = null
                if (popup.isShowing && fragment === f && sortPopup(popup)) {
                    installRefreshIcons(popup)
                    sortedDialog = popup
                }
            }
        }
        refreshPickerIcons(f.player.getCurrentPreferredSubtitle()?.url)
        val offsetDialog = runCatching { offsetDialogField?.get(f) as? Dialog }.getOrNull()
        if (offsetDialog != null && offsetDialog.isShowing &&
            offsetDialog !== refreshedOffsetDialog) {
            refreshedOffsetDialog = offsetDialog
            showRefreshedCuesInOffsetDialog(offsetDialog)
        }
    }

    fun watch(candidate: Fragment) {
        if (candidate !is GeneratorPlayer || candidate === fragment) return
        detach()
        fragment = candidate
        model = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
        val currentModel = model ?: return
        val observer = Observer<com.lagradost.cloudstream3.ui.player.VideoLive<Resource<Unit>>> {
            if (lastInstance != currentModel.state.instance) {
                clearRefreshState()
                lastInstance = currentModel.state.instance
                isStremio = checkStremio()
                sortedDialog = null
            }
            if (isStremio) bindPlayer()
        }
        currentModel.loadingLinks.observe(candidate.viewLifecycleOwner, observer)
        val linksObserver = Observer<com.lagradost.cloudstream3.ui.player.VideoLive<Set<com.lagradost.cloudstream3.ui.player.VideoLink>>> {
            if (isStremio) bindPlayer()
        }
        currentModel.currentLinks.observe(candidate.viewLifecycleOwner, linksObserver)
        removeStateObserver = {
            currentModel.loadingLinks.removeObserver(observer)
            currentModel.currentLinks.removeObserver(linksObserver)
        }
        candidate.activity?.window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.let {
            focusTree = it
            it.addOnWindowFocusChangeListener(focusListener)
        }
        inspectDialogs()
    }

    private fun sortPopup(dialog: Dialog): Boolean {
        val state = model?.state ?: return false
        val list = dialog.findViewById<ListView>(R.id.sort_subtitles) ?: return false
        val wrapper = list.adapter
        val adapter = (if (wrapper is HeaderViewListAdapter) wrapper.wrappedAdapter else wrapper)
            as? ArrayAdapter<*> ?: return false

        val grouped = sortSubs(state.subtitles).groupBy { it.originalName }.values.toList()
        // Subtitle results can arrive after the native dialog captured its rows.
        // Keep the native order when the live model has a different group count.
        if (adapter.count != grouped.size + 1) return true
        val texts = (0 until adapter.count).map { adapter.getItem(it) as? Spanned ?: return false }
        val langs = grouped.map { it.first().getIETF_tag() ?: it.first().originalName }
        val starts = langs.withIndex().groupBy({ it.value }, { it.index })
            .mapValues { (_, positions) -> positions.first() }
        val ranks = grouped.map { sameName ->
            sameName.minOf { sub ->
                StremioC.subtitleOrderByUrl[sub.url] ?: when {
                    sub.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO -> 30_000
                    SubtitleHelper.fromLanguageToTagIETF(sub.originalName)?.let {
                        SubtitleHelper.fromTagToEnglishLanguageName(it)
                    }?.equals(sub.originalName, true) == true -> 0
                    else -> 40_000
                }
            }
        }
        val order = grouped.indices.sortedWith(
            compareBy<Int> { starts[langs[it]] ?: it }.thenBy { ranks[it] }.thenBy { it }
        )

        if (order.indices.all { order[it] == it }) return true
        val originalClick = list.onItemClickListener ?: return false
        val previouslySelected = list.checkedItemPosition
        @Suppress("UNCHECKED_CAST")
        val editable = adapter as ArrayAdapter<Spanned>
        editable.clear()
        editable.addAll(listOf(texts.first()) + order.map { texts[it + 1] })
        list.setTag(R.id.sort_subtitles, order.toIntArray())
        list.onItemClickListener = AdapterView.OnItemClickListener { parent, view, position, id ->
            val originalPosition = if (position in 1..order.size) order[position - 1] + 1 else position
            originalClick.onItemClick(parent, view, originalPosition, id)
            list.setItemChecked(position, true)
        }
        val selected = if (previouslySelected in 1..order.size)
            order.indexOf(previouslySelected - 1) + 1 else previouslySelected
        if (selected >= 0) {
            list.setItemChecked(selected, true)
            list.setSelection(selected)
        }
        return true
    }


    /**
     * Decorates only the native rows. Underlying ArrayAdapters, indices, selection
     * listeners and the StremioC order mapping remain unchanged.
     */
    private fun installRefreshIcons(dialog: Dialog) {
        val current = model?.state ?: return
        val list = dialog.findViewById<ListView>(R.id.sort_subtitles) ?: return
        val options = dialog.findViewById<ListView>(R.id.sort_subtitles_options) ?: return
        val byLabel = sortSubs(current.subtitles).groupBy { it.originalName }
            .mapKeys { (name, _) -> name.html().toString() }
        val parent = (list.adapter as? HeaderViewListAdapter)?.wrappedAdapter
            ?: list.adapter
        val adapter = parent as? ArrayAdapter<*> ?: return
        // Map the rows actually shown by CloudStream, not a newly loaded list.
        val groups = (1 until adapter.count).map { position ->
            byLabel[adapter.getItem(position)?.toString()].orEmpty()
        }
        fun groupAt(position: Int): List<SubtitleData>? =
            groups.getOrNull(position - 1)?.takeIf { it.isNotEmpty() }

        val selected = list.checkedItemPosition
        list.adapter = RefreshRowAdapter(adapter) { position ->
            groupAt(position)?.singleOrNull()?.let { StremioSubtitleRefreshRegistry.find(it.url) }
        }
        if (selected >= 0) list.setItemChecked(selected, true)

        val optionsParent = (options.adapter as? HeaderViewListAdapter)?.wrappedAdapter
            ?: options.adapter
        val optionsAdapter = optionsParent as? ArrayAdapter<*> ?: return
        val optionSelected = options.checkedItemPosition
        options.adapter = RefreshRowAdapter(optionsAdapter) { position ->
            val siblings = groupAt(list.checkedItemPosition) ?: return@RefreshRowAdapter null
            if (siblings.size < 2) return@RefreshRowAdapter null
            val ordered = siblings.sortedBy { it.nameSuffix.toIntOrNull() ?: 0 }
            ordered.getOrNull(position)?.let { StremioSubtitleRefreshRegistry.find(it.url) }
        }
        if (optionSelected >= 0) options.setItemChecked(optionSelected, true)
        iconSelectionUrl = fragment?.player?.getCurrentPreferredSubtitle()?.url

        fun checkedEntry(): StremioSubtitleRefreshRegistry.Entry? {
            val siblings = groupAt(list.checkedItemPosition) ?: return null
            val selected = if (siblings.size == 1) siblings.first()
                else siblings.sortedBy { it.nameSuffix.toIntOrNull() ?: 0 }
                    .getOrNull(options.checkedItemPosition)
            return selected?.let { StremioSubtitleRefreshRegistry.find(it.url) }
        }

        val target = fragment ?: return
        dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                val queued = queuedRefresh
                queuedRefresh = null
                if (queued != null && fragment === target &&
                    model?.state?.instance == queued.instance &&
                    model?.state?.generatorState?.id == queued.videoId &&
                    (runCatching {
                        nativeSelectedSubtitleField?.get(target) as? SubtitleData
                    }.getOrNull()?.url == queued.entry.originalUrl)) {
                    appliedRefresh = queued
                    updateRefreshedSubtitle()
                }
                if (sortedDialog === dialog) sortedDialog = null
            }
        })

        // TV remotes long-press the focused ListView item, not necessarily its child button.
        val groupLongClick = list.onItemLongClickListener
        list.onItemLongClickListener = AdapterView.OnItemLongClickListener { parentView, row, position, id ->
            val entry = if (list.checkedItemPosition == position)
                groupAt(position)?.singleOrNull()?.let { StremioSubtitleRefreshRegistry.find(it.url) }
            else null
            val button = (row as? RefreshRow)?.getChildAt(1) as? ImageButton
            if (entry != null && button != null) {
                requestOrQueueRefresh(entry, button, automatic = true)
                true
            } else groupLongClick?.onItemLongClick(parentView, row, position, id) ?: false
        }
        val optionLongClick = options.onItemLongClickListener
        options.onItemLongClickListener = AdapterView.OnItemLongClickListener { parentView, row, position, id ->
            val item = if (options.checkedItemPosition == position)
                groupAt(list.checkedItemPosition)
                    ?.sortedBy { it.nameSuffix.toIntOrNull() ?: 0 }?.getOrNull(position)
            else null
            val entry = item?.let { StremioSubtitleRefreshRegistry.find(it.url) }
            val button = (row as? RefreshRow)?.getChildAt(1) as? ImageButton
            if (entry != null && button != null) {
                requestOrQueueRefresh(entry, button, automatic = true)
                true
            } else optionLongClick?.onItemLongClick(parentView, row, position, id) ?: false
        }

        // The native group listener updates options before StremioC's visual
        // reorder mapping updates the checked index. Rebind icons afterwards.
        val nativeClick = list.onItemClickListener
        list.onItemClickListener = AdapterView.OnItemClickListener { parentView, row, position, id ->
            val previous = list.checkedItemPosition
            nativeClick?.onItemClick(parentView, row, position, id)
            if (position in 0..groups.size) list.setItemChecked(position, true)
            (list.getChildAt(previous - list.firstVisiblePosition) as? RefreshRow)
                ?.setChecked(list.isItemChecked(previous))
            (row as? RefreshRow)?.setChecked(list.isItemChecked(position))
            if (queuedRefresh?.entry?.originalUrl != checkedEntry()?.originalUrl) queuedRefresh = null
            (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
            (options.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }
        val nativeOptionClick = options.onItemClickListener
        options.onItemClickListener = AdapterView.OnItemClickListener { parentView, row, position, id ->
            val previous = options.checkedItemPosition
            nativeOptionClick?.onItemClick(parentView, row, position, id)
            if (position in 0 until optionsAdapter.count) options.setItemChecked(position, true)
            (options.getChildAt(previous - options.firstVisiblePosition) as? RefreshRow)
                ?.setChecked(options.isItemChecked(previous))
            (row as? RefreshRow)?.setChecked(options.isItemChecked(position))
            if (queuedRefresh?.entry?.originalUrl != checkedEntry()?.originalUrl) queuedRefresh = null
            (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
            (options.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }
    }

    private class RefreshRow(context: Context) : FrameLayout(context), Checkable {
        private var checked = false
        var onCheckedChanged: (() -> Unit)? = null
        override fun isChecked() = checked
        override fun toggle() = setChecked(!checked)
        override fun setChecked(value: Boolean) {
            checked = value
            isActivated = value
            getChildAt(0)?.isActivated = value
            (getChildAt(0) as? Checkable)?.isChecked = value
            onCheckedChanged?.invoke()
        }
    }

    private inner class RefreshRowAdapter(
        private val source: ArrayAdapter<*>,
        private val sourceAt: (Int) -> StremioSubtitleRefreshRegistry.Entry?
    ) : BaseAdapter() {
        private val originalEndPadding = WeakHashMap<TextView, Int>()
        private val observer = object : DataSetObserver() {
            override fun onChanged() = notifyDataSetChanged()
            override fun onInvalidated() = notifyDataSetInvalidated()
        }
        init { source.registerDataSetObserver(observer) }
        override fun getCount(): Int = source.count
        override fun getItem(position: Int): Any? = source.getItem(position)
        override fun getItemId(position: Int): Long = source.getItemId(position)
        override fun hasStableIds(): Boolean = source.hasStableIds()
        override fun isEnabled(position: Int): Boolean = source.isEnabled(position)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? RefreshRow) ?: RefreshRow(parent.context)
            row.onCheckedChanged = null
            val oldText = row.getChildAt(0) as? TextView
            val text = source.getView(position, oldText, parent) as TextView
            if (text !== oldText) {
                (text.parent as? ViewGroup)?.removeView(text)
                if (oldText != null) row.removeView(oldText)
                row.addView(text, 0, FrameLayout.LayoutParams(-1, -2))
            }
            row.setChecked((parent as? ListView)?.isItemChecked(position) == true)
            text.isActivated = row.isChecked
            (text as? Checkable)?.isChecked = row.isChecked
            // A focusable refresh button must not consume the native name action.
            text.setOnClickListener {
                (parent as? ListView)?.performItemClick(row, position, getItemId(position))
            }
            val button = (row.getChildAt(1) as? ImageButton) ?: ImageButton(parent.context).apply {
                val iconId = pluginResources.getIdentifier(
                    "stremioc_subtitle_refresh", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME
                )
                setImageDrawable(
                    runCatching {
                        if (iconId != 0) pluginResources.getDrawable(iconId, null) else null
                    }.getOrNull() ?: parent.context.getDrawable(android.R.drawable.ic_popup_sync)
                )
                background = null
                foreground = null
                stateListAnimator = null
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                isFocusable = true
                contentDescription = getPluginSubtitleString(
                    "stremioc_refresh_subtitle", "Refresh subtitle"
                )
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        if (view.visibility == View.VISIBLE &&
                            ((autoRefreshUrl != null && view.tag == autoRefreshUrl) ||
                                (queuedRefresh?.automatic == true &&
                                    view.tag == queuedRefresh?.entry?.originalUrl))) {
                            startAutoSpin(view as ImageButton)
                        }
                    }
                    override fun onViewDetachedFromWindow(view: View) {
                        if (autoSpinView === view) stopAutoSpin()
                    }
                })
                row.addView(this, FrameLayout.LayoutParams(
                    (48 * resources.displayMetrics.density).toInt(), -1, Gravity.END or Gravity.CENTER_VERTICAL
                ))
            }
            val entry = sourceAt(position)
            val original = originalEndPadding.getOrPut(text) { text.paddingRight }
            if (button.tag != entry?.originalUrl) {
                button.animate().cancel()
                if (autoSpinView === button) stopAutoSpin()
            }
            button.tag = entry?.originalUrl
            // ListView can update Checkable rows without rebinding the adapter.
            // Update the refresh action on the same event as the native checkmark.
            row.onCheckedChanged = {
                val selected = row.isChecked && entry != null
                val queued = queuedRefresh?.takeIf {
                    selected && it.instance == model?.state?.instance &&
                        it.entry.originalUrl == entry?.originalUrl
                }
                val spinning = selected &&
                    (autoRefreshUrl == entry?.originalUrl || queued?.automatic == true)
                val right = original + if (selected) (48 * text.resources.displayMetrics.density).toInt() else 0
                if (text.paddingRight != right) {
                    text.setPadding(text.paddingLeft, text.paddingTop, right, text.paddingBottom)
                }
                button.visibility = if (selected) View.VISIBLE else View.GONE
                val playing = fragment?.player?.getCurrentPreferredSubtitle()?.url == entry?.originalUrl
                button.isEnabled = selected && (!playing ||
                    autoRefreshUrl == entry?.originalUrl || refreshJob?.isActive != true)
                button.alpha = if (button.isEnabled) 1f else 0.35f
                if (autoSpinView === button && !spinning) stopAutoSpin()
                if (spinning) {
                    if (button.isAttachedToWindow) startAutoSpin(button)
                } else if (autoSpinView !== button) {
                    button.rotation = if (queued != null) 90f else 0f
                }
            }
            row.onCheckedChanged?.invoke()
            if (entry != null) {
                button.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
                button.setOnClickListener { requestOrQueueRefresh(entry, button) }
                button.setOnLongClickListener {
                    requestOrQueueRefresh(entry, button, automatic = true)
                    true
                }
            } else {
                button.setOnClickListener(null)
                button.setOnLongClickListener(null)
            }
            return row
        }
    }

    private fun getPluginSubtitleString(name: String, fallback: String): String {
        val id = pluginResources.getIdentifier(
            name, "string", BuildConfig.LIBRARY_PACKAGE_NAME
        )
        return if (id != 0) runCatching { pluginResources.getString(id) }.getOrDefault(fallback)
        else fallback
    }

    private fun stopAutoSpin() {
        autoSpinAnimator?.cancel()
        autoSpinAnimator = null
        autoSpinView?.rotation = 0f
        autoSpinView = null
    }

    private fun startAutoSpin(button: ImageButton) {
        if (autoSpinView === button && autoSpinAnimator?.isRunning == true) return
        stopAutoSpin()
        button.animate().cancel()
        button.rotation = 0f
        autoSpinView = button
        autoSpinAnimator = ObjectAnimator.ofFloat(button, View.ROTATION, 0f, 360f).apply {
            duration = 800L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun endAutoRefresh() {
        autoRefreshUrl = null
        stopAutoSpin()
    }

    private fun requestOrQueueRefresh(
        entry: StremioSubtitleRefreshRegistry.Entry, button: ImageButton, automatic: Boolean = false
    ) {
        val target = fragment ?: return
        val instance = model?.state?.instance ?: return
        if (target.player.getCurrentPreferredSubtitle()?.url == entry.originalUrl) {
            queuedRefresh = null
            requestSubtitleRefresh(entry, button, automatic)
        } else if (sortedDialog?.isShowing == true) {
            val previous = queuedRefresh
            val cancel = automatic && previous?.entry?.originalUrl == entry.originalUrl &&
                previous.automatic
            queuedRefresh = if (cancel) null else DeferredRefresh(
                entry, automatic, instance, model?.state?.generatorState?.id
            )
            if (autoSpinView === button) stopAutoSpin()
            button.animate().cancel()
            button.rotation = 0f
            if (!cancel) {
                if (automatic) {
                    if (button.isAttachedToWindow) startAutoSpin(button)
                } else {
                    button.animate().rotation(450f).setDuration(500L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .withEndAction { button.rotation = 90f }
                        .start()
                }
            }
        }
    }

    private fun requestSubtitleRefresh(
        entry: StremioSubtitleRefreshRegistry.Entry, button: ImageButton?, automatic: Boolean = false
    ) {
        val target = fragment ?: return
        val instance = model?.state?.instance ?: return
        if (target.player.getCurrentPreferredSubtitle()?.url != entry.originalUrl) return
        bindPlayer()
        if (automatic && autoRefreshUrl == entry.originalUrl) {
            refreshGeneration++
            refreshJob?.cancel()
            refreshJob = null
            endAutoRefresh()
            button?.isEnabled = true
            button?.alpha = 1f
            return
        }
        if (refreshJob?.isActive == true) return
        val mediaPlayer = observedPlayer ?: return
        val generation = ++refreshGeneration
        val originalTint = button?.imageTintList
        button?.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
        if (automatic) {
            autoRefreshUrl = entry.originalUrl
            button?.let {
                if (it.isAttachedToWindow && it.visibility == View.VISIBLE) startAutoSpin(it)
            }
        } else {
            button?.apply {
                isEnabled = false
                animate().cancel()
                rotation = 0f
                animate().rotation(360f).setDuration(800L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction { rotation = 0f }
                    .start()
            }
        }
        refreshJob = refreshScope.launch {
            fun current(): Boolean = generation == refreshGeneration && fragment === target &&
                model?.state?.instance == instance && target.playerView?.player === mediaPlayer &&
                target.player.getCurrentPreferredSubtitle()?.url == entry.originalUrl
            try {
                val deadline = SystemClock.elapsedRealtime() + AUTO_REFRESH_DURATION_MS
                val attempts = if (automatic) AUTO_REFRESH_MAX_REQUESTS else 1
                for (attempt in 0 until attempts) {
                    if (!current()) break
                    val previous = refreshedCues[entry.originalUrl]
                    val result = if (automatic) {
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0L) break
                        withTimeout(minOf(30_000L, remaining)) {
                            StremioSubtitleRefreshRegistry.refresh(
                                entry, previous, checkList = attempt % 3 == 0
                            )
                        }
                    } else StremioSubtitleRefreshRegistry.refresh(entry, previous)
                    if (!current()) break
                    if (result !== previous) {
                        // Only rebuild the overlay when the subtitle body changed.
                        if (entry.originalUrl !in refreshedCues && refreshedCues.size >= 4) {
                            refreshedCues.keys.firstOrNull { it != selectedUrl }?.let(refreshedCues::remove)
                        }
                        refreshedCues[entry.originalUrl] = result
                        refreshedOverlay.attach(target, entry.originalUrl, result)
                    }
                    if (!automatic || attempt + 1 >= attempts ||
                        SystemClock.elapsedRealtime() + AUTO_REFRESH_INTERVAL_MS >= deadline) break
                    delay(AUTO_REFRESH_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current()) {
                    android.widget.Toast.makeText(
                        target.context, getPluginSubtitleString(
                            "stremioc_refresh_failed", "Subtitle refresh failed"
                        ), android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                if (generation == refreshGeneration) {
                    if (automatic) endAutoRefresh()
                    refreshJob = null
                    if (button != null && button.tag == entry.originalUrl) {
                        val selected = fragment === target && model?.state?.instance == instance &&
                            target.player.getCurrentPreferredSubtitle()?.url == entry.originalUrl
                        button.visibility = if (selected) View.VISIBLE else View.GONE
                        button.isEnabled = selected
                        button.alpha = if (selected) 1f else 0.35f
                        button.imageTintList = originalTint
                    }
                }
            }
        }
    }

    fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree = null
        removeStateObserver?.invoke()
        removeStateObserver = null
        observedPlayer?.removeListener(playerListener)
        observedPlayer = null
        selectedUrl = null
        clearRefreshState()
        fragment = null
        model = null
        lastInstance = -1
        sortedDialog = null
        pendingDialog = null
        isStremio = false
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
    private val subtitleController = StremioSubtitleController(resources)
    private val scrobble = StremioScrobble(context)
    private val mdbUpdate: (String) -> Unit = { cacheKey ->
        val matched = watchers.values.filter { it.matchesMdbKey(cacheKey) }
        val applied = matched.count { it.refreshLateMdb() }
    }
    private val homeEntries = CatalogHomeEntryHook(openCatalog, ::bindHomePreview, ::bindPhoneHero)
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
                subtitleController.watch(f)
                scrobble.watch(f)
            }
        }

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment")
                syncHomeHooks()
            subtitleController.watch(f)
            scrobble.watch(f)
            watchers[f]?.let { watcher ->
                watcher.render() 
            }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment") {
                homeRoot = null
                homeEntries.detach()
            }
            subtitleController.detach(f)
            scrobble.detach(f)
            watchers.remove(f)?.detach()
        }
    }

    fun start() {
        StremioCMdbUiBridge.listen(mdbUpdate)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        scrobble.stop()
        subtitleController.detach()
        scrobble.detach()
        StremioCMdbUiBridge.listen(null)
        removeHomeProviderObserver?.invoke()
        removeHomeProviderObserver = null
        homeRoot = null
        homeEntries.detach()
        detachPreview()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        watchers.values.toList().forEach { it.detach() }
        watchers.clear()
        host = null
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        subtitleController.detach()
        scrobble.detach()
        removeHomeProviderObserver?.invoke()
        removeHomeProviderObserver = null
        homeRoot = null
        homeEntries.detach()
        detachPreview()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        watchers.values.toList().forEach { it.detach() }
        watchers.clear()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallback, true)
        val homeModel = ViewModelProvider(activity)[HomeViewModel::class.java]
        val homeObserver = Observer<String?> { syncHomeHooks() }
        homeModel.apiName.observe(activity, homeObserver)
        removeHomeProviderObserver = { homeModel.apiName.removeObserver(homeObserver) }
        observePreview(activity)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                f.view?.let {
                    if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment") {
                        homeRoot = it
                        syncHomeHooks()
                    }
                    attach(f, it)
                    subtitleController.watch(f)
                    scrobble.watch(f)
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

    private class SeasonSyncProbe(
        private val fragment: Fragment, private val root: View, private val detailUrl: String?,
        private val pluginResources: Resources
    ) {
        private companion object {
            val IMDb = Regex("tt\\d+")
            val allowed = setOf("mal", "anilist", "kitsu", "simkl")
            val titles = mapOf("mal" to "MAL", "anilist" to "AniList",
                "kitsu" to "Kitsu", "simkl" to "Simkl")

            val pendingWrites = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        }

        private data class Draft(
            var status: SyncWatchType, var watched: Int, var score: Score?,
            var explicitNone: Boolean = false
        ) {
            fun snapshot() = copy()
        }

        private data class Tracker(
            val prefix: String, val id: String, val repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            var baseline: Draft? = null, var draft: Draft? = null,
            var total: Int? = null, var totalSource: String = "unknown",
            var error: String? = null, var loading: Boolean = true, var saving: Boolean = false,
            var writeBlocked: String? = null, var simklSeason: SimklSeasonSnapshot? = null,
            var simklAnime: AnimeSnapshot? = null, var ratingLoaded: Boolean = true,
            var seasonMapping: SeasonTrackerIds? = null
        )

        private data class SimklSeasonSnapshot(
            val ids: JSONObject,
            val seasonNumber: Int,
            val available: List<Int>,
            val watched: Set<Int>,
            val otherSeasons: Map<Int, Set<Int>>,
            val listStatus: SyncWatchType?
        )

        private fun simklSeasonStatus(snap: SimklSeasonSnapshot): SyncWatchType {
            if (snap.watched.size == snap.available.size) return SyncWatchType.COMPLETED
            val global = snap.listStatus
            if (global == SyncWatchType.ONHOLD || global == SyncWatchType.DROPPED ||
                global == SyncWatchType.PLANTOWATCH || global == SyncWatchType.REWATCHING)
                return global
            return if (snap.watched.isEmpty()) SyncWatchType.NONE
                else SyncWatchType.WATCHING
        }

        private fun simklIds(id: String): JSONObject? {
            val obj = runCatching { JSONObject(id) }.getOrNull() ?: return null
            val ids = JSONObject()
            for ((key, out) in listOf("Imdb" to "imdb", "Simkl" to "simkl",
                "Mal" to "mal", "AniList" to "anilist", "Tvdb" to "tvdb")) {
                val value = obj.optString(key).takeIf { it.isNotBlank() }
                if (value != null) ids.put(out, value)
            }
            return ids.takeIf { it.length() > 0 }
        }

        private suspend fun simklSeasonRead(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            id: String, requested: Int
        ): SimklSeasonSnapshot? {
            val headers = SimklApi.getHeaders(repo.authToken() ?: return null) +
                mapOf("content-type" to "application/json")
            val ids = simklIds(id)
            if (ids == null) {
                return null
            }
            return try {
                val payload = JSONArray().put(ids)
                val response = app.post("https://api.simkl.com/sync/watched?extended=episodes",
                    headers=headers, requestBody=payload.toString().toRequestBody())
                if (response.code !in 200..299) {
                    response.okhttpResponse.close()
                    return null
                }
                val records = JSONArray(response.text)
                val record = (0 until records.length()).mapNotNull { records.optJSONObject(it) }
                    .singleOrNull { row ->
                        ids.keys().asSequence().any { key ->
                            row.optString(key).isNotBlank() &&
                                row.optString(key) == ids.optString(key)
                        }
                    }
                val rows = record?.optJSONArray("seasons")
                val seasons = (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }
                val parsed = linkedMapOf<Int, Pair<List<Int>,Set<Int>>>()
                var malformed = false
                for (part in seasons) {
                    val n = part.optInt("number")
                    val episodes = part.optJSONArray("episodes")
                    if (n <= 0 || episodes == null) { malformed = true; break }
                    val all = mutableListOf<Int>()
                    val viewed = mutableSetOf<Int>()
                    for (i in 0 until episodes.length()) {
                        val episode = episodes.optJSONObject(i)
                        val ep = episode?.optInt("number") ?: 0
                        val watchedFlag = episode?.opt("watched")
                        if (ep <= 0 || watchedFlag !is Boolean) {
                            malformed = true; break
                        }
                        all += ep
                        if (watchedFlag) viewed += ep
                    }
                    if (malformed || all.size != all.distinct().size || parsed.containsKey(n)) {
                        malformed = true; break
                    }
                    parsed[n] = all.sorted() to viewed
                }
                val chosen = parsed[requested]
                val listStatus = record?.takeIf { it.has("list") }
                    ?.let { simklWatchlistStatus(it.opt("list")) }
                val result = if (!malformed && chosen != null && chosen.first.isNotEmpty())
                    SimklSeasonSnapshot(ids, requested, chosen.first, chosen.second,
                        parsed.filterKeys { it != requested }.mapValues { it.value.second }, listStatus)
                else null
                result
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                  null
              }
        }

        private suspend fun simklLibraryObject(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            endpoint: String
        ): JSONObject? {
            val headers=SimklApi.getHeaders(repo.authToken() ?: return null)
            val response=try {
                app.get("https://api.simkl.com/sync/all-items/$endpoint",
                    headers=headers,cacheTime=0)
            } catch(e: CancellationException) { throw e }
              catch(e: Exception) { return null }
            if(response.code !in 200..299) { response.okhttpResponse.close(); return null }
            val body=response.text.trim()
            return if(body=="null") JSONObject()
                else runCatching { JSONObject(body) }.getOrNull()
        }

        private suspend fun simklScores(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            targets: List<JSONObject>, anime: Boolean
        ): List<Score?>? {
            if(targets.isEmpty())return null
            val kind=if(anime)"anime" else "shows"
            val root=simklLibraryObject(repo,kind) ?: return null
            val rows=root.optJSONArray(kind) ?: if(root.length()==0) JSONArray() else return null
            val scores=mutableListOf<Score?>()
            for(ids in targets) {
                var found: JSONObject?=null
                for(i in 0 until rows.length()) {
                    val item=rows.optJSONObject(i) ?: return null
                    val rowIds=item.optJSONObject("show")?.optJSONObject("ids")
                        ?: item.optJSONObject("ids") ?: return null
                    if(ids.keys().asSequence().any { key ->
                        ids.optString(key).isNotBlank() &&
                            ids.optString(key)==rowIds.optString(key)
                    }) {
                        if(found!=null)return null
                        found=item
                    }
                }
                val raw=found?.opt("user_rating")
                val rating=when(raw) {
                    null, JSONObject.NULL -> null
                    is Number -> raw.toInt()
                    else -> raw.toString().toIntOrNull() ?: return null
                }
                if(rating!=null && rating !in 1..10)return null
                scores+=rating?.let { Score.from10(it) }
            }
            return scores
        }

        private suspend fun simklSaveScores(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            targets: List<JSONObject>, score: Score?, anime: Boolean
        ): Boolean {
            if(targets.isEmpty())return false
            val value=score?.toInt(10) ?: 0
            if(value !in 0..10)return false
            val headers=SimklApi.getHeaders(repo.authToken() ?: return false)+
                mapOf("content-type" to "application/json")
            val items=JSONArray().also { array ->
                targets.forEach { ids ->
                    val entry=JSONObject().put("ids",ids)
                    if(value>0)entry.put("rating",value)
                    array.put(entry)
                }
            }
            val action=if(value>0)"ratings" else "ratings/remove"
            val response=try {
                app.post("https://api.simkl.com/sync/$action",headers=headers,
                    requestBody=JSONObject().put("shows",items).toString().toRequestBody())
            }catch(e:CancellationException){throw e}
             catch(e:Exception){return false}
            if(response.code !in 200..299) { response.okhttpResponse.close(); return false }
            val ack=runCatching { JSONObject(response.text) }.getOrNull() ?: return false
            val saved=ack.optJSONObject(if(value>0)"added" else "deleted")
                ?.optInt("shows",-1) ?: -1
            val missing=ack.optJSONObject("not_found")
            if(saved!=targets.size ||
                (missing?.optJSONArray("shows")?.length() ?: 0)>0 ||
                (missing?.optJSONArray("anime")?.length() ?: 0)>0)return false
            for(attempt in 1..3) {
                if(attempt>1)kotlinx.coroutines.delay(if(attempt==2)350L else 900L)
                val actual=simklScores(repo,targets,anime)
                if(actual!=null && actual.all { (it?.toInt(10) ?: 0)==value })
                    return true
            }
            return false
        }

        private suspend fun simklTvLibraryMembership(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            ids: JSONObject
        ): Boolean? {
            val root=simklLibraryObject(repo,"shows?extended=ids_only") ?: return null
            val rows=root.optJSONArray("shows") ?: if(root.length()==0) {
                JSONArray()
            } else return null
            for(i in 0 until rows.length()) {
                val entry=rows.optJSONObject(i) ?: return null
                val rowIds=entry.optJSONObject("show")?.optJSONObject("ids")
                    ?: entry.optJSONObject("ids") ?: return null
                if(ids.keys().asSequence().any { k ->
                    ids.optString(k).isNotBlank() && ids.optString(k)==rowIds.optString(k)
                })return true
            }
            return false
        }

        private suspend fun simklSeasonSave(
            tracker: Tracker, draft: Draft, baseline: Draft, sn: Int
        ): Boolean {
            val initial = tracker.simklSeason ?: return false
            if (draft.score != baseline.score || draft.watched !in 0..initial.available.size)
                return false
            val headers = SimklApi.getHeaders(tracker.repo.authToken() ?: return false) +
                mapOf("content-type" to "application/json")
            val url = "https://api.simkl.com/sync/"

            val clearSelectedSeason = draft.status == SyncWatchType.NONE && draft.explicitNone

            val seasonCompleted = draft.status == SyncWatchType.COMPLETED
            val requestedCount = when {
                clearSelectedSeason -> 0
                seasonCompleted -> initial.available.size
                else -> draft.watched
            }
            val countDiff = requestedCount - initial.watched.size
            val autoWatching = baseline.status == SyncWatchType.NONE &&
                draft.status == SyncWatchType.WATCHING && countDiff > 0
            val progressDemotion = draft.status == SyncWatchType.WATCHING &&
                baseline.status == SyncWatchType.COMPLETED && countDiff < 0

            val statusChanged = !clearSelectedSeason && !seasonCompleted &&
                draft.status != baseline.status && !autoWatching &&
                !progressDemotion && initial.listStatus != draft.status
            val otherWatched=initial.otherSeasons.values.any { it.isNotEmpty() }
            val fullDelete=clearSelectedSeason && !otherWatched
            if (statusChanged && (initial.listStatus == null ||
                    simklWatchlistDestination(draft.status) == null)) return false

            if(fullDelete) {

                val fresh=simklSeasonRead(tracker.repo,tracker.id,sn)
                val safe=fresh!=null && fresh.available==initial.available &&
                    fresh.watched==initial.watched &&
                    fresh.otherSeasons==initial.otherSeasons &&
                    fresh.otherSeasons.values.all { it.isEmpty() }
                if(!safe)return false
                val presentBefore=simklTvLibraryMembership(tracker.repo,initial.ids)
                    ?: return false
                if(presentBefore) {
                    val payload=JSONObject().put("shows",JSONArray().put(
                        JSONObject().put("ids",initial.ids)))
                    val response=try {
                        app.post(url+"history/remove",headers=headers,
                            requestBody=payload.toString().toRequestBody())
                    }catch(e:CancellationException){throw e}
                     catch(e:Exception){
                        return false
                     }
                    val ack=runCatching { JSONObject(response.text) }.getOrNull()
                    val deleted=ack?.optJSONObject("deleted")?.optInt("shows",-1) ?: -1
                    val notFound=ack?.optJSONObject("not_found")?.optJSONArray("shows")?.length() ?: 0
                    if(response.code !in 200..299 || deleted<=0 || notFound!=0)return false
                }else if(initial.watched.isNotEmpty() || fresh?.listStatus!=SyncWatchType.NONE) {

                    return false
                }
                tracker.simklSeason=fresh!!.copy(watched=emptySet(),listStatus=SyncWatchType.NONE)
                tracker.repo.requireLibraryRefresh=true
                return true
            }

            if (countDiff == 0 && !statusChanged) {

                val current = simklSeasonRead(tracker.repo, tracker.id, sn)
                val unchanged = current != null &&
                    current.available == initial.available &&
                    current.watched == initial.watched &&
                    current.otherSeasons == initial.otherSeasons &&
                    (if (clearSelectedSeason) current.watched.isEmpty()
                     else if (seasonCompleted) current.watched.size == current.available.size
                     else true)
                if (unchanged) tracker.simklSeason = current
                return unchanged
            }

            val fresh = simklSeasonRead(tracker.repo, tracker.id, sn)
            if (fresh == null || fresh.available != initial.available ||
                fresh.watched != initial.watched ||
                fresh.otherSeasons != initial.otherSeasons) {
                return false
            }

            if (countDiff != 0) {
                val delta = if (countDiff > 0)
                    initial.available.filterNot { it in initial.watched }.take(countDiff)
                else initial.available.filter { it in initial.watched }.takeLast(-countDiff)
                if (delta.size != kotlin.math.abs(countDiff)) return false
                val expected = if (countDiff > 0) initial.watched + delta
                    else initial.watched - delta.toSet()
                val scope = JSONObject().put("ids", initial.ids)
                    .put("seasons", JSONArray().put(JSONObject()
                        .put("number", sn).put("episodes", JSONArray().also { arr ->
                            delta.forEach { arr.put(JSONObject().put("number", it)) }
                        })))
                val endpoint = if (countDiff > 0) "history" else "history/remove"
                val response = try {
                    app.post(url + endpoint, headers = headers,
                        requestBody = JSONObject().put("shows", JSONArray().put(scope))
                            .toString().toRequestBody())
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                  return false }
                val missing = runCatching {
                    JSONObject(response.text).optJSONObject("not_found")
                        ?.optJSONArray("shows")?.length() ?: 0
                }.getOrDefault(-1)
                if (response.code !in 200..299 || missing != 0) return false
                var confirmed = false
                for (attempt in 1..3) {
                    if (attempt > 1) kotlinx.coroutines.delay(if (attempt == 2) 350L else 900L)
                    val current = simklSeasonRead(tracker.repo, tracker.id, sn)
                    confirmed = current?.watched == expected &&
                        current.otherSeasons == initial.otherSeasons
                    if (confirmed) {
                        tracker.simklSeason = current
                        break
                    }
                }
                if (!confirmed) return false
            }

            if (statusChanged) {

                val destination = simklWatchlistDestination(draft.status) ?: return false
                val payload = JSONObject().put("shows", JSONArray().put(
                    JSONObject().put("ids", initial.ids).put("to", destination)))
                val response = try {
                    app.post(url + "add-to-list", headers = headers,
                        requestBody = payload.toString().toRequestBody())
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                  return false }
                if (response.code !in 200..299) { response.okhttpResponse.close(); return false }
                val result = runCatching { JSONObject(response.text) }.getOrNull()
                    ?: return false
                if ((result.optJSONObject("not_found")?.optJSONArray("shows")?.length() ?: 0) > 0)
                    return false
                val accepted = result.optJSONObject("added")?.optJSONArray("shows")
                    ?.optJSONObject(0)?.opt("to")?.let { simklWatchlistStatus(it) }
                    ?.takeIf { it != SyncWatchType.NONE } ?: draft.status
                for (attempt in 1..3) {
                    if (attempt > 1) kotlinx.coroutines.delay(if (attempt == 2) 350L else 900L)
                    val current = simklSeasonRead(tracker.repo, tracker.id, sn)
                    if (current?.listStatus == accepted &&
                        current.watched == (tracker.simklSeason ?: initial).watched &&
                        current.otherSeasons == (tracker.simklSeason ?: initial).otherSeasons) {
                        tracker.simklSeason = current
                        tracker.repo.requireLibraryRefresh = true
                        return true
                    }
                }
                return false
            }
            return true
        }

        private data class AnimeSlot(val simkl: Int, val native: Int, val tvdbSeason: Int, val tvdbEpisode: Int) {
            val key get() = "$simkl:$native"
        }
        private data class AnimeSnapshot(
            val tvdbId: Int?, val tvdbSeason: Int?, val mapped: List<AnimeSlot>,
            val watched: Set<String>, val outside: Set<String>,
            val mode: String, val sourceIds: Set<Int>,
            val status: SyncWatchType?, val statusEditable: Boolean
        )

        private fun animeSeasonDisplayStatus(snap: AnimeSnapshot): SyncWatchType {
            val count=snap.watched.size
            if(!snap.statusEditable && snap.status==SyncWatchType.COMPLETED &&
                snap.mapped.isNotEmpty() && count<snap.mapped.size)
                return if(count==0) SyncWatchType.NONE else SyncWatchType.WATCHING
            return snap.status ?: when {
                count==0 -> SyncWatchType.NONE
                count==snap.mapped.size -> SyncWatchType.COMPLETED
                else -> SyncWatchType.WATCHING
            }
        }

        private data class AnimeWatchGrid(
            val watched: Set<String>, val statuses: Map<Int, SyncWatchType>
        )
        private val animeEpisodeCache = mutableMapOf<Int, List<AnimeSlot>>()

        private fun jsonInt(obj: JSONObject?, key: String): Int? =
            obj?.opt(key)?.toString()?.toIntOrNull()?.takeIf { it > 0 }

        private fun simklWatchlistStatus(value: Any?): SyncWatchType? = when (value) {
            null, JSONObject.NULL -> SyncWatchType.NONE
            "watching" -> SyncWatchType.WATCHING
            "plantowatch" -> SyncWatchType.PLANTOWATCH
            "completed" -> SyncWatchType.COMPLETED
            "hold" -> SyncWatchType.ONHOLD
            "dropped" -> SyncWatchType.DROPPED
            else -> null
        }

        private fun simklWatchlistDestination(status: SyncWatchType): String? = when (status) {
            SyncWatchType.WATCHING, SyncWatchType.REWATCHING -> "watching"
            SyncWatchType.PLANTOWATCH -> "plantowatch"
            SyncWatchType.COMPLETED -> "completed"
            SyncWatchType.ONHOLD -> "hold"
            SyncWatchType.DROPPED -> "dropped"
            else -> null
        }

        private suspend fun simklAnimeWatched(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            ids: Set<Int>
        ): AnimeWatchGrid? {
            if(ids.isEmpty())return null
            val headers=SimklApi.getHeaders(repo.authToken() ?: return null)+
                mapOf("content-type" to "application/json")
            val request=JSONArray().also { array ->
                ids.forEach { id ->
                    array.put(JSONObject().put("ids",JSONObject().put("simkl",id)))
                }
            }
            val response=try {
                app.post("https://api.simkl.com/sync/watched?extended=episodes",
                    headers=headers,requestBody=request.toString().toRequestBody())
            }catch(e: CancellationException){throw e}
             catch(e: Exception){
                  return null}
            if(response.code !in 200..299) { response.okhttpResponse.close(); return null }
            val rows=runCatching { JSONArray(response.text) }.getOrNull() ?: return null
            if(rows.length()!=ids.size)return null
            val watched=linkedSetOf<String>()
            val observed=linkedSetOf<String>()
            val returned=linkedSetOf<Int>()
            val statuses=linkedMapOf<Int, SyncWatchType>()
            for(i in 0 until rows.length()){
                val row=rows.optJSONObject(i) ?: return null
                val id=jsonInt(row.optJSONObject("ids"),"simkl") ?: return null
                val rootId=jsonInt(row,"simkl")
                if(id !in ids || (rootId!=null && rootId!=id) || !returned.add(id))return null
                if(!row.has("list"))return null
                statuses[id]=simklWatchlistStatus(row.opt("list")) ?: return null
                val seasons=row.optJSONArray("seasons") ?: return null
                for(j in 0 until seasons.length()){
                    val part=seasons.optJSONObject(j) ?: return null
                    val episodes=part.optJSONArray("episodes") ?: return null
                    if(jsonInt(part,"number")==null)return null
                    for(k in 0 until episodes.length()){
                        val ep=episodes.optJSONObject(k) ?: return null
                        val number=jsonInt(ep,"number") ?: return null
                        val flag=ep.opt("watched")
                        if(flag !is Boolean || !observed.add("$id:$number"))return null
                        if(flag)watched+="$id:$number"
                    }
                }
            }
            return AnimeWatchGrid(watched,statuses).takeIf { returned==ids }
        }

        private suspend fun simklAnimeSetStatus(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            ids: Set<Int>, requested: SyncWatchType
        ): SyncWatchType? {
            val destination=simklWatchlistDestination(requested) ?: return null
            if(ids.isEmpty())return null
            val headers=SimklApi.getHeaders(repo.authToken() ?: return null)+
                mapOf("content-type" to "application/json")

            val body=JSONObject().put("shows",JSONArray().also { items ->
                ids.forEach { id ->
                    items.put(JSONObject().put("to",destination)
                        .put("ids",JSONObject().put("simkl",id)))
                }
            })
            val response=try {
                app.post("https://api.simkl.com/sync/add-to-list",
                    headers=headers,requestBody=body.toString().toRequestBody())
            }catch(e: CancellationException){throw e}
             catch(e: Exception){
                  return null}
            if(response.code !in 200..299) { response.okhttpResponse.close(); return null }
            val result=runCatching { JSONObject(response.text) }.getOrNull() ?: return null
            val notFound=result.optJSONObject("not_found")
            if((notFound?.optJSONArray("anime")?.length() ?: 0)>0 ||
               (notFound?.optJSONArray("shows")?.length() ?: 0)>0 ||
               (notFound?.optJSONArray("movies")?.length() ?: 0)>0)return null
            val added=result.optJSONObject("added") ?: return null
            val echoed=linkedSetOf<Int>()
            val actual=linkedSetOf<SyncWatchType>()
            for(group in listOf("shows","anime")){
                val rows=added.optJSONArray(group) ?: continue
                for(i in 0 until rows.length()){
                    val item=rows.optJSONObject(i) ?: return null
                    val id=jsonInt(item.optJSONObject("ids"),"simkl") ?: return null
                    if(id !in ids || !echoed.add(id))return null
                    val state=simklWatchlistStatus(item.opt("to")) ?: return null
                    if(state==SyncWatchType.NONE)return null
                    actual+=state
                }
            }
            val stored=actual.singleOrNull()?.takeIf { echoed==ids }
            if(stored!=requested) {
                return null
            }
            return stored
        }

        private suspend fun simklAnimeLibraryStatus(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            ids: Set<Int>
        ): SyncWatchType? {
            if(ids.isEmpty())return null
            val body=simklLibraryObject(repo,"anime") ?: return null

            val anime=body.optJSONArray("anime") ?: if(!body.has("anime") &&
                body.keys().asSequence().all { it in setOf("shows","movies") })
                JSONArray() else return null
            val statuses=linkedMapOf<Int, SyncWatchType>()
            for(i in 0 until anime.length()) {
                val item=anime.optJSONObject(i) ?: continue
                val id=jsonInt(item.optJSONObject("show")?.optJSONObject("ids"),"simkl")
                    ?: continue
                if(id !in ids)continue
                val status=simklWatchlistStatus(item.opt("status")) ?: return null
                if(status==SyncWatchType.NONE || statuses.put(id,status)!=null)return null
            }
            if(statuses.isEmpty())return SyncWatchType.NONE
            return ids.map { statuses[it] ?: return null }.distinct().singleOrNull()
        }

        private suspend fun simklAnimeRemoveFromLibrary(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            ids: Set<Int>
        ): Boolean {
            if(ids.isEmpty())return false
            val headers=SimklApi.getHeaders(repo.authToken() ?: return false)+
                mapOf("content-type" to "application/json")
            val payload=JSONObject().put("shows",JSONArray().also { items ->
                ids.forEach { id ->
                    items.put(JSONObject().put("ids",JSONObject().put("simkl",id)))
                }
            })
            val response=try {
                app.post("https://api.simkl.com/sync/history/remove",
                    headers=headers,requestBody=payload.toString().toRequestBody())
            }catch(e:CancellationException){throw e}
             catch(e:Exception) {
                return false
             }
            if(response.code !in 200..299) { response.okhttpResponse.close(); return false }
            val ack=runCatching { JSONObject(response.text) }.getOrNull() ?: return false

            val deleted=ack.optJSONObject("deleted")?.optInt("shows",-1) ?: -1
            val notFound=ack.optJSONObject("not_found")
            val missing=(notFound?.optJSONArray("shows")?.length() ?: 0)+
                (notFound?.optJSONArray("anime")?.length() ?: 0)
            return deleted==ids.size && missing==0
        }

        private suspend fun simklAnimeRead(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            mapped: SeasonTrackerIds, targetSeason: Int
        ): AnimeSnapshot? {
            val auth = repo.authToken()
            val headers = SimklApi.getHeaders(auth ?: return null)
            val candidates = linkedSetOf<Int>()
            val tvdbSearchMatches = linkedSetOf<Int>()
            val malSearchMatches = linkedSetOf<Int>()
            mapped.simkl?.let { candidates += it }
            suspend fun search(field: String, value: Int) {
                try {
                    val resp=app.get("https://api.simkl.com/search/id",
                        params=mapOf(field to value.toString()),headers=headers,cacheTime=0)
                    val array=if (resp.code in 200..299)
                        runCatching { JSONArray(resp.text) }.getOrNull()
                        else { resp.okhttpResponse.close(); null }
                    val found=mutableListOf<Int>()
                    for(i in 0 until (array?.length() ?: 0)){
                        val item=array?.optJSONObject(i)
                        val ids=item?.optJSONObject("ids")
                            ?: item?.optJSONObject("anime")?.optJSONObject("ids")
                        val id=jsonInt(ids,"simkl")
                        if (id != null) found+=id
                    }
                    candidates.addAll(found.take(24))
                    if (field=="tvdb")tvdbSearchMatches.addAll(found.take(24))
                    if (field=="mal")malSearchMatches.addAll(found.take(24))
                }catch(e: CancellationException){throw e}
                 catch(e: Exception){
                 }
            }

            mapped.mal?.let { search("mal",it) }
            mapped.tvdb?.let { search("tvdb",it) }
            if(candidates.isEmpty()){
                return null
            }
            val tvdbSeason=mapped.tvdbSeason
            val tvdbId=mapped.tvdb
            val slots=mutableListOf<AnimeSlot>()
            val validated=linkedSetOf<Int>()
            for(id in candidates.take(24)){
                try{
                    val detail=app.get("https://api.simkl.com/anime/$id",
                        headers=headers,cacheTime=600)
                    if(detail.code !in 200..299){
                        detail.okhttpResponse.close()
                        continue
                    }
                    val info=runCatching { JSONObject(detail.text) }.getOrNull()
                    if(info == null){
                        continue
                    }
                    val ids=info.optJSONObject("ids")
                    if(ids == null){
                        continue
                    }
                    if(jsonInt(ids,"simkl") != id){
                        continue
                    }

                    val malMatched=mapped.mal != null &&
                        jsonInt(ids,"mal")==mapped.mal
                    val direct=mapped.simkl==id
                    val row=animeEpisodeCache[id] ?: run {
                        val list=app.get("https://api.simkl.com/anime/episodes/$id",
                            headers=headers,cacheTime=600)
                        if(list.code !in 200..299) {
                            list.okhttpResponse.close()
                            throw IllegalStateException("episodes-http-${list.code}")
                        }
                        val array=JSONArray(list.text)
                        val found=(0 until array.length()).mapNotNull { i ->
                            val ep=array.optJSONObject(i)
                            val native=jsonInt(ep,"episode") ?: return@mapNotNull null
                            if(ep?.optString("type")=="special")return@mapNotNull null
                            val tvdb=ep?.optJSONObject("tvdb")
                            val season=jsonInt(tvdb,"season") ?: 0
                            val episode=jsonInt(tvdb,"episode") ?: 0
                            AnimeSlot(id,native,season,episode)
                        }
                        if(found.map { it.native }.distinct().size != found.size)
                            throw IllegalStateException("duplicate-native-episodes")
                        animeEpisodeCache[id]=found
                        found
                    }
                    if(tvdbId!=null && tvdbSeason!=null){
                        val correctParent=jsonInt(ids,"tvdb")==tvdbId ||
                            id in tvdbSearchMatches || (direct && malMatched)
                        if(!correctParent){
                            continue
                        }
                        val subset=row.filter { it.tvdbSeason == tvdbSeason && it.tvdbEpisode > 0 }
                        if(subset.isNotEmpty()){
                            slots+=subset
                            validated+=id
                        }
                    } else if(malMatched && candidates.size==1 &&
                        (direct || id in malSearchMatches)){
                        slots+=row.map { it.copy(tvdbEpisode=it.native) }
                        validated+=id
                    }
                }catch(e: CancellationException){throw e}
                 catch(e: Exception){
                 }
            }
            val sorted=slots.sortedWith(compareBy<AnimeSlot> { it.tvdbEpisode }.thenBy { it.simkl })
            val keys=sorted.map { it.tvdbEpisode }

            if(sorted.isEmpty() || keys.distinct().size != keys.size){
                return null
            }
            val grid=simklAnimeWatched(repo,validated) ?: return null
            val targetKeys=sorted.mapTo(linkedSetOf()) { it.key }
            val unifiedStatus=grid.statuses.values.distinct().singleOrNull()

            val seasonLocalTitles=validated.all { id ->
                val episodes=animeEpisodeCache[id].orEmpty()
                episodes.isNotEmpty() && (tvdbSeason==null ||
                    episodes.all { it.tvdbSeason==tvdbSeason })
            }
            val result=AnimeSnapshot(tvdbId,tvdbSeason,sorted,
                grid.watched.intersect(targetKeys),grid.watched-targetKeys,
                if(tvdbId!=null && tvdbSeason!=null)"tvdb" else "native",validated,
                unifiedStatus,unifiedStatus!=null && seasonLocalTitles)
            return result
        }

        private suspend fun simklAnimeSave(tracker: Tracker, draft: Draft,
            initial: Draft, seasonNumber: Int): Boolean {
            val before=tracker.simklAnime ?: return false
            if(draft.status==SyncWatchType.NONE && draft.explicitNone) {
                if(!before.statusEditable)return false
                val removed=simklAnimeRemoveFromLibrary(tracker.repo,before.sourceIds)
                if(removed)tracker.simklAnime=before.copy(
                    watched=emptySet(),outside=emptySet(),status=SyncWatchType.NONE)
                return removed
            }
            if(draft.score!=initial.score)return false
            if(draft.watched !in 0..before.mapped.size)return false
            val diff=draft.watched-before.watched.size
            val autoWatching=initial.status==SyncWatchType.NONE &&
                draft.status==SyncWatchType.WATCHING && diff>0
            val progressDemotion=!before.statusEditable &&
                initial.status==SyncWatchType.COMPLETED &&
                draft.status==SyncWatchType.WATCHING && diff<0
            val statusChanged=draft.status!=initial.status && !autoWatching && !progressDemotion
            if(statusChanged && (!before.statusEditable ||
                simklWatchlistDestination(draft.status)==null))return false
            if(diff==0 && !statusChanged)return true

            val expected=if(diff>0) {
                val changes=before.mapped.filterNot { it.key in before.watched }.take(diff)
                if(changes.size!=diff)return false
                before.watched+changes.map { it.key }
            } else if(diff<0) {
                val changes=before.mapped.filter { it.key in before.watched }.takeLast(-diff)
                if(changes.size!=-diff)return false
                before.watched-changes.map { it.key }.toSet()
            } else before.watched

            if(diff!=0) {
                val changes=if(diff>0)
                    before.mapped.filterNot { it.key in before.watched }.take(diff)
                else before.mapped.filter { it.key in before.watched }.takeLast(-diff)
                val body=if(before.mode=="tvdb"){
                    val episodes=JSONArray().also { a ->
                        changes.forEach { a.put(JSONObject().put("number",it.tvdbEpisode)) }
                    }
                    JSONObject().put("shows",JSONArray().put(JSONObject()
                        .put("ids",JSONObject().put("tvdb",before.tvdbId))
                        .put("use_tvdb_anime_seasons",true)
                        .put("seasons",JSONArray().put(JSONObject()
                            .put("number",before.tvdbSeason)
                            .put("episodes",episodes)))))
                }else{
                    val id=before.sourceIds.singleOrNull() ?: return false
                    JSONObject().put("anime",JSONArray().put(JSONObject()
                        .put("ids",JSONObject().put("simkl",id))
                        .put("episodes",JSONArray().also { a ->
                            changes.forEach { a.put(JSONObject().put("number",it.native)) }
                        })))
                }
                val endpoint=if(diff>0)"history" else "history/remove"
                val headers=SimklApi.getHeaders(tracker.repo.authToken() ?: return false)+
                    mapOf("content-type" to "application/json")
                val response=try {
                    app.post("https://api.simkl.com/sync/$endpoint",
                        headers=headers,requestBody=body.toString().toRequestBody())
                }catch(e: CancellationException){throw e}
                 catch(e: Exception){
                  return false}
                val failures=runCatching {
                    val obj=JSONObject(response.text)
                    val notFound=obj.optJSONObject("not_found")
                    (notFound?.optJSONArray("shows")?.length() ?: 0)+
                        (notFound?.optJSONArray("anime")?.length() ?: 0)
                }.getOrNull()
                if(response.code !in 200..299 || failures==null || failures>0)return false
            }

            val acceptedStatus=if(statusChanged)
                simklAnimeSetStatus(tracker.repo,before.sourceIds,draft.status)
                    ?: return false
            else null

            if(statusChanged && diff==0) {
                for(attempt in 1..3) {
                    if(attempt>1)kotlinx.coroutines.delay(if(attempt==2)350L else 900L)
                    val stored=simklAnimeLibraryStatus(tracker.repo,before.sourceIds)
                    if(stored!=null && stored==acceptedStatus) {
                        tracker.simklAnime=before.copy(status=stored)
                        return true
                    }
                }
                return false
            }

            for(attempt in 1..3) {
                if(attempt>1)kotlinx.coroutines.delay(if(attempt==2)350L else 900L)
                val mapped=tracker.seasonMapping ?: return false
                val after=simklAnimeRead(tracker.repo,mapped,seasonNumber)

                val watchedMatches=diff==0 && statusChanged ||
                    after?.watched==expected
                val outsidePreserved=after?.outside==before.outside
                val statusMatches=!statusChanged || after?.status==acceptedStatus
                if(after!=null && watchedMatches && outsidePreserved && statusMatches) {
                    tracker.simklAnime=after
                    return true
                }
            }
            return false
        }

        private val castItems by lazy { root.findViewById<RecyclerView>(R.id.result_cast_items) }
        private var seasonCast: List<ActorData>? = null
        private var showingAniListCast = false
        private var coreAniListLoading = false

        private fun showAniListCast(actors: List<ActorData>?) {
            if (actors.isNullOrEmpty()) return
            showingAniListCast = true
            seasonCast = actors
            showSeasonCast(actors)
        }

        private fun showTmdbFallback() {
            // Do not render fallback before the native selected season is known.
            // It can otherwise win the first frame of an episodic anime even
            // though its AniList season mapping has not started yet.
            val url = pageUrl ?: return
            if (StremioC.episodeBasedByUrl[url] == true &&
                (seasonNumbers.isEmpty() || selectionIndex?.let {
                    seasonNumbers.getOrNull(it) != null
                } != true)) return
            // A terminal fallback never displaces a successful AniList cast.
            if (showingAniListCast) return
            val actors = StremioC.animeFallbackActorsByUrl[url]
                ?.takeIf { it.isNotEmpty() } ?: return
            seasonCast = actors
            showSeasonCast(actors)
        }

        private var originalCastAnimator: RecyclerView.ItemAnimator? = null
        private var castAnimationsDisabled = false

        private fun castEnabled() = castItems != null &&
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(root.context)
                .getBoolean(root.context.getString(R.string.show_cast_in_details_key), true)

        private fun showSeasonCast(actors: List<ActorData>) {
            if (!active || !castEnabled()) return
            val list = castItems ?: return
            val adapter = list.adapter as? ActorAdaptor ?: return
            // Avoid RecyclerView remove/change fade during native season swaps.
            if (!castAnimationsDisabled) {
                originalCastAnimator = list.itemAnimator
                list.itemAnimator = null
                castAnimationsDisabled = true
            }
            // The core may return a different ActorData instance with identical content.
            // Submitting it again reloads the same images and produces a visible flash.
            if (adapter.immutableCurrentList != actors) adapter.submitList(actors)
            val targetVisibility = if (actors.isEmpty()) View.GONE else View.VISIBLE
            if (list.visibility != targetVisibility) list.visibility = targetVisibility
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val observers = mutableListOf<() -> Unit>()
        private var active = false
        private var generation = 0L
        private var pageUrl: String? = null
        private var rawSyncs: Map<String,String> = emptyMap()
        private var seasonNumbers: List<Int> = emptyList()
        private var selectionIndex: Int? = null
        private var season: Int? = null
        private var resolvedSeasonInputs: Triple<Int, List<Int>, Map<String, String>>? = null
        private var startedFor: String? = null
        private var selected: String? = null
        private var preferred: String? = null
        private var statuses = linkedMapOf<String, Tracker>()
        private var fetchJob: Job? = null
        private var applying = false
        private var combo: AppCompatSpinner? = null
        private var comboContainer: View? = null
        private var comboArrow: TextView? = null
        private var oldNames: TextView? = null
        private var syncModel: SyncViewModel? = null
        private val saveButton by lazy { root.findViewById<View>(R.id.result_sync_set_score) }
        private val minus by lazy { root.findViewById<View>(R.id.result_sync_sub_episode) }
        private val plus by lazy { root.findViewById<View>(R.id.result_sync_add_episode) }
        private val current by lazy { root.findViewById<android.widget.EditText>(R.id.result_sync_current_episodes) }
        private val maximum by lazy { root.findViewById<TextView>(R.id.result_sync_max_episodes) }
        private val bar by lazy { root.findViewById<android.widget.ProgressBar>(R.id.result_sync_episodes) }
        private val slider by lazy { root.findViewById<com.google.android.material.slider.Slider>(R.id.result_sync_rating) }
        private val scoreLabel by lazy { root.findViewById<TextView>(R.id.result_sync_score_text) }
        private val checks by lazy { root.findViewById<ListView>(R.id.result_sync_check) }
        private val holder by lazy { root.findViewById<View>(R.id.result_sync_holder) }
        private val shimmer by lazy { root.findViewById<View>(R.id.result_sync_loading_shimmer) }
        private fun valid(g: Long, n: Int?): Boolean =
            active && generation == g && season == n

        private fun isWriteInFlight(tracker: Tracker): Boolean =
            tracker.saving || pendingWrites.contains("${tracker.prefix}:${tracker.id}")

        private suspend fun refreshAfterVerifiedWrite(tracker: Tracker, provider: String) {
            val repo = tracker.repo
            repo.requireLibraryRefresh = true
            if (provider == "simkl") {
                repo.authUser()?.let { account ->
                    val key = account.id.toString()
                    removeKey(SimklApi.SIMKL_CACHED_LIST_TIME, key)
                    removeKey(SimklApi.SIMKL_CACHED_LIST, key)
                }
            }
        }

        private fun <T> observe(
            data: androidx.lifecycle.LiveData<T>, owner: androidx.lifecycle.LifecycleOwner,
            handler: (T) -> Unit
        ) {
            val observer = Observer<T> { if (active) handler(it) }
            data.observe(owner, observer)
            observers += { data.removeObserver(observer) }
        }

        fun start() {
            if (active) return
            active = true
            val vm = ViewModelProvider(fragment)[ResultViewModel2::class.java]
            syncModel = if (fragment is com.lagradost.cloudstream3.ui.result.ResultFragmentPhone)
                ViewModelProvider(fragment)[SyncViewModel::class.java] else null
            val owner = fragment.viewLifecycleOwner
            observe(vm.page, owner) { value ->
                when(value) {
                    is Resource.Success -> {
                        val data = value.value
                        if (pageUrl != data.url) {
                            generation++
                            pageUrl = data.url
                            rawSyncs = data.syncData.toMap()
                            seasonCast = null
                            showingAniListCast = false
                            coreAniListLoading = false
                            seasonNumbers = emptyList()
                            selectionIndex = null
                            season = null
                            resolvedSeasonInputs = null
                            reset()
                        } else {
                            rawSyncs = data.syncData.toMap()
                            // Native page rendering can re-submit its original cast.
                            // Re-apply the selected season synchronously before the next draw.
                            seasonCast?.let { actors -> showSeasonCast(actors) }
                        }
                        resolve()
                        // Apply the cached cast after season selection, so
                        // resolve() cannot immediately clear the reused list.
                        if (seasonCast == null &&
                            (StremioC.episodeBasedByUrl[data.url] == false ||
                                (seasonNumbers.size == 1 && selectionIndex?.let { it >= 0 } == true)) &&
                            StremioC.animeAudioByUrl[data.url] == true) {
                            rawSyncs["anilist"]?.toIntOrNull()
                                ?.let { StremioC.cachedAnimeCast(it) }
                                ?.let(::showAniListCast)
                        }
                    }
                    is Resource.Failure -> {}
                    else -> {}
                }
            }
            observe(vm.seasonSelections, owner) { items ->
                seasonNumbers = items.map { it.second }
                resolve()
            }
            observe(vm.selectedSeasonIndex, owner) { index ->
                selectionIndex = index
                resolve()
            }
            syncModel?.let { model ->
                observe(model.userData, owner) {
                    if (combo != null) {
                        root.post { if (active) render() }
                    }
                }
                observe(model.metadata, owner) { result ->
                    applyCoreCastMetadata(result)
                    if (combo != null) {
                        root.post { if (active) render() }
                    }
                }
                observe(model.synced, owner) {
                    if (combo != null) root.post { if (active) render() }
                }
            }
        }

        private fun applyCoreCastMetadata(result: Resource<SyncAPI.SyncResult>?) {
            val url = pageUrl ?: return
            val model = syncModel ?: return
            val expectedId = rawSyncs["anilist"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: return
            if (StremioC.animeAudioByUrl[url] != true ||
                model.getSyncs()["anilist"]?.toIntOrNull() != expectedId) return
            if (result is Resource.Loading) {
                coreAniListLoading = true
                return
            }
            // Core metadata is shared by all trackers. A terminal result is
            // cast-relevant only after the native one-season selection arrives.
            // Replay its latest value on that transition without another API call.
            val singleReady = StremioC.episodeBasedByUrl[url] == false ||
                (seasonNumbers.size == 1 && selectionIndex?.let {
                    seasonNumbers.getOrNull(it) != null
                } == true)
            if (!singleReady) return
            when (result) {
                is Resource.Success -> {
                    val meta = result.value
                    if (meta.id == expectedId.toString()) {
                        if (!meta.actors.isNullOrEmpty()) {
                            StremioC.rememberAnimeCast(expectedId, meta.actors)
                            showAniListCast(meta.actors)
                        } else if (meta.actors != null || coreAniListLoading) {
                            showTmdbFallback()
                        }
                    } else if (coreAniListLoading &&
                        model.getSyncs().any { (provider, id) ->
                            provider != "anilist" && id == meta.id
                        }) {
                        showTmdbFallback()
                    }
                    coreAniListLoading = false
                }
                is Resource.Failure -> {
                    if (coreAniListLoading) showTmdbFallback()
                    coreAniListLoading = false
                }
                else -> Unit
            }
        }

        private fun resolve() {
            val url = pageUrl ?: return
            // The core posts ResultData before seasonSelections and the selected
            // season index. Starting a provisional season 1 at that point treats
            // a multi-season anime as a single-season one. When the real season
            // list arrives, the old season-only guard prevents ID remapping and
            // MAL/AniList/Kitsu tracker setup until the user changes seasons.
            val chosen = if (seasonNumbers.isNotEmpty()) {
                selectionIndex?.takeIf { it >= 0 }
                    ?.let { seasonNumbers.getOrNull(it) }
            } else if (StremioC.episodeBasedByUrl[url] == false) {
                1 // Movies have no native season selections.
            } else null
            if (chosen == null || chosen <= 0) return
            val inputs = Triple(chosen, seasonNumbers.toList(), rawSyncs.toMap())
            if (inputs == resolvedSeasonInputs) return
            resolvedSeasonInputs = inputs
            if (seasonCast != null) {
                seasonCast = null
                showingAniListCast = false
                showSeasonCast(emptyList())
            }
            // The first season selection may occur after the core emitted
            // Loading. Do not discard that request's terminal-result guard.
            if (season != null) coreAniListLoading = false
            season = chosen
            generation++
            begin()
            // The native metadata result may have arrived before its season
            // selection. Reuse LiveData's current value, not a new network call.
            if (seasonNumbers.size <= 1)
                applyCoreCastMetadata(syncModel?.metadata?.value)
        }

        private fun reset() {
            fetchJob?.cancel()
            fetchJob = null
            statuses.clear()
            selected = null
            startedFor = null
        }

        private fun begin() {
            val url = pageUrl ?: return
            val sn = season ?: return
            val isAnime = StremioC.animeAudioByUrl[url]
                ?: detailUrl?.let { StremioC.animeAudioByUrl[it] }
            val multi = seasonNumbers.size > 1
            val generalSimkl = isAnime != true && multi && rawSyncs.containsKey("simkl")
            if (isAnime != true && !generalSimkl) {
                reset()
                teardownUi()
                return
            }
            reset()
            val gen = generation
            // Do not inflate the custom spinner during the result-page
            // transition. Create it only if a matched sync provider exists.
            fetchJob = scope.launch {
                var mappedSeason: SeasonTrackerIds? = null
                val ids = if (!multi) rawSyncs.filterKeys { it in allowed }
                    else if (generalSimkl) rawSyncs.filterKeys { it == "simkl" } else {
                    val imdb = IMDb.find(url)?.value ?: IMDb.find(detailUrl.orEmpty())?.value
                    val provider = APIHolder.allProviders.filterIsInstance<StremioC>()
                        .firstOrNull { it.name == fragment.arguments?.getString("apiName") }
                        ?: APIHolder.allProviders.filterIsInstance<StremioC>().firstOrNull()
                    if (imdb == null || provider == null) {
                        emptyMap()
                    } else {
                        val mapping = try { withContext(Dispatchers.IO) {
                            provider.seasonTrackerIds(imdb, sn)
                        } } catch (e: CancellationException) { throw e }
                          catch (e: Exception) {
                            null
                        }
                        mappedSeason = mapping
                        linkedMapOf<String,String>().apply {
                            mapping?.mal?.let { put("mal", it.toString()) }
                            mapping?.aniList?.let { put("anilist", it.toString()) }
                            mapping?.kitsu?.let { put("kitsu", it.toString()) }

                            if (mapping?.simkl != null) {
                                put("simkl", JSONObject().put("Simkl",mapping.simkl.toString()).toString())
                            } else mapping?.mal?.let { mal ->
                                put("simkl", JSONObject().put("Mal",mal.toString()).toString())
                            }
                        }
                    }
                }
                if (!valid(gen, sn)) return@launch
                val matched = if (syncModel != null && seasonNumbers.isNotEmpty())
                    AccountManager.syncApis.filter { ids.containsKey(it.idPrefix) }
                        .filter { it.authUser() != null }
                else emptyList()
                val animeId = if (isAnime == true && castEnabled()) {
                    ids["anilist"]?.toIntOrNull()?.takeIf { it > 0 } ?: run {
                        if (multi) null else {
                            val source = listOf("mal", "kitsu").firstNotNullOfOrNull { kind ->
                                ids[kind]?.toIntOrNull()?.takeIf { it > 0 }?.let { "${kind}:${it}" }
                            }
                            val provider = APIHolder.allProviders.filterIsInstance<StremioC>()
                                .firstOrNull { it.name == fragment.arguments?.getString("apiName") }
                                ?: APIHolder.allProviders.filterIsInstance<StremioC>().firstOrNull()
                            if (source == null || provider == null) null
                            else withContext(Dispatchers.IO) {
                                provider.playbackTrackerIds(
                                    StremioC.LoadData(id = source, isAnime = true)
                                )?.aniList
                            }
                        }
                    }
                } else null
                val castNeeded = isAnime == true &&
                    (multi || syncModel == null || ids["anilist"] == null)
                if (castNeeded) {
                    if (animeId == null) {
                        // Mapping has completed without an AniList ID.
                        showTmdbFallback()
                    } else {
                        val cached = StremioC.cachedAnimeCast(animeId)
                        if (cached != null) {
                            showAniListCast(cached)
                        } else if (matched.none { it.idPrefix == "anilist" &&
                                ids["anilist"] == animeId.toString() }) {
                            launch {
                                val actors = StremioC.loadAnimeCast(animeId)
                                if (valid(gen, sn) && pageUrl == url) {
                                    if (actors.isNullOrEmpty()) showTmdbFallback()
                                    else showAniListCast(actors)
                                }
                            }
                        }
                    }
                }
                if (syncModel == null || seasonNumbers.isEmpty()) {
                    teardownUi()
                    return@launch
                }
                if (matched.isEmpty()) {
                    render()
                    return@launch
                }
                statuses = linkedMapOf<String,Tracker>().apply {
                    matched.forEach { repo ->
                        put(repo.idPrefix, Tracker(repo.idPrefix, ids.getValue(repo.idPrefix), repo))
                    }
                }
                selected = preferred?.takeIf { it in statuses } ?: statuses.keys.firstOrNull()
                updateOptions()
                render()
                val simklSeasonReplies =
                    java.util.concurrent.ConcurrentHashMap<String, SimklSeasonSnapshot>()
                val simklAnimeReplies =
                    java.util.concurrent.ConcurrentHashMap<String, AnimeSnapshot>()
                val ratingLoaded = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
                val replies = matched.map { repo ->
                    async(Dispatchers.IO) {
                        val id = ids.getValue(repo.idPrefix)
                        val state = repo.status(id)

                        val meta = if (repo.idPrefix == "mal" || repo.idPrefix == "kitsu")
                            null else repo.load(id)
                        if (castNeeded && animeId != null && repo.idPrefix == "anilist" &&
                            id == animeId.toString()) {
                            val actors = meta?.getOrNull()?.actors
                            withContext(Dispatchers.Main) {
                                if (valid(gen, sn)) {
                                    if (!actors.isNullOrEmpty()) {
                                        StremioC.rememberAnimeCast(animeId, actors)
                                        showAniListCast(actors)
                                    } else showTmdbFallback()
                                }
                            }
                        }
                        val remote = state.getOrNull()
                        val simklExact = if (generalSimkl && repo.idPrefix == "simkl")
                            simklSeasonRead(repo, id, sn) else null
                        if (generalSimkl && repo.idPrefix == "simkl" && simklExact != null)
                            simklSeasonReplies["simkl"] = simklExact
                        val animeExact = if (multi && isAnime == true &&
                            repo.idPrefix == "simkl" && mappedSeason != null) {
                            simklAnimeRead(repo, mappedSeason!!, sn)
                        } else null
                        if (multi && isAnime == true && repo.idPrefix == "simkl" &&
                            animeExact != null) simklAnimeReplies["simkl"] = animeExact
                        var total = when {
                            generalSimkl && repo.idPrefix == "simkl" -> simklExact?.available?.size
                            multi && isAnime == true && repo.idPrefix == "simkl" ->
                                animeExact?.mapped?.size
                            else -> meta?.getOrNull()?.totalEpisodes?.takeIf { it > 0 }
                                ?: remote?.maxEpisodes?.takeIf { it > 0 }
                        }
                        var origin = if (simklExact != null) "simkl-season-episode-keys"
                            else if (animeExact != null) "simkl-anime-tvdb-episode-map"
                            else if (repo.idPrefix == "simkl" && multi) "unverified"
                            else if (meta?.getOrNull()?.totalEpisodes?.let { it > 0 } == true)
                                "provider-metadata"
                            else if (total != null) "provider-status" else "unknown"
                        if (repo.idPrefix == "kitsu") {
                            for (host in listOf("https://kitsu.io/api/edge", "https://kitsu.app/api/edge")) {
                                try {
                                    val response = app.get(
                                        "$host/anime/$id?fields[anime]=episodeCount",
                                        cacheTime = 0)
                                    val attributes = JSONObject(response.text)
                                        .optJSONObject("data")?.optJSONObject("attributes")
                                    val episodes = attributes?.opt("episodeCount")?.toString()
                                        ?.toIntOrNull()?.takeIf { it > 0 }
                                    if (episodes != null) {
                                        total = episodes
                                        origin = "kitsu-episodeCount"
                                        break
                                    }
                                } catch (e: Exception) {
                                }
                            }
                        }
                        if (total == null && repo.idPrefix == "mal") {
                            try {
                                val token = repo.authToken()?.accessToken
                                if (token != null) {
                                    val response = app.get(
                                        "https://api.myanimelist.net/v2/anime/$id?fields=num_episodes",
                                        headers = mapOf("Authorization" to "Bearer $token"), cacheTime = 0)
                                    total = JSONObject(response.text).optInt("num_episodes").takeIf { it > 0 }
                                    if (total != null) origin = "mal-lightweight"
                                }
                            } catch (e: Exception) {
                            }
                        }
                        val fields = if (multi && isAnime == true &&
                            repo.idPrefix == "simkl") animeExact?.let {
                            val watched=it.watched.size
                            val projected=animeSeasonDisplayStatus(it)
                            val targets=it.sourceIds.map { source ->
                                JSONObject().put("simkl",source)
                            }
                            val scores=simklScores(repo,targets,true)
                            ratingLoaded["simkl"]=scores!=null
                            val score=scores?.distinctBy { value -> value?.toInt(10) }
                                ?.singleOrNull()
                            Draft(projected,watched,score)
                        } else remote?.let {
                            Draft(if (generalSimkl && repo.idPrefix == "simkl" && simklExact != null)
                                simklSeasonStatus(simklExact) else it.status,
                                if (generalSimkl && repo.idPrefix == "simkl")
                                    simklExact?.watched?.size ?: 0
                                else it.watchedEpisodes ?: 0, it.score)
                        }
                        Triple(repo.idPrefix, fields, Pair(total, origin))
                    }
                }.awaitAll()
                if (!valid(gen, sn)) { return@launch }
                statuses["simkl"]?.let { simkl ->
                    simkl.simklSeason = simklSeasonReplies["simkl"]
                    simkl.simklAnime = simklAnimeReplies["simkl"]
                    simkl.ratingLoaded = ratingLoaded["simkl"] ?: true
                    simkl.seasonMapping = mappedSeason
                }
                val providerTotals = replies.mapNotNull { it.third.first }.filter { it > 0 }
                val commonTotal = if (!multi) providerTotals.maxOrNull()
                    else providerTotals.distinct().singleOrNull()
                replies.forEach { (prefix, draft, count) ->
                    val target = statuses[prefix] ?: return@forEach
                    target.loading = false
                    target.baseline = draft?.snapshot()
                    target.draft = draft?.snapshot()
                    val trackerTotal = count.first
                    target.total = trackerTotal ?: commonTotal
                    target.totalSource = if (trackerTotal != null) count.second
                        else if (commonTotal != null) {
                            if (multi) "season-mapped-peer" else "single-season-peer"
                        } else "unknown"
                    target.error = if (draft == null) "status-unavailable" else null

                    target.writeBlocked = when {
                        generalSimkl && prefix == "simkl" && target.simklSeason == null ->
                            "simkl-season-episodes-unverified"
                        multi && isAnime == true && prefix == "simkl" &&
                            target.simklAnime == null -> "simkl-anime-episode-map-unverified"
                        else -> null
                    }
                }
                updateOptions()
                render()
            }
        }

        private fun ensureUi() {
            if (combo != null) return
            val names = root.findViewById<TextView>(R.id.result_sync_names) ?: return
            val parent = names.parent as? android.widget.LinearLayout ?: return
            val index = parent.indexOfChild(names)
            oldNames = names
            val activity = fragment.requireActivity()
            val dp = root.resources.displayMetrics.density

            val spinner = catalogStyledSelector(activity)
            val width = (208 * dp).toInt()
            spinner.dropDownWidth = width
            spinner.contentDescription = "Sync provider"
            val container = FrameLayout(activity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    width, (48 * dp).toInt()
                ).apply { bottomMargin = (10 * dp).toInt() }
                addView(spinner, FrameLayout.LayoutParams(-1, -1))
            }
            val arrow = TextView(activity).apply {
                text = "▼"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * 0.7f)
                setTextColor(activity.colorFromAttribute(R.attr.textColor))
                gravity = Gravity.CENTER
                isClickable = false
                isFocusable = false
            }
            container.addView(arrow, FrameLayout.LayoutParams(
                (40 * dp).toInt(), -1, Gravity.END))
            parent.addView(container, index)
            names.visibility = View.GONE
            combo = spinner
            comboContainer = container
            comboArrow = arrow
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (applying) return
                    val key = statuses.keys.toList().getOrNull(position) ?: return
                    if (key == selected) return
                    selected = key
                    preferred = key
                    render()
                }
            }
            plus?.setOnClickListener { adjust(1) }
            minus?.setOnClickListener { adjust(-1) }
            saveButton?.setOnClickListener { save() }
            checks?.setOnItemClickListener { _, _, pos, _ ->
                val tracker = statuses[selected] ?: return@setOnItemClickListener
                if (isWriteInFlight(tracker) || tracker.writeBlocked != null) {
                    render()
                    return@setOnItemClickListener
                }
                val edit = tracker.draft ?: return@setOnItemClickListener
                edit.status = SyncWatchType.fromInternalId(pos - 1)
                val tv = tracker.simklSeason
                val anime = tracker.simklAnime
                if (anime != null && edit.status == SyncWatchType.COMPLETED)
                    edit.watched = anime.mapped.size
                if (tv != null) {
                    if (edit.status == SyncWatchType.COMPLETED)
                        edit.watched = tv.available.size
                    else if (edit.status == SyncWatchType.NONE) edit.watched = 0
                } else if (tracker.prefix == "mal" &&
                    edit.status == SyncWatchType.COMPLETED &&
                    tracker.totalSource == "mal-lightweight") {
                    tracker.total?.takeIf { it > 0 }?.let { edit.watched = it }
                }
                edit.explicitNone = edit.status == SyncWatchType.NONE
                render()
            }
            slider?.clearOnChangeListeners()
            slider?.addOnChangeListener { _, value, fromUser ->
                if (!fromUser || applying) return@addOnChangeListener
                val tracker = statuses[selected] ?: return@addOnChangeListener
                if (isWriteInFlight(tracker) || tracker.writeBlocked != null) {
                    return@addOnChangeListener
                }
                val draft = tracker.draft ?: return@addOnChangeListener
                draft.score = Score.from(value, 10)
                render()
            }
            current?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (applying || current?.hasFocus() != true) return
                    val tracker = statuses[selected] ?: return
                    if (isWriteInFlight(tracker) || tracker.writeBlocked != null) {
                        return
                    }
                    val draft = tracker.draft ?: return
                    val entered = s?.toString()?.toIntOrNull() ?: return
                    val clamped = entered.coerceIn(0, tracker.total?.takeIf { it > 0 } ?: Int.MAX_VALUE)
                    draft.watched = clamped
                    reconcileManualProgress(tracker,draft)
                    root.post { if (active && selected == tracker.prefix) render() }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        private var lastProviderOptions: List<String> = emptyList()

        private fun updateOptions() {
            if (!active || !fragment.isAdded) return
            if (combo == null) ensureUi()
            val spinner = combo ?: return
            applying = true
            try {
                val names = statuses.keys.map { titles[it] ?: it }
                if (spinner.adapter == null || names != lastProviderOptions) {
                    val activity = fragment.requireActivity()
                    val checkId = pluginResources.getIdentifier(
                        "addon_check_icon", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
                    spinner.adapter = catalogStyledAdapter(activity, names, spinner,
                        android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                            android.graphics.Typeface.BOLD),
                        android.widget.TextView(activity).textSize,
                        if (checkId != 0) pluginResources.getDrawable(checkId, null) else null)
                    lastProviderOptions = names
                }
                spinner.isEnabled = statuses.size > 1
                spinner.isClickable = statuses.size > 1
                spinner.isFocusable = statuses.size > 1
                comboArrow?.visibility = if (statuses.size > 1) View.VISIBLE else View.GONE
                val position = statuses.keys.indexOf(selected).coerceAtLeast(0)
                if (names.isNotEmpty() && spinner.selectedItemPosition != position)
                    spinner.setSelection(position, false)
            } finally { applying = false }
        }

        private fun reconcileManualProgress(tracker: Tracker, edit: Draft) {
            val total=tracker.total?.takeIf { it>0 }
            if(total!=null && edit.status==SyncWatchType.COMPLETED &&
                edit.watched<total) {
                edit.status=SyncWatchType.WATCHING
                edit.explicitNone=false
            }
        }

        private fun adjust(delta: Int) {
            val tracker = statuses[selected] ?: return
            if (isWriteInFlight(tracker) || tracker.writeBlocked != null) {
                return
            }
            val draft = tracker.draft ?: return
            val upper = tracker.total?.takeIf { it > 0 } ?: Int.MAX_VALUE
            draft.watched = (draft.watched.toLong() + delta).coerceIn(0L, upper.toLong()).toInt()
            reconcileManualProgress(tracker,draft)
            render()
        }

        private fun render() {
            if (!active || combo == null) return
            val target = statuses[selected]
            if (target == null || target.loading || target.draft == null) {

                applying = true
                try {
                    holder?.visibility = View.VISIBLE
                    shimmer?.visibility = View.GONE
                    saveButton?.isEnabled = false
                    plus?.isEnabled = false
                    minus?.isEnabled = false
                    if (maximum?.text?.toString() != "/ ?") maximum?.text = "/ ?"
                    if (bar?.progress != 0) bar?.progress = 0
                    if (!current?.text.isNullOrEmpty()) current?.setText("")
                    if (checks?.checkedItemCount != 0) checks?.clearChoices()
                    if (scoreLabel?.text?.toString() != "?") scoreLabel?.text = "?"
                } finally { applying = false }

                return
            }
            val data = target.draft!!
            applying = true
            try {
                holder?.visibility = View.VISIBLE
                shimmer?.visibility = View.GONE
                (shimmer as? com.facebook.shimmer.ShimmerFrameLayout)?.stopShimmer()
                val max = target.total?.takeIf { it > 0 }
                val maxLabel = if (max == null) "/ ?" else "/ $max"
                if (maximum?.text?.toString() != maxLabel) maximum?.text = maxLabel
                val barMax = (max ?: maxOf(1, data.watched)).coerceAtMost(1000000) * 1000
                val barProgress = data.watched.coerceAtMost(1000000) * 1000
                if (bar?.max != barMax) bar?.max = barMax
                if (bar?.progress != barProgress) bar?.progress = barProgress
                val newText = data.watched.toString()
                if (current?.text?.toString() != newText) {
                    current?.setText(newText)
                    current?.setSelection(newText.length)
                }
                val checked = data.status.internalId + 1
                if (checks?.checkedItemPosition != checked)
                    checks?.setItemChecked(checked, true)

                checks?.isEnabled = !isWriteInFlight(target) && target.writeBlocked == null &&
                    (target.simklSeason == null || target.simklSeason?.listStatus != null) &&
                    (target.simklAnime == null || target.simklAnime?.statusEditable == true)
                slider?.isEnabled = !isWriteInFlight(target) && target.writeBlocked == null &&
                    (target.simklAnime == null || target.ratingLoaded)
                val valScore = data.score?.toFloat(10) ?: 0f
                val value = valScore.coerceIn(0f,10f).toInt().toFloat()
                if (slider?.value != value) slider?.value = value
                val scoreText = data.score?.toInt(10)?.toString()?.plus("/10") ?: "?"
                if (scoreLabel?.text?.toString() != scoreText) scoreLabel?.text = scoreText
                saveButton?.isEnabled = !isWriteInFlight(target) && target.writeBlocked == null
                current?.isEnabled = !isWriteInFlight(target) && target.writeBlocked == null
                plus?.isEnabled = !isWriteInFlight(target) && target.writeBlocked == null
                minus?.isEnabled = !isWriteInFlight(target) && target.writeBlocked == null
            } catch (e: Exception) {
            } finally { applying = false }
        }

        private suspend fun malDirectVerify(
            tracker: Tracker, expected: com.lagradost.cloudstream3.syncproviders.SyncAPI.AbstractSyncStatus,
            compareStatus: Boolean, compareProgress: Boolean, compareScore: Boolean
        ): Boolean {
            val token=tracker.repo.authToken()?.accessToken
            val id=tracker.id.toIntOrNull()
            if(token==null || id==null)return false
            val response=try {
                app.get("https://api.myanimelist.net/v2/anime/$id?fields=my_list_status",
                    headers=mapOf("Authorization" to "Bearer $token"),cacheTime=0)
            }catch(e:CancellationException){throw e}
             catch(e:Exception){
                return false
             }
            if(response.code !in 200..299) { response.okhttpResponse.close(); return false }
            val body=runCatching { JSONObject(response.text) }.getOrNull()
            val actual=body?.optJSONObject("my_list_status") ?: return false
            val wantedStatus=when(expected.status){
                SyncWatchType.WATCHING -> "watching"
                SyncWatchType.COMPLETED -> "completed"
                SyncWatchType.ONHOLD -> "on_hold"
                SyncWatchType.DROPPED -> "dropped"
                SyncWatchType.PLANTOWATCH -> "plan_to_watch"
                else -> null
            }
            val statusOk=!compareStatus || (wantedStatus!=null &&
                actual.optString("status")==wantedStatus)
            val progressOk=!compareProgress ||
                actual.optInt("num_episodes_watched",-1)==(expected.watchedEpisodes ?: 0)
            val scoreOk=!compareScore ||
                actual.optInt("score",-1)==(expected.score?.toInt(10) ?: 0)
            val valid=statusOk && progressOk && scoreOk
            return valid
        }

        private fun save() {
            val tracker = statuses[selected] ?: return
            val draft = tracker.draft?.snapshot() ?: return
            val initial = tracker.baseline ?: return
            val gen = generation
            val sn = season ?: return
            val prefix = tracker.prefix
            val id = tracker.id
            val before = initial.snapshot()
            if (tracker.writeBlocked != null || isWriteInFlight(tracker)) {
                return
            }
            if (prefix == "mal" && draft.status == SyncWatchType.COMPLETED &&
                before.status != SyncWatchType.COMPLETED) {

                val total = tracker.total?.takeIf {
                    it > 0 && tracker.totalSource == "mal-lightweight"
                }
                if (total == null) {
                    return
                }
                draft.watched = total
                tracker.draft?.watched = total
            }
            if (draft.status == SyncWatchType.NONE && draft.watched > 0 && !draft.explicitNone) {
                draft.status = SyncWatchType.WATCHING
                tracker.draft?.status = SyncWatchType.WATCHING
            }
            val updated = draft != before
            if (!updated) { return }
            if (draft.status == SyncWatchType.NONE && !draft.explicitNone &&
                (draft.score != before.score || draft.watched != before.watched)) {
                return
            }
            if (draft.watched < 0 || tracker.total?.let { it > 0 && draft.watched > it } == true) {
                return
            }
            if (tracker.simklAnime != null && !tracker.ratingLoaded &&
                draft.score != before.score) return
            val animeProgressDemotion=tracker.simklAnime?.statusEditable==false &&
                before.status==SyncWatchType.COMPLETED &&
                draft.status==SyncWatchType.WATCHING && draft.watched<before.watched
            if (tracker.simklAnime != null && draft.status != before.status &&
                !animeProgressDemotion &&
                !(before.status == SyncWatchType.NONE &&
                  draft.status == SyncWatchType.WATCHING && draft.watched > 0) &&
                (tracker.simklAnime?.statusEditable != true ||
                 (draft.status != SyncWatchType.NONE &&
                  simklWatchlistDestination(draft.status)==null))) {
                return
            }
            val operationKey="$prefix:$id"
            if(!pendingWrites.add(operationKey)) {
                return
            }
            var writeAcceptedButUnconfirmed=false
            tracker.saving = true
            render()

            saveScope.launch {
                val result = try { withContext(Dispatchers.IO) {
                    if(prefix=="mal" && draft.status==SyncWatchType.NONE &&
                        draft.explicitNone) {
                        malRemoveFromLibrary(tracker.repo,id)
                    } else if (tracker.simklSeason != null) {
                        val changed=draft.status!=before.status || draft.watched!=before.watched ||
                            (draft.status==SyncWatchType.NONE && draft.explicitNone)
                        val ratingChanged=draft.score!=before.score &&
                            !(draft.status==SyncWatchType.NONE && draft.explicitNone)
                        (!changed || simklSeasonSave(tracker,draft.copy(score=before.score),before,sn)) &&
                            (!ratingChanged || simklSaveScores(tracker.repo,
                                listOf(tracker.simklSeason!!.ids),draft.score,false))
                    } else if (tracker.simklAnime != null) {
                        val changed=draft.status!=before.status || draft.watched!=before.watched ||
                            (draft.status==SyncWatchType.NONE && draft.explicitNone)
                        val ratingChanged=draft.score!=before.score &&
                            !(draft.status==SyncWatchType.NONE && draft.explicitNone)
                        (!changed || simklAnimeSave(tracker,draft.copy(score=before.score),before,sn)) &&
                            (!ratingChanged || simklSaveScores(tracker.repo,
                                tracker.simklAnime!!.sourceIds.map { source ->
                                    JSONObject().put("simkl",source)
                                },draft.score,true))
                    } else {
                    val fresh = tracker.repo.status(id)
                    val remote = fresh.getOrNull()
                    if (remote == null) {
                        false
                    } else {
                        if (draft.status != before.status) remote.status = draft.status
                        if (draft.watched != before.watched) remote.watchedEpisodes = draft.watched
                        if (draft.score != before.score) remote.score = draft.score
                        if (remote.status == SyncWatchType.NONE && (remote.watchedEpisodes ?: 0) > 0
                            && !draft.explicitNone) remote.status = SyncWatchType.WATCHING
                        if (remote.status != SyncWatchType.NONE && remote.watchedEpisodes == null)
                            remote.watchedEpisodes = 0
                        val issued = tracker.repo.updateStatus(id, remote)
                        var verified = false
                        val maxAttempts=if(prefix=="mal")7 else 5
                        for (attempt in 1..maxAttempts) {
                            if (attempt > 1) kotlinx.coroutines.delay(
                                when(attempt) {
                                    2->350L;3->900L;4->1800L;5->3000L
                                    6->4000L;else->5000L
                                })
                            val read = tracker.repo.status(id)
                            val got = read.getOrNull()
                            verified = got != null &&
                                (draft.status == before.status || got.status == remote.status) &&
                                (draft.watched == before.watched || (got.watchedEpisodes ?: 0) == (remote.watchedEpisodes ?: 0)) &&
                                (draft.score == before.score || got.score == remote.score)
                            if (verified) break
                        }
                        if(!verified && prefix=="mal" && issued.getOrNull()==true) {
                            for(attempt in 1..2) {
                                if(attempt>1)kotlinx.coroutines.delay(2000L)
                                verified=malDirectVerify(tracker,remote,
                                    draft.status!=before.status,
                                    draft.watched!=before.watched,
                                    draft.score!=before.score)
                                if(verified)break
                            }
                            if(!verified) {
                                writeAcceptedButUnconfirmed=true
                            }
                        }
                        if (!verified && prefix == "kitsu" && remote.status != SyncWatchType.NONE) {
                            val fallback = kitsuFallback(id, remote, tracker.repo)
                            if (fallback) {
                                val after = tracker.repo.status(id).getOrNull()
                                verified = after != null &&
                                    (draft.status == before.status || after.status == remote.status) &&
                                    (draft.watched == before.watched ||
                                        (after.watchedEpisodes ?: 0) == (remote.watchedEpisodes ?: 0)) &&
                                    (draft.score == before.score || after.score == remote.score)
                            }
                        }
                        verified
                    }
                    }
                } } catch(e:CancellationException) {
                    false
                } catch(e:Exception) {
                    false
                }
                if(result || writeAcceptedButUnconfirmed) {
                    try {
                        withContext(Dispatchers.IO) { refreshAfterVerifiedWrite(tracker,prefix) }
                    } catch(e:CancellationException) {
                    } catch(e:Exception) {
                    }
                }
                pendingWrites.remove(operationKey)
                if (valid(gen, sn) && statuses[prefix] === tracker) {
                    tracker.saving = false
                    if (result) {
                        tracker.error = null
                        val anime = tracker.simklAnime
                        if (anime != null) {
                            val count=anime.watched.size
                            val actual=animeSeasonDisplayStatus(anime)
                            val savedScore=if(draft.status==SyncWatchType.NONE && draft.explicitNone)
                                null else draft.score
                            tracker.baseline=Draft(actual,count,savedScore)
                            tracker.draft?.let { current ->
                                if(current == draft) {
                                    current.status=actual
                                    current.watched=count
                                    current.score=savedScore
                                    current.explicitNone=false
                                }
                            }
                        } else if (tracker.simklSeason != null) {
                            val actual = tracker.simklSeason!!
                            tracker.baseline = Draft(simklSeasonStatus(actual),
                                actual.watched.size,
                                if(draft.status==SyncWatchType.NONE && draft.explicitNone)
                                    null else draft.score)
                            tracker.draft = tracker.baseline?.snapshot()
                        } else if(prefix=="mal" && draft.status==SyncWatchType.NONE &&
                            draft.explicitNone) {
                            tracker.baseline=Draft(SyncWatchType.NONE,0,null)
                            tracker.draft=tracker.baseline?.snapshot()
                        } else {
                            tracker.baseline = draft.snapshot()
                            tracker.draft?.let { if (it == draft) it.explicitNone = false }
                        }
                    } else tracker.error = if(writeAcceptedButUnconfirmed)
                        "server-write-readback-pending" else "save-or-readback-failed"
                    render()
                }
                if(result || writeAcceptedButUnconfirmed) {
                    MainActivity.reloadLibraryEvent(true)
                    val model=syncModel
                    if(active && model!=null) {
                        model.updateUserData()
                        model.updateSynced()
                    }
                }
            }
        }

        private suspend fun malRemoveFromLibrary(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            id: String
        ): Boolean {
            val animeId=id.toIntOrNull()?.takeIf { it>0 } ?: return false
            val token=repo.authToken()?.accessToken ?: return false
            val headers=mapOf("Authorization" to "Bearer $token")
            val url="https://api.myanimelist.net/v2/anime/$animeId/my_list_status"
            val deleted=try {
                app.delete(url,headers=headers)
            }catch(e: CancellationException){throw e}
             catch(e: Exception){
                  return false}
            val deleteStatus = deleted.code
            deleted.okhttpResponse.close()
            if(deleteStatus !in 200..299 && deleteStatus != 404)return false
            repeat(3){ attempt ->
                if(attempt>0)kotlinx.coroutines.delay(if(attempt==1)350L else 900L)
                val response=try {
                    app.get("https://api.myanimelist.net/v2/anime/$animeId",
                        headers=headers,params=mapOf("fields" to "my_list_status"),
                        cacheTime=0)
                }catch(e: CancellationException){throw e}
                 catch(e: Exception){
                  return@repeat}
                if(response.code in 200..299) {
                    val data=runCatching { JSONObject(response.text) }.getOrNull()
                    if(data!=null && (data.isNull("my_list_status") ||
                        !data.has("my_list_status")))return true
                } else response.okhttpResponse.close()
            }
            return false
        }

        private suspend fun kitsuFallback(
            id: String, expected: SyncAPI.AbstractSyncStatus,
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo
        ): Boolean {
            val status = when (expected.status) {
                SyncWatchType.WATCHING, SyncWatchType.REWATCHING -> "current"
                SyncWatchType.COMPLETED -> "completed"
                SyncWatchType.PLANTOWATCH -> "planned"
                SyncWatchType.ONHOLD -> "on_hold"
                SyncWatchType.DROPPED -> "dropped"
                else -> return false
            }
            val user = repo.authData() ?: return false
            val headers = mapOf("Authorization" to "Bearer ${user.token.accessToken}",
                "content-type" to "application/vnd.api+json")
            val payload = JSONObject().put("status", status)
                .put("progress", expected.watchedEpisodes ?: 0)
            expected.score?.let { payload.put("ratingTwenty", it.toInt(20)) }
            for (host in listOf("https://kitsu.io/api/edge", "https://kitsu.app/api/edge")) {
                try {
                    val find = app.get(
                        "$host/library-entries?filter[userId]=${user.user.id}&filter[animeId]=$id",
                        headers = headers, cacheTime = 0)
                    val entry = if (find.code in 200..299)
                        JSONObject(find.text).optJSONArray("data")?.optJSONObject(0)
                            ?.optString("id")?.takeIf { it.isNotBlank() } else null
                    if (find.code !in 200..299) {
                        find.okhttpResponse.close()
                        continue
                    }
                    val data = JSONObject().put("type","libraryEntries").put("attributes",payload)
                    if (entry != null) data.put("id",entry)
                    else data.put("relationships", JSONObject()
                        .put("anime", JSONObject().put("data",
                            JSONObject().put("type","anime").put("id",id)))
                        .put("user", JSONObject().put("data",
                            JSONObject().put("type","users").put("id",user.user.id.toString()))))
                    val body = JSONObject().put("data",data).toString().toRequestBody()
                    val response = if (entry == null)
                        app.post("$host/library-entries", headers=headers, requestBody=body)
                    else app.patch("$host/library-entries/$entry", headers=headers, requestBody=body)

                    val writeStatus = response.code
                    response.okhttpResponse.close()
                    if (writeStatus in 200..299) return true
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                }
            }
            return false
        }

        private fun teardownUi() {
            val spinner = combo ?: return
            (comboContainer?.parent as? android.view.ViewGroup)?.removeView(comboContainer)
            comboContainer = null
            comboArrow = null
            combo = null
            lastProviderOptions = emptyList()
            oldNames?.visibility = View.VISIBLE
            oldNames = null
            syncModel?.let { model ->
                saveButton?.setOnClickListener { model.publishUserData() }
                plus?.setOnClickListener { model.setEpisodesDelta(1) }
                minus?.setOnClickListener { model.setEpisodesDelta(-1) }
                checks?.setOnItemClickListener { _, _, index, _ ->
                    model.setStatus(index - 1)
                }
                slider?.clearOnChangeListeners()
                slider?.addOnChangeListener { view, value, fromUser ->
                    if (fromUser) model.setScore(Score.from(value, view.valueTo.toInt()))
                }
            }
        }

        fun stop() {
            if (!active) return
            active = false
            generation++
            fetchJob?.cancel()

            scope.cancel()
            observers.forEach { it() }
            observers.clear()
            if (castAnimationsDisabled) {
                castItems?.itemAnimator = originalCastAnimator
                castAnimationsDisabled = false
                originalCastAnimator = null
            }
            teardownUi()
            syncModel = null
        }
    }

    private inner class Watcher(private val view: TextView, private val root: View, private val detailUrl: String?, private val fragment: Fragment) : TextWatcher {
        private val backdrop = DetailBackdropLayout(root, detailUrl)
        private val seasonSyncProbe = SeasonSyncProbe(fragment, root, detailUrl, resources)
        private val trailers = TrailerChooser(fragment, root, detailUrl)
        private var changing = false

        fun matchesMdbKey(key: String): Boolean = StremioCMdbUiBridge.keyFor(detailUrl) == key

        fun refreshLateMdb(): Boolean {
            val key = StremioCMdbUiBridge.keyFor(detailUrl) ?: return false
            if (!fragment.isVisible || fragment.view !== root) {
                return false
            }
            return injectCachedRatings("late")
        }

        private fun injectCachedRatings(reason: String): Boolean {
            if (!prefs.getBoolean("mdblist_enabled", false)) return false
            val key = StremioCMdbUiBridge.keyFor(detailUrl) ?: return false
            val ratings = StremioC.mdbRatingsForUi(key)
            if (ratings == null) {
                return false
            }
            val old = view.text ?: return false
            if (old.isEmpty() || old.startsWith('\u2063')) {
                return false
            }
            val combined = SpannableStringBuilder("\u2063${ratings.joinToString("  ")}\n").append(old)
            val decorated = decoratedRatings(combined, view.textSize) ?: combined
            changing = true
            try { view.setText(decorated, TextView.BufferType.SPANNABLE) }
            finally { changing = false }
            return true
        }

        private val castItems = root.resources.getIdentifier("result_cast_items", "id", root.context.packageName)
            .takeIf { it != 0 }?.let { root.findViewById<RecyclerView>(it) }
        private val actorNameId = root.resources.getIdentifier("actor_name", "id", root.context.packageName)
        private val actorInformation = ActorInformation.Controller(fragment, detailUrl)
        private var pendingActorOpenAnimation: android.view.animation.Animation? = null

        private fun cancelPendingActorOpen() {
            pendingActorOpenAnimation?.setAnimationListener(null)
            pendingActorOpenAnimation = null
        }
        private val genreGroup = root.findViewById<ChipGroup>(R.id.result_tag)
        private val boundGenreChips = mutableMapOf<View, CatalogShortcutTarget>()
        private val boundActorCards = mutableSetOf<View>()

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

        private fun openActorSearch(query: String) {
            if (query.isBlank()) return
            val id = root.resources.getIdentifier("navigation_search", "id", root.context.packageName)
            if (id == 0) return
            val enter = root.resources.getIdentifier("enter_anim", "anim", root.context.packageName)
            val exit = root.resources.getIdentifier("exit_anim", "anim", root.context.packageName)
            fragment.activity?.navigate(id, Bundle().apply {
                putString("search_query", query)
                putBoolean("global_detail_search", true)
            }, NavOptions.Builder()
                .setEnterAnim(enter).setExitAnim(exit)
                .setPopEnterAnim(enter).setPopExitAnim(exit)
                .build())
        }
        // The native click expands 0.8 -> 1.0 in 200ms.
        // Start the reverse press feedback immediately on ACTION_DOWN so the
        // portrait reaches 0.8 before Android's normal long-click timeout.
        private val castPressInterpolator = android.view.animation.DecelerateInterpolator()
        private var castLongPressHandled = false
        private val castPressTouch = View.OnTouchListener { card, event ->
            if (pendingActorOpenAnimation != null) return@OnTouchListener false
            val portrait = card.findViewById<View>(R.id.voice_actor_image_holder2)
                ?: return@OnTouchListener false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    castLongPressHandled = false
                    portrait.animate().cancel()
                    portrait.clearAnimation()
                    portrait.scaleX = 1f
                    portrait.scaleY = 1f
                    portrait.animate().scaleX(0.8f).scaleY(0.8f)
                        .setDuration(200L)
                        .setInterpolator(castPressInterpolator)
                        .start()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    // Do not jump to 1.0 here: onClick hands off to the native
                    // 0.8 -> 1.0 scale animation in the same UI turn.
                    if (!castLongPressHandled) portrait.animate().cancel()
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    // A successful long-click keeps the image at 0.8 while
                    // navigating; only an abandoned press resets immediately.
                    if (!castLongPressHandled) restoreCastPress(card)
                }
            }
            // Preserve Android's normal click/long-click gesture recognition.
            false
        }
        private fun restoreCastPress(card: View) {
            card.findViewById<View>(R.id.voice_actor_image_holder2)?.let { portrait ->
                portrait.animate().cancel()
                portrait.scaleX = 1f
                portrait.scaleY = 1f
            }
        }
        private val castClick = object : View.OnClickListener {
            override fun onClick(card: View) {
                // Keep the first actor-dialog click until its native animation completes.
                if (pendingActorOpenAnimation != null) return
                val list = castItems ?: return
                val position = list.getChildAdapterPosition(card)
                if (position == RecyclerView.NO_POSITION) return
                val adapter = list.adapter as? ActorAdaptor ?: return
                val actor = adapter.getItemOrNull(position) ?: return
                val model = ViewModelProvider(fragment)[ResultViewModel2::class.java]
                val page = (model.page.value as? Resource.Success<ResultData>)?.value
                val nativeAnimeCast = page != null &&
                    (StremioC.animeAudioByUrl[page.url] == true ||
                        detailUrl?.let { StremioC.animeAudioByUrl[it] } == true) &&
                    !ActorInformation.hasTmdbPerson(page.url, model.currentRepo?.api as? StremioC,
                        page, position, actor)
                val holder = list.findViewHolderForAdapterPosition(position)
                // Reset the property transform immediately before the native
                // click animation. No intermediate 1.0 frame is rendered.
                restoreCastPress(card)
                if (nativeAnimeCast) {
                    if (holder is ViewHolderState<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val nativeHolder = holder as ViewHolderState<Any>
                        adapter.inverted[actor] = adapter.inverted[actor] != true
                        adapter.onUpdateContent(nativeHolder, actor, position)
                        // Native rebind restores its browser-search handler.
                        card.setOnClickListener(this)
                        card.setOnLongClickListener(castLongClick)
                    }
                    return
                }
                if (holder is ViewHolderState<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val nativeHolder = holder as ViewHolderState<Any>
                    adapter.onUpdateContent(nativeHolder, actor, position)
                    // Core rebinds its default click and long-click handlers.
                    // Restore StremioC's actor dialog and in-app person search.
                    card.setOnClickListener(this)
                    card.setOnLongClickListener(castLongClick)
                }
                // Wait for the native portrait's 200ms scale animation, not a guessed delay.
                val runningAnimation = card.findViewById<View>(
                    R.id.voice_actor_image_holder2
                )?.animation
                if (runningAnimation == null) {
                    actorInformation.click(position, actor)
                } else {
                    pendingActorOpenAnimation = runningAnimation
                    runningAnimation.setAnimationListener(object :
                        android.view.animation.Animation.AnimationListener {
                        override fun onAnimationStart(animation: android.view.animation.Animation?) = Unit
                        override fun onAnimationRepeat(animation: android.view.animation.Animation?) = Unit
                        override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                            if (pendingActorOpenAnimation !== runningAnimation) return
                            pendingActorOpenAnimation = null
                            runningAnimation.setAnimationListener(null)
                            if (!fragment.isAdded || !card.isAttachedToWindow ||
                                list.getChildAdapterPosition(card) != position ||
                                adapter.getItemOrNull(position) !== actor) return
                            actorInformation.click(position, actor)
                        }
                    })
                }
            }
        }
        private val castLongClick = View.OnLongClickListener { card ->
            if (pendingActorOpenAnimation != null) return@OnLongClickListener true
            val list = castItems
            val position = list?.getChildAdapterPosition(card) ?: RecyclerView.NO_POSITION
            if (position != RecyclerView.NO_POSITION) {
                val adapter = list?.adapter as? ActorAdaptor
                val actor = adapter?.getItemOrNull(position)
                if (actor != null) {
                    // The native cast adapter flips its main photo using inverted,
                    // but leaves actor_name unchanged. Search the visible photo's owner.
                    val voiceShown = adapter.inverted[actor] == true &&
                        !actor.voiceActor?.image.isNullOrBlank()
                    val query = (if (voiceShown) actor.voiceActor?.name else actor.actor.name)
                        ?.trim().orEmpty()
                    if (query.isNotBlank()) {
                        castLongPressHandled = true
                        openActorSearch(query)
                    }
                }
            }
            // No valid search target: do not leave a consumed long-press scaled.
            if (!castLongPressHandled) restoreCastPress(card)
            true
        }
        // Bind on actual layout changes, never on every draw frame.
        // RecyclerView click handlers resolve the current adapter position at
        // click time, so recycled cards do not need per-frame rebinding.
        private val detailLayout = ViewTreeObserver.OnGlobalLayoutListener {
            if (fragment.isAdded) {
                castItems?.takeIf { it.isShown }?.let { list ->
                    val children = (0 until list.childCount).map(list::getChildAt).toSet()
                    for (card in children) {
                        boundActorCards.add(card)
                        // A native item rebind may replace both listeners even on the
                        // same attached card; restore only at actual layout boundaries.
                        card.setOnClickListener(castClick)
                        card.setOnLongClickListener(castLongClick)
                        card.setOnTouchListener(castPressTouch)
                    }
                    val removed = boundActorCards.filterNot(children::contains)
                    for (card in removed) {
                        card.setOnClickListener(null)
                        card.setOnLongClickListener(null)
                        card.setOnTouchListener(null)
                        restoreCastPress(card)
                        boundActorCards.remove(card)
                    }
                }
                bindGenreClicks()
            }
        }
        fun start() {
            actorInformation.start()
            runCatching { seasonSyncProbe.start() }.onFailure {
            }
            view.addTextChangedListener(this)
            if (castItems != null || genreGroup != null)
                root.viewTreeObserver.addOnGlobalLayoutListener(detailLayout)
            detailLayout.onGlobalLayout()
            runCatching { backdrop.start() }; runCatching { trailers.start() }; runCatching { render() }
        }
        fun detach() {
            castLongPressHandled = false
            cancelPendingActorOpen()
            seasonSyncProbe.stop()
            actorInformation.stop()
            view.removeTextChangedListener(this)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(detailLayout)
            boundGenreChips.keys.forEach { chip ->
                chip.setOnClickListener(null)
                chip.setOnTouchListener(null)
                chip.isClickable = false
                chip.isFocusable = false
            }
            boundGenreChips.clear()
            boundActorCards.forEach { card ->
                card.setOnClickListener(null)
                card.setOnLongClickListener(null)
                card.setOnTouchListener(null)
                restoreCastPress(card)
            }
            boundActorCards.clear()
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
            if (injectCachedRatings("render")) return
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
        removeHomeProviderObserver?.invoke()
        removeHomeProviderObserver = null
        homeRoot = null
        homeEntries.detach()
        detachPreview()
        subtitleController.detach()
        scrobble.detach()
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
