// 파일명: hsp1020/Utils.kt
package com.hsp1020

import com.hsp1020.pip.PipSubtitleScaler
import androidx.annotation.OptIn
import androidx.core.os.ConfigurationCompat
import androidx.media3.common.C
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.VideoLive

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
import kotlinx.coroutines.withContext
import android.os.SystemClock
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.SyncWatchType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import kotlin.math.round
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Build
import android.content.Context
import android.content.res.Resources
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.Gravity
import androidx.cardview.widget.CardView
import com.google.android.material.chip.Chip
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.AppCompatSpinner
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
import com.lagradost.cloudstream3.ui.result.SyncViewModel
import com.lagradost.cloudstream3.ui.result.ActorAdaptor
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
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.PREFERENCES_NAME
import com.lagradost.cloudstream3.ui.subtitles.SUBTITLE_AUTO_SELECT_KEY
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.utils.AppContextUtils.sortSubs
internal fun themedPressedForeground(view: View): Drawable? {
    val out = TypedValue()
    return if (view.context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, out, true
        ) && out.resourceId != 0)
        AppCompatResources.getDrawable(view.context, out.resourceId)?.mutate()
    else null
}

/** Use the episode foreground ripple, not a second hardcoded Chip ripple. */
internal fun applyEpisodeChipFeedback(chip: Chip) {
    chip.rippleColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
    chip.foreground = themedPressedForeground(chip)
    chip.stateListAnimator = null
    chip.tag = chip.context.getString(R.string.tv_no_focus_tag)
}

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

/** StremioC-only subtitle ordering and embedded selection; native player UI is preserved. */
@OptIn(UnstableApi::class)
internal class StremioSubtitleController {
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var viewTree: ViewTreeObserver? = null
    private var removeObserver: (() -> Unit)? = null
    private var lastInstance = -1
    private var lastPlayer: Media3Player? = null
    private var lastLinkUrl: String? = null
    private var embeddedApplied = false
    private var manualOverride = false
    private var selectionDialog: Dialog? = null
    private var selectionBeforeDialog: SubtitleData? = null
    private var settings: SharedPreferences? = null
    private var preferredLanguage = "en"
    private var sortedDialog: Dialog? = null
    private var pendingDialog: Dialog? = null
    private var isStremio = false
    private val dialogField = runCatching {
        FullScreenPlayer::class.java.getDeclaredField("selectSourceDialog").apply {
            isAccessible = true
        }
    }.getOrNull()
    private val selectedField = runCatching {
        GeneratorPlayer::class.java.getDeclaredField("currentSelectedSubtitles")
            .apply { isAccessible = true }
    }.getOrNull()
    private val helperField = runCatching {
        CS3IPlayer::class.java.getDeclaredField("subtitleHelper")
            .apply { isAccessible = true }
    }.getOrNull()
    private val activeField = runCatching {
        PlayerSubtitleHelper::class.java.getDeclaredField("activeSubtitles")
            .apply { isAccessible = true }
    }.getOrNull()
    private val linkField = runCatching {
        CS3IPlayer::class.java.getDeclaredField("currentLink")
            .apply { isAccessible = true }
    }.getOrNull()
    private val subtitleListener = java.lang.reflect.Proxy.newProxyInstance(
        Media3Player.Listener::class.java.classLoader,
        arrayOf(Media3Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onTracksChanged" -> {
                (args?.getOrNull(0) as? Tracks)?.let { selectEmbedded(it) }
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "StremioSubtitleListener"
            else -> null
        }
    } as Media3Player.Listener
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SUBTITLE_AUTO_SELECT_KEY) {
            preferredLanguage = readPreferredLanguage()
            if (isStremio) manualOverride = true
        }
    }
    private fun readPreferredLanguage(): String =
        runCatching { settings?.getString(SUBTITLE_AUTO_SELECT_KEY, null) }
            .getOrNull()?.trim()?.removeSurrounding("\"")
            ?.takeIf { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) }
            ?: "en"


    private fun currentLinkUrl(f: GeneratorPlayer): String? =
        runCatching { (linkField?.get(f.player) as? ExtractorLink)?.url }.getOrNull()

    private fun selectedByHost(f: GeneratorPlayer): SubtitleData? =
        runCatching { selectedField?.get(f) as? SubtitleData }.getOrNull()

    private fun trackDialog(f: GeneratorPlayer, dialog: Dialog?) {
        val shown = dialog?.takeIf { it.isShowing }
        if (shown != null) {
            if (shown !== selectionDialog) {
                selectionDialog = shown
                selectionBeforeDialog = selectedByHost(f)
            } else if (selectedByHost(f) != selectionBeforeDialog) {
                manualOverride = true
            }
        } else if (selectionDialog != null) {
            if (selectedByHost(f) != selectionBeforeDialog) manualOverride = true
            selectionDialog = null
            selectionBeforeDialog = null
        }
    }

    /** Mark only tracks that Media3 has actually exposed as active to the native picker. */
    private fun markActive(f: GeneratorPlayer, embedded: SubtitleData): Boolean =
        runCatching {
            val helper = helperField?.get(f.player) as? PlayerSubtitleHelper
                ?: return@runCatching false
            @Suppress("UNCHECKED_CAST")
            val existing = activeField?.get(helper) as? Set<SubtitleData>
                ?: return@runCatching false
            if (embedded !in existing) helper.setActiveSubtitles(existing + embedded)
            true
        }.getOrDefault(false)

    private fun checkStremio(): Boolean {
        val response = model?.state?.generatorState?.response ?: return false
        return response.apiName.equals("StremioC", true) ||
            APIHolder.allProviders.any { it is StremioC && it.name.equals(response.apiName, true) }
    }

    private val onDraw = ViewTreeObserver.OnPreDrawListener {
        val f = fragment
        val state = model?.state
        if (f != null && state != null) {
            if (lastInstance != state.instance) {
                lastInstance = state.instance
                lastPlayer?.removeListener(subtitleListener)
                lastPlayer = null
                lastLinkUrl = null
                embeddedApplied = false
                manualOverride = false
                selectionDialog = null
                sortedDialog = null
                isStremio = checkStremio()
            }
            if (isStremio) {
                val current = f.playerView?.player
                if (current != null && current !== lastPlayer) {
                    lastPlayer?.removeListener(subtitleListener)
                    val sourceUrl = currentLinkUrl(f)
                    if (lastPlayer != null && (sourceUrl == null || sourceUrl != lastLinkUrl)) {
                        // New source: old embedded IDs and the prior track override are invalid.
                        model?.modifyState {
                            set(subtitles.filterNot { it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO })
                        }
                        manualOverride = false
                    }
                    lastPlayer = current
                    lastLinkUrl = sourceUrl
                    embeddedApplied = false
                    current.addListener(subtitleListener)
                }
                if (current != null) {
                    val popup = runCatching { dialogField?.get(f) as? Dialog }.getOrNull()
                    trackDialog(f, popup)
                    if (!embeddedApplied) selectEmbedded()
                    if (popup != null && popup.isShowing &&
                        popup !== sortedDialog && popup !== pendingDialog) {
                        pendingDialog = popup
                        f.view?.post {
                            pendingDialog = null
                            if (popup.isShowing && fragment === f && sortPopup(popup)) {
                                sortedDialog = popup
                            }
                        }
                    }
                }
            }
        }
        true
    }

    fun watch(candidate: Fragment) {
        if (candidate !is GeneratorPlayer || candidate === fragment) return
        detach()
        fragment = candidate
        model = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
        val observer = Observer<VideoLive<Set<SubtitleData>>> { event ->
            if (event != null && event.instance == model?.state?.instance && checkStremio())
                selectEmbedded()
        }
        model?.currentSubtitles?.observe(candidate.viewLifecycleOwner, observer)
        removeObserver = { model?.currentSubtitles?.removeObserver(observer) }
        settings = candidate.context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferredLanguage = readPreferredLanguage()
        settings?.registerOnSharedPreferenceChangeListener(settingsListener)
        candidate.view?.viewTreeObserver?.takeIf { it.isAlive }?.let {
            it.addOnPreDrawListener(onDraw)
            viewTree = it
        }
    }

    private fun selectEmbedded(tracks: Tracks? = null) {
        val f = fragment ?: return
        if (model?.state?.instance != lastInstance || !isStremio) return
        val current = lastPlayer ?: return
        if (current !== f.playerView?.player) return
        val available = tracks ?: current.currentTracks
        val subtitles = model?.state?.subtitles ?: return
        val embeddedTracks = available.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        // Expose only tracks belonging to this actual video to the native picker.
        for (subtitle in subtitles) {
            if (subtitle.origin != SubtitleOrigin.EMBEDDED_IN_VIDEO) continue
            val found = embeddedTracks.any { group ->
                (0 until group.length).any { index ->
                    group.getTrackFormat(index).id?.replace(Regex("^\\d+:"), "") == subtitle.url
                }
            }
            if (found) markActive(f, subtitle)
        }

        val popup = runCatching { dialogField?.get(f) as? Dialog }.getOrNull()
        trackDialog(f, popup)
        if (manualOverride || popup?.isShowing == true) return
        val language = preferredLanguage
        if (language.isBlank()) return
        val target = subtitles.firstOrNull {
            it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO && it.matchesLanguageCode(language)
        } ?: return
        val match = embeddedTracks.firstNotNullOfOrNull { group ->
            (0 until group.length).firstOrNull { index ->
                group.isTrackSupported(index) &&
                    group.getTrackFormat(index).id?.replace(Regex("^\\d+:"), "") == target.url
            }?.let { index -> group to index }
        } ?: return
        if (!markActive(f, target)) return
        val (group, index) = match
        if (group.isTrackSelected(index)) {
            embeddedApplied = true
            return
        }
        // Direct Media3 track override, never GeneratorPlayer.setSubtitles/loadLink.
        runCatching {
            current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                .build()
            embeddedApplied = true
        }
    }

    private fun sortPopup(dialog: Dialog): Boolean {
        val state = model?.state ?: return false
        val list = dialog.findViewById<ListView>(R.id.sort_subtitles) ?: return false
        val wrapper = list.adapter
        val adapter = (if (wrapper is HeaderViewListAdapter) wrapper.wrappedAdapter else wrapper)
            as? ArrayAdapter<*> ?: return false
        // Match the exact grouping and item positions used by the CloudStream dialog.
        val grouped = sortSubs(state.subtitles).groupBy { it.originalName }.values.toList()
        if (adapter.count != grouped.size + 1) return false
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
        // Change the displayed label only. Keep originalName and URL unchanged so
        // CloudStream's grouping, selection and embedded track matching still work.
        val names = grouped.indices.map { index ->
            val group = grouped[index]
            if (group.isNotEmpty() && group.all { it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO }) {
                val embeddedName = fragment?.context?.getString(R.string.subtitles_from_embedded)
                    ?: return false
                android.text.SpannedString("${group.first().originalName} ($embeddedName)")
            } else texts[index + 1]
        }
        if (order.indices.all { order[it] == it } &&
            names.indices.all { names[it] == texts[it + 1] }) return true
        val originalClick = list.onItemClickListener ?: return false
        val previouslySelected = list.checkedItemPosition
        @Suppress("UNCHECKED_CAST")
        val editable = adapter as ArrayAdapter<Spanned>
        editable.clear()
        editable.addAll(listOf(texts.first()) + order.map { names[it] })
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

    fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        viewTree?.let { if (it.isAlive) it.removeOnPreDrawListener(onDraw) }
        viewTree = null
        lastPlayer?.removeListener(subtitleListener)
        settings?.unregisterOnSharedPreferenceChangeListener(settingsListener)
        settings = null
        removeObserver?.invoke()
        removeObserver = null
        fragment = null
        model = null
        lastPlayer = null
        lastLinkUrl = null
        lastInstance = -1
        embeddedApplied = false
        manualOverride = false
        selectionDialog = null
        selectionBeforeDialog = null
        sortedDialog = null
        pendingDialog = null
        isStremio = false
    }
}

/** Auto-select StremioC audio without changing CloudStream's saved language. */
@OptIn(UnstableApi::class)
internal class StremioAudioSelector {
    private var fragment: GeneratorPlayer? = null
    private var removeObserver: (() -> Unit)? = null
    private var player: Media3Player? = null
    private var previousPlayer: Media3Player? = null
    private var pendingDraw: ViewTreeObserver? = null
    private var lastInstance: Int? = null
    private var anime = false
    private var selected = false

    // Avoid Kotlin default-interface bridges: the host Media3 listener ABI can differ.
    private val audioListener = java.lang.reflect.Proxy.newProxyInstance(
        Media3Player.Listener::class.java.classLoader,
        arrayOf(Media3Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onTracksChanged" -> {
                (args?.getOrNull(0) as? Tracks)?.let(::select)
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "StremioAudioSelectorListener"
            else -> null
        }
    } as Media3Player.Listener
    private val onDraw = ViewTreeObserver.OnPreDrawListener {
        val current = fragment?.playerView?.player
        if (current != null && current !== previousPlayer) {
            stopWaiting()
            player = current
            current.addListener(audioListener)
            select(current.currentTracks)
        }
        true
    }

    fun watch(candidate: Fragment) {
        if (candidate !is GeneratorPlayer || fragment === candidate) return
        detach()
        fragment = candidate
        val model = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
        val observer = Observer<VideoLive<Resource<Unit>>> { state ->
            val instance = state?.instance ?: return@Observer
            if (instance == lastInstance) return@Observer
            val response = model.state.generatorState?.response ?: return@Observer
            lastInstance = instance
            val active = response.apiName == "StremioC" || APIHolder.allProviders.any {
                it is StremioC && it.name.equals(response.apiName, ignoreCase = true)
            }
            previousPlayer = player
            player?.removeListener(audioListener)
            player = null
            stopWaiting()
            if (!active) return@Observer
            anime = StremioC.animeAudioByUrl[response.url]
                ?: (response.type == TvType.Anime || response.type == TvType.AnimeMovie)
            selected = false
            candidate.view?.viewTreeObserver?.takeIf { it.isAlive }?.let {
                it.addOnPreDrawListener(onDraw)
                pendingDraw = it
            }
        }
        model.loadingLinks.observe(candidate.viewLifecycleOwner, observer)
        removeObserver = { model.loadingLinks.removeObserver(observer) }
    }

    private fun select(tracks: Tracks) {
        if (selected) return
        val current = player ?: return
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) return
        val appLanguage = fragment?.context?.resources?.configuration?.let {
            ConfigurationCompat.getLocales(it).get(0)?.language
        }
        val languages = listOfNotNull(appLanguage, if (anime) "ja" else "en",
            "en".takeIf { anime }).map { it.lowercase(Locale.ROOT) }.distinct()
        val match = languages.firstNotNullOfOrNull { language ->
            groups.firstNotNullOfOrNull { group ->
                (0 until group.length).firstOrNull { i ->
                    val raw = group.getTrackFormat(i).language?.trim().orEmpty()
                    val code = SubtitleHelper.fromCodeToLangTagIETF(raw)
                        ?: SubtitleHelper.fromCodeToLangTagIETF(
                            raw.replace('_', '-').substringBefore('-')
                        )
                    group.isTrackSupported(i) &&
                        code?.substringBefore('-')?.equals(language, ignoreCase = true) == true
                }?.let { group to it }
            }
        }
        selected = true
        current.removeListener(audioListener)
        if (match != null) {
            val (group, index) = match
            current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
        }
        // No matching language: keep Media3's selected default track.
    }

    private fun stopWaiting() {
        pendingDraw?.let { if (it.isAlive) it.removeOnPreDrawListener(onDraw) }
        pendingDraw = null
    }

    fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        stopWaiting()
        removeObserver?.invoke()
        removeObserver = null
        player?.removeListener(audioListener)
        player = null
        previousPlayer = null
        lastInstance = null
        fragment = null
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
    private val audioSelector = StremioAudioSelector()
    private val subtitleController = StremioSubtitleController()
    private val pipSubtitles = PipSubtitleScaler { apiName ->
        apiName == "StremioC" || APIHolder.allProviders.any {
            it is StremioC && it.name.equals(apiName, ignoreCase = true)
        }
    }
    private val mdbUpdate: (String) -> Unit = { cacheKey ->
        val matched = watchers.values.filter { it.matchesMdbKey(cacheKey) }
        val applied = matched.count { it.refreshLateMdb() }
    }
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
        val navHost = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            as? androidx.navigation.fragment.NavHostFragment ?: return
        if (navHost.navController.currentDestination?.id !in setOf(
                R.id.navigation_results_phone, R.id.navigation_results_tv
            )) return
        val content = find("nav_host_fragment") ?: return
        val bounds = content.parent as? android.view.ViewGroup ?: return
        // Observe only the NavHost; never block drawing of the whole Activity.
        val observer = content.viewTreeObserver
        if (!observer.isAlive) return
        detailRevealEnd?.invoke()
        val started = android.os.SystemClock.uptimeMillis()
        var stable = 0
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        lateinit var timeout: Runnable
        lateinit var release: () -> Unit
        release = {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            main.removeCallbacks(timeout)
            if (detailRevealEnd === release) detailRevealEnd = null
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
            } else {
                // Keep the previous search frame until the result fills the NavHost.
                content.postInvalidateOnAnimation()
                false
            }
        }
        timeout = Runnable {
            if (detailRevealEnd === release) {
                release()
                content.postInvalidateOnAnimation()
            }
        }
        detailRevealEnd = release
        observer.addOnPreDrawListener(listener)
        main.postDelayed(timeout, 650L)
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
                pipSubtitles.watch(f)
                audioSelector.watch(f)
                subtitleController.watch(f)
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
                    
            }
        }

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            pipSubtitles.watch(f)
            audioSelector.watch(f)
            subtitleController.watch(f)
            watchers[f]?.let { watcher ->
                watcher.render() // CloudStream may reuse an old LoadResponse without calling load().
                watcher.auditReentry()
            }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment")
                homeEntries.detach()
            releaseHomeMenu(f)
            pipSubtitles.detach(f)
            audioSelector.detach(f)
            subtitleController.detach(f)
            watchers.remove(f)?.detach()
        }
    }

    fun start() {
        StremioCMdbUiBridge.listen(mdbUpdate)
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
        audioSelector.detach()
        subtitleController.detach()
        pipSubtitles.detach()
        StremioCMdbUiBridge.listen(null)
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
        pipSubtitles.detach()
        audioSelector.detach()
        subtitleController.detach()
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
                    pipSubtitles.watch(f)
                    audioSelector.watch(f)
                    subtitleController.watch(f)
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
            it.auditReentry()
        }
    }

    /** Plugin-owned independent tracker states. Native detail, episode selector and panel stay intact. */
    private class SeasonSyncProbe(
        private val fragment: Fragment, private val root: View, private val detailUrl: String?,
        private val pluginResources: Resources
    ) {
        private companion object {
            const val TAG = "STCSeasonSync"
            val IMDb = Regex("tt\\d+")
            val allowed = setOf("mal", "anilist", "kitsu", "simkl")
            val titles = mapOf("mal" to "MAL", "anilist" to "AniList",
                "kitsu" to "Kitsu", "simkl" to "Simkl")
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
            var simklAnimeVerified: Boolean = false,
            var simklAnime: AnimeSnapshot? = null,
            var seasonMapping: SeasonTrackerIds? = null
        )

        /** Exact Simkl episode keys, never the aggregate watchedEpisodesCount. */
        private data class SimklSeasonSnapshot(
            val ids: JSONObject,
            val seasonNumber: Int,
            val available: List<Int>,
            val watched: Set<Int>,
            val otherSeasons: Map<Int, Set<Int>>
        )

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
            id: String, requested: Int, gen: Long
        ): SimklSeasonSnapshot? {
            val headers = SimklApi.getHeaders(repo.authToken() ?: return null) +
                mapOf("content-type" to "application/json")
            val ids = simklIds(id)
            if (ids == null) {
                Log.w(TAG, "event=SIMKL_P3_BLOCK session=$session gen=$gen " +
                    "season=$requested reason=missing-id")
                return null
            }
            return try {
                val payload = JSONArray().put(ids)
                val response = app.post("https://api.simkl.com/sync/watched?extended=episodes",
                    headers=headers, requestBody=payload.toString().toRequestBody())
                if (response.code !in 200..299) {
                    Log.w(TAG, "event=SIMKL_P3_HTTP session=$session gen=$gen " +
                        "season=$requested response=${response.code}")
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
                val result = if (!malformed && chosen != null && chosen.first.isNotEmpty())
                    SimklSeasonSnapshot(ids, requested, chosen.first, chosen.second,
                        parsed.filterKeys { it != requested }.mapValues { it.value.second })
                else null
                Log.i(TAG,"event=SIMKL_P3_READ session=$session gen=$gen season=$requested " +
                    "http=${response.code} record=${record != null} seasons=${parsed.keys} " +
                    "total=${result?.available?.size} watched=${result?.watched?.size} " +
                    "malformed=$malformed readOnly=${result == null}")
                result
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                  Log.w(TAG,"event=SIMKL_P3_READ_FAILED session=$session gen=$gen season=$requested " +
                      "cause=${e.javaClass.simpleName}")
                  null
              }
        }

        private suspend fun simklSeasonSave(
            tracker: Tracker, draft: Draft, baseline: Draft, gen: Long, sn: Int
        ): Boolean {
            val initial = tracker.simklSeason ?: return false
            if (draft.score != baseline.score ||
                (draft.status != baseline.status &&
                    !(baseline.status == SyncWatchType.NONE &&
                      draft.status == SyncWatchType.WATCHING && draft.watched > 0))) {
                Log.w(TAG,"event=SIMKL_P3_BLOCK session=$session gen=$gen season=$sn " +
                    "reason=title-wide-status-or-rating")
                return false
            }
            if (draft.watched !in 0..initial.available.size) return false
            val countDiff = draft.watched - initial.watched.size
            if (countDiff == 0) return true
            val delta = if (countDiff > 0)
                initial.available.filterNot { it in initial.watched }.take(countDiff)
            else initial.available.filter { it in initial.watched }.takeLast(-countDiff)
            if (delta.size != kotlin.math.abs(countDiff)) return false
            val expected = if (countDiff > 0) initial.watched + delta
                else initial.watched - delta.toSet()
            val scope = JSONObject().put("ids", initial.ids)
                .put("seasons", JSONArray().put(JSONObject()
                    .put("number",sn).put("episodes", JSONArray().also { arr ->
                        delta.forEach { arr.put(JSONObject().put("number",it)) }
                    })))
            val body = JSONObject().put("shows",JSONArray().put(scope)).toString()
            val api = if (countDiff > 0) "history" else "history/remove"
            val headers = SimklApi.getHeaders(tracker.repo.authToken() ?: return false) +
                mapOf("content-type" to "application/json")
            Log.i(TAG,"event=SIMKL_P3_WRITE session=$session gen=$gen season=$sn " +
                "operation=$api episodes=$delta before=${initial.watched.size} " +
                "desired=${draft.watched} untouchedSeasons=${initial.otherSeasons.keys}")
            val response = try { app.post("https://api.simkl.com/sync/$api",
                headers=headers, requestBody=body.toRequestBody()) }
                catch (e: Exception) {
                    Log.w(TAG,"event=SIMKL_P3_WRITE_FAILED session=$session gen=$gen " +
                        "season=$sn cause=${e.javaClass.simpleName}")
                    return false
                }
            val missing = runCatching { JSONObject(response.text)
                .optJSONObject("not_found")?.optJSONArray("shows")?.length() ?: 0
                }.getOrDefault(-1)
            if (response.code !in 200..299 || missing != 0) {
                Log.w(TAG,"event=SIMKL_P3_WRITE_RESPONSE session=$session gen=$gen " +
                    "season=$sn http=${response.code} notFound=$missing")
                return false
            }
            for (attempt in 1..3) {
                if (attempt > 1) kotlinx.coroutines.delay(if (attempt == 2) 350L else 900L)
                val current = simklSeasonRead(tracker.repo, tracker.id, sn, gen)
                val sameOther = current != null &&
                    initial.otherSeasons.all { (key, seen) ->
                        current.otherSeasons[key] == seen
                    }
                val exact = current?.watched == expected
                Log.i(TAG,"event=SIMKL_P3_VERIFY session=$session gen=$gen season=$sn " +
                    "attempt=$attempt exact=$exact otherSeasons=$sameOther " +
                    "expected=$expected observed=${current?.watched}")
                if (exact && sameOther) {
                    tracker.simklSeason = current
                    return true
                }
            }
            return false
        }

        /** Season-exact TVDB episode map; never inferred from a title's total count. */
        private data class AnimeSlot(val simkl: Int, val native: Int, val tvdbSeason: Int, val tvdbEpisode: Int) {
            val key get() = "$simkl:$native"
        }
        private data class AnimeSnapshot(
            val tvdbId: Int?, val tvdbSeason: Int?, val mapped: List<AnimeSlot>,
            val watched: Set<String>, val outside: Set<String>,
            val mode: String, val sourceIds: Set<Int>
        )
        private var animeLibraryCache: Pair<Long, JSONObject>? = null
        private val animeEpisodeCache = mutableMapOf<Int, List<AnimeSlot>>()

        private fun jsonInt(obj: JSONObject?, key: String): Int? =
            obj?.opt(key)?.toString()?.toIntOrNull()?.takeIf { it > 0 }

        private suspend fun animeLibrary(repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            gen: Long, force: Boolean = false): JSONObject? {
            val now = SystemClock.elapsedRealtime()
            if (!force) animeLibraryCache?.let {
                if (now - it.first in 0L..20_000L) {
                    Log.i(TAG,"event=SIMKL_P4_LIBRARY_CACHE session=$session gen=$gen age=${now-it.first}")
                    return it.second
                }
            }
            val header = SimklApi.getHeaders(repo.authToken() ?: return null)
            val resp = app.get("https://api.simkl.com/sync/all-items/anime",
                params=mapOf("extended" to "full_anime_seasons"),headers=header,cacheTime=0)
            if (resp.code !in 200..299) {
                Log.w(TAG,"event=SIMKL_P4_LIBRARY_HTTP session=$session gen=$gen http=${resp.code}")
                return null
            }
            val obj = runCatching { JSONObject(resp.text) }.getOrNull()
            if (obj == null || !obj.has("anime")) {
                Log.w(TAG,"event=SIMKL_P4_LIBRARY_INVALID session=$session gen=$gen")
                return null
            }
            animeLibraryCache=now to obj
            Log.i(TAG,"event=SIMKL_P4_LIBRARY session=$session gen=$gen items=" +
                "${obj.optJSONArray("anime")?.length() ?: 0} http=${resp.code}")
            return obj
        }

        private suspend fun simklAnimeRead(
            repo: com.lagradost.cloudstream3.syncproviders.SyncRepo,
            mapped: SeasonTrackerIds, targetSeason: Int, sourceCount: Int?, gen: Long,
            force: Boolean = false
        ): AnimeSnapshot? {
            val headers = SimklApi.getHeaders(repo.authToken() ?: return null)
            val candidates = linkedSetOf<Int>()
            val tvdbSearchMatches = linkedSetOf<Int>()
            val malSearchMatches = linkedSetOf<Int>()
            mapped.simkl?.let { candidates += it }
            suspend fun search(field: String, value: Int) {
                try {
                    val resp=app.get("https://api.simkl.com/search/id",
                        params=mapOf(field to value.toString()),headers=headers,cacheTime=0)
                    val array=if (resp.code in 200..299)
                        runCatching { JSONArray(resp.text) }.getOrNull() else null
                    val found=mutableListOf<Int>()
                    for(i in 0 until (array?.length() ?: 0)){
                        val item=array?.optJSONObject(i)
                        val ids=item?.optJSONObject("ids")
                            ?: item?.optJSONObject("anime")?.optJSONObject("ids")
                        val id=jsonInt(ids,"simkl")
                        if (id != null) found+=id
                    }
                    Log.i(TAG,"event=SIMKL_P4_SEARCH session=$session gen=$gen season=$targetSeason " +
                        "field=$field http=${resp.code} rows=${array?.length()} ids=$found")
                    candidates.addAll(found.take(24))
                    if (field=="tvdb")tvdbSearchMatches.addAll(found.take(24))
                    if (field=="mal")malSearchMatches.addAll(found.take(24))
                }catch(e: CancellationException){throw e}
                 catch(e: Exception){
                    Log.w(TAG,"event=SIMKL_P4_SEARCH_FAIL session=$session gen=$gen season=$targetSeason " +
                        "field=$field cause=${e.javaClass.simpleName}")
                 }
            }
            // Simkl's own native ID resolver, with TVDB parent search to find every cour.
            mapped.mal?.let { search("mal",it) }
            mapped.tvdb?.let { search("tvdb",it) }
            if(candidates.isEmpty())return null
            val tvdbSeason=mapped.tvdbSeason
            val tvdbId=mapped.tvdb
            val slots=mutableListOf<AnimeSlot>()
            val validated=linkedSetOf<Int>()
            for(id in candidates.take(24)){
                try{
                    val detail=app.get("https://api.simkl.com/anime/$id",
                        headers=headers,cacheTime=600)
                    if(detail.code !in 200..299)continue
                    val info=runCatching { JSONObject(detail.text) }.getOrNull() ?: continue
                    val ids=info.optJSONObject("ids") ?: continue
                    if(jsonInt(ids,"simkl") != id)continue
                    // Only the mapped direct ID may be accepted by native MAL identity.
                    val malMatched=mapped.mal != null &&
                        jsonInt(ids,"mal")==mapped.mal
                    val direct=mapped.simkl==id
                    val row=animeEpisodeCache[id] ?: run {
                        val list=app.get("https://api.simkl.com/anime/episodes/$id",
                            headers=headers,cacheTime=600)
                        if(list.code !in 200..299)throw IllegalStateException("episodes-http-${list.code}")
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
                        if(!correctParent)continue
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
                    Log.i(TAG,"event=SIMKL_P4_CANDIDATE session=$session gen=$gen " +
                        "season=$targetSeason id=$id matchedMal=$malMatched direct=$direct " +
                        "tvdb=$tvdbId accepted=${id in validated} episodes=${row.size}")
                }catch(e: CancellationException){throw e}
                 catch(e: Exception){
                    Log.w(TAG,"event=SIMKL_P4_CANDIDATE_FAIL session=$session gen=$gen " +
                        "season=$targetSeason id=$id cause=${e.javaClass.simpleName}")
                 }
            }
            val sorted=slots.sortedWith(compareBy<AnimeSlot> { it.tvdbEpisode }.thenBy { it.simkl })
            val keys=sorted.map { it.tvdbEpisode }
            if(sorted.isEmpty() || keys.distinct().size != keys.size ||
                keys != (1..keys.size).toList() ||
                (sourceCount != null && sourceCount > sorted.size)){
                Log.w(TAG,"event=SIMKL_P4_COVERAGE session=$session gen=$gen season=$targetSeason " +
                    "candidates=$candidates mapped=$validated slots=${sorted.size} " +
                    "source=$sourceCount duplicates=${keys.size!=keys.distinct().size} valid=false")
                return null
            }
            val library=animeLibrary(repo,gen,force) ?: return null
            val watchedAll=linkedSetOf<String>()
            val claimedWatched=mutableMapOf<Int,Int>()
            val anime=library.optJSONArray("anime")
            for(i in 0 until (anime?.length() ?: 0)){
                val entry=anime?.optJSONObject(i) ?: continue
                val id=jsonInt(entry.optJSONObject("anime")?.optJSONObject("ids"),"simkl")
                    ?:continue
                val claimed=entry.optInt("watched_episodes_count",-1)
                if(claimed>=0)claimedWatched[id]=claimed
                val seasons=entry?.optJSONArray("seasons")
                for(j in 0 until(seasons?.length() ?: 0)){
                    val episodes=seasons?.optJSONObject(j)?.optJSONArray("episodes")
                    for(k in 0 until(episodes?.length() ?: 0)){
                        val ep=episodes?.optJSONObject(k)
                        val native=jsonInt(ep,"number") ?:continue
                        if(ep?.opt("watched") != false)watchedAll+="$id:$native"
                    }
                }
            }
            val badCounts=validated.filter { id ->
                val counted=watchedAll.count { it.startsWith("$id:") }
                val expected=claimedWatched[id]
                expected!=null && counted<expected
            }
            if(badCounts.isNotEmpty()){
                Log.w(TAG,"event=SIMKL_P4_LIBRARY_INCOMPLETE session=$session gen=$gen " +
                    "season=$targetSeason ids=$badCounts")
                return null
            }
            val targetKeys=sorted.mapTo(linkedSetOf()) { it.key }
            val result=AnimeSnapshot(tvdbId,tvdbSeason,sorted,
                watchedAll.intersect(targetKeys),watchedAll-targetKeys,
                if(tvdbId!=null && tvdbSeason!=null)"tvdb" else "native",validated)
            Log.i(TAG,"event=SIMKL_P4_READY session=$session gen=$gen season=$targetSeason " +
                "mode=${result.mode} simklIds=${result.sourceIds} total=${sorted.size} " +
                "watched=${result.watched.size} other=${result.outside.size}")
            return result
        }

        private suspend fun simklAnimeSave(tracker: Tracker, draft: Draft,
            initial: Draft, gen: Long, seasonNumber: Int): Boolean {
            val before=tracker.simklAnime ?:return false
            if(draft.score!=initial.score ||
               (draft.status!=initial.status && !(
                   initial.status==SyncWatchType.NONE &&
                   draft.status==SyncWatchType.WATCHING && draft.watched>0)))return false
            val diff=draft.watched-before.watched.size
            if(diff==0)return true
            if(draft.watched !in 0..before.mapped.size)return false
            val changes=if(diff>0)before.mapped.filterNot { it.key in before.watched }.take(diff)
                else before.mapped.filter { it.key in before.watched }.takeLast(-diff)
            if(changes.size != kotlin.math.abs(diff))return false
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
                val id=before.sourceIds.singleOrNull()?:return false
                JSONObject().put("anime",JSONArray().put(JSONObject()
                    .put("ids",JSONObject().put("simkl",id))
                    .put("episodes",JSONArray().also { a ->
                        changes.forEach { a.put(JSONObject().put("number",it.native)) }
                    })))
            }
            val endpoint=if(diff>0)"history" else "history/remove"
            val headers=SimklApi.getHeaders(tracker.repo.authToken()?:return false)+
                mapOf("content-type" to "application/json")
            Log.i(TAG,"event=SIMKL_P4_WRITE session=$session gen=$gen season=$seasonNumber " +
                "mode=${before.mode} operation=$endpoint entries=${changes.map{it.key}} " +
                "otherProtected=${before.outside.size}")
            val response=app.post("https://api.simkl.com/sync/$endpoint",
                headers=headers,requestBody=body.toString().toRequestBody())
            val failed=runCatching{
                val obj=JSONObject(response.text)
                val notFound=obj.optJSONObject("not_found")
                (notFound?.optJSONArray("shows")?.length()?:0)+
                    (notFound?.optJSONArray("anime")?.length()?:0)
            }.getOrNull()
            if(response.code !in 200..299 || failed==null || failed>0){
                Log.w(TAG,"event=SIMKL_P4_WRITE_FAIL session=$session gen=$gen " +
                    "http=${response.code} notFound=$failed")
                return false
            }
            animeLibraryCache=null
            val expected=if(diff>0)before.watched+changes.map{it.key}
                else before.watched-changes.map{it.key}.toSet()
            for(attempt in 1..3){
                if(attempt>1)kotlinx.coroutines.delay(if(attempt==2)350L else 900L)
                val mapped=tracker.seasonMapping ?: return false
                val after=simklAnimeRead(tracker.repo,mapped,seasonNumber,
                    StremioC.seasonEpisodeCountsByUrl[pageUrl.orEmpty()]?.get(seasonNumber),
                    gen,force=true)
                val same=after?.watched==expected
                val preserved=after?.outside==before.outside
                Log.i(TAG,"event=SIMKL_P4_VERIFY session=$session gen=$gen season=$seasonNumber " +
                    "attempt=$attempt exact=$same others=$preserved " +
                    "readCount=${after?.watched?.size}")
                if(same && preserved){tracker.simklAnime=after;return true}
            }
            return false
        }

        private val session = Integer.toHexString(System.identityHashCode(fragment))
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val observers = mutableListOf<() -> Unit>()
        private var active = false
        private var generation = 0L
        private var pageUrl: String? = null
        private var rawSyncs: Map<String,String> = emptyMap()
        private var seasonNumbers: List<Int> = emptyList()
        private var selectionIndex: Int? = null
        private var season: Int? = null
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

        private fun log(event: String, info: String = "") {
            Log.i(TAG, "event=$event session=$session gen=$generation season=${season ?: "none"} " +
                "chosen=${selected ?: "none"} $info")
        }
        private fun valid(g: Long, n: Int?): Boolean =
            active && generation == g && season == n

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
            log("ATTACH", "screen=${fragment.javaClass.simpleName} syncModel=${syncModel != null}")
            observe(vm.page, owner) { value ->
                when(value) {
                    is Resource.Success -> {
                        val data = value.value
                        if (pageUrl != data.url) {
                            generation++
                            pageUrl = data.url
                            rawSyncs = data.syncData.toMap()
                            seasonNumbers = emptyList()
                            selectionIndex = null
                            season = null
                            reset("page-change")
                            log("PAGE", "name=${data.title.take(80)} imdb=${IMDb.find(data.url)?.value} " +
                                "services=${rawSyncs.keys}")
                        } else {
                            rawSyncs = data.syncData.toMap()
                        }
                        resolve("page")
                    }
                    is Resource.Failure -> log("PAGE_FAILURE")
                    else -> {}
                }
            }
            observe(vm.seasonSelections, owner) { items ->
                seasonNumbers = items.map { it.second }
                log("SEASON_LIST", "numbers=$seasonNumbers")
                resolve("list")
            }
            observe(vm.selectedSeasonIndex, owner) { index ->
                selectionIndex = index
                log("SEASON_INDEX", "index=$index resolved=${seasonNumbers.getOrNull(index)}")
                resolve("index")
            }
            syncModel?.let { model ->
                observe(model.userData, owner) { result ->
                    if (combo != null) {
                        log("CORE_USER_IGNORED", "resource=${result?.javaClass?.simpleName} " +
                            "coreIds=${model.getSyncs().keys} panelOwner=StremioC")
                        root.post { if (active) render("core-user") }
                    }
                }
                observe(model.metadata, owner) { result ->
                    if (combo != null) {
                        log("CORE_META_IGNORED", "resource=${result?.javaClass?.simpleName}")
                        root.post { if (active) render("core-metadata") }
                    }
                }
                observe(model.synced, owner) {
                    if (combo != null) root.post { if (active) render("core-synced") }
                }
            }
        }

        private fun resolve(origin: String) {
            val chosen = selectionIndex?.let { seasonNumbers.getOrNull(it) }
            if (pageUrl == null || chosen == null || chosen <= 0) return
            if (chosen == season) return
            season = chosen
            generation++
            log("SEASON_CHANGED", "origin=$origin index=$selectionIndex seasonList=$seasonNumbers " +
                "imdb=${IMDb.find(pageUrl.orEmpty())?.value}")
            begin()
        }

        private fun reset(reason: String) {
            fetchJob?.cancel()
            fetchJob = null
            statuses.clear()
            selected = null
            startedFor = null
            log("TARGET_RESET", "reason=$reason")
        }

        private fun begin() {
            val url = pageUrl ?: return
            val sn = season ?: return
            val isAnime = StremioC.animeAudioByUrl[url]
                ?: detailUrl?.let { StremioC.animeAudioByUrl[it] }
            val multi = seasonNumbers.size > 1
            val generalSimkl = isAnime != true && multi && rawSyncs.containsKey("simkl")
            if ((isAnime != true && !generalSimkl) || syncModel == null) {
                reset("non-anime-or-tv")
                teardownUi()
                log("ELIGIBILITY", "enabled=false anime=$isAnime multi=$multi")
                return
            }
            reset("season-selection")
            val gen = generation
            ensureUi()
            log("ELIGIBILITY", "enabled=true multi=$multi raw=${rawSyncs.keys} " +
                "sourceEpisodes=${StremioC.seasonEpisodeCountsByUrl[url]?.get(sn)}")
            fetchJob = scope.launch {
                var mappedSeason: SeasonTrackerIds? = null
                val ids = if (!multi) rawSyncs.filterKeys { it in allowed }
                    else if (generalSimkl) rawSyncs.filterKeys { it == "simkl" } else {
                    val imdb = IMDb.find(url)?.value ?: IMDb.find(detailUrl.orEmpty())?.value
                    val provider = APIHolder.allProviders.filterIsInstance<StremioC>()
                        .firstOrNull { it.name == fragment.arguments?.getString("apiName") }
                        ?: APIHolder.allProviders.filterIsInstance<StremioC>().firstOrNull()
                    if (imdb == null || provider == null) {
                        log("MAPPING_BLOCK", "reason=missing-imdb-or-provider")
                        emptyMap()
                    } else {
                        log("MAPPING_START", "imdb=$imdb target=$sn")
                        val mapping = try { withContext(Dispatchers.IO) {
                            provider.seasonTrackerIds(imdb, sn)
                        } } catch (e: CancellationException) { throw e }
                          catch (e: Exception) {
                            log("MAPPING_ERROR", "cause=${e.javaClass.simpleName}")
                            null
                        }
                        mappedSeason = mapping
                        log("MAPPING_RESULT", "target=$sn MAL=${mapping?.mal} " +
                            "AniList=${mapping?.aniList} Kitsu=${mapping?.kitsu} " +
                            "Simkl=${mapping?.simkl} TVDB=${mapping?.tvdb} " +
                            "TVDBSeason=${mapping?.tvdbSeason}")
                        linkedMapOf<String,String>().apply {
                            mapping?.mal?.let { put("mal", it.toString()) }
                            mapping?.aniList?.let { put("anilist", it.toString()) }
                            mapping?.kitsu?.let { put("kitsu", it.toString()) }
                            // Prefer the authoritative season Simkl ID supplied by
                            // AIOStreams; Simkl's native search is the fallback.
                            if (mapping?.simkl != null) {
                                put("simkl", JSONObject().put("Simkl",mapping.simkl).toString())
                            } else mapping?.mal?.let { mal ->
                                put("simkl", JSONObject().put("Mal",mal).toString())
                            }
                        }
                    }
                }
                if (!valid(gen, sn)) { log("STALE", "phase=map"); return@launch }
                val matched = AccountManager.syncApis.filter { ids.containsKey(it.idPrefix) }
                    .filter { it.authUser() != null }
                log("TARGETS", "ids=${ids.keys} connected=${matched.map { it.idPrefix }} " +
                    "omitted=${ids.keys - matched.map { it.idPrefix }.toSet()}")
                if (matched.isEmpty()) {
                    log("TARGET_EMPTY", "reason=unmapped-or-no-account")
                    render("empty")
                    return@launch
                }
                statuses = linkedMapOf<String,Tracker>().apply {
                    matched.forEach { repo ->
                        put(repo.idPrefix, Tracker(repo.idPrefix, ids.getValue(repo.idPrefix), repo))
                    }
                }
                selected = preferred?.takeIf { it in statuses } ?: statuses.keys.firstOrNull()
                updateOptions()
                render("fetch-start")
                val simklSeasonReplies =
                    java.util.concurrent.ConcurrentHashMap<String, SimklSeasonSnapshot>()
                val simklAnimeReplies =
                    java.util.concurrent.ConcurrentHashMap<String, AnimeSnapshot>()
                val replies = matched.map { repo ->
                    async(Dispatchers.IO) {
                        val id = ids.getValue(repo.idPrefix)
                        val started = SystemClock.elapsedRealtime()
                        val state = repo.status(id)
                        // MAL's general load() parses unrelated statistics fields with
                        // unstable string/number types. Use the minimal num_episodes API
                        // below instead, avoiding a second failing full-document parse.
                        val meta = if (repo.idPrefix == "mal" || repo.idPrefix == "kitsu")
                            null else repo.load(id)
                        val remote = state.getOrNull()
                        val simklExact = if (generalSimkl && repo.idPrefix == "simkl")
                            simklSeasonRead(repo, id, sn, gen) else null
                        if (generalSimkl && repo.idPrefix == "simkl" && simklExact != null)
                            simklSeasonReplies["simkl"] = simklExact
                        val animeExact = if (multi && isAnime == true &&
                            repo.idPrefix == "simkl" && mappedSeason != null) {
                            simklAnimeRead(repo, mappedSeason!!, sn,
                                StremioC.seasonEpisodeCountsByUrl[url]?.get(sn), gen)
                        } else null
                        if (multi && isAnime == true && repo.idPrefix == "simkl" &&
                            animeExact != null) simklAnimeReplies["simkl"] = animeExact
                        if (multi && isAnime == true && repo.idPrefix == "simkl") {
                            Log.i(TAG, "event=SIMKL_P4_VALIDATE session=$session gen=$gen " +
                                "season=$sn verified=${animeExact != null} " +
                                "mappedTitles=${animeExact?.sourceIds} " +
                                "total=${animeExact?.mapped?.size}")
                        }
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
                                    Log.i(TAG, "event=KITSU_TOTAL session=$session gen=$gen " +
                                        "season=$sn id=$id host=${host.substringAfter("https://")} " +
                                        "http=${response.code} episodes=$episodes")
                                    if (episodes != null) {
                                        total = episodes
                                        origin = "kitsu-episodeCount"
                                        break
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "event=KITSU_TOTAL_FAIL session=$session gen=$gen " +
                                        "season=$sn id=$id cause=${e.javaClass.simpleName}")
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
                                    Log.i(TAG, "event=MAL_LIGHT session=$session gen=$gen id=$id " +
                                        "http=${response.code} total=$total")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG,"event=MAL_LIGHT_FAILED session=$session gen=$gen " +
                                    "id=$id cause=${e.javaClass.simpleName}")
                            }
                        }
                        val fields = if (multi && isAnime == true &&
                            repo.idPrefix == "simkl") animeExact?.let {
                            val watched=it.watched.size
                            Draft(if (watched==0) SyncWatchType.NONE
                                  else if (watched==it.mapped.size) SyncWatchType.COMPLETED
                                  else SyncWatchType.WATCHING, watched, null)
                        } else remote?.let {
                            Draft(it.status, if (generalSimkl && repo.idPrefix == "simkl")
                                simklExact?.watched?.size ?: 0
                                else it.watchedEpisodes ?: 0, it.score)
                        }
                        Log.i(TAG, "event=PROVIDER_FETCH session=$session gen=$gen season=$sn " +
                            "provider=${repo.idPrefix} id=$id stateOk=${state.isSuccess} " +
                            "metaOk=${meta?.isSuccess ?: "skipped"} status=${fields?.status} " +
                            "watched=${fields?.watched} total=$total origin=$origin " +
                            "stateError=${state.exceptionOrNull()?.javaClass?.simpleName} " +
                            "metaError=${meta?.exceptionOrNull()?.javaClass?.simpleName} " +
                            "elapsedMs=${SystemClock.elapsedRealtime()-started}")
                        Triple(repo.idPrefix, fields, Pair(total, origin))
                    }
                }.awaitAll()
                if (!valid(gen, sn)) { log("STALE", "phase=fetch"); return@launch }
                statuses["simkl"]?.let { simkl ->
                    simkl.simklSeason = simklSeasonReplies["simkl"]
                    simkl.simklAnime = simklAnimeReplies["simkl"]
                    simkl.seasonMapping = mappedSeason
                }
                val providerTotals = replies.mapNotNull { it.third.first }.filter { it > 0 }
                val commonTotal = if (!multi) providerTotals.maxOrNull()
                    else providerTotals.distinct().singleOrNull()
                if (multi && commonTotal != null)
                    log("TOTAL_PEER", "season=$sn verifiedMappedIds=${statuses.keys} " +
                        "providerKnownTotals=$providerTotals inferred=$commonTotal")
                val offeredCount = StremioC.seasonEpisodeCountsByUrl[url]?.get(sn)
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
                    // The catalog episode list may contain 24 entries while a verified
                    // tracker season is a 12-episode anime entry. Never equate
                    // catalog count with the service's actual episode total.
                    // A missing/invalid season ID is excluded before reaching here.
                    target.writeBlocked = when {
                        generalSimkl && prefix == "simkl" && target.simklSeason == null ->
                            "simkl-season-episodes-unverified"
                        multi && isAnime == true && prefix == "simkl" &&
                            target.simklAnime == null -> "simkl-anime-episode-map-unverified"
                        else -> null
                    }
                    log("PROVIDER_READY", "provider=$prefix status=${draft?.status} " +
                        "watched=${draft?.watched} total=${target.total} source=${target.totalSource} " +
                        "readOnly=${target.writeBlocked} offered=$offeredCount")
                }
                updateOptions()
                render("fetch-complete")
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
            // The exact selector and dropdown adapter are shared with Browse Genre.
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
                    log("PROVIDER_SELECT", "provider=$key list=${statuses.keys}")
                    render("provider-selected")
                }
            }
            plus?.setOnClickListener { adjust(1) }
            minus?.setOnClickListener { adjust(-1) }
            saveButton?.setOnClickListener { save() }
            checks?.setOnItemClickListener { _, _, pos, _ ->
                val tracker = statuses[selected] ?: return@setOnItemClickListener
                val edit = tracker.draft ?: return@setOnItemClickListener
                edit.status = SyncWatchType.fromInternalId(pos - 1)
                edit.explicitNone = edit.status == SyncWatchType.NONE
                log("FIELD_EDIT", "provider=${tracker.prefix} field=status value=${edit.status}")
                render("status-edit")
            }
            slider?.clearOnChangeListeners()
            slider?.addOnChangeListener { _, value, fromUser ->
                if (!fromUser || applying) return@addOnChangeListener
                val tracker = statuses[selected] ?: return@addOnChangeListener
                val draft = tracker.draft ?: return@addOnChangeListener
                draft.score = Score.from(value, 10)
                log("FIELD_EDIT", "provider=${tracker.prefix} field=rating value=$value")
                render("rating-edit")
            }
            current?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (applying || current?.hasFocus() != true) return
                    val tracker = statuses[selected] ?: return
                    val draft = tracker.draft ?: return
                    val entered = s?.toString()?.toIntOrNull() ?: return
                    val clamped = entered.coerceIn(0, tracker.total?.takeIf { it > 0 } ?: Int.MAX_VALUE)
                    draft.watched = clamped
                    log("FIELD_EDIT", "provider=${tracker.prefix} field=episodes entered=$entered " +
                        "applied=$clamped max=${tracker.total} source=${tracker.totalSource}")
                    root.post { if (active && selected == tracker.prefix) render("text-edit") }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            log("UI_ATTACHED", "selector=BrowseGenre shared widthDp=208 " +
                "save=${saveButton != null} plus=${plus != null} " +
                "minus=${minus != null} rating=${slider != null} " +
                "statusList=${checks != null} episodesInput=${current != null}")
        }

        private fun updateOptions() {
            val spinner = combo ?: return
            applying = true
            try {
                val names = statuses.keys.map { titles[it] ?: it }
                val activity = fragment.requireActivity()
                val checkId = pluginResources.getIdentifier(
                    "addon_check_icon", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
                spinner.adapter = catalogStyledAdapter(activity, names, spinner,
                    android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.BOLD),
                    android.widget.TextView(activity).textSize,
                    if (checkId != 0) pluginResources.getDrawable(checkId, null) else null)
                spinner.isEnabled = statuses.size > 1
                spinner.isClickable = statuses.size > 1
                spinner.isFocusable = statuses.size > 1
                comboArrow?.visibility = if (statuses.size > 1) View.VISIBLE else View.GONE
                val position = statuses.keys.indexOf(selected).coerceAtLeast(0)
                if (names.isNotEmpty()) spinner.setSelection(position, false)
                log("UI_OPTIONS", "items=${statuses.keys} selected=$selected enabled=${spinner.isEnabled} " +
                    "popupWidth=${spinner.dropDownWidth}")
            } finally { applying = false }
        }

        private fun adjust(delta: Int) {
            val tracker = statuses[selected] ?: return
            val draft = tracker.draft ?: return
            val upper = tracker.total?.takeIf { it > 0 } ?: Int.MAX_VALUE
            val before = draft.watched
            draft.watched = (draft.watched.toLong() + delta).coerceIn(0L, upper.toLong()).toInt()
            log("FIELD_EDIT", "provider=${tracker.prefix} field=episodes-delta " +
                "before=$before delta=$delta after=${draft.watched} total=${tracker.total}")
            render("episode-button")
        }

        private fun render(reason: String) {
            if (!active || combo == null) return
            val target = statuses[selected]
            if (target == null || target.loading || target.draft == null) {
                // The selector lives inside the native holder. Never hide it when
                // an individual provider fails or is still loading.
                applying = true
                try {
                    holder?.visibility = View.VISIBLE
                    shimmer?.visibility = View.GONE
                    saveButton?.isEnabled = false
                    plus?.isEnabled = false
                    minus?.isEnabled = false
                    maximum?.text = "/ ?"
                    bar?.progress = 0
                    current?.setText("")
                    checks?.clearChoices()
                    scoreLabel?.text = "?"
                } finally { applying = false }
                if (target == null) {
                    log("UI_UNAVAILABLE", "reason=$reason count=${statuses.size}")
                } else if (!target.loading) {
                    log("UI_UNAVAILABLE", "reason=$reason provider=${target.prefix} error=${target.error}")
                }
                return
            }
            val data = target.draft!!
            applying = true
            try {
                holder?.visibility = View.VISIBLE
                shimmer?.visibility = View.GONE
                (shimmer as? com.facebook.shimmer.ShimmerFrameLayout)?.stopShimmer()
                val max = target.total?.takeIf { it > 0 }
                maximum?.text = if (max == null) "/ ?" else "/ $max"
                bar?.max = (max ?: maxOf(1, data.watched)).coerceAtMost(1000000) * 1000
                bar?.progress = data.watched.coerceAtMost(1000000) * 1000
                val newText = data.watched.toString()
                if (current?.text?.toString() != newText) {
                    current?.setText(newText)
                    current?.setSelection(newText.length)
                }
                checks?.setItemChecked(data.status.internalId + 1, true)
                // A Simkl show has one global status/rating. Never disguise it as
                // a season-local edit; only per-episode history is writable.
                checks?.isEnabled = target.writeBlocked == null &&
                    target.simklSeason == null && target.simklAnime == null
                slider?.isEnabled = target.writeBlocked == null &&
                    target.simklSeason == null && target.simklAnime == null
                val valScore = data.score?.toFloat(10) ?: 0f
                slider?.value = valScore.coerceIn(0f,10f).toInt().toFloat()
                scoreLabel?.text = data.score?.toInt(10)?.toString()?.plus("/10") ?: "?"
                saveButton?.isEnabled = !target.saving && target.writeBlocked == null
                plus?.isEnabled = target.writeBlocked == null
                minus?.isEnabled = target.writeBlocked == null
            } catch (e: Exception) {
                log("UI_ERROR", "phase=$reason cause=${e.javaClass.simpleName}")
            } finally { applying = false }
            log("UI_RENDER", "reason=$reason provider=${target.prefix} status=${data.status} " +
                "watched=${data.watched} total=${target.total} totalSource=${target.totalSource} " +
                "readOnly=${target.writeBlocked} dirty=${data != target.baseline}")
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
            if (tracker.writeBlocked != null || tracker.saving) {
                log("SAVE_BLOCK", "provider=$prefix cause=${tracker.writeBlocked ?: "already-saving"}")
                return
            }
            if (draft.status == SyncWatchType.NONE && draft.watched > 0 && !draft.explicitNone) {
                draft.status = SyncWatchType.WATCHING
                tracker.draft?.status = SyncWatchType.WATCHING
                log("AUTO_WATCHING", "provider=$prefix reason=progress-positive")
            }
            val updated = draft != before
            if (!updated) { log("SAVE_NOOP", "provider=$prefix"); return }
            if (draft.status == SyncWatchType.NONE && !draft.explicitNone &&
                (draft.score != before.score || draft.watched != before.watched)) {
                log("SAVE_BLOCK", "provider=$prefix cause=none-would-delete")
                return
            }
            if (draft.watched < 0 || tracker.total?.let { it > 0 && draft.watched > it } == true) {
                log("SAVE_BLOCK", "provider=$prefix cause=progress-limit total=${tracker.total}")
                return
            }
            if ((tracker.simklSeason != null || tracker.simklAnime != null) &&
                (draft.score != before.score ||
                    (draft.status != before.status &&
                     !(before.status == SyncWatchType.NONE &&
                       draft.status == SyncWatchType.WATCHING && draft.watched > 0)))) {
                log("SAVE_BLOCK","provider=$prefix reason=simkl-status-is-show-wide")
                return
            }
            tracker.saving = true
            render("save-start")
            log("SAVE_REQUEST", "provider=$prefix id=$id season=$sn gen=$gen " +
                "before=$before after=$draft total=${tracker.total} origin=${tracker.totalSource}")
            // Detached/changed screens do not receive this result, but the snapshot
            // keeps writes directed to the user's original chosen service/season.
            scope.launch {
                val started = SystemClock.elapsedRealtime()
                val result = withContext(Dispatchers.IO) {
                    if (tracker.simklSeason != null) {
                        simklSeasonSave(tracker, draft, before, gen, sn)
                    } else if (tracker.simklAnime != null) {
                        simklAnimeSave(tracker, draft, before, gen, sn)
                    } else {
                    val fresh = tracker.repo.status(id)
                    val remote = fresh.getOrNull()
                    if (remote == null) {
                        Log.w(TAG, "event=SAVE_REMOTE_MISSING session=$session gen=$gen season=$sn " +
                            "provider=$prefix id=$id error=${fresh.exceptionOrNull()?.javaClass?.simpleName}")
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
                        Log.i(TAG, "event=SAVE_HTTP session=$session gen=$gen season=$sn provider=$prefix " +
                            "id=$id ok=${issued.getOrNull()} error=${issued.exceptionOrNull()?.javaClass?.simpleName}")
                        var verified = false
                        for (attempt in 1..3) {
                            if (attempt > 1) kotlinx.coroutines.delay(if (attempt == 2) 350L else 900L)
                            val read = tracker.repo.status(id)
                            val got = read.getOrNull()
                            verified = got != null &&
                                (draft.status == before.status || got.status == remote.status) &&
                                (draft.watched == before.watched || (got.watchedEpisodes ?: 0) == (remote.watchedEpisodes ?: 0)) &&
                                (draft.score == before.score || got.score == remote.score)
                            Log.i(TAG,"event=SAVE_READBACK session=$session gen=$gen season=$sn " +
                                "provider=$prefix attempt=$attempt request=${issued.getOrNull()} " +
                                "verified=$verified gotStatus=${got?.status} gotEpisodes=${got?.watchedEpisodes} " +
                                "expectedStatus=${remote.status} expectedEpisodes=${remote.watchedEpisodes} " +
                                "exception=${read.exceptionOrNull()?.javaClass?.simpleName}")
                            if (verified) break
                        }
                        if (!verified && prefix == "kitsu" && remote.status != SyncWatchType.NONE) {
                            val fallback = kitsuFallback(id, remote, gen, sn, tracker.repo)
                            if (fallback) {
                                val after = tracker.repo.status(id).getOrNull()
                                verified = after != null &&
                                    (draft.status == before.status || after.status == remote.status) &&
                                    (draft.watched == before.watched ||
                                        (after.watchedEpisodes ?: 0) == (remote.watchedEpisodes ?: 0)) &&
                                    (draft.score == before.score || after.score == remote.score)
                                Log.i(TAG, "event=KITSU_RECHECK session=$session gen=$gen season=$sn " +
                                    "verified=$verified status=${after?.status} progress=${after?.watchedEpisodes}")
                            }
                        }
                        verified
                    }
                    }
                }
                if (valid(gen, sn) && statuses[prefix] === tracker) {
                    tracker.saving = false
                    if (result) {
                        val anime = tracker.simklAnime
                        if (anime != null) {
                            val count=anime.watched.size
                            val actual=when {
                                count == 0 -> SyncWatchType.NONE
                                count == anime.mapped.size -> SyncWatchType.COMPLETED
                                else -> SyncWatchType.WATCHING
                            }
                            tracker.baseline=Draft(actual,count,null)
                            tracker.draft?.let { current ->
                                if(current == draft) {
                                    current.status=actual
                                    current.watched=count
                                    current.explicitNone=false
                                }
                            }
                            log("SIMKL_P4_UI_RECONCILE",
                                "provider=$prefix actual=$actual watched=$count total=${anime.mapped.size}")
                        } else {
                            tracker.baseline = draft.snapshot()
                            tracker.draft?.let { if (it == draft) it.explicitNone = false }
                        }
                    } else tracker.error = "save-or-readback-failed"
                    log("SAVE_COMPLETE", "provider=$prefix id=$id verified=$result " +
                        "elapsedMs=${SystemClock.elapsedRealtime()-started}")
                    render("save-done")
                } else {
                    log("STALE", "phase=save-complete provider=$prefix origSeason=$sn verified=$result")
                }
            }
        }

        private suspend fun kitsuFallback(
            id: String, expected: SyncAPI.AbstractSyncStatus, gen: Long, sn: Int,
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
                    Log.i(TAG, "event=KITSU_HTTP session=$session gen=$gen season=$sn " +
                        "host=${host.substringAfter("https://")} step=find code=${find.code} " +
                        "existing=${entry != null}")
                    if (find.code !in 200..299) continue
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
                    val errors = if (response.code !in 200..299)
                        runCatching { JSONObject(response.text).optJSONArray("errors")?.length() }.getOrNull()
                    else null
                    Log.i(TAG, "event=KITSU_HTTP session=$session gen=$gen season=$sn " +
                        "host=${host.substringAfter("https://")} step=${if(entry == null) "create" else "update"} " +
                        "code=${response.code} errors=$errors")
                    if (response.code in 200..299) return true
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    Log.e(TAG, "event=KITSU_HTTP session=$session gen=$gen season=$sn " +
                        "failure=${e.javaClass.simpleName}")
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
            log("UI_DETACH")
        }

        fun resume() {
            if (active) log("RESUME", "page=${pageUrl?.hashCode()} season=$season")
        }

        fun stop() {
            if (!active) return
            log("DETACH", "observers=${observers.size} statuses=${statuses.keys} " +
                "running=${fetchJob?.isActive}")
            active = false
            generation++
            fetchJob?.cancel()
            scope.cancel()
            observers.forEach { it() }
            observers.clear()
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

        fun auditReentry() {
            seasonSyncProbe.resume()
            val key = StremioCMdbUiBridge.keyFor(detailUrl) ?: return
        }

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

        private val searchButton = listOf("result_search_Button", "result_search").firstNotNullOfOrNull {
            val id = root.resources.getIdentifier(it, "id", root.context.packageName)
            if (id == 0) null else root.findViewById<View>(id)
        }
        private val searchTitle = root.resources.getIdentifier("result_title", "id", root.context.packageName)
            .takeIf { it != 0 }?.let { root.findViewById<TextView>(it) }
        private val castItems = root.resources.getIdentifier("result_cast_items", "id", root.context.packageName)
            .takeIf { it != 0 }?.let { root.findViewById<RecyclerView>(it) }
        private val episodeList = root.findViewById<RecyclerView>(R.id.result_episodes)
        private val episodeIndicatorRestores = mutableMapOf<View, () -> Unit>()
        private val episodeChildListener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = positionEpisodeIndicator(view)
            override fun onChildViewDetachedFromWindow(view: View) = restoreEpisodeIndicator(view)
        }

        private fun positionEpisodeIndicator(row: View) {
            // The compact text-only episode row has no thumbnail and must remain native.
            if (row.findViewById<View>(R.id.episode_poster) == null) return
            val indicator = row.findViewById<View>(R.id.watch_progress_container) ?: return
            if (episodeIndicatorRestores.containsKey(indicator)) return
            val original = indicator.layoutParams as? FrameLayout.LayoutParams ?: return
            val progress = row.findViewById<CircularProgressIndicator>(R.id.episode_progress) ?: return
            val originalInset = progress.indicatorInset
            val originalColors = progress.indicatorColor.copyOf()
            // Leave the CloudStream native white 20% track color untouched.
            val themeColor = row.context.colorFromAttribute(R.attr.colorPrimary)
            val inset = (1.5f * indicator.resources.displayMetrics.density + 0.5f).toInt()
            val positioned = FrameLayout.LayoutParams(original).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                marginStart = inset
                bottomMargin = inset
            }
            val alpha = indicator.alpha
            val scaleX = indicator.scaleX
            val scaleY = indicator.scaleY
            val pivotX = indicator.pivotX
            val pivotY = indicator.pivotY
            // Scale toward the bottom-left, so 1.5dp is the visible circle's inset.
            val pivotListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.pivotX = 0f
                v.pivotY = v.height.toFloat()
            }
            episodeIndicatorRestores[indicator] = {
                indicator.removeOnLayoutChangeListener(pivotListener)
                indicator.layoutParams = original
                indicator.alpha = alpha
                indicator.scaleX = scaleX
                indicator.scaleY = scaleY
                indicator.pivotX = pivotX
                indicator.pivotY = pivotY
                progress.setIndicatorInset(originalInset)
                progress.setIndicatorColor(*originalColors)
                progress.requestLayout()
            }
            progress.setIndicatorInset(0)
            progress.setIndicatorColor(themeColor)
            // Material's inset setter only invalidates drawing; force the new 40dp measure.
            progress.requestLayout()
            indicator.addOnLayoutChangeListener(pivotListener)
            indicator.layoutParams = positioned
            indicator.pivotX = 0f
            indicator.pivotY = indicator.height.toFloat()
            indicator.scaleX = scaleX * 0.9f
            indicator.scaleY = scaleY * 0.9f
            indicator.alpha = 0.8f
        }

        private fun restoreEpisodeIndicator(row: View) {
            val indicator = row.findViewById<View>(R.id.watch_progress_container) ?: return
            episodeIndicatorRestores.remove(indicator)?.invoke()
        }
        private val actorNameId = root.resources.getIdentifier("actor_name", "id", root.context.packageName)
        private val actorInformation = ActorInformation.Controller(fragment, detailUrl)
        private val genreGroup = root.findViewById<ChipGroup>(R.id.result_tag)
        private val boundGenreChips = mutableMapOf<View, CatalogShortcutTarget>()
        private val actorCardForegrounds = mutableMapOf<View, Drawable?>()

        private fun bindActorCardRipple(card: View) {
            if (actorCardForegrounds.containsKey(card)) return
            val original = card.foreground
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (card as? CardView)?.radius ?: 0f
                setColor(android.graphics.Color.WHITE)
            }
            card.foreground = RippleDrawable(
                ColorStateList.valueOf(STREMIOC_DETAIL_RIPPLE_COLOR), original, mask
            )
            card.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.foreground?.setHotspot(event.x, event.y)
                        view.isPressed = true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.isPressed = false
                }
                false // Preserve actor search, scrolling and long-press behavior.
            }
            actorCardForegrounds[card] = original
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
                    (chip as? Chip)?.let(::applyEpisodeChipFeedback)
                    chip.setOnClickListener {
                        openCatalog(target, null)
                    }
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
            val list = castItems ?: return@OnClickListener
            val position = list.getChildAdapterPosition(card)
            if (position == RecyclerView.NO_POSITION) return@OnClickListener
            (list.adapter as? ActorAdaptor)?.getItemOrNull(position)?.let { actorInformation.click(position, it) }
        }
        private val castLongClick = View.OnLongClickListener { card ->
            if (actorNameId != 0) {
                openSearch(card.findViewById<TextView>(actorNameId)?.text?.toString()?.trim().orEmpty())
            }
            true
        }
        private val detailPreDraw = ViewTreeObserver.OnPreDrawListener {
            if (fragment.isAdded) {
                if (searchButton?.isShown == true) searchButton.setOnClickListener(searchClick)
                castItems?.takeIf { it.isShown }?.let { list ->
                    for (i in 0 until list.childCount) {
                        val card = list.getChildAt(i)
                        card.setOnClickListener(castClick)
                        card.setOnLongClickListener(castLongClick)
                        bindActorCardRipple(card)
                    }
                }
                bindGenreClicks()
            }
            true
        }
        fun start() {
            episodeList?.let { list ->
                list.addOnChildAttachStateChangeListener(episodeChildListener)
                for (i in 0 until list.childCount) positionEpisodeIndicator(list.getChildAt(i))
            }
            actorInformation.start()
            runCatching { seasonSyncProbe.start() }.onFailure {
                Log.e("STCSeasonSync", "event=ATTACH_FAILURE fragment=${fragment.javaClass.simpleName}", it)
            }
            view.addTextChangedListener(this)
            searchButton?.setOnClickListener(searchClick)
            if (searchButton != null || castItems != null || genreGroup != null)
                root.viewTreeObserver.addOnPreDrawListener(detailPreDraw)
            runCatching { backdrop.start() }; runCatching { trailers.start() }; runCatching { render() }
        }
        fun detach() {
            seasonSyncProbe.stop()
            episodeList?.removeOnChildAttachStateChangeListener(episodeChildListener)
            episodeIndicatorRestores.values.toList().forEach { it.invoke() }
            episodeIndicatorRestores.clear()
            actorInformation.stop()
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
            actorCardForegrounds.forEach { (card, foreground) ->
                card.setOnClickListener(null)
                card.setOnLongClickListener(null)
                card.setOnTouchListener(null)
                card.isPressed = false
                card.foreground = foreground
            }
            actorCardForegrounds.clear()
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
        detachPreview()
        detailRevealEnd?.invoke()
        resetSearchState()
        pipSubtitles.detach()
        audioSelector.detach()
        subtitleController.detach()
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
