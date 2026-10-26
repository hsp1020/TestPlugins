package com.hsp1020.youtube

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.SearchManager
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.navigation.NavOptions
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.Animation
import android.view.inspector.WindowInspector
import android.widget.ImageView
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.setResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper.setResultSeason
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ui.result.ActorAdaptor
import com.lagradost.cloudstream3.ui.result.EpisodeAdapter
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.ResultFragment
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
    private data class PlayedEpisode(val number: Int, val season: Int?)
    private data class DirectPlayback(
        val card: SearchResponse,
        val parentId: Int,
        val startingNumber: Int,
        val startingSeason: Int?,
        val episodes: CopyOnWriteArrayList<ResultEpisode>,
        var prefetchStarted: Boolean = false,
    )
    private var lastPlayed: PlayedEpisode? = null
    private var directPlayback: DirectPlayback? = null
    private var directLoading = false
    private var expandedReturnDialog: Dialog? = null
    // One pending selection bridges player exit and the native detail ViewModel.
    private var pendingRange: Pair<Int, PlayedEpisode>? = null
    private var appliedRange: Pair<Int, PlayedEpisode>? = null
    private var playbackNav: NavController? = null
    private var playbackReturnListener: NavController.OnDestinationChangedListener? = null
    private var playerFragment: Fragment? = null

    /** Resolve only the native Home dialog containing this expanded catalog. */
    private fun findExpandedDialog(windowRoot: View): Dialog? {
        val home = screens.keys.firstOrNull {
            it.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment"
        } ?: return null
        val recycler = windowRoot.findViewById<RecyclerView>(R.id.home_expanded_recycler)
            ?: return null
        return runCatching {
            home.javaClass.getDeclaredField("bottomSheetDialog").apply {
                isAccessible = true
            }.get(home) as? Dialog
        }.getOrNull()?.takeIf { dialog ->
            dialog.window?.decorView?.findViewById<View>(R.id.home_expanded_recycler) === recycler
        }
    }

    private fun clearPlaybackReturnListener() {
        playbackNav?.let { nav ->
            playbackReturnListener?.let(nav::removeOnDestinationChangedListener)
        }
        playbackNav = null
        playbackReturnListener = null
    }

    /**
     * The native player pops its current destination on back. Before the
     * previous Home destination can draw, replace that destination with the
     * native details destination. Unlike pushing details before the player,
     * this does not start any detail HTTP request on initial playback.
     */
    private fun registerReturn(
        controller: NavController,
        session: DirectPlayback,
    ) {
        clearPlaybackReturnListener()
        val origin = controller.currentDestination?.id ?: return
        var enteredPlayer = false
        val listener = NavController.OnDestinationChangedListener { nav, destination, _ ->
            if (destination.id == R.id.navigation_player) {
                enteredPlayer = true
            } else if (enteredPlayer) {
                if (destination.id == origin) expandedReturnDialog?.hide()
                clearPlaybackReturnListener()
                if (directPlayback === session) {
                    playerFragment?.let(::recordPlayerEpisode)
                    val played = lastPlayed
                        ?: PlayedEpisode(session.startingNumber, session.startingSeason)
                    selectLastPlaybackRange(session.parentId, played)
                    directPlayback = null
                    lastPlayed = null
                    appliedRange = null
                    playerFragment = null
                    if (destination.id == origin) {
                        val target = if (Globals.isLayout(Globals.TV or Globals.EMULATOR)) {
                            R.id.global_to_navigation_results_tv
                        } else R.id.global_to_navigation_results_phone
                        val noTransition = NavOptions.Builder()
                            .setEnterAnim(0).setExitAnim(0)
                            .setPopEnterAnim(0).setPopExitAnim(0).build()
                        // Prefer immediate transition within the destination
                        // callback so Home is never drawn between player and detail.
                        // If Navigation is still dispatching a pop, retry on the
                        // next main-loop turn rather than crashing the player.
                        try {
                            nav.navigate(target, ResultFragment.newInstance(session.card), noTransition)
                        } catch (_: IllegalStateException) {
                            main.post {
                                if (playbackNav == null && host != null &&
                                    nav.currentDestination?.id == origin) {
                                    if (runCatching {
                                        nav.navigate(target, ResultFragment.newInstance(session.card), noTransition)
                                    }.isFailure) {
                                        expandedReturnDialog?.show()
                                        expandedReturnDialog = null
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        playbackNav = controller
        playbackReturnListener = listener
        controller.addOnDestinationChangedListener(listener)
    }

    /** Never delay the first stream for YouTube recommendations. */
    private fun prefetchAfterLinks(fragment: Fragment) {
        val session = directPlayback ?: return
        if (session.prefetchStarted || fragment.javaClass.name != PLAYER_SCREEN) return
        val api = APIHolder.getApiFromNameNull("YouTube") as? YoutubeProvider ?: return
        val model = ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
        model.loadingLinks.observe(fragment.viewLifecycleOwner) { update ->
            if (session.prefetchStarted ||
                update?.value !is Resource.Success ||
                model.state.links.isEmpty() ||
                directPlayback !== session) return@observe
            session.prefetchStarted = true
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val full = try {
                    withContext(Dispatchers.IO) {
                        api.prefetchDirectDetails(session.card.url)
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                } ?: return@launch
                if (directPlayback !== session) return@launch
                val known = session.episodes.mapTo(hashSetOf()) {
                    YoutubeUtils.extractYoutubeId(it.data) ?: it.data
                }
                val previousCount = session.episodes.size
                full.episodes.sortedBy {
                    (it.season?.times(10_000) ?: 0) + (it.episode ?: 0)
                }.forEachIndexed { index, episode ->
                    val key = YoutubeUtils.extractYoutubeId(episode.data) ?: episode.data
                    if (!known.add(key)) return@forEachIndexed
                    val number = episode.episode ?: index + 1
                    session.episodes.add(buildResultEpisode(
                        headerName = full.name,
                        name = episode.name,
                        poster = episode.posterUrl,
                        episode = number,
                        seasonIndex = episode.season,
                        season = episode.season,
                        data = episode.data,
                        apiName = full.apiName,
                        id = session.parentId +
                            (episode.season?.times(100_000) ?: 0) + number + 1,
                        index = session.episodes.size,
                        rating = episode.score,
                        description = episode.description,
                        isFiller = null,
                        tvType = full.type,
                        parentId = session.parentId,
                        airDate = episode.date,
                        runTime = episode.runTime,
                    ))
                }
                if (session.episodes.size > previousCount) {
                    // Preserve the native next-episode/resume metadata without
                    // reloading any video link, player, or UI hierarchy.
                    model.modifyState {
                        val old = generatorState ?: return@modifyState this
                        copy(generatorState = old.copy(
                            nextMeta = session.episodes.getOrNull(old.index + 1),
                            allMeta = session.episodes,
                        ))
                    }
                    // Native GeneratorPlayer updates the next button mainly in
                    // playerPositionChanged(). If recommendations arrived later
                    // (or playback is paused/buffering), its previous GONE state
                    // otherwise lingers despite the episodes overlay being populated.
                    // Synchronize the existing native controls once, on actual
                    // episode-list growth. The native click listeners remain intact.
                    if (model.hasNextEpisode() == true) {
                        val root = fragment.view
                        if (Globals.isLayout(Globals.PHONE)) {
                            root?.findViewById<View>(R.id.player_skip_episode)?.visibility =
                                View.VISIBLE
                        } else {
                            root?.findViewById<View>(R.id.player_go_forward_root)?.visibility =
                                View.VISIBLE
                            root?.findViewById<View>(R.id.player_go_forward)?.visibility =
                                View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun playHomeVideo(card: SearchResponse, popupRoot: View? = null) {
        if (directLoading || directPlayback != null) return
        val activity = host ?: return
        val api = APIHolder.getApiFromNameNull("YouTube") as? YoutubeProvider ?: return
        val navHost = activity.supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return
        val videoId = YoutubeUtils.extractYoutubeId(card.url) ?: return
        val popupDialog = popupRoot?.let(::findExpandedDialog)
        directLoading = true
        activity.lifecycleScope.launch {
        try {
            // Build the player from the card (plus the existing Shorts list), without HTTP.
            val page = api.quickPlaybackPage(card)
            api.cacheDirectReturnPage(card.url, page)
            val parentId = page.uniqueUrl.replace(api.mainUrl, "")
                .replace("/", "").hashCode()
            val initial = page.episodes.sortedBy {
                (it.season?.times(10_000) ?: 0) + (it.episode ?: 0)
            }.mapIndexed { index, episode ->
                val number = episode.episode ?: index + 1
                buildResultEpisode(
                    headerName = page.name,
                    name = episode.name,
                    poster = episode.posterUrl,
                    episode = number,
                    seasonIndex = episode.season,
                    season = episode.season,
                    data = episode.data,
                    apiName = page.apiName,
                    id = parentId + (episode.season?.times(100_000) ?: 0) + number + 1,
                    index = index,
                    rating = episode.score,
                    description = episode.description,
                    isFiller = null,
                    tvType = page.type,
                    parentId = parentId,
                    airDate = episode.date,
                    runTime = episode.runTime,
                )
            }
            val selectedIndex = initial.indexOfFirst {
                YoutubeUtils.extractYoutubeId(it.data) == videoId
            }
            if (selectedIndex < 0) return@launch
            val episodes = CopyOnWriteArrayList(initial)
            val session = DirectPlayback(
                card, parentId, initial[selectedIndex].episode,
                initial[selectedIndex].season, episodes,
            )
            lastPlayed = null
            pendingRange = null
            appliedRange = null
            directPlayback = session
            if (popupDialog != null) {
                popupDialog.hide()
                expandedReturnDialog = popupDialog
            }
            registerReturn(navHost.navController, session)
            navHost.navController.navigate(
                R.id.global_to_navigation_player,
                GeneratorPlayer.newInstance(
                    RepoLinkGenerator(episodes, page = page),
                    selectedIndex,
                    HashMap(page.syncData),
                ),
            )
        } catch (e: Exception) {
            clearPlaybackReturnListener()
            directPlayback = null
            if (expandedReturnDialog === popupDialog) {
                expandedReturnDialog = null
                popupDialog?.show()
            }
            Toast.makeText(activity, e.localizedMessage ?: "Unable to play video", Toast.LENGTH_SHORT).show()
        } finally {
            directLoading = false
        }
        }
    }

    private fun recordPlayerEpisode(fragment: Fragment) {
        val playback = directPlayback ?: return
        if (fragment.javaClass.name != PLAYER_SCREEN) return
        val episode = runCatching {
            ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
                .state.generatorState?.meta as? ResultEpisode
        }.getOrNull() ?: return
        if (episode.apiName != "YouTube" || episode.parentId != playback.parentId) return
        lastPlayed = PlayedEpisode(episode.episode, episode.season)
    }

    /**
     * CloudStream remembers the selected season and the beginning of the
     * selected episode range. It does not focus an individual episode on return.
     * Persist those native preferences and ask an already-loaded detail model to
     * select the actual EpisodeRange, without manipulating RecyclerView.
     */
    private fun selectLastPlaybackRange(parentId: Int, played: PlayedEpisode) {
        val selection = parentId to played
        if (appliedRange == selection) return
        appliedRange = selection
        val rangeSize = if (Globals.isLayout(Globals.TV or Globals.EMULATOR)) 50 else 20
        val startEpisode = ((played.number.coerceAtLeast(1) - 1) / rangeSize) * rangeSize + 1
        setResultEpisode(parentId, startEpisode)
        played.season?.let { setResultSeason(parentId, it) }
        pendingRange = selection
        screens.values.forEach { it.applyNativeRangeIfPending() }
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            recordPlayerEpisode(f)
        }

        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name != PLAYER_SCREEN) return
            recordPlayerEpisode(f)
            if (playerFragment === f) playerFragment = null
            // Normally the destination listener already handled return.
            // Disarm a stale listener if the player was removed externally.
            if (directPlayback != null && playbackReturnListener == null) {
                directPlayback = null
                lastPlayed = null
                expandedReturnDialog = null
            }
        }

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            when (f.javaClass.name) {
                "com.lagradost.cloudstream3.ui.home.HomeFragment" -> expandedReturnDialog?.hide()
                in DETAIL_SCREENS -> expandedReturnDialog = null
            }
            screens[f]?.applyNativeRangeIfPending()
        }

        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, view: View, state: Bundle?) {
            if (f.javaClass.name == "com.lagradost.cloudstream3.ui.home.HomeFragment") {
                expandedReturnDialog?.hide()
            }
            if (f.javaClass.name == PLAYER_SCREEN) {
                playerFragment = f
                prefetchAfterLinks(f)
            }
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
        clearPlaybackReturnListener()
        playerFragment = null
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree = null
        dialogs.values.toList().forEach { it.close() }
        dialogs.clear()
        screens.values.toList().forEach { it.close() }
        screens.clear()
        lastPlayed = null
        directPlayback = null
        expandedReturnDialog = null
        pendingRange = null
        appliedRange = null
        directLoading = false
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
        private val pageUrl = fragment?.arguments?.getString("url").orEmpty()
        private val pageId = pageUrl.takeIf {
            fragment?.arguments?.getString("apiName") == "YouTube" &&
                (it.contains("/shorts/") || it.contains("/watch?v="))
        }?.replace("https://www.youtube.com", "")?.replace("/", "")?.hashCode()

        private var requestedSeason: Int? = null

        /**
         * In CloudStream's postEpisodeRange(), selectedRangeIndex is posted
         * after seasonSelections, rangeSelections and selectedSeasonIndex.
         * This one native signal covers both initial loading and season changes.
         */
        private fun observeNativeRanges() {
            if (pageId == null) return
            val hostFragment = fragment ?: return
            val model = ViewModelProvider(hostFragment)[ResultViewModel2::class.java]
            model.selectedRangeIndex.observe(hostFragment.viewLifecycleOwner) {
                applyNativeRangeIfPending()
            }
        }

        fun applyNativeRangeIfPending() {
            val (parentId, played) = pendingRange ?: return
            if (pageId != parentId) return
            val hostFragment = fragment ?: return
            val model = ViewModelProvider(hostFragment)[ResultViewModel2::class.java]
            played.season?.let { season ->
                val seasons = model.seasonSelections.value.orEmpty()
                if (seasons.isEmpty()) return
                val targetSeasonIndex = seasons.indexOfFirst { it.second == season }
                if (targetSeasonIndex < 0) return
                if (model.selectedSeasonIndex.value != targetSeasonIndex) {
                    if (requestedSeason != season) {
                        requestedSeason = season
                        model.changeSeason(season)
                    }
                    return
                }
            }
            requestedSeason = null
            val ranges = model.rangeSelections.value.orEmpty()
            val targetRangeIndex = ranges.indexOfFirst {
                played.number in it.second.startEpisode..it.second.endEpisode
            }
            if (targetRangeIndex < 0) return
            // Consume before changing the ViewModel to prevent reentrant updates.
            pendingRange = null
            if (model.selectedRangeIndex.value != targetRangeIndex) {
                model.changeRange(ranges[targetRangeIndex].second)
            }
        }

        init {
            observeNativeRanges()
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

        fun restoreChannelPressOnResume() {
            watchers.values.forEach { it.restoreChannelPressOnResume() }
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
            private val directClicks = IdentityHashMap<View, View.OnClickListener>()
            private val castViews = IdentityHashMap<View, Boolean>()
            private val channelPressInterpolator = android.view.animation.DecelerateInterpolator()
            private var channelLongPressHandled = false
            private val channelPressTouch = View.OnTouchListener { card, event ->
                if (pendingChannelNavigation?.first === card) return@OnTouchListener false
                val portrait = card.findViewById<View>(R.id.voice_actor_image_holder2)
                    ?: return@OnTouchListener false
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        channelLongPressHandled = false
                        portrait.animate().cancel()
                        portrait.clearAnimation()
                        portrait.scaleX = 1f
                        portrait.scaleY = 1f
                        portrait.animate().scaleX(0.8f).scaleY(0.8f)
                            .setDuration(200L)
                            .setInterpolator(channelPressInterpolator)
                            .start()
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        // Let the native click animation restore 0.8 -> 1.0;
                        // resetting here causes a visible second scale pulse.
                        if (!channelLongPressHandled) portrait.animate().cancel()
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        if (!channelLongPressHandled) restoreChannelPress(card)
                    }
                }
                // Let ActorAdaptor handle its original click and browser search.
                false
            }
            private fun restoreChannelPress(card: View) {
                card.findViewById<View>(R.id.voice_actor_image_holder2)?.let { portrait ->
                    portrait.animate().cancel()
                    portrait.scaleX = 1f
                    portrait.scaleY = 1f
                }
            }
            // ActorAdaptor's existing phone-only ACTION_WEB_SEARCH behavior,
            // with press-state marking before launching the browser.
            private val channelLongClick = View.OnLongClickListener { card ->
                val position = list.getChildAdapterPosition(card)
                val adapter = list.adapter as? ActorAdaptor
                val actor = if (position == RecyclerView.NO_POSITION) null
                    else adapter?.getItemOrNull(position) as? ActorData
                if (actor != null && Globals.isLayout(Globals.PHONE)) {
                    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                        putExtra(SearchManager.QUERY, actor.actor.name)
                    }
                    if (intent.resolveActivity(card.context.packageManager) != null) {
                        channelLongPressHandled = true
                        card.context.startActivity(intent)
                    }
                }
                // The native handler also consumes unsupported searches.
                // Restore the press only when nothing was launched.
                if (!channelLongPressHandled) restoreChannelPress(card)
                true
            }
            // Existing onActivityResumed path; only restore after browser return.
            fun restoreChannelPressOnResume() {
                if (!channelLongPressHandled) return
                channelLongPressHandled = false
                castViews.keys.forEach(::restoreChannelPress)
            }
            private var pendingChannelNavigation: Pair<View, Animation>? = null

            private fun cancelPendingChannelNavigation(view: View? = null) {
                val pending = pendingChannelNavigation ?: return
                if (view != null && pending.first !== view) return
                pending.second.setAnimationListener(null)
                pendingChannelNavigation = null
            }

            private var observed: RecyclerView.Adapter<*>? = null
            private var visibleShorts: Boolean? = null
            private var adaptiveRemeasurePosted = false
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
                    if (role == Role.HOME_CARD) remeasureOnVisibleShortsChange()
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
                    } else if (role == Role.DETAIL_CAST) {
                        restoreCast(view)
                    } else {
                        directClicks.remove(view)
                        restore(view)
                    }
                    if (role == Role.HOME_CARD) remeasureOnVisibleShortsChange()
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
                cancelPendingChannelNavigation()
                channelLongPressHandled = false
                list.removeOnChildAttachStateChangeListener(children)
                list.removeOnLayoutChangeListener(resized)
                observed?.unregisterAdapterDataObserver(changes)
                observed = null
                modified.keys.toList().forEach(::restore)
                modified.clear()
                episodeSizes.keys.toList().forEach(::restoreEpisode)
                castViews.keys.toList().forEach(::restoreCast)
                directClicks.clear()
                visibleShorts = null
                adaptiveRemeasurePosted = false
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
                if (role == Role.HOME_CARD) remeasureOnVisibleShortsChange()
            }

            /**
             * CloudStream's horizontal catalog is wrap_content. Its measured height
             * can shrink while only landscape cards are visible. When Shorts return,
             * request one native measure pass so the newly bound four-line caption
             * contributes to the row height. Never set fixed/minimum row heights.
             */
            private fun remeasureOnVisibleShortsChange() {
                if (role != Role.HOME_CARD) return
                val adapter = list.adapter as? HomeChildItemAdapter ?: return
                var hasShorts = false
                for (i in 0 until list.childCount) {
                    val position = list.getChildAdapterPosition(list.getChildAt(i))
                    if (position == RecyclerView.NO_POSITION) continue
                    val item = adapter.getItemOrNull(position) ?: continue
                    if (item.apiName == "YouTube" && item.url.contains("/shorts/")) {
                        hasShorts = true
                        break
                    }
                }
                val previous = visibleShorts
                visibleShorts = hasShorts
                if (previous == null || previous == hasShorts || adaptiveRemeasurePosted) return
                adaptiveRemeasurePosted = true
                list.post {
                    adaptiveRemeasurePosted = false
                    if (watchers[list] === this@Watcher && list.isAttachedToWindow) {
                        list.requestLayout()
                    }
                }
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
                // The expanded Home catalog uses DIALOG_GRID and SearchAdapter.
                // Apply the same direct-play click to its cards; the HomeFragment
                // itself performs the native bottom-sheet hide on navigation.
                if ((role == Role.HOME_CARD || role == Role.DIALOG_GRID) &&
                    (video || shorts) && !item.url.contains("list=") &&
                    item !is DataStoreHelper.ResumeWatchingResult) {
                    val click = directClicks.getOrPut(view) {
                        View.OnClickListener {
                            val currentPosition = list.getChildViewHolder(view).bindingAdapterPosition
                            val current = if (currentPosition == RecyclerView.NO_POSITION) null
                                else (list.adapter as? BaseAdapter<*, *>)?.getItemOrNull(currentPosition)
                                    as? SearchResponse
                            if (current?.apiName == "YouTube" &&
                                (current.url.contains("/watch?v=") ||
                                    current.url.contains("/shorts/")) &&
                                !current.url.contains("list=")) {
                                playHomeVideo(current, if (role == Role.DIALOG_GRID) root else null)
                            }
                        }
                    }
                    view.setOnClickListener(click)
                    card.setOnClickListener(click)
                }
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

            /** Restore modified cast holders before RecyclerView recycles them. */
            private fun restoreCast(view: View) {
                cancelPendingChannelNavigation(view)
                if (castViews.remove(view) == null) return
                restoreChannelPress(view)
                view.setOnTouchListener(null)
                view.setOnClickListener(null)
                view.setOnLongClickListener(null)
                view.isClickable = false
                view.isLongClickable = false
                // cast_item.xml defines this root as focusable by default.
                view.isFocusable = true
                view.findViewById<View>(R.id.voice_actor_image_holder2)?.clearAnimation()
            }

            private fun applyCast(view: View) {
                val hostFragment = fragment ?: return
                if (hostFragment.arguments?.getString("apiName") != "YouTube") return
                val adapter = list.adapter as? ActorAdaptor ?: return
                val position = list.getChildViewHolder(view).bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val actor = adapter.getItemOrNull(position) as? ActorData ?: return
                castViews[view] = true
                if (position == 0) {
                    // First actor is the channel. The channel's own profile
                    // should never recursively navigate to itself.
                    val pageUrl = hostFragment.arguments?.getString("url").orEmpty()
                    val isChannelPage = pageUrl.contains("/channel/") ||
                        pageUrl.contains("/@") || pageUrl.contains("/c/") ||
                        pageUrl.contains("/user/")
                    val channel = YoutubeProvider.channelForUrl(pageUrl)
                    val link = channel?.takeIf {
                        !isChannelPage &&
                            actor.actor.name.equals(it.name, ignoreCase = true)
                    }
                    view.isFocusable = true
                    view.isClickable = true
                    view.setOnTouchListener(channelPressTouch)
                    view.setOnLongClickListener(channelLongClick)
                    // Reuse CloudStream's actual ActorAdaptor animation rather
                    // than maintaining another gesture/animation implementation.
                    view.setOnClickListener(object : View.OnClickListener {
                        override fun onClick(clicked: View) {
                            // A second tap must not restart the pending native animation.
                            if (pendingChannelNavigation?.first === view) return
                            val currentPosition = list.getChildAdapterPosition(view)
                            if (currentPosition == RecyclerView.NO_POSITION) return
                            val currentActor = adapter.getItemOrNull(currentPosition)
                                ?: return
                            // Property scale and legacy native ScaleAnimation must
                            // never stack. The reset occurs before startAnimation
                            // in the same UI turn, without a rendered 1.0 frame.
                            restoreChannelPress(view)
                            val holder = list.findViewHolderForAdapterPosition(currentPosition)
                            if (holder is ViewHolderState<*>) {
                                @Suppress("UNCHECKED_CAST")
                                val nativeHolder = holder as ViewHolderState<Any>
                                adapter.onUpdateContent(
                                    nativeHolder, currentActor, currentPosition
                                )
                                // Native onUpdateContent also rebinds the
                                // native click listener; retain channel navigation.
                                view.setOnClickListener(this)
                                view.setOnLongClickListener(channelLongClick)
                            }
                            if (link != null) {
                                // The native ActorAdaptor starts a 200ms portrait scale
                                // animation. Navigate only after its completion.
                                val animation = view.findViewById<View>(
                                    R.id.voice_actor_image_holder2
                                )?.animation
                                if (animation == null) {
                                    hostFragment.activity?.loadResult(
                                        link.url, "YouTube", link.name
                                    )
                                } else {
                                    pendingChannelNavigation = view to animation
                                    animation.setAnimationListener(object : Animation.AnimationListener {
                                        override fun onAnimationStart(animation: Animation?) = Unit
                                        override fun onAnimationRepeat(animation: Animation?) = Unit
                                        override fun onAnimationEnd(animation: Animation?) {
                                            val pending = pendingChannelNavigation
                                            if (pending == null || pending.first !== view || pending.second !== animation) return
                                            cancelPendingChannelNavigation(view)
                                            // A stale / recycled card must never navigate.
                                            if (watchers[list] !== this@Watcher ||
                                                !hostFragment.isAdded ||
                                                view.parent !== list ||
                                                list.getChildAdapterPosition(view) != currentPosition ||
                                                adapter.getItemOrNull(currentPosition) !== currentActor) return
                                            val currentPage = hostFragment.arguments
                                                ?.getString("url").orEmpty()
                                            val target = YoutubeProvider.channelForUrl(currentPage)
                                            if (target == null || target.url != link.url || target.name != link.name) return
                                            hostFragment.activity?.loadResult(
                                                link.url, "YouTube", link.name
                                            )
                                        }
                                    })
                                }
                            }
                        }
                    })
                } else {
                    // Subscriber/video counts are display-only statistics, not
                    // actors. Native ActorAdaptor binds both click-to-invert and
                    // long-click web search to every item: disable both here.
                    view.setOnTouchListener(null)
                    view.setOnClickListener(null)
                    view.setOnLongClickListener(null)
                    view.isClickable = false
                    view.isLongClickable = false
                    view.isFocusable = false
                    restoreChannelPress(view)
                    view.findViewById<View>(R.id.voice_actor_image_holder2)?.clearAnimation()
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
        if (activity is MainActivity) {
            if (host === activity) screens.values.forEach { it.restoreChannelPressOnResume() }
            use(activity)
        }
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
        const val PLAYER_SCREEN = "com.lagradost.cloudstream3.ui.player.GeneratorPlayer"
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
