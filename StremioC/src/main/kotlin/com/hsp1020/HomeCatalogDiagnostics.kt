package com.hsp1020

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.util.IdentityHashMap

internal class HomeCatalogDiagnostics(
    private val application: Application
) : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var host: FragmentActivity? = null
    private var home: Fragment? = null
    private var homeRoot: View? = null
    private var model: HomeViewModel? = null
    private var master: RecyclerView? = null
    private var owner: androidx.lifecycle.LifecycleOwner? = null
    private var parentObserved: RecyclerView.Adapter<*>? = null
    private val children = IdentityHashMap<RecyclerView, RowMonitor>()
    private var session = 0
    private var seq = 0
    private var providerName: String? = null
    private var lastVisibleSignature: String? = null
    private var sampleAfter = 0L
    private var lastFrameSample = 0L
    private var scheduledBatch = 0
    private var running = false

    private fun id(value: Any?): String = if (value == null) "null"
        else Integer.toHexString(System.identityHashCode(value))

    private fun url(value: String?): String {
        if (value == null) return "null"
        val raw = value.take(500)
        val q = raw.indexOf('?')
        return if (q < 0) raw else raw.substring(0, q) + "?qhash=" + raw.substring(q).hashCode()
    }

    private fun say(event: String, message: String) {
        val prefix = "s=$session n=${++seq} t=${SystemClock.uptimeMillis()} provider=${providerName ?: "?"} $event "
        val line = prefix + message.replace('\n', ' ')
        if (line.length <= 3500) {
            Log.i("SC_HOME_TRACE", line)
        } else {
            line.chunked(3400).forEachIndexed { i, part ->
                Log.i("SC_HOME_TRACE", "part=${i + 1} $part")
            }
        }
    }

    private fun card(label: String, index: Int, item: SearchResponse) {
        say(label, "i=$index owner=${item.apiName} name=${item.name.take(100)} url=${url(item.url)} poster=${url(item.posterUrl)} posterHeaders=${item.posterHeaders?.keys}")
    }

    private val pageObserver = Observer<Resource<Map<String, HomeViewModel.ExpandableHomepageList>>> { value ->
        session++
        say("MODEL_PAGE", "state=${value?.javaClass?.simpleName} apiName=${model?.apiName?.value} saved=${DataStoreHelper.currentHomePage} repo=${model?.repo?.api?.name} valueId=${id(value)}")
        if (value is Resource.Success) {
            say("MODEL_MAP", "mapId=${id(value.value)} categories=${value.value.size} keys=${value.value.keys.joinToString("|")}")
            value.value.forEach { (key, group) ->
                val cards = group.list.list
                say("MODEL_ROW", "key=$key list=${group.list.name} groupId=${id(group)} cardsId=${id(cards)} count=${cards.size} page=${group.currentPage} next=${group.hasNext} firstOwner=${cards.firstOrNull()?.apiName}")
                cards.forEachIndexed { i, item -> card("MODEL_CARD:$key", i, item) }
            }
        } else if (value is Resource.Failure) {
            say("MODEL_ERROR", "error=${value.errorString}")
        }
        schedule("page")
    }

    private val apiObserver = Observer<String> { name ->
        val previous = providerName
        providerName = name
        session++
        say("PROVIDER", "from=$previous to=$name saved=${DataStoreHelper.currentHomePage} repo=${model?.repo?.api?.name}")
        schedule("provider")
    }

    private val previewObserver = Observer<Resource<Pair<Boolean, List<LoadResponse>>>> { value ->
        val size = if (value is Resource.Success) value.value.second.size else -1
        val owners = if (value is Resource.Success) value.value.second.take(15).joinToString("|") { it.apiName + ":" + it.name.take(35) } else "-"
        say("PREVIEW", "state=${value?.javaClass?.simpleName} count=$size entries=$owners")
        schedule("preview")
    }

    private val parentObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = parentEvent("changed", 0, 0)
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = parentEvent("inserted", positionStart, itemCount)
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = parentEvent("removed", positionStart, itemCount)
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = parentEvent("changedRange", positionStart, itemCount)
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = parentEvent("moved:$toPosition", fromPosition, itemCount)
    }

    private fun parentEvent(what: String, position: Int, count: Int) {
        say("PARENT_DIFF", "op=$what position=$position count=$count adapterSize=${parentObserved?.itemCount} adapter=${id(parentObserved)}")
        dumpParent("diff:$what")
        schedule("parent:$what")
    }

    private val masterScroll = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            say("PARENT_SCROLL", "state=$newState scrollY=${recyclerView.computeVerticalScrollOffset()}")
            snapshot("parent-scroll-state", true)
        }
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy != 0 || dx != 0) {
                say("PARENT_SCROLL", "dx=$dx dy=$dy scrollY=${recyclerView.computeVerticalScrollOffset()}")
                sampleAfter = SystemClock.uptimeMillis() + 2500
            }
        }
    }

    private val masterChildListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            say("PARENT_CHILD_ATTACH", "view=${id(view)}")
            syncRows()
            snapshot("parent-attach", true)
        }
        override fun onChildViewDetachedFromWindow(view: View) {
            say("PARENT_CHILD_DETACH", "view=${id(view)}")
            schedule("parent-detach")
        }
    }

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        syncRows()
        snapshot("layout", false)
    }

    private val drawListener = ViewTreeObserver.OnPreDrawListener {
        val now = SystemClock.uptimeMillis()
        if (now < sampleAfter && now - lastFrameSample >= 180) {
            lastFrameSample = now
            syncRows()
            snapshot("frame", false)
        }
        true
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?) {
            if (fragment.javaClass.name == HOME_CLASS) attach(fragment, view)
        }
        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            if (fragment.javaClass.name == HOME_CLASS) {
                say("HOME_RESUME", "view=${id(fragment.view)}")
                fragment.view?.let { attach(fragment, it) }
                snapshot("resume", true)
            }
        }
        override fun onFragmentPaused(fm: FragmentManager, fragment: Fragment) {
            if (fragment === home) {
                snapshot("pause", true)
                say("HOME_PAUSE", "view=${id(fragment.view)}")
            }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (fragment === home) detach()
        }
    }

    fun start() {
        handler.post {
            if (running) return@post
            running = true
            application.registerActivityLifecycleCallbacks(this)
            say("START", "installed=true")
            (CommonActivity.activity as? MainActivity)?.let(::use)
        }
    }

    fun stop() {
        handler.post {
            if (!running) return@post
            running = false
            detach()
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
            host = null
            application.unregisterActivityLifecycleCallbacks(this)
            say("STOP", "installed=false")
        }
    }

    private fun use(activity: FragmentActivity) {
        if (!running) return
        if (host === activity) return
        detach()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        host = activity
        say("ACTIVITY", "attach=${id(activity)}")
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { fragment ->
                if (fragment.javaClass.name == HOME_CLASS) fragment.view?.let { attach(fragment, it) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun attach(fragment: Fragment, root: View) {
        if (home === fragment && homeRoot === root) return
        detach()
        home = fragment
        homeRoot = root
        owner = fragment.viewLifecycleOwner
        model = host?.let { ViewModelProvider(it)[HomeViewModel::class.java] }
        say("HOME_ATTACH", "view=${id(root)} model=${id(model)} size=${root.width}x${root.height} orientation=${root.resources.configuration.orientation}")
        model?.apiName?.observe(fragment.viewLifecycleOwner, apiObserver)
        model?.page?.observe(fragment.viewLifecycleOwner, pageObserver)
        model?.preview?.observe(fragment.viewLifecycleOwner, previewObserver)
        master = root.findViewById(R.id.home_master_recycler)
        master?.let {
            say("RECYCLER_ATTACH", "master=${id(it)} adapter=${id(it.adapter)} recycledPool=${id(it.recycledViewPool)}")
            it.addOnScrollListener(masterScroll)
            it.addOnChildAttachStateChangeListener(masterChildListener)
            if (it.viewTreeObserver.isAlive) {
                it.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
                it.viewTreeObserver.addOnPreDrawListener(drawListener)
            }
        }
        syncRows()
        snapshot("attach", true)
        schedule("attach")
    }

    private fun detach() {
        if (home != null) say("HOME_DETACH", "view=${id(homeRoot)}")
        model?.apiName?.removeObserver(apiObserver)
        model?.page?.removeObserver(pageObserver)
        model?.preview?.removeObserver(previewObserver)
        parentObserved?.unregisterAdapterDataObserver(parentObserver)
        parentObserved = null
        children.values.toList().forEach { it.stop() }
        children.clear()
        master?.let {
            it.removeOnScrollListener(masterScroll)
            it.removeOnChildAttachStateChangeListener(masterChildListener)
            if (it.viewTreeObserver.isAlive) {
                it.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
                it.viewTreeObserver.removeOnPreDrawListener(drawListener)
            }
        }
        master = null
        homeRoot = null
        home = null
        owner = null
        model = null
        lastVisibleSignature = null
        sampleAfter = 0
        scheduledBatch++
    }

    private fun schedule(reason: String) {
        sampleAfter = SystemClock.uptimeMillis() + 12000L
        lastVisibleSignature = null
        val batch = ++scheduledBatch
        say("SCHEDULE", "reason=$reason batch=$batch")
        val reference = homeRoot
        for (delay in listOf(0L, 80L, 200L, 500L, 1000L, 2000L, 4000L, 8000L, 12000L)) {
            handler.postDelayed({
                if (running && batch == scheduledBatch && reference != null && homeRoot === reference) {
                    syncRows()
                    snapshot("timer:$reason:$delay", true)
                }
            }, delay)
        }
    }

    private fun dumpParent(reason: String) {
        val adapter = parentObserved as? ParentItemAdapter ?: return
        say("PARENT_LIST", "reason=$reason id=${id(adapter)} count=${adapter.immutableCurrentList.size} displayed=${master?.isShown}")
        adapter.immutableCurrentList.forEachIndexed { i, item ->
            val cards = item.list.list
            say("PARENT_ROW", "i=$i name=${item.list.name} count=${cards.size} cardsId=${id(cards)} first=${cards.firstOrNull()?.apiName} page=${item.currentPage} next=${item.hasNext}")
            cards.forEachIndexed { n, card -> card("ADAPTER_CARD:${item.list.name}", n, card) }
        }
    }

    private fun syncRows() {
        val parent = master ?: return
        if (parentObserved !== parent.adapter) {
            parentObserved?.unregisterAdapterDataObserver(parentObserver)
            parentObserved = parent.adapter
            parentObserved?.registerAdapterDataObserver(parentObserver)
            say("PARENT_ADAPTER", "adapter=${id(parent.adapter)} count=${parent.adapter?.itemCount} pool=${id(parent.recycledViewPool)}")
            dumpParent("adapter-change")
        }
        val visible = HashSet<RecyclerView>()
        for (i in 0 until parent.childCount) {
            val view = parent.getChildAt(i)
            val child = view.findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: continue
            visible.add(child)
            val old = children[child]
            if (old == null || old.adapter !== child.adapter) {
                old?.stop()
                val monitor = RowMonitor(child, view)
                children[child] = monitor
                monitor.start()
            }
        }
        children.keys.toList().filter { it !in visible }.forEach {
            children.remove(it)?.stop()
        }
    }

    private inner class RowMonitor(
        private val recycler: RecyclerView,
        private val rowView: View
    ) {
        val adapter = recycler.adapter
        private val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = changed("all", 0, 0)
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = changed("insert", positionStart, itemCount)
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = changed("remove", positionStart, itemCount)
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = changed("change", positionStart, itemCount)
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = changed("move:$toPosition", fromPosition, itemCount)
        }
        private val childEvents = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                say("CARD_ATTACH", "row=${rowName()} recycler=${id(recycler)} view=${id(view)}")
                snapshot("card-attach", false)
            }
            override fun onChildViewDetachedFromWindow(view: View) {
                say("CARD_DETACH", "row=${rowName()} recycler=${id(recycler)} view=${id(view)}")
                snapshot("card-detach", false)
            }
        }
        private val scroll = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                say("CARD_SCROLL", "row=${rowName()} state=$newState offset=${recyclerView.computeHorizontalScrollOffset()}")
                snapshot("card-scroll-state", true)
            }
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx != 0 || dy != 0) {
                    say("CARD_SCROLL", "row=${rowName()} dx=$dx dy=$dy offset=${recyclerView.computeHorizontalScrollOffset()}")
                    sampleAfter = SystemClock.uptimeMillis() + 3000L
                }
            }
        }
        private fun rowName() = rowView.findViewById<TextView>(R.id.home_child_more_info)?.text?.toString()?.take(90)

        fun start() {
            adapter?.registerAdapterDataObserver(observer)
            recycler.addOnChildAttachStateChangeListener(childEvents)
            recycler.addOnScrollListener(scroll)
            say("ROW_ATTACH", "row=${rowName()} recycler=${id(recycler)} rowView=${id(rowView)} adapter=${id(adapter)} pool=${id(recycler.recycledViewPool)}")
            dump("attach")
        }

        fun stop() {
            say("ROW_DETACH", "row=${rowName()} recycler=${id(recycler)} adapter=${id(adapter)}")
            adapter?.unregisterAdapterDataObserver(observer)
            recycler.removeOnChildAttachStateChangeListener(childEvents)
            recycler.removeOnScrollListener(scroll)
        }

        private fun changed(operation: String, start: Int, count: Int) {
            say("CHILD_DIFF", "row=${rowName()} op=$operation start=$start count=$count adapter=${id(adapter)} size=${adapter?.itemCount}")
            dump("diff:$operation")
            schedule("child:$operation")
        }

        fun dump(why: String) {
            val cards = (adapter as? HomeChildItemAdapter)?.immutableCurrentList
            say("CHILD_LIST", "why=$why row=${rowName()} rowView=${id(rowView)} adapter=${id(adapter)} cardsId=${id(cards)} count=${cards?.size} offset=${recycler.computeHorizontalScrollOffset()}")
            cards?.forEachIndexed { index, response -> card("CHILD_CARD:${rowName()}", index, response) }
        }
    }

    private fun snapshot(reason: String, forced: Boolean) {
        val parent = master ?: return
        val adapter = parent.adapter as? ParentItemAdapter
        val state = model?.page?.value
        val signature = StringBuilder()
        val visible = ArrayList<String>()
        val bitmaps = HashMap<String, MutableList<String>>()
        for (rowIndex in 0 until parent.childCount) {
            val row = parent.getChildAt(rowIndex)
            val child = row.findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: continue
            val parentPosition = runCatching { parent.getChildViewHolder(row).bindingAdapterPosition }.getOrDefault(-1)
            val parentItem = adapter?.getItemOrNull(parentPosition)
            val childAdapter = child.adapter as? HomeChildItemAdapter
            val rowTitle = row.findViewById<TextView>(R.id.home_child_more_info)?.text?.toString().orEmpty()
            val rowHeader = "rowView=${id(row)} rowPos=$parentPosition rowName=${rowTitle.take(80)} modelName=${parentItem?.list?.name} child=${id(child)} adapter=${id(child.adapter)} childCount=${child.childCount} dataCount=${childAdapter?.immutableCurrentList?.size} offset=${child.computeHorizontalScrollOffset()}"
            visible.add("VIEW_ROW $rowHeader")
            signature.append(rowHeader)
            for (index in 0 until child.childCount) {
                val cardView = child.getChildAt(index)
                val position = runCatching { child.getChildViewHolder(cardView).bindingAdapterPosition }.getOrDefault(-1)
                val card = childAdapter?.getItemOrNull(position)
                val poster = cardView.findViewById<ImageView>(R.id.imageView)
                val renderedName = cardView.findViewById<TextView>(R.id.imageText)?.text?.toString()?.take(55)
                val drawable = poster?.drawable
                val drawableKey = id(drawable?.constantState ?: drawable)
                val cardLine = "view=${id(cardView)} pos=$position tag=${cardView.tag} renderedName=$renderedName responseOwner=${card?.apiName} responseUrl=${url(card?.url)} responseName=${card?.name?.take(55)} expectedPoster=${url(card?.posterUrl)} imageView=${id(poster)} drawable=${drawable?.javaClass?.simpleName} dId=$drawableKey dSize=${drawable?.intrinsicWidth}x${drawable?.intrinsicHeight} imageShown=${poster?.isShown} attached=${cardView.isAttachedToWindow} transient=${cardView.hasTransientState()} alpha=${poster?.alpha}"
                visible.add("VIEW_CARD row=${rowTitle.take(50)} $cardLine")
                signature.append(cardLine)
                if (drawable != null && drawable.javaClass.simpleName.contains("Bitmap", true)) {
                    bitmaps.getOrPut(drawableKey) { mutableListOf() }.add("${rowTitle.take(35)}:$position:${card?.name?.take(25)}")
                }
                if (card != null && model?.apiName?.value != null && card.apiName != model?.apiName?.value) {
                    visible.add("OWNER_MISMATCH selected=${model?.apiName?.value} row=$rowTitle pos=$position itemOwner=${card.apiName} item=${url(card.url)}")
                }
                if (parentItem != null && card != null && parentItem.list.list.none { it.url == card.url }) {
                    visible.add("ROW_LIST_MISMATCH row=$rowTitle pos=$position card=${url(card.url)}")
                }
            }
        }
        val key = signature.toString()
        if (!forced && lastVisibleSignature == key) return
        lastVisibleSignature = key
        say("VIEW_SNAPSHOT", "reason=$reason pageState=${state?.javaClass?.simpleName} animatorRunning=${parent.itemAnimator?.isRunning} computingLayout=${parent.isComputingLayout} saved=${DataStoreHelper.currentHomePage} model=${model?.apiName?.value} masterVisible=${parent.isShown} viewSize=${parent.width}x${parent.height} adapter=${id(parent.adapter)} adapterCount=${parent.adapter?.itemCount} attachedRows=${parent.childCount} rvPool=${id(parent.recycledViewPool)}")
        visible.forEach { say(it.substringBefore(' '), it.substringAfter(' ', "")) }
        bitmaps.filterValues { it.size > 1 }.forEach { (drawable, usages) ->
            say("SHARED_BITMAP", "drawable=$drawable views=${usages.joinToString("|")}")
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (activity === host) {
            detach()
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
            host = null
            say("ACTIVITY", "destroy=${id(activity)}")
        }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        const val HOME_CLASS = "com.lagradost.cloudstream3.ui.home.HomeFragment"
    }
}
