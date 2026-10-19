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
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor

internal class SubtitleRefererRetry(private val application: Application) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val linkField = runCatching {
        CS3IPlayer::class.java.getDeclaredField("currentLink").apply { isAccessible = true }
    }.getOrNull()
    @Volatile private var active = false
    @Volatile private var subtitleReferers: Map<String, String> = emptyMap()

    private val retryInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val videoReferer = if (active && response.code == 403) {
            subtitleReferers[request.url.toString()]
        } else null
        if (videoReferer == null || videoReferer == request.header("Referer")?.trim()) {
            response
        } else {
            response.close()
            chain.proceed(request.newBuilder().header("Referer", videoReferer).build())
        }
    }

    private var host: FragmentActivity? = null
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var removeObserver: (() -> Unit)? = null
    private var tree: ViewTreeObserver? = null
    private var lastInstance = -1
    private var lastLink: ExtractorLink? = null
    private var lastReferer: String? = null
    private var lastSubtitles: Set<SubtitleData>? = null
    private val onDraw = ViewTreeObserver.OnPreDrawListener { refresh(); true }

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
            val observer = Observer<VideoLive<Set<SubtitleData>>> { refresh() }
            model?.currentSubtitles?.observe(f.viewLifecycleOwner, observer)
            removeObserver = { model?.currentSubtitles?.removeObserver(observer) }
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
        val f = fragment ?: return
        val state = model?.state ?: return
        val link = runCatching { linkField?.get(f.player) as? ExtractorLink }.getOrNull()
        val referer = link?.headers?.entries?.firstOrNull {
            it.key.equals("Referer", true) && it.value.isNotBlank()
        }?.value?.trim() ?: link?.referer?.trim()
        if (lastInstance == state.instance && lastLink === link &&
            lastReferer == referer && lastSubtitles === state.subtitles) return
        lastInstance = state.instance
        lastLink = link
        lastReferer = referer
        lastSubtitles = state.subtitles
        val safeReferer = referer?.takeIf { it.toHttpUrlOrNull() != null }
        subtitleReferers = if (safeReferer != null) {
            state.subtitles.asSequence()
                .filter { it.origin == SubtitleOrigin.URL }
                .mapNotNull { sub ->
                    sub.getFixedUrl().toHttpUrlOrNull()?.toString()?.let { it to safeReferer }
                }.toMap()
        } else emptyMap()
    }

    private fun detach(f: Fragment? = null) {
        if (f != null && f !== fragment) return
        tree?.let { if (it.isAlive) it.removeOnPreDrawListener(onDraw) }
        tree = null
        removeObserver?.invoke()
        removeObserver = null
        fragment = null
        model = null
        lastInstance = -1
        lastLink = null
        lastReferer = null
        lastSubtitles = null
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
