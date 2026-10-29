package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.utils.SubtitleHelper

/**
 * Localize plugin subtitle groups before the native picker builds its originalName groups.
 * Keep each URL, headers and language code; the native picker still owns selection/options.
 */
internal class SubtitleLanguageNormalizer(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var removeObserver: (() -> Unit)? = null
    private val dialogField = runCatching {
        FullScreenPlayer::class.java.getDeclaredField("selectSourceDialog")
            .apply { isAccessible = true }
    }.getOrNull()

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: android.view.View, saved: Bundle?
        ) = watch(f)

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) = watch(f)
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) = detach(f)
    }

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
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
        if (candidate !is GeneratorPlayer || !candidate.isAdded ||
            candidate.view == null || candidate === fragment) return
        detach()
        fragment = candidate
        val vm = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
        model = vm
        val observer = Observer<VideoLive<Set<SubtitleData>>> { event ->
            if (event != null && event.instance == vm.state.instance &&
                fragment === candidate) normalize(vm)
        }
        vm.currentSubtitles.observe(candidate.viewLifecycleOwner, observer)
        removeObserver = { vm.currentSubtitles.removeObserver(observer) }
        normalize(vm)
    }

    private fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        removeObserver?.invoke()
        removeObserver = null
        fragment = null
        model = null
    }

    private fun stremioRanks(): Map<*, *>? = runCatching {
        val method = APIHolder::class.java.methods.firstOrNull { it.name == "getAllProviders" }
        val providers = method?.invoke(APIHolder) as? Iterable<*>
        val provider = providers?.firstOrNull {
            it?.javaClass?.name == "com.hsp1020.StremioC"
        } ?: return@runCatching null
        val field = provider.javaClass.getDeclaredField("subtitleOrderByUrl")
            .apply { isAccessible = true }
        field.get(null) as? Map<*, *>
    }.getOrNull()

    private fun localized(sub: SubtitleData, ranks: Map<*, *>?): SubtitleData {
        // ms-ind is used for Indonesian next to a separate ms (Malay) track.
        // Do not treat unrecognized language tags as generic Malay.
        val code = if (sub.languageCode.equals("ms-ind", ignoreCase = true)) "id"
            else sub.languageCode
        if (sub.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) {
            val label = SubtitleHelper.fromTagToLanguageName(code)
                ?: sub.originalName.substringBefore(" (").ifBlank { "Unknown" }
            return if (sub.originalName == label && sub.languageCode == code) sub
                else sub.copy(originalName = label, languageCode = code)
        }
        if (sub.origin != SubtitleOrigin.URL) return sub

        // StremioC's own stream/subtitle and catalog addon labels stay detailed.
        // Group 0 is ranked 1000..9999; CineStream/StreamPlay integrations are >=10000.
        val rank = (ranks?.get(sub.url) as? Number)?.toInt()
        if (rank != null && rank in 1000..9999) return sub
        if (rank == null && (sub.originalName.contains(" · ") ||
                sub.originalName.contains(" — "))) return sub

        val sourceName = sub.originalName.trim()
        if (sourceName.isEmpty() && code.isNullOrBlank()) {
            return sub.copy(originalName = "Unknown")
        }
        val stremio = sourceName.endsWith(" (Stremio)", ignoreCase = true)
        val languageName = if (stremio)
            sourceName.dropLast(" (Stremio)".length).trim() else sourceName
        val tag = SubtitleHelper.fromCodeToLangTagIETF(code)
            ?: SubtitleHelper.fromLanguageToTagIETF(code)
            ?: SubtitleHelper.fromCodeToLangTagIETF(languageName)
            ?: SubtitleHelper.fromLanguageToTagIETF(languageName)
            ?: SubtitleHelper.fromLanguageToTagIETF(languageName, halfMatch = true)
            ?: return sub
        val displayLanguage = SubtitleHelper.fromTagToLanguageName(tag) ?: return sub
        val displayed = displayLanguage + if (stremio) " (Stremio)" else ""
        if (displayed == sub.originalName && code == sub.languageCode) return sub

        // Native right-hand options keep the original track label when it held detail.
        val genericName = SubtitleHelper.fromTagToEnglishLanguageName(tag)
        val rawPlain = languageName.equals(genericName, true) ||
            languageName.equals(displayLanguage, true) ||
            languageName.equals(tag, true)
        val details = if (rawPlain) sub.nameSuffix else
            languageName + if (sub.nameSuffix.isBlank()) "" else " · " + sub.nameSuffix
        return sub.copy(originalName = displayed, nameSuffix = details, languageCode = tag)
    }

    private fun isPreviewVtt(sub: SubtitleData): Boolean =
        sub.origin == SubtitleOrigin.URL &&
            sub.url.substringBefore('?').substringBefore('#').lowercase()
                .endsWith("/preview/thumbnails.vtt")

    private fun normalize(vm: PlayerGeneratorViewModel) {
        val f = fragment ?: return
        if (runCatching { (dialogField?.get(f) as? Dialog)?.isShowing == true }
                .getOrDefault(false)) return
        val state = vm.state
        if (state.subtitles.isEmpty()) return
        val ranks = stremioRanks()
        val embeddedLabel = f.getString(R.string.subtitles_from_embedded)
        val normalized = state.subtitles.filterNot(::isPreviewVtt)
            .map { localized(it, ranks) }

        // Only groups containing embedded tracks need explicit option numbers.
        // The native picker orders by nameSuffix.toIntOrNull() ?: 0, so the
        // embedded "1 (내장 자막)" option comes before numeric online options.
        val replacements = HashMap<SubtitleData, SubtitleData>()
        normalized.groupBy { it.originalName }.values.forEach { group ->
            val embedded = group.filter { it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO }
                .sortedBy { it.url }
            if (embedded.isEmpty()) return@forEach
            embedded.forEachIndexed { index, sub ->
                val originalSuffix = sub.nameSuffix
                val markedSuffix = Regex("""^\\d+ \\([^)]*\\)$""").matches(originalSuffix)
                val detail = if (markedSuffix) {
                    originalSuffix.substringAfter(" · ", "").removeSuffix(")")
                } else originalSuffix
                val suffix = "${index + 1} ($embeddedLabel" +
                    if (detail.isNotBlank()) " · $detail)" else ")"
                if (sub.nameSuffix != suffix) replacements[sub] = sub.copy(nameSuffix = suffix)
            }
            group.filter { it.origin == SubtitleOrigin.URL }
                .sortedWith(compareBy<SubtitleData> { it.nameSuffix }.thenBy { it.url })
                .forEachIndexed { index, sub ->
                    val suffix = (embedded.size + index + 1).toString()
                    if (sub.nameSuffix != suffix) replacements[sub] = sub.copy(nameSuffix = suffix)
                }
        }
        val changed = normalized.map { replacements[it] ?: it }
        if (changed.toSet() == state.subtitles) return
        vm.modifyState {
            if (instance != state.instance) this else set(changed)
        }
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
