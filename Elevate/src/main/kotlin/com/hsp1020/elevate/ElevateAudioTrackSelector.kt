package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.OptIn
import androidx.core.os.ConfigurationCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.SubtitleHelper
import org.json.JSONObject
import java.util.Locale

@OptIn(UnstableApi::class)
internal class ElevateAudioTrackSelector(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private var activity: FragmentActivity? = null
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var removeObserver: (() -> Unit)? = null
    private var player: Media3Player? = null
    private var waitingOn: ViewTreeObserver? = null
    private var lastInstance: Int? = null
    private var isAnime = false
    private var selected = false

    private val audioListener = java.lang.reflect.Proxy.newProxyInstance(
        Media3Player.Listener::class.java.classLoader,
        arrayOf(Media3Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onTracksChanged" -> {
                (args?.getOrNull(0) as? Tracks)?.let(::select)
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ElevateAudioTrackSelectorListener"
            else -> null
        }
    } as Media3Player.Listener

    private val onDraw = ViewTreeObserver.OnPreDrawListener {
        val current = fragment?.playerView?.player
        if (current != null && current !== player) {
            stopWaiting()
            player?.removeListener(audioListener)
            player = current
            current.addListener(audioListener)
            select(current.currentTracks)
        }
        true
    }

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, saved: Bundle?
        ) = watch(f)
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) = watch(f)
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) = detach(f)
    }

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        detach()
        activity = null
    }

    private fun use(next: FragmentActivity) {
        if (activity === next) return
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        detach()
        activity = next
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                if (f.view != null) watch(f)
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
    }

    private fun isSupportedProvider(name: String): Boolean {
        when (name.lowercase(Locale.ROOT)) {
            "stremioc", "streamplay", "streamplay-anime",
            "cinestream", "cinetmdb", "cinesimkl" -> return true
        }
        return APIHolder.allProviders.any { provider ->
            provider.name.equals(name, ignoreCase = true) &&
                provider.javaClass.name == "com.hsp1020.StremioC"
        }
    }

    private fun animeFlag(episode: ResultEpisode?): Boolean? {
        val data = episode?.data?.takeIf { it.trimStart().startsWith("{") } ?: return null
        return runCatching {
            JSONObject(data).takeIf { it.has("isAnime") && !it.isNull("isAnime") }
                ?.getBoolean("isAnime")
        }.getOrNull()
    }

    private fun watch(candidate: Fragment) {
        if (candidate !is GeneratorPlayer || !candidate.isAdded || candidate.view == null ||
            fragment === candidate) return
        detach()
        fragment = candidate
        val vm = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
        model = vm
        val observer = Observer<VideoLive<Resource<Unit>>> { event ->
            val state = event ?: return@Observer
            if (lastInstance == state.instance) return@Observer
            val generator = vm.state.generatorState ?: return@Observer
            val response = generator.response ?: return@Observer
            lastInstance = state.instance
            player?.removeListener(audioListener)
            player = null
            stopWaiting()
            selected = false
            val episode = generator.meta as? ResultEpisode
            val name = response.apiName
            if (!isSupportedProvider(name)) return@Observer
            isAnime = animeFlag(episode) ?: ((episode?.tvType ?: response.type) in setOf(
                TvType.Anime, TvType.AnimeMovie, TvType.OVA
            ))
            candidate.view?.viewTreeObserver?.takeIf { it.isAlive }?.let {
                it.addOnPreDrawListener(onDraw)
                waitingOn = it
            }
        }
        vm.loadingLinks.observe(candidate.viewLifecycleOwner, observer)
        removeObserver = { vm.loadingLinks.removeObserver(observer) }
    }

    private fun select(tracks: Tracks) {
        if (selected) return
        val current = player ?: return
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) return
        val appLanguage = fragment?.context?.resources?.configuration?.let {
            ConfigurationCompat.getLocales(it).get(0)?.language
        }
        val languages = listOfNotNull(appLanguage, if (isAnime) "ja" else "en",
            "en".takeIf { isAnime }).map { it.lowercase(Locale.ROOT) }.distinct()
        val match = languages.firstNotNullOfOrNull { language ->
            groups.firstNotNullOfOrNull { group ->
                (0 until group.length).firstOrNull { i ->
                    val raw = group.getTrackFormat(i).language?.trim().orEmpty()
                    val code = SubtitleHelper.fromCodeToLangTagIETF(raw)
                        ?: SubtitleHelper.fromCodeToLangTagIETF(
                            raw.replace('_', '-').substringBefore('-')
                        )
                    group.isTrackSupported(i) &&
                        code?.substringBefore('-')?.equals(language, ignoreCase = true) == true
                }?.let { group to it }
            }
        }
        selected = true
        current.removeListener(audioListener)
        if (match != null) {
            val (group, index) = match
            current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
        }
    }

    private fun stopWaiting() {
        waitingOn?.let { if (it.isAlive) it.removeOnPreDrawListener(onDraw) }
        waitingOn = null
    }

    private fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        stopWaiting()
        removeObserver?.invoke()
        removeObserver = null
        player?.removeListener(audioListener)
        player = null
        model = null
        lastInstance = null
        fragment = null
        selected = false
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity) {
            this.activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
            detach()
            this.activity = null
        }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
