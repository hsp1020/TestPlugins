package com.hsp1020.elevate

import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.ExtractorLinkGenerator
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.VideoGenerator
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.ResultData
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import org.json.JSONArray

private fun installedYouTubeExtractor(): ExtractorApi? {
    for (index in extractorApis.lastIndex downTo 0) {
        val extractor = extractorApis[index]
        if (extractor.javaClass.name != "com.hsp1020.youtube.YoutubeExtractor" ||
            extractor.name != "YouTube" ||
            !extractor.mainUrl.trimEnd('/').equals("https://www.youtube.com", ignoreCase = true) ||
            extractor.sourcePlugin.isNullOrBlank()) continue
        if (APIHolder.allProviders.any { provider ->
                provider.name == "YouTube" && provider.sourcePlugin == extractor.sourcePlugin
            }) return extractor
    }
    return null
}

private suspend fun extractTrailerMedia(youtubeId: String): Pair<List<ExtractorLink>, List<SubtitleData>> =
    withContext(Dispatchers.IO) {
        val registered = installedYouTubeExtractor()
            ?: return@withContext emptyList<ExtractorLink>() to emptyList<SubtitleData>()
        // Preserve the previous per-playback extractor instance rather than sharing mutable state.
        val extractor = runCatching {
            registered.javaClass.getDeclaredConstructor().newInstance() as ExtractorApi
        }.getOrDefault(registered)
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subtitles = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        extractor.getUrl(
            "https://www.youtube.com/watch?v=$youtubeId",
            null,
            { subtitles.add(it) },
            { links.add(it) }
        )
        synchronized(links) { links.toList() }
            .distinctBy { it.url } to
            synchronized(subtitles) { subtitles.toList() }
                .distinctBy { it.url }.map { PlayerSubtitleHelper.getSubtitleData(it) }
    }


internal data class TrailerChoice(
    val url: String,
    val title: String,
    val type: String,
    val language: String? = null,
    val referer: String? = null,
    val raw: Boolean = false,
    val headers: Map<String, String> = emptyMap()
) {
    val youtubeId: String?
        get() {
            val parsed = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
            val host = parsed.host?.lowercase() ?: return null
            val candidate = when {
                host == "youtu.be" || host.endsWith(".youtu.be") -> parsed.pathSegments.firstOrNull()
                host == "youtube.com" || host.endsWith(".youtube.com") ||
                    host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ->
                    parsed.getQueryParameter("v") ?: parsed.pathSegments
                        .takeIf { it.firstOrNull() in listOf("embed", "shorts", "live", "v") }
                        ?.getOrNull(1)
                else -> null
            }
            return candidate?.takeIf { id -> id.length == 11 && id.all { c ->
                (c.isLetterOrDigit() && c.code < 128) || c == '_' || c == '-'
            } }
        }
}

private suspend fun extractTrailerMedia(entry: TrailerChoice): Pair<List<ExtractorLink>, List<SubtitleData>> {
    val youtubeId = entry.youtubeId
    if (youtubeId != null && installedYouTubeExtractor() != null) return extractTrailerMedia(youtubeId)
    return withContext(Dispatchers.IO) {
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subtitles = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        val handled = loadExtractor(entry.url, entry.referer,
            { subtitles.add(it) }, { links.add(it) })
        if (!handled && entry.raw) links.add(newExtractorLink(
            "", "Trailer", entry.url
        ) {
            referer = entry.referer.orEmpty()
            headers = entry.headers
        })
        synchronized(links) { links.toList() }.distinctBy { it.url } to
            synchronized(subtitles) { subtitles.toList() }
                .distinctBy { it.url }.map { PlayerSubtitleHelper.getSubtitleData(it) }
    }
}

internal class TrailerChooser(
    private val fragment: Fragment,
    private val root: View,
    private val detailUrl: String?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val model by lazy { ViewModelProvider(fragment)[ResultViewModel2::class.java] }
    private val responseField by lazy {
        runCatching { ResultViewModel2::class.java.getDeclaredField("currentResponse")
            .apply { isAccessible = true } }.getOrNull()
    }
    private val pageObserver = Observer<Resource<ResultData>?> { refresh() }
    private val nativeObserver = Observer<List<com.lagradost.cloudstream3.ui.result.ExtractedTrailerData>> { refresh() }
    private fun choices(): List<TrailerChoice> {
        val page = (model.page.value as? Resource.Success<ResultData>)?.value
        val extras = runCatching { JSONArray(page?.syncData?.get("trailer_choices").orEmpty()) }
            .getOrNull()?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val url = item.optString("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                    TrailerChoice(url, item.optString("title", "Trailer"), item.optString("type", "Trailer"),
                        item.optString("language").takeIf { it.isNotBlank() })
                }
            }.orEmpty()
        if (extras.isNotEmpty()) return extras.distinctBy { it.url }.take(30)
        val response = runCatching { responseField?.get(model) as? LoadResponse }.getOrNull()
        if (page == null) return emptyList()
        val native = if (response != null && response.url == page.url) response.trailers.mapIndexedNotNull { index, trailer ->
            val url = trailer.extractorUrl.trim().takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            TrailerChoice(url, "Trailer ${index + 1}", "Trailer",
                referer = trailer.referer, raw = trailer.raw, headers = trailer.headers)
        } else emptyList()
        if (native.isNotEmpty()) return native.distinctBy { it.url }.take(30)
        return model.trailers.value.orEmpty().flatMap { it.mirros }.mapIndexed { index, pair ->
            TrailerChoice(pair.second, "Trailer ${index + 1}", "Trailer")
        }.distinctBy { it.url }.take(30)
    }
    private var button: TextView? = null
    private var dialog: BottomSheetDialog? = null
    private var opening = false
    private var stopped = false
    private var navigatingToPlayer = false
    private var extractionJob: kotlinx.coroutines.Job? = null
    private var extractionProgress: android.widget.ProgressBar? = null
    private var reopenAfterPlayback = false
    private var reopenScrollY = 0
    private var reopenFocusedRow = -1
    private var restorePosted = false
    private val returnKey = "elevate_trailer_picker_return"
    private val returnScrollKey = "${returnKey}_scroll"
    private val returnFocusKey = "${returnKey}_focus"
    private val resumeObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            val navEntry = runCatching { fragment.findNavController().currentBackStackEntry }.getOrNull()
            if (detailUrl != null && navEntry?.savedStateHandle?.get<String>(returnKey) == detailUrl) {
                val saved = navEntry.savedStateHandle
                saved.remove<String>(returnKey)
                reopenScrollY = saved.remove<Int>(returnScrollKey) ?: 0
                reopenFocusedRow = saved.remove<Int>(returnFocusKey) ?: -1
                reopenAfterPlayback = true
            }
            refresh()
            reopenPickerIfReady()
        }
    }
    private var items: List<TrailerChoice> = emptyList()
    private val tvButton = find("result_play_trailer_button")
    private val tvContainer = find("result_play_trailer")
    private val isTv = tvButton != null && tvContainer != null
    private val pickerClick = View.OnClickListener { showPicker() }
    // The TV trailer button can be rebound when the result layout changes;
    // it does not need to be polled on each rendered frame.
    private val tvLayout = ViewTreeObserver.OnGlobalLayoutListener {
        if (fragment.isAdded) {
            refresh()
            syncTvButton()
        }
    }

    private fun find(name: String): View? {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        return if (id == 0) null else root.findViewById(id)
    }
    private val title: TextView? = root.resources.getIdentifier("result_title", "id", root.context.packageName)
        .takeIf { it != 0 }?.let { root.findViewById(it) }
    private val titleWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = refresh()
    }

    private fun dp(value: Int): Int = (value * root.resources.displayMetrics.density + 0.5f).toInt()

    private fun localizedLanguage(raw: String?): String? {
        val code = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val appLocale = root.resources.configuration.locales.get(0).toLanguageTag()
        return SubtitleHelper.fromTagToLanguageName(code, appLocale) ?: code.uppercase(java.util.Locale.ROOT)
    }

    private fun themeColor(attrName: String, fallback: Int): Int {
        val id = root.resources.getIdentifier(attrName, "attr", root.context.packageName)
        if (id == 0) return fallback
        val out = TypedValue()
        return if (root.context.theme.resolveAttribute(id, out, true)) out.data else fallback
    }

    fun start() {
        stopped = false
        fragment.lifecycle.addObserver(resumeObserver)
        model.page.observe(fragment.viewLifecycleOwner, pageObserver)
        model.trailers.observe(fragment.viewLifecycleOwner, nativeObserver)
        title?.addTextChangedListener(titleWatcher)
        if (isTv) {

            tvButton?.setOnClickListener(pickerClick)
            root.viewTreeObserver.addOnGlobalLayoutListener(tvLayout)
        }
        refresh()
    }

    private fun syncTvButton() {
        if (!isTv) return
        val desired = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (tvContainer?.visibility != desired) tvContainer?.visibility = desired

        if (desired == View.VISIBLE) {
            tvButton?.setOnClickListener(pickerClick)
        }
    }

    private fun reopenPickerIfReady() {
        if (!reopenAfterPlayback || !fragment.isResumed || items.isEmpty() || restorePosted) return
        if (dialog?.isShowing == true) {
            reopenAfterPlayback = false
            return
        }
        restorePosted = true
        root.post {
            restorePosted = false
            if (reopenAfterPlayback && fragment.isResumed && fragment.view === root && items.isNotEmpty() &&
                showPicker(reopenScrollY, reopenFocusedRow)) {
                reopenAfterPlayback = false
            }
        }
    }

    fun refresh() {
        if (!fragment.isAdded) return
        val context = root.context
        val next = choices()
        if (next == items && (isTv || button != null || next.isEmpty())) {
            if (!isTv && button != null) updatePhoneLabel()
            reopenPickerIfReady()
            return
        }
        items = next
        if (isTv) {
            syncTvButton()
            if (items.isEmpty()) dialog?.dismiss()
            reopenPickerIfReady()
            return
        }
        if (items.isEmpty()) {
            (button?.parent as? ViewGroup)?.removeView(button)
            button = null
            dialog?.dismiss()
            return
        }
        val id = context.resources.getIdentifier("result_top_holder", "id", context.packageName)
        val hero = if (id != 0) root.findViewById<FrameLayout>(id) else null
        if (hero == null) return
        if (button == null) {
            button = Chip(context).apply {

                setChipDrawable(ChipDrawable.createFromAttributes(context, null, 0, R.style.ChipFilled).apply {
                    alpha = 0x80
                })
                chipBackgroundColor = ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, R.color.primaryGrayBackground))
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.white))
                applyElevateNativeFont(bold = true)
                fontFeatureSettings = "'liga' 0, 'clig' 0, 'calt' 0, 'dlig' 0"
                isCheckable = false
                isClickable = true
                isFocusable = true

                setOnClickListener { showPicker() }
            }.also {
                hero.addView(it, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply { topMargin = dp(16); marginEnd = dp(16) })
            }
        }
        updatePhoneLabel()
        reopenPickerIfReady()
    }

    private fun updatePhoneLabel() {
        val label = root.context.getString(R.string.trailer)
        button?.text = "$label (${items.size})"
        button?.contentDescription = "${root.context.getString(R.string.play_trailer_button)} (${items.size})"
    }


    private fun showPicker(restoreScrollY: Int? = null, restoreFocus: Int = -1): Boolean {
        val activity = fragment.activity ?: return false
        if (dialog?.isShowing == true || opening || items.isEmpty()) return false
        navigatingToPlayer = false
        val foreground = themeColor("textColor", Color.WHITE)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        // Match CloudStream's home expanded header: WatchHeaderText and drag-down arrow.
        val header = FrameLayout(activity).apply {
            setPadding(dp(4), dp(12), dp(4), dp(12))
            isClickable = true
            isFocusable = true
            val ripple = TypedValue()
            if (activity.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, ripple, true
                ) && ripple.resourceId != 0) {
                this.foreground = androidx.core.content.ContextCompat.getDrawable(activity, ripple.resourceId)
            }
            if (isTv) this.foreground = this.foreground?.let { pressed ->
                val border = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    activity, R.drawable.outline_drawable)?.mutate()
                if (border != null) android.graphics.drawable.LayerDrawable(arrayOf(pressed, border))
                else pressed
            }
            setOnClickListener { dialog?.dismiss() }
        }
        header.addView(TextView(activity, null, 0, R.style.WatchHeaderText).apply {
            text = "${activity.getString(R.string.trailer)} (${items.size})"
            applyElevateNativeFont(bold = true)
            fontFeatureSettings = "'liga' 0, 'clig' 0, 'calt' 0, 'dlig' 0"
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL
        ).apply { marginEnd = dp(50) })
        header.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_baseline_keyboard_arrow_down_24)
            imageTintList = ColorStateList.valueOf(foreground)
            contentDescription = activity.getString(R.string.home_expanded_hide)
        }, FrameLayout.LayoutParams(
            dp(30), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { marginEnd = dp(5) })
        list.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        // Let rows grow for multiline titles. Limit only the total scroll viewport,
        // rather than assuming each row is 88dp and clipping taller rows.
        val maxListHeight = (activity.resources.displayMetrics.heightPixels * 0.6f).toInt()
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxListHeight, View.MeasureSpec.AT_MOST)
                )
            }
        }
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { index, entry ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(92)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                isFocusable = true
                val focusBackground = TypedValue()
                if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, focusBackground, true) &&
                    focusBackground.resourceId != 0) setBackgroundResource(focusBackground.resourceId)
                setOnClickListener {
                    val focused = (0 until rows.childCount)
                        .firstOrNull { rows.getChildAt(it).hasFocus() } ?: if (isTv) index else -1
                    play(entry, scroll.scrollY, focused)
                }
            }
            val thumbnail = CardView(activity).apply {
                // Match the inner CardView in result_episode_large.xml:
                // default CardView radius/elevation, with native focus outline.
                isDuplicateParentStateEnabled = true
                this.foreground = androidx.core.content.ContextCompat.getDrawable(
                    activity, R.drawable.outline_drawable
                )
                addView(ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    isDuplicateParentStateEnabled = true
                    val ripple = TypedValue()
                    if (activity.theme.resolveAttribute(
                            android.R.attr.selectableItemBackgroundBorderless, ripple, true
                        ) && ripple.resourceId != 0
                    ) {
                        this.foreground = androidx.core.content.ContextCompat.getDrawable(
                            activity, ripple.resourceId
                        )
                    }
                    entry.youtubeId?.let { loadImage("https://i.ytimg.com/vi/" + it + "/hqdefault.jpg") }
                }, FrameLayout.LayoutParams(dp(126), dp(72)))
            }
            row.addView(thumbnail, LinearLayout.LayoutParams(dp(126), dp(72)))
            val labels = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), 0, dp(6), 0)
            }
            labels.addView(TextView(activity).apply {
                text = "${index + 1}. ${entry.title}"
                applyElevateNativeFont(bold = true)
                // Native episode_text has no maxLines/ellipsize restriction.
                setTextColor(foreground)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            labels.addView(TextView(activity).apply {
                text = (if (entry.type.equals("Trailer", true)) activity.getString(R.string.trailer) else entry.type) +
                    (localizedLanguage(entry.language)?.let { " · " + it } ?: "")
                setTextColor(themeColor("grayTextColor", foreground))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rows.addView(row)
        }
        scroll.addView(rows)
        list.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val progress = android.widget.ProgressBar(activity).apply {
            isIndeterminate = true
            visibility = View.GONE
            isFocusable = false
        }
        list.addView(progress, LinearLayout.LayoutParams(-1, dp(36)))
        extractionProgress = progress
        val picker = BottomSheetDialog(activity).apply {
            setContentView(list)
            setOnDismissListener {
                if (dialog === this) {
                    extractionJob?.cancel()
                    extractionJob = null
                    extractionProgress = null
                    opening = false
                    dialog = null
                    if (isTv && !stopped && !navigatingToPlayer && fragment.isResumed &&
                        fragment.view === root) {
                        tvButton?.takeIf { it.isAttachedToWindow && it.isShown && it.isFocusable }
                            ?.let { focus ->
                                focus.post {
                                    if (!stopped && fragment.isResumed && fragment.view === root &&
                                        dialog == null && focus.isAttachedToWindow &&
                                        focus.isShown && focus.isFocusable)
                                        focus.requestFocus()
                                }
                            }
                    }
                    navigatingToPlayer = false
                }
            }
            if (isTv) setOnShowListener {
                rows.getChildAt(restoreFocus.takeIf { it in 0 until rows.childCount } ?: 0)?.requestFocus()
            }
        }
        dialog = picker
        picker.show()
        if (restoreScrollY != null) scroll.post {
            if (dialog === picker && picker.isShowing) {
                scroll.scrollTo(0, restoreScrollY.coerceAtLeast(0))
                if (isTv && restoreFocus in 0 until rows.childCount)
                    rows.getChildAt(restoreFocus).requestFocus()
            }
        }
        return true
    }

    private fun play(entry: TrailerChoice, scrollY: Int, focusedRow: Int) {
        if (opening) return
        val picker = dialog?.takeIf { it.isShowing } ?: return
        opening = true
        extractionProgress?.visibility = View.VISIBLE
        extractionJob = scope.launch {
            try {
                val (playable, subtitles) = extractTrailerMedia(entry)
                if (!fragment.isResumed || fragment.view !== root || dialog !== picker || !picker.isShowing)
                    return@launch
                if (playable.isEmpty()) {
                    toast(fragment.requireContext().getString(R.string.no_links_found_toast))
                    return@launch
                }

                val selectedIndex = items.indexOf(entry).takeIf { it >= 0 } ?: return@launch
                val heading = title?.text?.toString()?.trim().orEmpty()
                    .ifBlank { root.context.getString(R.string.trailer) }
                val controller = runCatching { fragment.findNavController() }.getOrNull()
                    ?: return@launch
                val returnEntry = controller.currentBackStackEntry ?: return@launch
                val playerArgs = GeneratorPlayer.newInstance(
                    TrailerPlaybackGenerator(
                        items.toList(), heading, fragment.arguments?.getString("apiName") ?: "Elevate",
                        selectedIndex, playable, subtitles
                    ),
                    selectedIndex
                )
                val saved = returnEntry.savedStateHandle
                saved.set(returnKey, detailUrl)
                saved.set(returnScrollKey, scrollY)
                saved.set(returnFocusKey, focusedRow)
                try {
                    controller.navigate(R.id.global_to_navigation_player, playerArgs)
                    navigatingToPlayer = true
                } catch (error: Exception) {
                    saved.remove<String>(returnKey)
                    saved.remove<Int>(returnScrollKey)
                    saved.remove<Int>(returnFocusKey)
                    throw error
                }
                picker.dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (fragment.isAdded) toast(fragment.requireContext().getString(R.string.error))
            } finally {
                opening = false
                if (dialog === picker) extractionProgress?.visibility = View.GONE
                extractionJob = null
            }
        }
    }

    private fun toast(message: String) {
        fragment.activity?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    fun stop() {
        stopped = true
        fragment.lifecycle.removeObserver(resumeObserver)
        model.page.removeObserver(pageObserver)
        model.trailers.removeObserver(nativeObserver)
        title?.removeTextChangedListener(titleWatcher)
        if (isTv) {
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(tvLayout)
            val nativeLinks = model.trailers.value.orEmpty().flatMap { it.mirros }.map { it.first }
            tvContainer?.visibility = if (nativeLinks.isEmpty()) View.GONE else View.VISIBLE
            tvButton?.setOnClickListener(if (nativeLinks.isEmpty()) null else View.OnClickListener {
                runCatching {
                    fragment.findNavController().navigate(
                        R.id.global_to_navigation_player,
                        GeneratorPlayer.newInstance(ExtractorLinkGenerator(nativeLinks, emptyList()), 0)
                    )
                }
            })
        }

        extractionJob?.cancel()
        extractionJob = null
        dialog?.dismiss()
        dialog = null
        (button?.parent as? ViewGroup)?.removeView(button)
        button = null
        items = emptyList()
        scope.cancel()
    }
}

internal class TrailerPlaybackGenerator(
    private val trailers: List<TrailerChoice>,
    heading: String,
    providerName: String,
    selectedIndex: Int,
    selectedLinks: List<ExtractorLink>,
    selectedSubtitles: List<SubtitleData>
) : VideoGenerator<ResultEpisode>(
    trailers.mapIndexed { index, item ->
        ResultEpisode(
            headerName = heading,
            name = item.title,
            poster = item.youtubeId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
            episode = index + 1,
            seasonIndex = null,
            season = null,
            data = item.url,
            apiName = providerName,
            id = -(("elevate-trailer:" + item.url).hashCode() and Int.MAX_VALUE) - 1,
            index = index,
            position = 0L,
            duration = 0L,
            score = null,
            description = null,
            isFiller = null,
            tvType = TvType.Others,
            parentId = -(heading.hashCode() and Int.MAX_VALUE) - 1,
            videoWatchState = VideoWatchState.None
        )
    }
) {
    override val hasCache = false
    override val canSkipLoading = false
    private val cache = mutableMapOf(
        selectedIndex to (selectedLinks to selectedSubtitles)
    )

    override fun getId(index: Int): Int? = videos.getOrNull(index)?.id

    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean
    ): Boolean {
        val trailer = trailers.getOrNull(offset) ?: return false
        if (clearCache) cache.remove(offset)
        val (links, subtitles) = cache[offset] ?: extractTrailerMedia(trailer)
            .also { cache[offset] = it }

        links.filter { it.type in sourceTypes }.forEach { callback(it to null) }
        subtitles.forEach(subtitleCallback)
        return links.any { it.type in sourceTypes }
    }
}
