package com.hsp1020

import android.app.Dialog
import android.text.Spanned
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.HeaderViewListAdapter
import android.widget.ListView
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.utils.AppContextUtils.sortSubs
import com.lagradost.cloudstream3.utils.SubtitleHelper

/** StremioC-only subtitle ordering and embedded selection; native player UI is preserved. */
@OptIn(UnstableApi::class)
internal class StremioSubtitleController {
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var viewTree: ViewTreeObserver? = null
    private var removeObserver: (() -> Unit)? = null
    private var lastInstance = -1
    private var lastPlayer: Media3Player? = null
    private var embeddedApplied = false
    private var sortedDialog: Dialog? = null
    private var pendingDialog: Dialog? = null
    private var isStremio = false
    private val dialogField = runCatching {
        FullScreenPlayer::class.java.getDeclaredField("selectSourceDialog").apply {
            isAccessible = true
        }
    }.getOrNull()
    private val selectMethod = runCatching {
        GeneratorPlayer::class.java.getDeclaredMethod(
            "setSubtitles", SubtitleData::class.java, java.lang.Boolean.TYPE
        ).apply { isAccessible = true }
    }.getOrNull()

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
                lastPlayer = null
                embeddedApplied = false
                sortedDialog = null
                isStremio = checkStremio()
            }
            if (isStremio) {
                val current = f.playerView?.player
                if (lastPlayer != null && lastPlayer !== current) {
                    // Embedded subtitle IDs are only valid for their source video.
                    model?.modifyState {
                        set(subtitles.filterNot { it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO })
                    }
                    embeddedApplied = false
                }
                lastPlayer = current
                if (current != null) {
                    selectEmbedded()
                    val popup = runCatching { dialogField?.get(f) as? Dialog }.getOrNull()
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
        candidate.view?.viewTreeObserver?.takeIf { it.isAlive }?.let {
            it.addOnPreDrawListener(onDraw)
            viewTree = it
        }
    }

    private fun selectEmbedded() {
        if (embeddedApplied || lastPlayer == null) return
        val f = fragment ?: return
        val preferred = SubtitlesFragment.getAutoSelectLanguageTagIETF()
        if (preferred.isBlank()) return
        val embedded = model?.state?.subtitles?.firstOrNull {
            it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO && it.matchesLanguageCode(preferred)
        } ?: return
        // Preserve the host's selected subtitle and dialog state.
        val method = selectMethod ?: return
        if (runCatching { method.invoke(f, embedded, false) }.isSuccess)
            embeddedApplied = true
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
        if (order.indices.all { order[it] == it }) return true
        val originalClick = list.onItemClickListener ?: return false
        val previouslySelected = list.checkedItemPosition
        @Suppress("UNCHECKED_CAST")
        val editable = adapter as ArrayAdapter<Spanned>
        editable.clear()
        editable.addAll(listOf(texts.first()) + order.map { texts[it + 1] })
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
        removeObserver?.invoke()
        removeObserver = null
        fragment = null
        model = null
        lastPlayer = null
        lastInstance = -1
        embeddedApplied = false
        sortedDialog = null
        pendingDialog = null
        isStremio = false
    }
}
