package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.text.Spanned
import android.view.View
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.HeaderViewListAdapter
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.utils.AppContextUtils.sortSubs
import com.lagradost.cloudstream3.utils.AppContextUtils.html
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
    private var singleOptionAdapter: ArrayAdapter<Spanned>? = null
    private var singleOptionItem: Spanned? = null
    private var originalColumns: Pair<LinearLayout.LayoutParams, LinearLayout.LayoutParams>? = null
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

    private fun clearSingleOption() {
        val adapter = singleOptionAdapter
        if (adapter?.count == 1 && adapter.getItem(0) === singleOptionItem) adapter.clear()
        singleOptionAdapter = null
        singleOptionItem = null
    }

    private fun clearSingleOptionDialog() {
        clearSingleOption()
        originalColumns?.let { (language, subtitles) ->
            singleGroups?.layoutParams = language
            singleOptions?.layoutParams = subtitles
        }
        originalColumns = null
        val list = singleGroups
        if (list?.onItemClickListener === singleGroupClick) {
            list?.onItemClickListener = originalGroupClick
        }
        singleDialog = null
        singleGroups = null
        singleOptions = null
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
        val languageParams = list.layoutParams as? LinearLayout.LayoutParams
        val optionParams = options.layoutParams as? LinearLayout.LayoutParams
        if (languageParams != null && optionParams != null) {
            originalColumns = languageParams to optionParams
            list.layoutParams = LinearLayout.LayoutParams(languageParams).apply {
                width = 0
                weight = 3.5f
            }
            options.layoutParams = LinearLayout.LayoutParams(optionParams).apply {
                width = 0
                weight = 6.5f
            }
        }

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
        val group = if (displayed?.count == groups.size + 1) {
            groups.getOrNull(index)
        } else null
        val item = group?.singleOrNull()

        if (item == null) {
            clearSingleOption()
            if (group != null) updateOptionLabels(dialog, group, options)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val source = (options.getTag(R.id.sort_subtitles_options) as? ArrayAdapter<Spanned>)
            ?: (options.adapter as? ArrayAdapter<Spanned>) ?: return
        val detail = if (item.origin == SubtitleOrigin.URL)
            stremioSubtitleDetail(item.url)?.takeIf { it.isNotBlank() } else null
        val text = if (item.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) {
            embeddedOptionLabel(dialog, item, 1)
        } else {
            val label = item.nameSuffix.ifBlank { "1" }
            if (detail != null) "$label($detail)" else label
        }
        if (source.count != 1 || source.getItem(0) !== singleOptionItem ||
            singleOptionItem.toString() != text) {
            clearSingleOption()
            if (source.count != 0) return
            val entry = text.html()
            source.add(entry)
            singleOptionAdapter = source
            singleOptionItem = entry
        }
        options.visibility = View.VISIBLE
        options.setItemChecked(0, true)
        (list.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
    }

    /**
     * The native picker owns subtitle selection. Number embedded options only in its
     * display adapter: changing SubtitleData.nameSuffix breaks the player's active-track
     * equality check and can trigger repeated player reloads.
     */
    private fun embeddedOptionLabel(dialog: Dialog, sub: SubtitleData, position: Int): String {
        val label = dialog.context.getString(R.string.subtitles_from_embedded)
        var detail = sub.nameSuffix
        val prefix = Regex("^\\d+ \\(" + Regex.escape(label) + "(?: · )?")
        while (true) {
            val match = prefix.find(detail) ?: break
            detail = detail.substring(match.value.length).removeSuffix(")")
        }
        return "$position ($label" + if (detail.isNotBlank()) " · $detail)" else ")"
    }

    private fun updateOptionLabels(
        dialog: Dialog, group: List<SubtitleData>, options: ListView
    ) {
        val ordered = group.sortedBy { it.nameSuffix.toIntOrNull() ?: 0 }
        if (ordered.size < 2 || group.none { it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO ||
                (it.origin == SubtitleOrigin.URL && stremioRanks()?.containsKey(it.url) == true) }) return
        @Suppress("UNCHECKED_CAST")
        val source = (options.getTag(R.id.sort_subtitles_options) as? ArrayAdapter<Spanned>)
            ?: (options.adapter as? ArrayAdapter<Spanned>) ?: return
        if (source.count != ordered.size) return
        val labels = ArrayList<Spanned>(ordered.size)
        ordered.forEachIndexed { index, sub ->
            val existing = source.getItem(index) ?: return
            labels.add(when (sub.origin) {
                SubtitleOrigin.EMBEDDED_IN_VIDEO -> embeddedOptionLabel(dialog, sub, index + 1).html()
                SubtitleOrigin.URL -> (index + 1).toString().html()
                else -> existing
            })
        }
        if (labels.indices.all { labels[it].toString() == source.getItem(it)?.toString() }) return
        source.setNotifyOnChange(false)
        source.clear()
        source.addAll(labels)
        source.notifyDataSetChanged()
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
        // Keep the player's original embedded track object (including its language code).
        // Media3 reports the same track again after reload; copied data is not equal to it.
        if (sub.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) return sub
        // ms-ind is used for Indonesian next to a separate ms (Malay) track.
        // Do not treat unrecognized language tags as generic Malay.
        val code = if (sub.languageCode.equals("ms-ind", ignoreCase = true)) "id"
            else sub.languageCode
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
        val normalized = state.subtitles.map { localized(it, ranks) }
            .filter {
                val language = it.originalName.substringBefore(" · ").substringBefore(" — ").trim()
                language.isNotEmpty() && !language.equals("Unknown", ignoreCase = true) &&
                    !language.equals("und", ignoreCase = true)
            }
            .distinctBy { if (it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) it.origin to it.url else it }

        // Native selection identifies choices by nameSuffix. Assign a unique
        // number only to newly added addon subtitles; never renumber existing
        // tracks, since nameSuffix is part of the Media3 track ID.
        val replacements = HashMap<SubtitleData, SubtitleData>()
        normalized.groupBy { it.originalName }.values.forEach { group ->
            val embeddedCount = group.count { it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO }
            if (embeddedCount == 0 && group.none {
                    it.origin == SubtitleOrigin.URL && ranks?.containsKey(it.url) == true
                }) return@forEach
            val used = group.filter { it.origin == SubtitleOrigin.URL }
                .mapNotNull { it.nameSuffix.toIntOrNull()?.takeIf { number -> number > 0 } }
                .toMutableSet()
            var nextNumber = embeddedCount + 1
            group.filter { it.origin == SubtitleOrigin.URL && it.nameSuffix.toIntOrNull() == null }
                .sortedWith(compareBy<SubtitleData> {
                    (ranks?.get(it.url) as? Number)?.toInt() ?: Int.MAX_VALUE
                }.thenBy { it.url })
                .forEach { sub ->
                    while (nextNumber in used) nextNumber++
                    replacements[sub] = sub.copy(nameSuffix = nextNumber.toString())
                    used.add(nextNumber++)
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
