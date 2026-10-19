package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.navigation.NavigationBarView
import com.hsp1020.pip.PipSubtitleLifecycle
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.UIHelper.navigate

@CloudstreamPlugin
class ElevatePlugin : Plugin() {
    private var hook: NativeDetailSearchHook? = null
    private var pipSubtitles: PipSubtitleLifecycle? = null
    private var episodeProgress: EpisodeProgressHook? = null
    private var subtitleRetry: SubtitleRefererRetry? = null
    private var homeRecyclerGuard: HomeRecyclerCacheGuard? = null

    override fun load(context: Context) {
        hook?.stop()
        pipSubtitles?.stop()
        episodeProgress?.stop()
        subtitleRetry?.stop()
        homeRecyclerGuard?.stop()
        val application = context.applicationContext as? Application
        hook = application?.let { NativeDetailSearchHook(it).also(NativeDetailSearchHook::start) }
        pipSubtitles = application?.let { PipSubtitleLifecycle(it) { true }.also { service -> service.start() } }
        episodeProgress = application?.let { EpisodeProgressHook(it).also(EpisodeProgressHook::start) }
        subtitleRetry = application?.let { SubtitleRefererRetry(it).also(SubtitleRefererRetry::start) }
        homeRecyclerGuard = application?.let { HomeRecyclerCacheGuard(it).also(HomeRecyclerCacheGuard::start) }
    }

    override fun beforeUnload() {
        hook?.stop()
        hook = null
        pipSubtitles?.stop()
        pipSubtitles = null
        episodeProgress?.stop()
        episodeProgress = null
        subtitleRetry?.stop()
        subtitleRetry = null
        homeRecyclerGuard?.stop()
        homeRecyclerGuard = null
        super.beforeUnload()
    }
}

private class NativeDetailSearchHook(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private var host: FragmentActivity? = null
    private val details = mutableMapOf<Fragment, DetailBinding>()
    private var lastSearchQuery: String? = null
    private var searchSession = false
    private var freshSearchPending = false
    private var homeMenuOwner: Fragment? = null
    private var homeMenuItems = emptyList<MenuItem>()
    private var detailRevealEnd: (() -> Unit)? = null

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        app.unregisterActivityLifecycleCallbacks(this)
        detach()
    }

    private fun detach() {
        detailRevealEnd?.invoke()
        detailRevealEnd = null
        releaseHomeMenu()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        details.values.toList().forEach { it.detach() }
        details.clear()
        host = null
        lastSearchQuery = null
        searchSession = false
        freshSearchPending = false
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        detach()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallback, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                fragment.view?.let { view ->
                    if (fragment.javaClass.name == SEARCH_CLASS) attachSearch(fragment, view)
                    else attachDetail(fragment, view)
                }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun openSearch(fragment: Fragment, root: View, query: String) {
        if (query.isBlank()) return
        val activity = fragment.activity ?: return
        val id = root.resources.getIdentifier("navigation_search", "id", root.context.packageName)
        if (id == 0) return
        val enter = root.resources.getIdentifier("enter_anim", "anim", root.context.packageName)
        val exit = root.resources.getIdentifier("exit_anim", "anim", root.context.packageName)
        searchSession = true
        freshSearchPending = true
        lastSearchQuery = query
        activity.navigate(id, Bundle().apply {
            putString("search_query", query)
            putBoolean(MARKER, true)
        }, NavOptions.Builder()
            .setEnterAnim(enter).setExitAnim(exit)
            .setPopEnterAnim(enter).setPopExitAnim(exit)
            .build())
    }

    private inner class DetailBinding(
        private val fragment: Fragment,
        private val root: View,
        private val button: View
    ) {
        private val titleView: TextView? =
            root.resources.getIdentifier("result_title", "id", root.context.packageName)
                .takeIf { it != 0 }?.let { root.findViewById<TextView>(it) }
        private val click = View.OnClickListener {
            val title = titleView?.text?.toString()?.trim().orEmpty()
                .ifEmpty { fragment.arguments?.getString("name")?.trim().orEmpty() }
            openSearch(fragment, root, title)
        }
        private val tree = root.viewTreeObserver
        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (fragment.isAdded && fragment.view === root && button.isShown) {
                button.setOnClickListener(click)
            }
            true
        }

        fun attach() {
            button.setOnClickListener(click)
            if (tree.isAlive) tree.addOnPreDrawListener(preDraw)
        }

        fun detach() {
            if (tree.isAlive) tree.removeOnPreDrawListener(preDraw)
            if (fragment.view === root && root.isAttachedToWindow) {
                button.setOnClickListener {
                    fragment.activity?.let { activity ->
                        val title = titleView?.text?.toString()?.trim().orEmpty()
                            .ifEmpty { fragment.arguments?.getString("name").orEmpty() }
                        QuickSearchFragment.pushSearch(activity, title)
                    }
                }
            }
        }
    }

    private fun attachDetail(fragment: Fragment, root: View) {
        if (fragment.javaClass.name !in DETAIL_CLASSES || details.containsKey(fragment)) return
        val button = listOf("result_search_Button", "result_search").firstNotNullOfOrNull { name ->
            val id = root.resources.getIdentifier(name, "id", root.context.packageName)
            if (id == 0) null else root.findViewById<View>(id)
        } ?: return
        details[fragment] = DetailBinding(fragment, root, button).also { it.attach() }
    }

    private fun releaseHomeMenu(owner: Fragment? = null) {
        if (owner != null && homeMenuOwner !== owner) return
        homeMenuItems.forEach { it.setOnMenuItemClickListener(null) }
        homeMenuItems = emptyList()
        homeMenuOwner = null
    }

    private fun attachSearch(fragment: Fragment, root: View) {
        val marked = fragment.arguments?.getBoolean(MARKER) == true ||
            fragment.arguments?.getBoolean("stremioc_detail_search") == true
        if (!marked && !searchSession) return
        searchSession = true
        val fresh = marked && freshSearchPending
        freshSearchPending = false
        val activity = fragment.activity ?: return
        fun id(name: String) = root.resources.getIdentifier(name, "id", root.context.packageName)
        val search = id("main_search").takeIf { it != 0 }?.let { root.findViewById<SearchView>(it) }
        if (!fresh && search?.query.isNullOrEmpty() &&
            fragment.arguments?.getString("search_query").isNullOrBlank() &&
            MainActivity.nextSearchQuery.isNullOrBlank()) {
            lastSearchQuery?.let { search?.setQuery(it, false) }
        }
        if (marked && isLayout(PHONE)) {
            search?.post {
                if (fragment.isAdded && fragment.view === root) {
                    search.clearFocus()
                    hideKeyboard(search)
                }
            }
        }
        val homeId = id("navigation_home")
        val searchId = id("navigation_search")
        val hostId = id("nav_host_fragment")
        if (homeId == 0 || searchId == 0 || hostId == 0) return
        val navHost = activity.supportFragmentManager.findFragmentById(hostId) as? NavHostFragment ?: return
        releaseHomeMenu()
        homeMenuOwner = fragment
        homeMenuItems = listOf("nav_view", "nav_rail_view").mapNotNull { name ->
            val barId = id(name)
            val bar = if (barId == 0) null else activity.findViewById<NavigationBarView>(barId)
            bar?.menu?.findItem(homeId)?.apply {
                setOnMenuItemClickListener {
                    if (!fragment.isAdded || fragment.view !== root ||
                        navHost.navController.currentDestination?.id != searchId) {
                        false
                    } else {
                        runCatching {
                            val nav = navHost.navController
                            if (!nav.popBackStack(homeId, false, false)) {
                                nav.navigate(homeId, null, NavOptions.Builder()
                                    .setLaunchSingleTop(true)
                                    .setRestoreState(false)
                                    .build())
                            }
                            true
                        }.getOrDefault(false)
                    }
                }
            }
        }
    }

    private fun holdDetailReveal(search: Fragment) {
        if (search.arguments?.getBoolean(MARKER) != true &&
            search.arguments?.getBoolean("stremioc_detail_search") != true) return
        val activity = search.activity ?: return
        fun find(name: String): View? {
            val id = activity.resources.getIdentifier(name, "id", activity.packageName)
            return if (id == 0) null else activity.findViewById(id)
        }
        if (listOf("nav_view", "nav_rail_view").any { find(it)?.visibility == View.VISIBLE }) return
        val nav = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            as? NavHostFragment ?: return
        if (nav.navController.currentDestination?.id !in setOf(
                R.id.navigation_results_phone, R.id.navigation_results_tv
            )) return
        val content = find("nav_host_fragment") ?: return
        val bounds = content.parent as? android.view.ViewGroup ?: return
        val observer = content.viewTreeObserver
        if (!observer.isAlive) return
        detailRevealEnd?.invoke()
        val started = SystemClock.uptimeMillis()
        var stable = 0
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        lateinit var timeout: Runnable
        lateinit var release: () -> Unit
        release = {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            main.removeCallbacks(timeout)
            if (detailRevealEnd === release) detailRevealEnd = null
        }
        listener = ViewTreeObserver.OnPreDrawListener {
            val elapsed = SystemClock.uptimeMillis() - started
            val full = content.visibility == View.VISIBLE &&
                content.left <= bounds.paddingLeft &&
                content.top <= bounds.paddingTop &&
                content.right >= bounds.width - bounds.paddingRight &&
                content.bottom >= bounds.height - bounds.paddingBottom &&
                !content.isLayoutRequested
            stable = if (full) stable + 1 else 0
            if (stable >= 2 || elapsed >= 600L) {
                release()
                true
            } else {
                content.postInvalidateOnAnimation()
                false
            }
        }
        timeout = Runnable {
            if (detailRevealEnd === release) {
                release()
                content.postInvalidateOnAnimation()
            }
        }
        detailRevealEnd = release
        observer.addOnPreDrawListener(listener)
        main.postDelayed(timeout, 650L)
    }

    private val fragmentCallback = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            if (fragment.javaClass.name == SEARCH_CLASS) attachSearch(fragment, view)
            else attachDetail(fragment, view)
        }

        override fun onFragmentPaused(fm: FragmentManager, fragment: Fragment) {
            if (fragment.javaClass.name != SEARCH_CLASS) return
            if (searchSession) {
                val root = fragment.view
                val id = root?.resources?.getIdentifier("main_search", "id", root.context.packageName) ?: 0
                val query = if (id == 0) null else root?.findViewById<SearchView>(id)?.query?.toString()
                if (query != null) lastSearchQuery = query.takeIf { it.isNotBlank() }
            }
            holdDetailReveal(fragment)
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            releaseHomeMenu(fragment)
            details.remove(fragment)?.detach()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) detach()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        const val MARKER = "global_detail_search"
        const val SEARCH_CLASS = "com.lagradost.cloudstream3.ui.search.SearchFragment"
        val DETAIL_CLASSES = setOf(
            "com.lagradost.cloudstream3.ui.result.ResultTrailerPlayer",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentTv"
        )
    }
}
