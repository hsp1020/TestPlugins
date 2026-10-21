package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.player.VideoLive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class SubtitleRefererRetry(private val application: Application) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    @Volatile private var subtitleReferers: Map<String, List<String>> = emptyMap()

    private val retryDispatcher = Dispatcher().apply {
        maxRequests = 8
        maxRequestsPerHost = 8
    }

    private val retryInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val referers = if (active && response.code == 403 &&
            request.header("Referer").isNullOrBlank()
        ) subtitleReferers[request.url.toString()].orEmpty() else emptyList()
        when (referers.size) {
            0 -> response
            1 -> {
                response.close()
                chain.proceed(request.newBuilder().header("Referer", referers[0]).build())
            }
            else -> retryInParallel(chain, request, response, referers)
        }
    }

    private fun retryInParallel(
        chain: Interceptor.Chain,
        request: Request,
        original: Response,
        referers: List<String>
    ): Response {
        val client = app.baseClient.newBuilder().dispatcher(retryDispatcher).build()
        val finished = LinkedBlockingQueue<Pair<Call, Response?>>()
        val lock = Any()
        val calls = ArrayList<Call>()
        var accepting = true
        var nextIndex = 0
        var inFlight = 0
        var chosenCall: Call? = null
        var chosenResponse: Response? = null

        fun launchNext() {
            val referer = referers[nextIndex++]
            val call = client.newCall(request.newBuilder().header("Referer", referer).build())
            calls.add(call)
            inFlight++
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    synchronized(lock) {
                        if (accepting) finished.offer(call to null)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    synchronized(lock) {
                        if (accepting) finished.offer(call to response)
                        else response.close()
                    }
                }
            })
        }

        try {
            repeat(minOf(4, referers.size)) { launchNext() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (inFlight > 0 && !chain.call().isCanceled()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) break
                val completed = finished.poll(
                    minOf(remaining, TimeUnit.MILLISECONDS.toNanos(200)),
                    TimeUnit.NANOSECONDS
                ) ?: continue
                inFlight--
                val candidate = completed.second
                if (candidate != null && candidate.isSuccessful) {
                    chosenCall = completed.first
                    chosenResponse = candidate
                    break
                }
                candidate?.close()
                if (nextIndex < referers.size) launchNext()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(lock) {
                accepting = false
                while (true) {
                    val leftover = finished.poll() ?: break
                    leftover.second?.close()
                }
            }
            calls.forEach { call -> if (call !== chosenCall) call.cancel() }
        }

        return chosenResponse?.also { original.close() } ?: original
    }

    private var host: FragmentActivity? = null
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var removeObserver: (() -> Unit)? = null
    private var lastInstance = -1
    private var refreshPending = false
    private var generation = 0L
    private var worker: ExecutorService? = null
    private var pendingJob: Future<*>? = null
    private val refreshRunnable = Runnable {
        refreshPending = false
        val state = model?.state ?: return@Runnable
        ensureInterceptor()
        val token = generation
        val links = state.links.toList()
        val subtitles = state.subtitles.toList()
        pendingJob = worker?.submit {
            val referers = buildReferers(links, subtitles)
            if (!Thread.currentThread().isInterrupted) {
                main.post {
                    if (active && generation == token) subtitleReferers = referers
                }
            }
        }
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
            worker = Executors.newSingleThreadExecutor()
            application.registerActivityLifecycleCallbacks(this)
            (CommonActivity.activity as? MainActivity)?.let(::use)
        }
    }

    fun stop() {
        main.post {
            if (!active) return@post
            active = false
            detach()
            retryDispatcher.cancelAll()
            worker?.shutdownNow()
            worker = null
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
            val loadingObserver = Observer<VideoLive<Resource<Unit>>> { refresh() }
            model?.currentSubtitles?.observe(f.viewLifecycleOwner, subtitlesObserver)
            model?.currentLinks?.observe(f.viewLifecycleOwner, linksObserver)
            model?.loadingLinks?.observe(f.viewLifecycleOwner, loadingObserver)
            removeObserver = {
                model?.currentSubtitles?.removeObserver(subtitlesObserver)
                model?.currentLinks?.removeObserver(linksObserver)
                model?.loadingLinks?.removeObserver(loadingObserver)
            }
        }
        refresh()
    }

    private fun refresh() {
        if (!active) return
        val instance = model?.state?.instance ?: return
        if (lastInstance != instance) {
            lastInstance = instance
            subtitleReferers = emptyMap()
        }
        if (refreshPending) return
        refreshPending = true
        generation++
        pendingJob?.cancel(true)
        main.postDelayed(refreshRunnable, 100L)
    }

    private fun buildReferers(
        links: List<VideoLink>,
        subtitles: List<SubtitleData>
    ): Map<String, List<String>> {
        val byHost = HashMap<String, MutableSet<String>>()
        val byDomain = HashMap<String, MutableSet<String>>()
        links.forEach { (video, _) ->
            if (Thread.currentThread().isInterrupted) return emptyMap()
            video ?: return@forEach
            val candidate = video.headers.entries.firstOrNull {
                it.key.equals("Referer", ignoreCase = true) && it.value.isNotBlank()
            }?.value?.trim() ?: video.referer.trim()
            val videoReferer = candidate.toHttpUrlOrNull() ?: return@forEach
            val referer = videoReferer.toString()
            listOfNotNull(videoReferer, video.url.toHttpUrlOrNull()).forEach { source ->
                byHost.getOrPut(source.host) { LinkedHashSet() }.add(referer)
                source.topPrivateDomain()?.let { domain ->
                    byDomain.getOrPut(domain) { LinkedHashSet() }.add(referer)
                }
            }
        }
        val result = HashMap<String, List<String>>()
        subtitles.forEach { subtitle ->
            if (Thread.currentThread().isInterrupted) return emptyMap()
            if (subtitle.origin != SubtitleOrigin.URL || subtitle.headers.entries.any {
                    it.key.equals("Referer", ignoreCase = true) && it.value.isNotBlank()
                }) return@forEach
            val url = subtitle.getFixedUrl().toHttpUrlOrNull() ?: return@forEach
            val matches = LinkedHashSet<String>()
            byHost[url.host]?.let(matches::addAll)
            url.topPrivateDomain()?.let { byDomain[it]?.let(matches::addAll) }
            if (matches.isNotEmpty()) result[url.toString()] = matches.toList()
        }
        return result
    }

    private fun detach(f: Fragment? = null) {
        if (f != null && f !== fragment) return
        main.removeCallbacks(refreshRunnable)
        refreshPending = false
        generation++
        pendingJob?.cancel(true)
        pendingJob = null
        removeObserver?.invoke()
        removeObserver = null
        fragment = null
        model = null
        lastInstance = -1
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
