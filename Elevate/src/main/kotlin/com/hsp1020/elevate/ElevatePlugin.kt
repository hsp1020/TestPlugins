package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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
    private var searchProviderOrder: SearchProviderOrderHook? = null
    private var audioSelector: ElevateAudioTrackSelector? = null
    private var homeHero: HomeHeroToggle? = null
    private var featurePrefs: SharedPreferences? = null
    private var featureListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var app: Application? = null
    private val main = Handler(Looper.getMainLooper())
    private val generation = java.util.concurrent.atomic.AtomicInteger()

    override fun load(context: Context) {
        val application = context.applicationContext as? Application ?: return
        val token = generation.incrementAndGet()
        openSettings = { ctx ->
            (ctx as? FragmentActivity)?.takeUnless { it.isFinishing || it.isDestroyed }?.let { host ->
                if (host.supportFragmentManager.findFragmentByTag("ElevateSettings") == null) {
                    ElevateSettingsFragment().show(host.supportFragmentManager, "ElevateSettings")
                }
            }
        }
        main.post {
            if (generation.get() != token) return@post
            stopAll()
            app = application
            try {
                val preferences = application.getSharedPreferences(ElevateFeaturePrefs.FILE, Context.MODE_PRIVATE)
                // Preserve the user's previous visible/hidden choice when changing to "ON = hide".
                if (!preferences.getBoolean("hero_hide_semantics_v2", false)) {
                    preferences.edit().apply {
                        if (preferences.contains(ElevateFeature.HERO_CAROUSEL.name)) {
                            putBoolean(
                                ElevateFeature.HERO_CAROUSEL.name,
                                !preferences.getBoolean(ElevateFeature.HERO_CAROUSEL.name, true)
                            )
                        }
                        putBoolean("hero_hide_semantics_v2", true)
                    }.apply()
                }
                featurePrefs = preferences
                featureListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (ElevateFeature.entries.any { it.name == key }) {
                        main.post {
                            if (generation.get() == token) {
                                try {
                                    syncFeatures()
                                } catch (failure: Exception) {
                                    android.util.Log.e("Elevate", "Feature update failed", failure)
                                }
                            }
                        }
                    }
                }.also(preferences::registerOnSharedPreferenceChangeListener)
                homeHero = HomeHeroToggle(application).also { it.start() }
                syncFeatures()
            } catch (failure: Exception) {
                stopAll()
                android.util.Log.e("Elevate", "Feature initialization failed", failure)
            }
        }
    }

    private fun enabled(feature: ElevateFeature): Boolean =
        featurePrefs?.getBoolean(feature.name, ElevateFeaturePrefs.defaultEnabled(feature))
            ?: ElevateFeaturePrefs.defaultEnabled(feature)

    private fun syncFeatures() {
        val application = app ?: run {
            return
        }
        if (enabled(ElevateFeature.DETAIL_SEARCH)) {
            if (hook == null) hook = NativeDetailSearchHook(application).also { it.start() }
        } else {
            hook?.stop()
            hook = null
        }
        if (enabled(ElevateFeature.PROVIDER_ORDER)) {
            if (searchProviderOrder == null) {
                searchProviderOrder = SearchProviderOrderHook(application).also { it.start() }
            }
        } else {
            searchProviderOrder?.stop()
            searchProviderOrder = null
        }
        homeHero?.setHidden(enabled(ElevateFeature.HERO_CAROUSEL))
        if (enabled(ElevateFeature.HOME_REPAIR)) {
            if (homeRecyclerGuard == null) {
                homeRecyclerGuard = HomeRecyclerCacheGuard(application).also { it.start() }
            }
        } else {
            homeRecyclerGuard?.stop()
            homeRecyclerGuard = null
        }
        if (enabled(ElevateFeature.EPISODE_PROGRESS)) {
            if (episodeProgress == null) {
                episodeProgress = EpisodeProgressHook(application).also { it.start() }
            }
        } else {
            episodeProgress?.stop()
            episodeProgress = null
        }
        if (enabled(ElevateFeature.AUDIO_TRACKS)) {
            if (audioSelector == null) {
                audioSelector = ElevateAudioTrackSelector(application).also { it.start() }
            }
        } else {
            audioSelector?.stop()
            audioSelector = null
        }
        if (enabled(ElevateFeature.PIP_BUTTON) || enabled(ElevateFeature.PIP_SUBTITLES)) {
            if (pipSubtitles == null) {
                pipSubtitles = PipSubtitleLifecycle(
                    application, { enabled(ElevateFeature.PIP_SUBTITLES) },
                    { enabled(ElevateFeature.PIP_BUTTON) }
                ).also { it.start() }
            }
            pipSubtitles?.refresh()
        } else {
            pipSubtitles?.stop()
            pipSubtitles = null
        }
        if (enabled(ElevateFeature.SUBTITLE_RETRY)) {
            if (subtitleRetry == null) {
                subtitleRetry = SubtitleRefererRetry(application).also { it.start() }
            }
        } else {
            subtitleRetry?.stop()
            subtitleRetry = null
        }
    }

    private fun stopAll() {
        featureListener?.let { featurePrefs?.unregisterOnSharedPreferenceChangeListener(it) }
        featureListener = null
        featurePrefs = null
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
        searchProviderOrder?.stop()
        searchProviderOrder = null
        audioSelector?.stop()
        audioSelector = null
        homeHero?.stop()
        homeHero = null
        app = null
    }

    override fun beforeUnload() {
        val token = generation.incrementAndGet()
        openSettings = null
        main.post {
            if (generation.get() == token) stopAll()
        }
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
