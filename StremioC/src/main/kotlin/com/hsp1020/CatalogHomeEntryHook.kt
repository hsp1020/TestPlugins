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
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import kotlin.math.abs

internal data class CatalogShortcutTarget(val addonUrl: String, val type: String, val catalogId: String)
internal data class CatalogHomeSnapshot(
    val target: CatalogShortcutTarget,
    val items: List<SearchResponse>,
    val nextSkip: Int,
    val hasNext: Boolean
)

/** Route StremioC home-row headings without modifying native poster data. */
internal class CatalogHomeEntryHook(
    private val open: (CatalogShortcutTarget, CatalogHomeSnapshot?) -> Unit
) {
    private val rows = HashMap<RecyclerView, Row>()
    private var master: RecyclerView? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { scanRows() }

    private fun scanRows() {
        val parent = master ?: return
        for (i in 0 until parent.childCount) bindRow(parent.getChildAt(i))
    }

    private val parentListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) { bindRow(view) }
        override fun onChildViewDetachedFromWindow(view: View) {
            view.findViewById<RecyclerView>(R.id.home_child_recyclerview)
                ?.let { rows.remove(it)?.dispose() }
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
        master?.viewTreeObserver?.let {
            if (it.isAlive) it.removeOnGlobalLayoutListener(layoutListener)
        }
        master = null
        rows.values.toList().forEach { it.dispose() }
        rows.clear()
    }

    private fun bindRow(view: View) {
        val child = view.findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: return
        val adapter = child.adapter as? HomeChildItemAdapter ?: return
        if (rows[child]?.adapter === adapter) return
        rows.remove(child)?.dispose()
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

    private inner class Row(val view: View, private val child: RecyclerView, val adapter: HomeChildItemAdapter) {
        private val title = view.findViewById<View>(R.id.home_child_more_info)
        private val originalFocusable = title?.isFocusable == true
        private var queued = false
        private val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = schedule()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = schedule()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = schedule()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = schedule()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = schedule()
        }

        private fun homeTarget(): CatalogShortcutTarget? =
            adapter.immutableCurrentList.takeIf { it.isNotEmpty() }?.let { resolve(this, it) }

        private fun refreshFocus() {
            if (isLayout(TV)) title?.isFocusable = homeTarget() != null || originalFocusable
        }

        private fun schedule() {
            if (queued) return
            queued = true
            child.post {
                queued = false
                if (rows[child] === this) refreshFocus()
            }
        }

        private fun openTarget(target: CatalogShortcutTarget) {
            val cards = adapter.immutableCurrentList
            val provider = APIHolder.allProviders.filterIsInstance<StremioC>()
                .firstOrNull { it.mainUrl == target.addonUrl }
            val cursor = provider?.homeCursorFor(target, cards)
            val snapshot = cursor?.let {
                CatalogHomeSnapshot(target, cards.toList(), it, adapter.hasNext)
            }
            open(target, snapshot)
        }

        fun install() {
            adapter.registerAdapterDataObserver(observer)
            refreshFocus()
            val control = title ?: return
            val slop = ViewConfiguration.get(control.context).scaledTouchSlop
            var pressedTarget: CatalogShortcutTarget? = null
            var downX = 0f
            var downY = 0f
            var downAt = 0L
            var moved = false
            control.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressedTarget = homeTarget()
                        if (pressedTarget == null) return@setOnTouchListener false
                        downX = event.x
                        downY = event.y
                        downAt = event.eventTime
                        moved = false
                        v.isPressed = true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (pressedTarget == null) return@setOnTouchListener false
                        if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) moved = true
                        if (moved) v.isPressed = false
                    }
                    MotionEvent.ACTION_UP -> {
                        val target = pressedTarget ?: return@setOnTouchListener false
                        pressedTarget = null
                        v.isPressed = false
                        if (!moved && event.eventTime - downAt < ViewConfiguration.getLongPressTimeout() &&
                            homeTarget() == target) openTarget(target)
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                        if (pressedTarget == null) return@setOnTouchListener false
                        pressedTarget = null
                        moved = true
                        v.isPressed = false
                    }
                    else -> return@setOnTouchListener pressedTarget != null
                }
                true
            }
            control.setOnKeyListener { _, key, event ->
                if (key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER &&
                    key != KeyEvent.KEYCODE_NUMPAD_ENTER && key != KeyEvent.KEYCODE_BUTTON_A
                ) return@setOnKeyListener false
                val target = homeTarget() ?: return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) openTarget(target)
                true
            }
        }

        fun dispose() {
            adapter.unregisterAdapterDataObserver(observer)
            title?.apply {
                setOnTouchListener(null)
                setOnKeyListener(null)
                isPressed = false
                if (isLayout(TV)) isFocusable = originalFocusable
            }
        }
    }
}
