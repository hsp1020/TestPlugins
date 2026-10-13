package com.hsp1020

import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import java.util.UUID
import kotlin.math.abs

internal data class CatalogShortcutTarget(val addonUrl: String, val type: String, val catalogId: String)

/** UI-only catalog entry cards. No homepage data, preview responses or native navigation are changed. */
internal class CatalogHomeEntryHook(private val open: (CatalogShortcutTarget) -> Unit) {
    private val prefix = "https://stremioc.invalid/browse/"
    private val targets = HashMap<String, CatalogShortcutTarget>()
    private val rows = HashMap<RecyclerView, Row>()
    private var master: RecyclerView? = null
    // The initial parent may attach before HomeFragment assigns the child adapter.
    // Rescan when the layout is first populated instead of waiting for user scrolling.
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { scanRows() }

    private fun scanRows() {
        val parent = master ?: return
        for (i in 0 until parent.childCount) bindRow(parent.getChildAt(i))
    }

    private val parentListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) { bindRow(view) }
        override fun onChildViewDetachedFromWindow(view: View) {
            val child = view.findViewById<RecyclerView>(R.id.home_child_recyclerview)
            child?.let { rows.remove(it)?.dispose(false) }
        }
    }

    fun attach(root: View) {
        val next = root.findViewById<RecyclerView>(R.id.home_master_recycler) ?: return
        if (master === next) return
        detach()
        master = next
        next.addOnChildAttachStateChangeListener(parentListener)
        next.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        scanRows()
        next.post { if (master === next) scanRows() }
    }

    fun detach() {
        master?.removeOnChildAttachStateChangeListener(parentListener)
        master?.viewTreeObserver?.let { if (it.isAlive) it.removeOnGlobalLayoutListener(layoutListener) }
        master = null
        rows.values.toList().forEach { it.dispose(true) }
        rows.clear()
        targets.clear()
    }

    private fun bindRow(view: View) {
        val child = view.findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: return
        val adapter = child.adapter as? HomeChildItemAdapter ?: return
        if (rows[child]?.adapter === adapter) return
        rows.remove(child)?.dispose(true)
        Row(view, child, adapter).also {
            rows[child] = it
            it.install()
        }
    }

    private fun resolve(row: Row, cards: List<SearchResponse>): CatalogShortcutTarget? {
        val parent = master ?: return null
        val holder = parent.findContainingViewHolder(row.view) ?: return null
        val adapter = parent.adapter as? ParentItemAdapter ?: return null
        val position = holder.bindingAdapterPosition - adapter.headers
        val title = adapter.getItemOrNull(position)?.list?.name ?: return null
        val apiName = cards.firstOrNull()?.apiName ?: return null
        val provider = APIHolder.allProviders.filterIsInstance<StremioC>()
            .firstOrNull { it.name == apiName } ?: return null
        val path = provider.mainPage.firstOrNull { it.name == title }?.data ?: return null
        val route = path.substringAfter("/catalog/", "")
        val type = Uri.decode(route.substringBefore('/')).takeIf { it.isNotBlank() } ?: return null
        val id = Uri.decode(route.substringAfter('/', "").substringBefore('/').removeSuffix(".json"))
            .takeIf { it.isNotBlank() } ?: return null
        return CatalogShortcutTarget(provider.mainUrl, type, id)
    }

    private fun shortcut(target: CatalogShortcutTarget, provider: StremioC): SearchResponse {
        val value = target.addonUrl + "\u0000" + target.type + "\u0000" + target.catalogId
        val url = prefix + UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8))
        targets[url] = target
        return provider.newMovieSearchResponse("Browse Genres", url, TvType.Others, fix = false) {
            // Free-to-use Unsplash photograph of a red cinema auditorium.
            posterUrl = "https://images.unsplash.com/photo-1604975701397-6365ccbd028a?w=360&h=540&fit=crop&auto=format&q=80"
        }
    }

    private inner class Row(val view: View, val child: RecyclerView, val adapter: HomeChildItemAdapter) {
        private var queued = false
        private val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = schedule()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = schedule()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = schedule()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = schedule()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = schedule()
        }
        private val attached = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) { installInput(view) }
            override fun onChildViewDetachedFromWindow(view: View) { clearInput(view) }
        }

        fun install() {
            adapter.registerAdapterDataObserver(observer)
            child.addOnChildAttachStateChangeListener(attached)
            for (i in 0 until child.childCount) installInput(child.getChildAt(i))
            schedule()
        }

        fun dispose(removeCards: Boolean) {
            child.removeOnChildAttachStateChangeListener(attached)
            adapter.unregisterAdapterDataObserver(observer)
            for (i in 0 until child.childCount) clearInput(child.getChildAt(i))
            if (removeCards) {
                val original = adapter.immutableCurrentList.filterNot { it.url.startsWith(prefix) }
                if (original.size != adapter.immutableCurrentList.size) adapter.submitList(original)
            }
        }

        private fun schedule() {
            if (queued) return
            queued = true
            child.post {
                queued = false
                if (rows[child] === this) refresh()
            }
        }

        private fun refresh() {
            val current = adapter.immutableCurrentList
            val ordinary = current.filterNot { it.url.startsWith(prefix) }
            val target = resolve(this, ordinary)
            val provider = target?.let { wanted ->
                APIHolder.allProviders.filterIsInstance<StremioC>()
                    .firstOrNull { it.mainUrl == wanted.addonUrl }
            }
            val updated = if (target != null && provider != null) {
                listOf(shortcut(target, provider)) + ordinary
            } else ordinary
            if (current.map { it.url } != updated.map { it.url }) {
                val firstInsert = current.none { it.url.startsWith(prefix) } &&
                    updated.firstOrNull()?.url?.startsWith(prefix) == true
                adapter.submitList(updated) {
                    // An insertion at position 0 can retain the old scroll anchor at 1.
                    if (firstInsert && child.isAttachedToWindow &&
                        (child.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                            ?.findFirstVisibleItemPosition() in 0..1) child.scrollToPosition(0)
                }
            }
        }

        private fun targetAt(view: View): Pair<Boolean, CatalogShortcutTarget?> {
            val holder = child.findContainingViewHolder(view) ?: return false to null
            val item = adapter.getItemOrNull(holder.bindingAdapterPosition)
            val url = item?.url ?: return false to null
            return url.startsWith(prefix) to targets[url]
        }

        private fun installInput(itemView: View) {
            listOfNotNull(itemView, itemView.findViewById<View>(R.id.background_card))
                .distinct().forEach { control ->
                    val slop = ViewConfiguration.get(control.context).scaledTouchSlop
                    var downX = 0f
                    var downY = 0f
                    var downAt = 0L
                    var moved = false
                    control.setOnTouchListener { _, event ->
                        if (!targetAt(itemView).first) return@setOnTouchListener false
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = event.x; downY = event.y
                                downAt = event.eventTime; moved = false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) moved = true
                            }
                            MotionEvent.ACTION_UP -> {
                                if (!moved && event.eventTime - downAt < ViewConfiguration.getLongPressTimeout())
                                    targetAt(itemView).second?.let(open)
                            }
                            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> moved = true
                        }
                        true
                    }
                    control.setOnKeyListener { _, key, event ->
                        if (key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER &&
                            key != KeyEvent.KEYCODE_NUMPAD_ENTER && key != KeyEvent.KEYCODE_BUTTON_A
                        ) return@setOnKeyListener false
                        if (!targetAt(itemView).first) return@setOnKeyListener false
                        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled)
                            targetAt(itemView).second?.let(open)
                        true
                    }
                }
        }

        private fun clearInput(itemView: View) {
            listOfNotNull(itemView, itemView.findViewById<View>(R.id.background_card))
                .distinct().forEach {
                    it.setOnTouchListener(null)
                    it.setOnKeyListener(null)
                }
        }
    }
}
