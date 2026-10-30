package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.HeaderViewListAdapter
import android.widget.ListView
import android.widget.TextView
import com.lagradost.cloudstream3.utils.AppContextUtils.sortSubs
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
    private var focusTree: ViewTreeObserver? = null
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        fragment?.view?.post { inspectSingleOptionDialog() }
    }
    private var singleDialog: Dialog? = null
    private var singleGroups: ListView? = null
    private var singleOptions: ListView? = null
    private var singleFooter: View? = null
    private var originalGroupClick: AdapterView.OnItemClickListener? = null
    private var singleGroupClick: AdapterView.OnItemClickListener? = null
    private var pendingDialogView: android.view.View? = null
    private val onDialogDetached = object : android.view.View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: android.view.View) = Unit
        override fun onViewDetachedFromWindow(view: android.view.View) {
            if (pendingDialogView !== view) return
            view.removeOnAttachStateChangeListener(this)
            pendingDialogView = null
            clearSingleOptionDialog()
            val f = fragment ?: return
            val vm = model ?: return
            f.view?.post {
                if (fragment === f && model === vm) normalize(vm)
            }
        }
    }
    private val dialogField = runCatching {
        FullScreenPlayer::class.java.getDeclaredField("selectSourceDialog")
            .apply { isAccessible = true }
    }.getOrNull()

    private fun clearSingleOptionDialog() {
        val list = singleGroups
        if (list?.onItemClickListener === singleGroupClick) {
            list?.onItemClickListener = originalGroupClick
        }
        singleFooter?.let { singleOptions?.removeFooterView(it) }
        singleDialog = null
        singleGroups = null
        singleOptions = null
        singleFooter = null
        originalGroupClick = null
        singleGroupClick = null
    }

    private fun stremioSubtitleDetail(url: String): String? = runCatching {
        val method = APIHolder::class.java.methods.firstOrNull { it.name == "getAllProviders" }
        val provider = (method?.invoke(APIHolder) as? Iterable<*>)?.firstOrNull {
            it?.javaClass?.name == "com.hsp1020.StremioC"
        } ?: return@runCatching null
        val field = provider.javaClass.getDeclaredField("subtitleDetailByUrl")
            .apply { isAccessible = true }
        (field.get(null) as? Map<*, *>)?.get(url) as? String
    }.getOrNull()

    private fun inspectSingleOptionDialog() {
        val f = fragment ?: return
        val dialog = runCatching { dialogField?.get(f) as? Dialog }.getOrNull()
            ?.takeIf { it.isShowing } ?: return
        dialog.window?.decorView?.let { decor ->
            if (pendingDialogView !== decor) {
                pendingDialogView?.removeOnAttachStateChangeListener(onDialogDetached)
                pendingDialogView = decor
                decor.addOnAttachStateChangeListener(onDialogDetached)
            }
        }
        if (singleDialog === dialog) return
        val vm = model ?: return
        val list = dialog.findViewById<ListView>(R.id.sort_subtitles) ?: return
        val options = dialog.findViewById<ListView>(R.id.sort_subtitles_options) ?: return
        val originalClick = list.onItemClickListener ?: return
        if (options.adapter == null) return
        clearSingleOptionDialog()
        singleDialog = dialog
        singleGroups = list
        singleOptions = options
        originalGroupClick = originalClick

        val click = AdapterView.OnItemClickListener { parent, view, position, id ->
            originalClick.onItemClick(parent, view, position, id)
            list.post { if (singleDialog === dialog && dialog.isShowing) updateSingleOption(vm) }
        }
        singleGroupClick = click
        list.onItemClickListener = click
        updateSingleOption(vm)
    }

    private fun updateSingleOption(vm: PlayerGeneratorViewModel) {
        val dialog = singleDialog?.takeIf { it.isShowing } ?: return
        val list = singleGroups ?: return
        val options = singleOptions ?: return
        val groups = sortSubs(vm.state.subtitles).groupBy { it.originalName }.values.toList()
        val displayed = (list.adapter as? HeaderViewListAdapter)?.wrappedAdapter ?: list.adapter
        val order = (list.getTag(R.id.sort_subtitles) as? IntArray)
            ?.takeIf { it.size == groups.size && it.toSet().size == groups.size }
        val position = list.checkedItemPosition - 1
        val index = if (order != null && position in order.indices) order[position] else position
        val item = if (displayed?.count == groups.size + 1) {
            groups.getOrNull(index)?.singleOrNull()
        } else null

        if (item == null) {
            singleFooter?.let { options.removeFooterView(it) }
            singleFooter = null
            return
        }
        if (singleFooter == null) {
            if (options.adapter?.count != 0) return
            val footer = android.view.LayoutInflater.from(dialog.context)
                .inflate(R.layout.sort_bottom_single_choice, options, false) as? TextView ?: return
            options.addFooterView(footer, null, true)
            singleFooter = footer
        }
        val detail = stremioSubtitleDetail(item.url)?.takeIf { it.isNotBlank() }
        val label = item.nameSuffix.ifBlank { "1" }
        (singleFooter as? TextView)?.text = if (detail != null) "$label($detail)" else label
        options.visibility = View.VISIBLE
        options.setItemChecked(0, true)
    }

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
        candidate.activity?.window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.let {
            focusTree = it
            it.addOnWindowFocusChangeListener(focusListener)
        }
        normalize(vm)
    }

    private fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        pendingDialogView?.removeOnAttachStateChangeListener(onDialogDetached)
        pendingDialogView = null
        clearSingleOptionDialog()
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree = null
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
                ?: sub.originalName.substringBefore(" (")
            return if (sub.originalName == label && sub.languageCode == code) sub
                else sub.copy(originalName = label, languageCode = code)
        }
        if (sub.origin != SubtitleOrigin.URL) return sub

        // StremioC preserves subtitle detail metadata separately from the language group.
        // Rank ranges 1000/10000/20000 refer to registered StremioC/CineStream/StreamPlay addons.
        val rank = (ranks?.get(sub.url) as? Number)?.toInt()
        val integrationSubtitle = rank != null && rank >= 10_000

        val sourceName = sub.originalName.trim()
        val stremio = sourceName.endsWith(" (Stremio)", ignoreCase = true)
        val languageName = (if (stremio)
            sourceName.dropLast(" (Stremio)".length).trim() else sourceName)
            .substringBefore(" · ").substringBefore(" — ").trim()
        val exactLanguage = languageName.substringBefore(" (").trim()
        val tag = SubtitleHelper.fromCodeToLangTagIETF(exactLanguage)
            ?: SubtitleHelper.fromLanguageToTagIETF(exactLanguage)
            ?: (if (languageName == sourceName)
                SubtitleHelper.fromCodeToLangTagIETF(code)
                    ?: SubtitleHelper.fromLanguageToTagIETF(code) else null)
            ?: return sub
        val displayLanguage = SubtitleHelper.fromTagToLanguageName(tag) ?: return sub
        val displayed = displayLanguage + if (stremio && !integrationSubtitle) " (Stremio)" else ""
        if (displayed == sub.originalName && code == sub.languageCode) return sub

        // Native right-hand options keep the original track label when it held detail.
        val details = sourceName.substringAfter(" · ", "").ifBlank { sub.nameSuffix }
        return sub.copy(originalName = displayed, nameSuffix = details, languageCode = tag)
    }

    private fun normalize(vm: PlayerGeneratorViewModel) {
        val f = fragment ?: return
        val dialog = runCatching { dialogField?.get(f) as? Dialog }.getOrNull()
        if (dialog?.isShowing == true) {
            inspectSingleOptionDialog()
            return
        }
        pendingDialogView?.removeOnAttachStateChangeListener(onDialogDetached)
        pendingDialogView = null
        val state = vm.state
        if (state.subtitles.isEmpty()) return
        val ranks = stremioRanks()
        val embeddedLabel = f.getString(R.string.subtitles_from_embedded)
        val normalized = state.subtitles.map { localized(it, ranks) }
            .filter {
                val language = it.originalName.substringBefore(" · ").substringBefore(" — ").trim()
                language.isNotEmpty() && !language.equals("Unknown", ignoreCase = true) &&
                    !language.equals("und", ignoreCase = true)
            }
            .distinctBy { if (it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) it.origin to it.url else it }

        // Only groups containing embedded tracks need explicit option numbers.
        // The native picker orders by nameSuffix.toIntOrNull() ?: 0, so the
        // embedded "1 (내장 자막)" option comes before numeric online options.
        val replacements = HashMap<SubtitleData, SubtitleData>()
        val embeddedPrefix = Regex("""^\d+ \(${Regex.escape(embeddedLabel)}(?: · )?""")
        normalized.groupBy { it.originalName }.values.forEach { group ->
            val embedded = group.filter { it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO }
                .sortedWith(compareBy<SubtitleData> { it.url.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.url })
            // Renumber previously numbered online options when embedded tracks disappear.
            // Preserve descriptive labels in groups that were never numbered here.
            if (embedded.isEmpty() &&
                group.none { it.origin == SubtitleOrigin.URL && ranks?.containsKey(it.url) == true } &&
                group.any {
                    it.origin != SubtitleOrigin.URL || it.nameSuffix.toIntOrNull() == null
                }) return@forEach
            embedded.forEachIndexed { index, sub ->
                var detail = sub.nameSuffix
                // Unwrap previously generated labels, including names with nested parentheses.
                while (true) {
                    val prefix = embeddedPrefix.find(detail) ?: break
                    detail = detail.substring(prefix.value.length).removeSuffix(")")
                }
                val suffix = "${index + 1} ($embeddedLabel" +
                    if (detail.isNotBlank()) " · $detail)" else ")"
                if (sub.nameSuffix != suffix) replacements[sub] = sub.copy(nameSuffix = suffix)
            }
            group.filter { it.origin == SubtitleOrigin.URL }
                .sortedWith(compareBy<SubtitleData> {
                    (ranks?.get(it.url) as? Number)?.toInt() ?: Int.MAX_VALUE
                }.thenBy { it.nameSuffix.toIntOrNull() ?: Int.MAX_VALUE })
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
