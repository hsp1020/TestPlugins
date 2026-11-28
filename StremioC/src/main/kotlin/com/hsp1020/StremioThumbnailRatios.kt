package com.hsp1020

import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.request.SuccessResult
import coil3.util.CoilUtils
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import java.util.IdentityHashMap
import kotlin.math.roundToInt

internal fun stremioPosterHeight(width: Int, imageWidth: Int, imageHeight: Int): Int =
    (width.toFloat() * imageHeight / imageWidth).roundToInt().coerceAtLeast(1)

/** Only StremioC results; uses the already completed Coil request. */
internal class StremioThumbnailRatios(private val density: Float) {
    private val watchers = IdentityHashMap<RecyclerView, Watcher>()

    fun attach(root: View) {
        listOf(R.id.home_master_recycler, R.id.search_master_recycler,
            R.id.quick_search_master_recycler).forEach { id ->
            root.findViewById<RecyclerView>(id)?.let { watch(it, true) }
        }
        listOf(R.id.search_autofit_results, R.id.quick_search_autofit_results,
            R.id.home_expanded_recycler).forEach { id ->
            root.findViewById<RecyclerView>(id)?.let { watch(it) }
        }
    }

    fun watch(list: RecyclerView, parent: Boolean = false) {
        if (!watchers.containsKey(list)) {
            Watcher(list, parent).also { watchers[list] = it; it.open() }
        }
    }

    fun close() {
        val closing = watchers.values.toList().asReversed()
        watchers.clear()
        closing.forEach { it.close() }
    }

    private data class CardState(
        val card: View, val image: ImageView, val title: TextView,
        val originalWidth: Int, val originalHeight: Int,
        val originalImageHeight: Int, val originalTitleWidth: Int,
        var identity: String = "", var posterUrl: String = "", var applied: Boolean = false
    )
    private inner class Watcher(private val list: RecyclerView, private val parent: Boolean) {
        private val states = IdentityHashMap<View, CardState>()
        private val pending = IdentityHashMap<View, CardState>()
        private var preDrawTree: ViewTreeObserver? = null
        private val imageReady = ViewTreeObserver.OnPreDrawListener {
            val ready = ArrayList<View>()
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                val (view, state) = iterator.next()
                if (states[view] !== state || view.parent !== list) {
                    iterator.remove()
                    continue
                }
                val result = CoilUtils.result(state.image)
                if (result != null && result.request.data.toString() == state.posterUrl) {
                    iterator.remove()
                    ready.add(view)
                }
            }
            if (pending.isEmpty()) clearPreDraw()
            ready.forEach(::apply)
            true
        }
        private fun clearPreDraw() {
            preDrawTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(imageReady)
            preDrawTree = null
        }
        private var observed: RecyclerView.Adapter<*>? = null
        private var posted = false
        private var closed = false
        private val changes = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = schedule()
            override fun onItemRangeChanged(start: Int, count: Int) = schedule()
            override fun onItemRangeInserted(start: Int, count: Int) = schedule()
            override fun onItemRangeRemoved(start: Int, count: Int) = schedule()
            override fun onItemRangeMoved(from: Int, to: Int, count: Int) = schedule()
        }
        private val children = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) { apply(view) }
            override fun onChildViewDetachedFromWindow(view: View) {
                if (parent) {
                    view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let {
                        watchers.remove(it)?.close()
                    }
                } else restore(view)
            }
        }
        fun open() {
            list.addOnChildAttachStateChangeListener(children)
            sync()
            scan()
            schedule()
        }
        fun close() {
            if (closed) return
            closed = true
            posted = false
            list.removeOnChildAttachStateChangeListener(children)
            observed?.unregisterAdapterDataObserver(changes)
            observed = null
            pending.clear()
            clearPreDraw()
            states.keys.toList().forEach(::restore)
            if (parent) {
                for (i in 0 until list.childCount) {
                    list.getChildAt(i).findViewById<RecyclerView>(R.id.home_child_recyclerview)
                        ?.let { watchers.remove(it)?.close() }
                }
            }
        }
        private fun sync() {
            if (closed || observed === list.adapter) return
            observed?.unregisterAdapterDataObserver(changes)
            observed = list.adapter
            observed?.registerAdapterDataObserver(changes)
        }
        private fun schedule() {
            if (posted) return
            posted = true
            list.post {
                if (!posted || watchers[list] !== this) return@post
                posted = false
                scan()
            }
        }
        private fun scan() {
            sync()
            for (i in 0 until list.childCount) apply(list.getChildAt(i))
        }
        private fun apply(view: View) {
            if (parent) {
                view.findViewById<RecyclerView>(R.id.home_child_recyclerview)
                    ?.let { watch(it, false) }
                return
            }
            val adapter = list.adapter as? BaseAdapter<*, *> ?: return
            val position = list.getChildAdapterPosition(view)
            val item = if (position == RecyclerView.NO_POSITION) null
                else adapter.getItemOrNull(position) as? SearchResponse
            if (item == null || item.posterUrl.isNullOrBlank() ||
                (item.apiName != "StremioC" && APIHolder.getApiFromNameNull(item.apiName) !is StremioC)) {
                restore(view); return
            }
            val state = states[view] ?: run {
                val card = view.findViewById<View>(R.id.background_card) ?: return
                val image = view.findViewById<ImageView>(R.id.imageView) ?: return
                val title = view.findViewById<TextView>(R.id.imageText) ?: return
                CardState(card, image, title, card.layoutParams.width,
                    card.layoutParams.height, image.layoutParams.height,
                    title.layoutParams.width).also { states[view] = it }
            }
            val posterUrl = item.posterUrl ?: return restore(view)
            val identity = item.apiName + "|" + item.url + "|" + posterUrl
            if (state.identity != identity) {
                disarm(view)
                restoreSize(state)
                state.identity = identity
                state.posterUrl = posterUrl
            }
            val result = CoilUtils.result(state.image)
            if (result == null || result.request.data.toString() != posterUrl) {
                restoreSize(state)
                pending[view] = state
                if (preDrawTree == null) {
                    list.viewTreeObserver.takeIf { it.isAlive }?.let {
                        preDrawTree = it
                        it.addOnPreDrawListener(imageReady)
                    }
                }
                return
            }
            disarm(view)
            val loaded = result as? SuccessResult ?: run { restoreSize(state); return }
            val w = loaded.image.width
            val h = loaded.image.height
            if (w <= 8 || h <= 8) return
            val width = if (list is AutofitRecyclerView) {
                (list.itemWidth - dp(4)).takeIf { it > 0 } ?: return
            } else (if (w > h) HomeChildItemAdapter.maxPosterSize
                else HomeChildItemAdapter.minPosterSize).takeIf { it > 0 }
                ?: dp(if (w > h) 180 else 114)
            val height = stremioPosterHeight(width, w, h)
            if (state.applied && state.card.layoutParams.width == width &&
                (if (list is AutofitRecyclerView) state.image.layoutParams.height
                 else state.card.layoutParams.height) == height) return
            if (list is AutofitRecyclerView) {
                state.card.layoutParams = state.card.layoutParams.apply { this.width = width }
                state.image.layoutParams = state.image.layoutParams.apply { this.height = height }
            } else {
                state.card.layoutParams = state.card.layoutParams.apply {
                    this.width = width
                    this.height = height
                }
                if (state.title.parent !== state.card) {
                    state.title.layoutParams = state.title.layoutParams.apply { this.width = width }
                }
            }
            state.applied = true
        }
        private fun disarm(view: View) {
            pending.remove(view)
            if (pending.isEmpty()) clearPreDraw()
        }
        private fun restoreSize(state: CardState) {
            if (!state.applied) return
            state.card.layoutParams = state.card.layoutParams.apply {
                width = state.originalWidth; height = state.originalHeight
            }
            state.image.layoutParams = state.image.layoutParams.apply {
                height = state.originalImageHeight
            }
            state.title.layoutParams = state.title.layoutParams.apply {
                width = state.originalTitleWidth
            }
            state.applied = false
        }
        private fun restore(view: View) {
            disarm(view)
            states.remove(view)?.let(::restoreSize)
        }
    }
    private fun dp(value: Int): Int =
        (value * density + 0.5f).toInt()
}
