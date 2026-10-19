package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.DataSetObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.ui.search.SearchViewModel
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import org.json.JSONArray

internal class SearchProviderOrderHook(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("ElevateSearchOrder", Context.MODE_PRIVATE)
    private var started = false
    private var host: FragmentActivity? = null
    private var screen: SearchScreen? = null
    private val order = mutableListOf<String>()

    private data class Entry(
        val name: String, val label: String, val nativeIndex: Int, val checked: Boolean
    )

    init {
        val saved = prefs.getString("providers", "[]") ?: "[]"
        runCatching {
            val json = JSONArray(saved)
            for (i in 0 until json.length()) {
                val name = json.optString(i)
                if (name.isNotBlank() && name !in order) order.add(name)
            }
        }
    }

    fun start() {
        main.post {
            if (started) return@post
            started = true
            app.registerActivityLifecycleCallbacks(this)
            (CommonActivity.activity as? MainActivity)?.let(::bindActivity)
        }
    }

    fun stop() {
        main.post {
            if (!started) return@post
            started = false
            app.unregisterActivityLifecycleCallbacks(this)
            unbindActivity()
        }
    }

    private val lifecycle = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
        ) {
            if (f.javaClass.name == SEARCH_FRAGMENT) bindSearch(f, v)
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (screen?.fragment === f) {
                screen?.close()
                screen = null
            }
        }
    }

    private fun bindActivity(next: FragmentActivity) {
        if (!started || host === next) return
        unbindActivity()
        host = next
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(lifecycle, true)
        fun find(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                if (f.javaClass.name == SEARCH_FRAGMENT) f.view?.let { bindSearch(f, it) }
                if (f.isAdded) find(f.childFragmentManager)
            }
        }
        find(next.supportFragmentManager)
    }

    private fun unbindActivity() {
        screen?.close()
        screen = null
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(lifecycle)
        host = null
    }

    private fun bindSearch(fragment: Fragment, root: View) {
        if (screen?.fragment === fragment && screen?.root === root) return
        screen?.close()
        screen = SearchScreen(fragment, root).also { it.open() }
    }

    private fun priority(name: String): Int {
        val index = order.indexOf(name)
        return if (index < 0) Int.MAX_VALUE else index
    }

    private fun storeOrder(visible: List<String>) {
        val names = APIHolder.allProviders.map { it.name }.distinct()
            .sortedBy { it.lowercase() }
        val merged = (order.filter { it in names } + names.filterNot { it in order }).toMutableList()
        val positions = merged.indices.filter { merged[it] in visible }
        if (positions.size != visible.size) return
        positions.forEachIndexed { index, position -> merged[position] = visible[index] }
        order.clear()
        order.addAll(merged)
        prefs.edit().putString("providers", JSONArray(order).toString()).apply()
        screen?.sortResults()
    }

    private inner class SearchScreen(val fragment: Fragment, val root: View) {
        private val results = root.findViewById<RecyclerView>(R.id.search_master_recycler)
        private val filter = root.findViewById<View>(R.id.search_filter)
        private var popup: Popup? = null
        private var model: SearchViewModel? = null
        private val searchChanges =
            Observer<Map<String, com.lagradost.cloudstream3.ui.search.ExpandableSearchList>> { list ->
                if (results?.adapter !is ParentItemAdapter) return@Observer
                val ctx = fragment.context ?: return@Observer
                val entries = list.entries.sortedWith(
                    compareBy<Map.Entry<String, com.lagradost.cloudstream3.ui.search.ExpandableSearchList>> {
                        priority(it.key)
                    }.thenBy { it.key.lowercase() }
                )
                val rows = entries.map { (name, info) ->
                    HomeViewModel.ExpandableHomepageList(
                        HomePageList(name, ctx.filterSearchResultByFilmQuality(info.list)),
                        info.currentPage, info.hasNext
                    )
                }
                (results.adapter as? ParentItemAdapter)?.submitList(rows)
            }
        private val popupWindow = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (!focused) main.post { findPopup() }
        }

        fun open() {
            root.viewTreeObserver.addOnWindowFocusChangeListener(popupWindow)
            filter?.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP)
                    main.post { findPopup() }
                false
            }
            filter?.setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_UP &&
                    key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE)
                ) main.post { findPopup() }
                false
            }
            root.post {
                if (screen !== this) return@post
                runCatching {
                    val vm = ViewModelProvider(fragment.requireActivity())[SearchViewModel::class.java]
                    model = vm
                    vm.currentSearch.observe(fragment.viewLifecycleOwner, searchChanges)
                }
            }
        }

        fun close() {
            popup?.close()
            popup = null
            filter?.setOnTouchListener(null)
            filter?.setOnKeyListener(null)
            model?.currentSearch?.removeObserver(searchChanges)
            model = null
            root.viewTreeObserver.takeIf { it.isAlive }
                ?.removeOnWindowFocusChangeListener(popupWindow)
        }

        fun sortResults() {
            val vm = model ?: return
            val value = vm.currentSearch.value ?: return
            searchChanges.onChanged(value)
        }

        private fun findPopup() {
            if (screen !== this || fragment.view !== root) return
            val activity = fragment.activity ?: return
            val roots = if (Build.VERSION.SDK_INT >= 29) {
                WindowInspector.getGlobalWindowViews()
            } else {
                runCatching {
                    val cls = Class.forName("android.view.WindowManagerGlobal")
                    val obj = cls.getMethod("getInstance").invoke(null)
                    @Suppress("UNCHECKED_CAST")
                    cls.getDeclaredField("mViews").apply { isAccessible = true }
                        .get(obj) as List<View>
                }.getOrDefault(emptyList())
            }
            val native = roots.asSequence()
                .filter { owner(it.context) === activity }
                .firstOrNull {
                    it.findViewById<View>(R.id.tvtypes_chips_scroll) != null &&
                        it.findViewById<ListView>(R.id.listview1) != null
                }?.findViewById<ListView>(R.id.listview1) ?: return
            if (popup?.native === native) return
            popup?.close()
            popup = Popup(native).also { it.open() }
        }

        private fun owner(ctx: Context?): Activity? {
            var next = ctx
            while (next is ContextWrapper) {
                if (next is Activity) return next
                next = next.baseContext
            }
            return next as? Activity
        }

        private inner class Popup(val native: ListView) {
            private val ctx = native.context
            private val list = RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            }
            private val visible = mutableListOf<Entry>()
            private val rowAdapter = OrderAdapter()
            private var observer: DataSetObserver? = null
            private var touchHelper: ItemTouchHelper? = null
            private var updateQueued = false
            private var originalVisibility = native.visibility
            private val detach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    if (popup === this@Popup) {
                        popup = null
                        close()
                    }
                }
            }

            fun open() {
                val parent = native.parent as? FrameLayout ?: return
                val nativeAdapter = native.adapter ?: return
                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(60)
                }
                list.adapter = rowAdapter
                parent.addView(list, parent.indexOfChild(native) + 1, params)
                native.visibility = View.INVISIBLE
                native.addOnAttachStateChangeListener(detach)
                observer = object : DataSetObserver() {
                    override fun onChanged() = queueUpdate()
                    override fun onInvalidated() = queueUpdate()
                }.also(nativeAdapter::registerDataSetObserver)
                val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
                ) {
                    override fun isLongPressDragEnabled() = false
                    override fun onMove(
                        recyclerView: RecyclerView, from: RecyclerView.ViewHolder,
                        to: RecyclerView.ViewHolder
                    ): Boolean {
                        val a = from.bindingAdapterPosition
                        val b = to.bindingAdapterPosition
                        if (a !in visible.indices || b !in visible.indices) return false
                        val moved = visible.removeAt(a)
                        visible.add(b, moved)
                        rowAdapter.notifyItemMoved(a, b)
                        rowAdapter.notifyItemRangeChanged(minOf(a, b), kotlin.math.abs(a - b) + 1)
                        return true
                    }
                    override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
                    override fun clearView(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder) {
                        super.clearView(recyclerView, holder)
                        storeOrder(visible.map { it.name })
                    }
                })
                touchHelper = helper
                helper.attachToRecyclerView(list)
                queueUpdate()
            }

            private fun queueUpdate() {
                if (updateQueued) return
                updateQueued = true
                native.post {
                    updateQueued = false
                    if (popup !== this || !native.isAttachedToWindow) return@post
                    if (list.isComputingLayout) {
                        queueUpdate()
                        return@post
                    }
                    val names = APIHolder.allProviders.map { it.name }.distinct()
                        .sortedByDescending { it.length }
                    val source = native.adapter ?: return@post
                    val next = (0 until source.count).mapNotNull { index ->
                        val label = source.getItem(index)?.toString() ?: return@mapNotNull null
                        val name = names.firstOrNull { label.endsWith(it) } ?: label
                        Entry(name, label, index, native.isItemChecked(index))
                    }
                    val sorted = next.sortedWith(compareBy { entry ->
                        val idx = order.indexOf(entry.name)
                        if (idx < 0) Int.MAX_VALUE else idx
                    })
                    visible.clear()
                    visible.addAll(sorted)
                    rowAdapter.notifyDataSetChanged()
                }
            }

            fun close() {
                native.removeOnAttachStateChangeListener(detach)
                observer?.let { native.adapter?.unregisterDataSetObserver(it) }
                observer = null
                touchHelper?.attachToRecyclerView(null)
                touchHelper = null
                (list.parent as? ViewGroup)?.removeView(list)
                native.visibility = originalVisibility
            }

            private fun move(name: String, destination: Int) {
                val from = visible.indexOfFirst { it.name == name }
                if (from < 0) return
                val to = destination.coerceIn(0, visible.lastIndex)
                if (from == to) {
                    rowAdapter.notifyItemChanged(from)
                    return
                }
                val item = visible.removeAt(from)
                visible.add(to, item)
                rowAdapter.notifyItemMoved(from, to)
                rowAdapter.notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(from - to) + 1)
                storeOrder(visible.map { it.name })
            }

            private inner class OrderAdapter : RecyclerView.Adapter<OrderAdapter.Holder>() {
                inner class Holder(
                    val line: LinearLayout, val number: EditText,
                    val handle: TextView, val checkbox: CheckBox
                ) : RecyclerView.ViewHolder(line)

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                        minimumHeight = dp(52)
                    }
                    val handle = TextView(ctx).apply {
                        text = "≡"
                        textSize = 25f
                        gravity = Gravity.CENTER
                        contentDescription = "Drag to reorder provider"
                        isClickable = true
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(48))
                    }
                    val number = EditText(ctx).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER
                        imeOptions = EditorInfo.IME_ACTION_DONE
                        textSize = 14f
                        setSingleLine(true)
                        gravity = Gravity.CENTER
                        setSelectAllOnFocus(true)
                        layoutParams = LinearLayout.LayoutParams(dp(52), dp(46))
                    }
                    val check = CheckBox(ctx).apply {
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                        )
                    }
                    row.addView(handle)
                    row.addView(number)
                    row.addView(check)
                    return Holder(row, number, handle, check).also { holder ->
                        handle.setOnTouchListener { _, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                                holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                                touchHelper?.startDrag(holder)
                            }
                            false
                        }
                    }
                }
                override fun getItemCount() = visible.size

                override fun onBindViewHolder(holder: Holder, position: Int) {
                    val entry = visible[position]
                    holder.checkbox.setOnCheckedChangeListener(null)
                    holder.checkbox.text = entry.label
                    holder.checkbox.isChecked = native.isItemChecked(entry.nativeIndex)
                    holder.checkbox.setOnCheckedChangeListener { _, _ ->
                        native.performItemClick(null, entry.nativeIndex,
                            native.adapter?.getItemId(entry.nativeIndex) ?: entry.nativeIndex.toLong())
                        queueUpdate()
                    }
                    holder.number.onFocusChangeListener = null
                    if (!holder.number.hasFocus()) holder.number.setText((position + 1).toString())
                    holder.number.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
                        if (!focused) {
                            val value = holder.number.text.toString().toIntOrNull()
                            if (value != null) move(entry.name, value - 1)
                            else holder.number.setText(
                                (visible.indexOfFirst { it.name == entry.name } + 1).toString()
                            )
                        }
                    }
                    holder.number.setOnEditorActionListener { _, action, _ ->
                        if (action == EditorInfo.IME_ACTION_DONE) {
                            holder.number.clearFocus()
                            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                                as? InputMethodManager)?.hideSoftInputFromWindow(
                                holder.number.windowToken, 0
                            )
                            true
                        } else false
                    }
                }
            }

            private fun dp(value: Int): Int =
                (value * ctx.resources.displayMetrics.density + 0.5f).toInt()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) bindActivity(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) unbindActivity()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private companion object {
        const val SEARCH_FRAGMENT = "com.lagradost.cloudstream3.ui.search.SearchFragment"
    }
}
