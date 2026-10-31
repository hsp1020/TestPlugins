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
import android.net.Uri
import android.graphics.Color
import android.text.Editable
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.util.Patterns
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.core.view.doOnPreDraw
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
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.TvType
import androidx.media3.common.Player as Media3Player
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
        val returnToDetail: Boolean = false,
    )
    private var lastPlayed: PlayedEpisode? = null
    private var directPlayback: DirectPlayback? = null
    private var directLoading = false
    private data class PendingTimeSeek(val videoId: String, val positionMs: Long)
    private var pendingTimeSeek: PendingTimeSeek? = null
    private var seekPlayer: Media3Player? = null
    private var seekListener: Media3Player.Listener? = null

    private fun detachTimeSeekListener() {
        seekListener?.let { listener -> seekPlayer?.removeListener(listener) }
        seekListener = null
        seekPlayer = null
    }

    private fun clearPendingTimeSeek() {
        detachTimeSeekListener()
        pendingTimeSeek = null
    }

    /** The existing player event path supplies the loaded media; no polling. */
    private fun watchPendingTimeSeek(fragment: Fragment) {
        if (pendingTimeSeek == null || fragment.javaClass.name != PLAYER_SCREEN) return
        val playerFragment = fragment as? GeneratorPlayer ?: return
        val model = ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
        fun seekWhenReady(mediaPlayer: Media3Player) {
            val current = pendingTimeSeek ?: return
            val meta = model.state.generatorState?.meta as? ResultEpisode
            if (meta?.apiName != "YouTube" ||
                YoutubeUtils.extractYoutubeId(meta.data) != current.videoId) {
                Log.w("YouTubeMeta", "[DETAIL_LINK] action=time_seek status=episode_mismatch")
                clearPendingTimeSeek()
                return
            }
            if (!mediaPlayer.isCurrentMediaItemSeekable) {
                Log.w("YouTubeMeta", "[DETAIL_LINK] action=time_seek status=not_seekable")
                clearPendingTimeSeek()
                return
            }
            val duration = mediaPlayer.duration
            val target = if (duration > 0) current.positionMs.coerceAtMost(
                (duration - 1_000L).coerceAtLeast(0L)
            ) else current.positionMs
            mediaPlayer.seekTo(target)
            Log.i("YouTubeMeta", "[DETAIL_LINK] action=time_seek status=applied positionMs=$target")
            clearPendingTimeSeek()
        }
        fun attachToReadyPlayer() {
            if (pendingTimeSeek == null || this.playerFragment !== fragment || !fragment.isAdded) return
            val mediaPlayer = playerFragment.playerView?.player ?: return
            if (seekPlayer !== mediaPlayer) {
                detachTimeSeekListener()
                seekPlayer = mediaPlayer
                // Media3 listener defaults differ between plugin compile and app runtime.
                // A runtime proxy handles all callbacks without missing abstract methods.
                val listener = java.lang.reflect.Proxy.newProxyInstance(
                    Media3Player.Listener::class.java.classLoader,
                    arrayOf(Media3Player.Listener::class.java)
                ) { proxy, method, args ->
                    when (method.name) {
                        "onPlaybackStateChanged" -> {
                            if (args?.firstOrNull() == Media3Player.STATE_READY) seekWhenReady(mediaPlayer)
                            null
                        }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "YouTubeTimeSeekListener"
                        else -> null
                    }
                } as Media3Player.Listener
                seekListener = listener
                mediaPlayer.addListener(listener)
            }
            if (mediaPlayer.playbackState == Media3Player.STATE_READY) {
                seekWhenReady(mediaPlayer)
            }
        }
        // Both are existing native state transitions; no synthetic timer.
        model.loadingLinks.observe(fragment.viewLifecycleOwner) { attachToReadyPlayer() }
        model.currentLinks.observe(fragment.viewLifecycleOwner) { attachToReadyPlayer() }
        attachToReadyPlayer()
    }
    private var expandedReturnDialog: Dialog? = null
    private var popupAwaitingPlayer = false
    private var popupReturnNav: NavController? = null
    private var popupReturnListener: NavController.OnDestinationChangedListener? = null
    // One pending selection bridges player exit and the native detail ViewModel.
    private var pendingRange: Pair<Int, PlayedEpisode>? = null
    private var appliedRange: Pair<Int, PlayedEpisode>? = null
    private var playbackNav: NavController? = null
    private var playbackReturnListener: NavController.OnDestinationChangedListener? = null
    private var playerFragment: Fragment? = null

    /** Find the native Home, Search, or Quick Search expanded catalog dialog. */
    private fun findExpandedDialog(windowRoot: View): Dialog? {
        val recycler = windowRoot.findViewById<RecyclerView>(R.id.home_expanded_recycler)
            ?: return null
        return screens.keys.asSequence().mapNotNull { fragment ->
            runCatching {
                fragment.javaClass.getDeclaredField("bottomSheetDialog").apply {
                    isAccessible = true
                }.get(fragment) as? Dialog
            }.getOrNull()
        }.firstOrNull { dialog ->
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

    private fun clearPopupReturnListener() {
        popupReturnNav?.let { nav ->
            popupReturnListener?.let(nav::removeOnDestinationChangedListener)
        }
        popupReturnNav = null
        popupReturnListener = null
    }

    private fun registerPopupReturn(nav: NavController, originEntry: String, dialog: Dialog) {
        clearPopupReturnListener()
        val listener = NavController.OnDestinationChangedListener { controller, destination, _ ->
            when {
                controller.currentBackStackEntry?.id == originEntry -> {
                    clearPopupReturnListener()
                    if (expandedReturnDialog === dialog) {
                        expandedReturnDialog = null
                        if (dialog.window?.decorView?.isAttachedToWindow == true) {
                            dialog.window?.setWindowAnimations(0)
                            dialog.show()
                        }
                    }
                }
                destination.id != R.id.navigation_results_phone &&
                    destination.id != R.id.navigation_results_tv -> {
                    clearPopupReturnListener()
                    if (expandedReturnDialog === dialog) expandedReturnDialog = null
                }
            }
        }
        popupReturnNav = nav
        popupReturnListener = listener
        nav.addOnDestinationChangedListener(listener)
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
        val originEntry = controller.currentBackStackEntry?.id
        var enteredPlayer = false
        val listener = NavController.OnDestinationChangedListener { nav, destination, _ ->
            if (destination.id == R.id.navigation_player) {
                enteredPlayer = true
            } else if (enteredPlayer) {
                popupAwaitingPlayer = false
                clearPendingTimeSeek()
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
                    if (destination.id == origin && !session.returnToDetail) {
                        val target = if (Globals.isLayout(Globals.TV or Globals.EMULATOR)) {
                            R.id.global_to_navigation_results_tv
                        } else R.id.global_to_navigation_results_phone
                        val noTransition = NavOptions.Builder()
                            .setEnterAnim(0).setExitAnim(0)
                            .setPopEnterAnim(0).setPopExitAnim(0).build()
                        val popup = expandedReturnDialog
                        fun openDetail(): Boolean = try {
                            nav.navigate(target, ResultFragment.newInstance(session.card), noTransition)
                            if (popup != null && originEntry != null)
                                registerPopupReturn(nav, originEntry, popup)
                            true
                        } catch (_: IllegalStateException) {
                            false
                        }
                        // Retry once if the player pop is still being dispatched.
                        if (!openDetail()) main.post {
                            if (playbackNav == null && host != null &&
                                nav.currentBackStackEntry?.id == originEntry && !openDetail()) {
                                if (expandedReturnDialog === popup) {
                                    expandedReturnDialog = null
                                    popup?.show()
                                }
                            }
                        }
                    } else if (destination.id != origin) {
                        expandedReturnDialog = null
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

    private fun playHomeVideo(
        card: SearchResponse,
        popupRoot: View? = null,
        startAtMs: Long? = null,
        returnToDetail: Boolean = false,
    ) {
        if (directLoading || directPlayback != null) return
        clearPopupReturnListener()
        expandedReturnDialog = null
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
                returnToDetail = returnToDetail,
            )
            lastPlayed = null
            pendingRange = null
            appliedRange = null
            directPlayback = session
            if (startAtMs != null) {
                clearPendingTimeSeek()
                pendingTimeSeek = PendingTimeSeek(videoId, startAtMs)
                Log.i("YouTubeMeta", "[DETAIL_LINK] action=time_seek status=pending positionMs=$startAtMs")
            }
            expandedReturnDialog = popupDialog
            popupAwaitingPlayer = popupDialog != null
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
            popupAwaitingPlayer = false
            clearPendingTimeSeek()
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
            if (playerFragment === f) {
                clearPendingTimeSeek()
                playerFragment = null
            }
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
                in EXPANDED_ORIGINS -> if (!popupAwaitingPlayer) expandedReturnDialog?.hide()
                in DETAIL_SCREENS -> if (popupReturnListener == null) expandedReturnDialog = null
            }
            screens[f]?.applyNativeRangeIfPending()
        }

        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, view: View, state: Bundle?) {
            if (f.javaClass.name in EXPANDED_ORIGINS && !popupAwaitingPlayer) {
                expandedReturnDialog?.hide()
            }
            if (f.javaClass.name == PLAYER_SCREEN) {
                playerFragment = f
                val popup = expandedReturnDialog
                if (popupAwaitingPlayer && popup != null) view.doOnPreDraw {
                    if (popupAwaitingPlayer && expandedReturnDialog === popup) {
                        popupAwaitingPlayer = false
                        popup.hide()
                    }
                }
                prefetchAfterLinks(f)
                watchPendingTimeSeek(f)
            }
            attach(f, view)
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (popupAwaitingPlayer && f.javaClass.name in EXPANDED_ORIGINS) {
                expandedReturnDialog?.let { popup ->
                    val decor = popup.window?.decorView
                    if (decor?.isAttachedToWindow == true && decor.visibility != View.VISIBLE) {
                        popup.window?.setWindowAnimations(0)
                        popup.show()
                    }
                }
            }
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
        clearPendingTimeSeek()
        clearPlaybackReturnListener()
        clearPopupReturnListener()
        popupAwaitingPlayer = false
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
        // Native expanded descriptions are separate AlertDialog text views.
        windows.forEach { window ->
            if (owner(window.context) === activity) {
                screens.values.forEach { it.bindExpandedDescription(window) }
            }
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

        private val tagGroup = if (fragment?.arguments?.getString("apiName") == "YouTube")
            root.findViewById<ChipGroup>(R.id.result_tag) else null
        private val description = if (fragment?.arguments?.getString("apiName") == "YouTube")
            root.findViewById<TextView>(R.id.result_description) else null
        private var descriptionWatcher: TextWatcher? = null
        private var nativeMovement: android.text.method.MovementMethod? = null
        private var editingDescription = false
        private val linkPadding = IdentityHashMap<TextView, Int>()

        private fun openTimestamp(seconds: Int) {
            val active = fragment?.takeIf { it.isAdded } ?: return
            val videoId = YoutubeUtils.extractYoutubeId(pageUrl) ?: return
            val currentPlayer = playerFragment as? GeneratorPlayer
            val currentMeta = currentPlayer?.let {
                ViewModelProvider(it)[PlayerGeneratorViewModel::class.java]
                    .state.generatorState?.meta as? ResultEpisode
            }
            val media = currentPlayer?.playerView?.player
            if (currentMeta?.apiName == "YouTube" &&
                YoutubeUtils.extractYoutubeId(currentMeta.data) == videoId &&
                media?.playbackState == Media3Player.STATE_READY &&
                media.isCurrentMediaItemSeekable) {
                media.seekTo(seconds * 1000L)
                Log.i("YouTubeMeta", "[DETAIL_LINK] action=time_seek status=active positionMs=${seconds * 1000L}")
                return
            }
            if (directLoading || directPlayback != null) return
            val api = APIHolder.getApiFromNameNull("YouTube") as? YoutubeProvider ?: return
            val title = active.arguments?.getString("name").orEmpty().ifBlank { "YouTube Video" }
            val card = api.newMovieSearchResponse(title, pageUrl, TvType.Movie) {
                posterUrl = YoutubeUtils.buildThumbnailFromId(videoId)
            }
            playHomeVideo(card, startAtMs = seconds * 1000L, returnToDetail = true)
        }

        private fun openTagSearch(query: String) {
            val active = fragment?.takeIf { it.isAdded } ?: return
            val id = root.resources.getIdentifier("navigation_search", "id", root.context.packageName)
            if (id == 0) return
            val enter = root.resources.getIdentifier("enter_anim", "anim", root.context.packageName)
            val exit = root.resources.getIdentifier("exit_anim", "anim", root.context.packageName)
            active.activity?.navigate(id, Bundle().apply {
                putString("search_query", query)
                putBoolean("global_detail_search", true)
            }, NavOptions.Builder()
                .setEnterAnim(enter).setExitAnim(exit)
                .setPopEnterAnim(enter).setPopExitAnim(exit).build())
            Log.i("YouTubeMeta", "[DETAIL_LINK] action=tag_search query=$query")
        }

        private fun bindTagClicks() {
            val group = tagGroup ?: return
            for (index in 0 until group.childCount) {
                val chip = group.getChildAt(index) as? Chip ?: continue
                val keyword = chip.text?.toString()?.trim().orEmpty()
                if (keyword.isBlank()) continue
                chip.setOnClickListener { openTagSearch(keyword) }
                chip.isFocusable = true
            }
        }

        private fun openExternalLink(url: String, kind: String) {
            val activity = fragment?.activity ?: return
            val intent = if (kind == "email") {
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$url"))
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            }
            try {
                activity.startActivity(intent)
                Log.i("YouTubeMeta", "[DETAIL_LINK] action=$kind opened=true")
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(activity, "No app to open link", Toast.LENGTH_SHORT).show()
                Log.w("YouTubeMeta", "[DETAIL_LINK] action=$kind opened=false")
            } catch (_: SecurityException) {
                Log.w("YouTubeMeta", "[DETAIL_LINK] action=$kind blocked=true")
            }
        }

        private fun decorateDescription(view: TextView) {
            if (editingDescription) return
            val original = view.text ?: return
            if (original is Spanned &&
                original.getSpans(0, original.length, DescriptionLink::class.java).isNotEmpty()) return
            val text = original.toString()
            val styled = SpannableStringBuilder(original)
            var links = 0
            fun add(start: Int, end: Int, color: Int, action: () -> Unit) {
                if (start >= end || styled.getSpans(start, end, ClickableSpan::class.java).isNotEmpty()) return
                styled.setSpan(DescriptionLink(color, action), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                links++
            }
            WEB_LINK.findAll(text).forEach { found ->
                val start = found.range.first
                var end = found.range.last + 1
                while (end > start && text[end - 1] in ".,;:!?)]") end--
                if (end > start) {
                    val raw = text.substring(start, end)
                    val target = if (raw.startsWith("www.", true)) "https://$raw" else raw
                    add(start, end, LINK_RED) { openExternalLink(target, "url") }
                }
            }
            val emails = Patterns.EMAIL_ADDRESS.matcher(text)
            while (emails.find()) {
                val mail = emails.group()
                add(emails.start(), emails.end(), LINK_RED) { openExternalLink(mail, "email") }
            }
            val videoId = YoutubeUtils.extractYoutubeId(pageUrl)
            if (videoId != null) {
                TIME_LINK.findAll(text).forEach { found ->
                    val parts = found.value.split(':').mapNotNull(String::toIntOrNull)
                    if (parts.size !in 2..3 || parts.last() > 59 ||
                        (parts.size == 3 && parts[1] > 59)) return@forEach
                    val seconds = if (parts.size == 2) parts[0] * 60 + parts[1]
                        else parts[0] * 3600 + parts[1] * 60 + parts[2]
                    add(found.range.first, found.range.last + 1, LINK_BLUE) {
                        openTimestamp(seconds)
                    }
                }
            }
            if (links == 0) return
            // A last-line native underline can be clipped by TextView's bottom edge.
            if (!linkPadding.containsKey(view)) {
                linkPadding[view] = view.paddingBottom
                view.setPadding(
                    view.paddingLeft, view.paddingTop, view.paddingRight,
                    view.paddingBottom + dp(2)
                )
            }
            editingDescription = true
            try {
                view.setText(styled, TextView.BufferType.SPANNABLE)
                view.movementMethod = LinkMovementMethod.getInstance()
            } finally {
                editingDescription = false
            }
        }

        fun bindExpandedDescription(window: View) {
            val text = description?.text?.toString().orEmpty()
            if (text.isBlank() || text == "\u200b") return
            val message = window.findViewById<TextView>(android.R.id.message) ?: return
            if (message !== description && message.text?.toString() == text) decorateDescription(message)
        }

        private fun bindDescription() {
            val view = description ?: return
            if (descriptionWatcher == null) {
                nativeMovement = view.movementMethod
                descriptionWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (!editingDescription) decorateDescription(view)
                    }
                }.also(view::addTextChangedListener)
            }
            decorateDescription(view)
        }

        private fun observeDescriptionAndTags() {
            val owner = fragment ?: return
            if (description == null && tagGroup == null) return
            val model = ViewModelProvider(owner)[ResultViewModel2::class.java]
            model.page.observe(owner.viewLifecycleOwner) { page ->
                if (page is Resource.Success) root.post {
                    if (screens[owner] === this@Screen && root.isAttachedToWindow) {
                        bindTagClicks()
                        bindDescription()
                    }
                }
            }
        }

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
            observeDescriptionAndTags()
            if (popup) root.findViewById<RecyclerView>(R.id.home_expanded_recycler)
                ?.let { watch(it, Role.DIALOG_GRID) }
            root.findViewById<RecyclerView>(R.id.home_master_recycler)?.let { watch(it, Role.HOME_PARENT) }
            root.findViewById<RecyclerView>(R.id.search_master_recycler)?.let { watch(it, Role.SEARCH_PARENT) }
            root.findViewById<RecyclerView>(R.id.quick_search_master_recycler)?.let { watch(it, Role.SEARCH_PARENT) }
            root.findViewById<RecyclerView>(R.id.search_autofit_results)?.let { watch(it, Role.SEARCH_GRID) }
            root.findViewById<RecyclerView>(R.id.quick_search_autofit_results)?.let { watch(it, Role.SEARCH_GRID) }
            if (fragment?.arguments?.getString("apiName") == "YouTube") {
                root.findViewById<RecyclerView>(R.id.result_recommendations_list)
                    ?.let { watch(it, Role.DETAIL_RECOMMENDATIONS) }
                root.findViewById<RecyclerView>(R.id.result_episodes)?.let { watch(it, Role.DETAIL_EPISODE) }
                root.findViewById<RecyclerView>(R.id.result_cast_items)?.let { watch(it, Role.DETAIL_CAST) }
            }
        }

        fun close() {
            linkPadding.forEach { (view, bottom) ->
                if (view.paddingBottom != bottom) {
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom)
                }
            }
            linkPadding.clear()
            descriptionWatcher?.let { listener -> description?.removeTextChangedListener(listener) }
            descriptionWatcher = null
            description?.movementMethod = nativeMovement
            tagGroup?.let { group ->
                for (index in 0 until group.childCount) {
                    group.getChildAt(index)?.apply {
                        setOnClickListener(null)
                        isClickable = false
                        isFocusable = false
                    }
                }
            }
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
                    } else if (role == Role.DETAIL_EPISODE) {
                        restoreEpisode(view)
                    } else if (role == Role.DETAIL_CAST) {
                        restoreCast(view)
                    } else {
                        directClicks.remove(view)
                        // Keep expanded grid sizes stable while items are temporarily detached.
                        if (role != Role.DIALOG_GRID) restore(view)
                    }
                    if (role == Role.HOME_CARD) remeasureOnVisibleShortsChange()
                }
            }
            private val resized = View.OnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (role != Role.DIALOG_GRID || right - left != oldRight - oldLeft) schedule()
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
                    view.findViewById<RecyclerView>(R.id.home_child_recyclerview)
                        ?.let { watch(it, if (role == Role.SEARCH_PARENT) Role.SEARCH_CARD else Role.HOME_CARD) }
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
                val card = view.findViewById<View>(R.id.background_card) ?: return
                val video = item.url.contains("/watch?v=")
                val shorts = item.url.contains("/shorts/")
                // Direct play only for Home/Search cards and their catalog dialogs.
                if ((role == Role.HOME_CARD || role == Role.SEARCH_CARD ||
                    role == Role.SEARCH_GRID || role == Role.DIALOG_GRID) &&
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
                // Search thumbnails are already sized by the native app/Elevate.
                if (role == Role.SEARCH_CARD || role == Role.SEARCH_GRID) return
                val title = view.findViewById<TextView>(R.id.imageText) ?: return
                val image = view.findViewById<ImageView>(R.id.imageView) ?: return
                val state = modified[view] ?: CardState(title, card, image).also { modified[view] = it }
                val expanded = !card.contains(title)
                val lines = 4
                if (Build.VERSION.SDK_INT >= 29 && title.isSingleLine) title.setSingleLine(false)
                if (title.maxLines != lines) title.maxLines = lines
                if (title.minLines != 1) title.minLines = 1
                if (!video && !shorts) {
                    state.restoreSize()
                    return
                }

                if (role == Role.DIALOG_GRID || role == Role.DETAIL_RECOMMENDATIONS) {
                    val baseWidth = (list as? AutofitRecyclerView)?.itemWidth
                        ?.minus(dp(4))?.takeIf { it > 0 }
                        ?: card.width.takeIf { it > 0 } ?: return
                    val width = if (shorts) {
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
                if (expanded && role == Role.HOME_CARD) {
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

    private enum class Role { HOME_PARENT, HOME_CARD, SEARCH_PARENT, SEARCH_CARD, SEARCH_GRID, DIALOG_GRID, DETAIL_RECOMMENDATIONS, DETAIL_EPISODE, DETAIL_CAST }

    private class DescriptionLink(
        private val color: Int, private val action: () -> Unit
    ) : ClickableSpan() {
        override fun onClick(widget: View) = action()
        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = true
        }
    }

    private companion object {
        val WEB_LINK = Regex("""(?i)(?:https?://|www\.)[^\s<>"']+""")
        val TIME_LINK = Regex("""(?<![\w/:])(?:\d{1,2}:)?\d{1,2}:\d{2}(?!\d)""")
        val LINK_RED = Color.rgb(229, 9, 20) // Netflix red #E50914
        val LINK_BLUE = Color.rgb(72, 157, 255)
        const val PLAYER_SCREEN = "com.lagradost.cloudstream3.ui.player.GeneratorPlayer"
        val EXPANDED_ORIGINS = setOf(
            "com.lagradost.cloudstream3.ui.home.HomeFragment",
            "com.lagradost.cloudstream3.ui.search.SearchFragment",
            "com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment"
        )
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
