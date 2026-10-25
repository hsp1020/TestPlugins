package com.hsp1020.elevate

import android.app.Activity
import android.view.ContextThemeWrapper
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.IPlayer
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.result.ACTION_CLICK_DEFAULT
import com.lagradost.cloudstream3.ui.result.EpisodeAdapter
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showDialog
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
    private var skipController: ElevateSkipController? = null
    private var embeddedSubtitles: EmbeddedSubtitleController? = null
    private var homeHero: HomeHeroToggle? = null
    private var libraryPosters: LibraryPosterHook? = null
    private var performanceTrace: ElevatePerformanceTrace? = null
    private var nativeRecyclerGuard: NativeRecyclerListenerGuard? = null
    private var nativeEpisodeOverlay: NativeEpisodeOverlayHook? = null
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
            performanceTrace = ElevatePerformanceTrace(application).also { it.start() }
            nativeRecyclerGuard = NativeRecyclerListenerGuard(application).also { it.start() }
            nativeEpisodeOverlay = NativeEpisodeOverlayHook(application).also { it.start() }
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
        if (enabled(ElevateFeature.HERO_CAROUSEL)) {
            if (homeHero == null) homeHero = HomeHeroToggle(application).also { it.start() }
            homeHero?.setHidden(true)
        } else {
            homeHero?.stop()
            homeHero = null
        }
        if (enabled(ElevateFeature.HOME_REPAIR)) {
            if (homeRecyclerGuard == null) {
                homeRecyclerGuard = HomeRecyclerCacheGuard(application).also { it.start() }
            }
        } else {
            homeRecyclerGuard?.stop()
            homeRecyclerGuard = null
        }
        if (enabled(ElevateFeature.LIBRARY_POSTERS)) {
            if (libraryPosters == null) libraryPosters = LibraryPosterHook(application).also { it.start() }
        } else {
            libraryPosters?.stop()
            libraryPosters = null
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
        if (enabled(ElevateFeature.SKIP)) {
            if (skipController == null) {
                skipController = ElevateSkipController(application).also { it.start() }
            }
        } else {
            skipController?.stop()
            skipController = null
        }
        if (enabled(ElevateFeature.EMBEDDED_SUBTITLES)) {
            if (embeddedSubtitles == null) {
                embeddedSubtitles = EmbeddedSubtitleController(application).also { it.start() }
            }
        } else {
            embeddedSubtitles?.stop()
            embeddedSubtitles = null
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
        skipController?.stop()
        skipController = null
        embeddedSubtitles?.stop()
        embeddedSubtitles = null
        homeHero?.stop()
        homeHero = null
        libraryPosters?.stop()
        libraryPosters = null
        performanceTrace?.stop()
        performanceTrace = null
        nativeEpisodeOverlay?.stop()
        nativeEpisodeOverlay = null
        nativeRecyclerGuard?.stop()
        nativeRecyclerGuard = null
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


/**
 * Changes only the native episode overlay. The native player, episode loading,
 * overlay animation, and controls remain owned by CloudStream.
 */
internal class NativeEpisodeOverlayHook(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private var activity: FragmentActivity? = null
    private val screens = LinkedHashMap<GeneratorPlayer, Screen>()

    private inner class Screen(val fragment: GeneratorPlayer, val root: View) {
        private val overlay = root.findViewById<LinearLayout>(R.id.player_episode_overlay)
        private val title = root.findViewById<TextView>(R.id.player_episode_overlay_title)
        private val list = root.findViewById<RecyclerView>(R.id.player_episode_list)
        private val oldBackground: Drawable? = overlay?.background
        private val oldNextFocusUpId = list?.nextFocusUpId
        private var selector: MaterialButton? = null
        private var wrapper: OverlayPlayer? = null
        private var model: PlayerGeneratorViewModel? = null
        private var fullEpisodes = emptyList<ResultEpisode>()
        private var selectedSeason: Int? = null
        private var filteredAdapter: EpisodeAdapter? = null

        fun attach() {
            val native = fragment.player
            if (native is OverlayPlayer) return
            wrapper = OverlayPlayer(native, this).also { fragment.player = it }
            // Alpha belongs to the panel background, not to episode text/posters.
            val translucent = oldBackground?.constantState?.newDrawable(root.resources)?.mutate()
            if (translucent != null) {
                translucent.alpha = (255 * 0.7f).toInt()
                overlay?.background = translucent
            }
        }

        fun detach() {
            if (fragment.player === wrapper) {
                wrapper?.let { fragment.player = it.native }
            }
            selector?.let { (it.parent as? ViewGroup)?.removeView(it) }
            selector = null
            title?.isGone = false
            overlay?.background = oldBackground
            oldNextFocusUpId?.let { list?.nextFocusUpId = it }
            filteredAdapter = null
            model = null
            wrapper = null
            fullEpisodes = emptyList()
        }

        fun overlayOpening() {
            // Native showEpisodesOverlay() replaces its adapter after issuing Pause.
            // Run once after that method, never poll or observe layout frames.
            main.post {
                if (fragment.view === root && fragment.isAdded && overlay?.isVisible == true) {
                    configureSeasons()
                }
            }
        }

        private fun seasonOf(episode: ResultEpisode) =
            episode.seasonIndex ?: episode.season ?: 0

        private fun configureSeasons() {
            val vm = ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
            model = vm
            val state = vm.state.generatorState ?: return
            fullEpisodes = state.allMeta?.filterIsInstance<ResultEpisode>()
                ?.map { episode ->
                    getViewPos(episode.id)?.let { saved ->
                        episode.copy(position = saved.position, duration = saved.duration)
                    } ?: episode
                } ?: emptyList()

            val distinct = fullEpisodes.distinctBy(::seasonOf)
            if (distinct.size <= 1) {
                selector?.isGone = true
                title?.isVisible = true
                filteredAdapter = null
                return
            }

            if (selector == null) {
                val context = overlay?.context ?: return
                val density = context.resources.displayMetrics.density
                selector = MaterialButton(ContextThemeWrapper(context, R.style.MultiSelectButton)).apply {
                    id = View.generateViewId()
                    isAllCaps = false
                    setCompoundDrawablesRelativeWithIntrinsicBounds(
                        0, 0, R.drawable.ic_baseline_keyboard_arrow_down_24, 0
                    )
                    compoundDrawablePadding = (5 * density).toInt()
                    setPadding((10 * density).toInt(), 0, (5 * density).toInt(), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, (40 * density).toInt()
                    ).apply { setMargins(0, (5 * density).toInt(), 0, (5 * density).toInt()) }
                    nextFocusDownId = list?.id ?: View.NO_ID
                    setOnClickListener { openSeasonDialog() }
                }.also { overlay?.addView(it, 0) }
            }
            title?.isGone = true
            selector?.isVisible = true
            list?.nextFocusUpId = selector?.id ?: View.NO_ID

            // Reopen on the season of the episode that is actually playing.
            val current = fullEpisodes.getOrNull(state.index)
            selectedSeason = seasonOf(current ?: distinct.first())
            if (filteredAdapter == null) filteredAdapter = EpisodeAdapter(false, { event ->
                if (event.action == ACTION_CLICK_DEFAULT) {
                    // Adapter positions become season-local after filtering. Use
                    // the native generator's global list index, not that position.
                    val index = fullEpisodes.indexOfFirst {
                        it.id == event.data.id && it.index == event.data.index
                    }
                    if (index >= 0) {
                        // Match the native overlay's explicit episode-choice path.
                        runCatching {
                            GeneratorPlayer::class.java.getDeclaredField("isNextEpisode").apply {
                                isAccessible = true
                            }.setBoolean(fragment, false)
                        }
                        fragment.releasePlayer()
                        overlay?.isGone = true
                        vm.loadThisEpisode(index)
                    }
                }
            }, { download -> DownloadButtonSetup.handleDownloadClick(download) })
            list?.adapter = filteredAdapter
            renderSeason()
        }

        private fun openSeasonDialog() {
            val context = fragment.activity ?: return
            val distinct = fullEpisodes.distinctBy(::seasonOf)
            val numbers = distinct.map(::seasonOf)
            val names = distinct.map { episode ->
                ResultViewModel2.seasonToTxt(episode.seasonData, seasonOf(episode))
                    ?.asStringNull(context) ?: context.getString(R.string.season)
            }
            with(com.lagradost.cloudstream3.utils.SingleSelectionHelper) {
                context.showDialog(
                    names,
                    numbers.indexOf(selectedSeason),
                    context.getString(R.string.season),
                    false,
                    {}
                ) { index ->
                    selectedSeason = numbers.getOrNull(index) ?: return@showDialog
                    renderSeason()
                }
            }
        }

        private fun renderSeason() {
            val season = selectedSeason ?: return
            val context = overlay?.context ?: return
            val episode = fullEpisodes.firstOrNull { seasonOf(it) == season } ?: return
            selector?.text = ResultViewModel2.seasonToTxt(episode.seasonData, season)
                ?.asStringNull(context) ?: context.getString(R.string.season)
            val visible = fullEpisodes.filter { seasonOf(it) == season }
            val currentId = model?.state?.generatorState?.meta as? ResultEpisode
            val currentPosition = visible.indexOfFirst { it.id == currentId?.id }
                .takeIf { it >= 0 } ?: 0
            filteredAdapter?.submitList(visible) {
                if (selectedSeason != season || fragment.view !== root) return@submitList
                list?.scrollToPosition(currentPosition)
                if (com.lagradost.cloudstream3.ui.settings.Globals.isLayout(
                        com.lagradost.cloudstream3.ui.settings.Globals.TV
                    )) {
                    list?.post {
                        if (selectedSeason == season && fragment.view === root) {
                            list?.findViewHolderForAdapterPosition(currentPosition)
                                ?.itemView?.requestFocus()
                        }
                    }
                }
            }
        }
    }

    private inner class OverlayPlayer(val native: IPlayer, private val screen: Screen) :
        IPlayer by native {
        private var suppressedPause = false

        override fun handleEvent(event: CSPlayerEvent, source: PlayerEventSource) {
            if (event == CSPlayerEvent.Pause || event == CSPlayerEvent.Play) {
                // The only native call site to suppress is the open/close branch
                // of FullScreenPlayer.toggleEpisodesOverlay().
                val fromEpisodes = Thread.currentThread().stackTrace.any {
                    it.className == "com.lagradost.cloudstream3.ui.player.FullScreenPlayer" &&
                        it.methodName == "toggleEpisodesOverlay"
                }
                if (fromEpisodes) {
                    if (event == CSPlayerEvent.Pause) {
                        screen.overlayOpening()
                        suppressedPause = native.getIsPlaying()
                        // Preserve playWhenReady during initial loading/rebuffering too.
                        return
                    } else if (suppressedPause) {
                        // Native tries to restore an already uninterrupted stream.
                        suppressedPause = false
                        return
                    }
                }
            }
            native.handleEvent(event, source)
        }

        override fun release() {
            suppressedPause = false
            native.release()
        }
    }

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            if (fragment is GeneratorPlayer) main.post {
                if (fragment.isAdded && fragment.view === view) watch(fragment, view)
            }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            (fragment as? GeneratorPlayer)?.let { screens.remove(it)?.detach() }
        }
    }

    private fun watch(fragment: GeneratorPlayer, view: View) {
        if (screens[fragment]?.root === view) return
        screens.remove(fragment)?.detach()
        screens[fragment] = Screen(fragment, view).also { it.attach() }
    }

    private fun use(next: FragmentActivity) {
        if (activity === next) return
        unbind()
        activity = next
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                if (fragment is GeneratorPlayer) fragment.view?.let { watch(fragment, it) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
    }

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        unbind()
    }

    private fun unbind() {
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        screens.values.forEach { it.detach() }
        screens.clear()
        activity = null
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity) unbind()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
