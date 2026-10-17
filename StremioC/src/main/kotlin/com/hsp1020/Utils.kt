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
        if (key == SUBTITLE_AUTO_SELECT_KEY && isStremio) manualOverride = true
    }

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
        val language = SubtitlesFragment.getAutoSelectLanguageTagIETF()
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

    private inner class Watcher(private val view: TextView, private val root: View, private val detailUrl: String?, private val fragment: Fragment) : TextWatcher {
        private val backdrop = DetailBackdropLayout(root, detailUrl)
        private val trailers = TrailerChooser(fragment, root, detailUrl)
        private var changing = false

        fun matchesMdbKey(key: String): Boolean = StremioCMdbUiBridge.keyFor(detailUrl) == key

        fun auditReentry() {
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
            view.addTextChangedListener(this)
            searchButton?.setOnClickListener(searchClick)
            if (searchButton != null || castItems != null || genreGroup != null)
                root.viewTreeObserver.addOnPreDrawListener(detailPreDraw)
            runCatching { backdrop.start() }; runCatching { trailers.start() }; runCatching { render() }
        }
        fun detach() {
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
