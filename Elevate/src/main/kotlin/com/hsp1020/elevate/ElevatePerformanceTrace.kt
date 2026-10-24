package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.WeakHashMap
import java.util.Collections
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * Temporary P0/P1 diagnostics, restricted to:
 *  - subtitle update triggers, cost and associated layout
 *  - CloseGuard / StrictMode resource leak allocation stacks
 *  - main-thread stalls, second layout passes and hot call stacks
 *  - WebView extractor stack samples
 *  - M3U8 preview extraction stack samples and heap/GC pressure
 *
 * Does not call requestLayout(), trigger media loading, or replace a WebViewClient.
 * OkHttp response bodies passing through app.baseClient are instrumented directly
 * at their source().close() boundary. View.requestLayout and the final core player
 * implementation remain observer-based; do not attribute sampled stacks to callers.
 */
internal class ElevatePerformanceTrace(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private val tag = "Elevate-Perf"
    private val main = Handler(Looper.getMainLooper())
    private val sequence = AtomicInteger()
    private var host: FragmentActivity? = null
    @Volatile private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var tree: ViewTreeObserver? = null
    private var draw: ViewTreeObserver.OnPreDrawListener? = null
    private var globalLayout: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var unsubscribe: (() -> Unit)? = null
    private val viewObservers = mutableMapOf<View, View.OnLayoutChangeListener>()
    private var session = 0
    private var lastSubtitleSet: Set<SubtitleData> = emptySet()
    private var lastSubtitleEventAt = 0L
    private var lastLoadingAt = 0L
    private var lastLinksAt = 0L
    private var lastPlayerAt = 0L
    private var lastPlayerId = 0
    private var lastMediaKey: String? = null
    private var layoutPasses = 0
    private var unchangedLayouts = 0
    private var layoutChanges = 0
    private val layoutCounts = mutableMapOf<String, Int>()
    private var subtitleWindowEvents = 0
    private var subtitleWindowMax = 0
    private var subtitleWindowAdded = 0
    private var subtitleWindowRemoved = 0
    private var lastDrawAt = 0L
    private var lastReportAt = 0L
    @Volatile private var lastHeartbeatAt = SystemClock.elapsedRealtime()
    @Volatile private var monitoring = false
    private var worker: Thread? = null
    private var lastStallAt = 0L
    private var lastHotSampleAt = 0L
    private var lastHeapAt = 0L
    private var lastAllocatedBytes = -1L
    private var lastGcCount = -1L
    private var lastWebSignature = ""
    private var lastPreviewSignature = ""
    @Volatile private var lastDetachAtMs = 0L
    private var lastPreviewThreads = 0
    private var lastWebThreads = 0
    private var priorVmPolicy: StrictMode.VmPolicy? = null
    private var installedVmPolicy: StrictMode.VmPolicy? = null
    private val httpSequence = AtomicLong()
    private val httpOpenCount = AtomicInteger()
    private val httpCloseCount = AtomicInteger()
    private val httpLeaks = AtomicInteger()
    private val sampledLeakHosts = HashSet<String>()
    // OkHttp calls EventListener.Factory.create synchronously when newCall()
    // is created, before asynchronous dispatch. Preserve the existing factory
    // and use a weak map to correlate the initiating call stack with a response.
    private data class CallOrigin(
        val callerThread: String, val createdMs: Long,
        val frames: Array<StackTraceElement>,
        val provider: String,
        val entryPoint: String
    )

    private fun providerOf(frames: Array<StackTraceElement>): Pair<String, String> {
        // Real plugin package names are case-sensitive: StreamPlay uses
        // com.Phisher98 and CineStream uses com.megix.
        val cine = frames.firstOrNull {
            it.className.startsWith("com.megix.", ignoreCase = true)
        }
        if (cine != null)
            return "CineStream" to (cine.className.substringAfterLast(".") + "." + cine.methodName)
        val stream = frames.firstOrNull {
            it.className.startsWith("com.phisher98.", ignoreCase = true)
        }
        if (stream != null) {
            val actor = stream.className.substringAfterLast(".") + "." + stream.methodName
            val anime = frames.any { it.className.contains("StreamPlayAnime", ignoreCase = true) }
            return (if (anime) "StreamPlay-Anime" else "StreamPlay-family") to actor
        }
        val mine = frames.firstOrNull { it.className.startsWith("com.hsp1020.") &&
            !it.className.contains("ElevatePerformanceTrace") }
        if (mine != null)
            return "StremioC-or-other-HSP" to
                (mine.className.substringAfterLast(".") + "." + mine.methodName)
        val core = frames.firstOrNull {
            it.className.startsWith("com.lagradost.") &&
                !it.className.contains("nicehttp", ignoreCase = true)
        }
        return if (core != null)
            "CloudStream-core" to (core.className.substringAfterLast(".") + "." + core.methodName)
        else "unknown" to "unknown"
    }
    private val callOrigins = Collections.synchronizedMap(
        WeakHashMap<Request, CallOrigin>())
    @Volatile private var parentEventFactory: EventListener.Factory? = null
    private val originEventFactory = EventListener.Factory { call ->
        if (monitoring) {
            val candidate = Thread.currentThread().stackTrace.filter { frame ->
                val name = frame.className.lowercase(java.util.Locale.ROOT)
                name.startsWith("com.hsp1020.") ||
                    name.startsWith("com.lagradost.") ||
                    name.startsWith("com.phisher98.") ||
                    name.startsWith("com.megix.") ||
                    name.contains("streamplay") ||
                    name.contains("cloudstream")
            }.take(36).toTypedArray()
            val (provider, entry) = providerOf(candidate)
            callOrigins[call.request()] = CallOrigin(
                Thread.currentThread().name,
                SystemClock.elapsedRealtime(), candidate, provider, entry)
        }
        // Delegation is essential: do not disable host timing/analytics hooks.
        parentEventFactory?.create(call) ?: EventListener.NONE
    }
    private var lastHttpEnsureAt = 0L
    private val httpQueue = ReferenceQueue<ResponseBody>()
    private val httpPending = ConcurrentHashMap<PhantomReference<ResponseBody>, HttpRecord>()
    private val httpInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.body
        if (!monitoring || body == null || response.code == 101) {
            response
        } else {
            val origin = callOrigins[request] ?: callOrigins[chain.call().request()]
            val record = HttpRecord(
                id = httpSequence.incrementAndGet(),
                started = SystemClock.elapsedRealtime(),
                host = request.url.host,
                route = "",
                code = response.code,
                method = request.method,
                contentType = body.contentType()?.toString().orEmpty().take(48),
                contentLength = body.contentLength(),
                rangeRequest = request.header("Range") != null,
                sourceThread = Thread.currentThread().name,
                // Interceptor entry gives the actual HTTP execution stack. Async
                // OkHttp dispatch can obscure which plugin initiated the request.
                executionStack = Thread.currentThread().stackTrace.drop(2).take(24),
                callOrigin = origin
            )
            lateinit var reference: PhantomReference<ResponseBody>
            val tracked = TracedResponseBody(body,
                onRead = { count ->
                    if (count > 0L) record.readBytes.addAndGet(count)
                    else if (count == -1L) record.readEof = true
                },
                onClose = { onHttpClosed(record, reference) }
            )
            reference = PhantomReference(tracked as ResponseBody, httpQueue)
            httpPending[reference] = record
            httpOpenCount.incrementAndGet()
            response.newBuilder().body(tracked).build()
        }
    }

    private data class HttpRecord(
        val id: Long,
        val started: Long,
        val host: String,
        val route: String,
        val code: Int,
        val method: String,
        val contentType: String,
        val contentLength: Long,
        val rangeRequest: Boolean,
        val sourceThread: String,
        val executionStack: List<StackTraceElement>,
        val callOrigin: CallOrigin?,
        val readBytes: AtomicLong = AtomicLong(),
        @Volatile var readEof: Boolean = false,
        @Volatile var warned: Boolean = false
    ) {
        val provider: String get() = callOrigin?.provider ?: "unknown"
        val entryPoint: String get() = callOrigin?.entryPoint ?: "unknown"
        // EOF is a valid stream completion, while an abandoned 403/503 body
        // with zero reads is more strongly indicative of missing close().
        fun category(): String {
            if (readEof) return "eof-no-explicit-close"
            if (rangeRequest || contentType.startsWith("video/") ||
                contentType.contains("mpegurl", ignoreCase = true))
                return if (readBytes.get() > 0) "media-partial-ambiguous" else "media-unread-ambiguous"
            if (readBytes.get() > 0) return "partial-read-no-close"
            if (code == 204 || code == 205 || code == 304) return "no-body-or-empty-status"
            if (code in listOf(301, 302, 303, 307, 308, 403, 429, 500, 503))
                return "unread-redirect-or-error"
            return "unread-response"
        }
    }

    private class TracedResponseBody(
        private val delegate: ResponseBody,
        private val onRead: (Long) -> Unit,
        private val onClose: () -> Unit
    ) : ResponseBody() {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        private val trackedSource: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val result = super.read(sink, byteCount)
                    onRead(result)
                    return result
                }
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        if (closed.compareAndSet(false, true)) onClose()
                    }
                }
            }.buffer()
        }
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = trackedSource
    }

    private fun onHttpClosed(record: HttpRecord, ref: PhantomReference<ResponseBody>) {
        httpPending.remove(ref)
        ref.clear()
        httpCloseCount.incrementAndGet()
        if (record.warned && monitoring) {
            log("http-close-after-warning",
                "requestId=" + record.id + " host=" + record.host +
                " elapsedMs=" + (SystemClock.elapsedRealtime() - record.started) +
                " owner=" + record.provider + " caller=" + record.entryPoint +
                " bytes=" + record.readBytes.get() + " eof=" + record.readEof)
            stack("http-close-stack requestId=" + record.id,
                Thread.currentThread(), Thread.currentThread().stackTrace)
        }
    }

    private fun ensureHttpInterceptor() {
        if (!monitoring) return
        // Other Elevate features rebuild app.baseClient on preference changes.
        // Install on the current client only, preserving existing interceptors.
        runCatching {
            val client = app.baseClient
            val missingInterceptor = client.interceptors.none { it === httpInterceptor }
            val missingCallOrigin = client.eventListenerFactory !== originEventFactory
            if (missingInterceptor || missingCallOrigin) {
                if (missingCallOrigin) parentEventFactory = client.eventListenerFactory
                app.baseClient = client.newBuilder().apply {
                    if (missingInterceptor) addInterceptor(httpInterceptor)
                    if (missingCallOrigin) eventListenerFactory(originEventFactory)
                }.build()
                log("http-interceptor-installed", "clientId=" +
                    System.identityHashCode(app.baseClient) +
                    " originCapture=" + missingCallOrigin)
            }
        }.onFailure { Log.w(tag, "http-interceptor-install-failed " +
            it.javaClass.simpleName) }
    }

    private fun sampleHttp(now: Long) {
        var scanned = 0
        var warned = 0
        val leakedByHost = linkedMapOf<String, Int>()
        val leakedByCaller = linkedMapOf<String, Int>()
        val firstLeakSamples = ArrayList<HttpRecord>()
        while (true) {
            @Suppress("UNCHECKED_CAST")
            val ref = httpQueue.poll() as? PhantomReference<ResponseBody> ?: break
            val record = httpPending.remove(ref)
            ref.clear()
            if (record != null) {
                httpLeaks.incrementAndGet()
                leakedByHost[record.host] = (leakedByHost[record.host] ?: 0) + 1
                val key = record.provider + "/" + record.entryPoint + "/" + record.category()
                leakedByCaller[key] = (leakedByCaller[key] ?: 0) + 1
                // Sample each distinct path+failure category once, rather than
                // collecting dozens of identical stacks each GC cycle.
                if (sampledLeakHosts.add(key) && firstLeakSamples.size < 12)
                    firstLeakSamples.add(record)
            }
        }
        if (leakedByHost.isNotEmpty()) {
            log("http-unclosed-by-caller",
                leakedByCaller.entries.sortedByDescending { it.value }.take(15)
                    .joinToString(",") { it.key + ":" + it.value })
            log("http-gc-leaks-by-host",
                leakedByHost.entries.sortedByDescending { it.value }
                    .take(15).joinToString(",") { it.key + ":" + it.value })
            for (record in firstLeakSamples) {
                log("http-body-gc-first-sample",
                    "requestId=" + record.id + " host=" + record.host +
                    " httpCode=" + record.code + " type=" + record.contentType +
                    " ageMs=" + age(now, record.started) +
                    " owner=" + record.provider +
                    " caller=" + record.entryPoint +
                    " category=" + record.category() +
                    " method=" + record.method +
                    " bytesRead=" + record.readBytes.get() +
                    " eof=" + record.readEof +
                    " declaredBytes=" + record.contentLength +
                    " rangeRequest=" + record.rangeRequest +
                    " executedOn=" + record.sourceThread)
                record.callOrigin?.let { origin ->
                    log("http-origin", "requestId=" + record.id +
                        " host=" + record.host + " createdThread=" +
                        origin.callerThread + " originAgeMs=" +
                        age(now, origin.createdMs))
                    stack("http-origin-stack requestId=" + record.id,
                        Thread.currentThread(), origin.frames)
                }
                stack("http-unclosed-acquisition requestId=" + record.id,
                    Thread.currentThread(), record.executionStack.toTypedArray())
            }
        }
        // An unclosed live response is suspicious, not automatically a leak.
        // Print the execution stack once; confirmed leaks are separately marked.
        for ((ref, record) in httpPending) {
            if (++scanned > 2500) break
            if (!record.warned && now - record.started > 30_000L) {
                record.warned = true
                warned++
                log("http-body-open-30s",
                    "requestId=" + record.id + " host=" + record.host +
                    " httpCode=" + record.code + " type=" + record.contentType +
                    " owner=" + record.provider +
                    " caller=" + record.entryPoint +
                    " category=" + record.category() +
                    " bytesRead=" + record.readBytes.get() +
                    " eof=" + record.readEof +
                    " rangeRequest=" + record.rangeRequest +
                    " executedOn=" + record.sourceThread)
                record.callOrigin?.let { origin ->
                    log("http-origin", "requestId=" + record.id +
                        " host=" + record.host + " createdThread=" +
                        origin.callerThread + " originAgeMs=" +
                        age(now, origin.createdMs))
                    stack("http-origin-stack requestId=" + record.id,
                        Thread.currentThread(), origin.frames)
                }
                stack("http-body-acquisition requestId=" + record.id,
                    Thread.currentThread(), record.executionStack.toTypedArray())
            }
        }
        val opened = httpOpenCount.getAndSet(0)
        val closed = httpCloseCount.getAndSet(0)
        val leaked = httpLeaks.getAndSet(0)
        if (opened != 0 || closed != 0 || leaked != 0 || warned != 0) {
            log("http-summary", "opened=" + opened + " closed=" + closed +
                " gcUnclosed=" + leaked + " pending=" + httpPending.size +
                " warned=" + warned)
        }
    }

    private fun source(): String {
        val state = model?.state
        return "instance=" + state?.instance +
            " episode=" + (state?.generatorState?.meta as? ResultEpisode)?.id +
            " provider=" + state?.generatorState?.response?.apiName +
            " playerId=" + lastPlayerId
    }

    private fun log(stage: String, detail: String = "") {
        Log.i(tag, "session=" + session + " seq=" + sequence.incrementAndGet() +
            " atMs=" + SystemClock.elapsedRealtime() + " stage=" + stage +
            " " + detail + " " + source())
    }

    private fun stack(stage: String, thread: Thread, elements: Array<StackTraceElement>) {
        if (elements.isEmpty()) return
        // Segment to avoid logcat's single-message truncation.
        val label = "session=" + session + " seq=" + sequence.incrementAndGet() +
            " stage=" + stage + " thread=" + thread.name + "/" + thread.id
        elements.take(48).map { it.toString() }.chunked(12).forEachIndexed { i, entries ->
            Log.w(tag, label + " part=" + (i + 1) + " frames=" + entries.joinToString(" <- "))
        }
    }

    private val lifecycle = object : FragmentManager.FragmentLifecycleCallbacks() {
        private fun relevant(f: Fragment): Boolean {
            val name = f.javaClass.name
            return f is GeneratorPlayer ||
                name.contains(".ui.result.") || name.contains(".ui.search.")
        }
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, state: Bundle?) {
            if (relevant(f)) log("fragment-view-created",
                "fragment=" + f.javaClass.simpleName +
                " viewId=" + System.identityHashCode(v))
            attach(f)
        }
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (relevant(f)) log("fragment-resumed",
                "fragment=" + f.javaClass.simpleName)
            attach(f)
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (relevant(f)) log("fragment-view-destroyed",
                "fragment=" + f.javaClass.simpleName)
            if (f === fragment) detach()
        }
    }

    fun start() {
        // CloseGuard reports *allocation* stack of leaked Closeables, not merely
        // the finalizer warning. Restore the pre-existing VM policy on stop.
        priorVmPolicy = StrictMode.getVmPolicy()
        runCatching {
            val policy = StrictMode.VmPolicy.Builder(priorVmPolicy!!)
                .detectLeakedClosableObjects().penaltyLog().build()
            StrictMode.setVmPolicy(policy)
            installedVmPolicy = policy
        }.onFailure {
            Log.w(tag, "strictmode-install-failed " + it.javaClass.simpleName)
        }
        monitoring = true
        ensureHttpInterceptor()
        worker = Thread(::watch, "Elevate-P0-P1-Watch").apply {
            isDaemon = true
            start()
        }
        main.post(heartbeat)
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::bindHost)
        log("diagnostic-start", "closeguard=" + (installedVmPolicy != null))
    }

    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(lifecycle)
        detach()
        host = null
        monitoring = false
        runCatching {
            val client = app.baseClient
            if (client.interceptors.any { it === httpInterceptor } ||
                client.eventListenerFactory === originEventFactory) {
                app.baseClient = client.newBuilder().apply {
                    interceptors().removeAll { it === httpInterceptor }
                    if (client.eventListenerFactory === originEventFactory)
                        parentEventFactory?.let(::eventListenerFactory)
                }.build()
            }
        }
        httpPending.clear()
        callOrigins.clear()
        worker?.interrupt()
        worker = null
        main.removeCallbacks(heartbeat)
        if (StrictMode.getVmPolicy() === installedVmPolicy) {
            priorVmPolicy?.let(StrictMode::setVmPolicy)
        }
        installedVmPolicy = null
        priorVmPolicy = null
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!monitoring) return
            val now = SystemClock.elapsedRealtime()
            lastHeartbeatAt = now
            if (now - lastHttpEnsureAt > 5_000L) {
                lastHttpEnsureAt = now
                ensureHttpInterceptor()
            }
            main.postDelayed(this, 40L)
        }
    }

    private fun watch() {
        val mainThread = Looper.getMainLooper().thread
        while (monitoring) {
            try {
                Thread.sleep(80L)
                val now = SystemClock.elapsedRealtime()
                val delay = now - lastHeartbeatAt
                if (delay >= 90L && now - lastStallAt >= 300L) {
                    lastStallAt = now
                    log("main-stall", "heartbeatDelayMs=" + delay +
                        " lastSubtitleMs=" + age(now, lastSubtitleEventAt) +
                        " lastLinksMs=" + age(now, lastLinksAt) +
                        " lastLoadingMs=" + age(now, lastLoadingAt) +
                        " lastPlayerMs=" + age(now, lastPlayerAt))
                    stack("main-stall-stack", mainThread, mainThread.stackTrace)
                }
                if ((fragment != null ||
                        (lastDetachAtMs > 0L && now - lastDetachAtMs < 45_000L)) &&
                    now - lastHotSampleAt >= 1250L) {
                    lastHotSampleAt = now
                    inspectHotThreads()
                }
                if (now - lastHeapAt >= 2000L) {
                    lastHeapAt = now
                    val memory = Runtime.getRuntime()
                    val used = memory.totalMemory() - memory.freeMemory()
                    val gcCount = Debug.getRuntimeStat("art.gc.gc-count")
                        ?.toLongOrNull() ?: -1L
                    val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated")
                        ?.toLongOrNull() ?: -1L
                    val allocDeltaMb = if (lastAllocatedBytes >= 0L &&
                        allocated >= lastAllocatedBytes)
                        (allocated - lastAllocatedBytes) / 1048576L else -1L
                    val gcDelta = if (lastGcCount >= 0L &&
                        gcCount >= lastGcCount) gcCount - lastGcCount else -1L
                    lastAllocatedBytes = allocated
                    lastGcCount = gcCount
                    val blockingGc = Debug.getRuntimeStat("art.gc.blocking-gc-count")
                        ?: "unknown"
                    sampleHttp(now)
                    log("heap", "javaUsedMB=" + used / 1048576L +
                        " javaCommittedMB=" + memory.totalMemory() / 1048576L +
                        " javaMaxMB=" + memory.maxMemory() / 1048576L +
                        " nativeMB=" + Debug.getNativeHeapAllocatedSize() / 1048576L +
                        " gcCount=" + gcCount +
                        " gcDelta=" + gcDelta +
                        " allocDeltaMB=" + allocDeltaMb +
                        " blockingGcCount=" + blockingGc +
                        " threadCount=" + Thread.activeCount())
                }
            } catch (_: InterruptedException) {
                break
            } catch (ex: Exception) {
                Log.w(tag, "sampler-error " + ex.javaClass.simpleName)
            }
        }
    }

    private fun age(now: Long, value: Long): Long = if (value > 0L) now - value else -1L

    private fun inspectHotThreads() {
        var webActive = 0
        var previewActive = 0
        var extractActive = 0
        for ((thread, frames) in Thread.getAllStackTraces()) {
            val relevant = frames.filter {
                val n = it.className
                n.contains("WebViewResolver") || n.contains("PreviewImgM3u8") ||
                    n.contains("WebViewExtractor") ||
                    n.contains("WebViewResolver") ||
                    n.contains("loadExtractor") || n.contains("PreviewGenerator")
            }
            if (relevant.isEmpty()) continue
            val isPreview = relevant.any { it.className.contains("Preview") }
            val isWeb = relevant.any { it.className.contains("WebView") }
            if (isPreview) previewActive++
            else if (isWeb) webActive++
            else extractActive++
            val identity = relevant.joinToString("|") { it.className + "." + it.methodName }
            if (isPreview && identity != lastPreviewSignature) {
                lastPreviewSignature = identity
                stack("preview-active-callstack", thread, frames)
            } else if (isWeb && identity != lastWebSignature) {
                lastWebSignature = identity
                stack("webview-active-callstack", thread, frames)
            }
        }
        val now = SystemClock.elapsedRealtime()
        val sinceDetach = age(now, lastDetachAtMs)
        // Capture the full lifecycle rather than stopping telemetry the moment
        // GeneratorPlayer is destroyed; native preview work may outlive it.
        if (lastPreviewThreads != previewActive || lastWebThreads != webActive ||
            (previewActive > 0 && sinceDetach in 0..45_000L)) {
            log("extractor-lifecycle", "webviewThreads=" + webActive +
                " previewThreads=" + previewActive +
                " otherExtractors=" + extractActive +
                " playerDetachedMsAgo=" + sinceDetach +
                " previewThreadsPrev=" + lastPreviewThreads +
                " webThreadsPrev=" + lastWebThreads)
        }
        lastPreviewThreads = previewActive
        lastWebThreads = webActive
    }

    private fun bindHost(activity: FragmentActivity) {
        if (host === activity) return
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(lifecycle)
        detach()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(lifecycle, true)
        fun scan(fm: FragmentManager) {
            for (f in fm.fragments) {
                if (f.view != null) attach(f)
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun attach(f: Fragment) {
        if (f !is GeneratorPlayer || !f.isAdded || f.view == null || f === fragment) return
        detach()
        fragment = f
        lastDetachAtMs = 0L
        model = ViewModelProvider(f)[PlayerGeneratorViewModel::class.java]
        session++
        lastSubtitleSet = emptySet()
        lastMediaKey = null
        lastPlayerId = 0
        layoutPasses = 0
        unchangedLayouts = 0
        layoutChanges = 0
        layoutCounts.clear()
        subtitleWindowEvents = 0
        subtitleWindowMax = 0
        subtitleWindowAdded = 0
        subtitleWindowRemoved = 0
        lastReportAt = SystemClock.elapsedRealtime()
        log("player-attach")
        val vm = model ?: return
        val subtitles = Observer<VideoLive<Set<SubtitleData>>> { update ->
            if (update == null) return@Observer
            val started = SystemClock.elapsedRealtimeNanos()
            val current = update.value
            val prior = lastSubtitleSet
            val added = (current - prior).size
            val removed = (prior - current).size
            lastSubtitleSet = current
            lastSubtitleEventAt = SystemClock.elapsedRealtime()
            subtitleWindowEvents++
            subtitleWindowMax = maxOf(subtitleWindowMax, current.size)
            subtitleWindowAdded += added
            subtitleWindowRemoved += removed
            // Sample only anomalously expensive observer executions.
            val observerUs = (SystemClock.elapsedRealtimeNanos() - started) / 1000L
            if (observerUs > 5000L)
                log("subtitle-observer-slow", "costUs=" + observerUs +
                    " size=" + current.size + " added=" + added + " removed=" + removed)
        }
        val links = Observer<VideoLive<Set<VideoLink>>> { update ->
            if (update != null) {
                lastLinksAt = SystemClock.elapsedRealtime()
                log("links-change", "eventInstance=" + update.instance +
                    " count=" + update.value.size)
            }
            observePlayer("links")
        }
        val loading = Observer<VideoLive<Resource<Unit>>> { update ->
            if (update != null) {
                lastLoadingAt = SystemClock.elapsedRealtime()
                val state = when (update.value) {
                    is Resource.Loading -> "Loading"
                    is Resource.Success -> "Success"
                    is Resource.Failure -> "Failure"
                    else -> "Other"
                }
                log("loading-change", "eventInstance=" + update.instance + " phase=" + state)
            }
            observePlayer("loading")
        }
        vm.currentSubtitles.observe(f.viewLifecycleOwner, subtitles)
        vm.currentLinks.observe(f.viewLifecycleOwner, links)
        vm.loadingLinks.observe(f.viewLifecycleOwner, loading)
        unsubscribe = {
            vm.currentSubtitles.removeObserver(subtitles)
            vm.currentLinks.removeObserver(links)
            vm.loadingLinks.removeObserver(loading)
        }
        val root = f.view ?: return
        val onDraw = ViewTreeObserver.OnPreDrawListener {
            observePlayer("draw")
            val now = SystemClock.elapsedRealtime()
            val frameGap = if (lastDrawAt != 0L) now - lastDrawAt else 0L
            if (frameGap >= 48L) {
                log("frame-gap", "gapMs=" + frameGap +
                    " layoutPasses=" + layoutPasses +
                    " viewLayoutChanges=" + layoutChanges +
                    " sameBounds=" + unchangedLayouts +
                    " sinceSubtitleMs=" + age(now, lastSubtitleEventAt) +
                    " sinceLinksMs=" + age(now, lastLinksAt) +
                    " sinceLoadingMs=" + age(now, lastLoadingAt))
            }
            if (now - lastReportAt >= 1000L) {
                if (layoutPasses > 1 || layoutChanges > 0 ||
                    subtitleWindowEvents > 0) log("layout-window",
                    "passes=" + layoutPasses + " changes=" + layoutChanges +
                    " sameBounds=" + unchangedLayouts +
                    " targets=" + layoutCounts.entries
                        .sortedByDescending { it.value }.take(6)
                        .joinToString(",") { it.key + ":" + it.value } +
                    " subtitleEvents=" + subtitleWindowEvents +
                    " subtitleMax=" + subtitleWindowMax +
                    " subtitleAdded=" + subtitleWindowAdded +
                    " subtitleRemoved=" + subtitleWindowRemoved +
                    " sinceSubtitleMs=" + age(now, lastSubtitleEventAt) +
                    " sincePlayerMs=" + age(now, lastPlayerAt))
                layoutPasses = 0
                layoutChanges = 0
                layoutCounts.clear()
                subtitleWindowEvents = 0
                subtitleWindowMax = 0
                subtitleWindowAdded = 0
                subtitleWindowRemoved = 0
                unchangedLayouts = 0
                lastReportAt = now
            }
            lastDrawAt = now
            true
        }
        root.viewTreeObserver.takeIf { it.isAlive }?.let { observer ->
            tree = observer
            draw = onDraw
            globalLayout = ViewTreeObserver.OnGlobalLayoutListener { layoutPasses++ }
            observer.addOnPreDrawListener(onDraw)
            observer.addOnGlobalLayoutListener(globalLayout!!)
        }
        val names = setOf("exo_content_frame", "extra_brightness_overlay",
            "start_panel", "end_panel", "text1", "player_pause_play",
            "skip_chapter_button", "sort_subtitles")
        // Traverse once per player view, including nested text1 views. No scan per frame.
        fun visit(view: View, depth: Int) {
            if (depth > 14 || viewObservers.size > 100) return
            val idName = if (view.id > 0) runCatching {
                view.resources.getResourceEntryName(view.id)
            }.getOrNull() else null
            if (idName != null && idName in names) {
                val listener = View.OnLayoutChangeListener { v, l, t, r, b, ol, ot, orr, ob ->
                    layoutChanges++
                    val same = l == ol && t == ot && r == orr && b == ob
                    if (same) unchangedLayouts++
                    layoutCounts[idName] = (layoutCounts[idName] ?: 0) + 1
                    // Animation normally changes width every 16 ms. Aggregating
                    // avoids hundreds of logcat writes on the UI thread.
                    if (!same && layoutChanges == 1) {
                        log("layout-target", "view=" + idName +
                            " viewId=" + System.identityHashCode(v) +
                            " from=" + (orr-ol) + "x" + (ob-ot) +
                            " to=" + (r-l) + "x" + (b-t) +
                            " unchanged=" + same +
                            " sinceSubtitleMs=" + age(SystemClock.elapsedRealtime(), lastSubtitleEventAt))
                    }
                }
                view.addOnLayoutChangeListener(listener)
                viewObservers[view] = listener
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
        observePlayer("initial")
    }

    private fun observePlayer(trigger: String) {
        val next = fragment?.playerView?.player
        val id = next?.let(System::identityHashCode) ?: 0
        if (id == lastPlayerId) return
        val now = SystemClock.elapsedRealtime()
        val uri = runCatching { next?.currentMediaItem?.localConfiguration?.uri?.toString() }.getOrNull()
        // Only compare equal URIs in memory; do not log URL, tokens or hash them per frame.
        val sameMedia = uri != null && uri == lastMediaKey
        log("player-replacement", "trigger=" + trigger +
            " oldId=" + lastPlayerId + " newId=" + id +
            " sameMediaUri=" + sameMedia +
            " sinceLinksMs=" + age(now, lastLinksAt) +
            " sinceLoadingMs=" + age(now, lastLoadingAt))
        lastPlayerId = id
        lastPlayerAt = now
        lastMediaKey = uri
    }

    private fun detach() {
        tree?.let {
            if (it.isAlive) {
                draw?.let(it::removeOnPreDrawListener)
                globalLayout?.let(it::removeOnGlobalLayoutListener)
            }
        }
        tree = null
        draw = null
        globalLayout = null
        viewObservers.forEach { (v, listener) -> v.removeOnLayoutChangeListener(listener) }
        viewObservers.clear()
        unsubscribe?.invoke()
        unsubscribe = null
        if (fragment != null) {
            lastDetachAtMs = SystemClock.elapsedRealtime()
            log("player-detach", "lastPlayerId=" + lastPlayerId +
                " lastLoadingMs=" + age(lastDetachAtMs, lastLoadingAt) +
                " lastLinksMs=" + age(lastDetachAtMs, lastLinksAt))
        }
        fragment = null
        model = null
        lastDrawAt = 0L
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) bindHost(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) {
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(lifecycle)
            detach()
            host = null
        }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
