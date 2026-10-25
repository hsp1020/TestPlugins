package com.hsp1020.youtube

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.widget.ImageView
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ui.result.ActorAdaptor
import com.lagradost.cloudstream3.ui.result.EpisodeAdapter
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.START_ACTION_LOAD_EP
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import java.util.IdentityHashMap
import kotlin.math.roundToInt

internal class YouTubeCardHook(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private var host: FragmentActivity? = null
    private var focusTree: ViewTreeObserver? = null
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        main.post { discoverDialogs() }
    }
    private val screens = mutableMapOf<Fragment, Screen>()
    private val dialogs = mutableMapOf<View, Screen>()
    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentPreCreated(fm: FragmentManager, f: Fragment, state: Bundle?) {
            if (f.javaClass.name !in DETAIL_SCREENS) return
            val args = f.arguments ?: return
            if (args.getString("apiName") != "YouTube") return
            val pageUrl = args.getString("url") ?: return
            val selected = YoutubeProvider.homeShortIndex(pageUrl) ?: return
            val videoId = YoutubeUtils.extractYoutubeId(pageUrl) ?: return
            // Mirrors the core's LoadResponse ID and TvSeries episode ID formula.
            val mainId = pageUrl.replace("https://www.youtube.com", "").replace("/", "").hashCode()
            args.putInt("startAction", START_ACTION_LOAD_EP)
            args.putInt("startValue", mainId + selected + 2)
            args.putString(HOME_SHORT_ID_KEY, videoId)
            args.putInt(HOME_SHORT_NUMBER_KEY, selected + 1)
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            screens[f]?.pagePaused()
        }

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            screens[f]?.pageResumed()
        }

        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, view: View, state: Bundle?) {
            attach(f, view)
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            screens.remove(f)?.close()
        }
    }

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        app.unregisterActivityLifecycleCallbacks(this)
        release()
    }

    private fun release() {
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree = null
        dialogs.values.toList().forEach { it.close() }
        dialogs.clear()
        screens.values.toList().forEach { it.close() }
        screens.clear()
        host = null
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        release()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        activity.window.decorView.viewTreeObserver.also {
            focusTree = it
            if (it.isAlive) it.addOnWindowFocusChangeListener(focusListener)
        }
        main.post { discoverDialogs() }
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                fragment.view?.let { attach(fragment, it) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun attach(fragment: Fragment, root: View) {
        if (fragment.javaClass.name !in SCREENS) return
        if (screens[fragment]?.root === root) return
        screens.remove(fragment)?.close()
        screens[fragment] = Screen(root, fragment = fragment)
    }

    private fun discoverDialogs() {
        val activity = host ?: return
        val windows: List<View> = if (Build.VERSION.SDK_INT >= 29) {
            WindowInspector.getGlobalWindowViews()
        } else {
            runCatching {
                val type = Class.forName("android.view.WindowManagerGlobal")
                val manager = type.getMethod("getInstance").invoke(null)
                @Suppress("UNCHECKED_CAST")
                type.getDeclaredField("mViews").apply { isAccessible = true }
                    .get(manager) as List<View>
            }.getOrDefault(emptyList())
        }
        val active = windows.filter { window ->
            owner(window.context) === activity &&
                window.isAttachedToWindow &&
                (window.findViewById<RecyclerView>(R.id.home_expanded_recycler)
                    ?.adapter as? SearchAdapter)?.immutableCurrentList
                    ?.any { item -> item.apiName == "YouTube" } == true
        }.toSet()
        dialogs.keys.toList().filter { it !in active }.forEach { window ->
            dialogs.remove(window)?.close()
        }
        active.forEach { window ->
            if (!dialogs.containsKey(window)) dialogs[window] = Screen(window, true)
        }
    }

    private fun owner(context: Context): Activity? {
        var current = context
        repeat(12) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

    private inner class Screen(val root: View, popup: Boolean = false, private val fragment: Fragment? = null) {
        private val watchers = mutableMapOf<RecyclerView, Watcher>()
        private val selectedShort = fragment?.arguments?.getString(HOME_SHORT_ID_KEY)
        private var leftDetail = false
        private var focusReturned = false
        private var pendingFocus: String? = null
        private var focusPosted = false

        fun pagePaused() {
            if (selectedShort != null) leftDetail = true
        }

        fun pageResumed() {
            if (!leftDetail || focusReturned || selectedShort == null) return
            leftDetail = false
            focusReturned = true
            pendingFocus = selectedShort
            val host = fragment ?: return
            val list = root.findViewById<RecyclerView>(R.id.result_episodes) ?: return
            val position = (list.adapter as? EpisodeAdapter)?.immutableCurrentList
                ?.indexOfFirst { YoutubeUtils.extractYoutubeId(it.data) == selectedShort } ?: -1
            if (position < 0) {
                val number = host.arguments?.getInt(HOME_SHORT_NUMBER_KEY) ?: return
                val model = ViewModelProvider(host)[ResultViewModel2::class.java]
                model.rangeSelections.value?.firstOrNull {
                    number in it.second.startEpisode..it.second.endEpisode
                }?.second?.let(model::changeRange)
            }
            focusSelectedEpisode(list)
        }

        private fun focusSelectedEpisode(list: RecyclerView) {
            val target = pendingFocus ?: return
            val adapter = list.adapter as? EpisodeAdapter ?: return
            val position = adapter.immutableCurrentList.indexOfFirst {
                YoutubeUtils.extractYoutubeId(it.data) == target
            }
            if (position < 0 || focusPosted) return
            focusPosted = true
            list.scrollToPosition(position)
            list.post {
                focusPosted = false
                if (pendingFocus != target) return@post
                val card = list.findViewHolderForAdapterPosition(position)?.itemView ?: return@post
                var parent = card.parent
                while (parent is View && parent !is NestedScrollView) parent = parent.parent
                if (parent is NestedScrollView) {
                    val rect = Rect(0, 0, card.width, card.height)
                    parent.offsetDescendantRectToMyCoords(card, rect)
                    parent.smoothScrollTo(0, (rect.top - dp(80)).coerceAtLeast(0))
                }
                card.requestFocus()
                pendingFocus = null
            }
        }

        init {
            if (popup) root.findViewById<RecyclerView>(R.id.home_expanded_recycler)
                ?.let { watch(it, Role.DIALOG_GRID) }
            root.findViewById<RecyclerView>(R.id.home_master_recycler)?.let { watch(it, Role.HOME_PARENT) }
            root.findViewById<RecyclerView>(R.id.search_master_recycler)?.let { watch(it, Role.SEARCH_PARENT) }
            root.findViewById<RecyclerView>(R.id.quick_search_master_recycler)?.let { watch(it, Role.SEARCH_PARENT) }
            root.findViewById<RecyclerView>(R.id.search_autofit_results)?.let { watch(it, Role.SEARCH_GRID) }
            root.findViewById<RecyclerView>(R.id.quick_search_autofit_results)?.let { watch(it, Role.SEARCH_GRID) }
            root.findViewById<RecyclerView>(R.id.home_watch_child_recyclerview)?.let { watch(it, Role.RESUME_CARD) }
            if (fragment?.arguments?.getString("apiName") == "YouTube") {
                root.findViewById<RecyclerView>(R.id.result_recommendations_list)?.let { watch(it, Role.DETAIL_RECOMMEND) }
                root.findViewById<RecyclerView>(R.id.result_episodes)?.let { watch(it, Role.DETAIL_EPISODE) }
                root.findViewById<RecyclerView>(R.id.result_cast_items)?.let { watch(it, Role.DETAIL_CAST) }
            }
        }

        fun close() {
            watchers.values.toList().asReversed().forEach { it.close() }
            watchers.clear()
        }

        private fun watch(list: RecyclerView, role: Role) {
            if (watchers.containsKey(list)) return
            Watcher(list, role).also { watchers[list] = it; it.open() }
        }

        private fun unwatch(list: RecyclerView) {
            watchers.remove(list)?.close()
        }

        private inner class Watcher(private val list: RecyclerView, private val role: Role) {
            private val modified = IdentityHashMap<View, CardState>()
            private val episodeSizes = IdentityHashMap<View, Pair<View, Pair<Int, Int>>>()
            private var observed: RecyclerView.Adapter<*>? = null
            private var posted = false

            private val changes = object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = schedule()
                override fun onItemRangeChanged(start: Int, count: Int) = schedule()
                override fun onItemRangeInserted(start: Int, count: Int) = schedule()
                override fun onItemRangeRemoved(start: Int, count: Int) = schedule()
                override fun onItemRangeMoved(from: Int, to: Int, count: Int) = schedule()
            }
            private val children = object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    syncAdapter()
                    apply(view)
                    if (!isParent) view.post {
                        if (view.parent === list && watchers[list] === this@Watcher) apply(view)
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    if (isParent) {
                        view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let(::unwatch)
                        if (role == Role.HOME_PARENT) {
                            view.findViewById<RecyclerView>(R.id.home_watch_child_recyclerview)
                                ?.let(::unwatch)
                        }
                    } else if (role == Role.DETAIL_EPISODE) {
                        restoreEpisode(view)
                    } else if (role != Role.DETAIL_CAST) {
                        restore(view)
                    }
                }
            }
            private val resized = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                schedule()
            }
            private val isParent get() = role == Role.HOME_PARENT || role == Role.SEARCH_PARENT

            fun open() {
                list.addOnChildAttachStateChangeListener(children)
                list.addOnLayoutChangeListener(resized)
                syncAdapter()
                scan()
            }

            fun close() {
                list.removeOnChildAttachStateChangeListener(children)
                list.removeOnLayoutChangeListener(resized)
                observed?.unregisterAdapterDataObserver(changes)
                observed = null
                modified.keys.toList().forEach(::restore)
                modified.clear()
                episodeSizes.keys.toList().forEach(::restoreEpisode)
                posted = false
            }

            private fun syncAdapter() {
                val current = list.adapter
                if (observed === current) return
                observed?.unregisterAdapterDataObserver(changes)
                observed = current
                current?.registerAdapterDataObserver(changes)
            }

            private fun schedule() {
                if (posted) return
                posted = true
                list.post {
                    if (!posted || watchers[list] !== this) return@post
                    posted = false
                    syncAdapter()
                    scan()
                }
            }

            private fun scan() {
                for (i in 0 until list.childCount) apply(list.getChildAt(i))
                if (role == Role.DETAIL_EPISODE) focusSelectedEpisode(list)
            }

            private fun apply(view: View) {
                if (isParent) {
                    view.findViewById<RecyclerView>(R.id.home_child_recyclerview)?.let {
                        watch(it, if (role == Role.HOME_PARENT) Role.HOME_CARD else Role.SEARCH_CARD)
                    }
                    if (role == Role.HOME_PARENT) {
                        view.findViewById<RecyclerView>(R.id.home_watch_child_recyclerview)
                            ?.let { watch(it, Role.RESUME_CARD) }
                    }
                    return
                }

                if (role == Role.DETAIL_EPISODE) return applyEpisode(view)
                if (role == Role.DETAIL_CAST) return applyCast(view)
                val adapter = list.adapter as? BaseAdapter<*, *> ?: return restore(view)
                if (adapter !is SearchAdapter && adapter !is HomeChildItemAdapter) return restore(view)
                val position = list.getChildViewHolder(view).bindingAdapterPosition
                val item = if (position == RecyclerView.NO_POSITION) null
                    else adapter.getItemOrNull(position) as? SearchResponse
                if (item?.apiName != "YouTube") return restore(view)
                val title = view.findViewById<TextView>(R.id.imageText) ?: return
                val card = view.findViewById<View>(R.id.background_card) ?: return
                val image = view.findViewById<ImageView>(R.id.imageView) ?: return
                val state = modified[view] ?: CardState(title, card, image).also { modified[view] = it }
                val expanded = !card.contains(title)
                val lines = if (role == Role.RESUME_CARD) 2 else 4
                if (Build.VERSION.SDK_INT >= 29 && title.isSingleLine) title.setSingleLine(false)
                if (title.maxLines != lines) title.maxLines = lines
                if (title.minLines != 1) title.minLines = 1

                if (role == Role.RESUME_CARD && item is DataStoreHelper.ResumeWatchingResult) {
                    val videoId = YoutubeUtils.extractYoutubeId(item.url)
                    val original = videoId?.let {
                        if (item.url.contains("/shorts/")) YoutubeUtils.getShortsSearchPoster(it)
                        else YoutubeUtils.buildThumbnailFromId(it)
                    }
                    if (original != null && original != item.posterUrl) {
                        item.posterUrl = original
                        image.loadImage(original)
                    }
                }
                val video = item.url.contains("/watch?v=")
                val shorts = item.url.contains("/shorts/")
                if (!video && !shorts) {
                    state.restoreSize()
                    return
                }

                if (role == Role.SEARCH_GRID || role == Role.DIALOG_GRID || role == Role.DETAIL_RECOMMEND) {
                    val baseWidth = (list as? AutofitRecyclerView)?.itemWidth
                        ?.minus(dp(4))?.takeIf { it > 0 }
                        ?: card.width.takeIf { it > 0 } ?: return
                    val width = if (shorts && role == Role.DIALOG_GRID) {
                        minOf(baseWidth, (HomeChildItemAdapter.maxPosterSize * 1.4f).roundToInt()
                            .takeIf { it > 0 } ?: dp(252))
                    } else baseWidth
                    state.setCardWidth(width)
                    state.setImageHeight((width * (if (shorts) 16f / 9f else 9f / 16f)).roundToInt())
                } else {
                    val wide = HomeChildItemAdapter.maxPosterSize.takeIf { it > 0 } ?: dp(180)
                    val narrow = HomeChildItemAdapter.minPosterSize.takeIf { it > 0 } ?: dp(114)
                    state.setCardSize(
                        if (shorts) narrow else wide,
                        if (shorts) (narrow * 16f / 9f).roundToInt() else (wide * 9f / 16f).roundToInt()
                    )
                }
                if (expanded && role != Role.DIALOG_GRID) {
                    val titleWidth = card.layoutParams.width.takeIf { it > 0 }
                        ?: card.width.takeIf { it > 0 }
                    if (titleWidth != null) state.setTitleWidth(titleWidth)
                }
            }

            private fun applyEpisode(view: View) {
                val adapter = list.adapter as? EpisodeAdapter ?: return restoreEpisode(view)
                val position = list.getChildViewHolder(view).bindingAdapterPosition
                val ep = if (position == RecyclerView.NO_POSITION) null
                    else adapter.getItemOrNull(position) as? ResultEpisode
                if (ep?.apiName != "YouTube" || !ep.data.contains("/shorts/"))
                    return restoreEpisode(view)
                val frame = view.findViewById<ImageView>(R.id.episode_poster)?.parent as? View
                    ?: return restoreEpisode(view)
                if (episodeSizes[view]?.first !== frame) {
                    restoreEpisode(view)
                    episodeSizes[view] = frame to (frame.layoutParams.width to frame.layoutParams.height)
                }
                val params = frame.layoutParams
                val width = dp(72)
                val height = dp(128)
                if (params.width != width || params.height != height) {
                    params.width = width
                    params.height = height
                    frame.layoutParams = params
                }
                if (pendingFocus != null) focusSelectedEpisode(list)
            }

            private fun restoreEpisode(view: View) {
                val original = episodeSizes.remove(view) ?: return
                val params = original.first.layoutParams
                if (params.width != original.second.first || params.height != original.second.second) {
                    params.width = original.second.first
                    params.height = original.second.second
                    original.first.layoutParams = params
                }
            }

            private fun applyCast(view: View) {
                val hostFragment = fragment ?: return
                if (hostFragment.arguments?.getString("apiName") != "YouTube") return
                val adapter = list.adapter as? ActorAdaptor ?: return
                val position = list.getChildViewHolder(view).bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val actor = adapter.getItemOrNull(position) as? ActorData ?: return
                val pageUrl = hostFragment.arguments?.getString("url").orEmpty()
                val channel = YoutubeProvider.channelForUrl(pageUrl)
                if (channel != null && position == 0 &&
                    actor.actor.name.equals(channel.name, ignoreCase = true)) {
                    view.setOnClickListener {
                        hostFragment.activity?.loadResult(channel.url, "YouTube", channel.name)
                    }
                } else if (channel == null && position == 0 &&
                    (pageUrl.contains("/channel/") || pageUrl.contains("/@") ||
                        pageUrl.contains("/c/") || pageUrl.contains("/user/"))) {
                    // The channel's own portrait must not open the same page.
                    // Long-click remains the native actor search action.
                    view.setOnClickListener { }
                }
            }

            private fun restore(view: View) {
                modified.remove(view)?.restore()
            }
        }
    }

    private class CardState(
        private val title: TextView,
        private val card: View,
        private val image: ImageView
    ) {
        private val max = title.maxLines
        private val min = title.minLines
        private val cardWidth = card.layoutParams.width
        private val cardHeight = card.layoutParams.height
        private val imageHeight = image.layoutParams.height
        private val titleWidth = title.layoutParams.width
        private var changedTitleWidth = false
        private val horizontal = Build.VERSION.SDK_INT >= 29 && title.isSingleLine
        private var changedCard = false
        private var changedImage = false

        fun setTitleWidth(width: Int) {
            val params = title.layoutParams
            if (params.width != width) {
                params.width = width
                title.layoutParams = params
            }
            changedTitleWidth = true
        }

        fun setCardSize(width: Int, height: Int) {
            val params = card.layoutParams
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                card.layoutParams = params
            }
            changedCard = true
        }

        fun setCardWidth(width: Int) {
            val params = card.layoutParams
            if (params.width != width) {
                params.width = width
                card.layoutParams = params
            }
            changedCard = true
        }

        fun setImageHeight(height: Int) {
            val params = image.layoutParams
            if (params.height != height) {
                params.height = height
                image.layoutParams = params
            }
            changedImage = true
        }

        fun restoreSize() {
            if (changedTitleWidth) {
                val params = title.layoutParams
                if (params.width != titleWidth) {
                    params.width = titleWidth
                    title.layoutParams = params
                }
                changedTitleWidth = false
            }
            if (changedCard) {
                val params = card.layoutParams
                if (params.width != cardWidth || params.height != cardHeight) {
                    params.width = cardWidth
                    params.height = cardHeight
                    card.layoutParams = params
                }
                changedCard = false
            }
            if (changedImage) {
                val params = image.layoutParams
                if (params.height != imageHeight) {
                    params.height = imageHeight
                    image.layoutParams = params
                }
                changedImage = false
            }
        }

        fun restore() {
            title.maxLines = max
            title.minLines = min
            if (horizontal) title.setSingleLine(true)
            restoreSize()
        }
    }

    private fun View.contains(other: View): Boolean {
        var parent = other.parent
        while (parent is View) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }

    private fun dp(value: Int): Int =
        (value * app.resources.displayMetrics.density + 0.5f).toInt()

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) release()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private enum class Role { HOME_PARENT, SEARCH_PARENT, HOME_CARD, SEARCH_CARD, SEARCH_GRID, DIALOG_GRID, RESUME_CARD, DETAIL_RECOMMEND, DETAIL_EPISODE, DETAIL_CAST }

    private companion object {
        const val HOME_SHORT_ID_KEY = "youtube_home_short_video"
        const val HOME_SHORT_NUMBER_KEY = "youtube_home_short_number"
        val DETAIL_SCREENS = setOf(
            "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
            "com.lagradost.cloudstream3.ui.result.ResultTrailerPlayer",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentTv"
        )
        val SCREENS = setOf(
            "com.lagradost.cloudstream3.ui.home.HomeFragment",
            "com.lagradost.cloudstream3.ui.search.SearchFragment",
            "com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
            "com.lagradost.cloudstream3.ui.result.ResultTrailerPlayer",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentTv"
        )
    }
}
