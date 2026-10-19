package com.hsp1020.elevate

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import java.util.IdentityHashMap

/**
 * Temporary, event-driven home UI audit. Does not change adapter lists, binding or image loading.
 * Tracks model -> parent diff -> row adapter -> child diff -> holder -> rendered text/drawable.
 */
internal class HomeTransitionAudit(
    private val handler: Handler,
    private val providerName: () -> String?
) {
    private var parent: RecyclerView? = null
    private var parentAdapter: RecyclerView.Adapter<*>? = null
    private val children = IdentityHashMap<RecyclerView, ChildMonitor>()
    private val previousCards = IdentityHashMap<View, String>()
    private val previousRows = IdentityHashMap<View, String>()
    private var generation = 0
    private var serial = 0
    private var samplingCycle = 0
    private var sessionActiveUntil = 0L
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var preDrawRoot: RecyclerView? = null
    private var lastSample = 0L
    private var lastMismatch = ""
    private var snapshots = 0
    private var mismatchEvents = 0
    private var providerTransitions = 0

    private fun id(x: Any?): String =
        if (x == null) "-" else System.identityHashCode(x).toString(16)

    private fun label(s: String?): String =
        s?.replace('\n', ' ')?.take(75) ?: "-"

    private fun searchCard(c: SearchResponse?): String =
        if (c == null) "none" else
            "name=${label(c.name)} owner=${label(c.apiName)} urlKey=${c.url.hashCode().toUInt().toString(16)} posterKey=${c.posterUrl?.hashCode()?.toUInt()?.toString(16)}"

    private fun log(event: String, message: String) {
        if (message.length > 3500) {
            Log.i(TAG, "s=$generation n=${++serial} t=${SystemClock.uptimeMillis()} owner=${label(providerName())} event=$event ${message.take(3450)} TRUNCATED")
        } else {
            Log.i(TAG, "s=$generation n=${++serial} t=${SystemClock.uptimeMillis()} owner=${label(providerName())} event=$event $message")
        }
    }

    fun attach(recycler: RecyclerView?) {
        if (parent === recycler) return
        detach()
        parent = recycler ?: return
        recycler.addOnChildAttachStateChangeListener(parentChildrenListener)
        recycler.addOnScrollListener(parentScrollListener)
        recycler.addOnLayoutChangeListener(parentLayoutListener)
        syncParentAdapter("attach")
        discover("attach")
        log("ATTACH", "rv=${id(recycler)} pool=${id(recycler.recycledViewPool)} count=${recycler.childCount} layout=${recycler.layoutManager?.javaClass?.simpleName}")
        startSampling("attach")
    }

    fun detach() {
        val rv = parent
        stopSampling()
        if (rv != null) {
            log("DETACH", "rv=${id(rv)} activeChildren=${children.size} samples=$snapshots mismatches=$mismatchEvents")
            rv.removeOnChildAttachStateChangeListener(parentChildrenListener)
            rv.removeOnScrollListener(parentScrollListener)
            rv.removeOnLayoutChangeListener(parentLayoutListener)
        }
        val old = parentAdapter
        old?.unregisterAdapterDataObserver(parentObserver)
        parentAdapter = null
        children.values.toList().forEach { it.close() }
        children.clear()
        parent = null
        previousCards.clear()
        previousRows.clear()
        lastMismatch = ""
        generation++
    }

    fun transition(previous: String?, next: String?) {
        generation++
        providerTransitions++
        snapshots = 0
        mismatchEvents = 0
        previousCards.clear()
        previousRows.clear()
        lastMismatch = ""
        log("TRANSITION", "from=${label(previous)} to=${label(next)} index=$providerTransitions rv=${id(parent)}")
        capture("BEFORE_PROVIDER_CHANGE", full = true)
        startSampling("provider-switch")
    }

    fun modelLoading() {
        log("MODEL_LOADING", "page=Loading")
        if (!isActive()) startSampling("refresh-loading")
        capture("MODEL_LOADING", full = true)
    }

    fun modelSuccess(rows: Map<String, HomeViewModel.ExpandableHomepageList>) {
        log("MODEL_SUCCESS", "rows=${rows.size} keys=${rows.keys.joinToString(limit=20) { label(it) }}")
        rows.entries.forEachIndexed { pos, pair ->
            val row = pair.value
            log("MODEL_ROW", "pos=$pos key=${label(pair.key)} name=${label(row.list.name)} listKey=${id(row.list.list)} count=${row.list.list.size} hasNext=${row.hasNext} firstOwners=${row.list.list.take(5).map { it.apiName }.distinct()}")
            row.list.list.take(4).forEachIndexed { item, card ->
                log("MODEL_CARD", "row=$pos pos=$item ${searchCard(card)}")
            }
        }
        if (!isActive()) startSampling("refresh-success")
        syncParentAdapter("model-success")
        discover("model-success")
        capture("MODEL_SUCCESS_CURRENT_VIEW", full = true)
    }

    fun modelFailure(message: String?) {
        log("MODEL_FAILURE", label(message))
        capture("MODEL_FAILURE_VIEW", full = true)
    }

    fun phase(phase: String) {
        if (isActive()) {
            log("PHASE", phase)
            syncParentAdapter(phase)
            discover(phase)
            capture(phase, full = true)
        }
    }

    private fun isActive(): Boolean = parent != null && SystemClock.uptimeMillis() < sessionActiveUntil

    private fun startSampling(reason: String) {
        val root = parent ?: return
        stopSampling()
        val cycle = ++samplingCycle
        sessionActiveUntil = SystemClock.uptimeMillis() + 11500
        lastSample = 0
        log("SAMPLE_START", "reason=$reason durationMs=11500")
        // One bounded pre-draw observer catches cards created after the initial data commit.
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val now = SystemClock.uptimeMillis()
                if (parent !== root || now >= sessionActiveUntil) {
                    stopSampling()
                    return true
                }
                if (now - lastSample >= 125) {
                    lastSample = now
                    discover("pre-draw")
                    capture("FRAME", full = false)
                }
                return true
            }
        }
        preDrawRoot = root
        preDrawListener = listener
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.addOnPreDrawListener(listener)
        val session = generation
        for (delay in listOf(40L, 100L, 200L, 350L, 600L, 1000L, 2000L, 3500L, 6000L, 10000L, 11500L)) {
            handler.postDelayed({
                if (parent === root && generation == session && samplingCycle == cycle) {
                    discover("delay-$delay")
                    capture("CHECKPOINT_$delay", full = true)
                    if (delay == 11500L) {
                        log("SESSION_SUMMARY", "snapshots=$snapshots mismatchEvents=$mismatchEvents rowsWatched=${children.size}")
                        stopSampling()
                    }
                }
            }, delay)
        }
    }

    private fun stopSampling() {
        val rv = preDrawRoot
        val listener = preDrawListener
        if (rv != null && listener != null && rv.viewTreeObserver.isAlive) {
            rv.viewTreeObserver.removeOnPreDrawListener(listener)
        }
        preDrawRoot = null
        preDrawListener = null
        sessionActiveUntil = 0
    }

    private val parentObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = changed("all", 0, 0)
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = changed("insert", positionStart, itemCount)
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = changed("remove", positionStart, itemCount)
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            log("PARENT_DIFF", "op=move from=$fromPosition to=$toPosition count=$itemCount")
            changed("move", fromPosition, itemCount)
        }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = changed("changed", positionStart, itemCount)
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            log("PARENT_PAYLOAD", "position=$positionStart count=$itemCount payload=${id(payload)} type=${payload?.javaClass?.simpleName}")
            changed("changed-payload", positionStart, itemCount)
        }
    }

    private fun changed(op: String, start: Int, count: Int) {
        val adapter = parent?.adapter as? ParentItemAdapter
        log("PARENT_DIFF", "op=$op at=$start count=$count currentRows=${adapter?.immutableCurrentList?.size} parentItemCount=${parent?.adapter?.itemCount} headers=${adapter?.headers} adapter=${id(adapter)}")
        adapter?.immutableCurrentList?.forEachIndexed { index, row ->
            log("PARENT_MODEL", "index=$index name=${label(row.list.name)} size=${row.list.list.size} listId=${id(row.list.list)} first=${searchCard(row.list.list.firstOrNull())}")
        }
        capture("PARENT_DIFF_$op", full = true)
        parent?.post {
            discover("parent-diff-$op-post")
            capture("PARENT_DIFF_AFTER_LAYOUT_$op", full = true)
        }
    }

    private fun syncParentAdapter(reason: String) {
        val new = parent?.adapter
        if (new === parentAdapter) return
        parentAdapter?.unregisterAdapterDataObserver(parentObserver)
        parentAdapter = new
        new?.registerAdapterDataObserver(parentObserver)
        log("PARENT_ADAPTER", "reason=$reason adapter=${id(new)} count=${new?.itemCount} type=${new?.javaClass?.simpleName}")
    }

    private val parentChildrenListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            log("PARENT_CHILD_ATTACH", "view=${id(view)} holder=${id(parent?.getChildViewHolder(view))}")
            monitorRow(view, "parent-attach")
            capture("ROW_ATTACHED", full = true)
        }
        override fun onChildViewDetachedFromWindow(view: View) {
            log("PARENT_CHILD_DETACH", "view=${id(view)}")
            view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let { child ->
                children.remove(child)?.close()
            }
        }
    }
    private val parentScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
            log("PARENT_SCROLL_STATE", "state=$newState")
            capture("PARENT_SCROLL_STATE", full = false)
        }
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (isActive()) {
                log("PARENT_SCROLL", "dx=$dx dy=$dy")
                discover("parent-scroll")
                capture("PARENT_SCROLL", full = false)
            }
        }
    }
    private val parentLayoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, oright, ob ->
        if (isActive()) {
            log("PARENT_LAYOUT", "from=${oright-ol}x${ob-ot} to=${r-l}x${b-t}")
            discover("parent-layout")
            capture("PARENT_LAYOUT", full = false)
        }
    }

    private fun discover(reason: String) {
        val rv = parent ?: return
        syncParentAdapter(reason)
        for (pos in 0 until rv.childCount) monitorRow(rv.getChildAt(pos), reason)
    }

    private fun monitorRow(rowView: View, reason: String) {
        val child = rowView.findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: return
        val current = children[child]
        if (current != null) {
            current.sync(reason)
            return
        }
        val monitor = ChildMonitor(child)
        children[child] = monitor
        monitor.begin(reason)
        if (isActive()) log("ROW_MONITOR", "reason=$reason rv=${id(child)} rowView=${id(rowView)} pool=${id(child.recycledViewPool)}")
    }

    private inner class ChildMonitor(val rv: RecyclerView) {
        private var adapter: RecyclerView.Adapter<*>? = null
        private var closed = false
        private val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = diff("all", 0, 0)
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = diff("insert", positionStart, itemCount)
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = diff("remove", positionStart, itemCount)
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                log("CHILD_DIFF", "rv=${id(rv)} op=move from=$fromPosition to=$toPosition count=$itemCount")
                diff("move", fromPosition, itemCount)
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = diff("changed", positionStart, itemCount)
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                log("CHILD_PAYLOAD", "rv=${id(rv)} pos=$positionStart count=$itemCount payload=${id(payload)}")
                diff("changed-payload", positionStart, itemCount)
            }
        }
        private fun diff(op: String, at: Int, count: Int) {
            val a = rv.adapter as? HomeChildItemAdapter
            log("CHILD_DIFF", "rv=${id(rv)} op=$op at=$at count=$count adapter=${id(a)} data=${a?.immutableCurrentList?.size} shownCount=${rv.childCount} first=${searchCard(a?.getItemOrNull(0))} second=${searchCard(a?.getItemOrNull(1))}")
            capture("CHILD_DIFF_$op", full = true)
            rv.post { capture("CHILD_DIFF_AFTER_LAYOUT_$op", full = true) }
        }
        private val attachment = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (isActive()) {
                    val holder = rv.findContainingViewHolder(view)
                    log("CARD_ATTACH", "rv=${id(rv)} holder=${id(holder)} pos=${holder?.bindingAdapterPosition} text=${label(view.findViewById<TextView>(R.id.imageText)?.text?.toString())}")
                    capture("CARD_ATTACH", full = false)
                }
            }
            override fun onChildViewDetachedFromWindow(view: View) {
                if (isActive()) {
                    val holder = rv.findContainingViewHolder(view)
                    log("CARD_DETACH", "rv=${id(rv)} holder=${id(holder)} pos=${holder?.bindingAdapterPosition} text=${label(view.findViewById<TextView>(R.id.imageText)?.text?.toString())}")
                }
            }
        }
        private val scroll = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (isActive()) {
                    log("CHILD_SCROLL_STATE", "rv=${id(rv)} state=$state")
                    capture("CHILD_SCROLL_STATE", full = false)
                }
            }
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (isActive()) {
                    log("CHILD_SCROLL", "rv=${id(rv)} dx=$dx dy=$dy")
                    capture("CHILD_SCROLL", full = false)
                }
            }
        }
        private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, oright, ob ->
            if (isActive()) {
                log("CHILD_LAYOUT", "rv=${id(rv)} from=${oright-ol}x${ob-ot} to=${r-l}x${b-t}")
                capture("CHILD_LAYOUT", full = false)
            }
        }

        fun begin(reason: String) {
            rv.addOnChildAttachStateChangeListener(attachment)
            rv.addOnScrollListener(scroll)
            rv.addOnLayoutChangeListener(layoutListener)
            sync(reason)
        }
        fun sync(reason: String) {
            if (closed || rv.adapter === adapter) return
            val old = adapter
            old?.unregisterAdapterDataObserver(observer)
            adapter = rv.adapter
            adapter?.registerAdapterDataObserver(observer)
            log("CHILD_ADAPTER", "reason=$reason rv=${id(rv)} old=${id(old)} new=${id(adapter)} count=${adapter?.itemCount} first=${searchCard((adapter as? HomeChildItemAdapter)?.getItemOrNull(0))}")
        }
        fun close() {
            if (closed) return
            closed = true
            adapter?.unregisterAdapterDataObserver(observer)
            rv.removeOnChildAttachStateChangeListener(attachment)
            rv.removeOnScrollListener(scroll)
            rv.removeOnLayoutChangeListener(layoutListener)
            adapter = null
        }
    }

    private fun capture(reason: String, full: Boolean) {
        val rv = parent ?: return
        if (!full && !isActive()) return
        snapshots++
        val pa = rv.adapter as? ParentItemAdapter
        if (full) {
            log("VIEW_PARENT", "reason=$reason rv=${id(rv)} adapter=${id(pa)} dataCount=${pa?.immutableCurrentList?.size} shownRows=${rv.childCount} layout=${rv.isComputingLayout} animate=${rv.itemAnimator?.isRunning} pool=${id(rv.recycledViewPool)}")
            if (reason.startsWith("CHECKPOINT") || reason.startsWith("MODEL_") ||
                reason.startsWith("BEFORE_") || reason.startsWith("draw-") ||
                reason.startsWith("settled-") || reason == "PARENT_SCROLL_STATE") {
                HomeCacheTrace.snapshot("audit-$reason", providerName(), rv)
            }
        }
        for (i in 0 until rv.childCount) {
            val row = rv.getChildAt(i)
            val rowHolder = rv.findContainingViewHolder(row)
            val pos = rowHolder?.bindingAdapterPosition ?: -1
            val expectedRow = pa?.getItemOrNull(pos - (pa?.headers ?: 0))
            val renderedRow = row.findViewById<TextView>(R.id.home_child_more_info)?.text?.toString()
            val child = row.findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: continue
            val ca = child.adapter as? HomeChildItemAdapter
            val rowSignature = "${id(rowHolder)}|$pos|${expectedRow?.list?.name}|${renderedRow}|${id(ca)}|${ca?.itemCount}"
            val rowMismatch = expectedRow != null && renderedRow != expectedRow.list.name
            if (full || rowMismatch || previousRows[row] != rowSignature) {
                log("VIEW_ROW", "reason=$reason index=$i holder=${id(rowHolder)} position=$pos expected=${label(expectedRow?.list?.name)} rendered=${label(renderedRow)} mismatch=$rowMismatch recycler=${id(child)} childAdapter=${id(ca)} childCount=${child.childCount} dataCount=${ca?.itemCount} first=${searchCard(ca?.getItemOrNull(0))}")
                previousRows[row] = rowSignature
            }
            if (rowMismatch) {
                mismatchEvents++
                log("ROW_MISMATCH", "reason=$reason pos=$pos expected=${label(expectedRow?.list?.name)} shown=${label(renderedRow)}")
            }
            for (j in 0 until child.childCount) {
                val view = child.getChildAt(j)
                val holder = child.findContainingViewHolder(view)
                val childPos = holder?.bindingAdapterPosition ?: -1
                val expected = ca?.getItemOrNull(childPos)
                val rendered = view.findViewById<TextView>(R.id.imageText)?.text?.toString()
                val image = view.findViewById<ImageView>(R.id.imageView)
                val drawable = image?.drawable
                val correct = expected != null && expected.name == rendered
                val bound = holder != null && childPos >= 0 && expected != null
                val mismatch = bound && !correct
                val signature = "${id(holder)}|$childPos|${expected?.url?.hashCode()}|${rendered}|${id(drawable)}"
                val change = previousCards[view] != signature
                // Important: do not treat null holders during layout as incorrect data.
                if (full && (childPos <= 2 || mismatch) || mismatch || change) {
                    log("VIEW_CARD", "reason=$reason row=${label(renderedRow)} rowIndex=$i visualIndex=$j pos=$childPos holder=${id(holder)} view=${id(view)} tag=${view.tag} expected=${searchCard(expected)} shown=${label(rendered)} drawable=${drawable?.javaClass?.simpleName}#${id(drawable)} drawableSize=${drawable?.intrinsicWidth}x${drawable?.intrinsicHeight} attached=${view.isAttachedToWindow} transient=${view.hasTransientState()} recyclable=${holder?.isRecyclable} mismatch=$mismatch changed=$change")
                }
                if (mismatch) {
                    mismatchEvents++
                    val detail = "row=${label(renderedRow)} pos=$childPos holder=${id(holder)} view=${id(view)} expected=${label(expected?.name)} shown=${label(rendered)} modelUrl=${expected?.url?.hashCode()} drawable=${id(drawable)}"
                    if (lastMismatch != detail) {
                        log("CARD_MISMATCH", "reason=$reason $detail")
                        lastMismatch = detail
                    }
                }
                previousCards[view] = signature
            }
        }
    }

    private companion object {
        const val TAG = "ELEVATE_HOME_AUDIT"
    }
}
