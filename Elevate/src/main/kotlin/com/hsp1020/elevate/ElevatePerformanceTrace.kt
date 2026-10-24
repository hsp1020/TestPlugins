package com.hsp1020.elevate

import android.app.Application
import android.util.Log
import com.lagradost.cloudstream3.app
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap

/**
 * Production HTTP response cleanup only. No frame listeners, GC samplers,
 * per-response tracking objects, stack dumps, polling or watchdog threads.
 * The one caller classification at OkHttp call creation is required because
 * third-party providers cannot be edited.
 */
internal class ElevatePerformanceTrace(@Suppress("UNUSED_PARAMETER") application: Application) {
    private data class Origin(
        val streamPlay: Boolean,
        val cineStream: Boolean,
        val cfFirst: Boolean,
        val cfRetry: Boolean,
        val buzz: Boolean,
        val moviebox: Boolean
    )

    @Volatile private var active = false
    @Volatile private var priorFactory: EventListener.Factory? = null
    private val originMap = Collections.synchronizedMap(WeakHashMap<Request, Origin>())
    private val originFactory = EventListener.Factory { call ->
        if (active && (call.request().method == "GET" || call.request().method == "POST")) {
            val frames = Thread.currentThread().stackTrace
            var stream = false
            var cine = false
            var first = false
            var retry = false
            var buzz = false
            var movie = false
            for (f in frames) {
                val name = f.className
                if (name.startsWith("com.phisher98.", ignoreCase = true)) {
                    stream = true
                    if (name.contains("BuzzServer", true) ||
                        name.contains("Hubcloudone", true) || name.contains("VCloud", true))
                        buzz = true
                }
                if (name.startsWith("com.megix.", ignoreCase = true)) {
                    cine = true
                    if (name.contains("CineStreamExtractors")) {
                        if (name.contains("$" + "cfGet")) retry = true
                        if (name == "com.megix.CineStreamExtractors" && f.methodName == "cfGet")
                            first = true
                        if (f.methodName.contains("invokeMoviebox", ignoreCase = true))
                            movie = true
                    }
                }
            }
            if (stream || cine)
                originMap[call.request()] = Origin(stream, cine, first, retry, buzz, movie)
        }
        priorFactory?.create(call) ?: EventListener.NONE
    }

    // cfGet uses a mutex keyed by URL for its CloudflareKiller retries. Initial
    // attempts can be parallel, so count those separately from the serialized
    // second/third requests; the old single pending slot generated false
    // "parallel ambiguous" failures.
    private data class CfState(
        var firstFailures: Int = 0,
        var secondFailed: Boolean = false,
        var touched: Long = android.os.SystemClock.elapsedRealtime()
    )
    private val cfLock = Any()
    private val cfState = LinkedHashMap<String, CfState>(32, 0.75f, true)
    private fun cfEntry(key: String): CfState {
        val now = android.os.SystemClock.elapsedRealtime()
        val existing = cfState[key]
        if (existing != null && now - existing.touched < 120_000L) {
            existing.touched = now
            return existing
        }
        if (existing != null) cfState.remove(key)
        while (cfState.size >= 128) cfState.remove(cfState.keys.first())
        return CfState(touched = now).also { cfState[key] = it }
    }
    private fun cfStartingStage(request: Request, origin: Origin?): Int {
        if (request.method != "GET" || origin?.cineStream != true || !origin.cfRetry) return 0
        val key = request.url.toString()
        synchronized(cfLock) {
            val state = cfState[key] ?: return 0
            if (android.os.SystemClock.elapsedRealtime() - state.touched >= 120_000L) {
                cfState.remove(key)
                return 0
            }
            state.touched = android.os.SystemClock.elapsedRealtime()
            if (state.secondFailed) {
                state.secondFailed = false
                if (state.firstFailures == 0) cfState.remove(key)
                return 3
            }
            if (state.firstFailures > 0) {
                state.firstFailures--
                if (state.firstFailures == 0) cfState.remove(key)
                return 2
            }
            return 0
        }
    }
    private fun markCfFirst(request: Request) {
        synchronized(cfLock) {
            val state = cfEntry(request.url.toString())
            state.firstFailures = (state.firstFailures + 1).coerceAtMost(128)
        }
    }
    private fun markCfSecond(request: Request) {
        synchronized(cfLock) { cfEntry(request.url.toString()).secondFailed = true }
    }

    private fun headerOnly(request: Request, origin: Origin?): Boolean {
        if (request.method != "GET" || origin == null) return false
        if (origin.streamPlay && origin.buzz &&
            request.url.encodedPath.endsWith("/download")) {
            val referer = request.header("Referer")?.trimEnd('/')
            return referer != null &&
                referer == request.url.toString().substringBeforeLast("/download").trimEnd('/')
        }
        return origin.cineStream && origin.moviebox &&
            request.url.host == "h5-api.aoneroom.com" &&
            request.url.encodedPath == "/wefeed-h5api-bff/app/get-latest-app-pkgs"
    }

    // Retain identical bytes for NiceHTTP .text/.document while releasing the
    // original connection. Unknown-length bodies are probed only up to cap+1.
    // If over cap, the same source (with prefetched bytes) is returned untouched.
    private fun bufferedResponse(request: Request, response: Response, origin: Origin?): Response? {
        if (origin?.streamPlay != true || request.header("Range") != null) return null
        val redirect = response.code in 300..399 && response.header("Location") != null
        val error = response.code in 400..599
        if (!redirect && !error) return null
        val body = response.body ?: return null
        val length = body.contentLength()
        val type = body.contentType()
        val typeName = type?.toString().orEmpty()
        if (typeName.startsWith("video/", true) ||
            typeName.contains("mpegurl", true) ||
            typeName.startsWith("application/octet-stream", true)) return null
        val limit = if (redirect) 4096L else 16384L
        if (length > limit) return null
        return try {
            if (length < 0 && body.source().request(limit + 1L)) return null
            val bytes = body.bytes()
            body.close()
            response.newBuilder().body(bytes.toResponseBody(type)).build()
        } catch (problem: Exception) {
            // Never terminate an unknown original body after a failed probe.
            // The BufferedSource retains any bytes already fetched.
            Log.w("Elevate-Http", "bounded response cleanup skipped: " +
                problem.javaClass.simpleName)
            null
        }
    }

    private val guard = Interceptor { chain ->
        val request = chain.request()
        val origin = originMap[request] ?: originMap[chain.call().request()]
        val cfStage = cfStartingStage(request, origin)
        val response = chain.proceed(request)
        val body = response.body
        if (!active || response.code == 101) {
            response
        } else if (request.method == "HEAD" || (body != null && headerOnly(request, origin))) {
            runCatching { body?.close() }
                .onFailure { Log.w("Elevate-Http", "header cleanup failed: " + it.javaClass.simpleName) }
            response
        } else if (origin?.cfFirst == true && !origin.cfRetry &&
            (response.code == 403 || response.code == 503)) {
            val closed = runCatching { body?.close() }.isSuccess
            if (closed) markCfFirst(request)
            response
        } else if (cfStage == 2 && (response.code == 403 || response.code == 503)) {
            // The second response is discarded by cfGet before its third call.
            // Its third/final response is NEVER closed here.
            val closed = runCatching { body?.close() }.isSuccess
            if (closed) markCfSecond(request)
            response
        } else {
            bufferedResponse(request, response, origin) ?: response
        }
    }

    fun start() {
        active = true
        val client = app.baseClient
        val hasGuard = client.interceptors.any { it === guard }
        val hasFactory = client.eventListenerFactory === originFactory
        if (hasGuard && hasFactory) return
        if (!hasFactory) priorFactory = client.eventListenerFactory
        app.baseClient = client.newBuilder().apply {
            if (!hasGuard) addInterceptor(guard)
            if (!hasFactory) eventListenerFactory(originFactory)
        }.build()
    }

    fun stop() {
        active = false
        synchronized(cfLock) { cfState.clear() }
        originMap.clear()
        val client = app.baseClient
        if (client.interceptors.none { it === guard } &&
            client.eventListenerFactory !== originFactory) return
        app.baseClient = client.newBuilder().apply {
            interceptors().removeAll { it === guard }
            if (client.eventListenerFactory === originFactory) {
                val original = priorFactory
                if (original != null) eventListenerFactory(original)
            }
        }.build()
    }
}
