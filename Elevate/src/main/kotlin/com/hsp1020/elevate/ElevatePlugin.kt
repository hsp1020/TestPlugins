package com.hsp1020.elevate

import android.app.Activity
import com.lagradost.cloudstream3.ui.player.PlayerView
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import androidx.cardview.widget.CardView
import androidx.appcompat.widget.AppCompatSpinner
import androidx.appcompat.content.res.AppCompatResources
import android.widget.Spinner
import android.widget.ImageView
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.view.Gravity
import android.util.TypedValue
import android.text.TextUtils
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
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
    private var httpResponseGuard: ElevateHttpResponseGuard? = null
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
            httpResponseGuard = ElevateHttpResponseGuard(application).also { it.start() }
            homeRecyclerGuard = HomeRecyclerCacheGuard(application).also { it.start() }
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
        // Native RecyclerView listener cleanup always runs; only stale-poster
        // rebind repair is controlled by the Home Poster Refresh setting.
        homeRecyclerGuard?.setRepairEnabled(enabled(ElevateFeature.HOME_REPAIR))
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
        if (enabled(ElevateFeature.EPISODE_OVERLAY)) {
            if (nativeEpisodeOverlay == null) {
                nativeEpisodeOverlay = NativeEpisodeOverlayHook(application).also { it.start() }
            }
        } else {
            nativeEpisodeOverlay?.stop()
            nativeEpisodeOverlay = null
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
        httpResponseGuard?.stop()
        httpResponseGuard = null
        nativeEpisodeOverlay?.stop()
        nativeEpisodeOverlay = null
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

    private fun Context.dp(size: Int) = (size * resources.displayMetrics.density).toInt()

    private fun Context.themeColor(attribute: Int): Int {
        val array = obtainStyledAttributes(intArrayOf(attribute))
        return try {
            array.getColor(0, Color.WHITE)
        } finally {
            array.recycle()
        }
    }

    private inner class Screen(val fragment: GeneratorPlayer, val root: View) {
        private val overlay = root.findViewById<LinearLayout>(R.id.player_episode_overlay)
        private val title = root.findViewById<TextView>(R.id.player_episode_overlay_title)
        private val titlePosition = title?.let { overlay?.indexOfChild(it) }?.takeIf { it >= 0 } ?: 0
        private val list = root.findViewById<RecyclerView>(R.id.player_episode_list)
        private val oldBackground: Drawable? = overlay?.background
        private val oldNextFocusUpId = list?.nextFocusUpId
        private val originalCardColors = LinkedHashMap<CardView, ColorStateList>()
        private var selector: AppCompatSpinner? = null
        private var wrapper: OverlayPlayer? = null
        private var model: PlayerGeneratorViewModel? = null
        private var fullEpisodes = emptyList<ResultEpisode>()
        private var selectedSeason: Int? = null
        private var filteredAdapter: EpisodeAdapter? = null
        private var host: PlayerView? = null
        private var callbacks: PlayerView.Callbacks? = null
        private var guardedCallbacks: PlayerView.Callbacks? = null

        private val cardListener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = fadeCard(view)
            override fun onChildViewDetachedFromWindow(view: View) = restoreCard(view)
        }

        private fun fadeCard(view: View) {
            val card = view as? CardView ?: return
            val oldColor = originalCardColors.getOrPut(card) { card.cardBackgroundColor }
            val color = oldColor.defaultColor
            card.setCardBackgroundColor(Color.argb(
                (Color.alpha(color) * 0.7f).toInt(),
                Color.red(color), Color.green(color), Color.blue(color)
            ))
        }

        private fun restoreCard(view: View) {
            val card = view as? CardView ?: return
            originalCardColors.remove(card)?.let { card.setCardBackgroundColor(it) }
        }

        private fun guardAutoHide() {
            // The native timer dispatches via PlayerView.Callbacks; intercept only its
            // auto-hide event while the native episodes panel is visible.
            val target = runCatching {
                AbstractPlayerFragment::class.java.getDeclaredField("playerHostView")
                    .apply { isAccessible = true }.get(fragment) as? PlayerView
            }.getOrNull() ?: return
            if (target === host && target.callbacks === guardedCallbacks) return
            val original = target.callbacks ?: return
            val guard = object : PlayerView.Callbacks by original {
                override fun onAutoHideUI() {
                    if (overlay?.isShown != true) original.onAutoHideUI()
                }
            }
            if (host?.callbacks === guardedCallbacks) host?.callbacks = callbacks
            host = target
            callbacks = original
            guardedCallbacks = guard
            target.callbacks = guard
        }

        fun attach() {
            val native = fragment.player
            if (native is OverlayPlayer) return
            wrapper = OverlayPlayer(native, this).also { fragment.player = it }

            // Only the panel surface is translucent; text and images stay opaque.
            oldBackground?.constantState?.newDrawable(root.resources)?.mutate()?.let {
                it.alpha = (255 * 0.7f).toInt()
                overlay?.background = it
            }
            list?.addOnChildAttachStateChangeListener(cardListener)
            list?.let { recycler ->
                for (i in 0 until recycler.childCount) fadeCard(recycler.getChildAt(i))
            }
            guardAutoHide()
        }

        private fun removeSelector() {
            selector?.let { (it.parent as? ViewGroup)?.removeView(it) }
            selector = null
            title?.let {
                if (it.parent !== overlay) overlay?.addView(it, titlePosition)
                it.isVisible = true
            }
            oldNextFocusUpId?.let { list?.nextFocusUpId = it }
        }

        fun detach() {
            val restoreNativeList = fragment.isAdded && fragment.view === root &&
                overlay?.isShown == true && filteredAdapter != null &&
                list?.adapter === filteredAdapter
            if (fragment.player === wrapper) wrapper?.let { fragment.player = it.native }
            if (host?.callbacks === guardedCallbacks) host?.callbacks = callbacks
            host = null
            callbacks = null
            guardedCallbacks = null
            list?.removeOnChildAttachStateChangeListener(cardListener)
            originalCardColors.toMap().forEach { (card, color) ->
                card.setCardBackgroundColor(color)
            }
            originalCardColors.clear()
            removeSelector()
            overlay?.background = oldBackground
            if (restoreNativeList) fragment.showEpisodesOverlay()
            filteredAdapter = null
            model = null
            wrapper = null
            fullEpisodes = emptyList()
        }

        fun overlayOpening() {
            // Native showEpisodesOverlay() replaces the adapter after dispatching Pause.
            main.post {
                if (fragment.isAdded && fragment.view === root && overlay?.isVisible == true) {
                    guardAutoHide()
                    configureSeasons()
                }
            }
        }

        private fun seasonOf(episode: ResultEpisode): Int =
            episode.seasonIndex ?: episode.season ?: 0

        private fun seasonLabel(episode: ResultEpisode, context: Context): String =
            ResultViewModel2.seasonToTxt(episode.seasonData, seasonOf(episode))
                ?.asStringNull(context) ?: context.getString(R.string.season)

        private fun makeSeasonSpinner(context: Context): AppCompatSpinner {
            val choices = fullEpisodes.distinctBy(::seasonOf)
            val labels = choices.map { seasonLabel(it, context) }
            val normalTextColor = title?.currentTextColor ?: context.themeColor(R.attr.textColor)
            val nativeBackground = context.themeColor(R.attr.primaryGrayBackground)
            val spinner = AppCompatSpinner(
                context, null, androidx.appcompat.R.attr.spinnerStyle, Spinner.MODE_DROPDOWN
            )
            spinner.id = View.generateViewId()
            spinner.isFocusable = true
            spinner.minimumHeight = context.dp(44)
            spinner.background = ColorDrawable(Color.TRANSPARENT)
            val rippleValue = TypedValue()
            if (context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, rippleValue, true
                ) && rippleValue.resourceId != 0) {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    spinner.foreground = AppCompatResources.getDrawable(
                        context, rippleValue.resourceId
                    )
                }
            }
            spinner.setPopupBackgroundDrawable(ColorDrawable(nativeBackground))
            spinner.dropDownWidth = list?.layoutParams?.width?.takeIf { it > 0 }
                ?: context.dp(350)
            spinner.adapter = object : ArrayAdapter<String>(
                context, android.R.layout.simple_spinner_item, labels
            ) {
                override fun getView(
                    position: Int, convertView: View?, parent: ViewGroup
                ): View = TextView(context).apply {
                    text = labels[position]
                    setTextColor(normalTextColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, title?.textSize ?: context.dp(15).toFloat())
                    typeface = title?.typeface ?: Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_VERTICAL
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(context.dp(10), 0, context.dp(10), 0)
                    minimumHeight = context.dp(44)
                    val arrow = AppCompatResources.getDrawable(
                        context, R.drawable.ic_baseline_keyboard_arrow_down_24
                    )?.mutate()
                    arrow?.setTint(normalTextColor)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null)
                    compoundDrawablePadding = context.dp(6)
                }

                override fun getDropDownView(
                    position: Int, convertView: View?, parent: ViewGroup
                ): View = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    minimumHeight = context.dp(48)
                    setPadding(context.dp(16), 0, context.dp(12), 0)
                    setBackgroundColor(nativeBackground)
                    val ripple = TypedValue()
                    if (context.theme.resolveAttribute(
                            android.R.attr.selectableItemBackground, ripple, true
                        ) && ripple.resourceId != 0) {
                        if (android.os.Build.VERSION.SDK_INT >= 23) {
                            foreground = AppCompatResources.getDrawable(context, ripple.resourceId)
                        }
                    }
                    addView(TextView(context).apply {
                        text = labels[position]
                        setTextColor(normalTextColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, title?.textSize ?: context.dp(15).toFloat())
                        typeface = title?.typeface ?: Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER_VERTICAL
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, context.dp(48), 1f))
                    addView(ImageView(context).apply {
                        if (position == spinner.selectedItemPosition) {
                            setImageResource(R.drawable.ic_baseline_check_24)
                            imageTintList = ColorStateList.valueOf(normalTextColor)
                        }
                    }, LinearLayout.LayoutParams(context.dp(28), context.dp(24)))
                }
            }
            val current = choices.indexOfFirst { seasonOf(it) == selectedSeason }
                .takeIf { it >= 0 } ?: 0
            spinner.setSelection(current, false)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    val newSeason = choices.getOrNull(position)?.let(::seasonOf) ?: return
                    if (selectedSeason != newSeason) {
                        selectedSeason = newSeason
                        renderSeason()
                    }
                }
            }
            return spinner
        }

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
                removeSelector()
                filteredAdapter = null
                return
            }

            // Replace the native season label in the SAME position, rather
            // than adding another row above it.
            if (selector == null) {
                val context = overlay?.context ?: return
                selectedSeason = seasonOf(fullEpisodes.getOrNull(state.index) ?: distinct.first())
                selector = makeSeasonSpinner(context)
                title?.let { overlay.removeView(it) }
                overlay.addView(selector, titlePosition,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(44)))
            } else {
                selectedSeason = seasonOf(fullEpisodes.getOrNull(state.index) ?: distinct.first())
                val position = distinct.indexOfFirst { seasonOf(it) == selectedSeason }
                if (position >= 0) selector?.setSelection(position, false)
            }
            list?.nextFocusUpId = selector?.id ?: View.NO_ID

            if (filteredAdapter == null) filteredAdapter = EpisodeAdapter(false, { click ->
                if (click.action == ACTION_CLICK_DEFAULT) {
                    // Native adapter positions become season-local after filtering.
                    // Resolve the original generator list index before playback.
                    val index = fullEpisodes.indexOfFirst {
                        it.id == click.data.id && it.index == click.data.index
                    }
                    if (index >= 0) {
                        runCatching {
                            GeneratorPlayer::class.java.getDeclaredField("isNextEpisode")
                                .apply { isAccessible = true }
                                .setBoolean(fragment, false)
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

        private fun renderSeason() {
            val season = selectedSeason ?: return
            val visible = fullEpisodes.filter { seasonOf(it) == season }
            val currentId = (model?.state?.generatorState?.meta as? ResultEpisode)?.id
            val currentPosition = visible.indexOfFirst { it.id == currentId }
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
                val episodeToggle = Thread.currentThread().stackTrace.any {
                    it.className == "com.lagradost.cloudstream3.ui.player.FullScreenPlayer" &&
                        it.methodName == "toggleEpisodesOverlay"
                }
                if (episodeToggle) {
                    if (event == CSPlayerEvent.Pause) {
                        screen.overlayOpening()
                        suppressedPause = native.getIsPlaying()
                        // Do not interrupt buffering or playWhenReady, either.
                        return
                    } else if (suppressedPause) {
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
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { fragment ->
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
