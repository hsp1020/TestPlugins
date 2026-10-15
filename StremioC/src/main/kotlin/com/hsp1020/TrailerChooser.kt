package com.hsp1020

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
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
import androidx.cardview.widget.CardView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.utils.ExtractorLink
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
// Shared by the first trailer launch and subsequent playlist entries.
private suspend fun extractTrailerMedia(youtubeId: String): Pair<List<ExtractorLink>, List<SubtitleData>> =
    withContext(Dispatchers.IO) {
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subtitles = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        com.hsp1020.youtube.YoutubeExtractor().getUrl(
            "https://www.youtube.com/watch?v=$youtubeId",
            null,
            { subtitles.add(it) },
            { links.add(it) }
        )
        synchronized(links) { links.toList() }
            .filter { it.source == "YouTube" && it.type in setOf(
                ExtractorLinkType.VIDEO, ExtractorLinkType.DASH, ExtractorLinkType.M3U8
            ) }.distinctBy { it.url } to
            synchronized(subtitles) { subtitles.toList() }
                .distinctBy { it.url }.map { PlayerSubtitleHelper.getSubtitleData(it) }
    }

/** StremioC-only trailer picker: no fake episodes or modifications to player internals. */
internal class TrailerChooser(
    private val fragment: Fragment,
    private val root: View,
    private val detailUrl: String?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var button: TextView? = null
    private var dialog: BottomSheetDialog? = null
    private var opening = false
    private var extractionJob: kotlinx.coroutines.Job? = null
    private var extractionProgress: android.widget.ProgressBar? = null
    private var finishPlayerHandoff: (() -> Unit)? = null
    private var reopenAfterPlayback = false
    private var reopenScrollY = 0
    private var reopenFocusedRow = -1
    private var restorePosted = false
    private val returnKey = "stremioc_trailer_picker_return"
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
    private val tvPreDraw = ViewTreeObserver.OnPreDrawListener {
        if (fragment.isAdded) {
            // Host trailer LiveData may reset the native TV button after page metadata arrives.
            refresh()
            syncTvButton()
        }
        true
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
        fragment.lifecycle.addObserver(resumeObserver)
        title?.addTextChangedListener(titleWatcher)
        if (isTv) {
            // This is CloudStream's native ResultSmallButtonTV: keep its
            // built-in ripple, focus and DPAD click handling untouched.
            tvButton?.setOnClickListener(pickerClick)
            root.viewTreeObserver.addOnPreDrawListener(tvPreDraw)
        }
        refresh()
    }

    private fun syncTvButton() {
        if (!isTv) return
        val desired = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (tvContainer?.visibility != desired) tvContainer?.visibility = desired
        // The host can replace the click listener or button style after metadata arrives.
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
        if (!fragment.isAdded || detailUrl.isNullOrBlank()) return
        val context = root.context
        val settingId = context.resources.getIdentifier("show_trailers_key", "string", context.packageName)
        val enabled = settingId == 0 || PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(settingId), true)
        val next = if (enabled) StremioC.trailerChoicesByUrl[detailUrl].orEmpty() else emptyList()
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
                // Use the exact genre-tag Chip style, preserving its font size, padding and shape.
                setChipDrawable(ChipDrawable.createFromAttributes(context, null, 0, R.style.ChipFilled).apply {
                    alpha = 0x80
                })
                chipBackgroundColor = ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, R.color.primaryGrayBackground))
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.white))
                applyEpisodeChipFeedback(this)
                typeface = Typeface.create(ResourcesCompat.getFont(context, R.font.google_sans), Typeface.BOLD)
                fontFeatureSettings = "'liga' 0, 'clig' 0, 'calt' 0, 'dlig' 0"
                isCheckable = false
                isClickable = true
                isFocusable = true
                // Chip handles DOWN/UP/CANCEL; do not set pressed state manually.
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
        val foreground = themeColor("textColor", Color.WHITE)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val episodeHeading = find("result_episodes_text") as? TextView
        list.addView(TextView(activity).apply {
            text = "${activity.getString(R.string.trailer)} (${items.size})"
            if (episodeHeading != null) {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, episodeHeading.textSize)
                typeface = episodeHeading.typeface
            } else {
                textSize = 17f
                typeface = Typeface.create(typeface, Typeface.NORMAL)
            }
            // The font can render "(2)" as ② through ligatures.
            fontFeatureSettings = "'liga' 0, 'clig' 0, 'calt' 0, 'dlig' 0"
            setTextColor(foreground)
            setPadding(dp(4), dp(8), 0, dp(4))
        })
        val scroll = ScrollView(activity)
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { index, entry ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(88)
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
                cardElevation = 0f
                addView(ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    loadImage("https://i.ytimg.com/vi/" + entry.youtubeId + "/hqdefault.jpg")
                }, FrameLayout.LayoutParams(dp(112), dp(63)))
                addView(TextView(activity).apply {
                    text = "▶"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xB3000000.toInt())
                    }
                }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
            }
            row.addView(thumbnail, LinearLayout.LayoutParams(dp(112), dp(63)))
            val labels = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(6), 0)
            }
            labels.addView(TextView(activity).apply {
                text = "${index + 1}. ${entry.title}"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(foreground)
            })
            labels.addView(TextView(activity).apply {
                text = (if (entry.type.equals("Trailer", true)) activity.getString(R.string.trailer) else entry.type) +
                    (localizedLanguage(entry.language)?.let { " · " + it } ?: "")
                textSize = 12f
                setTextColor(foreground)
            })
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rows.addView(row)
        }
        scroll.addView(rows)
        list.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(88) * items.size + dp(1), (activity.resources.displayMetrics.heightPixels * 0.6f).toInt())
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
                    finishPlayerHandoff?.invoke()
                    extractionJob?.cancel()
                    extractionJob = null
                    extractionProgress = null
                    opening = false
                    dialog = null
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

    // Keep the picker above the previous detail until the player can draw.
    // Failure recovery must also wait for a drawable result/home screen.
    private fun dismissPickerOnPlayerFrame(
        picker: BottomSheetDialog, controller: androidx.navigation.NavController
    ) {
        finishPlayerHandoff?.invoke()
        val manager = fragment.parentFragmentManager
        val owner = fragment.activity
        val handler = Handler(Looper.getMainLooper())
        var active = true
        var rollingBack = false
        var homeRecovery = false
        var recoveryAttempts = 0
        var recoveryStarted = 0L
        var watchedView: View? = null
        var releaseWatcher: (() -> Unit)? = null
        lateinit var callback: FragmentManager.FragmentLifecycleCallbacks
        lateinit var timeout: Runnable
        lateinit var returnTimeout: Runnable
        lateinit var release: () -> Unit
        lateinit var dismiss: () -> Unit

        fun currentView(): View? = owner?.let { stremioCurrentDestinationView(it, controller) }
        fun safeToReveal(view: View): Boolean = owner != null &&
            currentView() === view && stremioDestinationReady(owner, view) &&
            (if (rollingBack) controller.currentDestination?.id != R.id.navigation_player
             else controller.currentDestination?.id == R.id.navigation_player)

        val ownerObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dismiss()
        }
        release = {
            if (active) {
                active = false
                releaseWatcher?.invoke()
                releaseWatcher = null
                manager.unregisterFragmentLifecycleCallbacks(callback)
                handler.removeCallbacks(timeout)
                handler.removeCallbacks(returnTimeout)
                owner?.lifecycle?.removeObserver(ownerObserver)
                if (finishPlayerHandoff === release) finishPlayerHandoff = null
            }
        }
        dismiss = {
            release()
            if (picker.isShowing) picker.dismiss()
        }

        fun watch(view: View) {
            if (!active || !view.isAttachedToWindow || watchedView === view) return
            releaseWatcher?.invoke()
            val observer = view.viewTreeObserver
            if (!observer.isAlive) return
            watchedView = view
            var stable = 0
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            lateinit var unwatch: () -> Unit
            unwatch = {
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
                if (releaseWatcher === unwatch) releaseWatcher = null
                if (watchedView === view) watchedView = null
            }
            listener = ViewTreeObserver.OnPreDrawListener {
                if (!active || currentView() !== view) {
                    unwatch()
                } else {
                    stable = if (safeToReveal(view)) stable + 1 else 0
                    if (stable >= 2) {
                        unwatch()
                        view.postOnAnimation {
                            if (active && safeToReveal(view)) dismiss()
                        }
                    } else view.postInvalidateOnAnimation()
                }
                true
            }
            releaseWatcher = unwatch
            observer.addOnPreDrawListener(listener)
            view.postInvalidateOnAnimation()
        }

        fun recoverHome(): Boolean {
            if (homeRecovery || owner?.isFinishing == true || owner?.isDestroyed == true ||
                owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true)
                return false
            homeRecovery = true
            val moved = runCatching {
                controller.navigate(R.id.global_to_navigation_home)
                controller.currentDestination?.id == R.id.navigation_home
            }.getOrDefault(false)
            if (!moved) homeRecovery = false
            return moved
        }

        fun attemptReturn() {
            if (!active || owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true)
                return
            if (controller.currentDestination?.id == R.id.navigation_player) {
                val popped = runCatching { controller.popBackStack() }.getOrDefault(false)
                if (!popped || controller.currentDestination?.id == R.id.navigation_player)
                    recoveryAttempts++
                // Repeated failed pops must not dismiss the cover over a blank player.
                if (recoveryAttempts >= 2) recoverHome()
            }
            currentView()?.let(::watch)
        }

        callback = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(
                fm: FragmentManager, f: Fragment, view: View, savedInstanceState: Bundle?
            ) {
                view.doOnPreDraw {
                    if (active && currentView() === view) watch(view)
                }
            }
        }
        returnTimeout = object : Runnable {
            override fun run() {
                if (!active) return
                val current = currentView()
                if (current != null) watch(current)
                if (rollingBack) {
                    if (controller.currentDestination?.id == R.id.navigation_player) attemptReturn()
                    else if (!homeRecovery &&
                        android.os.SystemClock.uptimeMillis() - recoveryStarted >= 6000L) recoverHome()
                }
                // Only the observed destination's stable frames may dismiss the picker.
                handler.postDelayed(this, 1000L)
            }
        }
        timeout = Runnable {
            if (active) {
                rollingBack = true
                recoveryStarted = android.os.SystemClock.uptimeMillis()
                attemptReturn()
                handler.postDelayed(returnTimeout, 1000L)
            }
        }
        finishPlayerHandoff = release
        manager.registerFragmentLifecycleCallbacks(callback, false)
        owner?.lifecycle?.addObserver(ownerObserver)
        handler.postDelayed(timeout, 6000L)
    }

    private fun play(entry: TrailerChoice, scrollY: Int, focusedRow: Int) {
        if (opening) return
        val picker = dialog?.takeIf { it.isShowing } ?: return
        opening = true
        extractionProgress?.visibility = View.VISIBLE
        extractionJob = scope.launch {
            try {
                val (playable, subtitles) = extractTrailerMedia(entry.youtubeId)
                if (!fragment.isResumed || fragment.view !== root || dialog !== picker || !picker.isShowing)
                    return@launch
                if (playable.isEmpty()) {
                    toast(fragment.requireContext().getString(R.string.no_links_found_toast))
                    return@launch
                }
                // Pass every MP4/HLS/DASH quality and track-bearing DASH MPD to the host player.
                val selectedIndex = items.indexOf(entry).takeIf { it >= 0 } ?: return@launch
                val heading = title?.text?.toString()?.trim().orEmpty()
                    .ifBlank { root.context.getString(R.string.trailer) }
                val controller = runCatching { fragment.findNavController() }.getOrNull()
                    ?: return@launch
                val returnEntry = controller.currentBackStackEntry ?: return@launch
                val playerArgs = GeneratorPlayer.newInstance(
                    TrailerPlaybackGenerator(items.toList(), heading, selectedIndex, playable, subtitles),
                    selectedIndex
                )
                val saved = returnEntry.savedStateHandle
                saved.set(returnKey, detailUrl)
                saved.set(returnScrollKey, scrollY)
                saved.set(returnFocusKey, focusedRow)
                try {
                    dismissPickerOnPlayerFrame(picker, controller)
                    controller.navigate(R.id.global_to_navigation_player, playerArgs)
                } catch (error: Exception) {
                    finishPlayerHandoff?.invoke()
                    saved.remove<String>(returnKey)
                    saved.remove<Int>(returnScrollKey)
                    saved.remove<Int>(returnFocusKey)
                    throw error // The original picker is still visible.
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("StremioCTrailers", "Trailer playback failed", error)
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
        fragment.lifecycle.removeObserver(resumeObserver)
        title?.removeTextChangedListener(titleWatcher)
        if (isTv) {
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(tvPreDraw)
        }
        // Navigation is transferring visibility to the player; its one-shot
        // callback owns dialog dismissal until the next drawable frame.
        extractionJob?.cancel()
        extractionJob = null
        if (finishPlayerHandoff == null) dialog?.dismiss()
        dialog = null
        (button?.parent as? ViewGroup)?.removeView(button)
        button = null
        items = emptyList()
        scope.cancel()
    }
}

/**
 * Player-only playlist. None of these trailers become StremioC seasons or episodes.
 * ResultEpisode metadata enables CloudStream's built-in title, episode list and next controls.
 */
internal class TrailerPlaybackGenerator(
    private val trailers: List<TrailerChoice>,
    heading: String,
    selectedIndex: Int,
    selectedLinks: List<ExtractorLink>,
    selectedSubtitles: List<SubtitleData>
) : VideoGenerator<ResultEpisode>(
    trailers.mapIndexed { index, item ->
        ResultEpisode(
            headerName = heading,
            name = item.title,
            poster = "https://i.ytimg.com/vi/${item.youtubeId}/hqdefault.jpg",
            episode = index + 1,
            seasonIndex = null,
            season = null,
            data = "https://www.youtube.com/watch?v=${item.youtubeId}",
            apiName = "StremioC",
            id = -(("stremioc-trailer:" + item.youtubeId).hashCode() and Int.MAX_VALUE) - 1,
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
        val (links, subtitles) = cache[offset] ?: extractTrailerMedia(trailer.youtubeId)
            .also { cache[offset] = it }

        links.filter { it.type in sourceTypes }.forEach { callback(it to null) }
        subtitles.forEach(subtitleCallback)
        return links.any { it.type in sourceTypes }
    }
}
