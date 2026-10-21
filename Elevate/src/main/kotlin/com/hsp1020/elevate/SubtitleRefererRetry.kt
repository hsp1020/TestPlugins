package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.player.VideoLive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor

internal class SubtitleRefererRetry(private val application: Application) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    @Volatile private var subtitleReferers: Map<String, String> = emptyMap()

    private val retryInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val sourceReferer = if (active && response.code == 403 &&
            request.header("Referer").isNullOrBlank()
        ) subtitleReferers[request.url.toString()] else null
        if (sourceReferer == null) {
            response
        } else {
            response.close()
            chain.proceed(request.newBuilder().header("Referer", sourceReferer).build())
        }
    }

    private var host: FragmentActivity? = null
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var removeObserver: (() -> Unit)? = null
    private var tree: ViewTreeObserver? = null
    private var lastInstance = -1
    private var lastSubtitles: Set<SubtitleData>? = null
    private var lastLinks: Set<VideoLink>? = null
    private val onDraw = ViewTreeObserver.OnPreDrawListener { refresh(); true }
    private var refreshPending = false
    private val refreshRunnable = Runnable {
        refreshPending = false
        rebuildReferers()
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
        ) = watch(f)

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) = watch(f)
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) = detach(f)
    }

    fun start() {
        main.post {
            if (active) return@post
            active = true
            application.registerActivityLifecycleCallbacks(this)
            (CommonActivity.activity as? MainActivity)?.let(::use)
        }
    }

    fun stop() {
        main.post {
            if (!active) return@post
            active = false
            detach()
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
            host = null
            application.unregisterActivityLifecycleCallbacks(this)
            val client = app.baseClient
            if (client.interceptors.any { it === retryInterceptor }) {
                app.baseClient = client.newBuilder().apply {
                    interceptors().removeAll { it === retryInterceptor }
                }.build()
            }
        }
    }

    private fun ensureInterceptor() {
        if (!active) return
        val client = app.baseClient
        if (client.interceptors.none { it === retryInterceptor }) {
            app.baseClient = client.newBuilder().addInterceptor(retryInterceptor).build()
        }
    }

    private fun use(activity: FragmentActivity) {
        if (!active) return
        ensureInterceptor()
        if (host === activity) return
        detach()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                if (f.view != null) watch(f)
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun watch(f: Fragment) {
        if (f !is GeneratorPlayer || !f.isAdded || f.view == null) return
        if (fragment !== f) {
            detach()
            fragment = f
            model = ViewModelProvider(f)[PlayerGeneratorViewModel::class.java]
            val subtitlesObserver = Observer<VideoLive<Set<SubtitleData>>> { refresh() }
            val linksObserver = Observer<VideoLive<Set<VideoLink>>> { refresh() }
            model?.currentSubtitles?.observe(f.viewLifecycleOwner, subtitlesObserver)
            model?.currentLinks?.observe(f.viewLifecycleOwner, linksObserver)
            removeObserver = {
                model?.currentSubtitles?.removeObserver(subtitlesObserver)
                model?.currentLinks?.removeObserver(linksObserver)
            }
            f.view?.viewTreeObserver?.takeIf { it.isAlive }?.let {
                it.addOnPreDrawListener(onDraw)
                tree = it
            }
        }
        refresh()
    }

    private fun refresh() {
        if (!active) return
        ensureInterceptor()
        val state = model?.state ?: return
        if (lastInstance == state.instance &&
            lastSubtitles === state.subtitles && lastLinks === state.links) return
        if (!refreshPending) {
            refreshPending = true
            main.postDelayed(refreshRunnable, 100L)
        }
    }

    private fun rebuildReferers() {
        if (!active) return
        val state = model?.state ?: return
        lastInstance = state.instance
        lastSubtitles = state.subtitles
        lastLinks = state.links

        val byHost = HashMap<String, MutableSet<String>>()
        val byDomain = HashMap<String, MutableSet<String>>()
        state.links.forEach { (video, _) ->
            video ?: return@forEach
            val candidate = video.headers.entries.firstOrNull {
                it.key.equals("Referer", ignoreCase = true) && it.value.isNotBlank()
            }?.value?.trim() ?: video.referer.trim()
            val videoReferer = candidate.toHttpUrlOrNull() ?: return@forEach
            val referer = videoReferer.toString()
            listOfNotNull(videoReferer, video.url.toHttpUrlOrNull()).forEach { source ->
                byHost.getOrPut(source.host) { HashSet() }.add(referer)
                source.topPrivateDomain()?.let { domain ->
                    byDomain.getOrPut(domain) { HashSet() }.add(referer)
                }
            }
        }
        subtitleReferers = state.subtitles.asSequence()
            .filter { it.origin == SubtitleOrigin.URL }
            .filter { subtitle ->
                subtitle.headers.entries.none {
                    it.key.equals("Referer", ignoreCase = true) && it.value.isNotBlank()
                }
            }
            .mapNotNull { subtitle ->
                val url = subtitle.getFixedUrl().toHttpUrlOrNull() ?: return@mapNotNull null
                val matches = HashSet<String>()
                byHost[url.host]?.let(matches::addAll)
                url.topPrivateDomain()?.let { byDomain[it]?.let(matches::addAll) }
                matches.singleOrNull()?.let { url.toString() to it }
            }.toMap()
    }

    private fun detach(f: Fragment? = null) {
        if (f != null && f !== fragment) return
        main.removeCallbacks(refreshRunnable)
        refreshPending = false
        tree?.let { if (it.isAlive) it.removeOnPreDrawListener(onDraw) }
        tree = null
        removeObserver?.invoke()
        removeObserver = null
        fragment = null
        model = null
        lastInstance = -1
        lastSubtitles = null
        lastLinks = null
        subtitleReferers = emptyMap()
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) {
            detach()
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
            host = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
