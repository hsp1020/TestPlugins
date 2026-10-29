package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Spanned
import android.text.SpannedString
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ArrayAdapter
import android.widget.HeaderViewListAdapter
import android.widget.ListView
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.ui.subtitles.SUBTITLE_AUTO_SELECT_KEY
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.PREFERENCES_NAME
import com.lagradost.cloudstream3.utils.AppContextUtils.sortSubs
import java.lang.reflect.Proxy

@OptIn(UnstableApi::class)
internal class EmbeddedSubtitleController(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var focusTree: ViewTreeObserver? = null
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        fragment?.view?.post { inspectDialogs() }
    }
    private var removeObserver: (() -> Unit)? = null
    private var player: Media3Player? = null
    private var instance: Int? = null
    private var linkUrl: String? = null
    private var previousEmbedded = emptySet<SubtitleData>()
    private var lastHandledSubtitles: Set<SubtitleData>? = null
    private var lastHandledInstance: Int? = null
    private var manualOverride = false
    private var selectionDialog: Dialog? = null
    private var selectionBeforeDialog: SubtitleData? = null
    private var labeledDialog: Dialog? = null
    private var pendingDialog: Dialog? = null
    private var settings: SharedPreferences? = null
    private var preferredLanguage: String? = null
    private val trackIdPrefix = Regex("^\\d+:")
    private val extraCaptionRegex = Regex(
        """（[^（）]*）[ \t]*|^[ \t]*[♬♪♫~～]+[ \t]*(?:\r?\n|$)|[⸨⸩≪≫➡⚟《》＜＞｟｠📱🔊]""",
        RegexOption.MULTILINE
    )
    private val captionOutputField = runCatching {
        SubtitleView::class.java.getDeclaredField("output").apply { isAccessible = true }
    }.getOrNull()
    private val captionCuesField = runCatching {
        SubtitleView::class.java.getDeclaredField("cues").apply { isAccessible = true }
    }.getOrNull()
    private var captionView: SubtitleView? = null
    private var captionNativeOutput: Any? = null
    private var captionWrappedOutput: Any? = null
    private var captionFailedView: SubtitleView? = null
    private var captionLayoutTree: ViewTreeObserver? = null
    private val captionLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (cleanExtraCaptions) fragment?.let(::attachCaptionOutput)
    }
    private var cleanExtraCaptions = false
    private val captionStyleListener: (SaveCaptionStyle) -> Unit = { style ->
        cleanExtraCaptions = style.removeCaptions
        monitorCaptionLayouts()
        if (cleanExtraCaptions) {
            fragment?.let(::attachCaptionOutput)
            refreshCaptionOutput()
        } else {
            restoreCaptionOutput()
            captionFailedView = null
        }
    }

    private val dialogField = runCatching {
        FullScreenPlayer::class.java.getDeclaredField("selectSourceDialog")
            .apply { isAccessible = true }
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

    private val listener = java.lang.reflect.Proxy.newProxyInstance(
        Media3Player.Listener::class.java.classLoader,
        arrayOf(Media3Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onTracksChanged" -> {
                synchronizeSource()
                (args?.getOrNull(0) as? Tracks)?.let(::selectEmbedded)
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ElevateEmbeddedSubtitleListener"
            else -> null
        }
    } as Media3Player.Listener

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SUBTITLE_AUTO_SELECT_KEY) {
            preferredLanguage = readPreferredLanguage()
            val dialog = runCatching { dialogField?.get(fragment) as? Dialog }.getOrNull()
            if (dialog?.isShowing == true) {
                manualOverride = true
            } else {
                manualOverride = false
                player?.currentTracks?.let(::selectEmbedded)
            }
        }
    }

    private fun readPreferredLanguage(): String? =
        runCatching { settings?.getString(SUBTITLE_AUTO_SELECT_KEY, null) }
            .getOrNull()?.trim()?.removeSurrounding("\"")
            ?.takeIf { it.isEmpty() || it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) }

    private fun currentLinkUrl(f: GeneratorPlayer): String? =
        runCatching { (linkField?.get(f.player) as? ExtractorLink)?.url }.getOrNull()

    private fun selectedByHost(f: GeneratorPlayer): SubtitleData? =
        runCatching { selectedField?.get(f) as? SubtitleData }.getOrNull()

    private fun trackDialog(f: GeneratorPlayer) {
        val shown = runCatching { dialogField?.get(f) as? Dialog }.getOrNull()
            ?.takeIf { it.isShowing }
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

    private fun discardPreviousEmbedded() {
        val stale = previousEmbedded
        previousEmbedded = emptySet()
        if (stale.isEmpty()) return
        model?.let { vm ->
            if (vm.state.subtitles.any { it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO && it in stale }) {
                vm.modifyState {
                    set(subtitles.filterNot {
                        it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO && it in stale
                    })
                }
            }
        }
        runCatching {
            val helper = helperField?.get(fragment?.player) as? PlayerSubtitleHelper
                ?: return@runCatching
            @Suppress("UNCHECKED_CAST")
            val active = activeField?.get(helper) as? Set<SubtitleData>
                ?: return@runCatching
            if (active.any { it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO && it in stale }) {
                helper.setActiveSubtitles(active.filterNot {
                    it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO && it in stale
                }.toSet())
            }
        }
    }


    /** Run once per emitted cue batch, after CloudStream's native ASCII caption filtering. */
    private fun cleanCaptionCues(cues: List<Cue>): List<Cue> {
        var cleaned: ArrayList<Cue>? = null
        cues.forEachIndexed { index, cue ->
            val original = cue.text
            val matches = if (cue.bitmap == null && original != null) {
                extraCaptionRegex.findAll(original).toList()
            } else emptyList()
            if (matches.isEmpty()) {
                cleaned?.add(cue)
                return@forEachIndexed
            }
            val result = SpannableStringBuilder(original ?: return@forEachIndexed)
            for (match in matches.asReversed()) {
                result.delete(match.range.first, match.range.last + 1)
            }
            val output = cleaned ?: ArrayList<Cue>(cues.size).also {
                it.addAll(cues.subList(0, index))
                cleaned = it
            }
            if (result.isNotBlank()) output.add(cue.buildUpon().setText(result).build())
        }
        return cleaned ?: cues
    }

    /** Only monitor layouts when both caption removal and Embedded Subtitle Management are on. */
    private fun monitorCaptionLayouts() {
        val next = if (cleanExtraCaptions) {
            host?.window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }
        } else null
        if (next === captionLayoutTree) return
        captionLayoutTree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(captionLayoutListener)
        captionLayoutTree = next
        next?.addOnGlobalLayoutListener(captionLayoutListener)
    }

    private fun captionHookFailed(view: SubtitleView) {
        captionFailedView = view
    }

    /** Intercept Media3's existing subtitle output; never add a renderer or modify playback. */
    private fun attachCaptionOutput(f: GeneratorPlayer) {
        if (!cleanExtraCaptions) return
        val nativeView = f.subView ?: return
        val holder = nativeView.parent as? ViewGroup
        // The refreshed subtitle inherits native motion as a nested SubtitleView.
        val nested = (nativeView.childCount - 1 downTo 0).firstNotNullOfOrNull { index ->
            (nativeView.getChildAt(index) as? SubtitleView)?.takeIf { it.alpha > 0f }
        }
        val view = nested ?: (holder?.let { parent ->
            (parent.childCount - 1 downTo 0).firstNotNullOfOrNull { index ->
                (parent.getChildAt(index) as? SubtitleView)
                    ?.takeIf { it !== nativeView && it.alpha > 0f }
            }
        }) ?: nativeView
        if (captionFailedView === view) return
        val field = captionOutputField ?: return
        val currentOutput = runCatching { field.get(view) }.getOrElse {
            captionHookFailed(view)
            return
        } ?: return
        if (captionView === view && currentOutput === captionWrappedOutput) return
        restoreCaptionOutput()
        val update = runCatching {
            field.type.declaredMethods.first { it.name == "update" }.apply { isAccessible = true }
        }.getOrElse {
            captionHookFailed(view)
            return
        }
        val wrapper = runCatching {
            Proxy.newProxyInstance(field.type.classLoader, arrayOf(field.type)) { proxy, method, args ->
                when (method.name) {
                    "update" -> {
                        val parameters = args ?: return@newProxyInstance null
                        if (cleanExtraCaptions) {
                            @Suppress("UNCHECKED_CAST")
                            val cues = parameters.getOrNull(0) as? List<Cue>
                            if (cues != null) {
                                parameters[0] = cleanCaptionCues(cues)
                            }
                        }
                        runCatching { update.invoke(currentOutput, *parameters) }.getOrElse {
                            captionHookFailed(view)
                            view.post { if (captionView === view) restoreCaptionOutput() }
                            null
                        }
                    }
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "ElevateCaptionOutput"
                    else -> method.invoke(currentOutput, *(args ?: emptyArray()))
                }
            }
        }.getOrElse {
            captionHookFailed(view)
            return
        }
        if (runCatching { field.set(view, wrapper) }.onFailure {
                captionHookFailed(view)
            }.isFailure) return
        captionView = view
        captionNativeOutput = currentOutput
        captionWrappedOutput = wrapper
        refreshCaptionOutput()
    }

    private fun refreshCaptionOutput() {
        val view = captionView ?: return
        @Suppress("UNCHECKED_CAST")
        val cues = runCatching { captionCuesField?.get(view) as? List<Cue> }.getOrElse {
            captionHookFailed(view)
            return
        } ?: return
        if (runCatching { view.setCues(cues) }.onFailure {
                captionHookFailed(view)
            }.isFailure) restoreCaptionOutput()
    }

    private fun restoreCaptionOutput() {
        val view = captionView
        val native = captionNativeOutput
        val wrapper = captionWrappedOutput
        captionView = null
        captionNativeOutput = null
        captionWrappedOutput = null
        if (view != null && native != null && wrapper != null) {
            runCatching {
                val field = captionOutputField ?: return@runCatching
                if (field.get(view) === wrapper) {
                    field.set(view, native)
                    @Suppress("UNCHECKED_CAST")
                    val cues = captionCuesField?.get(view) as? List<Cue>
                    if (cues != null) view.setCues(cues)
                }
            }.onFailure { captionHookFailed(view) }
        }
    }

    private fun synchronizeSource() {
        val f = fragment ?: return
        val nextInstance = model?.state?.instance ?: return
        val current = f.playerView?.player
        val nextUrl = currentLinkUrl(f)
        val changedInstance = instance != null && instance != nextInstance
        val changedUrl = linkUrl != null && nextUrl != null && linkUrl != nextUrl
        val changedPlayer = current != null && current !== player
        val changedUnknownSource = changedPlayer && player != null &&
            (nextUrl == null || linkUrl == null)
        val changedSource = changedInstance || changedUrl || changedUnknownSource

        instance = nextInstance
        if (nextUrl != null) linkUrl = nextUrl
        if (changedSource) {
            labeledDialog = null
            manualOverride = false
            selectionDialog = null
            selectionBeforeDialog = null
            discardPreviousEmbedded()
        }
        if (changedPlayer) {
            player?.removeListener(listener)
            player = current
            current?.addListener(listener)
        }
        if ((changedSource || changedPlayer) && current != null) selectEmbedded(current.currentTracks)
        attachCaptionOutput(f)
    }


    private fun labelEmbeddedPopup(dialog: Dialog): Boolean {
        val state = model?.state ?: return false
        val list = dialog.findViewById<ListView>(R.id.sort_subtitles) ?: return false
        val wrapper = list.adapter
        val adapter = (if (wrapper is HeaderViewListAdapter) wrapper.wrappedAdapter else wrapper)
            as? ArrayAdapter<*> ?: return false
        val grouped = sortSubs(state.subtitles).groupBy { it.originalName }.values.toList()
        if (adapter.count != grouped.size + 1) return false
        val texts = (0 until adapter.count).map { adapter.getItem(it) as? Spanned ?: return false }
        val order = (list.getTag(R.id.sort_subtitles) as? IntArray)
            ?.takeIf { it.size == grouped.size && it.toSet().size == grouped.size &&
                it.all { index -> index in grouped.indices } }
        val embeddedName = fragment?.context?.getString(R.string.subtitles_from_embedded)
            ?: return false
        val names = grouped.indices.map { row ->
            val group = grouped[order?.get(row) ?: row]
            if (group.isNotEmpty() && group.all { it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO }) {
                val originalName = group.first().originalName
                SpannedString(if (originalName.endsWith(" ($embeddedName)")) originalName else "$originalName ($embeddedName)")
            } else texts[row + 1]
        }
        if (names.indices.all { names[it] == texts[it + 1] }) return true
        @Suppress("UNCHECKED_CAST")
        val editable = adapter as ArrayAdapter<Spanned>
        editable.clear()
        editable.addAll(listOf(texts.first()) + names)
        return true
    }

    private fun inspectDialogs() {
        val f = fragment ?: return
        if (f.playerView?.player?.let { it !== player } == true) synchronizeSource()
        attachCaptionOutput(f)
        trackDialog(f)
        val popup = selectionDialog
        if (popup != null && popup !== labeledDialog && popup !== pendingDialog) {
            pendingDialog = popup
            f.view?.post {
                pendingDialog = null
                if (popup.isShowing && fragment === f && labelEmbeddedPopup(popup)) {
                    labeledDialog = popup
                }
            }
        }
    }

    private fun markActive(f: GeneratorPlayer, embedded: Set<SubtitleData>): Boolean =
        runCatching {
            val helper = helperField?.get(f.player) as? PlayerSubtitleHelper
                ?: return@runCatching false
            @Suppress("UNCHECKED_CAST")
            val existing = activeField?.get(helper) as? Set<SubtitleData>
                ?: return@runCatching false
            val missing = embedded.filterNot { it in existing }
            if (missing.isNotEmpty()) helper.setActiveSubtitles(existing + missing)
            true
        }.getOrDefault(false)

    private fun selectEmbedded(tracks: Tracks) {
        val f = fragment ?: return
        val current = player ?: return
        if (current !== f.playerView?.player || instance != model?.state?.instance) return
        val subtitles = model?.state?.subtitles ?: return

        val available = LinkedHashMap<String, Pair<Tracks.Group, Int>>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val id = group.getTrackFormat(i).id?.replace(trackIdPrefix, "") ?: continue
                available.putIfAbsent(id, group to i)
            }
        }
        if (available.isEmpty()) return
        val embedded = subtitles.filterTo(mutableSetOf()) {
            it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO && it.url in available
        }
        if (embedded.isEmpty()) return

        trackDialog(f)
        if (!markActive(f, embedded)) return
        if (manualOverride || selectionDialog?.isShowing == true) return
        val language = preferredLanguage?.takeIf { it.isNotEmpty() } ?: return
        val match = embedded.firstNotNullOfOrNull { subtitle ->
            if (!subtitle.matchesLanguageCode(language)) return@firstNotNullOfOrNull null
            available[subtitle.url]?.takeIf { (group, index) -> group.isTrackSupported(index) }
        } ?: return
        val (group, index) = match
        if (group.isTrackSelected(index)) return
        runCatching {
            current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                .build()
        }
    }

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, saved: Bundle?
        ) = watch(f)
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) = watch(f)
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) = detach(f)
    }

    fun start() {
        cleanExtraCaptions = SubtitlesFragment.getCurrentSavedStyle().removeCaptions
        SubtitlesFragment.applyStyleEvent += captionStyleListener
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        SubtitlesFragment.applyStyleEvent -= captionStyleListener
        application.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        detach()
        host = null
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        detach()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                if (f.view != null) watch(f)
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun watch(candidate: Fragment) {
        if (candidate !is GeneratorPlayer || !candidate.isAdded || candidate.view == null ||
            candidate === fragment) return
        detach()
        fragment = candidate
        model = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
        val subtitleObserver = Observer<VideoLive<Set<SubtitleData>>> { event ->
            if (event != null && event.instance == model?.state?.instance) {
                synchronizeSource()
                if (lastHandledInstance == event.instance &&
                    (lastHandledSubtitles === event.value ||
                        lastHandledSubtitles == event.value)) return@Observer
                lastHandledInstance = event.instance
                lastHandledSubtitles = event.value
                labeledDialog = null
                previousEmbedded = model?.state?.subtitles
                    ?.filterTo(mutableSetOf()) { it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO }
                    ?: emptySet()
                player?.currentTracks?.let(::selectEmbedded)
                inspectDialogs()
            }
        }
        settings = candidate.context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferredLanguage = readPreferredLanguage()
        settings?.registerOnSharedPreferenceChangeListener(settingsListener)
        model?.currentSubtitles?.observe(candidate.viewLifecycleOwner, subtitleObserver)
        val linksObserver = Observer<VideoLive<Set<com.lagradost.cloudstream3.ui.player.VideoLink>>> {
            synchronizeSource()
        }
        model?.currentLinks?.observe(candidate.viewLifecycleOwner, linksObserver)
        val loadingObserver = Observer<VideoLive<com.lagradost.cloudstream3.mvvm.Resource<Unit>>> {
            synchronizeSource()
        }
        model?.loadingLinks?.observe(candidate.viewLifecycleOwner, loadingObserver)
        removeObserver = {
            model?.currentSubtitles?.removeObserver(subtitleObserver)
            model?.currentLinks?.removeObserver(linksObserver)
            model?.loadingLinks?.removeObserver(loadingObserver)
        }
        candidate.activity?.window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.let {
            focusTree = it
            it.addOnWindowFocusChangeListener(focusListener)
        }
        synchronizeSource()
        monitorCaptionLayouts()
        candidate.view?.post { inspectDialogs() }
    }

    private fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        captionLayoutTree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(captionLayoutListener)
        captionLayoutTree = null
        restoreCaptionOutput()
        captionFailedView = null
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree = null
        player?.removeListener(listener)
        player = null
        settings?.unregisterOnSharedPreferenceChangeListener(settingsListener)
        settings = null
        removeObserver?.invoke()
        removeObserver = null
        fragment = null
        model = null
        instance = null
        linkUrl = null
        previousEmbedded = emptySet()
        lastHandledSubtitles = null
        lastHandledInstance = null
        manualOverride = false
        selectionDialog = null
        selectionBeforeDialog = null
        labeledDialog = null
        pendingDialog = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) {
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
            detach()
            host = null
        }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
