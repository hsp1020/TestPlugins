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
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.ui.search.ExpandableSearchList
import com.lagradost.cloudstream3.ui.search.SearchViewModel
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import org.json.JSONArray

internal class SearchProviderOrderHook(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("ElevateSearchOrder", Context.MODE_PRIVATE)
    private val order = mutableListOf<String>()
    private var started = false
    private var activity: FragmentActivity? = null
    private var home: HomeScreen? = null
    private var search: SearchScreen? = null

    private data class Row(val name: String, val nativeIndex: Int)

    init {
        runCatching {
            val saved = JSONArray(prefs.getString("providers", "[]") ?: "[]")
            repeat(saved.length()) { i ->
                val name = saved.optString(i)
                if (name.isNotBlank() && name !in order) order.add(name)
            }
        }
    }

    fun start() {
        handler.post {
            if (started) return@post
            started = true
            app.registerActivityLifecycleCallbacks(this)
            (CommonActivity.activity as? MainActivity)?.let(::attachActivity)
        }
    }

    fun stop() {
        handler.post {
            if (!started) return@post
            started = false
            app.unregisterActivityLifecycleCallbacks(this)
            detachActivity()
        }
    }

    private fun rank(name: String): Int {
        val index = order.indexOf(name)
        return if (index < 0) Int.MAX_VALUE else index
    }

    private fun saveVisible(visible: List<String>) {
        val all = (order + APIHolder.allProviders.filter { it.hasMainPage }
            .map { it.name }.sortedBy { it.lowercase() }).distinct().toMutableList()
        val names = visible.toSet()
        val positions = all.indices.filter { all[it] in names }
        if (positions.size != visible.size) return
        positions.forEachIndexed { index, pos -> all[pos] = visible[index] }
        order.clear()
        order.addAll(all)
        prefs.edit().putString("providers", JSONArray(order).toString()).apply()
        search?.refresh()
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, saved: Bundle?
        ) {
            when (fragment.javaClass.name) {
                HOME_CLASS -> attachHome(fragment, view)
                SEARCH_CLASS -> attachSearch(fragment, view)
            }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (home?.fragment === fragment) {
                home?.close()
                home = null
            }
            if (search?.fragment === fragment) {
                search?.close()
                search = null
            }
        }
    }

    private fun attachActivity(next: FragmentActivity) {
        if (!started || activity === next) return
        detachActivity()
        activity = next
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                fragment.view?.let { view ->
                    when (fragment.javaClass.name) {
                        HOME_CLASS -> attachHome(fragment, view)
                        SEARCH_CLASS -> attachSearch(fragment, view)
                    }
                }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
    }

    private fun detachActivity() {
        home?.close()
        home = null
        search?.close()
        search = null
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        activity = null
    }

    private fun attachHome(fragment: Fragment, root: View) {
        if (home?.fragment === fragment && home?.root === root) return
        home?.close()
        home = HomeScreen(fragment, root).also { it.open() }
    }

    private fun attachSearch(fragment: Fragment, root: View) {
        if (search?.fragment === fragment && search?.root === root) return
        search?.close()
        search = SearchScreen(fragment, root).also { it.open() }
    }

    private inner class SearchScreen(val fragment: Fragment, val root: View) {
        private val recycler = root.findViewById<RecyclerView>(R.id.search_master_recycler)
        private var vm: SearchViewModel? = null
        private val changes = Observer<Map<String, ExpandableSearchList>> { lists ->
            val context = fragment.context ?: return@Observer
            val adapter = recycler?.adapter as? ParentItemAdapter ?: return@Observer
            val rows = lists.entries.sortedWith(
                compareBy<Map.Entry<String, ExpandableSearchList>> { rank(it.key) }
                    .thenBy { it.key.lowercase() }
            ).map { (name, value) ->
                HomeViewModel.ExpandableHomepageList(
                    HomePageList(name, context.filterSearchResultByFilmQuality(value.list)),
                    value.currentPage,
                    value.hasNext
                )
            }
            adapter.submitList(rows)
        }

        fun open() {
            root.post {
                if (search !== this || fragment.view !== root) return@post
                runCatching {
                    val model = ViewModelProvider(fragment.requireActivity())[SearchViewModel::class.java]
                    vm = model
                    model.currentSearch.observe(fragment.viewLifecycleOwner, changes)
                }
            }
        }

        fun refresh() {
            vm?.currentSearch?.value?.let(changes::onChanged)
        }

        fun close() {
            vm?.currentSearch?.removeObserver(changes)
            vm = null
        }
    }

    private inner class HomeScreen(val fragment: Fragment, val root: View) {
        private var popup: Popup? = null
        private val windowFocus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (!focused) handler.post { findPopup() }
        }
        private val selectors = listOfNotNull(
            root.findViewById<View>(R.id.home_api_fab),
            root.findViewById<View>(R.id.home_change_api)
        )

        fun open() {
            root.viewTreeObserver.addOnWindowFocusChangeListener(windowFocus)
            selectors.forEach { button ->
                button.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_UP)
                        handler.post { findPopup() }
                    false
                }
                button.setOnKeyListener { _, key, event ->
                    if (event.action == KeyEvent.ACTION_UP &&
                        key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
                    ) handler.post { findPopup() }
                    false
                }
            }
        }

        fun close() {
            popup?.close()
            popup = null
            selectors.forEach {
                it.setOnTouchListener(null)
                it.setOnKeyListener(null)
            }
            root.viewTreeObserver.takeIf { it.isAlive }
                ?.removeOnWindowFocusChangeListener(windowFocus)
        }

        private fun findPopup() {
            if (home !== this || fragment.view !== root || !fragment.isVisible) return
            val host = fragment.activity ?: return
            val windows: List<View> = if (Build.VERSION.SDK_INT >= 29) {
                WindowInspector.getGlobalWindowViews()
            } else {
                runCatching {
                    val clazz = Class.forName("android.view.WindowManagerGlobal")
                    val instance = clazz.getMethod("getInstance").invoke(null)
                    @Suppress("UNCHECKED_CAST")
                    clazz.getDeclaredField("mViews").apply { isAccessible = true }
                        .get(instance) as List<View>
                }.getOrDefault(emptyList())
            }
            val native = windows.asSequence()
                .filter { owner(it.context) === host }
                .mapNotNull { window -> window.findViewById<ListView>(R.id.listview1) }
                .firstOrNull { list ->
                    val adapter = list.adapter ?: return@firstOrNull false
                    if (adapter.count < 2) return@firstOrNull false
                    val row = runCatching { adapter.getView(0, null, list) }.getOrNull()
                    row?.findViewById<View>(R.id.pinicon) != null
                } ?: return
            if (popup?.native === native) return
            popup?.close()
            popup = Popup(native).also {
                popup = it
                it.open()
            }
        }

        private inner class Popup(val native: ListView) {
            private val context = native.context
            private val list = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
            }
            private val entries = mutableListOf<Row>()
            private val adapter = Rows()
            private var touchHelper: ItemTouchHelper? = null
            private var data: DataSetObserver? = null
            private var queued = false
            private var closed = false
            private val oldVisibility = native.visibility
            private val detach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    handler.post {
                        if (popup === this@Popup) {
                            popup = null
                            close()
                        }
                    }
                }
            }

            fun open() {
                val frame = native.parent as? FrameLayout ?: return
                list.adapter = adapter
                frame.addView(list, frame.indexOfChild(native) + 1, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(60)
                })
                native.visibility = View.INVISIBLE
                native.addOnAttachStateChangeListener(detach)
                data = object : DataSetObserver() {
                    override fun onChanged() = update()
                    override fun onInvalidated() = update()
                }.also { native.adapter?.registerDataSetObserver(it) }
                touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
                ) {
                    override fun isLongPressDragEnabled() = false
                    override fun onMove(
                        rv: RecyclerView, source: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder
                    ): Boolean {
                        val from = source.bindingAdapterPosition
                        val to = target.bindingAdapterPosition
                        if (from !in entries.indices || to !in entries.indices ||
                            from < 2 || to < 2) return false
                        val row = entries.removeAt(from)
                        entries.add(to, row)
                        adapter.notifyItemMoved(from, to)
                        adapter.notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(from - to) + 1)
                        return true
                    }
                    override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                        super.clearView(rv, vh)
                        saveOrder()
                    }
                    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit
                }).also { it.attachToRecyclerView(list) }
                update()
            }

            fun close() {
                if (closed) return
                closed = true
                native.removeOnAttachStateChangeListener(detach)
                data?.let { observer -> native.adapter?.unregisterDataSetObserver(observer) }
                data = null
                touchHelper?.attachToRecyclerView(null)
                touchHelper = null
                (list.parent as? ViewGroup)?.removeView(list)
                if (native.isAttachedToWindow) native.visibility = oldVisibility
            }

            private fun update() {
                if (queued) return
                queued = true
                native.post {
                    queued = false
                    if (popup !== this || !native.isAttachedToWindow) return@post
                    val source = native.adapter ?: return@post
                    val names = APIHolder.allProviders.filter { it.hasMainPage }
                        .map { it.name }.distinct().sortedByDescending { it.length }
                    val next = (0 until source.count).map { index ->
                        val label = source.getItem(index)?.toString().orEmpty()
                        val name = if (index < 2) label
                            else names.firstOrNull { label.endsWith(it) } ?: label
                        Row(name, index)
                    }
                    val specials = next.take(2)
                    val ordered = next.drop(2).sortedWith(
                        compareBy<Row> { rank(it.name) }.thenBy { it.name.lowercase() }
                    )
                    entries.clear()
                    entries.addAll(specials + ordered)
                    adapter.notifyDataSetChanged()
                }
            }

            private fun saveOrder() {
                saveVisible(entries.drop(2).map { it.name })
            }

            private fun move(name: String, destination: Int) {
                val from = entries.indexOfFirst { it.name == name }
                if (from < 2) return
                val to = destination.coerceIn(2, entries.lastIndex)
                if (from == to) {
                    adapter.notifyItemChanged(from)
                    return
                }
                val row = entries.removeAt(from)
                entries.add(to, row)
                adapter.notifyItemMoved(from, to)
                adapter.notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(from - to) + 1)
                saveOrder()
            }

            private inner class Rows : RecyclerView.Adapter<Rows.Holder>() {
                inner class Holder(
                    val outer: LinearLayout,
                    val handle: TextView,
                    val number: EditText,
                    val content: FrameLayout
                ) : RecyclerView.ViewHolder(outer)

                override fun getItemCount() = entries.size

                override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
                    val layout = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(52)
                        layoutParams = RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    val handle = TextView(context).apply {
                        text = "≡"
                        textSize = 25f
                        gravity = Gravity.CENTER
                        isFocusable = false
                        contentDescription = "Drag provider to reorder"
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(48))
                    }
                    val number = EditText(context).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER
                        imeOptions = EditorInfo.IME_ACTION_DONE
                        setSingleLine(true)
                        setSelectAllOnFocus(true)
                        if (context.resources.configuration.uiMode and
                                android.content.res.Configuration.UI_MODE_TYPE_MASK ==
                            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
                            showSoftInputOnFocus = false
                        }
                        gravity = Gravity.CENTER
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(dp(52), dp(44))
                    }
                    val content = FrameLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                        )
                    }
                    layout.addView(handle)
                    layout.addView(number)
                    layout.addView(content)
                    return Holder(layout, handle, number, content).also { holder ->
                        handle.setOnTouchListener { _, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                                holder.bindingAdapterPosition >= 2)
                                touchHelper?.startDrag(holder)
                            false
                        }
                    }
                }

                override fun onBindViewHolder(holder: Holder, pos: Int) {
                    val item = entries[pos]
                    holder.handle.visibility = if (pos < 2) View.INVISIBLE else View.VISIBLE
                    holder.number.visibility = if (pos < 2) View.INVISIBLE else View.VISIBLE
                    holder.number.onFocusChangeListener = null
                    holder.number.setText((pos - 1).toString())
                    holder.number.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
                        if (!focused && pos >= 2) {
                            val number = holder.number.text.toString().toIntOrNull()
                            if (number != null) move(item.name, number + 1)
                        }
                    }
                    holder.number.setOnEditorActionListener { _, action, _ ->
                        if (action == EditorInfo.IME_ACTION_DONE) {
                            holder.number.clearFocus()
                            (context.getSystemService(Context.INPUT_METHOD_SERVICE)
                                as? InputMethodManager)?.hideSoftInputFromWindow(
                                    holder.number.windowToken, 0
                                )
                            true
                        } else false
                    }
                    val original = native.adapter?.getView(item.nativeIndex, null, native) ?: return
                    holder.content.removeAllViews()
                    (original.parent as? ViewGroup)?.removeView(original)
                    holder.content.addView(original, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                    original.setOnClickListener {
                        native.performItemClick(original, item.nativeIndex,
                            native.adapter?.getItemId(item.nativeIndex) ?: item.nativeIndex.toLong())
                    }
                    original.setOnLongClickListener {
                        native.onItemLongClickListener?.onItemLongClick(
                            native, original, item.nativeIndex,
                            native.adapter?.getItemId(item.nativeIndex) ?: item.nativeIndex.toLong()
                        ) ?: false
                    }
                }
            }

            private fun dp(dp: Int) = (dp * context.resources.displayMetrics.density + 0.5f).toInt()
        }
    }

    private fun owner(context: Context?): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? Activity
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) attachActivity(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity) detachActivity()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private companion object {
        const val HOME_CLASS = "com.lagradost.cloudstream3.ui.home.HomeFragment"
        const val SEARCH_CLASS = "com.lagradost.cloudstream3.ui.search.SearchFragment"
    }
}
