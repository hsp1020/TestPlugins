package com.hsp1020.elevate

import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter

/**
 * Diagnostic-only snapshots for provider transitions. Never changes any RecyclerView,
 * triggers networking or observes every frame. Cache members are read reflectively.
 */
internal object HomeCacheTrace {
    private const val TAG = "ELEVATE_HOME_TRACE"
    private fun id(value: Any?): String =
        if (value == null) "null" else System.identityHashCode(value).toString(16)

    fun info(event: String, message: String) {
        Log.i(TAG, "event=$event $message")
    }

    private fun cacheEntries(rv: RecyclerView): String = try {
        val f = RecyclerView::class.java.getDeclaredField("mRecycler")
        f.isAccessible = true
        val recycler = f.get(rv)
        val c = recycler.javaClass.getDeclaredField("mCachedViews")
        c.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val holders = c.get(recycler) as? List<RecyclerView.ViewHolder>
        if (holders == null) "no-list" else holders.joinToString(prefix="[", postfix="]") {
            val shown = it.itemView.findViewById<TextView>(R.id.imageText)?.text?.toString()?.take(45)
            "holder=${id(it)},pos=${it.bindingAdapterPosition},label=$shown"
        }
    } catch (e: Exception) {
        "unavailable:${e.javaClass.simpleName}"
    }

    fun snapshot(phase: String, provider: String?, parent: RecyclerView?) {
        if (parent == null) {
            info("snapshot", "phase=$phase provider=$provider parent=null")
            return
        }
        val adapter = parent.adapter as? ParentItemAdapter
        info("snapshot", "phase=$phase provider=$provider parent=${id(parent)} rows=${parent.childCount} data=${adapter?.immutableCurrentList?.size} cache=${cacheEntries(parent)}")
        for (rowIndex in 0 until parent.childCount) {
            val row = parent.getChildAt(rowIndex)
            val rowHolder = parent.findContainingViewHolder(row)
            val dataPosition = (rowHolder?.bindingAdapterPosition ?: -1) - (adapter?.headers ?: 0)
            val declared = adapter?.getItemOrNull(dataPosition)?.list?.name
            val rowLabel = row.findViewById<TextView>(R.id.home_child_more_info)?.text?.toString()
            val child = row.findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: continue
            val childAdapter = child.adapter as? HomeChildItemAdapter
            info("row", "phase=$phase row=$rowIndex dataPos=$dataPosition visible=$rowLabel adapter=$declared child=${id(child)} childData=${childAdapter?.immutableCurrentList?.size} cached=${cacheEntries(child)}")
            for (pos in 0 until minOf(2, childAdapter?.itemCount ?: 0)) {
                val holder = child.findViewHolderForAdapterPosition(pos)
                val expected = childAdapter?.getItemOrNull(pos)
                val shown = holder?.itemView?.findViewById<TextView>(R.id.imageText)?.text?.toString()
                val drawable = holder?.itemView?.findViewById<ImageView>(R.id.imageView)?.drawable
                info("card", "phase=$phase row=$rowLabel pos=$pos holder=${id(holder)} view=${id(holder?.itemView)} boundPos=${holder?.bindingAdapterPosition} tag=${holder?.itemView?.tag} expected=${expected?.name?.take(55)} expectedOwner=${expected?.apiName} shown=${shown?.take(55)} drawable=${drawable?.javaClass?.simpleName}#${id(drawable)} mismatch=${expected != null && shown != expected.name}")
            }
        }
    }
}
