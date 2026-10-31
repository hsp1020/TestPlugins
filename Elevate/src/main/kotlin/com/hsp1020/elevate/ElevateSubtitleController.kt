
package com.hsp1020.elevate

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

internal class ElevateSubtitleController(private val pluginResources: Resources) {
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
    private val refreshedOverlay = ElevateRefreshedSubtitleOverlay()
    private val refreshedCues = linkedMapOf<String, ElevateSubtitleRefreshRegistry.Prepared>()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private var autoRefreshUrl: String? = null
    private var autoSpinView: ImageButton? = null
    private var autoSpinAnimator: ObjectAnimator? = null
    private var observedPlayer: Player? = null
    private var selectedUrl: String? = null
    private var panelListener: ((GeneratorPlayer, String) -> Unit)? = null
    private var iconSelectionUrl: String? = null
    private data class DeferredRefresh(
        val entry: ElevateSubtitleRefreshRegistry.Entry, val automatic: Boolean,
        val instance: Int, val videoId: Int?
    )
    private var queuedRefresh: DeferredRefresh? = null
    private var appliedRefresh: DeferredRefresh? = null
    private var refreshedOffsetDialog: Dialog? = null
    private var removeStateObserver: (() -> Unit)? = null
    private var removeLinksObserver: (() -> Unit)? = null
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
            "onTracksChanged" -> {
                null
            }
            "onPlaybackStateChanged" -> {
                null
            }
            "onRenderedFirstFrame" -> {
                null
            }
            "onPlayerError" -> {
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ElevateSubtitlePlayerListener"
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

    private fun checkStremio(): Boolean = true

    private fun syncProviderObservers() {
        val f = fragment ?: return
        val vm = model ?: return
        if (!isStremio) {
            removeLinksObserver?.invoke()
            removeLinksObserver = null
            focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
            focusTree = null
            observedPlayer?.removeListener(playerListener)
            observedPlayer = null
            return
        }
        if (removeLinksObserver == null) {
            val observer = Observer<com.lagradost.cloudstream3.ui.player.VideoLive<Set<com.lagradost.cloudstream3.ui.player.VideoLink>>> {
                if (isStremio && vm.state.instance == lastInstance) bindPlayer()
            }
            vm.currentLinks.observe(f.viewLifecycleOwner, observer)
            removeLinksObserver = { vm.currentLinks.removeObserver(observer) }
        }
        if (focusTree == null) {
            f.activity?.window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.let {
                focusTree = it
                it.addOnWindowFocusChangeListener(focusListener)
            }
        }
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
            syncProviderObservers()
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
        val observer = Observer<com.lagradost.cloudstream3.ui.player.VideoLive<Resource<Unit>>> { event ->
            if (lastInstance != currentModel.state.instance) {
                clearRefreshState()
                lastInstance = currentModel.state.instance
                isStremio = checkStremio()
                sortedDialog = null
                syncProviderObservers()
            }
            if (isStremio) bindPlayer()
        }
        currentModel.loadingLinks.observe(candidate.viewLifecycleOwner, observer)
        removeStateObserver = { currentModel.loadingLinks.removeObserver(observer) }
        inspectDialogs()
    }

    private fun sortPopup(dialog: Dialog): Boolean {
        val state = model?.state ?: return false
        val list = dialog.findViewById<ListView>(R.id.sort_subtitles) ?: return false
        val wrapper = list.adapter
        val adapter = (if (wrapper is HeaderViewListAdapter) wrapper.wrappedAdapter else wrapper)
            as? ArrayAdapter<*> ?: return false

        val grouped = sortSubs(state.subtitles).groupBy { it.originalName }.values.toList()
        if (adapter.count != grouped.size + 1) return false
        val texts = (0 until adapter.count).map { adapter.getItem(it) as? Spanned ?: return false }
        // Prefer the native language code for embedded tracks; online labels may contain filenames.
        val langs = grouped.map { group ->
            val label = group.first().originalName.substringBefore(" · ").substringBefore(" — ").trim()
            group.firstOrNull { it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO }?.getIETF_tag()
                ?: SubtitleHelper.fromLanguageToTagIETF(label)
                ?: SubtitleHelper.fromLanguageToTagIETF(label, halfMatch = true)
                ?: label
        }
        val starts = langs.withIndex().groupBy({ it.value }, { it.index })
            .mapValues { (_, positions) -> positions.first() }
        val ranks = grouped.map { sameName ->
            sameName.minOf { sub ->
                when {
                    sub.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO -> -1
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
        val groups = sortSubs(current.subtitles).groupBy { it.originalName }.values.toList()
        val order = (list.getTag(R.id.sort_subtitles) as? IntArray)
            ?.takeIf { it.size == groups.size && it.toSet().size == groups.size }
            ?: groups.indices.toList().toIntArray()

        fun groupAt(position: Int): List<SubtitleData>? {
            if (position !in 1..groups.size) return null
            return groups.getOrNull(order[position - 1])
        }

        // Keep the native adapter and Apply button's option indices unchanged.
        // Only reorder visible rows, and translate interactions back to native indices.
        val nativeOptions = groups.map { it.sortedBy { sub -> sub.nameSuffix.toIntOrNull() ?: 0 } }
        val visibleOptions = nativeOptions.map { items ->
            items.indices.sortedWith(compareBy<Int> { index ->
                val sub = items[index]
                if (sub.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) -1 else 40_000
            }.thenBy { it })
        }
        fun optionOrder(): List<Int> {
            val checked = list.checkedItemPosition
            val index = order.getOrNull(checked - 1) ?: return emptyList()
            return visibleOptions.getOrNull(index) ?: emptyList()
        }
        fun optionAt(position: Int): SubtitleData? {
            val checked = list.checkedItemPosition
            val index = order.getOrNull(checked - 1) ?: return null
            return nativeOptions[index].getOrNull(optionOrder().getOrNull(position) ?: return null)
        }

        val parent = (list.adapter as? HeaderViewListAdapter)?.wrappedAdapter
            ?: list.adapter
        val adapter = parent as? ArrayAdapter<*> ?: return
        if (adapter.count != groups.size + 1) return
        val selected = list.checkedItemPosition
        list.adapter = RefreshRowAdapter(adapter, sourceAt = { position ->
            groupAt(position)?.singleOrNull()
                ?.takeUnless { options.visibility == View.VISIBLE && options.adapter?.count == 1 }
                ?.let { ElevateSubtitleRefreshRegistry.entryFor(it) }
        })
        if (selected >= 0) list.setItemChecked(selected, true)

        val optionsParent = (options.adapter as? HeaderViewListAdapter)?.wrappedAdapter
            ?: options.adapter
        val optionsAdapter = optionsParent as? ArrayAdapter<*> ?: return
        options.setTag(R.id.sort_subtitles_options, optionsAdapter)
        val optionSelected = options.checkedItemPosition
        fun optionLabel(position: Int): String? {
            val sub = optionAt(position) ?: return null
            val number = position + 1
            if (sub.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) {
                val embedded = dialog.context.getString(R.string.subtitles_from_embedded)
                return "$number ($embedded" +
                    (if (sub.nameSuffix.isNotBlank()) " · ${sub.nameSuffix}" else "") + ")"
            }
            val detail = sub.nameSuffix.takeIf { it.isNotBlank() && it.toIntOrNull() == null }
            return "$number" + (detail?.let { "($it)" } ?: "")
        }
        options.adapter = RefreshRowAdapter(optionsAdapter, { position ->
            optionAt(position)?.let { ElevateSubtitleRefreshRegistry.entryFor(it) }
        }, { position -> optionLabel(position) },
            { position -> optionOrder().getOrNull(position) ?: position })
        if (optionSelected >= 0) {
            val displayIndex = optionOrder().indexOf(optionSelected)
            options.setItemChecked(if (displayIndex >= 0) displayIndex else optionSelected, true)
        }
        iconSelectionUrl = fragment?.player?.getCurrentPreferredSubtitle()?.url

        fun checkedEntry(): ElevateSubtitleRefreshRegistry.Entry? {
            return optionAt(options.checkedItemPosition)
                ?.let { ElevateSubtitleRefreshRegistry.entryFor(it) }
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
            val entry = if (list.checkedItemPosition == position &&
                !(options.visibility == View.VISIBLE && options.adapter?.count == 1))
                groupAt(position)?.singleOrNull()?.let { ElevateSubtitleRefreshRegistry.entryFor(it) }
            else null
            val button = (row as? RefreshRow)?.getChildAt(1) as? ImageButton
            if (entry != null && button != null) {
                requestOrQueueRefresh(entry, button, automatic = true)
                true
            } else groupLongClick?.onItemLongClick(parentView, row, position, id) ?: false
        }
        val optionLongClick = options.onItemLongClickListener
        options.onItemLongClickListener = AdapterView.OnItemLongClickListener { parentView, row, position, id ->
            val item = if (options.checkedItemPosition == position) optionAt(position) else null
            val entry = item?.let { ElevateSubtitleRefreshRegistry.entryFor(it) }
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
            val nativeIndex = options.checkedItemPosition
            val displayIndex = optionOrder().indexOf(nativeIndex)
            if (displayIndex >= 0) options.setItemChecked(displayIndex, true)
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
            val nativeIndex = optionOrder().getOrNull(position) ?: position
            nativeOptionClick?.onItemClick(parentView, row, nativeIndex, optionsAdapter.getItemId(nativeIndex))
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
        private val sourceAt: (Int) -> ElevateSubtitleRefreshRegistry.Entry?,
        private val displayAt: (Int) -> String? = { null },
        private val sourcePosition: (Int) -> Int = { it }
    ) : BaseAdapter() {
        private val originalEndPadding = WeakHashMap<TextView, Int>()
        private val observer = object : DataSetObserver() {
            override fun onChanged() = notifyDataSetChanged()
            override fun onInvalidated() = notifyDataSetInvalidated()
        }
        init { source.registerDataSetObserver(observer) }
        override fun getCount(): Int = source.count
        override fun getItem(position: Int): Any? = source.getItem(sourcePosition(position))
        override fun getItemId(position: Int): Long = source.getItemId(sourcePosition(position))
        override fun hasStableIds(): Boolean = source.hasStableIds()
        override fun isEnabled(position: Int): Boolean = source.isEnabled(sourcePosition(position))

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? RefreshRow) ?: RefreshRow(parent.context)
            row.onCheckedChanged = null
            val oldText = row.getChildAt(0) as? TextView
            val text = source.getView(sourcePosition(position), oldText, parent) as TextView
            displayAt(position)?.let { text.text = it }
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
                    "elevate_subtitle_refresh", "drawable", "com.example"
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
                    "elevate_refresh_subtitle", "Refresh subtitle"
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
            name, "string", "com.example"
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
        entry: ElevateSubtitleRefreshRegistry.Entry, button: ImageButton, automatic: Boolean = false
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
        entry: ElevateSubtitleRefreshRegistry.Entry, button: ImageButton?, automatic: Boolean = false
    ): Boolean {
        val target = fragment ?: run {
            return false
        }
        val instance = model?.state?.instance ?: run {
            return false
        }
        val actual = target.player.getCurrentPreferredSubtitle()?.url
        if (actual != entry.originalUrl) {
            return false
        }
        bindPlayer()
        if (automatic && autoRefreshUrl == entry.originalUrl) {
            refreshGeneration++
            refreshJob?.cancel()
            refreshJob = null
            endAutoRefresh()
            button?.isEnabled = true
            button?.alpha = 1f
            return false
        }
        if (refreshJob?.isActive == true) {
            return false
        }
        val mediaPlayer = observedPlayer ?: run {
            return false
        }
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
                    if (!current()) {
                        break
                    }
                    val previous = refreshedCues[entry.originalUrl]
                    val result = if (automatic) {
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0L) break
                        withTimeout(minOf(30_000L, remaining)) {
                            ElevateSubtitleRefreshRegistry.refresh(
                                entry, previous, checkList = attempt % 3 == 0
                            )
                        }
                    } else ElevateSubtitleRefreshRegistry.refresh(entry, previous)
                    if (!current()) {
                        break
                    }
                    if (result !== previous) {
                        // Only rebuild the overlay when the subtitle body changed.
                        if (entry.originalUrl !in refreshedCues && refreshedCues.size >= 4) {
                            refreshedCues.keys.firstOrNull { it != selectedUrl }?.let(refreshedCues::remove)
                        }
                        refreshedCues[entry.originalUrl] = result
                        refreshedOverlay.attach(target, entry.originalUrl, result)
                    }
                    panelListener?.invoke(target, entry.originalUrl)
                    if (!automatic || attempt + 1 >= attempts ||
                        SystemClock.elapsedRealtime() + AUTO_REFRESH_INTERVAL_MS >= deadline) break
                    delay(AUTO_REFRESH_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ElevateSubtitleRefresh", "failed stage=fetchOrParse generation=$generation urlHash=${entry.originalUrl.hashCode()} current=${current()}", e)
                if (current()) {
                    android.widget.Toast.makeText(
                        target.context, getPluginSubtitleString(
                            "elevate_refresh_failed", "Subtitle refresh failed"
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
        return true
    }

    fun setPanelListener(listener: ((GeneratorPlayer, String) -> Unit)?) {
        panelListener = listener
    }

    fun refreshAppearance() = refreshedOverlay.onSubtitleOffsetChanged()

    /** Called directly from the integrated panel, never through a fake Dialog. */
    fun refreshSelected(automatic: Boolean = false, button: ImageButton? = null,
                        expectedUrl: String? = null): Boolean {
        val target = fragment ?: run {
            return false
        }
        val url = target.player.getCurrentPreferredSubtitle()?.url ?: run {
            return false
        }
        if (expectedUrl != null && expectedUrl != url) {
            return false
        }
        val subtitle = model?.state?.subtitles?.firstOrNull { it.url == url } ?: run {
            return false
        }
        val entry = ElevateSubtitleRefreshRegistry.entryFor(subtitle) ?: run {
            return false
        }
        return requestSubtitleRefresh(entry, button, automatic)
    }

    fun refreshedCues(): List<SubtitleCue>? {
        val url = fragment?.player?.getCurrentPreferredSubtitle()?.url ?: return null
        return refreshedCues[url]?.cues?.mapNotNull { segment ->
            val lines = segment.cues.mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }
            if (lines.isEmpty()) null else SubtitleCue(
                segment.startTimeMs, (segment.endTimeMs - segment.startTimeMs).coerceAtLeast(1L), lines
            )
        }
    }

    fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree = null
        removeStateObserver?.invoke()
        removeStateObserver = null
        removeLinksObserver?.invoke()
        removeLinksObserver = null
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

/** A single refresh owner, separate from the optional four-tab player panel. */
internal class ElevateSubtitleRefreshHook(
    private val application: Application,
    pluginResources: Resources
) : Application.ActivityLifecycleCallbacks {
    private val controller = ElevateSubtitleController(pluginResources)
    private var activity: FragmentActivity? = null
    private val lifecycle = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) { controller.watch(fragment) }
        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            controller.watch(fragment)
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            controller.detach(fragment)
        }
    }
    private fun bind(next: FragmentActivity) {
        if (activity === next) return
        unbind()
        activity = next
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(lifecycle, true)
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { fragment ->
                if (fragment is GeneratorPlayer && fragment.view != null) controller.watch(fragment)
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
    }
    private fun unbind() {
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(lifecycle)
        controller.detach()
        activity = null
    }
    fun start() {
        current = this
        application.registerActivityLifecycleCallbacks(this)
        PipLibraryNavigation.currentPlayerHost()?.let(::bind)
    }
    fun stop() {
        if (current === this) current = null
        application.unregisterActivityLifecycleCallbacks(this)
        unbind()
    }
    fun refreshSelected(automatic: Boolean = false, button: ImageButton? = null,
                        expectedUrl: String? = null) =
        controller.refreshSelected(automatic, button, expectedUrl)
    fun setPanelListener(listener: ((GeneratorPlayer, String) -> Unit)?) =
        controller.setPanelListener(listener)
    fun refreshedCues(): List<SubtitleCue>? = controller.refreshedCues()
    fun refreshAppearance() = controller.refreshAppearance()
    companion object {
        private var current: ElevateSubtitleRefreshHook? = null
        fun active(): ElevateSubtitleRefreshHook? = current
    }
    override fun onActivityStarted(activity: Activity) {
        PipLibraryNavigation.playerHost(activity)?.let(::bind)
    }
    override fun onActivityResumed(activity: Activity) {
        PipLibraryNavigation.playerHost(activity)?.let(::bind)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity) unbind()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

